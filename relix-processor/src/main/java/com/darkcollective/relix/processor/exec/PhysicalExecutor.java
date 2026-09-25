/*
 * Copyright 2026 Darkcollective, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.darkcollective.relix.processor.exec;

import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.processor.internal.ExecutionContext;
import com.darkcollective.relix.processor.Row;

import java.util.stream.Stream;

/**
 * Executes a {@link PhysicalNode} plan, producing a lazy {@link Stream}{@code <}{@link Row}{@code
 * >}.
 *
 * <p>Unlike a logical tree, the physical plan has already fixed every execution
 * decision: each node carries its output
 * {@link com.darkcollective.relix.symbol.Schema}, and every
 * {@link PhysicalNode.Join} states its algorithm (hash vs nested-loop), build
 * side, and equi-join key columns.  This executor therefore performs no cost
 * estimation, key extraction, or schema inference — it mechanically runs what the
 * {@link com.darkcollective.relix.plan.internal.Planner} chose.
 *
 * <p>Streaming unary operators chain onto the input stream; operators that need
 * random access or full buffering (sort, aggregation, the build side of a join,
 * set operations, division) materialise as required.
 *
 * <h2>Thread safety</h2>
 * <p>Stateless and therefore thread-safe; the streams it returns are not.
 */
public final class PhysicalExecutor {

    private static final System.Logger LOG = System.getLogger(PhysicalExecutor.class.getName());

    /** Child-execution seam handed to the operator-group executors so they can recurse. */
    private final ChildDispatch dispatch = this::execute;

    private final CoverExecutor cover = new CoverExecutor(dispatch);
    private final PivotUnpivotExecutor pivotUnpivot = new PivotUnpivotExecutor(dispatch);
    private final DownsampleExecutor downsample = new DownsampleExecutor(dispatch);
    private final JoinExecutor joins = new JoinExecutor(dispatch);
    private final LateralExecutor lateral = new LateralExecutor(dispatch);
    private final LeafExecutor leaf = new LeafExecutor(dispatch);
    private final RecursionExecutor recursion = new RecursionExecutor(dispatch);
    private final UnaryExecutor unary = new UnaryExecutor(dispatch);
    private final AggregateExecutor aggregates = new AggregateExecutor(dispatch);
    private final SolverExecutor solver = new SolverExecutor(dispatch);
    private final SetOpExecutor setOps = new SetOpExecutor(dispatch);
    private final SpoolExecutor spool = new SpoolExecutor(dispatch);
    private final WindowExecutor window = new WindowExecutor(dispatch);
    private final SessionizeExecutor sessionize = new SessionizeExecutor(dispatch);
    private final TreeExecutor tree = new TreeExecutor(dispatch);
    private final WhyExecutor why = new WhyExecutor(dispatch);

    /**
     * Executes {@code node} against {@code ctx}.  The stream should be consumed
     * once and closed after use.
     *
     * @param node the physical plan to run; must not be null
     * @param ctx  the shared execution context; must not be null
     * @return a lazy stream of result rows
     */
    public Stream<Row> execute(PhysicalNode node, ExecutionContext ctx) {
        EvalCtx eval = EvalCtx.from(ctx);
        try {
            // Closing the root is the end of the execution, and the only point at which a
            // shared sub-plan's read is known to be finished with: a spool nobody exhausted
            // and nobody took over — every consumer stopped early — is still holding one.
            // Guarded at the root, so every row the consumer pulls passes the check;
            // MaterializationBudget carries the same check into the operators that
            // drain an input without yielding, which the root cannot reach.
            return Cancellation.interruptible(execute(node, eval))
                    .onClose(eval.spools()::close);
        } catch (RuntimeException failed) {
            // A blocking operator runs *here*, during this call, rather than on the first
            // pull — so a failure in one happens before there is a root stream for anyone
            // to close, and the onClose above was never attached. A caller's
            // try-with-resources does not run for a resource whose initializer threw, so
            // without this nothing ever reaches what the plan had already opened.
            eval.spools().close();
            throw failed;
        }
    }

    /**
     * Runs one operator and charges each row it yields to the execution's work budget.
     *
     * <p>Every operator's output passes through here, since this is also the seam the
     * operator groups recurse through, so the budget sees every row that moves between
     * two operators. With no limit set the stream is returned as it is.
     */
    private Stream<Row> execute(PhysicalNode node, EvalCtx ctx) {
        return ctx.work().meter(run(node, ctx));
    }

    private Stream<Row> run(PhysicalNode node, EvalCtx ctx) {
        return switch (node) {
            case PhysicalNode.Scan s      -> leaf.executeScan(s, ctx);
            // ∅ — no rows, and nothing to run: the sub-plan the optimizer proved
            // unsatisfiable is not present in the physical plan at all.
            case PhysicalNode.Empty ignored     -> Stream.empty();
            case PhysicalNode.PushedScan s -> ctx.connector()
                    .openQuery(s.connectorType(), s.connection(), s.nativeQuery(), s.schema());
            case PhysicalNode.Spool sp    -> spool.executeSpool(sp, ctx);
            case PhysicalNode.Select s    -> execute(s.input(), ctx)
                    .filter(row -> ctx.predicateEval().evaluate(s.predicate(), row));
            case PhysicalNode.Project p   -> unary.executeProject(p, ctx);
            case PhysicalNode.Rename r    -> unary.executeRename(r, ctx);
            case PhysicalNode.Distinct d  -> unary.executeDistinct(d, ctx);
            case PhysicalNode.Unnest u    -> leaf.executeUnnest(u, ctx);
            case PhysicalNode.Closure c       -> recursion.executeClosure(c, ctx);
            case PhysicalNode.Cluster c       -> recursion.executeCluster(c, ctx);
            case PhysicalNode.Path p          -> recursion.executePath(p, ctx);
            case PhysicalNode.Trace t         -> recursion.executeTrace(t, ctx);
            case PhysicalNode.Fixpoint f      -> recursion.executeFixpoint(f, ctx);
            case PhysicalNode.RecursiveRef r  -> recursion.executeRecursiveRef(r, ctx);
            case PhysicalNode.Limit l     -> unary.executeLimit(l, ctx);
            case PhysicalNode.Sort s      -> aggregates.executeSort(s, ctx);
            case PhysicalNode.Aggregate a -> aggregates.executeAggregate(a, ctx);
            case PhysicalNode.Universal u -> aggregates.executeUniversal(u, ctx);
            case PhysicalNode.Solve s     -> solver.executeSolve(s, ctx);
            case PhysicalNode.Optimize o  -> solver.executeOptimize(o, ctx);
            case PhysicalNode.TopK t      -> solver.executeTopK(t, ctx);
            case PhysicalNode.Window w    -> window.executeWindow(w, ctx);
            case PhysicalNode.Sessionize s -> sessionize.executeSessionize(s, ctx);
            case PhysicalNode.BernoulliSample b  -> solver.executeBernoulli(b, ctx);
            case PhysicalNode.ReservoirSample r -> solver.executeReservoir(r, ctx);
            case PhysicalNode.Cover v     -> cover.executeCover(v, ctx);
            case PhysicalNode.ConstructiveCover v -> cover.executeConstructiveCover(v, ctx);
            case PhysicalNode.Downsample d -> downsample.executeDownsample(d, ctx);
            case PhysicalNode.Join j      -> joins.executeJoin(j, ctx);
            case PhysicalNode.AsOfJoin a  -> joins.executeAsOfJoin(a, ctx);
            case PhysicalNode.IntervalJoin j -> joins.executeIntervalJoin(j, ctx);
            case PhysicalNode.LateralJoin l -> lateral.executeLateral(l, ctx);
            case PhysicalNode.SetOp s     -> setOps.executeSetOp(s, ctx);
            case PhysicalNode.Division d  -> setOps.executeDivision(d, ctx);
            case PhysicalNode.Unpivot uv  -> pivotUnpivot.executeUnpivot(uv, ctx);
            case PhysicalNode.Pivot pv    -> pivotUnpivot.executePivot(pv, ctx);
            case PhysicalNode.Tree t      -> tree.executeTree(t, ctx);
            case PhysicalNode.Why w       -> why.executeWhy(w, ctx);
        };
    }

}

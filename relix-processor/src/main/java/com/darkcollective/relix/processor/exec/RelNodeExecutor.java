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

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.events.QueryEventListener;
import com.darkcollective.relix.plan.PlanEstimates;
import com.darkcollective.relix.cost.ObservedCardinalities;
import com.darkcollective.relix.plan.Planner;

import java.util.Objects;
import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.processor.ExecutionContext;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.eval.EvaluationException;

import java.util.stream.Stream;

/**
 * Executes a logical relational algebra {@link RelNode} tree.
 *
 * <p>Execution is two-stage: the tree is first {@link Planner planned} into a
 * {@link PhysicalNode} plan — which fixes each join's algorithm and build side and
 * resolves every node's output schema — and the plan is then run by a
 * {@link PhysicalExecutor}, yielding a lazy {@link Stream}{@code <}{@link Row}{@code >}.
 * This class is the thin adapter between the two; all operator logic lives in
 * {@link PhysicalExecutor}.
 *
 * <h2>Precondition</h2>
 * <p>The {@link ExecutionContext} must have been built from a fully-valid
 * {@link com.darkcollective.relix.semantic.SemanticModel}: its
 * {@link ExecutionContext#nodeSchemas()} must cover {@code node} (and any view
 * bodies it references), since the planner reads schemas from there.
 *
 * <h2>Thread safety</h2>
 * <p>Stateless and therefore thread-safe; the streams it returns are not.
 */
public final class RelNodeExecutor {

    /**
     * Row counts a previous run produced, preferred by the planner's cost model over the
     * ones it would estimate. Empty unless a caller supplies one — this adapter is
     * created per use, so an executor that was never told about a session's history
     * plans exactly as it did before.
     */
    private ObservedCardinalities observed = ObservedCardinalities.NONE;

    /**
     * Plans through a cost model that prefers what a previous run measured.
     *
     * <p>A wither rather than a constructor argument or a context component: an
     * {@code ExecutionContext} is the state of one execution, while a store of measured
     * counts belongs to whatever outlives the executions — a session, typically — and
     * putting it on the context would have made every caller of a ten-component record
     * name a thing it has no opinion about.
     *
     * @param observed the recorded counts; must not be null
     * @return this executor, for chaining
     */
    public RelNodeExecutor withObservedCardinalities(ObservedCardinalities observed) {
        this.observed = Objects.requireNonNull(observed, "observed");
        return this;
    }

    private final PhysicalExecutor physicalExecutor = new PhysicalExecutor();

    /**
     * Plans and executes {@code node}, returning a lazy stream of result rows.
     *
     * <p>The stream should be consumed exactly once and closed after use.
     *
     * <p>Planning reports its physical decisions to the context's own
     * {@link ExecutionContext#listener()} — the same listener the operators emit
     * their {@code EXECUTE} events to — so a context that is observing sees the
     * whole run, not only its execution half. A context that is not observing
     * carries {@link QueryEventListener#NONE} and nothing is emitted.
     *
     * @param node the logical relational algebra node to execute; must not be null
     * @param ctx  the shared execution context; must not be null
     * @return a lazy stream of rows; caller is responsible for closing
     * @throws EvaluationException if a data-level error occurs at runtime
     */
    public Stream<Row> execute(RelNode node, ExecutionContext ctx) {
        return execute(node, ctx, ctx.listener());
    }

    /**
     * As {@link #execute(RelNode, ExecutionContext)}, but reports the planner's
     * decisions to {@code planListener} rather than to the context's listener.
     *
     * <p>The two differ for a caller that plans the same tree twice and must not
     * report it twice: {@code QueryExecutor.traceExecute} has already collected
     * the {@code PLAN} events from a separate planning pass, and passes
     * {@link QueryEventListener#NONE} here so only the {@code EXECUTE} stage
     * reaches the feed a second time.
     *
     * @param node         the logical relational algebra node to execute; must not be null
     * @param ctx          the shared execution context; must not be null
     * @param planListener notified on each physical decision; must not be null
     * @return a lazy stream of rows; caller is responsible for closing
     * @throws EvaluationException if a data-level error occurs at runtime
     */
    public Stream<Row> execute(RelNode node, ExecutionContext ctx,
                               QueryEventListener planListener) {
        return physicalExecutor.execute(plan(node, ctx, planListener), ctx);
    }

    /**
     * Plans {@code node} into a {@link PhysicalNode} without executing it — the
     * planning half of {@link #execute}, exposed for {@code --explain}.
     *
     * @param node the logical relational algebra node to plan; must not be null
     * @param ctx  the shared execution context; must not be null
     * @return the physical plan
     */
    public PhysicalNode plan(RelNode node, ExecutionContext ctx) {
        return plan(node, ctx, QueryEventListener.NONE);
    }

    /**
     * Plans {@code node}, emitting a {@link com.darkcollective.relix.events.QueryEvent}
     * to {@code listener} for each physical decision — used by
     * {@link com.darkcollective.relix.processor.QueryExecutor#trace}.
     *
     * @param node     the logical node to plan; must not be null
     * @param ctx      the shared execution context; must not be null
     * @param listener notified on each physical decision; must not be null
     * @return the physical plan
     */
    public PhysicalNode plan(RelNode node, ExecutionContext ctx, QueryEventListener listener) {
        return planWithEstimates(node, ctx, listener).plan();
    }

    /**
     * A planned tree together with the cardinality estimates the planner computed for
     * it.
     *
     * <p>The estimates are a side table rather than a component of the nodes — see
     * {@link PlanEstimates} — so they have to travel alongside the plan rather than
     * inside it. This record is that pairing.
     *
     * @param plan      the planned physical tree
     * @param estimates the per-node estimated row counts
     */
    public record PlannedQuery(PhysicalNode plan, PlanEstimates estimates) {}

    /**
     * Plans {@code node} and returns the plan together with its cardinality estimates
     * — the form {@code --explain} and the playground bundle need, since a plan node
     * does not carry its own estimate.
     *
     * @param node     the logical node to plan; must not be null
     * @param ctx      the shared execution context; must not be null
     * @param listener notified on each physical decision; must not be null
     * @return the plan and its estimates
     */
    public PlannedQuery planWithEstimates(RelNode node, ExecutionContext ctx,
                                          QueryEventListener listener) {
        // The Planner runs the materialisation-safety check + build-side rule using
        // the context's per-leaf boundedness (ADR-0008).
        // The catalogue is the model's own (ADR-0026 S4/S6): it types a call inside an
        // inlined table-valued-function body, and it is where a function's backend
        // spelling comes from, so pushing YEAR(at) into a database needs it here.
        Planner planner = new Planner(ctx.symbolTable(), ctx.nodeSchemas(),
                ctx.statistics(), ctx.sources(), ctx.connections(), listener,
                ctx.boundedness(), ctx.functions())
                .withObservedCardinalities(observed)
                // The run's ambient state, so a call that is constant for the run —
                // NOW() — can be evaluated once and pushed as a value instead of costing
                // its operator the fold. It must be *this* context's, because the value
                // sent to the backend has to equal the one the unfolded half of the same
                // query computes; ExecutionContext pins the instant so it does.
                .withFunctionContext(ctx.functionContext());
        PhysicalNode plan = planner.plan(node);
        return new PlannedQuery(plan, planner.estimates());
    }
}

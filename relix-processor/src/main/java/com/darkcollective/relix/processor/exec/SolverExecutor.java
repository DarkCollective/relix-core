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

import com.darkcollective.relix.ast.AllocationSpec;
import com.darkcollective.relix.events.QueryEvent;
import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.processor.ArrayRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.eval.AllocationRow;
import com.darkcollective.relix.processor.eval.EquationSolver;
import com.darkcollective.relix.processor.eval.SubsetOptimizer;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.Schema;
import static com.darkcollective.relix.processor.exec.ExecSupport.keys;
import static com.darkcollective.relix.processor.exec.ExecSupport.rowComparator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.SequencedMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Executes the per-group solver/sampling operators — SOLVE goal-seek, OPTIMIZE, TOP-k, and
 * reservoir sampling.
 *
 * <p>Holds a {@link ChildDispatch} to run input sub-plans.
 */
final class SolverExecutor {

    private static final System.Logger LOG = System.getLogger(SolverExecutor.class.getName());

    private final ChildDispatch dispatch;

    SolverExecutor(ChildDispatch dispatch) {
        this.dispatch = dispatch;
    }

    /**
     * Goal-seek: for each row, fills the single NULL participating column of the
     * equation {@code left = right} by inverting the arithmetic.  Streaming and
     * per-row — rows without exactly one NULL participating column pass through
     * unchanged.
     */
    Stream<Row> executeSolve(PhysicalNode.Solve node, EvalCtx ctx) {
        EquationSolver solver = new EquationSolver(ctx.operandEval());
        return dispatch.execute(node.input(), ctx)
                .map(row -> solver.solve(node.left(), node.right(), row));
    }

    /**
     * Declarative optimisation: partitions the input by the grouping keys and
     * solves per group via {@link SubsetOptimizer}.
     *
     * <p>MIP mode (default): emits chosen rows unchanged; a group the solver
     * <em>proves</em> infeasible is logged and skipped.  LP mode
     * ({@code OPTIMIZE ALLOCATE}): emits every row with its solver-assigned
     * allocation value appended as the last column.
     *
     * <p>A search that stops without deciding is not a skipped group — it raises,
     * from {@link SubsetOptimizer}, so a failure cannot be mistaken for an answer.
     */
    Stream<Row> executeOptimize(PhysicalNode.Optimize node, EvalCtx ctx) {
        Schema outputSchema = node.schema();
        Schema inputSchema = node.input().schema();
        List<String> keys = node.groupingKeys();

        IndexedRelation indexed;
        try (Stream<Row> input = dispatch.buffering(node.input(), ctx, node)) {
            indexed = IndexedRelation.build(inputSchema, input,
                    row -> keys.stream().map(row::get).toList());
        }

        SubsetOptimizer optimizer = new SubsetOptimizer(ctx.operandEval());
        List<Row> outputRows = new ArrayList<>();

        if (node.allocation().isPresent()) {
            // LP mode: every row is returned with its allocation value appended.
            AllocationSpec spec = node.allocation().get();
            for (var entry : indexed.groups().entrySet()) {
                String groupDesc = describeGroup(keys, entry.getKey());
                Optional<List<AllocationRow>> result = optimizer.allocate(
                        node.sense(), node.objective(), node.constraints(),
                        spec.lo(), spec.hi(), entry.getValue(), groupDesc);
                if (result.isEmpty()) {
                    LOG.log(System.Logger.Level.WARNING,
                            () -> "OPTIMIZE ALLOCATE: skipping infeasible group " + groupDesc);
                    ctx.listener().onEvent(QueryEvent.of(QueryEvent.Stage.EXECUTE, "OPTIMIZE",
                            "skipped infeasible OPTIMIZE ALLOCATE group " + groupDesc));
                    continue;
                }
                for (AllocationRow ar : result.get()) {
                    List<Value> vals = new ArrayList<>(ar.row().width() + 1);
                    for (int i = 0; i < ar.row().width(); i++) vals.add(ar.row().get(i));
                    vals.add(new NumberValue(BigDecimal.valueOf(ar.allocation())));
                    outputRows.add(ArrayRow.of(outputSchema, vals));
                }
            }
        } else {
            // MIP mode: emit chosen rows verbatim.
            for (var entry : indexed.groups().entrySet()) {
                String groupDesc = describeGroup(keys, entry.getKey());
                Optional<List<Row>> chosen = optimizer.choose(
                        node.sense(), node.objective(), node.constraints(),
                        entry.getValue(), groupDesc);
                if (chosen.isEmpty()) {
                    LOG.log(System.Logger.Level.WARNING,
                            () -> "OPTIMIZE: skipping infeasible group " + groupDesc);
                    ctx.listener().onEvent(QueryEvent.of(QueryEvent.Stage.EXECUTE, "OPTIMIZE",
                            "skipped infeasible OPTIMIZE group " + groupDesc));
                    continue;
                }
                outputRows.addAll(chosen.get());
            }
        }

        return BagRelation.of(outputSchema, outputRows).stream();
    }

    /**
     * Renders a group key as a readable {@code key=value, …} description for the
     * skipped-group log line and {@link QueryEvent.Stage#EXECUTE} event.  When
     * there are no grouping keys (whole-relation OPTIMIZE) the single group is
     * described as {@code (all rows)}.
     */
    private static String describeGroup(List<String> keys, Object groupKey) {
        if (keys.isEmpty()) {
            return "(all rows)";
        }
        List<?> values = (groupKey instanceof List<?> l) ? l : List.of(groupKey);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(", ");
            Object v = i < values.size() ? values.get(i) : "?";
            sb.append(keys.get(i)).append('=')
              .append(v instanceof Value val ? val.asDisplayString() : String.valueOf(v));
        }
        return sb.toString();
    }

    /**
     * Top-k per group: partitions the input by the grouping keys and emits the
     * {@code count} rows of each group that follow {@code offset} in sort-spec order.
     * Output rows are the full input rows (the schema is unchanged).
     *
     * <p><b>Each group holds {@code offset + count} rows, not the group.</b> A group's
     * rows are ranked as they arrive by a {@link TopRows} buffer that keeps only the
     * best that many, which is what {@code LIM-003} — the {@code λ∘τ → TOP} fusion —
     * promises the operator does with a bounded request, and what the partition-by form
     * means in its own right. The previous shape indexed every row of the input and
     * sorted each group whole, so a {@code TOP 1} over a million rows held a million and
     * compared them {@code n log n} times to discard all but one; it now holds one per
     * group and compares {@code n log k}, with a row worse than the worst already kept
     * turned away on a single comparison.
     *
     * <p>The input is still read to its end — the last row may be the largest — so this
     * is a claim about memory and comparisons rather than about rows pulled. What that
     * changes for the row cap is which buffer it counts: the operator is metered on the
     * rows it <em>holds</em> ({@link ChildDispatch#holding}), since its buffer grows with
     * the number of groups and not with the size of its input.
     */
    Stream<Row> executeTopK(PhysicalNode.TopK node, EvalCtx ctx) {
        List<String> groupAttrs = node.groupingAttributes();
        Comparator<Row> comparator = node.sortSpecs().isEmpty()
                ? null                                     // nothing to rank by: first come, first kept
                : rowComparator(node.sortSpecs(), ctx.operandEval());
        long offset = node.offset().orElse(0L);
        int bound = retained(offset, node.count());

        SequencedMap<List<Value>, TopRows> groups = new LinkedHashMap<>();
        long[] held = {0};
        try (Stream<Row> input = dispatch.holding(node.input(), ctx, node, () -> held[0])) {
            input.forEach(row -> {
                List<Value> key = groupAttrs.stream().map(row::get).toList();
                if (groups.computeIfAbsent(key, _ -> new TopRows(bound, comparator)).offer(row)) {
                    held[0]++;
                }
            });
        }

        List<Row> output = new ArrayList<>();
        for (TopRows group : groups.values()) {
            group.ordered().stream().skip(offset).forEach(output::add);
        }
        return BagRelation.of(node.schema(), output).stream();
    }

    /**
     * The rows one group has to hold to answer {@code offset} then {@code count} — both
     * of them, since the offset is skipped from the <em>ranked</em> rows rather than from
     * the input.
     *
     * <p>Saturating rather than exact: the sum of two longs can overflow, and the buffer
     * needs an {@code int}. Clamping each side before adding keeps the sum in range
     * without a branch, and a bound past the largest list the JVM can hold is the same
     * bound as none.
     */
    private static int retained(long offset, long count) {
        return (int) Math.min(
                Math.min(offset, Integer.MAX_VALUE) + Math.min(count, Integer.MAX_VALUE),
                Integer.MAX_VALUE);
    }

    /**
     * The largest reservoir that can be asked for. A reservoir is held in one list and
     * indexed by {@code int}, so this is what a relation can hold rather than a policy —
     * and refusing above it is what keeps {@code (int) j} exact.
     */
    private static final long MAX_RESERVOIR_ROWS = Integer.MAX_VALUE;

    /**
     * Bernoulli sampling: keeps each row of {@code input} independently with
     * probability {@code probability}.  When a seed is present the draw is
     * deterministic; otherwise a fresh {@link ThreadLocalRandom} is used.
     */
    Stream<Row> executeBernoulli(PhysicalNode.BernoulliSample node, EvalCtx ctx) {
        double p = node.probability();
        Random rnd = node.seed().<Random>map(Random::new).orElseGet(ThreadLocalRandom::current);
        return dispatch.execute(node.input(), ctx).filter(_ -> rnd.nextDouble() < p);
    }

    /**
     * Reservoir (fixed-count) sampling: keeps exactly {@code count} rows chosen
     * uniformly at random without replacement, via Vitter's Algorithm R — a single
     * streaming pass that holds at most {@code count} rows.  The first {@code count}
     * rows seed the reservoir; each subsequent row (the {@code i}-th, zero-based)
     * replaces a uniformly chosen reservoir slot with probability
     * {@code count / (i + 1)}.  When the input has fewer than {@code count} rows they
     * are all kept.  When a seed is present the sampling is deterministic.
     */
    Stream<Row> executeReservoir(PhysicalNode.ReservoirSample node, EvalCtx ctx) {
        long count = node.count();
        if (count == 0) {
            return BagRelation.of(node.schema(), List.of()).stream();
        }
        if (count > MAX_RESERVOIR_ROWS) {
            throw new EvaluationException(
                    "RESERVOIR " + count + " asks for more rows than one relation can hold; "
                    + "the most is " + MAX_RESERVOIR_ROWS);
        }
        List<Row> reservoir = new ArrayList<>();
        Random rnd = node.seed().<Random>map(Random::new).orElseGet(ThreadLocalRandom::current);
        long seen = 0;
        // Metered on what it holds rather than what it reads, the shape TOP uses above:
        // the buffer grows with `count` and not with the input, so metering the input
        // would refuse a small sample of a large table and pass a large sample of a small
        // one. `count` comes from the query, so "bounded by construction" — which is what
        // this said while it was unmetered — was only ever a bound the query chose.
        try (Stream<Row> input = dispatch.holding(node.input(), ctx, node, reservoir::size)) {
            Iterator<Row> it = input.iterator();
            while (it.hasNext()) {
                Row row = it.next();
                if (seen < count) {
                    reservoir.add(row);
                } else {
                    long j = nextLong(rnd, seen + 1);   // uniform in [0, seen]
                    if (j < count) {
                        // j < count ≤ MAX_RESERVOIR_ROWS, so the narrowing is exact. It was
                        // not before that bound existed: a count above it made this index
                        // negative rather than refusing the query.
                        reservoir.set((int) j, row);
                    }
                }
                seen++;
            }
        }
        return BagRelation.of(node.schema(), reservoir).stream();
    }

    /** Returns a uniformly distributed {@code long} in {@code [0, bound)}. */
    private static long nextLong(Random rnd, long bound) {
        if (rnd instanceof ThreadLocalRandom tlr) {
            return tlr.nextLong(bound);
        }
        // For seeded Random: rejection-sample to avoid modulo bias.
        long bits, val;
        do {
            bits = rnd.nextLong() >>> 1;
            val  = bits % bound;
        } while (bits - val + (bound - 1) < 0);
        return val;
    }
}

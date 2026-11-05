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

import com.darkcollective.relix.processor.eval.ValueComparator;
import com.darkcollective.relix.ast.CollateOperand;
import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.AggregationNode;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.SortDirection;
import com.darkcollective.relix.ast.SortSpecification;
import com.darkcollective.relix.function.Accumulator;
import com.darkcollective.relix.function.AggregateSignature;
import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.processor.internal.ArrayRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.processor.eval.OperandEvaluator;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.Type;
import static com.darkcollective.relix.processor.exec.ExecSupport.keys;
import static com.darkcollective.relix.processor.exec.ExecSupport.rowComparator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Executes the grouping/ordering operators — sort, (streaming) aggregation, and universal
 * quantification.
 *
 * <p>Holds a {@link ChildDispatch} to run input sub-plans.
 */
final class AggregateExecutor {

    private final ChildDispatch dispatch;

    AggregateExecutor(ChildDispatch dispatch) {
        this.dispatch = dispatch;
    }

    /** Wraps an ordered iterator as a sequential, lazily-consumed stream. */
    private static Stream<Row> lazyStream(Iterator<Row> it) {
        return java.util.stream.StreamSupport.stream(
                java.util.Spliterators.spliteratorUnknownSize(
                        it, java.util.Spliterator.ORDERED), false);
    }

    Stream<Row> executeSort(PhysicalNode.Sort node, EvalCtx ctx) {
        List<Row> rows;
        try (Stream<Row> input = dispatch.buffering(node.input(), ctx, node)) {
            rows = new ArrayList<>(input.toList());
        }
        rows.sort(rowComparator(node.sortSpecs(), ctx.operandEval()));
        return SortedBagRelation.of(node.schema(), rows).stream();
    }

    Stream<Row> executeAggregate(PhysicalNode.Aggregate node, EvalCtx ctx) {
        if (node.streaming()) {
            return executeStreamingAggregate(node, ctx);
        }
        if (!node.groupingSets().isEmpty()) {
            return executeGroupingSets(node, ctx);
        }
        Schema outputSchema = node.schema();
        Schema inputSchema = node.input().schema();
        List<GroupingKey> groupKeys = node.groupingKeys();
        OperandEvaluator eval = ctx.operandEval();

        if (node.aggregates().stream().allMatch(AggregateExecutor::constantState)) {
            return executeFolding(node, ctx);
        }

        IndexedRelation indexed;
        try (Stream<Row> input = dispatch.buffering(node.input(), ctx, node)) {
            indexed = IndexedRelation.build(inputSchema, input,
                    row -> ExecSupport.canonicalKey(bucketKey(row, groupKeys, eval),
                            ExecSupport.keyTypes(outputSchema, groupKeys.size())));
        }

        Map<List<Value>, List<Row>> groups = indexed.groups();
        if (groupKeys.isEmpty() && groups.isEmpty()) {
            groups = Map.of(List.of(), List.of());   // empty global aggregate → one group
        }

        List<Row> outputRows = new ArrayList<>(groups.size());
        for (var entry : groups.entrySet()) {
            outputRows.add(aggregateRow(outputSchema, groupKeys, node.aggregates(),
                    entry.getValue(), ctx.operandEval()));
        }
        return BagRelation.of(outputSchema, outputRows).stream();
    }

    /**
     * Whether {@code agg} keeps a fixed amount of state per group however many rows it
     * reads: a count, a sum, an extremum, a running variance, a boolean fold, the best
     * row so far. Such an aggregate needs no row of its group after it has folded it in.
     * One that is {@code DISTINCT} remembers every value it has seen, and one that reads
     * its group in an order ({@code ORDER BY}, or {@code MODE}, which breaks ties by
     * value) needs the whole group, as do {@code MEDIAN}, {@code PERCENTILE_*},
     * {@code COLLECT} and {@code STRING_AGG}, whose results are the values themselves.
     */
    private static boolean constantState(AggregateFunction agg) {
        if (agg.distinct() || !agg.orderBy().isEmpty()) {
            return false;
        }
        return switch (agg.operator()) {
            case SUM, AVG, COUNT, MIN, MAX, ARGMAX, ARGMIN, STDDEV, STDDEV_SAMP, STDDEV_POP,
                 VARIANCE, VAR_SAMP, VAR_POP, BOOL_AND, BOOL_OR, GROUPING -> true;
            default -> false;
        };
    }

    /** One group's running state in {@link #executeFolding}. */
    private static final class GroupState {
        final List<Value> shown;
        final Reduction[] reductions;

        GroupState(List<Value> shown, Reduction[] reductions) {
            this.shown = shown;
            this.reductions = reductions;
        }
    }

    /**
     * Hash aggregation that folds each row into its group's accumulators as it arrives and
     * keeps no row, so the memory held is one accumulator per group per aggregate rather
     * than the input. {@code γ COUNT(*) (Range(1, 5000000))} holds one counter.
     *
     * <p>Rows are folded in input order and the groups come out in order of first
     * appearance, exactly as the buffering path does, and the key a row shows is chosen by
     * the same rule as {@link #shownKey}. The groups themselves are the one thing held,
     * and are what the materialisation cap and its event count.
     */
    private Stream<Row> executeFolding(PhysicalNode.Aggregate node, EvalCtx ctx) {
        Schema outputSchema = node.schema();
        List<GroupingKey> groupKeys = node.groupingKeys();
        List<AggregateFunction> aggregates = node.aggregates();
        OperandEvaluator eval = ctx.operandEval();
        List<Type> keyTypes = ExecSupport.keyTypes(outputSchema, groupKeys.size());

        Map<List<Value>, GroupState> groups = new java.util.LinkedHashMap<>();
        // Metered like TOP: what is held is the groups, not the rows that reach them, so the
        // cap and the MATERIALIZE event both answer in groups. forEach, not an iterator,
        // so the poll after each row sees the group that row created.
        try (Stream<Row> input = MaterializationBudget.holding(node,
                dispatch.execute(node.input(), ctx), ctx, groups::size)) {
            input.forEach(row -> {
                List<Value> key = ExecSupport.canonicalKey(bucketKey(row, groupKeys, eval), keyTypes);
                GroupState state = groups.get(key);
                if (state == null) {
                    state = new GroupState(new ArrayList<>(groupKey(row, groupKeys, eval)),
                            reductions(aggregates, eval));
                    groups.put(key, state);
                }
                for (int i = 0; i < groupKeys.size(); i++) {
                    Operand expression = groupKeys.get(i).expression();
                    if (expression instanceof CollateOperand) {
                        Value value = eval.evaluate(expression, row);
                        if (!value.isNull()
                                && ValueComparator.compareNonNull(value, state.shown.get(i)) < 0) {
                            state.shown.set(i, value);
                        }
                    }
                }
                for (Reduction reduction : state.reductions) {
                    if (reduction != null) {
                        reduction.accept(row);
                    }
                }
            });
        }
        if (groupKeys.isEmpty() && groups.isEmpty()) {
            groups.put(List.of(), new GroupState(List.of(), reductions(aggregates, eval)));
        }

        List<Row> outputRows = new ArrayList<>(groups.size());
        for (GroupState state : groups.values()) {
            List<Value> output = new ArrayList<>(outputSchema.width());
            output.addAll(state.shown);
            for (int i = 0; i < aggregates.size(); i++) {
                AggregateFunction agg = aggregates.get(i);
                output.add(agg.operator() == AggregateOperator.GROUPING
                        ? groupingValue(agg, groupKeys, allKeys(groupKeys))
                        : state.reductions[i].finish());
            }
            outputRows.add(ArrayRow.of(outputSchema, output));
        }
        return BagRelation.of(outputSchema, outputRows).stream();
    }

    private static Reduction[] reductions(List<AggregateFunction> aggregates, OperandEvaluator eval) {
        Reduction[] reductions = new Reduction[aggregates.size()];
        for (int i = 0; i < reductions.length; i++) {
            AggregateFunction agg = aggregates.get(i);
            reductions[i] = agg.operator() == AggregateOperator.GROUPING ? null : new Reduction(agg, eval);
        }
        return reductions;
    }

    /**
     * Streaming aggregation (ADR-0009, Phase C3): the planner has proven the input
     * delivers an ordering grouping the rows by the grouping keys, so every group is
     * contiguous.  This makes a single lazy linear pass, accumulating one group at a
     * time and emitting its aggregated row when the grouping key changes — no full
     * hash index, and the output streams.  Only chosen for keyed aggregates (a scalar
     * aggregate must read the whole input and never streams), so an empty input yields
     * no rows, matching the hash variant's grouped-empty behaviour.
     */
    private Stream<Row> executeStreamingAggregate(PhysicalNode.Aggregate node, EvalCtx ctx) {
        Schema outputSchema = node.schema();
        List<GroupingKey> groupKeys = node.groupingKeys();
        List<AggregateFunction> aggregates = node.aggregates();
        OperandEvaluator eval = ctx.operandEval();

        List<Type> keyTypes = ExecSupport.keyTypes(outputSchema, groupKeys.size());

        Stream<Row> input = dispatch.execute(node.input(), ctx);
        Iterator<Row> src = input.iterator();
        Iterator<Row> grouped = new Iterator<>() {
            private Row pending = src.hasNext() ? src.next() : null;

            @Override public boolean hasNext() {
                return pending != null;
            }

            @Override public Row next() {
                if (pending == null) {
                    throw new java.util.NoSuchElementException();
                }
                List<Value> key = ExecSupport.canonicalKey(
                        bucketKey(pending, groupKeys, eval), keyTypes);
                List<Row> groupRows = new ArrayList<>();
                groupRows.add(pending);
                pending = null;
                while (src.hasNext()) {
                    Row row = src.next();
                    if (key.equals(ExecSupport.canonicalKey(
                            bucketKey(row, groupKeys, eval), keyTypes))) {
                        groupRows.add(row);
                    } else {
                        pending = row;   // first row of the next group; held for the next call
                        break;
                    }
                }
                return aggregateRow(outputSchema, groupKeys, aggregates, groupRows, eval);
            }
        };
        return lazyStream(grouped).onClose(input::close);
    }

    /**
     * Aggregation by several grouping sets — {@code ROLLUP}, {@code CUBE},
     * {@code GROUPING SETS}. The input is read once and each row's keys evaluated once;
     * then every set groups those rows by its own keys, and its rows carry NULL for each
     * key it leaves out. An empty set is a total over every row, and like an ungrouped
     * aggregate gives its row over an empty input too. The rows come set by set, in the
     * order the sets are listed.
     */
    private Stream<Row> executeGroupingSets(PhysicalNode.Aggregate node, EvalCtx ctx) {
        Schema outputSchema = node.schema();
        List<GroupingKey> groupKeys = node.groupingKeys();
        OperandEvaluator eval = ctx.operandEval();
        List<Type> keyTypes = ExecSupport.keyTypes(outputSchema, groupKeys.size());

        List<Row> rows;
        try (Stream<Row> input = dispatch.buffering(node.input(), ctx, node)) {
            rows = input.toList();
        }
        List<List<Value>> canonical = new ArrayList<>(rows.size());
        for (Row row : rows) {
            canonical.add(ExecSupport.canonicalKey(bucketKey(row, groupKeys, eval), keyTypes));
        }

        List<Row> outputRows = new ArrayList<>();
        for (List<Integer> set : node.groupingSets()) {
            Map<List<Value>, List<Integer>> groups = new java.util.LinkedHashMap<>();
            for (int r = 0; r < rows.size(); r++) {
                List<Value> key = new ArrayList<>(set.size());
                for (int index : set) {
                    key.add(canonical.get(r).get(index));
                }
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
            }
            if (set.isEmpty() && groups.isEmpty()) {
                groups.put(List.of(), List.of());   // the grand total over no rows
            }
            for (List<Integer> members : groups.values()) {
                List<Row> groupRows = members.stream().map(rows::get).toList();
                List<Value> output = new ArrayList<>(outputSchema.width());
                List<Value> shown = shownKey(groupRows, groupKeys, eval);
                for (int i = 0; i < groupKeys.size(); i++) {
                    // As in aggregateRow, the key a row shows is the one shownKey picks.
                    output.add(set.contains(i) ? shown.get(i) : NullValue.INSTANCE);
                }
                for (AggregateFunction agg : node.aggregates()) {
                    output.add(agg.operator() == AggregateOperator.GROUPING
                            ? groupingValue(agg, groupKeys, set)
                            : computeAggregate(agg, groupRows, eval));
                }
                outputRows.add(ArrayRow.of(outputSchema, output));
            }
        }
        return BagRelation.of(outputSchema, outputRows).stream();
    }

    /**
     * The value of {@code GROUPING(k, …)} in a row of grouping set {@code set}: one bit
     * per named key, the last the lowest, set where the row has rolled the key up.
     */
    private static Value groupingValue(AggregateFunction agg, List<GroupingKey> groupKeys, List<Integer> set) {
        long bits = 0;
        for (Operand key : agg.groupingArguments()) {
            // Analysis has checked that every argument names a key.
            int index = AggregationNode.groupingIndex(groupKeys, key).orElseThrow();
            bits = bits << 1 | (set.contains(index) ? 0 : 1);
        }
        return new NumberValue(java.math.BigDecimal.valueOf(bits));
    }

    /**
     * The grouping-key tuple of {@code row}: each grouping key's expression
     * evaluated against the row (a bare column reduces to {@code row.get(name)},
     * a derived key such as {@code YEAR(ts)} to the computed value).
     */
    private static List<Value> groupKey(Row row, List<GroupingKey> groupKeys, OperandEvaluator eval) {
        return groupKeys.stream().map(k -> eval.evaluate(k.expression(), row)).toList();
    }

    /**
     * The tuple {@code row} is grouped by: its {@link #groupKey}, with a key written under
     * a collation replaced by the collation's key, so that {@code name COLLATE NOCASE}
     * puts {@code 'Ada'} and {@code 'ADA'} in one group.
     */
    private static List<Value> bucketKey(Row row, List<GroupingKey> groupKeys, OperandEvaluator eval) {
        return groupKeys.stream()
                .map(k -> ExecSupport.bucketValue(k.expression(), eval.evaluate(k.expression(), row)))
                .toList();
    }

    /**
     * The key a group's output row shows. Any row of the group would do for a key with
     * no collation, they being the rows it could not tell apart, and the first is the one
     * a stable sort would have put there. A collated key's rows do differ, {@code 'Ada'}
     * and {@code 'ADA'}, so it shows the group's least by code point: what a database
     * answers for {@code GROUP BY LOWER(name)} with {@code MIN(name)}, whichever order
     * the rows arrived in.
     */
    private static List<Value> shownKey(List<Row> groupRows, List<GroupingKey> groupKeys, OperandEvaluator eval) {
        if (groupRows.isEmpty()) {
            return List.of();
        }
        List<Value> shown = new ArrayList<>(groupKey(groupRows.getFirst(), groupKeys, eval));
        for (int i = 0; i < groupKeys.size(); i++) {
            Operand expression = groupKeys.get(i).expression();
            if (!(expression instanceof CollateOperand)) {
                continue;
            }
            for (Row row : groupRows) {
                Value value = eval.evaluate(expression, row);
                // A group's rows are all NULL or none is, NULL keying only with NULL.
                if (!value.isNull() && ValueComparator.compareNonNull(value, shown.get(i)) < 0) {
                    shown.set(i, value);
                }
            }
        }
        return shown;
    }

    /**
     * Builds one aggregated output row: the group key tuple followed by each
     * aggregate computed over {@code groupRows}.
     */
    private static Row aggregateRow(Schema outputSchema, List<GroupingKey> groupKeys,
                                    List<AggregateFunction> aggregates, List<Row> groupRows,
                                    OperandEvaluator eval) {
        List<Value> output = new ArrayList<>(outputSchema.width());
        // The emitted key comes from a row of the group rather than from the map key,
        // because the map key is canonical (ExecSupport.canonicalKey) and a key is not an
        // output: grouping a STRING column of dates must still return the strings. Any
        // row of the group would do — they are the rows the key could not tell apart —
        // and the first is the one a stable sort would have put there. The empty list is
        // the scalar aggregate over no rows, which has no key to emit.
        output.addAll(shownKey(groupRows, groupKeys, eval));
        for (AggregateFunction agg : aggregates) {
            // An ordinary grouping rolls no key up, so its GROUPING is 0.
            output.add(agg.operator() == AggregateOperator.GROUPING
                    ? groupingValue(agg, groupKeys, allKeys(groupKeys))
                    : computeAggregate(agg, groupRows, eval));
        }
        return ArrayRow.of(outputSchema, output);
    }

    /** The indexes of every key: the one grouping set of an ordinary grouping. */
    private static List<Integer> allKeys(List<GroupingKey> groupKeys) {
        return java.util.stream.IntStream.range(0, groupKeys.size()).boxed().toList();
    }

    /**
     * Group-wise universal quantification (∀): groups the input by the grouping
     * keys and emits the key tuple of each group in which <em>every</em> row
     * satisfies the predicate.  Strict NULL semantics — a row whose predicate is
     * UNKNOWN is treated as not-satisfied (same as a selection filter), so it
     * disqualifies its group.
     */
    Stream<Row> executeUniversal(PhysicalNode.Universal node, EvalCtx ctx) {
        Schema outputSchema = node.schema();
        Schema inputSchema = node.input().schema();
        List<String> groupAttrs = node.groupingAttributes();

        IndexedRelation indexed;
        try (Stream<Row> input = dispatch.buffering(node.input(), ctx, node)) {
            indexed = IndexedRelation.build(inputSchema, input,
                    row -> ExecSupport.canonicalKey(groupAttrs.stream().map(row::get).toList(),
                            ExecSupport.namedTypes(inputSchema, groupAttrs)));
        }

        List<Row> outputRows = new ArrayList<>();
        for (var entry : indexed.groups().entrySet()) {
            boolean allSatisfy = entry.getValue().stream()
                    .allMatch(row -> ctx.predicateEval().evaluate(node.predicate(), row));
            if (allSatisfy) {
                // As in aggregateRow: the key groups, the group's own row is emitted.
                Row representative = entry.getValue().getFirst();
                outputRows.add(ArrayRow.of(outputSchema,
                        groupAttrs.stream().map(representative::get)
                                .collect(java.util.stream.Collectors.toCollection(ArrayList::new))));
            }
        }

        // No-key whole-relation ∀ over an empty input: there is no group to emit a
        // key for, but "every row satisfies P" is vacuously true → the empty tuple
        // (DEE), not no tuple (DUM).  The keyed form correctly emits nothing here.
        if (groupAttrs.isEmpty() && indexed.groups().isEmpty()) {
            outputRows.add(ArrayRow.of(outputSchema, new ArrayList<>()));
        }
        return BagRelation.of(outputSchema, outputRows).stream();
    }

    /**
     * Computes one aggregate over a group's rows, through the accumulator the function
     * library supplies.
     *
     * <p>Nothing here knows what {@code SUM} does. The name resolves to an
     * {@code AggregateFunction} in the catalogue the query was analysed against, the
     * rows are fed to a fresh {@link Accumulator} in input order, and the accumulator is
     * asked for the group's value. An aggregate a third party installed is reduced by
     * exactly this loop.
     *
     * <h2>NULL handling</h2>
     * <p>The rule is read off the signature rather than restated per aggregate: an
     * aggregate that {@linkplain AggregateSignature#skipsNulls() skips NULLs} never sees
     * a row with a NULL argument, which is what makes {@code COUNT(expr)} count values
     * rather than rows and {@code SUM}/{@code AVG}/{@code MIN}/{@code MAX} yield NULL
     * over a group with nothing to reduce. {@code COLLECT} declares otherwise and keeps
     * them, matching {@code array_agg}/{@code $push}; {@code ARGMAX}/{@code ARGMIN}
     * declare otherwise and apply their own rule to the ranking argument. This is the
     * same behaviour a γ pushed to a SQL backend produces, which is why it is stated
     * once.
     */
    static Value computeAggregate(AggregateFunction agg, List<Row> rows, OperandEvaluator eval) {
        Reduction reduction = new Reduction(agg, eval);
        // An ordered aggregate — STRING_AGG(x, ', ' ORDER BY y) — reads its group in that order.
        // MODE breaks a tie by that order, and with none given reads its values ascending,
        // so the smallest of the most frequent wins (#296).
        List<SortSpecification> order = agg.orderBy().isEmpty() && agg.operator() == AggregateOperator.MODE
                ? List.of(new SortSpecification(agg.argument(), SortDirection.ASC))
                : agg.orderBy();
        List<Row> ordered = rows;
        if (!order.isEmpty()) {
            ordered = new ArrayList<>(rows);
            ordered.sort(rowComparator(order, eval));
        }
        for (Row row : ordered) {
            reduction.accept(row);
        }
        return reduction.finish();
    }

    /** One aggregate's accumulator over one group, fed a row at a time. */
    private static final class Reduction {

        private final OperandEvaluator eval;
        private final List<Operand> arguments;
        private final boolean skipNulls;
        private final Accumulator accumulator;
        /** DISTINCT: each value once, under the engine's equality (1 and 1.0 are one value). */
        private final Set<List<Value>> seen;

        Reduction(AggregateFunction agg, OperandEvaluator eval) {
            String name = agg.operator().name();
            com.darkcollective.relix.function.AggregateFunction reduction =
                    eval.functions().aggregate(name).orElseThrow(() -> new EvaluationException(
                            "Unknown aggregate: " + name + (eval.functions().isEmpty()
                                    ? " (no function library is installed — check that a"
                                            + " FunctionLibrary provider is on the module path)"
                                    : "")));
            this.eval = eval;
            // [argument] for a one-value reduction, [rank, yield] for ARGMAX/ARGMIN.
            this.arguments = new ArrayList<>(2);
            arguments.add(agg.argument());
            agg.yieldExpr().ifPresent(arguments::add);
            this.skipNulls = reduction.signature().skipsNulls();
            this.accumulator = reduction.accumulator(eval.functionContext());
            this.seen = agg.distinct() ? new HashSet<>() : null;
        }

        void accept(Row row) {
            List<Value> values = new ArrayList<>(arguments.size());
            boolean absent = false;
            for (Operand argument : arguments) {
                Value value = eval.evaluate(argument, row);
                absent |= value.isNull();
                values.add(value);
            }
            if (skipNulls && absent) {
                return;
            }
            if (seen != null && !seen.add(ExecSupport.canonicalKey(values, List.of()))) {
                return;
            }
            accumulator.accumulate(values);
        }

        Value finish() {
            return accumulator.finish();
        }
    }
}

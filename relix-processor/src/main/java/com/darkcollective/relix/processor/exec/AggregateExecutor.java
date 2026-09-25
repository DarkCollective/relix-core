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

import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.function.Accumulator;
import com.darkcollective.relix.function.AggregateSignature;
import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.processor.internal.ArrayRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.processor.eval.OperandEvaluator;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.Type;
import static com.darkcollective.relix.processor.exec.ExecSupport.keys;
import static com.darkcollective.relix.processor.exec.ExecSupport.rowComparator;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
        Schema outputSchema = node.schema();
        Schema inputSchema = node.input().schema();
        List<GroupingKey> groupKeys = node.groupingKeys();
        OperandEvaluator eval = ctx.operandEval();

        IndexedRelation indexed;
        try (Stream<Row> input = dispatch.buffering(node.input(), ctx, node)) {
            indexed = IndexedRelation.build(inputSchema, input,
                    row -> ExecSupport.canonicalKey(groupKey(row, groupKeys, eval),
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
                        groupKey(pending, groupKeys, eval), keyTypes);
                List<Row> groupRows = new ArrayList<>();
                groupRows.add(pending);
                pending = null;
                while (src.hasNext()) {
                    Row row = src.next();
                    if (key.equals(ExecSupport.canonicalKey(
                            groupKey(row, groupKeys, eval), keyTypes))) {
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
     * The grouping-key tuple of {@code row}: each grouping key's expression
     * evaluated against the row (a bare column reduces to {@code row.get(name)},
     * a derived key such as {@code YEAR(ts)} to the computed value).
     */
    private static List<Value> groupKey(Row row, List<GroupingKey> groupKeys, OperandEvaluator eval) {
        return groupKeys.stream().map(k -> eval.evaluate(k.expression(), row)).toList();
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
        output.addAll(groupRows.isEmpty() ? List.of() : groupKey(groupRows.getFirst(), groupKeys, eval));
        for (AggregateFunction agg : aggregates) {
            output.add(computeAggregate(agg, groupRows, eval));
        }
        return ArrayRow.of(outputSchema, output);
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
        String name = agg.operator().name();
        com.darkcollective.relix.function.AggregateFunction reduction =
                eval.functions().aggregate(name).orElseThrow(() -> new EvaluationException(
                        "Unknown aggregate: " + name + (eval.functions().isEmpty()
                                ? " (no function library is installed — check that a"
                                        + " FunctionLibrary provider is on the module path)"
                                : "")));

        // [argument] for a one-value reduction, [rank, yield] for ARGMAX/ARGMIN.
        List<Operand> arguments = new ArrayList<>(2);
        arguments.add(agg.argument());
        agg.yieldExpr().ifPresent(arguments::add);

        boolean skipNulls = reduction.signature().skipsNulls();
        Accumulator accumulator = reduction.accumulator(eval.functionContext());
        for (Row row : rows) {
            List<Value> values = new ArrayList<>(arguments.size());
            boolean absent = false;
            for (Operand argument : arguments) {
                Value value = eval.evaluate(argument, row);
                absent |= value.isNull();
                values.add(value);
            }
            if (skipNulls && absent) {
                continue;
            }
            accumulator.accumulate(values);
        }
        return accumulator.finish();
    }
}

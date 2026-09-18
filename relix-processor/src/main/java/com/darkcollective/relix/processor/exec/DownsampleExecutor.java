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

import com.darkcollective.relix.ast.ConsolidationFunction;
import com.darkcollective.relix.function.Accumulator;
import com.darkcollective.relix.function.AggregateFunction;
import com.darkcollective.relix.function.FunctionContext;
import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.processor.ArrayRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.semantic.DownsampleColumns;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Executes the {@code DOWNSAMPLE} operator: groups input rows into fixed-width
 * time buckets and consolidates each bucket's columns using the chosen
 * {@link ConsolidationFunction}.
 *
 * <p>Nothing here knows what {@code MIN} does. The consolidation function names an
 * aggregate, that name is resolved in the catalogue the query was analysed against, and
 * each bucket is reduced by a fresh {@link Accumulator} — the same reduction, over the
 * same values, that a {@code γ} over the same column would perform. The operator used to
 * carry its own arithmetic, which is how {@code MIN} came to be defined over numbers
 * here and over every ordered value there.
 */
final class DownsampleExecutor {

    /** What {@code COUNT} is fed per row: a value that is never NULL, so every row counts. */
    private static final Value ROW = new NumberValue(BigDecimal.ONE);

    private final ChildDispatch dispatch;

    DownsampleExecutor(ChildDispatch dispatch) {
        this.dispatch = dispatch;
    }

    Stream<Row> executeDownsample(PhysicalNode.Downsample node, EvalCtx ctx) {
        Schema inputSchema = node.input().schema();
        Schema outputSchema = node.schema();

        // Collect all input rows
        List<Row> inputRows;
        try (Stream<Row> input = dispatch.buffering(node.input(), ctx, node)) {
            inputRows = input.toList();
        }

        // The columns this operator consolidates, from the rule schema inference built
        // the output heading with.
        List<DownsampleColumns.Consolidation> consolidations =
                DownsampleColumns.of(node.function(), node.groupingKeys(),
                        node.timestampColumn(), inputSchema);
        AggregateFunction reduction = resolve(node.function(), ctx);
        FunctionContext functions = ctx.operandEval().functionContext();

        // Group rows by (groupingKeys, bucket)
        Map<List<Value>, List<Row>> groups = new LinkedHashMap<>();
        for (Row row : inputRows) {
            Value tsVal = row.get(node.timestampColumn());
            Instant bucket = toBucket(tsVal, node.intervalSeconds());
            if (bucket == null) continue; // skip null/non-timestamp timestamps

            List<Value> key = new ArrayList<>(node.groupingKeys().size() + 1);
            for (String gk : node.groupingKeys()) {
                key.add(row.get(gk));
            }
            key.add(new TimestampValue(bucket));
            groups.computeIfAbsent(key, unused -> new ArrayList<>()).add(row);
        }

        // Consolidate each group into an output row
        List<Row> outputRows = new ArrayList<>(groups.size());
        for (Map.Entry<List<Value>, List<Row>> entry : groups.entrySet()) {
            outputRows.add(consolidateGroup(outputSchema, entry.getKey(), entry.getValue(),
                    reduction, functions, consolidations));
        }

        // Apply maxRows: keep the most-recent N buckets (sort desc by bucket then take first N)
        if (node.maxRows().isPresent()) {
            outputRows.sort(Comparator.<Row, Instant>comparing(
                    r -> ((TimestampValue) r.get(DownsampleColumns.BUCKET_COLUMN)).value()).reversed());
            int limit = (int) Math.min(node.maxRows().getAsLong(), outputRows.size());
            outputRows = outputRows.subList(0, limit);
        }

        return BagRelation.of(outputSchema, outputRows).stream();
    }

    /**
     * The aggregate that performs this consolidation, from the query's own catalogue.
     *
     * <p>Resolved once per execution rather than per bucket: a name that is not installed
     * is a fact about the run, not about a group.
     */
    private static AggregateFunction resolve(ConsolidationFunction function, EvalCtx ctx) {
        String name = function.name();
        return ctx.operandEval().functions().aggregate(name)
                .orElseThrow(() -> new EvaluationException(
                        "Unknown aggregate: " + name + " (DOWNSAMPLE … USING " + name + ")"
                                + (ctx.operandEval().functions().isEmpty()
                                        ? " — no function library is installed; check that a"
                                                + " FunctionLibrary provider is on the module path"
                                        : "")));
    }

    /**
     * Converts a timestamp value to a bucket boundary instant, or returns {@code null}
     * if the value is null or not a timestamp.
     */
    private static Instant toBucket(Value tsVal, long intervalSeconds) {
        if (tsVal == null || tsVal.isNull() || !(tsVal instanceof TimestampValue tv)) {
            return null;
        }
        long epoch = tv.value().getEpochSecond();
        long bucketEpoch = Math.floorDiv(epoch, intervalSeconds) * intervalSeconds;
        return Instant.ofEpochSecond(bucketEpoch);
    }

    /**
     * Builds one output row for a bucket group.  The key values (grouping keys +
     * bucket) are the first columns; then the consolidated values follow.
     */
    private static Row consolidateGroup(Schema outputSchema, List<Value> key, List<Row> rows,
                                        AggregateFunction reduction, FunctionContext functions,
                                        List<DownsampleColumns.Consolidation> consolidations) {
        List<Value> values = new ArrayList<>(key);
        for (DownsampleColumns.Consolidation consolidation : consolidations) {
            values.add(consolidate(reduction, functions, consolidation.inputColumn(), rows));
        }
        return ArrayRow.of(outputSchema, values);
    }

    /**
     * Reduces one column of one bucket, through the accumulator the function library
     * supplies.
     *
     * <p>NULL handling is the signature's, exactly as a {@code γ}'s is: a row whose value
     * is NULL never reaches a reduction that declares it skips them, so a bucket with
     * nothing to reduce yields NULL rather than a zero. A consolidation over no input
     * column — {@code COUNT} — is fed one non-NULL value per row, which is what makes it
     * count rows and not values.
     */
    private static Value consolidate(AggregateFunction reduction, FunctionContext functions,
                                     Optional<String> column, List<Row> rows) {
        boolean skipsNulls = reduction.signature().skipsNulls();
        Accumulator accumulator = reduction.accumulator(functions);
        for (Row row : rows) {
            Value value = column.map(name -> {
                Value read = row.get(name);
                return read == null ? NullValue.INSTANCE : read;
            }).orElse(ROW);
            if (skipsNulls && value.isNull()) {
                continue;
            }
            accumulator.accumulate(List.of(value));
        }
        return accumulator.finish();
    }
}

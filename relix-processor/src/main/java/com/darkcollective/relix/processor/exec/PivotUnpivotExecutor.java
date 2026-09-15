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
import com.darkcollective.relix.processor.ArrayRow;
import com.darkcollective.relix.processor.DocumentRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.Schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.stream.Stream;

/**
 * Executes the UNPIVOT (columns-to-rows) and PIVOT (rows-to-columns) operators.
 *
 * <h2>UNPIVOT</h2>
 * For each input row, emits one output row <em>per listed column</em>.  Each
 * output row contains the non-listed input columns, plus:
 * <ul>
 *   <li>{@code nameColumn} — a {@link StringValue} holding the source column name</li>
 *   <li>{@code valueColumn} — the cell value from the source column</li>
 * </ul>
 * Streaming — never buffers the full input.
 *
 * <h2>PIVOT</h2>
 * Groups the input by the optional {@code groupKeys} (or treats the whole
 * relation as one group when none are specified).  Within each group the
 * distinct values of {@code keyColumn} become new column headers; the cell
 * for each header is taken from {@code valueColumn} for the group's matching
 * row, or {@link NullValue} when no row matches.  Output rows are
 * {@link DocumentRow}s (open schema — headers are only known at runtime).
 * Blocking — must materialise the full input.
 */
final class PivotUnpivotExecutor {

    private final ChildDispatch dispatch;

    PivotUnpivotExecutor(ChildDispatch dispatch) {
        this.dispatch = dispatch;
    }

    // ── UNPIVOT ─────────────────────────────────────────────────────────────

    /**
     * Folds the listed columns of each input row into separate output rows.
     * Streaming: each input row fans out to {@code columns.size()} output rows.
     */
    Stream<Row> executeUnpivot(PhysicalNode.Unpivot node, EvalCtx ctx) {
        Schema outputSchema = node.schema();
        List<String> foldCols = node.columns();
        String nameCol = node.nameColumn();
        String valueCol = node.valueColumn();

        // Determine which column positions to include in the output (all non-folded columns).
        // The output schema was already computed by inference; we just need to build rows
        // that conform to it.
        return dispatch.execute(node.input(), ctx).flatMap(row -> {
            // Collect the values for the "pass-through" columns (those not being unpivoted).
            // The output schema contains these columns first (in original order), then
            // nameColumn and valueColumn at the end.
            Schema inputSchema = row.schema();
            List<String> passThroughCols;
            if (inputSchema.isOpen()) {
                // For open-schema inputs, gather column names from the row and exclude folded.
                passThroughCols = new ArrayList<>();
                for (String col : row.columnNames()) {
                    if (!foldCols.contains(col)) {
                        passThroughCols.add(col);
                    }
                }
            } else {
                passThroughCols = new ArrayList<>();
                for (var col : inputSchema.columns()) {
                    if (!foldCols.contains(col.name())) {
                        passThroughCols.add(col.name());
                    }
                }
            }

            // Emit one output row per fold column.
            List<Row> outputRows = new ArrayList<>(foldCols.size());
            for (String foldCol : foldCols) {
                Value cellValue = row.get(foldCol);
                if (!outputSchema.isOpen()) {
                    // Closed output schema (standard case).
                    List<Value> values = new ArrayList<>(passThroughCols.size() + 2);
                    for (String passCol : passThroughCols) {
                        values.add(row.get(passCol));
                    }
                    values.add(new StringValue(foldCol));
                    values.add(cellValue);
                    outputRows.add(ArrayRow.of(outputSchema, values));
                } else {
                    // Open input schema — build DocumentRow.
                    Map<String, Value> fields = new LinkedHashMap<>();
                    for (String passCol : passThroughCols) {
                        fields.put(passCol, row.get(passCol));
                    }
                    fields.put(nameCol, new StringValue(foldCol));
                    fields.put(valueCol, cellValue);
                    outputRows.add(new DocumentRow(new StructValue(fields)));
                }
            }
            return outputRows.stream();
        });
    }

    // ── PIVOT ────────────────────────────────────────────────────────────────

    /**
     * Rotates rows into columns.  Groups the input by {@code groupKeys}, then
     * within each group turns each distinct value of {@code keyColumn} into a
     * new column whose cell is taken from {@code valueColumn}.  Output rows are
     * {@link DocumentRow}s with an open schema.
     *
     * <p>Blocking — the full input must be consumed before any output is emitted,
     * because the set of column headers is not known until all key values have been
     * seen.
     */
    Stream<Row> executePivot(PhysicalNode.Pivot node, EvalCtx ctx) {
        List<String> groupKeys = node.groupKeys();
        String keyCol = node.keyColumn();
        String valueCol = node.valueColumn();

        // Blocking: the column-header set is unknown until every key value has
        // been seen, so the input must be fully materialised before emission.
        List<Row> inputRows = dispatch.materialize(node.input(), ctx, node);
        if (inputRows.isEmpty()) {
            return Stream.empty();
        }

        // One pass collects everything emission needs, each in encounter order:
        // the distinct headers, each group's key-value → cell-value map, and the
        // group-key pass-through values captured from the group's first row.
        // With no group keys the whole relation is one group (the global pivot).
        LinkedHashSet<String> headers = new LinkedHashSet<>();
        SequencedMap<List<Value>, Map<String, Value>> cellsByGroup = new LinkedHashMap<>();
        Map<List<Value>, Map<String, Value>> passThroughByGroup = new LinkedHashMap<>();

        for (Row row : inputRows) {
            List<Value> groupKey = groupKeys.isEmpty()
                    ? GLOBAL_GROUP
                    : groupKeys.stream().map(row::get).toList();

            passThroughByGroup.computeIfAbsent(groupKey, k -> {
                Map<String, Value> passThrough = new LinkedHashMap<>();
                for (String gk : groupKeys) {
                    passThrough.put(gk, row.get(gk));
                }
                return passThrough;
            });

            Value kv = row.get(keyCol);
            if (kv != NullValue.INSTANCE) {
                String header = kv.asDisplayString();
                headers.add(header);
                cellsByGroup.computeIfAbsent(groupKey, k -> new LinkedHashMap<>())
                        .put(header, row.get(valueCol));
            } else {
                cellsByGroup.computeIfAbsent(groupKey, k -> new LinkedHashMap<>());
            }
        }

        List<Row> result = new ArrayList<>(cellsByGroup.size());
        for (Map.Entry<List<Value>, Map<String, Value>> entry : cellsByGroup.entrySet()) {
            Map<String, Value> fields =
                    new LinkedHashMap<>(passThroughByGroup.get(entry.getKey()));
            for (String header : headers) {
                fields.put(header, entry.getValue().getOrDefault(header, NullValue.INSTANCE));
            }
            result.add(new DocumentRow(new StructValue(fields)));
        }

        return result.stream();
    }

    /** Group key used when PIVOT has no PER keys: the whole relation is one group. */
    private static final List<Value> GLOBAL_GROUP = List.of(new StringValue("_global_"));
}

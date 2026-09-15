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
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Schema;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Executes set operations (union/intersect/difference) and relational division.
 *
 * <p>Holds a {@link ChildDispatch} to run input sub-plans.
 */
final class SetOpExecutor {

    private final ChildDispatch dispatch;

    SetOpExecutor(ChildDispatch dispatch) {
        this.dispatch = dispatch;
    }

    Stream<Row> executeSetOp(PhysicalNode.SetOp node, EvalCtx ctx) {
        Schema schema = node.schema();
        return switch (node.kind()) {
            // Each branch's rows are re-labelled onto the output heading before anything
            // compares them (ExecSupport.relabel): set-operation compatibility is decided
            // positionally, so the two branches may legally disagree about what the
            // columns are called, and row equality is not positional.
            case UNION -> {
                // Keyed on ExecSupport.identityKey, not on Row.equals: the values decide
                // which tuples are one tuple, under the same rule σ and the joins read.
                // The map keeps the first row of each key, so insertion order survives.
                LinkedHashMap<List<Value>, Row> seen = new LinkedHashMap<>();
                collectDistinct(dispatch.materialize(node.left(), ctx, node), schema, seen);
                collectDistinct(dispatch.materialize(node.right(), ctx, node), schema, seen);
                yield SetRelation.of(schema, seen.values()).stream();
            }
            case UNION_ALL -> {
                List<Row> left = dispatch.materialize(node.left(), ctx, node);
                List<Row> right = dispatch.materialize(node.right(), ctx, node);
                List<Row> all = new ArrayList<>(left.size() + right.size());
                all.addAll(left);
                all.addAll(right);
                // ⊎ compares nothing, but its output is still one relation under one
                // heading — and a δ above it will compare exactly these rows.
                yield BagRelation.of(schema, ExecSupport.relabelAll(all, schema)).stream();
            }
            case OUTER_UNION -> {
                // Heterogeneous merge: re-project each side onto the merged output
                // schema, NULL-padding columns it does not have, then dedup (set
                // semantics). Unlike UNION the inputs need not share a schema.
                Set<Row> seen = new LinkedHashSet<>();
                reproject(dispatch.materialize(node.left(), ctx, node), node.left().schema(), schema, seen);
                reproject(dispatch.materialize(node.right(), ctx, node), node.right().schema(), schema, seen);
                yield SetRelation.of(schema, seen).stream();
            }
            // ∩ and − are SET operations on BOTH sides. Making a set of the right input
            // alone is not enough: it decides which left rows survive, and says nothing
            // about how many times each one does. The left input is therefore
            // deduplicated too — the same hash-based pass δ makes, metered against the
            // row budget for the same reason, since it retains every distinct row.
            case INTERSECT -> {
                Set<List<Value>> rightKeys =
                        identityKeys(dispatch.materialize(node.right(), ctx, node), schema);
                yield dispatch.buffering(node.left(), ctx, node)
                        .map(row -> ExecSupport.relabel(row, schema))
                        .filter(ExecSupport.distinctByIdentity(schema))
                        .filter(row -> rightKeys.contains(ExecSupport.identityKey(row, schema)));
            }
            case DIFFERENCE -> {
                Set<List<Value>> rightKeys =
                        identityKeys(dispatch.materialize(node.right(), ctx, node), schema);
                yield dispatch.buffering(node.left(), ctx, node)
                        .map(row -> ExecSupport.relabel(row, schema))
                        .filter(ExecSupport.distinctByIdentity(schema))
                        .filter(row -> !rightKeys.contains(ExecSupport.identityKey(row, schema)));
            }
        };
    }

    /**
     * Adds each row under the output heading, keeping the first of each identity key.
     */
    private static void collectDistinct(List<Row> rows, Schema schema,
                                        LinkedHashMap<List<Value>, Row> into) {
        for (Row row : rows) {
            Row relabelled = ExecSupport.relabel(row, schema);
            into.putIfAbsent(ExecSupport.identityKey(relabelled, schema), relabelled);
        }
    }

    /** The identity keys of {@code rows}, read under the output heading. */
    private static Set<List<Value>> identityKeys(List<Row> rows, Schema schema) {
        Set<List<Value>> keys = new HashSet<>();
        for (Row row : rows) {
            keys.add(ExecSupport.identityKey(ExecSupport.relabel(row, schema), schema));
        }
        return keys;
    }

    /**
     * Re-projects each input row onto {@code outputSchema}: for every output
     * column, copies the input's value when {@code inputSchema} carries that
     * column (matched case-insensitively), else NULL-pads it.  Re-projected rows
     * are added to {@code seen} (de-duplicating under set semantics).
     */
    private static void reproject(List<Row> rows, Schema inputSchema, Schema outputSchema, Set<Row> seen) {
        List<ColumnDefinition> outCols = outputSchema.columns();
        for (Row row : rows) {
            List<Value> values = new ArrayList<>(outCols.size());
            for (ColumnDefinition col : outCols) {
                int idx = inputSchema.indexOf(col.name());
                values.add(idx >= 0 ? row.get(idx) : NullValue.INSTANCE);
            }
            seen.add(ArrayRow.of(outputSchema, values));
        }
    }

    /**
     * Relational division {@code left ÷ right}: the payload tuple {@code t} is in
     * the result iff, for every divisor row {@code r}, the left relation contains a
     * row pairing {@code t} with {@code r}.
     */
    Stream<Row> executeDivision(PhysicalNode.Division node, EvalCtx ctx) {
        Schema outputSchema = node.schema();
        List<Row> leftRows = dispatch.materialize(node.left(), ctx, node);
        List<Row> rightRows = dispatch.materialize(node.right(), ctx, node);
        if (leftRows.isEmpty()) {
            return BagRelation.of(outputSchema, List.of()).stream();
        }

        Schema leftSchema = node.left().schema();
        Schema rightSchema = node.right().schema();
        Set<String> rightNames = new HashSet<>();
        rightSchema.columns().forEach(c -> rightNames.add(c.name().toLowerCase(Locale.ROOT)));

        List<ColumnDefinition> leftCols = leftSchema.columns();
        List<Integer> resultIndices = new ArrayList<>();
        Set<Integer> divisorIndexSet = new HashSet<>();
        for (int i = 0; i < leftCols.size(); i++) {
            if (rightNames.contains(leftCols.get(i).name().toLowerCase(Locale.ROOT))) {
                divisorIndexSet.add(i);
            } else {
                resultIndices.add(i);
            }
        }

        Set<List<Value>> leftLookup = new HashSet<>();
        for (Row row : leftRows) {
            leftLookup.add(ExecSupport.allValues(row));
        }

        Set<List<Value>> candidates = new LinkedHashSet<>();
        for (Row row : leftRows) {
            candidates.add(ExecSupport.projectValues(row, resultIndices));
        }

        List<Row> outputRows = new ArrayList<>();
        for (List<Value> candidate : candidates) {
            boolean valid = true;
            for (Row rightRow : rightRows) {
                List<Value> fullKey = new ArrayList<>(leftCols.size());
                int ci = 0;
                for (int i = 0; i < leftCols.size(); i++) {
                    if (divisorIndexSet.contains(i)) {
                        fullKey.add(rightRow.get(leftCols.get(i).name()));
                    } else {
                        fullKey.add(candidate.get(ci++));
                    }
                }
                if (!leftLookup.contains(fullKey)) {
                    valid = false;
                    break;
                }
            }
            if (valid) outputRows.add(ArrayRow.of(outputSchema, candidate));
        }
        return BagRelation.of(outputSchema, outputRows).stream();
    }
}

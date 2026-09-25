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
import com.darkcollective.relix.processor.internal.ArrayRow;
import com.darkcollective.relix.processor.internal.DocumentRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.Schema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.darkcollective.relix.processor.exec.ExecSupport.rowComparator;

/**
 * Executes the adjacency-to-forest nesting operator (TREE, ADR-0019): folds a
 * self-referential adjacency relation into a forest of nested documents — one
 * output row per <em>root</em>, each carrying its whole subtree in the appended
 * {@code childrenColumn} array.
 *
 * <p>The operator is the recursive generalisation of {@code COLLECT}: it follows
 * the {@code keyColumn} → {@code parentColumn} edge to fixpoint, gathering each
 * level of children into an {@link ArrayValue} of {@link StructValue} documents of
 * the same recursive shape (each child's columns ⊕ its own {@code childrenColumn}).
 * Leaves carry an empty children array.  The recursive document is typed {@code ANY}
 * (schema-on-read; ADR-0001), so the appended children column is a nested value.
 *
 * <h2>Forest semantics & well-formedness</h2>
 * <ul>
 *   <li>A row whose parent-key is {@code NULL}, or references a key absent from the
 *       input, is a <strong>root</strong>.</li>
 *   <li>A <strong>duplicate</strong> (non-null) {@code keyColumn} value is a key
 *       violation → {@link EvaluationException}.</li>
 *   <li>A <strong>cycle</strong> in the key→parent-key graph is a user error: because
 *       every non-root has exactly one parent in the key set, a cycle is a component
 *       unreachable from any root, so any row not reached from a root signals a cycle
 *       → {@link EvaluationException} (mirroring the recursion engine's cycle cap).</li>
 * </ul>
 *
 * <p>Sibling order within each level (and the order of the roots themselves) follows
 * the {@code orderSpecs}; an empty spec list preserves input order.  The operator is
 * blocking — it materialises the whole input to build the forest.
 */
final class TreeExecutor {

    private final ChildDispatch dispatch;

    TreeExecutor(ChildDispatch dispatch) {
        this.dispatch = dispatch;
    }

    Stream<Row> executeTree(PhysicalNode.Tree node, EvalCtx ctx) {
        Schema outputSchema = node.schema();
        String keyCol = node.keyColumn();
        String parentCol = node.parentColumn();
        String childrenCol = node.childrenColumn();

        List<Row> rows;
        try (Stream<Row> input = dispatch.buffering(node.input(), ctx, node)) {
            rows = input.toList();
        }

        // Index every row by its (non-null) key, detecting duplicates eagerly.
        Map<Value, Row> byKey = new LinkedHashMap<>();
        for (Row row : rows) {
            Value key = row.get(keyCol);
            if (key == null || key.isNull()) {
                continue;   // a null key has no identity — it can only ever be a leaf
            }
            if (byKey.putIfAbsent(key, row) != null) {
                throw new EvaluationException(
                        "TREE: duplicate key '" + key.asDisplayString() + "' in column '"
                        + keyCol + "'; the adjacency relation must have unique keys");
            }
        }

        // Partition into roots (null/absent parent) and children-by-parent.
        List<Row> roots = new ArrayList<>();
        Map<Value, List<Row>> childrenByParent = new LinkedHashMap<>();
        for (Row row : rows) {
            Value parent = row.get(parentCol);
            if (parent == null || parent.isNull() || !byKey.containsKey(parent)) {
                roots.add(row);
            } else {
                childrenByParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(row);
            }
        }

        Comparator<Row> order = node.orderSpecs().isEmpty()
                ? null : rowComparator(node.orderSpecs(), ctx.operandEval());
        if (order != null) {
            roots.sort(order);
        }

        // Build the forest, counting every row woven in so an unreachable row (only
        // possible inside a cycle, given single-parent edges) is reported as a cycle.
        int[] woven = {0};
        List<Row> output = new ArrayList<>(roots.size());
        for (Row root : roots) {
            woven[0]++;
            ArrayValue children = childrenOf(root, keyCol, parentCol, childrenCol,
                    childrenByParent, order, woven);
            output.add(rootRow(root, outputSchema, childrenCol, children));
        }
        if (woven[0] != rows.size()) {
            throw new EvaluationException(
                    "TREE: cycle detected in the '" + keyCol + "' → '" + parentCol
                    + "' graph; a tree requires an acyclic, single-parent forest");
        }
        return BagRelation.of(outputSchema, output).stream();
    }

    /** The ordered array of child documents of {@code parentRow}, built recursively. */
    private static ArrayValue childrenOf(Row parentRow, String keyCol, String parentCol,
                                         String childrenCol, Map<Value, List<Row>> childrenByParent,
                                         Comparator<Row> order, int[] woven) {
        Value parentKey = parentRow.get(keyCol);
        List<Row> kids = (parentKey == null || parentKey.isNull())
                ? List.of() : childrenByParent.getOrDefault(parentKey, List.of());
        if (kids.isEmpty()) {
            return new ArrayValue(List.of());
        }
        List<Row> ordered = new ArrayList<>(kids);
        if (order != null) {
            ordered.sort(order);
        }
        List<Value> docs = new ArrayList<>(ordered.size());
        for (Row kid : ordered) {
            woven[0]++;
            ArrayValue grandChildren = childrenOf(kid, keyCol, parentCol, childrenCol,
                    childrenByParent, order, woven);
            docs.add(document(kid, childrenCol, grandChildren));
        }
        return new ArrayValue(docs);
    }

    /** A nested document for {@code row}: its own columns ⊕ {@code childrenCol}. */
    private static StructValue document(Row row, String childrenCol, ArrayValue children) {
        Map<String, Value> fields = new LinkedHashMap<>();
        for (String name : row.columnNames()) {
            fields.put(name, row.get(name));
        }
        fields.put(childrenCol, children);
        return new StructValue(fields);
    }

    /**
     * The output row for a root: its own columns ⊕ {@code childrenCol}.  An open
     * (schema-on-read) output emits a {@link DocumentRow}; a closed output appends the
     * children value as the final column.
     */
    private static Row rootRow(Row root, Schema outputSchema, String childrenCol, ArrayValue children) {
        if (outputSchema.isOpen()) {
            return new DocumentRow(document(root, childrenCol, children));
        }
        List<Value> values = new ArrayList<>(outputSchema.width());
        for (int i = 0; i < root.width(); i++) {
            values.add(root.get(i));
        }
        values.add(children);
        return ArrayRow.of(outputSchema, values);
    }
}

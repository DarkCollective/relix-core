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
package com.darkcollective.relix.ast;

import com.darkcollective.relix.ast.visitor.RelNodeVisitor;

import java.util.List;
import java.util.Objects;

/**
 * Adjacency-to-forest nesting operator — folds a
 * self-referential <em>adjacency relation</em> into a <em>forest of nested
 * documents</em>, one output row per root, each carrying its whole subtree as a
 * nested {@code ANY} document.
 *
 * <p>{@code TREE} is the <strong>recursive generalisation of {@code COLLECT}</strong>
 * (NEST): where {@code COLLECT} gathers a single level of children into an
 * array, {@code TREE} follows the {@link #keyColumn()} → {@link #parentColumn()} edge
 * to fixpoint, assembling an unbounded-depth tree in one pass. Its marquee use is
 * dogfooding the engine's own IR — {@code TREE(relix.plan)} renders the logical tree
 * as a nested document — but it is general (org charts, bill-of-materials, threaded
 * comments, file trees).
 *
 * <p>Surface syntax:
 * <pre>
 *   TREE node_id BY parent_id ORDER ordinal AS children (relix.plan)
 *   TREE id BY manager_id AS reports (Employees)
 * </pre>
 *
 * <ul>
 *   <li>{@link #keyColumn()} — the node-identity column (e.g. {@code node_id}).</li>
 *   <li>{@link #parentColumn()} — the column pointing at the parent's key; a row whose
 *       parent-key is {@code NULL}, or references a key absent from the input, is a
 *       <strong>root</strong> (forest semantics).</li>
 *   <li>{@link #orderSpecs()} — optional sibling ordering; empty = input order.</li>
 *   <li>{@link #childrenColumn()} — the name of the added nested array column.</li>
 * </ul>
 *
 * <p><strong>Output:</strong> one row per root. Each row is the root's own input
 * columns ⊕ {@code childrenColumn: ANY}, where the children column is an array of
 * documents of the same recursive shape. Leaves carry an empty children array. The
 * recursive document is typed {@code ANY} (schema-on-read; there is no recursive
 * static type).
 *
 * <p><strong>Well-formedness:</strong> a cycle in the key→parent-key graph and a
 * duplicate {@link #keyColumn()} are user errors raised at evaluation time (the
 * recursion engine's bounded-fixpoint cap is the cycle safety valve).
 *
 * <p>Evaluation must see the whole relation to build the forest, so {@code TREE} is a
 * blocking operator ({@link MaterializationMode#BAG}); it never pushes down to a
 * source and is subject to the boundedness check over unbounded inputs.
 *
 * @param input          the source adjacency relation; must not be null
 * @param keyColumn      the node-identity column; must not be blank
 * @param parentColumn   the parent-key column; must not be blank
 * @param orderSpecs     the optional sibling ordering; empty = input order; must not be null
 * @param childrenColumn the name of the appended nested array column; must not be blank
 * @param location       the source location of this node; never null
 */
public record TreeNode(
        RelNode input,
        String keyColumn,
        String parentColumn,
        List<SortSpecification> orderSpecs,
        String childrenColumn,
        SourceLocation location
) implements RelNode {

    public TreeNode {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(keyColumn, "keyColumn");
        if (keyColumn.isBlank()) {
            throw new IllegalArgumentException("Tree keyColumn must not be blank");
        }
        Objects.requireNonNull(parentColumn, "parentColumn");
        if (parentColumn.isBlank()) {
            throw new IllegalArgumentException("Tree parentColumn must not be blank");
        }
        Objects.requireNonNull(orderSpecs, "orderSpecs");
        Objects.requireNonNull(childrenColumn, "childrenColumn");
        if (childrenColumn.isBlank()) {
            throw new IllegalArgumentException("Tree childrenColumn must not be blank");
        }
        Objects.requireNonNull(location, "location");
        orderSpecs = List.copyOf(orderSpecs);
    }

    /** Convenience constructor for tests: {@link SourceLocation#UNKNOWN}. */
    public TreeNode(RelNode input, String keyColumn, String parentColumn,
                    List<SortSpecification> orderSpecs, String childrenColumn) {
        this(input, keyColumn, parentColumn, orderSpecs, childrenColumn, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

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
package com.darkcollective.relix.cost;

import com.darkcollective.relix.ast.Ordering;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SortNode;

/**
 * Derives the {@link Ordering} a {@link RelNode} sub-tree is guaranteed to deliver,
 * bottom-up and purely structurally.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li><strong>Establish</strong> — {@code τ} (SORT) delivers exactly its sort
 *       keys.</li>
 *   <li><strong>Preserve</strong> — order-preserving operators carry their input's
 *       ordering through: {@code σ} (selection filters but keeps order), {@code λ}
 *       (limit/offset takes an ordered prefix), and a relation-only {@code ρ}
 *       (renames the relation, not its columns).</li>
 *   <li><strong>Clear</strong> — every other operator delivers {@link Ordering#none()}
 *       conservatively (it may reorder, regroup, or — for a column-renaming
 *       {@code ρ} — invalidate the ordering's column names). Under-claiming order is
 *       always safe: it only ever <em>keeps</em> a sort that could have been removed.</li>
 * </ul>
 *
 * <p>This is the conservative C1 set; richer propagation (key-preserving {@code π},
 * merge-join output order, sort-capable sources) arrives with the merge operators.
 */
public final class OrderDeriver {

    /** Maximum recursion depth, mirroring {@link CostEstimator#MAX_DEPTH}. */
    static final int MAX_DEPTH = 64;

    private OrderDeriver() {}

    /**
     * Derives the ordering delivered by {@code node}.
     *
     * @param node the tree to analyse; must not be null
     * @return the delivered ordering; never null (conservatively
     *         {@link Ordering#none()} when no order is guaranteed)
     */
    public static Ordering derive(RelNode node) {
        return deriveAt(node, 0);
    }

    private static Ordering deriveAt(RelNode node, int depth) {
        if (depth > MAX_DEPTH) return Ordering.none();
        if (node instanceof SortNode s) {
            return Ordering.of(s.sortSpecs());
        }
        // Consult the shared classification rather than duplicating the operator list.
        // OperatorSemantics.preservesInputOrder covers σ, λ, and relation-only ρ.
        if (OperatorSemantics.preservesInputOrder(node)) {
            return deriveAt(node.children().get(0), depth + 1);
        }
        return Ordering.none();
    }
}

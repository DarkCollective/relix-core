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
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.SortNode;
import com.darkcollective.relix.ast.internal.OrderingMaps;

import java.util.Optional;

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
 *   <li><strong>Rename</strong> — {@code π} and a pair-form {@code ρ} keep their
 *       input's row order but change its column names, so they deliver the longest
 *       prefix of the input's ordering whose keys survive, under their output names
 *       ({@link OrderingMaps}). A projected expression needs an alias to be named
 *       here; the physical plan, which has the schema, names it either way.</li>
 *   <li><strong>Clear</strong> — every other operator delivers {@link Ordering#none()}
 *       conservatively (it may reorder or regroup, and a positional {@code ρ} names
 *       columns by a position this tree does not know). Under-claiming order is
 *       always safe: it only ever <em>keeps</em> a sort that could have been removed.</li>
 * </ul>
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
        if (node instanceof ProjectionNode p) {
            return OrderingMaps.throughProjection(deriveAt(p.input(), depth + 1), p.attributes(),
                    i -> OrderingMaps.nameOf(p.attributes().get(i)));
        }
        if (node instanceof RenameNode r && r.attributes().isEmpty()) {
            return OrderingMaps.renamed(deriveAt(r.input(), depth + 1), column -> Optional.of(
                    r.pairs().stream()
                            .filter(pair -> pair.from().equalsIgnoreCase(column))
                            .map(RenameNode.RenamePair::to)
                            .findFirst()
                            .orElse(column)));
        }
        return Ordering.none();
    }
}

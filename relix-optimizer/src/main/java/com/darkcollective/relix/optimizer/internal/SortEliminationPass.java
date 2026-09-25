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
package com.darkcollective.relix.optimizer.internal;

import com.darkcollective.relix.optimizer.OptimizationCode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SortNode;
import com.darkcollective.relix.cost.OrderDeriver;
import com.darkcollective.relix.ast.Ordering;

/**
 * Optimization pass that removes a redundant {@code τ} (SORT) whose input already
 * delivers a satisfying order ({@code SORT-001}).
 *
 * <h2>Rule</h2>
 * <pre>{@code
 *   τ keys (R)  →  R     when R already delivers an order that satisfies `keys`
 * }</pre>
 *
 * <p>The delivered order is derived by {@link OrderDeriver}: a {@code τ} establishes
 * its sort keys, and order-preserving operators ({@code σ}, {@code λ}, relation-only
 * {@code ρ}) carry it. The required order is satisfied when it is a <em>prefix</em>
 * of the delivered order, so {@code τ x (τ x, y (R))} → {@code τ x, y (R)} (sorting by
 * {@code (x, y)} already gives {@code x} order) and {@code τ x (σ p (τ x (R)))} →
 * {@code σ p (τ x (R))}.
 *
 * <p>The pass is <em>bottom-up</em>: children are rewritten (via
 * {@link RelNode#mapChildren}) before the rule is attempted at the current node, so a
 * {@code τ} exposed as redundant by an inner rewrite is also removed.
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, OptimizationContext)} as a
 * static method.
 */
final class SortEliminationPass {

    private SortEliminationPass() {}

    /**
     * Applies redundant-sort elimination (SORT-001) to the entire tree rooted at
     * {@code node}.
     *
     * @param node      root of the tree to transform; must not be null
     * @param queryName display name used in transformation records
     * @param ctx       transformation record accumulator
     * @return the transformed tree, or {@code node} unchanged when no rule fires
     */
    static RelNode apply(RelNode node, String queryName,
                         OptimizationContext ctx) {
        return rewrite(node, queryName, ctx);
    }

    private static RelNode rewrite(RelNode node, String queryName, OptimizationContext ctx) {
        RelNode rewritten = node.mapChildren(child -> rewrite(child, queryName, ctx));
        if (rewritten instanceof SortNode s
                && OrderDeriver.derive(s.input()).satisfies(Ordering.of(s.sortSpecs()))) {
            ctx.record(OptimizationCode.SORT_001, queryName,
                    "redundant SORT removed; input already delivers a satisfying order",
                    s.location());
            return s.input();
        }
        return rewritten;
    }
}

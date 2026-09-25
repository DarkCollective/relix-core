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
import com.darkcollective.relix.ast.ProductNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.TruthRelationNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;

/**
 * Optimization pass that removes a Cartesian product against the one-tuple truth
 * relation {@code UNIT} ({@code DEE}) — the identity of {@code ×} ({@code PROD-001}).
 *
 * <h2>Rule</h2>
 * <pre>{@code
 *   R × UNIT  →  R
 *   UNIT × R  →  R
 * }</pre>
 *
 * <p>{@code UNIT} has the empty heading and holds exactly one row (the empty
 * tuple), so pairing it with every row of {@code R} reproduces {@code R} — the
 * same rows, and, because concatenating zero columns on either side leaves the
 * column sequence untouched, the same <em>ordered</em> schema. That last part is
 * what makes the rewrite safe for the positional consumers ({@code ρ}'s positional
 * form, the whole-row set operations) — a join-commuting rewrite, which does
 * permute that sequence, is not.
 *
 * <p>There is deliberately no companion rule for the zero-tuple {@code EMPTY}
 * ({@code DUM}): {@code R × EMPTY} yields no rows but keeps {@code R}'s heading,
 * whereas {@code EMPTY} has the empty heading, so the two are not interchangeable.
 *
 * <p>The pass is <em>bottom-up</em>: each input is rewritten before the rule is
 * attempted at the current node, so a chain such as {@code (R × UNIT) × UNIT}
 * collapses fully in one traversal.
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, SchemaAnnotations, OptimizationContext)} as a
 * static method.
 */
final class ProductIdentityPass {

    private ProductIdentityPass() {}

    /**
     * Applies product-identity elimination (PROD-001) to the entire tree rooted at
     * {@code node}.
     *
     * @param node      root of the tree to transform; must not be null
     * @param queryName display name used in transformation records
     * @param schemas   schema annotations; unused by this pass but accepted for
     *                  API consistency with the other passes
     * @param ctx       transformation record accumulator
     * @return the transformed tree, or {@code node} unchanged when no rule fires
     */
    static RelNode apply(RelNode node, String queryName,
                         SchemaAnnotations schemas, OptimizationContext ctx) {
        return rewriteNode(node, queryName, schemas, ctx);
    }

    private static RelNode rewriteNode(RelNode node, String queryName,
                                       SchemaAnnotations schemas,
                                       OptimizationContext ctx) {
        RelNode rewritten = node.mapChildren(child -> rewriteNode(child, queryName, schemas, ctx));
        if (!(rewritten instanceof ProductNode p)) {
            return rewritten;
        }
        if (isUnit(p.right())) {
            record(queryName, ctx, p);
            return p.left();
        }
        if (isUnit(p.left())) {
            record(queryName, ctx, p);
            return p.right();
        }
        return rewritten;
    }

    /** {@return whether {@code node} is the one-tuple truth relation {@code UNIT}} */
    private static boolean isUnit(RelNode node) {
        return node instanceof TruthRelationNode t && t.holdsTuple();
    }

    private static void record(String queryName, OptimizationContext ctx, ProductNode p) {
        ctx.record(OptimizationCode.PROD_001, queryName,
                "product against UNIT removed; UNIT is the identity of ×",
                p.location());
    }
}

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
import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;

/**
 * Optimization pass that decomposes every conjunctive selection predicate into
 * two stacked selections, enabling each factor to be pushed independently
 * ({@code SEL-001}).
 *
 * <p>Rule: {@code σ(A ∧ B)(R) → σ(A)(σ(B)(R))}.
 *
 * <p>The rule is applied recursively so deeply nested conjunctions are fully
 * split in a single pass:
 * {@code σ(A ∧ B ∧ C)(R) → σ(A)(σ(B)(σ(C)(R)))}.
 *
 * <p>This pass should run <em>before</em> the pushdown pass so that individual
 * conjuncts are independently moveable.
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, SchemaAnnotations, OptimizationContext)} as a
 * static method.
 */
public final class SelectionSplitPass {

    private SelectionSplitPass() {}

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Applies conjunctive selection splitting (SEL-001) to the entire tree
     * rooted at {@code node}.
     *
     * @param node      root of the tree to transform; must not be null
     * @param queryName display name used in transformation records
     * @param schemas   schema annotations (not used by this pass)
     * @param ctx       transformation record accumulator
     * @return the transformed tree, or {@code node} unchanged when no rule fires
     */
    static RelNode apply(RelNode node, String queryName,
                         SchemaAnnotations schemas, OptimizationContext ctx) {
        return rewriteNode(node, queryName, ctx);
    }

    // =========================================================================
    // RelNode traversal
    // =========================================================================

    private static RelNode rewriteNode(RelNode node, String queryName, OptimizationContext ctx) {
        return switch (node) {

            // ── σ: split conjunctions after recursing into input ─────────────
            case SelectionNode s -> {
                RelNode newInput = rewriteNode(s.input(), queryName, ctx);
                SelectionNode current = (newInput != s.input())
                        ? new SelectionNode(s.predicate(), newInput, s.location()) : s;
                yield splitIfConjunction(current, queryName, ctx);
            }

            // ── All other nodes: recurse into children, no rule fires ────────
            default -> node.mapChildren(child -> rewriteNode(child, queryName, ctx));
        };
    }

    // =========================================================================
    // Split helper
    // =========================================================================

    /**
     * If {@code s} has a conjunctive predicate, recursively creates stacked
     * selections — one per conjunct — returning the outermost.  Records
     * {@link OptimizationCode#SEL_001} once per split.  Returns {@code s}
     * unchanged when the predicate is not a conjunction.
     */
    private static SelectionNode splitIfConjunction(SelectionNode s,
                                                     String queryName,
                                                     OptimizationContext ctx) {
        if (s.predicate() instanceof AndPredicate a) {
            // Recursively split the inner (right) half first
            SelectionNode inner = splitIfConjunction(
                    new SelectionNode(a.right(), s.input(), s.location()),
                    queryName, ctx);
            ctx.record(OptimizationCode.SEL_001, queryName,
                    "conjunctive predicate split into two stacked selections",
                    s.location());
            return new SelectionNode(a.left(), inner, s.location());
        }
        return s;
    }
}

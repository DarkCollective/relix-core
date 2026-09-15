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
package com.darkcollective.relix.optimizer;

import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;

/**
 * Cleanup pass that merges two adjacent (stacked) selections into a single
 * selection with a conjunctive predicate ({@code SEL-002}).
 *
 * <p>Rule: {@code σ(A)(σ(B)(R)) → σ(A ∧ B)(R)}.
 *
 * <p>Multiple adjacent selections are collapsed in a single pass:
 * {@code σ(A)(σ(B)(σ(C)(R))) → σ(A ∧ B ∧ C)(R)} (two firings, bottom-up).
 *
 * <p>This pass is the inverse of {@link SelectionSplitPass} and is intended as
 * a cleanup step after selection pushdown has been iterated to fixpoint.
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, SchemaAnnotations, OptimizationContext)} as a
 * static method.
 */
final class SelectionMergePass {

    private SelectionMergePass() {}

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Applies adjacent selection merging (SEL-002) to the entire tree rooted
     * at {@code node}.
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

            // ── σ: after recursing into input, try to merge with inner σ ─────
            case SelectionNode s -> {
                RelNode newInput = rewriteNode(s.input(), queryName, ctx);
                SelectionNode current = (newInput != s.input())
                        ? new SelectionNode(s.predicate(), newInput, s.location()) : s;
                yield mergeAdjacent(current, queryName, ctx);
            }

            // ── All other nodes: recurse into children, no rule fires ────────
            default -> node.mapChildren(child -> rewriteNode(child, queryName, ctx));
        };
    }

    // =========================================================================
    // Merge helper
    // =========================================================================

    /**
     * Merges {@code s} with its inner selection (if any), producing a single
     * selection with {@code AND}-ed predicates.  Repeats until no adjacent
     * pair remains.  Records {@link OptimizationCode#SEL_002} once per merge.
     */
    private static RelNode mergeAdjacent(SelectionNode s,
                                          String queryName,
                                          OptimizationContext ctx) {
        if (s.input() instanceof SelectionNode inner) {
            var merged = new SelectionNode(
                    new AndPredicate(s.predicate(), inner.predicate(), s.location()),
                    inner.input(),
                    s.location());
            ctx.record(OptimizationCode.SEL_002, queryName,
                    "two adjacent selections merged into one conjunctive predicate",
                    s.location());
            // Recurse: maybe the merged selection's input is another selection
            return mergeAdjacent(merged, queryName, ctx);
        }
        return s;
    }
}

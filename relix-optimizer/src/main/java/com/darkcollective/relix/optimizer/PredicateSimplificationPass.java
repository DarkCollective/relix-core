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

import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelNodeOperands;
import com.darkcollective.relix.semantic.SchemaAnnotations;

import java.util.function.UnaryOperator;

/**
 * A full-tree optimization pass that simplifies every {@link Predicate}
 * sub-expression inside a {@link RelNode} tree using {@link PredicateSimplifier}
 * (rules PRED-001..003).
 *
 * <p>The pass visits every node in the tree and simplifies <em>every</em> predicate that
 * node carries — the set {@link RelNodeOperands#map} enumerates.  That is σ's predicate
 * and the conditional joins' conditions, but equally ∀'s predicate and an AS-OF join's
 * condition: {@code AsOfJoinNode} is deliberately not a
 * {@link com.darkcollective.relix.ast.ConditionalJoinNode} (it carries extra state
 * beyond a single predicate), so an arm matching only that interface would miss an
 * otherwise ordinary σ-shaped predicate.  Driving the rewrite through the exhaustive
 * walker removes the chance of that class of omission entirely.
 *
 * <p>The simplifier itself recurses into {@code AND}/{@code OR}/{@code NOT}
 * sub-trees, so deeply nested predicates are fully rewritten in one pass.
 *
 * <p>Unchanged subtrees are reused by reference (no unnecessary allocation).
 *
 * <p>This pass should run <em>after</em> {@link ExpressionSimplificationPass}
 * so that operands inside predicates are already in constant-folded form before
 * PRED-003 normalises comparison operand order and PRED-001 evaluates
 * all-literal comparisons.
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, SchemaAnnotations, OptimizationContext)} as a
 * static method.
 */
final class PredicateSimplificationPass {

    private PredicateSimplificationPass() {}

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Applies predicate simplification (PRED-001..003) to the entire tree
     * rooted at {@code node}, recording every transformation in {@code ctx}.
     *
     * @param node      root of the tree to simplify; must not be null
     * @param queryName display name used in transformation records
     * @param schemas   schema annotations (unused by this pass, present for
     *                  API consistency with other passes)
     * @param ctx       transformation record accumulator
     * @return the simplified tree, or {@code node} unchanged when no rules fire
     */
    static RelNode apply(RelNode node, String queryName,
                         SchemaAnnotations schemas, OptimizationContext ctx) {
        var simplifier = new PredicateSimplifier(queryName, ctx);
        return rewriteNode(node, simplifier);
    }

    // =========================================================================
    // RelNode traversal
    // =========================================================================

    /**
     * Rewrites {@code node}'s children, then every predicate {@code node} itself
     * carries.  Operands are left alone — {@link ExpressionSimplificationPass} owns
     * those, and has already run.
     */
    private static RelNode rewriteNode(RelNode node, PredicateSimplifier simplifier) {
        RelNode withChildren = node.mapChildren(child -> rewriteNode(child, simplifier));
        return RelNodeOperands.map(withChildren,
                UnaryOperator.<Operand>identity(),
                simplifier::simplify);
    }
}

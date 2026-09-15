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
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelNodeOperands;
import com.darkcollective.relix.semantic.SchemaAnnotations;

/**
 * A full-tree optimization pass that rewrites every {@link Operand}
 * sub-expression inside a {@link RelNode} tree using {@link OperandSimplifier}
 * (rules EXPR-001..006).
 *
 * <p>The traversal visits every node in the tree, and at each node rewrites
 * <em>every</em> expression that node carries — the operand set
 * {@link RelNodeOperands#map} enumerates.  That is not only σ's predicate and π's
 * attribute expressions: it is also a γ's aggregate arguments and grouping keys, a τ
 * sort key, an AS-OF tolerance, a generator's produce bound, a {@code LATERAL} or TVF
 * call's arguments, and the rest.  Driving the rewrite through the exhaustive walker
 * rather than a hand-written {@code switch} is what keeps that list complete: a new
 * node kind cannot quietly opt out of simplification, because it does not compile
 * until its expressions are registered in the walker.
 *
 * <p>Predicates themselves are walked recursively so that operands nested
 * inside {@code AND}/{@code OR}/{@code NOT} trees are reached — including the
 * predicate a {@link com.darkcollective.relix.ast.ConditionOperand} wraps, so an
 * {@code IIf} condition is simplified like any other expression.  Reaching in there
 * also applies {@code PRED-001..003} to that condition (see
 * {@link OperandSimplifier#simplifyWithin}), which is why this pass can record a
 * {@code PRED-*} code even though {@link PredicateSimplificationPass} owns those rules
 * everywhere else: nothing else walks operands, so nothing else can find a condition
 * sitting inside one.
 *
 * <p>The pass produces a new tree only when at least one rule fires;
 * unchanged subtrees are reused by reference (no unnecessary allocation).
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, SchemaAnnotations, OptimizationContext)} as a
 * static method.
 */
final class ExpressionSimplificationPass {

    private ExpressionSimplificationPass() {}

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Applies expression simplification (EXPR-001..006) to the entire tree
     * rooted at {@code node}, recording every transformation in {@code ctx}.
     *
     * @param node      root of the tree to simplify; must not be null
     * @param queryName display name used in transformation records
     * @param schemas   schema annotations (unused by this pass, present for
     *                  future rule consistency)
     * @param ctx       transformation record accumulator
     * @return the simplified tree, or {@code node} unchanged when no rules fire
     */
    static RelNode apply(RelNode node, String queryName,
                         SchemaAnnotations schemas, OptimizationContext ctx) {
        var simplifier = new OperandSimplifier(queryName, ctx);
        return rewriteNode(node, simplifier);
    }

    // =========================================================================
    // RelNode traversal
    // =========================================================================

    /**
     * Rewrites {@code node}'s children, then every expression {@code node} itself
     * carries.  Both steps return their argument by reference when nothing changed, so
     * an untouched subtree costs no allocation and the pipeline's reference-inequality
     * progress check stays accurate.
     */
    private static RelNode rewriteNode(RelNode node, OperandSimplifier simplifier) {
        RelNode withChildren = node.mapChildren(child -> rewriteNode(child, simplifier));
        return RelNodeOperands.map(withChildren,
                simplifier::simplify,
                simplifier::simplifyWithin);
    }
}

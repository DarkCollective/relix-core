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
import com.darkcollective.relix.ast.AggregationNode;
import com.darkcollective.relix.ast.AntiJoinNode;
import com.darkcollective.relix.ast.AsOfJoinNode;
import com.darkcollective.relix.ast.CompositionNode;
import com.darkcollective.relix.ast.DifferenceNode;
import com.darkcollective.relix.ast.DistinctNode;
import com.darkcollective.relix.ast.DivisionNode;
import com.darkcollective.relix.ast.EmptyRelationNode;
import com.darkcollective.relix.ast.FullOuterJoinNode;
import com.darkcollective.relix.ast.IntersectionNode;
import com.darkcollective.relix.ast.IntervalJoinNode;
import com.darkcollective.relix.ast.LateralJoinNode;
import com.darkcollective.relix.ast.LeftOuterJoinNode;
import com.darkcollective.relix.ast.LimitNode;
import com.darkcollective.relix.ast.NaturalJoinNode;
import com.darkcollective.relix.ast.OuterUnionNode;
import com.darkcollective.relix.ast.ProductNode;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.RightOuterJoinNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SemiJoinNode;
import com.darkcollective.relix.ast.SortNode;
import com.darkcollective.relix.ast.SymmetricDifferenceNode;
import com.darkcollective.relix.ast.ThetaJoinNode;
import com.darkcollective.relix.ast.UnionAllNode;
import com.darkcollective.relix.ast.UnionNode;
import com.darkcollective.relix.ast.UniversalNode;
import com.darkcollective.relix.ast.UnnestNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;

/**
 * Turns a provably unsatisfiable sub-expression into {@link EmptyRelationNode} and
 * propagates that emptiness up the tree — {@code EMPTY-001}, {@code EMPTY-002} and
 * {@code EMPTY-003}.
 *
 * <h2>Rules</h2>
 * <pre>{@code
 *   EMPTY-001   σ false (R)          →  ∅
 *   EMPTY-002   ∅ ⋈ X, X ⋈ ∅         →  ∅        (and the operators listed below)
 *   EMPTY-003   X ⊎ ∅                →  X
 * }</pre>
 *
 * <p>{@code PRED-004} produces the constant-false predicate this pass keys on, so a
 * contradictory filter such as {@code σ x > 5 ∧ x < 3 (Orders)} loses not only the
 * scan of {@code Orders} but every join and aggregation stacked above it.
 *
 * <h2>The empty relation keeps a heading</h2>
 * <p>Each replacement wraps the node it replaces, so {@code ∅} always carries the
 * heading its position requires: collapsing {@code ∅ ⋈ X} yields the empty relation
 * with the <em>join's</em> columns, not the empty side's. That is why this is not
 * {@code EMPTY}/{@code DUM} ({@link com.darkcollective.relix.ast.TruthRelationNode}),
 * which is the zero-column relation and would change the query's output shape.
 *
 * <h2>Which operators propagate, and which deliberately do not</h2>
 * <p>An operator only collapses when an empty input makes its output empty
 * <em>for certain</em>:
 * <ul>
 *   <li><b>Unary</b> — σ, π, ρ, τ, δ, λ, μ: each emits at most one output row per
 *       input row, so no input means no output.</li>
 *   <li><b>γ and ∀ only when they group.</b> A <em>scalar</em> aggregate over nothing
 *       is one row, not none — {@code γ COUNT(*)} of an empty relation is {@code 0},
 *       and a no-key {@code ∀} over an empty relation is vacuously true. Collapsing
 *       either would delete a row the query must return.</li>
 *   <li><b>Joins</b> — ×, ⋈, ⨝θ, ∘, interval: either side. ⋉: either side (an empty
 *       right matches nothing). ▷ and ⟕: left only. ⟖: right only. ⟗: both.
 *       AS-OF and lateral: left only, since each is driven by its left input.</li>
 *   <li><b>Set operations</b> — ∩ and ÷: left (and, for ∩, either). ∪, ⊎, ⊔, ∆: both.</li>
 * </ul>
 *
 * <p>Three of the identities the issue proposed are <strong>excluded</strong>, each
 * for a reason worth keeping:
 * <ul>
 *   <li><b>{@code ∅ ⊎ X → X} is unsound, while its mirror is fine.</b> A bag union
 *       takes its output schema from its <em>left</em> branch, so dropping an empty
 *       left branch renames the result's columns to the right branch's. The mirror
 *       {@code X ⊎ ∅ → X} keeps the left schema and, since ⊎ is a bag operation, the
 *       left multiplicities too — so only that direction ships, as
 *       {@code EMPTY-003}. "Safe in principle" is not safe when the operator is
 *       positional — the same trap column pruning has to avoid.</li>
 *   <li><b>{@code X − ∅ → X} is unsound against the <em>declared</em> semantics.</b>
 *       Today's executor streams the left input of {@code −} and filters, so the
 *       rewrite would be row-for-row correct; but {@code −} declares
 *       {@link com.darkcollective.relix.ast.MaterializationMode#SET}, and
 *       {@code PropertyDeriver} tells {@code DIST-001} that its output is
 *       duplicate-free on the strength of that declaration. Replacing the difference
 *       with a possibly-duplicated {@code X} would make that claim false, and a δ
 *       {@code DIST-001} had already removed would not come back. The same reasoning
 *       excludes {@code ∅ ∪ X → X}.</li>
 *   <li><b>{@code X ÷ ∅} is not empty.</b> Division by an empty divisor returns every
 *       row of the quotient's projection — vacuous truth again — so only the
 *       <em>left</em>-empty direction collapses.</li>
 * </ul>
 *
 * <p>The pass is <em>bottom-up</em>: each input is rewritten before the node above it
 * is tested, so emptiness introduced at a leaf travels all the way to the root in one
 * traversal.
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, SchemaAnnotations, OptimizationContext)} as a static
 * method.
 */
final class EmptyRelationPropagationPass {

    private EmptyRelationPropagationPass() {
    }

    /**
     * Applies empty-relation introduction and propagation to the whole tree rooted at
     * {@code node}.
     *
     * @param node      root of the tree to transform; must not be null
     * @param queryName display name used in transformation records
     * @param schemas   schema annotations; unused by this pass but accepted for API
     *                  consistency with the other passes
     * @param ctx       transformation record accumulator
     * @return the transformed tree, or {@code node} unchanged when no rule fires
     */
    static RelNode apply(RelNode node, String queryName,
                         SchemaAnnotations schemas, OptimizationContext ctx) {
        return rewrite(node, queryName, schemas, ctx);
    }

    private static RelNode rewrite(RelNode node, String queryName,
                                   SchemaAnnotations schemas, OptimizationContext ctx) {
        RelNode rewritten = node.mapChildren(child -> rewrite(child, queryName, schemas, ctx));

        // EMPTY-001 — an unsatisfiable filter.
        if (rewritten instanceof SelectionNode s && isConstantFalse(s)) {
            ctx.record(OptimizationCode.EMPTY_001, queryName,
                    "selection can admit no row; sub-tree replaced by the empty relation",
                    s.location());
            return EmptyRelationNode.of(rewritten);
        }

        // EMPTY-003 — a bag union with an empty right branch is its left branch.
        if (rewritten instanceof UnionAllNode u && isEmpty(u.right())) {
            ctx.record(OptimizationCode.EMPTY_003, queryName,
                    "empty right branch of ⊎ dropped", u.location());
            return u.left();
        }

        // EMPTY-002 — the operator's output is empty because an input is.
        if (forcesEmpty(rewritten)) {
            ctx.record(OptimizationCode.EMPTY_002, queryName,
                    "operator output is empty because an input is; replaced by ∅",
                    rewritten.location());
            return EmptyRelationNode.of(rewritten);
        }
        return rewritten;
    }

    /** {@return whether {@code s}'s predicate is the constant-false predicate} */
    private static boolean isConstantFalse(SelectionNode s) {
        return PredicateSimplifier.evalConstant(s.predicate())
                .filter(value -> !value)
                .isPresent();
    }

    /** {@return whether {@code node} is already known to produce no rows} */
    private static boolean isEmpty(RelNode node) {
        return node instanceof EmptyRelationNode;
    }

    /**
     * {@return whether {@code node}'s output is empty because the right input is}
     *
     * <p>An empty node is not itself re-wrapped: {@code ∅⟨∅⟨…⟩⟩} adds nothing and
     * would make the pass non-terminating under the pipeline's re-sweep.
     */
    private static boolean forcesEmpty(RelNode node) {
        if (isEmpty(node)) {
            return false;
        }
        return switch (node) {
            // Unary: at most one output row per input row.
            case SelectionNode n  -> isEmpty(n.input());
            case ProjectionNode n -> isEmpty(n.input());
            case RenameNode n     -> isEmpty(n.input());
            case SortNode n       -> isEmpty(n.input());
            case DistinctNode n   -> isEmpty(n.input());
            case LimitNode n      -> isEmpty(n.input());
            case UnnestNode n     -> isEmpty(n.input());

            // Grouping operators only when they group: a scalar aggregate over an
            // empty input is one row (COUNT = 0), and a no-key ∀ is vacuously true.
            case AggregationNode n -> !n.groupingKeys().isEmpty() && isEmpty(n.input());
            case UniversalNode n   -> !n.groupingAttributes().isEmpty() && isEmpty(n.input());

            // Joins where either side being empty empties the result.
            case ProductNode n       -> isEmpty(n.left()) || isEmpty(n.right());
            case NaturalJoinNode n   -> isEmpty(n.left()) || isEmpty(n.right());
            case ThetaJoinNode n     -> isEmpty(n.left()) || isEmpty(n.right());
            case CompositionNode n   -> isEmpty(n.left()) || isEmpty(n.right());
            case IntervalJoinNode n  -> isEmpty(n.left()) || isEmpty(n.right());
            // ⋉ keeps a left row only if the right side has a match; no right row,
            // no match, so an empty right empties the result just as an empty left does.
            case SemiJoinNode n      -> isEmpty(n.left()) || isEmpty(n.right());

            // Joins driven by one side: an empty *other* side pads or passes through.
            case LeftOuterJoinNode n  -> isEmpty(n.left());
            case RightOuterJoinNode n -> isEmpty(n.right());
            case AntiJoinNode n       -> isEmpty(n.left());
            case AsOfJoinNode n       -> isEmpty(n.left());
            case LateralJoinNode n    -> isEmpty(n.left());
            case FullOuterJoinNode n  -> isEmpty(n.left()) && isEmpty(n.right());

            // Set operations. − and ÷ output a subset of their left input; ∩ is empty
            // if either side is. ∪/⊎/⊔/∆ need both. (X − ∅ → X and ∅ ⊎ X → X are
            // deliberately absent — see the class Javadoc.)
            case DifferenceNode n         -> isEmpty(n.left());
            case DivisionNode n           -> isEmpty(n.left());
            case IntersectionNode n       -> isEmpty(n.left()) || isEmpty(n.right());
            case UnionNode n              -> isEmpty(n.left()) && isEmpty(n.right());
            case UnionAllNode n           -> isEmpty(n.left()) && isEmpty(n.right());
            case OuterUnionNode n         -> isEmpty(n.left()) && isEmpty(n.right());
            case SymmetricDifferenceNode n -> isEmpty(n.left()) && isEmpty(n.right());

            // Everything else — generators, recursion, solver, analytics, COVER, WHY,
            // the truth literals — is left alone. Some could be added (τ over ∅ is
            // already covered above), but each needs its own argument about what an
            // empty input means, and an unproved collapse is a wrong answer rather
            // than a missed optimisation.
            default -> false;
        };
    }
}

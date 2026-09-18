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
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.ConditionalJoinNode;
import com.darkcollective.relix.ast.ElementOfPredicate;
import com.darkcollective.relix.ast.FullOuterJoinNode;
import com.darkcollective.relix.ast.LeftOuterJoinNode;
import com.darkcollective.relix.ast.NotPredicate;
import com.darkcollective.relix.ast.NullPredicate;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.OrPredicate;
import com.darkcollective.relix.ast.PatternPredicate;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.Predicates;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RightOuterJoinNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.ThetaJoinNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.symbol.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Outer-join demotion ({@code JOIN-004}) — turning an outer join into a less outer one
 * when a filter above it makes the padded rows unreachable.
 *
 * <pre>{@code
 *   σ p (A ⟕ B)   ≡   σ p (A ⨝θ B)     when p rejects NULLs on a column of B
 * }</pre>
 *
 * <p>An outer join emits, besides the matched rows, rows padded with NULLs on the side
 * that had no match.  If a predicate above the join can never be <em>true</em> of a row
 * whose column {@code c} is NULL, and {@code c} comes from the padded side, then those
 * padded rows are all filtered out — so producing them was work for nothing, and the
 * join is equivalent to an inner one.
 *
 * <h2>Why it is worth a rule of its own</h2>
 * <p>Demotion is a gate in front of three optimizations an outer join simply cannot
 * have:
 * <ol>
 *   <li><b>σ pushdown into both sides.</b>  {@link SelectionPushdownPass} pushes into
 *       {@code ⟕}'s left only and {@code ⟖}'s right only; an inner join takes both.</li>
 *   <li><b>Merge eligibility and SQL pushdown.</b>  {@code Planner.mergeEligible} is
 *       {@code {INNER, SEMI, ANTI}}, so an outer join can never take a sort-merge plan,
 *       and {@code SqlPushdownPlanner} folds only theta joins into a {@code JOIN … ON}.</li>
 *   <li><b>{@code EQ-001}.</b>  It reads facts out of inner-join conditions only, so a
 *       demoted join becomes eligible for equality propagation — which is why this rule
 *       runs before it in the pushdown phase.</li>
 * </ol>
 * <p>The planner additionally builds the cheaper side of an inner join by cost
 * ({@code Planner.buildSide}), a choice it does not make for the asymmetric join kinds.
 *
 * <h2>What each shape demotes to</h2>
 * <p>A {@code ⟗} loses one half at a time, because each half is killed by a predicate on
 * the <em>other</em> side's columns — the unmatched-left rows are the ones carrying NULL
 * right columns:
 * <table border="1">
 *   <caption>Demotion table</caption>
 *   <tr><th>Join</th><th>rejects on left columns</th><th>rejects on right columns</th></tr>
 *   <tr><td>{@code ⟕}</td><td>—</td><td>{@code ⨝θ}</td></tr>
 *   <tr><td>{@code ⟖}</td><td>{@code ⨝θ}</td><td>—</td></tr>
 *   <tr><td>{@code ⟗}</td><td>{@code ⟕}</td><td>{@code ⟖}</td></tr>
 * </table>
 * <p>A {@code ⟗} whose filter rejects on <em>both</em> sides loses both halves at once
 * and goes straight to {@code ⨝θ} — one step, one record.
 *
 * <h2>Null rejection</h2>
 * <p>A predicate <em>rejects NULL</em> on {@code c} when it can never evaluate to true
 * for a row whose {@code c} is NULL:
 * <ul>
 *   <li>a comparison, a {@code LIKE}, or an {@code ∈} whose operand <em>is</em> {@code c}
 *       — rejects;</li>
 *   <li>{@code c IS NOT NULL} — rejects; <b>{@code c IS NULL} — does not</b>, and that is
 *       the trap: {@code σ B.x IS NULL (A ⟕ B)} <em>is</em> the anti-join idiom, and
 *       demoting it would return the exact opposite rows;</li>
 *   <li>{@code ∧} — rejects if either side does; {@code ∨} — only if both do;</li>
 *   <li>anything <em>computed</em> from {@code c} — a function call, an arithmetic
 *       expression — does not reject, conservatively.  Relix has {@code Nz},
 *       {@code Coalesce} and {@code IIf}, which exist precisely to map NULL to something
 *       truthy.</li>
 * </ul>
 *
 * <p>The {@code ¬} arm is deliberately blunt: only {@code ¬(c IS NULL)} is treated as
 * rejecting.  Under three-valued logic {@code ¬(c = 5)} also rejects — negating
 * <em>unknown</em> leaves it unknown, which is not true — but establishing that in
 * general needs the dual analysis ("can this be <em>false</em> when {@code c} is NULL?")
 * rather than an inversion of this one.  Under-firing costs an optimization; getting it
 * wrong costs the answer.
 *
 * <p>Which side a column belongs to is resolved by {@link JoinSides}, so a reference
 * either input could own never counts as rejecting.
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, SchemaAnnotations, OptimizationContext)} as a static
 * method.
 */
final class OuterJoinDemotionPass {

    private OuterJoinDemotionPass() {}

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Demotes every outer join under a null-rejecting selection in the tree rooted at
     * {@code node}.
     *
     * @param node      root of the tree to transform; must not be null
     * @param queryName display name used in transformation records
     * @param schemas   schema annotations; a join whose inputs are unannotated is skipped
     * @param ctx       transformation record accumulator
     * @return the transformed tree, or {@code node} unchanged when no join demoted
     */
    static RelNode apply(RelNode node, String queryName,
                         SchemaAnnotations schemas, OptimizationContext ctx) {
        return rewrite(node, List.of(), queryName, schemas, ctx);
    }

    // =========================================================================
    // Traversal
    // =========================================================================

    /**
     * Rewrites {@code node}, where {@code fromAbove} are the conjuncts of the whole
     * σ-chain it sits beneath — the chain, not just the nearest σ, because
     * {@code SEL-001} splits a conjunction into stacked selections and which one lands
     * next to the join is then an accident of how the user wrote the {@code ∧}.
     */
    private static RelNode rewrite(RelNode node, List<Predicate> fromAbove, String queryName,
                                   SchemaAnnotations schemas, OptimizationContext ctx) {
        if (node instanceof SelectionNode s) {
            var extended = new ArrayList<>(fromAbove);
            extended.addAll(Predicates.conjuncts(s.predicate()));
            RelNode newInput = rewrite(s.input(), extended, queryName, schemas, ctx);
            return (newInput == s.input()) ? s
                    : new SelectionNode(s.predicate(), newInput, s.location());
        }
        RelNode demoted = (node instanceof ConditionalJoinNode j && isOuter(j))
                ? demote(j, fromAbove, queryName, schemas, ctx)
                : node;
        return demoted.mapChildren(child -> rewrite(child, List.of(), queryName, schemas, ctx));
    }

    private static boolean isOuter(ConditionalJoinNode j) {
        return j instanceof LeftOuterJoinNode
                || j instanceof RightOuterJoinNode
                || j instanceof FullOuterJoinNode;
    }

    // =========================================================================
    // The demotion
    // =========================================================================

    /**
     * Returns the least-outer join equivalent to {@code join} under {@code filters}, or
     * {@code join} unchanged.  Both halves of a {@code ⟗} are decided together, so the
     * result never demotes further.
     */
    private static RelNode demote(ConditionalJoinNode join, List<Predicate> filters,
                                  String queryName, SchemaAnnotations schemas,
                                  OptimizationContext ctx) {
        Optional<Schema> left  = schemas.get(join.left());
        Optional<Schema> right = schemas.get(join.right());
        if (left.isEmpty() || right.isEmpty()) {
            return join;
        }
        boolean rejectsLeft  = anyRejects(filters, JoinSides.Side.LEFT,  left.get(), right.get());
        boolean rejectsRight = anyRejects(filters, JoinSides.Side.RIGHT, left.get(), right.get());

        ConditionalJoinNode demoted = switch (join) {
            // The rows a ⟕ pads carry NULL *right* columns, so it is a predicate on the
            // right that makes them unreachable.
            case LeftOuterJoinNode ignored  -> rejectsRight ? theta(join) : join;
            case RightOuterJoinNode ignored -> rejectsLeft  ? theta(join) : join;
            case FullOuterJoinNode ignored  -> {
                if (rejectsLeft && rejectsRight) yield theta(join);
                if (rejectsRight) yield new RightOuterJoinNode(join.left(), join.right(),
                        join.condition(), join.location());
                if (rejectsLeft)  yield new LeftOuterJoinNode(join.left(), join.right(),
                        join.condition(), join.location());
                yield join;
            }
            default -> join;
        };
        if (demoted == join) {
            return join;
        }
        ctx.record(OptimizationCode.JOIN_004, queryName,
                label(join) + " demoted to " + label(demoted)
                        + " — the filter above rejects NULLs on the padded side",
                join.location());
        return demoted;
    }

    private static ThetaJoinNode theta(ConditionalJoinNode join) {
        return new ThetaJoinNode(join.left(), join.right(), join.condition(), join.location());
    }

    private static String label(RelNode join) {
        return switch (join) {
            case LeftOuterJoinNode ignored  -> "⟕";
            case RightOuterJoinNode ignored -> "⟖";
            case FullOuterJoinNode ignored  -> "⟗";
            case ThetaJoinNode ignored      -> "⨝";
            default                   -> join.getClass().getSimpleName();
        };
    }

    // =========================================================================
    // Null-rejection analysis
    // =========================================================================

    /** Whether any conjunct rejects NULLs on a column belonging to {@code side}. */
    private static boolean anyRejects(List<Predicate> filters, JoinSides.Side side,
                                      Schema left, Schema right) {
        return filters.stream().anyMatch(p -> rejectsNull(p, side, left, right));
    }

    /**
     * Whether {@code p} can never be <em>true</em> for a row whose column from
     * {@code side} is NULL.
     */
    private static boolean rejectsNull(Predicate p, JoinSides.Side side,
                                       Schema left, Schema right) {
        return switch (p) {
            case ComparisonPredicate c ->
                    isSideColumn(c.left(), side, left, right)
                            || isSideColumn(c.right(), side, left, right);
            // `c NOT LIKE …` / `c ∉ {…}` are negated forms; treat them like ¬ and leave
            // them alone rather than reason about how the evaluator handles NULL there.
            case PatternPredicate pat ->
                    !pat.negated() && isSideColumn(pat.operand(), side, left, right);
            case ElementOfPredicate e ->
                    !e.isNegated() && isSideColumn(e.element(), side, left, right);
            // IS NOT NULL rejects; IS NULL is the anti-join idiom and must not.
            case NullPredicate n ->
                    !n.isNull() && isSideColumn(n.operand(), side, left, right);
            case AndPredicate a ->
                    rejectsNull(a.left(), side, left, right)
                            || rejectsNull(a.right(), side, left, right);
            case OrPredicate o ->
                    rejectsNull(o.left(), side, left, right)
                            && rejectsNull(o.right(), side, left, right);
            case NotPredicate n ->
                    n.predicate() instanceof NullPredicate inner && inner.isNull()
                            && isSideColumn(inner.operand(), side, left, right);
        };
    }

    /**
     * Whether {@code operand} is a <em>bare</em> reference to a column of {@code side}.
     * Anything computed from the column — a function call, an arithmetic expression, a
     * struct or array — is not, because the computation may turn a NULL into a value the
     * predicate accepts.
     */
    private static boolean isSideColumn(Operand operand, JoinSides.Side side,
                                        Schema left, Schema right) {
        return operand instanceof AttributeOperand a
                && JoinSides.sideOf(a.name(), left, right) == side;
    }
}

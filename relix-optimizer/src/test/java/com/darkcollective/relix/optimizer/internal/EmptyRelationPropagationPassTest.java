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
import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.AllenRelation;
import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.EmptyRelationNode;
import com.darkcollective.relix.ast.IntersectionNode;
import com.darkcollective.relix.ast.IntervalJoinNode;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;

/**
 * Tests for {@link EmptyRelationPropagationPass} — {@code EMPTY-001} / {@code -002} /
 * {@code -003}.
 */
@DisplayName("EmptyRelationPropagationPass")
final class EmptyRelationPropagationPassTest {

    private OptimizationContext ctx;

    private RelNode run(RelNode node) {
        ctx = new OptimizationContext();
        return EmptyRelationPropagationPass.apply(node, "Q", SchemaAnnotations.empty(), ctx);
    }

    private boolean fired(OptimizationCode code) {
        return ctx.records().stream().anyMatch(t -> t.code() == code);
    }

    /** A selection whose predicate is the canonical constant-false. */
    private static SelectionNode falseFilter(String relation) {
        return select(PredicateSimplifier.constant(false), rel(relation));
    }

    /** An empty relation standing in for a collapsed sub-tree over {@code relation}. */
    private static EmptyRelationNode empty(String relation) {
        return EmptyRelationNode.of(rel(relation));
    }

    /** An interval join; only its two inputs matter to this pass. */
    private static IntervalJoinNode intervalJoin(RelNode left, RelNode right) {
        return AstBuilders.intervalJoin(left, right, AllenRelation.INTERSECTS,
                "a_start", "a_end", "b_start", "b_end");
    }

    /** A join condition; its content is irrelevant to this pass, only the operator is. */
    private static Predicate joinOn() {
        return cmp(attr("A.k"),
                ComparisonOperator.EQUAL, attr("B.k"));
    }

    // =========================================================================
    // EMPTY-001
    // =========================================================================

    @Nested
    @DisplayName("EMPTY-001 — σ false (R) → ∅")
    class Introduction {

        @Test
        @DisplayName("a constant-false selection becomes the empty relation")
        void constantFalseCollapses() {
            RelNode result = run(falseFilter("R"));
            assertThat(result).isNode(EmptyRelationNode.class);
            assertThat(fired(OptimizationCode.EMPTY_001)).isTrue();
        }

        @Test
        @DisplayName("the ∅ keeps the heading of the selection it replaced")
        void keepsTheHeading() {
            SelectionNode filter = falseFilter("R");
            RelNode result = run(filter);
            assertThat(((EmptyRelationNode) result).heading()).isSameAs(filter);
        }

        @Test
        @DisplayName("a satisfiable selection is untouched")
        void satisfiableUntouched() {
            RelNode filter = select(
                    cmp(attr("x"),
                            ComparisonOperator.GREATER, num("5")),
                    rel("R"));
            assertThat(run(filter)).isSameAs(filter);
            assertThat(ctx.records()).isEmpty();
        }

        @Test
        @DisplayName("a constant-TRUE selection is not collapsed")
        void constantTrueUntouched() {
            RelNode filter = select(PredicateSimplifier.constant(true),
                    rel("R"));
            assertThat(run(filter)).isSameAs(filter);
        }

        @Test
        @DisplayName("re-applying the pass is a no-op — ∅ is never re-wrapped")
        void idempotent() {
            RelNode once = run(falseFilter("R"));
            RelNode twice = run(once);
            assertThat(twice).isSameAs(once);
            assertThat(ctx.records()).isEmpty();
        }
    }

    // =========================================================================
    // EMPTY-002 — unary
    // =========================================================================

    @Nested
    @DisplayName("EMPTY-002 — unary operators")
    class Unary {

        @Test
        @DisplayName("π, δ and σ over ∅ all collapse")
        void unaryCollapses() {
            assertThat(run(project(
                    List.of(ProjectedAttribute.simple(attr("a"))), empty("R"))))
                    .isNode(EmptyRelationNode.class);
            assertThat(run(distinct(empty("R")))).isNode(EmptyRelationNode.class);
            assertThat(run(select(
                    cmp(attr("x"),
                            ComparisonOperator.GREATER, num("1")),
                    empty("R")))).isNode(EmptyRelationNode.class);
        }

        @Test
        @DisplayName("emptiness travels from a leaf to the root in one traversal")
        void propagatesThroughAStack() {
            RelNode tree = distinct(project(
                    List.of(ProjectedAttribute.simple(attr("a"))), falseFilter("R")));
            assertThat(run(tree)).isNode(EmptyRelationNode.class);
            assertThat(fired(OptimizationCode.EMPTY_001)).isTrue();
            assertThat(fired(OptimizationCode.EMPTY_002)).isTrue();
        }

        @Test
        @DisplayName("a grouped γ over ∅ collapses")
        void groupedAggregationCollapses() {
            RelNode agg = groupBy(List.of("k"),
                    List.of(AggregateFunction.simple(AggregateOperator.SUM, "v")), empty("R"));
            assertThat(run(agg)).isNode(EmptyRelationNode.class);
        }

        @Test
        @DisplayName("a SCALAR γ over ∅ does NOT collapse — COUNT of nothing is a row saying zero")
        void scalarAggregationDoesNotCollapse() {
            RelNode agg = groupBy(List.of(),
                    List.of(AggregateFunction.simple(AggregateOperator.COUNT, "v")), empty("R"));
            assertThat(run(agg)).isSameAs(agg);
            assertThat(ctx.records()).isEmpty();
        }

        @Test
        @DisplayName("a no-key ∀ over ∅ does NOT collapse — it is vacuously true")
        void noKeyUniversalDoesNotCollapse() {
            Predicate p = cmp(attr("x"),
                    ComparisonOperator.GREATER, num("0"));
            RelNode all = universal(List.of(), p, empty("R"));
            assertThat(run(all)).isSameAs(all);
        }

        @Test
        @DisplayName("a grouped ∀ over ∅ does collapse")
        void groupedUniversalCollapses() {
            Predicate p = cmp(attr("x"),
                    ComparisonOperator.GREATER, num("0"));
            RelNode all = universal(List.of("k"), p, empty("R"));
            assertThat(run(all)).isNode(EmptyRelationNode.class);
        }
    }

    // =========================================================================
    // EMPTY-002 — joins
    // =========================================================================

    @Nested
    @DisplayName("EMPTY-002 — joins")
    class Joins {

        @Test
        @DisplayName("⋈ collapses from either side")
        void naturalJoinEitherSide() {
            assertThat(run(naturalJoin(empty("A"), rel("B"))))
                    .isNode(EmptyRelationNode.class);
            assertThat(run(naturalJoin(rel("A"), empty("B"))))
                    .isNode(EmptyRelationNode.class);
        }

        @Test
        @DisplayName("⟕ collapses on an empty LEFT only — an empty right pads instead")
        void leftOuterJoin() {
            assertThat(run(leftJoin(empty("A"), rel("B"), joinOn())))
                    .isNode(EmptyRelationNode.class);
            RelNode rightEmpty = leftJoin(rel("A"), empty("B"), joinOn());
            assertThat(run(rightEmpty)).isSameAs(rightEmpty);
        }

        @Test
        @DisplayName("⟖ is the mirror of ⟕")
        void rightOuterJoin() {
            assertThat(run(rightJoin(rel("A"), empty("B"), joinOn())))
                    .isNode(EmptyRelationNode.class);
            RelNode leftEmpty = rightJoin(empty("A"), rel("B"), joinOn());
            assertThat(run(leftEmpty)).isSameAs(leftEmpty);
        }

        @Test
        @DisplayName("⟗ needs both sides empty")
        void fullOuterJoin() {
            RelNode oneSide = fullJoin(empty("A"), rel("B"), joinOn());
            assertThat(run(oneSide)).isSameAs(oneSide);
            assertThat(run(fullJoin(empty("A"), empty("B"), joinOn())))
                    .isNode(EmptyRelationNode.class);
        }

        @Test
        @DisplayName("⋉ collapses from either side — an empty right matches nothing")
        void semiJoin() {
            assertThat(run(AstBuilders.semiJoin(empty("A"), rel("B"), joinOn())))
                    .isNode(EmptyRelationNode.class);
            assertThat(run(AstBuilders.semiJoin(rel("A"), empty("B"), joinOn())))
                    .isNode(EmptyRelationNode.class);
        }

        @Test
        @DisplayName("▷ collapses on an empty LEFT only — an empty right keeps every left row")
        void antiJoin() {
            assertThat(run(AstBuilders.antiJoin(empty("A"), rel("B"), joinOn())))
                    .isNode(EmptyRelationNode.class);
            RelNode rightEmpty = AstBuilders.antiJoin(rel("A"), empty("B"), joinOn());
            assertThat(run(rightEmpty)).isSameAs(rightEmpty);
        }

        @Test
        @DisplayName("× collapses from either side — nothing to pair with is no pairs")
        void product() {
            assertThat(run(AstBuilders.product(empty("A"), rel("B"))))
                    .isNode(EmptyRelationNode.class);
            assertThat(run(AstBuilders.product(rel("A"), empty("B"))))
                    .isNode(EmptyRelationNode.class);
        }

        @Test
        @DisplayName("⨝ collapses from either side")
        void thetaJoin() {
            assertThat(run(join(empty("A"), rel("B"), joinOn())))
                    .isNode(EmptyRelationNode.class);
            assertThat(run(join(rel("A"), empty("B"), joinOn())))
                    .isNode(EmptyRelationNode.class);
        }

        @Test
        @DisplayName("∘ collapses from either side — a composition needs both relations")
        void composition() {
            assertThat(run(AstBuilders.composition(empty("A"), rel("B"))))
                    .isNode(EmptyRelationNode.class);
            assertThat(run(AstBuilders.composition(rel("A"), empty("B"))))
                    .isNode(EmptyRelationNode.class);
        }

        @Test
        @DisplayName("an interval join collapses from either side")
        void intervalJoinEitherSide() {
            assertThat(run(intervalJoin(empty("A"), rel("B"))))
                    .isNode(EmptyRelationNode.class);
            assertThat(run(intervalJoin(rel("A"), empty("B"))))
                    .isNode(EmptyRelationNode.class);
        }

        @Test
        @DisplayName("AS-OF collapses on an empty LEFT only — an empty right pads, as ⟕ does")
        void asOfJoin() {
            assertThat(run(AstBuilders.asOfJoin(empty("A"), rel("B"), joinOn())))
                    .isNode(EmptyRelationNode.class);
            RelNode rightEmpty = AstBuilders.asOfJoin(rel("A"), empty("B"), joinOn());
            assertThat(run(rightEmpty)).isSameAs(rightEmpty);
        }

        @Test
        @DisplayName("LATERAL collapses on an empty LEFT — its body runs per left row")
        void lateralJoin() {
            assertThat(run(lateral(empty("A"), "explode")))
                    .isNode(EmptyRelationNode.class);
            RelNode nonEmptyLeft = lateral(rel("A"), "explode");
            assertThat(run(nonEmptyLeft)).isSameAs(nonEmptyLeft);
        }

        @Test
        @DisplayName("a join with neither side empty is left alone")
        void neitherSideEmpty() {
            // The short-circuiting first test, reached only when the left is non-empty.
            List<RelNode> untouched = List.of(
                    AstBuilders.product(rel("A"), rel("B")),
                    join(rel("A"), rel("B"), joinOn()),
                    AstBuilders.composition(rel("A"), rel("B")),
                    intervalJoin(rel("A"), rel("B")),
                    unionAll(rel("A"), rel("B")),
                    symmetricDifference(rel("A"), rel("B")));
            assertThat(untouched).allSatisfy(node -> assertThat(run(node)).isSameAs(node));
        }

        @Test
        @DisplayName("an interval join with both sides empty collapses once, not twice")
        void intervalJoinBothEmpty() {
            assertThat(run(intervalJoin(empty("A"), empty("B"))))
                    .isNode(EmptyRelationNode.class);
        }

        @Test
        @DisplayName("⟗ is not collapsed by an empty RIGHT either — the mirror of the left case")
        void fullOuterJoinRightOnly() {
            // The && is short-circuiting, so an empty right alone is only reached
            // through the arm a left-empty test never enters.
            RelNode rightOnly = fullJoin(rel("A"), empty("B"), joinOn());
            assertThat(run(rightOnly)).isSameAs(rightOnly);
        }
    }

    // =========================================================================
    // EMPTY-002 / EMPTY-003 — set operations
    // =========================================================================

    @Nested
    @DisplayName("EMPTY-002/003 — set operations")
    class SetOps {

        @Test
        @DisplayName("∩ collapses from either side")
        void intersection() {
            assertThat(run(AstBuilders.intersection(empty("A"), rel("B"))))
                    .isNode(EmptyRelationNode.class);
            assertThat(run(AstBuilders.intersection(rel("A"), empty("B"))))
                    .isNode(EmptyRelationNode.class);
        }

        @Test
        @DisplayName("− collapses on an empty LEFT; X − ∅ is deliberately left alone")
        void difference() {
            assertThat(run(AstBuilders.difference(empty("A"), rel("B"))))
                    .isNode(EmptyRelationNode.class);
            // X − ∅ → X would be row-correct against today's executor but would break
            // the SET distinctness that DIST-001 already consumes. See the pass Javadoc.
            RelNode rightEmpty = AstBuilders.difference(rel("A"), empty("B"));
            assertThat(run(rightEmpty)).isSameAs(rightEmpty);
        }

        @Test
        @DisplayName("÷ collapses on an empty LEFT only — an empty divisor is vacuously satisfied")
        void division() {
            assertThat(run(AstBuilders.division(empty("A"), rel("B"))))
                    .isNode(EmptyRelationNode.class);
            RelNode emptyDivisor = AstBuilders.division(rel("A"), empty("B"));
            assertThat(run(emptyDivisor)).isSameAs(emptyDivisor);
        }

        @Test
        @DisplayName("∪ needs both sides empty — dropping one branch would drop its dedup too")
        void union() {
            RelNode oneSide = AstBuilders.union(empty("A"), rel("B"));
            assertThat(run(oneSide)).isSameAs(oneSide);
            assertThat(run(AstBuilders.union(empty("A"), empty("B"))))
                    .isNode(EmptyRelationNode.class);
        }

        @Test
        @DisplayName("EMPTY-003 — X ⊎ ∅ becomes X")
        void bagUnionRightEmptyDropped() {
            RelationNode left = rel("A");
            assertThat(run(unionAll(left, empty("B")))).isSameAs(left);
            assertThat(fired(OptimizationCode.EMPTY_003)).isTrue();
        }

        @Test
        @DisplayName("∅ ⊎ X is NOT rewritten — ⊎ takes its schema from the left branch")
        void bagUnionLeftEmptyKept() {
            RelNode leftEmpty = unionAll(empty("A"), rel("B"));
            assertThat(run(leftEmpty)).isSameAs(leftEmpty);
            assertThat(ctx.records()).isEmpty();
        }

        @Test
        @DisplayName("⊎ with both branches empty collapses to ∅")
        void bagUnionBothEmpty() {
            // EMPTY-003 fires first and yields the left branch, which is already ∅.
            assertThat(run(unionAll(empty("A"), empty("B"))))
                    .isNode(EmptyRelationNode.class);
        }

        @Test
        @DisplayName("a ⊎ of two live branches is not empty, asked from above")
        void bagUnionOfLiveBranchesIsNotEmpty() {
            // EMPTY-003 rewrites `X ⊎ ∅` away before anything can ask whether it is
            // empty, so the only way to put that question to a ⊎ with a live left branch
            // is from an operator above it. ∩ is the one that asks: it is empty when
            // either side is, so it reads the ⊎ first and must be told no.
            RelNode live = unionAll(rel("A"), rel("B"));
            assertThat(run(AstBuilders.intersection(live, empty("C"))))
                    .as("the ∩ is empty because its right side is, not because the ⊎ is")
                    .isNode(EmptyRelationNode.class);
        }

        @Test
        @DisplayName("a nested ⊎ with an empty right branch is rewritten before anything asks about it")
        void bagUnionEmptyRightRewrittenBeneath() {
            // Why the ⊎ arm of the emptiness classifier can never see an empty right
            // branch: EMPTY-003 reaches the node first, bottom-up, and replaces it with
            // its left branch. An operator above it is therefore asking about the branch,
            // never about the union.
            RelNode result = run(AstBuilders.intersection(unionAll(rel("A"), empty("B")), rel("C")));

            assertThat(result).isNode(IntersectionNode.class);
            assertThat(((IntersectionNode) result).left())
                    .as("the ⊎ is gone, replaced by the branch that had rows")
                    .isNode(RelationNode.class);
            assertThat(fired(OptimizationCode.EMPTY_003)).isTrue();
        }

        @Test
        @DisplayName("∆ needs both sides empty — each side contributes what the other lacks")
        void symmetricDifference() {
            RelNode leftOnly = AstBuilders.symmetricDifference(empty("A"), rel("B"));
            assertThat(run(leftOnly)).as("∅ ∆ X is X's rows").isSameAs(leftOnly);
            RelNode rightOnly = AstBuilders.symmetricDifference(rel("A"), empty("B"));
            assertThat(run(rightOnly)).as("X ∆ ∅ is X's rows").isSameAs(rightOnly);
            assertThat(run(AstBuilders.symmetricDifference(empty("A"), empty("B"))))
                    .isNode(EmptyRelationNode.class);
        }

        @Test
        @DisplayName("⊔ needs both sides empty")
        void outerUnion() {
            RelNode leftOnly = AstBuilders.outerUnion(empty("A"), rel("B"));
            assertThat(run(leftOnly)).isSameAs(leftOnly);
            RelNode rightOnly = AstBuilders.outerUnion(rel("A"), empty("B"));
            assertThat(run(rightOnly)).isSameAs(rightOnly);
            assertThat(run(AstBuilders.outerUnion(empty("A"), empty("B"))))
                    .isNode(EmptyRelationNode.class);
        }

        @Test
        @DisplayName("∪ is not collapsed by an empty RIGHT either — the mirror of the left case")
        void unionRightOnly() {
            RelNode rightOnly = AstBuilders.union(rel("A"), empty("B"));
            assertThat(run(rightOnly)).isSameAs(rightOnly);
        }
    }
}

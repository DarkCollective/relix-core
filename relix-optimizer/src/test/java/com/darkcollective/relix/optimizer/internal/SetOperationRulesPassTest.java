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
import com.darkcollective.relix.optimizer.OptimizerFixtures;
import com.darkcollective.relix.ast.internal.AstEquivalence;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.DistinctNode;
import com.darkcollective.relix.ast.EmptyRelationNode;
import com.darkcollective.relix.ast.NotPredicate;
import com.darkcollective.relix.ast.NullPredicate;
import com.darkcollective.relix.ast.OrPredicate;
import com.darkcollective.relix.ast.ConditionOperand;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProductNode;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.UnionNode;
import com.darkcollective.relix.cost.DistinctnessSource;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.attr;
import static com.darkcollective.relix.ast.AstBuilders.cmp;
import static com.darkcollective.relix.ast.AstBuilders.difference;
import static com.darkcollective.relix.ast.AstBuilders.distinct;
import static com.darkcollective.relix.ast.AstBuilders.intersection;
import static com.darkcollective.relix.ast.AstBuilders.num;
import static com.darkcollective.relix.ast.AstBuilders.product;
import static com.darkcollective.relix.ast.AstBuilders.project;
import static com.darkcollective.relix.ast.AstBuilders.projected;
import static com.darkcollective.relix.ast.AstBuilders.rel;
import static com.darkcollective.relix.ast.AstBuilders.rename;
import static com.darkcollective.relix.ast.AstBuilders.sample;
import static com.darkcollective.relix.ast.AstBuilders.select;
import static com.darkcollective.relix.ast.AstBuilders.symmetricDifference;
import static com.darkcollective.relix.ast.AstBuilders.union;
import static com.darkcollective.relix.ast.AstBuilders.unionAll;
import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;

@DisplayName("Set-operation identities — SET-001..005")
final class SetOperationRulesPassTest {

    private OptimizationContext ctx;

    @BeforeEach
    void setUp() {
        ctx = OptimizerFixtures.reproducible();
    }

    private RelNode apply(RelNode node) {
        return apply(node, SchemaAnnotations.empty());
    }

    private RelNode apply(RelNode node, SchemaAnnotations schemas) {
        return SetOperationRulesPass.apply(node, "Q", schemas, ctx);
    }

    private int firings(OptimizationCode code) {
        return ctx.recordsFor(code).size();
    }

    private static Predicate open() {
        return cmp(attr("amount"), ComparisonOperator.GREATER, num("0"));
    }

    private static Predicate large() {
        return cmp(attr("amount"), ComparisonOperator.GREATER, num("100"));
    }

    /** The relation named twice, as two separate but structurally identical nodes. */
    private static RelNode orders() {
        return rel("Orders");
    }

    // =========================================================================
    // SET-001 — the two sides are the same expression
    // =========================================================================

    @Nested
    @DisplayName("SET-001 — R ⊕ R")
    class Idempotence {

        @Test
        @DisplayName("R ∪ R → δ R, the two sides matched though they are separate nodes")
        void unionOfItself() {
            RelNode result = apply(union(orders(), orders()));

            assertThat(result).isNode(DistinctNode.class);
            assertThat(((DistinctNode) result).input()).isNode(RelationNode.class);
            assertThat(firings(OptimizationCode.SET_001)).isEqualTo(1);
        }

        @Test
        @DisplayName("R ∩ R → δ R")
        void intersectionOfItself() {
            assertThat(apply(intersection(orders(), orders()))).isNode(DistinctNode.class);
            assertThat(firings(OptimizationCode.SET_001)).isEqualTo(1);
        }

        @Test
        @DisplayName("the δ is dropped when the input is already duplicate-free")
        void distinctInputNeedsNoDelta() {
            RelNode distinctInput = distinct(orders());

            RelNode result = apply(union(distinctInput, distinct(orders())));

            // δ(δ R) would be the lazy answer; the rule asks PropertyDeriver instead.
            assertThat(result).isSameAs(distinctInput);
            assertThat(firings(OptimizationCode.SET_001)).isEqualTo(1);
        }

        @Test
        @DisplayName("R − R → ∅ carrying the difference's own heading")
        void differenceOfItself() {
            RelNode node = difference(orders(), orders());

            RelNode result = apply(node);

            assertThat(result).isNode(EmptyRelationNode.class);
            assertThat(((EmptyRelationNode) result).heading()).isSameAs(node);
            assertThat(firings(OptimizationCode.SET_001)).isEqualTo(1);
        }

        @Test
        @DisplayName("R ∆ R → ∅")
        void symmetricDifferenceOfItself() {
            assertThat(apply(symmetricDifference(orders(), orders())))
                    .isNode(EmptyRelationNode.class);
            assertThat(firings(OptimizationCode.SET_001)).isEqualTo(1);
        }

        @Test
        @DisplayName("a whole sub-tree matches, not just a leaf")
        void matchesASubtree() {
            assertThat(apply(union(select(open(), orders()), select(open(), orders()))))
                    .isNode(DistinctNode.class);
            assertThat(firings(OptimizationCode.SET_001)).isEqualTo(1);
        }

        @Test
        @DisplayName("⊎ is excluded — a bag union's multiplicities are the answer")
        void bagUnionExcluded() {
            RelNode node = unionAll(orders(), orders());

            assertThat(apply(node)).isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SET_001);
        }

        @Test
        @DisplayName("an unseeded SAMPLE makes the two sides different relations")
        void volatileInputDeclines() {
            ctx = OptimizerFixtures.context(DistinctnessSource.NONE,
                    // the answer RelationDeterminism gives for an unseeded sample
                    expression -> false);
            RelNode node = union(sample(0.5, orders()), sample(0.5, orders()));

            assertThat(apply(node)).isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SET_001);
        }

        @Test
        @DisplayName("two genuinely different relations are left alone")
        void differentSidesDecline() {
            RelNode node = union(rel("Orders"), rel("Recent"));

            assertThat(apply(node)).isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SET_001);
        }
    }

    // =========================================================================
    // SET-002 — a selection against its own input
    // =========================================================================

    @Nested
    @DisplayName("SET-002 — σk(R) ⊕ R")
    class Absorption {

        @Test
        @DisplayName("σk(R) ∪ R → δ R")
        void unionKeepsTheInput() {
            RelNode result = apply(union(select(open(), orders()), orders()));

            assertThat(result).isNode(DistinctNode.class);
            assertThat(((DistinctNode) result).input())
                    .isNode(RelationNode.class);
            assertThat(firings(OptimizationCode.SET_002)).isEqualTo(1);
        }

        @Test
        @DisplayName("R ∪ σk(R) → δ R — the operand order does not matter for ∪")
        void unionEitherWayRound() {
            RelNode result = apply(union(orders(), select(open(), orders())));

            assertThat(result).isNode(DistinctNode.class);
            assertThat(((DistinctNode) result).input())
                    .isNode(RelationNode.class);
            assertThat(firings(OptimizationCode.SET_002)).isEqualTo(1);
        }

        @Test
        @DisplayName("σk(R) ∩ R → δ σk(R) — the filter is the narrower side")
        void intersectionKeepsTheSelection() {
            RelNode result = apply(intersection(select(open(), orders()), orders()));

            assertThat(result).isNode(DistinctNode.class);
            assertThat(((DistinctNode) result).input())
                    .isNode(SelectionNode.class);
            assertThat(firings(OptimizationCode.SET_002)).isEqualTo(1);
        }

        @Test
        @DisplayName("R ∩ σk(R) → δ σk(R)")
        void intersectionEitherWayRound() {
            RelNode result = apply(intersection(orders(), select(open(), orders())));

            assertThat(result).isNode(DistinctNode.class);
            assertThat(((DistinctNode) result).input())
                    .isNode(SelectionNode.class);
            assertThat(firings(OptimizationCode.SET_002)).isEqualTo(1);
        }

        @Test
        @DisplayName("σk(R) − R → ∅")
        void differenceOfASelectionFromItsInput() {
            assertThat(apply(difference(select(open(), orders()), orders())))
                    .isNode(EmptyRelationNode.class);
            assertThat(firings(OptimizationCode.SET_002)).isEqualTo(1);
        }

        @Test
        @DisplayName("R − σk(R) → δ σ (¬k ∨ k IS UNKNOWN) (R), not δ σ ¬k (R)")
        void reverseDifferenceIsTheComplement() {
            RelNode result = apply(difference(orders(), select(open(), orders())));

            assertThat(result).isNode(DistinctNode.class);
            var complement = (OrPredicate)
                    ((SelectionNode) ((DistinctNode) result).input()).predicate();
            // The second disjunct is what a bare ¬k would have lost: the rows whose
            // k could not be decided, which the subtrahend also failed to hold.
            assertThat(complement.left()).isInstanceOf(NotPredicate.class);
            assertThat(((NullPredicate) complement.right()).operand())
                    .isInstanceOf(ConditionOperand.class);
            assertThat(firings(OptimizationCode.SET_002)).isEqualTo(1);
        }

        @Test
        @DisplayName("σk(R) ∆ R is that same difference, in either operand order")
        void symmetricDifferenceIsTheComplement() {
            for (RelNode node : List.of(
                    symmetricDifference(select(open(), orders()), orders()),
                    symmetricDifference(orders(), select(open(), orders())))) {
                setUp();
                RelNode result = apply(node);

                assertThat(result).isNode(DistinctNode.class);
                assertThat(((SelectionNode) ((DistinctNode) result).input()).predicate())
                        .isInstanceOf(OrPredicate.class);
                assertThat(firings(OptimizationCode.SET_002)).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("a volatile bare side declines though the σ above it reads reproducible")
        void volatileBareSideDeclines() {
            RelNode bare = orders();
            ctx = OptimizerFixtures.context(DistinctnessSource.NONE,
                    expression -> !AstEquivalence.equivalent(expression, bare));
            RelNode node = union(select(open(), orders()), bare);

            assertThat(apply(node)).isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SET_002);
        }

        @Test
        @DisplayName("a σ over a different relation is not absorption")
        void unrelatedSelectionDeclines() {
            RelNode node = union(select(open(), rel("Recent")), orders());

            assertThat(apply(node)).isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SET_002);
        }
    }

    // =========================================================================
    // SET-003 — a common rename hoisted out
    // =========================================================================

    @Nested
    @DisplayName("SET-003 — ρ hoisted out of a set operation")
    class RenameHoisting {

        private RenameNode renamed(RelNode input) {
            return rename(Optional.of("E"), List.of(),
                    List.of(new RenameNode.RenamePair("cust", "customer")), input);
        }

        @Test
        @DisplayName("ρ s (R) ∪ ρ s (Q) → ρ s (R ∪ Q), with no δ — a rename dedups nothing")
        void hoistedOutOfUnion() {
            RelNode result = apply(union(renamed(rel("Orders")), renamed(rel("Recent"))));

            assertThat(result).isNode(RenameNode.class);
            RenameNode hoisted = (RenameNode) result;
            assertThat(hoisted.pairs()).hasSize(1);
            assertThat(hoisted.input()).isNode(UnionNode.class);
            assertThat(firings(OptimizationCode.SET_003)).isEqualTo(1);
        }

        @Test
        @DisplayName("also out of ∩, −, ∆ and ⊎")
        void hoistedOutOfEveryArm() {
            for (RelNode node : List.of(
                    intersection(renamed(rel("Orders")), renamed(rel("Recent"))),
                    difference(renamed(rel("Orders")), renamed(rel("Recent"))),
                    symmetricDifference(renamed(rel("Orders")), renamed(rel("Recent"))),
                    unionAll(renamed(rel("Orders")), renamed(rel("Recent"))))) {
                setUp();
                assertThat(apply(node)).isNode(RenameNode.class);
                assertThat(firings(OptimizationCode.SET_003)).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("two renames that say different things are not one common operator")
        void differingSpecsDecline() {
            RelNode node = union(
                    rename(Optional.of("E"), List.of(),
                            List.of(new RenameNode.RenamePair("cust", "customer")), rel("Orders")),
                    rename(Optional.of("F"), List.of(),
                            List.of(new RenameNode.RenamePair("cust", "customer")), rel("Recent")));

            assertThat(apply(node)).isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SET_003);
        }

        @Test
        @DisplayName("same relation name, different positional attributes — not one operator")
        void differingAttributesDecline() {
            RelNode node = union(
                    rename("E", List.of("a", "b"), rel("Orders")),
                    rename("E", List.of("x", "y"), rel("Recent")));

            assertThat(apply(node)).isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SET_003);
        }

        @Test
        @DisplayName("same relation name, different pairs — not one operator either")
        void differingPairsDecline() {
            RelNode node = union(
                    rename(Optional.of("E"), List.of(),
                            List.of(new RenameNode.RenamePair("cust", "customer")), rel("Orders")),
                    rename(Optional.of("E"), List.of(),
                            List.of(new RenameNode.RenamePair("cust", "buyer")), rel("Recent")));

            assertThat(apply(node)).isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SET_003);
        }

        @Test
        @DisplayName("the positional form hoists too — both sides keep the same arity")
        void positionalForm() {
            RelNode node = union(
                    rename("E", List.of("a", "b"), rel("Orders")),
                    rename("E", List.of("a", "b"), rel("Recent")));

            assertThat(apply(node)).isNode(RenameNode.class);
            assertThat(firings(OptimizationCode.SET_003)).isEqualTo(1);
        }
    }

    // =========================================================================
    // SET-004 — a common projection hoisted out of a union
    // =========================================================================

    @Nested
    @DisplayName("SET-004 — π hoisted out of a union")
    class ProjectionHoisting {

        private final RelNode left  = rel("Orders");
        private final RelNode right = rel("Recent");

        private RelNode projection(RelNode input) {
            return project(List.of(projected(attr("cust"))), input);
        }

        private SchemaAnnotations headings(Schema leftSchema, Schema rightSchema) {
            return new SchemaAnnotations(Map.of(left, leftSchema, right, rightSchema));
        }

        private Schema of(String... names) {
            return new Schema(Arrays.stream(names)
                    .map(n -> new ColumnDefinition(n, ScalarType.STRING)).toList());
        }

        @Test
        @DisplayName("π c (A) ∪ π c (B) → δ π c (A ∪ B) when the two headings agree")
        void hoisted() {
            RelNode node = union(projection(left), projection(right));

            RelNode result = apply(node, headings(of("cust", "amount"), of("cust", "amount")));

            assertThat(result).isNode(DistinctNode.class);
            RelNode projected = ((DistinctNode) result).input();
            assertThat(projected).isNode(ProjectionNode.class);
            assertThat(((ProjectionNode) projected).input()).isNode(UnionNode.class);
            assertThat(firings(OptimizationCode.SET_004)).isEqualTo(1);
        }

        @Test
        @DisplayName("headings naming the same columns in a DIFFERENT order decline")
        void headingsOutOfOrderDecline() {
            // The union pairs columns by position and the π names them: hoisting here
            // would project B's `amount` under the name `cust`.
            RelNode node = union(projection(left), projection(right));

            assertThat(apply(node, headings(of("cust", "amount"), of("amount", "cust"))))
                    .isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SET_004);
        }

        @Test
        @DisplayName("an un-annotated input declines rather than guesses")
        void missingHeadingDeclines() {
            RelNode node = union(projection(left), projection(right));

            assertThat(apply(node, SchemaAnnotations.empty())).isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SET_004);
        }

        @Test
        @DisplayName("an open, schema-on-read heading declines — its columns are unknowable")
        void openHeadingDeclines() {
            RelNode node = union(projection(left), projection(right));

            assertThat(apply(node, headings(Schema.open(), Schema.open()))).isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SET_004);
        }

        @Test
        @DisplayName("only one branch is a π")
        void oneBranchOnlyDeclines() {
            RelNode node = union(projection(left), right);

            assertThat(apply(node, headings(of("cust", "amount"), of("cust", "amount"))))
                    .isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SET_004);
        }

        @Test
        @DisplayName("one input annotated and the other not still declines")
        void oneHeadingMissingDeclines() {
            RelNode node = union(projection(left), projection(right));
            var onlyLeft = new SchemaAnnotations(Map.of(left, of("cust", "amount")));

            assertThat(apply(node, onlyLeft)).isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SET_004);
        }

        @Test
        @DisplayName("one closed heading and one open one declines")
        void oneOpenHeadingDeclines() {
            RelNode node = union(projection(left), projection(right));

            assertThat(apply(node, headings(of("cust", "amount"), Schema.open())))
                    .isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SET_004);
        }

        @Test
        @DisplayName("two different projections are not one common operator")
        void differingProjectionsDecline() {
            RelNode node = union(projection(left),
                    project(List.of(projected(attr("amount"))), right));

            assertThat(apply(node, headings(of("cust", "amount"), of("cust", "amount"))))
                    .isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SET_004);
        }
    }

    // =========================================================================
    // SET-005 — a common product operand hoisted out of a union
    // =========================================================================

    @Nested
    @DisplayName("SET-005 — × hoisted out of a union")
    class ProductHoisting {

        @Test
        @DisplayName("A × B ∪ A × C → δ (A × (B ∪ C))")
        void commonLeftOperand() {
            RelNode result = apply(union(
                    product(rel("A"), rel("B")),
                    product(rel("A"), rel("C"))));

            assertThat(result).isNode(DistinctNode.class);
            RelNode hoisted = ((DistinctNode) result).input();
            assertThat(hoisted).isNode(ProductNode.class);
            ProductNode p = (ProductNode) hoisted;
            assertThat(p.left()).isNode(RelationNode.class);
            assertThat(p.right()).isNode(UnionNode.class);
            assertThat(firings(OptimizationCode.SET_005)).isEqualTo(1);
        }

        @Test
        @DisplayName("A × C ∪ B × C → δ ((A ∪ B) × C)")
        void commonRightOperand() {
            RelNode result = apply(union(
                    product(rel("A"), rel("C")),
                    product(rel("B"), rel("C"))));

            assertThat(result).isNode(DistinctNode.class);
            ProductNode p = (ProductNode) ((DistinctNode) result).input();
            assertThat(p.left()).isNode(UnionNode.class);
            assertThat(p.right()).isNode(RelationNode.class);
            assertThat(firings(OptimizationCode.SET_005)).isEqualTo(1);
        }

        @Test
        @DisplayName("A × B ∪ C × A declines — factoring it would permute the heading")
        void oppositeSidesDecline() {
            RelNode node = union(
                    product(rel("A"), rel("B")),
                    product(rel("C"), rel("A")));

            assertThat(apply(node)).isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SET_005);
        }

        @Test
        @DisplayName("a volatile common operand declines — it would be drawn once, not twice")
        void volatileOperandDeclines() {
            ctx = OptimizerFixtures.context(DistinctnessSource.NONE, expression -> false);
            RelNode node = union(
                    product(sample(0.5, rel("A")), rel("B")),
                    product(sample(0.5, rel("A")), rel("C")));

            assertThat(apply(node)).isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SET_005);
        }

        @Test
        @DisplayName("only one branch is a × ")
        void oneBranchOnlyDeclines() {
            RelNode node = union(product(rel("A"), rel("B")), rel("C"));

            assertThat(apply(node)).isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SET_005);
        }

        @Test
        @DisplayName("a volatile shared RIGHT operand declines")
        void volatileRightOperandDeclines() {
            RelNode shared = rel("C");
            ctx = OptimizerFixtures.context(DistinctnessSource.NONE,
                    expression -> !AstEquivalence.equivalent(expression, shared));
            RelNode node = union(product(rel("A"), rel("C")), product(rel("B"), rel("C")));

            assertThat(apply(node)).isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SET_005);
        }

        @Test
        @DisplayName("no shared operand at all declines")
        void nothingInCommonDeclines() {
            RelNode node = union(product(rel("A"), rel("B")), product(rel("C"), rel("D")));

            assertThat(apply(node)).isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SET_005);
        }
    }

    // =========================================================================
    // Traversal
    // =========================================================================

    @Nested
    @DisplayName("traversal")
    class Traversal {

        @Test
        @DisplayName("a chain collapses fully in one bottom-up pass")
        void chainCollapses() {
            RelNode result = apply(union(union(orders(), orders()), union(orders(), orders())));

            // Each inner union becomes δ R; the outer then sees two equivalent sides.
            assertThat(result).isNode(DistinctNode.class);
            assertThat(firings(OptimizationCode.SET_001)).isEqualTo(3);
        }

        @Test
        @DisplayName("a set operation buried under another operator is still rewritten")
        void firesBelowAnotherOperator() {
            RelNode result = apply(select(large(), union(orders(), orders())));

            assertThat(result).isNode(SelectionNode.class);
            assertThat(((SelectionNode) result).input())
                    .isNode(DistinctNode.class);
            assertThat(firings(OptimizationCode.SET_001)).isEqualTo(1);
        }

        @Test
        @DisplayName("a tree with no set operation is returned unchanged")
        void noSetOperation() {
            RelNode node = select(open(), rel("Orders"));

            assertThat(apply(node)).isSameAs(node);
            assertThat(ctx.records()).isEmpty();
        }
    }
}

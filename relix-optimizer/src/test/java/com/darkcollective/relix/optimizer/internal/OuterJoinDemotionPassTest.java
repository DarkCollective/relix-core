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
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.LeftOuterJoinNode;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.RightOuterJoinNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.ThetaJoinNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ColumnProvenance;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;

@DisplayName("Outer-join demotion — JOIN-004 (#532)")
final class OuterJoinDemotionPassTest {

    private OptimizationContext ctx;

    @BeforeEach
    void setUp() { ctx = new OptimizationContext(); }

    // ── fixtures ───────────────────────────────────────────────────────────

    private static final RelationNode CUSTOMERS = rel("Customers");
    private static final RelationNode SALES     = rel("Sales");

    private static Schema customersSchema() {
        return new Schema(List.of(
                new ColumnDefinition("cid", ScalarType.NUMBER, new ColumnProvenance("Customers", "cid")),
                new ColumnDefinition("name", ScalarType.STRING, new ColumnProvenance("Customers", "name"))));
    }

    private static Schema salesSchema() {
        return new Schema(List.of(
                new ColumnDefinition("cid", ScalarType.NUMBER, new ColumnProvenance("Sales", "cid")),
                new ColumnDefinition("amount", ScalarType.NUMBER, new ColumnProvenance("Sales", "amount"))));
    }

    private RelNode apply(RelNode node) {
        return OuterJoinDemotionPass.apply(node, "Q",
                new SchemaAnnotations(Map.of(CUSTOMERS, customersSchema(), SALES, salesSchema())),
                ctx);
    }

    private boolean fired() {
        return !ctx.recordsFor(OptimizationCode.JOIN_004).isEmpty();
    }

    private static final Predicate JOIN_KEYS = cmp(
            attr("Customers.cid"), ComparisonOperator.EQUAL,
            attr("Sales.cid"));

    /** {@code σ filter (Customers ⟨join⟩ Sales)} over the two annotated leaves. */
    private static RelNode filtered(BiFunction<RelNode, RelNode, RelNode> join, Predicate filter) {
        return select(filter, join.apply(CUSTOMERS, SALES));
    }

    private static RelNode leftOuter(RelNode l, RelNode r) {
        return leftJoin(l, r, JOIN_KEYS);
    }

    private static RelNode rightOuter(RelNode l, RelNode r) {
        return rightJoin(l, r, JOIN_KEYS);
    }

    private static RelNode fullOuter(RelNode l, RelNode r) {
        return fullJoin(l, r, JOIN_KEYS);
    }

    /** A predicate on a right-side (padded, for ⟕) column. */
    private static Predicate rightGreaterThan(String value) {
        return cmp(attr("Sales.amount"),
                ComparisonOperator.GREATER, num(value));
    }

    /** A predicate on a left-side column. */
    private static Predicate leftEquals(String value) {
        return cmp(attr("Customers.name"),
                ComparisonOperator.EQUAL, str(value));
    }

    private static RelNode joinUnder(RelNode selection) {
        return ((SelectionNode) selection).input();
    }

    // =========================================================================
    // The demotion table
    // =========================================================================

    @Nested
    @DisplayName("what each shape demotes to")
    class DemotionTable {

        @Test
        @DisplayName("⟕ under a predicate on a right column becomes an inner join")
        void leftOuterDemotesToInner() {
            RelNode result = apply(filtered(OuterJoinDemotionPassTest::leftOuter,
                    rightGreaterThan("20")));

            assertThat(joinUnder(result)).isNode(ThetaJoinNode.class);
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("⟕ under a predicate on a left column stays outer — its own rows are not padded")
        void leftOuterKeepsItsPreservedSide() {
            RelNode tree = filtered(OuterJoinDemotionPassTest::leftOuter, leftEquals("ada"));

            assertThat(apply(tree)).isSameAs(tree);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("⟖ demotes on a left-column predicate, not a right-column one")
        void rightOuterMirrorsIt() {
            assertThat(joinUnder(apply(filtered(OuterJoinDemotionPassTest::rightOuter,
                    leftEquals("ada"))))).isNode(ThetaJoinNode.class);

            var second = new OuterJoinDemotionPassTest();
            second.setUp();
            RelNode kept = filtered(OuterJoinDemotionPassTest::rightOuter, rightGreaterThan("20"));
            assertThat(second.apply(kept)).isSameAs(kept);
        }

        @Test
        @DisplayName("⟗ under a right-column predicate loses only its left-padded half, becoming ⟖")
        void fullOuterLosesOneHalf() {
            RelNode result = apply(filtered(OuterJoinDemotionPassTest::fullOuter,
                    rightGreaterThan("20")));

            assertThat(joinUnder(result)).isNode(RightOuterJoinNode.class);
        }

        @Test
        @DisplayName("⟗ under a left-column predicate becomes ⟕")
        void fullOuterLosesTheOtherHalf() {
            RelNode result = apply(filtered(OuterJoinDemotionPassTest::fullOuter,
                    leftEquals("ada")));

            assertThat(joinUnder(result)).isNode(LeftOuterJoinNode.class);
        }

        @Test
        @DisplayName("⟗ under predicates on both sides loses both halves at once, to an inner join")
        void fullOuterDemotesToInner() {
            RelNode result = apply(filtered(OuterJoinDemotionPassTest::fullOuter,
                    and(leftEquals("ada"), rightGreaterThan("20"))));

            assertThat(joinUnder(result)).isNode(ThetaJoinNode.class);
            assertThat(ctx.recordsFor(OptimizationCode.JOIN_004)).hasSize(1);
        }

        @Test
        @DisplayName("the whole σ-chain is consulted, not only the σ nearest the join")
        void wholeChainIsConsulted() {
            // What SEL-001 leaves behind: σ left (σ right (⟕ …)). Reading only the
            // nearest σ would make firing depend on which side of the ∧ was written
            // first.
            RelNode tree = select(rightGreaterThan("20"),
                    select(leftEquals("ada"), leftOuter(CUSTOMERS, SALES)));

            RelNode inner = ((SelectionNode) ((SelectionNode) apply(tree)).input()).input();

            assertThat(inner).isNode(ThetaJoinNode.class);
        }
    }

    // =========================================================================
    // Null-rejection analysis
    // =========================================================================

    @Nested
    @DisplayName("which predicates reject NULLs")
    class NullRejection {

        private RelNode demoteUnder(Predicate filter) {
            return joinUnder(apply(filtered(OuterJoinDemotionPassTest::leftOuter, filter)));
        }

        @Test
        @DisplayName("IS NOT NULL rejects")
        void isNotNullRejects() {
            assertThat(demoteUnder(nullPred(attr("Sales.amount"), false)))
                    .isNode(ThetaJoinNode.class);
        }

        @Test
        @DisplayName("IS NULL does NOT — it is the anti-join idiom, and demoting inverts the answer")
        void isNullIsTheTrap() {
            RelNode tree = filtered(OuterJoinDemotionPassTest::leftOuter,
                    nullPred(attr("Sales.amount"), true));

            assertThat(apply(tree)).isSameAs(tree);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("¬(c IS NULL) rejects")
        void notIsNullRejects() {
            assertThat(demoteUnder(not(
                    nullPred(attr("Sales.amount"), true))))
                    .isNode(ThetaJoinNode.class);
        }

        @Test
        @DisplayName("a comparison rejects from EITHER operand — 5 < c as much as c > 5")
        void comparisonReadsBothOperands() {
            // The test is `isSideColumn(left) || isSideColumn(right)`, so a fixture that
            // always puts the column first short-circuits and never shows the second
            // operand is looked at. A user writing `5 < Sales.amount` gets the same
            // NULL-rejection and so must get the same demotion.
            assertThat(demoteUnder(cmp(num("20"),
                    ComparisonOperator.LESS, attr("Sales.amount"))))
                    .isNode(ThetaJoinNode.class);
        }

        @Test
        @DisplayName("∈ rejects, but ∉ does not — a negated membership holds of a NULL row")
        void membershipRejectsOnlyWhenPositive() {
            assertThat(demoteUnder(elementOf(
                    attr("Sales.amount"),
                    set(num("1")))))
                    .as("∈ rejects").isNode(ThetaJoinNode.class);

            RelNode negated = filtered(OuterJoinDemotionPassTest::leftOuter,
                    notElementOf(attr("Sales.amount"),
                            set(num("1"))));
            assertThat(apply(negated)).as("∉ is left alone").isSameAs(negated);
        }

        @Test
        @DisplayName("¬(c IS NOT NULL) is left alone — only the IS NULL form is invertible")
        void notIsNotNullIsConservative() {
            // The ¬ arm reads `inner instanceof NullPredicate && inner.isNull()`; the
            // second half is what separates ¬(IS NULL) — which rejects — from
            // ¬(IS NOT NULL), which is `IS NULL` again and must not demote.
            RelNode tree = filtered(OuterJoinDemotionPassTest::leftOuter,
                    not(nullPred(
                            attr("Sales.amount"), false)));
            assertThat(apply(tree)).isSameAs(tree);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("¬(c = 5) is left alone — establishing that needs the dual analysis")
        void notComparisonIsConservative() {
            RelNode tree = filtered(OuterJoinDemotionPassTest::leftOuter,
                    not(rightGreaterThan("20")));

            assertThat(apply(tree)).isSameAs(tree);
        }

        @Test
        @DisplayName("LIKE rejects; NOT LIKE is left alone")
        void patternRejectsOnlyUnnegated() {
            assertThat(demoteUnder(like(attr("Sales.amount"),
                    str("1%")))).isNode(ThetaJoinNode.class);

            var second = new OuterJoinDemotionPassTest();
            second.setUp();
            RelNode kept = filtered(OuterJoinDemotionPassTest::leftOuter,
                    notLike(attr("Sales.amount"),
                            str("1%")));
            assertThat(second.apply(kept)).isSameAs(kept);
        }

        @Test
        @DisplayName("∈ rejects")
        void elementOfRejects() {
            assertThat(demoteUnder(elementOf(attr("Sales.amount"),
                    set(num("10")))))
                    .isNode(ThetaJoinNode.class);
        }

        @Test
        @DisplayName("∨ rejects only when both branches do")
        void disjunctionNeedsBothBranches() {
            assertThat(demoteUnder(or(
                    rightGreaterThan("20"),
                    nullPred(attr("Sales.amount"), false))))
                    .isNode(ThetaJoinNode.class);

            var second = new OuterJoinDemotionPassTest();
            second.setUp();
            // One branch is IS NULL, which a padded row satisfies — so the padded rows
            // survive the σ and the join must stay outer.
            RelNode kept = filtered(OuterJoinDemotionPassTest::leftOuter, or(
                    rightGreaterThan("20"),
                    nullPred(attr("Sales.amount"), true)));
            assertThat(second.apply(kept)).isSameAs(kept);
        }

        @Test
        @DisplayName("a function over the column does not reject — Nz and Coalesce exist to map NULL away")
        void functionCallDoesNotReject() {
            RelNode tree = filtered(OuterJoinDemotionPassTest::leftOuter,
                    cmp(
                            func("Nz",attr("Sales.amount"),
                                    num("0")),
                            ComparisonOperator.EQUAL, num("0")));

            assertThat(apply(tree)).isSameAs(tree);
        }

        @Test
        @DisplayName("a column either side could own is not attributed to the padded side")
        void ambiguousColumnDoesNotReject() {
            // `cid` names a column of both inputs; treating it as the right's would
            // demote on a predicate that constrains the left.
            RelNode tree = filtered(OuterJoinDemotionPassTest::leftOuter,
                    cmp(attr("cid"),
                            ComparisonOperator.EQUAL, num("1")));

            assertThat(apply(tree)).isSameAs(tree);
        }
    }

    // =========================================================================
    // Guards
    // =========================================================================

    @Nested
    @DisplayName("guards")
    class Guards {

        @Test
        @DisplayName("an outer join with no σ above it is untouched")
        void bareOuterJoinIsUntouched() {
            RelNode tree = leftOuter(CUSTOMERS, SALES);

            assertThat(apply(tree)).isSameAs(tree);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("an unannotated input stops the analysis")
        void missingSchemaStopsIt() {
            RelNode tree = select(rightGreaterThan("20"),
                    leftJoin(CUSTOMERS, rel("Unknown"), JOIN_KEYS));

            assertThat(apply(tree)).isSameAs(tree);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("an inner join is already as demoted as it gets")
        void innerJoinIsUntouched() {
            RelNode tree = select(rightGreaterThan("20"),
                    join(CUSTOMERS, SALES, JOIN_KEYS));

            assertThat(apply(tree)).isSameAs(tree);
            assertThat(fired()).isFalse();
        }
    }
}

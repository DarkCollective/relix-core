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
package com.darkcollective.relix.embed.equivalence;

import com.darkcollective.relix.ast.EmptyRelationNode;
import com.darkcollective.relix.optimizer.OptimizationCode;
import com.darkcollective.relix.optimizer.internal.OptimizationResult;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.assertEquivalent;
import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.assertNotFiredButEqual;
import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.optimizeFirst;
import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.run;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;

/**
 * Execution-level equivalence for contradiction detection and empty-relation
 * propagation (#533): {@code PRED-004} through {@code PRED-006} and
 * {@code EMPTY-001} through {@code EMPTY-003} must leave the answer unchanged over
 * real rows.
 *
 * <p>These rules are unusual in that the "unchanged answer" is usually <em>no rows</em>,
 * which an equivalence assertion alone would satisfy vacuously — an optimizer that
 * deleted the query would pass. Each case therefore also pins that the rule fired,
 * and the collapse cases pin the shape of the optimized tree.
 *
 * <p>Harness: {@link OptimizerEquivalence}.
 */
@DisplayName("Contradiction detection + empty propagation — execution equivalence")
final class EmptyRelationEquivalenceTest {

    private static final String ORDERS =
            "Orders := [\n"
          + "| cust | region | amount |\n"
          + "|------|--------|--------|\n"
          + "| A    | west   | 10     |\n"
          + "| A    | west   | 30     |\n"
          + "| B    | east   | 5      |\n"
          + "| B    | east   | 5      |\n"
          + "| C    | west   | 70     |\n"
          + "];\n";

    private static final String CUSTOMERS =
            "Customers := [\n"
          + "| cust | tier |\n"
          + "|------|------|\n"
          + "| A    | gold |\n"
          + "| B    | bronze |\n"
          + "];\n";

    // =========================================================================
    // PRED-004 / EMPTY-001
    // =========================================================================

    @Nested
    @DisplayName("PRED-004 + EMPTY-001 — a contradictory filter")
    class Contradiction {

        @Test
        @DisplayName("disjoint ranges: the optimized plan returns the same (empty) answer")
        void disjointRangesEquivalent() {
            assertEquivalent(
                    ORDERS + "query { σ amount > 50 ∧ amount < 20 (Orders) };\n",
                    OptimizationCode.PRED_004);
        }

        @Test
        @DisplayName("conflicting equalities are equivalent too")
        void conflictingEqualitiesEquivalent() {
            assertEquivalent(
                    ORDERS + "query { σ amount = 10 ∧ amount = 30 (Orders) };\n",
                    OptimizationCode.PRED_004);
        }

        @Test
        @DisplayName("the optimized tree really is ∅ — not merely a query that returns nothing")
        void collapsesToTheEmptyRelation() {
            SemanticModel m = model(
                    ORDERS + "query { σ amount > 50 ∧ amount < 20 (Orders) };\n");
            OptimizationResult r = optimizeFirst(m);
            assertThat(r.optimized()).isNode(EmptyRelationNode.class);
            assertThat(run(r.optimized(), m)).isEmpty();
        }

        @Test
        @DisplayName("emptiness reaches the root through a join, deleting the other side's scan")
        void propagatesThroughAJoin() {
            SemanticModel m = model(
                    ORDERS + CUSTOMERS
                  + "query { (σ amount > 50 ∧ amount < 20 (Orders)) ⋈ Customers };\n");
            OptimizationResult r = optimizeFirst(m);
            assertThat(r.optimized()).isNode(EmptyRelationNode.class);
            assertThat(run(r.optimized(), m))
                    .containsExactlyInAnyOrderElementsOf(run(r.original(), m));
        }

        @Test
        @DisplayName("a satisfiable range is left alone and still returns its rows")
        void satisfiableRangeUntouched() {
            assertNotFiredButEqual(
                    ORDERS + "query { σ amount > 5 ∧ amount < 50 (Orders) };\n",
                    OptimizationCode.PRED_004);
        }

        @Test
        @DisplayName("⚠ a contradiction under ¬ is NOT collapsed — NULL rows would change hands")
        void negatedContradictionUntouched() {
            // ¬(x > 50 ∧ x < 20) is TRUE for every non-NULL amount, so every row comes
            // back; the point is that the rule must not fire, whatever the answer.
            assertNotFiredButEqual(
                    ORDERS + "query { σ ¬(amount > 50 ∧ amount < 20) (Orders) };\n",
                    OptimizationCode.PRED_004);
        }
    }

    // =========================================================================
    // PRED-005 / PRED-006
    // =========================================================================

    @Nested
    @DisplayName("PRED-005 / PRED-006 — redundant conjuncts")
    class Redundant {

        @Test
        @DisplayName("a subsumed bound is dropped without changing which rows survive")
        void subsumptionEquivalent() {
            assertEquivalent(
                    ORDERS + "query { σ amount > 20 ∧ amount > 5 (Orders) };\n",
                    OptimizationCode.PRED_005);
        }

        @Test
        @DisplayName("an equality subsumes a range on the same column")
        void equalitySubsumesRangeEquivalent() {
            assertEquivalent(
                    ORDERS + "query { σ amount = 30 ∧ amount > 5 (Orders) };\n",
                    OptimizationCode.PRED_005);
        }

        @Test
        @DisplayName("a repeated conjunct is dropped without changing the answer")
        void duplicateEquivalent() {
            assertEquivalent(
                    ORDERS + "query { σ region = \"west\" ∧ region = \"west\" (Orders) };\n",
                    OptimizationCode.PRED_006);
        }
    }

    // =========================================================================
    // EMPTY-003
    // =========================================================================

    @Nested
    @DisplayName("EMPTY-003 — an empty bag-union branch")
    class BagUnion {

        @Test
        @DisplayName("X ⊎ ∅ drops the branch and keeps every row of X, duplicates included")
        void rightEmptyBranchDropped() {
            assertEquivalent(
                    ORDERS
                  + "query { Orders ⊎ (σ amount > 50 ∧ amount < 20 (Orders)) };\n",
                    OptimizationCode.EMPTY_003);
        }

        @Test
        @DisplayName("∅ ⊎ X is left alone — ⊎ takes its output schema from the left branch")
        void leftEmptyBranchKept() {
            assertNotFiredButEqual(
                    ORDERS
                  + "query { (σ amount > 50 ∧ amount < 20 (Orders)) ⊎ Orders };\n",
                    OptimizationCode.EMPTY_003);
        }
    }
}

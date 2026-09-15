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

import com.darkcollective.relix.optimizer.OptimizationCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.assertEquivalent;
import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.assertEquivalentInOrder;
import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.assertNotFiredButEqual;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-level <em>equivalence</em> tests for the textbook σ and λ arms of
 * {@code SelectionPushdownPass} / {@code LimitPushdownPass} (epic #523): the query must produce
 * <strong>identical results</strong> whether or not the rule fires. The pass unit
 * tests prove the rewrite's shape; these prove it is answer-preserving over real rows.
 *
 * <p>Harness: {@link OptimizerEquivalence} (shared with {@link SipEquivalenceTest}).
 */
@DisplayName("Textbook σ/λ rewrites — execution equivalence (optimized == unoptimized)")
final class RewriteEquivalenceTest {

    // ─── inline fixtures ────────────────────────────────────────────────────────

    /** Orders across three customers, with duplicate rows so δ and ⊎ have work to do. */
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

    // =========================================================================
    // SEL-007 — HAVING → WHERE
    // =========================================================================

    @Nested
    @DisplayName("SEL-007 — selection pushed below aggregation")
    class Sel007 {

        @Test
        @DisplayName("σ on a grouping key: filtering before the γ equals filtering after it")
        void groupingKeyEquivalent() {
            assertEquivalent(
                    ORDERS
                  + "query { σ cust = \"A\" (γ cust, SUM(amount) → total (Orders)) };\n",
                    OptimizationCode.SEL_007);
        }

        @Test
        @DisplayName("two grouping keys, σ on one of them: same groups survive")
        void multiKeyEquivalent() {
            assertEquivalent(
                    ORDERS
                  + "query { σ region = \"west\" (γ cust, region, SUM(amount) → total (Orders)) };\n",
                    OptimizationCode.SEL_007);
        }

        @Test
        @DisplayName("mixed σ: the key conjunct is demoted to WHERE, the aggregate one stays a HAVING")
        void mixedConjunctionEquivalent() {
            assertEquivalent(
                    ORDERS
                  + "query { σ region = \"west\" ∧ total > 20"
                  + "        (γ cust, region, SUM(amount) → total (Orders)) };\n",
                    OptimizationCode.SEL_007);
        }

        @Test
        @DisplayName("σ on an aggregate output alone does not fire, and the answer is unchanged")
        void aggregateOutputDoesNotFire() {
            assertNotFiredButEqual(
                    ORDERS
                  + "query { σ total > 20 (γ cust, SUM(amount) → total (Orders)) };\n",
                    OptimizationCode.SEL_007);
        }

        @Test
        @DisplayName("σ over a scalar (ungrouped) γ does not fire — a scalar γ emits a row regardless")
        void scalarAggregationDoesNotFire() {
            assertNotFiredButEqual(
                    ORDERS
                  + "query { σ total > 1000 (γ SUM(amount) → total (Orders)) };\n",
                    OptimizationCode.SEL_007);
        }
    }

    // =========================================================================
    // SEL-008 — below δ / τ
    // =========================================================================

    @Nested
    @DisplayName("SEL-008 — selection pushed below DISTINCT / SORT")
    class Sel008 {

        @Test
        @DisplayName("σ p (δ R) ≡ δ (σ p R) — deduplicating the survivors is the same set")
        void belowDistinctEquivalent() {
            assertEquivalent(
                    ORDERS
                  + "query { σ region = \"east\" (δ (π region, amount (Orders))) };\n",
                    OptimizationCode.SEL_008);
        }

        @Test
        @DisplayName("σ p (τ k R) ≡ τ k (σ p R) — filtering first cannot reorder the survivors")
        void belowSortEquivalent() {
            assertEquivalent(
                    ORDERS
                  + "query { σ amount > 8 (τ amount DESC (Orders)) };\n",
                    OptimizationCode.SEL_008);
        }
    }

    // =========================================================================
    // SEL-009 — over the set operations
    // =========================================================================

    @Nested
    @DisplayName("SEL-009 — selection distributed over set operations")
    class Sel009 {

        /** A second, overlapping relation so ∪ / ∩ / − / ∆ all have something to do. */
        private static final String RECENT =
                "Recent := [\n"
              + "| cust | region | amount |\n"
              + "|------|--------|--------|\n"
              + "| A    | west   | 30     |\n"
              + "| B    | east   | 5      |\n"
              + "| D    | west   | 15     |\n"
              + "];\n";

        @Test
        @DisplayName("σ p (A ∪ B) ≡ (σ p A) ∪ (σ p B)")
        void overUnionEquivalent() {
            assertEquivalent(
                    ORDERS + RECENT
                  + "query { σ region = \"west\" (Orders ∪ Recent) };\n",
                    OptimizationCode.SEL_009);
        }

        @Test
        @DisplayName("σ p (A ∩ B) ≡ (σ p A) ∩ (σ p B)")
        void overIntersectionEquivalent() {
            assertEquivalent(
                    ORDERS + RECENT
                  + "query { σ region = \"west\" (Orders ∩ Recent) };\n",
                    OptimizationCode.SEL_009);
        }

        @Test
        @DisplayName("σ p (A ∆ B) ≡ (σ p A) ∆ (σ p B)")
        void overSymmetricDifferenceEquivalent() {
            assertEquivalent(
                    ORDERS + RECENT
                  + "query { σ region = \"west\" (Orders ∆ Recent) };\n",
                    OptimizationCode.SEL_009);
        }

        @Test
        @DisplayName("σ p (A − B) ≡ (σ p A) − B — the subtrahend must NOT be filtered")
        void overDifferenceEquivalent() {
            // The regression this guards: filtering B too would keep rows of A that B
            // was supposed to remove.  Here `B    east 5` is removed from Orders by the
            // difference, and `region = "west"` would have filtered it out of the
            // subtrahend — so a wrong push shows up as an extra row.
            assertEquivalent(
                    ORDERS + RECENT
                  + "query { σ amount > 4 (Orders − Recent) };\n",
                    OptimizationCode.SEL_009);
        }
    }

    // =========================================================================
    // LIM-002/003/004 — the λ arms
    // =========================================================================

    @Nested
    @DisplayName("LIM-002 — limit pushed below rename")
    class Lim002 {

        @Test
        @DisplayName("λ n (ρ … R) ≡ ρ … (λ n R) — a rename cannot change which rows come first")
        void belowRenameEquivalent() {
            assertEquivalentInOrder(
                    ORDERS
                  + "query { λ 3 (ρ O(c, r, a) (Orders)) };\n",
                    OptimizationCode.LIM_002);
        }
    }

    @Nested
    @DisplayName("LIM-003 — limit over sort fused into TOP")
    class Lim003 {

        @Test
        @DisplayName("λ n (τ k R) ≡ TOP n ORDER BY k (R) — same rows, same order")
        void topNEquivalent() {
            assertEquivalentInOrder(
                    ORDERS
                  + "query { λ 3 (τ amount DESC (Orders)) };\n",
                    OptimizationCode.LIM_003);
        }

        @Test
        @DisplayName("the λ's offset carries into the TOP")
        void topNWithOffsetEquivalent() {
            assertEquivalentInOrder(
                    ORDERS
                  + "query { λ 1, 2 (τ amount DESC (Orders)) };\n",
                    OptimizationCode.LIM_003);
        }

        @Test
        @DisplayName("a redundant outer τ is still removed (SORT-001 runs before the fusion)")
        void redundantOuterSortStillEliminated() {
            // The ordering hazard #528 flagged: TOP is not order-establishing to
            // OrderDeriver, so if LIM-003 ran before SORT-001 the outer τ would survive.
            // It does not — phase 7e precedes phase 8 — and the answer is unchanged
            // either way.
            String src = ORDERS
                  + "query { τ amount DESC (λ 3 (τ amount DESC (Orders))) };\n";
            var m = com.darkcollective.relix.semantic.SemanticFixtures.model(src);
            var r = OptimizerEquivalence.optimizeFirst(m);

            assertThat(OptimizerEquivalence.fired(r, OptimizationCode.SORT_001)).isTrue();
            assertThat(OptimizerEquivalence.fired(r, OptimizationCode.LIM_003)).isTrue();
            assertThat(OptimizerEquivalence.run(r.optimized(), m))
                    .containsExactlyElementsOf(OptimizerEquivalence.run(r.original(), m));
        }
    }

    @Nested
    @DisplayName("LIM-004 — limit replicated into union-all branches")
    class Lim004 {

        private static final String RECENT =
                "Recent := [\n"
              + "| cust | region | amount |\n"
              + "|------|--------|--------|\n"
              + "| D    | north  | 1      |\n"
              + "| E    | north  | 2      |\n"
              + "| F    | north  | 3      |\n"
              + "];\n";

        @Test
        @DisplayName("λ n (A ⊎ B) ≡ λ n ((λ n A) ⊎ (λ n B)) — capping each branch keeps the same prefix")
        void unionAllEquivalent() {
            assertEquivalentInOrder(
                    ORDERS + RECENT
                  + "query { λ 4 (Orders ⊎ Recent) };\n",
                    OptimizationCode.LIM_004);
        }

        @Test
        @DisplayName("with an offset the branch bound is offset + count, so no row is lost")
        void unionAllWithOffsetEquivalent() {
            // The trap: capping each branch at `count` would drop rows the outer
            // offset was going to skip past, shortening the result.
            assertEquivalentInOrder(
                    ORDERS + RECENT
                  + "query { λ 4, 3 (Orders ⊎ Recent) };\n",
                    OptimizationCode.LIM_004);
        }
    }

    // =========================================================================
    // PROJ-004 — column pruning
    // =========================================================================

    @Nested
    @DisplayName("PROJ-004 — column pruning")
    class Proj004 {

        /** A second relation sharing `cust` with Orders, so ⋈ and ⨝ have a key. */
        private static final String CUSTOMERS =
                "Customers := [\n"
              + "| cust | city    | tier |\n"
              + "|------|---------|------|\n"
              + "| A    | leeds   | 1    |\n"
              + "| B    | york    | 2    |\n"
              + "| C    | hull    | 1    |\n"
              + "];\n";

        @Test
        @DisplayName("γ over a wider table: the same totals come back over narrowed rows")
        void aggregationEquivalent() {
            assertEquivalent(
                    ORDERS
                  + "query { γ cust, SUM(amount) → total (Orders) };\n",
                    OptimizationCode.PROJ_004);
        }

        @Test
        @DisplayName("π over σ: the filtered column survives the prune it is not projected by")
        void selectionColumnSurvives() {
            assertEquivalent(
                    ORDERS
                  + "query { π cust (σ amount > 8 (Orders)) };\n",
                    OptimizationCode.PROJ_004);
        }

        @Test
        @DisplayName("τ over a narrowed input: the sort key survives even when unprojected")
        void sortKeySurvives() {
            assertEquivalentInOrder(
                    ORDERS
                  + "query { π cust (τ amount DESC (Orders)) };\n",
                    OptimizationCode.PROJ_004);
        }

        @Test
        @DisplayName("⋈: the common column is a join key and must survive being unread")
        void naturalJoinKeySurvives() {
            // Nothing above reads `cust`, but dropping it would change what ⋈ joins on.
            assertEquivalent(
                    ORDERS + CUSTOMERS
                  + "query { π amount, city (Orders ⋈ Customers) };\n",
                    OptimizationCode.PROJ_004);
        }

        @Test
        @DisplayName("θ-join: the condition's columns survive on both sides")
        void thetaJoinConditionSurvives() {
            assertEquivalent(
                    ORDERS + CUSTOMERS
                  + "query { π amount, city (Orders ⨝ Orders.cust = Customers.cust Customers) };\n",
                    OptimizationCode.PROJ_004);
        }

        @Test
        @DisplayName("⋉ prunes its right side to the condition alone and still matches the same rows")
        void semiJoinRightSidePruned() {
            // The semi-join emits no right column, so its right input is narrowed to the
            // join condition — the one arm that prunes even when nothing is read above.
            assertEquivalent(
                    ORDERS + CUSTOMERS
                  + "query { Orders ⋉ Orders.cust = Customers.cust Customers };\n",
                    OptimizationCode.PROJ_004);
        }

        @Test
        @DisplayName("▷ likewise — the same rows are excluded over a narrowed right side")
        void antiJoinRightSidePruned() {
            assertEquivalent(
                    ORDERS + CUSTOMERS
                  + "query { Orders ▷ Orders.cust = Customers.cust Customers };\n",
                    OptimizationCode.PROJ_004);
        }

        @Test
        @DisplayName("δ is not pruned below — dropping a column first would merge distinct rows")
        void distinctIsNotPruned() {
            // Orders has two identical (B, east, 5) rows; π cust (δ Orders) must still
            // return four rows, not three.
            assertNotFiredButEqual(
                    ORDERS
                  + "query { π cust (δ (Orders)) };\n",
                    OptimizationCode.PROJ_004);
        }

        @Test
        @DisplayName("a query that reads every column does not fire, and is unchanged")
        void nothingToPrune() {
            assertNotFiredButEqual(
                    ORDERS
                  + "query { σ amount > 8 (Orders) };\n",
                    OptimizationCode.PROJ_004);
        }
    }

    // =========================================================================
    // DIST-002 — a δ the aggregation above it makes irrelevant (#541)
    // =========================================================================

    /**
     * The fixture matters here: {@code Orders} carries two identical {@code (B, east, 5)}
     * rows, so a δ below the γ genuinely removes a row and the rule is not vacuous.
     */
    @Nested
    @DisplayName("DIST-002 — DISTINCT below a duplicate-insensitive aggregation")
    class Dist002 {

        @Test
        @DisplayName("MIN over deduplicated rows equals MIN over the duplicates")
        void minEquivalent() {
            assertEquivalent(
                    ORDERS
                  + "query { γ region, MIN(amount) → lowest (δ (Orders)) };\n",
                    OptimizationCode.DIST_002);
        }

        @Test
        @DisplayName("MAX likewise")
        void maxEquivalent() {
            assertEquivalent(
                    ORDERS
                  + "query { γ region, MAX(amount) → highest (δ (Orders)) };\n",
                    OptimizationCode.DIST_002);
        }

        // A γ with *no* aggregates — the other duplicate-insensitive shape — has no
        // surface syntax (the parser requires at least one aggregate function), so it is
        // reachable only as an intermediate tree. `DistinctEliminationPassTest` covers
        // that case at the AST level.

        @Test
        @DisplayName("a σ between the γ and the δ is looked through")
        void selectionBetweenEquivalent() {
            assertEquivalent(
                    ORDERS
                  + "query { γ region, MIN(amount) → lowest (σ amount > 1 (δ (Orders))) };\n",
                    OptimizationCode.DIST_002);
        }

        @Test
        @DisplayName("COUNT counts duplicates — the rule must not fire, and the answer stands")
        void countDoesNotFire() {
            // Without the δ this counts 5 rows, with it 4: the negative case is the
            // whole point of the rule's aggregate check.
            assertNotFiredButEqual(
                    ORDERS
                  + "query { γ region, COUNT(amount) → n (δ (Orders)) };\n",
                    OptimizationCode.DIST_002);
        }

        @Test
        @DisplayName("SUM likewise reads multiplicity — no rewrite")
        void sumDoesNotFire() {
            assertNotFiredButEqual(
                    ORDERS
                  + "query { γ region, SUM(amount) → total (δ (Orders)) };\n",
                    OptimizationCode.DIST_002);
        }

        @Test
        @DisplayName("a λ between the γ and the δ blocks it — how many rows arrive matters")
        void limitBetweenDoesNotFire() {
            assertNotFiredButEqual(
                    ORDERS
                  + "query { γ region, MIN(amount) → lowest (λ 3 (δ (Orders))) };\n",
                    OptimizationCode.DIST_002);
        }
    }
}

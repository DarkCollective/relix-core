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
import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.assertNotFiredButEqual;

/**
 * Execution-level <em>equivalence</em> tests for the Tier-2 rewrites of epic #523: the
 * query must produce <strong>identical results</strong> whether or not the rule fires.
 * The pass unit tests prove each rewrite's shape; these prove it is answer-preserving
 * over real rows.
 *
 * <p>Harness: {@link OptimizerEquivalence}, shared with {@link SipEquivalenceTest}
 * (the ADR-0020/0021 magic-sets family) and {@link RewriteEquivalenceTest} (the
 * textbook σ/λ arms).
 */
@DisplayName("Tier-2 rewrites — execution equivalence (optimized == unoptimized)")
final class Tier2EquivalenceTest {

    // ─── inline fixtures ────────────────────────────────────────────────────────

    private static final String ORDERS =
            "Orders := [\n"
          + "| cust | region | amount |\n"
          + "|------|--------|--------|\n"
          + "| A    | west   | 10     |\n"
          + "| A    | west   | 30     |\n"
          + "| B    | east   | 5      |\n"
          + "| C    | west   | 70     |\n"
          + "];\n";

    // =========================================================================
    // RENAME-002 — the alias wrapper view inlining leaves behind
    // =========================================================================

    @Nested
    @DisplayName("RENAME-002 — an unreferenced view alias")
    class Rename002 {

        @Test
        @DisplayName("dropping the alias a view was inlined behind preserves the answer")
        void inlinedViewAliasRemoved() {
            assertEquivalent(
                    ORDERS
                  + "Big := { σ amount > 5 (Orders) };\n"
                  + "query { σ cust = \"A\" (Big) };\n",
                    OptimizationCode.RENAME_002);
        }

        @Test
        @DisplayName("a nested view (two alias wrappers) still yields the same rows")
        void nestedViewAliasesRemoved() {
            assertEquivalent(
                    ORDERS
                  + "Big  := { σ amount > 5 (Orders) };\n"
                  + "West := { σ region = \"west\" (Big) };\n"
                  + "query { π cust, amount (West) };\n",
                    OptimizationCode.RENAME_002);
        }

        @Test
        @DisplayName("an alias the query actually names is kept, and the answer is unchanged")
        void referencedAliasKept() {
            assertNotFiredButEqual(
                    ORDERS
                  + "Big := { σ amount > 5 (Orders) };\n"
                  + "query { σ Big.cust = \"A\" (Big) };\n",
                    OptimizationCode.RENAME_002);
        }
    }

    // =========================================================================
    // EQ-001 — transitive equality propagation
    // =========================================================================

    /** Customers joined to orders by a shared `cid`, with unmatched rows on both sides. */
    private static final String CUSTOMERS =
            "Customers := [\n"
          + "| cid | name |\n"
          + "|-----|------|\n"
          + "| 1   | ada  |\n"
          + "| 2   | bea  |\n"
          + "| 3   | cyd  |\n"
          + "];\n";

    private static final String SALES =
            "Sales := [\n"
          + "| cid | amount |\n"
          + "|-----|--------|\n"
          + "| 1   | 10     |\n"
          + "| 1   | 25     |\n"
          + "| 2   | 40     |\n"
          + "| 4   | 99     |\n"
          + "];\n";

    // =========================================================================
    // SESSION-001 / DOWNSAMPLE-001 — partition pruning into the time-series operators
    // =========================================================================

    /** Two users whose readings interleave, so a wrongly-scoped session id would show. */
    private static final String READINGS =
            "Readings := [\n"
          + "| user_id | seq | v  |\n"
          + "|---------|-----|----|\n"
          + "| u1      | 1   | 10 |\n"
          + "| u1      | 3   | 20 |\n"
          + "| u1      | 40  | 30 |\n"
          + "| u2      | 2   | 40 |\n"
          + "| u2      | 50  | 50 |\n"
          + "];\n";

    /** Raw strings plus the view that types `ts` as a TIMESTAMP for DOWNSAMPLE. */
    private static final String METRICS =
            "Raw := [\n"
          + "| ts                   | host | cpu |\n"
          + "|----------------------|------|-----|\n"
          + "| 2026-01-01T00:00:30Z | h1   | 10  |\n"
          + "| 2026-01-01T00:02:00Z | h1   | 20  |\n"
          + "| 2026-01-01T00:06:00Z | h1   | 30  |\n"
          + "| 2026-01-01T00:01:00Z | h2   | 40  |\n"
          + "| 2026-01-01T00:08:00Z | h2   | 50  |\n"
          + "];\n"
          + "Metrics := { π to_timestamp(ts) → ts, host, cpu (Raw) };\n";

    @Nested
    @DisplayName("SESSION-001 / DOWNSAMPLE-001 — computing one partition instead of all")
    class TimeSeriesPruning {

        @Test
        @DisplayName("SESSIONIZE: sessionizing one partition gives the same ids as sessionizing all")
        void sessionizePruned() {
            assertEquivalent(
                    READINGS
                  + "query { σ user_id = \"u1\""
                  + "        (SESSIONIZE seq GAP 5 PER user_id AS run (Readings)) };\n",
                    OptimizationCode.SESSION_001);
        }

        @Test
        @DisplayName("SESSIONIZE: a non-key conjunct stays above and still filters")
        void sessionizeWithResidual() {
            assertEquivalent(
                    READINGS
                  + "query { σ user_id = \"u1\" ∧ v > 15"
                  + "        (SESSIONIZE seq GAP 5 PER user_id AS run (Readings)) };\n",
                    OptimizationCode.SESSION_001);
        }

        @Test
        @DisplayName("DOWNSAMPLE: bucketing one group gives the same buckets as bucketing all")
        void downsamplePruned() {
            assertEquivalent(
                    METRICS
                  + "query { σ host = \"h1\""
                  + "        (DOWNSAMPLE ts BY '5m' USING AVG PER host (Metrics)) };\n",
                    OptimizationCode.DOWNSAMPLE_001);
        }

        @Test
        @DisplayName("DOWNSAMPLE FOR n ROWS does not fire — and would give a different answer if it did")
        void maxRowsBlocksThePush() {
            // The n most recent buckets are taken across *all* groups. Here that is
            // h2's 00:05 bucket and h1's 00:05 bucket; filtering to h1 afterwards leaves
            // one row, while pushing the filter first would yield both of h1's buckets.
            assertNotFiredButEqual(
                    METRICS
                  + "query { σ host = \"h1\""
                  + "        (DOWNSAMPLE ts BY '5m' USING AVG PER host FOR 2 ROWS (Metrics)) };\n",
                    OptimizationCode.DOWNSAMPLE_001);
        }
    }

    // =========================================================================
    // JOIN-004 — outer-join demotion
    // =========================================================================

    /**
     * Every query here runs over data with unmatched rows on <strong>both</strong>
     * sides, so the outer join really does pad — a fixture without them cannot tell a
     * correct demotion from an incorrect one and would pass vacuously.
     */
    @Nested
    @DisplayName("JOIN-004 — an outer join whose padded rows cannot survive the filter")
    class Join004 {

        @Test
        @DisplayName("⟕ under a filter on a right column: demoting to inner keeps the answer")
        void leftOuterDemotesToInner() {
            // cid 3 (cyd) has no sale and cid 4's sale has no customer, so ⟕ pads.
            assertEquivalent(
                    CUSTOMERS + SALES
                  + "query { π name, amount (σ Sales.amount > 20"
                  + "        (Customers ⟕ Customers.cid = Sales.cid Sales)) };\n",
                    OptimizationCode.JOIN_004);
        }

        @Test
        @DisplayName("⟗ under a filter on a right column loses only its left-padded half")
        void fullOuterLosesOneHalf() {
            assertEquivalent(
                    CUSTOMERS + SALES
                  + "query { π name, amount (σ Sales.amount > 20"
                  + "        (Customers ⟗ Customers.cid = Sales.cid Sales)) };\n",
                    OptimizationCode.JOIN_004);
        }

        @Test
        @DisplayName("IS NULL is the anti-join idiom: it must not fire, and the padded row survives")
        void isNullKeepsTheOuterJoin() {
            assertNotFiredButEqual(
                    CUSTOMERS + SALES
                  + "query { π name (σ Sales.amount IS NULL"
                  + "        (Customers ⟕ Customers.cid = Sales.cid Sales)) };\n",
                    OptimizationCode.JOIN_004);
        }

        @Test
        @DisplayName("a filter on the preserved side alone leaves the join outer")
        void preservedSideFilterDoesNotFire() {
            assertNotFiredButEqual(
                    CUSTOMERS + SALES
                  + "query { π name, amount (σ Customers.name = \"cyd\""
                  + "        (Customers ⟕ Customers.cid = Sales.cid Sales)) };\n",
                    OptimizationCode.JOIN_004);
        }
    }

    @Nested
    @DisplayName("EQ-001 — a literal binding carried across an equi-join")
    class Eq001 {

        @Test
        @DisplayName("filtering one side by a constant also filters the other, same rows out")
        void literalPropagatesAcrossTheJoin() {
            assertEquivalent(
                    CUSTOMERS + SALES
                  + "query { σ Customers.cid = 1"
                  + "        (Customers ⨝ Customers.cid = Sales.cid Sales) };\n",
                    OptimizationCode.EQ_001);
        }

        @Test
        @DisplayName("the binding written on the right propagates to the left just the same")
        void literalPropagatesRightToLeft() {
            assertEquivalent(
                    CUSTOMERS + SALES
                  + "query { σ Sales.cid = 2"
                  + "        (Customers ⨝ Customers.cid = Sales.cid Sales) };\n",
                    OptimizationCode.EQ_001);
        }

        @Test
        @DisplayName("a binding stated inside the join condition propagates too")
        void bindingInsideTheConditionPropagates() {
            assertEquivalent(
                    CUSTOMERS + SALES
                  + "query { Customers ⨝ Customers.cid = Sales.cid ∧ Customers.cid = 1 Sales };\n",
                    OptimizationCode.EQ_001);
        }

        @Test
        @DisplayName("a binding that matches nothing yields the same (empty) answer on both sides")
        void unmatchedBindingIsStillEquivalent() {
            assertEquivalent(
                    CUSTOMERS + SALES
                  + "query { σ Customers.cid = 3"
                  + "        (Customers ⨝ Customers.cid = Sales.cid Sales) };\n",
                    OptimizationCode.EQ_001);
        }

        @Test
        @DisplayName("an inequality is not an equality class, so nothing is derived")
        void inequalityDoesNotFire() {
            assertNotFiredButEqual(
                    CUSTOMERS + SALES
                  + "query { σ Customers.cid > 1"
                  + "        (Customers ⨝ Customers.cid = Sales.cid Sales) };\n",
                    OptimizationCode.EQ_001);
        }

        @Test
        @DisplayName("an outer join's condition is never read as a fact — the padded rows deny it")
        void outerJoinDoesNotFire() {
            // σ Customers.cid = 1 (Customers ⟕ Sales): deriving Sales.cid = 1 and
            // filtering Sales would still be answer-preserving *here* only because the
            // σ is on the preserved side — but the pass must not fire at all, since the
            // same derivation from a predicate on the null-supplying side would delete
            // rows the join is required to keep.
            assertNotFiredButEqual(
                    CUSTOMERS + SALES
                  + "query { σ Customers.cid = 1"
                  + "        (Customers ⟕ Customers.cid = Sales.cid Sales) };\n",
                    OptimizationCode.EQ_001);
        }
    }
}

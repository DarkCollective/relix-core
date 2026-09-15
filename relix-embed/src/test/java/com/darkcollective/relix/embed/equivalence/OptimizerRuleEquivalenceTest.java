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
 * Execution-level <em>equivalence</em> for the optimizer rules that had none: each query
 * must return <strong>identical rows</strong> whether or not the rule fires.
 *
 * <p>{@link SipEquivalenceTest} and {@link RewriteEquivalenceTest} established the harness
 * during epic #523 and applied it to the rules being added at the time. The convention
 * that "any new rule owes an {@code OptimizerEquivalence} test" was adopted going forward,
 * so the rules already in the tree were never retrofitted — and they are the oldest and
 * most load-bearing ones: selection pushdown, projection pushdown, the join rules, the
 * NF² nest/unnest laws. Each had a pass unit test proving the rewrite's <em>shape</em> and
 * nothing proving it preserves the <em>answer</em> when executed.
 *
 * <p>{@link OptimizerRuleCoverageTest} is what stops the gap reopening: it fails on a rule
 * code that no equivalence test names and no exclusion justifies.
 *
 * <h2>The one rule with no query here</h2>
 *
 * <p>{@code AGG-001} collapses a γ that has grouping keys but <em>no aggregates</em> over
 * another γ. The grammar cannot express an aggregate-free γ — {@code γ cust (…)} is a
 * parse error ("Expected aggregate function") — so no source text reaches the rule, and
 * its {@code RedundantGroupingPass} unit test builds the node directly. Recorded in
 * {@link OptimizerRuleCoverageTest}, not worked around.
 */
@DisplayName("Optimizer rules — execution equivalence (optimized == unoptimized)")
final class OptimizerRuleEquivalenceTest {

    /** Duplicate rows and two regions, so δ, ⊎ and the grouping rules all have work. */
    static final String ORDERS =
            "Orders := [\n"
          + "| cust | region | amount |\n"
          + "|------|--------|--------|\n"
          + "| A    | west   | 10     |\n"
          + "| A    | west   | 30     |\n"
          + "| B    | east   | 5      |\n"
          + "| B    | east   | 5      |\n"
          + "| C    | west   | 70     |\n"
          + "];\n";

    /** A dimension to join against, with one tier shared by two customers. */
    static final String CUST =
            "Cust := [\n"
          + "| name | tier |\n"
          + "|------|------|\n"
          + "| A    | gold |\n"
          + "| B    | std  |\n"
          + "| C    | gold |\n"
          + "];\n";

    // =========================================================================
    // Expression simplification (simplify phase) — EXPR-002/3/5/6/7/8
    // =========================================================================

    @Nested
    @DisplayName("EXPR — arithmetic and function folding preserve the projected values")
    class Expressions {

        @Test
        @DisplayName("EXPR-002 — two string literals concatenate to the same text")
        void constantStringConcat() {
            assertEquivalent(ORDERS + "query { π \"a\" + \"b\" → s, cust (Orders) };\n",
                    OptimizationCode.EXPR_002);
        }

        @Test
        @DisplayName("EXPR-003 — x + 0 folds away without changing the column")
        void additiveIdentity() {
            assertEquivalent(ORDERS + "query { π amount + 0 → a (Orders) };\n",
                    OptimizationCode.EXPR_003);
        }

        @Test
        @DisplayName("EXPR-005 — x × 0 folds to 0 for every row")
        void zeroMultiplication() {
            assertEquivalent(ORDERS + "query { π amount * 0 → z (Orders) };\n",
                    OptimizationCode.EXPR_005);
        }

        @Test
        @DisplayName("EXPR-006 — −(−x) folds to x")
        void doubleNegation() {
            assertEquivalent(ORDERS + "query { π -(-amount) → a (Orders) };\n",
                    OptimizationCode.EXPR_006);
        }

        @Test
        @DisplayName("EXPR-007 — (x + 1) + 2 accumulates to x + 3")
        void constantAccumulation() {
            assertEquivalent(ORDERS + "query { π amount + 1 + 2 → a (Orders) };\n",
                    OptimizationCode.EXPR_007);
        }

        @Test
        @DisplayName("EXPR-008 — UCase(UCase(x)) collapses to one call")
        void idempotentFunction() {
            assertEquivalent(ORDERS + "query { π UCase(UCase(region)) → r (Orders) };\n",
                    OptimizationCode.EXPR_008);
        }
    }

    // =========================================================================
    // Predicate simplification (simplify phase) — PRED-001/2/3
    // =========================================================================

    @Nested
    @DisplayName("PRED — predicate folding keeps the same rows")
    class Predicates {

        @Test
        @DisplayName("PRED-001 — a constant-true conjunct drops without widening the filter")
        void constantBranch() {
            assertEquivalent(ORDERS + "query { σ 1 = 1 ∧ amount > 5 (Orders) };\n",
                    OptimizationCode.PRED_001);
        }

        @Test
        @DisplayName("PRED-002 — ¬¬p is p, including for the rows p leaves out")
        void doubleNot() {
            assertEquivalent(ORDERS + "query { σ ¬(¬(amount > 5)) (Orders) };\n",
                    OptimizationCode.PRED_002);
        }

        @Test
        @DisplayName("PRED-003 — 5 < amount normalises to amount > 5, not amount < 5")
        void comparisonNormalisation() {
            assertEquivalent(ORDERS + "query { σ 5 < amount (Orders) };\n",
                    OptimizationCode.PRED_003);
        }
    }

    // =========================================================================
    // Selection pushdown (pushdown phase) — SEL-001..006
    // =========================================================================

    @Nested
    @DisplayName("SEL — a filter moved down the tree filters the same rows")
    class Selections {

        @Test
        @DisplayName("SEL-001 — a conjunction split into nested σ keeps the ∧ semantics")
        void conjunctiveSplit() {
            assertEquivalent(
                    ORDERS + "query { σ amount > 5 ∧ region = \"west\" (Orders) };\n",
                    OptimizationCode.SEL_001);
        }

        @Test
        @DisplayName("SEL-002 — adjacent σ merged into one conjunction")
        void adjacentMerge() {
            assertEquivalent(
                    ORDERS + "query { σ amount > 5 (σ region = \"west\" (Orders)) };\n",
                    OptimizationCode.SEL_002);
        }

        @Test
        @DisplayName("SEL-003 — σ below π: filtering before the narrowing is the same set")
        void belowProjection() {
            assertEquivalent(
                    ORDERS + "query { σ amount > 5 (π cust, amount (Orders)) };\n",
                    OptimizationCode.SEL_003);
        }

        @Test
        @DisplayName("SEL-004 — σ below ρ: the predicate follows the renamed column")
        void belowRename() {
            assertEquivalent(
                    ORDERS + "query { σ a > 5 (ρ R2(c, r, a) (Orders)) };\n",
                    OptimizationCode.SEL_004);
        }

        @Test
        @DisplayName("SEL-005 — σ into a join input: the same pairs survive")
        void intoJoinInput() {
            assertEquivalent(
                    ORDERS + CUST
                  + "query { σ amount > 5 ((Orders) ⨝ cust = name (Cust)) };\n",
                    OptimizationCode.SEL_005);
        }

        /**
         * A nested column on the left, and a plain column on the right sharing the
         * nested field's name — the collision that decides which reading of a dotted
         * name a rewrite uses. The cities deliberately differ from the regions, so the
         * two readings return different rows rather than coinciding.
         */
        private static final String NESTED_JOIN = ORDERS
                + "Placed := { π cust, region, {city: region} → loc (Orders) };\n"
                + "Cities := [\n"
                + "| owner | city   |\n"
                + "|-------|--------|\n"
                + "| A     | zurich |\n"
                + "| B     | oslo   |\n"
                + "| C     | lima   |\n"
                + "];\n";

        @Test
        @DisplayName("SEL-005 — a σ on a nested column reaches the input that owns it")
        void intoJoinInputByPath() {
            // `loc.city` is a path into the left's nested column; `city` is a plain
            // column of the right. Resolving the reference by its tail sent the σ into
            // `Cities`, filtering a relation the predicate says nothing about — so the
            // query returned no rows where it should return two.
            assertEquivalent(
                    NESTED_JOIN
                  + "query { σ loc.city = \"west\" ((Placed) ⨝ cust = owner (Cities)) };\n",
                    OptimizationCode.SEL_005);
        }

        @Test
        @DisplayName("SEL-006 — σ into both ⊎ branches keeps the bag's duplicates")
        void intoUnionAllBranch() {
            assertEquivalent(
                    ORDERS + "query { σ amount > 5 ((Orders) ⊎ (Orders)) };\n",
                    OptimizationCode.SEL_006);
        }
    }

    // =========================================================================
    // Projection rules (cleanup phase) — PROJ-001/2/3
    // =========================================================================

    @Nested
    @DisplayName("PROJ — projections merged or moved keep the same columns and rows")
    class Projections {

        @Test
        @DisplayName("PROJ-001 — a π projecting the whole schema is dropped")
        void redundantProjection() {
            assertEquivalent(ORDERS + "query { π cust, region, amount (Orders) };\n",
                    OptimizationCode.PROJ_001);
        }

        @Test
        @DisplayName("PROJ-002 — consecutive π merged to the outer one's columns")
        void consecutiveMerged() {
            assertEquivalent(ORDERS + "query { π cust (π cust, amount (Orders)) };\n",
                    OptimizationCode.PROJ_002);
        }

        @Test
        @DisplayName("PROJ-003 — π below σ, retaining the column the predicate reads")
        void belowSelection() {
            assertEquivalent(
                    ORDERS + "query { π cust, amount (σ amount > 5 (Orders)) };\n",
                    OptimizationCode.PROJ_003);
        }
    }

    // =========================================================================
    // Join rules (pushdown phase) — JOIN-001/002
    // =========================================================================

    @Nested
    @DisplayName("JOIN — a product rewritten as a join produces the same pairs")
    class Joins {

        @Test
        @DisplayName("JOIN-001 — σ over × becomes ⨝ with the same matching pairs")
        void productToTheta() {
            assertEquivalent(
                    ORDERS + CUST + "query { σ cust = name ((Orders) × (Cust)) };\n",
                    OptimizationCode.JOIN_001);
        }

        @Test
        @DisplayName("JOIN-002 — a one-sided predicate pushed into that side of a ×")
        void oneSidedIntoProduct() {
            assertEquivalent(
                    ORDERS + CUST + "query { σ tier = \"gold\" ((Orders) × (Cust)) };\n",
                    OptimizationCode.JOIN_002);
        }
    }

    // =========================================================================
    // Limit, rename, distinct, product identity
    // =========================================================================

    @Nested
    @DisplayName("LIM / RENAME / DIST / PROD — structural rules")
    class Structural {

        @Test
        @DisplayName("LIM-001 — λ below π returns the same first rows, in the same order")
        void limitBelowProjection() {
            assertEquivalentInOrder(ORDERS + "query { λ 2 (π cust (Orders)) };\n",
                    OptimizationCode.LIM_001);
        }

        @Test
        @DisplayName("RENAME-001 — ρ over ρ collapsed, keeping the inner column renames")
        void renameChainCollapsed() {
            assertEquivalent(ORDERS + "query { ρ A (ρ B(c, r, a) (Orders)) };\n",
                    OptimizationCode.RENAME_001);
        }

        @Test
        @DisplayName("DIST-001 — δ over an already-distinct input drops without adding rows")
        void redundantDistinct() {
            assertEquivalent(ORDERS + "query { δ (δ (Orders)) };\n",
                    OptimizationCode.DIST_001);
        }

        @Test
        @DisplayName("PROD-001 — R × UNIT is R, heading and all")
        void productAgainstUnit() {
            assertEquivalent(ORDERS + "query { (Orders) × UNIT };\n",
                    OptimizationCode.PROD_001);
        }
    }

    // =========================================================================
    // Empty propagation (simplify phase) — EMPTY-002
    // =========================================================================

    @Nested
    @DisplayName("EMPTY — a contradiction becomes ∅, and ∅ propagates upward")
    class EmptyPropagation {

        /**
         * EMPTY-001 was named by {@code EmptyRelationEquivalenceTest}'s
         * "PRED-004 + EMPTY-001" display name but asserted only on {@code PRED_004} — so
         * nothing checked that EMPTY-001 fired, and the test would have passed had it
         * stopped firing. Found by {@link OptimizerRuleCoverageTest} on its first run.
         */
        @Test
        @DisplayName("EMPTY-001 — σ false collapses to ∅, returning no rows either way")
        void contradictionBecomesEmpty() {
            assertEquivalent(ORDERS + "query { σ 1 = 2 (Orders) };\n",
                    OptimizationCode.EMPTY_001);
        }

        @Test
        @DisplayName("through λ")
        void throughLimit() {
            assertEquivalent(ORDERS + "query { λ 3 (σ 1 = 2 (Orders)) };\n",
                    OptimizationCode.EMPTY_002);
        }

        @Test
        @DisplayName("through δ")
        void throughDistinct() {
            assertEquivalent(ORDERS + "query { δ (σ 1 = 2 (Orders)) };\n",
                    OptimizationCode.EMPTY_002);
        }

        @Test
        @DisplayName("through τ")
        void throughSort() {
            assertEquivalent(ORDERS + "query { τ amount (σ 1 = 2 (Orders)) };\n",
                    OptimizationCode.EMPTY_002);
        }
    }

    // =========================================================================
    // NF² nest/unnest laws (ADR-0006) — NEST-001/2/3
    // =========================================================================

    @Nested
    @DisplayName("NEST — the μ/COLLECT laws round-trip to the same rows")
    class NestUnnest {

        /** cust, region and a two-element array, so a π below μ has something to prune. */
        private static final String ARRAYS =
                ORDERS + "Boxed := { π cust, region, [amount, amount] → arr (Orders) };\n";

        @Test
        @DisplayName("NEST-001 — μ over γ COLLECT collapses to the projection it came from")
        void roundTripCollapsed() {
            assertEquivalent(
                    ORDERS + "query { μ ids (γ cust, COLLECT(amount) → ids (Orders)) };\n",
                    OptimizationCode.NEST_001);
        }

        @Test
        @DisplayName("NEST-002 — σ below μ when the predicate avoids the unnested column")
        void selectionBelowUnnest() {
            assertEquivalent(ARRAYS + "query { σ cust = \"A\" (μ arr (Boxed)) };\n",
                    OptimizationCode.NEST_002);
        }

        @Test
        @DisplayName("NEST-003 — a column-pruning π below μ keeps the unnested column")
        void projectionBelowUnnest() {
            assertEquivalent(ARRAYS + "query { π cust, arr (μ arr (Boxed)) };\n",
                    OptimizationCode.NEST_003);
        }

        /**
         * An array of <em>structs</em>, so a reference can read a field of the element.
         * The field is deliberately named {@code cust}, which is also a column of the
         * relation — the collision that makes a mis-resolved path visible.
         */
        private static final String STRUCTS = ORDERS
                + "Boxed := { π cust, region, [{cust: region, amt: amount}] → arr (Orders) };\n";

        @Test
        @DisplayName("NEST-002 does not fire when the predicate reads a field of the unnested column")
        void selectionIntoTheElementStaysAboveUnnest() {
            // Below the μ, `arr` is the array and `arr.amt` resolves to nothing, so a
            // push turns the predicate UNKNOWN and the query returns no rows at all.
            // The rule must decline, and the answer must be the same either way.
            assertNotFiredButEqual(STRUCTS + "query { σ arr.amt > 20 (μ arr (Boxed)) };\n",
                    OptimizationCode.NEST_002);
        }

        @Test
        @DisplayName("NEST-003 does not fire when the projection reads a field of the unnested column")
        void projectionIntoTheElementStaysAboveUnnest() {
            assertNotFiredButEqual(STRUCTS + "query { π arr.amt, arr (μ arr (Boxed)) };\n",
                    OptimizationCode.NEST_003);
        }
    }

    // =========================================================================
    // Partition/group pruning (ADR-0020 SIP family) — WINDOW-001, TOPK-001, OPTIMIZE-001
    // =========================================================================

    @Nested
    @DisplayName("Partition pruning — a σ on the partition key below the operator")
    class PartitionPruning {

        @Test
        @DisplayName("WINDOW-001 — the surviving partition's running totals are unchanged")
        void windowPartition() {
            assertEquivalent(
                    ORDERS + "query { σ region = \"west\" (ROLLING SUM(amount) OVER ALL ROWS "
                           + "SORT amount PER region AS rt (Orders)) };\n",
                    OptimizationCode.WINDOW_001);
        }

        @Test
        @DisplayName("TOPK-001 — pruning a partition does not change the other's top rows")
        void topKPartition() {
            assertEquivalentInOrder(
                    ORDERS + "query { σ region = \"west\" (TOP 2 amount DESC PER region (Orders)) };\n",
                    OptimizationCode.TOPK_001);
        }

        @Test
        @DisplayName("OPTIMIZE-001 — pruning a group leaves the other group's solution intact")
        void optimizeGroup() {
            assertEquivalent(
                    ORDERS + "query { σ region = \"west\" (OPTIMIZE MAXIMIZE SUM(amount) "
                           + "SUBJECT TO SUM(amount) ≤ 40 PER region (Orders)) };\n",
                    OptimizationCode.OPTIMIZE_001);
        }
    }

    // =========================================================================
    // View inlining (preamble) — INLINE-001
    // =========================================================================

    @Nested
    @DisplayName("INLINE-001 — a view expanded in place computes what the reference did")
    class ViewInlining {

        @Test
        @DisplayName("the inlined body returns the view's rows")
        void viewBodyInlined() {
            assertEquivalent(
                    ORDERS + "V := { σ amount > 5 (Orders) };\nquery { V };\n",
                    OptimizationCode.INLINE_001);
        }
    }

    // =========================================================================
    // Generators — GEN-001
    // =========================================================================

    @Nested
    @DisplayName("GEN-001 — a bound folded into an unbounded generator")
    class Generators {

        /**
         * Checked against an expected result rather than against the unoptimized tree,
         * because <strong>the unoptimized tree does not terminate</strong>: σ n &lt; 10
         * over ℕ emits ten rows and then scans forever, since nothing tells the generator
         * to stop looking for an eleventh. Making that scan finite is the whole point of
         * the rule, so there is no baseline run to compare against — measured, not
         * assumed: the unoptimized side was still running after five seconds.
         *
         * <p>The expectation is therefore the rule's specification: the bound is folded
         * into the generator, and the rows are exactly those σ would have kept.
         */
        @Test
        @DisplayName("the folded scan yields exactly the rows the filter would have kept")
        void boundFoldedIntoGenerator() {
            assertThat(OptimizerEquivalence.runWithGenerators(
                    "source Naturals from generator { name: \"Naturals\" };\n"
                  + "query { σ n < 10 (Naturals) };\n",
                    OptimizationCode.GEN_001))
                    .containsExactly("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
        }
    }
}

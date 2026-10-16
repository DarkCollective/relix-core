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
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.assertEquivalent;
import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.assertNotFiredButEqual;

/**
 * The rules that look through a γ, over one with grouping sets (#125): each either still
 * fires and returns the same rows, or holds back where a rolled-up key's NULL or a
 * repeated set would make it change the answer.
 *
 * <p>Harness: {@link OptimizerEquivalence}.
 */
@DisplayName("Grouping sets through the optimizer — execution equivalence (optimized == unoptimized)")
final class GroupingSetsEquivalenceTest {

    // A NULL region of the data's own, beside the NULLs the subtotals roll up to.
    private static final String SALES = """
            Sales := [| region | product | amount |
            | east   | pen     | 10     |
            | east   | ink     | 30     |
            | west   | pen     | 5      |
            | NULL   | pen     | 7      |];
            """;

    @Test
    @DisplayName("SEL-007 — a condition on a key every set groups by is filtered first")
    void selectionOnAKeyInEverySet() {
        assertEquivalent(SALES + "query { σ region = 'east' (γ region, ROLLUP (product), SUM(amount) → t (Sales)) };\n",
                OptimizationCode.SEL_007);
    }

    @Test
    @DisplayName("SEL-007 — not on a key a set rolls up: below the γ it would keep that set's rows")
    void selectionOnARolledUpKey() {
        assertNotFiredButEqual(SALES
                + "query { σ product = 'pen' (γ ROLLUP (region, product), SUM(amount) → t (Sales)) };\n",
                OptimizationCode.SEL_007);
    }

    @Test
    @DisplayName("EMPTY-002 — grouping sets over no rows are empty when no set is the empty one")
    void emptyWithoutAGrandTotal() {
        assertEquivalent(SALES + "query { γ GROUPING SETS ((region), (product)), COUNT(*) → n "
                        + "(σ amount > 10 ∧ amount < 5 (Sales)) };\n",
                OptimizationCode.EMPTY_002);
    }

    @Test
    @DisplayName("EMPTY-002 — not through a ROLLUP, whose grand total is a row over no rows")
    void grandTotalOverNoRows() {
        assertNotFiredButEqual(SALES + "query { γ ROLLUP (region), COUNT(*) → n "
                        + "(σ amount > 10 ∧ amount < 5 (Sales)) };\n",
                OptimizationCode.EMPTY_002);
    }

    @Test
    @DisplayName("DIST-002 — δ below a duplicate-insensitive aggregate drops, with GROUPING beside it")
    void distinctBelowGroupingSets() {
        assertEquivalent(SALES + "query { γ CUBE (region, product), MAX(amount) → hi, "
                        + "GROUPING(region, product) → g (δ (Sales)) };\n",
                OptimizationCode.DIST_002);
    }

    @Test
    @DisplayName("DIST-001 — δ over grouping sets stays: a set listed twice gives its rows twice")
    void distinctOverRepeatedSets() {
        assertNotFiredButEqual(SALES + "query { δ (γ GROUPING SETS ((region), (region)), COUNT(*) → n (Sales)) };\n",
                OptimizationCode.DIST_001);
    }

    @Test
    @DisplayName("NEST-001 — μ over a rolled-up COLLECT is not the projection of the rows")
    void unnestOverRollup() {
        assertNotFiredButEqual(SALES
                        + "query { UNNEST products (γ ROLLUP (region), COLLECT(product) → products (Sales)) };\n",
                OptimizationCode.NEST_001);
    }

    @Test
    @DisplayName("PROJ-004 — the columns no set or aggregate reads are pruned below the γ")
    void pruningBelowGroupingSets() {
        assertEquivalent(SALES + "query { π region, t (γ ROLLUP (region), SUM(amount) → t (Sales)) };\n",
                OptimizationCode.PROJ_004);
    }
}

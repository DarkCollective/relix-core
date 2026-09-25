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
import com.darkcollective.relix.optimizer.internal.OptimizationResult;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.assertEquivalent;
import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.optimizeFirst;
import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.run;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-level <em>equivalence</em> tests for the rules that read the two sides of a
 * set operation together ({@code SEL-010}, {@code SET-001}..{@code SET-005}) and for the
 * column-rename hygiene rules ({@code RENAME-003}, {@code RENAME-004}): the query must
 * return identical rows whether or not the rule fires.
 *
 * <p>Every fixture here carries <strong>duplicate rows</strong>, and that is the point.
 * The de-duplicating set operations declare {@code SET}, so a rewrite that removes one
 * has to keep the claim true — which is the trap a set-only implementation of this family
 * never has to see, and which a fixture of distinct rows would hide completely.
 *
 * <p>Harness: {@link OptimizerEquivalence}.
 */
@DisplayName("Set-operation identities — execution equivalence (optimized == unoptimized)")
final class SetOperationEquivalenceTest {

    /** Orders, with `B east 5` written twice so every δ decision is observable. */
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

    /** Union-compatible with {@code Orders}, and named column for column — which is what
     *  {@code SET-004} asks for, the π naming what the ∪ pairs by position. */
    private static final String RECENT =
            "Recent := [\n"
          + "| cust | region | amount |\n"
          + "|------|--------|--------|\n"
          + "| A    | west   | 30     |\n"
          + "| B    | east   | 5      |\n"
          + "| D    | south  | 15     |\n"
          + "];\n";

    /** Two one-column relations for the product arm, with a duplicate in each. */
    private static final String TAGS =
            "Tags := [\n"
          + "| tag  |\n"
          + "|------|\n"
          + "| red  |\n"
          + "| red  |\n"
          + "| blue |\n"
          + "];\n";

    private static final String KINDS =
            "Kinds := [\n"
          + "| tag   |\n"
          + "|-------|\n"
          + "| blue  |\n"
          + "| green |\n"
          + "];\n";

    /**
     * Rows carrying a NULL, with the NULL-bearing row written <strong>twice</strong>.
     *
     * <p>Separate from the fixtures above so the cases that read it are visibly about
     * one question: whether the row-matching these rules lean on holds a NULL row equal
     * to itself.
     */
    private static final String READINGS =
            "Readings := [\n"
          + "| sensor | reading |\n"
          + "|--------|---------|\n"
          + "| a      | 10      |\n"
          + "| b      |         |\n"
          + "| b      |         |\n"
          + "| c      | 500     |\n"
          + "];\n";

    /** Union-compatible with {@code Readings}, named column for column, also NULL-bearing. */
    private static final String BACKUP =
            "Backup := [\n"
          + "| sensor | reading |\n"
          + "|--------|---------|\n"
          + "| b      |         |\n"
          + "| d      | 20      |\n"
          + "];\n";

    // =========================================================================
    // SEL-010
    // =========================================================================

    @Nested
    @DisplayName("SEL-010 — a set operation over two selections of one input")
    class Sel010 {

        @Test
        @DisplayName("σ i (A) ∪ σ q (A) ≡ δ σ (i ∨ q) (A)")
        void unionOfTwoSelections() {
            assertEquivalent(
                    ORDERS
                  + "query { σ region = \"west\" (Orders) ∪ σ amount > 50 (Orders) };\n",
                    OptimizationCode.SEL_010);
        }

        @Test
        @DisplayName("σ i (A) ∩ σ q (A) ≡ δ σ (i ∧ q) (A)")
        void intersectionOfTwoSelections() {
            assertEquivalent(
                    ORDERS
                  + "query { σ region = \"west\" (Orders) ∩ σ amount > 20 (Orders) };\n",
                    OptimizationCode.SEL_010);
        }

        @Test
        @DisplayName("σ i (A) − σ q (A) ≡ δ σ (i ∧ (¬q ∨ q IS UNKNOWN)) (A)")
        void differenceOfTwoSelections() {
            // The arm that needs the complement rather than a bare ¬q. Over READINGS,
            // `reading > 100` is UNKNOWN for the two NULL rows, so the subtrahend does
            // not hold them and the difference keeps them — which a merged `i ∧ ¬q`
            // would drop. The fixture is chosen so that distinction shows up as rows.
            assertEquivalent(
                    READINGS
                  + "query { σ sensor ≠ \"z\" (Readings) − σ reading > 100 (Readings) };\n",
                    OptimizationCode.SEL_010);
        }

        @Test
        @DisplayName("the branches may overlap — the ∪'s de-duplication must survive as δ")
        void overlappingBranchesStayDeduplicated() {
            // Every row matching `amount > 4` also matches `amount > 0`, so the ∪ is
            // de-duplicating both across branches AND within the duplicated `B east 5`.
            assertEquivalent(
                    ORDERS
                  + "query { σ amount > 0 (Orders) ∪ σ amount > 4 (Orders) };\n",
                    OptimizationCode.SEL_010);
        }
    }

    // =========================================================================
    // SET-001 / SET-002
    // =========================================================================

    @Nested
    @DisplayName("SET-001 — a set operation over two copies of one expression")
    class Set001 {

        @Test
        @DisplayName("R ∪ R ≡ δ R — the duplicate row must not come back")
        void unionOfItself() {
            assertEquivalent(ORDERS + "query { Orders ∪ Orders };\n",
                    OptimizationCode.SET_001);
        }

        @Test
        @DisplayName("R ∩ R ≡ δ R")
        void intersectionOfItself() {
            assertEquivalent(ORDERS + "query { Orders ∩ Orders };\n",
                    OptimizationCode.SET_001);
        }

        @Test
        @DisplayName("R − R ≡ ∅")
        void differenceOfItself() {
            assertEquivalent(ORDERS + "query { Orders − Orders };\n",
                    OptimizationCode.SET_001);
        }

        @Test
        @DisplayName("R ∆ R ≡ ∅")
        void symmetricDifferenceOfItself() {
            assertEquivalent(ORDERS + "query { Orders ∆ Orders };\n",
                    OptimizationCode.SET_001);
        }

        @Test
        @DisplayName("the two sides may be whole sub-trees written out twice")
        void matchesASubtree() {
            // Also pins the order the pass tries its rules in: identical sides are
            // SET-001's, not SET-004's, so the π is not hoisted over a union that is
            // about to disappear. (A σ sub-tree would not reach this pass at all —
            // SEL-010 runs earlier in the phase and merges the two filters.)
            assertEquivalent(
                    ORDERS
                  + "query { π cust, region (Orders) ∪ π cust, region (Orders) };\n",
                    OptimizationCode.SET_001);
        }
    }

    @Nested
    @DisplayName("SET-002 — a selection against its own input")
    class Set002 {

        @Test
        @DisplayName("σ k (R) ∪ R ≡ δ R")
        void unionWithItsInput() {
            assertEquivalent(
                    ORDERS + "query { σ amount > 20 (Orders) ∪ Orders };\n",
                    OptimizationCode.SET_002);
        }

        @Test
        @DisplayName("R ∪ σ k (R) ≡ δ R — the other operand order")
        void unionWithItsInputReversed() {
            assertEquivalent(
                    ORDERS + "query { Orders ∪ σ amount > 20 (Orders) };\n",
                    OptimizationCode.SET_002);
        }

        @Test
        @DisplayName("σ k (R) ∩ R ≡ δ σ k (R)")
        void intersectionWithItsInput() {
            assertEquivalent(
                    ORDERS + "query { σ amount > 20 (Orders) ∩ Orders };\n",
                    OptimizationCode.SET_002);
        }

        @Test
        @DisplayName("σ k (R) − R ≡ ∅")
        void differenceFromItsInput() {
            assertEquivalent(
                    ORDERS + "query { σ amount > 20 (Orders) − Orders };\n",
                    OptimizationCode.SET_002);
        }

        @Test
        @DisplayName("R − σ k (R) ≡ δ σ (¬k ∨ k IS UNKNOWN) (R)")
        void inputMinusASelectionOfItself() {
            assertEquivalent(
                    ORDERS + "query { Orders − σ amount > 20 (Orders) };\n",
                    OptimizationCode.SET_002);
        }

        @Test
        @DisplayName("σ k (R) ∆ R ≡ the same complement")
        void symmetricDifferenceWithItsInput() {
            assertEquivalent(
                    ORDERS + "query { σ amount > 20 (Orders) ∆ Orders };\n",
                    OptimizationCode.SET_002);
        }

        @Test
        @DisplayName("R ∆ σ k (R) ≡ the same complement, the other operand order")
        void symmetricDifferenceWithItsInputReversed() {
            assertEquivalent(
                    ORDERS + "query { Orders ∆ σ amount > 20 (Orders) };\n",
                    OptimizationCode.SET_002);
        }
    }

    // =========================================================================
    // SET-003 / SET-004 / SET-005
    // =========================================================================

    @Nested
    @DisplayName("SET-003 — a common rename hoisted out")
    class Set003 {

        @Test
        @DisplayName("ρ s (R) ∪ ρ s (Q) ≡ ρ s (R ∪ Q)")
        void hoistedOutOfUnion() {
            assertEquivalent(
                    ORDERS + RECENT
                  + "query { ρ (cust → customer) (Orders) ∪ ρ (cust → customer) (Recent) };\n",
                    OptimizationCode.SET_003);
        }

        @Test
        @DisplayName("ρ s (R) − ρ s (Q) ≡ ρ s (R − Q)")
        void hoistedOutOfDifference() {
            assertEquivalent(
                    ORDERS + RECENT
                  + "query { ρ (cust → customer) (Orders) − ρ (cust → customer) (Recent) };\n",
                    OptimizationCode.SET_003);
        }

        @Test
        @DisplayName("ρ s (R) ⊎ ρ s (Q) ≡ ρ s (R ⊎ Q) — the bag arm keeps every duplicate")
        void hoistedOutOfBagUnion() {
            assertEquivalent(
                    ORDERS + RECENT
                  + "query { ρ (cust → customer) (Orders) ⊎ ρ (cust → customer) (Recent) };\n",
                    OptimizationCode.SET_003);
        }
    }

    @Nested
    @DisplayName("SET-004 — a common projection hoisted out of a union")
    class Set004 {

        @Test
        @DisplayName("π c (A) ∪ π c (B) ≡ δ π c (A ∪ B)")
        void hoistedOutOfUnion() {
            // Without the δ this returns `A` twice: A appears in both relations and
            // `π cust` cannot see the rows differ.
            assertEquivalent(
                    ORDERS + RECENT
                  + "query { π cust (Orders) ∪ π cust (Recent) };\n",
                    OptimizationCode.SET_004);
        }

        @Test
        @DisplayName("a narrowing π over rows the union no longer separates")
        void narrowingKeepsTheSet() {
            assertEquivalent(
                    ORDERS + RECENT
                  + "query { π cust, region (Orders) ∪ π cust, region (Recent) };\n",
                    OptimizationCode.SET_004);
        }
    }

    @Nested
    @DisplayName("SET-005 — a common product operand hoisted out of a union")
    class Set005 {

        @Test
        @DisplayName("A × B ∪ A × C ≡ δ (A × (B ∪ C))")
        void commonLeftOperand() {
            assertEquivalent(
                    ORDERS + TAGS + KINDS
                  + "query { (Orders × Tags) ∪ (Orders × Kinds) };\n",
                    OptimizationCode.SET_005);
        }

        @Test
        @DisplayName("A × C ∪ B × C ≡ δ ((A ∪ B) × C)")
        void commonRightOperand() {
            assertEquivalent(
                    ORDERS + RECENT + TAGS
                  + "query { (Orders × Tags) ∪ (Recent × Tags) };\n",
                    OptimizationCode.SET_005);
        }
    }

    // =========================================================================
    // RENAME-003 / RENAME-004
    // =========================================================================

    @Nested
    @DisplayName("RENAME-003 / RENAME-004 — column-rename hygiene")
    class RenameHygiene {

        @Test
        @DisplayName("RENAME-003 — an identity pair drops without changing the heading")
        void identityPairDropped() {
            assertEquivalent(
                    ORDERS + "query { ρ (cust → cust, amount → total) (Orders) };\n",
                    OptimizationCode.RENAME_003);
        }

        @Test
        @DisplayName("RENAME-004 — ρ b→c (ρ a→b (R)) ≡ ρ a→c (R)")
        void stackedRenamesCompose() {
            assertEquivalent(
                    ORDERS
                  + "query { ρ (total → grand) (ρ (amount → total) (Orders)) };\n",
                    OptimizationCode.RENAME_004);
        }

        @Test
        @DisplayName("RENAME-004 — a cycle composes to an identity pair and both rules fire")
        void cycleCancels() {
            assertEquivalent(
                    ORDERS
                  + "query { ρ (total → amount) (ρ (amount → total) (Orders)) };\n",
                    OptimizationCode.RENAME_004);
        }
    }

    // =========================================================================
    // Rows carrying a NULL
    // =========================================================================

    @Nested
    @DisplayName("NULL-bearing rows — the row-matching these rules lean on is reflexive")
    class NullBearingRows {

        /*
         * Why these are here rather than assumed. Every rule in this family reasons
         * about rows being "the same row", and the engine answers that question two
         * different ways depending on the operator:
         *
         *   - a JOIN matches on key equality, where NULL never matches — not even
         *     itself (ExecSupport.filterNullKeys). That is what makes `R ⋈ R → R`
         *     unsound and why the self-join arm is not offered;
         *   - a SET OPERATION and δ key whole rows through Row.equals into a
         *     LinkedHashSet, and NullValue is a singleton, so a NULL row does equal
         *     itself. SQL's UNION/DISTINCT semantics.
         *
         * The second is an argument, and the arms below are what execute it. Each
         * would fail in a visible direction if it were wrong: a non-reflexive match
         * makes `R ∩ R` LOSE the NULL row, `R − R` KEEP it, and `R ∪ R` emit it twice
         * where δ emits it once.
         */

        @Test
        @DisplayName("R ∪ R ≡ δ R — the NULL row is de-duplicated across the branches")
        void unionOfItself() {
            assertEquivalent(READINGS + "query { Readings ∪ Readings };\n",
                    OptimizationCode.SET_001);
        }

        @Test
        @DisplayName("R ∩ R ≡ δ R — the NULL row matches itself, so it survives")
        void intersectionOfItself() {
            assertEquivalent(READINGS + "query { Readings ∩ Readings };\n",
                    OptimizationCode.SET_001);
        }

        @Test
        @DisplayName("R − R ≡ ∅ — the NULL row removes itself, so nothing is left")
        void differenceOfItself() {
            assertEquivalent(READINGS + "query { Readings − Readings };\n",
                    OptimizationCode.SET_001);
        }

        @Test
        @DisplayName("σ k (R) ∪ R ≡ δ R with a filter that KEEPS the NULL rows")
        void unionWithItsInputOverANullTest() {
            // `reading = NULL` is the one predicate family that is never UNKNOWN, so
            // the NULL-bearing rows are present on *both* sides of the union — which is
            // the arrangement that needs them to match each other.
            assertEquivalent(
                    READINGS + "query { σ reading = NULL (Readings) ∪ Readings };\n",
                    OptimizationCode.SET_002);
        }

        @Test
        @DisplayName("σ k (R) ∩ R ≡ δ σ k (R), likewise")
        void intersectionWithItsInputOverANullTest() {
            assertEquivalent(
                    READINGS + "query { σ reading = NULL (Readings) ∩ Readings };\n",
                    OptimizationCode.SET_002);
        }

        @Test
        @DisplayName("a filter that DROPS the NULL rows leaves the same answer either way")
        void unionWithItsInputOverAComparison() {
            // `reading > 100` is UNKNOWN for a missing reading, so the σ drops those
            // rows and only the bare branch carries them — the other arrangement.
            assertEquivalent(
                    READINGS + "query { σ reading > 100 (Readings) ∪ Readings };\n",
                    OptimizationCode.SET_002);
        }

        @Test
        @DisplayName("the fixture really does carry a NULL, and ∩ matches it against itself")
        void theFixtureIsNullBearing() {
            // Without this, every case in this class would pass just as well over a
            // fixture whose empty cell had parsed as "" — assertEquivalent compares the
            // rewrite against the original and is blind to what either contains. This is
            // the assertion that makes the rest of the class about NULL at all.
            SemanticModel m = model(READINGS + "query { Readings ∩ Readings };\n");
            OptimizationResult r = optimizeFirst(m);

            assertThat(run(r.original(), m))
                    .as("∩ must hold the NULL-bearing row equal to itself, or it vanishes")
                    .contains("b,NULL");
            assertThat(run(r.optimized(), m))
                    .as("and the rewrite must keep it")
                    .contains("b,NULL");
        }

        @Test
        @DisplayName("R − σ k (R) keeps the rows k could not decide — a bare ¬k would not")
        void complementKeepsTheUndecidedRows() {
            // This is the case the whole of SelectionComplement exists for, and the
            // only fixture in this file where ¬k and the complement differ in rows
            // rather than only in shape.
            assertEquivalent(
                    READINGS + "query { Readings − σ reading > 100 (Readings) };\n",
                    OptimizationCode.SET_002);
        }

        @Test
        @DisplayName("and the complement is what the difference returned, row for row")
        void complementMatchesTheDifference() {
            // assertEquivalent compares the rewrite against the original, so it would
            // pass just as well if BOTH lost the NULL rows. This names the rows.
            SemanticModel m = model(
                    READINGS + "query { Readings − σ reading > 100 (Readings) };\n");
            OptimizationResult r = optimizeFirst(m);

            assertThat(run(r.optimized(), m))
                    .as("the two NULL readings are undecided by `reading > 100`, so the "
                            + "difference keeps them and the complement must too")
                    .containsExactlyInAnyOrder("a,10", "b,NULL");
        }

        @Test
        @DisplayName("SET-004 — hoisting a π over a NULL-bearing column needs the same δ")
        void projectionHoistedOverNulls() {
            // Two rows project to a NULL `reading`, one from each relation. The union
            // de-duplicates them before the hoist and the δ does it after.
            assertEquivalent(
                    READINGS + BACKUP
                  + "query { π reading (Readings) ∪ π reading (Backup) };\n",
                    OptimizationCode.SET_004);
        }
    }
}

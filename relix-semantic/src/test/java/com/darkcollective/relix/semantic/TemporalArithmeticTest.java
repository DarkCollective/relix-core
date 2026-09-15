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
package com.darkcollective.relix.semantic;

import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.StructType;
import com.darkcollective.relix.symbol.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.darkcollective.relix.ast.ArithmeticOperator.DIVIDE;
import static com.darkcollective.relix.ast.ArithmeticOperator.MINUS;
import static com.darkcollective.relix.ast.ArithmeticOperator.MULTIPLY;
import static com.darkcollective.relix.ast.ArithmeticOperator.PLUS;
import static com.darkcollective.relix.symbol.ScalarType.ANY;
import static com.darkcollective.relix.symbol.ScalarType.BOOLEAN;
import static com.darkcollective.relix.symbol.ScalarType.DATE;
import static com.darkcollective.relix.symbol.ScalarType.DURATION;
import static com.darkcollective.relix.symbol.ScalarType.NUMBER;
import static com.darkcollective.relix.symbol.ScalarType.STRING;
import static com.darkcollective.relix.symbol.ScalarType.TIME;
import static com.darkcollective.relix.symbol.ScalarType.TIMESTAMP;
import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;

/** Unit tests for the temporal arithmetic/comparison type rules (ADR-0013 slice 3, #193). */
@DisplayName("TemporalArithmetic — typed temporal algebra")
final class TemporalArithmeticTest {

    private static Type binary(ScalarType l, ArithmeticOperator op, ScalarType r) {
        TemporalArithmetic.Result res = TemporalArithmetic.binary(l, op, r);
        assertThat(res.isError())
                .as("expected legal combination %s %s %s", l, op, r)
                .isFalse();
        return res.type();
    }

    private static String binaryError(ScalarType l, ArithmeticOperator op, ScalarType r) {
        TemporalArithmetic.Result res = TemporalArithmetic.binary(l, op, r);
        assertThat(res.isError())
                .as("expected illegal combination %s %s %s", l, op, r)
                .isTrue();
        return res.error();
    }

    @Nested
    class LegalBinary {
        @Test void timestampMinusTimestampIsDuration() { assertThat(binary(TIMESTAMP, MINUS, TIMESTAMP)).isEqualTo(DURATION); }
        @Test void timestampPlusDurationIsTimestamp()  { assertThat(binary(TIMESTAMP, PLUS, DURATION)).isEqualTo(TIMESTAMP); }
        @Test void timestampMinusDurationIsTimestamp() { assertThat(binary(TIMESTAMP, MINUS, DURATION)).isEqualTo(TIMESTAMP); }
        @Test void durationPlusTimestampIsTimestamp()  { assertThat(binary(DURATION, PLUS, TIMESTAMP)).isEqualTo(TIMESTAMP); }
        @Test void dateMinusDateIsDuration()           { assertThat(binary(DATE, MINUS, DATE)).isEqualTo(DURATION); }
        @Test void datePlusDurationIsTimestamp()       { assertThat(binary(DATE, PLUS, DURATION)).isEqualTo(TIMESTAMP); }
        @Test void dateMinusDurationIsTimestamp()      { assertThat(binary(DATE, MINUS, DURATION)).isEqualTo(TIMESTAMP); }
        @Test void timePlusDurationIsTime()            { assertThat(binary(TIME, PLUS, DURATION)).isEqualTo(TIME); }
        @Test void timeMinusDurationIsTime()           { assertThat(binary(TIME, MINUS, DURATION)).isEqualTo(TIME); }
        @Test void durationPlusDurationIsDuration()    { assertThat(binary(DURATION, PLUS, DURATION)).isEqualTo(DURATION); }
        @Test void durationMinusDurationIsDuration()   { assertThat(binary(DURATION, MINUS, DURATION)).isEqualTo(DURATION); }
        @Test void durationTimesNumberIsDuration()     { assertThat(binary(DURATION, MULTIPLY, NUMBER)).isEqualTo(DURATION); }
        @Test void numberTimesDurationIsDuration()     { assertThat(binary(NUMBER, MULTIPLY, DURATION)).isEqualTo(DURATION); }
        @Test void durationDividedByNumberIsDuration() { assertThat(binary(DURATION, DIVIDE, NUMBER)).isEqualTo(DURATION); }
        @Test void durationDividedByDurationIsNumber() { assertThat(binary(DURATION, DIVIDE, DURATION)).isEqualTo(NUMBER); }
        @Test void numberPlusNumberStaysNumber()       { assertThat(binary(NUMBER, PLUS, NUMBER)).isEqualTo(NUMBER); }
    }

    @Nested
    class IllegalBinary {
        @Test void cannotAddNumberToTimestamp() {
            assertThat(binaryError(TIMESTAMP, PLUS, NUMBER))
                    .contains("cannot add NUMBER to TIMESTAMP");
        }
        @Test void cannotAddTwoTimestamps() {
            assertThat(binaryError(TIMESTAMP, PLUS, TIMESTAMP)).contains("cannot add");
        }
        @Test void cannotDivideNumberByDuration() {
            assertThat(binaryError(NUMBER, DIVIDE, DURATION)).contains("cannot divide NUMBER by DURATION");
        }
        @Test void cannotMultiplyDurations() {
            assertThat(binaryError(DURATION, MULTIPLY, DURATION)).contains("cannot multiply");
        }
        @Test void cannotAddTwoDates() {
            assertThat(binaryError(DATE, PLUS, DATE)).contains("cannot add");
        }
        @Test void cannotSubtractDateFromTimestamp() {
            assertThat(binaryError(TIMESTAMP, MINUS, DATE)).contains("cannot subtract");
        }
        @Test void cannotMultiplyDateByNumber() {
            assertThat(binaryError(DATE, MULTIPLY, NUMBER)).contains("cannot multiply");
        }
    }

    @Nested
    class UnaryMinus {
        @Test void negatedDurationIsDuration() {
            assertThat(TemporalArithmetic.unary(DURATION).type()).isEqualTo(DURATION);
        }
        @Test void negatedNumberIsNumber() {
            assertThat(TemporalArithmetic.unary(NUMBER).type()).isEqualTo(NUMBER);
        }
        @Test void cannotNegateTimestamp() {
            TemporalArithmetic.Result r = TemporalArithmetic.unary(TIMESTAMP);
            assertThat(r.isError()).isTrue();
            assertThat(r.error()).contains("cannot negate TIMESTAMP");
        }
        @Test void cannotNegateDate() {
            assertThat(TemporalArithmetic.unary(DATE).isError()).isTrue();
        }
    }

    @Nested
    class Leniency {
        @Test void anyOperandIsNotAnError() {
            assertThat(TemporalArithmetic.binary(ANY, PLUS, TIMESTAMP).isError()).isFalse();
            assertThat(TemporalArithmetic.binary(ANY, PLUS, TIMESTAMP).type()).isEqualTo(ANY);
            assertThat(TemporalArithmetic.binary(TIMESTAMP, PLUS, ANY).orAny()).isEqualTo(ANY);
        }
        @Test void illegalCombinationInfersAsAnyLeniently() {
            assertThat(TemporalArithmetic.binary(TIMESTAMP, PLUS, NUMBER).orAny()).isEqualTo(ANY);
        }
    }

    @Nested
    class Comparison {
        @Test void sameTemporalTypeComparesFine() {
            assertThat(TemporalArithmetic.comparisonError(TIMESTAMP, TIMESTAMP)).isNull();
            assertThat(TemporalArithmetic.comparisonError(DURATION, DURATION)).isNull();
        }
        @Test void differentTemporalTypesError() {
            assertThat(TemporalArithmetic.comparisonError(TIMESTAMP, DATE))
                    .contains("cannot compare TIMESTAMP with DATE");
        }
        @Test void temporalVsNumberError() {
            assertThat(TemporalArithmetic.comparisonError(TIMESTAMP, NUMBER)).contains("cannot compare");
        }
        @Test void nonTemporalComparisonUnchanged() {
            assertThat(TemporalArithmetic.comparisonError(NUMBER, STRING)).isNull();
        }
        @Test void anyIsLenient() {
            assertThat(TemporalArithmetic.comparisonError(TIMESTAMP, ANY)).isNull();
        }
        /**
         * STRING is the untyped cell, not a rival temporal type: inline-table, CSV
         * and JSON columns infer as STRING, and the executor has parsed those cells
         * into the temporal they denote since issue #277. Rejecting the pairing here
         * made the answer depend on which operator asked — a selection was refused
         * while a natural join over the same two columns matched.
         */
        @Test void stringAgainstTemporalIsPermitted() {
            assertThat(TemporalArithmetic.comparisonError(TIMESTAMP, STRING)).isNull();
            assertThat(TemporalArithmetic.comparisonError(STRING, TIMESTAMP)).isNull();
            assertThat(TemporalArithmetic.comparisonError(DATE, STRING)).isNull();
            assertThat(TemporalArithmetic.comparisonError(STRING, DURATION)).isNull();
        }
        /** The relaxation is STRING only — every other mismatch still errors. */
        @Test void relaxationDoesNotReachOtherTypes() {
            assertThat(TemporalArithmetic.comparisonError(TIMESTAMP, DATE)).contains("cannot compare");
            assertThat(TemporalArithmetic.comparisonError(DATE, TIME)).contains("cannot compare");
            assertThat(TemporalArithmetic.comparisonError(DURATION, NUMBER)).contains("cannot compare");
            assertThat(TemporalArithmetic.comparisonError(BOOLEAN, TIMESTAMP)).contains("cannot compare");
        }
    }

    // =========================================================================
    // The whole table
    // =========================================================================

    /**
     * Every operand-type pair, for every operator — the legal ones by their result type,
     * and every other pair as an error.
     *
     * <p>The named cases above read as documentation of the headline rules; this states
     * the rest of the contract, which is mostly about what is <em>absent</em>. Each
     * operator is a run of {@code l == X && r == Y} tests and several are written as an
     * unordered {@code pair(…)}, so the reversed spelling of a commutative rule is a
     * distinct arm and the pairs with no rule at all fall past every one of them.
     *
     * <p>This deliberately mirrors {@code TemporalArithmeticExecutionTest.EveryPair} in
     * relix-processor cell for cell: inference and evaluation are two implementations of
     * one table, and {@code TemporalAgreementTest} holds them to it.
     */
    @Nested
    @DisplayName("an operand with no scalar type at all")
    class NonScalarOperands {

        @Test
        @DisplayName("a struct or array operand is lenient, exactly as ANY is")
        void nonScalarIsLenient() {
            // `scalar(Type)` answers null for a StructType or an ArrayType, which is a
            // third way into the leniency guard beside the two ANY arms — and the one a
            // nested column reaches. Deciding it statically is impossible, so the rule
            // must decline rather than reject: an NF² column in an arithmetic expression
            // is a runtime question, not an analysis error.
            Type struct = struct(new StructType.Field("at", TIMESTAMP));
            Type array = array(TIMESTAMP);

            assertThat(TemporalArithmetic.binary(struct, PLUS, TIMESTAMP).isError()).isFalse();
            assertThat(TemporalArithmetic.binary(struct, PLUS, TIMESTAMP).type()).isEqualTo(ANY);
            assertThat(TemporalArithmetic.binary(TIMESTAMP, MINUS, array).isError()).isFalse();
            assertThat(TemporalArithmetic.binary(TIMESTAMP, MINUS, array).type()).isEqualTo(ANY);
        }
    }

    @Nested
    @DisplayName("every operand-type pair, for every operator")
    class EveryPair {

        /** The types the rules are written over. STRING is excluded — see the agreement test. */
        private static final List<ScalarType> KINDS =
                List.of(TIMESTAMP, DATE, TIME, DURATION, NUMBER);

        /** {@code "LEFT op RIGHT"} → result type, for every combination that has a rule. */
        private static Map<String, ScalarType> legal(ArithmeticOperator op) {
            Map<String, ScalarType> rules = new LinkedHashMap<>();
            switch (op) {
                case PLUS -> {
                    rules.put(key(TIMESTAMP, DURATION), TIMESTAMP);
                    rules.put(key(DURATION, TIMESTAMP), TIMESTAMP);
                    rules.put(key(DATE, DURATION), TIMESTAMP);
                    rules.put(key(DURATION, DATE), TIMESTAMP);
                    rules.put(key(TIME, DURATION), TIME);
                    rules.put(key(DURATION, TIME), TIME);
                    rules.put(key(DURATION, DURATION), DURATION);
                }
                case MINUS -> {
                    // Not commutative: no reversed spelling of any of these has a rule.
                    rules.put(key(TIMESTAMP, TIMESTAMP), DURATION);
                    rules.put(key(DATE, DATE), DURATION);
                    rules.put(key(TIMESTAMP, DURATION), TIMESTAMP);
                    rules.put(key(DATE, DURATION), TIMESTAMP);
                    rules.put(key(TIME, DURATION), TIME);
                    rules.put(key(DURATION, DURATION), DURATION);
                }
                case MULTIPLY -> {
                    rules.put(key(DURATION, NUMBER), DURATION);
                    rules.put(key(NUMBER, DURATION), DURATION);
                }
                case DIVIDE -> {
                    rules.put(key(DURATION, NUMBER), DURATION);
                    rules.put(key(DURATION, DURATION), NUMBER);
                }
            }
            // Neither side temporal → ordinary numeric arithmetic, whatever the operator.
            rules.put(key(NUMBER, NUMBER), NUMBER);
            return rules;
        }

        private static String key(ScalarType l, ScalarType r) {
            return l + " " + r;
        }

        private void checkEveryPair(ArithmeticOperator op) {
            Map<String, ScalarType> rules = legal(op);
            for (ScalarType l : KINDS) {
                for (ScalarType r : KINDS) {
                    ScalarType expected = rules.get(key(l, r));
                    if (expected == null) {
                        assertThat(TemporalArithmetic.binary(l, op, r).isError())
                                .as("%s %s %s has no rule and must be an error", l, op, r)
                                .isTrue();
                    } else {
                        assertThat(binary(l, op, r)).as("%s %s %s", l, op, r).isEqualTo(expected);
                    }
                }
            }
        }

        @Test @DisplayName("+ over every pair")        void plus()     { checkEveryPair(PLUS); }
        @Test @DisplayName("− over every pair")        void minus()    { checkEveryPair(MINUS); }
        @Test @DisplayName("× over every pair")        void multiply() { checkEveryPair(MULTIPLY); }
        @Test @DisplayName("÷ over every pair")        void divide()   { checkEveryPair(DIVIDE); }

        @Test
        @DisplayName("a commutative rule accepts both spellings; a non-commutative one does not")
        void commutativityIsPerRule() {
            // pair(…) matches an unordered pair, so both orders reach the same rule…
            assertThat(binary(DURATION, PLUS, TIME)).isEqualTo(TIME);
            assertThat(binary(TIME, PLUS, DURATION)).isEqualTo(TIME);
            // …while − and ÷ are written as ordered tests and the reverse has no rule.
            assertThat(TemporalArithmetic.binary(DURATION, MINUS, TIME).isError()).isTrue();
            assertThat(TemporalArithmetic.binary(NUMBER, DIVIDE, DURATION).isError()).isTrue();
        }
    }

    // =========================================================================
    // …and every operator that carries one asks the rule
    // =========================================================================

    /**
     * The rule above is only worth having where something consults it.
     *
     * <p>σ, τ and γ route an expression through {@code PredicateValidator}, which asks on
     * the way past. A projection has its own attribute walk — so that it can report with
     * the position of the offending reference rather than at 0:0 — and for a while that
     * walk did not ask at all: an illegal combination in a π analysed clean, typed as the
     * lenient {@code ANY}, and raised in the evaluator once rows were already flowing
     * ({@code #700}).
     *
     * <p>The cross-layer property — that analysis and evaluation accept the same pairs —
     * is stated in {@code TemporalAgreementTest} (relix-processor), which is the module
     * that can see both. These are the module-local guards.
     */
    @Nested
    @DisplayName("every expression-carrying operator reports an illegal combination")
    class Reported {

        private static final String TWO_TIMESTAMPS = """
                source T from database { url: "${DB}", table: "t",
                    schema: { a: TIMESTAMP, b: TIMESTAMP } };
                """;

        private static SemanticResult analysisOf(String query) {
            return analyze(TWO_TIMESTAMPS + "query { " + query + " };");
        }

        @Test
        @DisplayName("σ, τ, γ and π each report it")
        void everyCarrierReports() {
            for (String query : List.of(
                    "σ a + b > 0 (T)",
                    "τ a + b (T)",
                    "γ a, SUM(a + b) → s (T)",
                    "π a + b → r (T)")) {
                assertThat(analysisOf(query))
                        .as("%s", query)
                        .hasErrorContaining("cannot add TIMESTAMP to TIMESTAMP");
            }
        }

        @Test
        @DisplayName("a projection reports it from inside a call, a construction or a condition")
        void nestedInAProjection() {
            assertThat(analysisOf("π Abs(a + b) → r (T)")).as("call argument").hasErrors();
            assertThat(analysisOf("π [a + b] → r (T)")).as("array element").hasErrors();
            assertThat(analysisOf("π {v: a + b} → r (T)")).as("struct field").hasErrors();
            assertThat(analysisOf("π IIf(a + b > 0, 1, 0) → r (T)")).as("condition").hasErrors();
        }

        @Test
        @DisplayName("the projection error carries a real source position, not 0:0")
        void positioned() {
            assertThat(analyze(TWO_TIMESTAMPS + "query { π a + b → r (T) };").errors())
                    .filteredOn(e -> e.message().contains("cannot add"))
                    .isNotEmpty()
                    .allSatisfy(e -> assertThat(e.line()).isPositive());
        }

        @Test
        @DisplayName("a legal combination is not reported — the check does not over-fire")
        void legalIsQuiet() {
            String legal = """
                    source T from database { url: "${DB}", table: "t",
                        schema: { a: TIMESTAMP, b: DURATION } };
                    """;
            assertThat(analyze(legal + "query { π a + b → r (T) };").errors()).isEmpty();
            assertThat(analysisOf("π a - b → r (T)"))
                    .as("TIMESTAMP − TIMESTAMP is legal — it is a DURATION").hasNoErrors();
        }
    }
}

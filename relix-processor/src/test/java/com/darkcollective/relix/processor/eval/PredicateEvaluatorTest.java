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
package com.darkcollective.relix.processor.eval;

import com.darkcollective.relix.processor.EvaluationException;

import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.OrPredicate;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PredicateEvaluator — boolean predicate evaluation")
final class PredicateEvaluatorTest extends ProcessorTestSupport {

    private PredicateEvaluator eval;
    private Row row;

    @BeforeEach
    void setUp() {
        eval = new PredicateEvaluator(new OperandEvaluator());
        var schema = schema(
                col("id",     ScalarType.NUMBER),
                col("name",   ScalarType.STRING),
                col("active", ScalarType.BOOLEAN));
        row = row(schema, num(10), str("Alice"), bool(true));
    }

    // ── ComparisonPredicate ────────────────────────────────────────────────────

    @Nested
    @DisplayName("ComparisonPredicate")
    class Comparisons {

        @Test @DisplayName("EQUAL: same numbers")
        void equalNumbers() {
            assertThat(eval.evaluate(cmp("id", ComparisonOperator.EQUAL, "10"), row)).isTrue();
            assertThat(eval.evaluate(cmp("id", ComparisonOperator.EQUAL, "11"), row)).isFalse();
        }

        @Test @DisplayName("EQUAL: number values with different scale compare equal")
        void equalDifferentScale() {
            // 10 == 10.0 should be true (BigDecimal.compareTo)
            var pred = AstBuilders.cmp(
                    AstBuilders.num("10"), ComparisonOperator.EQUAL, AstBuilders.num("10.0"));
            assertThat(eval.evaluate(pred, row)).isTrue();
        }

        @Test @DisplayName("EQUAL: same strings")
        void equalStrings() {
            assertThat(eval.evaluate(
                    AstBuilders.cmp(AstBuilders.str("Alice"),
                            ComparisonOperator.EQUAL, AstBuilders.str("Alice")), row)).isTrue();
        }

        @Test @DisplayName("EQUAL: string comparison is case-sensitive")
        void equalStringsCaseSensitive() {
            assertThat(eval.evaluate(
                    AstBuilders.cmp(AstBuilders.str("alice"),
                            ComparisonOperator.EQUAL, AstBuilders.str("Alice")), row)).isFalse();
        }

        @Test @DisplayName("EQUAL: different types are never equal")
        void equalMixedTypes() {
            var pred = AstBuilders.cmp(
                    AstBuilders.num("1"), ComparisonOperator.EQUAL, AstBuilders.str("1"));
            assertThat(eval.evaluate(pred, row)).isFalse();
        }

        // A boolean-shaped inline/CSV cell infers as STRING; comparing it to a
        // boolean value coerces the string so the two match (mirrors the
        // string→temporal coercion).
        @Test @DisplayName("EQUAL: 'true' string coerces to a boolean value")
        void equalStringBoolCoercion() {
            var s = schema(col("sflag", ScalarType.STRING), col("bflag", ScalarType.BOOLEAN));
            var r = row(s, str("true"), bool(true));
            assertThat(eval.evaluate(AstBuilders.cmp(attr("sflag"),
                    ComparisonOperator.EQUAL, attr("bflag")), r)).isTrue();
        }

        @Test @DisplayName("EQUAL: 'false' string vs boolean true is not equal")
        void stringFalseVsBoolTrue() {
            var s = schema(col("sflag", ScalarType.STRING), col("bflag", ScalarType.BOOLEAN));
            var r = row(s, str("false"), bool(true));
            assertThat(eval.evaluate(AstBuilders.cmp(attr("sflag"),
                    ComparisonOperator.EQUAL, attr("bflag")), r)).isFalse();
        }

        @Test @DisplayName("EQUAL: string→boolean coercion is case-insensitive and order-independent")
        void stringBoolCaseInsensitive() {
            var s = schema(col("sflag", ScalarType.STRING), col("bflag", ScalarType.BOOLEAN));
            var r = row(s, str("TRUE"), bool(true));
            // boolean on the left, string on the right, too
            assertThat(eval.evaluate(AstBuilders.cmp(attr("bflag"),
                    ComparisonOperator.EQUAL, attr("sflag")), r)).isTrue();
        }

        @Test @DisplayName("EQUAL: a non-boolean string never coerces to boolean")
        void nonBooleanStringVsBool() {
            var s = schema(col("sflag", ScalarType.STRING), col("bflag", ScalarType.BOOLEAN));
            var r = row(s, str("yes"), bool(true));
            assertThat(eval.evaluate(AstBuilders.cmp(attr("sflag"),
                    ComparisonOperator.EQUAL, attr("bflag")), r)).isFalse();
        }

        @Test @DisplayName("NOT_EQUAL: true when values differ")
        void notEqual() {
            assertThat(eval.evaluate(cmp("id", ComparisonOperator.NOT_EQUAL, "99"), row)).isTrue();
            assertThat(eval.evaluate(cmp("id", ComparisonOperator.NOT_EQUAL, "10"), row)).isFalse();
        }

        @Test @DisplayName("LESS: true when left < right")
        void less() {
            assertThat(eval.evaluate(cmp("id", ComparisonOperator.LESS, "20"), row)).isTrue();
            assertThat(eval.evaluate(cmp("id", ComparisonOperator.LESS, "10"), row)).isFalse();
        }

        @Test @DisplayName("LESS_EQUAL: true when left <= right")
        void lessEqual() {
            assertThat(eval.evaluate(cmp("id", ComparisonOperator.LESS_EQUAL, "10"), row)).isTrue();
            assertThat(eval.evaluate(cmp("id", ComparisonOperator.LESS_EQUAL, "9"), row)).isFalse();
        }

        @Test @DisplayName("GREATER: true when left > right")
        void greater() {
            assertThat(eval.evaluate(cmp("id", ComparisonOperator.GREATER, "5"), row)).isTrue();
            assertThat(eval.evaluate(cmp("id", ComparisonOperator.GREATER, "10"), row)).isFalse();
        }

        @Test @DisplayName("GREATER_EQUAL: true when left >= right")
        void greaterEqual() {
            assertThat(eval.evaluate(cmp("id", ComparisonOperator.GREATER_EQUAL, "10"), row)).isTrue();
            assertThat(eval.evaluate(cmp("id", ComparisonOperator.GREATER_EQUAL, "11"), row)).isFalse();
        }

        // The NULL rule is a claim about *every* comparison, so the operator is the
        // test input rather than one constant somebody picked. Asserted for EQUAL
        // alone, a test display-named "any comparison with NULL" left NULL < 5
        // untested, and a seventh operator would have inherited the same silence.
        //
        // Both halves are asserted — the three-valued answer and the row-keeping
        // question evaluate() asks of it — because UNKNOWN and FALSE are
        // indistinguishable through evaluate(), and it is UNKNOWN rather than FALSE
        // that makes ¬p drop the row as well as p.
        @ParameterizedTest
        @EnumSource(ComparisonOperator.class)
        @DisplayName("every comparison with NULL on the left is UNKNOWN")
        void nullLeftIsUnknown(ComparisonOperator op) {
            var nullRow = row(schema("x"), nullVal());
            var pred = AstBuilders.cmp(
                    attr("x"), op, AstBuilders.num("1"));
            assertThat(eval.truth(pred, nullRow)).isEqualTo(PredicateEvaluator.Truth.UNKNOWN);
            assertThat(eval.evaluate(pred, nullRow)).isFalse();
        }

        @ParameterizedTest
        @EnumSource(ComparisonOperator.class)
        @DisplayName("every comparison with NULL on the right is UNKNOWN")
        void nullRightIsUnknown(ComparisonOperator op) {
            var nullRow = row(schema("x"), nullVal());
            var pred = AstBuilders.cmp(
                    AstBuilders.num("1"), op, attr("x"));
            assertThat(eval.truth(pred, nullRow)).isEqualTo(PredicateEvaluator.Truth.UNKNOWN);
            assertThat(eval.evaluate(pred, nullRow)).isFalse();
        }

        @ParameterizedTest
        @EnumSource(ComparisonOperator.class)
        @DisplayName("NULL against NULL is UNKNOWN for every comparison (SQL semantics)")
        void nullAgainstNullIsUnknown(ComparisonOperator op) {
            var nullRow = row(schema("x", "y"), nullVal(), nullVal());
            var pred = AstBuilders.cmp(
                    attr("x"), op, attr("y"));
            assertThat(eval.truth(pred, nullRow)).isEqualTo(PredicateEvaluator.Truth.UNKNOWN);
            assertThat(eval.evaluate(pred, nullRow)).isFalse();
        }

        @Test @DisplayName("ordered comparison of mixed types throws EvaluationException")
        void orderedMixedTypesThrows() {
            var pred = AstBuilders.cmp(
                    AstBuilders.num("1"), ComparisonOperator.LESS, AstBuilders.str("2"));
            assertThatThrownBy(() -> eval.evaluate(pred, row))
                    .isInstanceOf(EvaluationException.class);
        }
    }

    // ── AndPredicate ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AndPredicate")
    class And {

        @Test @DisplayName("true AND true = true")
        void tt() { assertThat(eval.evaluate(and(true, true), row)).isTrue(); }

        @Test @DisplayName("true AND false = false")
        void tf() { assertThat(eval.evaluate(and(true, false), row)).isFalse(); }

        @Test @DisplayName("false AND true = false (short-circuit)")
        void ft() { assertThat(eval.evaluate(and(false, true), row)).isFalse(); }

        @Test @DisplayName("false AND false = false")
        void ff() { assertThat(eval.evaluate(and(false, false), row)).isFalse(); }
    }

    // ── OrPredicate ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("OrPredicate")
    class Or {

        @Test @DisplayName("true OR true = true")
        void tt() { assertThat(eval.evaluate(or(true, true), row)).isTrue(); }

        @Test @DisplayName("true OR false = true (short-circuit)")
        void tf() { assertThat(eval.evaluate(or(true, false), row)).isTrue(); }

        @Test @DisplayName("false OR true = true")
        void ft() { assertThat(eval.evaluate(or(false, true), row)).isTrue(); }

        @Test @DisplayName("false OR false = false")
        void ff() { assertThat(eval.evaluate(or(false, false), row)).isFalse(); }
    }

    // ── NotPredicate ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("NotPredicate")
    class Not {

        @Test @DisplayName("NOT true = false")
        void notTrue() {
            assertThat(eval.evaluate(not(boolPred(true)), row)).isFalse();
        }

        @Test @DisplayName("NOT false = true")
        void notFalse() {
            assertThat(eval.evaluate(not(boolPred(false)), row)).isTrue();
        }

        @Test @DisplayName("double negation")
        void doubleNegation() {
            assertThat(eval.evaluate(
                    not(not(boolPred(true))), row)).isTrue();
        }
    }

    // ── NullPredicate ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("NullPredicate")
    class Null {

        @Test @DisplayName("IS NULL on null value → true")
        void isNullTrue() {
            var s = schema("x");
            var r = row(s, nullVal());
            assertThat(eval.evaluate(nullPred(attr("x"), true), r)).isTrue();
        }

        @Test @DisplayName("IS NULL on non-null value → false")
        void isNullFalse() {
            assertThat(eval.evaluate(
                    nullPred(AstBuilders.num("1"), true), row)).isFalse();
        }

        @Test @DisplayName("IS NOT NULL on non-null value → true")
        void isNotNullTrue() {
            assertThat(eval.evaluate(
                    nullPred(AstBuilders.num("1"), false), row)).isTrue();
        }

        @Test @DisplayName("IS NOT NULL on null value → false")
        void isNotNullFalse() {
            var s = schema("x");
            var r = row(s, nullVal());
            assertThat(eval.evaluate(nullPred(attr("x"), false), r)).isFalse();
        }
    }

    // ── ElementOfPredicate ────────────────────────────────────────────────────

    @Nested
    @DisplayName("ElementOfPredicate")
    class ElementOf {

        @Test @DisplayName("IN: element is in set → true")
        void inTrue() {
            var pred = elementOf(
                    AstBuilders.num("10"),
                    set(
                            AstBuilders.num("5"),
                            AstBuilders.num("10"),
                            AstBuilders.num("15")));
            assertThat(eval.evaluate(pred, row)).isTrue();
        }

        @Test @DisplayName("IN: element not in set → false")
        void inFalse() {
            var pred = elementOf(
                    AstBuilders.num("99"),
                    set(AstBuilders.num("1"), AstBuilders.num("2")));
            assertThat(eval.evaluate(pred, row)).isFalse();
        }

        @Test @DisplayName("NOT IN: element is in set → false")
        void notInFalse() {
            var pred = notElementOf(
                    AstBuilders.num("10"),
                    set(AstBuilders.num("10")));
            assertThat(eval.evaluate(pred, row)).isFalse();
        }

        @Test @DisplayName("NOT IN: element not in set → true")
        void notInTrue() {
            var pred = notElementOf(
                    AstBuilders.num("99"),
                    set(AstBuilders.num("1"), AstBuilders.num("2")));
            assertThat(eval.evaluate(pred, row)).isTrue();
        }

        @Test @DisplayName("NULL element IN set → false")
        void nullElementReturnsFalse() {
            var nullSchema = schema("x");
            var nullRow = row(nullSchema, nullVal());
            var pred = elementOf(
                    attr("x"),
                    set(AstBuilders.num("1")));
            assertThat(eval.evaluate(pred, nullRow)).isFalse();
        }

        @Test @DisplayName("NULL values in set are ignored during membership test")
        void nullInSetIgnored() {
            var nullSchema = schema("x");
            var nullRow = row(nullSchema, nullVal());
            // element = NULL, set has NULL — still false (SQL NULL semantics)
            var pred = elementOf(
                    attr("x"),
                    set(
                            attr("x"),
                            AstBuilders.num("1")));
            assertThat(eval.evaluate(pred, nullRow)).isFalse();
        }

        @Test @DisplayName("string set membership test")
        void stringSet() {
            var pred = elementOf(
                    AstBuilders.str("Alice"),
                    set(
                            AstBuilders.str("Alice"),
                            AstBuilders.str("Bob")));
            assertThat(eval.evaluate(pred, row)).isTrue();
        }

        @Test @DisplayName("boolean set membership test — true IN {true}")
        void booleanSet() {
            var pred = elementOf(
                    AstBuilders.bool(true),
                    set(AstBuilders.bool(true)));
            assertThat(eval.evaluate(pred, row)).isTrue();
        }

        @Test @DisplayName("boolean set membership test — false NOT IN {true}")
        void booleanSetNotFound() {
            var pred = elementOf(
                    AstBuilders.bool(false),
                    set(AstBuilders.bool(true)));
            assertThat(eval.evaluate(pred, row)).isFalse();
        }
    }

    @Nested
    @DisplayName("Comparison — boolean values")
    class BooleanComparisons {

        @Test @DisplayName("true == true")
        void trueEqualsTrue() {
            var pred = AstBuilders.cmp(
                    AstBuilders.bool(true), ComparisonOperator.EQUAL, AstBuilders.bool(true));
            assertThat(eval.evaluate(pred, row)).isTrue();
        }

        @Test @DisplayName("false == false")
        void falseEqualsFalse() {
            var pred = AstBuilders.cmp(
                    AstBuilders.bool(false), ComparisonOperator.EQUAL, AstBuilders.bool(false));
            assertThat(eval.evaluate(pred, row)).isTrue();
        }

        @Test @DisplayName("true != false")
        void trueNotEqualsFalse() {
            var pred = AstBuilders.cmp(
                    AstBuilders.bool(true), ComparisonOperator.NOT_EQUAL, AstBuilders.bool(false));
            assertThat(eval.evaluate(pred, row)).isTrue();
        }

        @Test @DisplayName("true > false (true sorts after false)")
        void trueGreaterThanFalse() {
            var pred = AstBuilders.cmp(
                    AstBuilders.bool(true), ComparisonOperator.GREATER, AstBuilders.bool(false));
            assertThat(eval.evaluate(pred, row)).isTrue();
        }
    }

    @Nested
    @DisplayName("ValueComparator — incompatible types")
    class TypeMismatch {

        @Test @DisplayName("comparing number with string throws EvaluationException")
        void numberVsStringThrows() {
            // ComparisonPredicate with a literal NUMBER and a literal STRING
            var pred = AstBuilders.cmp(
                    AstBuilders.num("1"), ComparisonOperator.LESS, AstBuilders.str("a"));
            assertThatThrownBy(() -> eval.evaluate(pred, row))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("Cannot compare");
        }
    }

    @Nested
    @DisplayName("ComparisonPredicate — string ordering")
    class StringOrdering {

        @Test @DisplayName("LESS: 'apple' < 'banana' is true")
        void stringLess() {
            var pred = AstBuilders.cmp(
                    AstBuilders.str("apple"), ComparisonOperator.LESS, AstBuilders.str("banana"));
            assertThat(eval.evaluate(pred, row)).isTrue();
        }

        @Test @DisplayName("GREATER: 'banana' > 'apple' is true")
        void stringGreater() {
            var pred = AstBuilders.cmp(
                    AstBuilders.str("banana"), ComparisonOperator.GREATER, AstBuilders.str("apple"));
            assertThat(eval.evaluate(pred, row)).isTrue();
        }

        @Test @DisplayName("EQUAL: same string is true")
        void stringEqual() {
            var pred = AstBuilders.cmp(
                    AstBuilders.str("hello"), ComparisonOperator.EQUAL, AstBuilders.str("hello"));
            assertThat(eval.evaluate(pred, row)).isTrue();
        }
    }

    @Nested
    @DisplayName("ElementOfPredicate — non-SetLiteralOperand set expression")
    class NonLiteralSet {

        @Test @DisplayName("IN with non-set-literal operand throws EvaluationException")
        void nonSetLiteralThrows() {
            // AttributeOperand is not a SetLiteralOperand — should throw
            var pred = elementOf(
                    AstBuilders.num("1"), attr("id"));
            assertThatThrownBy(() -> eval.evaluate(pred, row))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("set literal");
        }
    }

    // ── PatternPredicate (LIKE) ───────────────────────────────────────────────

    @Nested
    @DisplayName("PatternPredicate (LIKE)")
    class PatternPredicates {

        @Test @DisplayName("LIKE: exact match without wildcards")
        void exactMatch() {
            var s = schema(col("name", ScalarType.STRING));
            var r = row(s, str("Alice"));
            assertThat(eval.evaluate(like(
                    attr("name"), AstBuilders.str("Alice")), r)).isTrue();
            assertThat(eval.evaluate(like(
                    attr("name"), AstBuilders.str("Bob")), r)).isFalse();
        }

        @Test @DisplayName("LIKE: % matches any sequence of characters")
        void percentWildcard() {
            var s = schema(col("name", ScalarType.STRING));
            var r = row(s, str("Alice Smith"));
            assertThat(eval.evaluate(like(
                    attr("name"), AstBuilders.str("%Smith")), r)).isTrue();
            assertThat(eval.evaluate(like(
                    attr("name"), AstBuilders.str("Alice%")), r)).isTrue();
            assertThat(eval.evaluate(like(
                    attr("name"), AstBuilders.str("%lic%")), r)).isTrue();
        }

        @Test @DisplayName("LIKE: % matches empty string")
        void percentMatchesEmpty() {
            var s = schema(col("name", ScalarType.STRING));
            var r = row(s, str(""));
            assertThat(eval.evaluate(like(
                    attr("name"), AstBuilders.str("%")), r)).isTrue();
        }

        @Test @DisplayName("LIKE: _ matches exactly one character")
        void underscoreWildcard() {
            var s = schema(col("name", ScalarType.STRING));
            var r = row(s, str("ABC"));
            assertThat(eval.evaluate(like(
                    attr("name"), AstBuilders.str("A_C")), r)).isTrue();
            assertThat(eval.evaluate(like(
                    attr("name"), AstBuilders.str("A__C")), r)).isFalse();
        }

        @Test @DisplayName("LIKE: mixed % and _ wildcards")
        void mixedWildcards() {
            var s = schema(col("name", ScalarType.STRING));
            var r = row(s, str("A1B2C"));
            assertThat(eval.evaluate(like(
                    attr("name"), AstBuilders.str("A_B%")), r)).isTrue();
        }

        @Test @DisplayName("LIKE: regex metacharacters in pattern are treated literally")
        void regexMetacharactersEscaped() {
            var s = schema(col("name", ScalarType.STRING));
            var r = row(s, str("price(100)"));
            assertThat(eval.evaluate(like(
                    attr("name"), AstBuilders.str("price(100)")), r)).isTrue();
            var r2 = row(s, str("price.100"));
            assertThat(eval.evaluate(like(
                    attr("name"), AstBuilders.str("price(100)")), r2)).isFalse();
        }

        @Test @DisplayName("NOT LIKE: negation of a matching pattern is false")
        void notLikeMatching() {
            var s = schema(col("name", ScalarType.STRING));
            var r = row(s, str("Alice"));
            assertThat(eval.evaluate(notLike(
                    attr("name"), AstBuilders.str("Alice%")), r)).isFalse();
        }

        @Test @DisplayName("NOT LIKE: negation of a non-matching pattern is true")
        void notLikeNonMatching() {
            var s = schema(col("name", ScalarType.STRING));
            var r = row(s, str("Bob"));
            assertThat(eval.evaluate(notLike(
                    attr("name"), AstBuilders.str("Alice%")), r)).isTrue();
        }

        @Test @DisplayName("LIKE: NULL text returns false")
        void nullTextReturnsFalse() {
            var s = schema(col("x", ScalarType.STRING));
            var r = row(s, nullVal());
            assertThat(eval.evaluate(like(
                    attr("x"), AstBuilders.str("%")), r)).isFalse();
        }

        @Test @DisplayName("LIKE: NULL pattern returns false")
        void nullPatternReturnsFalse() {
            var s = schema(col("x", ScalarType.STRING));
            var r = row(s, nullVal());
            assertThat(eval.evaluate(like(
                    AstBuilders.str("Alice"), attr("x")), r)).isFalse();
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private static ComparisonPredicate cmp(String column, ComparisonOperator op, String numberLiteral) {
        return AstBuilders.cmp(
                attr(column), op, AstBuilders.num(numberLiteral));
    }

    private static AndPredicate and(boolean left, boolean right) {
        return AstBuilders.and(boolPred(left), boolPred(right));
    }

    private static OrPredicate or(boolean left, boolean right) {
        return AstBuilders.or(boolPred(left), boolPred(right));
    }

    /** Wraps a boolean as a Predicate that evaluates to that boolean value. */
    private static com.darkcollective.relix.ast.Predicate boolPred(boolean value) {
        // value == true → true when value is true, false when value is false
        return AstBuilders.cmp(
                AstBuilders.bool(value), ComparisonOperator.EQUAL, AstBuilders.bool(true));
    }
}

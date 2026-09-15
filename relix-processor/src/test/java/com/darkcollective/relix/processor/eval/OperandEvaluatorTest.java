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

import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ConditionOperand;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static com.darkcollective.relix.ast.Expr.*;
import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OperandEvaluator — scalar expression evaluation")
final class OperandEvaluatorTest extends ProcessorTestSupport {

    private OperandEvaluator eval;
    private Row row;

    @BeforeEach
    void setUp() {
        eval = new OperandEvaluator();
        var schema = schema(
                col("id",     ScalarType.NUMBER),
                col("name",   ScalarType.STRING),
                col("active", ScalarType.BOOLEAN));
        row = row(schema, num(42), str("Alice"), bool(true));
    }

    // ── Literals ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Literal operands")
    class Literals {

        @Test @DisplayName("StringOperand → StringValue")
        void stringLiteral() {
            assertThat(eval.evaluate(AstBuilders.str("hello"), row))
                    .isEqualTo(str("hello"));
        }

        @Test @DisplayName("NumberOperand → NumberValue")
        void numberLiteral() {
            assertThat(eval.evaluate(AstBuilders.num("3.14"), row))
                    .isEqualTo(NumberValue.of("3.14"));
        }

        @Test @DisplayName("BooleanOperand true → BooleanValue.TRUE")
        void booleanLiteralTrue() {
            assertThat(eval.evaluate(AstBuilders.bool(true), row))
                    .isEqualTo(BooleanValue.TRUE);
        }

        @Test @DisplayName("BooleanOperand false → BooleanValue.FALSE")
        void booleanLiteralFalse() {
            assertThat(eval.evaluate(AstBuilders.bool(false), row))
                    .isEqualTo(BooleanValue.FALSE);
        }

        @Test @DisplayName("DateOperand → DateValue")
        void dateLiteral() {
            assertThat(eval.evaluate(date(LocalDate.parse("2026-06-15")), row))
                    .isEqualTo(new DateValue(LocalDate.parse("2026-06-15")));
        }

        @Test @DisplayName("TimeOperand → TimeValue")
        void timeLiteral() {
            assertThat(eval.evaluate(time(LocalTime.parse("13:40:00")), row))
                    .isEqualTo(new TimeValue(LocalTime.parse("13:40:00")));
        }

        @Test @DisplayName("TimestampOperand → TimestampValue")
        void timestampLiteral() {
            assertThat(eval.evaluate(timestamp(Instant.parse("2026-06-15T13:40:00Z")), row))
                    .isEqualTo(new TimestampValue(Instant.parse("2026-06-15T13:40:00Z")));
        }

        @Test @DisplayName("DurationOperand → DurationValue")
        void durationLiteral() {
            assertThat(eval.evaluate(duration(Duration.parse("PT30M")), row))
                    .isEqualTo(new DurationValue(Duration.parse("PT30M")));
        }
    }

    // ── AttributeOperand ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("AttributeOperand")
    class Attributes {

        @Test @DisplayName("simple column name lookup")
        void simpleColumnLookup() {
            assertThat(eval.evaluate(attr("id"), row)).isEqualTo(num(42));
        }

        @Test @DisplayName("column lookup is case-insensitive")
        void caseInsensitiveLookup() {
            assertThat(eval.evaluate(attr("NAME"), row)).isEqualTo(str("Alice"));
        }

        @Test @DisplayName("qualified name strips relation prefix")
        void qualifiedNameStripsPrefix() {
            assertThat(eval.evaluate(attr("Users.id"), row)).isEqualTo(num(42));
        }

        @Test @DisplayName("multi-part qualified name uses last segment")
        void multiPartQualifiedName() {
            assertThat(eval.evaluate(attr("schema.Users.name"), row))
                    .isEqualTo(str("Alice"));
        }

        @Test @DisplayName("unknown column throws EvaluationException")
        void unknownColumnThrows() {
            assertThatThrownBy(() -> eval.evaluate(attr("nonexistent"), row))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("nonexistent");
        }
    }

    // ── UnaryOperand ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("UnaryOperand (negation)")
    class Unary {

        @Test @DisplayName("negates a positive number")
        void negatesPositive() {
            var result = eval.evaluate(unary(AstBuilders.num("5")), row);
            assertThat(result).isEqualTo(new NumberValue(new BigDecimal("-5")));
        }

        @Test @DisplayName("negates a negative number (double negation)")
        void negatesNegative() {
            var result = eval.evaluate(
                    unary(unary(AstBuilders.num("7"))), row);
            assertThat(((NumberValue) result).value().compareTo(new BigDecimal("7"))).isZero();
        }

        @Test @DisplayName("propagates NULL")
        void propagatesNull() {
            var nullSchema = schema("x");
            var nullRow = row(nullSchema, nullVal());
            var result = eval.evaluate(unary(attr("x")), nullRow);
            assertThat(result.isNull()).isTrue();
        }

        @Test @DisplayName("rejects non-numeric operand")
        void rejectsNonNumeric() {
            assertThatThrownBy(() ->
                    eval.evaluate(unary(AstBuilders.str("hello")), row))
                    .isInstanceOf(EvaluationException.class);
        }
    }

    // ── BinaryArithmeticExpression ─────────────────────────────────────────────

    @Nested
    @DisplayName("BinaryArithmeticExpression")
    class Arithmetic {

        @Test @DisplayName("NUMBER + NUMBER = sum")
        void numberAddition() {
            var expr = arith(
                    AstBuilders.num("10"), ArithmeticOperator.PLUS, AstBuilders.num("3"));
            assertThat(((NumberValue) eval.evaluate(expr, row)).value()
                    .compareTo(new BigDecimal("13"))).isZero();
        }

        @Test @DisplayName("NUMBER - NUMBER = difference")
        void numberSubtraction() {
            var expr = arith(
                    AstBuilders.num("10"), ArithmeticOperator.MINUS, AstBuilders.num("3"));
            assertThat(((NumberValue) eval.evaluate(expr, row)).value()
                    .compareTo(new BigDecimal("7"))).isZero();
        }

        @Test @DisplayName("NUMBER * NUMBER = product")
        void numberMultiplication() {
            var expr = arith(
                    AstBuilders.num("4"), ArithmeticOperator.MULTIPLY, AstBuilders.num("3"));
            assertThat(((NumberValue) eval.evaluate(expr, row)).value()
                    .compareTo(new BigDecimal("12"))).isZero();
        }

        @Test @DisplayName("NUMBER / NUMBER = quotient")
        void numberDivision() {
            var expr = arith(
                    AstBuilders.num("10"), ArithmeticOperator.DIVIDE, AstBuilders.num("4"));
            assertThat(((NumberValue) eval.evaluate(expr, row)).value()
                    .compareTo(new BigDecimal("2.5"))).isZero();
        }

        @Test @DisplayName("STRING + STRING = concatenation")
        void stringConcatenation() {
            var expr = arith(
                    AstBuilders.str("Hello"), ArithmeticOperator.PLUS, AstBuilders.str(" World"));
            assertThat(eval.evaluate(expr, row)).isEqualTo(str("Hello World"));
        }

        @Test @DisplayName("NULL on left propagates to NULL result")
        void nullLeftPropagates() {
            var nullSchema = schema("x");
            var nullRow = row(nullSchema, nullVal());
            var expr = arith(
                    attr("x"), ArithmeticOperator.PLUS, AstBuilders.num("1"));
            assertThat(eval.evaluate(expr, nullRow).isNull()).isTrue();
        }

        @Test @DisplayName("NULL on right propagates to NULL result")
        void nullRightPropagates() {
            var nullSchema = schema("x");
            var nullRow = row(nullSchema, nullVal());
            var expr = arith(
                    AstBuilders.num("1"), ArithmeticOperator.PLUS, attr("x"));
            assertThat(eval.evaluate(expr, nullRow).isNull()).isTrue();
        }

        @Test @DisplayName("division by zero throws EvaluationException")
        void divisionByZero() {
            var expr = arith(
                    AstBuilders.num("1"), ArithmeticOperator.DIVIDE, AstBuilders.num("0"));
            assertThatThrownBy(() -> eval.evaluate(expr, row))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("zero");
        }

        @Test @DisplayName("type mismatch in subtraction throws EvaluationException")
        void typeMismatchThrows() {
            var expr = arith(
                    AstBuilders.str("x"), ArithmeticOperator.MINUS, AstBuilders.num("1"));
            assertThatThrownBy(() -> eval.evaluate(expr, row))
                    .isInstanceOf(EvaluationException.class);
        }
    }

    // ── SetLiteralOperand ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("SetLiteralOperand")
    class SetLiteral {

        @Test @DisplayName("throws when evaluated as scalar")
        void throwsAsScalar() {
            var set = set(AstBuilders.num("1"));
            assertThatThrownBy(() -> eval.evaluate(set, row))
                    .isInstanceOf(EvaluationException.class);
        }
    }

    // ── Built-in functions ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Rand() builtin")
    class Rand {

        @Test
        @DisplayName("returns a NumberValue in [0, 1)")
        void inUnitInterval() {
            for (int i = 0; i < 100; i++) {
                Value v = eval.evaluate(func("Rand"), row);
                assertThat(v).isInstanceOf(NumberValue.class);
                BigDecimal n = ((NumberValue) v).value();
                assertThat(n).isGreaterThanOrEqualTo(BigDecimal.ZERO);
                assertThat(n).isLessThan(BigDecimal.ONE);
            }
        }

        @Test
        @DisplayName("is case-insensitive (RAND / rand / Rand)")
        void caseInsensitive() {
            for (String name : List.of("RAND", "rand", "Rand")) {
                assertThat(eval.evaluate(func(name), row))
                        .isInstanceOf(NumberValue.class);
            }
        }

        @Test
        @DisplayName("rejects arguments")
        void rejectsArgs() {
            assertThatThrownBy(() -> eval.evaluate(
                    func("Rand",AstBuilders.num("1")), row))
                    .isInstanceOf(EvaluationException.class);
        }
    }

    // ── Condition operands (a predicate in operand position) ────────────────────

    @Nested
    @DisplayName("ConditionOperand — boolean-valued predicate")
    class Conditions {

        private ConditionOperand cond(String col, ComparisonOperator op, String n) {
            return condition(cmp(
                    attr(col), op, AstBuilders.num(n)));
        }

        @Test @DisplayName("satisfied comparison → BooleanValue.TRUE")
        void satisfied() {
            // row.id = 42
            assertThat(eval.evaluate(cond("id", ComparisonOperator.GREATER, "10"), row))
                    .isEqualTo(BooleanValue.TRUE);
        }

        @Test @DisplayName("unsatisfied comparison → BooleanValue.FALSE")
        void unsatisfied() {
            assertThat(eval.evaluate(cond("id", ComparisonOperator.GREATER, "100"), row))
                    .isEqualTo(BooleanValue.FALSE);
        }

        @Test @DisplayName("drives IIf: true branch")
        void drivesIifTrue() {
            // IIf(id > 10, "big", "small") → "big"
            var iif = func("IIf",
                    cond("id", ComparisonOperator.GREATER, "10"),
                    AstBuilders.str("big"), AstBuilders.str("small"));
            assertThat(eval.evaluate(iif, row)).isEqualTo(str("big"));
        }

        @Test @DisplayName("drives IIf: false branch")
        void drivesIifFalse() {
            var iif = func("IIf",
                    cond("id", ComparisonOperator.GREATER, "100"),
                    AstBuilders.str("big"), AstBuilders.str("small"));
            assertThat(eval.evaluate(iif, row)).isEqualTo(str("small"));
        }
    }

}

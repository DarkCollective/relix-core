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

import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.DateOperand;
import com.darkcollective.relix.ast.DurationOperand;
import com.darkcollective.relix.ast.TimeOperand;
import com.darkcollective.relix.ast.TimestampOperand;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
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

/**
 * Unit tests for the built-in scalar functions registered in {@link OperandEvaluator}.
 *
 * <p>Covers string, math, conditional, type-check, conversion, trigonometric,
 * logarithmic and date/time functions as well as error-handling edge cases.
 *
 * <p>Two error cases are not here: that a call of the wrong length is rejected, and
 * that a name resolves in any casing. Both were asserted once, for {@code Len}, and
 * both are properties of every signature in the catalogue rather than of that one
 * function — so they moved to {@link FunctionCatalogContractTest}, which asserts them
 * about each installed function.
 */
@DisplayName("OperandEvaluator — built-in function evaluation")
final class BuiltinFunctionEvaluatorTest extends ProcessorTestSupport {

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

    // ── String functions ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("FunctionCall — string functions")
    class StringFunctions {

        @Test @DisplayName("Len returns string length")
        void len() {
            var call = func("Len",AstBuilders.str("hello"));
            assertThat(((NumberValue) eval.evaluate(call, row)).value()
                    .compareTo(BigDecimal.valueOf(5))).isZero();
        }

        @Test @DisplayName("UCase uppercases")
        void ucase() {
            var call = func("UCase",AstBuilders.str("hello"));
            assertThat(eval.evaluate(call, row)).isEqualTo(str("HELLO"));
        }

        @Test @DisplayName("LCase lowercases")
        void lcase() {
            var call = func("LCase",AstBuilders.str("HELLO"));
            assertThat(eval.evaluate(call, row)).isEqualTo(str("hello"));
        }

        @Test @DisplayName("Trim strips both ends")
        void trim() {
            var call = func("Trim",AstBuilders.str("  hi  "));
            assertThat(eval.evaluate(call, row)).isEqualTo(str("hi"));
        }

        @Test @DisplayName("LTrim strips leading whitespace")
        void ltrim() {
            var call = func("LTrim",AstBuilders.str("  hi"));
            assertThat(eval.evaluate(call, row)).isEqualTo(str("hi"));
        }

        @Test @DisplayName("RTrim strips trailing whitespace")
        void rtrim() {
            var call = func("RTrim",AstBuilders.str("hi  "));
            assertThat(eval.evaluate(call, row)).isEqualTo(str("hi"));
        }

        @Test @DisplayName("Left takes first n characters")
        void left() {
            var call = func("Left",AstBuilders.str("hello"), AstBuilders.num("3"));
            assertThat(eval.evaluate(call, row)).isEqualTo(str("hel"));
        }

        @Test @DisplayName("Right takes last n characters")
        void right() {
            var call = func("Right",AstBuilders.str("hello"), AstBuilders.num("3"));
            assertThat(eval.evaluate(call, row)).isEqualTo(str("llo"));
        }

        @Test @DisplayName("Mid(str, start) — 1-based, to end")
        void midTwoArg() {
            var call = func("Mid",AstBuilders.str("hello"), AstBuilders.num("2"));
            assertThat(eval.evaluate(call, row)).isEqualTo(str("ello"));
        }

        @Test @DisplayName("Mid(str, start, len) — 1-based, fixed length")
        void midThreeArg() {
            var call = func("Mid",AstBuilders.str("hello"), AstBuilders.num("2"), AstBuilders.num("3"));
            assertThat(eval.evaluate(call, row)).isEqualTo(str("ell"));
        }

        @Test @DisplayName("InStr(str, find) returns 1-based position")
        void instr() {
            var call = func("InStr",AstBuilders.str("hello"), AstBuilders.str("ll"));
            assertThat(((NumberValue) eval.evaluate(call, row)).value()
                    .compareTo(BigDecimal.valueOf(3))).isZero();
        }

        @Test @DisplayName("InStr returns 0 when not found")
        void instrNotFound() {
            var call = func("InStr",AstBuilders.str("hello"), AstBuilders.str("xyz"));
            assertThat(((NumberValue) eval.evaluate(call, row)).value()
                    .compareTo(BigDecimal.ZERO)).isZero();
        }

        @Test @DisplayName("Chr converts code to character")
        void chr() {
            var call = func("Chr",AstBuilders.num("65"));
            assertThat(eval.evaluate(call, row)).isEqualTo(str("A"));
        }

        @Test @DisplayName("Asc returns character code")
        void asc() {
            var call = func("Asc",AstBuilders.str("A"));
            assertThat(((NumberValue) eval.evaluate(call, row)).value()
                    .compareTo(BigDecimal.valueOf(65))).isZero();
        }

        @Test @DisplayName("Replace substitutes occurrences")
        void replace() {
            var call = func("Replace",AstBuilders.str("hello world"),
                            AstBuilders.str("world"),
                            AstBuilders.str("there"));
            assertThat(eval.evaluate(call, row)).isEqualTo(str("hello there"));
        }

        @Test @DisplayName("string function returns NULL for NULL input")
        void nullPropagation() {
            var nullSchema = schema("s");
            var nullRow = row(nullSchema, nullVal());
            var callOnNull = func("Len",attr("s"));
            assertThat(eval.evaluate(callOnNull, nullRow).isNull()).isTrue();
        }
    }

    // ── Math functions ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("FunctionCall — math functions")
    class MathFunctions {

        @Test @DisplayName("Abs returns absolute value")
        void abs() {
            var call = func("Abs",AstBuilders.num("-5"));
            assertThat(((NumberValue) eval.evaluate(call, row)).value()
                    .compareTo(BigDecimal.valueOf(5))).isZero();
        }

        @Test @DisplayName("Int rounds toward negative infinity")
        void intFloor() {
            // Int(-2.9) = -3 (floor)
            var call = func("Int",AstBuilders.num("-2.9"));
            assertThat(((NumberValue) eval.evaluate(call, row)).value()
                    .compareTo(BigDecimal.valueOf(-3))).isZero();
        }

        @Test @DisplayName("Fix truncates toward zero")
        void fixTruncate() {
            // Fix(-2.9) = -2 (truncate)
            var call = func("Fix",AstBuilders.num("-2.9"));
            assertThat(((NumberValue) eval.evaluate(call, row)).value()
                    .compareTo(BigDecimal.valueOf(-2))).isZero();
        }

        @Test @DisplayName("Round(n) rounds to integer")
        void roundNoPlaces() {
            var call = func("Round",AstBuilders.num("3.6"));
            assertThat(((NumberValue) eval.evaluate(call, row)).value()
                    .compareTo(BigDecimal.valueOf(4))).isZero();
        }

        @Test @DisplayName("Round(n, places) rounds to given decimal places")
        void roundWithPlaces() {
            var call = func("Round",AstBuilders.num("3.145"), AstBuilders.num("2"));
            assertThat(((NumberValue) eval.evaluate(call, row)).value()
                    .compareTo(new BigDecimal("3.15"))).isZero();
        }

        @Test @DisplayName("Ceil rounds up")
        void ceil() {
            var call = func("Ceil",AstBuilders.num("2.1"));
            assertThat(((NumberValue) eval.evaluate(call, row)).value()
                    .compareTo(BigDecimal.valueOf(3))).isZero();
        }

        @Test @DisplayName("Sgn returns sign: -1, 0, 1")
        void sgn() {
            assertSgn("-5", -1);
            assertSgn("0",   0);
            assertSgn("5",   1);
        }

        private void assertSgn(String input, int expected) {
            var call = func("Sgn",AstBuilders.num(input));
            assertThat(((NumberValue) eval.evaluate(call, row)).value()
                    .compareTo(BigDecimal.valueOf(expected))).isZero();
        }

        @Test @DisplayName("Sqr returns square root")
        void sqr() {
            var call = func("Sqr",AstBuilders.num("9"));
            assertThat(((NumberValue) eval.evaluate(call, row)).value()
                    .compareTo(BigDecimal.valueOf(3))).isZero();
        }

        @Test @DisplayName("Sqr rejects negative argument")
        void sqrNegative() {
            var call = func("Sqr",AstBuilders.num("-1"));
            assertThatThrownBy(() -> eval.evaluate(call, row))
                    .isInstanceOf(EvaluationException.class);
        }

        @Test @DisplayName("Power computes exponentiation")
        void power() {
            var call = func("Power",AstBuilders.num("2"), AstBuilders.num("10"));
            assertThat(((NumberValue) eval.evaluate(call, row)).value()
                    .compareTo(BigDecimal.valueOf(1024))).isZero();
        }
    }

    // ── Conditional functions ─────────────────────────────────────────────────

    @Nested
    @DisplayName("FunctionCall — conditional functions")
    class ConditionalFunctions {

        @Test @DisplayName("IIf returns trueVal when condition is true")
        void iifTrue() {
            var call = func("IIf",AstBuilders.bool(true),
                            AstBuilders.str("yes"),
                            AstBuilders.str("no"));
            assertThat(eval.evaluate(call, row)).isEqualTo(str("yes"));
        }

        @Test @DisplayName("IIf returns falseVal when condition is false")
        void iifFalse() {
            var call = func("IIf",AstBuilders.bool(false),
                            AstBuilders.str("yes"),
                            AstBuilders.str("no"));
            assertThat(eval.evaluate(call, row)).isEqualTo(str("no"));
        }

        @Test @DisplayName("Nz(value) returns value when not null")
        void nzNotNull() {
            var call = func("Nz",AstBuilders.str("hello"));
            assertThat(eval.evaluate(call, row)).isEqualTo(str("hello"));
        }

        @Test @DisplayName("Nz(null) returns empty string")
        void nzNull() {
            var nullSchema = schema("x");
            var nullRow = row(nullSchema, nullVal());
            var call = func("Nz",attr("x"));
            assertThat(eval.evaluate(call, nullRow)).isEqualTo(str(""));
        }

        @Test @DisplayName("Nz(null, replacement) returns replacement")
        void nzNullWithReplacement() {
            var nullSchema = schema("x");
            var nullRow = row(nullSchema, nullVal());
            var call = func("Nz",attr("x"), AstBuilders.str("default"));
            assertThat(eval.evaluate(call, nullRow)).isEqualTo(str("default"));
        }

        @Test @DisplayName("Coalesce returns first non-null")
        void coalesceReturnsFirstNonNull() {
            var nullSchema = schema("x", "y");
            var nullRow = row(nullSchema, nullVal(), str("found"));
            var call = func("Coalesce",attr("x"), attr("y"));
            assertThat(eval.evaluate(call, nullRow)).isEqualTo(str("found"));
        }

        @Test @DisplayName("Coalesce returns NULL when all null")
        void coalesceAllNull() {
            var nullSchema = schema("x");
            var nullRow = row(nullSchema, nullVal());
            var call = func("Coalesce",attr("x"));
            assertThat(eval.evaluate(call, nullRow).isNull()).isTrue();
        }
    }

    // ── Type check and conversion ─────────────────────────────────────────────

    @Nested
    @DisplayName("FunctionCall — type check and conversion")
    class TypeCheckAndConversion {

        @Test @DisplayName("IsNull returns true for null value")
        void isNullTrue() {
            var nullSchema = schema("x");
            var nullRow = row(nullSchema, nullVal());
            var call = func("IsNull",attr("x"));
            assertThat(eval.evaluate(call, nullRow)).isEqualTo(BooleanValue.TRUE);
        }

        @Test @DisplayName("IsNull returns false for non-null value")
        void isNullFalse() {
            var call = func("IsNull",AstBuilders.str("x"));
            assertThat(eval.evaluate(call, row)).isEqualTo(BooleanValue.FALSE);
        }

        @Test @DisplayName("IsNumeric returns true for number")
        void isNumericTrue() {
            var call = func("IsNumeric",AstBuilders.num("3"));
            assertThat(eval.evaluate(call, row)).isEqualTo(BooleanValue.TRUE);
        }

        @Test @DisplayName("IsNumeric returns true for numeric string")
        void isNumericStringTrue() {
            var call = func("IsNumeric",AstBuilders.str("3.14"));
            assertThat(eval.evaluate(call, row)).isEqualTo(BooleanValue.TRUE);
        }

        @Test @DisplayName("IsNumeric returns false for non-numeric string")
        void isNumericFalse() {
            var call = func("IsNumeric",AstBuilders.str("abc"));
            assertThat(eval.evaluate(call, row)).isEqualTo(BooleanValue.FALSE);
        }

        @Test @DisplayName("CStr converts number to string")
        void cstrFromNumber() {
            var call = func("CStr",AstBuilders.num("42"));
            assertThat(eval.evaluate(call, row)).isEqualTo(str("42"));
        }

        @Test @DisplayName("CInt rounds number to integer")
        void cint() {
            var call = func("CInt",AstBuilders.num("3.7"));
            assertThat(((NumberValue) eval.evaluate(call, row)).value()
                    .compareTo(BigDecimal.valueOf(4))).isZero();
        }

        @Test @DisplayName("CDbl passes number through unchanged")
        void cdbl() {
            var call = func("CDbl",AstBuilders.num("3.14"));
            assertThat(((NumberValue) eval.evaluate(call, row)).value()
                    .compareTo(new BigDecimal("3.14"))).isZero();
        }
    }

    // ── Error cases ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("FunctionCall — error cases")
    class FunctionErrors {

        @Test @DisplayName("unknown function throws EvaluationException")
        void unknownFunction() {
            var call = func("nonexistentFunc");
            assertThatThrownBy(() -> eval.evaluate(call, row))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("nonexistentFunc");
        }

        @Test @DisplayName("string-typed function rejects non-string argument")
        void stringTypeMismatch() {
            var call = func("UCase",AstBuilders.num("42"));
            assertThatThrownBy(() -> eval.evaluate(call, row))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("expected STRING");
        }

        @Test @DisplayName("number-typed function rejects non-number argument")
        void numberTypeMismatch() {
            var call = func("Sqr",AstBuilders.str("hello"));
            assertThatThrownBy(() -> eval.evaluate(call, row))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("expected NUMBER");
        }

        @Test @DisplayName("IIf rejects non-boolean first argument")
        void iifNonBooleanCondition() {
            var call = func("IIf",AstBuilders.num("1"),
                            AstBuilders.str("yes"), AstBuilders.str("no"));
            assertThatThrownBy(() -> eval.evaluate(call, row))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("BOOLEAN");
        }

        @Test @DisplayName("Coalesce with zero arguments throws")
        void coalesceZeroArgs() {
            var call = func("Coalesce");
            assertThatThrownBy(() -> eval.evaluate(call, row))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("Coalesce");
        }

        @Test @DisplayName("Chr with invalid code throws")
        void chrInvalidCode() {
            // Chr requires 0-127; -1 is invalid
            var call = func("Chr",AstBuilders.num("-1"));
            assertThatThrownBy(() -> eval.evaluate(call, row))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("Chr");
        }
    }

    // ── Trigonometric and logarithmic functions ───────────────────────────────

    @Nested
    @DisplayName("Trigonometric and logarithmic functions")
    class TrigAndLogFunctions {

        @Test @DisplayName("Log returns natural log")
        void log() {
            var call = func("Log",AstBuilders.num("1"));
            NumberValue result = (NumberValue) eval.evaluate(call, row);
            // log(1) == 0
            assertThat(result.value().doubleValue()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test @DisplayName("Exp returns e^x")
        void exp() {
            var call = func("Exp",AstBuilders.num("0"));
            NumberValue result = (NumberValue) eval.evaluate(call, row);
            assertThat(result.value().doubleValue()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test @DisplayName("Sin(0) == 0")
        void sin() {
            var call = func("Sin",AstBuilders.num("0"));
            NumberValue result = (NumberValue) eval.evaluate(call, row);
            assertThat(result.value().doubleValue()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test @DisplayName("Cos(0) == 1")
        void cos() {
            var call = func("Cos",AstBuilders.num("0"));
            NumberValue result = (NumberValue) eval.evaluate(call, row);
            assertThat(result.value().doubleValue()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test @DisplayName("Tan(0) == 0")
        void tan() {
            var call = func("Tan",AstBuilders.num("0"));
            NumberValue result = (NumberValue) eval.evaluate(call, row);
            assertThat(result.value().doubleValue()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test @DisplayName("Atn(1) ≈ π/4")
        void atn() {
            var call = func("Atn",AstBuilders.num("1"));
            NumberValue result = (NumberValue) eval.evaluate(call, row);
            assertThat(result.value().doubleValue())
                    .isCloseTo(Math.PI / 4, org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    // ── Date/time functions ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Date/time functions (typed — ADR-0013)")
    class DateTimeFunctions {

        private final DateOperand date           = date(LocalDate.parse("2024-06-15"));
        private final TimeOperand time           = time(LocalTime.parse("10:30:45"));
        private final TimestampOperand timestamp = timestamp(Instant.parse("2024-06-15T10:30:45Z"));
        private final DurationOperand duration   = duration(Duration.parse("PT1H30M"));

        private int numFn(String name, com.darkcollective.relix.ast.Operand arg) {
            return ((NumberValue) eval.evaluate(func(name,arg), row))
                    .value().intValue();
        }

        @Test @DisplayName("YEAR/MONTH/DAY over a DATE")
        void datePartsOverDate() {
            assertThat(numFn("YEAR", date)).isEqualTo(2024);
            assertThat(numFn("MONTH", date)).isEqualTo(6);
            assertThat(numFn("DAY", date)).isEqualTo(15);
        }

        @Test @DisplayName("YEAR over a TIMESTAMP (UTC)")
        void yearOverTimestamp() {
            assertThat(numFn("YEAR", timestamp)).isEqualTo(2024);
        }

        @Test @DisplayName("HOUR/MINUTE/SECOND over a TIME")
        void timePartsOverTime() {
            assertThat(numFn("HOUR", time)).isEqualTo(10);
            assertThat(numFn("MINUTE", time)).isEqualTo(30);
            assertThat(numFn("SECOND", time)).isEqualTo(45);
        }

        @Test @DisplayName("HOUR over a TIMESTAMP (UTC)")
        void hourOverTimestamp() {
            assertThat(numFn("HOUR", timestamp)).isEqualTo(10);
        }

        @Test @DisplayName("a component function over the wrong type is an error")
        void componentWrongType() {
            assertThatThrownBy(() -> eval.evaluate(
                    func("YEAR",AstBuilders.str("2024-06-15")), row))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("expected DATE or TIMESTAMP");
            assertThatThrownBy(() -> eval.evaluate(func("HOUR",date), row))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("expected TIME or TIMESTAMP");
        }

        @Test @DisplayName("DATE_TRUNC buckets a TIMESTAMP at UTC")
        void dateTrunc() {
            assertThat(eval.evaluate(func("DATE_TRUNC",AstBuilders.str("hour"), timestamp), row))
                    .isEqualTo(new TimestampValue(Instant.parse("2024-06-15T10:00:00Z")));
            assertThat(eval.evaluate(func("DATE_TRUNC",AstBuilders.str("day"), timestamp), row))
                    .isEqualTo(new TimestampValue(Instant.parse("2024-06-15T00:00:00Z")));
            assertThat(eval.evaluate(func("DATE_TRUNC",AstBuilders.str("month"), timestamp), row))
                    .isEqualTo(new TimestampValue(Instant.parse("2024-06-01T00:00:00Z")));
        }

        @Test @DisplayName("DATE_TRUNC with an unknown unit is an error")
        void dateTruncBadUnit() {
            assertThatThrownBy(() -> eval.evaluate(func("DATE_TRUNC",AstBuilders.str("fortnight"), timestamp), row))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("unknown unit");
        }

        @Test @DisplayName("MINUTES/SECONDS/DAYS measure a DURATION")
        void durationMeasures() {
            assertThat(numFn("MINUTES", duration)).isEqualTo(90);   // PT1H30M
            assertThat(numFn("SECONDS", duration)).isEqualTo(5400);
            assertThat(numFn("DAYS", duration(Duration.parse("PT48H")))).isEqualTo(2);
        }

        @Test @DisplayName("NOW/CURRENT_DATE/CURRENT_TIME return the typed current value")
        void currentTime() {
            assertThat(eval.evaluate(func("NOW"), row))
                    .isInstanceOf(TimestampValue.class);
            assertThat(eval.evaluate(func("CURRENT_DATE"), row))
                    .isInstanceOf(DateValue.class);
            assertThat(eval.evaluate(func("CURRENT_TIME"), row))
                    .isInstanceOf(TimeValue.class);
        }

        @Test @DisplayName("to_date / to_timestamp / to_time parse strings into temporal values")
        void conversions() {
            assertThat(eval.evaluate(func("to_date",AstBuilders.str("2024-06-15")), row))
                    .isEqualTo(new DateValue(LocalDate.parse("2024-06-15")));
            assertThat(eval.evaluate(func("to_timestamp",AstBuilders.str("2024-06-15T10:30:45Z")), row))
                    .isEqualTo(new TimestampValue(Instant.parse("2024-06-15T10:30:45Z")));
            assertThat(eval.evaluate(func("to_time",AstBuilders.str("10:30:45")), row))
                    .isEqualTo(new TimeValue(LocalTime.parse("10:30:45")));
        }

        @Test @DisplayName("to_timestamp normalises an offset to UTC")
        void toTimestampNormalisesOffset() {
            assertThat(eval.evaluate(func("to_timestamp",AstBuilders.str("2024-06-15T11:30:45+01:00")), row))
                    .isEqualTo(new TimestampValue(Instant.parse("2024-06-15T10:30:45Z")));
        }

        @Test @DisplayName("to_date with a malformed string is an error")
        void toDateInvalid() {
            assertThatThrownBy(() -> eval.evaluate(func("to_date",AstBuilders.str("not-a-date")), row))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("invalid date");
        }

        @Test @DisplayName("the legacy Jet date builtins were removed")
        void jetBuiltinsRemoved() {
            for (String name : List.of("Date", "DateAdd", "DateDiff", "DatePart", "DateSerial")) {
                assertThatThrownBy(() -> eval.evaluate(func(name), row))
                        .as("%s should be unknown", name)
                        .isInstanceOf(EvaluationException.class)
                        .hasMessageContaining("Unknown function");
            }
        }
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("InStr with start position")
    class InStrWithStart {

        @Test @DisplayName("InStr(start, str, find) returns 1-based position")
        void inStrStartPos() {
            var call = func("InStr",AstBuilders.num("2"), AstBuilders.str("banana"),
                            AstBuilders.str("a"));
            NumberValue result = (NumberValue) eval.evaluate(call, row);
            assertThat(result.value().intValue()).isEqualTo(2);
        }

        @Test @DisplayName("InStr(start, str, find) returns 0 when not found")
        void inStrNotFound() {
            var call = func("InStr",AstBuilders.num("1"), AstBuilders.str("hello"),
                            AstBuilders.str("z"));
            NumberValue result = (NumberValue) eval.evaluate(call, row);
            assertThat(result.value().intValue()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("CInt / CDbl conversion edge cases")
    class ConversionEdgeCases {

        @Test @DisplayName("CInt from string parses and returns number")
        void cintFromString() {
            var call = func("CInt",AstBuilders.str("42"));
            NumberValue result = (NumberValue) eval.evaluate(call, row);
            assertThat(result.value().intValue()).isEqualTo(42);
        }

        @Test @DisplayName("CDbl from string parses and returns number")
        void cdblFromString() {
            var call = func("CDbl",AstBuilders.str("3.14"));
            NumberValue result = (NumberValue) eval.evaluate(call, row);
            assertThat(result.value().doubleValue()).isCloseTo(3.14, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test @DisplayName("CInt from NULL throws EvaluationException")
        void cintFromNull() {
            var nullSchema = schema("x");
            var nullRow    = row(nullSchema, nullVal());
            var call = func("CInt",attr("x"));
            assertThatThrownBy(() -> eval.evaluate(call, nullRow))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("NULL");
        }

        @Test @DisplayName("CDbl from NULL throws EvaluationException")
        void cdblFromNull() {
            var nullSchema = schema("x");
            var nullRow    = row(nullSchema, nullVal());
            var call = func("CDbl",attr("x"));
            assertThatThrownBy(() -> eval.evaluate(call, nullRow))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("NULL");
        }

        @Test @DisplayName("CInt from boolean true returns 1")
        void cintFromBooleanTrue() {
            var boolSchema = schema("flag");
            var boolRow    = row(boolSchema, new BooleanValue(true));
            var call = func("CInt",attr("flag"));
            NumberValue result = (NumberValue) eval.evaluate(call, boolRow);
            assertThat(result.value().intValue()).isEqualTo(1);
        }

        @Test @DisplayName("CDbl from boolean false returns 0")
        void cdblFromBooleanFalse() {
            var boolSchema = schema("flag");
            var boolRow    = row(boolSchema, new BooleanValue(false));
            var call = func("CDbl",attr("flag"));
            NumberValue result = (NumberValue) eval.evaluate(call, boolRow);
            assertThat(result.value().doubleValue()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("requireInt — overflow value")
    class RequireInt {

        @Test @DisplayName("Left(str, count > MAX_INT) throws EvaluationException")
        void overflowArgThrows() {
            var call = func("Left",AstBuilders.str("hello"), AstBuilders.num("9999999999"));
            assertThatThrownBy(() -> eval.evaluate(call, row))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("integer");
        }
    }

    @Nested
    @DisplayName("requireArityRange — wrong argument count")
    class RequireArityRange {

        @Test @DisplayName("Mid with too few args throws EvaluationException")
        void midTooFewArgsThrows() {
            // Mid requires 2–3 args; passing 1 should throw
            var call = func("Mid",AstBuilders.str("hello"));
            assertThatThrownBy(() -> eval.evaluate(call, row))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("Mid");
        }

        @Test @DisplayName("Mid with too many args throws EvaluationException")
        void midTooManyArgsThrows() {
            // Mid requires 2–3 args; passing 4 should throw
            var call = func("Mid",AstBuilders.str("hello"), AstBuilders.num("1"),
                            AstBuilders.num("2"), AstBuilders.num("3"));
            assertThatThrownBy(() -> eval.evaluate(call, row))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("Mid");
        }
    }

    @Nested
    @DisplayName("IsNumeric — type-check edge cases")
    class IsNumericEdgeCases {

        @Test @DisplayName("IsNumeric(boolean) returns false")
        void booleanIsNotNumeric() {
            var call = func("IsNumeric",AstBuilders.bool(true));
            assertThat(eval.evaluate(call, row)).isEqualTo(BooleanValue.FALSE);
        }
    }

}

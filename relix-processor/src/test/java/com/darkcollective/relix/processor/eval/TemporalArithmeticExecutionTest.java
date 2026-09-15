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
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static com.darkcollective.relix.ast.Expr.*;
import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** End-to-end evaluation of the temporal arithmetic algebra (ADR-0013 slice 3, #193). */
@DisplayName("Temporal arithmetic — runtime evaluation")
final class TemporalArithmeticExecutionTest extends com.darkcollective.relix.processor.ProcessorTestSupport {

    private OperandEvaluator eval;
    private PredicateEvaluator pred;
    private Row row;

    @BeforeEach
    void setUp() {
        eval = new OperandEvaluator();
        pred = new PredicateEvaluator(eval);
        row = row(schema(col("dummy", ScalarType.NUMBER)), num(0)); // operands are literals; row unused
    }

    private Value evalBin(Operand l, ArithmeticOperator op, Operand r) {
        return eval.evaluate(arith(l, op, r), row);
    }

    @Nested
    class Computation {
        @Test void timestampMinusTimestamp() {
            assertThat(evalBin(timestamp("2026-06-15T13:40:00Z"), ArithmeticOperator.MINUS, timestamp("2026-06-15T13:10:00Z")))
                    .isEqualTo(new DurationValue(Duration.ofMinutes(30)));
        }

        @Test void timestampPlusDuration() {
            assertThat(evalBin(timestamp("2026-06-15T13:40:00Z"), ArithmeticOperator.PLUS, duration("PT30M")))
                    .isEqualTo(new TimestampValue(Instant.parse("2026-06-15T14:10:00Z")));
        }

        @Test void timestampMinusDuration() {
            assertThat(evalBin(timestamp("2026-06-15T13:40:00Z"), ArithmeticOperator.MINUS, duration("PT40M")))
                    .isEqualTo(new TimestampValue(Instant.parse("2026-06-15T13:00:00Z")));
        }

        @Test void dateMinusDateIsWholeDaySpan() {
            assertThat(evalBin(date("2026-06-18"), ArithmeticOperator.MINUS, date("2026-06-15")))
                    .isEqualTo(new DurationValue(Duration.ofDays(3)));
        }

        @Test void datePlusDurationIsTimestampFromUtcStartOfDay() {
            assertThat(evalBin(date("2026-06-15"), ArithmeticOperator.PLUS, duration("PT12H")))
                    .isEqualTo(new TimestampValue(Instant.parse("2026-06-15T12:00:00Z")));
        }

        @Test void timePlusDurationWrapsWithin24h() {
            assertThat(evalBin(time("23:30:00"), ArithmeticOperator.PLUS, duration("PT1H")))
                    .isEqualTo(new TimeValue(LocalTime.parse("00:30")));
        }

        @Test void durationPlusDuration() {
            assertThat(evalBin(duration("PT30M"), ArithmeticOperator.PLUS, duration("PT30M")))
                    .isEqualTo(new DurationValue(Duration.ofHours(1)));
        }

        @Test void durationMinusDuration() {
            assertThat(evalBin(duration("PT1H"), ArithmeticOperator.MINUS, duration("PT30M")))
                    .isEqualTo(new DurationValue(Duration.ofMinutes(30)));
        }

        @Test void durationTimesNumber() {
            assertThat(evalBin(duration("PT30M"), ArithmeticOperator.MULTIPLY, AstBuilders.num("2")))
                    .isEqualTo(new DurationValue(Duration.ofHours(1)));
        }

        @Test void numberTimesDuration() {
            assertThat(evalBin(AstBuilders.num("2"), ArithmeticOperator.MULTIPLY, duration("PT30M")))
                    .isEqualTo(new DurationValue(Duration.ofHours(1)));
        }

        @Test void durationTimesFractionalNumber() {
            assertThat(evalBin(duration("PT1H"), ArithmeticOperator.MULTIPLY, AstBuilders.num("1.5")))
                    .isEqualTo(new DurationValue(Duration.ofMinutes(90)));
        }

        @Test void durationDividedByNumber() {
            assertThat(evalBin(duration("PT1H"), ArithmeticOperator.DIVIDE, AstBuilders.num("2")))
                    .isEqualTo(new DurationValue(Duration.ofMinutes(30)));
        }

        @Test void durationDividedByDurationIsRatio() {
            assertThat(evalBin(duration("PT1H"), ArithmeticOperator.DIVIDE, duration("PT30M")))
                    .isEqualTo(num("2"));
        }

        @Test void negatedDuration() {
            assertThat(eval.evaluate(unary(duration("PT30M")), row))
                    .isEqualTo(new DurationValue(Duration.ofMinutes(30).negated()));
        }

        @Test void nonTemporalArithmeticStillNumeric() {
            assertThat(evalBin(AstBuilders.num("2"), ArithmeticOperator.PLUS, AstBuilders.num("3")))
                    .isEqualTo(num("5"));
        }
    }

    @Nested
    @DisplayName("String coercion — ISO-8601 strings from inline tables used in temporal arithmetic")
    class StringCoercion {
        private Value evalOnStringRow(String iso, ArithmeticOperator op, Operand right) {
            Row tsRow = row(schema(col("ts", ScalarType.STRING)), str(iso));
            return eval.evaluate(
                    arith(attr("ts"), op, right), tsRow);
        }

        @Test void isoTimestampStringPlusDurationYieldsTimestamp() {
            assertThat(evalOnStringRow("2026-06-15T13:40:00Z", ArithmeticOperator.PLUS, duration("PT30M")))
                    .isEqualTo(new TimestampValue(Instant.parse("2026-06-15T14:10:00Z")));
        }

        @Test void isoTimestampStringMinusDurationYieldsTimestamp() {
            assertThat(evalOnStringRow("2026-06-15T13:40:00Z", ArithmeticOperator.MINUS, duration("PT40M")))
                    .isEqualTo(new TimestampValue(Instant.parse("2026-06-15T13:00:00Z")));
        }

        @Test void isoDateStringPlusDurationYieldsTimestamp() {
            assertThat(evalOnStringRow("2026-06-15", ArithmeticOperator.PLUS, duration("PT12H")))
                    .isEqualTo(new TimestampValue(Instant.parse("2026-06-15T12:00:00Z")));
        }

        @Test void nonTemporalStringStillThrows() {
            assertThatThrownBy(() -> evalOnStringRow("not-a-date", ArithmeticOperator.PLUS, duration("PT1H")))
                    .isInstanceOf(EvaluationException.class);
        }

        @Test void compareStringEqualToTimestamp() {
            assertThat(ValueComparator.compareNonNull(
                    str("2026-06-15T13:40:00Z"),
                    new TimestampValue(Instant.parse("2026-06-15T13:40:00Z"))))
                    .isEqualTo(0);
        }

        @Test void compareStringBeforeTimestamp() {
            assertThat(ValueComparator.compareNonNull(
                    str("2026-06-15T13:00:00Z"),
                    new TimestampValue(Instant.parse("2026-06-15T13:40:00Z"))))
                    .isNegative();
        }

        @Test void compareTimestampAfterString() {
            assertThat(ValueComparator.compareNonNull(
                    new TimestampValue(Instant.parse("2026-06-15T14:00:00Z")),
                    str("2026-06-15T13:40:00Z")))
                    .isPositive();
        }

        @Test void compareStringEqualToDate() {
            assertThat(ValueComparator.compareNonNull(
                    str("2026-06-15"),
                    new DateValue(LocalDate.parse("2026-06-15"))))
                    .isEqualTo(0);
        }
    }

    @Nested
    class Comparison {
        @Test void timestampOrdering() {
            assertThat(pred.evaluate(cmp(
                    timestamp("2026-06-15T13:10:00Z"), ComparisonOperator.LESS, timestamp("2026-06-15T13:40:00Z")), row)).isTrue();
            assertThat(pred.evaluate(cmp(
                    timestamp("2026-06-15T13:40:00Z"), ComparisonOperator.GREATER_EQUAL, timestamp("2026-06-15T13:40:00Z")), row)).isTrue();
        }

        @Test void timestampEquality() {
            assertThat(pred.evaluate(cmp(
                    timestamp("2026-06-15T13:40:00Z"), ComparisonOperator.EQUAL, timestamp("2026-06-15T13:40:00Z")), row)).isTrue();
        }

        @Test void durationOrdering() {
            assertThat(pred.evaluate(cmp(
                    duration("PT30M"), ComparisonOperator.LESS, duration("PT1H")), row)).isTrue();
        }

        @Test void dateOrdering() {
            assertThat(pred.evaluate(cmp(
                    date("2026-06-15"), ComparisonOperator.LESS, date("2026-06-16")), row)).isTrue();
        }
    }

    // =========================================================================
    // The whole table — every operand-kind pair, for every operator
    // =========================================================================

    /**
     * Temporal arithmetic as a cross-product rather than a list of the shapes a query
     * happened to need.
     *
     * <p>Each operator is a run of {@code l instanceof X && r instanceof Y} tests, so the
     * pairs that are <em>not</em> written are as much a part of the contract as the ones
     * that are: an unmatched pair falls to the end and raises, and a pair matched by the
     * wrong arm computes the wrong value silently. The canonical orderings were tested and
     * their commuted twins mostly were not — {@code DURATION + TIMESTAMP} is a separate arm
     * from {@code TIMESTAMP + DURATION} and has to be written out to be reached.
     *
     * <p>{@code null} in the table means "no arm matches": the pair must raise rather than
     * return anything.
     */
    @Nested
    @DisplayName("every operand-kind pair, for every operator")
    class EveryPair {

        private static final Operand TS = timestamp(Instant.parse("2026-06-15T12:00:00Z"));
        private static final Operand DATE = date(LocalDate.parse("2026-06-15"));
        private static final Operand TIME = time(LocalTime.parse("09:00"));
        private static final Operand DUR = duration(Duration.ofHours(2));
        private static final Operand NUM = AstBuilders.num("2");

        /** One cell: the two operands, and the expected value or {@code null} to reject. */
        private record Cell(Operand left, Operand right, Value expected) { }

        private static Cell ok(Operand l, Operand r, Value expected) {
            return new Cell(l, r, expected);
        }

        private static Cell rejected(Operand l, Operand r) {
            return new Cell(l, r, null);
        }

        private void check(ArithmeticOperator op, List<Cell> table) {
            for (Cell c : table) {
                String label = describe(c.left()) + " " + op + " " + describe(c.right());
                if (c.expected() == null) {
                    assertThatThrownBy(() -> evalBin(c.left(), op, c.right()))
                            .as("%s must raise", label)
                            .isInstanceOf(EvaluationException.class);
                } else {
                    assertThat(evalBin(c.left(), op, c.right())).as("%s", label)
                            .isEqualTo(c.expected());
                }
            }
        }

        private static String describe(Operand o) {
            return o.getClass().getSimpleName().replace("Operand", "");
        }

        @Test
        @DisplayName("+ : a duration may be added to any temporal, in either order")
        void plus() {
            Value tsPlus2h = new TimestampValue(Instant.parse("2026-06-15T14:00:00Z"));
            Value dateStartPlus2h = new TimestampValue(Instant.parse("2026-06-15T02:00:00Z"));
            Value timePlus2h = new TimeValue(LocalTime.parse("11:00"));
            check(ArithmeticOperator.PLUS, List.of(
                    ok(TS, DUR, tsPlus2h),
                    ok(DUR, TS, tsPlus2h),
                    ok(DATE, DUR, dateStartPlus2h),
                    ok(DUR, DATE, dateStartPlus2h),
                    ok(TIME, DUR, timePlus2h),
                    ok(DUR, TIME, timePlus2h),
                    ok(DUR, DUR, new DurationValue(Duration.ofHours(4))),
                    // No arm adds two instants, two dates or two times — "when" plus "when"
                    // is not a quantity — nor a bare number to any of them.
                    rejected(TS, TS), rejected(DATE, DATE), rejected(TIME, TIME),
                    rejected(TS, DATE), rejected(DATE, TS), rejected(TS, TIME),
                    rejected(TS, NUM), rejected(NUM, TS),
                    rejected(DATE, NUM), rejected(NUM, DATE),
                    rejected(TIME, NUM), rejected(NUM, TIME),
                    rejected(DUR, NUM), rejected(NUM, DUR)));
        }

        @Test
        @DisplayName("− : two of a kind give a span; a duration shifts a point")
        void minus() {
            check(ArithmeticOperator.MINUS, List.of(
                    ok(TS, TS, new DurationValue(Duration.ZERO)),
                    ok(DATE, DATE, new DurationValue(Duration.ZERO)),
                    ok(TS, DUR, new TimestampValue(Instant.parse("2026-06-15T10:00:00Z"))),
                    ok(DATE, DUR, new TimestampValue(Instant.parse("2026-06-14T22:00:00Z"))),
                    ok(TIME, DUR, new TimeValue(LocalTime.parse("07:00"))),
                    ok(DUR, DUR, new DurationValue(Duration.ZERO)),
                    // Deliberately absent: TIME − TIME (a wall-clock span is ambiguous
                    // across midnight), and subtraction is not commutative, so none of
                    // the reversed forms have an arm.
                    rejected(TIME, TIME),
                    rejected(DUR, TS), rejected(DUR, DATE), rejected(DUR, TIME),
                    rejected(TS, DATE), rejected(DATE, TS),
                    rejected(TS, NUM), rejected(NUM, TS), rejected(DUR, NUM), rejected(NUM, DUR)));
        }

        @Test
        @DisplayName("× : only a duration scaled by a number, in either order")
        void multiply() {
            Value fourHours = new DurationValue(Duration.ofHours(4));
            check(ArithmeticOperator.MULTIPLY, List.of(
                    ok(DUR, NUM, fourHours),
                    ok(NUM, DUR, fourHours),
                    rejected(DUR, DUR),
                    rejected(TS, NUM), rejected(NUM, TS),
                    rejected(DATE, NUM), rejected(NUM, DATE),
                    rejected(TIME, NUM), rejected(NUM, TIME),
                    rejected(TS, DUR), rejected(DUR, TS)));
        }

        @Test
        @DisplayName("÷ : a duration by a number is a duration; by a duration, a ratio")
        void divide() {
            check(ArithmeticOperator.DIVIDE, List.of(
                    ok(DUR, NUM, new DurationValue(Duration.ofHours(1))),
                    ok(DUR, DUR, num("1")),
                    // Division does not commute, so there is no NUMBER ÷ DURATION arm.
                    rejected(NUM, DUR),
                    rejected(TS, NUM), rejected(NUM, TS),
                    rejected(DATE, DUR), rejected(TIME, DUR),
                    rejected(TS, DUR), rejected(DUR, TS)));
        }

        @Test
        @DisplayName("÷ by zero is reported, whichever kind the divisor is")
        void divideByZero() {
            assertThatThrownBy(() -> evalBin(DUR, ArithmeticOperator.DIVIDE, AstBuilders.num("0")))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("Division by zero");
            assertThatThrownBy(() -> evalBin(DUR, ArithmeticOperator.DIVIDE,
                    duration(Duration.ZERO)))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("Division by zero");
        }

        @Test
        @DisplayName("the rejection names both types, so the message says what was tried")
        void rejectionNamesBothTypes() {
            assertThatThrownBy(() -> evalBin(TS, ArithmeticOperator.PLUS, TS))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("add")
                    .hasMessageContaining("TIMESTAMP");
        }
    }
}

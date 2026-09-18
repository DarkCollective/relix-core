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
package com.darkcollective.relix.value;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What differs by {@link Value} kind: the values each stores, the text each displays,
 * the arguments each rejects.
 *
 * <p>What every kind shares — {@code isNull()}, {@code type()}, value-based equality —
 * is one claim repeated ten times and lives in {@link ValueContractTest}, over a corpus
 * held to {@code Value}'s {@code permits} clause. Asserted here per kind, it covered
 * the six kinds in this file and missed whichever the next {@code permits} entry
 * turned out to be.
 */
@DisplayName("Value — sealed runtime value hierarchy")
final class ValueTest {

    // Terse constructors, so a test reads as the assertion it is making. These used
    // to come from relix-processor's ProcessorTestSupport, which is out of reach now
    // that the hierarchy lives in its own module — and unreachable in the right
    // direction: the values do not depend on the executor.
    private static StringValue str(String value) {
        return new StringValue(value);
    }

    private static NumberValue num(String value) {
        return NumberValue.of(value);
    }

    private static BooleanValue bool(boolean value) {
        return BooleanValue.of(value);
    }

    private static NullValue nullVal() {
        return NullValue.INSTANCE;
    }

    @Nested
    @DisplayName("StringValue")
    class StringValueTests {

        @Test
        @DisplayName("stores value as given")
        void storesValue() {
            assertThat(str("hello").value()).isEqualTo("hello");
        }

        @Test
        @DisplayName("asDisplayString() returns the raw value")
        void displayStringIsValue() {
            assertThat(str("hello world").asDisplayString()).isEqualTo("hello world");
        }

        @Test
        @DisplayName("rejects null value")
        void rejectsNull() {
            assertThatThrownBy(() -> new StringValue(null))
                    .isInstanceOf(NullPointerException.class);
        }

    }

    @Nested
    @DisplayName("NumberValue")
    class NumberValueTests {

        @Test
        @DisplayName("stores BigDecimal value")
        void storesBigDecimal() {
            assertThat(num("42").value()).isEqualByComparingTo(new BigDecimal("42"));
        }

        @Test
        @DisplayName("of(String) parses numeric literal")
        void parsesLiteral() {
            assertThat(NumberValue.of("3.14").value()).isEqualByComparingTo(new BigDecimal("3.14"));
        }

        @Test
        @DisplayName("of(String) parses negative number")
        void parsesNegative() {
            assertThat(NumberValue.of("-7").value()).isEqualByComparingTo(new BigDecimal("-7"));
        }

        @Test
        @DisplayName("asDisplayString() strips trailing zeros")
        void displayStringStripsTrailingZeros() {
            assertThat(NumberValue.of("1.50").asDisplayString()).isEqualTo("1.5");
            assertThat(NumberValue.of("3.00").asDisplayString()).isEqualTo("3");
            assertThat(NumberValue.of("0").asDisplayString()).isEqualTo("0");
        }

        @Test
        @DisplayName("asDisplayString() uses plain notation, not scientific")
        void displayStringIsPlain() {
            assertThat(NumberValue.of("1000000").asDisplayString()).isEqualTo("1000000");
            assertThat(NumberValue.of("0.000001").asDisplayString()).isEqualTo("0.000001");
        }

        @Test
        @DisplayName("rejects null text in of()")
        void rejectsNullText() {
            assertThatThrownBy(() -> NumberValue.of(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects non-numeric text in of()")
        void rejectsNonNumericText() {
            assertThatThrownBy(() -> NumberValue.of("abc"))
                    .isInstanceOf(NumberFormatException.class);
        }

    }

    @Nested
    @DisplayName("BooleanValue")
    class BooleanValueTests {

        @Test
        @DisplayName("TRUE constant has value true")
        void trueConstant() {
            assertThat(BooleanValue.TRUE.value()).isTrue();
        }

        @Test
        @DisplayName("FALSE constant has value false")
        void falseConstant() {
            assertThat(BooleanValue.FALSE.value()).isFalse();
        }

        @Test
        @DisplayName("of() returns the appropriate constant")
        void ofReturnsConstant() {
            assertThat(BooleanValue.of(true)).isSameAs(BooleanValue.TRUE);
            assertThat(BooleanValue.of(false)).isSameAs(BooleanValue.FALSE);
        }

        @Test
        @DisplayName("asDisplayString() returns 'true' or 'false'")
        void displayString() {
            assertThat(bool(true).asDisplayString()).isEqualTo("true");
            assertThat(bool(false).asDisplayString()).isEqualTo("false");
        }

    }

    @Nested
    @DisplayName("NullValue")
    class NullValueTests {

        @Test
        @DisplayName("INSTANCE is a singleton")
        void isSingleton() {
            assertThat(NullValue.INSTANCE).isSameAs(NullValue.INSTANCE);
        }

        @Test
        @DisplayName("asDisplayString() returns 'NULL'")
        void displayStringIsNull() {
            assertThat(NullValue.INSTANCE.asDisplayString()).isEqualTo("NULL");
        }
    }

    @Nested
    @DisplayName("Pattern matching over sealed hierarchy")
    class PatternMatchingTests {

        @Test
        @DisplayName("exhaustive switch covers all subtypes")
        void exhaustiveSwitchCoversAllSubtypes() {
            Value[] values = { str("x"), num("1"), bool(true), nullVal() };
            String[] expected = { "string", "number", "boolean", "null" };

            for (int i = 0; i < values.length; i++) {
                Value v = values[i];
                String kind = switch (v) {
                    case StringValue    ignored -> "string";
                    case NumberValue    ignored -> "number";
                    case BooleanValue   ignored -> "boolean";
                    case NullValue      ignored -> "null";
                    case StructValue    ignored -> "struct";
                    case ArrayValue     ignored -> "array";
                    case DateValue      ignored -> "date";
                    case TimeValue      ignored -> "time";
                    case TimestampValue ignored -> "timestamp";
                    case DurationValue  ignored -> "duration";
                };
                assertThat(kind).isEqualTo(expected[i]);
            }
        }
    }
}

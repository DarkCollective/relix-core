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

import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a string as the temporal the other operand already is.
 *
 * <p>An inline table and a schema-on-read source both hand the engine text where a
 * heading may say nothing, so {@code "2024-01-15T10:00:00Z" − DURATION 'PT1H'} has a
 * string on one side and a real value on the other. {@code coerceString} takes the type
 * from the operand that has one.
 *
 * <p>Mutation found all four hint arms uncalled: every one could have returned
 * {@code null} with nothing failing, which means the hint was never supplying a type in
 * any test — the hint-free probe below it was answering every case, and that probe tries
 * {@code TIMESTAMP} first. So a string that is a valid date <em>and</em> a valid
 * timestamp prefix would have been read by the fallback's order rather than by what it
 * was being compared against, and nothing said which had happened.
 */
@DisplayName("Temporal coercion — the hint decides, then the probe")
final class TemporalCoercionTest {

    private static final String TIMESTAMP_TEXT = "2024-01-15T10:30:00Z";
    private static final String DATE_TEXT = "2024-01-15";
    private static final String TIME_TEXT = "10:30:00";
    private static final String DURATION_TEXT = "PT1H30M";

    private static Value coerce(String text, Value hint) {
        return TemporalValueArithmetic.coerceString(new StringValue(text), hint);
    }

    @Nested
    @DisplayName("with a hint")
    final class WithHint {

        @Test
        @DisplayName("each temporal kind reads the string as its own")
        void everyHintArm() {
            assertThat(coerce(TIMESTAMP_TEXT, new TimestampValue(Instant.EPOCH)))
                    .isEqualTo(new TimestampValue(Instant.parse(TIMESTAMP_TEXT)));
            assertThat(coerce(DATE_TEXT, new DateValue(LocalDate.EPOCH)))
                    .isEqualTo(new DateValue(LocalDate.parse(DATE_TEXT)));
            assertThat(coerce(TIME_TEXT, new TimeValue(LocalTime.MIDNIGHT)))
                    .isEqualTo(new TimeValue(LocalTime.parse(TIME_TEXT)));
            assertThat(coerce(DURATION_TEXT, new DurationValue(Duration.ZERO)))
                    .isEqualTo(new DurationValue(Duration.parse(DURATION_TEXT)));
        }

        @Test
        @DisplayName("a DATE hint reads a date, where the hint-free probe would not")
        void theHintOutranksTheProbeOrder() {
            // The fallback probes TIMESTAMP first, so this is the case that proves the
            // hint is consulted at all rather than shadowed by the probe beneath it.
            assertThat(coerce(DATE_TEXT, new DateValue(LocalDate.EPOCH)))
                    .isInstanceOf(DateValue.class);
        }

        @Test
        @DisplayName("a hint the string cannot satisfy falls through to the probe")
        void unparseableUnderTheHint() {
            // "10:30:00" is no duration; the DURATION hint fails and the probe finds the
            // time. Falling through rather than failing is what lets one bad hint in a
            // mixed column cost nothing.
            assertThat(coerce(TIME_TEXT, new DurationValue(Duration.ZERO)))
                    .isEqualTo(new TimeValue(LocalTime.parse(TIME_TEXT)));
        }

        @Test
        @DisplayName("a string that is no temporal at all is returned unchanged")
        void notTemporalAtAll() {
            StringValue text = new StringValue("hello");
            assertThat(TemporalValueArithmetic.coerceString(text, new DateValue(LocalDate.EPOCH)))
                    .isSameAs(text);
        }
    }

    @Nested
    @DisplayName("without a usable hint")
    final class WithoutHint {

        @Test
        @DisplayName("a non-temporal hint leaves the probe to decide")
        void nonTemporalHint() {
            assertThat(coerce(DATE_TEXT, NumberValue.of("1"))).isEqualTo(
                    new DateValue(LocalDate.parse(DATE_TEXT)));
            assertThat(coerce(DURATION_TEXT, new StringValue("x"))).isEqualTo(
                    new DurationValue(Duration.parse(DURATION_TEXT)));
        }

        @Test
        @DisplayName("a value that is not a string is not this method's business")
        void nonString() {
            Value number = NumberValue.of("5");
            assertThat(TemporalValueArithmetic.coerceString(number, number)).isSameAs(number);
        }
    }

    @Nested
    @DisplayName("the cheap gate in front of the parsers")
    final class Gate {

        /**
         * A pre-filter stands in front of the four parse attempts, and it is documented
         * as admitting too much rather than too little: rejecting a string a parser would
         * have accepted is the one way it can change an answer, because that string then
         * stays text and stops matching the temporal it equals.
         *
         * <p>Asserted through {@code coerceString} rather than against the gate itself.
         * The gate is private and is meant to be — from outside, a string it wrongly
         * turned away is simply a string that failed to canonicalise, which is exactly
         * what this checks. Its other direction, admitting too much, is a cost property
         * and has no observable consequence to assert.
         */
        @Test
        @DisplayName("every temporal shape still canonicalises, so nothing is turned away")
        void nothingTheParsersAcceptIsRejected() {
            for (String s : List.of(
                    TIMESTAMP_TEXT, DATE_TEXT, TIME_TEXT, DURATION_TEXT,
                    "2024-01-15T10:30:00.123Z", "2024-01-15T10:30:00+02:00",
                    "00:00:00", "23:59:59", "0001-01-01", "-PT5M", "P1DT2H", "pt1h")) {
                assertThat(coerce(s, NumberValue.of("1")))
                        .as("%s is a temporal literal and must not come back as text", s)
                        .isNotInstanceOf(StringValue.class);
            }
        }

        @Test
        @DisplayName("ordinary text comes back as itself")
        void ordinaryTextIsUntouched() {
            for (String s : List.of("", "hello", "a1", "-", "+", "12", "1234", "2024", "x:30")) {
                assertThat(coerce(s, NumberValue.of("1")))
                        .as("%s denotes no temporal", s)
                        .isInstanceOf(StringValue.class);
            }
        }
    }
}

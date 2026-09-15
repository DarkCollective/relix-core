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
package com.darkcollective.relix.function.builtin;

import com.darkcollective.relix.function.FunctionContext;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;

import static com.darkcollective.relix.function.builtin.BuiltinCalls.call;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.number;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.s;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("The temporal built-ins")
final class DateTimeFunctionsTest {

    private static final Value NULL = NullValue.INSTANCE;
    private static final Value TS =
            new TimestampValue(Instant.parse("2026-07-08T13:40:30Z"));
    private static final Value DATE = new DateValue(LocalDate.of(2026, 7, 8));
    private static final Value TIME = new TimeValue(LocalTime.of(13, 40, 30));

    @Nested
    @DisplayName("The current moment")
    final class CurrentMoment {

        @Test
        @DisplayName("all three read the context's clock, not the platform's")
        void readsTheContextClock() {
            assertThat(call("NOW")).isEqualTo(new TimestampValue(BuiltinCalls.PINNED));
            assertThat(call("CURRENT_DATE")).isEqualTo(new DateValue(LocalDate.of(2026, 7, 8)));
            assertThat(call("CURRENT_TIME")).isEqualTo(new TimeValue(LocalTime.of(13, 40, 30)));
        }

        @Test
        @DisplayName("a different clock gives a different answer, which is the point")
        void followsTheClock() {
            FunctionContext later = FunctionContext.of(
                    Clock.fixed(Instant.parse("2030-01-02T03:04:05Z"), ZoneOffset.UTC));

            assertThat(call(later, "NOW"))
                    .isEqualTo(new TimestampValue(Instant.parse("2030-01-02T03:04:05Z")));
            assertThat(call(later, "CURRENT_DATE"))
                    .isEqualTo(new DateValue(LocalDate.of(2030, 1, 2)));
            assertThat(call(later, "CURRENT_TIME"))
                    .isEqualTo(new TimeValue(LocalTime.of(3, 4, 5)));
        }
    }

    @Nested
    @DisplayName("Component extraction")
    final class Components {

        @Test
        @DisplayName("the date components read a DATE or a TIMESTAMP")
        void dateComponents() {
            assertThat(number(call("YEAR", TS))).isEqualTo("2026");
            assertThat(number(call("MONTH", TS))).isEqualTo("7");
            assertThat(number(call("DAY", TS))).isEqualTo("8");
            assertThat(number(call("YEAR", DATE))).isEqualTo("2026");
            assertThat(number(call("MONTH", DATE))).isEqualTo("7");
            assertThat(number(call("DAY", DATE))).isEqualTo("8");
        }

        @Test
        @DisplayName("the time components read a TIME or a TIMESTAMP")
        void timeComponents() {
            assertThat(number(call("HOUR", TS))).isEqualTo("13");
            assertThat(number(call("MINUTE", TS))).isEqualTo("40");
            assertThat(number(call("SECOND", TS))).isEqualTo("30");
            assertThat(number(call("HOUR", TIME))).isEqualTo("13");
            assertThat(number(call("MINUTE", TIME))).isEqualTo("40");
            assertThat(number(call("SECOND", TIME))).isEqualTo("30");
        }

        @Test
        @DisplayName("each names the temporal kinds it accepts when given another")
        void wrongTemporalKind() {
            assertThatThrownBy(() -> call("YEAR", TIME))
                    .hasMessage("YEAR: expected DATE or TIMESTAMP, got TIME");
            assertThatThrownBy(() -> call("HOUR", DATE))
                    .hasMessage("HOUR: expected TIME or TIMESTAMP, got DATE");
        }

        @Test
        @DisplayName("a NULL input yields NULL")
        void nullIn() {
            for (String name : new String[]{"YEAR", "MONTH", "DAY", "HOUR", "MINUTE",
                    "SECOND", "MINUTES", "SECONDS", "DAYS", "to_date", "to_time",
                    "to_timestamp"}) {
                assertThat(call(name, NULL).isNull()).as(name).isTrue();
            }
            assertThat(call("DATE_TRUNC", s("day"), NULL).isNull()).isTrue();
        }
    }

    @Nested
    @DisplayName("Truncation")
    final class Truncation {

        @Test
        @DisplayName("every unit truncates to the start of its bucket")
        void units() {
            assertThat(call("DATE_TRUNC", s("year"), TS))
                    .isEqualTo(new TimestampValue(Instant.parse("2026-01-01T00:00:00Z")));
            assertThat(call("DATE_TRUNC", s("month"), TS))
                    .isEqualTo(new TimestampValue(Instant.parse("2026-07-01T00:00:00Z")));
            assertThat(call("DATE_TRUNC", s("day"), TS))
                    .isEqualTo(new TimestampValue(Instant.parse("2026-07-08T00:00:00Z")));
            assertThat(call("DATE_TRUNC", s("hour"), TS))
                    .isEqualTo(new TimestampValue(Instant.parse("2026-07-08T13:00:00Z")));
            assertThat(call("DATE_TRUNC", s("minute"), TS))
                    .isEqualTo(new TimestampValue(Instant.parse("2026-07-08T13:40:00Z")));
            assertThat(call("DATE_TRUNC", s("second"), TS))
                    .isEqualTo(new TimestampValue(Instant.parse("2026-07-08T13:40:30Z")));
        }

        @Test
        @DisplayName("the unit is matched without regard to case")
        void unitCase() {
            assertThat(call("DATE_TRUNC", s("DAY"), TS))
                    .isEqualTo(call("DATE_TRUNC", s("day"), TS));
        }

        @Test
        @DisplayName("an unknown unit lists the ones there are")
        void unknownUnit() {
            assertThatThrownBy(() -> call("DATE_TRUNC", s("fortnight"), TS))
                    .hasMessage("DATE_TRUNC: unknown unit 'fortnight' (expected year, "
                            + "month, day, hour, minute, or second)");
        }

        @Test
        @DisplayName("only a TIMESTAMP can be truncated")
        void wrongKind() {
            assertThatThrownBy(() -> call("DATE_TRUNC", s("day"), DATE))
                    .hasMessage("DATE_TRUNC: expected TIMESTAMP, got DATE");
        }
    }

    @Nested
    @DisplayName("Duration measures")
    final class Measures {

        private final Value duration = new DurationValue(Duration.ofSeconds(90 * 60 + 30));

        @Test
        @DisplayName("each measures the whole duration in its own unit")
        void measures() {
            assertThat(number(call("MINUTES", duration))).isEqualTo("90");
            assertThat(number(call("SECONDS", duration))).isEqualTo("5430");
            assertThat(number(call("DAYS", new DurationValue(Duration.ofHours(50)))))
                    .isEqualTo("2");
        }

        @Test
        @DisplayName("only a DURATION can be measured")
        void wrongKind() {
            assertThatThrownBy(() -> call("MINUTES", TS))
                    .hasMessage("MINUTES: expected DURATION, got TIMESTAMP");
        }
    }

    @Nested
    @DisplayName("Parsing")
    final class Parsing {

        @Test
        @DisplayName("each reads the ISO form of its own kind")
        void parses() {
            assertThat(call("to_date", s("2026-07-08")))
                    .isEqualTo(new DateValue(LocalDate.of(2026, 7, 8)));
            assertThat(call("to_time", s("13:40:30")))
                    .isEqualTo(new TimeValue(LocalTime.of(13, 40, 30)));
            assertThat(call("to_timestamp", s("2026-07-08T13:40:30Z")))
                    .isEqualTo(new TimestampValue(Instant.parse("2026-07-08T13:40:30Z")));
        }

        @Test
        @DisplayName("a timestamp is normalised to UTC, and one without an offset is read as UTC")
        void timestampOffsets() {
            assertThat(call("to_timestamp", s("2026-07-08T14:40:30+01:00")))
                    .isEqualTo(new TimestampValue(Instant.parse("2026-07-08T13:40:30Z")));
            assertThat(call("to_timestamp", s("2026-07-08T13:40:30")))
                    .isEqualTo(new TimestampValue(Instant.parse("2026-07-08T13:40:30Z")));
        }

        @Test
        @DisplayName("text that is not a temporal value is quoted back")
        void unparseable() {
            assertThatThrownBy(() -> call("to_date", s("8 July")))
                    .hasMessage("to_date: invalid date '8 July'");
            assertThatThrownBy(() -> call("to_time", s("half past one")))
                    .hasMessage("to_time: invalid time 'half past one'");
            assertThatThrownBy(() -> call("to_timestamp", s("yesterday")))
                    .hasMessage("to_timestamp: invalid timestamp 'yesterday'");
        }
    }
}

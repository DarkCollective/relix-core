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

import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What differs between the four temporal kinds: the {@code java.time} value each
 * stores, the ISO-8601 form each displays, the null component each rejects.
 *
 * <p>{@code isNull()} and {@code type()} were asserted here per kind and in
 * {@code ValueTest} per kind, which is the same claim written out ten times across two
 * files with nothing checking either list against {@code Value}'s {@code permits}
 * clause. They now live in {@link ValueContractTest}, which is held to it.
 */
@DisplayName("Temporal runtime values — DATE / TIME / TIMESTAMP / DURATION")
final class TemporalValueTest {

    @Nested
    @DisplayName("DateValue")
    class Dates {

        private final DateValue v = new DateValue(LocalDate.of(2026, 6, 15));

        @Test @DisplayName("stores the LocalDate")
        void storesValue() {
            assertThat(v.value()).isEqualTo(LocalDate.of(2026, 6, 15));
        }

        @Test @DisplayName("asDisplayString() is canonical ISO-8601")
        void display() {
            assertThat(v.asDisplayString()).isEqualTo("2026-06-15");
        }

        @Test @DisplayName("rejects a null component")
        void rejectsNull() {
            assertThatThrownBy(() -> new DateValue(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("TimeValue")
    class Times {

        private final TimeValue v = new TimeValue(LocalTime.of(13, 40));

        @Test @DisplayName("stores the LocalTime")
        void storesValue() {
            assertThat(v.value()).isEqualTo(LocalTime.of(13, 40));
        }

        @Test @DisplayName("asDisplayString() is canonical ISO-8601")
        void display() {
            assertThat(v.asDisplayString()).isEqualTo("13:40");
        }

        @Test @DisplayName("rejects a null component")
        void rejectsNull() {
            assertThatThrownBy(() -> new TimeValue(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("TimestampValue")
    class Timestamps {

        private final TimestampValue v =
                new TimestampValue(Instant.parse("2026-06-15T13:40:00Z"));

        @Test @DisplayName("stores the Instant")
        void storesValue() {
            assertThat(v.value()).isEqualTo(Instant.parse("2026-06-15T13:40:00Z"));
        }

        @Test @DisplayName("asDisplayString() is a canonical ISO-8601 instant with Z")
        void display() {
            assertThat(v.asDisplayString()).isEqualTo("2026-06-15T13:40:00Z");
        }

        @Test @DisplayName("rejects a null component")
        void rejectsNull() {
            assertThatThrownBy(() -> new TimestampValue(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("DurationValue")
    class Durations {

        private final DurationValue v = new DurationValue(Duration.ofMinutes(30));

        @Test @DisplayName("stores the Duration")
        void storesValue() {
            assertThat(v.value()).isEqualTo(Duration.ofMinutes(30));
        }

        @Test @DisplayName("asDisplayString() is canonical ISO-8601 duration")
        void display() {
            assertThat(v.asDisplayString()).isEqualTo("PT30M");
        }

        @Test @DisplayName("rejects a null component")
        void rejectsNull() {
            assertThatThrownBy(() -> new DurationValue(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test @DisplayName("calendar months are kept apart, and display as years and months")
        void calendarMonths() {
            DurationValue month = new DurationValue(1, Duration.ZERO);
            assertThat(month.isExact()).isFalse();
            assertThat(v.isExact()).isTrue();
            assertThat(month.asDisplayString()).isEqualTo("P1M");
            assertThat(new DurationValue(14, Duration.ofHours(3)).asDisplayString()).isEqualTo("P1Y2MT3H");
            assertThat(new DurationValue(-1, Duration.ZERO).asDisplayString()).isEqualTo("P-1M");
        }

        @Test @DisplayName("a month is as long as 30 days, so P1M equals P30D, and both hash alike")
        void monthsCompareAsThirtyDays() {
            DurationValue month = new DurationValue(1, Duration.ZERO);
            DurationValue thirtyDays = new DurationValue(Duration.ofDays(30));
            assertThat(month.length()).isEqualTo(Duration.ofDays(30));
            assertThat(month).isEqualTo(thirtyDays).hasSameHashCodeAs(thirtyDays);
            assertThat(month).isNotEqualTo(new DurationValue(Duration.ofDays(31))).isNotEqualTo("P1M");
        }
    }

    @Nested
    @DisplayName("ZonedTimestampValue")
    class ZonedTimestamps {

        private final ZonedTimestampValue athens =
                new ZonedTimestampValue(java.time.OffsetDateTime.parse("2026-03-01T09:00:00+02:00"));

        @Test @DisplayName("keeps its offset, displays it, and names an instant")
        void keepsItsOffset() {
            assertThat(athens.type()).isEqualTo(ScalarType.TIMESTAMPTZ);
            assertThat(athens.isNull()).isFalse();
            assertThat(athens.asDisplayString()).isEqualTo("2026-03-01T09:00:00+02:00");
            assertThat(athens.instant()).isEqualTo(java.time.Instant.parse("2026-03-01T07:00:00Z"));
            assertThat(new ZonedTimestampValue(java.time.OffsetDateTime.parse("2026-03-01T07:00:00.5Z"))
                    .asDisplayString()).isEqualTo("2026-03-01T07:00:00.5Z");
        }

        @Test @DisplayName("is the same value as another offset of the same instant, and hashes alike")
        void sameInstantSameValue() {
            ZonedTimestampValue utc = new ZonedTimestampValue(java.time.OffsetDateTime.parse("2026-03-01T07:00:00Z"));
            assertThat(athens).isEqualTo(utc).hasSameHashCodeAs(utc);
            assertThat(athens).isNotEqualTo(new ZonedTimestampValue(java.time.OffsetDateTime.parse("2026-03-01T09:00:00Z")))
                    .isNotEqualTo(new TimestampValue(athens.instant()));
            assertThatThrownBy(() -> new ZonedTimestampValue(null)).isInstanceOf(NullPointerException.class);
        }
    }
}

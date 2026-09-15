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
    }
}

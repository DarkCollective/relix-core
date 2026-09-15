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
package com.darkcollective.relix.ast;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * Parses the ISO-8601 payloads of the typed temporal literals
 * ({@code DATE '…'}, {@code TIME '…'}, {@code TIMESTAMP '…'},
 * {@code DURATION '…'}) into the {@code java.time} values carried by
 * {@link DateOperand}, {@link TimeOperand}, {@link TimestampOperand}, and
 * {@link DurationOperand}.
 *
 * <p>This is the single source of truth for literal payload validation, so the
 * parser (which reports a positioned error on a malformed payload) and the
 * test fixtures share identical parsing — most importantly the {@code TIMESTAMP}
 * UTC-normalisation rule: a payload carrying an offset
 * ({@code Z}, {@code +01:00}) is normalised to a UTC {@link Instant}, and a
 * payload <em>without</em> an offset is interpreted as UTC.
 *
 * <p>Every method throws {@link DateTimeParseException} (the unchecked
 * {@code java.time} parse failure) when the payload is not a valid ISO-8601
 * string of the expected shape; callers translate it into their own diagnostic.
 */
public final class TemporalLiterals {

    private TemporalLiterals() {
    }

    /** Parses an ISO-8601 local-date payload, e.g. {@code "2026-06-15"}. */
    public static LocalDate parseDate(String iso) {
        return LocalDate.parse(iso);
    }

    /** Parses an ISO-8601 local-time payload, e.g. {@code "13:40:00"}. */
    public static LocalTime parseTime(String iso) {
        return LocalTime.parse(iso);
    }

    /**
     * Parses an ISO-8601 timestamp payload into a UTC {@link Instant}.  A payload
     * carrying an offset ({@code "2026-06-15T13:40:00Z"}, {@code "…+01:00"}) is
     * normalised to UTC; a payload without an offset
     * ({@code "2026-06-15T13:40:00"}) is interpreted as UTC.
     */
    public static Instant parseTimestamp(String iso) {
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (DateTimeParseException withoutOffset) {
            return LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC);
        }
    }

    /** Parses an ISO-8601 duration payload, e.g. {@code "PT30M"}. */
    public static Duration parseDuration(String iso) {
        return Duration.parse(iso);
    }
}

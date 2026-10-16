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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * A runtime {@code TIMESTAMPTZ} value: a point on the timeline with the UTC offset it was
 * recorded at, backed by {@link OffsetDateTime} (e.g. {@code 2026-03-01T09:00:00+02:00}).
 * It is SQL's {@code TIMESTAMP WITH TIME ZONE}, as SQL Server's {@code datetimeoffset}
 * keeps it.
 *
 * <p><b>Two zoned timestamps are the same value when they name the same instant</b>, as
 * SQL compares them: {@code 09:00+02:00} equals {@code 07:00Z}. Each keeps the offset it
 * was written with, which is what it displays and what its local time is read in.
 * Equality and hashing read the {@linkplain #instant() instant}, so {@code =}, {@code δ}
 * and {@code γ} agree.
 *
 * @param value the moment and its offset; must not be {@code null}
 */
public record ZonedTimestampValue(OffsetDateTime value) implements Value {

    public ZonedTimestampValue {
        Objects.requireNonNull(value, "value");
    }

    /**
     * The instant this value names, without its offset.
     *
     * @return the instant
     */
    public Instant instant() {
        return value.toInstant();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ZonedTimestampValue other && instant().equals(other.instant());
    }

    @Override
    public int hashCode() {
        return instant().hashCode();
    }

    @Override
    public boolean isNull() {
        return false;
    }

    @Override
    public ScalarType type() {
        return ScalarType.TIMESTAMPTZ;
    }

    /**
     * Returns the ISO-8601 local time and its offset, e.g. {@code "2026-03-01T09:00:00+02:00"},
     * or with {@code Z} for UTC.
     *
     * @return ISO-8601 offset date-time string
     */
    @Override
    public String asDisplayString() {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value);
    }
}

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

import java.time.Duration;
import java.time.Period;
import java.util.Objects;

/**
 * A runtime duration value: a number of calendar {@linkplain #months() months} and an
 * exact elapsed span of seconds and nanoseconds, backed by {@link Duration} (e.g.
 * {@code PT30M} for thirty minutes, {@code P1M} for a month, {@code P1Y2MT12H} for a
 * year, two months and twelve hours).
 *
 * <p>This is PostgreSQL's {@code INTERVAL}, with its days folded into the exact part:
 * a day here is always 24 hours. A month has no fixed length, so it stays a count of
 * months, and adding one to a date or a timestamp moves it to the same day of the next
 * month, or to that month's last day when it is shorter.
 *
 * <p><b>Two durations are the same value when they are as long</b>, reading a month as
 * 30 days, as PostgreSQL compares intervals: {@code P1M} equals {@code P30D}, and is
 * shorter than {@code P31D}. Both keep how they were written, which is what they
 * display. Equality, hashing and order all read the same {@linkplain #length() length},
 * so {@code =}, {@code δ} and {@code τ} agree.
 *
 * @param months the calendar months; negative for a span backwards
 * @param value  the exact elapsed part; must not be {@code null}
 */
public record DurationValue(long months, Duration value) implements Value {

    /** The days a month counts for when a duration is compared or measured, as in PostgreSQL. */
    public static final long DAYS_PER_MONTH = 30;

    public DurationValue {
        Objects.requireNonNull(value, "value");
    }

    /**
     * An exact duration, with no calendar months.
     *
     * @param value the elapsed span; must not be {@code null}
     */
    public DurationValue(Duration value) {
        this(0, value);
    }

    /**
     * Whether this duration is an exact span, with no calendar months.
     *
     * @return {@code true} when {@link #months()} is zero
     */
    public boolean isExact() {
        return months == 0;
    }

    /**
     * How long this duration is, reading each month as {@value #DAYS_PER_MONTH} days:
     * what it is compared, hashed and measured by.
     *
     * @return the length as an exact span
     */
    public Duration length() {
        return value.plusDays(Math.multiplyExact(months, DAYS_PER_MONTH));
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof DurationValue other && length().equals(other.length());
    }

    @Override
    public int hashCode() {
        return length().hashCode();
    }

    @Override
    public boolean isNull() {
        return false;
    }

    @Override
    public ScalarType type() {
        return ScalarType.DURATION;
    }

    /**
     * Returns the canonical ISO-8601 duration string, e.g. {@code "PT30M"},
     * {@code "P1M"} or {@code "P1Y2MT12H"}: the months as years and months, then the
     * exact part as {@link Duration#toString()} writes it.
     *
     * @return ISO-8601 duration string
     */
    @Override
    public String asDisplayString() {
        return iso(months, value);
    }

    /**
     * The ISO-8601 text for {@code months} calendar months and an exact {@code value}.
     *
     * @param months the calendar months
     * @param value  the exact part
     * @return the text, the exact part's own when there are no months
     */
    public static String iso(long months, Duration value) {
        if (months == 0) {
            return value.toString();
        }
        String calendar = Period.ofMonths(Math.toIntExact(months)).normalized().toString();
        return value.isZero() ? calendar : calendar + value.toString().substring(1);
    }
}

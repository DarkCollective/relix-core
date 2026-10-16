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
package com.darkcollective.relix.ast.internal;

import com.darkcollective.relix.ast.DateOperand;
import com.darkcollective.relix.ast.DurationOperand;
import com.darkcollective.relix.ast.TimeOperand;
import com.darkcollective.relix.ast.TimestampOperand;
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

    /**
     * Parses a {@code TIMESTAMPTZ} payload, keeping its offset: ISO-8601 with an offset
     * ({@code "2026-03-01T09:00:00+02:00"}, {@code "…Z"}), or with a region in brackets
     * ({@code "2026-03-01T09:00:00[Europe/London]"}), which takes the region's offset at
     * that moment. A payload with neither is at UTC, as a {@code TIMESTAMP} is read.
     *
     * @throws DateTimeParseException when the payload is not one
     */
    public static OffsetDateTime parseZonedTimestamp(String iso) {
        try {
            return OffsetDateTime.parse(iso);
        } catch (DateTimeParseException notOffset) {
            try {
                int bracket = iso.indexOf('[');
                if (bracket < 0 || !iso.endsWith("]")) {
                    throw notOffset;
                }
                java.time.ZoneId region = java.time.ZoneId.of(iso.substring(bracket + 1, iso.length() - 1));
                String head = iso.substring(0, bracket);
                try {
                    // An offset written before the region is the one kept.
                    return OffsetDateTime.parse(head);
                } catch (DateTimeParseException local) {
                    // A local time in a region: the region's offset at that time.
                    return LocalDateTime.parse(head).atZone(region).toOffsetDateTime();
                }
            } catch (DateTimeParseException notZoned) {
                return LocalDateTime.parse(iso).atOffset(ZoneOffset.UTC);
            }
        }
    }

    /**
     * Parses an ISO-8601 duration payload that has no calendar months, e.g.
     * {@code "PT30M"} or {@code "P1DT2H"}, a day being 24 hours.
     *
     * @throws DateTimeParseException when the payload is malformed, or has months
     */
    public static Duration parseDuration(String iso) {
        Span span = parseSpan(iso);
        if (span.months() != 0) {
            throw new DateTimeParseException("a duration of calendar months is not exact", iso, 0);
        }
        return span.exact();
    }

    /**
     * A duration as a literal states it: a number of calendar months, and an exact part.
     *
     * @param months the calendar months
     * @param exact  the exact part, its days 24 hours each
     */
    public record Span(long months, Duration exact) {
        public Span {
            java.util.Objects.requireNonNull(exact, "exact");
        }
    }

    /**
     * Parses an ISO-8601 duration payload, with or without calendar months:
     * {@code "PT30M"}, {@code "P1M"}, {@code "P1Y2M3DT4H"}, {@code "P2W"}, {@code "-P1M"}.
     * Years are twelve months; weeks and days are exact, 24 hours a day.
     *
     * @throws DateTimeParseException when the payload is not one
     */
    public static Span parseSpan(String iso) {
        String text = iso.strip();
        boolean negative = text.startsWith("-");
        String body = negative || text.startsWith("+") ? text.substring(1) : text;
        if (body.length() < 2 || Character.toUpperCase(body.charAt(0)) != 'P') {
            throw new DateTimeParseException("not an ISO-8601 duration", iso, 0);
        }
        int t = Math.max(body.indexOf('T'), body.indexOf('t'));
        String datePart = t < 0 ? body : body.substring(0, t);
        java.time.Period period = datePart.length() == 1 ? java.time.Period.ZERO : java.time.Period.parse(datePart);
        Duration exact = t < 0 ? Duration.ZERO : Duration.parse("P" + body.substring(t));
        Span span = new Span(period.toTotalMonths(), exact.plusDays(period.getDays()));
        return negative ? new Span(-span.months(), span.exact().negated()) : span;
    }

    /**
     * The interval units {@link #parseInterval} reads, each as months and as seconds;
     * a word may also be written plural.
     */
    private static final java.util.Map<String, long[]> INTERVAL_UNITS = java.util.Map.ofEntries(
            java.util.Map.entry("year", new long[]{12, 0}), java.util.Map.entry("yr", new long[]{12, 0}),
            java.util.Map.entry("y", new long[]{12, 0}),
            java.util.Map.entry("quarter", new long[]{3, 0}),
            java.util.Map.entry("month", new long[]{1, 0}), java.util.Map.entry("mon", new long[]{1, 0}),
            java.util.Map.entry("week", new long[]{0, 604_800}), java.util.Map.entry("w", new long[]{0, 604_800}),
            java.util.Map.entry("day", new long[]{0, 86_400}), java.util.Map.entry("d", new long[]{0, 86_400}),
            java.util.Map.entry("hour", new long[]{0, 3_600}), java.util.Map.entry("hr", new long[]{0, 3_600}),
            java.util.Map.entry("h", new long[]{0, 3_600}),
            java.util.Map.entry("minute", new long[]{0, 60}), java.util.Map.entry("min", new long[]{0, 60}),
            java.util.Map.entry("m", new long[]{0, 60}),
            java.util.Map.entry("second", new long[]{0, 1}), java.util.Map.entry("sec", new long[]{0, 1}),
            java.util.Map.entry("s", new long[]{0, 1}));

    private static final java.util.regex.Pattern INTERVAL_PART = java.util.regex.Pattern.compile(
            "\\s*(?:([+-]?\\d+(?:\\.\\d+)?)\\s*([a-zA-Z]+)|([+-]?)(\\d+):(\\d{2})(?::(\\d{2}(?:\\.\\d+)?))?)");

    private static final java.math.BigDecimal NANOS_PER_SECOND = java.math.BigDecimal.valueOf(1_000_000_000L);

    /**
     * Parses an interval as SQL writes one: ISO-8601 ({@code "P1M"}), or PostgreSQL's
     * text, {@code "1 month"}, {@code "2 years 3 mons"}, {@code "1 day 02:30:00"},
     * {@code "3 hours ago"}. A unit is year, quarter, month, week, day, hour, minute or
     * second, singular or plural, or its short form (yr, mon, hr, min, sec, y, w, d, h,
     * m, s). A number may have a fraction: as in PostgreSQL, a fraction of a year is
     * whole months, the rest dropped, and a fraction of a month is days at 30 a month.
     *
     * @throws DateTimeParseException when the text is not an interval
     */
    public static Span parseInterval(String text) {
        String body = text.strip();
        if (body.startsWith("P") || body.startsWith("p") || body.startsWith("-P") || body.startsWith("+P")) {
            return parseSpan(body);
        }
        boolean ago = body.toLowerCase(java.util.Locale.ROOT).endsWith(" ago");
        if (ago) {
            body = body.substring(0, body.length() - 4);
        }
        long months = 0;
        java.math.BigDecimal nanos = java.math.BigDecimal.ZERO;
        java.util.regex.Matcher m = INTERVAL_PART.matcher(body);
        int at = 0;
        boolean any = false;
        while (at < body.length()) {
            if (!m.find(at) || m.start() != at) {
                throw new DateTimeParseException("not an interval", text, at);
            }
            any = true;
            if (m.group(2) != null) {
                String word = m.group(2).toLowerCase(java.util.Locale.ROOT);
                long[] unit = INTERVAL_UNITS.get(word);
                if (unit == null && word.endsWith("s")) {
                    unit = INTERVAL_UNITS.get(word.substring(0, word.length() - 1));
                }
                if (unit == null) {
                    throw new DateTimeParseException("unknown interval unit '" + m.group(2) + "'", text, m.start(2));
                }
                java.math.BigDecimal amount = new java.math.BigDecimal(m.group(1));
                if (unit[0] == 0) {
                    nanos = nanos.add(amount.multiply(java.math.BigDecimal.valueOf(unit[1])).multiply(NANOS_PER_SECOND));
                } else if (unit[0] == 12) {
                    // A fraction of a year is whole months; the rest is dropped.
                    months += amount.multiply(java.math.BigDecimal.valueOf(12)).longValue();
                } else {
                    java.math.BigDecimal total = amount.multiply(java.math.BigDecimal.valueOf(unit[0]));
                    long whole = total.longValue();
                    months += whole;
                    // A fraction of a month is days, at 30 a month.
                    nanos = nanos.add(total.subtract(java.math.BigDecimal.valueOf(whole))
                            .multiply(java.math.BigDecimal.valueOf(30L * 86_400)).multiply(NANOS_PER_SECOND));
                }
            } else {
                java.math.BigDecimal clock = java.math.BigDecimal.valueOf(Long.parseLong(m.group(4)) * 3_600
                        + Long.parseLong(m.group(5)) * 60)
                        .add(m.group(6) == null ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(m.group(6)));
                clock = clock.multiply(NANOS_PER_SECOND);
                nanos = m.group(3).equals("-") ? nanos.subtract(clock) : nanos.add(clock);
            }
            at = m.end();
        }
        if (!any) {
            throw new DateTimeParseException("not an interval", text, 0);
        }
        java.math.BigInteger[] parts = nanos.setScale(0, java.math.RoundingMode.HALF_UP).toBigInteger()
                .divideAndRemainder(NANOS_PER_SECOND.toBigInteger());
        Duration exact = Duration.ofSeconds(parts[0].longValueExact(), parts[1].longValue());
        return ago ? new Span(-months, exact.negated()) : new Span(months, exact);
    }

    /**
     * The ISO-8601 text for a duration of {@code months} calendar months and an exact
     * part, as {@code DURATION} prints: the exact part's own text when there are no
     * months, else the months as years and months followed by the exact part's time.
     *
     * @param months the calendar months
     * @param exact  the exact part
     * @return the text
     */
    public static String formatSpan(long months, Duration exact) {
        if (months == 0) {
            return exact.toString();
        }
        String calendar = java.time.Period.ofMonths(Math.toIntExact(months)).normalized().toString();
        return exact.isZero() ? calendar : calendar + exact.toString().substring(1);
    }
}

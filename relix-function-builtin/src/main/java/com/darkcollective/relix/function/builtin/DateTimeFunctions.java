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

import com.darkcollective.relix.function.Arity;
import com.darkcollective.relix.function.PushdownSpelling;
import com.darkcollective.relix.function.PushdownTarget;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.ZonedTimestampValue;
import com.darkcollective.relix.value.Value;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

import static com.darkcollective.relix.function.builtin.Arguments.reject;
import static com.darkcollective.relix.function.builtin.Arguments.string;
import static com.darkcollective.relix.function.builtin.Category.PURE_DETERMINISTIC;
import static com.darkcollective.relix.function.builtin.Category.STABLE;
import static com.darkcollective.relix.function.builtin.Category.p;
import static com.darkcollective.relix.symbol.ScalarType.ANY;
import static com.darkcollective.relix.symbol.ScalarType.DATE;
import static com.darkcollective.relix.symbol.ScalarType.DURATION;
import static com.darkcollective.relix.symbol.ScalarType.NUMBER;
import static com.darkcollective.relix.symbol.ScalarType.STRING;
import static com.darkcollective.relix.symbol.ScalarType.TIME;
import static com.darkcollective.relix.symbol.ScalarType.TIMESTAMP;
import static com.darkcollective.relix.symbol.ScalarType.TIMESTAMPTZ;

/**
 * The temporal built-ins: the current instant, component extraction, truncation,
 * duration measures, and parsing text into a temporal value.
 *
 * <p>These operate on real temporal values, not on formatted strings, and everything
 * that needs a zone uses UTC — a timestamp is an instant, and the component functions
 * must not depend on where the query happens to run.
 *
 * <p>Most of them can also be evaluated by the backend holding the data, so they carry a
 * spelling: extraction becomes {@code EXTRACT(unit FROM …)} in SQL and
 * {@code {"$year": …}} in an aggregation pipeline. A spelling declines where the backend
 * has no faithful equivalent, and the engine then evaluates the call itself.
 *
 * <p>The three current-time functions read the clock from the context rather than the
 * platform, which is what makes a run against a pinned clock reproducible — and is why
 * they alone among the temporal built-ins carry no backend spelling: a call the database
 * evaluated would read the database's clock instead. They declare {@code STABLE}: one
 * value for a whole run, a different one next time, so the engine may evaluate the call
 * once and send the <em>value</em> to a backend even though it may not send the call.
 */
final class DateTimeFunctions {

    private static final Category DATETIME = Category.of("datetime");

    /** A SQL dialect confirmed to have {@code date_trunc}, in relix's argument order. */
    private static final String POSTGRES = "postgres";

    /** The dialect that reaches the same truncation through {@code DATE_FORMAT}. */
    private static final String MYSQL = "mysql";

    /** @see #POSTGRES */
    private static final String DUCKDB = "duckdb";

    /**
     * The dialect with no date or time types, and so none of the temporal functions: a
     * SQLite "date" is TEXT, a REAL or an INTEGER by the application's convention.
     */
    private static final String SQLITE = "sqlite";

    /**
     * The dialect with no {@code EXTRACT}, whose {@code DATEPART} reads a
     * {@code DATETIMEOFFSET} in its own offset rather than at UTC.
     */
    private static final String SQLSERVER = "sqlserver";

    /** A dialect with {@code DATE_TRUNC} in relix's argument order, since Db2 11.1. */
    private static final String DB2 = "db2";

    private DateTimeFunctions() {
    }

    static List<ScalarFunction> all() {
        return List.of(
                // No backend spelling, and there never can be one: a folded clock call
                // would read the backend's clock, and the whole point of taking the
                // instant from the context is that the session decides what "now" means.
                // STABLE is how these reach a database anyway — the engine evaluates the
                // call once against the run's pinned instant and sends the resulting
                // literal, which is a value the backend has no opinion about.
                DATETIME.contextual("NOW", TIMESTAMP, STABLE, List.of(),
                        (context, args) -> new TimestampValue(utc(context))),

                DATETIME.contextual("CURRENT_DATE", DATE, STABLE, List.of(),
                        (context, args) -> new DateValue(
                                LocalDate.ofInstant(utc(context), ZoneOffset.UTC))),

                DATETIME.contextual("CURRENT_TIME", TIME, STABLE, List.of(),
                        (context, args) -> new TimeValue(
                                LocalTime.ofInstant(utc(context), ZoneOffset.UTC))),

                // YEAR/MONTH/DAY accept a DATE or a TIMESTAMP and HOUR/MINUTE/SECOND a
                // TIME or a TIMESTAMP. The parameter is declared ANY because a union of
                // two types is not expressible as one; the body states which it means.
                DATETIME.fn("YEAR", NUMBER, PURE_DETERMINISTIC, List.of(p("d", ANY)),
                        part("YEAR", "$year"),
                        args -> datePart(args, "YEAR", LocalDate::getYear)),

                DATETIME.fn("MONTH", NUMBER, PURE_DETERMINISTIC, List.of(p("d", ANY)),
                        part("MONTH", "$month"),
                        args -> datePart(args, "MONTH", LocalDate::getMonthValue)),

                DATETIME.fn("DAY", NUMBER, PURE_DETERMINISTIC, List.of(p("d", ANY)),
                        part("DAY", "$dayOfMonth"),
                        args -> datePart(args, "DAY", LocalDate::getDayOfMonth)),

                DATETIME.fn("HOUR", NUMBER, PURE_DETERMINISTIC, List.of(p("t", ANY)),
                        part("HOUR", "$hour"),
                        args -> timePart(args, "HOUR", LocalTime::getHour)),

                DATETIME.fn("MINUTE", NUMBER, PURE_DETERMINISTIC, List.of(p("t", ANY)),
                        part("MINUTE", "$minute"),
                        args -> timePart(args, "MINUTE", LocalTime::getMinute)),

                DATETIME.fn("SECOND", NUMBER, PURE_DETERMINISTIC, List.of(p("t", ANY)),
                        part("SECOND", "$second"),
                        args -> timePart(args, "SECOND", LocalTime::getSecond)),

                // The calendar fields SQL's EXTRACT names beyond the six above, each a
                // function of its own and each answering as PostgreSQL's field does.
                // DOW counts Sunday as 0; ISODOW counts Monday as 1 and Sunday as 7.
                DATETIME.fn("DOW", NUMBER, PURE_DETERMINISTIC, List.of(p("d", ANY)),
                        DateTimeFunctions::dowSpelling,
                        args -> datePart(args, "DOW", d -> d.getDayOfWeek().getValue() % 7)),

                DATETIME.fn("ISODOW", NUMBER, PURE_DETERMINISTIC, List.of(p("d", ANY)),
                        DateTimeFunctions::isodowSpelling,
                        args -> datePart(args, "ISODOW", d -> d.getDayOfWeek().getValue())),

                DATETIME.fn("DOY", NUMBER, PURE_DETERMINISTIC, List.of(p("d", ANY)),
                        field("DOY", "DAYOFYEAR", "dayofyear", "$dayOfYear"),
                        args -> datePart(args, "DOY", LocalDate::getDayOfYear)),

                // The ISO week: weeks start on Monday, and week 1 is the one holding the
                // year's first Thursday, so 1 January may fall in week 52 or 53 of the
                // year before. ISOYEAR is the year that week belongs to.
                DATETIME.fn("WEEK", NUMBER, PURE_DETERMINISTIC, List.of(p("d", ANY)),
                        field("WEEK", null, "iso_week", "$isoWeek"),
                        args -> datePart(args, "WEEK", d -> d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))),

                DATETIME.fn("ISOYEAR", NUMBER, PURE_DETERMINISTIC, List.of(p("d", ANY)),
                        field("ISOYEAR", null, null, "$isoWeekYear"),
                        args -> datePart(args, "ISOYEAR", d -> d.get(IsoFields.WEEK_BASED_YEAR))),

                DATETIME.fn("QUARTER", NUMBER, PURE_DETERMINISTIC, List.of(p("d", ANY)),
                        field("QUARTER", "QUARTER", "quarter", null),
                        args -> datePart(args, "QUARTER", d -> d.get(IsoFields.QUARTER_OF_YEAR))),

                // EPOCH is seconds since 1970-01-01T00:00:00Z for a timestamp or a date (its
                // midnight at UTC), seconds since midnight for a time, and a duration's
                // length in seconds — each with its fraction, as PostgreSQL's is.
                DATETIME.fn("EPOCH", NUMBER, PURE_DETERMINISTIC, List.of(p("t", ANY)),
                        DateTimeFunctions::epochSpelling, DateTimeFunctions::epoch),

                // ts AT TIME ZONE zone: the wall-clock time in the zone at the instant ts,
                // as a TIMESTAMP that reads it at UTC. PostgreSQL's timezone(zone, ts).
                // TIMEZONE(zone, ts) is ts AT TIME ZONE zone: a TIMESTAMP's wall clock in the
                // zone, or a TIMESTAMPTZ's instant at the zone's offset.
                DATETIME.contextual("TIMEZONE", TIMESTAMP, PURE_DETERMINISTIC,
                        List.of(p("zone", STRING), p("ts", ANY)), Arity.exactly(2),
                        types -> types.get(1) == TIMESTAMPTZ ? TIMESTAMPTZ : TIMESTAMP, PushdownSpelling.NONE,
                        (context, args) -> timezone(args)),

                DATETIME.fn("MAKE_DATE", DATE, PURE_DETERMINISTIC,
                        List.of(p("year", NUMBER), p("month", NUMBER), p("day", NUMBER)),
                        DateTimeFunctions::makeDateSpelling, DateTimeFunctions::makeDate),

                DATETIME.fn("LAST_DAY", DATE, PURE_DETERMINISTIC, List.of(p("d", ANY)),
                        Spellings.sqlOn("LAST_DAY", 1, Spellings.MYSQL),
                        args -> lastDay(args.get(0))),

                // DATEDIFF(unit, start, end): the unit boundaries crossed from start to
                // end, as SQL Server counts them, each read at UTC.
                DATETIME.fn("DATEDIFF", NUMBER, PURE_DETERMINISTIC,
                        List.of(p("unit", STRING), p("start", ANY), p("end", ANY)),
                        DateTimeFunctions::dateDiffSpelling, DateTimeFunctions::dateDiff),

                // A TIMESTAMPTZ is truncated on its own clock and keeps its offset.
                DATETIME.contextual("DATE_TRUNC", TIMESTAMP, PURE_DETERMINISTIC,
                        List.of(p("unit", STRING), p("ts", TIMESTAMP)), Arity.exactly(2),
                        types -> types.get(1) == TIMESTAMPTZ ? TIMESTAMPTZ : TIMESTAMP,
                        DateTimeFunctions::dateTruncSpelling, (context, args) -> dateTrunc(args)),

                // DATEADD(unit, n, d): SQL Server's, and MySQL's TIMESTAMPADD. A month from the
                // 31st lands on a shorter month's last day, as d + INTERVAL does.
                DATETIME.contextual("DATEADD", ANY, PURE_DETERMINISTIC,
                        List.of(p("unit", STRING), p("n", NUMBER), p("d", ANY)), Arity.exactly(3),
                        types -> temporalResult(types.get(2)), PushdownSpelling.NONE,
                        (context, args) -> dateAdd(args)),

                // ADD_MONTHS(d, n): Oracle's, whose last day of a month stays a last day.
                DATETIME.contextual("ADD_MONTHS", ANY, PURE_DETERMINISTIC,
                        List.of(p("d", ANY), p("n", NUMBER)), Arity.exactly(2),
                        types -> temporalResult(types.get(0)), PushdownSpelling.NONE,
                        (context, args) -> addMonths(args)),

                // DATE_ADD(d, INTERVAL …) and DATE_SUB: MySQL's d + interval and d - interval,
                // a DATE staying a DATE.
                DATETIME.contextual("DATE_ADD", ANY, PURE_DETERMINISTIC,
                        List.of(p("d", ANY), p("interval", DURATION)), Arity.exactly(2),
                        types -> temporalResult(types.get(0)), PushdownSpelling.NONE,
                        (context, args) -> dateAddInterval(args, "DATE_ADD", 1)),
                DATETIME.contextual("DATE_SUB", ANY, PURE_DETERMINISTIC,
                        List.of(p("d", ANY), p("interval", DURATION)), Arity.exactly(2),
                        types -> temporalResult(types.get(0)), PushdownSpelling.NONE,
                        (context, args) -> dateAddInterval(args, "DATE_SUB", -1)),

                // AGE(end, start): PostgreSQL's, the years, months and days between them.
                DATETIME.fn("AGE", DURATION, PURE_DETERMINISTIC, List.of(p("end", ANY), p("start", ANY)),
                        DateTimeFunctions::age),

                DATETIME.fn("MINUTES", NUMBER, PURE_DETERMINISTIC, List.of(p("d", DURATION)),
                        args -> measure(args, "MINUTES", Duration::toMinutes)),

                DATETIME.fn("SECONDS", NUMBER, PURE_DETERMINISTIC, List.of(p("d", DURATION)),
                        args -> measure(args, "SECONDS", Duration::getSeconds)),

                DATETIME.fn("DAYS", NUMBER, PURE_DETERMINISTIC, List.of(p("d", DURATION)),
                        args -> measure(args, "DAYS", Duration::toDays)),

                // Each reads ISO-8601 text, and converts the other temporal kinds as SQL's
                // CAST does, reading a timestamp at UTC: a timestamp's date or time of
                // day, and a date's midnight. CAST(x AS DATE | TIME | TIMESTAMP) is these.
                DATETIME.fn("to_date", DATE, PURE_DETERMINISTIC, List.of(p("s", ANY)),
                        args -> convert(args, "to_date", "date", DateTimeFunctions::toDate)),

                DATETIME.fn("to_timestamp", TIMESTAMP, PURE_DETERMINISTIC, List.of(p("s", ANY)),
                        args -> convert(args, "to_timestamp", "timestamp", DateTimeFunctions::toTimestamp)),

                DATETIME.fn("to_time", TIME, PURE_DETERMINISTIC, List.of(p("s", ANY)),
                        args -> convert(args, "to_time", "time", DateTimeFunctions::toTime)),

                // CAST(x AS TIMESTAMPTZ): text keeps its offset, an instant is at UTC.
                DATETIME.fn("to_timestamptz", TIMESTAMPTZ, PURE_DETERMINISTIC, List.of(p("s", ANY)),
                        args -> convert(args, "to_timestamptz", "timestamptz", DateTimeFunctions::toTimestamptz)));
    }

    // ── Implementations ───────────────────────────────────────────────────────

    private static Instant utc(com.darkcollective.relix.function.FunctionContext context) {
        return context.clock().instant();
    }

    /** Extracts a date component from a {@code DATE} or a {@code TIMESTAMP} read at UTC. */
    private static Value datePart(List<Value> args, String function,
                                  ToIntFunction<LocalDate> component) {
        Value value = args.get(0);
        if (value.isNull()) {
            return NullValue.INSTANCE;
        }
        LocalDate date = switch (value) {
            case DateValue d -> d.value();
            case TimestampValue ts -> LocalDate.ofInstant(ts.value(), ZoneOffset.UTC);
            case ZonedTimestampValue z -> z.value().toLocalDate();
            default -> throw reject(
                    function + ": expected DATE or TIMESTAMP, got " + value.type());
        };
        return number(component.applyAsInt(date));
    }

    /** Extracts a time component from a {@code TIME} or a {@code TIMESTAMP} read at UTC. */
    private static Value timePart(List<Value> args, String function,
                                  ToIntFunction<LocalTime> component) {
        Value value = args.get(0);
        if (value.isNull()) {
            return NullValue.INSTANCE;
        }
        LocalTime time = switch (value) {
            case TimeValue t -> t.value();
            case TimestampValue ts -> LocalTime.ofInstant(ts.value(), ZoneOffset.UTC);
            case ZonedTimestampValue z -> z.value().toLocalTime();
            default -> throw reject(
                    function + ": expected TIME or TIMESTAMP, got " + value.type());
        };
        return number(component.applyAsInt(time));
    }

    /** Truncates a {@code TIMESTAMP}, read at UTC, to the start of the named unit. */
    private static Value dateTrunc(List<Value> args) {
        String unit = string(args.get(0), "DATE_TRUNC").toLowerCase(Locale.ROOT);
        Value value = args.get(1);
        if (value.isNull()) {
            return NullValue.INSTANCE;
        }
        LocalDateTime moment = switch (value) {
            case TimestampValue ts -> LocalDateTime.ofInstant(ts.value(), ZoneOffset.UTC);
            case ZonedTimestampValue z -> z.value().toLocalDateTime();
            default -> throw reject("DATE_TRUNC: expected TIMESTAMP, got " + value.type());
        };
        LocalDateTime truncated = switch (unit) {
            case "year" -> moment.toLocalDate().withDayOfYear(1).atStartOfDay();
            case "month" -> moment.toLocalDate().withDayOfMonth(1).atStartOfDay();
            case "day" -> moment.toLocalDate().atStartOfDay();
            case "hour" -> moment.truncatedTo(ChronoUnit.HOURS);
            case "minute" -> moment.truncatedTo(ChronoUnit.MINUTES);
            case "second" -> moment.truncatedTo(ChronoUnit.SECONDS);
            default -> throw reject("DATE_TRUNC: unknown unit '" + unit
                    + "' (expected year, month, day, hour, minute, or second)");
        };
        return value instanceof ZonedTimestampValue z
                ? new ZonedTimestampValue(truncated.atOffset(z.value().getOffset()))
                : new TimestampValue(truncated.toInstant(ZoneOffset.UTC));
    }

    /** Measures a {@code DURATION} as a whole number of the given unit. */
    private static Value measure(List<Value> args, String function,
                                 ToLongFunction<Duration> unit) {
        Value value = args.get(0);
        if (value.isNull()) {
            return NullValue.INSTANCE;
        }
        if (!(value instanceof DurationValue duration)) {
            throw reject(function + ": expected DURATION, got " + value.type());
        }
        // A month is measured as 30 days, as durations are compared.
        return number(unit.applyAsLong(duration.length()));
    }

    /**
     * Converts a value to a temporal kind, passing NULL through and naming a value that
     * cannot be read or converted.
     */
    private static Value convert(List<Value> args, String function, String kind,
                                 java.util.function.Function<Value, Value> conversion) {
        Value value = args.get(0);
        if (value.isNull()) {
            return NullValue.INSTANCE;
        }
        try {
            return conversion.apply(value);
        } catch (DateTimeException e) {
            throw reject(function + ": invalid " + kind + " '" + value.asDisplayString() + "'");
        } catch (ClassCastException e) {
            throw reject(function + ": cannot convert " + value.type() + " to a " + kind);
        }
    }

    /** A date: text naming one (or a timestamp, whose UTC date it is), a timestamp's UTC date, or a date. */
    private static Value toDate(Value value) {
        return switch (value) {
            case DateValue d -> d;
            case TimestampValue ts -> new DateValue(LocalDate.ofInstant(ts.value(), ZoneOffset.UTC));
            case ZonedTimestampValue z -> new DateValue(z.value().toLocalDate());
            case com.darkcollective.relix.value.StringValue text -> {
                try {
                    yield new DateValue(LocalDate.parse(text.value()));
                } catch (DateTimeParseException notADate) {
                    yield new DateValue(LocalDate.ofInstant(instant(text.value()), ZoneOffset.UTC));
                }
            }
            default -> throw new ClassCastException();
        };
    }

    /** A timestamp: text naming one (or a date, whose midnight it is), a date's midnight at UTC, or a timestamp. */
    private static Value toTimestamp(Value value) {
        return switch (value) {
            case TimestampValue ts -> ts;
            case ZonedTimestampValue z -> new TimestampValue(z.instant());
            case DateValue d -> new TimestampValue(d.value().atStartOfDay().toInstant(ZoneOffset.UTC));
            case com.darkcollective.relix.value.StringValue text -> {
                try {
                    yield new TimestampValue(instant(text.value()));
                } catch (DateTimeParseException notATimestamp) {
                    yield new TimestampValue(LocalDate.parse(text.value()).atStartOfDay().toInstant(ZoneOffset.UTC));
                }
            }
            default -> throw new ClassCastException();
        };
    }

    /** A time of day: text naming one, a timestamp's time of day at UTC, or a time. */
    private static Value toTime(Value value) {
        return switch (value) {
            case TimeValue t -> t;
            case TimestampValue ts -> new TimeValue(LocalTime.ofInstant(ts.value(), ZoneOffset.UTC));
            case ZonedTimestampValue z -> new TimeValue(z.value().toLocalTime());
            case com.darkcollective.relix.value.StringValue text -> new TimeValue(LocalTime.parse(text.value()));
            default -> throw new ClassCastException();
        };
    }

    /**
     * {@code CAST(x AS TIMESTAMPTZ)}: a TIMESTAMPTZ as it is, text with the offset it
     * carries (at UTC without one), a TIMESTAMP at UTC, and a DATE at its UTC midnight.
     */
    private static Value toTimestamptz(Value value) {
        return switch (value) {
            case ZonedTimestampValue z -> z;
            case TimestampValue ts -> new ZonedTimestampValue(ts.value().atOffset(ZoneOffset.UTC));
            case DateValue d -> new ZonedTimestampValue(d.value().atStartOfDay().atOffset(ZoneOffset.UTC));
            case com.darkcollective.relix.value.StringValue text -> new ZonedTimestampValue(zoned(text.value()));
            default -> throw new ClassCastException();
        };
    }

    /** ISO-8601 text with an offset, or a bracketed region, or neither and so at UTC. */
    private static OffsetDateTime zoned(String text) {
        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException notOffset) {
            try {
                int bracket = text.indexOf('[');
                if (bracket < 0 || !text.endsWith("]")) {
                    throw notOffset;
                }
                java.time.ZoneId region = java.time.ZoneId.of(text.substring(bracket + 1, text.length() - 1));
                String head = text.substring(0, bracket);
                try {
                    // An offset written before the region is the one kept.
                    return OffsetDateTime.parse(head);
                } catch (DateTimeParseException local) {
                    // A local time in a region: the region's offset at that time.
                    return LocalDateTime.parse(head).atZone(region).toOffsetDateTime();
                }
            } catch (DateTimeParseException notZoned) {
                return LocalDateTime.parse(text).atOffset(ZoneOffset.UTC);
            }
        }
    }

    /** Time-zone names in any letter case, as PostgreSQL reads them: the IANA regions, and UTC. */
    private static final Map<String, java.time.ZoneId> ZONES = zones();

    private static Map<String, java.time.ZoneId> zones() {
        Map<String, java.time.ZoneId> zones = new java.util.HashMap<>();
        for (String id : java.time.ZoneId.getAvailableZoneIds()) {
            // An offset written as a name, Etc/GMT+2 or UTC+2, counts west where an ISO
            // offset counts east; POSIX and Java disagree on it, so neither is offered.
            if (!id.matches(".*[+-]\\d.*")) {
                zones.put(id.toLowerCase(Locale.ROOT), java.time.ZoneId.of(id));
            }
        }
        zones.put("utc", ZoneOffset.UTC);
        return Map.copyOf(zones);
    }

    /** {@code TIMEZONE(zone, ts)}: the wall-clock time in {@code zone} at the instant {@code ts}, read at UTC. */
    private static Value timezone(List<Value> args) {
        if (args.get(0).isNull() || args.get(1).isNull()) {
            return NullValue.INSTANCE;
        }
        String name = string(args.get(0), "TIMEZONE");
        java.time.ZoneId zone = ZONES.get(name.strip().toLowerCase(Locale.ROOT));
        if (zone == null) {
            throw reject("TIMEZONE: unknown time zone '" + name + "'; use a region name such as 'Europe/London', or 'UTC'");
        }
        return switch (args.get(1)) {
            case TimestampValue ts -> new TimestampValue(LocalDateTime.ofInstant(ts.value(), zone).toInstant(ZoneOffset.UTC));
            // The same instant, at the zone's offset then, as SQL Server converts a datetimeoffset.
            case ZonedTimestampValue z -> new ZonedTimestampValue(z.value().atZoneSameInstant(zone).toOffsetDateTime());
            default -> throw reject("TIMEZONE: expected TIMESTAMP or TIMESTAMPTZ, got " + args.get(1).type());
        };
    }

    /** {@code MAKE_DATE(year, month, day)}: the date, refusing one the calendar does not have. */
    private static Value makeDate(List<Value> args) {
        if (args.stream().anyMatch(Value::isNull)) {
            return NullValue.INSTANCE;
        }
        int year = Arguments.integer(args.get(0), "MAKE_DATE");
        int month = Arguments.integer(args.get(1), "MAKE_DATE");
        int day = Arguments.integer(args.get(2), "MAKE_DATE");
        try {
            if (year < 1) {
                throw new DateTimeException("year");
            }
            return new DateValue(LocalDate.of(year, month, day));
        } catch (DateTimeException e) {
            throw reject("MAKE_DATE: date field value out of range: " + year + "-" + month + "-" + day);
        }
    }

    /** {@code MAKE_DATE} on PostgreSQL and DuckDB, and SQL Server's {@code DATEFROMPARTS}. */
    private static Optional<String> makeDateSpelling(PushdownTarget target, List<String> arguments) {
        if (!target.isFamily(PushdownTarget.SQL) || arguments.size() != 3) {
            return Optional.empty();
        }
        if (target.isVariant(POSTGRES) || target.isVariant(DUCKDB)) {
            return Optional.of("make_date(" + String.join(", ", arguments) + ")");
        }
        if (target.isVariant(SQLSERVER)) {
            return Optional.of("DATEFROMPARTS(" + String.join(", ", arguments) + ")");
        }
        return Optional.empty();
    }

    /** The calendar date of a DATE or TIMESTAMP, the latter read at UTC. */
    private static LocalDateTime moment(Value value, String function) {
        return switch (value) {
            case DateValue d -> d.value().atStartOfDay();
            case TimestampValue ts -> LocalDateTime.ofInstant(ts.value(), ZoneOffset.UTC);
            case ZonedTimestampValue z -> z.value().toLocalDateTime();
            default -> throw reject(function + ": expected DATE or TIMESTAMP, got " + value.type());
        };
    }

    /** A DATE or a TIMESTAMP stays what it is; anything else is known only when it runs. */
    private static com.darkcollective.relix.symbol.ScalarType temporalResult(
            com.darkcollective.relix.symbol.ScalarType argument) {
        return argument == DATE || argument == TIMESTAMP || argument == TIMESTAMPTZ ? argument : ANY;
    }

    /** A whole count, as SQL Server's DATEADD and Oracle's ADD_MONTHS take one: a fraction is cut off. */
    private static long whole(Value value, String function) {
        if (!(value instanceof NumberValue n)) {
            throw reject(function + ": expected a NUMBER of units, got " + value.type());
        }
        return n.value().setScale(0, java.math.RoundingMode.DOWN).longValueExact();
    }

    /** A DATE or TIMESTAMP moved by {@code months} and {@code exact}, keeping its kind. */
    private static Value moved(Value value, long months, Duration exact, String function) {
        return switch (value) {
            case DateValue d when exact.toNanos() % 86_400_000_000_000L == 0 ->
                    new DateValue(d.value().plusMonths(months).plusDays(exact.toDays()));
            case DateValue d -> throw reject(function + ": a DATE moved by part of a day is a"
                    + " TIMESTAMP; write CAST(" + d.value() + " AS TIMESTAMP)");
            case TimestampValue ts -> new TimestampValue(
                    ts.value().atOffset(ZoneOffset.UTC).plusMonths(months).toInstant().plus(exact));
            case ZonedTimestampValue z -> new ZonedTimestampValue(z.value().plusMonths(months).plus(exact));
            default -> throw reject(function + ": expected DATE or TIMESTAMP, got " + value.type());
        };
    }

    /** {@code DATEADD(unit, n, d)}: {@code n} whole units added to {@code d}, read at UTC. */
    private static Value dateAdd(List<Value> args) {
        if (args.stream().anyMatch(Value::isNull)) {
            return NullValue.INSTANCE;
        }
        String unit = string(args.get(0), "DATEADD").strip().toLowerCase(Locale.ROOT);
        String canonical = DATEDIFF_UNITS.get(unit);
        if (canonical == null) {
            throw reject("DATEADD: unknown unit '" + unit
                    + "' (expected year, quarter, month, week, day, hour, minute or second)");
        }
        long n = whole(args.get(1), "DATEADD");
        return switch (canonical) {
            case "year" -> moved(args.get(2), Math.multiplyExact(n, 12), Duration.ZERO, "DATEADD");
            case "quarter" -> moved(args.get(2), Math.multiplyExact(n, 3), Duration.ZERO, "DATEADD");
            case "month" -> moved(args.get(2), n, Duration.ZERO, "DATEADD");
            case "week" -> moved(args.get(2), 0, Duration.ofDays(Math.multiplyExact(n, 7)), "DATEADD");
            case "day" -> moved(args.get(2), 0, Duration.ofDays(n), "DATEADD");
            case "hour" -> moved(args.get(2), 0, Duration.ofHours(n), "DATEADD");
            case "minute" -> moved(args.get(2), 0, Duration.ofMinutes(n), "DATEADD");
            default -> moved(args.get(2), 0, Duration.ofSeconds(n), "DATEADD");
        };
    }

    /**
     * {@code ADD_MONTHS(d, n)}: {@code d} moved {@code n} whole months, as Oracle moves it.
     * A day the target month lacks becomes its last day, and so does the last day of a
     * month: ADD_MONTHS of 28 February 2026 by one is 31 March.
     */
    private static Value addMonths(List<Value> args) {
        if (args.stream().anyMatch(Value::isNull)) {
            return NullValue.INSTANCE;
        }
        long n = whole(args.get(1), "ADD_MONTHS");
        LocalDateTime at = moment(args.get(0), "ADD_MONTHS");
        LocalDate date = at.toLocalDate();
        LocalDate target = date.plusMonths(n);
        if (date.getDayOfMonth() == date.lengthOfMonth()) {
            target = target.withDayOfMonth(target.lengthOfMonth());
        }
        return switch (args.get(0)) {
            case DateValue ignored -> new DateValue(target);
            case ZonedTimestampValue z -> new ZonedTimestampValue(target.atTime(at.toLocalTime()).atOffset(z.value().getOffset()));
            default -> new TimestampValue(target.atTime(at.toLocalTime()).toInstant(ZoneOffset.UTC));
        };
    }

    /** {@code DATE_ADD(d, interval)} and {@code DATE_SUB}: {@code d} moved by the interval, keeping its kind. */
    private static Value dateAddInterval(List<Value> args, String function, int sign) {
        if (args.stream().anyMatch(Value::isNull)) {
            return NullValue.INSTANCE;
        }
        if (!(args.get(1) instanceof DurationValue interval)) {
            throw reject(function + ": expected a DURATION, as INTERVAL 1 MONTH, got " + args.get(1).type());
        }
        return moved(args.get(0), sign * interval.months(),
                sign > 0 ? interval.value() : interval.value().negated(), function);
    }

    /**
     * {@code AGE(end, start)}: the time from {@code start} to {@code end} in years, months
     * and days, as PostgreSQL's {@code age} reckons it, both read at UTC. Each field is
     * subtracted on its own and a negative one borrows from the next: a day borrows the
     * length of the earlier argument's month, so from 13 June to 10 April is 9 months and
     * 27 days. Earlier to later is negative throughout.
     */
    private static Value age(List<Value> args) {
        if (args.stream().anyMatch(Value::isNull)) {
            return NullValue.INSTANCE;
        }
        LocalDateTime end = moment(args.get(0), "AGE");
        LocalDateTime start = moment(args.get(1), "AGE");
        boolean backwards = end.isBefore(start);
        LocalDateTime later = backwards ? start : end;
        LocalDateTime earlier = backwards ? end : start;
        long nanos = later.toLocalTime().toNanoOfDay() - earlier.toLocalTime().toNanoOfDay();
        long days = later.getDayOfMonth() - earlier.getDayOfMonth();
        long months = later.getMonthValue() - earlier.getMonthValue();
        long years = later.getYear() - earlier.getYear();
        if (nanos < 0) {
            nanos += 86_400_000_000_000L;
            days--;
        }
        if (days < 0) {
            // PostgreSQL borrows the month of the argument on the borrowing side: the start's.
            days += (backwards ? end : start).toLocalDate().lengthOfMonth();
            months--;
        }
        if (months < 0) {
            months += 12;
            years--;
        }
        long totalMonths = years * 12 + months;
        Duration exact = Duration.ofDays(days).plusNanos(nanos);
        return backwards ? new DurationValue(-totalMonths, exact.negated()) : new DurationValue(totalMonths, exact);
    }

    /** {@code LAST_DAY(d)}: the last day of the month {@code d} falls in. */
    private static Value lastDay(Value value) {
        if (value.isNull()) {
            return NullValue.INSTANCE;
        }
        LocalDate date = moment(value, "LAST_DAY").toLocalDate();
        return new DateValue(date.withDayOfMonth(date.lengthOfMonth()));
    }

    /** The units {@code DATEDIFF} counts, by every name SQL Server gives them. */
    private static final Map<String, String> DATEDIFF_UNITS = Map.ofEntries(
            Map.entry("year", "year"), Map.entry("yy", "year"), Map.entry("yyyy", "year"),
            Map.entry("quarter", "quarter"), Map.entry("qq", "quarter"), Map.entry("q", "quarter"),
            Map.entry("month", "month"), Map.entry("mm", "month"), Map.entry("m", "month"),
            Map.entry("week", "week"), Map.entry("wk", "week"), Map.entry("ww", "week"),
            Map.entry("day", "day"), Map.entry("dd", "day"), Map.entry("d", "day"),
            Map.entry("hour", "hour"), Map.entry("hh", "hour"),
            Map.entry("minute", "minute"), Map.entry("mi", "minute"), Map.entry("n", "minute"),
            Map.entry("second", "second"), Map.entry("ss", "second"), Map.entry("s", "second"));

    private static String dateDiffUnit(Value unit) {
        String written = string(unit, "DATEDIFF");
        String canonical = DATEDIFF_UNITS.get(written.strip().toLowerCase(Locale.ROOT));
        if (canonical == null) {
            throw reject("DATEDIFF: unknown unit '" + written
                    + "' (expected year, quarter, month, week, day, hour, minute or second)");
        }
        return canonical;
    }

    /**
     * {@code DATEDIFF(unit, start, end)}: how many boundaries of the unit lie between
     * {@code start} and {@code end}, negative when {@code end} is earlier. Months count
     * month starts crossed, so 31 January to 1 February is 1; a week starts on Monday.
     */
    private static Value dateDiff(List<Value> args) {
        if (args.stream().anyMatch(Value::isNull)) {
            return NullValue.INSTANCE;
        }
        String unit = dateDiffUnit(args.get(0));
        LocalDateTime a = moment(args.get(1), "DATEDIFF");
        LocalDateTime b = moment(args.get(2), "DATEDIFF");
        long n = switch (unit) {
            case "year" -> b.getYear() - a.getYear();
            case "quarter" -> (b.getYear() * 4L + (b.getMonthValue() - 1) / 3)
                    - (a.getYear() * 4L + (a.getMonthValue() - 1) / 3);
            case "month" -> (b.getYear() * 12L + b.getMonthValue()) - (a.getYear() * 12L + a.getMonthValue());
            case "week" -> ChronoUnit.WEEKS.between(
                    a.toLocalDate().with(java.time.DayOfWeek.MONDAY), b.toLocalDate().with(java.time.DayOfWeek.MONDAY));
            case "day" -> ChronoUnit.DAYS.between(a.toLocalDate(), b.toLocalDate());
            case "hour" -> ChronoUnit.HOURS.between(a.truncatedTo(ChronoUnit.HOURS), b.truncatedTo(ChronoUnit.HOURS));
            case "minute" -> ChronoUnit.MINUTES.between(a.truncatedTo(ChronoUnit.MINUTES), b.truncatedTo(ChronoUnit.MINUTES));
            default -> ChronoUnit.SECONDS.between(a.truncatedTo(ChronoUnit.SECONDS), b.truncatedTo(ChronoUnit.SECONDS));
        };
        return number(n);
    }

    /**
     * SQL Server's {@code DATEDIFF}, which counts boundaries as this does, at UTC; its
     * week starts on the session's {@code DATEFIRST}, so a week runs in the engine.
     * The unit is read back out of its rendered literal, since it is SQL Server's
     * keyword rather than a value there.
     */
    private static Optional<String> dateDiffSpelling(PushdownTarget target, List<String> arguments) {
        if (!target.isFamily(PushdownTarget.SQL) || !target.isVariant(SQLSERVER) || arguments.size() != 3) {
            return Optional.empty();
        }
        return Spellings.literal(arguments.get(0).startsWith("N'") ? arguments.get(0).substring(1) : arguments.get(0))
                .map(u -> DATEDIFF_UNITS.get(u.strip().toLowerCase(Locale.ROOT)))
                .filter(u -> !u.equals("week"))
                .map(u -> "DATEDIFF(" + u + ", SWITCHOFFSET(" + arguments.get(1) + ", '+00:00'), SWITCHOFFSET("
                        + arguments.get(2) + ", '+00:00'))");
    }

    /** {@code EPOCH}: seconds, with their fraction, as PostgreSQL's {@code EXTRACT(EPOCH …)} counts them. */
    private static Value epoch(List<Value> args) {
        Value value = args.get(0);
        return switch (value) {
            case NullValue ignored -> NullValue.INSTANCE;
            case TimestampValue ts -> seconds(ts.value().getEpochSecond(), ts.value().getNano());
            case ZonedTimestampValue z -> seconds(z.instant().getEpochSecond(), z.instant().getNano());
            case DateValue d -> number(d.value().toEpochDay() * 86_400L);
            case TimeValue t -> seconds(t.value().toSecondOfDay(), t.value().getNano());
            case DurationValue d -> seconds(d.length().getSeconds(), d.length().getNano());
            default -> throw reject("EPOCH: expected TIMESTAMP, DATE, TIME or DURATION, got " + value.type());
        };
    }

    private static NumberValue seconds(long whole, int nanos) {
        BigDecimal value = BigDecimal.valueOf(whole);
        return new NumberValue(nanos == 0 ? value
                : value.add(BigDecimal.valueOf(nanos, 9)).stripTrailingZeros());
    }

    /**
     * Reads an ISO-8601 timestamp as a UTC instant. A payload carrying an offset is
     * normalised to UTC; one without an offset is read as already being UTC — the same
     * rule the parser applies to a {@code TIMESTAMP} literal, restated here because the
     * AST sits above this library and a shared helper would be an edge back into it.
     */
    private static Instant instant(String iso) {
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (DateTimeParseException withoutOffset) {
            return LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC);
        }
    }

    private static NumberValue number(long n) {
        return new NumberValue(BigDecimal.valueOf(n));
    }

    // ── Backend spellings ─────────────────────────────────────────────────────

    /**
     * A one-argument component extraction: {@code EXTRACT(unit FROM e)} in SQL,
     * <code>&#123;"$op": e&#125;</code> in an aggregation pipeline.
     *
     * <p>The operator names are fixed identifiers with nothing in them a JSON escape
     * would touch, so they are quoted directly rather than through a JSON writer the
     * SPI cannot reach.
     */
    private static PushdownSpelling part(String sqlUnit, String mongoOperator) {
        return (target, arguments) -> {
            if (arguments.size() != 1) {
                return Optional.empty();
            }
            String argument = arguments.get(0);
            if (target.isFamily(PushdownTarget.SQL)) {
                // SQLite has no EXTRACT, and no date type for one to read: its strftime
                // parses whatever text the column holds, which is a different function.
                if (target.isVariant(SQLITE)) {
                    return Optional.empty();
                }
                // SQL Server spells it DATEPART, which reads a DATETIMEOFFSET's hour in
                // the offset it was stored with. SWITCHOFFSET to +00:00 first makes it
                // UTC's, as the engine's is, and leaves a zone-less DATETIME2, a DATE or
                // a TIME as it was — all four checked against a running server.
                if (target.isVariant(SQLSERVER)) {
                    return Optional.of("DATEPART(" + sqlUnit + ", SWITCHOFFSET(" + argument
                            + ", '+00:00'))");
                }
                return Optional.of("EXTRACT(" + sqlUnit + " FROM " + argument + ")");
            }
            if (target.isFamily(PushdownTarget.MONGO)) {
                return Optional.of("{\"" + mongoOperator + "\": " + argument + "}");
            }
            return Optional.empty();
        };
    }

    /**
     * One of the calendar fields beyond the six {@link #part} spells, per backend:
     * {@code EXTRACT(field FROM e)} on PostgreSQL and DuckDB, whose fields mean what the
     * engine's do; MySQL's function of the same meaning where it has one; SQL Server's
     * {@code DATEPART} at UTC where it has the part; and the pipeline operator in
     * MongoDB. A {@code null} name is a backend with no such field, which declines.
     */
    private static PushdownSpelling field(String sqlField, String mysqlFunction,
                                          String sqlServerPart, String mongoOperator) {
        return (target, arguments) -> {
            if (arguments.size() != 1) {
                return Optional.empty();
            }
            String argument = arguments.get(0);
            if (target.isFamily(PushdownTarget.SQL)) {
                if (target.isVariant(POSTGRES) || target.isVariant(DUCKDB)) {
                    return Optional.of("EXTRACT(" + sqlField + " FROM " + argument + ")");
                }
                if (target.isVariant(MYSQL) && mysqlFunction != null) {
                    return Optional.of(mysqlFunction + "(" + argument + ")");
                }
                if (target.isVariant(SQLSERVER) && sqlServerPart != null) {
                    return Optional.of("DATEPART(" + sqlServerPart + ", SWITCHOFFSET(" + argument
                            + ", '+00:00'))");
                }
                return Optional.empty();
            }
            if (target.isFamily(PushdownTarget.MONGO) && mongoOperator != null) {
                return Optional.of("{\"" + mongoOperator + "\": " + argument + "}");
            }
            return Optional.empty();
        };
    }

    /**
     * {@code DOW}, Sunday 0 to Saturday 6. MySQL's {@code DAYOFWEEK} and MongoDB's
     * {@code $dayOfWeek} count Sunday as 1, so they are one more. SQL Server's weekday
     * depends on the session's {@code DATEFIRST}, which a statement cannot see, so it
     * declines.
     */
    private static Optional<String> dowSpelling(PushdownTarget target, List<String> arguments) {
        if (target.isFamily(PushdownTarget.SQL) && target.isVariant(MYSQL) && arguments.size() == 1) {
            return Optional.of("(DAYOFWEEK(" + arguments.get(0) + ") - 1)");
        }
        if (target.isFamily(PushdownTarget.MONGO) && arguments.size() == 1) {
            return Optional.of("{\"$subtract\": [{\"$dayOfWeek\": " + arguments.get(0) + "}, 1]}");
        }
        return field("DOW", null, null, null).render(target, arguments);
    }

    /** {@code ISODOW}, Monday 1 to Sunday 7: MySQL's {@code WEEKDAY} counts Monday as 0. */
    private static Optional<String> isodowSpelling(PushdownTarget target, List<String> arguments) {
        if (target.isFamily(PushdownTarget.SQL) && target.isVariant(MYSQL) && arguments.size() == 1) {
            return Optional.of("(WEEKDAY(" + arguments.get(0) + ") + 1)");
        }
        return field("ISODOW", null, null, "$isoDayOfWeek").render(target, arguments);
    }

    /**
     * {@code EPOCH} on PostgreSQL alone. MySQL's {@code UNIX_TIMESTAMP} reads a
     * {@code DATETIME} in the session's zone, and the other backends' forms are not
     * confirmed here to keep the fraction of a second, so they run in the engine.
     */
    private static Optional<String> epochSpelling(PushdownTarget target, List<String> arguments) {
        if (target.isFamily(PushdownTarget.SQL) && target.isVariant(POSTGRES) && arguments.size() == 1) {
            return Optional.of("EXTRACT(EPOCH FROM " + arguments.get(0) + ")");
        }
        return Optional.empty();
    }

    /**
     * {@code DATE_TRUNC(unit, ts)} per backend. Postgres and DuckDB take the arguments in
     * relix's order, MongoDB spells it as a pipeline operator, and MySQL — which has no
     * {@code DATE_TRUNC} — reaches the same result through a per-unit
     * {@code DATE_FORMAT} pattern. The generic dialect declines: it is an
     * unidentified backend, and a wrong spelling is worse than evaluating the call
     * in-engine.
     */
    private static Optional<String> dateTruncSpelling(PushdownTarget target,
                                                      List<String> arguments) {
        if (arguments.size() != 2) {
            return Optional.empty();
        }
        String unit = arguments.get(0);
        String timestamp = arguments.get(1);
        if (target.isFamily(PushdownTarget.SQL)) {
            if (target.isVariant(POSTGRES) || target.isVariant(DUCKDB) || target.isVariant(DB2)) {
                return Optional.of("date_trunc(" + unit + ", " + timestamp + ")");
            }
            if (target.isVariant(MYSQL)) {
                return mysqlDateTrunc(unit, timestamp);
            }
            return Optional.empty();
        }
        if (target.isFamily(PushdownTarget.MONGO)) {
            return Optional.of("{\"$dateTrunc\": {\"date\": " + timestamp
                    + ", \"unit\": " + unit + "}}");
        }
        return Optional.empty();
    }

    /**
     * The {@code DATE_FORMAT} pattern for each unit MySQL can truncate to.
     *
     * <p>Every pattern carries the whole time-of-day the result type implies, because
     * relix's {@code DATE_TRUNC} returns a {@code TIMESTAMP} rather than the calendar
     * unit it truncated to: truncating to a month yields the first instant of that
     * month, not a month.
     */
    private static final Map<String, String> MYSQL_TRUNCATION_PATTERNS = Map.of(
            "year",   "%Y-01-01 00:00:00",
            "month",  "%Y-%m-01 00:00:00",
            "day",    "%Y-%m-%d 00:00:00",
            "hour",   "%Y-%m-%d %H:00:00",
            "minute", "%Y-%m-%d %H:%i:00",
            "second", "%Y-%m-%d %H:%i:%s");

    /**
     * {@code DATE_TRUNC} for MySQL and MariaDB, which have no function of that name —
     * the name belongs to Postgres, BigQuery and Snowflake, and the last two are where
     * the timestamp-first argument order comes from. What MySQL has is
     * {@code DATE_FORMAT}, which reaches the same value by writing out the parts to
     * keep and zeroing the rest.
     *
     * <p>The {@code CAST} is not cosmetic. {@code DATE_FORMAT} returns a string, and a
     * relix {@code DATE_TRUNC} column is a {@code TIMESTAMP} the JDBC connector reads
     * by asking for a {@code LocalDateTime}; handed a {@code VARCHAR} it coerces or
     * throws, and only in the pushed plan. NULL propagates through both functions and
     * both sides drop sub-second precision, so those two agree without help.
     *
     * <h2>Why this arm reads its own argument, when no other spelling does</h2>
     * A spelling is handed arguments <em>already rendered in the target's syntax</em>,
     * so the unit arrives as the SQL literal {@code 'day'} — quotes included. Postgres
     * and MongoDB never notice, because {@code date_trunc} and {@code $dateTrunc} take
     * the unit as a value and the backend branches on it. MySQL is the first case where
     * the unit's value decides the <em>shape</em> of the SQL, and the only way to
     * express that through this signature is to read the literal back out.
     *
     * <p>It fails safe, which is also the argument for keeping the map to exactly these
     * six: anything that is not a recognised quoted literal — a column reference, an
     * expression, a unit with no pattern — declines and is evaluated in-engine. A unit
     * that {@code DATE_FORMAT} could not express would decline of its own accord rather
     * than needing to be remembered here.
     */
    private static Optional<String> mysqlDateTrunc(String renderedUnit, String timestamp) {
        return Spellings.literal(renderedUnit)
                .map(unit -> unit.toLowerCase(Locale.ROOT))
                .map(MYSQL_TRUNCATION_PATTERNS::get)
                .map(pattern -> "CAST(DATE_FORMAT(" + timestamp + ", '" + pattern
                        + "') AS DATETIME)");
    }
}

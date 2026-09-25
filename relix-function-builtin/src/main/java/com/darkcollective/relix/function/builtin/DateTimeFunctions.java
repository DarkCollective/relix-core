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

import com.darkcollective.relix.function.PushdownSpelling;
import com.darkcollective.relix.function.PushdownTarget;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
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

                DATETIME.fn("DATE_TRUNC", TIMESTAMP, PURE_DETERMINISTIC,
                        List.of(p("unit", STRING), p("ts", TIMESTAMP)),
                        DateTimeFunctions::dateTruncSpelling, DateTimeFunctions::dateTrunc),

                DATETIME.fn("MINUTES", NUMBER, PURE_DETERMINISTIC, List.of(p("d", DURATION)),
                        args -> measure(args, "MINUTES", Duration::toMinutes)),

                DATETIME.fn("SECONDS", NUMBER, PURE_DETERMINISTIC, List.of(p("d", DURATION)),
                        args -> measure(args, "SECONDS", Duration::getSeconds)),

                DATETIME.fn("DAYS", NUMBER, PURE_DETERMINISTIC, List.of(p("d", DURATION)),
                        args -> measure(args, "DAYS", Duration::toDays)),

                DATETIME.fn("to_date", DATE, PURE_DETERMINISTIC, List.of(p("s", STRING)),
                        args -> parse(args, "to_date",
                                text -> new DateValue(LocalDate.parse(text)), "date")),

                DATETIME.fn("to_timestamp", TIMESTAMP, PURE_DETERMINISTIC, List.of(p("s", STRING)),
                        args -> parse(args, "to_timestamp",
                                text -> new TimestampValue(instant(text)), "timestamp")),

                DATETIME.fn("to_time", TIME, PURE_DETERMINISTIC, List.of(p("s", STRING)),
                        args -> parse(args, "to_time",
                                text -> new TimeValue(LocalTime.parse(text)), "time")));
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
        if (!(value instanceof TimestampValue ts)) {
            throw reject("DATE_TRUNC: expected TIMESTAMP, got " + value.type());
        }
        LocalDateTime moment = LocalDateTime.ofInstant(ts.value(), ZoneOffset.UTC);
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
        return new TimestampValue(truncated.toInstant(ZoneOffset.UTC));
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
        return number(unit.applyAsLong(duration.value()));
    }

    /** Parses text into a temporal value, naming what could not be read. */
    private static Value parse(List<Value> args, String function,
                               java.util.function.Function<String, Value> parser, String kind) {
        if (args.get(0).isNull()) {
            return NullValue.INSTANCE;
        }
        String text = string(args.get(0), function);
        try {
            return parser.apply(text);
        } catch (DateTimeException e) {
            throw reject(function + ": invalid " + kind + " '" + text + "'");
        }
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
                return Optional.of("EXTRACT(" + sqlUnit + " FROM " + argument + ")");
            }
            if (target.isFamily(PushdownTarget.MONGO)) {
                return Optional.of("{\"" + mongoOperator + "\": " + argument + "}");
            }
            return Optional.empty();
        };
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
            if (target.isVariant(POSTGRES) || target.isVariant(DUCKDB)) {
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
        return sqlStringLiteral(renderedUnit)
                .map(unit -> unit.toLowerCase(Locale.ROOT))
                .map(MYSQL_TRUNCATION_PATTERNS::get)
                .map(pattern -> "CAST(DATE_FORMAT(" + timestamp + ", '" + pattern
                        + "') AS DATETIME)");
    }

    /**
     * Reads the text of a rendered SQL string literal, or empty when the argument is
     * not one — an expression, a column, or a quoted run that does not close cleanly.
     *
     * <p>Undoubles an embedded quote, and declines a lone one: {@code 'a'||'b'} opens
     * and closes with a quote but is two literals and an operator, and reading it as
     * the single value {@code a'||'b} would be a spelling deciding what a fragment of
     * SQL means.
     */
    private static Optional<String> sqlStringLiteral(String rendered) {
        int last = rendered.length() - 1;
        if (last < 1 || rendered.charAt(0) != '\'' || rendered.charAt(last) != '\'') {
            return Optional.empty();
        }
        StringBuilder text = new StringBuilder(last - 1);
        for (int i = 1; i < last; i++) {
            char c = rendered.charAt(i);
            if (c != '\'') {
                text.append(c);
            } else if (i + 1 < last && rendered.charAt(i + 1) == '\'') {
                text.append('\'');
                i++;
            } else {
                return Optional.empty();
            }
        }
        return Optional.of(text.toString());
    }
}

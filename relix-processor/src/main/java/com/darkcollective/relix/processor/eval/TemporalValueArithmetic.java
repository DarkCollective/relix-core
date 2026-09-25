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

import com.darkcollective.relix.processor.EvaluationException;

import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.internal.TemporalLiterals;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Runtime evaluation of the typed temporal arithmetic algebra (ADR-0013)
 * over {@code java.time} values — the executor counterpart of the
 * {@code relix-semantic} {@code TemporalArithmetic} type rules. Both sides must
 * agree; the validator rejects illegal combinations ahead of execution, so an
 * illegal pairing reaching here throws an {@link EvaluationException} defensively.
 *
 * <p>Non-null propagation is the caller's responsibility ({@link OperandEvaluator}
 * returns {@code NULL} when either operand is {@code NULL} before delegating here).
 */
final class TemporalValueArithmetic {

    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);

    private TemporalValueArithmetic() {
    }

    /** {@code true} if either value is a temporal value (date/time/timestamp/duration). */
    static boolean involvesTemporal(Value a, Value b) {
        return isTemporal(a) || isTemporal(b);
    }

    private static boolean isTemporal(Value v) {
        return v instanceof DateValue || v instanceof TimeValue
                || v instanceof TimestampValue || v instanceof DurationValue;
    }

    /** Evaluates {@code left <op> right} for a combination involving a temporal value. */
    static Value binary(Value left, ArithmeticOperator op, Value right) {
        return switch (op) {
            case PLUS     -> plus(left, right);
            case MINUS    -> minus(left, right);
            case MULTIPLY -> multiply(left, right);
            case DIVIDE   -> divide(left, right);
        };
    }

    /** Negates a {@link DurationValue} (the only temporal type unary minus accepts). */
    static Value unaryMinus(DurationValue d) {
        return new DurationValue(d.value().negated());
    }

    // ── string coercion ───────────────────────────────────────────────────────

    /**
     * If {@code v} is a {@link StringValue}, attempts to parse it as the same
     * temporal kind as {@code hint} using {@link TemporalLiterals}.  Returns the
     * typed value on success, or {@code v} unchanged on failure so the caller's
     * existing error path fires.  When the hint gives no type information (it is
     * also a string or some non-temporal type), the string is probed as
     * TIMESTAMP → DATE → TIME → DURATION in that order — the most common inline-table
     * idiom of ISO-8601 timestamp cells used with DURATION arithmetic.
     */
    static Value coerceString(Value v, Value hint) {
        if (!(v instanceof StringValue sv)) return v;
        String s = sv.value();
        try {
            if (hint instanceof TimestampValue) return new TimestampValue(TemporalLiterals.parseTimestamp(s));
            if (hint instanceof DateValue)      return new DateValue(TemporalLiterals.parseDate(s));
            if (hint instanceof TimeValue)      return new TimeValue(TemporalLiterals.parseTime(s));
            if (hint instanceof DurationValue)  return new DurationValue(TemporalLiterals.parseDuration(s));
        } catch (DateTimeParseException ignored) { }
        return tryParseAsAnyTemporal(s).orElse(v);
    }

    /**
     * Returns the temporal value a temporal-shaped string denotes, or {@code v}
     * unchanged when it is not a string or denotes no temporal.
     *
     * <p>The hint-free counterpart of {@link #coerceString}, for callers that hold
     * one value and no second operand to take a type from — the hash-join key token
     * (see {@code ValueComparator.joinToken}) is computed a value at a time, long
     * before it is known which row it will be compared against. It probes in the
     * same TIMESTAMP→DATE→TIME→DURATION order {@code coerceString} falls back to, so
     * a string the hinted path would coerce is a string this one coerces too.
     */
    static Value coerceToAnyTemporal(Value v) {
        if (!(v instanceof StringValue sv)) return v;
        return tryParseAsAnyTemporal(sv.value()).orElse(v);
    }

    /**
     * Whether {@code s} could begin a temporal literal at all — a cheap gate in front of
     * the four parse attempts below.
     *
     * <p>It exists for cost, not for correctness. Every probe that fails does so by
     * throwing, and building four exceptions per call is affordable when the caller is a
     * lone arithmetic operand and ruinous when it is a comparator: this runs once per
     * comparison, so sorting a column of ordinary text was measured 300× slower without it
     * (100k strings, 49 ms → 14.7 s).
     *
     * <p>Asymmetric in what it risks, and written accordingly: admitting too much costs
     * only the exceptions it meant to save, while rejecting something a parser would accept
     * silently changes what a value means. So it tests necessary conditions on the shape and
     * leaves every real decision to the parsers — after an optional sign, either a
     * duration's {@code P}, or a run of digits followed by the separator that run implies:
     * {@code :} after a two-digit hour, {@code -} after a four-digit year (or after a longer
     * one, which ISO-8601 permits only with a sign).
     *
     * <p>The positional part is what earns its keep. A first-character test passes anything
     * beginning with a digit, so a column of order numbers or postcodes — {@code 12345-ACME},
     * {@code 555-1234} — still paid four thrown exceptions per distinct value. Requiring the
     * separator where a temporal would put it rejects those without looking at a parser.
     *
     * <p>{@code TemporalGuardTest} holds this to {@code TemporalLiterals} rather than to the
     * reasoning above: over a corpus of shapes, a string is canonicalised here exactly when
     * one of those parsers accepts it.
     */
    private static boolean mayBeTemporal(String s) {
        int i = (!s.isEmpty() && (s.charAt(0) == '+' || s.charAt(0) == '-')) ? 1 : 0;
        boolean signed = i == 1;
        if (i >= s.length()) return false;
        char first = s.charAt(i);
        if (first == 'P' || first == 'p') return true;              // a duration
        int digits = i;
        while (digits < s.length() && s.charAt(digits) >= '0' && s.charAt(digits) <= '9') digits++;
        int run = digits - i;
        if (digits >= s.length()) return false;                     // digits alone are no temporal
        char next = s.charAt(digits);
        if (next == ':') return run == 2;                           // a time's hour
        if (next == '-') return run == 4 || (signed && run > 4);    // a year, expanded only with a sign
        return false;
    }

    /**
     * What {@link #tryParseAsAnyTemporal} last answered for a given string.
     *
     * <p>Canonicalising is a pure function of the text, but it is asked once per
     * <em>comparison</em> rather than once per value, so sorting n rows parses the same
     * cell O(log n) times. The memo makes the repeats free and leaves the first of each
     * distinct string paying for all of them.
     *
     * <p>It and {@link #mayBeTemporal} solve different halves of the same cost and neither
     * substitutes for the other. The gate is what makes text that is <em>not</em> temporal
     * cheap; the memo is what makes text that <em>is</em> temporal cheap, since a
     * successful parse is expensive too. Sorting 100k rows, measured: ordinary text 47 ms
     * either way, but a column of ISO-8601 dates is 56 ms with the memo and 5.8 s without,
     * against a 49 ms baseline for the same sort before any of this.
     *
     * <p>Capped rather than evicting: a comparator has no execution to be scoped to, and an
     * unbounded map on a shared static is a leak. Past the cap the answer is still correct,
     * just recomputed — which is what makes a fixed cap acceptable here, and a badly chosen
     * one merely slow.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Optional<Value>> PARSED =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Entries retained before the memo stops growing. */
    private static final int PARSE_MEMO_LIMIT = 50_000;

    private static Optional<Value> tryParseAsAnyTemporal(String s) {
        if (!mayBeTemporal(s)) return Optional.empty();
        Optional<Value> memo = PARSED.get(s);
        if (memo != null) return memo;
        Optional<Value> parsed = parseAnyTemporal(s);
        if (PARSED.size() < PARSE_MEMO_LIMIT) PARSED.putIfAbsent(s, parsed);
        return parsed;
    }

    private static Optional<Value> parseAnyTemporal(String s) {
        try { return Optional.of(new TimestampValue(TemporalLiterals.parseTimestamp(s))); }
        catch (DateTimeParseException ignored) { }
        try { return Optional.of(new DateValue(TemporalLiterals.parseDate(s))); }
        catch (DateTimeParseException ignored) { }
        try { return Optional.of(new TimeValue(TemporalLiterals.parseTime(s))); }
        catch (DateTimeParseException ignored) { }
        try { return Optional.of(new DurationValue(TemporalLiterals.parseDuration(s))); }
        catch (DateTimeParseException ignored) { }
        return Optional.empty();
    }

    // ── operators ─────────────────────────────────────────────────────────────

    private static Value plus(Value l, Value r) {
        l = coerceString(l, r);
        r = coerceString(r, l);
        // Commutative: normalise NUMBER×DURATION / DURATION operand orders.
        if (l instanceof TimestampValue ts && r instanceof DurationValue d) {
            return new TimestampValue(ts.value().plus(d.value()));
        }
        if (l instanceof DurationValue d && r instanceof TimestampValue ts) {
            return new TimestampValue(ts.value().plus(d.value()));
        }
        if (l instanceof DateValue dt && r instanceof DurationValue d) {
            return new TimestampValue(startOfDay(dt).plus(d.value()));
        }
        if (l instanceof DurationValue d && r instanceof DateValue dt) {
            return new TimestampValue(startOfDay(dt).plus(d.value()));
        }
        if (l instanceof TimeValue t && r instanceof DurationValue d) {
            return new TimeValue(t.value().plus(d.value()));
        }
        if (l instanceof DurationValue d && r instanceof TimeValue t) {
            return new TimeValue(t.value().plus(d.value()));
        }
        if (l instanceof DurationValue a && r instanceof DurationValue b) {
            return new DurationValue(a.value().plus(b.value()));
        }
        throw illegal(l, "add", r);
    }

    private static Value minus(Value l, Value r) {
        l = coerceString(l, r);
        r = coerceString(r, l);
        if (l instanceof TimestampValue a && r instanceof TimestampValue b) {
            return new DurationValue(Duration.between(b.value(), a.value()));
        }
        if (l instanceof DateValue a && r instanceof DateValue b) {
            return new DurationValue(Duration.ofDays(ChronoUnit.DAYS.between(b.value(), a.value())));
        }
        if (l instanceof TimestampValue ts && r instanceof DurationValue d) {
            return new TimestampValue(ts.value().minus(d.value()));
        }
        if (l instanceof DateValue dt && r instanceof DurationValue d) {
            return new TimestampValue(startOfDay(dt).minus(d.value()));
        }
        if (l instanceof TimeValue t && r instanceof DurationValue d) {
            return new TimeValue(t.value().minus(d.value()));
        }
        if (l instanceof DurationValue a && r instanceof DurationValue b) {
            return new DurationValue(a.value().minus(b.value()));
        }
        throw illegal(l, "subtract", r);
    }

    private static Value multiply(Value l, Value r) {
        if (l instanceof DurationValue d && r instanceof NumberValue n) {
            return new DurationValue(scale(d.value(), n.value()));
        }
        if (l instanceof NumberValue n && r instanceof DurationValue d) {
            return new DurationValue(scale(d.value(), n.value()));
        }
        throw illegal(l, "multiply", r);
    }

    private static Value divide(Value l, Value r) {
        if (l instanceof DurationValue d && r instanceof NumberValue n) {
            if (n.value().compareTo(BigDecimal.ZERO) == 0) {
                throw new EvaluationException("Division by zero");
            }
            return new DurationValue(scale(d.value(), BigDecimal.ONE.divide(
                    n.value(), 20, RoundingMode.HALF_UP)));
        }
        if (l instanceof DurationValue a && r instanceof DurationValue b) {
            BigDecimal divisor = totalNanos(b.value());
            if (divisor.compareTo(BigDecimal.ZERO) == 0) {
                throw new EvaluationException("Division by zero");
            }
            return new NumberValue(totalNanos(a.value())
                    .divide(divisor, 10, RoundingMode.HALF_UP)
                    .stripTrailingZeros());
        }
        throw illegal(l, "divide", r);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Instant startOfDay(DateValue d) {
        return d.value().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static BigDecimal totalNanos(Duration d) {
        return new BigDecimal(BigInteger.valueOf(d.getSeconds()).multiply(NANOS_PER_SECOND)
                .add(BigInteger.valueOf(d.getNano())));
    }

    /** Scales a duration by a (possibly fractional) factor, computed in nanoseconds. */
    private static Duration scale(Duration d, BigDecimal factor) {
        BigInteger nanos = totalNanos(d).multiply(factor)
                .setScale(0, RoundingMode.HALF_UP).toBigInteger();
        BigInteger[] secsAndNanos = nanos.divideAndRemainder(NANOS_PER_SECOND);
        return Duration.ofSeconds(secsAndNanos[0].longValueExact(),
                secsAndNanos[1].longValue());
    }

    private static EvaluationException illegal(Value l, String verb, Value r) {
        return new EvaluationException(
                "cannot " + verb + " " + l.type() + " and " + r.type());
    }
}

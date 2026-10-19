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
package com.darkcollective.relix.semantic.internal;

import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Type;

import java.util.List;

/**
 * The typed temporal arithmetic and comparison algebra (ADR-0013 slice 3).
 *
 * <p>Pure, schema-free type rules shared by the two consumers that must agree on
 * them: {@link OperandTypeInferrer} (which assigns a result type to an arithmetic
 * expression) and {@link PredicateValidator} (which reports a precise error for an
 * illegal combination). Centralising the table here guarantees inference and
 * validation never diverge.
 *
 * <p>The legal binary combinations (every other temporal pairing is an error):
 * <pre>
 *   TIMESTAMP − TIMESTAMP            → DURATION
 *   TIMESTAMP ± DURATION             → TIMESTAMP
 *   TIMESTAMPTZ − TIMESTAMPTZ        → DURATION   (and either against a TIMESTAMP)
 *   TIMESTAMPTZ ± DURATION           → TIMESTAMPTZ (its months on its own calendar)
 *   DATE      − DATE                 → DURATION   (whole-day span)
 *   DATE      ± DURATION             → TIMESTAMP  (UTC start-of-day ± span)
 *   TIME      ± DURATION             → TIME       (wraps within 24h)
 *   DURATION  ± DURATION             → DURATION
 *   DURATION  × / ÷ NUMBER           → DURATION
 *   NUMBER    × DURATION             → DURATION
 *   DURATION  ÷ DURATION             → NUMBER     (ratio)
 *   −DURATION (unary)                → DURATION
 * </pre>
 * Addition and multiplication are commutative, so both operand orders are
 * accepted; subtraction and division are order-sensitive.
 *
 * <p>Arithmetic with no temporal operand is {@code NUMBER}, except {@code +} with a
 * {@code STRING} operand, which concatenates and is {@code STRING}.
 *
 * <p>{@code ×} and {@code ÷} take no {@code STRING} operand. The one temporal they scale
 * is a {@code DURATION}, while text beside them is far more often a number, which is
 * never read from a {@code STRING}: accepting it as a possible duration let
 * {@code SUBSTRING(b FROM 1 FOR 2) * 1} validate and then fail when run (#163). The
 * error names the {@code CAST} that says which was meant.
 *
 * <p>Any other {@code STRING} operand is read as the temporal value it holds, the way
 * {@link #comparisonError comparison} reads one: inline-table, CSV and JSON columns type
 * every non-numeric cell {@code STRING}, and the executor parses an ISO-8601 cell into
 * the {@code DATE}, {@code TIME}, {@code TIMESTAMP} or {@code DURATION} it denotes before
 * doing arithmetic on it. Such an expression is legal when some reading of each
 * {@code STRING} operand makes it legal, and its type is the one every legal reading
 * agrees on — {@code ANY} when they disagree. A combination no reading rescues, such as
 * {@code STRING × TIMESTAMP} or {@code STRING + NUMBER}, is an error.
 */
final class TemporalArithmetic {

    private TemporalArithmetic() {
    }

    /**
     * The outcome of typing a temporal arithmetic expression: either a result
     * {@link Type} (legal — including plain non-temporal arithmetic, which is
     * {@code NUMBER}, and the lenient {@code ANY} when an operand type is unknown),
     * or an {@code error} message for an illegal temporal pairing.
     */
    record Result(Type type, String error) {
        boolean isError() {
            return error != null;
        }

        /**
         * The result type for inference, which must stay lenient: an illegal
         * combination (reported as an error by the validator) infers as
         * {@link ScalarType#ANY} rather than failing.
         */
        Type orAny() {
            return type != null ? type : ScalarType.ANY;
        }

        static Result ok(Type type) {
            return new Result(type, null);
        }

        static Result error(String message) {
            return new Result(null, message);
        }
    }

    /** Types a binary arithmetic expression {@code left <op> right}. */
    static Result binary(Type left, ArithmeticOperator op, Type right) {
        ScalarType l = scalar(left);
        ScalarType r = scalar(right);

        // + concatenates strings. The evaluator concatenates only STRING + STRING, so a
        // STRING beside another STRING, or beside an operand of unknown type, makes STRING
        // the one result + can produce.
        if (isConcatenation(l, op, r)) {
            return Result.ok(ScalarType.STRING);
        }
        // × and ÷ scale only a DURATION, and text there is more often a number: say which.
        if (isScaling(op) && (l == ScalarType.STRING || r == ScalarType.STRING)) {
            return Result.error(scalingMessage(op, l, r));
        }
        // Any other STRING operand is an untyped cell, read as the temporal it holds.
        if (l == ScalarType.STRING || r == ScalarType.STRING) {
            return untyped(l, op, r);
        }
        // No temporal type involved → ordinary numeric arithmetic.
        if (!isTemporal(l) && !isTemporal(r)) {
            return Result.ok(ScalarType.NUMBER);
        }
        // A temporal type combined with an unknown operand (ANY or a non-scalar
        // struct/array) cannot be decided statically — stay lenient, no error.
        if (l == null || r == null || l == ScalarType.ANY || r == ScalarType.ANY) {
            return Result.ok(ScalarType.ANY);
        }

        ScalarType result = lookup(op, l, r);
        return result != null ? Result.ok(result) : Result.error(binaryMessage(op, l, r));
    }

    /**
     * {@code true} if {@code left <op> right} is {@code +} over a {@code STRING} and an
     * operand that may be one — another {@code STRING}, or one of unknown type.
     */
    private static boolean isConcatenation(ScalarType l, ArithmeticOperator op, ScalarType r) {
        return op == ArithmeticOperator.PLUS
                && ((l == ScalarType.STRING && mayBeString(r))
                        || (r == ScalarType.STRING && mayBeString(l)));
    }

    private static boolean mayBeString(ScalarType t) {
        return t == null || t == ScalarType.ANY || t == ScalarType.STRING;
    }

    /**
     * Types arithmetic with a {@code STRING} operand that is not a concatenation, by
     * trying each temporal the string could hold. Legal when some reading is; typed by
     * the result every legal reading shares, else {@code ANY}.
     */
    private static Result untyped(ScalarType l, ArithmeticOperator op, ScalarType r) {
        // The other operand is unknown: nothing can be decided statically.
        if (l == null || r == null || l == ScalarType.ANY || r == ScalarType.ANY) {
            return Result.ok(ScalarType.ANY);
        }
        ScalarType agreed = null;
        boolean legal = false;
        for (ScalarType lt : readings(l)) {
            for (ScalarType rt : readings(r)) {
                ScalarType result = lookup(op, lt, rt);
                if (result == null) continue;
                agreed = !legal || result == agreed ? result : ScalarType.ANY;
                legal = true;
            }
        }
        return legal ? Result.ok(agreed) : Result.error(untypedMessage(op, l, r));
    }

    /** The types {@code t} may stand for at run time: a STRING is any of the temporals. */
    private static List<ScalarType> readings(ScalarType t) {
        return t == ScalarType.STRING ? TEMPORALS : List.of(t);
    }

    private static final List<ScalarType> TEMPORALS = List.of(
            ScalarType.TIMESTAMP, ScalarType.DATE, ScalarType.TIME, ScalarType.DURATION);

    /** Types a unary-minus expression {@code −operand}. */
    static Result unary(Type operand) {
        ScalarType t = scalar(operand);
        // An untyped cell negates only as the DURATION it may hold.
        if (t == ScalarType.STRING) {
            return Result.ok(ScalarType.DURATION);
        }
        if (!isTemporal(t)) {
            // Numeric (or lenient unknown) negation — unchanged behaviour.
            return Result.ok(ScalarType.NUMBER);
        }
        if (t == ScalarType.DURATION) {
            return Result.ok(ScalarType.DURATION);
        }
        return Result.error("cannot negate " + t.name()
                + "; unary minus applies to NUMBER or DURATION");
    }

    /**
     * Returns an error message if comparing {@code left} with {@code right} is an
     * illegal temporal comparison, else {@code null}. Temporal values compare only
     * with the <em>same</em> temporal type, so comparing two temporals of different
     * types, or a temporal with a {@code NUMBER} or {@code BOOLEAN}, is an error.
     * Unknown ({@code ANY}) operands and purely non-temporal comparisons are left to
     * existing behaviour.
     *
     * <p>{@code STRING} is the exception, and deliberately so. It is not a rival
     * temporal type but the absence of one: inline-table, CSV and JSON columns infer
     * every cell as {@code STRING}, so a column that holds timestamps is typed
     * {@code STRING} until something says otherwise. The executor has parsed those
     * cells into the temporal they denote since issue #277 — it is what lets
     * {@code SESSIONIZE} run over inline data — and rejecting the same pairing here
     * made the rule depend on which operator asked: a selection was refused at
     * analysis time while a natural join over the identical two columns matched and
     * returned rows. Permitting it is what makes one answer come back either way.
     */
    static String comparisonError(Type left, Type right) {
        ScalarType l = scalar(left);
        ScalarType r = scalar(right);
        // Unknown operand, or no temporal type involved → no temporal check.
        if (l == null || r == null || l == ScalarType.ANY || r == ScalarType.ANY) {
            return null;
        }
        if (!isTemporal(l) && !isTemporal(r)) {
            return null;
        }
        if (l == r) {
            return null;
        }
        // Both name an instant, and SQL compares a TIMESTAMP WITH TIME ZONE as one.
        if (pair(l, r, ScalarType.TIMESTAMP, ScalarType.TIMESTAMPTZ)) {
            return null;
        }
        // An untyped (STRING) cell against a temporal: coerced at runtime, not an error.
        if (l == ScalarType.STRING || r == ScalarType.STRING) {
            return null;
        }
        return "cannot compare " + l.name() + " with " + r.name()
                + "; temporal values compare only with the same temporal type";
    }

    // ── the table ───────────────────────────────────────────────────────────

    private static ScalarType lookup(ArithmeticOperator op, ScalarType l, ScalarType r) {
        return switch (op) {
            case PLUS     -> plus(l, r);
            case MINUS    -> minus(l, r);
            case MULTIPLY -> multiply(l, r);
            case DIVIDE   -> divide(l, r);
        };
    }

    private static ScalarType plus(ScalarType l, ScalarType r) {
        // Commutative: accept either operand order.
        if (l == ScalarType.DURATION && r == ScalarType.DURATION) return ScalarType.DURATION;
        if (pair(l, r, ScalarType.TIMESTAMP, ScalarType.DURATION)) return ScalarType.TIMESTAMP;
        if (pair(l, r, ScalarType.TIMESTAMPTZ, ScalarType.DURATION)) return ScalarType.TIMESTAMPTZ;
        if (pair(l, r, ScalarType.DATE, ScalarType.DURATION))      return ScalarType.TIMESTAMP;
        if (pair(l, r, ScalarType.TIME, ScalarType.DURATION))      return ScalarType.TIME;
        return null;
    }

    private static ScalarType minus(ScalarType l, ScalarType r) {
        // Two instants, TIMESTAMP or TIMESTAMPTZ, are the span between them.
        if (instant(l) && instant(r)) return ScalarType.DURATION;
        if (l == ScalarType.TIMESTAMPTZ && r == ScalarType.DURATION) return ScalarType.TIMESTAMPTZ;
        if (l == ScalarType.DATE && r == ScalarType.DATE)           return ScalarType.DURATION;
        if (l == ScalarType.TIMESTAMP && r == ScalarType.DURATION)  return ScalarType.TIMESTAMP;
        if (l == ScalarType.DATE && r == ScalarType.DURATION)       return ScalarType.TIMESTAMP;
        if (l == ScalarType.TIME && r == ScalarType.DURATION)       return ScalarType.TIME;
        if (l == ScalarType.DURATION && r == ScalarType.DURATION)   return ScalarType.DURATION;
        return null;
    }

    private static ScalarType multiply(ScalarType l, ScalarType r) {
        // Commutative: DURATION × NUMBER and NUMBER × DURATION.
        if (pair(l, r, ScalarType.DURATION, ScalarType.NUMBER)) return ScalarType.DURATION;
        return null;
    }

    private static ScalarType divide(ScalarType l, ScalarType r) {
        if (l == ScalarType.DURATION && r == ScalarType.NUMBER)   return ScalarType.DURATION;
        if (l == ScalarType.DURATION && r == ScalarType.DURATION) return ScalarType.NUMBER;
        return null;
    }

    /** Whether {@code t} names an instant: a TIMESTAMP, or a TIMESTAMPTZ. */
    private static boolean instant(ScalarType t) {
        return t == ScalarType.TIMESTAMP || t == ScalarType.TIMESTAMPTZ;
    }

    /** {@code true} if {@code {l, r}} equals {@code {a, b}} as an unordered pair. */
    private static boolean pair(ScalarType l, ScalarType r, ScalarType a, ScalarType b) {
        return (l == a && r == b) || (l == b && r == a);
    }

    private static String binaryMessage(ArithmeticOperator op, ScalarType l, ScalarType r) {
        return cannot(op, l, r) + "; see ADR-0013 for the legal temporal arithmetic combinations";
    }

    private static boolean isScaling(ArithmeticOperator op) {
        return op == ArithmeticOperator.MULTIPLY || op == ArithmeticOperator.DIVIDE;
    }

    private static String scalingMessage(ArithmeticOperator op, ScalarType l, ScalarType r) {
        return cannot(op, l == null ? ScalarType.ANY : l, r == null ? ScalarType.ANY : r)
                + "; * and / do not read a STRING as a number: write CAST(… AS NUMBER) for text"
                + " that holds a number, or CAST(… AS DURATION) for one that holds a duration";
    }

    private static String untypedMessage(ArithmeticOperator op, ScalarType l, ScalarType r) {
        return cannot(op, l, r) + "; arithmetic needs NUMBER or a temporal type, and a STRING is read"
                + " as the DATE, TIME, TIMESTAMP or DURATION it holds, none of which is legal"
                + " here (+ concatenates only STRING with STRING)";
    }

    private static String cannot(ArithmeticOperator op, ScalarType l, ScalarType r) {
        return switch (op) {
            case PLUS     -> "cannot add " + r.name() + " to " + l.name();
            case MINUS    -> "cannot subtract " + r.name() + " from " + l.name();
            case MULTIPLY -> "cannot multiply " + l.name() + " by " + r.name();
            case DIVIDE   -> "cannot divide " + l.name() + " by " + r.name();
        };
    }

    private static ScalarType scalar(Type t) {
        return t instanceof ScalarType st ? st : null;
    }

    /**
     * Whether {@code t} is one of the five temporal types — the shared answer to
     * "is a temporal distance defined here?", read by the arithmetic table below
     * and by {@link RelAlgebraValidator}'s AS-OF {@code WITHIN} check.
     *
     * @param t the type to test
     * @return {@code true} for DATE, TIME, TIMESTAMP, TIMESTAMPTZ and DURATION
     */
    static boolean isTemporal(ScalarType t) {
        return t == ScalarType.DATE || t == ScalarType.TIME
                || t == ScalarType.TIMESTAMP || t == ScalarType.TIMESTAMPTZ || t == ScalarType.DURATION;
    }
}

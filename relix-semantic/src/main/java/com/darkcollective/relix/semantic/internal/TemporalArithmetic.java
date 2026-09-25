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

        // No temporal type involved → ordinary numeric arithmetic (unchanged).
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

    /** Types a unary-minus expression {@code −operand}. */
    static Result unary(Type operand) {
        ScalarType t = scalar(operand);
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
        if (pair(l, r, ScalarType.DATE, ScalarType.DURATION))      return ScalarType.TIMESTAMP;
        if (pair(l, r, ScalarType.TIME, ScalarType.DURATION))      return ScalarType.TIME;
        return null;
    }

    private static ScalarType minus(ScalarType l, ScalarType r) {
        if (l == ScalarType.TIMESTAMP && r == ScalarType.TIMESTAMP) return ScalarType.DURATION;
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

    /** {@code true} if {@code {l, r}} equals {@code {a, b}} as an unordered pair. */
    private static boolean pair(ScalarType l, ScalarType r, ScalarType a, ScalarType b) {
        return (l == a && r == b) || (l == b && r == a);
    }

    private static String binaryMessage(ArithmeticOperator op, ScalarType l, ScalarType r) {
        String detail = switch (op) {
            case PLUS     -> "cannot add " + r.name() + " to " + l.name();
            case MINUS    -> "cannot subtract " + r.name() + " from " + l.name();
            case MULTIPLY -> "cannot multiply " + l.name() + " by " + r.name();
            case DIVIDE   -> "cannot divide " + l.name() + " by " + r.name();
        };
        return detail + "; see ADR-0013 for the legal temporal arithmetic combinations";
    }

    private static ScalarType scalar(Type t) {
        return t instanceof ScalarType st ? st : null;
    }

    /**
     * Whether {@code t} is one of the four temporal types — the shared answer to
     * "is a temporal distance defined here?", read by the arithmetic table below
     * and by {@link RelAlgebraValidator}'s AS-OF {@code WITHIN} check.
     *
     * @param t the type to test
     * @return {@code true} for DATE, TIME, TIMESTAMP and DURATION
     */
    static boolean isTemporal(ScalarType t) {
        return t == ScalarType.DATE || t == ScalarType.TIME
                || t == ScalarType.TIMESTAMP || t == ScalarType.DURATION;
    }
}

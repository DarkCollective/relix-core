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
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.Value;

import com.darkcollective.relix.function.PushdownTarget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.function.DoubleUnaryOperator;

import static com.darkcollective.relix.function.builtin.Arguments.integer;
import static com.darkcollective.relix.function.builtin.Arguments.number;
import static com.darkcollective.relix.function.builtin.Arguments.reject;
import static com.darkcollective.relix.function.builtin.Category.PURE_DETERMINISTIC;
import static com.darkcollective.relix.function.builtin.Category.PURE_DETERMINISTIC_IDEMPOTENT;
import static com.darkcollective.relix.function.builtin.Category.VOLATILE;
import static com.darkcollective.relix.function.builtin.Category.p;
import static com.darkcollective.relix.symbol.ScalarType.NUMBER;

/**
 * The numeric built-ins: sign and magnitude, the three roundings, the transcendentals,
 * and a random number.
 *
 * <p>The roundings are three different questions and keep three different answers:
 * {@code Int} goes to negative infinity, {@code Fix} towards zero, {@code Ceil} to
 * positive infinity, and {@code Round} to the nearest with halves going up.
 *
 * <p>Arithmetic is exact where it can be — {@code Abs}, the roundings and {@code Sgn}
 * work on the {@code BigDecimal} a value carries — and goes through {@code double} where
 * the operation is transcendental and there is no exact answer to keep.
 *
 * <h2>Which of these a backend is offered</h2>
 * The exact ones — {@code ABS}, {@code FLOOR}, {@code CEILING}, {@code SIGN},
 * {@code ROUND}, and {@code TRUNCATE}/{@code TRUNC} per dialect — which agree digit for
 * digit over an exact numeric column. Not one of the transcendentals, for two reasons
 * that were measured rather than assumed, both of them by trying it.
 *
 * <p><b>A backend computes a different number.</b> MySQL answers {@code TAN(4)} with
 * {@code 1.1578212823495775} and this JVM with {@code 1.1578212823495777} — one unit in
 * the last place, and not a rounding of the same value but a different value: a
 * transcendental is computed to within an ulp or so, and two implementations are entitled
 * to land on either side. H2 agrees with the engine, which is worth knowing and proves
 * nothing, since it computes on the same JVM.
 *
 * <p><b>And half of them have no answer at all for some arguments.</b> relix rejects a
 * negative square root and a non-positive logarithm, and cannot represent the infinity an
 * overflowing {@code EXP} produces — where SQL, given the same argument, returns NULL or
 * NaN. That is worse than a last-place difference: a query that <em>succeeds</em> folded
 * and <em>raises</em> unfolded.
 *
 * <p>{@code Rand} declines for the reason it declares no optimizer contract at all: its
 * value may differ per row, so there is nothing constant for a backend to be given.
 */
final class MathFunctions {

    private static final Category MATH = Category.of("math");

    private MathFunctions() {
    }

    /**
     * The dialect whose rounding functions answer in binary floating point.
     *
     * <p>SQLite has no decimal type: {@code ROUND}, {@code FLOOR} and {@code CEILING}
     * take and return a REAL, so {@code ROUND(x, 2)} rounds the double nearest {@code x}
     * — {@code -49.555} is stored a hair above it and rounds to {@code -49.55} there,
     * where relix rounds the exact decimal half-up to {@code -49.56}. {@code FLOOR} and
     * {@code CEILING} are also a compile-time option of SQLite's rather than part of its
     * core, so a driver the user supplies need not have them at all. Both are reasons to
     * round here.
     */
    private static final String ROUNDS_IN_FLOATING_POINT = Spellings.SQLITE;

    static List<ScalarFunction> all() {
        return List.of(
                MATH.fn("Abs", NUMBER, PURE_DETERMINISTIC_IDEMPOTENT, List.of(p("x", NUMBER)),
                        Spellings.sql("ABS", 1),
                        args -> exact(args, "Abs", BigDecimal::abs)),

                // Int rounds towards negative infinity — the floor.
                MATH.fn("Int", NUMBER, PURE_DETERMINISTIC_IDEMPOTENT, List.of(p("x", NUMBER)),
                        // Not SQLite's: see ROUNDS_IN_FLOATING_POINT.
                        Spellings.sqlExcept("FLOOR", 1, ROUNDS_IN_FLOATING_POINT),
                        args -> exact(args, "Int", x -> x.setScale(0, RoundingMode.FLOOR))),

                // Fix truncates towards zero, so it disagrees with Int on negatives.
                // It is also the one rounding SQL has no single name for: MySQL spells
                // it TRUNCATE(x, 0) and Postgres TRUNC(x), so the generic dialect —
                // which is an unidentified backend — is offered neither.
                MATH.fn("Fix", NUMBER, PURE_DETERMINISTIC_IDEMPOTENT, List.of(p("x", NUMBER)),
                        MathFunctions::fixSpelling,
                        args -> exact(args, "Fix", x -> x.setScale(0, RoundingMode.DOWN))),

                // Round(x) and Round(x, places) are one function: the places default to
                // zero when the trailing argument is not passed.
                MATH.fn("Round", NUMBER, PURE_DETERMINISTIC,
                        List.of(p("x", NUMBER), p("places", NUMBER)), Arity.between(1, 2),
                        Spellings.firstOf(
                                // T-SQL's ROUND always takes the places.
                                Spellings.on(Spellings.SQLSERVER, 1, a -> "ROUND(" + a.get(0) + ", 0)"),
                                Spellings.sqlExcept("ROUND", ROUNDS_IN_FLOATING_POINT)),
                        MathFunctions::round),

                // CEILING rather than CEIL: both dialects have both, but CEILING is the
                // SQL standard's name and the one an unidentified backend is likeliest
                // to have.
                MATH.fn("Ceil", NUMBER, PURE_DETERMINISTIC_IDEMPOTENT, List.of(p("x", NUMBER)),
                        Spellings.sqlExcept("CEILING", 1, ROUNDS_IN_FLOATING_POINT),
                        args -> exact(args, "Ceil", x -> x.setScale(0, RoundingMode.CEILING))),

                MATH.fn("Sgn", NUMBER, PURE_DETERMINISTIC_IDEMPOTENT, List.of(p("x", NUMBER)),
                        Spellings.sql("SIGN", 1),
                        args -> exact(args, "Sgn", x -> BigDecimal.valueOf(x.signum()))),

                MATH.fn("Sqr", NUMBER, PURE_DETERMINISTIC, List.of(p("x", NUMBER)),
                        args -> approximate(args, "Sqr", d -> {
                            if (d < 0) {
                                throw reject("Sqr: argument must be non-negative");
                            }
                            return Math.sqrt(d);
                        })),

                MATH.fn("Log", NUMBER, PURE_DETERMINISTIC, List.of(p("x", NUMBER)),
                        args -> approximate(args, "Log", d -> {
                            if (d <= 0) {
                                throw reject("Log: argument must be positive");
                            }
                            return Math.log(d);
                        })),

                MATH.fn("Exp", NUMBER, PURE_DETERMINISTIC, List.of(p("x", NUMBER)),
                        args -> approximate(args, "Exp", Math::exp)),

                MATH.fn("Sin", NUMBER, PURE_DETERMINISTIC, List.of(p("x", NUMBER)),
                        args -> approximate(args, "Sin", Math::sin)),

                MATH.fn("Cos", NUMBER, PURE_DETERMINISTIC, List.of(p("x", NUMBER)),
                        args -> approximate(args, "Cos", Math::cos)),

                MATH.fn("Tan", NUMBER, PURE_DETERMINISTIC, List.of(p("x", NUMBER)),
                        args -> approximate(args, "Tan", Math::tan)),

                MATH.fn("Atn", NUMBER, PURE_DETERMINISTIC, List.of(p("x", NUMBER)),
                        args -> approximate(args, "Atn", Math::atan)),

                MATH.fn("Power", NUMBER, PURE_DETERMINISTIC,
                        List.of(p("base", NUMBER), p("exp", NUMBER)),
                        MathFunctions::power),

                // Rand reads the global RNG, so it declares no optimizer contract at
                // all: a folded or de-duplicated Rand() is the same number twice.
                MATH.fn("Rand", NUMBER, VOLATILE, List.of(),
                        args -> new NumberValue(BigDecimal.valueOf(Math.random()))));
    }

    /** Applies an exact decimal operation, passing NULL through. */
    private static Value exact(List<Value> args, String function,
                               java.util.function.UnaryOperator<BigDecimal> operation) {
        if (args.get(0).isNull()) {
            return NullValue.INSTANCE;
        }
        return new NumberValue(operation.apply(number(args.get(0), function)));
    }

    /** Applies a {@code double} operation, passing NULL through. */
    private static Value approximate(List<Value> args, String function,
                                     DoubleUnaryOperator operation) {
        if (args.get(0).isNull()) {
            return NullValue.INSTANCE;
        }
        double x = number(args.get(0), function).doubleValue();
        return new NumberValue(BigDecimal.valueOf(operation.applyAsDouble(x)));
    }

    private static Value round(List<Value> args) {
        if (args.get(0).isNull()) {
            return NullValue.INSTANCE;
        }
        BigDecimal x = number(args.get(0), "Round");
        int places = args.size() == 2 ? integer(args.get(1), "Round") : 0;
        return new NumberValue(x.setScale(places, RoundingMode.HALF_UP));
    }

    /**
     * {@code Fix} per dialect. Truncation towards zero is the one rounding SQL never
     * settled on a single name for — MySQL spells it {@code TRUNCATE(x, 0)}, Postgres and
     * DuckDB {@code TRUNC(x)}, and SQL Server {@code ROUND(x, 0, 1)} — and the generic dialect is offered neither, because a
     * name that has to be chosen per dialect is by definition one an unidentified
     * backend has not been confirmed to have.
     */
    private static Optional<String> fixSpelling(PushdownTarget target, List<String> arguments) {
        if (!target.isFamily(PushdownTarget.SQL) || arguments.size() != 1) {
            return Optional.empty();
        }
        String x = arguments.get(0);
        if (target.isVariant(Spellings.MYSQL)) {
            return Optional.of("TRUNCATE(" + x + ", 0)");
        }
        if (target.isVariant(Spellings.POSTGRES) || target.isVariant(Spellings.DUCKDB)) {
            return Optional.of("TRUNC(" + x + ")");
        }
        // T-SQL truncates through ROUND's third argument: any non-zero value truncates.
        if (target.isVariant(Spellings.SQLSERVER)) {
            return Optional.of("ROUND(" + x + ", 0, 1)");
        }
        return Optional.empty();
    }

    private static Value power(List<Value> args) {
        if (args.get(0).isNull() || args.get(1).isNull()) {
            return NullValue.INSTANCE;
        }
        double base = number(args.get(0), "Power").doubleValue();
        double exponent = number(args.get(1), "Power").doubleValue();
        return new NumberValue(BigDecimal.valueOf(Math.pow(base, exponent)));
    }
}

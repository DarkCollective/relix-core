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
import com.darkcollective.relix.function.FunctionContext;
import com.darkcollective.relix.function.FunctionSignature;
import com.darkcollective.relix.function.PushdownSpelling;
import com.darkcollective.relix.function.PushdownTarget;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.function.StrictScalarFunction;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.value.internal.CodePoints;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.darkcollective.relix.function.builtin.Arguments.integer;
import static com.darkcollective.relix.function.builtin.Arguments.number;
import static com.darkcollective.relix.function.builtin.Arguments.reject;
import static com.darkcollective.relix.function.builtin.Arguments.string;
import static com.darkcollective.relix.function.builtin.Category.PURE_DETERMINISTIC;
import static com.darkcollective.relix.function.builtin.Category.p;
import static com.darkcollective.relix.symbol.ScalarType.ANY;
import static com.darkcollective.relix.symbol.ScalarType.NUMBER;
import static com.darkcollective.relix.symbol.ScalarType.STRING;

/**
 * The SQL names of the built-ins, which are named for Visual Basic: {@code UPPER} for
 * {@code UCase}, {@code LENGTH} for {@code Len}, {@code SQRT} for {@code Sqr}.
 *
 * <p>Where SQL's function answers as the built-in does, its name is an <em>alias</em>:
 * a function of its own name with the built-in's parameters, result, body, backend
 * spelling and reference page, so that a call by either name is the same call. Where
 * PostgreSQL's function of that name answers otherwise, it is a function of its own
 * that answers as PostgreSQL's does: {@code SUBSTRING} counts a start before the first
 * character, {@code ASCII('')} is 0, {@code TRUNC} takes a number of places, and
 * {@code CONCAT} skips a NULL where {@code +} returns one.
 *
 * <p>{@code LOG} is not here: it is already a built-in, the natural logarithm as MySQL
 * and SQL Server have it, where PostgreSQL's {@code LOG} is base 10. {@code LN} is the
 * name all of them agree on.
 */
final class SqlNames {

    private static final Category STRINGS = Category.of("string");
    private static final Category MATH = Category.of("math");

    private SqlNames() {
    }

    /** Each SQL name for a built-in, and the built-in it names. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("UPPER", "UCase"),
            Map.entry("LOWER", "LCase"),
            Map.entry("LENGTH", "Len"),
            Map.entry("CHAR_LENGTH", "Len"),
            Map.entry("CHAR", "Chr"),
            Map.entry("FLOOR", "Int"),
            Map.entry("CEILING", "Ceil"),
            Map.entry("SQRT", "Sqr"),
            Map.entry("SIGN", "Sgn"),
            Map.entry("RANDOM", "Rand"),
            Map.entry("REPLICATE", "REPEAT"),
            Map.entry("DATEFROMPARTS", "MAKE_DATE"),
            Map.entry("EOMONTH", "LAST_DAY"));

    /**
     * Every alias here and the function it names, the ones of the SQL-named functions
     * themselves ({@code SUBSTR}, {@code TRUNCATE}) and {@code LN} included.
     *
     * @return alias name → the name of the function it is
     */
    static Map<String, String> aliases() {
        Map<String, String> all = new java.util.TreeMap<>(ALIASES);
        all.put("LN", "Log");
        all.put("SUBSTR", "SUBSTRING");
        all.put("TRUNCATE", "TRUNC");
        return all;
    }

    /**
     * The SQL names, given the built-ins they name.
     *
     * @param builtins every other built-in
     * @return the SQL-named functions
     */
    static List<ScalarFunction> all(List<ScalarFunction> builtins) {
        Map<String, ScalarFunction> byName = builtins.stream()
                .collect(Collectors.toMap(fn -> fn.signature().name(), Function.identity()));
        List<ScalarFunction> all = new ArrayList<>();
        ALIASES.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> all.add(alias(e.getKey(), byName.get(e.getValue()), null)));
        // LN is the one-argument Log: Log(b, y) is a base, which LN has none of.
        all.add(alias("LN", byName.get("Log"), Arity.exactly(1)));

        ScalarFunction substring = STRINGS.fn("SUBSTRING", STRING, PURE_DETERMINISTIC,
                List.of(p("s", STRING), p("start", NUMBER), p("length", NUMBER)),
                Arity.between(2, 3), SqlNames::substringSpelling, SqlNames::substring);
        all.add(substring);
        all.add(alias("SUBSTR", substring, null));

        all.add(STRINGS.fn("STRPOS", NUMBER, PURE_DETERMINISTIC,
                List.of(p("s", STRING), p("sub", STRING)),
                args -> position(args.get(0), args.get(1), "STRPOS")));
        all.add(STRINGS.fn("CHARINDEX", NUMBER, PURE_DETERMINISTIC,
                List.of(p("sub", STRING), p("s", STRING)),
                args -> position(args.get(1), args.get(0), "CHARINDEX")));

        all.add(STRINGS.fn("ASCII", NUMBER, PURE_DETERMINISTIC, List.of(p("s", STRING)),
                Spellings.sqlOn("ASCII", 1, Spellings.POSTGRES), SqlNames::ascii));

        all.add(STRINGS.fn("CONCAT", STRING, PURE_DETERMINISTIC, List.of(p("value", ANY)),
                Arity.atLeast(1), SqlNames::concat));

        ScalarFunction trunc = MATH.fn("TRUNC", NUMBER, PURE_DETERMINISTIC,
                List.of(p("x", NUMBER), p("places", NUMBER)), Arity.between(1, 2),
                SqlNames::truncSpelling, SqlNames::trunc);
        all.add(trunc);
        all.add(alias("TRUNCATE", trunc, null));
        return List.copyOf(all);
    }

    /**
     * {@code of} under another name: the same parameters, result, properties, page,
     * body and spelling, with {@code arity} in place of its own where given.
     */
    private static ScalarFunction alias(String name, ScalarFunction of, Arity arity) {
        FunctionSignature s = of.signature();
        FunctionSignature signature = new FunctionSignature(name,
                arity == null ? s.parameters() : s.parameters().subList(0, arity.max()),
                arity == null ? s.arity() : arity, s.returnType(), s.properties(), s.category(), s.docKey());
        return new Alias(signature, (StrictScalarFunction) of);
    }

    /** A built-in called by another name. */
    private record Alias(FunctionSignature signature, StrictScalarFunction of) implements StrictScalarFunction {

        @Override
        public Value invoke(FunctionContext context, List<Value> arguments) {
            return of.invoke(context, arguments);
        }

        @Override
        public PushdownSpelling pushdown() {
            return of.pushdown();
        }
    }

    /**
     * PostgreSQL's {@code substring(s, start [, length])}: the characters from
     * {@code start}, a 1-based position, for {@code length} of them. A start before the
     * first character still counts towards the length, so {@code SUBSTRING('hello', 0, 3)}
     * is {@code 'he'}, and a negative length is refused.
     */
    private static Value substring(List<Value> args) {
        if (args.get(0).isNull() || args.get(1).isNull() || args.size() == 3 && args.get(2).isNull()) {
            return NullValue.INSTANCE;
        }
        String s = string(args.get(0), "SUBSTRING");
        long start = integer(args.get(1), "SUBSTRING");
        long end = Long.MAX_VALUE;
        if (args.size() == 3) {
            long length = integer(args.get(2), "SUBSTRING");
            if (length < 0) {
                throw reject("SUBSTRING: negative substring length not allowed");
            }
            end = start + length;
        }
        int size = CodePoints.length(s);
        long from = Math.max(start, 1) - 1;
        long to = Math.min(end - 1, size);
        if (to <= from) {
            return new StringValue("");
        }
        return new StringValue(CodePoints.substring(s, (int) from, (int) (to - from)));
    }

    /**
     * {@code SUBSTRING} on PostgreSQL, whose function it is. MySQL counts a start of 0 as
     * an empty string and a negative start from the end, so there and elsewhere it runs
     * in the engine.
     */
    private static Optional<String> substringSpelling(PushdownTarget target, List<String> arguments) {
        if (target.isFamily(PushdownTarget.SQL) && target.isVariant(Spellings.POSTGRES)) {
            return Optional.of("SUBSTRING(" + String.join(", ", arguments) + ")");
        }
        return Optional.empty();
    }

    /** The 1-based position of {@code sub} in {@code s}, 0 when absent and 1 for an empty {@code sub}. */
    private static Value position(Value s, Value sub, String function) {
        if (s.isNull() || sub.isNull()) {
            return NullValue.INSTANCE;
        }
        int index = CodePoints.indexOf(string(s, function), string(sub, function), 0);
        return new NumberValue(BigDecimal.valueOf(index < 0 ? 0 : index + 1));
    }

    /** PostgreSQL's {@code ascii}: the first character's code, and 0 for an empty string. */
    private static Value ascii(List<Value> args) {
        if (args.get(0).isNull()) {
            return NullValue.INSTANCE;
        }
        String s = string(args.get(0), "ASCII");
        return new NumberValue(BigDecimal.valueOf(s.isEmpty() ? 0 : CodePoints.first(s)));
    }

    /**
     * PostgreSQL's {@code concat}: the text of each argument in turn, a NULL contributing
     * nothing, so the result is NULL never. A value that is not text is written as it
     * displays.
     */
    private static Value concat(List<Value> args) {
        StringBuilder text = new StringBuilder();
        for (Value value : args) {
            if (!value.isNull()) {
                text.append(value.asDisplayString());
            }
        }
        return new StringValue(text.toString());
    }

    /**
     * PostgreSQL's {@code trunc(x [, places])}: towards zero, to a whole number or to the
     * given number of decimal places, which may be negative to truncate to tens or
     * hundreds. A NULL in either argument returns NULL.
     */
    private static Value trunc(List<Value> args) {
        if (args.get(0).isNull() || args.size() == 2 && args.get(1).isNull()) {
            return NullValue.INSTANCE;
        }
        BigDecimal x = number(args.get(0), "TRUNC");
        int places = args.size() == 2 ? integer(args.get(1), "TRUNC") : 0;
        BigDecimal truncated = x.setScale(places, RoundingMode.DOWN);
        return new NumberValue(places < 0 ? truncated.setScale(0) : truncated);
    }

    /**
     * {@code TRUNC} per dialect: {@code TRUNC(x [, places])} on PostgreSQL, DuckDB and
     * Db2, and MySQL's {@code TRUNCATE(x, places)}, which always takes the places.
     */
    private static Optional<String> truncSpelling(PushdownTarget target, List<String> arguments) {
        if (!target.isFamily(PushdownTarget.SQL)) {
            return Optional.empty();
        }
        if (target.isVariant(Spellings.POSTGRES) || target.isVariant(Spellings.DUCKDB)
                || target.isVariant(Spellings.DB2)) {
            return Optional.of("TRUNC(" + String.join(", ", arguments) + ")");
        }
        if (target.isVariant(Spellings.MYSQL)) {
            return Optional.of("TRUNCATE(" + arguments.get(0) + ", "
                    + (arguments.size() == 2 ? arguments.get(1) : "0") + ")");
        }
        return Optional.empty();
    }
}

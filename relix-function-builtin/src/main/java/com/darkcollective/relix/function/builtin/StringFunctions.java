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
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.value.internal.CodePoints;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.Value;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import static com.darkcollective.relix.function.builtin.Arguments.integer;
import static com.darkcollective.relix.function.builtin.Arguments.reject;
import static com.darkcollective.relix.function.builtin.Arguments.string;
import static com.darkcollective.relix.function.builtin.Category.PURE_DETERMINISTIC;
import static com.darkcollective.relix.function.builtin.Category.PURE_DETERMINISTIC_IDEMPOTENT;
import static com.darkcollective.relix.function.builtin.Category.p;
import static com.darkcollective.relix.symbol.ScalarType.NUMBER;
import static com.darkcollective.relix.symbol.ScalarType.STRING;

/**
 * The string built-ins: length, case, trimming, slicing, search and replacement.
 *
 * <p>Positions are 1-based throughout, which is the convention of the library these
 * functions come from and of SQL, not of Java. A NULL input yields NULL rather than an
 * error — a missing value is not a bad value.
 *
 * <h2>Counted in code points</h2>
 * {@code Len}, {@code Left}, {@code Right}, {@code Mid}, {@code InStr} and {@code Asc}
 * measure and slice a string by <b>code point</b>, not by the UTF-16 code unit a Java
 * {@code char} is — so {@code Len("a}&#128512;{@code b")} is 3, and {@code Left} of two
 * never cuts an emoji in half. See {@code CodePoints} for why that is the language's
 * answer rather than the JDK's, and note that for text of only basic-plane characters
 * the two agree exactly.
 *
 <h2>Which of these a backend is offered</h2>
 * The counting and slicing four, and only where a backend has been <em>run</em> and
 * agreed: MySQL, PostgreSQL and DuckDB count characters, so each computes {@code Len},
 * {@code Left}, {@code Right} and {@code Mid} itself. H2, which resolves to the
 * generic dialect, counts UTF-16 code units exactly as Java used to here — so
 * {@code CHAR_LENGTH} there is a different function wearing the same name, and the
 * generic dialect is offered nothing. "SQL counts characters" is the kind of claim that
 * survives reading and not running.
 *
 * <p>Three more decline everywhere, for reasons that are properties of SQL rather than
 * of the functions:
 *
 * <ul>
 *   <li><b>Case mapping belongs to the collation, not to the function.</b> MySQL
 *       upper-cases {@code Straße} to {@code STRAßE} and Java to {@code STRASSE},
 *       because one is asking a collation and the other a JDK. {@code LCase} is
 *       declined with {@code UCase}: it happens to agree on the values tested, and the
 *       mechanism that breaks its sibling applies to it unchanged.</li>
 *   <li><b>Whitespace is not one set.</b> SQL {@code TRIM} removes spaces where
 *       {@code String.strip} removes every Unicode whitespace character, so a
 *       tab-padded value comes back trimmed from the engine and untrimmed from the
 *       database.</li>
 *   <li><b>Searching is collation-dependent.</b> {@code InStr} counts in code points
 *       now, but SQL's {@code LOCATE} matches under the column's collation and would
 *       find a case-insensitive hit where the engine finds none — the same mechanism
 *       that governs {@code =}, and one the search functions have no equivalent of the
 *       comparison renderer for.</li>
 * </ul>
 *
 * <p>None of this is settled by reading a manual, and none of it was: the agreement
 * suite in {@code relix-connectors-std} runs each of these both ways over values chosen
 * to break a plausible spelling, and it is what decided this list.
 */
final class StringFunctions {

    private static final Category STRINGS = Category.of("string");

    private StringFunctions() {
    }

    static List<ScalarFunction> all() {
        return List.of(
                STRINGS.fn("Len", NUMBER, PURE_DETERMINISTIC, List.of(p("s", STRING)),
                        Spellings.sqlOn("CHAR_LENGTH", 1, Spellings.MYSQL, Spellings.POSTGRES, Spellings.DUCKDB),
                        args -> args.get(0).isNull() ? NullValue.INSTANCE
                                : number(CodePoints.length(string(args.get(0), "Len")))),

                STRINGS.fn("UCase", STRING, PURE_DETERMINISTIC_IDEMPOTENT, List.of(p("s", STRING)),
                        args -> args.get(0).isNull() ? NullValue.INSTANCE
                                : new StringValue(string(args.get(0), "UCase").toUpperCase(Locale.ROOT))),

                STRINGS.fn("LCase", STRING, PURE_DETERMINISTIC_IDEMPOTENT, List.of(p("s", STRING)),
                        args -> args.get(0).isNull() ? NullValue.INSTANCE
                                : new StringValue(string(args.get(0), "LCase").toLowerCase(Locale.ROOT))),

                STRINGS.fn("Trim", STRING, PURE_DETERMINISTIC_IDEMPOTENT, List.of(p("s", STRING)),
                        args -> args.get(0).isNull() ? NullValue.INSTANCE
                                : new StringValue(string(args.get(0), "Trim").strip())),

                STRINGS.fn("LTrim", STRING, PURE_DETERMINISTIC_IDEMPOTENT, List.of(p("s", STRING)),
                        args -> args.get(0).isNull() ? NullValue.INSTANCE
                                : new StringValue(string(args.get(0), "LTrim").stripLeading())),

                STRINGS.fn("RTrim", STRING, PURE_DETERMINISTIC_IDEMPOTENT, List.of(p("s", STRING)),
                        args -> args.get(0).isNull() ? NullValue.INSTANCE
                                : new StringValue(string(args.get(0), "RTrim").stripTrailing())),

                STRINGS.fn("Left", STRING, PURE_DETERMINISTIC,
                        List.of(p("s", STRING), p("n", NUMBER)),
                        Spellings.sqlOn("LEFT", 2, Spellings.MYSQL, Spellings.POSTGRES, Spellings.DUCKDB), StringFunctions::left),

                STRINGS.fn("Right", STRING, PURE_DETERMINISTIC,
                        List.of(p("s", STRING), p("n", NUMBER)),
                        Spellings.sqlOn("RIGHT", 2, Spellings.MYSQL, Spellings.POSTGRES, Spellings.DUCKDB), StringFunctions::right),

                // Mid(s, start) and Mid(s, start, length) are one function of two forms,
                // not two functions: they differ only in whether the trailing argument
                // is passed.
                STRINGS.fn("Mid", STRING, PURE_DETERMINISTIC,
                        List.of(p("s", STRING), p("start", NUMBER), p("length", NUMBER)),
                        Arity.between(2, 3), Spellings.sqlOn("SUBSTRING", Spellings.MYSQL, Spellings.POSTGRES, Spellings.DUCKDB), StringFunctions::mid),

                // InStr(s, find) searches from the beginning; InStr(start, s, find) from
                // a position. The second form shifts what each position means, so the
                // parameter list names the form it can — the arity range carries the rest.
                STRINGS.fn("InStr", NUMBER, PURE_DETERMINISTIC,
                        List.of(p("s", STRING), p("sub", STRING)),
                        Arity.between(2, 3), StringFunctions::inStr),

                STRINGS.fn("Chr", STRING, PURE_DETERMINISTIC, List.of(p("n", NUMBER)),
                        StringFunctions::chr),

                STRINGS.fn("Asc", NUMBER, PURE_DETERMINISTIC, List.of(p("s", STRING)),
                        StringFunctions::asc),

                STRINGS.fn("Replace", STRING, PURE_DETERMINISTIC,
                        List.of(p("s", STRING), p("find", STRING), p("replacement", STRING)),
                        Spellings.sql("REPLACE", 3),
                        args -> {
                            if (args.get(0).isNull()) {
                                return NullValue.INSTANCE;
                            }
                            return new StringValue(string(args.get(0), "Replace")
                                    .replace(string(args.get(1), "Replace"),
                                            string(args.get(2), "Replace")));
                        }));
    }

    private static Value left(List<Value> args) {
        if (args.get(0).isNull()) {
            return NullValue.INSTANCE;
        }
        String s = string(args.get(0), "Left");
        int n = integer(args.get(1), "Left");
        if (n < 0) {
            throw reject("Left: length must be non-negative");
        }
        return new StringValue(CodePoints.substring(s, 0, n));
    }

    private static Value right(List<Value> args) {
        if (args.get(0).isNull()) {
            return NullValue.INSTANCE;
        }
        String s = string(args.get(0), "Right");
        int n = integer(args.get(1), "Right");
        if (n < 0) {
            throw reject("Right: length must be non-negative");
        }
        return new StringValue(CodePoints.last(s, n));
    }

    private static Value mid(List<Value> args) {
        if (args.get(0).isNull()) {
            return NullValue.INSTANCE;
        }
        String s = string(args.get(0), "Mid");
        int start = integer(args.get(1), "Mid");
        if (start < 1) {
            throw reject("Mid: start must be >= 1");
        }
        int from = start - 1;
        if (args.size() == 2) {
            return new StringValue(CodePoints.substring(s, from));
        }
        int length = integer(args.get(2), "Mid");
        if (length < 0) {
            throw reject("Mid: length must be non-negative");
        }
        return new StringValue(CodePoints.substring(s, from, length));
    }

    private static Value inStr(List<Value> args) {
        if (args.size() == 2) {
            if (args.get(0).isNull() || args.get(1).isNull()) {
                return NullValue.INSTANCE;
            }
            String s = string(args.get(0), "InStr");
            String find = string(args.get(1), "InStr");
            return position(CodePoints.indexOf(s, find, 0));
        }
        if (args.get(1).isNull() || args.get(2).isNull()) {
            return NullValue.INSTANCE;
        }
        int start = integer(args.get(0), "InStr");
        String s = string(args.get(1), "InStr");
        String find = string(args.get(2), "InStr");
        return position(CodePoints.indexOf(s, find, start - 1));
    }

    /** Turns a code-point index into the 1-based position the function reports, 0 for absent. */
    private static Value position(int index) {
        return number(index < 0 ? 0 : index + 1);
    }

    private static Value chr(List<Value> args) {
        if (args.get(0).isNull()) {
            return NullValue.INSTANCE;
        }
        int code = integer(args.get(0), "Chr");
        if (!Character.isValidCodePoint(code) || Character.isSurrogate((char) code)) {
            throw reject("Chr: invalid character code " + code);
        }
        return new StringValue(Character.toString(code));
    }

    private static Value asc(List<Value> args) {
        if (args.get(0).isNull()) {
            return NullValue.INSTANCE;
        }
        String s = string(args.get(0), "Asc");
        if (s.isEmpty()) {
            throw reject("Asc: empty string");
        }
        return number(CodePoints.first(s));
    }

    private static NumberValue number(long n) {
        return new NumberValue(BigDecimal.valueOf(n));
    }
}

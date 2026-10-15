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
import static com.darkcollective.relix.symbol.ScalarType.ANY;
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
 * agreed: MySQL, PostgreSQL, DuckDB, SQLite and Db2 count characters, so each computes
 * {@code Len}, {@code Left}, {@code Right} and {@code Mid} itself — SQLite under names of
 * its own, having no {@code CHAR_LENGTH}, {@code LEFT} or {@code RIGHT}, and Db2 through
 * {@code SUBSTRING} told to count in {@code CODEUNITS32}, its own {@code LEFT} counting
 * bytes and padding. H2, which resolves to the
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
                        Spellings.firstOf(
                                Spellings.sqlOn("CHAR_LENGTH", 1, Spellings.MYSQL, Spellings.POSTGRES, Spellings.DUCKDB),
                                // SQLite has no CHAR_LENGTH; its LENGTH counts a TEXT
                                // value's characters, which is the same count.
                                Spellings.sqlOn("LENGTH", 1, Spellings.SQLITE),
                                Spellings.on(Spellings.DB2, 1, a -> "CHARACTER_LENGTH(" + a.get(0)
                                        + ", " + Spellings.DB2_CHARACTERS + ")")),
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
                        Spellings.firstOf(
                                Spellings.sqlOn("LEFT", 2, Spellings.MYSQL, Spellings.POSTGRES, Spellings.DUCKDB),
                                // SQLite has no LEFT; a slice from the first character is it.
                                Spellings.on(Spellings.SQLITE, 2,
                                        a -> "SUBSTR(" + a.get(0) + ", 1, " + a.get(1) + ")"),
                                // Db2's own LEFT pads to the length asked for.
                                Spellings.on(Spellings.DB2, 2,
                                        a -> "SUBSTRING(" + a.get(0) + ", 1, " + a.get(1) + ", "
                                                + Spellings.DB2_CHARACTERS + ")")),
                        StringFunctions::left),

                STRINGS.fn("Right", STRING, PURE_DETERMINISTIC,
                        List.of(p("s", STRING), p("n", NUMBER)),
                        Spellings.firstOf(
                                Spellings.sqlOn("RIGHT", 2, Spellings.MYSQL, Spellings.POSTGRES, Spellings.DUCKDB),
                                // SQLite has no RIGHT, and SUBSTR(s, -n) is not one: at
                                // n = 0 it is SUBSTR(s, 0), the whole string. Counting the
                                // start from the left, floored at the first character,
                                // gives nothing for 0 and everything for n past the end.
                                Spellings.on(Spellings.SQLITE, 2,
                                        a -> "SUBSTR(" + a.get(0) + ", MAX(LENGTH(" + a.get(0)
                                                + ") - " + a.get(1) + ", 0) + 1)"),
                                Spellings.on(Spellings.DB2, 2,
                                        a -> "SUBSTRING(" + a.get(0) + ", GREATEST(CHARACTER_LENGTH("
                                                + a.get(0) + ", " + Spellings.DB2_CHARACTERS + ") - "
                                                + a.get(1) + ", 0) + 1, " + Spellings.DB2_CHARACTERS + ")")),
                        StringFunctions::right),

                // Mid(s, start) and Mid(s, start, length) are one function of two forms,
                // not two functions: they differ only in whether the trailing argument
                // is passed.
                STRINGS.fn("Mid", STRING, PURE_DETERMINISTIC,
                        List.of(p("s", STRING), p("start", NUMBER), p("length", NUMBER)),
                        Arity.between(2, 3), Spellings.firstOf(
                                Spellings.sqlOn("SUBSTRING", Spellings.MYSQL, Spellings.POSTGRES, Spellings.DUCKDB),
                                Spellings.sqlOn("SUBSTR", Spellings.SQLITE),
                                Spellings.on(Spellings.DB2, 2, a -> "SUBSTRING(" + a.get(0) + ", "
                                        + a.get(1) + ", " + Spellings.DB2_CHARACTERS + ")"),
                                Spellings.on(Spellings.DB2, 3, a -> "SUBSTRING(" + a.get(0) + ", "
                                        + a.get(1) + ", " + a.get(2) + ", " + Spellings.DB2_CHARACTERS + ")")),
                        StringFunctions::mid),

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
                        Spellings.firstOf(
                                // SQL Server's REPLACE matches under the collation of its
                                // first argument, which by default ignores case: 'Ada' with
                                // 'a' replaced would lose its capital too.
                                Spellings.on(Spellings.SQLSERVER, 3,
                                        a -> "REPLACE((" + a.get(0) + ") " + Spellings.SQLSERVER_EXACT
                                                + ", " + a.get(1) + ", " + a.get(2) + ")"),
                                Spellings.sql("REPLACE", 3)),
                        args -> {
                            if (args.get(0).isNull()) {
                                return NullValue.INSTANCE;
                            }
                            return new StringValue(string(args.get(0), "Replace")
                                    .replace(string(args.get(1), "Replace"),
                                            string(args.get(2), "Replace")));
                        }),

                // SQL's padding, as PostgreSQL pads: to a length in characters, cutting
                // a longer string to it, with a space unless told otherwise.
                STRINGS.fn("LPAD", STRING, PURE_DETERMINISTIC,
                        List.of(p("s", STRING), p("length", NUMBER), p("fill", STRING)),
                        Arity.between(2, 3), Spellings.sqlOn("LPAD", Spellings.POSTGRES),
                        args -> pad(args, "LPAD", true)),

                STRINGS.fn("RPAD", STRING, PURE_DETERMINISTIC,
                        List.of(p("s", STRING), p("length", NUMBER), p("fill", STRING)),
                        Arity.between(2, 3), Spellings.sqlOn("RPAD", Spellings.POSTGRES),
                        args -> pad(args, "RPAD", false)),

                // Reversed by character, so a character outside the basic plane stays whole.
                STRINGS.fn("REVERSE", STRING, PURE_DETERMINISTIC, List.of(p("s", STRING)),
                        Spellings.sqlOn("REVERSE", 1, Spellings.POSTGRES, Spellings.MYSQL),
                        args -> args.get(0).isNull() ? NullValue.INSTANCE
                                : new StringValue(new StringBuilder(string(args.get(0), "REVERSE"))
                                        .reverse().toString())),

                STRINGS.fn("REPEAT", STRING, PURE_DETERMINISTIC,
                        List.of(p("s", STRING), p("count", NUMBER)),
                        Spellings.sqlOn("REPEAT", 2, Spellings.POSTGRES, Spellings.MYSQL, Spellings.DUCKDB),
                        StringFunctions::repeat),

                STRINGS.fn("SPLIT_PART", STRING, PURE_DETERMINISTIC,
                        List.of(p("s", STRING), p("delimiter", STRING), p("n", NUMBER)),
                        Spellings.sqlOn("SPLIT_PART", 3, Spellings.POSTGRES),
                        StringFunctions::splitPart),

                // Evaluated here only: a backend writes a number or a date as text its own way.
                STRINGS.fn("CONCAT_WS", STRING, PURE_DETERMINISTIC,
                        List.of(p("separator", STRING), p("value", ANY)), Arity.atLeast(2),
                        StringFunctions::concatWs),

                // Case mapping belongs to a backend's collation, as for UCase, so this
                // too is evaluated here only.
                STRINGS.fn("INITCAP", STRING, PURE_DETERMINISTIC, List.of(p("s", STRING)),
                        args -> args.get(0).isNull() ? NullValue.INSTANCE
                                : new StringValue(initcap(string(args.get(0), "INITCAP")))));
    }

    /**
     * {@code LPAD} and {@code RPAD}: {@code s} brought to {@code length} characters, cut
     * from its end where longer and filled where shorter, the fill repeated and cut to
     * fit. An empty fill cannot fill, so a short string comes back as it is; a length
     * of zero or less is the empty string.
     */
    private static Value pad(List<Value> args, String function, boolean left) {
        if (args.stream().anyMatch(Value::isNull)) {
            return NullValue.INSTANCE;
        }
        String s = string(args.get(0), function);
        int length = Math.max(integer(args.get(1), function), 0);
        String fill = args.size() == 3 ? string(args.get(2), function) : " ";
        int have = CodePoints.length(s);
        if (have >= length) {
            return new StringValue(CodePoints.substring(s, 0, length));
        }
        if (fill.isEmpty()) {
            return new StringValue(s);
        }
        int need = length - have;
        StringBuilder padding = new StringBuilder();
        while (CodePoints.length(padding.toString()) < need) {
            padding.append(fill);
        }
        String cut = CodePoints.substring(padding.toString(), 0, need);
        return new StringValue(left ? cut + s : s + cut);
    }

    /** {@code REPEAT(s, n)}: {@code s} written {@code n} times, the empty string for none or fewer. */
    private static Value repeat(List<Value> args) {
        if (args.get(0).isNull() || args.get(1).isNull()) {
            return NullValue.INSTANCE;
        }
        String s = string(args.get(0), "REPEAT");
        int count = integer(args.get(1), "REPEAT");
        if (count > 0 && (long) s.length() * count > MAX_REPEATED) {
            throw reject("REPEAT: the result would be longer than " + MAX_REPEATED + " characters");
        }
        return new StringValue(count <= 0 ? "" : s.repeat(count));
    }

    /** The longest string {@code REPEAT} builds: a hundred million characters. */
    private static final long MAX_REPEATED = 100_000_000L;

    /**
     * PostgreSQL's {@code split_part(s, delimiter, n)}: the {@code n}-th field of
     * {@code s} cut at each delimiter, counting from 1, or from the end for a negative
     * {@code n}; the empty string past the last field. An empty delimiter leaves one
     * field, the whole string. A field number of zero is refused.
     */
    private static Value splitPart(List<Value> args) {
        if (args.stream().anyMatch(Value::isNull)) {
            return NullValue.INSTANCE;
        }
        String s = string(args.get(0), "SPLIT_PART");
        String delimiter = string(args.get(1), "SPLIT_PART");
        int n = integer(args.get(2), "SPLIT_PART");
        if (n == 0) {
            throw reject("SPLIT_PART: field position must not be zero");
        }
        List<String> fields = new java.util.ArrayList<>();
        if (delimiter.isEmpty()) {
            fields.add(s);
        } else {
            int from = 0;
            for (int at = s.indexOf(delimiter); at >= 0; at = s.indexOf(delimiter, from)) {
                fields.add(s.substring(from, at));
                from = at + delimiter.length();
            }
            fields.add(s.substring(from));
        }
        int index = n > 0 ? n - 1 : fields.size() + n;
        return new StringValue(index >= 0 && index < fields.size() ? fields.get(index) : "");
    }

    /**
     * {@code CONCAT_WS(separator, value, …)}: the values' text joined by the separator,
     * a NULL value skipped with its separator, and a NULL separator a NULL result.
     */
    private static Value concatWs(List<Value> args) {
        if (args.get(0).isNull()) {
            return NullValue.INSTANCE;
        }
        String separator = string(args.get(0), "CONCAT_WS");
        return new StringValue(args.subList(1, args.size()).stream()
                .filter(v -> !v.isNull())
                .map(Value::asDisplayString)
                .collect(java.util.stream.Collectors.joining(separator)));
    }

    /**
     * PostgreSQL's {@code initcap}: the first letter or digit of each word upper-cased
     * and the rest lower-cased, a word being a run of letters and digits.
     */
    private static String initcap(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean inWord = false;
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            boolean wordChar = Character.isLetterOrDigit(c);
            out.appendCodePoint(wordChar && !inWord ? Character.toUpperCase(c) : Character.toLowerCase(c));
            inWord = wordChar;
            i += Character.charCount(c);
        }
        return out.toString();
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

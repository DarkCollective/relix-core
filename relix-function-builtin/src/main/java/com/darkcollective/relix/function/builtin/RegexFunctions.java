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
import com.darkcollective.relix.function.PushdownTarget;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.Value;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static com.darkcollective.relix.function.builtin.Arguments.reject;
import static com.darkcollective.relix.function.builtin.Arguments.string;
import static com.darkcollective.relix.function.builtin.Category.PURE_DETERMINISTIC;
import static com.darkcollective.relix.function.builtin.Category.p;
import static com.darkcollective.relix.symbol.ScalarType.BOOLEAN;
import static com.darkcollective.relix.symbol.ScalarType.NUMBER;
import static com.darkcollective.relix.symbol.ScalarType.STRING;

/**
 * Regular expressions: a match test and the three functions SQL builds on one.
 *
 * <p>The dialect is Java's ({@link Pattern}), with {@code .} matching a newline as it
 * does in PostgreSQL. Flags are a string of letters: {@code i} matches ignoring case,
 * {@code c} with it (the default), and {@code g}, for {@code REGEXP_REPLACE} only,
 * replaces every match rather than the first, as PostgreSQL's does. A replacement
 * writes {@code \1} to {@code \9} for a group, {@code \&} for the whole match and
 * {@code \\} for a backslash, as PostgreSQL's does.
 *
 * <h2>Which of these a backend is offered</h2>
 * Regular-expression dialects differ in their corners — what {@code \w} and {@code $}
 * match, what {@code [[:alpha:]]} means — so a pattern goes to a backend only when it
 * is a literal written in the part every dialect here reads alike: literal characters,
 * {@code .}, {@code ^}, quantifiers, groups, alternation, bracketed classes and escaped
 * metacharacters. Anything else, a {@code \d}, a {@code $}, a look-around or a column
 * holding the pattern, is matched here.
 */
final class RegexFunctions {

    private static final Category STRINGS = Category.of("string");

    private RegexFunctions() {
    }

    static List<ScalarFunction> all() {
        return List.of(
                STRINGS.fn("REGEXP_LIKE", BOOLEAN, PURE_DETERMINISTIC,
                        List.of(p("s", STRING), p("pattern", STRING), p("flags", STRING)),
                        Arity.between(2, 3), RegexFunctions::likeSpelling, RegexFunctions::like),

                STRINGS.fn("REGEXP_REPLACE", STRING, PURE_DETERMINISTIC,
                        List.of(p("s", STRING), p("pattern", STRING), p("replacement", STRING),
                                p("flags", STRING)),
                        Arity.between(3, 4), RegexFunctions::replaceSpelling, RegexFunctions::replace),

                STRINGS.fn("REGEXP_SUBSTR", STRING, PURE_DETERMINISTIC,
                        List.of(p("s", STRING), p("pattern", STRING), p("flags", STRING)),
                        Arity.between(2, 3), RegexFunctions::substr),

                STRINGS.fn("REGEXP_COUNT", NUMBER, PURE_DETERMINISTIC,
                        List.of(p("s", STRING), p("pattern", STRING), p("flags", STRING)),
                        Arity.between(2, 3), RegexFunctions::count));
    }

    // ── Evaluation ────────────────────────────────────────────────────────────

    private static Value like(List<Value> args) {
        if (args.stream().anyMatch(Value::isNull)) {
            return NullValue.INSTANCE;
        }
        Matcher m = matcher(args, "REGEXP_LIKE", 2, false);
        return BooleanValue.of(m.find());
    }

    private static Value substr(List<Value> args) {
        if (args.stream().anyMatch(Value::isNull)) {
            return NullValue.INSTANCE;
        }
        Matcher m = matcher(args, "REGEXP_SUBSTR", 2, false);
        return m.find() ? new StringValue(m.group()) : NullValue.INSTANCE;
    }

    private static Value count(List<Value> args) {
        if (args.stream().anyMatch(Value::isNull)) {
            return NullValue.INSTANCE;
        }
        Matcher m = matcher(args, "REGEXP_COUNT", 2, false);
        long n = 0;
        while (m.find()) {
            n++;
        }
        return new NumberValue(BigDecimal.valueOf(n));
    }

    private static Value replace(List<Value> args) {
        if (args.stream().anyMatch(Value::isNull)) {
            return NullValue.INSTANCE;
        }
        String flags = args.size() == 4 ? string(args.get(3), "REGEXP_REPLACE") : "";
        Matcher m = matcher(args, "REGEXP_REPLACE", 3, true);
        String s = string(args.get(0), "REGEXP_REPLACE");
        String replacement = string(args.get(2), "REGEXP_REPLACE");
        boolean global = flags.indexOf('g') >= 0;
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(s, last, m.start()).append(expand(replacement, m));
            last = m.end();
            if (!global) {
                break;
            }
        }
        return new StringValue(out.append(s, last, s.length()).toString());
    }

    /** A PostgreSQL replacement: {@code \1}–{@code \9} a group, {@code \&} the match, {@code \\} a backslash. */
    private static String expand(String replacement, Matcher m) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < replacement.length(); i++) {
            char c = replacement.charAt(i);
            if (c != '\\' || i + 1 == replacement.length()) {
                out.append(c);
                continue;
            }
            char next = replacement.charAt(++i);
            if (next >= '1' && next <= '9') {
                int group = next - '0';
                if (group <= m.groupCount() && m.group(group) != null) {
                    out.append(m.group(group));
                }
            } else if (next == '&') {
                out.append(m.group());
            } else {
                out.append(next);
            }
        }
        return out.toString();
    }

    /**
     * The matcher for a call's string and pattern, under the flags at {@code flagIndex}
     * when given. {@code g} is a flag only where {@code replacing}.
     */
    private static Matcher matcher(List<Value> args, String function, int flagIndex, boolean replacing) {
        String s = string(args.get(0), function);
        String pattern = string(args.get(1), function);
        String flags = args.size() > flagIndex ? string(args.get(flagIndex), function) : "";
        return compile(pattern, flags, function, replacing).matcher(s);
    }

    /** Compiled patterns, so a pattern written once is compiled once per run rather than per row. */
    private static final Map<String, Pattern> CACHE = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Pattern> eldest) {
                    return size() > 256;
                }
            });

    private static Pattern compile(String pattern, String flags, String function, boolean replacing) {
        int options = Pattern.DOTALL;
        for (char flag : flags.toCharArray()) {
            switch (flag) {
                case 'i' -> options |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                case 'c' -> options &= ~(Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                case 'g' -> {
                    if (!replacing) {
                        throw reject(function + ": unknown flag 'g'");
                    }
                }
                default -> throw reject(function + ": unknown flag '" + flag + "'");
            }
        }
        String key = options + "\u0000" + pattern;
        Pattern compiled = CACHE.get(key);
        if (compiled == null) {
            try {
                compiled = Pattern.compile(pattern, options);
            } catch (PatternSyntaxException e) {
                throw reject(function + ": invalid regular expression '" + pattern + "': "
                        + e.getDescription());
            }
            CACHE.put(key, compiled);
        }
        return compiled;
    }

    // ── Backend spellings ─────────────────────────────────────────────────────

    /**
     * {@code REGEXP_LIKE} where the pattern and flags are literals every dialect reads as
     * Java does: {@code (s ~ p)} or {@code ~*} on PostgreSQL, and MySQL's
     * {@code REGEXP_LIKE(s, p, 'cn')} — case-sensitive whatever the column's collation,
     * with {@code .} matching a newline — or {@code 'in'} for {@code i}.
     */
    private static Optional<String> likeSpelling(PushdownTarget target, List<String> arguments) {
        if (!target.isFamily(PushdownTarget.SQL) || arguments.size() < 2) {
            return Optional.empty();
        }
        Optional<Boolean> ignoreCase = portableFlags(arguments, 2, false);
        if (ignoreCase.isEmpty() || !portable(arguments.get(1))) {
            return Optional.empty();
        }
        String s = arguments.get(0);
        String p = arguments.get(1);
        if (target.isVariant(Spellings.POSTGRES)) {
            return Optional.of("(" + s + (ignoreCase.get() ? " ~* " : " ~ ") + p + ")");
        }
        if (target.isVariant(Spellings.MYSQL)) {
            return Optional.of("REGEXP_LIKE(" + s + ", " + p + ", '" + (ignoreCase.get() ? "in" : "cn") + "')");
        }
        return Optional.empty();
    }

    /**
     * {@code REGEXP_REPLACE} on PostgreSQL, whose function it is, for a portable pattern
     * and a replacement whose only backslashes are the ones PostgreSQL reads too.
     */
    private static Optional<String> replaceSpelling(PushdownTarget target, List<String> arguments) {
        if (!target.isFamily(PushdownTarget.SQL) || !target.isVariant(Spellings.POSTGRES)
                || arguments.size() < 3 || !portable(arguments.get(1))
                || Spellings.literal(arguments.get(2)).isEmpty()) {
            return Optional.empty();
        }
        Optional<Boolean> flags = portableFlags(arguments, 3, true);
        if (flags.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("regexp_replace(" + String.join(", ", arguments) + ")");
    }

    /**
     * Whether a call's flags are a literal of {@code i}, {@code c} and, where
     * {@code replacing}, {@code g}: empty when they are not, else whether they ignore case.
     */
    private static Optional<Boolean> portableFlags(List<String> arguments, int index, boolean replacing) {
        if (arguments.size() <= index) {
            return Optional.of(false);
        }
        Optional<String> flags = Spellings.literal(arguments.get(index));
        if (flags.isEmpty() || !flags.get().matches(replacing ? "[icg]*" : "[ic]*")) {
            return Optional.empty();
        }
        String f = flags.get();
        return Optional.of(f.lastIndexOf('i') > f.lastIndexOf('c'));
    }

    /**
     * Whether a rendered pattern is a SQL string literal written only in the part of
     * the syntax every dialect here reads as Java does: no class escape such as
     * {@code \d}, no {@code $}, no POSIX class, no inline flag or look-around, and a
     * backslash only before a metacharacter.
     */
    static boolean portable(String rendered) {
        Optional<String> literal = Spellings.literal(rendered);
        if (literal.isEmpty()) {
            return false;
        }
        String p = literal.get();
        boolean inClass = false;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            char next = i + 1 < p.length() ? p.charAt(i + 1) : '\u0000';
            if (c == '\\') {
                if (next == '\u0000' || ".^$*+?()[]{}|\\-".indexOf(next) < 0) {
                    return false;
                }
                i++;
            } else if (inClass) {
                // Java reads [ and && inside a class as a union and an intersection.
                if (c == '[' || c == '&' && next == '&') {
                    return false;
                }
                inClass = c != ']';
            } else if (c == '$' || c == '(' && next == '?') {
                return false;
            } else if (c == '[') {
                inClass = true;
                // A ] first in the class, or after its ^, is a literal ].
                if (next == '^') {
                    i++;
                }
                if (i + 1 < p.length() && p.charAt(i + 1) == ']') {
                    i++;
                }
            }
        }
        return !inClass;
    }
}

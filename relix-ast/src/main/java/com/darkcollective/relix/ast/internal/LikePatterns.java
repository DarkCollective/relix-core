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
package com.darkcollective.relix.ast.internal;

import com.darkcollective.relix.ast.PatternPredicate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Translation of a SQL {@code LIKE} pattern into an anchored regular expression.
 *
 * <p>{@code %} becomes {@code .*} and {@code _} becomes {@code .}; every regex
 * metacharacter is escaped so it matches literally; the result is anchored with
 * {@code ^}…{@code $} so it matches the whole value, as {@code LIKE} does.
 *
 * <p>{@code %} and {@code _} match any character, a line terminator included, as they
 * do in SQL: the regex is compiled in dotall mode.
 *
 * <p>The in-engine evaluator does not run that regex. {@link #matches} reads the same
 * pattern with a wildcard matcher whose cost is at most the product of the two lengths,
 * where a regex backtracks exponentially on {@code %a%a%a%…c}, and keeps the parsed
 * pattern for the next row, a constant pattern being the usual case.
 *
 * <p>This lives beside {@link PatternPredicate} because more than one consumer
 * needs it — the in-engine predicate evaluator and the MongoDB {@code $regex}
 * renderer — and they previously carried byte-identical private copies,
 * including the same hand-typed metacharacter set. They agreed only by luck.
 *
 * <h2>Known divergences from SQL {@code LIKE}</h2>
 * <p>A σ that is <em>pushed down</em> to a SQL backend is evaluated by that
 * backend's own {@code LIKE}, not by this translation, so the two are not
 * interchangeable in every case. The differences, all pre-existing:
 * <ul>
 *   <li><b>Case sensitivity.</b> This translation is case-sensitive. MySQL's
 *       {@code LIKE} is case-insensitive under its default collation.</li>
 *   <li><b>Default escape.</b> Without {@code ESCAPE}, no character escapes here,
 *       so a backslash is a literal backslash. PostgreSQL and MySQL read a backslash
 *       as an escape by default.</li>
 * </ul>
 *
 * <p>With an escape character, the character after it is literal: PostgreSQL's
 * reading, so the escape before an ordinary character makes it match itself, and
 * a pattern that ends with the escape is refused.
 * Centralising the translation here is what makes those differences visible in
 * one place and fixable in one place.
 */
public final class LikePatterns {

    private LikePatterns() {
    }

    /** Regex metacharacters that must be escaped to match literally. */
    private static final String METACHARACTERS = "\\^$.|?*+()[]{}";

    /**
     * Converts a SQL {@code LIKE} pattern to an anchored regular expression.
     *
     * @param pattern the LIKE pattern, using {@code %} and {@code _} wildcards;
     *                must not be null
     * @return an anchored regex matching the same values, e.g. {@code a%} becomes
     *         {@code ^a.*$}
     */
    public static String toRegex(String pattern) {
        return toRegex(pattern, Optional.empty());
    }

    /**
     * Converts a SQL {@code LIKE} pattern with an optional escape character to an
     * anchored regular expression.
     *
     * @param pattern the LIKE pattern; must not be null
     * @param escape  the escape character, or empty for none; must not be null
     * @return an anchored regex matching the same values
     * @throws IllegalArgumentException if the pattern ends with the escape character
     */
    public static String toRegex(String pattern, Optional<String> escape) {
        return "^(?s:" + translate(pattern, escape, ".*", ".",
                ch -> METACHARACTERS.indexOf(ch) >= 0 ? "\\" + ch : String.valueOf(ch)) + ")$";
    }

    /**
     * Rewrites a {@code LIKE} pattern in another matcher's terms: each {@code %} as
     * {@code anySequence}, each {@code _} as {@code anyCharacter}, and every other
     * character, including one an escape made literal, through {@code literal}.
     *
     * @param pattern      the LIKE pattern; must not be null
     * @param escape       the escape character, or empty for none; must not be null
     * @param anySequence  what a {@code %} becomes
     * @param anyCharacter what a {@code _} becomes
     * @param literal      what a character that matches only itself becomes
     * @return the rewritten pattern, unanchored
     * @throws IllegalArgumentException if the pattern ends with the escape character
     */
    public static String translate(String pattern, Optional<String> escape, String anySequence,
                                   String anyCharacter, Function<Character, String> literal) {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(escape, "escape");
        char escapeChar = escape.map(e -> e.charAt(0)).orElse('\0');
        boolean escapes = escape.isPresent();
        StringBuilder out = new StringBuilder(pattern.length() + 2);
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (escapes && ch == escapeChar) {
                if (++i == pattern.length()) {
                    throw new IllegalArgumentException(
                            "LIKE pattern must not end with the escape character");
                }
                out.append(literal.apply(pattern.charAt(i)));
            } else if (ch == '%') {
                out.append(anySequence);
            } else if (ch == '_') {
                out.append(anyCharacter);
            } else {
                out.append(literal.apply(ch));
            }
        }
        return out.toString();
    }

    // =========================================================================
    // Direct matcher
    // =========================================================================

    /** Token for {@code %}. */
    private static final int ANY_SEQUENCE = -1;
    /** Token for {@code _}. */
    private static final int ANY_CHARACTER = -2;
    /** Patterns kept for the next row; a script rarely has more than a handful. */
    private static final int CACHE_LIMIT = 256;

    private record Key(String pattern, Optional<String> escape) {
    }

    private static final Map<Key, int[]> COMPILED = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, int[]> eldest) {
            return size() > CACHE_LIMIT;
        }
    };

    /**
     * Whether {@code text} satisfies the {@code LIKE} pattern, whole value, comparing code
     * points. {@code %} and {@code _} match any character including a line terminator.
     *
     * @param text    the value; must not be null
     * @param pattern the LIKE pattern; must not be null
     * @param escape  the escape character, or empty for none; must not be null
     * @return whether the value matches
     * @throws IllegalArgumentException if the pattern ends with the escape character
     */
    public static boolean matches(String text, String pattern, Optional<String> escape) {
        Objects.requireNonNull(text, "text");
        int[] tokens = compile(pattern, escape);
        int[] value = text.codePoints().toArray();
        int t = 0;
        int p = 0;
        int starAt = -1;
        int resume = 0;
        while (t < value.length) {
            if (p < tokens.length && tokens[p] == ANY_SEQUENCE) {
                starAt = p++;
                resume = t;
            } else if (p < tokens.length && (tokens[p] == ANY_CHARACTER || tokens[p] == value[t])) {
                p++;
                t++;
            } else if (starAt >= 0) {
                p = starAt + 1;
                t = ++resume;
            } else {
                return false;
            }
        }
        while (p < tokens.length && tokens[p] == ANY_SEQUENCE) {
            p++;
        }
        return p == tokens.length;
    }

    private static int[] compile(String pattern, Optional<String> escape) {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(escape, "escape");
        Key key = new Key(pattern, escape);
        synchronized (COMPILED) {
            int[] known = COMPILED.get(key);
            if (known != null) {
                return known;
            }
        }
        int escapeChar = escape.map(e -> e.codePointAt(0)).orElse(-1);
        List<Integer> tokens = new ArrayList<>();
        int[] chars = pattern.codePoints().toArray();
        for (int i = 0; i < chars.length; i++) {
            int ch = chars[i];
            if (ch == escapeChar) {
                if (++i == chars.length) {
                    throw new IllegalArgumentException(
                            "LIKE pattern must not end with the escape character");
                }
                tokens.add(chars[i]);
            } else if (ch == '%') {
                // a run of % is one
                if (tokens.isEmpty() || tokens.getLast() != ANY_SEQUENCE) {
                    tokens.add(ANY_SEQUENCE);
                }
            } else if (ch == '_') {
                tokens.add(ANY_CHARACTER);
            } else {
                tokens.add(ch);
            }
        }
        int[] compiled = tokens.stream().mapToInt(Integer::intValue).toArray();
        synchronized (COMPILED) {
            COMPILED.put(key, compiled);
        }
        return compiled;
    }
}

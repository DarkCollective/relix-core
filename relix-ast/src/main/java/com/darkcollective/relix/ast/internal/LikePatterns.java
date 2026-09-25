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
import java.util.Objects;

/**
 * Translation of a SQL {@code LIKE} pattern into an anchored regular expression.
 *
 * <p>{@code %} becomes {@code .*} and {@code _} becomes {@code .}; every regex
 * metacharacter is escaped so it matches literally; the result is anchored with
 * {@code ^}…{@code $} so it matches the whole value, as {@code LIKE} does.
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
 *   <li><b>Newlines.</b> {@code _} maps to regex {@code .}, which does not match
 *       a line terminator; SQL's {@code _} matches any character.</li>
 *   <li><b>Escape clause.</b> {@code LIKE … ESCAPE} is not modelled, so a
 *       backslash in a pattern is treated as a literal backslash rather than as
 *       an escape for a following {@code %} or {@code _}.</li>
 * </ul>
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
        Objects.requireNonNull(pattern, "pattern");
        StringBuilder regex = new StringBuilder(pattern.length() + 2).append('^');
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (ch == '%') {
                regex.append(".*");
            } else if (ch == '_') {
                regex.append('.');
            } else if (METACHARACTERS.indexOf(ch) >= 0) {
                regex.append('\\').append(ch);
            } else {
                regex.append(ch);
            }
        }
        return regex.append('$').toString();
    }
}

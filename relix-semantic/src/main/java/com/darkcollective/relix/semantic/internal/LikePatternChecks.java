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

import com.darkcollective.relix.ast.PatternPredicate;
import com.darkcollective.relix.ast.StringOperand;

import java.util.Optional;

/**
 * Warns of a LIKE pattern whose {@code _} is probably meant as an underscore.
 *
 * <p>{@code code LIKE '%_%'}, written for "the codes that contain an underscore", matches
 * every code of one character or more, and runs without a word. The pattern is checked
 * only when it has no {@code ESCAPE} and every {@code _} in it stands beside a {@code %},
 * where one is almost never meant as "any one character". A {@code _} between other
 * characters or at an end, {@code 'A_C'}, {@code '___'} or {@code 'AB-_'}, is left
 * alone: there it is the wildcard, written for a value of a known length.
 *
 * <p>The warning rewrites the pattern. Where every {@code _} already follows the same
 * escape-like character, {@code '%!_%'}, it is the {@code ESCAPE} that was left out;
 * otherwise each {@code _} is escaped with a character the pattern does not use.
 */
final class LikePatternChecks {

    /** The characters an escape is chosen from, in order of preference. */
    private static final String ESCAPES = "!#^~@";

    private LikePatternChecks() {
    }

    /**
     * The warning for {@code like}'s pattern, without the operator's label, or empty.
     *
     * @param like the pattern test
     * @return the warning, if its {@code _} looks meant as an underscore
     */
    static Optional<String> underscoreWarning(PatternPredicate like) {
        if (like.escape().isPresent() || !(like.pattern() instanceof StringOperand literal)) {
            return Optional.empty();
        }
        String pattern = literal.value();
        if (pattern.indexOf('_') < 0) {
            return Optional.empty();
        }
        Character before = null;
        boolean sameBefore = true;
        for (int i = pattern.indexOf('_'); i >= 0; i = pattern.indexOf('_', i + 1)) {
            Character previous = i > 0 ? pattern.charAt(i - 1) : null;
            boolean besidePercent = previous != null && previous == '%'
                    || i + 1 < pattern.length() && pattern.charAt(i + 1) == '%';
            if (!besidePercent) {
                return Optional.empty();
            }
            sameBefore &= previous != null && (before == null || before.equals(previous));
            before = previous;
        }
        String escaped;
        String escape;
        if (sameBefore && ESCAPES.indexOf(before) >= 0) {
            escaped = pattern;
            escape = String.valueOf(before);
        } else {
            Optional<String> unused = ESCAPES.chars()
                    .mapToObj(c -> String.valueOf((char) c))
                    .filter(c -> !pattern.contains(c))
                    .findFirst();
            if (unused.isEmpty()) {
                return Optional.empty();
            }
            escape = unused.get();
            escaped = pattern.replace("_", escape + "_");
        }
        return Optional.of("'_' in LIKE matches any one character; to match an underscore,"
                + " write LIKE " + quoted(escaped) + " ESCAPE " + quoted(escape));
    }

    /** {@code text} as a single-quoted string literal. */
    private static String quoted(String text) {
        return "'" + text.replace("'", "''") + "'";
    }
}

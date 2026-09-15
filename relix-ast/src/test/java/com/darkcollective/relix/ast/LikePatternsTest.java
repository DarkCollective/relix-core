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
package com.darkcollective.relix.ast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Tests for {@link LikePatterns}, the shared SQL {@code LIKE} → regex
 * translation.
 *
 * <p>Assertions are written against <em>matching behaviour</em> wherever
 * possible rather than the exact regex text, so the translation stays free to
 * change spelling as long as it keeps matching the same values.
 */
@DisplayName("LikePatterns — SQL LIKE to regex")
final class LikePatternsTest {

    /** Whether {@code value} satisfies {@code LIKE pattern}. */
    private static boolean matches(String value, String pattern) {
        return Pattern.matches(LikePatterns.toRegex(pattern), value);
    }

    @Nested
    @DisplayName("wildcards")
    class Wildcards {

        @Test
        @DisplayName("% matches any run of characters, including none")
        void percentMatchesAnyRun() {
            assertThat(matches("smith", "%smith%")).isTrue();
            assertThat(matches("mr smith jr", "%smith%")).isTrue();
            assertThat(matches("smith", "smith%")).isTrue();
            assertThat(matches("smithers", "smith%")).isTrue();
            assertThat(matches("blacksmith", "smith%")).isFalse();
        }

        @Test
        @DisplayName("_ matches exactly one character")
        void underscoreMatchesOne() {
            assertThat(matches("abc", "a_c")).isTrue();
            assertThat(matches("ac", "a_c")).isFalse();
            assertThat(matches("abbc", "a_c")).isFalse();
        }

        @Test
        @DisplayName("a pattern with no wildcard is an exact match")
        void literalPatternIsExact() {
            assertThat(matches("abc", "abc")).isTrue();
            assertThat(matches("abcd", "abc")).isFalse();
            assertThat(matches("xabc", "abc")).isFalse();
        }

        @Test
        @DisplayName("the empty pattern matches only the empty string")
        void emptyPattern() {
            assertThat(matches("", "")).isTrue();
            assertThat(matches("a", "")).isFalse();
        }
    }

    @Nested
    @DisplayName("regex metacharacters are matched literally")
    class Metacharacters {

        @Test
        @DisplayName("a dot in the pattern does not act as a wildcard")
        void dotIsLiteral() {
            assertThat(matches("a.c", "a.c")).isTrue();
            assertThat(matches("abc", "a.c")).isFalse();
        }

        @Test
        @DisplayName("every metacharacter round-trips as a literal")
        void allMetacharactersAreEscaped() {
            // Each of these would otherwise change the regex's meaning or make it
            // fail to compile.
            for (String meta : new String[] {
                    "\\", "^", "$", ".", "|", "?", "*", "+", "(", ")", "[", "]", "{", "}" }) {
                String value = "x" + meta + "y";
                assertThat(matches(value, value))
                        .as("literal %s", meta)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("a braced quantifier in the pattern is literal, not a repeat count")
        void bracesAreLiteral() {
            assertThat(matches("a{2}", "a{2}")).isTrue();
            assertThat(matches("aa", "a{2}")).isFalse();
        }

        @Test
        @DisplayName("a character class in the pattern is literal")
        void bracketsAreLiteral() {
            assertThat(matches("[abc]", "[abc]")).isTrue();
            assertThat(matches("a", "[abc]")).isFalse();
        }
    }

    @Nested
    @DisplayName("anchoring")
    class Anchoring {

        @Test
        @DisplayName("the pattern must match the whole value")
        void wholeValue() {
            assertThat(LikePatterns.toRegex("a")).startsWith("^").endsWith("$");
            assertThat(matches("xay", "a")).isFalse();
        }
    }

    @Test
    @DisplayName("rejects null")
    void rejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> LikePatterns.toRegex(null));
    }

    /**
     * These pin the documented gaps between this translation and a backend's own
     * {@code LIKE} — they are not endorsements, they are a tripwire so that
     * changing any of them is a deliberate, visible act.
     */
    @Nested
    @DisplayName("documented divergences from SQL LIKE")
    class DocumentedDivergences {

        @Test
        @DisplayName("matching is case-sensitive (MySQL's LIKE is not, by default)")
        void caseSensitive() {
            assertThat(matches("ABC", "abc")).isFalse();
        }

        @Test
        @DisplayName("_ does not match a newline (SQL's does)")
        void underscoreDoesNotMatchNewline() {
            assertThat(matches("a\nb", "a_b")).isFalse();
        }

        @Test
        @DisplayName("a backslash is literal — the ESCAPE clause is not modelled")
        void backslashIsNotAnEscape() {
            // In SQL, LIKE '100\%' matches the literal "100%". Here the backslash
            // is literal and the % is still a wildcard.
            assertThat(matches("100%", "100\\%")).isFalse();
            assertThat(matches("100\\anything", "100\\%")).isTrue();
        }
    }
}

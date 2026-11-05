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
    @DisplayName("an escape character")
    class Escapes {

        private boolean matches(String value, String pattern, String escape) {
            return Pattern.matches(LikePatterns.toRegex(pattern, java.util.Optional.of(escape)), value);
        }

        @Test
        @DisplayName("makes the % or _ after it literal")
        void escapedWildcards() {
            assertThat(matches("10% off", "%0!%%", "!")).isTrue();
            assertThat(matches("100 points", "%0!%%", "!")).isFalse();
            assertThat(matches("save_20", "save!_%", "!")).isTrue();
            assertThat(matches("save 20", "save!_%", "!")).isFalse();
        }

        @Test
        @DisplayName("escapes itself, and makes an ordinary character match itself, as PostgreSQL does")
        void escapedOrdinary() {
            assertThat(matches("a!b", "a!!b", "!")).isTrue();
            assertThat(matches("ab", "!a!b", "!")).isTrue();
            assertThat(matches("a.b", "a\\.b", "\\")).isTrue();
            assertThat(matches("axb", "a\\.b", "\\")).isFalse();
        }

        @Test
        @DisplayName("refuses a pattern that ends with the escape")
        void trailingEscape() {
            org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> LikePatterns.toRegex("50!", java.util.Optional.of("!")))
                    .withMessage("LIKE pattern must not end with the escape character");
        }

        @Test
        @DisplayName("without one, a backslash is an ordinary character")
        void noEscape() {
            assertThat(LikePatternsTest.matches("a\\%", "a\\%")).isTrue();
            assertThat(LikePatternsTest.matches("a\\b", "a\\%")).isTrue();
        }
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
        @DisplayName("a backslash is literal — the ESCAPE clause is not modelled")
        void backslashIsNotAnEscape() {
            // In SQL, LIKE '100\%' matches the literal "100%". Here the backslash
            // is literal and the % is still a wildcard.
            assertThat(matches("100%", "100\\%")).isFalse();
            assertThat(matches("100\\anything", "100\\%")).isTrue();
        }
    }

    @Nested
    @DisplayName("the direct matcher")
    class DirectMatcher {

        private boolean like(String text, String pattern) {
            return LikePatterns.matches(text, pattern, java.util.Optional.empty());
        }

        @Test
        @DisplayName("% and _ match every line terminator, as they do in SQL")
        void wildcardsMatchNewlines() {
            for (String terminator : new String[] {"\n", "\r", "\u0085", "\u2028", "\u2029"}) {
                assertThat(like("a" + terminator + "b", "a%b")).as("%% over %s", terminator.codePointAt(0)).isTrue();
                assertThat(like("a" + terminator + "b", "a_b")).as("_ over %s", terminator.codePointAt(0)).isTrue();
                // and the regex a pushdown sends does too
                assertThat(matches("a" + terminator + "b", "a%b")).isTrue();
                assertThat(matches("a" + terminator + "b", "a_b")).isTrue();
            }
        }

        @Test
        @DisplayName("agrees with the regex on a sweep of patterns and values")
        void agreesWithRegex() {
            String[] patterns = {"", "%", "_", "a%", "%a", "%a%", "a_c", "%a%b%", "a%%b", "__", "%_%", "ab%ab", "x%y_z"};
            String[] values = {"", "a", "ab", "abc", "ba", "aab", "abab", "ababab", "xyz", "xaayqz", "a\nb", "\uD83D\uDE00a"};
            for (String pattern : patterns) {
                for (String value : values) {
                    assertThat(like(value, pattern))
                            .as("'%s' LIKE '%s'", value, pattern)
                            .isEqualTo(matches(value, pattern));
                }
            }
        }

        @Test
        @DisplayName("counts a code point outside the BMP as one character")
        void codePoints() {
            assertThat(like("\uD83D\uDE00", "_")).isTrue();
            assertThat(like("\uD83D\uDE00", "__")).isFalse();
        }

        @Test
        @DisplayName("honours an escape")
        void escape() {
            assertThat(LikePatterns.matches("10%", "10!%", java.util.Optional.of("!"))).isTrue();
            assertThat(LikePatterns.matches("100", "10!%", java.util.Optional.of("!"))).isFalse();
            org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> LikePatterns.matches("x", "x!", java.util.Optional.of("!")))
                    .withMessage("LIKE pattern must not end with the escape character");
        }

        @Test
        @DisplayName("takes polynomial time where a regex backtracks exponentially")
        void noCatastrophicBacktracking() {
            String pattern = "%a".repeat(60) + "%c";
            String text = "a".repeat(3000) + "b";
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(5),
                    () -> assertThat(like(text, pattern)).isFalse());
        }

        @Test
        @DisplayName("keeps compiling correct when more patterns than the cache holds are used")
        void cacheEviction() {
            for (int i = 0; i < 600; i++) {
                assertThat(like("v" + i, "v" + i)).isTrue();
                assertThat(like("v" + i, "w" + i)).isFalse();
            }
            assertThat(like("v1", "v1")).isTrue();
        }

        @Test
        @DisplayName("rejects null")
        void nulls() {
            assertThatNullPointerException().isThrownBy(() -> like(null, "a"));
            assertThatNullPointerException().isThrownBy(() -> like("a", null));
        }
    }
}

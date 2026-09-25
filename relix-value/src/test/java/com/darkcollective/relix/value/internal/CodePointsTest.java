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
package com.darkcollective.relix.value.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Strings measured, sliced and ordered by code point.
 *
 * <p>Every case here is written against a string holding a character outside the basic
 * multilingual plane, because that is the only place these differ from the {@code String}
 * methods they replace. The BMP cases are there to pin that they still agree — a
 * replacement that changed the answer for ordinary text would be a different bug.
 */
@DisplayName("Code points")
final class CodePointsTest {

    /** U+1F600 GRINNING FACE: one code point, two UTF-16 code units. */
    private static final String EMOJI = "😀";

    /** U+FF5E FULLWIDTH TILDE: one of each, and above the emoji's leading surrogate. */
    private static final String TILDE = "～";

    @Nested
    @DisplayName("length")
    final class Length {

        @Test
        @DisplayName("counts a supplementary character once, where String.length counts twice")
        void supplementary() {
            assertThat(CodePoints.length("a" + EMOJI + "b")).isEqualTo(3);
            assertThat(("a" + EMOJI + "b").length()).isEqualTo(4);
        }

        @Test
        @DisplayName("agrees with String.length for text that has none")
        void basicPlane() {
            for (String text : new String[] {"", "a", "Ada", "Straße", TILDE, "\t x \t"}) {
                assertThat(CodePoints.length(text)).as("%s", text).isEqualTo(text.length());
            }
        }
    }

    @Nested
    @DisplayName("slicing")
    final class Slicing {

        @Test
        @DisplayName("never cuts a character in half")
        void keepsCharactersWhole() {
            // substring(0, 2) on the UTF-16 units would return "a" plus a lone surrogate.
            assertThat(CodePoints.substring("a" + EMOJI + "b", 0, 2)).isEqualTo("a" + EMOJI);
            assertThat(CodePoints.substring("a" + EMOJI + "b", 1)).isEqualTo(EMOJI + "b");
            assertThat(CodePoints.last("a" + EMOJI + "b", 2)).isEqualTo(EMOJI + "b");
        }

        @Test
        @DisplayName("clamps rather than raising — the callers ask for 'up to n'")
        void clamps() {
            assertThat(CodePoints.substring("abc", 0, 99)).isEqualTo("abc");
            assertThat(CodePoints.substring("abc", 99)).isEmpty();
            assertThat(CodePoints.substring("abc", 99, 2)).isEmpty();
            assertThat(CodePoints.substring("abc", 1, 0)).isEmpty();
            assertThat(CodePoints.last("abc", 99)).isEqualTo("abc");
            assertThat(CodePoints.last("abc", 0)).isEmpty();
        }
    }

    @Nested
    @DisplayName("searching")
    final class Searching {

        @Test
        @DisplayName("reports a position in code points, not in code units")
        void position() {
            assertThat(CodePoints.indexOf("a" + EMOJI + "b", "b", 0)).isEqualTo(2);
            assertThat(CodePoints.indexOf("a" + EMOJI + "b", EMOJI, 0)).isEqualTo(1);
            assertThat(CodePoints.indexOf("abcabc", "b", 2)).isEqualTo(4);
        }

        @Test
        @DisplayName("answers -1 for a string that does not occur")
        void absent() {
            assertThat(CodePoints.indexOf("abc", "z", 0)).isEqualTo(-1);
            assertThat(CodePoints.indexOf("abc", "a", 99)).isEqualTo(-1);
        }
    }

    @Nested
    @DisplayName("ordering")
    final class Ordering {

        /**
         * The pair that tells the two rules apart. As code points {@code U+FF5E} is below
         * {@code U+1F600}; as UTF-16 code units the emoji leads with the surrogate
         * {@code 0xD83D}, which is below {@code 0xFF5E} — so {@code String.compareTo}
         * puts them the other way round.
         */
        @Test
        @DisplayName("puts a supplementary character after a high basic-plane one")
        void supplementaryAfterBasicPlane() {
            assertThat(CodePoints.compare(TILDE, EMOJI)).isNegative();
            assertThat(TILDE.compareTo(EMOJI)).isPositive();
        }

        @Test
        @DisplayName("agrees with String.compareTo wherever no supplementary character is involved")
        void basicPlane() {
            String[] words = {"", "A", "Ada", "Alan", "a", "grace", "Straße", TILDE};
            for (String a : words) {
                for (String b : words) {
                    assertThat(Integer.signum(CodePoints.compare(a, b)))
                            .as("%s vs %s", a, b)
                            .isEqualTo(Integer.signum(a.compareTo(b)));
                }
            }
        }

        @Test
        @DisplayName("a prefix sorts before what extends it, and equal strings compare equal")
        void prefixAndEquality() {
            assertThat(CodePoints.compare("ab", "abc")).isNegative();
            assertThat(CodePoints.compare("abc", "ab")).isPositive();
            assertThat(CodePoints.compare("abc", "abc")).isZero();
            assertThat(CodePoints.compare(EMOJI, EMOJI)).isZero();
            assertThat(CodePoints.compare("", "a")).isNegative();
        }
    }

    @Test
    @DisplayName("the first code point is the character, not its leading surrogate")
    void first() {
        assertThat(CodePoints.first(EMOJI)).isEqualTo(0x1F600);
        assertThat(CodePoints.first("Ada")).isEqualTo('A');
    }
}

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

import com.darkcollective.relix.function.Blocking;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.symbol.FunctionProperty;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static com.darkcollective.relix.function.builtin.BuiltinCalls.CONTEXT;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.call;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.function;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.n;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.number;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.s;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DisplayName("The similarity built-ins")
final class SimilarityFunctionsTest {

    private static final Value NULL = NullValue.INSTANCE;

    /** A grinning face: one code point, two UTF-16 code units. */
    private static final String GRIN = "😀";

    private static double score(String function, String a, String b) {
        return number(call(function, s(a), s(b))).doubleValue();
    }

    @Nested
    @DisplayName("Levenshtein")
    final class LevenshteinDistance {

        @ParameterizedTest(name = "\"{0}\" to \"{1}\" is {2}")
        @CsvSource(value = {
                "kitten | sitting | 3",
                "''     | ''      | 0",
                "''     | abc     | 3",
                "abc    | ''      | 3",
                "abc    | abc     | 0",
                "abc    | abd     | 1",
                "abc    | ab      | 1",
                "ab     | abc     | 1",
                "flaw   | lawn    | 2",
                "Smith  | smith   | 1"}, delimiter = '|')
        @DisplayName("counts the fewest single-character edits")
        void distances(String a, String b, int expected) {
            assertThat(number(call("Levenshtein", s(a), s(b))))
                    .isEqualByComparingTo(BigDecimal.valueOf(expected));
        }

        @Test
        @DisplayName("an emoji is one character, so one edit")
        void codePoints() {
            assertThat(number(call("Levenshtein", s(GRIN), s(""))))
                    .isEqualByComparingTo("1");
            assertThat(number(call("Levenshtein", s("a" + GRIN + "b"), s("ab"))))
                    .isEqualByComparingTo("1");
        }
    }

    @Nested
    @DisplayName("EditSimilarity")
    final class EditSimilarity {

        @Test
        @DisplayName("is one minus the distance over the longer length")
        void normalised() {
            assertThat(score("EditSimilarity", "abcd", "abxd")).isEqualTo(0.75);
            assertThat(score("EditSimilarity", "kitten", "sitting"))
                    .isCloseTo(1 - 3.0 / 7, within(1e-12));
        }

        @Test
        @DisplayName("identical strings score 1, the empty string included")
        void identical() {
            assertThat(score("EditSimilarity", "same", "same")).isEqualTo(1.0);
            assertThat(score("EditSimilarity", "", "")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("nothing in common scores 0")
        void disjoint() {
            assertThat(score("EditSimilarity", "abc", "xyz")).isEqualTo(0.0);
            assertThat(score("EditSimilarity", "", "abc")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("measures in code points")
        void codePoints() {
            assertThat(score("EditSimilarity", "a" + GRIN, "ab")).isEqualTo(0.5);
        }
    }

    @Nested
    @DisplayName("JaroWinkler")
    final class JaroWinkler {

        @ParameterizedTest(name = "\"{0}\" and \"{1}\" score {2}")
        @CsvSource(value = {
                "MARTHA | MARHTA   | 0.961111",
                "DWAYNE | DUANE    | 0.840000",
                "DIXON  | DICKSONX | 0.813333",
                "CRATE  | TRACE    | 0.733333"}, delimiter = '|')
        @DisplayName("agrees with the published examples")
        void published(String a, String b, double expected) {
            assertThat(score("JaroWinkler", a, b)).isCloseTo(expected, within(1e-6));
        }

        @Test
        @DisplayName("does not reward a shared prefix below a Jaro score of 0.7")
        void threshold() {
            // Jaro gives 2/3 here; the prefix bonus would lift it to 0.7.
            assertThat(score("JaroWinkler", "ab", "ac")).isCloseTo(2.0 / 3, within(1e-12));
        }

        @Test
        @DisplayName("rewards at most four characters of prefix")
        void prefixCap() {
            double four = score("JaroWinkler", "abcdx", "abcdy");
            double jaro = (4.0 / 5 + 4.0 / 5 + 1) / 3;
            assertThat(four).isCloseTo(jaro + 4 * 0.1 * (1 - jaro), within(1e-12));
            double longer = score("JaroWinkler", "abcdefx", "abcdefy");
            double jaroLonger = (6.0 / 7 + 6.0 / 7 + 1) / 3;
            assertThat(longer).isCloseTo(jaroLonger + 4 * 0.1 * (1 - jaroLonger), within(1e-12));
        }

        @Test
        @DisplayName("is 1 for identical strings and 0 for nothing in common")
        void bounds() {
            assertThat(score("JaroWinkler", "smith", "smith")).isEqualTo(1.0);
            assertThat(score("JaroWinkler", "", "")).isEqualTo(1.0);
            assertThat(score("JaroWinkler", "abc", "xyz")).isEqualTo(0.0);
            assertThat(score("JaroWinkler", "", "abc")).isEqualTo(0.0);
            assertThat(score("JaroWinkler", "abc", "")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("is symmetric")
        void symmetric() {
            assertThat(score("JaroWinkler", "DIXON", "DICKSONX"))
                    .isEqualTo(score("JaroWinkler", "DICKSONX", "DIXON"));
        }

        @Test
        @DisplayName("matches an emoji as one character")
        void codePoints() {
            assertThat(score("JaroWinkler", GRIN, GRIN)).isEqualTo(1.0);
            assertThat(score("JaroWinkler", GRIN + "a", GRIN + "b"))
                    .isCloseTo(score("JaroWinkler", "xa", "xb"), within(1e-12));
        }
    }

    @Nested
    @DisplayName("Trigram")
    final class Trigram {

        @Test
        @DisplayName("is the Jaccard similarity of the padded trigram sets")
        void jaccard() {
            // "  a", " a " against "  a", " ab", "ab ": one shared of four.
            assertThat(score("Trigram", "a", "ab")).isEqualTo(0.25);
            // "  a", " ab", "abc", "bc " against "  a", " ab", "abd", "bd ".
            assertThat(score("Trigram", "abc", "abd")).isCloseTo(2.0 / 6, within(1e-12));
        }

        @Test
        @DisplayName("counts a repeated trigram once")
        void sets() {
            assertThat(SimilarityFunctions.trigrams("aaaa".codePoints().toArray()))
                    .containsExactlyInAnyOrder("  a", " aa", "aaa", "aa ");
        }

        @Test
        @DisplayName("is 1 for identical strings and 0 for nothing in common")
        void bounds() {
            assertThat(score("Trigram", "same", "same")).isEqualTo(1.0);
            assertThat(score("Trigram", "", "")).isEqualTo(1.0);
            assertThat(score("Trigram", "", "abc")).isEqualTo(0.0);
            assertThat(score("Trigram", "abc", "xyz")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("does not fold case")
        void caseSensitive() {
            assertThat(score("Trigram", "ABC", "abc")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("builds trigrams of code points")
        void codePoints() {
            assertThat(SimilarityFunctions.trigrams(GRIN.codePoints().toArray()))
                    .containsExactlyInAnyOrder("  " + GRIN, " " + GRIN + " ");
        }
    }

    @Nested
    @DisplayName("Soundex")
    final class Soundex {

        @ParameterizedTest(name = "{0} is {1}")
        @CsvSource({
                "Robert, R163", "Rupert, R163", "Rubin, R150", "Ashcraft, A261",
                "Ashcroft, A261", "Tymczak, T522", "Pfister, P236", "Honeyman, H555",
                "Lee, L000", "robert, R163", "O'Brien, O165", "Jackson, J250", "Bowman, B550"})
        @DisplayName("agrees with American Soundex")
        void american(String name, String expected) {
            assertThat(text(call("Soundex", s(name)))).isEqualTo(expected);
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"", "123", "  ", GRIN})
        @DisplayName("a string with no letter A–Z has the empty key")
        void noLetters(String input) {
            assertThat(text(call("Soundex", s(input)))).isEmpty();
        }

        @Test
        @DisplayName("skips characters that are not A–Z")
        void skipsOthers() {
            assertThat(text(call("Soundex", s("Müller")))).isEqualTo("M460");
        }
    }

    @Nested
    @DisplayName("Every similarity function")
    final class Common {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"Levenshtein", "EditSimilarity", "JaroWinkler", "Trigram"})
        @DisplayName("answers NULL when either argument is NULL")
        void nullPair(String name) {
            assertThat(call(name, NULL, s("a"))).isEqualTo(NULL);
            assertThat(call(name, s("a"), NULL)).isEqualTo(NULL);
            assertThat(call(name, NULL, NULL)).isEqualTo(NULL);
        }

        @Test
        @DisplayName("Soundex of NULL is NULL")
        void nullKey() {
            assertThat(call("Soundex", NULL)).isEqualTo(NULL);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"Levenshtein", "EditSimilarity", "JaroWinkler", "Trigram"})
        @DisplayName("rejects an argument that is not a string, naming itself")
        void wrongType(String name) {
            assertThatThrownBy(() -> call(name, n("1"), s("a")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(name);
            assertThatThrownBy(() -> call(name, s("a"), n("1")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(name);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"Levenshtein", "EditSimilarity", "JaroWinkler", "Trigram", "Soundex"})
        @DisplayName("is pure and deterministic")
        void properties(String name) {
            assertThat(function(name).signature().properties())
                    .contains(FunctionProperty.PURE, FunctionProperty.DETERMINISTIC);
            assertThat(function(name).signature().category()).isEqualTo("similarity");
        }
    }

    @Nested
    @DisplayName("Blocking")
    final class Blockings {

        /** Short strings over a small alphabet, so that near-misses are common. */
        private static List<String> corpus() {
            Random random = new Random(1153);
            List<String> values = new ArrayList<>(List.of("", "a", "ab", "ba", "abc",
                    "Smith", "Smyth", "Smithe", "Jon", "John", GRIN, GRIN + "a", "a" + GRIN + "b"));
            String alphabet = "abcde";
            for (int i = 0; i < 150; i++) {
                int length = random.nextInt(12);
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < length; j++) {
                    sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
                }
                values.add(sb.toString());
                // A near copy: one character changed, dropped or added.
                if (length > 0) {
                    StringBuilder near = new StringBuilder(sb);
                    switch (random.nextInt(3)) {
                        case 0 -> near.setCharAt(random.nextInt(length), 'e');
                        case 1 -> near.deleteCharAt(random.nextInt(length));
                        default -> near.insert(random.nextInt(length + 1), 'a');
                    }
                    values.add(near.toString());
                }
            }
            return values;
        }

        private static boolean satisfies(BigDecimal score, Blocking.Bound bound, BigDecimal t) {
            int c = score.compareTo(t);
            return switch (bound) {
                case AT_LEAST -> c >= 0;
                case ABOVE -> c > 0;
                case AT_MOST -> c <= 0;
                case BELOW -> c < 0;
            };
        }

        private static boolean meet(Collection<?> a, Collection<?> b) {
            Set<Object> keys = new HashSet<>(a);
            return b.stream().anyMatch(keys::contains);
        }

        /** Every passing pair shares a key, whichever side probes; returns pairs pruned. */
        private static long check(String name, Blocking.Bound bound, String threshold) {
            BigDecimal t = new BigDecimal(threshold);
            ScalarFunction fn = function(name);
            Blocking blocking = fn.blocking(bound, t).orElseThrow(
                    () -> new AssertionError(name + " offers no blocking for " + bound + " " + t));
            List<String> values = corpus();
            long pruned = 0;
            for (String a : values) {
                for (String b : values) {
                    BigDecimal score = number(call(name, s(a), s(b)));
                    boolean candidate = meet(blocking.probeKeys(s(a)), blocking.indexKeys(s(b)));
                    if (satisfies(score, bound, t)) {
                        assertThat(candidate)
                                .as("%s(\"%s\", \"%s\") = %s passes %s %s, so they must share a key",
                                        name, a, b, score, bound, t)
                                .isTrue();
                        assertThat(meet(blocking.probeKeys(s(b)), blocking.indexKeys(s(a))))
                                .as("the same pair, probed from the other side")
                                .isTrue();
                    } else if (!candidate) {
                        pruned++;
                    }
                }
            }
            return pruned;
        }

        @ParameterizedTest(name = "{0} {1} {2}")
        @CsvSource({
                "EditSimilarity, AT_LEAST, 0.5", "EditSimilarity, AT_LEAST, 0.8",
                "EditSimilarity, ABOVE, 0.75", "EditSimilarity, AT_LEAST, 1",
                "JaroWinkler, AT_LEAST, 0.85", "JaroWinkler, AT_LEAST, 0.95",
                "JaroWinkler, ABOVE, 0.9",
                "Trigram, AT_LEAST, 0.3", "Trigram, AT_LEAST, 0.5", "Trigram, ABOVE, 0.6",
                "Trigram, AT_LEAST, 1",
                "Levenshtein, AT_MOST, 0", "Levenshtein, AT_MOST, 1", "Levenshtein, AT_MOST, 2",
                "Levenshtein, BELOW, 2", "Levenshtein, BELOW, 1.5", "Levenshtein, AT_MOST, 2.5"})
        @DisplayName("never loses a pair that passes, and prunes some that do not")
        void neverLosesAPair(String name, Blocking.Bound bound, String threshold) {
            assertThat(check(name, bound, threshold))
                    .as("a blocking that prunes nothing is not worth offering")
                    .isPositive();
        }

        @ParameterizedTest(name = "{0} {1} {2}")
        @CsvSource({
                "EditSimilarity, AT_MOST, 0.8", "EditSimilarity, BELOW, 0.8",
                "EditSimilarity, AT_LEAST, 0.05", "EditSimilarity, AT_LEAST, 0",
                "JaroWinkler, AT_LEAST, 0.6", "JaroWinkler, AT_LEAST, 0.8",
                "JaroWinkler, AT_MOST, 0.9",
                "Trigram, AT_LEAST, 0", "Trigram, BELOW, 0.5", "Trigram, AT_MOST, 0.5",
                "Levenshtein, AT_LEAST, 1", "Levenshtein, ABOVE, 1", "Levenshtein, AT_MOST, 101"})
        @DisplayName("is not offered where it would keep nearly every pair, or cannot bound")
        void notOffered(String name, Blocking.Bound bound, String threshold) {
            assertThat(function(name).blocking(bound, new BigDecimal(threshold))).isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"EditSimilarity, AT_LEAST, 0.9", "JaroWinkler, AT_LEAST, 0.95",
                "Trigram, AT_LEAST, 0.5", "Levenshtein, AT_MOST, 1"})
        @DisplayName("gives NULL no keys, since a call with NULL is never kept")
        void nullHasNoKeys(String name, Blocking.Bound bound, String threshold) {
            Blocking blocking = function(name).blocking(bound, new BigDecimal(threshold))
                    .orElseThrow();
            assertThat(blocking.indexKeys(NULL)).isEmpty();
            assertThat(blocking.probeKeys(NULL)).isEmpty();
        }

        @Test
        @DisplayName("a negative edit distance admits no pair at all")
        void negativeDistance() {
            Blocking blocking = function("Levenshtein")
                    .blocking(Blocking.Bound.AT_MOST, new BigDecimal("-1")).orElseThrow();
            assertThat(blocking.probeKeys(s("abc"))).isEmpty();
        }

        @Test
        @DisplayName("a threshold above 1 admits no trigram pair")
        void unreachable() {
            Blocking blocking = function("Trigram")
                    .blocking(Blocking.Bound.AT_LEAST, new BigDecimal("1.5")).orElseThrow();
            assertThat(blocking.probeKeys(s("abc"))).isEmpty();
        }

        @Test
        @DisplayName("refuses a value that is not a string, naming the function")
        void refusesNonStrings() {
            for (String name : List.of("EditSimilarity", "Trigram", "Levenshtein")) {
                Blocking.Bound bound = name.equals("Levenshtein")
                        ? Blocking.Bound.AT_MOST : Blocking.Bound.AT_LEAST;
                Blocking blocking = function(name).blocking(bound, new BigDecimal("0.9"))
                        .or(() -> function(name).blocking(bound, BigDecimal.ONE)).orElseThrow();
                assertThatThrownBy(() -> blocking.indexKeys(n("1")))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(name);
                assertThatThrownBy(() -> blocking.probeKeys(n("1")))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(name);
            }
        }

        @Test
        @DisplayName("the functions without a bound offer none")
        void soundexOffersNone() {
            assertThat(function("Soundex").blocking(Blocking.Bound.AT_LEAST, BigDecimal.ONE))
                    .isEqualTo(Optional.empty());
            assertThat(function("Len").blocking(Blocking.Bound.AT_LEAST, BigDecimal.ONE))
                    .isEmpty();
        }

        @Test
        @DisplayName("a function offering a blocking still computes as declared")
        void stillComputes() {
            assertThat(number(call(CONTEXT, "EditSimilarity", s("ab"), s("ab"))))
                    .isEqualByComparingTo("1");
        }
    }
}

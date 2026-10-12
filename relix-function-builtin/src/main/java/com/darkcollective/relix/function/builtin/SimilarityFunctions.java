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

import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.Value;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.darkcollective.relix.function.builtin.Arguments.string;
import static com.darkcollective.relix.function.builtin.Category.PURE_DETERMINISTIC;
import static com.darkcollective.relix.function.builtin.Category.p;
import static com.darkcollective.relix.symbol.ScalarType.NUMBER;
import static com.darkcollective.relix.symbol.ScalarType.STRING;

/**
 * The string-similarity built-ins: how far apart two strings are, how alike on a scale
 * of 0 to 1, and a phonetic key that names sounding alike.
 *
 * <p>They exist for record linkage — finding rows that probably describe the same thing
 * although their text differs — and they are ordinary scalars: a similarity join is a
 * theta join whose condition calls one of them. A score is evidence that two strings
 * resemble each other, never proof that they name the same entity.
 *
 * <h2>Counted in code points, and case is the caller's</h2>
 * Every function here reads a string as a sequence of <b>code points</b>, as {@code Len}
 * and {@code Mid} do, so an emoji is one character and one edit. None folds case:
 * {@code "Smith"} and {@code "smith"} differ by one edit, and a caller who wants them
 * alike writes {@code LCase} on both sides. {@code Soundex} is the exception that proves
 * the rule — the algorithm is defined over letters regardless of case.
 *
 * <h2>Which of these a backend is offered</h2>
 * None. PostgreSQL has {@code levenshtein()} and {@code similarity()}, but only in the
 * {@code fuzzystrmatch} and {@code pg_trgm} extensions, and {@code pg_trgm} folds case
 * and splits words where {@code Trigram} does neither. MySQL's {@code SOUNDEX} does not
 * stop at four characters. Declining is always correct: the rows come back and the engine
 * scores them.
 */
final class SimilarityFunctions {

    private static final Category SIMILARITY = Category.of("similarity");

    /** Jaro–Winkler's prefix scale: how much each shared leading character is worth. */
    private static final double WINKLER_SCALE = 0.1;

    /** The longest shared prefix Jaro–Winkler rewards. */
    private static final int WINKLER_PREFIX = 4;

    /** The Jaro score below which Winkler's prefix bonus is not applied. */
    private static final double WINKLER_THRESHOLD = 0.7;

    private SimilarityFunctions() {
    }

    static List<ScalarFunction> all() {
        return List.of(
                SIMILARITY.fn("Levenshtein", NUMBER, PURE_DETERMINISTIC,
                        List.of(p("a", STRING), p("b", STRING)),
                        args -> pair(args, "Levenshtein",
                                (a, b) -> number(levenshtein(a, b)))),

                SIMILARITY.fn("EditSimilarity", NUMBER, PURE_DETERMINISTIC,
                        List.of(p("a", STRING), p("b", STRING)),
                        args -> pair(args, "EditSimilarity",
                                (a, b) -> number(editSimilarity(a, b)))),

                SIMILARITY.fn("JaroWinkler", NUMBER, PURE_DETERMINISTIC,
                        List.of(p("a", STRING), p("b", STRING)),
                        args -> pair(args, "JaroWinkler",
                                (a, b) -> number(jaroWinkler(a, b)))),

                SIMILARITY.fn("Trigram", NUMBER, PURE_DETERMINISTIC,
                        List.of(p("a", STRING), p("b", STRING)),
                        args -> pair(args, "Trigram",
                                (a, b) -> number(trigram(a, b)))),

                SIMILARITY.fn("Soundex", STRING, PURE_DETERMINISTIC, List.of(p("s", STRING)),
                        args -> args.get(0).isNull() ? NullValue.INSTANCE
                                : new StringValue(soundex(string(args.get(0), "Soundex")))));
    }

    @FunctionalInterface
    private interface Scorer {
        Value score(int[] a, int[] b);
    }

    /** Reads two string arguments as code points, or answers NULL if either is NULL. */
    private static Value pair(List<Value> args, String function, Scorer scorer) {
        if (args.get(0).isNull() || args.get(1).isNull()) {
            return NullValue.INSTANCE;
        }
        return scorer.score(string(args.get(0), function).codePoints().toArray(),
                string(args.get(1), function).codePoints().toArray());
    }

    /** The fewest single-character insertions, deletions and substitutions from a to b. */
    static int levenshtein(int[] a, int[] b) {
        if (a.length < b.length) {
            int[] swap = a;
            a = b;
            b = swap;
        }
        int[] previous = new int[b.length + 1];
        int[] current = new int[b.length + 1];
        for (int j = 0; j <= b.length; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length; i++) {
            current[0] = i;
            for (int j = 1; j <= b.length; j++) {
                int substitution = previous[j - 1] + (a[i - 1] == b[j - 1] ? 0 : 1);
                current[j] = Math.min(substitution,
                        Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length];
    }

    /** One minus the edit distance over the longer length; two empty strings are identical. */
    static double editSimilarity(int[] a, int[] b) {
        int longer = Math.max(a.length, b.length);
        if (longer == 0) {
            return 1.0;
        }
        return 1.0 - (double) levenshtein(a, b) / longer;
    }

    /**
     * Jaro similarity, raised by Winkler's bonus for a shared prefix of up to four
     * characters when the Jaro score is at least 0.7.
     */
    static double jaroWinkler(int[] a, int[] b) {
        double jaro = jaro(a, b);
        if (jaro < WINKLER_THRESHOLD) {
            return jaro;
        }
        int prefix = 0;
        int limit = Math.min(WINKLER_PREFIX, Math.min(a.length, b.length));
        while (prefix < limit && a[prefix] == b[prefix]) {
            prefix++;
        }
        return jaro + prefix * WINKLER_SCALE * (1.0 - jaro);
    }

    private static double jaro(int[] a, int[] b) {
        if (a.length == 0 && b.length == 0) {
            return 1.0;
        }
        if (a.length == 0 || b.length == 0) {
            return 0.0;
        }
        int window = Math.max(0, Math.max(a.length, b.length) / 2 - 1);
        boolean[] aMatched = new boolean[a.length];
        boolean[] bMatched = new boolean[b.length];
        int matches = 0;
        for (int i = 0; i < a.length; i++) {
            int from = Math.max(0, i - window);
            int to = Math.min(b.length - 1, i + window);
            for (int j = from; j <= to; j++) {
                if (!bMatched[j] && a[i] == b[j]) {
                    aMatched[i] = true;
                    bMatched[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0.0;
        }
        int outOfOrder = 0;
        int k = 0;
        for (int i = 0; i < a.length; i++) {
            if (aMatched[i]) {
                while (!bMatched[k]) {
                    k++;
                }
                if (a[i] != b[k]) {
                    outOfOrder++;
                }
                k++;
            }
        }
        double m = matches;
        return (m / a.length + m / b.length + (m - outOfOrder / 2.0) / m) / 3.0;
    }

    /**
     * Jaccard similarity of the two strings' sets of trigrams, each string padded with
     * two spaces before and one after so that its first and last characters weigh as
     * much as its middle — the padding {@code pg_trgm} gives each word.
     */
    static double trigram(int[] a, int[] b) {
        Set<String> left = trigrams(a);
        Set<String> right = trigrams(b);
        if (left.isEmpty() && right.isEmpty()) {
            return 1.0;
        }
        int shared = 0;
        for (String gram : left) {
            if (right.contains(gram)) {
                shared++;
            }
        }
        return (double) shared / (left.size() + right.size() - shared);
    }

    /** The padded trigrams of a string; none for the empty string. */
    static Set<String> trigrams(int[] s) {
        Set<String> grams = new HashSet<>();
        if (s.length == 0) {
            return grams;
        }
        int[] padded = new int[s.length + 3];
        padded[0] = ' ';
        padded[1] = ' ';
        System.arraycopy(s, 0, padded, 2, s.length);
        padded[padded.length - 1] = ' ';
        for (int i = 0; i + 3 <= padded.length; i++) {
            grams.add(new String(padded, i, 3));
        }
        return grams;
    }

    /**
     * American Soundex: the first letter, then the digits of the consonants that follow,
     * adjacent repeats collapsed, padded or cut to four characters. Only the letters A–Z
     * take part, in either case; a string with none of them has the empty key.
     */
    static String soundex(String s) {
        StringBuilder key = new StringBuilder(4);
        char previous = 0;
        for (int i = 0; i < s.length() && key.length() < 4; i++) {
            char c = Character.toUpperCase(s.charAt(i));
            if (c < 'A' || c > 'Z') {
                continue;
            }
            char code = soundexDigit(c);
            if (key.isEmpty()) {
                key.append(c);
                previous = code;
            } else if (c == 'H' || c == 'W') {
                // Neither coded nor a separator: the consonants either side still merge.
                continue;
            } else if (code == '0') {
                previous = code;
            } else if (code != previous) {
                key.append(code);
                previous = code;
            }
        }
        if (key.isEmpty()) {
            return "";
        }
        while (key.length() < 4) {
            key.append('0');
        }
        return key.toString();
    }

    private static char soundexDigit(char c) {
        return switch (c) {
            case 'B', 'F', 'P', 'V' -> '1';
            case 'C', 'G', 'J', 'K', 'Q', 'S', 'X', 'Z' -> '2';
            case 'D', 'T' -> '3';
            case 'L' -> '4';
            case 'M', 'N' -> '5';
            case 'R' -> '6';
            default -> '0';
        };
    }

    private static NumberValue number(double n) {
        return new NumberValue(BigDecimal.valueOf(n));
    }

    private static NumberValue number(long n) {
        return new NumberValue(BigDecimal.valueOf(n));
    }
}

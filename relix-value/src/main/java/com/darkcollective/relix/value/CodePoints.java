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
package com.darkcollective.relix.value;

import java.util.Objects;

/**
 * Strings as sequences of <b>code points</b> — the unit relix counts, slices and orders
 * a string by.
 *
 * <h2>Why not just use {@code String}'s own methods</h2>
 * Java's are written in {@code char}, which is a UTF-16 <em>code unit</em>: a character
 * outside the basic multilingual plane is two of them. So {@code "a😀b".length()} is 4,
 * {@code substring} can cut an emoji in half, and {@code compareTo} sorts every
 * supplementary character <em>before</em> {@code U+E000..U+FFFF} because it compares the
 * leading surrogate {@code 0xD83D} against a real character's code.
 *
 * <p>None of that is a decision relix made. It is an encoding detail of the JVM reaching
 * the language surface — nobody asking how long a name is means "how many UTF-16 code
 * units" — and it is the one thing that separated relix's answer from every SQL
 * database's, all of which count characters and order by code point. Counting the same
 * way is both the more defensible answer and what lets those functions be handed to a
 * backend at all.
 *
 * <p>For a string of only basic-plane characters — which is most text, and all of ASCII —
 * every method here agrees exactly with its {@code String} counterpart. The difference is
 * confined to the strings the {@code String} version is wrong about.
 */
public final class CodePoints {

    private CodePoints() {
    }

    /**
     * The number of code points in {@code text} — its length as a user would count it.
     *
     * @param text the string; must not be null
     * @return the count
     */
    public static int length(String text) {
        Objects.requireNonNull(text, "text");
        return text.codePointCount(0, text.length());
    }

    /**
     * The substring from {@code start} code points in, to the end.
     *
     * @param text  the string; must not be null
     * @param start the number of code points to skip; clamped to the string's length
     * @return the remainder
     */
    public static String substring(String text, int start) {
        return substring(text, start, Integer.MAX_VALUE);
    }

    /**
     * The substring of at most {@code count} code points, starting {@code start} code
     * points in. Both bounds are clamped, so this never raises for an over-long request —
     * the callers ask for "up to n" and mean it.
     *
     * @param text  the string; must not be null
     * @param start the number of code points to skip; clamped to the string's length
     * @param count the maximum number of code points to take; clamped to what remains
     * @return the slice
     */
    public static String substring(String text, int start, int count) {
        Objects.requireNonNull(text, "text");
        int total = length(text);
        int from = Math.min(Math.max(start, 0), total);
        int to = count >= total - from ? total : from + count;
        return text.substring(text.offsetByCodePoints(0, from), text.offsetByCodePoints(0, to));
    }

    /** The last {@code count} code points, or all of them when there are fewer. */
    public static String last(String text, int count) {
        Objects.requireNonNull(text, "text");
        return substring(text, Math.max(0, length(text) - count));
    }

    /**
     * The position of {@code sought} in {@code text}, counted in code points from
     * {@code from}, or {@code -1} when it does not occur.
     *
     * @param text   the string to search; must not be null
     * @param sought the string to find; must not be null
     * @param from   the code-point position to start at
     * @return the code-point index of the first occurrence, or -1
     */
    public static int indexOf(String text, String sought, int from) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(sought, "sought");
        int total = length(text);
        int start = Math.min(Math.max(from, 0), total);
        int found = text.indexOf(sought, text.offsetByCodePoints(0, start));
        return found < 0 ? -1 : text.codePointCount(0, found);
    }

    /**
     * The first code point of {@code text}.
     *
     * @param text a non-empty string; must not be null
     * @return the code point
     */
    public static int first(String text) {
        Objects.requireNonNull(text, "text");
        return text.codePointAt(0);
    }

    /**
     * Compares two strings by code point — the order every SQL binary collation uses, and
     * the order UTF-8 bytes already sort in.
     *
     * <p>{@code String.compareTo} does not: it compares UTF-16 code units, so a
     * supplementary character sorts before {@code U+E000..U+FFFF} rather than after it.
     * That is the only case in which the two disagree.
     *
     * @param a the first string; must not be null
     * @param b the second; must not be null
     * @return negative, zero or positive as {@code a} sorts before, with, or after {@code b}
     */
    public static int compare(String a, String b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        // One has run out: the shorter one sorts first, and equal lengths sort equal.
        return Integer.compare(a.length() - i, b.length() - j);
    }
}

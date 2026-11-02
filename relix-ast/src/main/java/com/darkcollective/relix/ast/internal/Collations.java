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

import com.darkcollective.relix.ast.CollateOperand;
import com.darkcollective.relix.ast.Operand;

import java.text.Collator;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * What each collation a {@link CollateOperand} names means: the one place analysis
 * checks a name against and execution compares under.
 *
 * <p>A collation is two things, kept consistent with each other: a {@link Collation#key key}, a
 * string two values share exactly when the collation calls them equal, which is what a
 * grouping or a hash join buckets by; and an {@link Collation#compare order}. Under
 * {@code BINARY} the key is the string itself. Under {@code NOCASE} it is the string
 * lower-cased, in the root locale, so the engine's answer does not move with the
 * machine's language. Under a locale it is the {@link java.text.CollationKey}'s bytes in
 * hex, whose code-point order is the locale's order.
 */
public final class Collations {

    private Collations() {
    }

    /** A resolved collation: an equality key and an order consistent with it. */
    public sealed interface Collation permits Binary, NoCase, Linguistic {

        /** {@return the string two values share exactly when this collation calls them equal} */
        String key(String value);

        /** {@return the order of two strings under this collation, ties included} */
        int compare(String a, String b);
    }

    private record Binary() implements Collation {
        @Override public String key(String value) {
            return value;
        }
        @Override public int compare(String a, String b) {
            return Arrays.compare(a.codePoints().toArray(), b.codePoints().toArray());
        }
    }

    private record NoCase() implements Collation {
        @Override public String key(String value) {
            return value.toLowerCase(Locale.ROOT);
        }
        @Override public int compare(String a, String b) {
            return new Binary().compare(key(a), key(b));
        }
    }

    /** A locale's dictionary order. A {@link Collator} is not safe to share, so each call takes its own. */
    private record Linguistic(Locale locale) implements Collation {
        @Override public String key(String value) {
            return HexFormat.of().formatHex(Collator.getInstance(locale).getCollationKey(value).toByteArray());
        }
        @Override public int compare(String a, String b) {
            return Collator.getInstance(locale).compare(a, b);
        }
    }

    /**
     * The collation {@code name} names, or empty when it names none: {@code BINARY},
     * {@code NOCASE}, or a locale the platform collates, written as a language tag
     * ({@code 'en-US'}, {@code 'de'}) or with an underscore ({@code 'en_US'}).
     *
     * @param name the name as written; must not be null
     * @return the collation, if there is one of that name
     */
    public static Optional<Collation> resolve(String name) {
        String upper = name.strip().toUpperCase(Locale.ROOT);
        if (upper.equals(CollateOperand.BINARY)) {
            return Optional.of(new Binary());
        }
        if (upper.equals(CollateOperand.NOCASE)) {
            return Optional.of(new NoCase());
        }
        Locale locale = Locale.forLanguageTag(name.strip().replace('_', '-'));
        if (locale.getLanguage().isEmpty()) {
            return Optional.empty();
        }
        boolean known = Arrays.stream(Collator.getAvailableLocales())
                .anyMatch(l -> l.getLanguage().equals(locale.getLanguage()));
        return known ? Optional.of(new Linguistic(locale)) : Optional.empty();
    }

    /**
     * The collation {@code operand} is written under, or empty when it is not a
     * {@link CollateOperand} or names none.
     *
     * @param operand an operand; must not be null
     * @return its collation, if it states one
     */
    public static Optional<Collation> of(Operand operand) {
        return operand instanceof CollateOperand c ? resolve(c.collation()) : Optional.empty();
    }

    /**
     * The collation a comparison of {@code left} with {@code right} is made under: the
     * one either side states, the left's when both do. Analysis refuses two sides that
     * state different ones.
     *
     * @param left  the left side; must not be null
     * @param right the right side; must not be null
     * @return the comparison's collation, or empty for the default, code-point order
     */
    public static Optional<Collation> of(Operand left, Operand right) {
        Optional<Collation> l = of(left);
        return l.isPresent() ? l : of(right);
    }

    /**
     * {@code operand} without the collation it is written under: the string it collates.
     *
     * @param operand an operand; must not be null
     * @return the operand inside a {@link CollateOperand}, or {@code operand} itself
     */
    public static Operand strip(Operand operand) {
        return operand instanceof CollateOperand c ? c.operand() : operand;
    }
}

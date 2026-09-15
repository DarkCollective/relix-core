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
package com.darkcollective.relix.function;

import java.util.Locale;
import java.util.Objects;

/**
 * The backend a call is being rendered for, named in a way the SPI can carry without
 * knowing anything about query planning.
 *
 * <p>A target is a family plus a variant: {@code ("sql", "postgres")},
 * {@code ("sql", "mysql")}, {@code ("mongo", "")}. The family says what shape of text
 * is wanted — SQL expression syntax, a MongoDB aggregation expression — and the variant
 * distinguishes dialects within it. A spelling that is the same everywhere in a family
 * can ignore the variant; one that is not switches on it.
 *
 * <p>Both parts are matched lower-cased, and an absent variant is the empty string
 * rather than {@code null}.
 *
 * @param family  the backend family, lower-cased and never blank
 * @param variant the dialect within that family, lower-cased; empty when the family has
 *                only one
 */
public record PushdownTarget(String family, String variant) {

    /** The family of backends that speak SQL expression syntax. */
    public static final String SQL = "sql";

    /** The family of backends that speak MongoDB aggregation expressions. */
    public static final String MONGO = "mongo";

    /**
     * @throws IllegalArgumentException if {@code family} is blank
     */
    public PushdownTarget {
        Objects.requireNonNull(family, "family");
        if (family.isBlank()) {
            throw new IllegalArgumentException("Pushdown family must not be blank");
        }
        family = family.toLowerCase(Locale.ROOT);
        variant = variant == null ? "" : variant.toLowerCase(Locale.ROOT);
    }

    /**
     * A SQL target for the named dialect.
     *
     * @param dialect the dialect name, e.g. {@code "postgres"}; may be empty for
     *                dialect-neutral SQL
     * @return the target
     */
    public static PushdownTarget sql(String dialect) {
        return new PushdownTarget(SQL, dialect);
    }

    /**
     * The MongoDB aggregation-expression target.
     *
     * @return the target
     */
    public static PushdownTarget mongo() {
        return new PushdownTarget(MONGO, "");
    }

    /**
     * @param candidate a family name, in any case
     * @return {@code true} when this target belongs to that family
     */
    public boolean isFamily(String candidate) {
        return family.equals(Objects.requireNonNull(candidate, "candidate")
                .toLowerCase(Locale.ROOT));
    }

    /**
     * @param candidate a variant name, in any case
     * @return {@code true} when this target's variant is {@code candidate}
     */
    public boolean isVariant(String candidate) {
        return variant.equals(Objects.requireNonNull(candidate, "candidate")
                .toLowerCase(Locale.ROOT));
    }
}

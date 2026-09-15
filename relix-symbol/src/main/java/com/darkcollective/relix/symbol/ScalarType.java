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
package com.darkcollective.relix.symbol;

import java.util.Locale;

/**
 * The scalar-value type lattice used for column types, function parameter types,
 * and function return types.
 *
 * <p>A {@code ScalarType} is the leaf case of the {@link Type} hierarchy; the
 * nested cases are {@link StructType} and {@link ArrayType}.
 *
 * <p>{@code ANY} is the top type — it is compatible with every other type and
 * serves as the default when no more specific type has been declared or inferred.
 * This allows symbols to be registered with minimal ceremony: a relation column
 * typed {@code ANY} will accept values of any kind at analysis time.
 *
 * <p>Type inference for inline (markdown) relations assigns {@code NUMBER} to a
 * column when every non-header cell in that column parses as a numeric literal;
 * otherwise {@code STRING} is used.
 */
public enum ScalarType implements Type {

    /**
     * Unconstrained — compatible with all other types.  Default when no type
     * annotation is present, and the <em>dynamic document</em> type for
     * schema-on-read sources (a value may be a scalar, struct, or array at runtime).
     */
    ANY,

    /** Textual data; corresponds to string literals enclosed in double quotes. */
    STRING,

    /** Numeric data; corresponds to integer or decimal numeric literals. */
    NUMBER,

    /** Boolean data; corresponds to the literals {@code true} and {@code false}. */
    BOOLEAN,

    /** A calendar day with no zone, e.g. {@code 2026-06-15} (backed by {@code java.time.LocalDate}). */
    DATE,

    /** A wall-clock time of day with no zone, e.g. {@code 13:40:00} (backed by {@code java.time.LocalTime}). */
    TIME,

    /**
     * An absolute point on the timeline, UTC-normalised (backed by
     * {@code java.time.Instant}).
     */
    TIMESTAMP,

    /** An exact elapsed span — seconds and nanoseconds (backed by {@code java.time.Duration}). */
    DURATION;

    /** {@return the lower-cased type name, e.g. {@code "number"}} */
    @Override
    public String display() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns the compact type code used in the IR report and in
     * machine-readable (JSON) output: {@code N}umber, {@code S}tring,
     * {@code B}oolean, {@code ?}=any, and the temporal codes {@code D}=date,
     * {@code T}=time, {@code TS}=timestamp, {@code DUR}=duration.
     *
     * @return the type code (one character for the scalar leaves, a short
     *         multi-letter token for the temporal types)
     */
    @Override
    public String code() {
        return switch (this) {
            case NUMBER    -> "N";
            case STRING    -> "S";
            case BOOLEAN   -> "B";
            case ANY       -> "?";
            case DATE      -> "D";
            case TIME      -> "T";
            case TIMESTAMP -> "TS";
            case DURATION  -> "DUR";
        };
    }

    /**
     * Returns the {@code ScalarType} constant whose name matches {@code name},
     * ignoring case.
     *
     * @param name the type name, e.g. {@code "string"}, {@code "NUMBER"}
     * @return the matching constant
     * @throws IllegalArgumentException if no constant matches
     */
    public static ScalarType fromString(String name) {
        return valueOf(name.toUpperCase(java.util.Locale.ROOT));
    }
}

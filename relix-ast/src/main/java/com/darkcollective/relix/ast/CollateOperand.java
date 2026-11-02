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

import com.darkcollective.relix.ast.visitor.OperandVisitor;
import java.util.Locale;
import java.util.Objects;

/**
 * A string compared under a named collation: {@code s COLLATE NOCASE}.
 *
 * <p>The operand's value is unchanged; the collation decides how it compares with
 * another string. It applies where a comparison is made: in a comparison, a membership
 * or pattern test, a join condition, a sort key and a grouping key.
 *
 * <p>{@code BINARY} compares by code point, which is what every string comparison does
 * without a collation. {@code NOCASE} compares strings equal when they are equal
 * lower-cased. Any other name is a locale, such as {@code 'en-US'}, which orders strings
 * as that language's dictionary does. Names are held as written, the two built-in ones
 * upper-cased; whether a name is a locale the engine has is decided at analysis.
 *
 * @param operand   the string being compared; must not be null
 * @param collation the collation's name: {@code BINARY}, {@code NOCASE} or a locale tag;
 *                  must not be blank
 * @param location  the source location of this operand; never null
 */
public record CollateOperand(Operand operand, String collation, SourceLocation location) implements Operand {

    /** Code-point order, the default. */
    public static final String BINARY = "BINARY";

    /** Equal when equal lower-cased. */
    public static final String NOCASE = "NOCASE";

    public CollateOperand {
        Objects.requireNonNull(operand, "operand");
        Objects.requireNonNull(collation, "collation");
        Objects.requireNonNull(location, "location");
        if (collation.isBlank()) {
            throw new IllegalArgumentException("collation must not be blank");
        }
        String upper = collation.strip().toUpperCase(Locale.ROOT);
        collation = upper.equals(BINARY) || upper.equals(NOCASE) ? upper : collation.strip();
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public CollateOperand(Operand operand, String collation) {
        this(operand, collation, SourceLocation.UNKNOWN);
    }

    /** {@return whether the collation is a locale rather than {@code BINARY} or {@code NOCASE}} */
    public boolean isLocale() {
        return !collation.equals(BINARY) && !collation.equals(NOCASE);
    }

    @Override
    public <R> R accept(OperandVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

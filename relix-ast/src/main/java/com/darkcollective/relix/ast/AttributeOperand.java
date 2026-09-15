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

import java.util.Objects;

/**
 * A column reference operand, optionally relation-qualified.
 *
 * <p>Examples: {@code id}, {@code Users.id}, {@code user_name}
 *
 * @param name     the attribute name, optionally prefixed with a relation name
 *                 separated by {@code .}; must not be blank
 * @param location the source location of this operand; never null
 */
public record AttributeOperand(String name, SourceLocation location) implements Operand {
    public AttributeOperand {
        requireNonBlank(name, "name");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public AttributeOperand(String name) {
        this(name, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(OperandVisitor<R> visitor) {
        return visitor.visit(this);
    }

    /**
     * Returns the column name with any relation qualifier stripped: for
     * {@code Users.id} this yields {@code id}, for a bare {@code id} it returns
     * {@code id} unchanged. The qualifier is everything up to and including the
     * last {@code .}.
     */
    public String unqualifiedName() {
        return AttributeNames.stripQualifier(name);
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

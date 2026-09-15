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
 * A numeric literal operand, stored as its original string representation.
 *
 * <p>Using a {@code String} field (rather than a primitive) preserves the
 * exact lexical form from the source expression — e.g., {@code "3.14"} or
 * {@code "9999999999999"} — without loss of precision or rounding.
 *
 * @param value    the numeric string; must not be blank
 * @param location the source location of this operand; never null
 */
public record NumberOperand(String value, SourceLocation location) implements Operand {
    public NumberOperand {
        requireNonBlank(value, "value");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public NumberOperand(String value) {
        this(value, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(OperandVisitor<R> visitor) {
        return visitor.visit(this);
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

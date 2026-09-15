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

import java.util.List;
import java.util.Objects;

/**
 * Represents a set literal like {1, 2, 3} or {"active", "pending"}
 * Used in element-of predicates for set membership testing.
 *
 * @param elements the set elements; must not be null
 * @param location the source location of this operand; never null
 */
public record SetLiteralOperand(List<Operand> elements, SourceLocation location) implements Operand {
    public SetLiteralOperand {
        Objects.requireNonNull(location, "location");
        elements = List.copyOf(elements);
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public SetLiteralOperand(List<Operand> elements) {
        this(elements, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(OperandVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

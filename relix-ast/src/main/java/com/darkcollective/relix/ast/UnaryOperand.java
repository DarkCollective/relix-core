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
 * Represents a unary operation applied to an operand.
 * Currently used for unary minus (negation).
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code -100}  — negation of a number literal</li>
 *   <li>{@code -price} — negation of an attribute</li>
 *   <li>{@code -(a + b)} — negation of an arithmetic expression</li>
 *   <li>{@code --x} — double negation</li>
 * </ul>
 *
 * @param operand  the operand to negate; must not be null
 * @param location the source location of this operand; never null
 */
public record UnaryOperand(Operand operand, SourceLocation location) implements Operand {
    public UnaryOperand {
        Objects.requireNonNull(operand, "operand");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public UnaryOperand(Operand operand) {
        this(operand, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(OperandVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

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
 * A binary arithmetic expression ({@code left op right}) used in projections
 * and predicates.
 *
 * <p>Examples: {@code price * 1.1}, {@code (a + b) / c}
 *
 * <p>Operator precedence follows standard arithmetic rules (* and / bind
 * tighter than + and −); the pretty-printer inserts parentheses as needed.
 *
 * @param left     the left operand; must not be null
 * @param operator the arithmetic operator; must not be null
 * @param right    the right operand; must not be null
 * @param location the source location of this operand; never null
 */
public record BinaryArithmeticExpression(Operand left, ArithmeticOperator operator, Operand right, SourceLocation location) implements Operand {
    public BinaryArithmeticExpression {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public BinaryArithmeticExpression(Operand left, ArithmeticOperator operator, Operand right) {
        this(left, operator, right, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(OperandVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

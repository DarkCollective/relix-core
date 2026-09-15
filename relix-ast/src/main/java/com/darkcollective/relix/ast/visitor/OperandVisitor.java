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
package com.darkcollective.relix.ast.visitor;

import com.darkcollective.relix.ast.*;

/**
 * Visitor over the {@link com.darkcollective.relix.ast.Operand} sealed hierarchy.
 *
 * <p>Implement this interface to process or transform scalar value expressions.
 * Every permitted operand type has a dedicated {@code visit} overload.
 *
 * @param <R> the return type produced by each visit method
 * @see com.darkcollective.relix.ast.Operand#accept(OperandVisitor)
 * @see OperandPrettyPrinter
 */
public interface OperandVisitor<R> {
    /** Visits an attribute (column) reference. */
    R visit(AttributeOperand node);
    /** Visits a string literal. */
    R visit(StringOperand node);
    /** Visits a numeric literal. */
    R visit(NumberOperand node);
    /** Visits a boolean literal. */
    R visit(BooleanOperand node);
    /** Visits a typed date literal ({@code DATE '2026-06-15'}). */
    R visit(DateOperand node);
    /** Visits a typed time-of-day literal ({@code TIME '13:40:00'}). */
    R visit(TimeOperand node);
    /** Visits a typed timestamp literal ({@code TIMESTAMP '2026-06-15T13:40:00Z'}). */
    R visit(TimestampOperand node);
    /** Visits a typed duration literal ({@code DURATION 'PT30M'}). */
    R visit(DurationOperand node);
    /** Visits a binary arithmetic expression (+, −, *, /). */
    R visit(BinaryArithmeticExpression node);
    /** Visits a function call. */
    R visit(FunctionCall node);
    /** Visits a set literal ({a, b, c}). */
    R visit(SetLiteralOperand node);
    /** Visits a unary negation expression (−operand). */
    R visit(UnaryOperand node);
    /** Visits a struct construction ({@code { name: expr, … }}). */
    R visit(StructConstruction node);
    /** Visits an array construction ({@code [ expr, … ]}). */
    R visit(ArrayConstruction node);
    /** Visits a boolean-valued condition operand (a predicate in operand position). */
    R visit(ConditionOperand node);
}

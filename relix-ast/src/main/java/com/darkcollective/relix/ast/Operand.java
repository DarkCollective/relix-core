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

/**
 * Root sealed interface for scalar value expressions used in projections and
 * predicates.
 *
 * <p>Operands include column references ({@link AttributeOperand}), literals
 * ({@link NumberOperand}, {@link StringOperand}, {@link BooleanOperand}, and the
 * typed temporal literals {@link DateOperand}, {@link TimeOperand},
 * {@link TimestampOperand}, {@link DurationOperand}), arithmetic expressions
 * ({@link BinaryArithmeticExpression}, {@link UnaryOperand}), function
 * invocations ({@link FunctionCall}), and set literals
 * ({@link SetLiteralOperand}).
 *
 * @see com.darkcollective.relix.ast.visitor.OperandVisitor
 */
public sealed interface Operand permits
        AttributeOperand,
        StringOperand,
        NumberOperand,
        BooleanOperand,
        DateOperand,
        TimeOperand,
        TimestampOperand,
        DurationOperand,
        BinaryArithmeticExpression,
        FunctionCall,
        SetLiteralOperand,
        UnaryOperand,
        StructConstruction,
        ArrayConstruction,
        ConditionOperand {
    <R> R accept(OperandVisitor<R> visitor);

    /** Returns the source location of the first token of this operand. */
    SourceLocation location();
}

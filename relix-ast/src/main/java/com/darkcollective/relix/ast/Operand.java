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
import com.darkcollective.relix.ast.visitor.internal.OperandPrettyPrinter;

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
        ConditionOperand,
        ParameterOperand {
    <R> R accept(OperandVisitor<R> visitor);

    /** Returns the source location of the first token of this operand. */
    SourceLocation location();

    /**
     * This operand as Relix text, writing the operators of any condition it holds as
     * glyphs. The same as {@link #prettyPrint(Spelling) prettyPrint(Spelling.GLYPHS)}.
     *
     * @return the operand as {@code .relix} text; never null
     * @since 1.0
     */
    default String prettyPrint() {
        return prettyPrint(Spelling.GLYPHS);
    }

    /**
     * This operand as Relix text that parses back to an equal operand, source
     * locations aside: {@code price * qty}, {@code UCase(name)}, {@code "it\"s"}.
     * Parentheses appear only where precedence needs them, and a column whose name
     * is a reserved word is written in backticks.
     *
     * <p>{@code spelling} chooses how the operators of a condition the operand holds
     * are written, as {@link RelNode#prettyPrint(Spelling)} does: {@code IIf(a ≥ 1, x, y)}
     * for {@link Spelling#GLYPHS}, {@code IIf(a >= 1, x, y)} for {@link Spelling#KEYWORDS}.
     *
     * @param spelling glyphs or ASCII keywords; must not be null
     * @return the operand as {@code .relix} text; never null
     * @since 1.0
     */
    default String prettyPrint(Spelling spelling) {
        return accept(new OperandPrettyPrinter(spelling));
    }
}

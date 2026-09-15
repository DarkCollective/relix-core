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

import java.util.function.Consumer;

/**
 * A single, reusable structural walk over {@link Operand} expression trees (and
 * the {@link Predicate} trees that contain them).
 *
 * <p>Several analyses each used to carry a near-identical {@code switch} that
 * recursed the compound operands and differed only in their terminal action —
 * the semantic validator's constant-argument / projected-operand / column-existence
 * checks, and the processor's COVER factor-dependency collector. Centralising the
 * recursion here gives every {@code Operand}/{@code Predicate} subtype a
 * <em>single registration point</em>: when a new operand kind is added, only this
 * walker needs the extra arm, and no caller can silently drop references inside it.
 *
 * <p>The walk recurses into every compound operand
 * ({@link BinaryArithmeticExpression}, {@link UnaryOperand},
 * {@link SetLiteralOperand}, {@link StructConstruction}, {@link ArrayConstruction},
 * and a {@link FunctionCall}'s arguments) and invokes:
 * <ul>
 *   <li>{@code onAttribute} for each {@link AttributeOperand}, and</li>
 *   <li>{@code onFunction} for each {@link FunctionCall} (the call's arguments
 *       are still walked).</li>
 * </ul>
 * Plain literals reference no columns and trigger no callback.
 *
 * <p>This module carries no dependencies beyond the JDK, so the walker is safe to
 * reuse from any module that depends on the AST.
 */
public final class OperandWalker {

    private OperandWalker() {
    }

    /**
     * Walks {@code expr}, invoking {@code onAttribute} for each attribute
     * reference and {@code onFunction} for each function call. A function call's
     * arguments are walked <em>before</em> {@code onFunction} fires, matching the
     * argument-then-callee error ordering callers rely on.
     */
    public static void walk(Operand expr,
                            Consumer<AttributeOperand> onAttribute,
                            Consumer<FunctionCall> onFunction) {
        switch (expr) {
            case AttributeOperand attr -> onAttribute.accept(attr);
            case FunctionCall fn -> {
                for (Operand arg : fn.arguments()) {
                    walk(arg, onAttribute, onFunction);
                }
                onFunction.accept(fn);
            }
            case BinaryArithmeticExpression arith -> {
                walk(arith.left(), onAttribute, onFunction);
                walk(arith.right(), onAttribute, onFunction);
            }
            case UnaryOperand unary -> walk(unary.operand(), onAttribute, onFunction);
            case ConditionOperand cond -> walk(cond.predicate(), onAttribute, onFunction);
            case SetLiteralOperand set -> {
                for (Operand elem : set.elements()) {
                    walk(elem, onAttribute, onFunction);
                }
            }
            case StructConstruction struct -> {
                for (StructConstruction.Field field : struct.fields()) {
                    walk(field.value(), onAttribute, onFunction);
                }
            }
            case ArrayConstruction array -> {
                for (Operand elem : array.elements()) {
                    walk(elem, onAttribute, onFunction);
                }
            }
            case NumberOperand _, StringOperand _, BooleanOperand _,
                 DateOperand _, TimeOperand _, TimestampOperand _, DurationOperand _ -> {
                /* literals reference no columns and need no callback */
            }
        }
    }

    /**
     * Walks every {@link Operand} reachable through {@code predicate}, recursing the
     * predicate's logical structure and driving {@link #walk(Operand, Consumer, Consumer)}
     * at each leaf operand. The callbacks fire with the same contract as the operand
     * overload.
     */
    public static void walk(Predicate predicate,
                            Consumer<AttributeOperand> onAttribute,
                            Consumer<FunctionCall> onFunction) {
        switch (predicate) {
            case ComparisonPredicate c -> {
                walk(c.left(), onAttribute, onFunction);
                walk(c.right(), onAttribute, onFunction);
            }
            case AndPredicate a -> {
                walk(a.left(), onAttribute, onFunction);
                walk(a.right(), onAttribute, onFunction);
            }
            case OrPredicate o -> {
                walk(o.left(), onAttribute, onFunction);
                walk(o.right(), onAttribute, onFunction);
            }
            case NotPredicate n -> walk(n.predicate(), onAttribute, onFunction);
            case NullPredicate n -> walk(n.operand(), onAttribute, onFunction);
            case ElementOfPredicate e -> {
                walk(e.element(), onAttribute, onFunction);
                walk(e.setExpression(), onAttribute, onFunction);
            }
            case PatternPredicate p -> {
                walk(p.operand(), onAttribute, onFunction);
                walk(p.pattern(), onAttribute, onFunction);
            }
        }
    }
}

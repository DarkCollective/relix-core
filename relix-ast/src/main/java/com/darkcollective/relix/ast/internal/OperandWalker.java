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
package com.darkcollective.relix.ast.internal;

import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.ArrayConstruction;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.BooleanOperand;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.ConditionOperand;
import com.darkcollective.relix.ast.DateOperand;
import com.darkcollective.relix.ast.DurationOperand;
import com.darkcollective.relix.ast.ElementOfPredicate;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.NotPredicate;
import com.darkcollective.relix.ast.NullPredicate;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.ParameterOperand;
import com.darkcollective.relix.ast.OrPredicate;
import com.darkcollective.relix.ast.PatternPredicate;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.SetLiteralOperand;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.ast.StructConstruction;
import com.darkcollective.relix.ast.TimeOperand;
import com.darkcollective.relix.ast.TimestampOperand;
import com.darkcollective.relix.ast.UnaryOperand;
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

    /** The parameter callback of the two-callback walks, which report none. */
    private static final Consumer<ParameterOperand> IGNORE = unused -> { };

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
        walk(expr, onAttribute, onFunction, IGNORE);
    }

    /**
     * Walks {@code expr} as {@link #walk(Operand, Consumer, Consumer)} does, also invoking
     * {@code onParameter} for each bound parameter ({@code $name}).
     */
    public static void walk(Operand expr,
                            Consumer<AttributeOperand> onAttribute,
                            Consumer<FunctionCall> onFunction,
                            Consumer<ParameterOperand> onParameter) {
        switch (expr) {
            case AttributeOperand attr -> onAttribute.accept(attr);
            case ParameterOperand param -> onParameter.accept(param);
            case FunctionCall fn -> {
                for (Operand arg : fn.arguments()) {
                    walk(arg, onAttribute, onFunction, onParameter);
                }
                onFunction.accept(fn);
            }
            case BinaryArithmeticExpression arith -> {
                walk(arith.left(), onAttribute, onFunction, onParameter);
                walk(arith.right(), onAttribute, onFunction, onParameter);
            }
            case UnaryOperand unary -> walk(unary.operand(), onAttribute, onFunction, onParameter);
            case ConditionOperand cond -> walk(cond.predicate(), onAttribute, onFunction, onParameter);
            case SetLiteralOperand set -> {
                for (Operand elem : set.elements()) {
                    walk(elem, onAttribute, onFunction, onParameter);
                }
            }
            case StructConstruction struct -> {
                for (StructConstruction.Field field : struct.fields()) {
                    walk(field.value(), onAttribute, onFunction, onParameter);
                }
            }
            case ArrayConstruction array -> {
                for (Operand elem : array.elements()) {
                    walk(elem, onAttribute, onFunction, onParameter);
                }
            }
            // literals reference no columns and need no callback
            case NumberOperand ignored -> { }
            case StringOperand ignored -> { }
            case BooleanOperand ignored -> { }
            case DateOperand ignored -> { }
            case TimeOperand ignored -> { }
            case TimestampOperand ignored -> { }
            case DurationOperand ignored -> { }
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
        walk(predicate, onAttribute, onFunction, IGNORE);
    }

    /**
     * Walks {@code predicate} as {@link #walk(Predicate, Consumer, Consumer)} does, also
     * invoking {@code onParameter} for each bound parameter ({@code $name}).
     */
    public static void walk(Predicate predicate,
                            Consumer<AttributeOperand> onAttribute,
                            Consumer<FunctionCall> onFunction,
                            Consumer<ParameterOperand> onParameter) {
        switch (predicate) {
            case ComparisonPredicate c -> {
                walk(c.left(), onAttribute, onFunction, onParameter);
                walk(c.right(), onAttribute, onFunction, onParameter);
            }
            case AndPredicate a -> {
                walk(a.left(), onAttribute, onFunction, onParameter);
                walk(a.right(), onAttribute, onFunction, onParameter);
            }
            case OrPredicate o -> {
                walk(o.left(), onAttribute, onFunction, onParameter);
                walk(o.right(), onAttribute, onFunction, onParameter);
            }
            case NotPredicate n -> walk(n.predicate(), onAttribute, onFunction, onParameter);
            case NullPredicate n -> walk(n.operand(), onAttribute, onFunction, onParameter);
            case ElementOfPredicate e -> {
                walk(e.element(), onAttribute, onFunction, onParameter);
                walk(e.setExpression(), onAttribute, onFunction, onParameter);
            }
            case PatternPredicate p -> {
                walk(p.operand(), onAttribute, onFunction, onParameter);
                walk(p.pattern(), onAttribute, onFunction, onParameter);
            }
        }
    }
}

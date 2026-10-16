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
package com.darkcollective.relix.semantic.internal;

import com.darkcollective.relix.ast.ArrayConstruction;
import com.darkcollective.relix.ast.NullOperand;
import com.darkcollective.relix.ast.internal.AttributeNames;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.BooleanOperand;
import com.darkcollective.relix.ast.ConditionOperand;
import com.darkcollective.relix.ast.DateOperand;
import com.darkcollective.relix.ast.DurationOperand;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.ParameterOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.SetLiteralOperand;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.ast.StructConstruction;
import com.darkcollective.relix.ast.TimeOperand;
import com.darkcollective.relix.ast.TimestampOperand;
import com.darkcollective.relix.ast.UnaryOperand;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.symbol.ArrayType;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.StructType;
import com.darkcollective.relix.symbol.Type;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Infers the static {@link Type} of an {@link Operand} expression against an input
 * {@link Schema}.
 *
 * <p>Shared by {@link SchemaInferenceVisitor} (to type projection columns,
 * aggregate results, etc.) and {@link PredicateValidator} (to type-check temporal
 * arithmetic and comparison operands), so the two agree on operand types. Literals
 * map to their scalar/temporal type; attribute references resolve through the
 * schema (unknown → {@code ANY}); function calls take their declared return type;
 * nested constructions yield struct/array types (NF² — ADR-0001); and binary/unary
 * arithmetic is typed by the temporal algebra ({@link TemporalArithmetic}), falling
 * back to {@code NUMBER} for ordinary numeric arithmetic.
 */
final class OperandTypeInferrer {

    private final SymbolTable symbolTable;
    private final FunctionCatalog functions;

    OperandTypeInferrer(SymbolTable symbolTable, FunctionCatalog functions) {
        this.symbolTable = Objects.requireNonNull(symbolTable, "symbolTable");
        this.functions   = Objects.requireNonNull(functions,   "functions");
    }

    /**
     * The type an attribute reference names, under each reading a dotted name has, in
     * the order {@link Schema#resolvePath} documents: the whole name as a column, then
     * the relation-qualified reading, then the path into a nested column, and last the
     * legacy stripped-tail fallback.
     *
     * <p>The order is the point. Trying the stripped tail first — which is what this
     * did — makes a path lose to any column that happens to share its last segment:
     * over a heading of {@code (name, level, skills)} where a skill has its own
     * {@code level}, {@code skills.level} inferred as the <em>person's</em> level. That
     * is worse than a wrong diagnostic, because inference does not fail: the heading
     * simply says {@code string} where the rows carry a number, and everything reading
     * that heading — a pushdown eligibility check, a cost estimate, a typed row
     * accessor — is confidently wrong.
     *
     * <p>It is deliberately the same order {@code ArrayRow.get} resolves values in. The
     * analyser's answer and the executor's have to come from one rule, or the heading
     * describes a row the query never produces.
     */
    private static Type attributeType(AttributeOperand attr, Schema inputSchema) {
        String qualifier = AttributeNames.qualifierOf(attr.name());
        if (qualifier != null) {
            List<Integer> matches =
                    inputSchema.qualifiedIndices(qualifier, attr.unqualifiedName());
            if (matches.size() == 1) {
                return inputSchema.columns().get(matches.getFirst()).type();
            }
        }
        return inputSchema.resolvePath(attr.name())
                .or(() -> inputSchema.column(attr.unqualifiedName())
                        .map(ColumnDefinition::type))
                .orElse(ScalarType.ANY);
    }

    /** Infers the type of {@code expr} against {@code inputSchema}. */
    Type infer(Operand expr, Schema inputSchema) {
        return switch (expr) {
            case AttributeOperand attr -> attributeType(attr, inputSchema);
            case NumberOperand            ignored -> ScalarType.NUMBER;
            case StringOperand            ignored -> ScalarType.STRING;
            case BooleanOperand           ignored -> ScalarType.BOOLEAN;
            // NULL takes its type from where it stands (see the conditional rules); alone it is ANY.
            case NullOperand              ignored -> ScalarType.ANY;
            case DateOperand              ignored -> ScalarType.DATE;
            case TimeOperand              ignored -> ScalarType.TIME;
            case TimestampOperand         ts -> ts.isZoned() ? ScalarType.TIMESTAMPTZ : ScalarType.TIMESTAMP;
            case DurationOperand          ignored -> ScalarType.DURATION;
            case BinaryArithmeticExpression b ->
                    TemporalArithmetic.binary(
                            infer(b.left(), inputSchema), b.operator(),
                            infer(b.right(), inputSchema)).orAny();
            case UnaryOperand u ->
                    TemporalArithmetic.unary(infer(u.operand(), inputSchema)).orAny();
            // A function's result may follow its arguments — a conditional returns
            // whichever branch it selects — so the argument types are inferred first
            // and the resolved definition is asked what a call with those returns.
            case FunctionCall fn -> callType(fn, inputSchema);
            case SetLiteralOperand        ignored -> ScalarType.ANY;
            // Nested constructions yield struct/array values with a recursively
            // inferred nested type (NF² — ADR-0001).
            case StructConstruction struct -> new StructType(struct.fields().stream()
                    .map(f -> new StructType.Field(f.name(), infer(f.value(), inputSchema)))
                    .toList());
            case ArrayConstruction array ->
                    new ArrayType(arrayElementType(array.elements(), inputSchema));
            // A predicate in operand position is a boolean value.
            case ConditionOperand    ignored -> ScalarType.BOOLEAN;
            // A parameter's type is read from where it is used, not from the parameter
            // itself (QueryParameters); on its own it is whatever its value turns out to be.
            case ParameterOperand    ignored -> ScalarType.ANY;
        };
    }

    /**
     * The type a call returns, asked of the resolved definition with the argument types.
     *
     * <p>A NULL argument has no type of its own and takes the one its context expects:
     * in {@code IIf(late, NULL, fee)} the other branch's. Read as {@code ANY}, it would
     * make the call {@code ANY} too, so when it does, each type another argument has is
     * tried in its place, and the first for which the call returns that same type is
     * the answer — {@code NUMBER} here — provided the other arguments hold it there.
     * Where none settles it, the call is {@code ANY}.
     */
    private Type callType(FunctionCall fn, Schema inputSchema) {
        ResolvedFunction resolved = ResolvedFunction.of(functions, fn.functionName(), symbolTable);
        List<ScalarType> types = fn.arguments().stream()
                .map(arg -> scalar(infer(arg, inputSchema)))
                .toList();
        Type plain = resolved.returnType(types);
        if (plain != ScalarType.ANY || fn.arguments().stream().noneMatch(NullOperand.class::isInstance)) {
            return plain;
        }
        Set<ScalarType> candidates = new LinkedHashSet<>();
        for (int i = 0; i < types.size(); i++) {
            if (!(fn.arguments().get(i) instanceof NullOperand) && types.get(i) != ScalarType.ANY) {
                candidates.add(types.get(i));
            }
        }
        for (ScalarType candidate : candidates) {
            // The candidate settles the call only if the other arguments hold it there:
            // where any type given to the NULLs comes straight back, as in
            // IIf(c, NULL, NULL), the NULLs alone decide and nothing is known.
            ScalarType probe = candidate == ScalarType.STRING ? ScalarType.NUMBER : ScalarType.STRING;
            if (resolved.returnType(withNulls(fn, types, candidate)) == candidate
                    && resolved.returnType(withNulls(fn, types, probe)) != probe) {
                return candidate;
            }
        }
        return plain;
    }

    /** {@code types} with each NULL argument's position given {@code type}. */
    private static List<ScalarType> withNulls(FunctionCall fn, List<ScalarType> types, ScalarType type) {
        List<ScalarType> settled = new ArrayList<>(types);
        for (int i = 0; i < settled.size(); i++) {
            if (fn.arguments().get(i) instanceof NullOperand) {
                settled.set(i, type);
            }
        }
        return settled;
    }

    /**
     * The scalar view of an inferred type, for the argument list a function's return
     * rule reads: a nested struct or array is not a scalar, and {@code ANY} is what a
     * scalar rule can say about one.
     */
    private static ScalarType scalar(Type type) {
        return type instanceof ScalarType s ? s : ScalarType.ANY;
    }

    /**
     * Infers the element type of an array literal: the common type of its elements
     * when they all agree, otherwise {@link ScalarType#ANY} (mixed or empty arrays
     * are heterogeneous under schema-on-read).
     */
    private Type arrayElementType(List<Operand> elements, Schema inputSchema) {
        if (elements.isEmpty()) return ScalarType.ANY;
        Type first = infer(elements.get(0), inputSchema);
        for (Operand e : elements.subList(1, elements.size())) {
            if (!infer(e, inputSchema).equals(first)) {
                return ScalarType.ANY;
            }
        }
        return first;
    }
}

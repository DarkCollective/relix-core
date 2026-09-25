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

import com.darkcollective.relix.semantic.SemanticError;
import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.ArrayConstruction;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.StructConstruction;
import com.darkcollective.relix.ast.BooleanOperand;
import com.darkcollective.relix.ast.ConditionOperand;
import com.darkcollective.relix.ast.DateOperand;
import com.darkcollective.relix.ast.TimeOperand;
import com.darkcollective.relix.ast.TimestampOperand;
import com.darkcollective.relix.ast.DurationOperand;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.ElementOfPredicate;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.NotPredicate;
import com.darkcollective.relix.ast.NullPredicate;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.OrPredicate;
import com.darkcollective.relix.ast.PatternPredicate;
import com.darkcollective.relix.ast.SetLiteralOperand;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.ast.UnaryOperand;
import com.darkcollective.relix.ast.visitor.PredicateVisitor;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.function.FunctionSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Validates attribute references and function calls within a predicate tree.
 *
 * <p>Used by {@link RelAlgebraValidator} to check the boolean conditions that
 * appear in:
 * <ul>
 *   <li>Selection nodes (σ) — predicate checked against the input relation's
 *       schema.</li>
 *   <li>Conditioned join nodes (⋈, ⟕, ⟖, ⟗, ⋉, ▷) — predicate checked
 *       against the combined left+right schema produced by
 *       the visitor's schema concatenation.</li>
 * </ul>
 *
 * <h2>Checks performed</h2>
 * <ul>
 *   <li>{@link AttributeOperand} — the column name (qualifier stripped) must
 *       exist in the available schema.</li>
 *   <li>{@link FunctionCall} — the function must be registered in the symbol
 *       table, and the argument count must match at least one overload.
 *       Arguments are recursively validated.</li>
 *   <li>Compound operands ({@link BinaryArithmeticExpression},
 *       {@link UnaryOperand}, {@link SetLiteralOperand}) — recursed into.</li>
 *   <li>Compound predicates ({@link AndPredicate}, {@link OrPredicate},
 *       {@link NotPredicate}) — recursed into.</li>
 *   <li>Literals ({@link NumberOperand}, {@link StringOperand},
 *       {@link BooleanOperand}) — no checks needed.</li>
 * </ul>
 *
 * <p>All errors are appended to the mutable {@code errors} list supplied at
 * construction time; the visitor never throws.
 */
public final class PredicateValidator implements PredicateVisitor<Void> {

    /** The schema against which attribute references are resolved. */
    private final Schema                  schema;
    private final SymbolTable             symbolTable;
    private final FunctionCatalog         functions;
    private final List<SemanticError>     errors;
    private final String                  filePath;
    /**
     * Where an error is placed when the offending operand carries no position of its
     * own — the operator the expression belongs to.
     */
    private final SourceLocation          fallback;
    /** Short label included in error messages, e.g. {@code "Selection σ"}. */
    private final String                  context;
    /**
     * Lower-cased names of in-scope function parameters (non-empty only when
     * validating a table-valued function body): an attribute reference matching a
     * parameter name resolves to that parameter rather than a relation column.
     */
    private final Set<String>             parameters;
    /** Types operands so temporal arithmetic/comparison rules can be checked. */
    private final OperandTypeInferrer     typeInferrer;

    PredicateValidator(Schema schema,
                       SymbolTable symbolTable,
                       FunctionCatalog functions,
                       List<SemanticError> errors,
                       String filePath,
                       SourceLocation fallback,
                       String context,
                       Set<String> parameters) {
        this.schema      = Objects.requireNonNull(schema,      "schema");
        this.symbolTable = Objects.requireNonNull(symbolTable, "symbolTable");
        this.functions   = Objects.requireNonNull(functions,   "functions");
        this.errors      = Objects.requireNonNull(errors,      "errors");
        this.filePath    = Objects.requireNonNull(filePath,    "filePath");
        this.fallback    = Objects.requireNonNull(fallback,    "fallback");
        this.context     = Objects.requireNonNull(context,     "context");
        this.parameters  = Objects.requireNonNull(parameters,  "parameters");
        this.typeInferrer = new OperandTypeInferrer(symbolTable, functions);
    }

    /**
     * Validates a bare scalar {@link Operand} (not part of a predicate) against
     * this validator's schema and symbol table — used to check aggregate-function
     * arguments such as {@code SUM(price * qty)} or {@code MIN(Abs(delta))}.
     *
     * @param operand the expression to validate; must not be null
     */
    void validateExpression(Operand operand) {
        validateOperand(operand);
    }

    // =========================================================================
    // Compound predicates — recurse into sub-predicates
    // =========================================================================

    @Override
    public Void visit(AndPredicate node) {
        node.left().accept(this);
        node.right().accept(this);
        return null;
    }

    @Override
    public Void visit(OrPredicate node) {
        node.left().accept(this);
        node.right().accept(this);
        return null;
    }

    @Override
    public Void visit(NotPredicate node) {
        node.predicate().accept(this);
        return null;
    }

    // =========================================================================
    // Leaf predicates — validate the operands they carry
    // =========================================================================

    @Override
    public Void visit(ComparisonPredicate node) {
        validateOperand(node.left());
        validateOperand(node.right());
        // Temporal values compare only with the same temporal type (ADR-0013).
        String cmpError = TemporalArithmetic.comparisonError(
                typeInferrer.infer(node.left(), schema),
                typeInferrer.infer(node.right(), schema));
        if (cmpError != null) {
            error(node.location(), cmpError);
        }
        return null;
    }

    @Override
    public Void visit(NullPredicate node) {
        validateOperand(node.operand());
        return null;
    }

    @Override
    public Void visit(ElementOfPredicate node) {
        validateOperand(node.element());
        validateOperand(node.setExpression());
        return null;
    }

    @Override
    public Void visit(PatternPredicate node) {
        validateOperand(node.operand());
        validateOperand(node.pattern());
        return null;
    }

    // =========================================================================
    // Operand validation
    // =========================================================================

    /**
     * Validates an attribute reference against {@link #schema}.
     *
     * <p>A <em>relation-qualified</em> reference ({@code rooms.name}) is resolved
     * by source-relation provenance when the schema carries it: it
     * must match exactly one column, else it is a validation error.  This closes
     * the silent-drop hole where a qualified reference above a join used to strip
     * its qualifier and bind to an unrelated first-match column — returning wrong
     * rows with no diagnostic.  A bare reference, or any reference against a schema
     * without provenance (or an open/schema-on-read relation), keeps the legacy
     * qualifier-stripping semantics.
     */
    private void validateAttribute(AttributeOperand attr) {
        String qualifiedError = QualifiedReferences.resolutionError(attr, schema);
        if (qualifiedError != null) {
            error(attr.location(), qualifiedError);
            return;
        }
        String colName = attr.unqualifiedName();
        if (schema.column(colName).isEmpty()
                && schema.resolvePath(attr.name()).isEmpty()
                && !parameters.contains(colName.toLowerCase(Locale.ROOT))) {
            error(attr.location(), "attribute '" + attr.name() + "' not found in input schema"
                    + Suggestions.columnHint(colName,
                            schema.columns().stream().map(ColumnDefinition::name).toList(),
                            !schema.isOpen()));
        }
    }

    /**
     * Validates a scalar operand by checking any contained attribute references
     * against {@link #schema} and any function calls against the symbol table.
     */
    private void validateOperand(Operand expr) {
        switch (expr) {
            case AttributeOperand attr -> validateAttribute(attr);
            case FunctionCall fn -> {
                // Validate arguments first, then check existence and arity.
                for (Operand arg : fn.arguments()) {
                    validateOperand(arg);
                }
                ResolvedFunction resolved =
                        ResolvedFunction.of(functions, fn.functionName(), symbolTable);
                if (resolved.isUnknown()) {
                    error(fn.location(), "unknown function '" + fn.functionName() + "'");
                } else {
                    int argCount = fn.arguments().size();
                    if (!resolved.accepts(argCount)) {
                        error(fn.location(), "function '" + fn.functionName()
                                + "' called with " + argCount
                                + " argument(s) but expects " + resolved.expectedArity());
                    }
                }
            }
            case BinaryArithmeticExpression arith -> {
                validateOperand(arith.left());
                validateOperand(arith.right());
                // Temporal arithmetic legality (ADR-0013); plain numeric arithmetic is fine.
                TemporalArithmetic.Result r = TemporalArithmetic.binary(
                        typeInferrer.infer(arith.left(), schema), arith.operator(),
                        typeInferrer.infer(arith.right(), schema));
                if (r.isError()) {
                    error(arith.location(), r.error());
                }
            }
            case UnaryOperand unary -> {
                validateOperand(unary.operand());
                TemporalArithmetic.Result r =
                        TemporalArithmetic.unary(typeInferrer.infer(unary.operand(), schema));
                if (r.isError()) {
                    error(unary.location(), r.error());
                }
            }
            // A boolean-valued condition operand (e.g. IIf's test): validate its
            // wrapped predicate exactly as a σ predicate — attribute refs and
            // nested function calls are checked by this same visitor.
            case ConditionOperand cond -> cond.predicate().accept(this);
            case SetLiteralOperand set -> {
                for (Operand elem : set.elements()) {
                    validateOperand(elem);
                }
            }
            case StructConstruction struct -> {
                for (StructConstruction.Field field : struct.fields()) {
                    validateOperand(field.value());
                }
            }
            case ArrayConstruction array -> {
                for (Operand elem : array.elements()) {
                    validateOperand(elem);
                }
            }
            case NumberOperand  ignored -> { /* literals need no validation */ }
            case StringOperand  ignored -> { /* literals need no validation */ }
            case BooleanOperand ignored -> { /* literals need no validation */ }
            case DateOperand      ignored -> { /* typed temporal literals are pre-validated at parse time */ }
            case TimeOperand      ignored -> { /* typed temporal literals are pre-validated at parse time */ }
            case TimestampOperand ignored -> { /* typed temporal literals are pre-validated at parse time */ }
            case DurationOperand  ignored -> { /* typed temporal literals are pre-validated at parse time */ }
        }
    }

    // =========================================================================
    // Error helper
    // =========================================================================

    /**
     * Records an error at {@code at}, the construct at fault, or at the operator the
     * expression belongs to when that construct was built without a position. A
     * diagnostic with neither is reported against the file.
     */
    private void error(SourceLocation at, String message) {
        SourceLocation where = at.line() > 0 ? at : fallback;
        errors.add(where.line() > 0
                ? SemanticError.error(where.filePath(), where.line(), where.column(),
                        context + ": " + message)
                : SemanticError.error(filePath, 0, 0, context + ": " + message));
    }
}

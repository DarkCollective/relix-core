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
package com.darkcollective.relix.semantic;

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.Symbol;
import com.darkcollective.relix.symbol.function.RelationFunctionSymbol;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Orchestrates the schema-inference pass (Phase 4 of semantic analysis).
 *
 * <p>The engine drives a {@link SchemaInferenceVisitor} over every
 * relational algebra expression reachable from the root script:
 * <ol>
 *   <li><strong>Named view bodies</strong> — every {@link QueryRelationSymbol}
 *       in the symbol table is visited in registration order.  After successful
 *       inference the symbol is re-registered with the resolved schema so that
 *       later symbols referencing it see the correct output type.</li>
 *   <li><strong>Root query expressions</strong> — the inline RA expression
 *       (if any) inside each {@link QueryStatement} in the root file.</li>
 * </ol>
 *
 * <p>Registration order equals dependency-first processing order because the
 * {@link SymbolCollector} already traverses files topologically.  This means
 * that in the common case all dependencies are resolved before dependants are
 * processed.  Symbols whose dependencies could not be inferred produce an
 * {@link SymbolCollector#UNRESOLVED_SCHEMA} placeholder; the engine silently
 * leaves those unannotated.
 *
 * <p>All errors are accumulated in a list and returned via {@link #errors()}.
 * The engine never throws.
 */
final class SchemaInferenceEngine {

    private final SymbolTable             symbolTable;
    private final SchemaAnnotations       annotations;
    private final FunctionCatalog         functions;
    private final List<SemanticError>     errors = new ArrayList<>();

    SchemaInferenceEngine(SymbolTable symbolTable,
                          SchemaAnnotations annotations,
                          FunctionCatalog functions) {
        this.symbolTable = Objects.requireNonNull(symbolTable,  "symbolTable");
        this.annotations = Objects.requireNonNull(annotations,  "annotations");
        this.functions   = Objects.requireNonNull(functions,    "functions");
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Runs schema inference over all {@link QueryRelationSymbol}s in the symbol
     * table (in registration order) and then over the inline expressions in the
     * supplied root-query list.
     *
     * @param rootQueries the root file's {@code query} statements
     */
    void infer(List<QueryStatement> rootQueries) {
        // Phase 4a: infer schemas for all named query-relation symbols. This
        // includes catalog views (the relix.* namespace, e.g. the relix.dependencies
        // view derived from relix.plan): their body nodes must be annotated here so
        // the planner can inline them at execution time, exactly like user views.
        for (Symbol sym : symbolTable.allSymbols()) {
            if (sym instanceof QueryRelationSymbol qrs) {
                inferQueryRelation(qrs);
            }
        }

        // Phase 4a': infer the output schema of every table-valued function body,
        // after views (a TVF body commonly references base relations or views).
        // The body's parameter references appear inside operands/predicates only, so
        // they do not affect the output schema; an unresolved parameter operand types
        // as ANY (it never reaches a relation leaf).
        for (Symbol sym : symbolTable.allSymbols()) {
            if (sym instanceof RelationFunctionSymbol rfs) {
                inferRelationFunction(rfs);
            }
        }

        // Phase 4b: infer schemas for root-query expressions.
        for (QueryStatement q : rootQueries) {
            switch (q.target()) {
                case ExpressionQueryTarget expr ->
                        runVisitor(expr.expression(), SemanticAnalyzer.STDIN_PATH);
                case NamedQueryTarget named -> {
                    // Named queries reference already-registered symbols —
                    // their schemas are already inferred in phase 4a.
                }
            }
        }
    }

    /** Returns accumulated inference errors (defensive copy). */
    List<SemanticError> errors() {
        return List.copyOf(errors);
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Infers the output schema for {@code qrs.body()} and, on success,
     * re-registers the symbol with the inferred schema so downstream symbols
     * see it resolved.
     */
    private void inferQueryRelation(QueryRelationSymbol qrs) {
        Optional<Schema> schemaOpt = runVisitor(qrs.body(), qrs.namespace());
        schemaOpt.ifPresent(schema -> {
            QueryRelationSymbol updated = new QueryRelationSymbol(
                    qrs.namespace(),
                    qrs.declaredName(),
                    qrs.provenance(),
                    qrs.shadowPolicy(),
                    schema,
                    qrs.body());
            symbolTable.register(updated); // PERMITTED → silent overwrite
        });
    }

    /**
     * Infers the output schema for {@code rfs.body()} and, on success, re-registers
     * the table-valued function symbol carrying its resolved {@code returnSchema} so
     * that call sites (and the planner) see it resolved.
     */
    private void inferRelationFunction(RelationFunctionSymbol rfs) {
        Optional<Schema> schemaOpt = runVisitor(rfs.body(), rfs.namespace());
        schemaOpt.ifPresent(schema ->
                symbolTable.register(rfs.withReturnSchema(schema))); // PERMITTED → silent overwrite
    }

    /**
     * Creates a fresh {@link SchemaInferenceVisitor} and runs it over
     * {@code root}.  Returns the inferred schema of the root node (or empty).
     */
    private Optional<Schema> runVisitor(RelNode root, String filePath) {
        var visitor = new SchemaInferenceVisitor(symbolTable, annotations, errors, filePath, functions);
        return root.accept(visitor);
    }
}

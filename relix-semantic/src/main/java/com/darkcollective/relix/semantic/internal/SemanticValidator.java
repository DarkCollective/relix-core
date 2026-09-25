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

import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.semantic.SemanticError;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.Symbol;
import com.darkcollective.relix.symbol.function.RelationFunctionSymbol;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.relation.RelationSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Orchestrates the validation pass (Phase 5 of semantic analysis).
 *
 * <p>The validator runs <em>after</em> schema inference (Phase 4) so that it
 * can use the fully-populated {@link SchemaAnnotations} map to check
 * cross-schema constraints without re-deriving schemas.
 *
 * <h2>Checks performed</h2>
 * <ol>
 *   <li><strong>Named query target resolution</strong> — every {@code query Name;}
 *       statement must reference a symbol that is registered in the symbol table.</li>
 *   <li><strong>Relational algebra tree validation</strong> — every
 *       {@link QueryRelationSymbol} body and every inline
 *       {@link ExpressionQueryTarget} expression is passed to a
 *       {@link RelAlgebraValidator} which checks:
 *       <ul>
 *         <li>Set-operation schema compatibility (equal width and compatible types)</li>
 *         <li>Division column-subset validity</li>
 *         <li>Projection attribute existence and function arity</li>
 *         <li>Aggregation group-by and aggregate attribute existence</li>
 *         <li>Sort attribute existence</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>All errors are accumulated; the validator never throws.
 */
public final class SemanticValidator {

    private final SymbolTable             symbolTable;
    private final SchemaAnnotations       annotations;
    private final FunctionCatalog         functions;
    private final List<SemanticError>     errors = new ArrayList<>();

    SemanticValidator(SymbolTable symbolTable,
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
     * Runs all validation checks over the registered symbols and the root
     * file's query statements.
     *
     * @param rootQueries the root file's {@code query} statements, in source order
     */
    void validate(List<QueryStatement> rootQueries) {
        // Phase 5a: validate every QueryRelationSymbol body, and every table-valued
        // function body (the latter with its parameters in scope, so a body's
        // reference to a parameter is not flagged as a missing column).
        for (Symbol sym : symbolTable.allSymbols()) {
            if (sym instanceof QueryRelationSymbol qrs) {
                String ctx = qrs.namespace() + "." + qrs.declaredName();
                validateTree(qrs.body(), ctx);
            } else if (sym instanceof RelationFunctionSymbol rfs) {
                validateRelationFunctionTree(rfs);
            }
        }

        // Phase 5b: validate root query statements.
        for (QueryStatement q : rootQueries) {
            switch (q.target()) {
                case NamedQueryTarget named -> validateNamedTarget(named.name());
                case ExpressionQueryTarget expr ->
                        validateTree(expr.expression(), SemanticAnalyzer.STDIN_PATH);
            }
        }
    }

    /** Returns accumulated validation errors (defensive copy). */
    List<SemanticError> errors() {
        return List.copyOf(errors);
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Checks that a named query target ({@code query Foo;}) refers to a known
     * symbol.
     */
    private void validateNamedTarget(String name) {
        if (symbolTable.lookupRelation(name).isEmpty()) {
            errors.add(SemanticError.error(SemanticAnalyzer.STDIN_PATH, 0, 0,
                    "Query target '" + name + "' is not defined"
                            + Suggestions.didYouMean(name, relationCandidates())));
        }
    }

    /**
     * Collects every known relation name as candidates for a query-target typo
     * suggestion.
     *
     * @return the set of known relation names (original casing)
     */
    private Set<String> relationCandidates() {
        Set<String> names = new java.util.HashSet<>();
        for (Symbol sym : symbolTable.allSymbols()) {
            if (sym instanceof RelationSymbol) {
                names.add(sym.declaredName());
            }
        }
        return names;
    }

    /** Runs a {@link RelAlgebraValidator} over a single RA expression tree. */
    private void validateTree(com.darkcollective.relix.ast.RelNode root, String filePath) {
        var validator = new RelAlgebraValidator(symbolTable, annotations, functions, errors, filePath);
        root.accept(validator);
    }

    /**
     * Validates a table-valued function body with its parameter names in scope, so a
     * reference to a parameter (e.g. {@code cid} in {@code σ customer_id = cid (Orders)})
     * resolves to the parameter rather than being reported as a missing column.
     */
    private void validateRelationFunctionTree(RelationFunctionSymbol rfs) {
        Set<String> params = rfs.parameters().stream()
                .map(ParameterDefinition::name)
                .map(n -> n.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        String ctx = rfs.namespace() + "." + rfs.declaredName();
        var validator = new RelAlgebraValidator(symbolTable, annotations, functions, errors, ctx, params);
        rfs.body().accept(validator);
    }
}

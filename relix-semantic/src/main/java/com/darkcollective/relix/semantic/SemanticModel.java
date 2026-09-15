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

import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.symbol.RelationStatistics;
import com.darkcollective.relix.symbol.graph.SchemaGraph;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The validated in-memory representation produced by a successful (or
 * partially-successful) semantic analysis run.
 *
 * <p>A {@code SemanticModel} is always returned inside a
 * {@link SemanticResult}.  When the analysis encountered non-fatal errors the
 * model reflects what could be determined up to the first unrecoverable failure
 * for each file; callers should inspect {@link SemanticResult#errors()} before
 * treating the model as authoritative.
 *
 * <h2>Contents</h2>
 * <dl>
 *   <dt>{@link #namespace()}</dt>
 *   <dd>The namespace declared in the root {@code .relix} file, or {@code "default"}
 *       if no {@code namespace} statement was present.</dd>
 *
 *   <dt>{@link #symbolTable()}</dt>
 *   <dd>The fully-populated symbol table, containing built-ins, imported symbols,
 *       and all declarations from the root file.  Backed by an
 *       {@code InMemorySymbolTable}.</dd>
 *
 *   <dt>{@link #sources()}</dt>
 *   <dd>Full source configuration for every {@code source} declaration reachable
 *       from the root file, keyed by the lower-cased canonical name.  The
 *       {@link #symbolTable()} contains the corresponding
 *       {@link com.darkcollective.relix.symbol.relation.SourceRelationSymbol}
 *       for schema-lookup purposes; this map provides the transport-level detail
 *       needed at execution time.</dd>
 *
 *   <dt>{@link #nodeSchemas()}</dt>
 *   <dd>Per-node schema annotations for every relational algebra tree that was
 *       successfully inferred.  Execution engines and optimizers may query the
 *       inferred schema at any node without re-running inference.</dd>
 *
 *   <dt>{@link #statistics()}</dt>
 *   <dd>Optional per-relation statistics (row counts, keys) gathered from a
 *       {@link CatalogProvider}, keyed by the same lower-cased canonical name as
 *       {@link #sources()}.  Consumed by the cost model; a relation absent from
 *       this map simply has no statistics.</dd>
 *
 *   <dt>{@link #schemaGraph()}</dt>
 *   <dd>The schema graph: declared relationship edges between
 *       relations, assembled from {@code relate} statements and
 *       {@code references:} blocks and validated against the inferred schemas,
 *       merged with any supplemental (session-learned) edges supplied to the
 *       analyzer.  {@link SchemaGraph#EMPTY} when nothing is declared.</dd>
 *
 *   <dt>{@link #rootQueries()}</dt>
 *   <dd>The ordered list of {@code query} statements in the root file; these are
 *       the top-level outputs the user requested.</dd>
 *
 *   <dt>{@link #functions()}</dt>
 *   <dd>The functions this model was analysed against — the catalogue the
 *       analyser resolved every call in it through.  It travels with the model
 *       because every later phase asks the same questions of a function that
 *       analysis did (what it returns, whether it may be folded, how a backend
 *       spells it), and two separately-built catalogues can disagree about what a
 *       name means.</dd>
 * </dl>
 *
 * @param namespace    the root file's namespace; defaults to {@code "default"}
 * @param symbolTable  the populated symbol table; must not be null
 * @param sources      canonical name → full source declaration; must not be null
 * @param connections  canonical name → database connection declaration; must not be null
 * @param statistics   canonical name → relation statistics; must not be null
 * @param nodeSchemas  per-node inferred schemas; must not be null
 * @param schemaGraph  the schema relationship graph; must not be null
 * @param rootQueries  ordered query statements from the root file; must not be null
 * @param functions    the catalogue every function call was resolved against; must
 *                     not be null
 */
public record SemanticModel(
        String namespace,
        SymbolTable symbolTable,
        Map<String, SourceDeclaration> sources,
        Map<String, ConnectionDeclaration> connections,
        Map<String, RelationStatistics> statistics,
        SchemaAnnotations nodeSchemas,
        SchemaGraph schemaGraph,
        List<QueryStatement> rootQueries,
        FunctionCatalog functions
) {
    public SemanticModel {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(symbolTable, "symbolTable");
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(connections, "connections");
        Objects.requireNonNull(statistics, "statistics");
        Objects.requireNonNull(nodeSchemas, "nodeSchemas");
        Objects.requireNonNull(schemaGraph, "schemaGraph");
        Objects.requireNonNull(rootQueries, "rootQueries");
        Objects.requireNonNull(functions, "functions");
        sources = Map.copyOf(sources);
        connections = Map.copyOf(connections);
        statistics = Map.copyOf(statistics);
        rootQueries = List.copyOf(rootQueries);
    }

    /**
     * Convenience constructor for models with no schema graph; the graph
     * defaults to {@link SchemaGraph#EMPTY} and {@link #functions()} to the
     * installed libraries.
     *
     * @param namespace    the root file's namespace
     * @param symbolTable  the populated symbol table
     * @param sources      canonical name → source declaration
     * @param connections  canonical name → connection declaration
     * @param statistics   canonical name → relation statistics
     * @param nodeSchemas  per-node inferred schemas
     * @param rootQueries  ordered query statements from the root file
     */
    public SemanticModel(String namespace, SymbolTable symbolTable,
                         Map<String, SourceDeclaration> sources,
                         Map<String, ConnectionDeclaration> connections,
                         Map<String, RelationStatistics> statistics,
                         SchemaAnnotations nodeSchemas, List<QueryStatement> rootQueries) {
        this(namespace, symbolTable, sources, connections, statistics, nodeSchemas,
                SchemaGraph.EMPTY, rootQueries, FunctionCatalog.discover());
    }

    /**
     * Convenience constructor for models with connections but no statistics.
     *
     * @param namespace    the root file's namespace
     * @param symbolTable  the populated symbol table
     * @param sources      canonical name → source declaration
     * @param connections  canonical name → connection declaration
     * @param nodeSchemas  per-node inferred schemas
     * @param rootQueries  ordered query statements from the root file
     */
    public SemanticModel(String namespace, SymbolTable symbolTable,
                         Map<String, SourceDeclaration> sources,
                         Map<String, ConnectionDeclaration> connections,
                         SchemaAnnotations nodeSchemas, List<QueryStatement> rootQueries) {
        this(namespace, symbolTable, sources, connections, Map.of(), nodeSchemas, rootQueries);
    }

    /**
     * Backward-compatible constructor for models with no database connections.
     *
     * @param namespace    the root file's namespace
     * @param symbolTable  the populated symbol table
     * @param sources      canonical name → source declaration
     * @param nodeSchemas  per-node inferred schemas
     * @param rootQueries  ordered query statements from the root file
     */
    public SemanticModel(String namespace, SymbolTable symbolTable,
                         Map<String, SourceDeclaration> sources,
                         SchemaAnnotations nodeSchemas, List<QueryStatement> rootQueries) {
        this(namespace, symbolTable, sources, Map.of(), Map.of(), nodeSchemas, rootQueries);
    }
}

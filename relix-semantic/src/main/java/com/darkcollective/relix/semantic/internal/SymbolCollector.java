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

import com.darkcollective.relix.semantic.CatalogProvider;
import com.darkcollective.relix.semantic.CatalogSnapshot;
import com.darkcollective.relix.semantic.SemanticError;
import com.darkcollective.relix.semantic.Severity;
import com.darkcollective.relix.ast.DateOperand;
import com.darkcollective.relix.ast.DurationOperand;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.ast.internal.TemporalLiterals;
import com.darkcollective.relix.ast.TimeOperand;
import com.darkcollective.relix.ast.TimestampOperand;
import com.darkcollective.relix.lang.ast.AssignmentStatement;
import com.darkcollective.relix.lang.ast.DefRelationStatement;
import com.darkcollective.relix.lang.ast.DefStatement;
import com.darkcollective.relix.lang.ast.EnvStatement;
import com.darkcollective.relix.lang.ast.ImportKind;
import com.darkcollective.relix.lang.ast.ImportStatement;
import com.darkcollective.relix.lang.ast.InlineTableBody;
import com.darkcollective.relix.lang.ast.QueryAssignmentBody;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.lang.ast.RelateStatement;
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.Statement;
import com.darkcollective.relix.lang.ast.source.ColumnReference;
import com.darkcollective.relix.lang.ast.source.ColumnSpec;
import com.darkcollective.relix.lang.ast.source.ConnectionTableSourceConfig;
import com.darkcollective.relix.lang.ast.source.CsvFileSourceConfig;
import com.darkcollective.relix.lang.ast.source.DatabaseSourceConfig;
import com.darkcollective.relix.lang.ast.source.GeneratorSourceConfig;
import com.darkcollective.relix.lang.ast.source.HttpSourceConfig;
import com.darkcollective.relix.lang.ast.source.JsonFileSourceConfig;
import com.darkcollective.relix.lang.ast.source.SourceConfig;
import com.darkcollective.relix.lang.ast.table.InlineTable;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.RegistrationResult;
import com.darkcollective.relix.symbol.RelationStatistics;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Type;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.ShadowPolicy;
import com.darkcollective.relix.symbol.Symbol;
import com.darkcollective.relix.symbol.SymbolError;
import com.darkcollective.relix.symbol.function.FunctionSymbol;
import com.darkcollective.relix.symbol.function.RelationFunctionSymbol;
import com.darkcollective.relix.symbol.function.ScalarFunctionSymbol;
import com.darkcollective.relix.symbol.relation.DatabaseRelationSymbol;
import com.darkcollective.relix.symbol.relation.InlineRelationSymbol;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.relation.RelationSymbol;
import com.darkcollective.relix.symbol.relation.SourceRelationSymbol;
import com.darkcollective.relix.symbol.relation.SystemRelationSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Performs the symbol-collection pass over all files reachable from the root.
 *
 * <p>Files are visited in <em>dependency-first</em> order (the order returned by
 * {@link ImportGraph#processingOrder()}), so that when a file is processed all
 * symbols from its imports are already in the table.
 *
 * <p>For each file the collector:
 * <ol>
 *   <li>Resolves {@code import} statements — copies exported symbols from already-
 *       processed dependency files into the current file's namespace.</li>
 *   <li>Registers {@code source} declarations as
 *       {@link SourceRelationSymbol}s; stores the full
 *       {@link SourceDeclaration} for execution-time use.</li>
 *   <li>Registers {@code :=} assignments as either
 *       {@link InlineRelationSymbol} (inline table, with type-inferred schema) or
 *       {@link QueryRelationSymbol} (RA expression, with a placeholder schema —
 *       the real schema is filled in by the schema-inference pass in Task 4).</li>
 *   <li>Registers {@code def} statements as {@link ScalarFunctionSymbol}s.</li>
 *   <li>Collects {@code query} statements from the <em>root file only</em>.</li>
 * </ol>
 *
 * <h2>Namespace handling</h2>
 * <p>Each file's namespace is taken from its {@code namespace} declaration, defaulting
 * to {@code "default"}.  When an import brings a symbol from a file with a different
 * namespace into the current file, the symbol is re-registered under the current
 * namespace so that unqualified lookups (which search {@code "default"} first) work
 * correctly.
 *
 * <h2>Error handling</h2>
 * <p>Registration conflicts (shadow-policy violations) and unresolved import names
 * are accumulated in the error list rather than thrown; all other files continue
 * to be processed.
 */
public final class SymbolCollector {

    /**
     * Placeholder schema used for {@link QueryRelationSymbol} instances whose output
     * schema has not yet been determined.  The schema-inference pass (Task 4) replaces
     * this placeholder with the inferred schema.
     */
    static final Schema UNRESOLVED_SCHEMA =
            new Schema(List.of(new ColumnDefinition("*", ScalarType.ANY)));

    private static final String DEFAULT_NS = "default";

    private final ImportGraph graph;
    private final SymbolTable symbolTable;

    private final List<SemanticError> errors      = new ArrayList<>();
    private final Map<String, SourceDeclaration> sources = new LinkedHashMap<>();
    private final Map<String, ConnectionDeclaration> connections = new LinkedHashMap<>();
    private final Map<String, RelationStatistics> statistics = new LinkedHashMap<>();
    private final List<QueryStatement> rootQueries = new ArrayList<>();
    private final List<RelateStatement> relates = new ArrayList<>();
    private final Map<String, List<ColumnReference>> inlineReferences = new LinkedHashMap<>();

    /**
     * Per-path exported symbols: resolved path → (canonicalName → Symbol).
     * Built incrementally as files are processed in dependency-first order;
     * referenced when resolving import statements encountered in later files.
     */
    private final Map<String, Map<String, Symbol>> fileExports = new LinkedHashMap<>();

    private final CatalogProvider catalog;
    private final GeneratorCatalog generators;

    SymbolCollector(ImportGraph graph, SymbolTable symbolTable) {
        this(graph, symbolTable, CatalogProvider.NONE);
    }

    SymbolCollector(ImportGraph graph, SymbolTable symbolTable, CatalogProvider catalog) {
        this(graph, symbolTable, catalog, GeneratorCatalog.NONE);
    }

    SymbolCollector(ImportGraph graph, SymbolTable symbolTable, CatalogProvider catalog,
                    GeneratorCatalog generators) {
        this.graph       = Objects.requireNonNull(graph,       "graph");
        this.symbolTable = Objects.requireNonNull(symbolTable, "symbolTable");
        this.catalog     = Objects.requireNonNull(catalog,     "catalog");
        this.generators  = Objects.requireNonNull(generators,  "generators");
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Runs the symbol collection pass over all successfully loaded files in the
     * import graph, in dependency-first order.
     */
    void collect() {
        String rootPath = graph.rootPath();
        for (String path : graph.processingOrder()) {
            Script script = graph.script(path).orElseThrow(
                    () -> new IllegalStateException(
                            "processingOrder() included path not in graph: " + path));
            collectFile(path, script, path.equals(rootPath));
        }
        resolveConnectionTableReferences();
        validateConnectionReferences();
        collectStatistics();
    }

    /**
     * Resolves dotted {@code connection.table} references appearing in view bodies
     * and root queries.  For each such reference whose prefix names a declared
     * connection, registers a {@link SourceRelationSymbol} whose schema comes from
     * the {@link CatalogProvider} (introspection), recording a synthetic
     * {@link ConnectionTableSourceConfig} source so execution can route it.  A
     * reference that cannot be resolved (no schema available) is an error.
     */
    private void resolveConnectionTableReferences() {
        Map<String, SourceLocation> refs = new LinkedHashMap<>();
        for (Symbol s : symbolTable.allSymbols()) {
            if (s instanceof QueryRelationSymbol qr) {
                collectRelationRefs(qr.body(), refs);
            }
        }
        for (QueryStatement q : rootQueries) {
            switch (q.target()) {
                case ExpressionQueryTarget e -> collectRelationRefs(e.expression(), refs);
                case NamedQueryTarget n -> refs.putIfAbsent(n.name(), q.location());
            }
        }
        refs.forEach(this::resolveConnectionTable);
    }

    private void collectRelationRefs(RelNode node, Map<String, SourceLocation> out) {
        if (node instanceof RelationNode r) {
            out.putIfAbsent(r.name(), r.location());
        }
        node.children().forEach(child -> collectRelationRefs(child, out));
    }

    private void resolveConnectionTable(String name, SourceLocation loc) {
        int dot = name.indexOf('.');
        if (dot < 0) {
            return;   // not a dotted reference
        }
        String connName = name.substring(0, dot);
        String table    = name.substring(dot + 1);
        ConnectionDeclaration conn = connections.get(connName.toLowerCase(Locale.ROOT));
        if (conn == null) {
            return;   // prefix is not a connection — left for normal relation resolution
        }
        if (symbolTable.lookupRelation(name).isPresent()) {
            return;   // already resolved (e.g. a prior reference)
        }
        // Every declared connection is asked, whatever its type. Which of them a given
        // provider can answer for is the provider's business — the introspecting one
        // reports a declaration it cannot open as "schema unavailable", and a replayed
        // snapshot answers for any connection it recorded. Testing the type here would
        // hard-code one implementation's reach into the caller.
        Optional<Schema> schema = catalog.tableSchema(conn, table);
        if (schema.isEmpty()) {
            errors.add(SemanticError.error(loc.filePath(), loc.line(), loc.column(),
                    "Cannot resolve schema for table '" + table + "' in connection '" + connName
                    + "': no schema available (declare it with a 'source ... from " + connName
                    + "' binding, or provide a catalog)"));
            return;
        }
        var sym = new SourceRelationSymbol(
                DEFAULT_NS, name, Provenance.USER, ShadowPolicy.PERMITTED, schema.get());
        if (registerAndCheck(sym, loc.filePath())) {
            sources.put(sym.canonicalName(),
                    new SourceDeclaration(true, name,
                            new ConnectionTableSourceConfig(connName, table, List.of())));
        }
    }

    /**
     * After all files are collected, verifies that every connection-bound source
     * references a connection that was actually declared.
     */
    private void validateConnectionReferences() {
        for (SourceDeclaration src : sources.values()) {
            if (src.config() instanceof ConnectionTableSourceConfig ct
                    && !connections.containsKey(ct.connection().toLowerCase(Locale.ROOT))) {
                errors.add(SemanticError.error(
                        src.location().filePath(), src.location().line(), src.location().column(),
                        "Source '" + src.name() + "' references undeclared connection '"
                        + ct.connection() + "'"));
            }
        }
    }

    /**
     * After connection-backed sources are known, gathers optimizer statistics
     * (row counts, keys, per-column distinct/null counts) for each leaf relation,
     * keyed by the same canonical name as {@link #sources}.  Three paths feed it,
     * none of which requires a live database:
     *
     * <ul>
     *   <li>A {@code connection.table} source is asked of the {@link CatalogProvider} —
     *       the only path that may need a connection, and not always: a replayed
     *       {@link CatalogSnapshot} answers from a file.</li>
     *   <li>A finite generator contributes its exact cardinality (ADR-0008).</li>
     *   <li>An {@link InlineRelationSymbol} contributes <em>exact</em> statistics
     *       derived from its in-memory rows (see {@link InlineStatistics}), so even
     *       a fully offline script sizes its inline relations precisely.</li>
     * </ul>
     *
     * <p>Statistics are advisory: a provider that returns empty (including the
     * offline {@link CatalogProvider#NONE}) simply leaves the relation without them.
     */
    private void collectStatistics() {
        for (Map.Entry<String, SourceDeclaration> entry : sources.entrySet()) {
            SourceConfig config = entry.getValue().config();
            if (config instanceof ConnectionTableSourceConfig ct) {
                ConnectionDeclaration conn = connections.get(ct.connection().toLowerCase(Locale.ROOT));
                if (conn == null) {
                    continue;   // undeclared connection, already reported by validation
                }
                catalog.tableStatistics(conn, ct.table())
                        .ifPresent(stats -> statistics.put(entry.getKey(), stats));
            } else if (config instanceof GeneratorSourceConfig gen) {
                // A finite generator knows its exact cardinality (ADR-0008); feed it
                // to the cost model as an exact row count.
                generators.generatorCardinality(gen.generatorName(), gen.args())
                        .ifPresent(count -> statistics.put(entry.getKey(), RelationStatistics.of(count)));
            }
        }
        // Inline relations carry their full data in the symbol layer, so their
        // statistics are computed exactly with no I/O — independent of any source.
        for (Symbol sym : symbolTable.allSymbols()) {
            if (sym instanceof InlineRelationSymbol inline) {
                statistics.putIfAbsent(inline.canonicalName(), InlineStatistics.of(inline));
            }
        }
    }

    /** Returns all errors accumulated during collection (defensive copy). */
    List<SemanticError> errors()             { return List.copyOf(errors);      }

    /** Returns all source declarations, keyed by canonical name (defensive copy). */
    Map<String, SourceDeclaration> sources() { return Map.copyOf(sources);      }

    /** Returns the collected database connections, keyed by canonical (lower-cased) name. */
    Map<String, ConnectionDeclaration> connections() { return Map.copyOf(connections); }

    /** Returns per-relation statistics, keyed by canonical name (defensive copy). */
    Map<String, RelationStatistics> statistics() { return Map.copyOf(statistics); }

    /** Returns {@code query} statements from the root file, in source order. */
    List<QueryStatement> rootQueries()       { return List.copyOf(rootQueries); }

    /** Returns {@code relate} statements from all collected files, in processing order. */
    List<RelateStatement> relates()          { return List.copyOf(relates);     }

    /** Returns inline-table {@code references} clauses, keyed by the declaring table's name. */
    Map<String, List<ColumnReference>> inlineReferences() { return Map.copyOf(inlineReferences); }

    // =========================================================================
    // Per-file collection
    // =========================================================================

    private void collectFile(String filePath, Script script, boolean isRoot) {
        String namespace = script.namespace().orElse(DEFAULT_NS);
        Map<String, Symbol> exports = new LinkedHashMap<>();
        fileExports.put(filePath, exports);

        for (Statement stmt : script.statements()) {
            switch (stmt) {
                case ImportStatement   imp  -> handleImport(filePath, namespace, imp, exports);
                case ConnectionDeclaration conn -> handleConnection(filePath, conn);
                case SourceDeclaration src  -> handleSource(filePath, namespace, src, exports);
                // Relationships are structural metadata, not symbols; they are
                // assembled into the SchemaGraph after schema inference (ADR-0024).
                case RelateStatement   rel  -> relates.add(rel);
                case AssignmentStatement as -> handleAssignment(filePath, namespace, as, exports);
                case DefStatement      def  -> handleDef(filePath, namespace, def, exports);
                case DefRelationStatement rdef -> handleDefRelation(filePath, namespace, rdef, exports);
                case QueryStatement    q    -> { if (isRoot) rootQueries.add(q); }
                case EnvStatement      e    -> { /* env vars carry no symbols */ }
            }
        }
    }

    // =========================================================================
    // Statement handlers
    // =========================================================================

    private void handleConnection(String filePath, ConnectionDeclaration conn) {
        String key = conn.name().toLowerCase(Locale.ROOT);
        if (connections.containsKey(key)) {
            errors.add(SemanticError.error(filePath,
                    conn.location().line(), conn.location().column(),
                    "Duplicate connection name: '" + conn.name() + "'"));
            return;
        }
        connections.put(key, conn);
    }

    private void handleSource(String filePath, String namespace,
                               SourceDeclaration src, Map<String, Symbol> exports) {
        Schema schema = schemaFromSourceConfig(src);
        if (src.config() instanceof HttpSourceConfig http) {
            for (String problem : HttpSourceChecks.check(src, http)) {
                errors.add(SemanticError.error(filePath,
                        src.location().line(), src.location().column(), problem));
            }
        }
        SourceRelationSymbol sym = new SourceRelationSymbol(
                namespace, src.name(), Provenance.USER, ShadowPolicy.PERMITTED, schema);

        if (registerAndCheck(sym, filePath)) {
            sources.put(sym.canonicalName(), src);
            if (src.exported()) exports.put(sym.canonicalName(), sym);
        }
    }

    private void handleAssignment(String filePath, String namespace,
                                   AssignmentStatement asgn, Map<String, Symbol> exports) {
        Symbol sym = switch (asgn.body()) {
            case InlineTableBody inline -> buildInlineRelation(namespace, asgn.name(), inline.table());
            case QueryAssignmentBody query -> new QueryRelationSymbol(
                    namespace, asgn.name(), Provenance.USER, ShadowPolicy.PERMITTED,
                    UNRESOLVED_SCHEMA, query.expression());
        };

        if (registerAndCheck(sym, filePath)) {
            if (asgn.exported()) exports.put(sym.canonicalName(), sym);
            if (asgn.body() instanceof InlineTableBody inline
                    && !inline.references().isEmpty()) {
                inlineReferences.put(asgn.name(), inline.references());
            }
        }
    }

    private void handleDef(String filePath, String namespace,
                           DefStatement def, Map<String, Symbol> exports) {
        var builder = ScalarFunctionSymbol.builder(def.name())
                .namespace(namespace)
                .provenance(Provenance.USER)
                .shadowPolicy(ShadowPolicy.PERMITTED)
                .returnType(def.returnType())
                .body(def.body());
        for (ParameterDefinition p : def.parameters()) {
            builder.parameter(p.name(), p.type());
        }
        ScalarFunctionSymbol sym = builder.build();

        if (registerAndCheck(sym, filePath)) {
            if (def.exported()) exports.put(sym.canonicalName(), sym);
        }
    }

    private void handleDefRelation(String filePath, String namespace,
                                   DefRelationStatement def, Map<String, Symbol> exports) {
        var builder = RelationFunctionSymbol.builder(def.name())
                .namespace(namespace)
                .provenance(Provenance.USER)
                .shadowPolicy(ShadowPolicy.PERMITTED)
                .body(def.body());
        for (ParameterDefinition p : def.parameters()) {
            builder.parameter(p.name(), p.type());
        }
        RelationFunctionSymbol sym = builder.build();

        if (registerAndCheck(sym, filePath)) {
            if (def.exported()) exports.put(sym.canonicalName(), sym);
        }
    }

    private void handleImport(String filePath, String namespace,
                              ImportStatement imp, Map<String, Symbol> exports) {
        String resolvedPath = ImportGraph.resolvePath(filePath, imp.sourcePath());
        Map<String, Symbol> sourceExports = fileExports.get(resolvedPath);

        if (sourceExports == null) {
            // File failed to load; the load error is already in the ImportGraph.
            return;
        }

        switch (imp.kind()) {
            case BULK -> {
                for (Symbol sym : sourceExports.values()) {
                    importSymbol(filePath, namespace, sym, imp.sourcePath(), exports);
                }
            }
            case SOURCE, RELATION, FUNCTION, UNQUALIFIED -> {
                for (String name : imp.names()) {
                    String key = name.toLowerCase(Locale.ROOT);
                    Symbol sym = sourceExports.get(key);
                    if (sym == null) {
                        errors.add(SemanticError.error(filePath, 0, 0,
                                "Symbol '" + name + "' not found or not exported by '"
                                        + imp.sourcePath() + "'"));
                        continue;
                    }
                    if (!kindMatches(imp.kind(), sym)) {
                        errors.add(SemanticError.error(filePath, 0, 0,
                                "Symbol '" + name + "' in '" + imp.sourcePath()
                                        + "' is not a " + kindLabel(imp.kind())));
                        continue;
                    }
                    importSymbol(filePath, namespace, sym, imp.sourcePath(), exports);
                }
            }
        }
    }

    /**
     * Registers {@code sym} (adapting its namespace if necessary) and exposes it
     * in the current file's exports.  Also propagates source-declaration metadata
     * when importing a {@link SourceRelationSymbol}.
     */
    private void importSymbol(String filePath, String namespace,
                              Symbol sym, String sourcePath, Map<String, Symbol> exports) {
        Symbol adapted = namespace.equals(sym.namespace()) ? sym : withNamespace(sym, namespace);
        if (registerAndCheck(adapted, filePath)) {
            exports.put(adapted.canonicalName(), adapted);
            if (sym instanceof SourceRelationSymbol) {
                SourceDeclaration decl = sources.get(sym.canonicalName());
                if (decl != null) sources.put(adapted.canonicalName(), decl);
            }
        }
    }

    // =========================================================================
    // Symbol construction helpers
    // =========================================================================

    private static InlineRelationSymbol buildInlineRelation(
            String namespace, String name, InlineTable table) {
        Schema schema = schemaFromInlineTable(table);
        List<Map<String, Operand>> rows = rowsFromInlineTable(table, schema);
        return new InlineRelationSymbol(
                namespace, name, Provenance.USER, ShadowPolicy.PERMITTED, schema, rows);
    }

    // =========================================================================
    // Registration and error conversion
    // =========================================================================

    /**
     * Registers {@code sym} in the symbol table and converts any
     * {@link SymbolError}s to {@link SemanticError}s.
     *
     * @return {@code true} if the symbol was actually registered
     */
    private boolean registerAndCheck(Symbol sym, String filePath) {
        RegistrationResult result = symbolTable.register(sym);
        for (SymbolError err : result.errors()) {
            Severity severity = switch (err.kind()) {
                case SHADOW_FORBIDDEN -> Severity.ERROR;
                case SHADOW_WARNING   -> Severity.WARNING;
            };
            errors.add(new SemanticError(filePath, 0, 0, err.message(), severity));
        }
        return result.registered();
    }

    // =========================================================================
    // Kind-matching and namespace adaptation
    // =========================================================================

    private static boolean kindMatches(ImportKind kind, Symbol sym) {
        return switch (kind) {
            case SOURCE      -> sym instanceof SourceRelationSymbol;
            case RELATION    -> sym instanceof RelationSymbol && !(sym instanceof SourceRelationSymbol);
            case FUNCTION    -> sym instanceof FunctionSymbol;
            case UNQUALIFIED, BULK -> true;
        };
    }

    private static String kindLabel(ImportKind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns a copy of {@code sym} with the specified namespace.
     * Because all symbol types are records, each branch constructs a new record.
     *
     * <p>The switch is exhaustive — {@link Symbol} is sealed with two branches
     * ({@link RelationSymbol} and {@link FunctionSymbol}), each of which is
     * itself sealed.
     */
    private static Symbol withNamespace(Symbol sym, String namespace) {
        return switch (sym) {
            case RelationSymbol rel -> switch (rel) {
                case SourceRelationSymbol   s -> new SourceRelationSymbol(
                        namespace, s.declaredName(), s.provenance(), s.shadowPolicy(), s.schema());
                case InlineRelationSymbol   s -> new InlineRelationSymbol(
                        namespace, s.declaredName(), s.provenance(), s.shadowPolicy(),
                        s.schema(), s.rows());
                case QueryRelationSymbol    s -> new QueryRelationSymbol(
                        namespace, s.declaredName(), s.provenance(), s.shadowPolicy(),
                        s.schema(), s.body());
                case DatabaseRelationSymbol s -> new DatabaseRelationSymbol(
                        namespace, s.declaredName(), s.provenance(), s.shadowPolicy(), s.schema());
                case SystemRelationSymbol   s -> new SystemRelationSymbol(
                        namespace, s.declaredName(), s.provenance(), s.shadowPolicy(),
                        s.schema(), s.rows());
            };
            case FunctionSymbol fn -> switch (fn) {
                case ScalarFunctionSymbol s -> new ScalarFunctionSymbol(
                        namespace, s.declaredName(), s.provenance(), s.shadowPolicy(),
                        s.parameters(), s.returnType(), s.properties(), s.body());
                case RelationFunctionSymbol s -> new RelationFunctionSymbol(
                        namespace, s.declaredName(), s.provenance(), s.shadowPolicy(),
                        s.parameters(), s.body(), s.returnSchema());
            };
        };
    }

    // =========================================================================
    // Schema extraction
    // =========================================================================

    /**
     * Builds a {@link Schema} from a source configuration by mapping each
     * {@link ColumnSpec} (IN or OUT) to a {@link ColumnDefinition}.
     *
     * <p>Both IN and OUT columns are included: IN columns appear as predicate
     * parameters in RA queries (e.g. {@code σ city = "Chicago" (weather_api)})
     * while OUT columns are the data fields returned.
     */
    private Schema schemaFromSourceConfig(SourceDeclaration src) {
        return switch (src.config()) {
            // Open / schema-on-read: a JSON source carries no declared columns;
            // its rows are nested documents navigated by path at query time.
            case JsonFileSourceConfig ignored -> Schema.open();
            // An HTTP source with no declared columns is open (schema-on-read),
            // exactly like a JSON file; a declared schema makes it closed + typed.
            case HttpSourceConfig     http -> http.isOpen() ? Schema.open() : closedSchema(http.columns());
            case DatabaseSourceConfig db   -> closedSchema(db.columns());
            case CsvFileSourceConfig  csv  -> closedSchema(csv.columns());
            case ConnectionTableSourceConfig ct -> closedSchema(ct.columns());
            // Generator-owned schema (ADR-0008): resolved from the registry by name.
            case GeneratorSourceConfig gen -> generatorSchema(src, gen);
        };
    }

    /**
     * Resolves a generator source's schema from the {@link GeneratorCatalog}.  An
     * unknown generator yields an error and the placeholder {@code UNRESOLVED_SCHEMA}
     * so analysis continues without cascading failures.
     */
    private Schema generatorSchema(SourceDeclaration src, GeneratorSourceConfig gen) {
        return generators.generatorSchema(gen.generatorName(), gen.args()).orElseGet(() -> {
            errors.add(SemanticError.error(
                    src.location().filePath(), src.location().line(), src.location().column(),
                    "Unknown generator '" + gen.generatorName() + "' for source '" + src.name() + "'"));
            return UNRESOLVED_SCHEMA;
        });
    }

    private static Schema closedSchema(List<ColumnSpec> specs) {
        List<ColumnDefinition> cols = specs.stream()
                .map(cs -> new ColumnDefinition(cs.name(), cs.type()))
                .collect(Collectors.toList());
        return new Schema(cols);
    }

    /**
     * Infers a {@link Schema} from an inline table by examining cell values
     * column-by-column.  A column is typed {@link ScalarType#NUMBER} if and only
     * if every non-empty cell in that column parses as a numeric literal; otherwise
     * it is {@link ScalarType#STRING}.
     */
    private static Schema schemaFromInlineTable(InlineTable table) {
        List<String> headers = table.headers();
        List<ColumnDefinition> cols = new ArrayList<>(headers.size());
        for (int i = 0; i < headers.size(); i++) {
            cols.add(new ColumnDefinition(headers.get(i), inferColumnType(table, i)));
        }
        return new Schema(cols);
    }

    /**
     * Converts the raw string cells in an inline table to typed {@link Operand}
     * values, using the already-inferred column types in {@code schema} to decide
     * whether each cell becomes a {@link NumberOperand} or a {@link StringOperand}.
     *
     * <p>A {@linkplain #isNullMarker(String) null-marker} cell (empty, {@code ⊥},
     * or {@code NULL}) is left out of the row map entirely; the executor
     * materialises an absent column as {@code NullValue}, so a NULL can appear in
     * a column of any type (e.g. a missing NUMBER to be filled by {@code SOLVE})
     * without forcing the column to STRING or failing to parse.
     */
    private static List<Map<String, Operand>> rowsFromInlineTable(
            InlineTable table, Schema schema) {
        List<String> headers = table.headers();
        List<Map<String, Operand>> result = new ArrayList<>(table.rows().size());
        for (List<String> row : table.rows()) {
            Map<String, Operand> rowMap = new LinkedHashMap<>(headers.size());
            for (int i = 0; i < headers.size() && i < row.size(); i++) {
                String col  = headers.get(i);
                String cell = row.get(i).trim();
                if (isNullMarker(cell)) {
                    continue;   // absent key -> NullValue at materialisation
                }
                Type type = schema.column(col)
                        .map(ColumnDefinition::type)
                        .orElse(ScalarType.STRING);
                Operand operand = switch (type) {
                    case ScalarType.NUMBER    -> new NumberOperand(cell);
                    case ScalarType.TIMESTAMP -> new TimestampOperand(TemporalLiterals.parseTimestamp(cell));
                    case ScalarType.DATE      -> new DateOperand(TemporalLiterals.parseDate(cell));
                    case ScalarType.TIME      -> new TimeOperand(TemporalLiterals.parseTime(cell));
                    case ScalarType.DURATION  -> new DurationOperand(TemporalLiterals.parseDuration(cell));
                    default -> new StringOperand(cell);
                };
                rowMap.put(col, operand);
            }
            result.add(rowMap);
        }
        return result;
    }

    /**
     * Returns {@link ScalarType#NUMBER} if every non-{@linkplain #isNullMarker
     * null} cell in column {@code colIndex} parses as a floating-point number;
     * otherwise {@link ScalarType#STRING}.
     *
     * <p>Null-marker cells are skipped rather than demoting the column: a numeric
     * column may carry a NULL (empty / {@code ⊥} / {@code NULL}) and still infer
     * as NUMBER — the type check is applied only to the values actually present.
     */
    private static ScalarType inferColumnType(InlineTable table, int colIndex) {
        for (List<String> row : table.rows()) {
            if (colIndex >= row.size()) continue;
            String cell = row.get(colIndex).trim();
            if (!isNullMarker(cell) && !isNumeric(cell)) {
                return ScalarType.STRING;
            }
        }
        return ScalarType.NUMBER; // all present cells numeric, or no data rows
    }

    /**
     * A cell that represents a missing value in an inline table: empty, the NULL
     * glyph {@code ⊥}, or the keyword {@code NULL} (case-insensitive). The cell is
     * assumed already trimmed.
     */
    private static boolean isNullMarker(String cell) {
        return cell.isEmpty() || cell.equals("⊥") || cell.equalsIgnoreCase("null");
    }

    private static boolean isNumeric(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}

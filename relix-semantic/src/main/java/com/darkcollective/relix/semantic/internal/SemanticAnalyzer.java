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
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.semantic.ScriptLoader;
import com.darkcollective.relix.semantic.SemanticError;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.events.QueryEvent;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.symbol.graph.SchemaGraph;
import com.darkcollective.relix.symbol.table.internal.InMemorySymbolTable;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Entry point for semantic analysis — the engine's front door.
 *
 * <p>The contract is {@link Script} → {@link SemanticResult}: the
 * engine analyses an <em>AST</em>, and text is not part of that contract.  How a
 * {@code Script} is produced — the {@code .relix} grammar, a serialized query,
 * a programmatic builder — is a frontend concern, and every frontend enters
 * here.
 *
 * <h2>Usage — AST-first (the primary entry point)</h2>
 * <pre>
 *   SemanticAnalyzer analyzer = new SemanticAnalyzer(loader);
 *   SemanticResult result = analyzer.analyze(script);
 * </pre>
 * The root is attributed to the synthetic path {@link #AST_PATH}
 * ({@code "<ast>"}); pass a real one with {@link #analyze(Script, String)} when
 * the caller knows where the AST came from.
 *
 * <h2>Usage — loader-based</h2>
 * <pre>
 *   SemanticAnalyzer analyzer = new SemanticAnalyzer(loader);
 *   SemanticResult result = analyzer.analyze("./weather.relix");
 * </pre>
 * The root is fetched through the configured {@link ScriptLoader}, which is also
 * what resolves every {@code import} the script reaches — so <em>how</em> a path
 * becomes a {@code Script} stays a frontend decision.  Reading {@code .relix}
 * <em>text</em> is one such frontend and lives outside the engine: see
 * {@code Relix.parse} in {@code relix-embed}, and {@code FileSystemScriptLoader} in
 * {@code relix-console}.
 *
 * <h2>Built-in symbols</h2>
 * Pass a {@link BuiltinProvider} to register functions or relations that should
 * be available in every script without an explicit {@code import}:
 * <pre>
 *   SemanticAnalyzer analyzer = new SemanticAnalyzer(loader, myBuiltins);
 * </pre>
 * Use {@link BuiltinProvider#none()} (the default) when no built-ins are needed.
 *
 * <h2>Functions</h2>
 * The scalar functions a script may call come from the installed function
 * libraries, discovered once per analyser and carried on to every later phase
 * through {@link SemanticModel#functions()}.  A library function enters the symbol
 * table when a script first mentions it, so an analysed script's symbols are the
 * functions it calls rather than the whole library.  Supply a
 * {@link com.darkcollective.relix.function.FunctionCatalog} explicitly to control
 * what is callable.
 *
 * <h2>Analysis phases</h2>
 * <ol>
 *   <li>Obtain the root script (supplied directly as a {@link Script}, or loaded
 *       from a path via the {@link ScriptLoader}).</li>
 *   <li>Load all transitively imported files via the {@link ScriptLoader};
 *       detect and report import cycles.</li>
 *   <li>Register built-in symbols via the {@link BuiltinProvider}.</li>
 *   <li>Collect all declarations (sources, assignments, defs) as typed symbols
 *       in the symbol table, processing files in topological import order.</li>
 *   <li>Resolve import statements — pull exported symbols from imported files
 *       into the importing file's scope.</li>
 *   <li>Infer schemas for relational-algebra expression bodies using a
 *       {@code SchemaInferenceVisitor}; annotate the per-node schema map.</li>
 *   <li>Assemble and validate the schema graph from {@code relate}
 *       statements and {@code references:} blocks, merging any supplemental
 *       session-learned edges (see {@link #withSupplementalRelationships}).</li>
 *   <li>Validate all names, arities, schema compatibility, and source-config
 *       completeness; accumulate all errors.</li>
 * </ol>
 *
 * <p>Instances are stateless after construction and may be reused across
 * multiple {@code analyze} calls.
 */
public final class SemanticAnalyzer {

    /**
     * Synthetic file path used in {@link SemanticError#filePath()} when the root
     * script came from a stream — piped stdin, or any other unnamed source —
     * rather than a named file.
     *
     * <p>Like {@link #AST_PATH} it is not a file name, and is never passed to the
     * file system.  A caller analysing text it read from standard input uses it as
     * the root path.
     */
    public static final String STDIN_PATH = "<stdin>";

    /**
     * Synthetic path the root script is attributed to when it was supplied as an
     * already-built {@link Script} with no path of its own — see
     * {@link #analyze(Script)}.
     *
     * <p>Like {@link #STDIN_PATH} it is not a file name, and is never passed to
     * the file system.
     */
    public static final String AST_PATH = "<ast>";

    private final ScriptLoader loader;
    private final BuiltinProvider builtins;
    private final CatalogProvider catalog;
    private final GeneratorCatalog generators;
    private final FunctionCatalog functions;
    private final SchemaGraph supplementalRelationships;
    private final List<QueryEvent> sessionEvents;
    private final ComponentInventory components;

    /**
     * Creates an analyser with a custom built-in provider and catalog provider, and
     * no generator catalog.
     *
     * @param loader   the script loader used to resolve imports; must not be null
     * @param builtins the hook for registering built-in symbols; must not be null
     * @param catalog  supplies schemas for connection-backed tables; must not be null
     */
    public SemanticAnalyzer(ScriptLoader loader, BuiltinProvider builtins, CatalogProvider catalog) {
        this(loader, builtins, catalog, GeneratorCatalog.NONE);
    }

    /**
     * Creates an analyser with a custom built-in provider, catalog provider, and
     * generator catalog.
     *
     * @param loader     the script loader used to resolve imports; must not be null
     * @param builtins   the hook for registering built-in symbols; must not be null
     * @param catalog    supplies schemas for connection-backed tables; must not be null
     * @param generators supplies schemas for generator sources; must not be null
     */
    public SemanticAnalyzer(ScriptLoader loader, BuiltinProvider builtins, CatalogProvider catalog,
                            GeneratorCatalog generators) {
        this(loader, builtins, catalog, generators, FunctionCatalog.discover());
    }

    /**
     * Creates an analyser that resolves function calls against {@code functions}
     * rather than against the installed libraries.
     *
     * <p>Every other constructor discovers the installed libraries — one catalogue,
     * built once and passed on through the {@link SemanticModel} — which is what an
     * embedder wants unless it is deliberately controlling which functions a script may
     * call.  Passing {@link FunctionCatalog#empty()} makes every function call unknown.
     *
     * @param loader     the script loader used to resolve imports; must not be null
     * @param builtins   the hook for registering built-in symbols; must not be null
     * @param catalog    supplies schemas for connection-backed tables; must not be null
     * @param generators supplies schemas for generator sources; must not be null
     * @param functions  the functions scripts may call; must not be null
     */
    public SemanticAnalyzer(ScriptLoader loader, BuiltinProvider builtins, CatalogProvider catalog,
                            GeneratorCatalog generators, FunctionCatalog functions) {
        this(loader, builtins, catalog, generators, functions, SchemaGraph.EMPTY, List.of(),
                ComponentInventory.NONE);
    }

    private SemanticAnalyzer(ScriptLoader loader, BuiltinProvider builtins, CatalogProvider catalog,
                             GeneratorCatalog generators, FunctionCatalog functions,
                             SchemaGraph supplementalRelationships,
                             List<QueryEvent> sessionEvents,
                             ComponentInventory components) {
        this.loader     = Objects.requireNonNull(loader,     "loader");
        this.builtins   = Objects.requireNonNull(builtins,   "builtins");
        this.catalog    = Objects.requireNonNull(catalog,    "catalog");
        this.generators = Objects.requireNonNull(generators, "generators");
        this.functions  = Objects.requireNonNull(functions,  "functions");
        this.supplementalRelationships =
                Objects.requireNonNull(supplementalRelationships, "supplementalRelationships");
        this.sessionEvents = List.copyOf(
                Objects.requireNonNull(sessionEvents, "sessionEvents"));
        this.components = Objects.requireNonNull(components, "components");
    }

    /**
     * Returns an analyser that merges the given supplemental relationships into
     * the schema graph on every analysis.
     *
     * <p>This is the seam for edges acquired <em>outside</em> the source text —
     * conversational acquisition ("how do orders relate to customers?") and
     * relationships captured from the joins a user actually wrote. A session
     * host (the REPL) keeps its learned edges and re-supplies them on each
     * accumulate-and-reanalyze pass. Supplemental edges are re-validated against
     * the current symbol table each time; a stale edge (its relation or column
     * no longer exists) is dropped with a <em>warning</em>, never a hard error —
     * unlike a declared edge in the file, it is not a claim the current source
     * makes.
     *
     * @param supplemental the session-learned edges; must not be null
     * @return a new analyser instance; this one is unchanged
     */
    public SemanticAnalyzer withSupplementalRelationships(SchemaGraph supplemental) {
        return new SemanticAnalyzer(loader, builtins, catalog, generators, functions,
                supplemental, sessionEvents, components);
    }

    /**
     * Returns an analyser that exposes {@code events} as the extent of
     * {@code relix.events} on every subsequent analysis.
     *
     * <p>This is the pull side of the observability bridge: a host collects the
     * {@code QueryEvent}s a run emitted — with an
     * {@link com.darkcollective.relix.events.internal.EventBuffer}, or any listener of its
     * own — and hands them back here, where they become rows a script can
     * {@code σ} and {@code γ} like any other relation.
     *
     * <p>The feed is necessarily the <em>previous</em> statement's, not the one
     * being analysed: a statement's events do not exist until it has been
     * optimized, planned, and run, all of which happen after analysis. A session
     * host (the REPL) that re-analyses per statement therefore shows the run
     * before; a one-shot batch script, analysed once before anything executes, has
     * no earlier feed and sees an empty relation.
     *
     * @param events the previous run's events, in arrival order; must not be null
     * @return a new analyser instance; this one is unchanged
     */
    /**
     * Returns an analyser whose {@code relix.version} additionally reports the
     * components the host can see.
     *
     * <p>The engine reports itself and the function libraries it holds; connectors,
     * solvers and JDBC drivers live above it — a driver behind a {@code java.sql}
     * dependency no engine module may have — so whoever assembled the process supplies
     * them here.
     *
     * @param components the host's inventory; must not be null
     * @return a new analyser instance; this one is unchanged
     */
    public SemanticAnalyzer withComponents(ComponentInventory components) {
        Objects.requireNonNull(components, "components");
        return new SemanticAnalyzer(loader, builtins, catalog, generators, functions,
                supplementalRelationships, sessionEvents, components);
    }

    public SemanticAnalyzer withSessionEvents(List<QueryEvent> events) {
        return new SemanticAnalyzer(loader, builtins, catalog, generators, functions,
                supplementalRelationships, events, components);
    }

    /**
     * Creates an analyser with a custom built-in provider and no catalog
     * (connection tables must declare their schema).
     *
     * @param loader   the script loader used to resolve imports; must not be null
     * @param builtins the hook for registering built-in symbols; must not be null
     */
    public SemanticAnalyzer(ScriptLoader loader, BuiltinProvider builtins) {
        this(loader, builtins, CatalogProvider.NONE);
    }

    /**
     * Creates an analyser with no built-in symbols and the given catalog provider.
     *
     * @param loader  the script loader used to resolve imports; must not be null
     * @param catalog supplies schemas for connection-backed tables; must not be null
     */
    public SemanticAnalyzer(ScriptLoader loader, CatalogProvider catalog) {
        this(loader, BuiltinProvider.none(), catalog);
    }

    /**
     * Creates an analyser with no built-in symbols, the given catalog provider, and
     * the given generator catalog.
     *
     * @param loader     the script loader used to resolve imports; must not be null
     * @param catalog    supplies schemas for connection-backed tables; must not be null
     * @param generators supplies schemas for generator sources; must not be null
     */
    public SemanticAnalyzer(ScriptLoader loader, CatalogProvider catalog, GeneratorCatalog generators) {
        this(loader, BuiltinProvider.none(), catalog, generators);
    }

    /**
     * Creates an analyser with no built-in symbols and no catalog.
     *
     * @param loader the script loader used to resolve imports; must not be null
     */
    public SemanticAnalyzer(ScriptLoader loader) {
        this(loader, BuiltinProvider.none(), CatalogProvider.NONE);
    }

    /**
     * Analyses an already-built {@link Script} — the engine's primary entry
     * point.
     *
     * <p>The script is analysed as the root of the import graph; any
     * {@code import} statements it contains are still resolved through the
     * configured {@link ScriptLoader}.  The root carries no path of its own, so
     * its relative import paths are normalised as-is ({@code "./lib.relix"} →
     * {@code "lib.relix"}) and it is attributed to {@link #AST_PATH} in the
     * diagnostics the analyser raises about the root <em>file</em>.  Diagnostics
     * about the tree's contents carry whatever {@code SourceLocation} the AST
     * itself holds.  Use {@link #analyze(Script, String)} to supply a real path.
     *
     * @param script the root script; must not be null
     * @return the analysis result; never null
     * @throws NullPointerException if {@code script} is null
     */
    public SemanticResult analyze(Script script) {
        return analyze(script, AST_PATH);
    }

    /**
     * Analyses an already-built {@link Script} that came from {@code rootPath}.
     *
     * <p>Identical to {@link #analyze(Script)} except that {@code rootPath} is
     * the root node's key in the import graph — so it is the base the root's
     * relative {@code import} paths resolve against, and the file path the
     * analyser names the root by.  A frontend that read the script from a file
     * (or from stdin, using {@link #STDIN_PATH}) should use this overload.
     *
     * @param script   the root script; must not be null
     * @param rootPath the path to attribute the script to; must not be null
     * @return the analysis result; never null
     * @throws NullPointerException if either argument is null
     */
    public SemanticResult analyze(Script script, String rootPath) {
        Objects.requireNonNull(script, "script");
        Objects.requireNonNull(rootPath, "rootPath");
        return analyzeScript(script, rootPath);
    }

    /**
     * Analyses the script at {@code rootPath} and returns a result containing
     * the semantic model (if analysis produced one) and all diagnostics collected.
     *
     * @param rootPath the path of the root {@code .relix} file, interpreted by
     *                 the configured {@link ScriptLoader}
     * @return the analysis result; never null
     * @throws NullPointerException if {@code rootPath} is null
     */
    public SemanticResult analyze(String rootPath) {
        Objects.requireNonNull(rootPath, "rootPath");
        return analyzeScript(null, rootPath); // script loaded lazily inside analyzeScript
    }

    // =========================================================================
    // Internal implementation (to be completed in Tasks 2–6)
    // =========================================================================

    /**
     * Core analysis pipeline.  Every public {@code analyze} method funnels here;
     * the {@code null}-script sentinel (load the root via the {@link ScriptLoader})
     * stays private, so the AST-first overloads can require a non-null script.
     *
     * <p>Current implementation covers phases 1–5:
     * <ol>
     *   <li>Build the {@link ImportGraph} (load all transitively imported files,
     *       detect cycles, record load errors).</li>
     *   <li>Create an {@link InMemorySymbolTable} and register built-in symbols.</li>
     *   <li>Run {@link SymbolCollector} to register every declared symbol and
     *       resolve import statements in dependency-first order.</li>
     *   <li>Run {@link SchemaInferenceEngine} to infer the output schema of every
     *       relational algebra expression body and annotate the node map.</li>
     *   <li>Run {@link SchemaGraphBuilder} to assemble the schema graph from
     *       declared relationships, validate every edge, and merge supplemental
     *       session-learned edges (ADR-0024).</li>
     *   <li>Run {@link SemanticValidator} to check set-op schema compatibility,
     *       division column subsets, projection attribute existence, function
     *       arities, aggregation/sort attribute existence, and named targets.</li>
     * </ol>
     *
     * @param rootScript the pre-parsed root script, or {@code null} when
     *                   {@code rootPath} should be loaded via the {@link ScriptLoader}
     * @param rootPath   the path (real or synthetic) to use in diagnostics and
     *                   as the key for the root file in the import graph
     */
    private SemanticResult analyzeScript(Script rootScript, String rootPath) {
        // ── Phase 1: Build import graph ───────────────────────────────────────
        ImportGraph graph = ImportGraph.build(rootPath, rootScript, loader);

        List<SemanticError> errors = new ArrayList<>(graph.loadErrors());

        // If the root itself failed to load there is nothing to analyse.
        if (graph.script(rootPath).isEmpty()) {
            return SemanticResult.failure(errors);
        }

        // ── Phase 2: Symbol table — register built-ins first ─────────────────
        SymbolTable symbolTable = new InMemorySymbolTable();
        builtins.register(symbolTable);

        // ── Phase 3: Symbol collection — dependency-first traversal ──────────
        SymbolCollector collector = new SymbolCollector(graph, symbolTable, catalog, generators);
        collector.collect();
        errors.addAll(collector.errors());

        // ── Phase 3.5: System catalog relations (relix.*) — ADR-0007 ──────────
        // Registered after collection (extent depends on the collected symbols)
        // but before inference, so a query may reference relix.* and have it
        // resolve, infer, and validate like any other relation.
        CatalogBuilder.registerInto(
                symbolTable, collector.connections(), collector.statistics(), sessionEvents,
                functions, components,
                new RelationBoundedness(symbolTable, collector.sources(), generators));

        // ── Phase 4: Schema inference — walk every RA expression body ─────────
        SchemaAnnotations annotations = new SchemaAnnotations();
        SchemaInferenceEngine inferenceEngine =
                new SchemaInferenceEngine(symbolTable, annotations, functions);
        inferenceEngine.infer(collector.rootQueries());
        errors.addAll(inferenceEngine.errors());

        // ── Phase 4.5: Fill relix.columns / relix.plan now that schemas resolve ─
        // (relix.relations/relix.dependencies were filled at Phase 3.5; columns'
        //  rows depend on inferred view schemas and plan's rows on the per-node
        //  schema annotations, so both are filled here — ADR-0007.)
        CatalogBuilder.fillColumns(symbolTable, collector.statistics());
        CatalogBuilder.fillPlan(symbolTable, annotations);

        // ── Phase 4.7: Schema graph — assemble + validate relationships ───────
        // (ADR-0024.)  After inference so view (QR) endpoints carry their
        // resolved schemas; declared-edge violations are hard errors, stale
        // supplemental (session-learned) edges demote to warnings.
        SchemaGraphBuilder graphBuilder = new SchemaGraphBuilder(symbolTable);
        SchemaGraph schemaGraph = graphBuilder.build(
                collector.sources().values(), collector.relates(),
                collector.inlineReferences(), supplementalRelationships, rootPath);
        errors.addAll(graphBuilder.errors());

        // ── Phase 5: Validation — structural and semantic constraints ─────────
        SemanticValidator validator = new SemanticValidator(symbolTable, annotations, functions);
        validator.validate(collector.rootQueries());
        errors.addAll(validator.errors());

        // Determine the root namespace from its script declaration.
        String namespace = graph.script(rootPath)
                .flatMap(Script::namespace)
                .orElse("default");

        // Build the semantic model.
        // (Task 6 adds integration tests.)
        SemanticModel model = new SemanticModel(
                namespace,
                symbolTable,
                collector.sources(),
                collector.connections(),
                collector.statistics(),
                annotations,
                schemaGraph,
                collector.rootQueries(),
                functions);

        return errors.isEmpty()
                ? SemanticResult.success(model)
                : SemanticResult.partial(model, errors);
    }
}

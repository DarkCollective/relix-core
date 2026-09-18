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
package com.darkcollective.relix.embed;

import com.darkcollective.relix.ast.Expr;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.connectors.std.ConnectionPool;
import com.darkcollective.relix.connectors.std.DataSourceRegistry;
import com.darkcollective.relix.connectors.std.DriverProvisioner;
import com.darkcollective.relix.connectors.std.HttpFetcher;
import com.darkcollective.relix.connectors.std.ConnectorCatalogProvider;
import com.darkcollective.relix.connectors.std.JdbcCatalogProvider;
import com.darkcollective.relix.lang.ScriptParser;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.lang.ast.ScriptParseException;
import com.darkcollective.relix.lang.ast.ScriptPrinter;
import com.darkcollective.relix.lang.ast.Statement;
import com.darkcollective.relix.events.QueryEvent;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.function.FunctionLibrary;
import com.darkcollective.relix.lang.ast.ScriptBuilders;
import com.darkcollective.relix.lang.ast.source.ConnectionTableSourceConfig;
import com.darkcollective.relix.lang.ast.table.MarkdownInlineTable;
import com.darkcollective.relix.processor.ArrayRow;
import com.darkcollective.relix.processor.DataSourceConnector;
import com.darkcollective.relix.processor.ExecutionContext;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.connector.RelixConnector;
import com.darkcollective.relix.processor.connector.ConnectorProvisioner;
import com.darkcollective.relix.processor.connector.ConnectorRegistry;
import com.darkcollective.relix.processor.generator.GeneratorRegistry;
import com.darkcollective.relix.semantic.BuiltinProvider;
import com.darkcollective.relix.semantic.IrReport;
import com.darkcollective.relix.semantic.CatalogProvider;
import com.darkcollective.relix.semantic.ComponentInventory;
import com.darkcollective.relix.semantic.CatalogSnapshot;
import com.darkcollective.relix.semantic.InMemoryScriptLoader;
import com.darkcollective.relix.semantic.ScriptLoader;
import com.darkcollective.relix.semantic.SemanticAnalyzer;
import com.darkcollective.relix.semantic.SchemaInference;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.cost.ObservedCardinalities;
import com.darkcollective.relix.semantic.SemanticResult;
import com.darkcollective.relix.symbol.graph.SchemaGraph;
import com.darkcollective.relix.symbol.RelationStatistics;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.value.Value;

import com.darkcollective.relix.solver.SolverCatalog;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * A Relix session — the environment relations resolve against, and the entry point to
 * the embedding API.
 *
 * <p>Two things live here and nowhere else: the <strong>declarations</strong> a script
 * would contain (sources, connections, views, functions, relationships) and the
 * <strong>bindings</strong> that connect them to the outside world (a live
 * {@link DataSource}, a clock, a catalog). Everything else is a {@link Relation}, which
 * is a value.
 *
 * {@snippet lang = "java":
 * try (Relix relix = Relix.builder().jdbc("warehouse", dataSource).build()) {
 *     relix.define("""
 *         source Orders from warehouse { table: "orders" };
 *         Open := { σ status = 'OPEN' (Orders) };
 *         """);
 *
 *     Relation open = relix.relation("Open");
 * }
 * }
 *
 * <h2>Declarations accumulate; relations are pinned</h2>
 *
 * <p>{@link #define} adds statements to the session, and the next relation minted from it
 * is analysed against everything declared so far. A {@link Relation}, however,
 * <strong>captures the model it was created against</strong>: redefining {@code Orders}
 * afterwards does not change a relation already built over it.
 *
 * <p>That rule is what makes a relation a value rather than a view onto mutable state. A
 * relation whose meaning shifted under its holder could not be composed with confidence,
 * cached, or handed to another thread — and the alternative costs nothing, because
 * {@link SemanticModel} is already immutable.
 *
 * <h2>It works with no database — deliberately, not by default</h2>
 *
 * <p>A source that <em>declares</em> its own schema needs nothing reachable, so a session
 * over one composes, renders and optimises offline with no special mode.
 *
 * <p>A reference that can only be resolved by asking a database — a dotted
 * {@code warehouse.orders}, whose columns live in the catalog — is different. The analyser
 * reports it as an unresolved name and still returns a model, and this session
 * <strong>rejects</strong> that by default: the overwhelmingly common cause is a typo,
 * and a relation built over a name that resolves to nothing is a failure deferred to a
 * worse moment. {@link Builder#allowUnresolved()} takes the other reading, for the case
 * where composing and rendering against an unreachable database is the actual goal.
 *
 * <h2>Thread safety</h2>
 *
 * <p>A session is not thread-safe: {@link #define} mutates it, and analysis is cached.
 * The relations it hands out <em>are</em> safe to share, which is the point of pinning.
 *
 * @since 1.0
 */
public final class Relix implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Relix.class.getName());

    /** The path a session's script is attributed to in diagnostics and import resolution. */
    static final String SESSION_PATH = "<session>";

    private final DataSourceRegistry dataSources;
    private final boolean allowUnresolved;
    private final CatalogProvider catalog;

    /** The catalog this session built, and therefore closes; null when one was supplied. */
    private final ConnectorCatalogProvider ownedCatalog;
    private final List<FunctionLibrary> installedLibraries = new ArrayList<>();

    /**
     * The caller's own connectors, dispatched to ahead of the discovered ones. Not closed
     * with the session: they are the caller's, as a bound {@link DataSource} is.
     */
    private final List<RelixConnector> installedConnectors = new ArrayList<>();

    /** The bindings execution reads: where relative paths resolve, and what time it is. */
    private final Path baseDirectory;
    private final Clock clock;
    private final int maxFixpointRounds;
    private final int maxMaterializedRows;
    private final DriverProvisioner driverProvisioner;
    private final ConnectorProvisioner connectorProvisioner;
    private final ScriptLoader scriptLoader;
    private final SchemaGraph relationships;
    private final List<QueryEvent> sessionEvents;

    /**
     * The generators, serving both halves of the pipeline: the analyser resolves a
     * generator source's schema through it, and the connector produces that source's rows
     * from the same registry — one directory rather than two that can disagree.
     */
    private final GeneratorRegistry generators = new GeneratorRegistry();

    /**
     * What this session's runs have measured, in the two shapes the cost model reads.
     *
     * <p>{@code observedRows} is per relation, which is where a leaf's real size belongs
     * and what a {@link CatalogSnapshot} can carry away; {@code observedExpressions} is
     * keyed by the printed expression, which is the only key a post-selection or
     * post-join count has. Both outlive any one query, which is why they are the
     * session's rather than a relation's.
     *
     * <p>Concurrent, and that is not incidental. A session is not thread-safe because
     * {@link #define} mutates it — but the relations it hands out <em>are</em> safe to
     * share, and every execution terminal writes here without the caller doing anything
     * to the session at all. Two threads draining two pinned relations is therefore
     * in-contract and races on this map, which is why it is not the plain
     * {@code LinkedHashMap} its sibling {@code observedExpressions} can afford to guard
     * with a lock instead. Iteration order carries no meaning: the merge in
     * {@link #statisticsWithObservations} is keyed by relation name.
     */
    private final Map<String, Long> observedRows = new java.util.concurrent.ConcurrentHashMap<>();
    private final ObservedCardinalities observedExpressions = new ObservedCardinalities();

    /**
     * The pool every JDBC connection in this session is borrowed from, opening fresh
     * connections through the registry — which is what carries a bound {@code DataSource}
     * into execution. It outlives any one query, so the session owns it and
     * {@link #close()} is what releases it.
     */
    private final ConnectionPool pool;

    /**
     * The row streams this session has handed out and not seen closed.
     *
     * <p>The <em>stream</em> rather than the connector, because the stream is what holds
     * the resources: closing it runs the whole chain — the live {@code ResultSet}, its
     * {@code Statement}, the connection back to the pool, and only then the connector.
     * Closing the connector alone reaches none of that, since a connector keeps no
     * register of the streams it opened; ownership runs the other way by design.
     *
     * <p>Concurrent for the reason {@link #closed} is volatile — a relation may be
     * streamed on one thread while another opens its own.
     */
    private final Set<StreamHandle> liveStreams =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Volatile because {@link #close} runs on the closing thread while a shared
     * relation's terminal reads it through {@link #requireOpen} on another — the same
     * reason {@code ConnectionPool} marks its own flag volatile.
     */
    private volatile boolean closed;

    private SemanticAnalyzer analyzer;
    private final List<Statement> statements = new ArrayList<>();

    private Optional<String> namespace = Optional.empty();

    /** The analysis of everything declared so far; invalidated by {@link #define}. */
    private SemanticModel cachedModel;

    private Relix(Builder builder) {
        this.dataSources = builder.dataSources;
        // The default catalog asks each connection's own connector to describe its tables
        // and falls back to JDBC introspection, which serves a bound handle and delegates a
        // declared url. Offline either answers empty rather than failing, which is what lets
        // a session compose with nothing reachable. A caller-supplied catalog is theirs, so
        // only the one built here is closed with the session.
        if (builder.catalog != null) {
            this.catalog = builder.catalog;
            this.ownedCatalog = null;
        } else {
            ConnectorCatalogProvider built =
                    new ConnectorCatalogProvider(new JdbcCatalogProvider(dataSources));
            this.catalog = built;
            this.ownedCatalog = built;
        }
        this.allowUnresolved = builder.allowUnresolved;
        this.baseDirectory = builder.baseDirectory;
        this.clock = builder.clock;
        this.maxFixpointRounds = builder.maxFixpointRounds;
        this.maxMaterializedRows = builder.maxMaterializedRows;
        this.driverProvisioner = builder.driverProvisioner;
        this.connectorProvisioner = builder.connectorProvisioner;
        this.scriptLoader = builder.scriptLoader;
        this.relationships = builder.relationships;
        this.sessionEvents = builder.sessionEvents;
        this.pool = new ConnectionPool(dataSources);
        this.installedLibraries.addAll(builder.libraries);
        this.installedConnectors.addAll(builder.connectors);
        rebuildAnalyzer();
        // A bound connection is a declaration the session makes on the caller's behalf:
        // the AST names the connection, the registry holds the handle (ADR-0027 D6).
        builder.connections.keySet()
                .forEach(name -> statements.add(dataSources.declarationFor(name)));
    }

    /**
     * Starts building a session.
     *
     * @return a new builder
     * @since 1.0
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * A session with no bindings.
     *
     * @return a session with default settings
     * @since 1.0
     */
    public static Relix open() {
        return builder().build();
    }

    // -------------------------------------------------------------------------
    // Declarations
    // -------------------------------------------------------------------------

    /**
     * Adds declarations to the session, given as {@code .relix} text.
     *
     * <p>Takes any statements the grammar accepts — {@code source}, {@code connection},
     * a view assignment, {@code def}, {@code relate}, an inline table — and a
     * {@code namespace}. A {@code query} statement is a declaration of nothing, so it is
     * rejected here; use {@link #script} for text that contains one.
     *
     * @param relixText the declarations; must not be null
     * @return this session, for chaining
     * @throws RelixException if the text does not parse, contains a query, or does not
     *                        analyse against what is already declared
     *
     * @since 1.0
     */
    public Relix define(String relixText) {
        Script parsed = parse(relixText);
        List<Statement> queries = parsed.statements().stream()
                .filter(QueryStatement.class::isInstance)
                .toList();
        if (!queries.isEmpty()) {
            throw new RelixException(
                    "define() takes declarations; this text has " + queries.size()
                            + " query statement(s). Use script(...) to get a Relation for each.");
        }
        return install(parsed);
    }

    /**
     * Adds every declaration in {@code relixText} and returns one {@link Relation} per
     * {@code query} statement, in source order.
     *
     * <p>The whole-script counterpart of {@link #define} — what a caller reaches for when
     * the text is a {@code .relix} file rather than a fragment.
     *
     * @param relixText the script; must not be null
     * @return one relation per query statement, in order; empty when there are none
     * @throws RelixException if the text does not parse or does not analyse
     * @since 1.0
     */
    public List<Relation> script(String relixText) {
        Script parsed = parse(relixText);
        // A query is not a declaration: it names a result, and installing it would make
        // every later analysis re-root it. The declarations go in; the queries come back
        // as relations.
        install(new Script(parsed.namespace(), parsed.statements().stream()
                .filter(st -> !(st instanceof QueryStatement))
                .toList()));
        SemanticModel pinned = analyse(new Script(namespace, withQueries(parsed)));
        return relationsFor(parsed, pinned);
    }

    /**
     * One relation per {@code query} statement, labelled as the script names it.
     *
     * <p>The label is the name for a {@code query Open;} and a positional
     * {@code <expression N>} for an unnamed one — the same rule the engine's own event
     * feed uses, so a rendered heading and a traced event agree about what to call a
     * result.
     */
    private List<Relation> relationsFor(Script parsed, SemanticModel pinned) {
        List<Relation> relations = new ArrayList<>();
        int index = 0;
        for (Statement statement : parsed.statements()) {
            if (!(statement instanceof QueryStatement query)) {
                continue;
            }
            index++;
            String label = query.target() instanceof NamedQueryTarget named
                    ? named.name()
                    : "<expression " + index + ">";
            RelNode expression = expressionOf(query);
            // A `query Name;` statement carries no expression node, so the one minted
            // for it here was never walked by the analyser and has no inferred heading.
            // Annotating it against the model the statement was analysed in is the same
            // step a combinator takes for the node it just built.
            relations.add(new Relation(this, annotated(pinned, expression), expression, label));
        }
        return List.copyOf(relations);
    }

    /**
     * The relation named by, or written as, {@code expression}.
     *
     * <p>Takes a relational expression in either spelling the language accepts —
     * {@code "Open"}, {@code "σ status = 'OPEN' (Orders)"},
     * {@code "SELECT status = 'OPEN' (Orders)"} — and resolves it against everything
     * declared so far.
     *
     * @param expression the relational expression; must not be null
     * @return the relation, pinned to the session's current analysis
     * @throws RelixException if the expression does not parse or does not analyse
     * @since 1.0
     */
    public Relation relation(String expression) {
        Objects.requireNonNull(expression, "expression");
        Script wrapped = parse("query { " + expression + " };");
        List<Statement> parsedStatements = wrapped.statements();
        if (parsedStatements.size() != 1
                || !(parsedStatements.getFirst() instanceof QueryStatement query)) {
            throw new RelixException(
                    "not a single relational expression: " + expression);
        }
        // Analysed against the session as it stands, then pinned: this relation keeps
        // this model even if the session is redefined afterwards.
        SemanticModel pinned = analyseWith(query);
        return new Relation(this, pinned, expressionOf(query));
    }

    /**
     * A relation over an already-built expression tree, for a caller composing with
     * {@code AstBuilders}/{@code Expr} rather than with text.
     *
     * @param node the expression; must not be null
     * @return the relation, pinned to the session's current analysis
     * @throws RelixException if the expression does not analyse
     * @since 1.0
     */
    public Relation relation(RelNode node) {
        Objects.requireNonNull(node, "node");
        QueryStatement query = new QueryStatement(
                new ExpressionQueryTarget(node), com.darkcollective.relix.ast.SourceLocation.UNKNOWN);
        return new Relation(this, analyseWith(query), node);
    }

    // -------------------------------------------------------------------------
    // Java data in
    // -------------------------------------------------------------------------

    /**
     * Declares a relation from rows the program already holds, under a stated heading.
     *
     * <p>"Here is my in-memory data, joined against my database table" is one of the
     * reasons to embed a query engine at all, and this is the small end of it: reference
     * data, a lookup table, fixtures, a result computed elsewhere.
     *
     * <p><strong>The columns are the heading, in the order given.</strong> Each row is
     * read by name, so the maps need no order of their own and {@code Map.of} is safe
     * here. A column a row does not mention is NULL for that row — which is how an
     * optional column is expressed — and a key naming no column is refused rather than
     * silently dropped.
     *
     * <p><strong>Types are inferred, not declared.</strong> This becomes an inline table
     * in the session, exactly as the same data written in a script would, so a column of
     * numbers types as {@code NUMBER} and everything else as {@code STRING}. Where the
     * types matter — a real {@code TIMESTAMP}, a large or lazily-produced relation —
     * declare a source instead.
     *
     * @param name    the relation name; must not be blank
     * @param columns the heading, in order; must not be null or empty, and must not name
     *                a column twice
     * @param rows    the rows, each a column-name to value map; must not be null, and may
     *                be empty since the heading is stated
     * @return this session, for chaining
     * @throws RelixException if the columns are empty or repeat a name, if a row names a
     *                        column the heading does not, or if the result does not analyse
     *
     * @since 1.0
     */
    public Relix table(String name, List<String> columns, List<? extends Map<String, ?>> rows) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(columns, "columns");
        Objects.requireNonNull(rows, "rows");
        if (columns.isEmpty()) {
            throw new RelixException("table '" + name + "' needs at least one column");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String column : columns) {
            // Case-insensitively, because that is how every column lookup resolves: two
            // columns differing only in case would make a reference to either ambiguous.
            if (!seen.add(column.toLowerCase(Locale.ROOT))) {
                throw new RelixException("table '" + name + "' names the column '" + column
                        + "' twice; a heading names each column once");
            }
        }
        List<List<String>> cells = new ArrayList<>();
        for (Map<String, ?> row : rows) {
            for (String key : row.keySet()) {
                if (!seen.contains(key.toLowerCase(Locale.ROOT))) {
                    throw new RelixException("row " + (cells.size() + 1) + " of table '" + name
                            + "' has a column '" + key + "', which the heading " + columns
                            + " does not name");
                }
            }
            // An absent key is NULL rather than an error: with the heading stated, a row
            // that does not mention a column is a row without a value for it.
            cells.add(columns.stream().map(column -> cell(row.get(column))).toList());
        }
        return install(new Script(namespace, List.of(
                ScriptBuilders.assign(name, new MarkdownInlineTable(List.copyOf(columns), cells)))));
    }

    /**
     * Declares a relation from rows the program already holds, taking the heading from
     * the first row.
     *
     * <p>{@link #table(String, List, List)} for a caller whose rows already carry their
     * order — the short form, and the one to reach for when the maps are built rather
     * than written out.
     *
     * <p><strong>The first row's map has to define an order for its keys</strong>, since
     * that order becomes the relation's heading. A {@code LinkedHashMap} does;
     * {@code Map.of} and {@code HashMap} do not, and {@code Map.of}'s iteration order is
     * randomised per JVM, so a heading taken from one would differ between runs of the
     * same program. A row of more than one entry that cannot say what its order is, is
     * therefore refused rather than accepted arbitrarily — state the columns instead.
     *
     * <p>Every row must carry the same columns: a relation has one heading, so a row that
     * disagrees is a mistake rather than a sparse row. Values are converted with
     * {@code Expr.lit}, so a {@code Long}, an {@code Instant} or a {@code BigDecimal} may
     * be passed directly.
     *
     * <p><strong>Types are inferred, not declared</strong> — see
     * {@link #table(String, List, List)}.
     *
     * @param name the relation name; must not be blank
     * @param rows the rows, each a column-name to value map; must not be null or empty
     * @return this session, for chaining
     * @throws RelixException if the first row cannot say what its column order is, if the
     *                        rows disagree about their columns, or if the result does not
     *                        analyse
     *
     * @since 1.0
     */
    public Relix table(String name, List<? extends Map<String, ?>> rows) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(rows, "rows");
        if (rows.isEmpty()) {
            throw new RelixException("table '" + name + "' has no rows, so there is nothing "
                    + "to take a heading from; state the columns, or declare a source");
        }
        List<String> headers = heading(name, rows.getFirst());
        Set<String> expected = new LinkedHashSet<>(headers);
        int number = 0;
        for (Map<String, ?> row : rows) {
            number++;
            Set<String> keys = new LinkedHashSet<>(row.keySet());
            if (!keys.equals(expected)) {
                throw new RelixException("row " + number + " of table '" + name
                        + "' has columns " + keys + ", but the first row has " + headers
                        + " — a relation has one heading");
            }
        }
        return table(name, headers, rows);
    }

    /**
     * The first row's keys as a heading, or a refusal.
     *
     * <p>The order of a {@code Map}'s keys is only a fact about some maps. Taking it from
     * one that has none is not merely unspecified, it is unstable: {@code Map.of}
     * randomises its iteration order per JVM, so the same program declares a differently
     * ordered relation on each run — and heading order is load-bearing for positional ρ,
     * the whole-row set operations, and anything that prints or reads a row by position.
     *
     * <p>A single-entry row is exempt, because one key is in only one order. That is the
     * shape a lookup table is naturally written in, and refusing it would be refusing
     * something that cannot be wrong.
     */
    private static List<String> heading(String name, Map<String, ?> first) {
        if (first.size() > 1 && !(first instanceof SequencedMap)) {
            throw new RelixException("the first row of table '" + name + "' is a "
                    + first.getClass().getSimpleName() + ", whose keys have no defined order, "
                    + "so the heading would differ between runs of the same program — pass a "
                    + "LinkedHashMap, or state the heading with table(name, columns, rows)");
        }
        return List.copyOf(first.keySet());
    }

    /**
     * Installs a library of the caller's own functions, discoverable to every relation
     * the session analyses afterwards.
     *
     * <p>The function SPI's argument is that anything the shipped library can do a third
     * party can do; needing a {@code META-INF/services} entry to exercise that inside
     * one's own program would be a gap in the claim rather than a feature of it.
     *
     * <p>Installed libraries are consulted <em>before</em> the discovered ones, so a name
     * declared here wins.
     *
     * @param library the library; must not be null
     * @return this session, for chaining
     * @since 1.0
     */
    public Relix functions(FunctionLibrary library) {
        Objects.requireNonNull(library, "library");
        installedLibraries.add(library);
        rebuildAnalyzer();
        return this;
    }

    /**
     * Declares a relation whose rows the program produces on demand.
     *
     * <p>The lazy counterpart to {@link #table}: where that one takes rows already in
     * memory and infers their types, this takes a <strong>declared heading</strong> and a
     * supplier called once per scan — which is what anything large, expensive or
     * genuinely streaming needs. A column typed {@code TIMESTAMP} here is a
     * {@code TIMESTAMP}, where an inline table could only have made it a string.
     *
     * <p>Build the rows with {@code ArrayRow.of(schema, values)}, in the heading's column
     * order. The stream is closed by the engine, so a supplier holding a resource should
     * release it through {@code Stream.onClose}.
     *
     * <p>The relation is <em>finite</em> as far as the engine is concerned, so the
     * collecting terminals will read it to the end; a supplier whose stream never
     * terminates should be consumed with {@link Relation#stream()}.
     *
     * @param name   the relation name; must not be null
     * @param schema the heading every produced row carries; must not be null
     * @param rows   called once per scan, producing that scan's rows; must not be null
     * @return this session, for chaining
     * @throws RelixException if the name is already taken, or the result does not analyse
     * @since 1.0
     */
    public Relix source(String name, Schema schema, Supplier<Stream<Row>> rows) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(rows, "rows");
        // A code-backed leaf relation is what a generator already is, and the registry is
        // the one directory both halves of the pipeline read: the analyser resolves the
        // heading through it and the connector produces the rows from it. Registering
        // here is therefore the whole of the wiring — no second seam, and no way for the
        // heading analysis saw to differ from the heading execution produces.
        try {
            generators.register(new SuppliedRows(name, schema, rows));
        } catch (IllegalArgumentException e) {
            throw new RelixException("cannot declare source '" + name + "': " + e.getMessage(), e);
        }
        return install(new Script(namespace, List.of(ScriptBuilders.source(
                name, ScriptBuilders.generatorSource(name, Map.of())))));
    }

    /**
     * Declares a relation from the rows another relation produces, computed now.
     *
     * <p>The step a multi-stage program otherwise writes by hand: compute something
     * expensive once, give it a name, and go on querying it like anything else the
     * session declares. What is registered carries the relation's <strong>own</strong>
     * heading, so a {@code TIMESTAMP} column is still a {@code TIMESTAMP} — which is the
     * difference between this and draining the rows into maps and handing them to
     * {@link #table}, where the types would be inferred back to {@code NUMBER} or
     * {@code STRING}.
     *
     * <p><strong>This is the one method on a session that executes.</strong> Everywhere
     * else, building a relation runs nothing and a declaration is a declaration; this one
     * drains its argument before it returns, because computing once and reusing the
     * result is the whole point of it. A relation that provably never ends is refused for
     * the reason {@link Relation#toList()} refuses one.
     *
     * <p>Nothing is written anywhere. The rows land in this session's memory and nowhere
     * else, and a session that has gone out of scope has taken them with it.
     *
     * @param name     the relation name; must not be null, and must not already be taken
     * @param relation the relation to compute; must not be null
     * @return this session, for chaining
     * @throws RelixException if the name is already taken, if the relation cannot be
     *                        executed, or if the result does not analyse
     * @throws UnboundedRelationException if the relation is provably unbounded, since
     *         collecting one would never return
     *
     * @since 1.0
     */
    public Relix materialize(String name, Relation relation) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(relation, "relation");
        // run() rather than toList(): it answers the rows and the heading they were
        // produced under from one execution, and a heading read separately is a second
        // statement of the same thing that nothing checks.
        Rows result = relation.run();
        Schema heading = result.schema();
        // Rebuilt against that heading rather than passed through as they are. A
        // generator's rows are read against the schema the registry answers with, so a
        // row carrying a schema of its own is a disagreement waiting to happen; the copy
        // is paid once, here, and not once per scan.
        List<Row> rows = result.rows().stream()
                .map(row -> (Row) ArrayRow.of(heading, values(row)))
                .toList();
        return source(name, heading, rows::stream);
    }

    /** One row's values, in its own column order — which is the heading's. */
    private static List<Value> values(Tuple row) {
        List<Value> values = new ArrayList<>(row.width());
        for (int i = 0; i < row.width(); i++) {
            values.add(row.get(i));
        }
        return values;
    }

    /**
     * Installs a connector of the caller's own, dispatched to by the type tokens it
     * {@link RelixConnector#handles() handles}.
     *
     * <p>A backend the program already knows how to read, reached without a
     * {@code META-INF/services} declaration — the same argument {@link #functions} makes:
     * the connector SPI's claim is that anything a shipped connector can do a third party
     * can do, so needing a service declaration to exercise it inside one's own program
     * would be a gap in the claim rather than a feature of it.
     *
     * <p>Declare a {@code connection} of the handled type and the session's sources over
     * it route here. An installed connector is consulted <em>before</em> the discovered
     * ones, so it wins a token a shipped connector also claims.
     *
     * <p>It is <strong>not</strong> closed with the session. The connector is the
     * caller's, exactly as a bound {@link DataSource} is.
     *
     * @param connector the connector; must not be null
     * @return this session, for chaining
     * @since 1.0
     */
    public Relix connector(RelixConnector connector) {
        installedConnectors.add(Objects.requireNonNull(connector, "connector"));
        return this;
    }

    /**
     * A generator over rows a program supplies — the mechanism behind
     * {@link Relix#source}.
     *
     * <p>Every capability a generator may declare is left at its default, and each
     * default is the right reading of caller-supplied rows: finite (so a collecting
     * terminal may read it), not known duplicate-free, not ascending in any column, and
     * of unknown cardinality. A caller who knows better states it in the query.
     */
    private record SuppliedRows(String name, Schema heading, Supplier<Stream<Row>> rows)
            implements com.darkcollective.relix.processor.generator.Generator {

        @Override
        public Schema schema(Map<String, String> args) {
            return heading;
        }

        @Override
        public Stream<Row> rows(Map<String, String> args, Schema schema) {
            Stream<Row> produced = rows.get();
            if (produced == null) {
                throw new RelixException("the supplier for source '" + name + "' produced no stream");
            }
            return produced;
        }
    }

    /**
     * One cell of an inline table: the value as the language spells it.
     *
     * <p>An inline table's cells are raw text — the analyser re-reads them to infer each
     * column's type — so a literal has to be rendered rather than {@code toString}ed,
     * which on a record gives {@code NumberOperand[value=9.99]}.
     *
     * <p>An empty cell is the language's null marker, which is why {@code null} maps to
     * one rather than to the string {@code "null"}.
     */
    private static String cell(Object value) {
        if (value == null) {
            return "";
        }
        return switch (Expr.lit(value)) {
            case com.darkcollective.relix.ast.StringOperand o -> o.value();
            case com.darkcollective.relix.ast.NumberOperand o -> o.value();
            case com.darkcollective.relix.ast.BooleanOperand o -> String.valueOf(o.value());
            case com.darkcollective.relix.ast.DateOperand o -> o.value().toString();
            case com.darkcollective.relix.ast.TimeOperand o -> o.value().toString();
            case com.darkcollective.relix.ast.TimestampOperand o -> o.value().toString();
            case com.darkcollective.relix.ast.DurationOperand o -> o.value().toString();
            case Operand o -> throw new RelixException(
                    "no inline-table cell for a " + o.getClass().getSimpleName()
                            + " — an inline table holds literals, not expressions");
        };
    }

    // -------------------------------------------------------------------------
    // Inspecting the session
    // -------------------------------------------------------------------------

    /**
     * The analysis of everything declared so far.
     *
     * <p>Cached until the next {@link #define}, so repeated calls on an unchanged session
     * return the same model. A {@link Relation}'s own model is <em>not</em> this one: it
     * additionally carries the schema annotations for that relation's expression, so
     * every relation has its own.
     *
     * @return the semantic model; never null
     * @throws RelixException if what is declared does not analyse
     * @since 1.0
     */
    public SemanticModel model() {
        if (cachedModel == null) {
            cachedModel = analyse(new Script(namespace, List.copyOf(statements)));
        }
        return cachedModel;
    }

    /**
     * The session's intermediate representation — every symbol, its heading, and the
     * expression tree behind each view.
     *
     * <p>The counterpart to {@link #definitions()} for reading rather than round-tripping:
     * that one renders what was declared, this one renders what the analyser made of it,
     * which is where an inferred heading or a resolved reference becomes visible.
     *
     * @return the IR report
     * @throws RelixException if what is declared does not analyse
     * @since 1.0
     */
    public String ir() {
        return IrReport.generate(model());
    }

    /**
     * Introspects every connection-backed table this session names, and returns what
     * comes back as a snapshot that can be written to a file and replayed later.
     *
     * <p>The one call in this class that reaches a database on purpose. Everything else
     * either runs a query or touches nothing; this asks each declared connection to
     * describe its tables, so that a later session with nothing reachable can still
     * <em>cost</em> a query rather than merely compose one.
     *
     * <p>Replay it by handing it back through {@link Builder#catalog}: a snapshot is a
     * {@code CatalogProvider}, so it goes exactly where the live one went.
     *
     * {@snippet lang = "java":
     * Files.writeString(path, live.captureCatalog().toJson());
     * // ... elsewhere, with no database in reach:
     * Relix offline = Relix.builder()
     *         .catalog(CatalogSnapshot.parse(Files.readString(path)))
     *         .build();
     * }
     *
     * @return the snapshot, empty when nothing could be introspected
     * @throws RelixException if the session is closed, or what is declared does not analyse
     * @since 1.0
     */
    public CatalogSnapshot captureCatalog() {
        requireOpen();
        SemanticModel current = model();
        CatalogSnapshot snapshot = CatalogSnapshot.capture(catalog, current, clock);
        // What this session's runs measured, recorded beside what the catalog claimed.
        // The snapshot keeps both rather than merging them, and prefers the measured one
        // field by field — so a real row count travels to the next offline session while
        // the introspected heading it arrived with is still there.
        java.time.Instant now = clock.instant();
        for (var source : current.sources().entrySet()) {
            Long rows = observedRows.get(source.getKey());
            if (rows == null
                    || !(source.getValue().config() instanceof ConnectionTableSourceConfig table)) {
                continue;
            }
            snapshot = snapshot.with(new CatalogSnapshot.Entry(
                    table.connection(), table.table(), Optional.empty(),
                    Optional.of(RelationStatistics.of(rows)),
                    CatalogSnapshot.Origin.OBSERVED, now));
        }
        return snapshot;
    }

    /**
     * The session's declarations, rendered back as {@code .relix} text.
     *
     * <p>Round-trips: the text parses back to an equal script, which is what makes a
     * session something a program can save, diff or hand to someone else. Formatting is
     * normalised rather than preserved — comments are not in the AST, so no printer can
     * return them.
     *
     * @return the declarations as text; empty when nothing is declared
     * @since 1.0
     */
    public String definitions() {
        return ScriptPrinter.print(new Script(namespace, List.copyOf(statements)));
    }

    /**
     * The statements declared so far, in declaration order.
     *
     * @return an immutable snapshot
     * @since 1.0
     */
    public List<Statement> statements() {
        return List.copyOf(statements);
    }

    /**
     * Analyses {@code relixText} against the session <em>without</em> installing it, and
     * reports what is wrong as data.
     *
     * <p>The counterpart to {@link #define} for a caller assembling text or trees from
     * user input, where an error is an expected outcome to render rather than a bug to
     * raise. Everything else in this class throws.
     *
     * @param relixText the text to check; must not be null
     * @return the diagnostics, empty when it analyses cleanly
     * @since 1.0
     */
    public List<Diagnostic> validate(String relixText) {
        Script parsed;
        try {
            parsed = parse(relixText);
        } catch (RelixException e) {
            return List.of(syntaxDiagnostic(e));
        }
        List<Statement> combined = new ArrayList<>(statements);
        combined.addAll(parsed.statements());
        SemanticResult result = analyzed(new Script(namespace, combined));
        return result.errors().stream().map(Diagnostic::of).toList();
    }

    /**
     * How many row streams this session has started and not seen closed.
     *
     * <p>Zero between executions is the healthy state. A lazy stream owns the connector
     * it reads through — and with it any pooled JDBC connection — so a caller who
     * abandons one holds that connection until the session closes; this is the number
     * that says so, and the only thing that can, since nothing else in a running program
     * can see a stream nobody kept.
     *
     * <p>It counts executions rather than connections, so a stream over an inline table
     * counts too: leaving one unclosed is the same mistake and shows up here before a
     * database is added to the query. {@link Relation#toList()}-shaped terminals never
     * contribute — they close what they drain — so a non-zero reading is always a
     * {@link Relation#stream()} result that got away.
     *
     * {@snippet lang = "java":
     * try (Relix relix = Relix.builder().build()) {
     *     // … the program's work …
     *     assert relix.openStreams() == 0;   // no stream got away
     * }
     * }
     *
     * @return the number of unclosed row streams; never negative
     * @since 1.0
     */
    public int openStreams() {
        return liveStreams.size();
    }

    /**
     * Releases what the session holds. Bound {@link DataSource}s are the caller's, and
     * are not closed.
     *
     * <p>A row stream the caller never closed is closed here rather than left: its
     * connector holds a borrowed connection, and a borrowed connection is exactly what
     * closing the pool does not reach. How many there were is logged as a warning —
     * {@link #openStreams()} is the same fact while the session is still open.
     *
     * @since 1.0
     */
    @Override
    public void close() {
        closed = true;
        int abandoned = releaseAbandoned();
        // The pool and the default catalog are the resources the session owns: the pool
        // holds idle JDBC connections opened on its behalf, and the catalog holds the
        // connector registry it introspects through. A bound DataSource is the caller's
        // handle, and so is a catalog they supplied — closing either would take away
        // something the caller may still be using elsewhere.
        pool.close();
        if (ownedCatalog != null) {
            ownedCatalog.close();
        }
        if (abandoned > 0) {
            LOG.log(System.Logger.Level.WARNING,
                    abandoned + " row stream(s) were never closed; each held its connector, and any "
                    + "pooled connection it was reading through, for the life of this session. "
                    + "Close what stream() returns — try-with-resources — or use toList(), "
                    + "which drains and closes for you.");
        }
    }

    /**
     * Closes the row streams this session handed out and nobody closed, returning how
     * many there were.
     *
     * <p>A stream the caller abandoned is a leak the session can still repair, because
     * the stream is reachable from here: closing it releases the connection back to the
     * pool, which is then closed with everything else. Before this the connection was
     * simply lost — {@code ConnectionPool.close} closes what is <em>idle</em>, and a
     * borrowed connection is by definition not, so it outlived the session entirely.
     *
     * <p>Safe at this point and only at this point: the session is already marked closed,
     * so anything still reading would fail at its next terminal anyway.
     */
    private int releaseAbandoned() {
        List<StreamHandle> abandoned = List.copyOf(liveStreams);
        abandoned.forEach(handle -> {
            try {
                handle.close();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "closing an abandoned row stream failed", e);
            }
        });
        liveStreams.clear();
        return abandoned.size();
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /** The clock this session's {@code NOW()} reads. */
    Clock clock() {
        return clock;
    }

    /** The expression-keyed counts this session's runs have measured. */
    ObservedCardinalities observedExpressions() {
        return observedExpressions;
    }

    /**
     * Records that a full scan of {@code relation} produced {@code rows} rows.
     *
     * <p>Called only for a scan the executor read to the end — see the scan event it is
     * folded from. A relation scanned again later simply overwrites: the newer
     * measurement is the better one, which is the same preference a snapshot applies
     * between an observed entry and an introspected one.
     */
    void observeRows(String relation, long rows) {
        observedRows.put(relation.toLowerCase(Locale.ROOT), rows);
    }

    /**
     * The model's statistics with this session's measurements folded in.
     *
     * <p>A measured row count replaces an estimated one and <strong>keeps everything
     * else</strong> — the candidate keys and per-column counts the catalog supplied are
     * not facts a row count supersedes. That is the same field-wise preference a catalog
     * snapshot applies between an observed entry and an introspected one, applied here
     * to the statistics one analysis already produced.
     */
    Map<String, RelationStatistics> statisticsWithObservations(
            Map<String, RelationStatistics> analysed) {
        if (observedRows.isEmpty()) {
            return analysed;
        }
        Map<String, RelationStatistics> merged = new java.util.LinkedHashMap<>(analysed);
        observedRows.forEach((relation, rows) -> {
            RelationStatistics existing = merged.get(relation);
            merged.put(relation, existing == null
                    ? RelationStatistics.of(rows)
                    : new RelationStatistics(java.util.OptionalLong.of(rows),
                            existing.columnStatistics(), existing.keys()));
        });
        return merged;
    }

    /** The cap on {@code FIX} iterations this session runs under. */
    int maxFixpointRounds() {
        return maxFixpointRounds;
    }

    /** The cap on what one blocking operator may buffer in this session. */
    int maxMaterializedRows() {
        return maxMaterializedRows;
    }

    /** The generators, as both a schema catalog and a row producer. */
    GeneratorRegistry generators() {
        return generators;
    }

    /**
     * Opens the connector stack for one execution of {@code model}.
     *
     * <p>Per execution because it is built over the relation's own pinned model — the
     * source declarations it reads are that model's. What is <em>not</em> per execution is
     * the connection pool: it is the session's, so a run leaves the JDBC connections it
     * borrowed available to the next one, and closing this connector does not close them.
     *
     * <p>Neither provisioner downloads unless the session was built with ones that do
     * ({@link Builder#provisioners}). A library call that reached out to the network and
     * wrote a jar into the user's home directory would be a surprise an embedder cannot
     * see coming; a driver an embedded program needs is a dependency of that program, and
     * an application that wants the fetching behaviour says so where it is visible.
     *
     * @param model the model whose sources are to be opened
     * @return the connector; the caller closes it
     */
    DataSourceConnector openConnector(SemanticModel model) {
        requireOpen();
        return new CompositeDataSourceConnector(model, baseDirectory,
                driverProvisioner, connectorProvisioner,
                generators, pool, List.copyOf(installedConnectors));
    }

    /**
     * Registers a row stream with this session, so one the caller never closes can still
     * be closed when the session is.
     *
     * <p>The handle exists because the deregistering close handler has to name the thing
     * it is attached to. {@code Stream.onClose} is specified to return an equivalent
     * stream rather than the same one, so what goes in the register is what
     * {@code onClose} handed back — and the caller is given that same stream, which is
     * what keeps the two in step whatever the implementation returns.
     *
     * @param rows the stream to register
     * @return the stream to hand the caller
     */
    <T> Stream<T> track(Stream<T> rows) {
        StreamHandle handle = new StreamHandle();
        liveStreams.add(handle);
        handle.stream = rows.onClose(() -> liveStreams.remove(handle));
        return handle.stream();
    }

    /**
     * One registered row stream. Identity-keyed: two streams over the same relation are
     * two streams, and a register that collapsed them would under-count the leak.
     */
    private static final class StreamHandle implements AutoCloseable {

        private Stream<?> stream;

        @SuppressWarnings("unchecked")
        <T> Stream<T> stream() {
            return (Stream<T>) stream;
        }

        @Override
        public void close() {
            Stream<?> s = stream;
            if (s != null) {   // registered but not yet published — nothing to close
                s.close();
            }
        }
    }

    /**
     * What this process has installed that the engine cannot see for itself.
     *
     * <p>Three of the four provider kinds live above the engine, and the fourth — a JDBC
     * driver — is behind a {@code java.sql} dependency no engine module is allowed to
     * have. This facade sits above all of them and is where they become visible, which is
     * why the seam is filled here rather than defaulted to nothing.
     *
     * <p>Read at analysis time rather than cached, because a driver can be registered
     * after a session is built and a report of what is loaded should say what is loaded.
     */
    private static List<ComponentInventory.Component> installedComponents() {
        List<ComponentInventory.Component> components = new ArrayList<>();
        components.add(ComponentInventory.Component.of(
                "relix-embed", ComponentInventory.FACADE, Relix.class));
        try (ConnectorRegistry connectors = ConnectorRegistry.create()) {
            connectors.types().stream().sorted().forEach(type ->
                    components.add(connectors.forType(type)
                            .map(c -> ComponentInventory.Component.of(
                                    type, ComponentInventory.CONNECTOR, c.getClass()))
                            .orElseThrow()));
        }
        SolverCatalog.installed().solvers().forEach(solver ->
                components.add(ComponentInventory.Component.of(
                        solver.name(), ComponentInventory.SOLVER, solver.getClass())));
        DriverManager.drivers().forEach(driver ->
                components.add(new ComponentInventory.Component(
                        driver.getClass().getName(), ComponentInventory.DRIVER,
                        driver.getMajorVersion() + "." + driver.getMinorVersion())));
        return components;
    }

    /** Fails if the session has been closed — its pool is gone, so nothing can run. */
    void requireOpen() {
        if (closed) {
            throw new RelixException("this session is closed");
        }
    }

    /**
     * Rebuilds the analyser over the currently installed libraries.
     *
     * <p>A catalogue is fixed at construction, so installing a library means a new one —
     * and a new analyser. Discovery happens once inside {@code FunctionCatalog}, and two
     * independently-populated catalogues can disagree about what a name means, so the
     * installed libraries are merged with the discovered ones rather than replacing them.
     */
    private void rebuildAnalyzer() {
        FunctionCatalog functions = installedLibraries.isEmpty()
                ? FunctionCatalog.discover()
                : FunctionCatalog.discoverWith(installedLibraries);
        this.analyzer = new SemanticAnalyzer(
                scriptLoader, BuiltinProvider.none(), catalog, generators, functions)
                .withSupplementalRelationships(relationships)
                .withSessionEvents(sessionEvents)
                .withComponents(Relix::installedComponents);
        this.cachedModel = null;
    }

    private Relix install(Script parsed) {
        List<Statement> combined = new ArrayList<>(statements);
        combined.addAll(parsed.statements());
        Optional<String> combinedNamespace = parsed.namespace().or(() -> namespace);

        // Analyse before accepting: a session that has taken text it cannot analyse is
        // one where every later call fails for a reason that is no longer on screen.
        cachedModel = analyse(new Script(combinedNamespace, combined));
        statements.clear();
        statements.addAll(combined);
        namespace = combinedNamespace;
        return this;
    }

    /** The session's declarations plus every query the given script carries. */
    private List<Statement> withQueries(Script parsed) {
        List<Statement> combined = new ArrayList<>(statements);
        parsed.statements().stream().filter(QueryStatement.class::isInstance).forEach(combined::add);
        return combined;
    }

    /**
     * {@code model} with {@code node}'s heading inferred, if it does not already carry
     * one.
     *
     * <p>Cheap where it is a no-op: annotation walks the node, and a node the analyser
     * already reached is left exactly as it was.
     */
    private static SemanticModel annotated(SemanticModel model, RelNode node) {
        if (model.nodeSchemas().get(node).isPresent()) {
            return model;
        }
        return new SemanticModel(
                model.namespace(), model.symbolTable(), model.sources(), model.connections(),
                model.statistics(),
                SchemaInference.annotate(model.symbolTable(), node,
                        model.nodeSchemas(), model.functions()),
                model.schemaGraph(), model.rootQueries(), model.functions());
    }

    /** Analyses the session plus one query statement, returning the pinned model. */
    private SemanticModel analyseWith(QueryStatement query) {
        List<Statement> combined = new ArrayList<>(statements);
        combined.add(query);
        return analyse(new Script(namespace, combined));
    }

    private SemanticModel analyse(Script script) {
        SemanticResult result = analyzed(script);
        // No model at all is fatal in either mode: there is nothing to hand back.
        if (result.hasModel() && (allowUnresolved || !result.hasErrors())) {
            return result.model().orElseThrow();
        }
        throw new RelixException(Diagnostic.describe(result.errors()));
    }

    /**
     * Runs the analyser, reporting a frontend's parse failure as this API's own.
     *
     * <p>Not every parse happens in {@link #parse}: an {@code import} is read and parsed
     * by the {@code ScriptLoader} part-way through analysis, so a script whose *import*
     * does not parse fails here rather than at the front door. That is still the grammar
     * failing to produce a tree, which is what {@link RelixException} means — and letting
     * it escape as itself would leave a caller unable to tell a malformed script from a
     * defect in the engine.
     */
    private SemanticResult analyzed(Script script) {
        try {
            return analyzer.analyze(script, SESSION_PATH);
        } catch (ScriptParseException e) {
            throw new RelixException(e.getMessage(), e);
        }
    }

    /**
     * A refused parse as a diagnostic, keeping the position the frontend reported.
     *
     * <p>This is the one method here whose whole purpose is text the program did not
     * write, so where the failure is is as much of the answer as what it is. The grammar
     * knows: a {@code ScriptParseException} carries the line and column of the offending
     * token, and {@link #parse} keeps it as the cause when it wraps it in the type this
     * API raises. Reading it back is what stops a caller rendering diagnostics from having
     * to find the position again inside the message text — where it is, and where the only
     * way to get it out is to parse English.
     *
     * <p>A position of zero means the frontend had none to give, which a builder-produced
     * {@code Script} genuinely does not, so that stays an unplaced diagnostic.
     */
    private static Diagnostic syntaxDiagnostic(RelixException raised) {
        if (raised.getCause() instanceof ScriptParseException parse && parse.line() > 0) {
            return Diagnostic.of(raised.getMessage(),
                    new SourceLocation(SESSION_PATH, parse.line(), parse.column()));
        }
        return Diagnostic.of(raised.getMessage());
    }

    private static Script parse(String relixText) {
        Objects.requireNonNull(relixText, "relixText");
        try {
            return ScriptParser.parse(relixText, SESSION_PATH);
        } catch (RuntimeException e) {
            throw new RelixException(e.getMessage(), e);
        }
    }

    private static RelNode expressionOf(QueryStatement query) {
        return switch (query.target()) {
            case ExpressionQueryTarget t -> t.expression();
            case NamedQueryTarget t -> com.darkcollective.relix.ast.AstBuilders.rel(t.name());
        };
    }

    /**
     * Assembles a {@link Relix} session.
     *
     * <p>Deliberately short. A knob here is one every embedder has to read past, so the
     * bar is that it cannot be expressed as a declaration or a relation.
     *
     * @since 1.0
     */
    public static final class Builder {

        private final DataSourceRegistry dataSources = new DataSourceRegistry();
        private final Map<String, DataSource> connections = new java.util.LinkedHashMap<>();
        private CatalogProvider catalog;
        private Clock clock = Clock.systemUTC();
        private Path baseDirectory = Path.of("");
        private boolean allowUnresolved;
        private int maxFixpointRounds = ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS;
        private int maxMaterializedRows = ExecutionContext.DEFAULT_MAX_MATERIALIZED_ROWS;
        private ScriptLoader scriptLoader = new InMemoryScriptLoader(Map.of());
        private SchemaGraph relationships = SchemaGraph.EMPTY;
        private List<QueryEvent> sessionEvents = List.of();
        private DriverProvisioner driverProvisioner = DriverProvisioner.create(false);
        private ConnectorProvisioner connectorProvisioner =
                ConnectorProvisioner.create(false, HttpFetcher.https());
        private final List<FunctionLibrary> libraries = new ArrayList<>();
        private final List<RelixConnector> connectors = new ArrayList<>();

        private Builder() {
        }

        /**
         * Binds a connection name to a live handle, so a script may reference its tables
         * by dotted name without declaring a URL.
         *
         * @param name       the connection name; must not be blank
         * @param dataSource the live handle; must not be null
         * @return this builder
         * @since 1.0
         */
        public Builder jdbc(String name, DataSource dataSource) {
            dataSources.register(name, dataSource);
            connections.put(name, dataSource);
            return this;
        }

        /**
         * Binds a connection name to a live handle with an explicit dialect, skipping the
         * metadata probe.
         *
         * @param name       the connection name; must not be blank
         * @param dataSource the live handle; must not be null
         * @param dialect    the dialect token (e.g. {@code "postgres"}); must not be null
         * @return this builder
         * @since 1.0
         */
        public Builder jdbc(String name, DataSource dataSource, String dialect) {
            dataSources.register(name, dataSource, dialect);
            connections.put(name, dataSource);
            return this;
        }

        /**
         * Supplies table schemas and statistics, replacing live introspection.
         *
         * @param catalog the catalog provider; must not be null
         * @return this builder
         * @since 1.0
         */
        public Builder catalog(CatalogProvider catalog) {
            this.catalog = Objects.requireNonNull(catalog, "catalog");
            return this;
        }

        /**
         * Sets the directory a source's relative path resolves against.
         *
         * <p>{@code source Orders from csv("./orders.csv")} names a file relative to
         * something, and in a script that something is the script's own directory. An
         * embedded session has no script, so it is this — the working directory unless
         * said otherwise, which is what a program run from its own root expects.
         *
         * @param directory the base directory; must not be null
         * @return this builder
         * @since 1.0
         */
        public Builder baseDirectory(Path directory) {
            this.baseDirectory = Objects.requireNonNull(directory, "directory");
            return this;
        }

        /**
         * Fixes the clock the session's temporal functions read, so {@code NOW()} is
         * reproducible.
         *
         * @param clock the clock; must not be null
         * @return this builder
         * @since 1.0
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Installs a library of the caller's own functions before the session opens.
         *
         * @param library the library; must not be null
         * @return this builder
         * @since 1.0
         */
        public Builder functions(FunctionLibrary library) {
            libraries.add(Objects.requireNonNull(library, "library"));
            return this;
        }

        /**
         * Installs a connector of the caller's own, as {@link Relix#connector} does, for
         * a session declared in one expression.
         *
         * @param connector the connector; must not be null
         * @return this builder, for chaining
         * @since 1.0
         */
        public Builder connector(RelixConnector connector) {
            connectors.add(Objects.requireNonNull(connector, "connector"));
            return this;
        }

        /**
         * Accepts relations whose names the analyser could not resolve, instead of
         * rejecting them.
         *
         * <p>What makes offline work <em>full-strength</em> for a reference that needs a
         * catalog: {@code warehouse.orders} composes, renders and optimises with the
         * database unreachable, at the cost of the weaker plan an unknown schema implies.
         *
         * <p>Off by default, because the same signal is what a typo produces. Turning it
         * on trades an error at composition time for one at execution time, which is the
         * right trade only when composing is the whole intent — tuning a query in CI, on
         * a machine with no access to production.
         *
         * @return this builder
         */
        /**
         * Caps how many rounds a recursive {@code FIX} may iterate before the engine
         * stops it.
         *
         * <p>The safety valve for a recursion that does not converge — a counting
         * semiring over a cyclic graph is the standard case, where the fixpoint is
         * genuinely infinite rather than merely slow. Unbounded by default, because a
         * cap that fires is an answer that is silently incomplete and that should be a
         * thing a caller asked for.
         *
         * @param rounds the maximum iterations; must be at least 1
         * @return this builder, for chaining
         * @since 1.0
         */
        public Builder maxFixpointRounds(int rounds) {
            if (rounds < 1) {
                throw new IllegalArgumentException("maxFixpointRounds must be >= 1: " + rounds);
            }
            this.maxFixpointRounds = rounds;
            return this;
        }

        /**
         * Caps how many rows a single blocking operator may buffer before the engine
         * stops it.
         *
         * <p>A blocking operator — {@code γ}, {@code τ}, a deduplicating set operation,
         * {@code ÷}, a full outer or hash join's build side — cannot emit a row until it
         * has read its whole input, so it holds that input in the JVM heap. Nothing in
         * the plan says how large that is. {@code toList()} refuses an <em>unbounded</em>
         * relation, and so does the planner for a blocking operator over one, but a
         * bounded table can be far larger than the heap: the query then dies with an
         * {@code OutOfMemoryError} attributable to no operator in particular. Under a cap
         * the query stops instead with an error naming the operator that was buffering
         * and the limit it crossed — which is a thing a caller can act on.
         *
         * <p>The cap is per operator, not per session total — the shape
         * {@link #maxFixpointRounds(int)} has, which likewise bounds one fixpoint rather
         * than a run's total rounds. A running total would refuse a recursion that
         * buffers the same thousand rows each round while its peak memory never moves.
         * So a plan with many blocking operators can exceed it in aggregate while no
         * single operator does; what it converts is the failure where one operator
         * swallows a table.
         *
         * <p>Unbounded by default, because a cap that fires turns a slow answer into no
         * answer, and that should be a thing a caller asked for.
         *
         * @param rows the maximum rows one blocking operator may buffer; must be at
         *             least 1
         * @return this builder, for chaining
         * @since 1.0
         */
        public Builder maxMaterializedRows(int rows) {
            if (rows < 1) {
                throw new IllegalArgumentException("maxMaterializedRows must be >= 1: " + rows);
            }
            this.maxMaterializedRows = rows;
            return this;
        }

        /**
         * Supplies the provisioners that fetch a missing JDBC driver or connector plugin.
         *
         * <p>A session provisions <strong>nothing</strong> by default, and that is a
         * decision rather than an oversight: a library call that reached out to the
         * network and wrote a jar into the user's home directory would be a surprise an
         * embedder cannot see coming, and a driver an embedded program needs is a
         * dependency of that program.
         *
         * <p>An <em>application</em> is a different matter — it can ask, report progress
         * and be told no — so one that wants the behaviour states it here, which is where
         * the decision is visible.
         *
         * @param drivers    the JDBC driver provisioner; must not be null
         * @param connectors the connector-plugin provisioner; must not be null
         * @return this builder, for chaining
         * @since 1.0
         */
        public Builder provisioners(DriverProvisioner drivers, ConnectorProvisioner connectors) {
            this.driverProvisioner = Objects.requireNonNull(drivers, "drivers");
            this.connectorProvisioner = Objects.requireNonNull(connectors, "connectors");
            return this;
        }

        /**
         * Supplies where an {@code import} statement resolves from.
         *
         * <p>A session serves imports from nothing by default: it is assembled in memory
         * and has no file of its own, so there is no directory for a relative path to be
         * relative to. An application that reads scripts from disk supplies
         * {@code FileSystemScriptLoader}; one that holds them already can serve them from
         * a map, which is what the seam is for.
         *
         * @param loader the loader; must not be null
         * @return this builder, for chaining
         * @since 1.0
         */
        public Builder scriptLoader(ScriptLoader loader) {
            this.scriptLoader = Objects.requireNonNull(loader, "loader");
            return this;
        }

        /**
         * Supplies relationships beyond the ones the session declares.
         *
         * <p>Where an edge comes from that a {@code relate} statement did not: a host
         * that has <em>observed</em> one — two relations repeatedly joined on the same
         * columns — can offer it here, and the analyser resolves a later join the same
         * way it would resolve a declared one.
         *
         * <p>They are supplemental rather than authoritative: a declared edge wins, and
         * nothing here changes what a query means, only what the engine can infer when a
         * query does not say.
         *
         * @param graph the additional edges; must not be null
         * @return this builder, for chaining
         * @since 1.0
         */
        public Builder relationships(SchemaGraph graph) {
            this.relationships = Objects.requireNonNull(graph, "graph");
            return this;
        }

        /**
         * Supplies the extent of the {@code relix.events} catalog relation.
         *
         * <p>A statement cannot observe itself — its events postdate the analysis that
         * would have to register them — so what a query selecting from {@code relix.events}
         * sees is a feed some earlier run produced, and this is where a host that kept one
         * hands it back. A session given none correctly reports no events rather than
         * pretending to none.
         *
         * @param events the feed; must not be null
         * @return this builder, for chaining
         * @since 1.0
         */
        public Builder sessionEvents(List<QueryEvent> events) {
            this.sessionEvents = List.copyOf(Objects.requireNonNull(events, "events"));
            return this;
        }

        /**
         * Accepts relations whose names the analyser could not resolve, instead of
         * rejecting them.
         *
         * <p>What makes offline work <em>full-strength</em> for a reference that needs a
         * catalog: {@code warehouse.orders} composes, renders and optimises with the
         * database unreachable, at the cost of the weaker plan an unknown schema implies.
         *
         * <p>Off by default, because the same signal is what a typo produces. Turning it
         * on trades an error at composition time for one at execution time, which is the
         * right trade only when composing is the whole intent — tuning a query in CI, on
         * a machine with no access to production.
         *
         * @return this builder
         * @since 1.0
         */
        public Builder allowUnresolved() {
            this.allowUnresolved = true;
            return this;
        }

        /**
         * Builds the session.
         *
         * @return the session
         * @since 1.0
         */
        public Relix build() {
            return new Relix(this);
        }

    }
}

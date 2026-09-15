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
package com.darkcollective.relix.processor;

import com.darkcollective.relix.cost.BoundednessSource;
import com.darkcollective.relix.events.QueryEventListener;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.function.FunctionContext;
import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.processor.eval.OperandEvaluator;
import com.darkcollective.relix.processor.generator.GeneratorBoundednessSource;
import com.darkcollective.relix.processor.generator.GeneratorRegistry;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.RelationStatistics;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/**
 * Shared execution state threaded through all operators during query evaluation.
 *
 * <p>An {@code ExecutionContext} bundles the three things an operator may need
 * at runtime:
 * <ul>
 *   <li>{@link #symbolTable()} — used by {@code RelationExecutor} to resolve a
 *       relation name to its symbol (inline rows, query body, or external
 *       source).</li>
 *   <li>{@link #nodeSchemas()} — the per-node schema annotations produced by
 *       semantic analysis; used to construct {@link Row} objects with the
 *       correct column set when opening a source.</li>
 *   <li>{@link #connector()} — called only for
 *       {@link com.darkcollective.relix.symbol.relation.DatabaseRelationSymbol}
 *       and
 *       {@link com.darkcollective.relix.symbol.relation.SourceRelationSymbol}
 *       relations that require external I/O.  Inline relations are served
 *       directly from the symbol table without calling the connector.</li>
 * </ul>
 *
 * <h2>Construction</h2>
 * <p>Prefer the factory methods over the canonical record constructor:
 * <ul>
 *   <li>{@link #of(SemanticModel, DataSourceConnector)} — normal use; extracts
 *       the relevant fields from a validated {@link SemanticModel}.</li>
 *   <li>{@link #inlineOnly(SemanticModel)} — convenience for scripts that
 *       contain only inline relations; any attempt to open an external source
 *       throws {@link EvaluationException}.</li>
 * </ul>
 *
 * <h2>Precondition</h2>
 * <p>The executor assumes it receives a <em>fully valid</em>
 * {@link SemanticModel}.  Passing an unvalidated model (e.g.
 * {@code result.isFullyValid() == false}) may cause
 * {@link EvaluationException}s that should have been caught during semantic
 * analysis.
 *
 * @param symbolTable  the fully-populated symbol table; must not be {@code null}
 * @param nodeSchemas  per-node schema annotations from inference; must not be {@code null}
 * @param statistics   per-relation statistics for the planner's cost model; must not be {@code null}
 * @param sources      canonical name → source declaration, for SQL pushdown planning; must not be {@code null}
 * @param connections  canonical name → connection declaration, for SQL pushdown planning; must not be {@code null}
 * @param connector    the data-source connector for external relations; must not be {@code null}
 * @param listener           observer notified of execution-stage {@code QueryEvent}s
 *                           (e.g. a declarative-optimisation group skipped as
 *                           infeasible); {@link QueryEventListener#NONE} to ignore them;
 *                           must not be {@code null}
 * @param maxFixpointRounds  maximum number of semi-naïve fixpoint iterations before
 *                           aborting with an {@link EvaluationException}; use
 *                           {@link #UNLIMITED_FIXPOINT_ROUNDS} for no cap (the default);
 *                           must be &ge; 1
 * @param maxMaterializedRows maximum rows a single blocking operator (&gamma;, &tau;, a
 *                           deduplicating set operation, a hash join's build side, …) may
 *                           buffer before aborting with an {@link EvaluationException}
 *                           naming it. Bounds <em>one</em> operator rather than a run's
 *                           total, exactly as {@code maxFixpointRounds} bounds one
 *                           fixpoint; use {@link #UNLIMITED_MATERIALIZED_ROWS} for no cap
 *                           (the default); must be &ge; 1
 * @param clock              the clock the current-time built-ins ({@code NOW},
 *                           {@code CURRENT_DATE}, {@code CURRENT_TIME}) read.
 *                           <b>Read once</b>, when the context is made: what the
 *                           context keeps is that instant, so every row of every
 *                           query run through it sees the same moment. Supplying
 *                           a pinned clock (see {@link #withClock}) additionally
 *                           makes the run reproducible — the same script over the
 *                           same data yields the same rows however much later it
 *                           is replayed. {@link Clock#systemUTC()} by default;
 *                           must not be {@code null}
 * @param functions          the function catalogue every call is evaluated against —
 *                           the same one the model was analysed against, so analysis
 *                           and evaluation cannot disagree about what a name means.
 *                           {@link #of(SemanticModel, DataSourceConnector)} takes it
 *                           from the model; a context built without one gets
 *                           {@link #installedFunctions()}. Must not be {@code null}
 */
public record ExecutionContext(
        SymbolTable symbolTable,
        SchemaAnnotations nodeSchemas,
        Map<String, RelationStatistics> statistics,
        Map<String, SourceDeclaration> sources,
        Map<String, ConnectionDeclaration> connections,
        DataSourceConnector connector,
        QueryEventListener listener,
        int maxFixpointRounds,
        int maxMaterializedRows,
        Clock clock,
        FunctionCatalog functions) {

    /** Sentinel value meaning no cap on fixpoint iteration rounds; also the default. */
    public static final int UNLIMITED_FIXPOINT_ROUNDS = Integer.MAX_VALUE;

    /** Sentinel value meaning no cap on a blocking operator's buffer. */
    public static final int UNLIMITED_MATERIALIZED_ROWS = Integer.MAX_VALUE;

    /**
     * The buffer a blocking operator is allowed by default — ten million rows.
     *
     * <p><b>A cap and a sentinel are not the same decision.</b> Passing
     * {@link #UNLIMITED_MATERIALIZED_ROWS} still means no cap; this is what a context
     * that names no number gets, and until now that was the sentinel.
     *
     * <p>The number is chosen to be one no reasonable query reaches and every runaway
     * does. What it buys is not memory — ten million rows is a great deal of memory — but
     * <em>attribution</em>: past it the query fails naming the operator that buffered and
     * the knob that raises the limit, where before the host JVM died with an
     * {@code OutOfMemoryError} belonging to nobody. This engine runs inside someone
     * else's process, so that error takes their program down and tells them nothing.
     *
     * <p>The fixpoint round count is deliberately <em>not</em> given the same treatment.
     * How many rounds a legitimate recursion needs is a property of the data — a
     * transitive closure over a long chain needs one round per hop — so any default there
     * refuses some correct query, and a truncated answer is harder to diagnose than a
     * hang. {@link #UNLIMITED_FIXPOINT_ROUNDS} remains the default and stays opt-in.
     */
    public static final int DEFAULT_MAX_MATERIALIZED_ROWS = 10_000_000;

    public ExecutionContext {
        Objects.requireNonNull(symbolTable,  "symbolTable");
        Objects.requireNonNull(nodeSchemas,  "nodeSchemas");
        Objects.requireNonNull(statistics,   "statistics");
        Objects.requireNonNull(sources,      "sources");
        Objects.requireNonNull(connections,  "connections");
        Objects.requireNonNull(connector,    "connector");
        Objects.requireNonNull(listener,     "listener");
        Objects.requireNonNull(clock,        "clock");
        Objects.requireNonNull(functions,    "functions");
        if (maxFixpointRounds < 1) {
            throw new IllegalArgumentException(
                    "maxFixpointRounds must be >= 1, was: " + maxFixpointRounds);
        }
        if (maxMaterializedRows < 1) {
            throw new IllegalArgumentException(
                    "maxMaterializedRows must be >= 1, was: " + maxMaterializedRows);
        }
        statistics  = Map.copyOf(statistics);
        sources     = Map.copyOf(sources);
        connections = Map.copyOf(connections);
        // The clock is read ONCE, here, and what the context carries from then on is an
        // instant rather than a source of them.
        //
        // It is what makes "a run sees one moment" true rather than merely intended.
        // NOW() is invoked per row, so under a live clock its value drifts across the
        // rows of one query — invisibly, because the drift is microseconds and because a
        // pinned clock hides it entirely. That was tolerable while a clock call was
        // always evaluated here. It stopped being so when the planner gained the right to
        // evaluate one *once* and push the resulting literal: the pushed half and the
        // unfolded half of the same query would then be comparing against two different
        // instants, which is precisely the disagreement pushdown must not introduce.
        //
        // Pinning is idempotent — fixing an already-fixed clock yields the same instant —
        // so every `with…` copy of a context keeps the moment the original read, and only
        // withClock, which is a caller asking for a different clock, reads a new one.
        clock = Clock.fixed(clock.instant(), clock.getZone());
    }

    /** Built-in generators — used to derive per-leaf boundedness (ADR-0008). */
    private static final GeneratorRegistry GENERATORS = new GeneratorRegistry();

    /**
     * The ambient state a function implementation may read while it runs — today the
     * {@linkplain #clock() clock}, so a pinned run is reproducible down to
     * {@code NOW()}.
     *
     * <p>It also carries the engine's total order over values, which is what an
     * aggregate that ranks its input — {@code MIN}, {@code ARGMAX} — reduces by.
     *
     * <p>Build it once per execution and hold it: it is a field of the evaluator, not
     * something to make per call.
     *
     * @return the function context for this execution
     */
    public FunctionContext functionContext() {
        return OperandEvaluator.contextFor(clock);
    }

    /**
     * The catalogue over whichever function libraries are installed, discovered once.
     *
     * <p>Discovery scans the module path, so doing it per context — let alone per query
     * — would be paid for on every run. It is also the answer that must not vary: two
     * catalogues assembled separately can disagree about what a name means. A context
     * built from a {@link SemanticModel} uses that model's catalogue instead, which is
     * the same one analysis resolved against.
     *
     * @return the catalogue of installed function libraries
     */
    public static FunctionCatalog installedFunctions() {
        return InstalledFunctions.CATALOG;
    }

    /** Holder idiom: discovery runs on first use, and only once. */
    private static final class InstalledFunctions {
        static final FunctionCatalog CATALOG = FunctionCatalog.discover();

        private InstalledFunctions() {
        }
    }

    /**
     * The per-leaf {@link BoundednessSource} for this context — a generator source
     * reports its declared boundedness, every other leaf is bounded. Used
     * by the planner's materialisation-safety check and join build-side rule. Derived
     * from {@link #sources()}, so it needs no extra construction or threading.
     *
     * @return the boundedness source for the relations in this context
     */
    public BoundednessSource boundedness() {
        return new GeneratorBoundednessSource(sources, GENERATORS);
    }

    /**
     * Convenience constructor that observes no execution events
     * ({@link QueryEventListener#NONE}) and imposes no fixpoint cap.  Keeps the
     * common construction path — and every existing call site — free of those
     * parameters.
     *
     * @param symbolTable the fully-populated symbol table; must not be {@code null}
     * @param nodeSchemas per-node schema annotations; must not be {@code null}
     * @param statistics  per-relation statistics; must not be {@code null}
     * @param sources     canonical name → source declaration; must not be {@code null}
     * @param connections canonical name → connection declaration; must not be {@code null}
     * @param connector   the data-source connector; must not be {@code null}
     */
    public ExecutionContext(SymbolTable symbolTable, SchemaAnnotations nodeSchemas,
                            Map<String, RelationStatistics> statistics,
                            Map<String, SourceDeclaration> sources,
                            Map<String, ConnectionDeclaration> connections,
                            DataSourceConnector connector) {
        this(symbolTable, nodeSchemas, statistics, sources, connections, connector,
                QueryEventListener.NONE, UNLIMITED_FIXPOINT_ROUNDS,
                DEFAULT_MAX_MATERIALIZED_ROWS, Clock.systemUTC(),
                installedFunctions());
    }

    /**
     * Returns a copy of this context that emits execution-stage events to
     * {@code listener}.  All other fields are shared unchanged.
     *
     * @param listener the observer to attach; must not be {@code null}
     * @return a new context with the given listener
     */
    public ExecutionContext withListener(QueryEventListener listener) {
        return new ExecutionContext(symbolTable, nodeSchemas, statistics, sources,
                connections, connector, listener, maxFixpointRounds, maxMaterializedRows,
                clock, functions);
    }

    /**
     * Returns a copy of this context with the given fixpoint-iteration cap.  All
     * other fields are shared unchanged.
     *
     * @param maxFixpointRounds the maximum number of semi-naïve fixpoint iterations
     *                          allowed before an {@link EvaluationException} is thrown;
     *                          must be &ge; 1; use {@link #UNLIMITED_FIXPOINT_ROUNDS}
     *                          for no cap
     * @return a new context with the given limit
     */
    public ExecutionContext withMaxFixpointRounds(int maxFixpointRounds) {
        return new ExecutionContext(symbolTable, nodeSchemas, statistics, sources,
                connections, connector, listener, maxFixpointRounds, maxMaterializedRows,
                clock, functions);
    }

    /**
     * Returns a copy of this context with the given cap on how many rows one blocking
     * operator may buffer.  All other fields are shared unchanged.
     *
     * <p>The guard for the failure a plan cannot see. {@code BoundednessChecker} refuses
     * a blocking operator over an <em>unbounded</em> input, and a collecting terminal
     * refuses to gather one; neither says anything about size, so a bounded table far
     * larger than the heap plans happily and dies with an {@code OutOfMemoryError}
     * attributable to no operator in particular. Under a cap the same query stops with
     * an {@link EvaluationException} naming the operator that was buffering.
     *
     * <p>It bounds <em>one</em> operator's buffer rather than a run's total — the shape
     * {@link #withMaxFixpointRounds(int)} has, which likewise bounds one fixpoint. A
     * running total would refuse a fixpoint that buffers the same thousand rows each
     * round without its peak memory ever moving.
     *
     * @param maxMaterializedRows the maximum rows a single blocking operator may buffer;
     *                            must be &ge; 1; use
     *                            {@link #UNLIMITED_MATERIALIZED_ROWS} for no cap
     * @return a new context with the given limit
     */
    public ExecutionContext withMaxMaterializedRows(int maxMaterializedRows) {
        return new ExecutionContext(symbolTable, nodeSchemas, statistics, sources,
                connections, connector, listener, maxFixpointRounds, maxMaterializedRows,
                clock, functions);
    }

    /**
     * Returns a copy of this context whose current-time built-ins read
     * {@code clock}.  All other fields are shared unchanged.
     *
     * <p>This is the reproducibility seam: pin the clock and {@code NOW()},
     * {@code CURRENT_DATE()} and {@code CURRENT_TIME()} become fixed for the
     * whole run, so a script with a relative time window ({@code NOW() - DURATION
     * 'PT1H'}) selects the same rows today and next month.  The CLI exposes it as
     * {@code --now} / {@code RELIX_NOW}.
     *
     * <p>The functions stay non-{@code PURE} and non-{@code DETERMINISTIC} in the
     * registry regardless: under the default clock they still advance, so the
     * optimizer must never fold or dedupe them.
     *
     * @param clock the clock to read; must not be {@code null}
     * @return a new context reading the given clock
     */
    public ExecutionContext withClock(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        return new ExecutionContext(symbolTable, nodeSchemas, statistics, sources,
                connections, connector, listener, maxFixpointRounds, maxMaterializedRows,
                clock, functions);
    }

    /**
     * Returns a copy of this context whose function calls are evaluated against
     * {@code functions}.  All other fields are shared unchanged.
     *
     * <p>The catalogue normally arrives from the analysed model, which is what keeps
     * analysis and evaluation agreeing about what a name means.  Overriding it is for a
     * caller that assembled its own — an embedder installing a library programmatically
     * rather than through discovery.
     *
     * @param functions the catalogue to evaluate calls against; must not be {@code null}
     * @return a new context over the given catalogue
     */
    public ExecutionContext withFunctions(FunctionCatalog functions) {
        Objects.requireNonNull(functions, "functions");
        return new ExecutionContext(symbolTable, nodeSchemas, statistics, sources,
                connections, connector, listener, maxFixpointRounds, maxMaterializedRows,
                clock, functions);
    }

    /**
     * Convenience constructor for contexts with no statistics and no pushdown
     * metadata (the planner uses tier-based cost only and never pushes SQL).
     *
     * @param symbolTable the fully-populated symbol table; must not be {@code null}
     * @param nodeSchemas per-node schema annotations; must not be {@code null}
     * @param connector   the data-source connector; must not be {@code null}
     */
    public ExecutionContext(SymbolTable symbolTable, SchemaAnnotations nodeSchemas,
                            DataSourceConnector connector) {
        this(symbolTable, nodeSchemas, Map.of(), Map.of(), Map.of(), connector);
    }

    /**
     * Creates an {@code ExecutionContext} from a validated {@link SemanticModel}
     * and a {@link DataSourceConnector} for external relations.  Pulls statistics,
     * sources, and connections from the model so cost-based planning and SQL
     * pushdown are enabled.
     *
     * @param model     the fully-validated semantic model; must not be {@code null}
     * @param connector the connector supplying external rows; must not be {@code null}
     * @return a new context
     */
    public static ExecutionContext of(SemanticModel model, DataSourceConnector connector) {
        Objects.requireNonNull(model, "model");
        return new ExecutionContext(model.symbolTable(), model.nodeSchemas(),
                model.statistics(), model.sources(), model.connections(), connector,
                QueryEventListener.NONE, UNLIMITED_FIXPOINT_ROUNDS,
                DEFAULT_MAX_MATERIALIZED_ROWS, Clock.systemUTC(),
                model.functions());
    }

    /**
     * Creates an {@code ExecutionContext} from a model and connector but with an
     * overriding set of schema annotations — used when executing an
     * optimizer-rewritten tree whose nodes are absent from
     * {@link SemanticModel#nodeSchemas()}.
     *
     * @param model       the semantic model; must not be {@code null}
     * @param nodeSchemas the annotations covering the tree to execute; must not be {@code null}
     * @param connector   the connector supplying external rows; must not be {@code null}
     * @return a new context
     */
    public static ExecutionContext of(SemanticModel model, SchemaAnnotations nodeSchemas,
                                      DataSourceConnector connector) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(nodeSchemas, "nodeSchemas");
        return new ExecutionContext(model.symbolTable(), nodeSchemas,
                model.statistics(), model.sources(), model.connections(), connector,
                QueryEventListener.NONE, UNLIMITED_FIXPOINT_ROUNDS,
                DEFAULT_MAX_MATERIALIZED_ROWS, Clock.systemUTC(),
                model.functions());
    }

    /**
     * Creates an {@code ExecutionContext} for scripts that contain only inline
     * relations.  Any attempt to open an external data source via the connector
     * throws {@link EvaluationException}.
     *
     * @param model the fully-validated semantic model; must not be {@code null}
     * @return a new context whose connector rejects all external source requests
     */
    public static ExecutionContext inlineOnly(SemanticModel model) {
        Objects.requireNonNull(model, "model");
        return of(model, (name, schema) -> {
            throw new EvaluationException(
                    "No data source connector configured; cannot open external relation '"
                    + name + "'");
        });
    }
}

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

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.events.EventMetrics;
import com.darkcollective.relix.events.QueryEvent;
import com.darkcollective.relix.events.QueryEventListener;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.cost.Boundedness;
import com.darkcollective.relix.cost.BoundednessSource;
import com.darkcollective.relix.cost.PropertyDeriver;
import com.darkcollective.relix.plan.BoundednessException;
import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.plan.PhysicalPlanPrinter;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.processor.exec.RelNodeExecutor;
import com.darkcollective.relix.processor.provenance.AnnotatedRelation;
import com.darkcollective.relix.processor.provenance.BaseAnnotator;
import com.darkcollective.relix.processor.provenance.ProvenanceEvaluator;
import com.darkcollective.relix.provenance.Polynomial;
import com.darkcollective.relix.provenance.PolynomialSemiring;
import com.darkcollective.relix.provenance.Semiring;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.semantic.SchemaInference;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.semantic.SemanticResult;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.relation.RelationSymbol;

import java.time.Duration;
import java.util.ArrayList;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Top-level orchestrator that drives query execution against a validated
 * {@link SemanticModel}.
 *
 * <p>The engine is <em>streaming-first</em>.  The {@code executeStreaming} methods
 * hand each {@code query} statement's output to a {@link RowStreamConsumer} as a
 * lazy {@link Stream} of rows, so a query's result is never fully buffered by the
 * executor itself — a consumer that processes rows incrementally (writing them
 * out, counting them, etc.) runs in constant memory regardless of result size.
 * The convenience {@code execute} methods are thin wrappers that collect each
 * stream into a {@link QueryResult} for callers that genuinely want the whole
 * result in memory.
 *
 * <p>For each {@code query} statement in {@link SemanticModel#rootQueries()} a
 * result is produced in declaration order.  Queries are executed one at a time:
 * a query's row stream is fully consumed (and closed) before the next query runs.
 *
 * <h2>Precondition</h2>
 * <p>The model (or semantic result) passed to any {@code execute}/
 * {@code executeStreaming} method must be <em>fully valid</em> — i.e.
 * {@link SemanticResult#isFullyValid()} must return {@code true}.  If a
 * {@link SemanticResult} with errors is supplied, an
 * {@link IllegalArgumentException} is thrown immediately before any execution
 * begins.  Passing a raw {@link SemanticModel} that was produced from an invalid
 * analysis may cause {@link EvaluationException}s that would have been caught
 * during semantic analysis.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * SemanticResult result = analyzer.analyze(inputStream);
 *
 * // Streaming: rows are written out incrementally, never all buffered.
 * executor.executeStreaming(result, (label, schema, rows) ->
 *         rows.forEach(row -> writer.write(format(row))));
 *
 * // Convenience: collect the whole result into memory.
 * List<QueryResult> results = executor.execute(result);
 * }</pre>
 *
 * <h2>Thread safety</h2>
 * <p>This class is stateless and therefore thread-safe.
 */
public final class QueryExecutor {

    private final RelNodeExecutor nodeExecutor = new RelNodeExecutor();

    /**
     * The clock every {@link ExecutionContext} this executor builds will read for
     * {@code NOW}/{@code CURRENT_DATE}/{@code CURRENT_TIME}.
     */
    private final Clock clock;

    /** Creates an executor reading the system UTC clock. */
    public QueryExecutor() {
        this(Clock.systemUTC());
    }

    /**
     * Creates an executor whose current-time built-ins read {@code clock}.
     *
     * <p>Pinning the clock makes a run reproducible: a script with a relative
     * window ({@code NOW() - DURATION 'PT1H'}) selects the same rows however much
     * later it is replayed. The CLI exposes this as {@code --now} / {@code RELIX_NOW}.
     *
     * @param clock the clock to read; must not be {@code null}
     */
    public QueryExecutor(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // =========================================================================
    // Streaming API
    // =========================================================================

    /**
     * Streams the results of all {@code query} statements in {@code result} to
     * {@code consumer}, using the supplied connector for external relations.
     *
     * @param result    a fully-valid semantic analysis result; must not be null
     * @param connector provides rows for external data sources; must not be null
     * @param consumer  receives each query's label, schema, and lazy row stream
     * @throws IllegalArgumentException if {@code result} contains semantic errors
     * @throws EvaluationException      if a runtime data-level error occurs while a stream is read
     */
    public void executeStreaming(SemanticResult result, DataSourceConnector connector,
                                 RowStreamConsumer consumer) {
        executeStreaming(validatedModel(result), connector, consumer);
    }

    /**
     * Streams the results of all {@code query} statements in {@code result} to
     * {@code consumer} using an inline-only connector.
     *
     * @param result   a fully-valid semantic analysis result; must not be null
     * @param consumer receives each query's label, schema, and lazy row stream
     * @throws IllegalArgumentException if {@code result} contains semantic errors
     * @throws EvaluationException      if a runtime error occurs, including external-source access
     */
    public void executeStreaming(SemanticResult result, RowStreamConsumer consumer) {
        executeStreaming(validatedModel(result), consumer);
    }

    /**
     * Streams the results of all {@code query} statements in {@code model} to
     * {@code consumer}, using the supplied connector for external relations.
     *
     * <p>Each query's row stream is opened, passed to {@code consumer}, and closed
     * before the next query begins.  The consumer must consume the stream within
     * the callback (see {@link RowStreamConsumer}).
     *
     * @param model     the semantic model; must not be null
     * @param connector provides rows for external data sources; must not be null
     * @param consumer  receives each query's label, schema, and lazy row stream
     * @throws EvaluationException if a runtime data-level error occurs while a stream is read
     */
    public void executeStreaming(SemanticModel model, DataSourceConnector connector,
                                 RowStreamConsumer consumer) {
        executeStreaming(model, connector, consumer, ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS);
    }

    /**
     * Like {@link #executeStreaming(SemanticModel, DataSourceConnector, RowStreamConsumer)}
     * but aborts any {@code FIX} fixpoint that exceeds {@code maxFixpointRounds} iterations
     * with an {@link EvaluationException}.
     *
     * @param model             the semantic model; must not be null
     * @param connector         provides rows for external data sources; must not be null
     * @param consumer          receives each query's label, schema, and lazy row stream
     * @param maxFixpointRounds maximum semi-naïve fixpoint iterations; use
     *                          {@link ExecutionContext#UNLIMITED_FIXPOINT_ROUNDS} for no cap
     * @throws EvaluationException if a runtime data-level error occurs or a fixpoint cap is hit
     */
    public void executeStreaming(SemanticModel model, DataSourceConnector connector,
                                 RowStreamConsumer consumer, int maxFixpointRounds) {
        executeStreaming(model, connector, consumer, maxFixpointRounds, QueryEventListener.NONE);
    }

    /**
     * Like {@link #executeStreaming(SemanticModel, DataSourceConnector,
     * RowStreamConsumer, int)} but reports the run's {@code PLAN} and
     * {@code EXECUTE} decisions to {@code listener}, each tagged with the owning
     * query's label.
     *
     * <p>This is the plain execution path's observability hook: unlike
     * {@link #trace}, it neither plans a second time nor drains rows on the
     * caller's behalf, so a host can watch an ordinary run without changing what
     * that run does. No {@code OPTIMIZE} events reach the feed here — this method
     * runs whatever trees it is given, and rewriting them is the optimizer's own
     * pass, which reports to its own listener.
     *
     * @param model             the semantic model; must not be null
     * @param connector         provides rows for external data sources; must not be null
     * @param consumer          receives each query's label, schema, and lazy row stream
     * @param maxFixpointRounds maximum semi-naïve fixpoint iterations; use
     *                          {@link ExecutionContext#UNLIMITED_FIXPOINT_ROUNDS} for no cap
     * @param listener          notified on each planning/execution decision; must not be null
     * @throws EvaluationException if a runtime data-level error occurs or a fixpoint cap is hit
     */
    public void executeStreaming(SemanticModel model, DataSourceConnector connector,
                                 RowStreamConsumer consumer, int maxFixpointRounds,
                                 QueryEventListener listener) {
        Objects.requireNonNull(model,     "model");
        Objects.requireNonNull(connector, "connector");
        Objects.requireNonNull(consumer,  "consumer");
        Objects.requireNonNull(listener,  "listener");
        ExecutionContext base = ExecutionContext.of(model, connector).withClock(clock)
                .withMaxFixpointRounds(maxFixpointRounds);
        List<QueryStatement> queries = model.rootQueries();
        for (int i = 0; i < queries.size(); i++) {
            QueryStatement query = queries.get(i);
            RelNode node = resolveNode(query);
            streamOne(query, node, i + 1, observing(base, query, i + 1, listener), consumer);
        }
    }

    /**
     * Streams the results of all {@code query} statements in {@code model} to
     * {@code consumer} using an inline-only connector.
     *
     * @param model    the semantic model; must not be null
     * @param consumer receives each query's label, schema, and lazy row stream
     * @throws EvaluationException if a runtime error occurs, including external-source access
     */
    public void executeStreaming(SemanticModel model, RowStreamConsumer consumer) {
        Objects.requireNonNull(model, "model");
        executeStreaming(model, ExecutionContext.inlineOnly(model).connector(), consumer);
    }

    // =========================================================================
    // Convenience API (collects each stream into a QueryResult)
    // =========================================================================

    /**
     * Executes all {@code query} statements in {@code result}, using the supplied
     * connector, and collects each result into a {@link QueryResult}.
     *
     * @param result    a fully-valid semantic analysis result; must not be null
     * @param connector provides rows for external data sources; must not be null
     * @return an unmodifiable list of results, one per {@code query} statement, in declaration order
     * @throws IllegalArgumentException if {@code result} contains semantic errors
     * @throws EvaluationException      if a runtime data-level error occurs
     */
    public List<QueryResult> execute(SemanticResult result, DataSourceConnector connector) {
        return execute(validatedModel(result), connector);
    }

    /**
     * Executes all {@code query} statements in {@code result} using an inline-only
     * connector and collects each result into a {@link QueryResult}.
     *
     * @param result a fully-valid semantic analysis result; must not be null
     * @return an unmodifiable list of results, one per {@code query} statement
     * @throws IllegalArgumentException if {@code result} contains semantic errors
     * @throws EvaluationException      if a runtime error occurs, including external-source access
     */
    public List<QueryResult> execute(SemanticResult result) {
        return execute(validatedModel(result));
    }

    /**
     * Executes all {@code query} statements in {@code model}, using the supplied
     * connector, and collects each result into a {@link QueryResult}.
     *
     * @param model     the semantic model; must not be null
     * @param connector provides rows for external data sources; must not be null
     * @return an unmodifiable list of results in declaration order
     * @throws EvaluationException if a runtime data-level error occurs
     */
    public List<QueryResult> execute(SemanticModel model, DataSourceConnector connector) {
        Objects.requireNonNull(model,     "model");
        Objects.requireNonNull(connector, "connector");
        requireBounded(rootNodes(model), ExecutionContext.of(model, connector).boundedness());
        return collect(c -> executeStreaming(model, connector, c), model.rootQueries().size());
    }

    /**
     * Executes all {@code query} statements in {@code model} using an inline-only
     * connector and collects each result into a {@link QueryResult}.
     *
     * @param model the semantic model; must not be null
     * @return an unmodifiable list of results in declaration order
     * @throws EvaluationException if a runtime error occurs
     */
    public List<QueryResult> execute(SemanticModel model) {
        Objects.requireNonNull(model, "model");
        requireBounded(rootNodes(model), ExecutionContext.inlineOnly(model).boundedness());
        return collect(c -> executeStreaming(model, c), model.rootQueries().size());
    }

    // =========================================================================
    // Provenance (annotated K-relation) execution
    // =========================================================================

    /**
     * Evaluates each {@code query} statement in {@code model} as an annotated
     * {@link AnnotatedRelation K-relation} over {@code semiring}, handing each result
     * to {@code consumer} in declaration order.
     *
     * <p>Provenance is an explicit, opt-in mode: the chosen semiring threads through
     * the positive-algebra operators (σ/π/×/⋈/∪) while every non-positive operator is
     * read as an opaque base relation — see {@link ProvenanceEvaluator}. The query is
     * evaluated from its <em>raw</em> logical tree (no optimizer, no pushdown):
     * annotation tracking is in-engine, above the federation boundary. Because a
     * K-relation is canonical, each result is fully materialised rather than streamed.
     *
     * @param model     the semantic model; must not be null
     * @param connector provides rows for external data sources; must not be null
     * @param semiring  the annotation semiring; must not be null
     * @param consumer  receives each query's label and annotated relation; must not be null
     * @param <K>       the semiring annotation type
     * @throws EvaluationException if a runtime data-level error occurs during evaluation
     */
    public <K> void executeProvenance(SemanticModel model, DataSourceConnector connector,
                                      Semiring<K> semiring, ProvenanceConsumer<K> consumer) {
        executeProvenance(model, connector, semiring, null,
                ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS, consumer);
    }

    /**
     * Evaluates each {@code query} as a K-relation over {@code semiring}, supplying
     * per-edge weights from {@code weightColumn} for any
     * {@linkplain com.darkcollective.relix.ast.ClosureNode transitive closure} in the
     * query — the semiring-weighted-closure path.
     *
     * <p>When {@code weightColumn} is non-null, each base edge is annotated with the
     * value of that column coerced into the semiring (a {@code double} for tropical
     * shortest path, a multiplicity for ℕ; the boolean/security semirings ignore the
     * weight). A null/absent/non-numeric weight, and every non-closure base tuple,
     * lift to {@link Semiring#one() one}. When {@code weightColumn} is null this is
     * the plain {@code one()} lift — boolean reachability and ℕ path-counting need no
     * weight column.
     *
     * <p>{@code maxFixpointRounds} bounds the weighted-closure iteration (see
     * {@link ExecutionContext#maxFixpointRounds()}); it matters only for a
     * non-idempotent semiring (ℕ) over a cyclic graph, which otherwise never
     * converges. Use {@link ExecutionContext#UNLIMITED_FIXPOINT_ROUNDS} for no cap.
     *
     * @param model             the semantic model; must not be null
     * @param connector         provides rows for external data sources; must not be null
     * @param semiring          the annotation semiring; must not be null
     * @param weightColumn      the per-edge weight column for weighted closures, or null
     * @param maxFixpointRounds the weighted-closure iteration cap (&ge; 1)
     * @param consumer          receives each query's label and annotated relation; must not be null
     * @param <K>               the semiring annotation type
     * @throws EvaluationException if a runtime data-level error occurs during evaluation
     */
    public <K> void executeProvenance(SemanticModel model, DataSourceConnector connector,
                                      Semiring<K> semiring, String weightColumn,
                                      int maxFixpointRounds, ProvenanceConsumer<K> consumer) {
        Objects.requireNonNull(model,     "model");
        Objects.requireNonNull(connector, "connector");
        Objects.requireNonNull(semiring,  "semiring");
        Objects.requireNonNull(consumer,  "consumer");
        ExecutionContext ctx = ExecutionContext.of(model, connector).withClock(clock)
                .withMaxFixpointRounds(maxFixpointRounds);
        ProvenanceEvaluator evaluator = new ProvenanceEvaluator();
        BaseAnnotator<K> annotator = BaseAnnotator.forSemiring(semiring, weightColumn);
        List<QueryStatement> queries = model.rootQueries();
        for (int i = 0; i < queries.size(); i++) {
            QueryStatement query = queries.get(i);
            RelNode node = resolveNode(query);
            AnnotatedRelation<K> relation = evaluator.evaluate(node, semiring, ctx, annotator);
            consumer.accept(resolveLabel(query, i + 1), relation);
        }
    }

    /**
     * Evaluates each {@code query} statement in {@code model} as a full
     * <em>why-provenance</em> K-relation over the polynomial-lineage semiring
     * {@code ℕ[X]}, handing each result to {@code consumer}.
     *
     * <p>Unlike the cheap semirings, lineage mints a <strong>distinct provenance
     * variable per base-tuple occurrence</strong> ({@code <relation>#<ordinal>}), so
     * each output tuple's annotation is the polynomial recording exactly which input
     * tuples produced it and how they were combined. The representation is bounded:
     * a polynomial that exceeds {@link PolynomialSemiring#MAX_MONOMIALS} terms is
     * truncated and flagged. Like all provenance evaluation this runs on the raw
     * logical tree, in-engine, never pushed down.
     *
     * @param model     the semantic model; must not be null
     * @param connector provides rows for external data sources; must not be null
     * @param consumer  receives each query's label and lineage-annotated relation; must not be null
     * @throws EvaluationException if a runtime data-level error occurs during evaluation
     */
    public void executeLineage(SemanticModel model, DataSourceConnector connector,
                               ProvenanceConsumer<Polynomial> consumer) {
        Objects.requireNonNull(model,     "model");
        Objects.requireNonNull(connector, "connector");
        Objects.requireNonNull(consumer,  "consumer");
        ExecutionContext ctx = ExecutionContext.of(model, connector).withClock(clock);
        ProvenanceEvaluator evaluator = new ProvenanceEvaluator();
        // Each base-tuple occurrence becomes a fresh variable named <source>#<ordinal>,
        // carrying the occurrence's captured columns so the lineage is machine-actionable
        // (a consumer can match the columns against a key to locate the exact source row).
        BaseAnnotator<Polynomial> annotator = BaseAnnotator.lineage();
        List<QueryStatement> queries = model.rootQueries();
        for (int i = 0; i < queries.size(); i++) {
            QueryStatement query = queries.get(i);
            RelNode node = resolveNode(query);
            AnnotatedRelation<Polynomial> relation =
                    evaluator.evaluate(node, PolynomialSemiring.INSTANCE, ctx, annotator);
            consumer.accept(resolveLabel(query, i + 1), relation);
        }
    }

    // =========================================================================
    // Optimized-plan execution
    // =========================================================================

    /**
     * Streams the results of optimizer-rewritten query trees, one per root query
     * in {@link SemanticModel#rootQueries()}, using the supplied connector.
     *
     * <p>{@code optimizedRoots} must be parallel to {@code model.rootQueries()}:
     * element {@code i} is the rewritten tree to execute in place of root query
     * {@code i}.  Because the optimizer produces fresh {@link RelNode} instances
     * absent from {@link SemanticModel#nodeSchemas()} (which is keyed by object
     * identity), this method re-infers schema annotations for each rewritten tree
     * via {@link SchemaInference#annotate}, retaining the original annotations so
     * that nested view bodies referenced by — but not rewritten within — the
     * optimized tree remain executable.
     *
     * @param model          the semantic model the trees were optimized from; must not be null
     * @param optimizedRoots rewritten trees, parallel to {@code model.rootQueries()}; must not be null
     * @param connector      provides rows for external data sources; must not be null
     * @param consumer       receives each query's label, schema, and lazy row stream
     * @throws IllegalArgumentException if {@code optimizedRoots} size differs from the root-query count
     * @throws EvaluationException      if a runtime data-level error occurs while a stream is read
     */
    public void executeOptimizedStreaming(SemanticModel model, List<RelNode> optimizedRoots,
                                          DataSourceConnector connector, RowStreamConsumer consumer) {
        executeOptimizedStreaming(model, optimizedRoots, connector, consumer,
                ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS);
    }

    /**
     * Like {@link #executeOptimizedStreaming(SemanticModel, List, DataSourceConnector,
     * RowStreamConsumer)}
     * but aborts any {@code FIX} fixpoint that exceeds {@code maxFixpointRounds} iterations.
     *
     * @param model             the semantic model; must not be null
     * @param optimizedRoots    rewritten trees, parallel to {@code model.rootQueries()}; must not be null
     * @param connector         provides rows for external data sources; must not be null
     * @param consumer          receives each query's label, schema, and lazy row stream
     * @param maxFixpointRounds maximum semi-naïve fixpoint iterations; use
     *                          {@link ExecutionContext#UNLIMITED_FIXPOINT_ROUNDS} for no cap
     * @throws IllegalArgumentException if {@code optimizedRoots} size differs from the root-query count
     * @throws EvaluationException      if a runtime data-level error occurs or a fixpoint cap is hit
     */
    public void executeOptimizedStreaming(SemanticModel model, List<RelNode> optimizedRoots,
                                          DataSourceConnector connector, RowStreamConsumer consumer,
                                          int maxFixpointRounds) {
        executeOptimizedStreaming(model, optimizedRoots, connector, consumer, maxFixpointRounds,
                QueryEventListener.NONE);
    }

    /**
     * Like {@link #executeOptimizedStreaming(SemanticModel, List,
     * DataSourceConnector, RowStreamConsumer, int)} but reports the run's
     * {@code PLAN} and {@code EXECUTE} decisions to {@code listener}, each tagged
     * with the owning query's label — the observing counterpart for a caller that
     * has already optimized. The {@code OPTIMIZE} events belong to that earlier
     * pass and are collected there.
     *
     * @param model             the semantic model the trees were optimized from; must not be null
     * @param optimizedRoots    rewritten trees, parallel to {@code model.rootQueries()}; must not be null
     * @param connector         provides rows for external data sources; must not be null
     * @param consumer          receives each query's label, schema, and lazy row stream
     * @param maxFixpointRounds maximum semi-naïve fixpoint iterations; use
     *                          {@link ExecutionContext#UNLIMITED_FIXPOINT_ROUNDS} for no cap
     * @param listener          notified on each planning/execution decision; must not be null
     * @throws IllegalArgumentException if {@code optimizedRoots} size differs from the root-query count
     * @throws EvaluationException      if a runtime data-level error occurs or a fixpoint cap is hit
     */
    public void executeOptimizedStreaming(SemanticModel model, List<RelNode> optimizedRoots,
                                          DataSourceConnector connector, RowStreamConsumer consumer,
                                          int maxFixpointRounds, QueryEventListener listener) {
        Objects.requireNonNull(model,          "model");
        Objects.requireNonNull(optimizedRoots, "optimizedRoots");
        Objects.requireNonNull(connector,      "connector");
        Objects.requireNonNull(consumer,       "consumer");
        Objects.requireNonNull(listener,       "listener");

        List<QueryStatement> queries = model.rootQueries();
        if (optimizedRoots.size() != queries.size()) {
            throw new IllegalArgumentException(
                    "optimizedRoots size (" + optimizedRoots.size()
                    + ") must match root query count (" + queries.size() + ")");
        }
        for (int i = 0; i < queries.size(); i++) {
            RelNode node = optimizedRoots.get(i);
            SchemaAnnotations merged = SchemaInference.annotate(
                    model.symbolTable(), node, model.nodeSchemas(), model.functions());
            ExecutionContext ctx = ExecutionContext.of(model, merged, connector).withClock(clock)
                    .withMaxFixpointRounds(maxFixpointRounds);
            streamOne(queries.get(i), node, i + 1,
                    observing(ctx, queries.get(i), i + 1, listener), consumer);
        }
    }

    /**
     * Streams the results of optimizer-rewritten query trees using an inline-only
     * connector.
     *
     * @param model          the semantic model the trees were optimized from; must not be null
     * @param optimizedRoots rewritten trees, parallel to {@code model.rootQueries()}; must not be null
     * @param consumer       receives each query's label, schema, and lazy row stream
     * @throws IllegalArgumentException if {@code optimizedRoots} size differs from the root-query count
     * @throws EvaluationException      if a runtime error occurs, including external-source access
     */
    public void executeOptimizedStreaming(SemanticModel model, List<RelNode> optimizedRoots,
                                          RowStreamConsumer consumer) {
        Objects.requireNonNull(model, "model");
        executeOptimizedStreaming(model, optimizedRoots,
                ExecutionContext.inlineOnly(model).connector(), consumer);
    }

    /**
     * Executes optimizer-rewritten query trees, using the supplied connector, and
     * collects each result into a {@link QueryResult}.  See
     * {@link #executeOptimizedStreaming(SemanticModel, List, DataSourceConnector,
     * RowStreamConsumer)}.
     *
     * @param model          the semantic model the trees were optimized from; must not be null
     * @param optimizedRoots rewritten trees, parallel to {@code model.rootQueries()}; must not be null
     * @param connector      provides rows for external data sources; must not be null
     * @return an unmodifiable list of results in declaration order
     * @throws IllegalArgumentException if {@code optimizedRoots} size differs from the root-query count
     * @throws EvaluationException      if a runtime data-level error occurs
     */
    public List<QueryResult> executeOptimized(SemanticModel model, List<RelNode> optimizedRoots,
                                              DataSourceConnector connector) {
        requireBounded(optimizedRoots, ExecutionContext.of(model, connector).boundedness());
        return collect(c -> executeOptimizedStreaming(model, optimizedRoots, connector, c),
                model.rootQueries().size());
    }

    /**
     * Executes optimizer-rewritten query trees using an inline-only connector and
     * collects each result into a {@link QueryResult}.
     *
     * @param model          the semantic model the trees were optimized from; must not be null
     * @param optimizedRoots rewritten trees, parallel to {@code model.rootQueries()}; must not be null
     * @return an unmodifiable list of results in declaration order
     * @throws IllegalArgumentException if {@code optimizedRoots} size differs from the root-query count
     * @throws EvaluationException      if a runtime error occurs, including external-source access
     */
    public List<QueryResult> executeOptimized(SemanticModel model, List<RelNode> optimizedRoots) {
        requireBounded(optimizedRoots, ExecutionContext.inlineOnly(model).boundedness());
        return collect(c -> executeOptimizedStreaming(model, optimizedRoots, c),
                model.rootQueries().size());
    }

    // =========================================================================
    // Explain (physical plan, no execution)
    // =========================================================================

    /**
     * Renders the physical plan of each {@code query} statement in {@code model}
     * and hands it to {@code consumer}, without executing anything.  This shows the
     * planner's physical decisions — notably which sub-trees were pushed to the
     * database as a {@link com.darkcollective.relix.plan.PhysicalNode.PushedScan} and
     * each join's algorithm and build side.
     *
     * @param model    the semantic model; must not be null
     * @param consumer receives each query's label and rendered plan; must not be null
     */
    public void explain(SemanticModel model, PlanConsumer consumer) {
        Objects.requireNonNull(model, "model");
        explain(model, model.rootQueries().stream().map(QueryExecutor::resolveNode).toList(), consumer);
    }

    /**
     * Renders the physical plan of each (optimizer-rewritten) root tree and hands
     * it to {@code consumer}, without executing anything.  Use this overload after
     * optimization so the explained plan matches what {@code executeOptimized*}
     * would run; pass {@code model.rootQueries()}'s resolved nodes (via
     * {@link #explain(SemanticModel, PlanConsumer)}) for the un-optimized plan.
     *
     * @param model the semantic model the trees belong to; must not be null
     * @param roots the trees to plan, parallel to {@code model.rootQueries()}; must not be null
     * @param consumer receives each query's label and rendered plan; must not be null
     * @throws IllegalArgumentException if {@code roots} size differs from the root-query count
     */
    public void explain(SemanticModel model, List<RelNode> roots, PlanConsumer consumer) {
        Objects.requireNonNull(model,    "model");
        Objects.requireNonNull(roots,    "roots");
        Objects.requireNonNull(consumer, "consumer");
        List<QueryStatement> queries = model.rootQueries();
        if (roots.size() != queries.size()) {
            throw new IllegalArgumentException(
                    "roots size (" + roots.size() + ") must match root query count ("
                    + queries.size() + ")");
        }
        DataSourceConnector none = ExecutionContext.inlineOnly(model).connector();
        for (int i = 0; i < queries.size(); i++) {
            RelNode node = roots.get(i);
            SchemaAnnotations merged = SchemaInference.annotate(
                    model.symbolTable(), node, model.nodeSchemas(), model.functions());
            ExecutionContext ctx = ExecutionContext.of(model, merged, none).withClock(clock);
            var planned = nodeExecutor.planWithEstimates(node, ctx, QueryEventListener.NONE);
            String planText = PhysicalPlanPrinter.explain(planned.plan(), planned.estimates());
            consumer.accept(resolveLabel(queries.get(i), i + 1), planText);
        }
    }

    // =========================================================================
    // Trace (planner events, no execution)
    // =========================================================================

    /**
     * Plans each (optimizer-rewritten) root tree — without executing it — so the
     * planner emits its physical-decision {@link QueryEvent}s to {@code listener}.
     * Each event is tagged with the owning query's label.
     *
     * <p>Pair this with the optimizer's listener-aware {@code optimize} overload on
     * the same listener to get one feed of both the optimizer's rule firings and the
     * planner's decisions.
     *
     * @param model    the semantic model the trees belong to; must not be null
     * @param roots    the trees to plan, parallel to {@code model.rootQueries()}; must not be null
     * @param listener notified on each physical decision; must not be null
     * @throws IllegalArgumentException if {@code roots} size differs from the root-query count
     */
    public void trace(SemanticModel model, List<RelNode> roots, QueryEventListener listener) {
        Objects.requireNonNull(model,    "model");
        Objects.requireNonNull(roots,    "roots");
        Objects.requireNonNull(listener, "listener");
        List<QueryStatement> queries = model.rootQueries();
        if (roots.size() != queries.size()) {
            throw new IllegalArgumentException(
                    "roots size (" + roots.size() + ") must match root query count ("
                    + queries.size() + ")");
        }
        DataSourceConnector none = ExecutionContext.inlineOnly(model).connector();
        for (int i = 0; i < queries.size(); i++) {
            String label = resolveLabel(queries.get(i), i + 1);
            QueryEventListener tagged = event -> listener.onEvent(event.withTarget(label));
            RelNode node = roots.get(i);
            SchemaAnnotations merged = SchemaInference.annotate(
                    model.symbolTable(), node, model.nodeSchemas(), model.functions());
            ExecutionContext ctx = ExecutionContext.of(model, merged, none).withClock(clock);
            nodeExecutor.plan(node, ctx, tagged);   // plan for its side-effect events only
        }
    }

    /**
     * Executes each (optimizer-rewritten) root tree purely for its
     * {@link QueryEvent.Stage#EXECUTE} side-effect events — discarding the rows —
     * emitting each to {@code listener} tagged with the owning query's label.
     *
     * <p>This is the execution-stage companion to {@link #trace}: where
     * {@code trace} surfaces the planner's decisions without running anything,
     * this surfaces decisions made <em>during</em> execution (e.g. a declarative-
     * optimisation group skipped as infeasible).  Planning inside execution uses
     * {@link QueryEventListener#NONE}, so no {@code PLAN} events are double-emitted;
     * only the {@code EXECUTE} feed flows here.
     *
     * @param model     the semantic model the trees belong to; must not be null
     * @param roots     the trees to run, parallel to {@code model.rootQueries()}; must not be null
     * @param connector provides rows for external data sources; must not be null
     * @param listener  notified on each execution-stage decision; must not be null
     * @throws IllegalArgumentException if {@code roots} size differs from the root-query count
     * @throws EvaluationException      if a runtime data-level error occurs while a stream is read
     */
    public void traceExecute(SemanticModel model, List<RelNode> roots,
                             DataSourceConnector connector, QueryEventListener listener) {
        Objects.requireNonNull(model,     "model");
        Objects.requireNonNull(roots,     "roots");
        Objects.requireNonNull(connector, "connector");
        Objects.requireNonNull(listener,  "listener");
        List<QueryStatement> queries = model.rootQueries();
        if (roots.size() != queries.size()) {
            throw new IllegalArgumentException(
                    "roots size (" + roots.size() + ") must match root query count ("
                    + queries.size() + ")");
        }
        for (int i = 0; i < queries.size(); i++) {
            String label = resolveLabel(queries.get(i), i + 1);
            QueryEventListener tagged = event -> listener.onEvent(event.withTarget(label));
            RelNode node = roots.get(i);
            SchemaAnnotations merged = SchemaInference.annotate(
                    model.symbolTable(), node, model.nodeSchemas(), model.functions());
            ExecutionContext ctx = ExecutionContext.of(model, merged, connector).withClock(clock).withListener(tagged);
            // NONE for planning: trace() above already emitted this tree's PLAN
            // events, and execute(node, ctx) would otherwise repeat them.
            try (Stream<Row> rows = nodeExecutor.execute(node, ctx, QueryEventListener.NONE)) {
                rows.forEach(row -> { });   // drain for side-effect events only
            }
        }
    }

    // =========================================================================
    // Plan (physical plan object, no execution)
    // =========================================================================

    /**
     * Plans each (optimizer-rewritten) root tree — without executing it — and
     * hands the resulting {@link PhysicalNode} to {@code consumer}, while emitting
     * the planner's physical-decision {@link QueryEvent}s to {@code listener}
     * (each tagged with the owning query's label).
     *
     * <p>This is the structured counterpart to {@link #explain(SemanticModel,
     * java.util.List, PlanConsumer)} (which renders text): use it when the caller
     * needs the plan object — for example to serialize it to JSON for the query
     * bundle. Pass {@link QueryEventListener#NONE} when the events are not wanted.
     *
     * @param model    the semantic model the trees belong to; must not be null
     * @param roots    the trees to plan, parallel to {@code model.rootQueries()}; must not be null
     * @param listener notified on each physical decision; must not be null
     * @param consumer receives each query's label and planned physical tree; must not be null
     * @throws IllegalArgumentException if {@code roots} size differs from the root-query count
     */
    public void plan(SemanticModel model, List<RelNode> roots,
                     QueryEventListener listener, PhysicalPlanConsumer consumer) {
        Objects.requireNonNull(model,    "model");
        Objects.requireNonNull(roots,    "roots");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(consumer, "consumer");
        List<QueryStatement> queries = model.rootQueries();
        if (roots.size() != queries.size()) {
            throw new IllegalArgumentException(
                    "roots size (" + roots.size() + ") must match root query count ("
                    + queries.size() + ")");
        }
        DataSourceConnector none = ExecutionContext.inlineOnly(model).connector();
        for (int i = 0; i < queries.size(); i++) {
            String label = resolveLabel(queries.get(i), i + 1);
            QueryEventListener tagged = event -> listener.onEvent(event.withTarget(label));
            RelNode node = roots.get(i);
            SchemaAnnotations merged = SchemaInference.annotate(
                    model.symbolTable(), node, model.nodeSchemas(), model.functions());
            ExecutionContext ctx = ExecutionContext.of(model, merged, none).withClock(clock);
            var planned = nodeExecutor.planWithEstimates(node, ctx, tagged);
            consumer.accept(label, planned.plan(), planned.estimates());
        }
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Opens the row stream for one query, hands it to {@code consumer}, and closes
     * it afterwards.
     */
    /**
     * Returns {@code ctx} observing {@code listener}, with the query's label supplied as
     * the target of any event that does not already name one — so a feed spanning several
     * queries stays attributable, the same tagging {@link #trace} applies. Returns
     * {@code ctx} unchanged when nothing is listening, so the non-observing path
     * allocates nothing.
     *
     * <p>An event that <em>does</em> name a target keeps it. The label says which query
     * an event came from, which is worth having only where the event has nothing more
     * specific to say: a scan naming the relation it read is strictly more informative,
     * and overwriting it would leave the feed less able to answer the question the
     * labelling exists to answer.
     */
    private ExecutionContext observing(ExecutionContext ctx, QueryStatement query, int index,
                                       QueryEventListener listener) {
        if (listener == QueryEventListener.NONE) {
            return ctx;
        }
        String label = resolveLabel(query, index);
        return ctx.withListener(event -> listener.onEvent(
                event.target().isPresent() ? event : event.withTarget(label)));
    }

    private void streamOne(QueryStatement query, RelNode node, int index,
                           ExecutionContext ctx, RowStreamConsumer consumer) {
        Schema schema = resolveSchema(query, node, ctx);
        String label  = resolveLabel(query, index);
        // Counted through peek rather than by buffering: the stream stays lazy, and the
        // consumer sees exactly the rows it would have seen. The tally is therefore rows
        // *delivered* — equal to the query's cardinality whenever the consumer drains,
        // and deliberately not claimed to be cardinality when it stops early.
        //
        // Atomic because the stream is handed to an arbitrary RowStreamConsumer, and one
        // that calls parallel() on it would tally across threads. A plain counter would
        // then under-report *silently* — the event still fires and still looks plausible —
        // and EventMetrics.rows exists precisely so a consumer can compute with it.
        AtomicLong delivered = new AtomicLong();
        // Wall clock, and inclusive on purpose: unlike a scan's self time, what this
        // event reports is the query's own elapsed time, which is the whole of the work
        // done on its behalf. It spans the consumer's handling of each row because that
        // consumer is what drains the stream — a pull-based engine does the work when
        // asked, so there is no interval that excludes the asking.
        long start = System.nanoTime();
        try (Stream<Row> rows = nodeExecutor.execute(node, ctx)) {
            consumer.accept(label, schema, rows.peek(row -> delivered.incrementAndGet()));
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        // Deliberately after the try-with-resources, and therefore not reached when the
        // consumer throws: a query that failed part-way delivered no result, and a row
        // count for it would be read as one relation's size by everything downstream.
        long count = delivered.get();
        ctx.listener().onEvent(QueryEvent.of(QueryEvent.Stage.EXECUTE, "ROWS",
                        "query delivered " + count + " row" + (count == 1 ? "" : "s"),
                        label)
                .withMetrics(EventMetrics.of(count, elapsed)));
    }

    /**
     * Refuses to collect a relation that provably never ends.
     *
     * <p>{@code BoundednessChecker} runs in the planner and catches a blocking
     * <em>operator</em> over an unbounded input. It cannot catch this one, because here
     * the blocking is done by the <em>consumer</em>: {@code σ (Naturals)} contains no
     * blocking node at all, so the plan is legal and it is {@link #collect} that never
     * returns. Streaming callers are right to be allowed it — emitting an endless relation
     * is what a generator is for — so the guard belongs on the collecting entry points
     * alone, which is why it lives here rather than in the planner.
     *
     * <p>The remedy named is the planner's own, so a user who has met either message has
     * met both.
     *
     * <p>Package-private rather than private so it can be tested against a real
     * {@code GeneratorBoundednessSource} without standing up a whole analysed script; the
     * streaming entry points deliberately never call it.
     *
     * @param roots       the trees about to be materialised
     * @param boundedness the per-leaf boundedness for this run
     * @throws BoundednessException if any root is provably unbounded
     */
    static void requireBounded(List<RelNode> roots, BoundednessSource boundedness) {
        for (int i = 0; i < roots.size(); i++) {
            if (PropertyDeriver.boundedness(roots.get(i), boundedness) == Boundedness.UNBOUNDED) {
                throw new BoundednessException(
                        "cannot collect the unbounded relation of query " + (i + 1)
                        + " into a list; add a bound (e.g. λ n), or stream it instead");
            }
        }
    }

    /** The root trees of {@code model}, in declaration order. */
    private static List<RelNode> rootNodes(SemanticModel model) {
        return model.rootQueries().stream().map(QueryExecutor::resolveNode).toList();
    }

    /**
     * Runs {@code streamingCall} with a consumer that collects each query result
     * into a {@link QueryResult} — the single place the convenience API buffers a
     * stream into a list.
     */
    private static List<QueryResult> collect(Consumer<RowStreamConsumer> streamingCall, int sizeHint) {
        List<QueryResult> results = new ArrayList<>(Math.max(sizeHint, 0));
        streamingCall.accept((label, schema, rows) ->
                results.add(new QueryResult(label, schema, rows.toList())));
        return List.copyOf(results);
    }

    /** Validates the result and returns its model, or throws on semantic errors. */
    private static SemanticModel validatedModel(SemanticResult result) {
        Objects.requireNonNull(result, "result");
        if (!result.isFullyValid()) {
            throw new IllegalArgumentException(
                    "Cannot execute a model with semantic errors: " + result.errors());
        }
        return result.model().orElseThrow();
    }

    /**
     * Resolves the {@link RelNode} to execute for a given query statement.
     * Named queries produce a fresh {@link RelationNode}; expression queries
     * expose their already-parsed node directly.
     */
    private static RelNode resolveNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget   named -> new RelationNode(named.name());
            case ExpressionQueryTarget e  -> e.expression();
        };
    }

    /**
     * Determines the output schema for a query result.  Named queries use the
     * relation's declared or inferred schema from the symbol table; expression
     * queries use the per-node annotation from semantic analysis.
     */
    private static Schema resolveSchema(QueryStatement query, RelNode node, ExecutionContext ctx) {
        return switch (query.target()) {
            case NamedQueryTarget named -> ctx.symbolTable()
                    .lookupRelation(named.name())
                    .map(RelationSymbol::schema)
                    .orElseThrow(() -> new EvaluationException(
                            "Unknown relation '" + named.name() + "' in query target"));
            case ExpressionQueryTarget ignored -> ctx.nodeSchemas()
                    .get(node)
                    .orElseThrow(() -> new EvaluationException(
                            "No schema annotation for query expression — "
                            + "was semantic analysis run?"));
        };
    }

    /**
     * Returns the display label for a query result.
     * Named queries use the relation name; expression queries use
     * {@code "<expression N>"} where {@code N} is the 1-based query index.
     */
    private static String resolveLabel(QueryStatement query, int index) {
        return switch (query.target()) {
            case NamedQueryTarget   named -> named.name();
            case ExpressionQueryTarget ignored -> "<expression " + index + ">";
        };
    }
}

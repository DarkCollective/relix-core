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
package com.darkcollective.relix.optimizer.internal;

import com.darkcollective.relix.optimizer.OptimizationCode;
import com.darkcollective.relix.optimizer.TransformationRecord;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.cost.DistinctnessSource;
import com.darkcollective.relix.cost.MonotoneGeneratorSource;
import com.darkcollective.relix.events.QueryEvent;
import com.darkcollective.relix.events.QueryEventListener;
import com.darkcollective.relix.function.FunctionCatalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Mutable accumulator for {@link TransformationRecord}s produced during a
 * query optimization run.
 *
 * <p>Rules call {@link #record(OptimizationCode, String, String, SourceLocation)}
 * each time they fire.  After optimization is complete the accumulated records
 * are available via {@link #records()} and can be passed to
 * the console's optimization report for rendering.
 *
 * <p>This class is <em>not</em> thread-safe.  A fresh instance should be
 * created for each optimization run.
 *
 * <p>Every {@link #record} also emits a {@link QueryEvent} to the
 * {@link QueryEventListener} supplied at construction (default {@link
 * QueryEventListener#NONE}), so a consumer can observe rule firings live.
 *
 * <h2>Usage by rule implementations</h2>
 * <pre>
 * // inside OptimizationRule.apply():
 * if (ruleApplies) {
 *     ctx.record(OptimizationCode.EXPR_001, queryName,
 *                "2 * 3 folded to 6", node.location());
 *     return rewrittenNode;
 * }
 * return node; // no change, nothing recorded
 * </pre>
 */
public final class OptimizationContext {

    private final List<TransformationRecord> records = new ArrayList<>();
    private final QueryEventListener listener;
    private final DistinctnessSource distinctness;
    private final MonotoneGeneratorSource monotoneGenerators;
    private final FunctionCatalog functions;
    private final DeterminismSource determinism;

    /** Creates a context that records transformations but emits no events. */
    public OptimizationContext() {
        this(QueryEventListener.NONE);
    }

    /**
     * Creates a context that, in addition to recording transformations, emits a
     * {@link QueryEvent} to {@code listener} for each rule firing.
     *
     * @param listener the listener to notify on every {@link #record}; must not be null
     */
    public OptimizationContext(QueryEventListener listener) {
        this(listener, DistinctnessSource.NONE);
    }

    /**
     * Creates a context with a listener and a per-leaf {@link DistinctnessSource}
     * — consulted by {@code DistinctEliminationPass} so {@code δ} over an
     * inherently-distinct leaf (e.g. a duplicate-free generator) is removed.
     *
     * @param listener     the listener to notify on every {@link #record}; must not be null
     * @param distinctness the per-leaf duplicate-free lookup; must not be null
     *                     (use {@link DistinctnessSource#NONE} when no leaves are known distinct)
     */
    public OptimizationContext(QueryEventListener listener, DistinctnessSource distinctness) {
        this(listener, distinctness, MonotoneGeneratorSource.NONE);
    }

    /**
     * Creates a context with a listener, a per-leaf {@link DistinctnessSource}, and a
     * per-leaf {@link MonotoneGeneratorSource} — consulted by
     * {@code SelectionIntoGeneratorPass} so an upper-bound {@code σ} over a monotone
     * unbounded generator is folded into a production stop.
     *
     * @param listener           the listener to notify on every {@link #record}; must not be null
     * @param distinctness       the per-leaf duplicate-free lookup; must not be null
     * @param monotoneGenerators the per-leaf ascending-generator lookup; must not be null
     *                           (use {@link MonotoneGeneratorSource#NONE} when no generators)
     */
    public OptimizationContext(QueryEventListener listener, DistinctnessSource distinctness,
                               MonotoneGeneratorSource monotoneGenerators) {
        this(listener, distinctness, monotoneGenerators, FunctionCatalog.empty());
    }

    /**
     * Creates a context that also carries the {@link FunctionCatalog} the query was
     * analysed against — what a rule asks whether a call may be folded, de-duplicated,
     * or reduced over a bag rather than a set.
     *
     * <p>The catalogue arrives here rather than as a parameter of every rule because
     * this is already the per-run bundle each rule is handed, alongside the two other
     * per-run lookups.  It is <em>supplied</em>, never discovered: a rule that reached
     * for the installed libraries itself could disagree with the analysis that produced
     * the tree it is rewriting, which is exactly the inconsistency this constructor
     * exists to remove.
     *
     * <p>The catalogue-free constructors default to {@link FunctionCatalog#empty()},
     * where every property lookup answers "not declared".  That is the conservative
     * direction — a rule declines to fire — so a caller that forgets to supply one gets
     * a slower plan, never a wrong one.
     *
     * @param listener           the listener to notify on every {@link #record}; must not be null
     * @param distinctness       the per-leaf duplicate-free lookup; must not be null
     * @param monotoneGenerators the per-leaf ascending-generator lookup; must not be null
     * @param functions          the functions this query was analysed against; must not be null
     */
    public OptimizationContext(QueryEventListener listener, DistinctnessSource distinctness,
                               MonotoneGeneratorSource monotoneGenerators,
                               FunctionCatalog functions) {
        this(listener, distinctness, monotoneGenerators, functions, DeterminismSource.NONE);
    }

    /**
     * Creates a context that additionally carries a {@link DeterminismSource} — what a
     * rule asks before it changes how many times a sub-expression is evaluated.
     *
     * <p>It arrives here for the same reason the catalogue does: this is the per-run
     * bundle every rule is handed, and the authoritative answer needs a
     * {@link com.darkcollective.relix.symbol.table.SymbolTable} that no rule in
     * {@link OptimizationPipeline} may take.
     *
     * <p>The determinism-free constructors default to {@link DeterminismSource#NONE},
     * which vouches for nothing, so {@code SEL-010} and the {@code SET} family decline
     * rather than guess.
     *
     * @param listener           the listener to notify on every {@link #record}; must not be null
     * @param distinctness       the per-leaf duplicate-free lookup; must not be null
     * @param monotoneGenerators the per-leaf ascending-generator lookup; must not be null
     * @param functions          the functions this query was analysed against; must not be null
     * @param determinism        the reproducibility lookup; must not be null
     */
    public OptimizationContext(QueryEventListener listener, DistinctnessSource distinctness,
                               MonotoneGeneratorSource monotoneGenerators,
                               FunctionCatalog functions, DeterminismSource determinism) {
        this.listener = Objects.requireNonNull(listener, "listener");
        this.distinctness = Objects.requireNonNull(distinctness, "distinctness");
        this.monotoneGenerators =
                Objects.requireNonNull(monotoneGenerators, "monotoneGenerators");
        this.functions = Objects.requireNonNull(functions, "functions");
        this.determinism = Objects.requireNonNull(determinism, "determinism");
    }

    /**
     * @return the functions this query was analysed against (default
     *         {@link FunctionCatalog#empty()})
     */
    public FunctionCatalog functions() {
        return functions;
    }

    /**
     * Returns the reproducibility lookup a rule consults before it changes how many times
     * a sub-expression is evaluated.
     *
     * @return the source (default {@link DeterminismSource#NONE}, which vouches for
     *         nothing); never null
     */
    public DeterminismSource determinism() {
        return determinism;
    }

    /**
     * @return the per-leaf distinctness source for this run (default
     *         {@link DistinctnessSource#NONE})
     */
    public DistinctnessSource distinctness() {
        return distinctness;
    }

    /**
     * @return the per-leaf monotone-generator source for this run (default
     *         {@link MonotoneGeneratorSource#NONE})
     */
    public MonotoneGeneratorSource monotoneGenerators() {
        return monotoneGenerators;
    }

    /**
     * Records a single rule firing and emits a corresponding {@link QueryEvent}.
     *
     * @param code         the rule that fired; must not be null
     * @param relationName the name of the query/relation being optimized;
     *                     must not be blank
     * @param detail       short description of what changed; must not be blank
     * @param location     source location of the transformed node; must not be null
     */
    public void record(OptimizationCode code,
                       String           relationName,
                       String           detail,
                       SourceLocation   location) {
        Objects.requireNonNull(code,         "code");
        Objects.requireNonNull(relationName, "relationName");
        Objects.requireNonNull(detail,       "detail");
        Objects.requireNonNull(location,     "location");
        records.add(new TransformationRecord(code, relationName, detail, location));
        listener.onEvent(QueryEvent.of(QueryEvent.Stage.OPTIMIZE, code.code(), detail, relationName));
    }

    /**
     * Returns all transformation records collected so far, in the order they
     * were added.
     *
     * @return unmodifiable view of the records list; never null
     */
    public List<TransformationRecord> records() {
        return Collections.unmodifiableList(records);
    }

    /**
     * Returns the total number of transformations recorded so far.
     *
     * @return count ≥ 0
     */
    public int size() {
        return records.size();
    }

    /**
     * Returns {@code true} if no transformations have been recorded yet.
     *
     * @return {@code true} when {@link #size()} == 0
     */
    public boolean isEmpty() {
        return records.isEmpty();
    }

    /**
     * Returns the number of times the given optimization rule has fired.
     *
     * @param code the rule to count; must not be null
     * @return count ≥ 0
     */
    public long countOf(OptimizationCode code) {
        Objects.requireNonNull(code, "code");
        return records.stream().filter(r -> r.code() == code).count();
    }

    /**
     * Returns all records for a specific optimization code, in the order they
     * were added.
     *
     * @param code the rule to filter by; must not be null
     * @return unmodifiable list; never null; may be empty
     */
    public List<TransformationRecord> recordsFor(OptimizationCode code) {
        Objects.requireNonNull(code, "code");
        return records.stream()
                .filter(r -> r.code() == code)
                .toList();
    }
}

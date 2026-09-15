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
package com.darkcollective.relix.cost;

import com.darkcollective.relix.ast.AstEquivalence;
import com.darkcollective.relix.ast.RelNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Row counts a previous run actually produced, keyed by the expression that produced
 * them.
 *
 * <p>The cost model otherwise estimates: it multiplies a leaf's row count by a
 * selectivity it inferred from a predicate and a distinct count. Where a query has
 * already run, there is something better than an estimate available — the number of rows
 * it really returned — and this is where that number is kept so the next plan can use it.
 *
 * <h2>The key is the expression, not the relation</h2>
 *
 * <p>A row count for a base relation has a home already: {@code RelationStatistics}, keyed
 * by relation name, which is where an introspected count goes and where an observed one
 * goes too. An observed post-selection or post-join count has no such home, because it is
 * not a fact about any <em>relation</em>. It is a fact about an <em>expression</em>.
 *
 * <p>So the key is {@link AstEquivalence#digest}, and three of its properties are why it
 * is the right key rather than a new one. It returns a <strong>{@code String}</strong> —
 * the location-free printed form, a full structural key and not a hash — so there is no
 * collision risk and a persisted map is readable and diffable. It is already load-bearing
 * for shared-sub-expression detection, so the two features share one canonicalisation
 * rather than drifting apart. And being location-free, the same expression written at a
 * different offset in a different script hits the same entry.
 *
 * <h2>Two limits, and they are the interesting part</h2>
 *
 * <p><strong>It memoises; it does not learn.</strong> Measuring
 * {@code σ status = 'OPEN' (Orders)} teaches this nothing about
 * {@code σ status = 'CLOSED' (Orders)}, because their digests differ. Inferring the cost
 * of an unseen predicate from measured ones is adaptive query optimisation, which is a
 * different thing and not what this is.
 *
 * <p><strong>A parameterised workload explodes the keyspace.</strong>
 * {@code σ id = 12345 (Orders)} mints a distinct entry per literal — the classic
 * plan-cache failure. The lever for that is the <em>bound</em>, not canonicalisation:
 * normalising literals to widen the hit rate would discard exactly the precision that
 * makes a recorded count worth more than an estimate. So this map is bounded and evicts
 * its least recently used entry, and a workload that overflows it degrades to estimating,
 * which is where it started.
 *
 * <p>Being a memo of what happened, it is only ever as current as the last run. A count
 * recorded before a bulk load is stale in the ordinary way statistics are stale: it costs
 * a worse plan, never a wrong answer.
 */
public final class ObservedCardinalities {

    /**
     * How many expressions are remembered. Large enough that a hand-written workload
     * never evicts, small enough that a parameterised one cannot grow without bound.
     */
    public static final int DEFAULT_MAX_ENTRIES = 1024;

    /** A store that remembers nothing and can be handed anywhere one is wanted. */
    public static final ObservedCardinalities NONE = new ObservedCardinalities(0);

    private final int maxEntries;
    private final Map<String, Long> rowsByDigest;

    /** Creates a store holding {@link #DEFAULT_MAX_ENTRIES} expressions. */
    public ObservedCardinalities() {
        this(DEFAULT_MAX_ENTRIES);
    }

    /**
     * Creates a store holding at most {@code maxEntries} expressions.
     *
     * @param maxEntries the bound; zero for a store that remembers nothing
     * @throws IllegalArgumentException if negative
     */
    public ObservedCardinalities(int maxEntries) {
        if (maxEntries < 0) {
            throw new IllegalArgumentException("maxEntries must not be negative: " + maxEntries);
        }
        this.maxEntries = maxEntries;
        // Access-ordered, so eviction takes the least recently *used* entry rather than
        // the least recently written one: a digest the planner keeps asking about is one
        // the workload keeps running.
        this.rowsByDigest = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > ObservedCardinalities.this.maxEntries;
            }
        };
    }

    /**
     * Records that {@code node} produced {@code rows} rows.
     *
     * <p>Only ever call this with a count the caller can <em>prove</em>: a stream that
     * was drained to the end, not one a consumer stopped reading. A partial read is a
     * number about the consumer, and filing it here would make the planner confident and
     * wrong.
     *
     * @param node the expression that was evaluated; must not be null
     * @param rows how many rows it produced; must not be negative
     */
    public void record(RelNode node, long rows) {
        record(AstEquivalence.digest(Objects.requireNonNull(node, "node")), rows);
    }

    /**
     * Records a count against an expression's digest directly.
     *
     * @param digest the expression's {@link AstEquivalence#digest}; must not be null
     * @param rows   how many rows it produced; must not be negative
     */
    public synchronized void record(String digest, long rows) {
        Objects.requireNonNull(digest, "digest");
        if (rows < 0) {
            throw new IllegalArgumentException("rows must not be negative: " + rows);
        }
        if (maxEntries == 0) {
            return;
        }
        rowsByDigest.put(digest, rows);
    }

    /**
     * {@return the observed row count for {@code node}, or empty if it has not been run}
     *
     * @param node the expression; must not be null
     */
    public synchronized OptionalLong forExpression(RelNode node) {
        Objects.requireNonNull(node, "node");
        if (rowsByDigest.isEmpty()) {
            // Checked before digesting, because digesting is printing the sub-tree and an
            // empty store is the overwhelmingly common case — nothing has run yet.
            return OptionalLong.empty();
        }
        Long rows = rowsByDigest.get(AstEquivalence.digest(node));
        return rows == null ? OptionalLong.empty() : OptionalLong.of(rows);
    }

    /** {@return whether anything has been recorded} */
    public synchronized boolean isEmpty() {
        return rowsByDigest.isEmpty();
    }

    /** {@return how many expressions are remembered} */
    public synchronized int size() {
        return rowsByDigest.size();
    }

    @Override
    public synchronized String toString() {
        return "ObservedCardinalities[" + rowsByDigest.size() + "/" + maxEntries + "]";
    }
}

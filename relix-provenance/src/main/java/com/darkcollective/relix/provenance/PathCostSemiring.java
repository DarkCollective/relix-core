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
package com.darkcollective.relix.provenance;

import java.util.SortedSet;
import java.util.TreeSet;
import java.math.BigDecimal;

/**
 * The cheapest-route semiring {@code ((ℝ ∪ {+∞}) × ℘(Route), ⊕, ⊗, (+∞, ∅), (0, {ε}))}
 * — the combined <em>cost-and-witness</em> algebra: a single weighted
 * {@linkplain com.darkcollective.relix.provenance closure} run carries both a path's
 * cheapest cost <em>and</em> the route(s) achieving it. It is the
 * standard arg-min / Viterbi "best value + witness set" construction lifted onto the
 * {@link TropicalSemiring tropical} cost, closing the gap that {@code tropical}
 * (cost, no route) and {@code lineage} (routes, no cost) leave when used alone.
 *
 * <p>{@link #plus ⊕} keeps the cheaper alternative, unioning the witness routes on a
 * cost tie (so all co-cheapest routes survive); {@link #times ⊗} composes two hops in
 * series — costs add and the route-sets multiply (each pair's edge sets
 * {@linkplain Route#then unioned}). {@link #zero() 0} is {@code (+∞, ∅)} (unreachable),
 * {@link #one() 1} is {@code (0.0, {ε})} (the free derivation). The laws hold:
 * {@code ⊕} is a min-with-union and {@code ⊗} is cost-addition with route-set
 * union, both commutative and associative (a {@link Route} is an unordered <em>edge
 * set</em> precisely so {@code ⊗} stays commutative — the K-relation contract a
 * sequence concatenation would break); {@code ⊗} distributes over {@code ⊕} (the
 * prepended cost shifts both branches equally, so the arg-min winner is unchanged); and
 * {@code (+∞, ∅)} annihilates {@code ⊗} (the {@code +∞} absorbs the cost, the empty
 * route-set empties the product).
 *
 * <h2>Bounded representation (the degraded mode)</h2>
 * <p>A cyclic graph can enumerate unboundedly many co-cheapest routes (mirroring
 * {@code lineage}/{@code counting}). To stay bounded, an operation leaving more than
 * {@link #MAX_ROUTES} distinct routes keeps the smallest {@code MAX_ROUTES} (in
 * {@link Route}'s deterministic order) and marks the result
 * {@link PathCost#truncated() truncated} — a sound under-approximation (surviving
 * routes are real, and the <strong>cost stays exact</strong> because {@code min} is
 * idempotent; only the route-set can grow). Truncation is contagious. The
 * {@code --max-fixpoint-rounds} cut-off bounds iteration as for every weighted closure.
 */
public enum PathCostSemiring implements Semiring<PathCost> {

    /** The singleton instance. */
    INSTANCE;

    /** Maximum distinct co-cheapest routes retained before a result is truncated. */
    public static final int MAX_ROUTES = 256;

    @Override
    public PathCost zero() {
        return PathCost.zero();
    }

    @Override
    public PathCost one() {
        return PathCost.one();
    }

    @Override
    public PathCost plus(PathCost a, PathCost b) {
        int byCost = Double.compare(a.cost(), b.cost());
        if (byCost < 0) {
            return a;
        }
        if (byCost > 0) {
            return b;
        }
        // Equal cost: keep every co-cheapest route.
        SortedSet<Route> union = new TreeSet<>(a.routes());
        union.addAll(b.routes());
        return bounded(a.cost(), union, a.truncated() || b.truncated());
    }

    @Override
    public PathCost times(PathCost a, PathCost b) {
        double cost = a.cost() + b.cost();
        SortedSet<Route> product = new TreeSet<>();
        for (Route ra : a.routes()) {
            for (Route rb : b.routes()) {
                product.add(ra.then(rb));
            }
        }
        return bounded(cost, product, a.truncated() || b.truncated());
    }

    /**
     * Builds a {@link PathCost} from {@code routes}, enforcing the route cap: if more
     * than {@link #MAX_ROUTES} distinct routes remain, keeps only the smallest
     * {@code MAX_ROUTES} (deterministic {@link Route} order) and flags truncation.
     */
    private static PathCost bounded(double cost, SortedSet<Route> routes, boolean truncated) {
        if (routes.size() <= MAX_ROUTES) {
            return new PathCost(cost, routes, truncated);
        }
        SortedSet<Route> kept = new TreeSet<>();
        for (Route r : routes) {
            if (kept.size() == MAX_ROUTES) {
                break;
            }
            kept.add(r);
        }
        return new PathCost(cost, kept, true);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Each base tuple is minted a token distinguishing it from every other edge, so
     * that the cheapest route can name the edges it travelled rather than only its cost.
     * An edge with no numeric weight costs {@code 0} — every route then weighs its hop
     * count of zero, which makes an unweighted graph a defined reachability-with-witness
     * rather than an error.
     */
    @Override
    public PathCost base(BaseTuple tuple) {
        double cost = tuple.weight().map(BigDecimal::doubleValue).orElse(0.0d);
        return PathCost.of(cost, Route.of(tuple.source() + "#" + tuple.ordinal()));
    }

}

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

import java.util.Collections;
import java.util.Objects;
import java.util.SortedSet;
import java.util.StringJoiner;
import java.util.TreeSet;

/**
 * An element of the cheapest-route semiring {@link PathCostSemiring} — a path's
 * minimum {@code cost} <em>and</em> the set of co-cheapest {@link Route routes}
 * that achieve it. It answers <em>"what is the cheapest itinerary <strong>and</strong>
 * what is it?"</em> in one annotation, where {@code tropical} keeps only the cost and
 * {@code lineage} only the routes.
 *
 * <p>Held canonically: {@link #zero() 0} is {@code (+∞, ∅)} (unreachable / absent) and
 * {@link #one() 1} is {@code (0.0, {ε})} (the free derivation). Every non-zero value's
 * {@code routes} are the derivations of exactly {@code cost} — the witness set of the
 * arg-min. The {@code truncated} flag records that the route-set hit
 * {@link PathCostSemiring#MAX_ROUTES the representation cap} and dropped some
 * co-cheapest routes — a sound under-approximation (the surviving routes are real,
 * each of cost {@code cost}; the cost itself is always exact, since {@code min} stays
 * idempotent and only the route-set can grow).
 *
 * @param cost      the cheapest derivation cost; {@code +∞} for an absent tuple
 * @param routes    the co-cheapest routes (the witness set); never {@code null}
 * @param truncated whether routes were dropped to stay within the representation bound
 */
public record PathCost(double cost, SortedSet<Route> routes, boolean truncated) {

    private static final PathCost ZERO =
            new PathCost(Double.POSITIVE_INFINITY, new TreeSet<>(), false);
    private static final PathCost ONE = of(0.0d, Route.empty());

    /**
     * Canonicalises {@code routes} into an immutable sorted copy.
     *
     * @param cost      the cheapest derivation cost
     * @param routes    the witness routes; never {@code null}
     * @param truncated whether some routes were dropped to honour the bound
     */
    public PathCost {
        Objects.requireNonNull(routes, "routes");
        routes = Collections.unmodifiableSortedSet(new TreeSet<>(routes));
    }

    /** {@return the zero element {@code (+∞, ∅)} — an unreachable / absent tuple} */
    public static PathCost zero() {
        return ZERO;
    }

    /** {@return the one element {@code (0.0, {ε})} — the free (empty-path) derivation} */
    public static PathCost one() {
        return ONE;
    }

    /**
     * {@return a single-route value {@code (cost, {route})}} The base annotation of one
     * weighted edge.
     *
     * @param cost  the edge weight
     * @param route the edge's route (typically a one-token route)
     */
    public static PathCost of(double cost, Route route) {
        SortedSet<Route> single = new TreeSet<>();
        single.add(Objects.requireNonNull(route, "route"));
        return new PathCost(cost, single, false);
    }

    /** {@return whether this is the zero element (no routes — an absent tuple)} */
    public boolean isZero() {
        return routes.isEmpty();
    }

    /**
     * {@return the value rendered as {@code 320.0 via r1 | r2}} {@code ∞} for the zero
     * element; a trailing {@code  | ⋯} when {@link #truncated()}.
     */
    @Override
    public String toString() {
        if (routes.isEmpty()) {
            return truncated ? "∞ + ⋯" : "∞";
        }
        StringJoiner via = new StringJoiner(" | ");
        routes.forEach(r -> via.add(r.toString()));
        String rendered = cost + " via " + via;
        return truncated ? rendered + " | ⋯" : rendered;
    }
}

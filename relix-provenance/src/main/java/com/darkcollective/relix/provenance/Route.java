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

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * One derivation (path) carried by the cheapest-route semiring
 * {@link PathCostSemiring} — the <strong>set of edge tokens</strong> traversed. The
 * empty route is {@code ε}, the witness of the free derivation (the multiplicative
 * identity).
 *
 * <p>Each token names a base-tuple occurrence (by convention
 * {@code <relation>#<ordinal>}, the same naming the lineage semiring uses), so a route
 * records <em>which</em> edges form the cheapest path. The edge set — rather than an
 * ordered sequence — is what keeps the semiring's {@code ⊗}
 * ({@link #then(Route) series composition} = set union) <strong>commutative and
 * associative</strong>, the K-relation contract a non-commutative concatenation would
 * break. The traversal order (and human-readable edge labels) is a separable rendering
 * nicety this record does not provide; the tokens are held in a canonical sorted
 * order so the
 * {@link Comparable natural order} the route-set cap relies on is deterministic.
 *
 * @param edges the canonical (sorted, de-duplicated) edge tokens; never {@code null}
 *              (empty = {@code ε})
 */
public record Route(List<String> edges) implements Comparable<Route> {

    private static final Route EMPTY = new Route(List.of());

    /**
     * Canonicalises {@code edges} into a sorted, de-duplicated immutable copy, so that
     * routes built in any order compare and combine identically (the source of {@code ⊗}
     * commutativity).
     *
     * @param edges the edge tokens; never {@code null}, no null element
     */
    public Route {
        Objects.requireNonNull(edges, "edges");
        edges = List.copyOf(new TreeSet<>(edges));
    }

    /** {@return the empty route {@code ε}} The witness of the free derivation. */
    public static Route empty() {
        return EMPTY;
    }

    /**
     * {@return a route of the given edge tokens}
     *
     * @param tokens the edge tokens
     */
    public static Route of(String... tokens) {
        return new Route(List.of(tokens));
    }

    /**
     * {@return this route composed in series with {@code other}} The union of the two
     * edge sets — the {@code ⊗} witness of composing two hops. Commutative and
     * associative (set union); either operand being {@code ε} returns the other.
     *
     * @param other the route to compose with; never {@code null}
     */
    public Route then(Route other) {
        Objects.requireNonNull(other, "other");
        if (edges.isEmpty()) {
            return other;
        }
        if (other.edges.isEmpty()) {
            return this;
        }
        TreeSet<String> combined = new TreeSet<>(edges);
        combined.addAll(other.edges);
        return new Route(List.copyOf(combined));
    }

    /**
     * Orders routes by edge count, then lexicographically by token — a total order, so
     * the cheapest-route semiring's route-set capping is deterministic.
     */
    @Override
    public int compareTo(Route other) {
        int byLength = Integer.compare(edges.size(), other.edges.size());
        if (byLength != 0) {
            return byLength;
        }
        for (int i = 0; i < edges.size(); i++) {
            int byToken = edges.get(i).compareTo(other.edges.get(i));
            if (byToken != 0) {
                return byToken;
            }
        }
        return 0;
    }

    /** {@return the route rendered as {@code t1·t2·t3} (sorted), or {@code ε} when empty} */
    @Override
    public String toString() {
        return edges.isEmpty() ? "ε" : String.join("·", edges);
    }
}

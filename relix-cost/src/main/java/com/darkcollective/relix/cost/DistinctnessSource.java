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

import java.util.Objects;

/**
 * Supplies whether a <em>leaf</em> relation is inherently duplicate-free — the one
 * piece of distinctness that is not structural but depends on what the leaf actually
 * is. It is the distinctness analogue of {@link BoundednessSource}.
 *
 * <p>{@link PropertyDeriver} derives distinctness structurally for operators, but a
 * leaf {@code RelationNode} is conservatively non-distinct unless a source says
 * otherwise — a generator that declares itself duplicate-free ({@code Range},
 * {@code Naturals}, {@code Primes}), or a base relation with a declared key
 * ({@link StatisticsDistinctnessSource}). Both kinds are consulted together via
 * {@link #anyOf(DistinctnessSource...)}. The default {@link #NONE} reports nothing
 * as distinct, so it is a safe no-op where no producer is wired in.
 *
 * <p>Used by {@code DistinctEliminationPass} (DIST-001): {@code δ(R) → R} when {@code R}
 * is duplicate-free — which, for an unbounded distinct generator, also dissolves the
 * {@code δ} unbounded-retained-state hazard.
 */
@FunctionalInterface
public interface DistinctnessSource {

    /** A source under which no leaf is known to be duplicate-free. */
    DistinctnessSource NONE = relationName -> false;

    /**
     * @param relationName the leaf relation name; never null
     * @return {@code true} if that leaf is inherently duplicate-free
     */
    boolean duplicateFreeLeaf(String relationName);

    /**
     * Combines several sources into one that reports a leaf duplicate-free when
     * <em>any</em> constituent does — the correct composition, since each source
     * knows about a disjoint kind of leaf (a generator declares itself distinct; a
     * base relation is distinct by virtue of a declared key) and none can refute
     * another's claim.
     *
     * <p>Sources are consulted in order and the scan short-circuits on the first
     * {@code true}. With no arguments the result is {@link #NONE}.
     *
     * @param sources the sources to combine; must not be null, nor contain null
     * @return a source reporting the disjunction of its constituents; never null
     */
    static DistinctnessSource anyOf(DistinctnessSource... sources) {
        Objects.requireNonNull(sources, "sources");
        DistinctnessSource[] copy = sources.clone();
        for (DistinctnessSource source : copy) {
            Objects.requireNonNull(source, "source");
        }
        if (copy.length == 0) {
            return NONE;
        }
        return relationName -> {
            for (DistinctnessSource source : copy) {
                if (source.duplicateFreeLeaf(relationName)) {
                    return true;
                }
            }
            return false;
        };
    }
}

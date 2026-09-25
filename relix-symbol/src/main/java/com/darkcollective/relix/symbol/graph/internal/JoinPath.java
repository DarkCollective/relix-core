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
package com.darkcollective.relix.symbol.graph.internal;

import com.darkcollective.relix.symbol.graph.Relationship;
import com.darkcollective.relix.symbol.graph.SchemaGraph;
import com.darkcollective.relix.symbol.relation.RelationSymbol;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One connected join path through the {@link SchemaGraph}: an ordered set of
 * {@link Relationship} edges spanning a set of terminal relations, plus the
 * relations those edges touch.
 *
 * <p>A path is the output of {@link SchemaGraphSearch}: the minimal connected
 * subgraph (Steiner tree) that ties the query's terminals together. Its
 * {@link #edges()} carry everything mechanical join assembly needs — the join
 * columns (positional, composite keys intact) and the multiplicity bounds — and
 * its {@link #relationshipNames()} are the vocabulary relix-ask uses to phrase a
 * "did you mean?" when the search returns more than one path.
 *
 * <p>The degenerate path is a single terminal with no edges (a query that names
 * columns from one relation): {@link #edges()} empty, {@link #relations()} the
 * lone relation.
 *
 * @param edges     the relationship edges on this path, in a deterministic order;
 *                  empty for a single-relation (no-join) path
 * @param relations every relation this path touches, deduplicated by
 *                  {@link SchemaGraph#key(RelationSymbol) graph key} in
 *                  first-appearance order; never empty
 */
public record JoinPath(List<Relationship> edges, List<RelationSymbol> relations) {

    public JoinPath {
        Objects.requireNonNull(edges, "edges");
        Objects.requireNonNull(relations, "relations");
        if (relations.isEmpty()) {
            throw new IllegalArgumentException("a join path spans at least one relation");
        }
        edges = List.copyOf(edges);
        relations = List.copyOf(relations);
    }

    /** The number of relations (tables) this path joins — the resultant relation's breadth. */
    public int tableCount() {
        return relations.size();
    }

    /** True if this path joins nothing — a single relation, no edges. */
    public boolean trivial() {
        return edges.isEmpty();
    }

    /**
     * The names of the relationships traversed, in edge order — the speakable
     * vocabulary for a "did you mean?" when the search is ambiguous (answerable
     * only because edges are named).
     */
    public List<String> relationshipNames() {
        return edges.stream().map(Relationship::name).toList();
    }

    /** The graph keys of every relation on this path, in first-appearance order. */
    public Set<String> relationKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (RelationSymbol r : relations) {
            keys.add(SchemaGraph.key(r));
        }
        return keys;
    }
}

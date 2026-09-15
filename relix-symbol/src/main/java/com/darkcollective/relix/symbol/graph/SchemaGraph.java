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
package com.darkcollective.relix.symbol.graph;

import com.darkcollective.relix.symbol.relation.RelationSymbol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The schema graph: an immutable set of {@link Relationship} edges between
 * relations.
 *
 * <p>The graph is a persistent value — {@link #with(Relationship)} and
 * {@link #merge(SchemaGraph)} return new graphs, never mutate. This fits the
 * REPL's accumulate-and-reanalyze session model: declared edges are rebuilt
 * from source on every analysis, while edges learned during the session
 * (conversational acquisition, joins observed in user queries) are merged in
 * as a supplemental graph each time.
 *
 * <p>Merging deduplicates edges by name and endpoints (direction-insensitively
 * — an edge and its reversal are the same relationship walked both ways). On a
 * duplicate, the more authoritative {@link EdgeOrigin} wins: {@code DECLARED}
 * over {@code LEARNED} over {@code INFERRED}; on an origin tie, this graph's
 * edge is kept.
 */
public final class SchemaGraph {

    /** The empty graph — the default on every {@code SemanticModel}. */
    public static final SchemaGraph EMPTY = new SchemaGraph(List.of());

    private final List<Relationship> relationships;

    private SchemaGraph(List<Relationship> relationships) {
        this.relationships = List.copyOf(relationships);
    }

    /** Creates a graph holding the given relationships (order preserved). */
    public static SchemaGraph of(List<Relationship> relationships) {
        Objects.requireNonNull(relationships, "relationships");
        return relationships.isEmpty() ? EMPTY : new SchemaGraph(relationships);
    }

    /** All edges of this graph, in insertion order. */
    public List<Relationship> relationships() {
        return relationships;
    }

    /** Returns {@code true} if this graph has no edges. */
    public boolean isEmpty() {
        return relationships.isEmpty();
    }

    /**
     * All edges incident on the given relation, regardless of which endpoint
     * holds it — every edge is walkable in both directions.
     */
    public List<Relationship> edgesOf(RelationSymbol relation) {
        Objects.requireNonNull(relation, "relation");
        String k = key(relation);
        List<Relationship> incident = new ArrayList<>();
        for (Relationship r : relationships) {
            if (key(r.source().relation()).equals(k) || key(r.target().relation()).equals(k)) {
                incident.add(r);
            }
        }
        return List.copyOf(incident);
    }

    /** Returns a new graph with the given edge added (deduplicating as {@link #merge}). */
    public SchemaGraph with(Relationship relationship) {
        Objects.requireNonNull(relationship, "relationship");
        return merge(new SchemaGraph(List.of(relationship)));
    }

    /**
     * Returns a new graph containing this graph's edges plus {@code other}'s,
     * deduplicated by identity (name + unordered endpoints); the more
     * authoritative origin wins a duplicate, this graph's edge wins a tie.
     */
    public SchemaGraph merge(SchemaGraph other) {
        Objects.requireNonNull(other, "other");
        if (other.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return other;
        }
        Map<String, Relationship> merged = new LinkedHashMap<>();
        for (Relationship r : relationships) {
            merged.merge(identity(r), r, SchemaGraph::moreAuthoritative);
        }
        for (Relationship r : other.relationships) {
            merged.merge(identity(r), r, SchemaGraph::moreAuthoritative);
        }
        return new SchemaGraph(new ArrayList<>(merged.values()));
    }

    /**
     * The lookup key of a relation in this graph: its namespace plus canonical
     * (lower-cased) name. Stable across distinct symbol instances for the same
     * relation.
     */
    public static String key(RelationSymbol relation) {
        Objects.requireNonNull(relation, "relation");
        return relation.namespace() + ":" + relation.canonicalName();
    }

    /** Keeps the existing edge unless the incoming one has a more authoritative origin. */
    private static Relationship moreAuthoritative(Relationship existing, Relationship incoming) {
        return incoming.origin().ordinal() < existing.origin().ordinal() ? incoming : existing;
    }

    /**
     * Edge identity for deduplication: case-insensitive name plus the two
     * endpoints (relation key + lower-cased columns), unordered so a reversed
     * duplicate collapses onto the original.
     */
    private static String identity(Relationship r) {
        String a = endpointIdentity(r.source());
        String b = endpointIdentity(r.target());
        String pair = a.compareTo(b) <= 0 ? a + "<->" + b : b + "<->" + a;
        return r.name().toLowerCase(Locale.ROOT) + "|" + pair;
    }

    private static String endpointIdentity(Endpoint e) {
        StringBuilder sb = new StringBuilder(key(e.relation()));
        for (String c : e.columns()) {
            sb.append('#').append(c.toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "SchemaGraph[" + relationships.size() + " relationship(s)]";
    }
}

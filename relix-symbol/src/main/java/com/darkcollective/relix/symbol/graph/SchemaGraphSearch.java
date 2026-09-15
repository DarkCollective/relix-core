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

import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.relation.RelationSymbol;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Minimal-path (Steiner-tree) search over a {@link SchemaGraph}.
 *
 * <p>Given the <em>terminals</em> a request touches (the relations owning the
 * columns it names), the join Relix must build is the minimal connected subgraph
 * of the schema graph spanning them. This is a Steiner tree over a small graph,
 * where exhaustive enumeration is cheap: the search grows connected edge-sets
 * outward from the terminals a breadth at a time, so the first breadth that spans
 * every terminal yields <em>all</em> minimal-size paths at once.
 *
 * <ul>
 *   <li><b>Exactly one</b> minimal path → {@link PathSearchResult#unique()};
 *       assemble it mechanically. The model never guesses the join.</li>
 *   <li><b>Several</b> equally minimal paths → {@link PathSearchResult#ambiguous()};
 *       the two-FK {@code issues → users} case. Enumerated by relationship name
 *       for a "did you mean?".</li>
 *   <li><b>Terminals in different components</b> → {@link PathSearchResult#disconnected()}.</li>
 * </ul>
 *
 * <p><b>Minimality = fewest edges = fewest tables.</b> A tree connecting a fixed
 * set of relations has one fewer edge than it has nodes, so minimising edges
 * minimises intermediate tables — exactly the "minimise the number of tables in
 * the resultant relation" goal.
 *
 * <p><b>Derived endpoints participate only when nominated</b> (§1.5 (ii)). A
 * {@link QueryRelationSymbol} node is excluded from the search unless it is a
 * terminal (the request named it) or listed in the {@code nominated} set. So an
 * un-nominated "active orders" view never competes with its base relation, while
 * a nominated one does — the base-vs-view gate whose preference policy is item 4.
 *
 * <p><b>Self-referential edges</b> (org hierarchies, symmetric cross-sells) are
 * loops that connect no new relation, so they never appear in a minimal tree.
 * Resolving an explicit self-join is a nomination the model must make (it names
 * the relationship), which is out of scope for terminal-driven search; the search
 * simply tolerates such edges rather than mis-selecting them.
 */
public final class SchemaGraphSearch {

    /**
     * Safety valve for a pathological graph: the ADR promises small graphs where
     * exhaustive enumeration is cheap, but a dense supplemental graph could in
     * principle explode. On exceeding this many distinct explored edge-sets the
     * search stops growing and returns the best (possibly none) found so far.
     */
    private static final int MAX_STATES = 200_000;

    private SchemaGraphSearch() {
    }

    /** Searches with no nominated derived endpoints (base relations only). */
    public static PathSearchResult search(SchemaGraph graph, Collection<? extends RelationSymbol> terminals) {
        return search(graph, terminals, List.of());
    }

    /**
     * Enumerates the minimal connected subgraph(s) spanning {@code terminals}.
     *
     * @param graph     the schema graph to search
     * @param terminals the relations the query touches; deduplicated by graph key
     * @param nominated derived ({@code QueryRelationSymbol}) relations permitted
     *                  to participate as path nodes (§1.5 (ii)); base relations
     *                  always participate
     * @return the search outcome — unique, ambiguous, or disconnected
     */
    public static PathSearchResult search(SchemaGraph graph,
                                          Collection<? extends RelationSymbol> terminals,
                                          Collection<? extends RelationSymbol> nominated) {
        return search(graph, terminals, nominated, MAX_STATES);
    }

    /** Package-private overload with an explicit exploration cap, for exercising the safety valve. */
    static PathSearchResult search(SchemaGraph graph,
                                   Collection<? extends RelationSymbol> terminals,
                                   Collection<? extends RelationSymbol> nominated,
                                   int maxStates) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(terminals, "terminals");
        Objects.requireNonNull(nominated, "nominated");

        // Deduplicate terminals by graph key, keeping the first representative symbol.
        Map<String, RelationSymbol> terminalNodes = new LinkedHashMap<>();
        for (RelationSymbol t : terminals) {
            terminalNodes.putIfAbsent(SchemaGraph.key(t), t);
        }
        Set<String> nominatedKeys = new HashSet<>();
        for (RelationSymbol n : nominated) {
            nominatedKeys.add(SchemaGraph.key(n));
        }

        // No terminals: nothing to join.
        if (terminalNodes.isEmpty()) {
            return new PathSearchResult(List.of(), List.of());
        }

        // Universe of nodes: every terminal, plus both endpoints of every edge.
        Map<String, RelationSymbol> nodes = new LinkedHashMap<>(terminalNodes);
        for (Relationship r : graph.relationships()) {
            nodes.putIfAbsent(SchemaGraph.key(r.source().relation()), r.source().relation());
            nodes.putIfAbsent(SchemaGraph.key(r.target().relation()), r.target().relation());
        }

        Set<String> terminalKeys = terminalNodes.keySet();

        // A node participates if it is not a derived view, or it is a terminal, or nominated.
        Set<String> allowed = new HashSet<>();
        for (Map.Entry<String, RelationSymbol> e : nodes.entrySet()) {
            String key = e.getKey();
            boolean derived = e.getValue() instanceof QueryRelationSymbol;
            if (!derived || terminalKeys.contains(key) || nominatedKeys.contains(key)) {
                allowed.add(key);
            }
        }

        // Usable edges: both endpoints participate, and not a self-loop (loops connect
        // no new relation, so they can never be part of a minimal spanning tree).
        List<Relationship> edges = new ArrayList<>();
        for (Relationship r : graph.relationships()) {
            String a = SchemaGraph.key(r.source().relation());
            String b = SchemaGraph.key(r.target().relation());
            if (a.equals(b) || !allowed.contains(a) || !allowed.contains(b)) {
                continue;
            }
            edges.add(r);
        }

        // Single terminal: the trivial no-join path.
        if (terminalKeys.size() == 1) {
            RelationSymbol only = terminalNodes.values().iterator().next();
            return new PathSearchResult(List.of(new JoinPath(List.of(), List.of(only))), List.of());
        }

        // Adjacency: node key → indices of incident edges.
        Map<String, List<Integer>> incident = new LinkedHashMap<>();
        for (int i = 0; i < edges.size(); i++) {
            Relationship r = edges.get(i);
            incident.computeIfAbsent(SchemaGraph.key(r.source().relation()), k -> new ArrayList<>()).add(i);
            incident.computeIfAbsent(SchemaGraph.key(r.target().relation()), k -> new ArrayList<>()).add(i);
        }

        // Disconnection check: are all terminals reachable from the first?
        List<String> unreachable = unreachableTerminals(terminalKeys, edges, incident);
        if (!unreachable.isEmpty()) {
            List<RelationSymbol> stranded = unreachable.stream().map(nodes::get).toList();
            return new PathSearchResult(List.of(), stranded);
        }

        List<JoinPath> minimal = enumerateMinimal(edges, nodes, terminalKeys, incident, maxStates);
        return new PathSearchResult(minimal, List.of());
    }

    /** The terminals not reachable from an arbitrary first terminal over the usable edges. */
    private static List<String> unreachableTerminals(Set<String> terminalKeys,
                                                     List<Relationship> edges,
                                                     Map<String, List<Integer>> incident) {
        String start = terminalKeys.iterator().next();
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            for (int idx : incident.getOrDefault(node, List.of())) {
                String other = otherEnd(edges.get(idx), node);
                if (seen.add(other)) {
                    queue.add(other);
                }
            }
        }
        List<String> unreachable = new ArrayList<>();
        for (String t : terminalKeys) {
            if (!seen.contains(t)) {
                unreachable.add(t);
            }
        }
        return unreachable;
    }

    /**
     * Breadth-by-breadth growth of connected edge-sets from the terminals: the
     * first breadth at which a set spans every terminal yields all minimal paths.
     */
    private static List<JoinPath> enumerateMinimal(List<Relationship> edges,
                                                   Map<String, RelationSymbol> nodes,
                                                   Set<String> terminalKeys,
                                                   Map<String, List<Integer>> incident,
                                                   int maxStates) {
        // Seed with every edge incident to a terminal (a minimal tree contains a terminal).
        List<State> frontier = new ArrayList<>();
        Set<String> seeded = new HashSet<>();
        for (String t : terminalKeys) {
            for (int idx : incident.getOrDefault(t, List.of())) {
                State s = State.of(idx, edges.get(idx));
                if (seeded.add(s.signature())) {
                    frontier.add(s);
                }
            }
        }

        int explored = seeded.size();
        while (!frontier.isEmpty()) {
            List<JoinPath> solutions = collectSolutions(frontier, edges, nodes, terminalKeys);
            if (!solutions.isEmpty()) {
                return solutions;
            }
            List<State> next = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            for (State s : frontier) {
                for (String node : s.nodeKeys) {
                    for (int idx : incident.getOrDefault(node, List.of())) {
                        if (s.edgeIdx.contains(idx)) {
                            continue;
                        }
                        State grown = s.grow(idx, edges.get(idx));
                        if (visited.add(grown.signature())) {
                            next.add(grown);
                            if (++explored > maxStates) {
                                return List.of();
                            }
                        }
                    }
                }
            }
            frontier = next;
        }
        return List.of();
    }

    /** Every frontier state that already spans all terminals, as deterministically ordered paths. */
    private static List<JoinPath> collectSolutions(List<State> frontier,
                                                   List<Relationship> edges,
                                                   Map<String, RelationSymbol> nodes,
                                                   Set<String> terminalKeys) {
        List<JoinPath> solutions = new ArrayList<>();
        for (State s : frontier) {
            if (s.nodeKeys.containsAll(terminalKeys)) {
                solutions.add(toPath(s, edges, nodes));
            }
        }
        // Deterministic order independent of traversal: by relationship names, then node keys.
        solutions.sort(Comparator
                .comparing((JoinPath p) -> String.join(",", p.relationshipNames()))
                .thenComparing(p -> String.join(",", p.relationKeys())));
        return solutions;
    }

    /** Materialises a state into a {@link JoinPath}: edges in index order, relations in first-appearance order. */
    private static JoinPath toPath(State s, List<Relationship> edges, Map<String, RelationSymbol> nodes) {
        List<Relationship> pathEdges = new ArrayList<>();
        List<RelationSymbol> relations = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int idx : s.edgeIdx) {
            Relationship r = edges.get(idx);
            pathEdges.add(r);
            addRelation(relations, seen, SchemaGraph.key(r.source().relation()), nodes);
            addRelation(relations, seen, SchemaGraph.key(r.target().relation()), nodes);
        }
        return new JoinPath(pathEdges, relations);
    }

    private static void addRelation(List<RelationSymbol> relations, Set<String> seen,
                                    String key, Map<String, RelationSymbol> nodes) {
        if (seen.add(key)) {
            relations.add(nodes.get(key));
        }
    }

    private static String otherEnd(Relationship edge, String nodeKey) {
        String a = SchemaGraph.key(edge.source().relation());
        return a.equals(nodeKey) ? SchemaGraph.key(edge.target().relation()) : a;
    }

    /** A connected edge-set under construction: its edge indices (sorted) and the nodes they span. */
    private static final class State {
        private final TreeSet<Integer> edgeIdx;
        private final Set<String> nodeKeys;

        private State(TreeSet<Integer> edgeIdx, Set<String> nodeKeys) {
            this.edgeIdx = edgeIdx;
            this.nodeKeys = nodeKeys;
        }

        static State of(int idx, Relationship edge) {
            TreeSet<Integer> e = new TreeSet<>();
            e.add(idx);
            Set<String> n = new LinkedHashSet<>();
            n.add(SchemaGraph.key(edge.source().relation()));
            n.add(SchemaGraph.key(edge.target().relation()));
            return new State(e, n);
        }

        State grow(int idx, Relationship edge) {
            TreeSet<Integer> e = new TreeSet<>(edgeIdx);
            e.add(idx);
            Set<String> n = new LinkedHashSet<>(nodeKeys);
            n.add(SchemaGraph.key(edge.source().relation()));
            n.add(SchemaGraph.key(edge.target().relation()));
            return new State(e, n);
        }

        String signature() {
            return edgeIdx.toString();
        }
    }
}

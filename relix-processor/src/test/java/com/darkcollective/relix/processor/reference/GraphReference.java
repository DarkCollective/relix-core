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
package com.darkcollective.relix.processor.reference;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The graph operators, stated the way a textbook states them.
 *
 * <p>Four definitions, each the shortest form that is obviously the definition rather
 * than an implementation of it: closure adds a hop and repeats until a round adds
 * nothing, components flood-fill, distance is a breadth-first search from every node.
 * There is no union-find here, no semi-naïve delta, no early exit — the engine is the one
 * allowed to be clever, and an oracle is worth having only while reading it settles the
 * question.
 *
 * <p>None of these operators has an external oracle. No SQL backend has CLOSURE, CLUSTER
 * or PATH, so the pushdown agreement suites cannot reach them; what asserts them
 * otherwise is rows somebody wrote out, which says the answer stopped changing rather
 * than that it started right. The engine reaches them through the most machinery it has —
 * a semi-naïve least fixpoint, spooled sub-plans, a build side hoisted out of the
 * recursion, and a source bound folded into the traversal.
 */
final class GraphReference {

    private GraphReference() {
    }

    /** A directed edge, and the shape a closure answer comes back in. */
    record Pair(int from, int to) {
    }

    /**
     * R⁺ — every pair joined by one or more hops.
     *
     * <p>Start from the edges themselves and keep extending by one more edge until a
     * whole pass adds nothing. A cyclic graph terminates because a pair already present
     * is not added again, which is the whole of why closure is computable at all.
     *
     * @param edges the directed edges; must not be null
     * @return every reachable pair
     */
    static Set<Pair> closure(List<Pair> edges) {
        Set<Pair> reach = new LinkedHashSet<>(edges);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Pair reached : List.copyOf(reach)) {
                for (Pair edge : edges) {
                    if (reached.to() == edge.from()) {
                        grew |= reach.add(new Pair(reached.from(), edge.to()));
                    }
                }
            }
        }
        return reach;
    }

    /**
     * R* — R⁺ together with {@code (n, n)} for every node the graph mentions.
     *
     * @param edges the directed edges; must not be null
     * @return every reachable pair, plus the identity pair of every node
     */
    static Set<Pair> reflexiveClosure(List<Pair> edges) {
        Set<Pair> reach = new LinkedHashSet<>(closure(edges));
        nodes(edges).forEach(node -> reach.add(new Pair(node, node)));
        return reach;
    }

    /**
     * The connected components of the <em>undirected</em> graph, labelled the way the
     * manual says they are labelled: 1-based and dense, in ascending order of each
     * component's smallest node, so the answer does not depend on the order of the rows.
     *
     * @param edges the edges, read without direction; must not be null
     * @return node → component label
     */
    static Map<Integer, Integer> components(List<Pair> edges) {
        Map<Integer, Set<Integer>> neighbours = new LinkedHashMap<>();
        for (Pair edge : edges) {
            neighbours.computeIfAbsent(edge.from(), k -> new LinkedHashSet<>()).add(edge.to());
            neighbours.computeIfAbsent(edge.to(), k -> new LinkedHashSet<>()).add(edge.from());
        }

        List<Set<Integer>> found = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (int node : nodes(edges)) {
            if (!seen.add(node)) {
                continue;
            }
            Set<Integer> component = new TreeSet<>();
            Deque<Integer> todo = new ArrayDeque<>(List.of(node));
            while (!todo.isEmpty()) {
                int here = todo.pop();
                if (component.add(here)) {
                    seen.add(here);
                    neighbours.getOrDefault(here, Set.of()).forEach(todo::push);
                }
            }
            found.add(component);
        }

        found.sort(Comparator.comparingInt(component -> component.iterator().next()));
        Map<Integer, Integer> labels = new LinkedHashMap<>();
        for (int i = 0; i < found.size(); i++) {
            int label = i + 1;
            found.get(i).forEach(node -> labels.put(node, label));
        }
        return labels;
    }

    /**
     * The shortest directed hop count for every reachable pair — a breadth-first search
     * from each node in turn.
     *
     * <p>The start is at depth zero but is not an answer at depth zero: a pair
     * {@code (n, n)} appears only if the graph comes back round to {@code n}, and then
     * carries the length of the shortest cycle through it. That is the same rule as
     * every other pair and not a special case, which is why the search records a node
     * the first time it is <em>reached</em> rather than initialising the start.
     *
     * @param edges    the directed edges; must not be null
     * @param maxHops  the longest path to look for; the search stops there
     * @return pair → shortest hop count
     */
    static Map<Pair, Integer> shortestHops(List<Pair> edges, int maxHops) {
        Map<Pair, Integer> distances = new LinkedHashMap<>();
        for (int start : nodes(edges)) {
            Map<Integer, Integer> depth = new HashMap<>();
            List<Integer> frontier = List.of(start);
            for (int hop = 1; hop <= maxHops && !frontier.isEmpty(); hop++) {
                List<Integer> next = new ArrayList<>();
                for (int here : frontier) {
                    for (Pair edge : edges) {
                        if (edge.from() == here && !depth.containsKey(edge.to())) {
                            depth.put(edge.to(), hop);
                            next.add(edge.to());
                        }
                    }
                }
                frontier = next;
            }
            depth.forEach((node, hops) -> distances.put(new Pair(start, node), hops));
        }
        return distances;
    }

    /** Every node either column mentions, in ascending order — the order labels are assigned in. */
    static Set<Integer> nodes(List<Pair> edges) {
        Set<Integer> all = new TreeSet<>();
        edges.forEach(edge -> {
            all.add(edge.from());
            all.add(edge.to());
        });
        return all;
    }
}

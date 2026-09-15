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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a semiring-weighted closure computes, stated as the textbook algorithms it is.
 *
 * <p>Threading a semiring through the least fixpoint {@code T = E ⊕ (T ∘ E)} specialises,
 * for each semiring, to something with a name of its own: the tropical one is
 * <em>shortest path</em>, the counting one is <em>how many distinct paths</em>, the boolean
 * one is <em>reachability</em>. So the oracle is not a second evaluator threading a second
 * semiring — it is Floyd–Warshall and a dynamic-programming path count, written the way
 * they are written everywhere, with no semiring in sight.
 *
 * <p>That is what makes them worth having. An oracle built the same way as the thing it
 * checks agrees with it for the same reasons it is wrong.
 */
final class WeightedClosureReference {

    private WeightedClosureReference() {
    }

    /** A weighted directed edge. */
    record Edge(int from, int to, double weight) {
    }

    /** A pair of nodes, and the shape both answers come back in. */
    record Pair(int from, int to) {
    }

    /**
     * The cost of the cheapest path of <strong>at least one edge</strong> between every
     * connected pair — Floyd–Warshall, min-plus.
     *
     * <p>The diagonal is deliberately not seeded with zero: {@code (n, n)} appears only
     * when the graph really does come back round to {@code n}, and then carries the cost
     * of the cheapest such cycle. That is the same rule as every other pair, and it is
     * what "at least one edge" means — the closure is R⁺, not R*.
     *
     * @param edges the weighted edges; parallel edges keep the cheaper
     * @return pair → cheapest cost, holding only the pairs a path connects
     */
    static Map<Pair, Double> shortestPaths(List<Edge> edges) {
        List<Integer> nodes = new ArrayList<>(nodes(edges));
        Map<Pair, Double> cost = new LinkedHashMap<>();
        for (Edge edge : edges) {
            Pair pair = new Pair(edge.from(), edge.to());
            cost.merge(pair, edge.weight(), Math::min);
        }
        for (int via : nodes) {
            for (int from : nodes) {
                Double toVia = cost.get(new Pair(from, via));
                if (toVia == null) {
                    continue;
                }
                for (int to : nodes) {
                    Double fromVia = cost.get(new Pair(via, to));
                    if (fromVia == null) {
                        continue;
                    }
                    cost.merge(new Pair(from, to), toVia + fromVia, Math::min);
                }
            }
        }
        return cost;
    }

    /**
     * How many distinct paths of at least one edge run between every connected pair.
     *
     * <p>Only meaningful over an acyclic graph: one cycle makes the count infinite, which
     * is the divergence the engine's round cap exists to cut off rather than a number to
     * compare against. The nodes are walked in topological order — which for a graph whose
     * every edge runs from a lower node to a higher one is simply ascending order, and
     * generating exactly such graphs is how the caller guarantees what this needs.
     *
     * @param edges the edges, every one running from a lower-numbered node to a higher
     * @return pair → number of distinct paths, holding only the pairs a path connects
     */
    static Map<Pair, BigInteger> pathCounts(List<Edge> edges) {
        Map<Integer, List<Integer>> successors = new LinkedHashMap<>();
        edges.forEach(edge -> successors
                .computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge.to()));

        List<Integer> descending = new ArrayList<>(nodes(edges));
        descending = descending.reversed();

        // A path out of `from` begins with exactly one of its edges, so the count is the
        // edges themselves plus, for each edge from → w, every path already counted out of
        // w. Descending order settles w before from, every edge running upwards.
        //
        // Note this cannot be the shortest-path loop with × for min: min is idempotent and
        // addition is not, so relaxing through every intermediate node in turn counts the
        // path 1→2→3→4 once through 3 and again through 2.
        Map<Pair, BigInteger> count = new LinkedHashMap<>();
        for (int from : descending) {
            for (int to : successors.getOrDefault(from, List.of())) {
                List<Map.Entry<Pair, BigInteger>> onward = count.entrySet().stream()
                        .filter(entry -> entry.getKey().from() == to)
                        .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                        .toList();
                count.merge(new Pair(from, to), BigInteger.ONE, BigInteger::add);
                onward.forEach(entry -> count.merge(
                        new Pair(from, entry.getKey().to()), entry.getValue(), BigInteger::add));
            }
        }
        return count;
    }

    /** Every node either column mentions, ascending. */
    static Set<Integer> nodes(List<Edge> edges) {
        Set<Integer> all = new TreeSet<>();
        edges.forEach(edge -> {
            all.add(edge.from());
            all.add(edge.to());
        });
        return new LinkedHashSet<>(all);
    }
}

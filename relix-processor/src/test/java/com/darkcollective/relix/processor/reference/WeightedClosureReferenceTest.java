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

import com.darkcollective.relix.processor.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.QueryExecutor;
import com.darkcollective.relix.processor.provenance.AnnotatedRelation;
import com.darkcollective.relix.processor.reference.WeightedClosureReference.Edge;
import com.darkcollective.relix.processor.reference.WeightedClosureReference.Pair;
import com.darkcollective.relix.provenance.BooleanSemiring;
import com.darkcollective.relix.provenance.CountingSemiring;
import com.darkcollective.relix.provenance.Semiring;
import com.darkcollective.relix.provenance.TropicalSemiring;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.semantic.SemanticResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The semiring-weighted closure against the algorithms it specialises to.
 *
 * <p>Threading a semiring through the least fixpoint {@code T = E ⊕ (T ∘ E)} is one piece
 * of code that computes a different thing per semiring, each with a name of its own:
 * tropical is shortest path, counting is how many distinct paths, boolean is reachability.
 * So the oracle is Floyd–Warshall and a dynamic-programming path count — no semiring
 * anywhere in it. An oracle built the same way as the thing it checks agrees with it for
 * the same reasons it is wrong.
 *
 * <p>What this replaces is three hand-written graphs of three edges each. On one of them
 * the shortest path is {@code 1→2→3} at 2 against a direct {@code 1→3} at 5 — a real
 * discriminating case, and the only one. Three nodes cannot express a four-hop route that
 * beats a two-hop one, a tie between two routes, parallel edges, or a cycle, and every one
 * of those is a way for a min-plus fixpoint to be wrong.
 */
@DisplayName("The weighted closure against the algorithms it specialises to")
final class WeightedClosureReferenceTest extends ProcessorTestSupport {

    private static final QueryExecutor EXECUTOR = new QueryExecutor();

    /** Named, never from the clock. */
    private static final long SEED = 20_260_914L;

    private static final int DRAWS = 120;

    private static final int NODES = 6;

    /** Enough rounds for any path across six nodes; the cap is not what is under test. */
    private static final int ROUNDS = 50;

    // ── running a weighted closure ────────────────────────────────────────────

    private static <K> Map<Pair, K> closure(String script, Semiring<K> semiring, String weightColumn) {
        SemanticResult result = analyze(script);
        assertThat(result.errors()).as("semantic errors").isEmpty();
        SemanticModel model = result.model().orElseThrow();
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        List<AnnotatedRelation<K>> out = new ArrayList<>();
        EXECUTOR.executeProvenance(model, ctx.connector(), semiring, weightColumn, ROUNDS,
                (label, rel) -> out.add(rel));
        assertThat(out).hasSize(1);

        Map<Pair, K> annotated = new LinkedHashMap<>();
        out.getFirst().stream().forEach(a -> annotated.put(
                new Pair(Integer.parseInt(a.row().get(0).asDisplayString()),
                        Integer.parseInt(a.row().get(1).asDisplayString())),
                a.annotation()));
        return annotated;
    }

    private static String table(List<Edge> edges) {
        StringBuilder text = new StringBuilder("Edges := [| src | dst | cost |\n");
        edges.forEach(e -> text.append("| ").append(e.from()).append(" | ").append(e.to())
                .append(" | ").append((long) e.weight()).append(" |\n"));
        return text.append("];\nquery { CLOSURE src, dst (Edges) };\n").toString();
    }

    private static String render(List<Edge> edges) {
        return edges.stream()
                .map(e -> e.from() + "-" + (long) e.weight() + "->" + e.to())
                .toList().toString();
    }

    // ── the draws ─────────────────────────────────────────────────────────────

    /**
     * A graph where each ordered pair is an edge one time in three, weighted 1 to 9.
     *
     * <p>Every ordered pair, so a self-loop and a cycle are ordinary draws — and a cycle
     * is what makes {@code (n, n)} appear at the cost of the cheapest loop through
     * {@code n}, which no three-node example reaches.
     */
    private static List<Edge> drawAny(Random rng) {
        List<Edge> edges = new ArrayList<>();
        for (int from = 1; from <= NODES; from++) {
            for (int to = 1; to <= NODES; to++) {
                if (rng.nextInt(3) == 0) {
                    edges.add(new Edge(from, to, rng.nextInt(9) + 1));
                }
            }
        }
        return edges;
    }

    /**
     * The same, restricted to edges running from a lower node to a higher one — acyclic by
     * construction, which is what counting paths requires: one cycle and the count is
     * infinite, which is a divergence the round cap cuts off rather than a number.
     */
    private static List<Edge> drawAcyclic(Random rng) {
        List<Edge> edges = new ArrayList<>();
        for (int from = 1; from <= NODES; from++) {
            for (int to = from + 1; to <= NODES; to++) {
                if (rng.nextInt(2) == 0) {
                    edges.add(new Edge(from, to, rng.nextInt(9) + 1));
                }
            }
        }
        return edges;
    }

    // ── the claims ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("120 weighted graphs: the tropical closure is Floyd–Warshall's answer")
    void theTropicalClosureIsShortestPath() {
        Random rng = new Random(SEED);
        int withShortcut = 0;
        int withLoop = 0;

        for (int draw = 0; draw < DRAWS; draw++) {
            List<Edge> edges = drawAny(rng);
            if (edges.isEmpty()) {
                continue;
            }
            Map<Pair, Double> expected = WeightedClosureReference.shortestPaths(edges);

            // A pair whose cheapest route is not its direct edge is the only kind of case
            // that tells a fixpoint that composes from one that just reads the edges.
            for (Edge edge : edges) {
                Double best = expected.get(new Pair(edge.from(), edge.to()));
                if (best != null && best < edge.weight()) {
                    withShortcut++;
                    break;
                }
            }
            if (expected.keySet().stream().anyMatch(p -> p.from() == p.to())) {
                withLoop++;
            }

            Map<Pair, Double> actual = closure(table(edges), TropicalSemiring.INSTANCE, "cost");
            assertThat(actual.keySet())
                    .as("reachable pairs — %s", render(edges))
                    .containsExactlyInAnyOrderElementsOf(expected.keySet());
            expected.forEach((pair, cost) -> assertThat(actual.get(pair))
                    .as("cheapest %d→%d — %s", pair.from(), pair.to(), render(edges))
                    .isCloseTo(cost, within(1e-9)));
        }

        assertThat(withShortcut)
                .as("draws where some pair's cheapest route beats its direct edge")
                .isGreaterThan(DRAWS / 3);
        assertThat(withLoop)
                .as("draws where a node reaches itself — the cost of its cheapest cycle, "
                    + "which no three-node example can express")
                .isGreaterThan(DRAWS / 2);
    }

    @Test
    @DisplayName("120 acyclic graphs: the counting closure is how many distinct paths there are")
    void theCountingClosureIsAPathCount() {
        Random rng = new Random(SEED);
        int aboveOne = 0;

        for (int draw = 0; draw < DRAWS; draw++) {
            List<Edge> edges = drawAcyclic(rng);
            if (edges.isEmpty()) {
                continue;
            }
            Map<Pair, BigInteger> expected = WeightedClosureReference.pathCounts(edges);
            if (expected.values().stream().anyMatch(n -> n.compareTo(BigInteger.ONE) > 0)) {
                aboveOne++;
            }

            assertThat(closure(table(edges), CountingSemiring.INSTANCE, null))
                    .as("path counts — %s", render(edges))
                    .containsExactlyInAnyOrderEntriesOf(expected);
        }

        assertThat(aboveOne)
                .as("draws where some pair is joined by more than one path — a count that "
                    + "is always 1 agrees with plain reachability and proves nothing")
                .isGreaterThan(DRAWS / 2);
    }

    @Test
    @DisplayName("the boolean closure reaches exactly what the ordinary operator returns")
    void theBooleanClosureAgreesWithTheUnannotatedOne() {
        Random rng = new Random(SEED);
        int compared = 0;

        for (int draw = 0; draw < DRAWS; draw++) {
            List<Edge> edges = drawAny(rng);
            if (edges.isEmpty()) {
                continue;
            }
            // Two evaluators with nothing in common: one threads a semiring through an
            // annotated relation, the other is the plain closure executor. They are asked
            // the same question and have to give the same pairs.
            Map<Pair, Boolean> annotated = closure(table(edges), BooleanSemiring.INSTANCE, null);

            assertThat(annotated.keySet())
                    .as("boolean annotation vs the plain closure — %s", render(edges))
                    .containsExactlyInAnyOrderElementsOf(
                            WeightedClosureReference.shortestPaths(edges).keySet());
            assertThat(annotated.values())
                    .as("every reachable pair is annotated true, never false — %s", render(edges))
                    .allMatch(Boolean::booleanValue);
            compared++;
        }

        assertThat(compared).as("graphs actually compared").isGreaterThan(DRAWS / 2);
    }


    @Test
    @DisplayName("an undirected weighted closure is the same edges written out both ways")
    void theUndirectedClosureAgreesWithTheDoubledTable() {
        // A one-way chain, so the two readings differ on every derived pair. No self-loops
        // and no pair already written both ways, so doubling the table by hand is the
        // reading exactly — see the self-loop case below for where the two part company.
        String oneWay = """
                Edges := [| src | dst | cost |
                |-----|-----|------|
                | 1   | 2   | 3    |
                | 2   | 3   | 4    |
                ];
                """;
        String bothWays = """
                Edges := [| src | dst | cost |
                |-----|-----|------|
                | 1   | 2   | 3    |
                | 2   | 1   | 3    |
                | 2   | 3   | 4    |
                | 3   | 2   | 4    |
                ];
                """;

        assertThat(closure(oneWay + "query { CLOSURE src \u2194 dst (Edges) };",
                        TropicalSemiring.INSTANCE, "cost"))
                .as("tropical")
                .isEqualTo(closure(bothWays + "query { CLOSURE src, dst (Edges) };",
                        TropicalSemiring.INSTANCE, "cost"));

        assertThat(closure(oneWay + "query { CLOSURE src \u2194 dst (Edges) };",
                        BooleanSemiring.INSTANCE, null))
                .as("boolean")
                .isEqualTo(closure(bothWays + "query { CLOSURE src, dst (Edges) };",
                        BooleanSemiring.INSTANCE, null));
    }

    @Test
    @DisplayName("the cheapest route may run against the direction a row was written in")
    void theUndirectedClosureTravelsBackwards() {
        String script = """
                Edges := [| src | dst | cost |
                |-----|-----|------|
                | 1   | 2   | 3    |
                | 2   | 3   | 4    |
                ];
                query { CLOSURE src \u2194 dst (Edges) };
                """;
        Map<Pair, Double> costs = closure(script, TropicalSemiring.INSTANCE, "cost");

        assertThat(costs).containsEntry(new Pair(1, 3), 7.0d);
        // The pair the directed reading cannot reach at all.
        assertThat(costs).containsEntry(new Pair(3, 1), 7.0d);
    }

    @Test
    @DisplayName("a self-loop is read both ways without disturbing the answer")
    void aSelfLoopIsHandled() {
        // A self-loop is its own transpose, so the undirected reading ⊕-s it with itself.
        // Under an idempotent semiring that is the same annotation, which is what makes the
        // simpler implementation correct; under a non-idempotent one the loop is a cycle
        // and the fixpoint does not converge either way.
        String withLoop = """
                Edges := [| src | dst | cost |
                |-----|-----|------|
                | 1   | 1   | 5    |
                | 1   | 2   | 3    |
                ];
                """;
        String doubled = """
                Edges := [| src | dst | cost |
                |-----|-----|------|
                | 1   | 1   | 5    |
                | 1   | 2   | 3    |
                | 2   | 1   | 3    |
                ];
                """;
        assertThat(closure(withLoop + "query { CLOSURE src \u2194 dst (Edges) };",
                        TropicalSemiring.INSTANCE, "cost"))
                .isEqualTo(closure(doubled + "query { CLOSURE src, dst (Edges) };",
                        TropicalSemiring.INSTANCE, "cost"));
    }

    @Test
    @DisplayName("a cheaper route of more hops still wins, which three nodes cannot say")
    void aLongerCheaperRouteWins() {
        // 1→5 directly costs 10; 1→2→3→4→5 costs 4. Only a fixpoint that keeps composing
        // finds it, and the existing hand-written case tops out at two hops.
        List<Edge> edges = List.of(
                new Edge(1, 5, 10), new Edge(1, 2, 1), new Edge(2, 3, 1),
                new Edge(3, 4, 1), new Edge(4, 5, 1));

        assertThat(closure(table(edges), TropicalSemiring.INSTANCE, "cost").get(new Pair(1, 5)))
                .as("four cheap hops beat one expensive one")
                .isCloseTo(WeightedClosureReference.shortestPaths(edges).get(new Pair(1, 5)),
                        within(1e-9))
                .isEqualTo(4.0d);
    }
}

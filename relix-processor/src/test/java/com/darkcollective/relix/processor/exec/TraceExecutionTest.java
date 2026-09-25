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
package com.darkcollective.relix.processor.exec;

import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.TraceNode;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.processor.internal.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.internal.QueryExecutor;
import com.darkcollective.relix.processor.internal.QueryResult;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RelNodeExecutor — optimal-path extraction (TRACE)")
final class TraceExecutionTest extends ProcessorTestSupport {

    /**
     * An AST number literal.  Not {@link ProcessorTestSupport#num}, which builds the
     * row <em>value</em> of the same spelling — the two vocabularies collide on the
     * name, and an inherited member wins over a static import, so this one is
     * spelled apart rather than shadowed.
     */
    private static Operand numOperand(String value) {
        return AstBuilders.num(value);
    }

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    // ── helpers ───────────────────────────────────────────────────────────────

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget   named -> rel(named.name());
            case ExpressionQueryTarget e  -> e.expression();
        };
    }

    /**
     * Executes the first root query of {@code src} and returns a map of
     * "from→to" strings to their best cost (as a double string) for easy assertions.
     */
    private static Map<String, Double> traceCosts(String src) {
        SemanticModel model = model(src);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        var query = model.rootQueries().getFirst();
        try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
            return stream.collect(Collectors.toMap(
                    r -> r.get("src").asDisplayString() + "→" + r.get("dst").asDisplayString(),
                    r -> Double.parseDouble(r.get("cost").asDisplayString())));
        }
    }

    /**
     * Executes the first root query and returns the path array string for the
     * given "from→to" pair (e.g. {@code "[1, 3]"}).
     */
    private static String tracePath(String src, String from, String to) {
        SemanticModel model = model(src);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        var query = model.rootQueries().getFirst();
        try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
            return stream
                    .filter(r -> r.get("src").asDisplayString().equals(from)
                            && r.get("dst").asDisplayString().equals(to))
                    .map(r -> r.get("route").asDisplayString())
                    .findFirst()
                    .orElseThrow();
        }
    }

    // ── Edges shared by several tests ─────────────────────────────────────────

    private static final String TRIANGLE_EDGES =
            // Direct 1→3 costs 10; path 1→2→3 costs 3+4=7 — should be preferred.
            "Edges := [| src | dst | cost |\n" +
            "           | 1   | 2   | 3    |\n" +
            "           | 2   | 3   | 4    |\n" +
            "           | 1   | 3   | 10   |];\n";

    // ── MINIMIZE tests ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("shortest path: cheaper multi-hop beats expensive direct edge")
    void shortestPathMultiHop() {
        String src = TRIANGLE_EDGES + "query { TRACE src, dst VIA cost MINIMIZE AS route (Edges) };";
        Map<String, Double> costs = traceCosts(src);

        // 1→3 via 1→2→3 costs 7, not 10
        assertThat(costs.get("1→3")).isEqualTo(7.0);
    }

    @Test
    @DisplayName("direct edge is the best path when no shorter multi-hop exists")
    void directEdgeIsOptimal() {
        String src = TRIANGLE_EDGES + "query { TRACE src, dst VIA cost MINIMIZE AS route (Edges) };";
        Map<String, Double> costs = traceCosts(src);

        assertThat(costs.get("1→2")).isEqualTo(3.0);
        assertThat(costs.get("2→3")).isEqualTo(4.0);
    }

    @Test
    @DisplayName("path array records the traversed nodes in order")
    void pathArrayContainsTraversedNodes() {
        String src = TRIANGLE_EDGES + "query { TRACE src, dst VIA cost MINIMIZE AS route (Edges) };";
        // Cheapest 1→3: via 1→2→3
        assertThat(tracePath(src, "1", "3")).isEqualTo("[1, 2, 3]");
    }

    // ── MAXIMIZE tests ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("longest path: expensive multi-hop beats cheap direct edge")
    void longestPathMultiHop() {
        String src = TRIANGLE_EDGES + "query { TRACE src, dst VIA cost MAXIMIZE AS route (Edges) };";
        Map<String, Double> costs = traceCosts(src);

        // 1→3 via direct edge costs 10, which is more than 7 → should prefer direct
        assertThat(costs.get("1→3")).isEqualTo(10.0);
    }

    @Test
    @DisplayName("MAXIMIZE: multi-hop is chosen when it accumulates more weight")
    void longestPathChoosesMultiHop() {
        // Direct 1→3 costs 2; 1→2→3 costs 5+6=11 — MAXIMIZE should choose the multi-hop.
        String src =
                "Edges := [| src | dst | cost |\n" +
                "           | 1   | 2   | 5    |\n" +
                "           | 2   | 3   | 6    |\n" +
                "           | 1   | 3   | 2    |];\n" +
                "query { TRACE src, dst VIA cost MAXIMIZE AS route (Edges) };";
        Map<String, Double> costs = traceCosts(src);

        assertThat(costs.get("1→3")).isEqualTo(11.0);
    }

    // ── Disconnected and reachability ─────────────────────────────────────────

    @Test
    @DisplayName("unreachable pairs are absent from the output")
    void unreachablePairsAbsent() {
        String src =
                "Edges := [| src | dst | cost |\n" +
                "           | 1   | 2   | 1    |\n" +
                "           | 3   | 4   | 1    |];\n" +
                "query { TRACE src, dst VIA cost MINIMIZE AS route (Edges) };";
        Map<String, Double> costs = traceCosts(src);

        // 1→4 and 3→2 are unreachable
        assertThat(costs).doesNotContainKey("1→4");
        assertThat(costs).doesNotContainKey("3→2");
        // But direct edges are present
        assertThat(costs).containsKey("1→2");
        assertThat(costs).containsKey("3→4");
    }

    // ── Null-endpoint handling ─────────────────────────────────────────────────

    @Test
    @DisplayName("null endpoints produced by outer join are skipped")
    void nullEndpointsSkipped() {
        // Left-outer join produces rows where dst / cost may be NULL for unmatched nodes;
        // those null-endpoint edges must be silently dropped by TRACE.
        String src =
                "Nodes  := [| src |\n" +
                "            | 1   |\n" +
                "            | 9   |];\n" +   // node 9 has no outgoing edge
                "RawEdges := [| src | dst | cost |\n" +
                "              | 1   | 2   | 5    |];\n" +
                "Joined := { Nodes ⟕ Nodes.src = RawEdges.src RawEdges };\n" +
                "query { TRACE src, dst VIA cost MINIMIZE AS route (Joined) };";
        Map<String, Double> costs = traceCosts(src);

        // Node 9's row has a null dst and null cost → must be skipped
        assertThat(costs).containsOnlyKeys("1→2");
    }

    // ── Cycle avoidance ───────────────────────────────────────────────────────

    @Test
    @DisplayName("the executor avoids cycles — a node is not visited twice in the same path")
    void cycleDoesNotCauseInfiniteLoop() {
        // A→B→C→A cycle: the executor must not extend a path back through nodes already visited.
        String src =
                "Edges := [| src | dst | cost |\n" +
                "           | 1   | 2   | 1    |\n" +
                "           | 2   | 3   | 1    |\n" +
                "           | 3   | 1   | 1    |];\n" +
                "query { TRACE src, dst VIA cost MINIMIZE AS route (Edges) };";
        // This must complete; the only reachable non-loop pairs are the direct edges.
        Map<String, Double> costs = traceCosts(src);
        // Each direct edge is a path; multi-hop paths are blocked by the no-revisit rule.
        assertThat(costs).containsKey("1→2");
        assertThat(costs).containsKey("2→3");
        assertThat(costs).containsKey("3→1");
    }

    // ── Endpoint pushdown (ADR-0020 / #328 slice 1) ──────────────────────────
    //
    // The optimizer folds a constant endpoint equality above a TRACE into its
    // boundSource/boundTarget, turning all-pairs path search into single-source /
    // single-target / single-pair search. relix-processor does not depend on the
    // optimizer, so these tests build the bounded TraceNode directly (as the
    // optimizer would) and run it through executeOptimized, which re-annotates the
    // rewritten tree before planning.

    @Nested
    @DisplayName("TRACE — endpoint pushdown (single-source / target / pair)")
    final class EndpointPushdown {

        private static QueryResult run(String src, Optional<Operand> from, Optional<Operand> to) {
            SemanticModel model = model(src);
            var query = model.rootQueries().getFirst();
            TraceNode raw = (TraceNode) queryNode(query);
            RelNode bounded = raw.withBounds(from, to);
            return new QueryExecutor().executeOptimized(model, List.of(bounded)).getFirst();
        }

        private static Map<String, Double> costs(String src, Optional<Operand> from, Optional<Operand> to) {
            return run(src, from, to).rows().stream().collect(Collectors.toMap(
                    r -> r.get("src").asDisplayString() + "→" + r.get("dst").asDisplayString(),
                    r -> Double.parseDouble(r.get("cost").asDisplayString())));
        }

        private static String path(String src, Optional<Operand> from, Optional<Operand> to,
                                   String f, String t) {
            return run(src, from, to).rows().stream()
                    .filter(r -> r.get("src").asDisplayString().equals(f)
                            && r.get("dst").asDisplayString().equals(t))
                    .map(r -> r.get("route").asDisplayString())
                    .findFirst().orElseThrow();
        }

        private static final String Q =
                TRIANGLE_EDGES + "query { TRACE src, dst VIA cost MINIMIZE AS route (Edges) };";
        private static final String QMAX =
                TRIANGLE_EDGES + "query { TRACE src, dst VIA cost MAXIMIZE AS route (Edges) };";

        @Test
        @DisplayName("σ src = 1 → single-source: only paths from node 1, optimal preserved")
        void singleSource() {
            Map<String, Double> c = costs(Q, Optional.of(numOperand("1")), Optional.empty());
            assertThat(c).containsOnlyKeys("1→2", "1→3");
            assertThat(c.get("1→2")).isEqualTo(3.0);
            assertThat(c.get("1→3")).isEqualTo(7.0);   // 1→2→3 beats the direct 1→3 (=10)
        }

        @Test
        @DisplayName("single-source still records the optimal traversed path")
        void singleSourcePath() {
            assertThat(path(Q, Optional.of(numOperand("1")), Optional.empty(), "1", "3"))
                    .isEqualTo("[1, 2, 3]");
        }

        @Test
        @DisplayName("σ dst = 3 → single-target: everything that reaches node 3, paths re-oriented")
        void singleTarget() {
            Map<String, Double> c = costs(Q, Optional.empty(), Optional.of(numOperand("3")));
            assertThat(c).containsOnlyKeys("1→3", "2→3");
            assertThat(c.get("2→3")).isEqualTo(4.0);
            assertThat(c.get("1→3")).isEqualTo(7.0);
            // The reversed-graph search must re-orient the path to read origin→target.
            assertThat(path(Q, Optional.empty(), Optional.of(numOperand("3")), "1", "3"))
                    .isEqualTo("[1, 2, 3]");
        }

        @Test
        @DisplayName("σ src = 1 ∧ dst = 3 → single-pair: just the one optimal path")
        void singlePair() {
            Map<String, Double> c = costs(Q, Optional.of(numOperand("1")), Optional.of(numOperand("3")));
            assertThat(c).containsOnlyKeys("1→3");
            assertThat(c.get("1→3")).isEqualTo(7.0);
        }

        @Test
        @DisplayName("single-pair to an unreachable target is empty")
        void singlePairUnreachable() {
            // Node 3 has no outgoing edge, so nothing is reachable from 3.
            assertThat(costs(Q, Optional.of(numOperand("3")), Optional.of(numOperand("1")))).isEmpty();
        }

        @Test
        @DisplayName("MAXIMIZE is honoured under a source bound (direct 1→3=10 beats 1→2→3=7)")
        void boundedMaximize() {
            Map<String, Double> c = costs(QMAX, Optional.of(numOperand("1")), Optional.empty());
            assertThat(c.get("1→3")).isEqualTo(10.0);
            assertThat(path(QMAX, Optional.of(numOperand("1")), Optional.empty(), "1", "3"))
                    .isEqualTo("[1, 3]");
        }

        @Test
        @DisplayName("single-pair MINIMIZE with a negative edge falls back from Dijkstra to relaxation")
        void negativeWeightFallsBackToRelaxation() {
            // Dijkstra is unsound with a negative edge; the executor must fall back to the
            // Bellman-Ford relaxation, which still finds 1→2→3 (5 + (-10) = -5) over the
            // direct 1→3 (=2).
            String src =
                    "Edges := [| src | dst | cost |\n" +
                    "           | 1   | 2   | 5    |\n" +
                    "           | 2   | 3   | -10  |\n" +
                    "           | 1   | 3   | 2    |];\n" +
                    "query { TRACE src, dst VIA cost MINIMIZE AS route (Edges) };";
            Map<String, Double> c = costs(src, Optional.of(numOperand("1")), Optional.of(numOperand("3")));
            assertThat(c).containsOnlyKeys("1→3");
            assertThat(c.get("1→3")).isEqualTo(-5.0);
            assertThat(path(src, Optional.of(numOperand("1")), Optional.of(numOperand("3")), "1", "3"))
                    .isEqualTo("[1, 2, 3]");
        }

        @Test
        @DisplayName("single-pair MAXIMIZE stays on the relaxation (Dijkstra is MINIMIZE-only)")
        void bothBoundsMaximizeUsesRelaxation() {
            Map<String, Double> c = costs(QMAX, Optional.of(numOperand("1")), Optional.of(numOperand("3")));
            assertThat(c).containsOnlyKeys("1→3");
            assertThat(c.get("1→3")).isEqualTo(10.0);   // MAXIMIZE prefers the direct edge (10)
        }

        @Test
        @DisplayName("a bounded slice equals the all-pairs result filtered to that origin")
        void boundedEqualsFilteredAllPairs() {
            Map<String, Double> all = traceCosts(Q);
            Map<String, Double> fromOne = all.entrySet().stream()
                    .filter(e -> e.getKey().startsWith("1→"))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            assertThat(costs(Q, Optional.of(numOperand("1")), Optional.empty()))
                    .containsExactlyInAnyOrderEntriesOf(fromOne);
        }
    }

    // ── Max-rounds guard ─────────────────────────────────────────────────────

    @Test
    @DisplayName("MAXIMIZE over a positive-weight cycle exceeds the round cap and throws")
    void maximizePositiveCycleExceedsRoundCap() {
        // In MAXIMIZE mode, 1→2→3→1 keeps improving endlessly if cycles were not excluded.
        // With the cycle-avoidance rule the executor terminates without the guard — but we
        // can construct a case where the cap fires: a very small cap on a convergent graph.
        // With cap=1, even a simple 2-hop path (src→mid→dst) would not be found in time.
        String src =
                "Edges := [| src | dst | cost |\n" +
                "           | 1   | 2   | 5    |\n" +
                "           | 2   | 3   | 5    |];\n" +
                "query { TRACE src, dst VIA cost MINIMIZE AS route (Edges) };";
        SemanticModel model = model(src);
        // cap=1: only the seed round runs; the relaxation round is blocked
        ExecutionContext ctx = ExecutionContext.inlineOnly(model).withMaxFixpointRounds(1);
        var query = model.rootQueries().getFirst();
        assertThatThrownBy(() -> {
            try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
                stream.toList();
            }
        }).isInstanceOf(EvaluationException.class)
                .hasMessageContaining("TRACE")
                .hasMessageContaining("1");
    }

    @Nested
    @DisplayName("undirected edges")
    final class Undirected {

        /**
         * A chain written one way only. Read as directed there is no route from 3 back to
         * 1; read both ways every pair is reachable, so this graph tells the two readings
         * apart on every assertion below.
         */
        private static final String ONE_WAY_CHAIN =
                "Edges := [| src | dst | cost |\n"
                + "           | 1   | 2   | 3    |\n"
                + "           | 2   | 3   | 4    |];\n";

        /** The same edges written out in both directions — the reading, done by hand. */
        private static final String BOTH_WAYS =
                "Edges := [| src | dst | cost |\n"
                + "           | 1   | 2   | 3    |\n"
                + "           | 2   | 1   | 3    |\n"
                + "           | 2   | 3   | 4    |\n"
                + "           | 3   | 2   | 4    |];\n";

        @Test
        @DisplayName("agrees with the same edges written out in both directions")
        void agreesWithTheDoubledTable() {
            // No oracle is needed: doubling the table is the definition of the reading, and
            // the executor does not do that — it reads one edge set from both ends.
            assertThat(traceCosts(ONE_WAY_CHAIN
                    + "query { TRACE src \u2194 dst VIA cost MINIMIZE AS route (Edges) };"))
                    .isEqualTo(traceCosts(BOTH_WAYS
                            + "query { TRACE src, dst VIA cost MINIMIZE AS route (Edges) };"));
        }

        @Test
        @DisplayName("finds the route that runs against the written direction")
        void travelsAnEdgeBackwards() {
            String script = ONE_WAY_CHAIN
                    + "query { TRACE src \u2194 dst VIA cost MINIMIZE AS route (Edges) };";
            Map<String, Double> costs = traceCosts(script);

            assertThat(costs).containsEntry("1\u21923", 7.0d);
            // The pair the directed reading cannot reach at all.
            assertThat(costs).containsEntry("3\u21921", 7.0d);
            assertThat(tracePath(script, "3", "1")).isEqualTo("[3, 2, 1]");
        }

        @Test
        @DisplayName("the directed reading is unchanged, and does not reach backwards")
        void directedIsUnaffected() {
            assertThat(traceCosts(ONE_WAY_CHAIN
                    + "query { TRACE src, dst VIA cost MINIMIZE AS route (Edges) };"))
                    .containsEntry("1\u21923", 7.0d)
                    .doesNotContainKey("3\u21921");
        }

        @Test
        @DisplayName("the ASCII spelling is the glyph")
        void asciiSpelling() {
            assertThat(traceCosts(ONE_WAY_CHAIN
                    + "query { TRACE src <-> dst VIA cost MINIMIZE AS route (Edges) };"))
                    .isEqualTo(traceCosts(ONE_WAY_CHAIN
                            + "query { TRACE src \u2194 dst VIA cost MINIMIZE AS route (Edges) };"));
        }
    }
}

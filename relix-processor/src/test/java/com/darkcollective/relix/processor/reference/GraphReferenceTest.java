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

import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.ClosureNode;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.processor.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.QueryExecutor;
import com.darkcollective.relix.processor.QueryResult;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.exec.RelNodeExecutor;
import com.darkcollective.relix.processor.reference.GraphReference.Pair;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.rel;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The graph operators against independent definitions, over generated graphs.
 *
 * <p>These are the operators with no external oracle at all. No SQL backend has CLOSURE,
 * CLUSTER or PATH, so the three pushdown agreement suites cannot reach them, and what
 * asserts them otherwise is rows somebody wrote out by hand — a regression test, which
 * says the answer stopped changing rather than that it started right.
 *
 * <p>The inputs are drawn rather than chosen because the interesting cases are shapes, not
 * examples: a cycle, a self-loop, two components, a node reachable two ways at different
 * lengths, a chain long enough that transitivity has somewhere to go. Those are
 * combinations of edges and a person writing them out picks the ones they already thought
 * of. A seed is named rather than taken from the clock — a generated-input test that fails
 * differently each run is worse than none.
 *
 * <p>Four claims per graph, and a fifth that is agreement between two engine paths rather
 * than against the definition: a {@code FIX} whose step spells out transitive closure must
 * give what the purpose-built operator gives. They share no code — one is
 * {@code RecursionExecutor}'s closure, the other the general fixpoint driver with its
 * spooled sub-plans and its build side hoisted out of the loop — so the agreement is
 * worth something on its own.
 */
@DisplayName("The graph operators against independent definitions")
final class GraphReferenceTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    /** Named, never from the clock. */
    private static final long SEED = 20_260_914L;

    private static final int DRAWS = 150;

    /** Five nodes: small enough that a draw is usually connected, big enough for a 4-hop path. */
    private static final int NODES = 5;

    // ── running a query ───────────────────────────────────────────────────────

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget named -> rel(named.name());
            case ExpressionQueryTarget e -> e.expression();
        };
    }

    /** Runs {@code script}'s one query and hands every row to {@code reader}. */
    private static void each(String script, Consumer<Row> reader) {
        SemanticModel model = model(script);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        try (Stream<Row> rows = EXECUTOR.execute(queryNode(model.rootQueries().getFirst()), ctx)) {
            rows.forEach(reader);
        }
    }

    /**
     * A drawn relation: the rows it holds, and the subset of them that are actually edges.
     *
     * <p>The two differ because a row may have a missing endpoint, and such a row is not
     * an edge — so it is in {@code rows}, which becomes the table, and not in
     * {@code edges}, which is what the definitions are given. That split <em>is</em> the
     * claim about nulls: stating it here rather than inside the oracle keeps the oracle
     * the textbook definition and puts the engine's rule where it can be disagreed with.
     */
    private record Drawn(List<String[]> rows, List<Pair> edges) {
    }

    /** The relation as an inline table, which is the one source that needs nothing outside. */
    private static String edges(Drawn drawn) {
        StringBuilder table = new StringBuilder("Edges := [| src | dst |\n");
        drawn.rows().forEach(r -> table.append("| ").append(r[0]).append(" | ").append(r[1]).append(" |\n"));
        return table.append("];\n").toString();
    }

    private static int number(Row row, String column) {
        return Integer.parseInt(row.get(column).asDisplayString());
    }

    private static Set<Pair> pairs(String script) {
        Set<Pair> found = new LinkedHashSet<>();
        each(script, row -> found.add(new Pair(number(row, "src"), number(row, "dst"))));
        return found;
    }

    /** The CLUSTER answer as node → component label. */
    private static Map<Integer, Integer> labels(String script) {
        Map<Integer, Integer> found = new LinkedHashMap<>();
        each(script, row -> found.put(number(row, "src"), number(row, "cid")));
        return found;
    }

    /** The PATH answer as pair → hop distance. */
    private static Map<Pair, Integer> hops(String script) {
        Map<Pair, Integer> found = new LinkedHashMap<>();
        each(script, row -> found.put(new Pair(number(row, "src"), number(row, "dst")),
                number(row, "depth")));
        return found;
    }

    // ── the draw ──────────────────────────────────────────────────────────────

    /**
     * A graph where each ordered pair is an edge with probability one in three.
     *
     * <p>Every ordered pair, so a self-loop and a two-cycle are both ordinary draws rather
     * than cases somebody remembered to add. One in three rather than a coin toss because
     * at even odds five nodes are almost always one strongly connected component, and a
     * closure that reaches everything from everything tests very little.
     */
    private static Drawn draw(Random rng) {
        List<String[]> rows = new ArrayList<>();
        List<Pair> graph = new ArrayList<>();
        for (int from = 1; from <= NODES; from++) {
            for (int to = 1; to <= NODES; to++) {
                if (rng.nextInt(3) == 0) {
                    rows.add(new String[] {String.valueOf(from), String.valueOf(to)});
                    graph.add(new Pair(from, to));
                }
            }
        }
        // And sometimes a row with one end missing. Drawn from the same nodes, so it lands
        // both on a node that has real edges (where dropping the row changes nothing) and
        // on one that has none (where counting it invents a node).
        for (int i = 0; i < 2; i++) {
            if (rng.nextInt(3) == 0) {
                String node = String.valueOf(rng.nextInt(NODES) + 1);
                rows.add(rng.nextBoolean() ? new String[] {node, ""} : new String[] {"", node});
            }
        }
        return new Drawn(rows, graph);
    }

    @Test
    @DisplayName("150 generated graphs: closure, reflexive closure, components and distance all agree")
    void agreesWithTheDefinitions() {
        Random rng = new Random(SEED);
        int transitiveDraws = 0;
        int multiComponentDraws = 0;
        int cyclicDraws = 0;
        int danglingDraws = 0;

        for (int draw = 0; draw < DRAWS; draw++) {
            Drawn drawn = draw(rng);
            List<Pair> graph = drawn.edges();
            if (graph.isEmpty()) {
                continue;   // an edgeless relation is its own case, below
            }
            String table = edges(drawn);
            String as = "rows " + render(drawn);
            if (drawn.rows().size() > graph.size()) {
                danglingDraws++;
            }

            Set<Pair> reachable = GraphReference.closure(graph);
            if (reachable.size() > graph.size()) {
                transitiveDraws++;
            }
            if (reachable.stream().anyMatch(p -> p.from() == p.to())) {
                cyclicDraws++;
            }
            assertThat(pairs(table + "query { CLOSURE src, dst (Edges) };"))
                    .as("CLOSURE — %s", as)
                    .containsExactlyInAnyOrderElementsOf(reachable);

            assertThat(pairs(table + "query { RCLOSURE src, dst (Edges) };"))
                    .as("RCLOSURE — %s", as)
                    .containsExactlyInAnyOrderElementsOf(GraphReference.reflexiveClosure(graph));

            Map<Integer, Integer> expectedLabels = GraphReference.components(graph);
            if (expectedLabels.values().stream().distinct().count() > 1) {
                multiComponentDraws++;
            }
            assertThat(labels(table + "query { CLUSTER src, dst AS cid (Edges) };"))
                    .as("CLUSTER — %s", as)
                    .containsExactlyInAnyOrderEntriesOf(expectedLabels);

            Map<Pair, Integer> shortest = GraphReference.shortestHops(graph, NODES);
            for (int[] window : new int[][] {{1, 1}, {1, 2}, {2, 3}, {1, NODES}}) {
                Map<Pair, Integer> expected = new LinkedHashMap<>();
                shortest.forEach((pair, hops) -> {
                    if (hops >= window[0] && hops <= window[1]) {
                        expected.put(pair, hops);
                    }
                });
                assertThat(hops(table + "query { PATH src, dst HOPS " + window[0] + " TO "
                                + window[1] + " AS depth (Edges) };"))
                        .as("PATH HOPS %d TO %d — %s", window[0], window[1], as)
                        .containsExactlyInAnyOrderEntriesOf(expected);
            }
        }

        // A search that only ever saw trivial graphs would pass while checking nothing.
        assertThat(transitiveDraws)
                .as("draws where closure found a pair no single edge gives")
                .isGreaterThan(DRAWS / 2);
        assertThat(multiComponentDraws)
                .as("draws with more than one component — a labelling rule needs something to order")
                .isGreaterThan(DRAWS / 20);
        assertThat(cyclicDraws)
                .as("draws with a cycle — the case closure has to terminate on")
                .isGreaterThan(DRAWS / 2);
        assertThat(danglingDraws)
                .as("draws carrying a row that is not an edge, because one end is missing")
                .isGreaterThan(DRAWS / 5);
    }

    /** The drawn rows as text, with a missing endpoint shown as {@code _}. */
    private static String render(Drawn drawn) {
        return drawn.rows().stream()
                .map(r -> (r[0].isEmpty() ? "_" : r[0]) + "->" + (r[1].isEmpty() ? "_" : r[1]))
                .toList().toString();
    }

    @Test
    @DisplayName("a FIX spelling out transitive closure gives what CLOSURE gives")
    void theGeneralFixpointAgreesWithTheOperator() {
        Random rng = new Random(SEED);
        int checked = 0;

        for (int draw = 0; draw < 40; draw++) {
            Drawn drawn = draw(rng);
            List<Pair> graph = drawn.edges();
            if (graph.isEmpty()) {
                continue;
            }
            String table = edges(drawn);

            // The manual's own example over E rather than Edges. CLOSURE drops the rows
            // that are not edges as part of what it means; FIX is a general fixpoint over
            // whatever it is given, and the filter is needed on *both* sides — a dangling
            // row reaches the answer through the step's join as readily as through the
            // base. Naming the edge relation once is what makes the two the same query
            // rather than two that agree until the data has a hole in it.
            Set<Pair> fix = pairs(table + """
                    E := { σ src IS NOT NULL ∧ dst IS NOT NULL (Edges) };
                    query { FIX Reach (
                        π src, dst (E),
                        π src, dst2 → dst (Reach ⋈ π src → dst, dst → dst2 (E))
                    ) };
                    """);

            assertThat(fix)
                    .as("FIX vs CLOSURE — graph %s", graph)
                    .containsExactlyInAnyOrderElementsOf(
                            pairs(table + "query { CLOSURE src, dst (Edges) };"));
            assertThat(fix)
                    .as("FIX vs the definition — graph %s", graph)
                    .containsExactlyInAnyOrderElementsOf(GraphReference.closure(graph));
            checked++;
        }

        assertThat(checked).as("graphs actually compared").isGreaterThan(30);
    }

    @Test
    @DisplayName("a bound folded into the traversal selects a slice, it does not change it")
    void aFoldedBoundSelectsRatherThanChanges() {
        Random rng = new Random(SEED);
        int nonEmptySlices = 0;

        for (int draw = 0; draw < 30; draw++) {
            Drawn drawn = draw(rng);
            List<Pair> graph = drawn.edges();
            if (graph.isEmpty()) {
                continue;
            }
            String table = edges(drawn);

            for (boolean reflexive : new boolean[] {false, true}) {
                String operator = reflexive ? "RCLOSURE" : "CLOSURE";
                Set<Pair> all = reflexive
                        ? GraphReference.reflexiveClosure(graph)
                        : GraphReference.closure(graph);
                String script = table + "query { " + operator + " src, dst (Edges) };";

                for (int node = 1; node <= NODES; node++) {
                    // AstBuilders.num spelled out: ProcessorTestSupport.num builds a row *value*
                    // of the same spelling, and an inherited member wins over a static import.
                    Operand s = AstBuilders.num(String.valueOf(node));
                    int fixed = node;

                    nonEmptySlices += checkSlice(script, Optional.of(s), Optional.empty(),
                            all.stream().filter(p -> p.from() == fixed).toList(),
                            operator + " from " + fixed + " — " + render(drawn));

                    // The target-only bound is a different algorithm — a search backwards
                    // over a reversed adjacency, which nothing else in the executor builds.
                    nonEmptySlices += checkSlice(script, Optional.empty(), Optional.of(s),
                            all.stream().filter(p -> p.to() == fixed).toList(),
                            operator + " to " + fixed + " — " + render(drawn));

                    Operand other = AstBuilders.num(String.valueOf(NODES + 1 - node));
                    int target = NODES + 1 - node;
                    nonEmptySlices += checkSlice(script, Optional.of(s), Optional.of(other),
                            all.stream().filter(p -> p.from() == fixed && p.to() == target).toList(),
                            operator + " " + fixed + "→" + target + " — " + render(drawn));
                }
            }
        }

        assertThat(nonEmptySlices)
                .as("bounded searches that returned rows — every slice empty would pass "
                    + "while comparing nothing")
                .isGreaterThan(200);
    }

    /**
     * Runs the parsed closure with the bounds the optimizer would have folded in, and
     * checks it against the all-pairs answer restricted to the same slice.
     *
     * <p>relix-processor does not depend on the optimizer, so the bounds are applied
     * directly, as {@code SelectionIntoClosurePass} would apply them; {@code executeOptimized}
     * re-annotates the rewritten tree before planning.
     *
     * @return 1 if the slice had rows, 0 otherwise — the search's own non-vacuity count
     */
    private static int checkSlice(String script, Optional<Operand> from, Optional<Operand> to,
                                  List<Pair> expected, String as) {
        SemanticModel model = model(script);
        ClosureNode raw = (ClosureNode) queryNode(model.rootQueries().getFirst());
        List<QueryResult> results =
                new QueryExecutor().executeOptimized(model, List.of(raw.withBounds(from, to)));
        Set<Pair> actual = results.getFirst().rows().stream()
                .map(row -> new Pair(number(row, "src"), number(row, "dst")))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        assertThat(actual).as("%s", as).containsExactlyInAnyOrderElementsOf(expected);
        return expected.isEmpty() ? 0 : 1;
    }

    @Test
    @DisplayName("a null endpoint is not a node, and the four operators agree on that")
    void aNullEndpointIsNotANode() {
        // Node 3 appears only in a row whose other end is missing. A row missing an end is
        // not an edge, so 3 is not a node — and the four operators have to say so with one
        // voice, because they are being asked the same question about the same relation.
        String table = """
                Edges := [| src | dst |
                          | 1   | 2   |
                          | 3   |     |
                          | 4   | 5   |];
                """;

        assertThat(pairs(table + "query { CLOSURE src, dst (Edges) };"))
                .as("the dangling row contributes no pair")
                .containsExactlyInAnyOrder(new Pair(1, 2), new Pair(4, 5));

        assertThat(pairs(table + "query { RCLOSURE src, dst (Edges) };"))
                .as("no identity pair for 3 — RCLOSURE adds one per node, and 3 is not one")
                .containsExactlyInAnyOrder(new Pair(1, 2), new Pair(4, 5),
                        new Pair(1, 1), new Pair(2, 2), new Pair(4, 4), new Pair(5, 5));

        assertThat(labels(table + "query { CLUSTER src, dst AS cid (Edges) };"))
                .as("no component for 3 — and {4,5} keeps label 2, which a spurious "
                    + "singleton would have pushed to 3")
                .containsExactlyInAnyOrderEntriesOf(Map.of(1, 1, 2, 1, 4, 2, 5, 2));

        assertThat(hops(table + "query { PATH src, dst HOPS 1 TO 3 AS depth (Edges) };"))
                .as("no path through a row that is not an edge")
                .containsExactlyInAnyOrderEntriesOf(Map.of(new Pair(1, 2), 1, new Pair(4, 5), 1));
    }
}

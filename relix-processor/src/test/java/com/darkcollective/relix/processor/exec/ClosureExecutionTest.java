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
import com.darkcollective.relix.ast.ClosureNode;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.processor.internal.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.internal.QueryExecutor;
import com.darkcollective.relix.processor.internal.QueryResult;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RelNodeExecutor — transitive closure (CLOSURE / RCLOSURE)")
final class ClosureExecutionTest extends ProcessorTestSupport {

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

    /** Returns the closure pairs as "from->to" strings. */
    private static List<String> pairs(String src) {
        SemanticModel model = model(src);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        var query = model.rootQueries().getFirst();
        try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
            return stream
                    .map(r -> r.get("src").asDisplayString() + "->" + r.get("dst").asDisplayString())
                    .toList();
        }
    }

    private static final String CHAIN =
            "Edges := [| src | dst |\n" +
            "           | 1   | 2   |\n" +
            "           | 2   | 3   |\n" +
            "           | 3   | 4   |];\n";

    @Test
    @DisplayName("transitive closure of a chain reaches every downstream node")
    void chainTransitive() {
        assertThat(pairs(CHAIN + "query { CLOSURE src, dst (Edges) };"))
                .containsExactlyInAnyOrder(
                        "1->2", "2->3", "3->4",   // direct
                        "1->3", "2->4",           // two hops
                        "1->4");                  // three hops
    }

    @Test
    @DisplayName("a cyclic graph terminates and reaches every node (including itself)")
    void cycleTerminatesAndReachesAll() {
        String cycle =
                "Edges := [| src | dst |\n" +
                "           | 1   | 2   |\n" +
                "           | 2   | 3   |\n" +
                "           | 3   | 1   |];\n";
        // Every node reaches every node, including itself, via the 3-cycle.
        assertThat(pairs(cycle + "query { CLOSURE src, dst (Edges) };"))
                .containsExactlyInAnyOrder(
                        "1->1", "1->2", "1->3",
                        "2->1", "2->2", "2->3",
                        "3->1", "3->2", "3->3");
    }

    @Test
    @DisplayName("R⁺ does not add identity pairs for an acyclic graph")
    void transitiveHasNoSelfPairs() {
        assertThat(pairs(
                "E := [| src | dst |\n       | 1 | 2 |];\n" +
                "query { CLOSURE src, dst (E) };"))
                .containsExactly("1->2");
    }

    @Test
    @DisplayName("R* adds the identity pair for every node")
    void reflexiveAddsIdentity() {
        assertThat(pairs(
                "E := [| src | dst |\n       | 1 | 2 |];\n" +
                "query { RCLOSURE src, dst (E) };"))
                .containsExactlyInAnyOrder("1->2", "1->1", "2->2");
    }

    @Test
    @DisplayName("a self-loop edge yields the identity pair under R⁺")
    void selfLoopEdge() {
        assertThat(pairs(
                "E := [| src | dst |\n       | 1 | 1 |];\n" +
                "query { CLOSURE src, dst (E) };"))
                .containsExactly("1->1");
    }

    @Test
    @DisplayName("disconnected components do not produce cross pairs")
    void disconnectedComponents() {
        assertThat(pairs(
                "E := [| src | dst |\n" +
                "       | 1   | 2   |\n" +
                "       | 3   | 4   |];\n" +
                "query { CLOSURE src, dst (E) };"))
                .containsExactlyInAnyOrder("1->2", "3->4");
    }

    @Test
    @DisplayName("a duplicate edge is collapsed (set semantics)")
    void duplicateEdgeCollapsed() {
        assertThat(pairs(
                "E := [| src | dst |\n" +
                "       | 1   | 2   |\n" +
                "       | 1   | 2   |];\n" +
                "query { CLOSURE src, dst (E) };"))
                .containsExactly("1->2");
    }

    @Test
    @DisplayName("an edge with a null endpoint is skipped")
    void nullEndpointSkipped() {
        // The left-outer join leaves the unmatched A row's 'dst' null; that edge is dropped.
        assertThat(pairs(
                "A := [| src |\n       | 1 |\n       | 2 |];\n" +
                "B := [| src | dst |\n       | 1 | 3 |];\n" +
                "query { CLOSURE src, dst (A |>< A.src = B.src B) };"))
                .containsExactly("1->3");
    }

    @Test
    @DisplayName("an edge whose 'from' endpoint is null is also skipped")
    void nullFromEndpointSkipped() {
        // 'src' comes from the nullable (right) side, so the unmatched row's from is null.
        assertThat(pairs(
                "A := [| k | dst |\n       | 1 | 9 |\n       | 2 | 8 |];\n" +
                "B := [| k | src |\n       | 1 | 5 |];\n" +
                "query { CLOSURE src, dst (A |>< A.k = B.k B) };"))
                .containsExactly("5->9");
    }

    @Test
    @DisplayName("closure composes with a selection on its input")
    void closureOverFilteredInput() {
        // Keep only edges with weight 1, then close: 1→2→3 (the weight-2 edge 3→4 is dropped).
        assertThat(pairs(
                "E := [| src | dst | w |\n" +
                "       | 1   | 2   | 1 |\n" +
                "       | 2   | 3   | 1 |\n" +
                "       | 3   | 4   | 2 |];\n" +
                "query { CLOSURE src, dst (σ w = 1 (E)) };"))
                .containsExactlyInAnyOrder("1->2", "2->3", "1->3");
    }

    // ── Endpoint pushdown (ADR-0020 / #330) ──────────────────────────────────
    //
    // The optimizer folds a constant endpoint equality above a closure into its
    // boundSource/boundTarget, turning all-pairs reachability into single-source /
    // single-target / single-pair traversal. relix-processor does not depend on the
    // optimizer, so these tests build the bounded ClosureNode directly (as the
    // optimizer would) and run it through executeOptimized, which re-annotates the
    // rewritten tree before planning.

    @Nested
    @DisplayName("CLOSURE — endpoint pushdown (single-source / target / pair)")
    final class EndpointPushdown {

        // 1→2→3→4 and 2→5: from 1 everything is reachable; 1,2,3 all reach 4.
        private static final String GRAPH =
                "Edges := [| src | dst |\n" +
                "           | 1   | 2   |\n" +
                "           | 2   | 3   |\n" +
                "           | 3   | 4   |\n" +
                "           | 2   | 5   |];\n";

        /** Builds the bounded closure from {@code src}'s parsed CLOSURE and runs it. */
        private static List<String> bounded(String src, Optional<Operand> from, Optional<Operand> to) {
            SemanticModel model = model(src);
            var query = model.rootQueries().getFirst();
            ClosureNode raw = (ClosureNode) queryNode(query);
            RelNode boundedTree = raw.withBounds(from, to);
            List<QueryResult> results = new QueryExecutor().executeOptimized(model, List.of(boundedTree));
            return results.getFirst().rows().stream()
                    .map(r -> r.get("src").asDisplayString() + "->" + r.get("dst").asDisplayString())
                    .toList();
        }

        @Test
        @DisplayName("σ src = 1 → single-source: only pairs from node 1")
        void singleSource() {
            assertThat(bounded(GRAPH + "query { CLOSURE src, dst (Edges) };",
                    Optional.of(numOperand("1")), Optional.empty()))
                    .containsExactlyInAnyOrder("1->2", "1->3", "1->4", "1->5");
        }

        @Test
        @DisplayName("σ src = 2 → single-source from an interior node")
        void singleSourceInterior() {
            assertThat(bounded(GRAPH + "query { CLOSURE src, dst (Edges) };",
                    Optional.of(numOperand("2")), Optional.empty()))
                    .containsExactlyInAnyOrder("2->3", "2->4", "2->5");
        }

        @Test
        @DisplayName("σ dst = 4 → single-target: everything that reaches node 4")
        void singleTarget() {
            assertThat(bounded(GRAPH + "query { CLOSURE src, dst (Edges) };",
                    Optional.empty(), Optional.of(numOperand("4"))))
                    .containsExactlyInAnyOrder("1->4", "2->4", "3->4");
        }

        @Test
        @DisplayName("σ src = 1 ∧ dst = 4 → single-pair: just the one reachable pair")
        void singlePair() {
            assertThat(bounded(GRAPH + "query { CLOSURE src, dst (Edges) };",
                    Optional.of(numOperand("1")), Optional.of(numOperand("4"))))
                    .containsExactly("1->4");
        }

        @Test
        @DisplayName("single-pair to an unreachable target is empty")
        void singlePairUnreachable() {
            assertThat(bounded(GRAPH + "query { CLOSURE src, dst (Edges) };",
                    Optional.of(numOperand("4")), Optional.of(numOperand("1"))))
                    .isEmpty();
        }

        @Test
        @DisplayName("a source bound on a node absent from the graph yields nothing")
        void sourceAbsentFromGraph() {
            assertThat(bounded(GRAPH + "query { CLOSURE src, dst (Edges) };",
                    Optional.of(numOperand("99")), Optional.empty()))
                    .isEmpty();
        }

        @Test
        @DisplayName("RCLOSURE σ src = 1 → single-source plus the identity pair (1,1)")
        void reflexiveSingleSource() {
            assertThat(bounded(GRAPH + "query { RCLOSURE src, dst (Edges) };",
                    Optional.of(numOperand("1")), Optional.empty()))
                    .containsExactlyInAnyOrder("1->1", "1->2", "1->3", "1->4", "1->5");
        }

        @Test
        @DisplayName("RCLOSURE σ dst = 4 → single-target plus the identity pair (4,4)")
        void reflexiveSingleTarget() {
            assertThat(bounded(GRAPH + "query { RCLOSURE src, dst (Edges) };",
                    Optional.empty(), Optional.of(numOperand("4"))))
                    .containsExactlyInAnyOrder("1->4", "2->4", "3->4", "4->4");
        }

        @Test
        @DisplayName("RCLOSURE adds no identity pair for a node the graph does not contain")
        void reflexiveBoundOnAnAbsentNode() {
            // The identity pair is conditional on the node actually being in the graph:
            // reflexivity is over the relation's own nodes, not over every value someone
            // can write. Adding (99,99) here would invent a row from nothing — and it is
            // the arm the CLOSURE absent-node test cannot reach, since that one stops at
            // the reflexive flag.
            assertThat(bounded(GRAPH + "query { RCLOSURE src, dst (Edges) };",
                    Optional.of(numOperand("99")), Optional.empty()))
                    .as("absent source").isEmpty();
            assertThat(bounded(GRAPH + "query { RCLOSURE src, dst (Edges) };",
                    Optional.empty(), Optional.of(numOperand("99"))))
                    .as("absent target").isEmpty();
        }

        @Test
        @DisplayName("a bounded slice equals the all-pairs result filtered to that endpoint")
        void boundedEqualsFilteredAllPairs() {
            // All-pairs from node 2, computed by the unbounded executor and filtered here,
            // must equal the single-source bounded result.
            List<String> allFromTwo = pairs(GRAPH + "query { CLOSURE src, dst (Edges) };")
                    .stream().filter(p -> p.startsWith("2->")).toList();
            assertThat(bounded(GRAPH + "query { CLOSURE src, dst (Edges) };",
                    Optional.of(numOperand("2")), Optional.empty()))
                    .containsExactlyInAnyOrderElementsOf(allFromTwo);
        }
    }

    // ── CLOSURE ≡ FIX equivalence suite ──────────────────────────────────────
    //
    // CLOSURE is the adjacency-specialised instance of the general semi-naïve
    // fixpoint (FIX).  These tests prove they produce identical sets of pairs
    // on four representative graph shapes (chain, cycle, dense/star,
    // null-endpoint).  Each test runs both forms and asserts they match.
    //
    // The FIX formulation:  FIX R (Edges, Edges ∪ π src,dst (ρ X(src,via)(R) ⋈ ρ Y(via,dst)(Edges)))
    // extends the accumulator by one hop per round.

    @Nested
    @DisplayName("CLOSURE ≡ FIX equivalence")
    final class EquivalenceWithFix {

        private static final String FIX_STEP =
                "Reach := { FIX R (\n" +
                "  Edges,\n" +
                "  Edges ∪ π src, dst (ρ X(src, via) (R) ⋈ ρ Y(via, dst) (Edges))\n" +
                ") };\n" +
                "query Reach;\n";

        private static List<String> fixPairs(String edgesDef) {
            return pairs(edgesDef + FIX_STEP);
        }

        private static List<String> closurePairs(String edgesDef) {
            return pairs(edgesDef + "query { CLOSURE src, dst (Edges) };\n");
        }

        @Test
        @DisplayName("chain graph: 1→2→3→4")
        void chain() {
            String edges =
                    "Edges := [| src | dst |\n" +
                    "           | 1   | 2   |\n" +
                    "           | 2   | 3   |\n" +
                    "           | 3   | 4   |];\n";
            assertThat(closurePairs(edges))
                    .containsExactlyInAnyOrderElementsOf(fixPairs(edges));
        }

        @Test
        @DisplayName("cyclic graph: 1→2→3→1")
        void cycle() {
            String edges =
                    "Edges := [| src | dst |\n" +
                    "           | 1   | 2   |\n" +
                    "           | 2   | 3   |\n" +
                    "           | 3   | 1   |];\n";
            assertThat(closurePairs(edges))
                    .containsExactlyInAnyOrderElementsOf(fixPairs(edges));
        }

        @Test
        @DisplayName("dense star graph: hub→spoke1..4 and spokes→hub")
        void dense() {
            // Hub node 0 with four spokes; every spoke can reach every other spoke via hub.
            String edges =
                    "Edges := [| src | dst |\n" +
                    "           | 0   | 1   |\n" +
                    "           | 0   | 2   |\n" +
                    "           | 0   | 3   |\n" +
                    "           | 0   | 4   |\n" +
                    "           | 1   | 0   |\n" +
                    "           | 2   | 0   |\n" +
                    "           | 3   | 0   |\n" +
                    "           | 4   | 0   |];\n";
            assertThat(closurePairs(edges))
                    .containsExactlyInAnyOrderElementsOf(fixPairs(edges));
        }

        // Null-endpoint equivalence is excluded: CLOSURE filters null endpoints during
        // execution, whereas the FIX seed includes the raw Edges rows (including nulls).
        // Null-endpoint behaviour is covered independently in nullEndpointSkipped() above.
    }
}

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

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.processor.internal.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution tests for the general fixpoint operator (FIX — ADR-0003, epic #46, #115).
 *
 * <p>Tests span: immediate convergence, multi-step accumulation, transitive closure,
 * cyclic-graph termination, empty base, duplicate collapsing, nested FIX, and
 * equivalence with the specialised CLOSURE operator.
 */
@DisplayName("RelNodeExecutor — general fixpoint (FIX)")
final class FixpointExecutionTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    // ── helpers ───────────────────────────────────────────────────────────────

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget   named -> rel(named.name());
            case ExpressionQueryTarget e  -> e.expression();
        };
    }

    /** Runs the first root query and returns each row as "col1=val1,col2=val2,…" strings. */
    private static List<String> rows(String src) {
        SemanticModel model = model(src);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        var query = model.rootQueries().getFirst();
        try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
            return stream.map(FixpointExecutionTest::rowStr).toList();
        }
    }

    /** Renders a row as "col=val,…" using the row's schema column order. */
    private static String rowStr(Row row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.width(); i++) {
            if (i > 0) sb.append(',');
            sb.append(row.get(i).asDisplayString());
        }
        return sb.toString();
    }

    /** Helper for single-column results. */
    private static List<String> singleCol(String src) {
        SemanticModel model = model(src);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        var query = model.rootQueries().getFirst();
        try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
            return stream.map(r -> r.get(0).asDisplayString()).toList();
        }
    }

    /** Helper for two-column "src->dst" results (like the closure tests). */
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

    // ── edge set used by several tests ─────────────────────────────────────────

    private static final String CHAIN_EDGES =
            "Edges := [| src | dst |\n" +
            "           | 1   | 2   |\n" +
            "           | 2   | 3   |\n" +
            "           | 3   | 4   |];\n";

    // FIX-based transitive closure: step extends current result by one hop via Edges.
    // ρ X(src, via)(R) ⋈ ρ Y(via, dst)(Edges) joins the accumulator to the edge set
    // on the shared "via" column; π src, dst projects the new transitive pairs.
    private static final String FIX_CLOSURE =
            "Reach := { FIX R (\n" +
            "  Edges,\n" +
            "  Edges ∪ π src, dst (ρ X(src, via) (R) ⋈ ρ Y(via, dst) (Edges))\n" +
            ") };\n" +
            "query Reach;\n";

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FIX with a step that adds no new rows converges immediately")
    void immediateConvergence() {
        // Edges ∪ R with R seeded from Edges: in the first step R = Edges,
        // so Edges ∪ R = Edges, next = Edges − Edges = ∅ → done in one round.
        String src = CHAIN_EDGES +
                "query { FIX R (Edges, Edges ∪ R) };\n";
        assertThat(pairs(src))
                .containsExactlyInAnyOrder("1->2", "2->3", "3->4");
    }

    @Test
    @DisplayName("FIX computes multi-step transitive closure (chain A→B→C→D)")
    void chainTransitiveClosure() {
        assertThat(pairs(CHAIN_EDGES + FIX_CLOSURE))
                .containsExactlyInAnyOrder(
                        "1->2", "2->3", "3->4",   // direct
                        "1->3", "2->4",           // two hops
                        "1->4");                  // three hops
    }

    @Test
    @DisplayName("FIX terminates on a cyclic graph and reaches every node")
    void cycleTerminates() {
        String cycle =
                "Edges := [| src | dst |\n" +
                "           | 1   | 2   |\n" +
                "           | 2   | 3   |\n" +
                "           | 3   | 1   |];\n";
        String query =
                "Reach := { FIX R (\n" +
                "  Edges,\n" +
                "  Edges ∪ π src, dst (ρ X(src, via) (R) ⋈ ρ Y(via, dst) (Edges))\n" +
                ") };\n" +
                "query Reach;\n";
        assertThat(pairs(cycle + query))
                .containsExactlyInAnyOrder(
                        "1->1", "1->2", "1->3",
                        "2->1", "2->2", "2->3",
                        "3->1", "3->2", "3->3");
    }

    @Test
    @DisplayName("FIX with empty base produces empty result")
    void emptyBase() {
        String src =
                "Empty := [| src | dst |];\n" +
                "query { FIX R (Empty, Empty ∪ R) };\n";
        assertThat(pairs(src)).isEmpty();
    }

    @Test
    @DisplayName("FIX SET semantics: duplicate rows in base and step are collapsed")
    void duplicatesCollapsed() {
        // Base contains a duplicate pair; the step re-generates it; output must be a set.
        String src =
                "Dup := [| src | dst |\n" +
                "         | 1   | 2   |\n" +
                "         | 1   | 2   |];\n" +
                "query { FIX R (Dup, Dup ∪ R) };\n";
        // Only one copy of (1,2) in the result.
        assertThat(pairs(src)).containsExactly("1->2");
    }

    @Test
    @DisplayName("FIX multi-step accumulation (Seeds ∪ More via fixed point)")
    void multiStepAccumulation() {
        // Base = {1,2}; step = More ∪ R where More = {3,4}.
        // Round 1: R={1,2} → More ∪ {1,2} = {1,2,3,4} → next={3,4}
        // Round 2: R={3,4} → More ∪ {3,4} = {3,4} → next=∅ → done
        // Result = {1,2,3,4}
        String src =
                "Seeds := [| n |\n" +
                "           | 1 |\n" +
                "           | 2 |];\n" +
                "More  := [| n |\n" +
                "           | 3 |\n" +
                "           | 4 |];\n" +
                "query { FIX R (Seeds, More ∪ R) };\n";
        assertThat(singleCol(src)).containsExactlyInAnyOrder("1", "2", "3", "4");
    }

    @Test
    @DisplayName("FIX result equals CLOSURE for the same edge set")
    void equivalenceWithClosure() {
        // The FIX-based transitive closure must produce the same pairs as CLOSURE.
        List<String> fixPairs  = pairs(CHAIN_EDGES + FIX_CLOSURE);
        List<String> closurePairs = pairs(CHAIN_EDGES + "query { CLOSURE src, dst (Edges) };\n");
        assertThat(fixPairs).containsExactlyInAnyOrderElementsOf(closurePairs);
    }

    @Test
    @DisplayName("nested FIX nodes with different names bind independently")
    void nestedFix() {
        // Outer FIX (name=Outer) accumulates {1,2}; Inner FIX (name=Inner)
        // is used inside the outer step as an inline sub-computation.
        // The inner FIX should not interfere with the outer binding.
        String src =
                "A := [| n |\n" +
                "       | 1 |];\n" +
                "B := [| n |\n" +
                "       | 2 |];\n" +
                // Inner: FIX Inner (B, B ∪ Inner) = B = {2}
                // Outer step: FIX Inner(B, B ∪ Inner) ∪ Outer — adds {2} in first round
                "query { FIX Outer (A, FIX Inner (B, B ∪ Inner) ∪ Outer) };\n";
        assertThat(singleCol(src)).containsExactlyInAnyOrder("1", "2");
    }

    @Test
    @DisplayName("FIX step may rename its columns: identity is positional, not by-name (issue #178)")
    void stepRenamesColumns() {
        // The step's final ρ relabels the output to (from, to) while the base is
        // (src, dst). FIX is union-compatible positionally (the validator never
        // matches names — same as ∪), and the output takes the base headings.
        // Before the fix, rowKey looked the step row's values up by the *base*
        // column names ("src"/"dst"), which the renamed step row lacks → it threw
        // IllegalArgumentException("No column 'src' in schema").
        String query =
                "Reach := { FIX R (\n" +
                "  Edges,\n" +
                "  ρ P(from, to) (\n" +
                "    Edges ∪ π src, dst (ρ X(src, via) (R) ⋈ ρ Y(via, dst) (Edges))\n" +
                "  )\n" +
                ") };\n" +
                "query Reach;\n";
        // Output schema is the base schema (src, dst); pairs are the full closure.
        assertThat(pairs(CHAIN_EDGES + query))
                .containsExactlyInAnyOrder(
                        "1->2", "2->3", "3->4",
                        "1->3", "2->4",
                        "1->4");
    }

    @Test
    @DisplayName("single-node diamond graph: FIX correctly handles fan-in")
    void diamondGraph() {
        // A→B, A→C, B→D, C→D — both B→D and C→D contribute to (A→D)
        String diamond =
                "Edges := [| src | dst |\n" +
                "           | 1   | 2   |\n" +
                "           | 1   | 3   |\n" +
                "           | 2   | 4   |\n" +
                "           | 3   | 4   |];\n";
        String query =
                "Reach := { FIX R (\n" +
                "  Edges,\n" +
                "  Edges ∪ π src, dst (ρ X(src, via) (R) ⋈ ρ Y(via, dst) (Edges))\n" +
                ") };\n" +
                "query Reach;\n";
        assertThat(pairs(diamond + query))
                .containsExactlyInAnyOrder(
                        "1->2", "1->3", "1->4",
                        "2->4",
                        "3->4");
    }
}

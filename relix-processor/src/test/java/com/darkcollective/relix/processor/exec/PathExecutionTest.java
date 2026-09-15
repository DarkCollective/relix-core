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
import com.darkcollective.relix.processor.ExecutionContext;
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

@DisplayName("RelNodeExecutor — bounded path (PATH)")
final class PathExecutionTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    // ── helpers ───────────────────────────────────────────────────────────────

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget   named -> rel(named.name());
            case ExpressionQueryTarget e  -> e.expression();
        };
    }

    /** Returns the reachable pairs as "from->to:depth" strings, in emitted order. */
    private static List<String> paths(String src) {
        SemanticModel model = model(src);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        var query = model.rootQueries().getFirst();
        try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
            return stream
                    .map(r -> r.get("src").asDisplayString() + "->"
                            + r.get("dst").asDisplayString() + ":"
                            + r.get("depth").asDisplayString())
                    .toList();
        }
    }

    private static final String CHAIN =
            "Edges := [| src | dst |\n" +
            "           | 1   | 2   |\n" +
            "           | 2   | 3   |\n" +
            "           | 3   | 4   |];\n";

    @Test
    @DisplayName("a chain reports each pair with its shortest hop distance")
    void chainDepthIncrements() {
        assertThat(paths(CHAIN + "query { PATH src, dst HOPS 1 TO 3 AS depth (Edges) };"))
                .containsExactly(
                        "1->2:1", "1->3:2", "1->4:3",
                        "2->3:1", "2->4:2",
                        "3->4:1");
    }

    @Test
    @DisplayName("the hop window excludes pairs shorter than the minimum")
    void windowExcludesBelowMinimum() {
        assertThat(paths(CHAIN + "query { PATH src, dst HOPS 2 TO 3 AS depth (Edges) };"))
                .containsExactly("1->3:2", "1->4:3", "2->4:2");
    }

    @Test
    @DisplayName("the hop window excludes pairs longer than the maximum")
    void windowExcludesAboveMaximum() {
        // 1→4 is 3 hops, excluded by a max of 2.
        assertThat(paths(CHAIN + "query { PATH src, dst HOPS 1 TO 2 AS depth (Edges) };"))
                .containsExactly("1->2:1", "1->3:2", "2->3:1", "2->4:2", "3->4:1");
    }

    @Test
    @DisplayName("the single-bound form HOPS n is shorthand for HOPS 1 TO n")
    void singleBoundForm() {
        assertThat(paths(CHAIN + "query { PATH src, dst HOPS 2 AS depth (Edges) };"))
                .isEqualTo(paths(CHAIN + "query { PATH src, dst HOPS 1 TO 2 AS depth (Edges) };"));
    }

    @Test
    @DisplayName("a direct edge wins over a longer alternative path (shortest distance)")
    void shortestDistanceWins() {
        // 1→3 exists directly (1 hop) as well as via 1→2→3 (2 hops); depth must be 1.
        String diamond =
                "Edges := [| src | dst |\n" +
                "           | 1   | 2   |\n" +
                "           | 2   | 3   |\n" +
                "           | 1   | 3   |];\n";
        assertThat(paths(diamond + "query { PATH src, dst HOPS 1 TO 2 AS depth (Edges) };"))
                .containsExactly("1->2:1", "1->3:1", "2->3:1");
    }

    @Test
    @DisplayName("a cyclic graph terminates within the hop bound")
    void cycleTerminates() {
        String cycle =
                "Edges := [| src | dst |\n" +
                "           | 1   | 2   |\n" +
                "           | 2   | 3   |\n" +
                "           | 3   | 1   |];\n";
        // Within 3 hops every ordered pair (incl. self-pairs via the 3-cycle) appears.
        assertThat(paths(cycle + "query { PATH src, dst HOPS 1 TO 3 AS depth (Edges) };"))
                .containsExactly(
                        "1->1:3", "1->2:1", "1->3:2",
                        "2->1:2", "2->2:3", "2->3:1",
                        "3->1:1", "3->2:2", "3->3:3");
    }

    @Test
    @DisplayName("an edge with a null endpoint is skipped")
    void nullEndpointSkipped() {
        // The left-outer join leaves the unmatched A row's 'dst' null; that edge is dropped.
        assertThat(paths(
                "A := [| src |\n       | 1 |\n       | 2 |];\n" +
                "B := [| src | dst |\n       | 1 | 3 |];\n" +
                "query { PATH src, dst HOPS 1 TO 2 AS depth (A |>< A.src = B.src B) };"))
                .containsExactly("1->3:1");
    }

    @Test
    @DisplayName("output is reproducible regardless of input row order")
    void deterministicOutput() {
        String forward =
                "Edges := [| src | dst |\n" +
                "           | 1   | 2   |\n" +
                "           | 2   | 3   |];\n";
        String reversed =
                "Edges := [| src | dst |\n" +
                "           | 2   | 3   |\n" +
                "           | 1   | 2   |];\n";
        List<String> a = paths(forward  + "query { PATH src, dst HOPS 1 TO 2 AS depth (Edges) };");
        List<String> b = paths(reversed + "query { PATH src, dst HOPS 1 TO 2 AS depth (Edges) };");
        assertThat(a).isEqualTo(b);
        assertThat(a).containsExactly("1->2:1", "1->3:2", "2->3:1");
    }

    @Test
    @DisplayName("scoping to a start node via σ keeps only that origin's reachable set")
    void startNodeViaSelection() {
        // The RA-native way to ask 'within N hops of node 1': compose a selection.
        assertThat(paths(CHAIN +
                "query { σ src = 1 (PATH src, dst HOPS 1 TO 3 AS depth (Edges)) };"))
                .containsExactly("1->2:1", "1->3:2", "1->4:3");
    }

    @Test
    @DisplayName("a NULL endpoint is not a graph node, on either side of the edge")
    void nullEndpointsAreNotEdges() {
        // Both halves of the guard are separate arms: a NULL source and a NULL target.
        // Neither may contribute a node, or the traversal would grow a phantom hub that
        // joins every row carrying a missing value to every other.
        String withNulls =
                "Edges := [| src  | dst  |\n" +
                "           | 1    | 2    |\n" +
                "           | NULL | 3    |\n" +
                "           | 2    | NULL |\n" +
                "           | 2    | 3    |];\n";
        assertThat(paths(withNulls + "query { PATH src, dst HOPS 1 TO 3 AS depth (Edges) };"))
                .containsExactly("1->2:1", "1->3:2", "2->3:1");
    }

    @Test
    @DisplayName("the traversal stops when the frontier empties, before the hop ceiling")
    void frontierEmptiesBeforeTheCeiling() {
        // A two-hop graph asked for up to ten: the loop's other exit condition. Without
        // it the walk would keep sweeping an empty frontier to the ceiling.
        String shortChain =
                "Edges := [| src | dst |\n" +
                "           | 1   | 2   |\n" +
                "           | 2   | 3   |];\n";
        assertThat(paths(shortChain + "query { PATH src, dst HOPS 1 TO 10 AS depth (Edges) };"))
                .containsExactly("1->2:1", "1->3:2", "2->3:1");
    }

    @Test
    @DisplayName("a pair shorter than the minimum is discovered and then withheld")
    void pairsBelowTheMinimumAreWithheld() {
        // The window is a pair of bounds and each rejects on its own. HOPS 3 TO 3 over a
        // chain of three edges finds the 1- and 2-hop pairs on the way and must emit
        // neither — a rule that read only the ceiling would return all of them.
        assertThat(paths(CHAIN + "query { PATH src, dst HOPS 3 TO 3 AS depth (Edges) };"))
                .containsExactly("1->4:3");
    }
}

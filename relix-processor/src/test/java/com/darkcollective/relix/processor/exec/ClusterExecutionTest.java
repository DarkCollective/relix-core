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

@DisplayName("RelNodeExecutor — connected components (CLUSTER)")
final class ClusterExecutionTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    // ── helpers ───────────────────────────────────────────────────────────────

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget   named -> rel(named.name());
            case ExpressionQueryTarget e  -> e.expression();
        };
    }

    /** Returns the cluster labelling as "node:label" strings, in emitted order. */
    private static List<String> labels(String src) {
        SemanticModel model = model(src);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        var query = model.rootQueries().getFirst();
        try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
            return stream
                    .map(r -> r.get("src").asDisplayString() + ":" + r.get("cid").asDisplayString())
                    .toList();
        }
    }

    @Test
    @DisplayName("two disjoint edges form two components with canonical 1-based labels")
    void twoComponents() {
        String edges =
                "Edges := [| src | dst |\n" +
                "           | 1   | 2   |\n" +
                "           | 3   | 4   |];\n";
        // Component {1,2} has min 1 → label 1; {3,4} has min 3 → label 2.
        assertThat(labels(edges + "query { CLUSTER src, dst AS cid (Edges) };"))
                .containsExactly("1:1", "2:1", "3:2", "4:2");
    }

    @Test
    @DisplayName("a transitive chain collapses into a single component")
    void singleComponentChain() {
        String edges =
                "Edges := [| src | dst |\n" +
                "           | 1   | 2   |\n" +
                "           | 2   | 3   |\n" +
                "           | 3   | 4   |];\n";
        assertThat(labels(edges + "query { CLUSTER src, dst AS cid (Edges) };"))
                .containsExactly("1:1", "2:1", "3:1", "4:1");
    }

    @Test
    @DisplayName("edges are undirected — reversed direction yields the same component")
    void undirected() {
        String edges =
                "Edges := [| src | dst |\n" +
                "           | 2   | 1   |\n" +
                "           | 4   | 3   |\n" +
                "           | 3   | 2   |];\n";
        // 2-1, 4-3, 3-2 connects all four nodes regardless of direction.
        assertThat(labels(edges + "query { CLUSTER src, dst AS cid (Edges) };"))
                .containsExactly("1:1", "2:1", "3:1", "4:1");
    }

    @Test
    @DisplayName("labels are canonical regardless of input row order")
    void deterministicLabels() {
        String forward =
                "Edges := [| src | dst |\n" +
                "           | 1   | 2   |\n" +
                "           | 3   | 4   |];\n";
        String reversed =
                "Edges := [| src | dst |\n" +
                "           | 3   | 4   |\n" +
                "           | 1   | 2   |];\n";
        List<String> a = labels(forward  + "query { CLUSTER src, dst AS cid (Edges) };");
        List<String> b = labels(reversed + "query { CLUSTER src, dst AS cid (Edges) };");
        assertThat(a).isEqualTo(b);
        assertThat(a).containsExactly("1:1", "2:1", "3:2", "4:2");
    }

    @Test
    @DisplayName("a cyclic component terminates and is one component")
    void cycle() {
        String edges =
                "Edges := [| src | dst |\n" +
                "           | 1   | 2   |\n" +
                "           | 2   | 3   |\n" +
                "           | 3   | 1   |];\n";
        assertThat(labels(edges + "query { CLUSTER src, dst AS cid (Edges) };"))
                .containsExactly("1:1", "2:1", "3:1");
    }

    @Test
    @DisplayName("string node ids cluster and order lexicographically")
    void stringNodes() {
        String edges =
                "Edges := [| src | dst |\n" +
                "           | \"alice\" | \"bob\"   |\n" +
                "           | \"carol\" | \"dave\"  |];\n";
        // StringValue.asDisplayString() quotes the value.
        assertThat(labels(edges + "query { CLUSTER src, dst AS cid (Edges) };"))
                .containsExactly("\"alice\":1", "\"bob\":1", "\"carol\":2", "\"dave\":2");
    }
}

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
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RelNodeExecutor — adjacency-to-forest nesting (TREE)")
final class TreeExecutionTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget   named -> rel(named.name());
            case ExpressionQueryTarget e  -> e.expression();
        };
    }

    private static List<Row> run(String src) {
        SemanticModel model = model(src);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        var query = model.rootQueries().getFirst();
        try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
            return stream.toList();
        }
    }

    @Test
    @DisplayName("one row per root; the subtree is nested in the children column")
    void nestsSubtreeUnderRoot() {
        // parent 0 is absent from the key set, so node 1 is the (only) root.
        String src =
                "Nodes := [| id | parent | name  |\n" +
                "          | 1  | 0      | root  |\n" +
                "          | 2  | 1      | a     |\n" +
                "          | 3  | 1      | b     |\n" +
                "          | 4  | 2      | leaf  |];\n" +
                "query { TREE id BY parent ORDER id AS children (Nodes) };";
        List<Row> rows = run(src);
        assertThat(rows).hasSize(1);
        Row root = rows.getFirst();
        assertThat(root).hasValue("id", "1")
                .hasValue("name", "root");
        // children = [ {id:2 … children:[{id:4 … children:[]}]}, {id:3 … children:[]} ]
        String children = root.get("children").asDisplayString();
        assertThat(children)
                .contains("id: 2")
                .contains("id: 3")
                .contains("id: 4")
                // node 4 is nested under node 2, not a sibling of the roots
                .containsPattern("id: 2.*id: 4");
    }

    @Test
    @DisplayName("a leaf carries an empty children array")
    void leafHasEmptyChildren() {
        String src =
                "Nodes := [| id | parent |\n" +
                "          | 1  | 0      |];\n" +
                "query { TREE id BY parent AS children (Nodes) };";
        List<Row> rows = run(src);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).hasValue("children", "[]");
    }

    @Test
    @DisplayName("multiple roots produce a forest — one row each")
    void forestOfRoots() {
        String src =
                "Nodes := [| id | parent |\n" +
                "          | 1  | 0      |\n" +
                "          | 2  | 0      |\n" +
                "          | 3  | 1      |];\n" +
                "query { TREE id BY parent ORDER id AS children (Nodes) };";
        List<Row> rows = run(src);
        assertThat(rows.stream().map(r -> r.get("id").asDisplayString()).toList())
                .containsExactly("1", "2");
    }

    @Test
    @DisplayName("ORDER … DESC controls sibling order within a level")
    void descendingSiblingOrder() {
        String src =
                "Nodes := [| id | parent |\n" +
                "          | 1  | 0      |\n" +
                "          | 2  | 1      |\n" +
                "          | 3  | 1      |];\n" +
                "query { TREE id BY parent ORDER id DESC AS children (Nodes) };";
        String children = run(src).getFirst().get("children").asDisplayString();
        // id 3 must appear before id 2 in the children array
        assertThat(children).containsPattern("id: 3.*id: 2");
    }

    @Test
    @DisplayName("a NULL parent is a root — the arm an absent-parent fixture does not reach")
    void nullParentIsARoot() {
        // The root test is three separate conditions: a null parent, an absent one, and
        // one naming a key nothing carries. The existing fixtures use the third (parent 0
        // is not in the key set); a genuine NULL takes the first.
        String src =
                "Nodes := [| id | parent |\n" +
                "          | 1  | NULL   |\n" +
                "          | 2  | 1      |];\n" +
                "query { TREE id BY parent AS children (Nodes) };";
        List<Row> rows = run(src);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).hasValue("id", "1");
        assertThat(rows.getFirst().get("children").asDisplayString()).contains("id: 2");
    }

    @Test
    @DisplayName("a NULL key has no identity — it can only ever be a leaf")
    void nullKeyIsAlwaysALeaf() {
        // Such a row is not indexed, so nothing can name it as a parent; it still appears
        // as a root of its own, with no children, rather than being dropped.
        String src =
                "Nodes := [| id   | parent |\n" +
                "          | 1    | NULL   |\n" +
                "          | NULL | 1      |];\n" +
                "query { TREE id BY parent AS children (Nodes) };";
        List<Row> rows = run(src);
        assertThat(rows).hasSize(1);
        Row root = rows.getFirst();
        assertThat(root).hasValue("id", "1");
        assertThat(root.get("children").asDisplayString())
                .as("the NULL-keyed row is woven in as a child, and carries no children")
                .contains("children: []");
    }

    @Test
    @DisplayName("two NULL keys are not duplicates of each other")
    void twoNullKeysAreNotDuplicates() {
        // The duplicate check runs over the indexed keys only, and a NULL key is never
        // indexed — so two of them coexist where two 1s would be a key violation.
        String src =
                "Nodes := [| id   | parent |\n" +
                "          | NULL | NULL   |\n" +
                "          | NULL | NULL   |];\n" +
                "query { TREE id BY parent AS children (Nodes) };";
        assertThat(run(src)).hasSize(2);
    }

    @Test
    @DisplayName("a duplicate key is a key violation")
    void duplicateKeyErrors() {
        String src =
                "Nodes := [| id | parent |\n" +
                "          | 1  | 0      |\n" +
                "          | 1  | 0      |];\n" +
                "query { TREE id BY parent AS children (Nodes) };";
        assertThatThrownBy(() -> run(src))
                .isInstanceOf(EvaluationException.class)
                .hasMessageContaining("duplicate key");
    }

    @Test
    @DisplayName("a cycle in the key→parent graph is reported, not looped forever")
    void cycleErrors() {
        // 1→2 and 2→1: neither is a root, so the whole component is a cycle.
        String src =
                "Nodes := [| id | parent |\n" +
                "          | 1  | 2      |\n" +
                "          | 2  | 1      |];\n" +
                "query { TREE id BY parent AS children (Nodes) };";
        assertThatThrownBy(() -> run(src))
                .isInstanceOf(EvaluationException.class)
                .hasMessageContaining("cycle");
    }
}

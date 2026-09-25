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

@DisplayName("RelNodeExecutor — outer-union (⊔ / OUNION)")
final class OuterUnionExecutionTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget   named -> rel(named.name());
            case ExpressionQueryTarget e  -> e.expression();
        };
    }

    private static List<Row> collect(String src) {
        SemanticModel model = model(src);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        var query = model.rootQueries().getFirst();
        try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
            return stream.toList();
        }
    }

    /** Reads a column, rendering an absent/NULL value as the string "null". */
    private static String cell(Row row, String column) {
        return row.get(column).isNull() ? "null" : row.get(column).asDisplayString();
    }

    @Test
    @DisplayName("heterogeneous schemas: common columns align, the rest NULL-pad")
    void mergesHeterogeneousSchemas() {
        var rows = collect(
                "A := [| id | name  |\n" +
                "       | 1  | Alice |];\n" +
                "B := [| id | city   |\n" +
                "       | 2  | Berlin |];\n" +
                "query { A ⊔ B };");

        // Merged schema is (id, name, city).
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().schema().columns())
                .extracting(c -> c.name())
                .containsExactly("id", "name", "city");

        // Alice's row carries id+name, NULL city; Berlin's row carries id+city, NULL name.
        assertThat(rows).extracting(r -> cell(r, "id") + "/" + cell(r, "name") + "/" + cell(r, "city"))
                .containsExactlyInAnyOrder("1/Alice/null", "2/null/Berlin");
    }

    @Test
    @DisplayName("identical schemas degenerate to a plain set union (dedup)")
    void identicalSchemasDeduplicate() {
        var rows = collect(
                "A := [| id | name  |\n" +
                "       | 1  | Alice |\n" +
                "       | 2  | Bob   |];\n" +
                "B := [| id | name  |\n" +
                "       | 2  | Bob   |\n" +
                "       | 3  | Carol |];\n" +
                "query { A ⊔ B };");

        assertThat(rows).extracting(r -> r.get("name").asDisplayString())
                .containsExactlyInAnyOrder("Alice", "Bob", "Carol");
    }

    @Test
    @DisplayName("fully disjoint columns NULL-pad both ways")
    void fullyDisjointColumns() {
        var rows = collect(
                "A := [| a |\n" +
                "       | 1 |];\n" +
                "B := [| b |\n" +
                "       | 2 |];\n" +
                "query { A ⊔ B };");

        assertThat(rows.getFirst().schema().columns())
                .extracting(c -> c.name()).containsExactly("a", "b");
        assertThat(rows).extracting(r -> cell(r, "a") + "/" + cell(r, "b"))
                .containsExactlyInAnyOrder("1/null", "null/2");
    }

    @Test
    @DisplayName("ASCII keyword OUNION behaves identically to the glyph")
    void keywordFormWorks() {
        var rows = collect(
                "A := [| id | name  |\n" +
                "       | 1  | Alice |];\n" +
                "B := [| id | city   |\n" +
                "       | 2  | Berlin |];\n" +
                "query { A OUNION B };");

        assertThat(rows).hasSize(2);
    }
}

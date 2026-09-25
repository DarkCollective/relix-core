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
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Struct & array construction execution")
final class ConstructionExecutionTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget named -> rel(named.name());
            case ExpressionQueryTarget e -> e.expression();
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

    @Test
    @DisplayName("struct construction yields a StructValue with named fields")
    void structConstruction() {
        var rows = collect(
                "Users := [| id | name | age |\n" +
                "           | 1  | Alice | 30 |];\n" +
                "query { π id, { full: name, years: age } → person (Users) };");

        assertThat(rows).hasSize(1);
        Object person = rows.get(0).get("person");
        assertThat(person).isInstanceOf(StructValue.class);
        StructValue s = (StructValue) person;
        assertThat(s.field("full")).map(v -> v.asDisplayString()).contains("Alice");
        assertThat(s.field("years")).map(v -> v.asDisplayString()).contains("30");
    }

    @Test
    @DisplayName("shorthand struct field { name } uses the attribute as both key and value")
    void structShorthand() {
        var rows = collect(
                "Users := [| name | age |\n" +
                "           | Bob  | 25  |];\n" +
                "query { π { name, age } → p (Users) };");

        StructValue s = (StructValue) rows.get(0).get("p");
        assertThat(s.field("name")).map(v -> v.asDisplayString()).contains("Bob");
        assertThat(s.field("age")).map(v -> v.asDisplayString()).contains("25");
    }

    @Test
    @DisplayName("array construction yields an ArrayValue in element order")
    void arrayConstruction() {
        var rows = collect(
                "P := [| x | y |\n" +
                "       | 10 | 20 |];\n" +
                "query { π [x, y] → coords (P) };");

        Object coords = rows.get(0).get("coords");
        assertThat(coords).isInstanceOf(ArrayValue.class);
        assertThat(((ArrayValue) coords).asDisplayString()).isEqualTo("[10, 20]");
    }

    @Test
    @DisplayName("π struct then γ COLLECT reshapes flat rows into an array of structs")
    void collectOfStructsRoundTrip() {
        var rows = collect(
                "Orders := [| customer | order_id | amount |\n" +
                "            | alice | 1 | 10 |\n" +
                "            | bob   | 2 | 99 |\n" +
                "            | alice | 3 | 20 |];\n" +
                "Built := { π customer, { order_id, amount } → o (Orders) };\n" +
                "query { γ customer, COLLECT(o) → orders (Built) };");

        assertThat(rows).hasSize(2);
        var byCustomer = rows.stream().collect(java.util.stream.Collectors.toMap(
                r -> r.get("customer").asDisplayString(),
                r -> r.get("orders")));
        assertThat(byCustomer.get("alice")).isInstanceOf(ArrayValue.class);
        assertThat(byCustomer.get("alice").asDisplayString())
                .isEqualTo("[{order_id: 1, amount: 10}, {order_id: 3, amount: 20}]");
        assertThat(byCustomer.get("bob").asDisplayString())
                .isEqualTo("[{order_id: 2, amount: 99}]");
    }
}

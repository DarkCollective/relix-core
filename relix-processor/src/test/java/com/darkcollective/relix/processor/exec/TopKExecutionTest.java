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
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@DisplayName("RelNodeExecutor — top-k per group (TOP)")
final class TopKExecutionTest extends ProcessorTestSupport {

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
        QueryStatement query = model.rootQueries().getFirst();
        try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
            return stream.toList();
        }
    }

    private static Tuple row(String cid, String amount) {
        return tuple(cid, amount);
    }

    private static final String ORDERS =
            "Orders := [| customer_id | amount |\n" +
            "            | 1 | 100 |\n" +
            "            | 1 | 50  |\n" +
            "            | 1 | 200 |\n" +
            "            | 2 | 30  |\n" +
            "            | 2 | 40  |];\n";

    @Test
    @DisplayName("keeps the top-k rows per group by the sort spec (full rows out)")
    void topNByAmountDesc() {
        var rows = collect(ORDERS + "query { TOP 2 amount DESC PER customer_id (Orders) };");

        assertThat(rows).extracting(
                        r -> r.get("customer_id").asDisplayString(),
                        r -> r.get("amount").asDisplayString())
                .containsExactlyInAnyOrder(
                        row("1", "200"), row("1", "100"),   // customer 1's two largest
                        row("2", "40"),  row("2", "30"));    // customer 2 has only two
    }

    @Test
    @DisplayName("ascending sort keeps the smallest per group")
    void topNAscending() {
        var rows = collect(ORDERS + "query { TOP 1 amount ASC PER customer_id (Orders) };");

        assertThat(rows).extracting(
                        r -> r.get("customer_id").asDisplayString(),
                        r -> r.get("amount").asDisplayString())
                .containsExactlyInAnyOrder(row("1", "50"), row("2", "30"));
    }

    @Test
    @DisplayName("offset skips rows within each group before taking count")
    void offsetWithinGroup() {
        // skip the top 1, then take 1 → each group's second-highest amount.
        var rows = collect(ORDERS + "query { TOP 1, 1 amount DESC PER customer_id (Orders) };");

        assertThat(rows).extracting(
                        r -> r.get("customer_id").asDisplayString(),
                        r -> r.get("amount").asDisplayString())
                .containsExactlyInAnyOrder(row("1", "100"), row("2", "30"));
    }

    @Test
    @DisplayName("a count larger than the group keeps the whole group")
    void countLargerThanGroup() {
        var rows = collect(ORDERS + "query { TOP 10 amount DESC PER customer_id (Orders) };");
        assertThat(rows).hasSize(5);   // all rows survive
    }
}

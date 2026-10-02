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
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.rel;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RelNodeExecutor — random permutation (SHUFFLE)")
final class ShuffleExecutionTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget   named -> rel(named.name());
            case ExpressionQueryTarget e  -> e.expression();
        };
    }

    private static List<String> ids(String src) {
        SemanticModel model = model(src);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        QueryStatement query = model.rootQueries().getFirst();
        try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
            return stream.map(r -> r.get("id").asDisplayString()).toList();
        }
    }

    private static final String EVENTS =
            "Events := [| id |\n" +
            "            | 1 |\n" +
            "            | 2 |\n" +
            "            | 3 |\n" +
            "            | 4 |\n" +
            "            | 5 |];\n";

    @RepeatedTest(20)
    @DisplayName("keeps every row exactly once — it is a permutation, not a sample")
    void keepsEveryRowOnce() {
        var rows = ids(EVENTS + "query { SHUFFLE (Events) };");
        assertThat(rows).hasSize(5);
        assertThat(rows).containsExactlyInAnyOrder("1", "2", "3", "4", "5");
    }

    @Test
    @DisplayName("an empty input shuffles to empty")
    void emptyInput() {
        var rows = ids("Empty := [| id |];\nquery { SHUFFLE (Empty) };");
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("a single row is its own permutation")
    void singleRow() {
        var rows = ids("One := [| id |\n| 7 |];\nquery { SHUFFLE SEED 1 (One) };");
        assertThat(rows).containsExactly("7");
    }

    // ─── Seeded / reproducible permutation ──────────────────────────────────────

    @Test
    @DisplayName("a seeded shuffle is the documented Fisher–Yates order")
    void seededMatchesFisherYates() {
        // Collections.shuffle([1,2,3,4,5], new Random(7)) == [5, 4, 1, 3, 2];
        // this pins the executor to the order the reference page prints.
        var rows = ids(EVENTS + "query { SHUFFLE SEED 7 (Events) };");
        assertThat(rows).containsExactly("5", "4", "1", "3", "2");
    }

    @RepeatedTest(5)
    @DisplayName("two runs with the same seed produce the identical order")
    void seededIsReproducible() {
        String query = EVENTS + "query { SHUFFLE SEED 2026 (Events) };";
        assertThat(ids(query)).isEqualTo(ids(query));
    }

    @Test
    @DisplayName("two runs with different seeds may produce different orders")
    void differentSeedsDiffer() {
        // Over 20 rows the chance two seeds give the identical order is 1/20! —
        // this test will not flake.
        String base = "R := [| id |\n" +
                "| 1 |\n| 2 |\n| 3 |\n| 4 |\n| 5 |\n" +
                "| 6 |\n| 7 |\n| 8 |\n| 9 |\n| 10 |\n" +
                "| 11 |\n| 12 |\n| 13 |\n| 14 |\n| 15 |\n" +
                "| 16 |\n| 17 |\n| 18 |\n| 19 |\n| 20 |];\n";
        assertThat(ids(base + "query { SHUFFLE SEED 1 (R) };"))
                .isNotEqualTo(ids(base + "query { SHUFFLE SEED 2 (R) };"));
    }
}

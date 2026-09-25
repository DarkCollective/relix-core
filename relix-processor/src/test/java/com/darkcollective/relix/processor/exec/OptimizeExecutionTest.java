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
import com.darkcollective.relix.events.QueryEvent;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.processor.internal.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RelNodeExecutor — declarative optimisation (OPTIMIZE) end-to-end")
final class OptimizeExecutionTest extends ProcessorTestSupport {

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

    private static List<String> valuesWhere(List<Row> rows, String region) {
        return rows.stream()
                .filter(r -> region.equals(r.get("region").asDisplayString()))
                .map(r -> r.get("value").asDisplayString())
                .sorted().toList();
    }

    @Nested
    @DisplayName("MIP mode (binary 0/1 knapsack)")
    class MipMode {

    @Test
    @DisplayName("per-group knapsack chooses each region's optimal subset")
    void knapsackPerRegion() {
        var rows = collect(
                "Items := [| region | value | weight |\n" +
                "           | a | 60  | 10 |\n" +
                "           | a | 100 | 20 |\n" +
                "           | a | 120 | 30 |\n" +
                "           | b | 50  | 40 |\n" +
                "           | b | 70  | 10 |];\n" +
                "query { OPTIMIZE MAXIMIZE SUM(value) " +
                "        SUBJECT TO SUM(weight) <= 50 PER region (Items) };");

        // region a, cap 50: {20,30} = value 220 (rows 100 + 120)
        assertThat(valuesWhere(rows, "a")).containsExactly("100", "120");
        // region b, cap 50: both items fit (40 + 10) = value 120
        assertThat(valuesWhere(rows, "b")).containsExactly("50", "70");
    }

    @Test
    @DisplayName("federation: optimise over candidates assembled from a join of two relations")
    void federationJoin() {
        var rows = collect(
                "Cand := [| id | weight |\n" +
                "          | 1 | 10 |\n" +
                "          | 2 | 20 |\n" +
                "          | 3 | 30 |];\n" +
                "Val  := [| id | value |\n" +
                "          | 1 | 60  |\n" +
                "          | 2 | 100 |\n" +
                "          | 3 | 120 |];\n" +
                "J := { Cand ⋈ Val };\n" +
                "query { OPTIMIZE MAXIMIZE SUM(value) SUBJECT TO SUM(weight) <= 50 (J) };");

        // whole-relation knapsack, cap 50: ids 2 and 3 (weights 20+30, value 220)
        assertThat(rows.stream().map(r -> r.get("id").asDisplayString()).sorted().toList())
                .containsExactly("2", "3");
    }

    @Test
    @DisplayName("an infeasible group is skipped (no rows, no error)")
    void infeasibleGroupSkipped() {
        var rows = collect(
                "Items := [| region | value | weight |\n" +
                "           | a | 60 | 10 |\n" +
                "           | b | 70 | 5  |];\n" +
                "query { OPTIMIZE MAXIMIZE SUM(value) " +
                "        SUBJECT TO SUM(weight) >= 1000 PER region (Items) };");

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("an infeasible group emits an EXECUTE QueryEvent to the listener")
    void infeasibleGroupEmitsExecuteEvent() {
        SemanticModel model = model(
                "Items := [| region | value | weight |\n" +
                "           | a | 60 | 10 |\n" +
                "           | b | 70 | 5  |];\n" +
                "query { OPTIMIZE MAXIMIZE SUM(value) " +
                "        SUBJECT TO SUM(weight) >= 1000 PER region (Items) };");

        List<QueryEvent> events = new ArrayList<>();
        ExecutionContext ctx = ExecutionContext.inlineOnly(model).withListener(events::add);
        QueryStatement query = model.rootQueries().getFirst();
        try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
            stream.toList();
        }

        // Both regions are infeasible (max achievable weight < 1000). Filtered to the
        // OPTIMIZE events: the feed also carries a SCAN event per leaf read to the end.
        List<QueryEvent> skipped = events.stream()
                .filter(e -> e.code().equals("OPTIMIZE")).toList();
        assertThat(skipped).hasSize(2);
        assertThat(skipped).allSatisfy(e ->
                assertThat(e.stage()).isEqualTo(QueryEvent.Stage.EXECUTE));
        assertThat(skipped).anySatisfy(e -> assertThat(e.description()).contains("region=a"));
        assertThat(skipped).anySatisfy(e -> assertThat(e.description()).contains("region=b"));
    }

    } // end MipMode

    @Nested
    @DisplayName("LP mode (OPTIMIZE ALLOCATE — continuous per-row weights)")
    class LpMode {

        @Test
        @DisplayName("allocation column is appended to output schema")
        void allocationColumnAppearedInOutputSchema() {
            var rows = collect(
                    "Assets := [| ret | risk |\n" +
                    "            | 0.1 | 0.05 |\n" +
                    "            | 0.2 | 0.08 |\n" +
                    "            | 0.15 | 0.03 |];\n" +
                    "query { OPTIMIZE ALLOCATE (0.0, 1.0) MAXIMIZE SUM(ret) " +
                    "        SUBJECT TO SUM(1) = 1.0 -> weight (Assets) };");

            // All 3 input rows returned (LP emits every row, not just chosen ones).
            assertThat(rows).hasSize(3);
            // The "weight" column must be present on every row.
            rows.forEach(r -> assertThat(r.get("weight")).isNotNull());
        }

        @Test
        @DisplayName("allocation values are within the declared [lo, hi] bounds")
        void allocationValuesWithinBounds() {
            var rows = collect(
                    "Assets := [| ret | risk |\n" +
                    "            | 0.1 | 0.05 |\n" +
                    "            | 0.2 | 0.08 |\n" +
                    "            | 0.3 | 0.03 |];\n" +
                    "query { OPTIMIZE ALLOCATE (0.0, 1.0) MAXIMIZE SUM(ret) " +
                    "        SUBJECT TO SUM(1) = 1.0 -> weight (Assets) };");

            assertThat(rows).hasSize(3);
            rows.forEach(r -> {
                double w = Double.parseDouble(r.get("weight").asDisplayString());
                assertThat(w).isBetween(-1e-9, 1.0 + 1e-9);
            });
        }

        @Test
        @DisplayName("LP allocation weights sum to the equality constraint bound")
        void allocationSumsToConstraintBound() {
            var rows = collect(
                    "Assets := [| ret | risk |\n" +
                    "            | 0.1 | 0.05 |\n" +
                    "            | 0.2 | 0.08 |\n" +
                    "            | 0.3 | 0.03 |];\n" +
                    "query { OPTIMIZE ALLOCATE (0.0, 1.0) MAXIMIZE SUM(ret) " +
                    "        SUBJECT TO SUM(1) = 1.0 -> weight (Assets) };");

            double total = rows.stream()
                    .mapToDouble(r -> Double.parseDouble(r.get("weight").asDisplayString()))
                    .sum();
            assertThat(total).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        }

        @Test
        @DisplayName("per-group LP allocation assigns weights independently per sector")
        void lpPerGroup() {
            var rows = collect(
                    "Assets := [| sector | ret |\n" +
                    "            | tech | 0.3 |\n" +
                    "            | tech | 0.2 |\n" +
                    "            | bond | 0.1 |\n" +
                    "            | bond | 0.05 |];\n" +
                    "query { OPTIMIZE ALLOCATE (0.0, 1.0) MAXIMIZE SUM(ret) " +
                    "        SUBJECT TO SUM(1) = 1.0 -> weight PER sector (Assets) };");

            assertThat(rows).hasSize(4);

            // Within each sector, weights sum to 1.
            double techTotal = rows.stream()
                    .filter(r -> "tech".equals(r.get("sector").asDisplayString()))
                    .mapToDouble(r -> Double.parseDouble(r.get("weight").asDisplayString()))
                    .sum();
            double bondTotal = rows.stream()
                    .filter(r -> "bond".equals(r.get("sector").asDisplayString()))
                    .mapToDouble(r -> Double.parseDouble(r.get("weight").asDisplayString()))
                    .sum();
            assertThat(techTotal).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
            assertThat(bondTotal).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        }

        @Test
        @DisplayName("an infeasible LP group is skipped and emits an EXECUTE event")
        void infeasibleLpGroupSkippedWithEvent() {
            SemanticModel m = model(
                    "Assets := [| sector | ret |\n" +
                    "            | tech | 0.3 |\n" +
                    "            | bond | 0.1 |];\n" +
                    "query { OPTIMIZE ALLOCATE (0.0, 0.4) MAXIMIZE SUM(ret) " +
                    "        SUBJECT TO SUM(1) = 2.0 -> weight PER sector (Assets) };");

            List<QueryEvent> events = new ArrayList<>();
            ExecutionContext ctx = ExecutionContext.inlineOnly(m).withListener(events::add);
            QueryStatement query = m.rootQueries().getFirst();
            List<Row> rows;
            try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
                rows = stream.toList();
            }

            // Each sector has 1 row; max allocation = 0.4, but constraint requires sum = 2.0 — infeasible.
            assertThat(rows).isEmpty();
            assertThat(events.stream().filter(e -> e.code().equals("OPTIMIZE")).toList())
                    .hasSize(2);
        }
    }
}

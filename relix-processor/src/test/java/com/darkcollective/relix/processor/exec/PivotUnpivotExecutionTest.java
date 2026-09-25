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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RelNodeExecutor — PIVOT (rows→columns) and UNPIVOT (columns→rows)")
final class PivotUnpivotExecutionTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget   named -> rel(named.name());
            case ExpressionQueryTarget e  -> e.expression();
        };
    }

    /** Runs the query and maps each output row through {@code render}, in emitted order. */
    private static List<String> run(String src, Function<Row, String> render) {
        SemanticModel model = model(src);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        var query = model.rootQueries().getFirst();
        try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
            return stream.map(render).toList();
        }
    }

    /** Renders the named columns of a row as {@code v1|v2|...} (NULL for null cells). */
    private static Function<Row, String> cols(String... names) {
        return row -> Stream.of(names)
                .map(n -> row.get(n).asDisplayString())
                .collect(Collectors.joining("|"));
    }

    private static final String QUARTERLY_LONG =
            "QuarterlySales := [| region | quarter | revenue |\n" +
            "                   | East   | q1      | 100     |\n" +
            "                   | East   | q2      | 120     |\n" +
            "                   | West   | q1      | 80      |\n" +
            "                   | West   | q2      | 90      |];\n";

    private static final String QUARTERLY_WIDE =
            "QuarterlySales := [| region | q1  | q2  |\n" +
            "                   | East   | 100 | 120 |\n" +
            "                   | West   | 80  | 90  |];\n";

    @Nested
    @DisplayName("UNPIVOT")
    final class Unpivot {

        @Test
        @DisplayName("folds the listed columns into one row each: wide → long (reference worked example)")
        void wideToLong() {
            String src = QUARTERLY_WIDE
                    + "query { UNPIVOT (q1, q2) AS (quarter, revenue) (QuarterlySales) };";
            assertThat(run(src, cols("region", "quarter", "revenue")))
                    .containsExactly(
                            "East|q1|100", "East|q2|120",
                            "West|q1|80", "West|q2|90");
        }

        @Test
        @DisplayName("emits fold rows in the listed column order, not schema order")
        void listedOrderWins() {
            String src = QUARTERLY_WIDE
                    + "query { UNPIVOT (q2, q1) AS (quarter, revenue) (QuarterlySales) };";
            assertThat(run(src, cols("quarter")))
                    .containsExactly("q2", "q1", "q2", "q1");
        }

        @Test
        @DisplayName("a null cell is carried through as the NULL value, not dropped")
        void nullCellCarried() {
            String src =
                    "Readings := [| site | sensor_a | sensor_b |\n" +
                    "             | lab  | 21       | ⊥        |];\n" +
                    "query { UNPIVOT (sensor_a, sensor_b) AS (sensor, temp) (Readings) };";
            assertThat(run(src, cols("site", "sensor", "temp")))
                    .containsExactly("lab|sensor_a|21", "lab|sensor_b|NULL");
        }

        @Test
        @DisplayName("all non-listed columns pass through unchanged")
        void passThroughColumns() {
            String src =
                    "T := [| id | tag | a | b |\n" +
                    "      | 1  | x   | 7 | 8 |];\n" +
                    "query { UNPIVOT (a, b) AS (k, v) (T) };";
            assertThat(run(src, cols("id", "tag", "k", "v")))
                    .containsExactly("1|x|a|7", "1|x|b|8");
        }

        @Test
        @DisplayName("empty input produces no rows")
        void emptyInput() {
            String src = QUARTERLY_WIDE
                    + "query { UNPIVOT (q1, q2) AS (quarter, revenue) "
                    + "(SELECT region = 'nowhere' (QuarterlySales)) };";
            assertThat(run(src, cols("quarter"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("PIVOT")
    final class Pivot {

        @Test
        @DisplayName("rotates rows into columns per group: long → wide (reference worked example)")
        void longToWide() {
            String src = QUARTERLY_LONG
                    + "query { PIVOT revenue BY quarter PER region (QuarterlySales) };";
            assertThat(run(src, cols("region", "q1", "q2")))
                    .containsExactly("East|100|120", "West|80|90");
        }

        @Test
        @DisplayName("a group missing a key value gets NULL in that column")
        void missingKeyIsNull() {
            String src =
                    "Sales := [| region | quarter | revenue |\n" +
                    "          | East   | q1      | 100     |\n" +
                    "          | East   | q2      | 120     |\n" +
                    "          | West   | q1      | 80      |];\n" +
                    "query { PIVOT revenue BY quarter PER region (Sales) };";
            assertThat(run(src, cols("region", "q1", "q2")))
                    .containsExactly("East|100|120", "West|80|NULL");
        }

        @Test
        @DisplayName("no PER keys — the whole relation pivots to a single row")
        void globalPivot() {
            String src =
                    "Totals := [| category | total |\n" +
                    "           | food     | 12    |\n" +
                    "           | tools    | 34    |];\n" +
                    "query { PIVOT total BY category (Totals) };";
            assertThat(run(src, cols("food", "tools")))
                    .containsExactly("12|34");
        }

        @Test
        @DisplayName("column headers appear in first-encounter order across all groups")
        void headerEncounterOrder() {
            String src =
                    "Sales := [| region | quarter | revenue |\n" +
                    "          | East   | q2      | 120     |\n" +
                    "          | West   | q1      | 80      |\n" +
                    "          | East   | q1      | 100     |];\n" +
                    "query { PIVOT revenue BY quarter PER region (Sales) };";
            // q2 seen first, so it precedes q1 in every output row.
            assertThat(run(src, cols("region", "q2", "q1")))
                    .containsExactly("East|120|100", "West|NULL|80");
        }

        @Test
        @DisplayName("rows whose key column is ⊥ contribute no header and no cell")
        void nullKeyIgnored() {
            String src =
                    "Sales := [| region | quarter | revenue |\n" +
                    "          | East   | q1      | 100     |\n" +
                    "          | East   | ⊥       | 999     |];\n" +
                    "query { PIVOT revenue BY quarter PER region (Sales) };";
            List<String> rows = run(src,
                    row -> row.columnNames().stream().collect(Collectors.joining("|")));
            assertThat(rows).containsExactly("region|q1");
        }

        @Test
        @DisplayName("a later duplicate key within a group wins (last write)")
        void duplicateKeyLastWins() {
            String src =
                    "Sales := [| region | quarter | revenue |\n" +
                    "          | East   | q1      | 100     |\n" +
                    "          | East   | q1      | 111     |];\n" +
                    "query { PIVOT revenue BY quarter PER region (Sales) };";
            assertThat(run(src, cols("region", "q1")))
                    .containsExactly("East|111");
        }

        @Test
        @DisplayName("empty input produces no rows")
        void emptyInput() {
            String src = QUARTERLY_LONG
                    + "query { PIVOT revenue BY quarter PER region "
                    + "(SELECT region = 'nowhere' (QuarterlySales)) };";
            assertThat(run(src, cols("region"))).isEmpty();
        }

        @Test
        @DisplayName("UNPIVOT of a PIVOT restores the long form (wide ↔ long round-trip)")
        void roundTrip() {
            String src = QUARTERLY_LONG
                    + "query { UNPIVOT (q1, q2) AS (quarter, revenue) "
                    + "(PIVOT revenue BY quarter PER region (QuarterlySales)) };";
            assertThat(run(src, cols("region", "quarter", "revenue")))
                    .containsExactly(
                            "East|q1|100", "East|q2|120",
                            "West|q1|80", "West|q2|90");
        }
    }
}

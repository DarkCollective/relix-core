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
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.processor.internal.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RelNodeExecutor — reservoir (fixed-count) sampling (SAMPLE n ROWS)")
final class ReservoirSampleExecutionTest extends ProcessorTestSupport {

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

    private static final String EVENTS =
            "Events := [| id |\n" +
            "            | 1 |\n" +
            "            | 2 |\n" +
            "            | 3 |\n" +
            "            | 4 |\n" +
            "            | 5 |];\n";

    @RepeatedTest(20)
    @DisplayName("keeps exactly `count` rows when the input is larger")
    void keepsExactlyCountRows() {
        var rows = collect(EVENTS + "query { SAMPLE 3 ROWS (Events) };");
        assertThat(rows).hasSize(3);
        // Every kept row is a distinct original Events row.
        assertThat(rows).allSatisfy(r ->
                assertThat(r.get("id").asDisplayString()).isIn("1", "2", "3", "4", "5"));
        assertThat(rows.stream().map(r -> r.get("id").asDisplayString()).distinct().count())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("keeps all rows when count exceeds the input size")
    void keepsAllWhenCountExceedsInput() {
        var rows = collect(EVENTS + "query { SAMPLE 100 ROWS (Events) };");
        assertThat(rows).hasSize(5);
        assertThat(rows.stream().map(r -> r.get("id").asDisplayString()).sorted().toList())
                .containsExactly("1", "2", "3", "4", "5");
    }

    @Test
    @DisplayName("count equal to the input size keeps every row")
    void countEqualsInputKeepsAll() {
        var rows = collect(EVENTS + "query { SAMPLE 5 ROWS (Events) };");
        assertThat(rows).hasSize(5);
    }

    @Test
    @DisplayName("SAMPLE 0 ROWS keeps no rows")
    void zeroRowsKeepsNone() {
        var rows = collect(EVENTS + "query { SAMPLE 0 ROWS (Events) };");
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("reservoir over an empty input is empty")
    void emptyInput() {
        var rows = collect(
                "Empty := [| id |];\n" +
                "query { SAMPLE 3 ROWS (Empty) };");
        assertThat(rows).isEmpty();
    }

    // ─── Seeded / reproducible reservoir ────────────────────────────────────────

    @RepeatedTest(5)
    @DisplayName("seeded reservoir keeps exactly `count` distinct rows")
    void seededKeepsExactlyCountRows() {
        var rows = collect(EVENTS + "query { SAMPLE 3 ROWS SEED 42 (Events) };");
        assertThat(rows).hasSize(3);
        assertThat(rows.stream().map(r -> r.get("id").asDisplayString()).distinct().count())
                .isEqualTo(3);
        assertThat(rows).allSatisfy(r ->
                assertThat(r.get("id").asDisplayString()).isIn("1", "2", "3", "4", "5"));
    }

    @Test
    @DisplayName("two runs with the same seed produce identical row selections")
    void seededReservoirIsReproducible() {
        String query = EVENTS + "query { SAMPLE 3 ROWS SEED 2026 (Events) };";
        List<Row> first  = collect(query);
        List<Row> second = collect(query);
        assertThat(first).hasSize(3);
        assertThat(second).hasSize(3);
        for (int i = 0; i < first.size(); i++) {
            assertThat(first.get(i).get("id").asDisplayString())
                    .isEqualTo(second.get(i).get("id").asDisplayString());
        }
    }

    @Test
    @DisplayName("seeded reservoir with 0 ROWS keeps nothing even with a seed")
    void seededZeroRowsKeepsNone() {
        var rows = collect(EVENTS + "query { SAMPLE 0 ROWS SEED 42 (Events) };");
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("seeded reservoir keeps all rows when count exceeds input")
    void seededKeepsAllWhenCountExceedsInput() {
        var rows = collect(EVENTS + "query { SAMPLE 100 ROWS SEED 99 (Events) };");
        assertThat(rows).hasSize(5);
        assertThat(rows.stream().map(r -> r.get("id").asDisplayString()).sorted().toList())
                .containsExactly("1", "2", "3", "4", "5");
    }

    @Test
    @DisplayName("two runs with different seeds may produce different selections")
    void differentSeedsProduceDifferentSamples() {
        // With 20 items and count=10, the probability two random seeds yield the
        // exact same selection is C(20,10)^-1 ≈ 1/184756 — this test will not flake.
        String base = "R := [| id |\n" +
                "| 1 |\n| 2 |\n| 3 |\n| 4 |\n| 5 |\n" +
                "| 6 |\n| 7 |\n| 8 |\n| 9 |\n| 10 |\n" +
                "| 11 |\n| 12 |\n| 13 |\n| 14 |\n| 15 |\n" +
                "| 16 |\n| 17 |\n| 18 |\n| 19 |\n| 20 |];\n";
        List<String> ids1 = collect(base + "query { SAMPLE 10 ROWS SEED 1 (R) };")
                .stream().map(r -> r.get("id").asDisplayString()).sorted().toList();
        List<String> ids2 = collect(base + "query { SAMPLE 10 ROWS SEED 2 (R) };")
                .stream().map(r -> r.get("id").asDisplayString()).sorted().toList();
        assertThat(ids1).isNotEqualTo(ids2);
    }

    /**
     * A reservoir is one list, indexed by {@code int}, so a count above what a list can
     * hold cannot be satisfied. Asking is refused by name.
     *
     * <p>It used to narrow instead: the chosen slot is computed as a {@code long} and was
     * cast, so a count past {@code Integer.MAX_VALUE} produced a negative index and the
     * query died on an {@code IndexOutOfBoundsException} from inside the sampler — an
     * engine defect to read, for a query that was merely asking for too much.
     */
    @Test
    @DisplayName("a count larger than a relation can hold is refused, not narrowed")
    void aCountBeyondAnIndexIsRefused() {
        assertThatThrownBy(() -> collect(EVENTS + "query { SAMPLE 3000000000 ROWS (Events) };\n"))
                .isInstanceOf(EvaluationException.class)
                .hasMessageContaining("3000000000")
                .hasMessageContaining("2147483647");
    }
}

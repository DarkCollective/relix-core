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

@DisplayName("RelNodeExecutor — Bernoulli sampling (SAMPLE)")
final class SampleExecutionTest extends ProcessorTestSupport {

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

    @Test
    @DisplayName("SAMPLE 1.0 keeps every row")
    void probabilityOneKeepsAll() {
        var rows = collect(EVENTS + "query { SAMPLE 1.0 (Events) };");
        assertThat(rows).hasSize(5);
    }

    @Test
    @DisplayName("SAMPLE 0.0 keeps no rows")
    void probabilityZeroKeepsNone() {
        var rows = collect(EVENTS + "query { SAMPLE 0.0 (Events) };");
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("An intermediate probability keeps a subset (0..n rows)")
    void intermediateKeepsSubset() {
        var rows = collect(EVENTS + "query { SAMPLE 0.5 (Events) };");
        assertThat(rows.size()).isBetween(0, 5);
        // Every surviving row is an original Events row (schema preserved).
        assertThat(rows).allSatisfy(r ->
                assertThat(r.get("id").asDisplayString()).isIn("1", "2", "3", "4", "5"));
    }

    // ─── Seeded / reproducible Bernoulli ────────────────────────────────────────

    @Test
    @DisplayName("SAMPLE 1.0 SEED n keeps every row (probability 1 always true regardless of seed)")
    void seededProbabilityOneKeepsAll() {
        var rows = collect(EVENTS + "query { SAMPLE 1.0 SEED 42 (Events) };");
        assertThat(rows).hasSize(5);
    }

    @Test
    @DisplayName("SAMPLE 0.0 SEED n keeps no rows (probability 0 always false regardless of seed)")
    void seededProbabilityZeroKeepsNone() {
        var rows = collect(EVENTS + "query { SAMPLE 0.0 SEED 42 (Events) };");
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("Two runs with the same seed produce identical results")
    void seededSampleIsReproducible() {
        String query = EVENTS + "query { SAMPLE 0.5 SEED 42 (Events) };";
        List<Row> first  = collect(query);
        List<Row> second = collect(query);
        assertThat(first).hasSize(second.size());
        for (int i = 0; i < first.size(); i++) {
            assertThat(first.get(i).get("id").asDisplayString())
                    .isEqualTo(second.get(i).get("id").asDisplayString());
        }
    }

    @Test
    @DisplayName("Two runs with different seeds produce independent samples")
    void differentSeedsProduceDifferentSamples() {
        // With 20 rows and p=0.5, the probability both seeds yield identical selections
        // is astronomically small — this test will never flake.
        String base = "R := [| id |\n" +
                "| 1 |\n| 2 |\n| 3 |\n| 4 |\n| 5 |\n" +
                "| 6 |\n| 7 |\n| 8 |\n| 9 |\n| 10 |\n" +
                "| 11 |\n| 12 |\n| 13 |\n| 14 |\n| 15 |\n" +
                "| 16 |\n| 17 |\n| 18 |\n| 19 |\n| 20 |];\n";
        List<Row> withSeed1 = collect(base + "query { SAMPLE 0.5 SEED 1 (R) };");
        List<Row> withSeed2 = collect(base + "query { SAMPLE 0.5 SEED 2 (R) };");
        // At least one of size or content must differ (almost certainly both).
        boolean differ = withSeed1.size() != withSeed2.size() ||
                !withSeed1.stream().map(r -> r.get("id").asDisplayString()).toList()
                        .equals(withSeed2.stream().map(r -> r.get("id").asDisplayString()).toList());
        assertThat(differ).isTrue();
    }

    @Test
    @DisplayName("Seeded SAMPLE preserves input schema")
    void seededSamplePreservesSchema() {
        var rows = collect(EVENTS + "query { SAMPLE 0.5 SEED 99 (Events) };");
        rows.forEach(r -> assertThat(r.get("id")).isNotNull());
    }
}

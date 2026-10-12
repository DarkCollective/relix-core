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
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.internal.ExecutionContext;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.rel;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Similarity join execution")
final class SimilarityJoinExecutionTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    private static final PhysicalExecutor PHYSICAL = new PhysicalExecutor();

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget named -> rel(named.name());
            case ExpressionQueryTarget e -> e.expression();
        };
    }

    /** A planned query and the context to run it in. */
    private record Planned(PhysicalNode plan, ExecutionContext ctx) {
    }

    private static Planned plan(String src) {
        SemanticModel m = model(src);
        ExecutionContext ctx = ExecutionContext.inlineOnly(m);
        return new Planned(EXECUTOR.plan(queryNode(m.rootQueries().getFirst()), ctx), ctx);
    }

    private static List<String> run(PhysicalNode plan, ExecutionContext ctx) {
        try (Stream<Row> rows = PHYSICAL.execute(plan, ctx)) {
            return rows.map(SimilarityJoinExecutionTest::render).toList();
        }
    }

    private static String render(Row row) {
        return IntStream.range(0, row.width())
                .mapToObj(i -> row.get(i).asDisplayString())
                .collect(Collectors.joining("|"));
    }

    /** The plan with every similarity join replaced by the nested loop it wraps. */
    private static PhysicalNode nestedLoop(PhysicalNode node) {
        UnaryOperator<PhysicalNode> strip = SimilarityJoinExecutionTest::nestedLoop;
        return node instanceof PhysicalNode.SimilarityJoin s
                ? s.join().mapChildren(strip)
                : node.mapChildren(strip);
    }

    private static Optional<PhysicalNode.SimilarityJoin> similarityJoin(PhysicalNode node) {
        if (node instanceof PhysicalNode.SimilarityJoin s) {
            return Optional.of(s);
        }
        return node.children().stream().map(SimilarityJoinExecutionTest::similarityJoin)
                .flatMap(Optional::stream).findFirst();
    }

    // ── corpus ─────────────────────────────────────────────────────────────────

    /**
     * Two relations of names drawn from a small alphabet so that near-misses are common,
     * with duplicates on both sides, NULLs (empty cells) and empty strings ({@code _},
     * replaced by nothing).
     */
    private static String corpus(long seed, int rows) {
        Random random = new Random(seed);
        StringBuilder src = new StringBuilder();
        for (String side : List.of("L", "R")) {
            List<String> names = new ArrayList<>(List.of("smith", "smyth", "smith", "_", "_", "jon"));
            while (names.size() < rows) {
                StringBuilder name = new StringBuilder();
                int length = 1 + random.nextInt(7);
                for (int i = 0; i < length; i++) {
                    name.append("abcde".charAt(random.nextInt(5)));
                }
                names.add(name.toString());
            }
            src.append(side).append("raw := [| id | raw |\n");
            for (int i = 0; i < names.size(); i++) {
                // Every seventh row has no name at all.
                String cell = i % 7 == 6 ? "" : names.get(i);
                src.append("| ").append(side.toLowerCase()).append(i).append(" | ")
                        .append(cell).append(" |\n");
            }
            src.setLength(src.length() - 1);
            src.append("];\n");
            src.append(side).append(" := { π id, Replace(raw, \"_\", \"\") → name (")
                    .append(side).append("raw) };\n");
        }
        return src.toString();
    }

    private static final String CORPUS = corpus(1156, 40);

    @Nested
    @DisplayName("agrees with the nested loop it replaces")
    final class Agreement {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "EditSimilarity(L.name, R.name) >= 0.8",
                "EditSimilarity(L.name, R.name) >= 0.5",
                "EditSimilarity(L.name, R.name) > 0.75",
                "EditSimilarity(L.name, R.name) >= 1",
                "EditSimilarity(R.name, L.name) >= 0.8",
                "0.8 <= EditSimilarity(L.name, R.name)",
                "EditSimilarity(L.name, R.name) = 1",
                "JaroWinkler(L.name, R.name) >= 0.9",
                "Trigram(L.name, R.name) >= 0.4",
                "Trigram(L.name, R.name) >= 1",
                "Levenshtein(L.name, R.name) <= 1",
                "Levenshtein(L.name, R.name) < 2",
                "Levenshtein(L.name, R.name) = 1",
                "1 >= Levenshtein(L.name, R.name)",
                "EditSimilarity(L.name, R.name) >= 0.8 ∧ L.id < R.id",
                "L.id < R.id ∧ Trigram(L.name, R.name) >= 0.5"})
        @DisplayName("pair for pair, in the same order")
        void agrees(String condition) {
            Planned planned = plan(CORPUS + "query { L ⨝ " + condition + " R };");
            assertThat(similarityJoin(planned.plan()))
                    .as("%s should plan as a similarity join", condition)
                    .isPresent();
            List<String> similarity = run(planned.plan(), planned.ctx());
            List<String> nested = run(nestedLoop(planned.plan()), planned.ctx());
            assertThat(similarity).isEqualTo(nested);
        }

        @Test
        @DisplayName("agrees when the left input is the one indexed")
        void leftIndexed() {
            Planned planned = plan(CORPUS + "query { L ⨝ EditSimilarity(L.name, R.name) >= 0.8 R };");
            PhysicalNode.SimilarityJoin s = similarityJoin(planned.plan()).orElseThrow();
            PhysicalNode.Join join = s.join();
            PhysicalNode.Join leftBuilt = new PhysicalNode.Join(join.schema(), join.kind(),
                    join.algorithm(), PhysicalNode.BuildSide.LEFT, join.condition(), join.keys(),
                    join.leftRelations(), join.rightRelations(), join.left(), join.right());
            PhysicalNode swapped = PhysicalNode.SimilarityJoin.of(leftBuilt, s.function(),
                    s.leftColumn(), s.rightColumn(), s.bound(), s.threshold());
            assertThat(run(swapped, planned.ctx()))
                    .containsExactlyInAnyOrderElementsOf(run(join, planned.ctx()));
        }

        @Test
        @DisplayName("finds a self-join's near-duplicates once each")
        void selfJoin() {
            Planned planned = plan(CORPUS + "query { ρ A (L) ⨝ EditSimilarity(A.name, B.name) >= 0.8"
                    + " ∧ A.id < B.id ρ B (L) };");
            assertThat(similarityJoin(planned.plan())).isPresent();
            List<String> rows = run(planned.plan(), planned.ctx());
            assertThat(rows).isEqualTo(run(nestedLoop(planned.plan()), planned.ctx()))
                    .contains("l0|smith|l2|smith", "l0|smith|l1|smyth");
        }

        @Test
        @DisplayName("keeps no pair with a NULL name, as the nested loop keeps none")
        void nulls() {
            Planned planned = plan(CORPUS + "query { L ⨝ EditSimilarity(L.name, R.name) >= 0.5 R };");
            // Rows 6, 13, 20, 27 and 34 of each side have no name.
            List<String> nameless = List.of("6", "13", "20", "27", "34");
            assertThat(run(planned.plan(), planned.ctx())).isNotEmpty().noneMatch(row -> {
                String[] fields = row.split("\\|", -1);
                return nameless.contains(fields[0].substring(1))
                        || nameless.contains(fields[2].substring(1));
            });
        }

        @Test
        @DisplayName("pairs two empty strings, which are identical")
        void emptyStrings() {
            Planned planned = plan(CORPUS + "query { L ⨝ EditSimilarity(L.name, R.name) >= 1 R };");
            assertThat(run(planned.plan(), planned.ctx())).contains("l3||r3|", "l4||r4|");
        }
    }

    @Nested
    @DisplayName("falls back to the nested loop")
    final class Fallback {

        @Test
        @DisplayName("when the catalogue it runs against does not offer the blocking")
        void noCatalogue() {
            Planned planned = plan(CORPUS + "query { L ⨝ EditSimilarity(L.name, R.name) >= 0.8 R };");
            PhysicalNode.SimilarityJoin s = similarityJoin(planned.plan()).orElseThrow();
            PhysicalNode.SimilarityJoin unknown = PhysicalNode.SimilarityJoin.of(s.join(),
                    "NoSuchFunction", s.leftColumn(), s.rightColumn(), s.bound(), s.threshold());
            assertThat(run(unknown, planned.ctx())).isEqualTo(run(s.join(), planned.ctx()));
        }

        @Test
        @DisplayName("and the function's own error still reaches the caller")
        void wrongType() {
            Planned planned = plan("""
                    L := [| id | name |
                    | 1 | ab |];
                    R := [| id | name |
                    | 1 | 12 |];
                    query { L ⨝ EditSimilarity(L.name, R.name) >= 0.8 R };
                    """);
            if (similarityJoin(planned.plan()).isEmpty()) {
                return;   // the analyser refused the types, which is fine too
            }
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> run(planned.plan(), planned.ctx()))
                    .hasMessageContaining("EditSimilarity");
        }

        @Test
        @DisplayName("is not chosen where nothing can be indexed")
        void notChosen() {
            for (String condition : List.of(
                    "EditSimilarity(L.name, R.name) >= 0",
                    "EditSimilarity(L.name, R.name) <= 0.8",
                    "EditSimilarity(L.name, L.name) >= 0.8",
                    "Levenshtein(L.name, R.name) >= 1",
                    "EditSimilarity(L.name, R.name) >= 0.8 ∨ L.id = R.id")) {
                Planned planned = plan(CORPUS + "query { L ⨝ " + condition + " R };");
                assertThat(similarityJoin(planned.plan())).as(condition).isEmpty();
            }
        }

        @Test
        @DisplayName("and not under an empty function catalogue")
        void emptyCatalogue() {
            SemanticModel m = model(CORPUS + "query { L ⨝ EditSimilarity(L.name, R.name) >= 0.8 R };");
            ExecutionContext ctx = ExecutionContext.inlineOnly(m).withFunctions(FunctionCatalog.empty());
            PhysicalNode plan = EXECUTOR.plan(queryNode(m.rootQueries().getFirst()), ctx);
            assertThat(similarityJoin(plan)).isEmpty();
        }
    }

    @Test
    @DisplayName("reports how many pairs it compared")
    void reportsComparedPairs() {
        List<QueryEvent> events = new ArrayList<>();
        SemanticModel m = model(CORPUS + "query { L ⨝ EditSimilarity(L.name, R.name) >= 0.8 R };");
        ExecutionContext ctx = ExecutionContext.inlineOnly(m).withListener(events::add);
        try (Stream<Row> rows = EXECUTOR.execute(queryNode(m.rootQueries().getFirst()), ctx)) {
            assertThat(rows.count()).isPositive();
        }
        assertThat(events).anySatisfy(e -> {
            assertThat(e.code()).isEqualTo("JOIN-SIMILARITY");
            assertThat(e.stage()).isEqualTo(QueryEvent.Stage.EXECUTE);
            assertThat(e.description()).contains("compared").contains("of 1600 pairs");
        });
        assertThat(events).anySatisfy(e -> {
            assertThat(e.code()).isEqualTo("JOIN-SIMILARITY");
            assertThat(e.stage()).isEqualTo(QueryEvent.Stage.PLAN);
        });
        assertThat(events).noneMatch(e -> e.code().equals("JOIN-NESTED-LOOP"));
    }

    @Test
    @Tag("benchmark")
    @DisplayName("compares a small fraction of the pairs a nested loop would")
    void comparesFewerPairs() {
        List<QueryEvent> events = new ArrayList<>();
        SemanticModel m = model(corpus(679, 600)
                + "query { L ⨝ EditSimilarity(L.name, R.name) >= 0.9 R };");
        ExecutionContext ctx = ExecutionContext.inlineOnly(m).withListener(events::add);
        try (Stream<Row> rows = EXECUTOR.execute(queryNode(m.rootQueries().getFirst()), ctx)) {
            rows.forEach(row -> { });
        }
        String report = events.stream().filter(e -> e.code().equals("JOIN-SIMILARITY")
                        && e.stage() == QueryEvent.Stage.EXECUTE)
                .map(QueryEvent::description).findFirst().orElseThrow();
        // "EditSimilarity blocking: compared C of N pairs"
        String[] words = report.split(" ");
        long compared = Long.parseLong(words[3]);
        long all = Long.parseLong(words[5]);
        assertThat(all).isEqualTo(600L * 600L);
        assertThat(compared).as(report).isLessThan(all / 3);
    }
}

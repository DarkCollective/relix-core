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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;

@DisplayName("RelNodeExecutor — symmetric difference (∆) and composition (∘)")
final class SymmetricDifferenceCompositionTest extends ProcessorTestSupport {

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

    // ── Symmetric difference ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Symmetric difference ∆ — rows in exactly one side")
    class SymmetricDifference {

        @Test
        @DisplayName("keeps rows unique to either side, drops shared rows")
        void keepsExclusiveRows() {
            var rows = collect(
                    "A := [| id | name |\n" +
                    "       | 1  | Alice |\n" +
                    "       | 2  | Bob   |];\n" +
                    "B := [| id | name |\n" +
                    "       | 2  | Bob   |\n" +
                    "       | 3  | Carol |];\n" +
                    "query { A ∆ B };");

            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(r -> r.get("name").asDisplayString())
                    .containsExactlyInAnyOrder("Alice", "Carol");
        }

        @Test
        @DisplayName("disjoint inputs behave like a union")
        void disjointInputs() {
            var rows = collect(
                    "A := [| id |\n       | 1 |];\n" +
                    "B := [| id |\n       | 2 |];\n" +
                    "query { A ∆ B };");

            assertThat(rows).extracting(r -> r.get("id").asDisplayString())
                    .containsExactlyInAnyOrder("1", "2");
        }

        @Test
        @DisplayName("symmetric difference with self is empty")
        void selfIsEmpty() {
            var rows = collect(
                    "A := [| id |\n       | 1 |\n       | 2 |];\n" +
                    "query { A ∆ A };");

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("ASCII keyword SYMDIFF behaves identically")
        void asciiKeyword() {
            var rows = collect(
                    "A := [| id |\n       | 1 |\n       | 2 |];\n" +
                    "B := [| id |\n       | 2 |\n       | 3 |];\n" +
                    "query { A SYMDIFF B };");

            assertThat(rows).extracting(r -> r.get("id").asDisplayString())
                    .containsExactlyInAnyOrder("1", "3");
        }
    }

    // ── Composition ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Sharing — the desugaring reads each input once, and it does not show")
    class Sharing {

        /**
         * {@code A ∆ B} desugars to {@code (A − B) ∪ (B − A)}, so each input is read at
         * two places.  The planner spools each one so it is evaluated once.  Every case
         * here is about that being <em>invisible</em>: the same rows, in the same order,
         * as when each branch evaluated its inputs for itself.
         */
        @Test
        @DisplayName("duplicate rows within one side do not survive")
        void duplicatesWithinASideAreDropped() {
            var rows = collect(
                    "A := [| id |\n       | 1  |\n       | 1  |\n       | 2  |];\n" +
                    "B := [| id |\n       | 2  |];\n" +
                    "query { A ∆ B };");

            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().get("id")).isEqualTo(num(1));
        }

        @Test
        @DisplayName("an empty side leaves the other side's rows, deduplicated")
        void emptySideLeavesTheOtherSide() {
            var rows = collect(
                    "A := [| id |\n       | 1  |\n       | 2  |];\n" +
                    "B := [| id |\n       | 1  |];\n" +
                    "query { (σ id > 99 (A)) ∆ B };");

            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().get("id")).isEqualTo(num(1));
        }

        @Test
        @DisplayName("a ∆ inside a recursive step still converges to the same relation")
        void symmetricDifferenceInsideAFixpoint() {
            // R's input to the ∆ is the current iteration's rows, so it is not shared;
            // A is invariant across the iterations and is. The answer must not care.
            var rows = collect(
                    "A := [| id |\n       | 1  |\n       | 2  |];\n" +
                    "query { FIX R (A, A ∆ R) };");

            assertThat(rows).hasSize(2);
            assertThat(rows.stream().map(r -> r.get("id")).toList())
                    .containsExactlyInAnyOrder(num(1), num(2));
        }

        @Test
        @DisplayName("a ∆ over a sampled side is not shared, and still answers")
        void sampledSideStillAnswers() {
            // SAMPLE 1.0 keeps every row, but the planner cannot know that: an unseeded
            // sample reads system state, so this side is evaluated per branch as before.
            var rows = collect(
                    "A := [| id |\n       | 1  |\n       | 2  |];\n" +
                    "B := [| id |\n       | 2  |];\n" +
                    "query { (SAMPLE 1.0 (A)) ∆ B };");

            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().get("id")).isEqualTo(num(1));
        }
    }

    @Nested
    @DisplayName("Composition ∘ — join on shared columns, drop them")
    class Composition {

        @Test
        @DisplayName("relates a to c through the shared column b")
        void relatesThroughSharedColumn() {
            var rows = collect(
                    "R := [| a | b |\n" +
                    "       | 1 | 10 |\n" +
                    "       | 2 | 20 |];\n" +
                    "S := [| b | c |\n" +
                    "       | 10 | 100 |\n" +
                    "       | 20 | 200 |\n" +
                    "       | 30 | 300 |];\n" +
                    "query { R ∘ S };");

            assertThat(rows).hasSize(2);
            // The shared column b is projected away.
            assertThat(rows.getFirst().schema().column("b")).isEmpty();
            assertThat(rows).extracting(
                    r -> r.get("a").asDisplayString(),
                    r -> r.get("c").asDisplayString())
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple("1", "100"),
                            org.assertj.core.groups.Tuple.tuple("2", "200"));
        }

        @Test
        @DisplayName("no matching shared values yields an empty relation")
        void noMatchIsEmpty() {
            var rows = collect(
                    "R := [| a | b |\n       | 1 | 10 |];\n" +
                    "S := [| b | c |\n       | 99 | 100 |];\n" +
                    "query { R ∘ S };");

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("ASCII keyword COMPOSE behaves identically")
        void asciiKeyword() {
            var rows = collect(
                    "R := [| a | b |\n       | 1 | 10 |];\n" +
                    "S := [| b | c |\n       | 10 | 100 |];\n" +
                    "query { R COMPOSE S };");

            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()).hasValue("a", "1")
                    .hasValue("c", "100");
        }
    }
}

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
import com.darkcollective.relix.plan.PhysicalNode;
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

/**
 * Executor tests for streaming {@code δ}/{@code γ} over an ordered input (Phase C3 —
 * ADR-0009, #58).
 *
 * <p>Tests drive the planner into choosing the streaming variant by ordering the
 * input with a {@code τ} (the planner sets {@code streaming} when the delivered
 * ordering covers the whole row for {@code δ}, or the grouping keys for {@code γ}),
 * then assert both the chosen plan flag and the produced rows — so the single-pass
 * linear executors are verified to match the hash/bag variants.
 */
@DisplayName("PhysicalExecutor — streaming δ/γ over ordered input (Phase C3)")
final class StreamingDistinctAggregateExecutionTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    // ── helpers ───────────────────────────────────────────────────────────────

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

    private static PhysicalNode plan(String src) {
        SemanticModel model = model(src);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        var query = model.rootQueries().getFirst();
        return EXECUTOR.plan(queryNode(query), ctx);
    }

    // ── streaming δ ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("streaming δ (DISTINCT)")
    class StreamingDistinct {

        private static final String DUPES =
                "R := [| id |\n" +
                "       | 3  |\n" +
                "       | 1  |\n" +
                "       | 1  |\n" +
                "       | 2  |\n" +
                "       | 3  |\n" +
                "       | 3  |];\n";

        @Test
        @DisplayName("the planner marks δ over a fully-ordered input streaming")
        void planIsStreaming() {
            PhysicalNode root = plan(DUPES + "query { δ (τ id ASC (R)) };");
            assertThat(root).isNode(PhysicalNode.Distinct.class);
            assertThat(((PhysicalNode.Distinct) root).streaming()).isTrue();
        }

        @Test
        @DisplayName("streaming δ removes adjacent duplicates, keeping input order")
        void dedupsAdjacent() {
            var rows = collect(DUPES + "query { δ (τ id ASC (R)) };");
            assertThat(rows).extracting(r -> r.get("id").asDisplayString())
                    .containsExactly("1", "2", "3");
        }

        @Test
        @DisplayName("hash δ (unsorted input) still removes duplicates")
        void hashFallbackStillDedups() {
            PhysicalNode root = plan(DUPES + "query { δ (R) };");
            assertThat(((PhysicalNode.Distinct) root).streaming()).isFalse();

            var rows = collect(DUPES + "query { δ (R) };");
            assertThat(rows).extracting(r -> r.get("id").asDisplayString())
                    .containsExactlyInAnyOrder("1", "2", "3");
        }

        @Test
        @DisplayName("streaming δ over an all-distinct ordered input keeps every row")
        void allDistinctKept() {
            var rows = collect(
                    "R := [| id |\n" +
                    "       | 1  |\n" +
                    "       | 2  |\n" +
                    "       | 3  |];\n" +
                    "query { δ (τ id ASC (R)) };");
            assertThat(rows).extracting(r -> r.get("id").asDisplayString())
                    .containsExactly("1", "2", "3");
        }
    }

    // ── streaming γ ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("streaming γ (GROUP)")
    class StreamingAggregate {

        private static final String ORDERS =
                "R := [| region | amount |\n" +
                "       | 1      | 10     |\n" +
                "       | 1      | 20     |\n" +
                "       | 2      | 5      |\n" +
                "       | 3      | 7      |\n" +
                "       | 3      | 3      |];\n";

        @Test
        @DisplayName("the planner marks γ over a group-ordered input streaming")
        void planIsStreaming() {
            PhysicalNode root = plan(ORDERS +
                    "query { γ region, SUM(amount) → total (τ region ASC (R)) };");
            assertThat(root).isNode(PhysicalNode.Aggregate.class);
            assertThat(((PhysicalNode.Aggregate) root).streaming()).isTrue();
        }

        @Test
        @DisplayName("streaming γ sums each contiguous group, emitting in group order")
        void sumsContiguousGroups() {
            var rows = collect(ORDERS +
                    "query { γ region, SUM(amount) → total (τ region ASC (R)) };");
            assertThat(rows).extracting(
                    r -> r.get("region").asDisplayString(),
                    r -> r.get("total").asDisplayString())
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("1", "30"),
                            org.assertj.core.groups.Tuple.tuple("2", "5"),
                            org.assertj.core.groups.Tuple.tuple("3", "10"));
        }

        @Test
        @DisplayName("streaming γ COUNT matches the hash-grouped count")
        void countsContiguousGroups() {
            var rows = collect(ORDERS +
                    "query { γ region, COUNT(amount) → n (τ region ASC (R)) };");
            assertThat(rows).extracting(
                    r -> r.get("region").asDisplayString(),
                    r -> r.get("n").asDisplayString())
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("1", "2"),
                            org.assertj.core.groups.Tuple.tuple("2", "1"),
                            org.assertj.core.groups.Tuple.tuple("3", "2"));
        }

        @Test
        @DisplayName("streaming γ groups correctly when the order has extra trailing keys")
        void groupsWithLongerOrder() {
            // Ordered by (region, amount); grouping by region — groups stay contiguous.
            PhysicalNode root = plan(ORDERS +
                    "query { γ region, SUM(amount) → total (τ region ASC, amount ASC (R)) };");
            assertThat(((PhysicalNode.Aggregate) root).streaming()).isTrue();

            var rows = collect(ORDERS +
                    "query { γ region, SUM(amount) → total (τ region ASC, amount ASC (R)) };");
            assertThat(rows).extracting(r -> r.get("total").asDisplayString())
                    .containsExactly("30", "5", "10");
        }

        @Test
        @DisplayName("hash γ (unsorted input) still aggregates correctly")
        void hashFallbackStillAggregates() {
            PhysicalNode root = plan(ORDERS +
                    "query { γ region, SUM(amount) → total (R) };");
            assertThat(((PhysicalNode.Aggregate) root).streaming()).isFalse();

            var rows = collect(ORDERS +
                    "query { γ region, SUM(amount) → total (R) };");
            assertThat(rows).extracting(
                    r -> r.get("region").asDisplayString(),
                    r -> r.get("total").asDisplayString())
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple("1", "30"),
                            org.assertj.core.groups.Tuple.tuple("2", "5"),
                            org.assertj.core.groups.Tuple.tuple("3", "10"));
        }
    }
}

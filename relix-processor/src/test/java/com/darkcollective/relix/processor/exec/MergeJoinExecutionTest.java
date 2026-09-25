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
import com.darkcollective.relix.processor.internal.ExecutionContext;
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
import static org.assertj.core.api.Assertions.tuple;

/**
 * Executor tests for sort-merge join execution (Phase C2 — ADR-0009).
 *
 * <p>Tests drive the planner into choosing {@link PhysicalNode.JoinAlgorithm#MERGE}
 * by providing at least one pre-sorted input (via a {@code τ} sort node), which
 * causes the planner to insert a Sort enforcer on the unsorted side and choose
 * MERGE over HASH.  Planner-selection tests inspect the returned {@link PhysicalNode}
 * to confirm the algorithm chosen.
 */
@DisplayName("PhysicalExecutor — sort-merge join operators (Phase C2)")
final class MergeJoinExecutionTest extends ProcessorTestSupport {

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

    // ── Planner selects MERGE algorithm ──────────────────────────────────────

    @Nested
    @DisplayName("Planner — MERGE vs HASH selection")
    class PlannerSelection {

        @Test
        @DisplayName("inner join with one sorted input uses MERGE algorithm")
        void oneSortedInputTriggersMerge() {
            PhysicalNode root = plan(
                    "L := [| l_id | val |\n" +
                    "       | 1    | x   |];\n" +
                    "R := [| r_id | data |\n" +
                    "       | 1    | a    |];\n" +
                    "query { (τ l_id ASC (L)) ⨝ l_id = r_id R };");

            assertThat(root).isNode(PhysicalNode.Join.class);
            assertThat(((PhysicalNode.Join) root).algorithm())
                    .isEqualTo(PhysicalNode.JoinAlgorithm.MERGE);
        }

        @Test
        @DisplayName("inner join with neither input sorted uses HASH algorithm")
        void noSortedInputUsesHash() {
            PhysicalNode root = plan(
                    "L := [| l_id | val |\n" +
                    "       | 1    | x   |];\n" +
                    "R := [| r_id | data |\n" +
                    "       | 1    | a    |];\n" +
                    "query { L ⨝ l_id = r_id R };");

            assertThat(root).isNode(PhysicalNode.Join.class);
            assertThat(((PhysicalNode.Join) root).algorithm())
                    .isEqualTo(PhysicalNode.JoinAlgorithm.HASH);
        }

        @Test
        @DisplayName("sort-merge planner inserts Sort enforcer on unsorted side")
        void sortEnforcerInsertedOnRight() {
            PhysicalNode root = plan(
                    "L := [| l_id | val |\n" +
                    "       | 1    | x   |];\n" +
                    "R := [| r_id | data |\n" +
                    "       | 1    | a    |];\n" +
                    "query { (τ l_id ASC (L)) ⨝ l_id = r_id R };");

            PhysicalNode.Join join = (PhysicalNode.Join) root;
            assertThat(join.algorithm()).isEqualTo(PhysicalNode.JoinAlgorithm.MERGE);
            // Right was unsorted — planner wraps it in a Sort enforcer
            assertThat(join.right()).isNode(PhysicalNode.Sort.class);
            // Left was already sorted — no additional Sort wrapper
            assertThat(join.left()).isNode(PhysicalNode.Sort.class);  // the explicit τ
        }

        @Test
        @DisplayName("semi join with sorted input uses MERGE algorithm")
        void semiMerge() {
            PhysicalNode root = plan(
                    "L := [| l_id | val |\n" +
                    "       | 1    | x   |];\n" +
                    "R := [| r_id | data |\n" +
                    "       | 1    | a    |];\n" +
                    "query { (τ l_id ASC (L)) ⋉ l_id = r_id R };");

            assertThat(root).isNode(PhysicalNode.Join.class);
            assertThat(((PhysicalNode.Join) root).algorithm())
                    .isEqualTo(PhysicalNode.JoinAlgorithm.MERGE);
        }

        @Test
        @DisplayName("anti join with sorted input uses MERGE algorithm")
        void antiMerge() {
            PhysicalNode root = plan(
                    "L := [| l_id | val |\n" +
                    "       | 1    | x   |];\n" +
                    "R := [| r_id | data |\n" +
                    "       | 1    | a    |];\n" +
                    "query { (τ l_id ASC (L)) ▷ l_id = r_id R };");

            assertThat(root).isNode(PhysicalNode.Join.class);
            assertThat(((PhysicalNode.Join) root).algorithm())
                    .isEqualTo(PhysicalNode.JoinAlgorithm.MERGE);
        }

        @Test
        @DisplayName("natural join with both inputs sorted uses MERGE algorithm")
        void naturalMerge() {
            PhysicalNode root = plan(
                    "L := [| id | val |\n" +
                    "       | 1  | x   |];\n" +
                    "R := [| id | data |\n" +
                    "       | 1  | a    |];\n" +
                    "query { (τ id ASC (L)) ⋈ (τ id ASC (R)) };");

            assertThat(root).isNode(PhysicalNode.Join.class);
            assertThat(((PhysicalNode.Join) root).algorithm())
                    .isEqualTo(PhysicalNode.JoinAlgorithm.MERGE);
        }
    }

    // ── Merge inner join execution ────────────────────────────────────────────

    @Nested
    @DisplayName("Merge inner join")
    class MergeInner {

        @Test
        @DisplayName("basic equi-join returns matching rows")
        void basicMatch() {
            var rows = collect(
                    "L := [| l_id | name  |\n" +
                    "       | 1    | Alice |\n" +
                    "       | 2    | Bob   |\n" +
                    "       | 3    | Carol |];\n" +
                    "R := [| r_id | score |\n" +
                    "       | 1    | 10    |\n" +
                    "       | 3    | 30    |];\n" +
                    "query { (τ l_id ASC (L)) ⨝ l_id = r_id (τ r_id ASC (R)) };");

            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(r -> r.get("name").asDisplayString())
                    .containsExactlyInAnyOrder("Alice", "Carol");
            assertThat(rows).extracting(r -> r.get("score").asDisplayString())
                    .containsExactlyInAnyOrder("10", "30");
        }

        @Test
        @DisplayName("no key overlap produces empty result")
        void noMatches() {
            var rows = collect(
                    "L := [| l_id | val |\n" +
                    "       | 1    | x   |\n" +
                    "       | 2    | y   |];\n" +
                    "R := [| r_id | data |\n" +
                    "       | 10   | a    |\n" +
                    "       | 20   | b    |];\n" +
                    "query { (τ l_id ASC (L)) ⨝ l_id = r_id (τ r_id ASC (R)) };");

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("duplicate keys on right produce one output row per right duplicate")
        void duplicateRightKeys() {
            var rows = collect(
                    "L := [| l_id | name  |\n" +
                    "       | 1    | Alice |];\n" +
                    "R := [| r_id | amount |\n" +
                    "       | 1    | 100    |\n" +
                    "       | 1    | 200    |\n" +
                    "       | 1    | 300    |];\n" +
                    "query { (τ l_id ASC (L)) ⨝ l_id = r_id (τ r_id ASC (R)) };");

            assertThat(rows).hasSize(3);
            assertThat(rows).extracting(r -> r.get("amount").asDisplayString())
                    .containsExactlyInAnyOrder("100", "200", "300");
        }

        @Test
        @DisplayName("duplicate keys on both sides produce full cross-product of equal-key groups")
        void duplicateKeysOnBothSides() {
            var rows = collect(
                    "L := [| l_id | left_val |\n" +
                    "       | 2    | A         |\n" +
                    "       | 2    | B         |];\n" +
                    "R := [| r_id | right_val |\n" +
                    "       | 2    | X          |\n" +
                    "       | 2    | Y          |];\n" +
                    "query { (τ l_id ASC (L)) ⨝ l_id = r_id (τ r_id ASC (R)) };");

            // 2 left × 2 right = 4 output rows
            assertThat(rows).hasSize(4);
        }

        @Test
        @DisplayName("empty left side produces empty result")
        void emptyLeft() {
            var rows = collect(
                    "L := [| l_id | val |];\n" +
                    "R := [| r_id | data |\n" +
                    "       | 1    | a    |];\n" +
                    "query { (τ l_id ASC (L)) ⨝ l_id = r_id (τ r_id ASC (R)) };");

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("empty right side produces empty result")
        void emptyRight() {
            var rows = collect(
                    "L := [| l_id | val |\n" +
                    "       | 1    | x   |];\n" +
                    "R := [| r_id | data |];\n" +
                    "query { (τ l_id ASC (L)) ⨝ l_id = r_id (τ r_id ASC (R)) };");

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("output schema contains columns from both sides")
        void outputSchemaContainsBothSides() {
            var rows = collect(
                    "L := [| l_id | name  |\n" +
                    "       | 1    | Alice |];\n" +
                    "R := [| r_id | score |\n" +
                    "       | 1    | 99    |];\n" +
                    "query { (τ l_id ASC (L)) ⨝ l_id = r_id (τ r_id ASC (R)) };");

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).schema().columns())
                    .extracting(c -> c.name())
                    .containsExactly("l_id", "name", "r_id", "score");
        }

        @Test
        @DisplayName("multi-key equi-join only matches when all keys agree")
        void multiKeyJoin() {
            var rows = collect(
                    "L := [| a | b | val |\n" +
                    "       | 1 | 2 | x   |\n" +
                    "       | 1 | 3 | y   |\n" +
                    "       | 2 | 2 | z   |];\n" +
                    "R := [| ra | rb | data |\n" +
                    "       | 1  | 2  | p    |\n" +
                    "       | 2  | 3  | q    |];\n" +
                    "query { (τ a ASC (τ b ASC (L))) ⨝ L.a = R.ra ∧ L.b = R.rb" +
                    "        (τ ra ASC (τ rb ASC (R))) };");

            // Only (1,2) matches (1,2) → one result
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("val", "x")
                    .hasValue("data", "p");
        }
    }

    // ── Merge natural join execution ──────────────────────────────────────────

    @Nested
    @DisplayName("Merge natural join")
    class MergeNatural {

        @Test
        @DisplayName("natural merge join matches on shared column and deduplicates it")
        void basicNaturalMerge() {
            var rows = collect(
                    "L := [| id | name  |\n" +
                    "       | 1  | Alice |\n" +
                    "       | 2  | Bob   |\n" +
                    "       | 3  | Carol |];\n" +
                    "R := [| id | score |\n" +
                    "       | 1  | 10   |\n" +
                    "       | 3  | 30   |];\n" +
                    "query { (τ id ASC (L)) ⋈ (τ id ASC (R)) };");

            assertThat(rows).hasSize(2);
            // Output has id, name, score (id appears once)
            assertThat(rows.get(0).schema().columns())
                    .extracting(c -> c.name())
                    .containsExactly("id", "name", "score");
            assertThat(rows).extracting(r -> r.get("name").asDisplayString())
                    .containsExactlyInAnyOrder("Alice", "Carol");
        }

        @Test
        @DisplayName("natural merge join on empty left yields empty result")
        void emptyLeft() {
            var rows = collect(
                    "L := [| id | val |];\n" +
                    "R := [| id | data |\n" +
                    "       | 1  | a    |];\n" +
                    "query { (τ id ASC (L)) ⋈ (τ id ASC (R)) };");

            assertThat(rows).isEmpty();
        }
    }

    // ── Merge semi-join execution ─────────────────────────────────────────────

    @Nested
    @DisplayName("Merge semi-join")
    class MergeSemi {

        @Test
        @DisplayName("semi-join emits left rows that have a matching right row")
        void basicSemi() {
            var rows = collect(
                    "Customers := [| cust_id | name  |\n" +
                    "               | 1       | Alice |\n" +
                    "               | 2       | Bob   |\n" +
                    "               | 3       | Carol |];\n" +
                    "Orders := [| order_cid | amount |\n" +
                    "            | 1         | 100    |\n" +
                    "            | 3         | 300    |];\n" +
                    "query { (τ cust_id ASC (Customers)) ⋉" +
                    "        cust_id = order_cid (τ order_cid ASC (Orders)) };");

            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(r -> r.get("name").asDisplayString())
                    .containsExactlyInAnyOrder("Alice", "Carol");
        }

        @Test
        @DisplayName("semi-join emits each left row at most once even with multiple right matches")
        void leftRowEmittedOnce() {
            var rows = collect(
                    "L := [| l_id | val |\n" +
                    "       | 1    | x   |];\n" +
                    "R := [| r_id | data |\n" +
                    "       | 1    | a    |\n" +
                    "       | 1    | b    |\n" +
                    "       | 1    | c    |];\n" +
                    "query { (τ l_id ASC (L)) ⋉ l_id = r_id (τ r_id ASC (R)) };");

            assertThat(rows).hasSize(1);
        }

        @Test
        @DisplayName("semi-join with no right matches produces empty result")
        void noMatches() {
            var rows = collect(
                    "L := [| l_id | val |\n" +
                    "       | 1    | x   |\n" +
                    "       | 2    | y   |];\n" +
                    "R := [| r_id | data |\n" +
                    "       | 10   | a    |];\n" +
                    "query { (τ l_id ASC (L)) ⋉ l_id = r_id (τ r_id ASC (R)) };");

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("semi-join output schema is left-only")
        void outputIsLeftOnly() {
            var rows = collect(
                    "L := [| l_id | name  |\n" +
                    "       | 1    | Alice |];\n" +
                    "R := [| r_id | score |\n" +
                    "       | 1    | 99    |];\n" +
                    "query { (τ l_id ASC (L)) ⋉ l_id = r_id (τ r_id ASC (R)) };");

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).schema().columns())
                    .extracting(c -> c.name())
                    .containsExactly("l_id", "name");
        }
    }

    // ── Merge anti-join execution ─────────────────────────────────────────────

    @Nested
    @DisplayName("Merge anti-join")
    class MergeAnti {

        @Test
        @DisplayName("anti-join emits left rows with no matching right row")
        void basicAnti() {
            var rows = collect(
                    "Customers := [| cust_id | name  |\n" +
                    "               | 1       | Alice |\n" +
                    "               | 2       | Bob   |\n" +
                    "               | 3       | Carol |];\n" +
                    "Orders := [| order_cid | amount |\n" +
                    "            | 1         | 100    |];\n" +
                    "query { (τ cust_id ASC (Customers)) ▷" +
                    "        cust_id = order_cid (τ order_cid ASC (Orders)) };");

            // Bob and Carol have no orders
            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(r -> r.get("name").asDisplayString())
                    .containsExactlyInAnyOrder("Bob", "Carol");
        }

        @Test
        @DisplayName("anti-join with all left matching produces empty result")
        void allMatchProducesEmpty() {
            var rows = collect(
                    "L := [| l_id | val |\n" +
                    "       | 1    | x   |\n" +
                    "       | 2    | y   |];\n" +
                    "R := [| r_id | data |\n" +
                    "       | 1    | a    |\n" +
                    "       | 2    | b    |];\n" +
                    "query { (τ l_id ASC (L)) ▷ l_id = r_id (τ r_id ASC (R)) };");

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("anti-join with empty right emits all left rows")
        void emptyRightEmitsAllLeft() {
            var rows = collect(
                    "L := [| l_id | val |\n" +
                    "       | 1    | x   |\n" +
                    "       | 2    | y   |\n" +
                    "       | 3    | z   |];\n" +
                    "R := [| r_id | data |];\n" +
                    "query { (τ l_id ASC (L)) ▷ l_id = r_id (τ r_id ASC (R)) };");

            assertThat(rows).hasSize(3);
        }

        @Test
        @DisplayName("anti-join output schema is left-only")
        void outputIsLeftOnly() {
            var rows = collect(
                    "L := [| l_id | name  |\n" +
                    "       | 2    | Bob   |];\n" +
                    "R := [| r_id | score |\n" +
                    "       | 1    | 99    |];\n" +
                    "query { (τ l_id ASC (L)) ▷ l_id = r_id (τ r_id ASC (R)) };");

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).schema().columns())
                    .extracting(c -> c.name())
                    .containsExactly("l_id", "name");
        }

        @Test
        @DisplayName("semi and anti-join together partition left rows by match status")
        void semiAndAntiPartition() {
            String data =
                    "L := [| l_id | val |\n" +
                    "       | 1    | x   |\n" +
                    "       | 2    | y   |\n" +
                    "       | 3    | z   |];\n" +
                    "R := [| r_id | data |\n" +
                    "       | 1    | a    |\n" +
                    "       | 3    | c    |];\n";
            var semiRows = collect(data +
                    "query { (τ l_id ASC (L)) ⋉ l_id = r_id (τ r_id ASC (R)) };");
            var antiRows = collect(data +
                    "query { (τ l_id ASC (L)) ▷ l_id = r_id (τ r_id ASC (R)) };");

            assertThat(semiRows).hasSize(2);
            assertThat(antiRows).hasSize(1);
            assertThat(semiRows).extracting(r -> r.get("l_id").asDisplayString())
                    .containsExactlyInAnyOrder("1", "3");
            assertThat(antiRows).extracting(r -> r.get("l_id").asDisplayString())
                    .containsExactlyInAnyOrder("2");
        }
    }

    // ── Reusing a merge join's delivered ordering (issue #529) ────────────────

    /**
     * A merge join advertises the order its scan produces, which lets the planner
     * feed a downstream merge join, streaming {@code δ}, or streaming {@code γ}
     * directly.  These tests check the <em>results</em> of the plans that reuse it:
     * a wrong ordering claim shows up as dropped or mis-grouped rows.
     */
    @Nested
    @DisplayName("Merge-join delivered ordering — downstream reuse")
    class DeliveredOrderingReuse {

        /** Three relations sharing the column {@code k}, with duplicate keys on each side. */
        private static final String THREE_KEYED =
                "A := [| k | a |\n" +
                "       | 2 | a2 |\n" +
                "       | 1 | a1 |\n" +
                "       | 1 | a1b |];\n" +
                "B := [| k | b |\n" +
                "       | 1 | b1 |\n" +
                "       | 3 | b3 |];\n" +
                "C := [| k | c |\n" +
                "       | 1 | c1 |];\n";

        @Test
        @DisplayName("a chain of merge joins plans without an intervening Sort and joins correctly")
        void chainedMergeJoins() {
            String query = "query { ((τ k ASC (A)) ⋈ (τ k ASC (B))) ⋈ (τ k ASC (C)) };";

            PhysicalNode.Join outer = (PhysicalNode.Join) plan(THREE_KEYED + query);
            assertThat(outer.algorithm()).isEqualTo(PhysicalNode.JoinAlgorithm.MERGE);
            assertThat(outer.left()).isNode(PhysicalNode.Join.class);

            // A ⋈ B on k=1 gives two rows (a1, a1b); ⋈ C on k=1 keeps both.
            var rows = collect(THREE_KEYED + query);
            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(r -> r.get("a").asDisplayString())
                    .containsExactlyInAnyOrder("a1", "a1b");
            assertThat(rows.get(0).schema().columns())
                    .extracting(c -> c.name())
                    .containsExactly("k", "a", "b", "c");
        }

        @Test
        @DisplayName("streaming δ over a merge join deduplicates the same rows as the hash variant")
        void streamingDistinctOverMergeJoin() {
            String data =
                    "A := [| k |\n" +
                    "       | 1 |\n" +
                    "       | 1 |\n" +
                    "       | 2 |];\n" +
                    "B := [| k |\n" +
                    "       | 1 |\n" +
                    "       | 2 |];\n";

            PhysicalNode sorted = plan(data + "query { δ ((τ k ASC (A)) ⋈ (τ k ASC (B))) };");
            assertThat(sorted).isNode(PhysicalNode.Distinct.class);
            assertThat(((PhysicalNode.Distinct) sorted).streaming()).isTrue();

            var streamed = collect(data + "query { δ ((τ k ASC (A)) ⋈ (τ k ASC (B))) };");
            var hashed   = collect(data + "query { δ (A ⋈ B) };");
            assertThat(streamed).extracting(r -> r.get("k").asDisplayString())
                    .containsExactlyInAnyOrderElementsOf(
                            hashed.stream().map(r -> r.get("k").asDisplayString()).toList())
                    .containsExactlyInAnyOrder("1", "2");
        }

        @Test
        @DisplayName("streaming γ over a merge join groups the same as the hash variant")
        void streamingAggregateOverMergeJoin() {
            String agg = "γ k, COUNT(*) → n (%s)";
            String merged = THREE_KEYED
                    + "query { " + agg.formatted("(τ k ASC (A)) ⋈ (τ k ASC (B))") + " };";
            String hashed = THREE_KEYED + "query { " + agg.formatted("A ⋈ B") + " };";

            PhysicalNode plan = plan(merged);
            assertThat(plan).isNode(PhysicalNode.Aggregate.class);
            assertThat(((PhysicalNode.Aggregate) plan).streaming()).isTrue();

            // Only k=1 matches, with two left rows → a single group of 2.
            assertThat(collect(merged)).extracting(
                            r -> r.get("k").asDisplayString(), r -> r.get("n").asDisplayString())
                    .containsExactlyInAnyOrderElementsOf(
                            collect(hashed).stream()
                                    .map(r -> tuple(r.get("k").asDisplayString(),
                                                    r.get("n").asDisplayString()))
                                    .toList())
                    .containsExactly(tuple("1", "2"));
        }
    }
}

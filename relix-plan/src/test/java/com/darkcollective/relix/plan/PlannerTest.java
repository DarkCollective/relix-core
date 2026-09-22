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
package com.darkcollective.relix.plan;

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SortDirection;
import com.darkcollective.relix.cost.Boundedness;
import com.darkcollective.relix.cost.BoundednessSource;
import com.darkcollective.relix.cost.Ordering;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.plan.PhysicalNode.BuildSide;
import com.darkcollective.relix.plan.PhysicalNode.JoinAlgorithm;
import com.darkcollective.relix.plan.PhysicalNode.JoinKind;
import com.darkcollective.relix.plan.PhysicalNode.SetKind;
import com.darkcollective.relix.events.QueryEvent;
import com.darkcollective.relix.events.QueryEventListener;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.RelationStatistics;
import org.junit.jupiter.api.DisplayName;
import com.darkcollective.relix.symbol.table.InMemorySymbolTable;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.relation.SourceRelationSymbol;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.plan.PlanAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@DisplayName("Planner — logical RelNode tree → physical plan")
final class PlannerTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Plans the first (expression) root query of {@code src}. */
    private static PhysicalNode planFirstQuery(String src) {
        SemanticModel model = model(src);
        RelNode logical = ((ExpressionQueryTarget) model.rootQueries().get(0).target()).expression();
        return new Planner(model.symbolTable(), model.nodeSchemas()).plan(logical);
    }

    /** Plans the first root query with an explicit relation-statistics map. */
    private static PhysicalNode planFirstQuery(String src, Map<String, RelationStatistics> stats) {
        SemanticModel model = model(src);
        RelNode logical = ((ExpressionQueryTarget) model.rootQueries().get(0).target()).expression();
        return new Planner(model.symbolTable(), model.nodeSchemas(), stats).plan(logical);
    }

    /** Plans the first root query with a stub per-leaf {@link BoundednessSource} (ADR-0008). */
    private static PhysicalNode planWithBoundedness(String src, BoundednessSource bs) {
        SemanticModel model = model(src);
        RelNode logical = ((ExpressionQueryTarget) model.rootQueries().get(0).target()).expression();
        return new Planner(model.symbolTable(), model.nodeSchemas(), Map.of(), Map.of(), Map.of(),
                QueryEventListener.NONE, bs).plan(logical);
    }

    /** A stub source that reports the named relations {@code UNBOUNDED}, all else {@code BOUNDED}. */
    private static BoundednessSource unbounded(String... names) {
        var set = java.util.Set.of(names);
        return name -> set.contains(name) ? Boundedness.UNBOUNDED : Boundedness.BOUNDED;
    }

    /** A script with two same-tier (database) relations joined on a key. */
    private static final String TWO_DB_JOIN =
            "source A from database { url: \"jdbc:h2:mem\", table: \"a\", schema: { k: NUMBER } };\n" +
            "source B from database { url: \"jdbc:h2:mem\", table: \"b\", schema: { k: NUMBER } };\n" +
            "query { A ⨝ A.k = B.k B };";

    // ── joins ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("equi theta-join → HASH with the extracted key columns")
    void thetaEquiJoinIsHash() {
        PhysicalNode.Join j = assertThat(planFirstQuery(
                "Users := [| id | name  |\n" +
                "           | 1  | Alice |];\n" +
                "Orders := [| user_id | amt |\n" +
                "            | 1       | 50  |];\n" +
                "query { Users ⨝ Users.id = Orders.user_id Orders };")).asNode(PhysicalNode.Join.class);

        assertThat(j.kind()).isEqualTo(JoinKind.INNER);
        assertThat(j.algorithm()).isEqualTo(JoinAlgorithm.HASH);
        assertThat(j.keys().left()).containsExactly(0);    // Users.id
        assertThat(j.keys().right()).containsExactly(0);   // Orders.user_id
        assertThat(j.left()).isNode(PhysicalNode.Scan.class);
        assertThat(j.right()).isNode(PhysicalNode.Scan.class);
        // both inline → equal cost → build the right (historical tie-break)
        assertThat(j.buildSide()).isEqualTo(BuildSide.RIGHT);
    }

    @Test
    @DisplayName("non-equi theta-join → NESTED_LOOP with no keys")
    void thetaInequalityIsNestedLoop() {
        PhysicalNode.Join j = assertThat(planFirstQuery(
                "Prices := [| pid | price |\n" +
                "            | 1   | 10    |];\n" +
                "Limits := [| lid | cap |\n" +
                "            | 1   | 25  |];\n" +
                "query { Prices ⨝ Prices.price <= Limits.cap Limits };")).asNode(PhysicalNode.Join.class);

        assertThat(j.algorithm()).isEqualTo(JoinAlgorithm.NESTED_LOOP);
        assertThat(j.keys().isEmpty()).isTrue();
        assertThat(j.condition()).isPresent();
    }

    @Test
    @DisplayName("natural join → NATURAL/HASH keyed on the common columns")
    void naturalJoinKeysOnCommonColumns() {
        PhysicalNode.Join j = assertThat(planFirstQuery(
                "A := [| k | a |\n" +
                "       | 1 | x |];\n" +
                "B := [| k | b |\n" +
                "       | 1 | y |];\n" +
                "query { A ⋈ B };")).asNode(PhysicalNode.Join.class);

        assertThat(j.kind()).isEqualTo(JoinKind.NATURAL);
        assertThat(j.algorithm()).isEqualTo(JoinAlgorithm.HASH);
        assertThat(j.condition()).isEmpty();
        assertThat(j.keys().left()).containsExactly(0);    // A.k
        assertThat(j.keys().right()).containsExactly(0);   // B.k
    }

    @Test
    @DisplayName("Cartesian product → PRODUCT/NESTED_LOOP, no condition, no keys")
    void productIsNestedLoop() {
        PhysicalNode.Join j = assertThat(planFirstQuery(
                "A := [| x |\n" +
                "       | 1 |];\n" +
                "B := [| y |\n" +
                "       | 2 |];\n" +
                "query { A × B };")).asNode(PhysicalNode.Join.class);

        assertThat(j.kind()).isEqualTo(JoinKind.PRODUCT);
        assertThat(j.algorithm()).isEqualTo(JoinAlgorithm.NESTED_LOOP);
        assertThat(j.condition()).isEmpty();
        assertThat(j.keys().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("build side follows the cost model: inline beats an external source")
    void buildSideFollowsCost() {
        PhysicalNode.Join j = assertThat(planFirstQuery(
                "Users := [| id | name  |\n" +
                "           | 1  | Alice |];\n" +
                "source Orders from database {\n" +
                "    url: \"jdbc:h2:mem\", table: \"o\",\n" +
                "    schema: { user_id: NUMBER }\n" +
                "};\n" +
                "query { Users ⨝ Users.id = Orders.user_id Orders };")).asNode(PhysicalNode.Join.class);

        // left = inline (cheap), right = database (costly) → build the left.
        assertThat(j.buildSide()).isEqualTo(BuildSide.LEFT);
    }

    @Test
    @DisplayName("row-count statistics build the smaller side, overriding a tier tie")
    void buildSideFollowsRowCounts() {
        // Both sides are database (same tier) — without stats the tie-break builds RIGHT.
        assertThat(assertThat(planFirstQuery(TWO_DB_JOIN)).asNode(PhysicalNode.Join.class).buildSide()).isEqualTo(BuildSide.RIGHT);

        // Left smaller → build LEFT; right smaller → build RIGHT.
        assertThat(assertThat(planFirstQuery(TWO_DB_JOIN,
                Map.of("a", RelationStatistics.of(10), "b", RelationStatistics.of(1000)))).asNode(PhysicalNode.Join.class)
                .buildSide()).isEqualTo(BuildSide.LEFT);
        assertThat(assertThat(planFirstQuery(TWO_DB_JOIN,
                Map.of("a", RelationStatistics.of(1000), "b", RelationStatistics.of(10)))).asNode(PhysicalNode.Join.class)
                .buildSide()).isEqualTo(BuildSide.RIGHT);
    }

    @Test
    @DisplayName("with only one side's row count known, planning falls back to the tier model")
    void buildSidePartialStatsFallsBackToTier() {
        // Only A has a row count → cannot compare by rows → tier tie → build RIGHT.
        assertThat(assertThat(planFirstQuery(TWO_DB_JOIN, Map.of("a", RelationStatistics.of(10)))).asNode(PhysicalNode.Join.class)
                .buildSide()).isEqualTo(BuildSide.RIGHT);
    }

    // ── merge join (Phase C2 — ADR-0009) ──────────────────────────────────────

    @Nested
    @DisplayName("Merge join — planner decisions")
    class MergeJoin {

        @Test
        @DisplayName("equi inner join with one sorted input uses MERGE algorithm")
        void oneSortedSideTriggersMerge() {
            PhysicalNode.Join j = assertThat(planFirstQuery(
                    "L := [| l_id | val |\n" +
                    "       | 1    | x   |];\n" +
                    "R := [| r_id | data |\n" +
                    "       | 1    | a    |];\n" +
                    "query { (τ l_id ASC (L)) ⨝ l_id = r_id R };")).asNode(PhysicalNode.Join.class);

            assertThat(j.algorithm()).isEqualTo(JoinAlgorithm.MERGE);
            assertThat(j.kind()).isEqualTo(JoinKind.INNER);
            assertThat(j.buildSide()).isEqualTo(BuildSide.RIGHT);  // convention for MERGE
        }

        @Test
        @DisplayName("planner inserts a Sort enforcer on the unsorted side")
        void sortEnforcerInsertedOnUnsortedSide() {
            PhysicalNode.Join j = assertThat(planFirstQuery(
                    "L := [| l_id | val |\n" +
                    "       | 1    | x   |];\n" +
                    "R := [| r_id | data |\n" +
                    "       | 1    | a    |];\n" +
                    "query { L ⨝ l_id = r_id (τ r_id ASC (R)) };")).asNode(PhysicalNode.Join.class);

            assertThat(j.algorithm()).isEqualTo(JoinAlgorithm.MERGE);
            // Left was unsorted — planner wraps it in a Sort enforcer
            assertThat(j.left()).isNode(PhysicalNode.Sort.class);
            // Right was explicitly sorted — it's the Sort node from the query
            assertThat(j.right()).isNode(PhysicalNode.Sort.class);
        }

        @Test
        @DisplayName("equi join with neither input sorted stays HASH (no single Sort enforcer overhead)")
        void neitherSortedStaysHash() {
            PhysicalNode.Join j = assertThat(planFirstQuery(
                    "L := [| l_id | val |\n" +
                    "       | 1    | x   |];\n" +
                    "R := [| r_id | data |\n" +
                    "       | 1    | a    |];\n" +
                    "query { L ⨝ l_id = r_id R };")).asNode(PhysicalNode.Join.class);

            assertThat(j.algorithm()).isEqualTo(JoinAlgorithm.HASH);
        }

        @Test
        @DisplayName("natural join with both sides sorted uses MERGE algorithm")
        void naturalJoinBothSortedIsMerge() {
            PhysicalNode.Join j = assertThat(planFirstQuery(
                    "A := [| k | a |\n" +
                    "       | 1 | x |];\n" +
                    "B := [| k | b |\n" +
                    "       | 1 | y |];\n" +
                    "query { (τ k ASC (A)) ⋈ (τ k ASC (B)) };")).asNode(PhysicalNode.Join.class);

            assertThat(j.kind()).isEqualTo(JoinKind.NATURAL);
            assertThat(j.algorithm()).isEqualTo(JoinAlgorithm.MERGE);
        }

        @Test
        @DisplayName("semi join with sorted left input uses MERGE algorithm")
        void semiJoinSortedInputIsMerge() {
            PhysicalNode.Join j = assertThat(planFirstQuery(
                    "L := [| l_id | val |\n" +
                    "       | 1    | x   |];\n" +
                    "R := [| r_id | data |\n" +
                    "       | 1    | a    |];\n" +
                    "query { (τ l_id ASC (L)) ⋉ l_id = r_id R };")).asNode(PhysicalNode.Join.class);

            assertThat(j.kind()).isEqualTo(JoinKind.SEMI);
            assertThat(j.algorithm()).isEqualTo(JoinAlgorithm.MERGE);
        }

        @Test
        @DisplayName("anti join with sorted left input uses MERGE algorithm")
        void antiJoinSortedInputIsMerge() {
            PhysicalNode.Join j = assertThat(planFirstQuery(
                    "L := [| l_id | val |\n" +
                    "       | 1    | x   |];\n" +
                    "R := [| r_id | data |\n" +
                    "       | 1    | a    |];\n" +
                    "query { (τ l_id ASC (L)) ▷ l_id = r_id R };")).asNode(PhysicalNode.Join.class);

            assertThat(j.kind()).isEqualTo(JoinKind.ANTI);
            assertThat(j.algorithm()).isEqualTo(JoinAlgorithm.MERGE);
        }

        @Test
        @DisplayName("deliveredOrdering() propagates through Sort → Select → Limit")
        void deliveredOrderingPropagation() {
            // Sort establishes an ordering; Select and Limit preserve it.
            PhysicalNode plan = planFirstQuery(
                    "L := [| id | val |\n" +
                    "       | 1  | x   |\n" +
                    "       | 2  | y   |];\n" +
                    "query { λ 1 (σ val != \"z\" (τ id ASC (L))) };");

            // Expected: Limit(Select(Sort))
            PhysicalNode.Limit limit = assertThat(plan).asNode(PhysicalNode.Limit.class);
            assertThat(plan.deliveredOrdering().keys()).isNotEmpty();

            PhysicalNode.Select select = (PhysicalNode.Select) limit.input();
            assertThat(select.deliveredOrdering().keys()).isNotEmpty();
        }
    }

    // ── merge-join delivered ordering (Phase C2 — ADR-0009, issue #529) ─────────

    @Nested
    @DisplayName("Merge join — delivered ordering")
    class MergeJoinOrdering {

        /** The column names of {@code node}'s delivered ordering, in priority order. */
        private static List<String> orderedColumns(PhysicalNode node) {
            return node.deliveredOrdering().keys().stream()
                    .map(k -> k.columnName().orElseThrow())
                    .toList();
        }

        /** Two single-column relations sharing the column {@code k}. */
        private static final String TWO_KEYED =
                "A := [| k | a |\n" +
                "       | 1 | x |];\n" +
                "B := [| k | b |\n" +
                "       | 1 | y |];\n";

        @Test
        @DisplayName("an inner MERGE join delivers its join keys, ascending")
        void innerMergeDeliversJoinKeys() {
            PhysicalNode.Join j = assertThat(planFirstQuery(
                    "L := [| l_id | val |\n" +
                    "       | 1    | x   |];\n" +
                    "R := [| r_id | data |\n" +
                    "       | 1    | a    |];\n" +
                    "query { (τ l_id ASC (L)) ⨝ l_id = r_id R };")).asNode(PhysicalNode.Join.class);

            assertThat(j.algorithm()).isEqualTo(JoinAlgorithm.MERGE);
            assertThat(orderedColumns(j)).containsExactly("l_id");
            assertThat(j.deliveredOrdering().keys().get(0).direction()).isEqualTo(SortDirection.ASC);
            // The delivered ordering is exactly what a downstream merge would require.
            assertThat(j.deliveredOrdering())
                    .isEqualTo(Planner.mergeOrdering(j.keys().left(), j.left().schema()));
        }

        @Test
        @DisplayName("a natural MERGE join delivers its common columns")
        void naturalMergeDeliversCommonColumns() {
            PhysicalNode.Join j = assertThat(planFirstQuery(
                    TWO_KEYED + "query { (τ k ASC (A)) ⋈ (τ k ASC (B)) };")).asNode(PhysicalNode.Join.class);

            assertThat(j.algorithm()).isEqualTo(JoinAlgorithm.MERGE);
            assertThat(orderedColumns(j)).containsExactly("k");
        }

        @Test
        @DisplayName("a semi MERGE join delivers the left input's ordering")
        void semiMergeDeliversLeftOrdering() {
            // The left is sorted by (l_id, val) — stronger than the merge key alone,
            // and preserved, since the output rows are a subsequence of the left.
            PhysicalNode.Join j = assertThat(planFirstQuery(
                    "L := [| l_id | val |\n" +
                    "       | 1    | x   |];\n" +
                    "R := [| r_id | data |\n" +
                    "       | 1    | a    |];\n" +
                    "query { (τ l_id ASC, val ASC (L)) ⋉ l_id = r_id R };")).asNode(PhysicalNode.Join.class);

            assertThat(j.algorithm()).isEqualTo(JoinAlgorithm.MERGE);
            assertThat(orderedColumns(j)).containsExactly("l_id", "val");
        }

        @Test
        @DisplayName("an anti MERGE join advertises no ordering (NULL keys are emitted first)")
        void antiMergeAdvertisesNoOrdering() {
            PhysicalNode.Join j = assertThat(planFirstQuery(
                    "L := [| l_id | val |\n" +
                    "       | 1    | x   |];\n" +
                    "R := [| r_id | data |\n" +
                    "       | 1    | a    |];\n" +
                    "query { (τ l_id ASC (L)) ▷ l_id = r_id R };")).asNode(PhysicalNode.Join.class);

            assertThat(j.algorithm()).isEqualTo(JoinAlgorithm.MERGE);
            assertThat(j.deliveredOrdering()).isEqualTo(Ordering.none());
        }

        @Test
        @DisplayName("a HASH or NESTED_LOOP join advertises no ordering")
        void nonMergeJoinsAdvertiseNoOrdering() {
            PhysicalNode.Join hash = assertThat(planFirstQuery(
                    TWO_KEYED + "query { A ⨝ A.k = B.k B };")).asNode(PhysicalNode.Join.class);
            assertThat(hash.algorithm()).isEqualTo(JoinAlgorithm.HASH);
            assertThat(hash.deliveredOrdering()).isEqualTo(Ordering.none());

            // Even with a sorted input: a hash scan makes no order promise.
            PhysicalNode.Join loop = assertThat(planFirstQuery(
                    "P := [| price |\n" +
                    "       | 10    |];\n" +
                    "C := [| cap |\n" +
                    "       | 25  |];\n" +
                    "query { (τ price ASC (P)) ⨝ price <= cap C };")).asNode(PhysicalNode.Join.class);
            assertThat(loop.algorithm()).isEqualTo(JoinAlgorithm.NESTED_LOOP);
            assertThat(loop.deliveredOrdering()).isEqualTo(Ordering.none());
        }

        @Test
        @DisplayName("a chain of merge joins needs no intervening Sort")
        void chainedMergeJoinsNeedNoInterveningSort() {
            PhysicalNode.Join outer = assertThat(planFirstQuery(
                    TWO_KEYED +
                    "C := [| k | c |\n" +
                    "       | 1 | z |];\n" +
                    "query { ((τ k ASC (A)) ⋈ (τ k ASC (B))) ⋈ (τ k ASC (C)) };")).asNode(PhysicalNode.Join.class);

            assertThat(outer.algorithm()).isEqualTo(JoinAlgorithm.MERGE);
            // The inner merge join already delivers k ASC, so it feeds the outer
            // merge directly rather than through an inserted Sort enforcer.
            assertThat(outer.left()).isNode(PhysicalNode.Join.class);
            assertThat(((PhysicalNode.Join) outer.left()).algorithm()).isEqualTo(JoinAlgorithm.MERGE);
            assertThat(outer.right()).isNode(PhysicalNode.Sort.class);  // the query's own τ
            assertThat(orderedColumns(outer)).containsExactly("k");
        }

        @Test
        @DisplayName("δ over a merge join's output streams instead of hashing")
        void distinctOverMergeJoinIsStreaming() {
            // The natural join's output is exactly the common column, so the delivered
            // ordering covers the whole row — duplicates are contiguous.
            PhysicalNode plan = planFirstQuery(
                    "A := [| k |\n" +
                    "       | 1 |];\n" +
                    "B := [| k |\n" +
                    "       | 1 |];\n" +
                    "query { δ ((τ k ASC (A)) ⋈ (τ k ASC (B))) };");

            assertThat(plan).isNode(PhysicalNode.Distinct.class);
            assertThat(((PhysicalNode.Distinct) plan).streaming()).isTrue();
        }

        @Test
        @DisplayName("γ grouped by a merge join's keys streams instead of hashing")
        void aggregateOverMergeJoinIsStreaming() {
            PhysicalNode plan = planFirstQuery(
                    TWO_KEYED +
                    "query { γ k, COUNT(*) → n ((τ k ASC (A)) ⋈ (τ k ASC (B))) };");

            assertThat(plan).isNode(PhysicalNode.Aggregate.class);
            assertThat(((PhysicalNode.Aggregate) plan).streaming()).isTrue();
        }
    }

    // ── source-sorted merge (Phase C4 — ADR-0009) ───────────────────────────────

    @Nested
    @DisplayName("Source-sorted merge — cost-based plan choice")
    class SourceSortedMerge {

        /** A cross-connection join: each side scans a table on its own database. */
        private static final String FEDERATION =
                "connection db1 from database { url: \"jdbc:h2:mem:a\" };\n" +
                "connection db2 from database { url: \"jdbc:h2:mem:b\" };\n" +
                "source Orders from db1 { table: \"orders\", schema: { id: NUMBER, amount: NUMBER } };\n" +
                "source Customers from db2 { table: \"customers\", schema: { cid: NUMBER, name: STRING } };\n" +
                "query { Orders ⨝ Orders.id = Customers.cid Customers };";

        /** A statistics value whose sole candidate key is {@code keyColumns}. */
        private static RelationStatistics keyedOn(String... keyColumns) {
            return new RelationStatistics(OptionalLong.empty(), Map.of(), List.of(List.of(keyColumns)));
        }

        /** Plans the federation query with pushdown enabled and the given per-table statistics. */
        private static PhysicalNode.Join planFederation(Map<String, RelationStatistics> stats) {
            SemanticModel model = model(FEDERATION);
            RelNode logical =
                    ((ExpressionQueryTarget) model.rootQueries().get(0).target()).expression();
            return assertThat(new Planner(model.symbolTable(), model.nodeSchemas(),
                    stats, model.sources(), model.connections(), QueryEventListener.NONE,
                    BoundednessSource.ALL_BOUNDED, model.functions()).plan(logical)).asNode(PhysicalNode.Join.class);
        }

        @Test
        @DisplayName("two index-backed sources merge by pushing ORDER BY into each (the federation win)")
        void bothIndexBackedUseSourceSortedMerge() {
            PhysicalNode.Join j = planFederation(
                    Map.of("orders", keyedOn("id"), "customers", keyedOn("cid")));
            assertThat(j.algorithm()).isEqualTo(JoinAlgorithm.MERGE);
            assertThat(j.left()).isNode(PhysicalNode.PushedScan.class);
            assertThat(j.right()).isNode(PhysicalNode.PushedScan.class);
            assertThat(((PhysicalNode.PushedScan) j.left()).nativeQuery())
                    .isEqualTo("SELECT id, amount FROM orders ORDER BY (id IS NULL) ASC, id ASC");
            assertThat(((PhysicalNode.PushedScan) j.right()).nativeQuery())
                    .isEqualTo("SELECT cid, name FROM customers ORDER BY (cid IS NULL) ASC, cid ASC");
        }

        @Test
        @DisplayName("with no candidate keys, neither source is index-backed → stays HASH")
        void noKeysStaysHash() {
            assertThat(planFederation(Map.of()).algorithm()).isEqualTo(JoinAlgorithm.HASH);
        }

        @Test
        @DisplayName("only one side index-backed → stays HASH (no lop-sided merge)")
        void oneSideIndexBackedStaysHash() {
            assertThat(planFederation(Map.of("orders", keyedOn("id"))).algorithm())
                    .isEqualTo(JoinAlgorithm.HASH);
        }

        @Test
        @DisplayName("a candidate key on a non-join column does not back the merge order → stays HASH")
        void nonOrderKeyStaysHash() {
            assertThat(planFederation(Map.of("orders", keyedOn("amount"), "customers", keyedOn("name")))
                    .algorithm()).isEqualTo(JoinAlgorithm.HASH);
        }
    }

    // ── streaming δ/γ over ordered input (Phase C3 — ADR-0009) ──────────────────

    @Nested
    @DisplayName("Streaming δ/γ — planner decisions over ordered input")
    class StreamingDistinctAggregate {

        @Test
        @DisplayName("δ over an input ordered by the whole row is streaming")
        void distinctOverFullyOrderedIsStreaming() {
            PhysicalNode.Distinct d = assertThat(planFirstQuery(
                    "R := [| id |\n" +
                    "       | 1  |];\n" +
                    "query { δ (τ id ASC (R)) };")).asNode(PhysicalNode.Distinct.class);
            assertThat(d.streaming()).isTrue();
            // a streaming δ preserves the input's delivered ordering
            assertThat(d.deliveredOrdering().keys()).isNotEmpty();
        }

        @Test
        @DisplayName("δ over an unsorted input falls back to the hash variant")
        void distinctOverUnsortedIsNotStreaming() {
            PhysicalNode.Distinct d = assertThat(planFirstQuery(
                    "R := [| id |\n" +
                    "       | 1  |];\n" +
                    "query { δ (R) };")).asNode(PhysicalNode.Distinct.class);
            assertThat(d.streaming()).isFalse();
            assertThat(d.deliveredOrdering().keys()).isEmpty();
        }

        @Test
        @DisplayName("δ over an order covering only some columns is not streaming")
        void distinctOverPartialOrderIsNotStreaming() {
            // Ordered by id only; val is uncovered, so equal rows are not guaranteed adjacent.
            assertThat(assertThat(planFirstQuery(
                    "R := [| id | val |\n" +
                    "       | 1  | x   |];\n" +
                    "query { δ (τ id ASC (R)) };")).asNode(PhysicalNode.Distinct.class).streaming()).isFalse();
        }

        @Test
        @DisplayName("δ over an open (schema-on-read) input is never streaming")
        void distinctOverOpenSchemaIsNotStreaming() {
            // An open relation has no declared columns, so the whole-row "clusters"
            // check is vacuously satisfied — but the input carries no proven ordering,
            // so adjacent-only deduplication would be unsound.  Must fall back to hash.
            assertThat(assertThat(planFirstQuery(
                    "source Docs from json(\"docs.json\");\n" +
                    "query { δ (Docs) };")).asNode(PhysicalNode.Distinct.class).streaming()).isFalse();
        }

        @Test
        @DisplayName("γ over an input ordered by its grouping key is streaming")
        void aggregateOverGroupOrderedIsStreaming() {
            assertThat(assertThat(planFirstQuery(
                    "R := [| region | amount |\n" +
                    "       | 1      | 10     |];\n" +
                    "query { γ region, SUM(amount) → total (τ region ASC (R)) };")).asNode(PhysicalNode.Aggregate.class).streaming()).isTrue();
        }

        @Test
        @DisplayName("γ over an unordered input falls back to the hash-grouped variant")
        void aggregateOverUnorderedIsNotStreaming() {
            assertThat(assertThat(planFirstQuery(
                    "R := [| region | amount |\n" +
                    "       | 1      | 10     |];\n" +
                    "query { γ region, SUM(amount) → total (R) };")).asNode(PhysicalNode.Aggregate.class).streaming()).isFalse();
        }

        @Test
        @DisplayName("a scalar γ (no grouping keys) is never streaming even over a sorted input")
        void scalarAggregateIsNotStreaming() {
            assertThat(assertThat(planFirstQuery(
                    "R := [| amount |\n" +
                    "       | 10     |];\n" +
                    "query { γ SUM(amount) → total (τ amount ASC (R)) };")).asNode(PhysicalNode.Aggregate.class).streaming()).isFalse();
        }

        @Test
        @DisplayName("a streaming γ delivers its output ordered by the grouping keys")
        void streamingAggregateDeliversGroupingOrder() {
            PhysicalNode.Aggregate a = assertThat(planFirstQuery(
                    "R := [| region | amount |\n" +
                    "       | 1      | 10     |];\n" +
                    "query { γ region, SUM(amount) → total (τ region ASC (R)) };")).asNode(PhysicalNode.Aggregate.class);
            assertThat(a.streaming()).isTrue();
            assertThat(a.deliveredOrdering().keys()).hasSize(1);
            assertThat(a.deliveredOrdering().keys().get(0).columnName()).contains("region");
        }

        @Test
        @DisplayName("a hash-grouped γ delivers no ordering")
        void hashAggregateDeliversNoOrdering() {
            PhysicalNode.Aggregate a = assertThat(planFirstQuery(
                    "R := [| region | amount |\n" +
                    "       | 1      | 10     |];\n" +
                    "query { γ region, SUM(amount) → total (R) };")).asNode(PhysicalNode.Aggregate.class);
            assertThat(a.streaming()).isFalse();
            assertThat(a.deliveredOrdering().keys()).isEmpty();
        }

        @Test
        @DisplayName("a streaming γ's delivered order lets an outer γ on the same key also stream")
        void streamingAggregateOrderEnablesOuterStreamingAggregate() {
            // The inner γ streams (input sorted by region) and now advertises its order
            // on region; the outer γ groups by region and reuses that order to stream too
            // — which it could not before the inner γ propagated its delivered ordering.
            PhysicalNode.Aggregate outer = assertThat(planFirstQuery(
                    "R := [| region | amount |\n" +
                    "       | 1      | 10     |];\n" +
                    "query { γ region, SUM(total) → grand "
                            + "(γ region, SUM(amount) → total (τ region ASC (R))) };")).asNode(PhysicalNode.Aggregate.class);
            assertThat(outer.streaming()).isTrue();
        }
    }

    // ── views, unary, set ops ─────────────────────────────────────────────────

    @Test
    @DisplayName("a referenced view is inlined — no view node in the plan")
    void viewIsInlined() {
        PhysicalNode plan = planFirstQuery(
                "Users  := [| id | age |\n" +
                "            | 1  | 30  |];\n" +
                "Adults := { σ age >= 18 (Users) };\n" +
                "query { Adults };");

        // The view body (σ over Users) is planned directly: Select over a Scan.
        assertThat(plan).isNode(PhysicalNode.Select.class).input().isScanOf("Users");
    }

    @Test
    @DisplayName("projection bakes its output schema into the physical node")
    void projectionCarriesSchema() {
        PhysicalNode plan = planFirstQuery(
                "Users := [| id | name  |\n" +
                "           | 1  | Alice |];\n" +
                "query { π name (Users) };");

        assertThat(plan).isNode(PhysicalNode.Project.class);
        assertThat(plan.schema()).hasColumnNames("name");
        assertThat(plan.children().get(0)).isNode(PhysicalNode.Scan.class);
    }

    @Test
    @DisplayName("equi-join on a shared column name resolves keys to opposite sides via the qualifier")
    void sharedColumnNameKeysResolveBySide() {
        // Both Users and Orders expose 'id'. The qualifier must route Users.id to the
        // left and Orders.id to the right, yielding a real two-sided hash key.
        PhysicalNode.Join j = assertThat(planFirstQuery(
                "Users  := [| id | name  |\n" +
                "            | 1  | Alice |];\n" +
                "Orders := [| id | amt |\n" +
                "            | 1  | 50  |];\n" +
                "query { Users ⨝ Users.id = Orders.id Orders };")).asNode(PhysicalNode.Join.class);

        assertThat(j.algorithm()).isEqualTo(JoinAlgorithm.HASH);
        assertThat(j.keys().left()).containsExactly(0);    // Users.id (left position 0)
        assertThat(j.keys().right()).containsExactly(0);   // Orders.id (right position 0)
    }

    @Test
    @DisplayName("union → a SetOp of kind UNION")
    void unionIsSetOp() {
        PhysicalNode plan = planFirstQuery(
                "A := [| id |\n" +
                "       | 1  |];\n" +
                "B := [| id |\n" +
                "       | 2  |];\n" +
                "query { A ∪ B };");

        assertThat(plan).isNode(PhysicalNode.SetOp.class);
        assertThat(((PhysicalNode.SetOp) plan).kind()).isEqualTo(SetKind.UNION);
    }

    @Test
    @DisplayName("outer-union → a SetOp of kind OUTER_UNION with the merged schema")
    void outerUnionIsSetOp() {
        PhysicalNode plan = planFirstQuery(
                "A := [| id | name  |\n" +
                "       | 1  | Alice |];\n" +
                "B := [| id | city   |\n" +
                "       | 2  | Berlin |];\n" +
                "query { A ⊔ B };");

        PhysicalNode.SetOp setOp = assertThat(plan).asNode(PhysicalNode.SetOp.class);
        assertThat(setOp.kind()).isEqualTo(SetKind.OUTER_UNION);
        assertThat(setOp.schema()).hasColumnNames("id", "name", "city");
    }

    // ── Transitive closure ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Transitive closure")
    class Closure {

        private static final String EDGES =
                "Edges := [| src | dst |\n       | 1 | 2 |];\n";

        @Test
        @DisplayName("CLOSURE translates to a PhysicalNode.Closure over its planned input")
        void translatesToPhysicalClosure() {
            PhysicalNode plan = planFirstQuery(EDGES + "query { CLOSURE src, dst (Edges) };");
            PhysicalNode.Closure c = assertThat(plan).asNode(PhysicalNode.Closure.class);
            assertThat(c.fromColumn()).isEqualTo("src");
            assertThat(c.toColumn()).isEqualTo("dst");
            assertThat(c.reflexive()).isFalse();
            assertThat(c.input()).isNode(PhysicalNode.Scan.class);
        }

        @Test
        @DisplayName("RCLOSURE carries the reflexive flag")
        void reflexiveFlag() {
            PhysicalNode plan = planFirstQuery(EDGES + "query { RCLOSURE src, dst (Edges) };");
            assertThat(((PhysicalNode.Closure) plan).reflexive()).isTrue();
        }
    }

    // ── Connected components (CLUSTER) ───────────────────────────────────────

    @Nested
    @DisplayName("Cluster (connected components)")
    class Cluster {

        private static final String EDGES =
                "Edges := [| src | dst |\n       | 1 | 2 |];\n";

        @Test
        @DisplayName("CLUSTER translates to a PhysicalNode.Cluster over its planned input")
        void translatesToPhysicalCluster() {
            PhysicalNode plan = planFirstQuery(EDGES + "query { CLUSTER src, dst AS cid (Edges) };");
            PhysicalNode.Cluster c = assertThat(plan).asNode(PhysicalNode.Cluster.class);
            assertThat(c.fromColumn()).isEqualTo("src");
            assertThat(c.toColumn()).isEqualTo("dst");
            assertThat(c.labelColumn()).isEqualTo("cid");
            assertThat(c.input()).isNode(PhysicalNode.Scan.class);
        }

        @Test
        @DisplayName("output schema is the node column plus the NUMBER label column")
        void outputSchema() {
            PhysicalNode plan = planFirstQuery(EDGES + "query { CLUSTER src, dst AS cid (Edges) };");
            assertThat(plan.schema().columns()).hasSize(2);
            assertThat(plan.schema().column("src")).isPresent();
            assertThat(plan.schema().column("cid")).isPresent();
        }
    }

    // ── Bounded path (PATH) ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Path (bounded variable-length reachability)")
    class Path {

        private static final String EDGES =
                "Edges := [| src | dst |\n       | 1 | 2 |];\n";

        @Test
        @DisplayName("PATH translates to a PhysicalNode.Path over its planned input")
        void translatesToPhysicalPath() {
            PhysicalNode plan = planFirstQuery(EDGES + "query { PATH src, dst HOPS 1 TO 3 AS depth (Edges) };");
            PhysicalNode.Path p = assertThat(plan).asNode(PhysicalNode.Path.class);
            assertThat(p.fromColumn()).isEqualTo("src");
            assertThat(p.toColumn()).isEqualTo("dst");
            assertThat(p.minHops()).isEqualTo(1);
            assertThat(p.maxHops()).isEqualTo(3);
            assertThat(p.depthColumn()).isEqualTo("depth");
            assertThat(p.input()).isNode(PhysicalNode.Scan.class);
        }

        @Test
        @DisplayName("output schema is both endpoint columns plus the NUMBER depth column")
        void outputSchema() {
            PhysicalNode plan = planFirstQuery(EDGES + "query { PATH src, dst HOPS 1 TO 3 AS depth (Edges) };");
            assertThat(plan.schema().columns()).hasSize(3);
            assertThat(plan.schema().column("src")).isPresent();
            assertThat(plan.schema().column("dst")).isPresent();
            assertThat(plan.schema().column("depth")).isPresent();
        }
    }

    @Nested
    @DisplayName("Window (ROLLING / WINDOW RANK / WINDOW LAG)")
    class Window {

        /** Inline (non-connection) source — pushdown never applies. */
        private static final String TICKS_INLINE =
                "Ticks := [| ticker | t | price |\n       | A | 1 | 10 |];\n";

        /** JDBC-backed source that enables pushdown. */
        private static final String TICKS_JDBC =
                "connection db from database { url: \"jdbc:h2:mem:x\" };\n" +
                "source Ticks from db { table: \"ticks\", " +
                "schema: { ticker: STRING, t: NUMBER, price: NUMBER } };\n";

        /** Plans the first query with pushdown enabled (sources + connections threaded in). */
        private static PhysicalNode planPushed(String src) {
            SemanticModel m = model(src);
            RelNode logical = ((ExpressionQueryTarget) m.rootQueries().get(0).target()).expression();
            return new Planner(m.symbolTable(), m.nodeSchemas(),
                    m.statistics(), m.sources(), m.connections(), QueryEventListener.NONE,
                    BoundednessSource.ALL_BOUNDED, m.functions()).plan(logical);
        }

        @Test
        @DisplayName("ROLLING over an inline source translates to a PhysicalNode.Window (in-engine)")
        void translatesToPhysicalWindow() {
            PhysicalNode plan = planFirstQuery(TICKS_INLINE
                    + "query { ROLLING SUM(price) OVER 3 ROWS SORT t ASC PER ticker AS run3 (Ticks) };");
            PhysicalNode.Window w = assertThat(plan).asNode(PhysicalNode.Window.class);
            assertThat(w.outputColumn()).isEqualTo("run3");
            assertThat(w.partitionKeys()).containsExactly("ticker");
            assertThat(w.frame()).isEqualTo(new com.darkcollective.relix.ast.WindowFrame.BoundedFrame(3));
            assertThat(w.input()).isNode(PhysicalNode.Scan.class);
        }

        @Test
        @DisplayName("output schema includes the appended window column (inline source)")
        void outputSchema() {
            PhysicalNode plan = planFirstQuery(TICKS_INLINE
                    + "query { ROLLING SUM(price) OVER ALL ROWS SORT t ASC PER ticker AS run (Ticks) };");
            assertThat(plan.schema().columns()).hasSize(4); // ticker, t, price, run
            assertThat(plan.schema().column("run")).isPresent();
        }

        @Test
        @DisplayName("an inline-source window is not pushed down (no connection → in-engine)")
        void inlineSourceNotPushedDown() {
            PhysicalNode plan = planFirstQuery(TICKS_INLINE
                    + "query { ROLLING SUM(price) OVER 2 ROWS SORT t ASC AS s (Ticks) };");
            assertThat(plan).isNode(PhysicalNode.Window.class);
        }

        @Test
        @DisplayName("a JDBC-backed ROLLING folds into a PushedScan containing OVER (…)")
        void jdbcRollingPushedDown() {
            PhysicalNode plan = planPushed(TICKS_JDBC
                    + "query { ROLLING SUM(price) OVER 3 ROWS SORT t ASC PER ticker AS run3 (Ticks) };");
            PhysicalNode.PushedScan scan = assertThat(plan).asNode(PhysicalNode.PushedScan.class);
            assertThat(scan.nativeQuery()).contains("OVER (").contains("PARTITION BY").contains("run3");
        }

        @Test
        @DisplayName("a JDBC-backed WINDOW RANK folds into a PushedScan")
        void jdbcRankPushedDown() {
            String src =
                    "connection db from database { url: \"jdbc:h2:mem:x\" };\n" +
                    "source Employees from db { table: \"employees\", " +
                    "schema: { dept: STRING, emp_id: NUMBER, salary: NUMBER } };\n" +
                    "query { WINDOW ROW_NUMBER() SORT salary DESC PER dept AS rn (Employees) };\n";
            PhysicalNode plan = planPushed(src);
            assertThat(plan).isNode(PhysicalNode.PushedScan.class);
            assertThat(((PhysicalNode.PushedScan) plan).nativeQuery())
                    .contains("ROW_NUMBER() OVER (").contains("AS rn");
        }

        /**
         * NTILE is the one ranking function carrying an argument, so it is the only one
         * whose bucket count has to be rendered as an expression rather than emitted as a
         * bare name — the arm the other ranking functions never reach.
         */
        @Test
        @DisplayName("a JDBC-backed WINDOW NTILE folds its bucket count into the pushed query")
        void jdbcNtilePushedDown() {
            String src =
                    "connection db from database { url: \"jdbc:h2:mem:x\" };\n" +
                    "source Employees from db { table: \"employees\", " +
                    "schema: { dept: STRING, emp_id: NUMBER, salary: NUMBER } };\n" +
                    "query { WINDOW NTILE(4) SORT salary DESC PER dept AS quartile (Employees) };\n";
            PhysicalNode plan = planPushed(src);
            assertThat(plan).isNode(PhysicalNode.PushedScan.class);
            assertThat(((PhysicalNode.PushedScan) plan).nativeQuery())
                    .contains("NTILE(4) OVER (").contains("AS quartile");
        }
    }

    // ── Sessionization (SESSIONIZE) ───────────────────────────────────────────

    @Nested
    @DisplayName("Sessionize (SESSIONIZE)")
    class Sessionize {

        private static final String READINGS_INLINE =
                "Readings := [| seq | u |\n       | 1 | a |];\n";

        @Test
        @DisplayName("SESSIONIZE translates to a PhysicalNode.Sessionize (in-engine)")
        void translatesToPhysicalSessionize() {
            PhysicalNode plan = planFirstQuery(READINGS_INLINE
                    + "query { SESSIONIZE seq GAP 2 PER u AS session (Readings) };");
            PhysicalNode.Sessionize s = assertThat(plan).asNode(PhysicalNode.Sessionize.class);
            assertThat(s.orderColumn()).isEqualTo("seq");
            assertThat(s.sessionColumn()).isEqualTo("session");
            assertThat(s.partitionKeys()).containsExactly("u");
            assertThat(s.input()).isNode(PhysicalNode.Scan.class);
        }

        @Test
        @DisplayName("output schema appends the NUMBER session column")
        void outputSchema() {
            PhysicalNode plan = planFirstQuery(READINGS_INLINE
                    + "query { SESSIONIZE seq GAP 2 AS session (Readings) };");
            assertThat(plan.schema().columns()).hasSize(3); // seq, u, session
            assertThat(plan.schema().column("session")).isPresent();
        }

        @Test
        @DisplayName("a JDBC-backed SESSIONIZE is not pushed down (in-engine)")
        void notPushedDown() {
            String src =
                    "connection db from database { url: \"jdbc:h2:mem:x\" };\n" +
                    "source Hits from db { table: \"hits\", " +
                    "schema: { seq: NUMBER, u: STRING } };\n" +
                    "query { SESSIONIZE seq GAP 2 PER u AS session (Hits) };\n";
            SemanticModel m = model(src);
            RelNode logical = ((ExpressionQueryTarget) m.rootQueries().get(0).target()).expression();
            PhysicalNode plan = new Planner(m.symbolTable(), m.nodeSchemas(),
                    m.statistics(), m.sources(), m.connections(), QueryEventListener.NONE,
                    BoundednessSource.ALL_BOUNDED, m.functions()).plan(logical);
            assertThat(plan).isNode(PhysicalNode.Sessionize.class);
        }
    }

    // ── Symmetric difference & composition (desugared) ────────────────────────

    @Nested
    @DisplayName("Symmetric difference ∆ — desugars to (A−B) ∪ (B−A)")
    class SymmetricDifference {

        private static final String AB =
                "A := [| id |\n       | 1  |];\n" +
                "B := [| id |\n       | 2  |];\n";

        /**
         * The ∆ whose inputs are worth sharing. A bare reference to an inline relation
         * is <em>not</em> — re-reading rows already in memory repeats no work, so the
         * planner declines it (see {@code SharingGates}) — and these tests are about the
         * sharing, not about which leaves qualify for it.
         */
        private static final String WORTH_SHARING =
                AB + "query { (σ id > 0 (A)) ∆ (σ id > 0 (B)) };";

        @Test
        @DisplayName("Top node is a UNION of two DIFFERENCE set ops")
        void desugarsToUnionOfDifferences() {
            PhysicalNode plan = planFirstQuery(AB + "query { A ∆ B };");

            PhysicalNode.SetOp union = assertThat(plan).asNode(PhysicalNode.SetOp.class);
            assertThat(union.kind()).isEqualTo(SetKind.UNION);

            assertThat(union.left()).isNode(PhysicalNode.SetOp.class);
            assertThat(union.right()).isNode(PhysicalNode.SetOp.class);
            assertThat(((PhysicalNode.SetOp) union.left()).kind()).isEqualTo(SetKind.DIFFERENCE);
            assertThat(((PhysicalNode.SetOp) union.right()).kind()).isEqualTo(SetKind.DIFFERENCE);
        }

        @Test
        @DisplayName("The two differences use opposite operand orders")
        void differencesAreOppositeOrders() {
            PhysicalNode plan = planFirstQuery(WORTH_SHARING);
            PhysicalNode.SetOp union = (PhysicalNode.SetOp) plan;
            PhysicalNode.SetOp leftMinusRight = (PhysicalNode.SetOp) union.left();
            PhysicalNode.SetOp rightMinusLeft = (PhysicalNode.SetOp) union.right();

            // A−B and B−A: the build/probe inputs are swapped. Each input is spooled,
            // so the scan under each is reached through its spool.
            String leftFirst  = scanUnderSpool(leftMinusRight.left()).source().canonicalName();
            String rightFirst = scanUnderSpool(rightMinusLeft.left()).source().canonicalName();
            assertThat(leftFirst).isNotEqualTo(rightFirst);
        }

        @Test
        @DisplayName("Each input is planned once and shared by both differences")
        void inputsAreSpooledNotPlannedTwice() {
            PhysicalNode plan = planFirstQuery(WORTH_SHARING);
            PhysicalNode.SetOp union = (PhysicalNode.SetOp) plan;
            PhysicalNode.SetOp leftMinusRight = (PhysicalNode.SetOp) union.left();
            PhysicalNode.SetOp rightMinusLeft = (PhysicalNode.SetOp) union.right();

            // (A − B) ∪ (B − A): A is the left of one difference and the right of the
            // other, and it must be the *same* spool in both places — that is the whole
            // point, since a plan is otherwise a tree and would evaluate A twice.
            assertThat(leftMinusRight.left()).isNode(PhysicalNode.Spool.class);
            assertThat(rightMinusLeft.right()).isNode(PhysicalNode.Spool.class);
            assertThat(leftMinusRight.left()).isEqualTo(rightMinusLeft.right());
            assertThat(leftMinusRight.right()).isEqualTo(rightMinusLeft.left());

            int idA = ((PhysicalNode.Spool) leftMinusRight.left()).id();
            int idB = ((PhysicalNode.Spool) leftMinusRight.right()).id();
            assertThat(idA).isNotEqualTo(idB);
        }

        @Test
        @DisplayName("A spool reports its input's schema and delivered ordering")
        void spoolIsTransparent() {
            PhysicalNode plan = planFirstQuery(WORTH_SHARING);
            PhysicalNode.SetOp union = (PhysicalNode.SetOp) plan;
            PhysicalNode.Spool spool =
                    (PhysicalNode.Spool) ((PhysicalNode.SetOp) union.left()).left();

            assertThat(spool.schema()).isEqualTo(spool.input().schema());
            assertThat(spool.deliveredOrdering()).isEqualTo(spool.input().deliveredOrdering());
            assertThat(spool.children()).containsExactly(spool.input());
        }

        /** Unwraps the spool and selection the planner puts over each ∆ input. */
        private static PhysicalNode.Scan scanUnderSpool(PhysicalNode node) {
            PhysicalNode inner = node;
            while (!(inner instanceof PhysicalNode.Scan)) {
                assertThat(inner.children()).hasSize(1);
                inner = inner.children().getFirst();
            }
            return (PhysicalNode.Scan) inner;
        }
    }

    @Nested
    @DisplayName("Sharing — sub-expressions the query reads in more than one place")
    class SharedSubexpressionDetection {

        private static final String R =
                "R := [| id | amount |\n       | 1  | 10     |\n       | 2  | 20     |];\n";

        /** Every spool in the plan, in traversal order, each id appearing once. */
        private static List<PhysicalNode.Spool> spools(PhysicalNode node) {
            List<PhysicalNode.Spool> found = new java.util.ArrayList<>();
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            collect(node, found, seen);
            return found;
        }

        private static void collect(PhysicalNode node, List<PhysicalNode.Spool> found,
                                    java.util.Set<Integer> seen) {
            if (node instanceof PhysicalNode.Spool s) {
                if (!seen.add(s.id())) {
                    return;
                }
                found.add(s);
            }
            node.children().forEach(child -> collect(child, found, seen));
        }

        @Test
        @DisplayName("a sub-expression two branches read is planned once and shared")
        void repeatedSubExpressionIsShared() {
            PhysicalNode plan = planFirstQuery(
                    R + "query { (σ amount > 5 (R)) ∪ (σ amount > 5 (R)) };");
            PhysicalNode.SetOp union = (PhysicalNode.SetOp) plan;

            assertThat(union.left()).isNode(PhysicalNode.Spool.class);
            assertThat(union.left())
                    .as("both branches must read the same spool, not two equal ones")
                    .isSameAs(union.right());
        }

        @Test
        @DisplayName("only the outermost repeat is spooled — the inner one is reached through it")
        void nestedRepeatIsNotSpooledTwice() {
            // δ(σ(R)) ∪ δ(σ(R)): the σ appears twice, but sharing the δ already reduces
            // it to one evaluation. A second spool would hold the same rows for a single
            // reader.
            PhysicalNode plan = planFirstQuery(
                    R + "query { (δ (σ amount > 5 (R))) ∪ (δ (σ amount > 5 (R))) };");

            assertThat(spools(plan)).hasSize(1);
            assertThat(spools(plan).getFirst().input())
                    .isNode(PhysicalNode.Distinct.class);
        }

        @Test
        @DisplayName("an inner repeat with a reader of its own is shared as well")
        void innerRepeatWithItsOwnReaderIsAlsoShared() {
            // (δ(σ) ∪ δ(σ)) ∪ σ — now the σ has a reader outside both δs, so both are
            // shared and the δ's spool reads the σ's. That is what a plan DAG is.
            PhysicalNode plan = planFirstQuery(
                    R + "query { ((δ (σ amount > 5 (R))) ∪ (δ (σ amount > 5 (R)))) "
                      + "∪ (σ amount > 5 (R)) };");

            assertThat(spools(plan)).hasSize(2);
        }

        @Test
        @DisplayName("the event reports how many places read the sub-expression")
        void eventReportsSiteCount() {
            List<QueryEvent> events = new java.util.ArrayList<>();
            SemanticModel model = model(
                    R + "query { ((σ amount > 5 (R)) ∪ (σ amount > 5 (R))) "
                      + "∪ (σ amount > 5 (R)) };");
            RelNode logical =
                    ((ExpressionQueryTarget) model.rootQueries().get(0).target()).expression();
            new Planner(model.symbolTable(), model.nodeSchemas(), model.statistics(),
                    model.sources(), model.connections(), events::add).plan(logical);

            assertThat(events).filteredOn(e -> e.code().equals("SPOOL"))
                    .singleElement()
                    .satisfies(e -> assertThat(e.description()).contains("(3 sites)"));
        }

        @Test
        @DisplayName("the event says so when the sub-expression recurs rather than repeats")
        void eventReportsAPerRoundShare() {
            List<QueryEvent> events = new java.util.ArrayList<>();
            SemanticModel model = model(
                    R + "query { FIX Rec ((σ amount > 5 (R)), Rec ∪ (σ amount > 9 (R))) };");
            RelNode logical =
                    ((ExpressionQueryTarget) model.rootQueries().get(0).target()).expression();
            new Planner(model.symbolTable(), model.nodeSchemas(), model.statistics(),
                    model.sources(), model.connections(), events::add).plan(logical);

            assertThat(events).filteredOn(e -> e.code().equals("SPOOL"))
                    .singleElement()
                    .satisfies(e -> assertThat(e.description())
                            .as("a count would be a claim about the text; the round count "
                                + "is not known until it runs")
                            .contains("(once per fixpoint round)"));
        }

        @Test
        @DisplayName("a sub-expression read once is left alone")
        void singleUseIsNotSpooled() {
            PhysicalNode plan = planFirstQuery(R + "query { σ amount > 5 (R) };");

            assertThat(spools(plan)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Sharing — when the planner declines to spool")
    class SharingGates {

        private static final String AB =
                "A := [| id |\n       | 1  |];\n" +
                "B := [| id |\n       | 2  |];\n";

        @Test
        @DisplayName("an input that reads system state is not shared — its two evaluations may differ")
        void nonDeterministicInputIsNotShared() {
            PhysicalNode plan = planFirstQuery(AB + "query { (SAMPLE 0.5 (A)) ∆ (σ id > 0 (B)) };");
            PhysicalNode.SetOp union = (PhysicalNode.SetOp) plan;
            PhysicalNode.SetOp leftMinusRight = (PhysicalNode.SetOp) union.left();

            assertThat(leftMinusRight.left())
                    .as("an unseeded sample draws afresh each time; replaying one draw "
                        + "would answer a different question")
                    .isNotInstanceOf(PhysicalNode.Spool.class);
            assertThat(leftMinusRight.right())
                    .as("the deterministic side is still shared")
                    .isNode(PhysicalNode.Spool.class);
        }

        @Test
        @DisplayName("a seeded sample is reproducible, so it is shared")
        void seededSampleIsShared() {
            PhysicalNode plan = planFirstQuery(AB + "query { (SAMPLE 0.5 SEED 42 (A)) ∆ (σ id > 0 (B)) };");
            PhysicalNode.SetOp leftMinusRight = (PhysicalNode.SetOp) ((PhysicalNode.SetOp) plan).left();

            assertThat(leftMinusRight.left()).isNode(PhysicalNode.Spool.class);
        }

        @Test
        @DisplayName("a ∆ input reading a recursive relation is not shared; the invariant one is")
        void recursiveInputIsNotShared() {
            PhysicalNode plan = planFirstQuery(AB + "query { FIX R ((σ id > 0 (A)), (σ id > 0 (A)) ∆ R) };");
            PhysicalNode.Fixpoint fix = (PhysicalNode.Fixpoint) plan;
            PhysicalNode.SetOp union = (PhysicalNode.SetOp) fix.step();
            PhysicalNode.SetOp aMinusR = (PhysicalNode.SetOp) union.left();

            assertThat(aMinusR.right())
                    .as("R denotes the current iteration's rows; a buffer filled on the "
                        + "first iteration would still be answering for it on the tenth")
                    .isNotInstanceOf(PhysicalNode.Spool.class);
            assertThat(aMinusR.left())
                    .as("A does not change with the iteration, so one evaluation serves "
                        + "all of them — the store outlives a single round deliberately")
                    .isNode(PhysicalNode.Spool.class);
        }

        @Test
        @DisplayName("an invariant sub-expression written once inside a step is spooled")
        void invariantStepInputIsSharedThoughWrittenOnce() {
            // Semi-naïve evaluation re-runs the step on every round, so the σ over B is
            // evaluated once per iteration although it is written once. Nothing about it
            // changes between rounds, so one evaluation serves all of them.
            PhysicalNode plan = planFirstQuery(
                    AB + "query { FIX Rec ((σ id > 0 (A)), Rec ∪ (σ id > 1 (B))) };");
            PhysicalNode.Fixpoint fix = (PhysicalNode.Fixpoint) plan;
            PhysicalNode.SetOp union = (PhysicalNode.SetOp) fix.step();

            assertThat(union.right()).isNode(PhysicalNode.Spool.class);
            assertThat(countSpools(plan)).isEqualTo(1);
        }

        @Test
        @DisplayName("the base is not spooled for the same reason — it runs once")
        void theBaseIsNotSharedPerRound() {
            PhysicalNode plan = planFirstQuery(
                    AB + "query { FIX Rec ((σ id > 0 (A)), Rec ∪ B) };");

            assertThat(countSpools(plan))
                    .as("the seed is evaluated once however many rounds follow it")
                    .isZero();
        }

        @Test
        @DisplayName("a self-contained FIX under a ∆ is shared — its references are its own")
        void selfContainedFixpointIsShared() {
            PhysicalNode plan = planFirstQuery(AB + "query { (FIX R (A, A ∪ R)) ∆ (σ id > 0 (B)) };");
            PhysicalNode.SetOp leftMinusRight = (PhysicalNode.SetOp) ((PhysicalNode.SetOp) plan).left();

            assertThat(leftMinusRight.left()).isNode(PhysicalNode.Spool.class);
        }

        @Test
        @DisplayName("a ∆ inside a LATERAL body is not shared — that body is planned per outer row")
        void lateralBodyIsNotShared() {
            PhysicalNode plan = planFirstQuery(
                    AB
                    + "def pick(n: NUMBER): RELATION := { (σ id = n (A)) ∆ (σ id > 0 (B)) };\n"
                    + "query { A LATERAL pick(id) };");

            PhysicalNode.LateralJoin lateral = (PhysicalNode.LateralJoin) plan;
            PhysicalNode body = lateral.bodyBuilder()
                    .apply(List.of(new com.darkcollective.relix.ast.NumberOperand("1")));

            assertThat(countSpools(body))
                    .as("a spool minted per outer row would allocate an id per row and "
                        + "buffer rows for every one of them")
                    .isZero();
        }

        @Test
        @DisplayName("a bare inline reference is not shared — re-reading it repeats no work")
        void freeToReEvaluateIsNotShared() {
            PhysicalNode plan = planFirstQuery(AB + "query { A ∆ B };");

            assertThat(countSpools(plan))
                    .as("the rows are already in memory; holding a second copy of them "
                        + "costs memory and saves nothing")
                    .isZero();
        }

        @Test
        @DisplayName("a view reference is shared — evaluating it means evaluating its body")
        void viewReferenceIsShared() {
            // A view is a leaf in the tree but not a free read: its body is the work.
            PhysicalNode plan = planFirstQuery(
                    AB
                    + "V := { γ id, COUNT(*) → n (A) };\n"
                    + "query { V ∪ V };");

            assertThat(countSpools(plan)).isEqualTo(1);
        }

        /** How many {@link PhysicalNode.Spool}s the plan contains, counting each id once. */
        private static int countSpools(PhysicalNode node) {
            java.util.Set<Integer> ids = new java.util.HashSet<>();
            collectSpools(node, ids);
            return ids.size();
        }

        private static void collectSpools(PhysicalNode node, java.util.Set<Integer> ids) {
            if (node instanceof PhysicalNode.Spool s && !ids.add(s.id())) {
                return;
            }
            node.children().forEach(child -> collectSpools(child, ids));
        }
    }

    @Nested
    @DisplayName("Composition ∘ — desugars to π (R ⋈ S)")
    class Composition {

        private static final String RS =
                "R := [| a | b |\n       | 1 | 2 |];\n" +
                "S := [| b | c |\n       | 2 | 3 |];\n";

        @Test
        @DisplayName("Top node is a Project over a NATURAL join")
        void desugarsToProjectOverJoin() {
            PhysicalNode plan = planFirstQuery(RS + "query { R ∘ S };");

            PhysicalNode.Project project = assertThat(plan).asNode(PhysicalNode.Project.class);
            assertThat(project.input()).isNode(PhysicalNode.Join.class);
            assertThat(((PhysicalNode.Join) project.input()).kind()).isEqualTo(JoinKind.NATURAL);
        }

        @Test
        @DisplayName("The projection drops the shared column, keeping a and c")
        void projectionDropsSharedColumn() {
            PhysicalNode plan = planFirstQuery(RS + "query { R ∘ S };");
            PhysicalNode.Project project = (PhysicalNode.Project) plan;

            List<String> outputCols = project.schema().columns().stream()
                    .map(c -> c.name()).toList();
            assertThat(outputCols).containsExactly("a", "c");
        }
    }

    // ── Universal quantification ──────────────────────────────────────────────

    @Nested
    @DisplayName("Universal quantification (∀)")
    class Universal {

        private static final String ORDERS =
                "Orders := [| customer_id | status |\n" +
                "            | 1 | done |];\n";

        @Test
        @DisplayName("∀ translates to a PhysicalNode.Universal over its planned input")
        void translatesToPhysicalUniversal() {
            PhysicalNode plan = planFirstQuery(
                    ORDERS + "query { ∀ customer_id : status = \"done\" (Orders) };");

            PhysicalNode.Universal u = assertThat(plan).asNode(PhysicalNode.Universal.class);
            assertThat(u.groupingAttributes()).containsExactly("customer_id");
            assertThat(u.input()).isNode(PhysicalNode.Scan.class);
            // Output schema is the grouping key only.
            assertThat(u.schema().columns().stream().map(c -> c.name()).toList())
                    .containsExactly("customer_id");
        }

        @Test
        @DisplayName("no-key ∀ plans with no grouping keys and the empty (truth) schema")
        void noKeyWholeRelation() {
            PhysicalNode plan = planFirstQuery(
                    ORDERS + "query { ∀ : status = \"done\" (Orders) };");

            PhysicalNode.Universal u = assertThat(plan).asNode(PhysicalNode.Universal.class);
            assertThat(u.groupingAttributes()).isEmpty();
            assertThat(u.schema().isEmpty()).isTrue();
        }
    }

    // ── Sampling ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Bernoulli sampling (SAMPLE)")
    class Sampling {

        @Test
        @DisplayName("SAMPLE p translates to a BernoulliSample physical node")
        void translatesToBernoulliSample() {
            PhysicalNode plan = planFirstQuery(
                    "Events := [| id |\n            | 1 |];\n" +
                    "query { SAMPLE 0.25 (Events) };");

            PhysicalNode.BernoulliSample bs = assertThat(plan).asNode(PhysicalNode.BernoulliSample.class);
            assertThat(bs.probability()).isEqualTo(0.25);
            assertThat(bs.seed()).isEmpty();
            assertThat(bs.input()).isNode(PhysicalNode.Scan.class);
        }

        @Test
        @DisplayName("SAMPLE p SEED n carries the seed through to the physical node")
        void seededBernoulliCarriesSeed() {
            PhysicalNode plan = planFirstQuery(
                    "Events := [| id |\n            | 1 |];\n" +
                    "query { SAMPLE 0.5 SEED 42 (Events) };");

            PhysicalNode.BernoulliSample bs = assertThat(plan).asNode(PhysicalNode.BernoulliSample.class);
            assertThat(bs.probability()).isEqualTo(0.5);
            assertThat(bs.seed()).contains(42L);
        }
    }

    // ── Reservoir sampling ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Reservoir sampling (SAMPLE n ROWS)")
    class ReservoirSampling {

        @Test
        @DisplayName("SAMPLE n ROWS translates to a PhysicalNode.ReservoirSample (no desugar)")
        void translatesToPhysicalReservoirSample() {
            PhysicalNode plan = planFirstQuery(
                    "Events := [| id |\n            | 1 |];\n" +
                    "query { SAMPLE 50 ROWS (Events) };");

            PhysicalNode.ReservoirSample r = assertThat(plan).asNode(PhysicalNode.ReservoirSample.class);
            assertThat(r.count()).isEqualTo(50L);
            assertThat(r.seed()).isEmpty();
            assertThat(r.input()).isNode(PhysicalNode.Scan.class);
            // Output schema is the full input schema (a row subset).
            assertThat(r.schema().columns().stream().map(c -> c.name()).toList())
                    .containsExactly("id");
        }

        @Test
        @DisplayName("SAMPLE n ROWS SEED k carries the seed through to the physical node")
        void seededReservoirCarriesSeed() {
            PhysicalNode plan = planFirstQuery(
                    "Events := [| id |\n            | 1 |];\n" +
                    "query { SAMPLE 50 ROWS SEED 2026 (Events) };");

            PhysicalNode.ReservoirSample r = assertThat(plan).asNode(PhysicalNode.ReservoirSample.class);
            assertThat(r.count()).isEqualTo(50L);
            assertThat(r.seed()).contains(2026L);
        }
    }

    // ── Top-k per group ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Top-k per group (TOP)")
    class TopK {

        @Test
        @DisplayName("TOP translates to a PhysicalNode.TopK over its planned input")
        void translatesToPhysicalTopK() {
            PhysicalNode plan = planFirstQuery(
                    "Orders := [| customer_id | amount |\n" +
                    "            | 1 | 10 |];\n" +
                    "query { TOP 3 amount DESC PER customer_id (Orders) };");

            PhysicalNode.TopK t = assertThat(plan).asNode(PhysicalNode.TopK.class);
            assertThat(t.count()).isEqualTo(3L);
            assertThat(t.offset()).isEmpty();
            assertThat(t.groupingAttributes()).containsExactly("customer_id");
            assertThat(t.sortSpecs()).hasSize(1);
            assertThat(t.input()).isNode(PhysicalNode.Scan.class);
            // Output schema is the full input schema (a row subset).
            assertThat(t.schema().columns().stream().map(c -> c.name()).toList())
                    .containsExactly("customer_id", "amount");
        }
    }

    // ── Goal-seek ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Goal-seek (SOLVE)")
    class Solve {

        @Test
        @DisplayName("SOLVE translates to a PhysicalNode.Solve over its planned input")
        void translatesToPhysicalSolve() {
            PhysicalNode plan = planFirstQuery(
                    "Loans := [| total | principal | rate |\n" +
                    "           | 100 | 20 | 5 |];\n" +
                    "query { SOLVE total = principal * rate (Loans) };");

            PhysicalNode.Solve s = assertThat(plan).asNode(PhysicalNode.Solve.class);
            assertThat(s.input()).isNode(PhysicalNode.Scan.class);
            assertThat(s.left()).isInstanceOf(com.darkcollective.relix.ast.AttributeOperand.class);
            assertThat(s.right()).isInstanceOf(
                    com.darkcollective.relix.ast.BinaryArithmeticExpression.class);
            // Output schema equals the input schema (fills holes, adds nothing).
            assertThat(s.schema().columns().stream().map(c -> c.name()).toList())
                    .containsExactly("total", "principal", "rate");
        }
    }

    // ── Declarative optimisation ──────────────────────────────────────────────

    @Nested
    @DisplayName("Declarative optimisation (OPTIMIZE)")
    class Optimisation {

        @Test
        @DisplayName("OPTIMIZE translates to a PhysicalNode.Optimize over its planned input")
        void translatesToPhysicalOptimize() {
            PhysicalNode plan = planFirstQuery(
                    "Items := [| region | value | weight |\n" +
                    "           | a | 60 | 10 |];\n" +
                    "query { OPTIMIZE MAXIMIZE SUM(value) " +
                    "        SUBJECT TO SUM(weight) <= 100 PER region (Items) };");

            PhysicalNode.Optimize o = assertThat(plan).asNode(PhysicalNode.Optimize.class);
            assertThat(o.sense())
                    .isEqualTo(com.darkcollective.relix.ast.ObjectiveSense.MAXIMIZE);
            assertThat(o.constraints()).hasSize(1);
            assertThat(o.groupingKeys()).containsExactly("region");
            assertThat(o.input()).isNode(PhysicalNode.Scan.class);
            // Output schema equals the input schema (a subset of rows).
            assertThat(o.schema().columns().stream().map(c -> c.name()).toList())
                    .containsExactly("region", "value", "weight");
        }
    }

    // ── General fixpoint (FIX) ───────────────────────────────────────────────

    @Nested
    @DisplayName("General fixpoint (FIX)")
    class Fixpoint {

        private static final String EDGES =
                "Edges := [| src | dst |\n       | 1 | 2 |];\n";

        @Test
        @DisplayName("FixpointNode plans to PhysicalNode.Fixpoint with base and step")
        void fixpointPlansToPhysicalFixpoint() {
            PhysicalNode plan = planFirstQuery(
                    EDGES + "query { FIX R (Edges, Edges ∪ R) };");
            PhysicalNode.Fixpoint fix = assertThat(plan).asNode(PhysicalNode.Fixpoint.class);
            assertThat(fix.name()).isEqualTo("R");
            assertThat(fix.base()).isNode(PhysicalNode.Scan.class);
        }

        @Test
        @DisplayName("RecursiveRefNode in the step plans to PhysicalNode.RecursiveRef")
        void recursiveRefPlansToPhysicalRef() {
            PhysicalNode plan = planFirstQuery(
                    EDGES + "query { FIX R (Edges, Edges ∪ R) };");
            PhysicalNode.Fixpoint fix = (PhysicalNode.Fixpoint) plan;
            // step is a SetOp(Scan, RecursiveRef)
            PhysicalNode.SetOp union = assertThat(fix.step()).asNode(PhysicalNode.SetOp.class);
            assertThat(union.right()).isNode(PhysicalNode.RecursiveRef.class);
            assertThat(((PhysicalNode.RecursiveRef) union.right()).name()).isEqualTo("R");
        }

        @Test
        @DisplayName("FIX output schema matches the base schema")
        void fixpointSchemaMatchesBase() {
            PhysicalNode plan = planFirstQuery(
                    EDGES + "query { FIX R (Edges, Edges ∪ R) };");
            assertThat(plan.schema().columns().stream().map(c -> c.name()).toList())
                    .containsExactly("src", "dst");
        }
    }

    // ── SQL pushdown ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SQL pushdown")
    class Pushdown {

        /** A connection + connection-table source with a declared schema (no catalog needed). */
        private static final String ORDERS_SETUP =
                "connection db from database { url: \"jdbc:h2:mem:x\" };\n" +
                "source Orders from db { table: \"orders\", schema: { id: NUMBER, amount: NUMBER } };\n";

        /**
         * Plans the first query with pushdown enabled from the model's
         * sources/connections, and the model's own function catalogue — which is where a
         * function's backend spelling comes from, so a planner without one folds no
         * calls into the pushed query (ADR-0026 S6).
         */
        private static PhysicalNode planPushed(String src) {
            SemanticModel model = model(src);
            RelNode logical =
                    ((ExpressionQueryTarget) model.rootQueries().get(0).target()).expression();
            return new Planner(model.symbolTable(), model.nodeSchemas(),
                    model.statistics(), model.sources(), model.connections(),
                    QueryEventListener.NONE, BoundednessSource.ALL_BOUNDED, model.functions())
                    .plan(logical);
        }

        @Test
        @DisplayName("a bare connection table → PushedScan selecting its columns")
        void bareTable() {
            assertThat(planPushed(ORDERS_SETUP + "query { Orders };"))
                    .isPushedTo("db", "SELECT id, amount FROM orders");
        }

        @Test
        @DisplayName("selection folds into WHERE")
        void selectionToWhere() {
            PhysicalNode.PushedScan s = assertThat(planPushed(
                    ORDERS_SETUP + "query { σ amount > 0 (Orders) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery()).isEqualTo("SELECT id, amount FROM orders WHERE (amount > 0)");
        }

        @Test
        @DisplayName("projection folds into the select list")
        void projectionToSelectList() {
            PhysicalNode.PushedScan s = assertThat(planPushed(
                    ORDERS_SETUP + "query { π amount (Orders) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery()).isEqualTo("SELECT amount FROM orders");
        }

        /** A connection table with temporal columns (ADR-0013). */
        private static final String TRADES_SETUP =
                "connection db from database { url: \"jdbc:h2:mem:x\" };\n" +
                "source Trades from db { table: \"trades\",\n" +
                "    schema: { id: NUMBER, at: TIMESTAMP, day: DATE, held: DURATION } };\n";

        @Test
        @DisplayName("a temporal comparison folds into WHERE (TIMESTAMP literal rendered)")
        void temporalSelectionToWhere() {
            PhysicalNode.PushedScan s = assertThat(planPushed(
                    TRADES_SETUP + "query { σ at >= TIMESTAMP '2026-06-15T13:40:00Z' (Trades) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery())
                    .isEqualTo("SELECT id, at, day, held FROM trades "
                            + "WHERE (at >= '2026-06-15 13:40:00')");
        }

        @Test
        @DisplayName("a DATE comparison folds too")
        void dateSelectionToWhere() {
            PhysicalNode.PushedScan s = assertThat(planPushed(
                    TRADES_SETUP + "query { σ day = DATE '2026-06-15' (Trades) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery()).contains("WHERE (day = '2026-06-15')");
        }

        @Test
        @DisplayName("a DURATION comparison is not pushable — it stays in-engine")
        void durationFilterFallsBack() {
            // The DURATION literal has no SQL form, so the σ does not fold into the scan.
            PhysicalNode node = planPushed(
                    TRADES_SETUP + "query { σ held > DURATION 'PT30M' (Trades) };");
            assertThat(node).isNotInstanceOf(PhysicalNode.PushedScan.class);
        }

        @Test
        @DisplayName("YEAR(at) in a projection folds as EXTRACT(YEAR FROM at) — GENERIC dialect")
        void temporalFunctionInProjection() {
            PhysicalNode.PushedScan s = assertThat(planPushed(
                    TRADES_SETUP + "query { π YEAR(at) → yr (Trades) };")).asNode(PhysicalNode.PushedScan.class);
            // The alias is tracked in the schema; the SQL select-list emits the expression only
            assertThat(s.nativeQuery()).isEqualTo("SELECT EXTRACT(YEAR FROM at) FROM trades");
        }

        @Test
        @DisplayName("YEAR(at) in a WHERE predicate folds into the SQL WHERE clause")
        void temporalFunctionInWhere() {
            PhysicalNode.PushedScan s = assertThat(planPushed(
                    TRADES_SETUP + "query { σ YEAR(at) > 2024 (Trades) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery())
                    .isEqualTo("SELECT id, at, day, held FROM trades WHERE (EXTRACT(YEAR FROM at) > 2024)");
        }

        @Test
        @DisplayName("DATE_TRUNC folds into the Postgres dialect form; identifiers are quoted")
        void dateTruncPostgresDialect() {
            String setup =
                    "connection db from database { url: \"jdbc:h2:mem:x\", dialect: postgres };\n" +
                    "source Trades from db { table: \"trades\",\n" +
                    "    schema: { id: NUMBER, at: TIMESTAMP, day: DATE, held: DURATION } };\n";
            PhysicalNode.PushedScan s = assertThat(planPushed(
                    setup + "query { π DATE_TRUNC('hour', at) → hr (Trades) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery())
                    .isEqualTo("SELECT date_trunc('hour', \"at\") FROM \"trades\"");
        }

        @Test
        @DisplayName("DATE_TRUNC falls back to in-engine on the GENERIC dialect")
        void dateTruncGenericFallsBack() {
            // GENERIC has no DATE_TRUNC equivalent, so the projection falls back.
            PhysicalNode node = planPushed(
                    TRADES_SETUP + "query { π DATE_TRUNC('hour', at) → hr (Trades) };");
            assertThat(node).isNotInstanceOf(PhysicalNode.PushedScan.class);
        }

        @Test
        @DisplayName("a DURATION literal renders as INTERVAL 'PT…' on Postgres")
        void durationLiteralPostgresInterval() {
            String setup =
                    "connection db from database { url: \"jdbc:h2:mem:x\", dialect: postgres };\n" +
                    "source Trades from db { table: \"trades\",\n" +
                    "    schema: { id: NUMBER, at: TIMESTAMP, day: DATE, held: DURATION } };\n";
            PhysicalNode.PushedScan s = assertThat(planPushed(
                    setup + "query { σ held > DURATION 'PT30M' (Trades) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery())
                    .contains("WHERE (\"held\" > INTERVAL 'PT30M')");
        }

        @Test
        @DisplayName("limit folds into LIMIT/OFFSET")
        void limitToLimit() {
            assertThat(assertThat(planPushed(ORDERS_SETUP + "query { λ 5 (Orders) };")).asNode(PhysicalNode.PushedScan.class).nativeQuery())
                    .isEqualTo("SELECT id, amount FROM orders LIMIT 5");
            assertThat(assertThat(planPushed(ORDERS_SETUP + "query { λ 5, 10 (Orders) };")).asNode(PhysicalNode.PushedScan.class).nativeQuery())
                    .isEqualTo("SELECT id, amount FROM orders LIMIT 10 OFFSET 5");
        }

        @Test
        @DisplayName("selection, projection and limit fold into one query, WHERE before SELECT before LIMIT")
        void fullStack() {
            PhysicalNode.PushedScan s = assertThat(planPushed(
                    ORDERS_SETUP + "query { λ 5 (π amount (σ amount > 0 (Orders))) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery())
                    .isEqualTo("SELECT amount FROM orders WHERE (amount > 0) LIMIT 5");
        }

        @Test
        @DisplayName("δ folds into SELECT DISTINCT, leaving no in-engine Distinct node (#540)")
        void distinctToSelectDistinct() {
            PhysicalNode node = planPushed(ORDERS_SETUP + "query { δ (π amount (Orders)) };");

            // The whole sub-tree became one scan: if any PhysicalNode.Distinct survived,
            // the database would deduplicate and the engine would do it a second time.
            assertThat(assertThat(node).asNode(PhysicalNode.PushedScan.class).nativeQuery())
                    .isEqualTo("SELECT DISTINCT amount FROM orders");
        }

        @Test
        @DisplayName("a δ that cannot be pushed keeps its in-engine Distinct over a pushed input")
        void unpushableDistinctFallsBack() {
            // δ over a GROUP BY is not rendered (γ already emits one row per key), so the
            // planner pushes the γ alone and keeps the Distinct above it.
            PhysicalNode node = planPushed(
                    ORDERS_SETUP + "query { δ (γ id, SUM(amount) → total (Orders)) };");

            assertThat(node).isNode(PhysicalNode.Distinct.class);
            assertThat(assertThat(((PhysicalNode.Distinct) node).input()).asNode(PhysicalNode.PushedScan.class).nativeQuery())
                    .isEqualTo("SELECT id, SUM(amount) FROM orders GROUP BY id");
        }

        @Test
        @DisplayName("sort folds into ORDER BY (default ASC, explicit DESC)")
        void sortToOrderBy() {
            assertThat(assertThat(planPushed(ORDERS_SETUP + "query { τ amount (Orders) };")).asNode(PhysicalNode.PushedScan.class).nativeQuery())
                    .isEqualTo("SELECT id, amount FROM orders ORDER BY (amount IS NULL) ASC, amount ASC");
            assertThat(assertThat(planPushed(
                    ORDERS_SETUP + "query { τ amount DESC, id ASC (Orders) };")).asNode(PhysicalNode.PushedScan.class).nativeQuery())
                    .isEqualTo("SELECT id, amount FROM orders ORDER BY (amount IS NULL) ASC, amount DESC, (id IS NULL) ASC, id ASC");
        }

        @Test
        @DisplayName("a pushed ORDER BY makes the PushedScan advertise that delivered ordering")
        void pushedSortDeliversOrdering() {
            PhysicalNode.PushedScan s = assertThat(planPushed(
                    ORDERS_SETUP + "query { τ amount DESC, id ASC (Orders) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.deliveredOrdering()).isEqualTo(Ordering.of(List.of(
                    desc("amount"),
                    asc("id"))));
        }

        @Test
        @DisplayName("a PushedScan with no pushed ORDER BY advertises no ordering")
        void unsortedScanDeliversNoOrdering() {
            assertThat(assertThat(planPushed(ORDERS_SETUP + "query { Orders };")).asNode(PhysicalNode.PushedScan.class)
                    .deliveredOrdering()).isEqualTo(Ordering.none());
            assertThat(assertThat(planPushed(ORDERS_SETUP + "query { σ amount > 0 (Orders) };")).asNode(PhysicalNode.PushedScan.class)
                    .deliveredOrdering()).isEqualTo(Ordering.none());
        }

        @Test
        @DisplayName("filter + sort + limit fold as WHERE … ORDER BY … LIMIT (top-N)")
        void filterSortLimit() {
            PhysicalNode.PushedScan s = assertThat(planPushed(
                    ORDERS_SETUP + "query { λ 3 (τ amount DESC (σ amount > 0 (Orders))) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery()).isEqualTo(
                    "SELECT id, amount FROM orders WHERE (amount > 0) ORDER BY (amount IS NULL) ASC, amount DESC LIMIT 3");
        }

        @Test
        @DisplayName("grouped aggregation folds into select list + GROUP BY")
        void aggregationToGroupBy() {
            PhysicalNode.PushedScan s = assertThat(planPushed(
                    ORDERS_SETUP + "query { γ id, SUM(amount) → total (Orders) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery()).isEqualTo("SELECT id, SUM(amount) FROM orders GROUP BY id");
        }

        @Test
        @DisplayName("aggregate over an arithmetic expression folds into SQL (E8)")
        void aggregateOverExpressionToSql() {
            PhysicalNode.PushedScan s = assertThat(planPushed(
                    ORDERS_SETUP + "query { γ id, SUM(amount * amount) → sq (Orders) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery())
                    .isEqualTo("SELECT id, SUM((amount * amount)) FROM orders GROUP BY id");
        }

        @Test
        @DisplayName("AVG folds into select list + GROUP BY")
        void avgAggregationToGroupBy() {
            PhysicalNode.PushedScan s = assertThat(planPushed(
                    ORDERS_SETUP + "query { γ id, SUM(amount) → total (Orders) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery()).isEqualTo("SELECT id, SUM(amount) FROM orders GROUP BY id");
        }

        @Test
        @DisplayName("COUNT folds into select list + GROUP BY")
        void countAggregationToGroupBy() {
            PhysicalNode.PushedScan s = assertThat(planPushed(
                    ORDERS_SETUP + "query { γ id, COUNT(amount) → total (Orders) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery()).isEqualTo("SELECT id, COUNT(amount) FROM orders GROUP BY id");
        }

        @Test
        @DisplayName("MIN folds into select list + GROUP BY")
        void minAggregationToGroupBy() {
            PhysicalNode.PushedScan s = assertThat(planPushed(
                    ORDERS_SETUP + "query { γ id, MIN(amount) → total (Orders) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery()).isEqualTo("SELECT id, MIN(amount) FROM orders GROUP BY id");
        }

        @Test
        @DisplayName("MAX folds into select list + GROUP BY")
        void maxAggregationToGroupBy() {
            PhysicalNode.PushedScan s = assertThat(planPushed(
                    ORDERS_SETUP + "query { γ id, MAX(amount) → total (Orders) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery()).isEqualTo("SELECT id, MAX(amount) FROM orders GROUP BY id");
        }

        @Test
        @DisplayName("COLLECT has no portable SQL — not folded; in-engine Aggregate over the base")
        void collectNotPushedDown() {
            PhysicalNode plan = planPushed(
                    ORDERS_SETUP + "query { γ id, COLLECT(amount) → all (Orders) };");
            assertThat(plan).isNode(PhysicalNode.Aggregate.class);
        }

        @Test
        @DisplayName("a scalar aggregate (no grouping) folds with no GROUP BY")
        void scalarAggregation() {
            PhysicalNode.PushedScan s = assertThat(planPushed(
                    ORDERS_SETUP + "query { γ SUM(amount) → total (Orders) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery()).isEqualTo("SELECT SUM(amount) FROM orders");
        }

        @Test
        @DisplayName("closure is not pushed down — in-engine Closure over the base scan")
        void closureNotPushedDown() {
            PhysicalNode plan = planPushed(
                    ORDERS_SETUP + "query { CLOSURE id, amount (Orders) };");
            assertThat(plan).isNode(PhysicalNode.Closure.class);
        }

        @Test
        @DisplayName("FIX is not pushed down — in-engine Fixpoint over base scan inputs")
        void fixpointNotPushedDown() {
            PhysicalNode plan = planPushed(
                    ORDERS_SETUP + "query { FIX R (Orders, Orders ∪ R) };");
            assertThat(plan).isNode(PhysicalNode.Fixpoint.class);
        }

        @Test
        @DisplayName("ARGMAX has no portable SQL — not folded; in-engine Aggregate over the base")
        void argmaxNotPushedDown() {
            PhysicalNode plan = planPushed(
                    ORDERS_SETUP + "query { γ ARGMAX(amount, id) → top (Orders) };");
            assertThat(plan).isNode(PhysicalNode.Aggregate.class);
        }

        @Test
        @DisplayName("aggregation folds WHERE before GROUP BY")
        void filteredAggregation() {
            PhysicalNode.PushedScan s = assertThat(planPushed(
                    ORDERS_SETUP + "query { γ id, SUM(amount) → total (σ amount > 0 (Orders)) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery()).isEqualTo(
                    "SELECT id, SUM(amount) FROM orders WHERE (amount > 0) GROUP BY id");
        }

        @Test
        @DisplayName("a sort above a computed projection is not folded (the key is a select-list alias)")
        void sortAboveProjectionNotFolded() {
            PhysicalNode plan = planPushed(
                    ORDERS_SETUP + "query { τ doubled (π amount * 2 → doubled (Orders)) };");
            assertThat(plan).isNode(PhysicalNode.Sort.class);
            assertThat(((PhysicalNode.Sort) plan).input()).isNode(PhysicalNode.PushedScan.class);
        }

        @Test
        @DisplayName("a selection above a computed projection cannot fold — engine Select over a pushed projection")
        void selectionAboveProjectionNotFolded() {
            PhysicalNode plan = planPushed(
                    ORDERS_SETUP + "query { σ doubled > 0 (π amount * 2 → doubled (Orders)) };");
            assertThat(plan).isNode(PhysicalNode.Select.class);
            PhysicalNode.PushedScan pushed = assertThat(((PhysicalNode.Select) plan).input()).asNode(PhysicalNode.PushedScan.class);
            assertThat(pushed.nativeQuery()).isEqualTo("SELECT (amount * 2) FROM orders");
        }

        @Test
        @DisplayName("a selection above a *column-pruning* projection does fold — one narrower SELECT")
        void selectionAbovePruningProjectionFolds() {
            // The shape PROJ-004 inserts. A pruning π names only real table columns, so
            // the σ above it still becomes a WHERE rather than an in-engine filter.
            PhysicalNode.PushedScan s = assertThat(planPushed(
                    ORDERS_SETUP + "query { σ amount > 0 (π amount (Orders)) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery()).isEqualTo("SELECT amount FROM orders WHERE (amount > 0)");
        }

        @Test
        @DisplayName("an untranslatable predicate falls back — engine Select over a pushed scan")
        void untranslatablePredicateFallsBack() {
            PhysicalNode plan = planPushed(
                    ORDERS_SETUP + "query { σ Sqr(amount) > 0 (Orders) };");
            assertThat(plan).isNode(PhysicalNode.Select.class);
            assertThat(((PhysicalNode.Select) plan).input()).isNode(PhysicalNode.PushedScan.class);
        }

        @Test
        @DisplayName("pushdown disabled (no connections) → an ordinary Scan, never a PushedScan")
        void disabledWithoutConnections() {
            // The 2-arg planner has no pushdown metadata.
            assertThat(planFirstQuery(ORDERS_SETUP + "query { Orders };"))
                    .isNode(PhysicalNode.Scan.class);
        }

        @Test
        @DisplayName("a non-connection (inline) source is never pushed")
        void inlineSourceNotPushed() {
            PhysicalNode plan = planPushed(
                    "Nums := [| n |\n       | 1 |];\n" +
                    "connection db from database { url: \"jdbc:h2:mem:x\" };\n" +
                    "query { σ n > 0 (Nums) };");
            assertThat(plan).isNode(PhysicalNode.Select.class);
            assertThat(((PhysicalNode.Select) plan).input()).isNode(PhysicalNode.Scan.class);
        }

        // ── MongoDB pushdown (ADR-0011) ─────────────────────────────────────

        /** A mongodb connection + a collection-backed source with a declared schema. */
        private static final String MONGO_SETUP =
                "connection mg from mongodb { uri: \"mongodb://localhost:27017\", database: \"app\" };\n" +
                "source Docs from mg { table: \"docs\", schema: { id: NUMBER, tags: ANY } };\n";

        @Test
        @DisplayName("a bare mongodb collection → a PushedScan with an empty pipeline")
        void mongoBareCollection() {
            PhysicalNode.PushedScan s = assertThat(planPushed(MONGO_SETUP + "query { Docs };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.connectorType()).isEqualTo("mongodb");
            assertThat(s.connection()).isEqualTo("mg");
            assertThat(s.nativeQuery()).isEqualTo("{\"collection\": \"docs\", \"pipeline\": []}");
        }

        @Test
        @DisplayName("selection folds into a $match stage")
        void mongoSelectionToMatch() {
            PhysicalNode.PushedScan s = assertThat(planPushed(MONGO_SETUP + "query { σ id > 0 (Docs) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery()).isEqualTo(
                    "{\"collection\": \"docs\", \"pipeline\": [{\"$match\": {\"id\": {\"$gt\": 0}}}]}");
        }

        @Test
        @DisplayName("unnest folds into a $unwind stage")
        void mongoUnnestToUnwind() {
            PhysicalNode.PushedScan s = assertThat(planPushed(MONGO_SETUP + "query { μ tags (Docs) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery()).isEqualTo(
                    "{\"collection\": \"docs\", \"pipeline\": [{\"$unwind\": \"$tags\"}]}");
        }

        @Test
        @DisplayName("selection then unnest fold into $match → $unwind, in pipeline order")
        void mongoSelectionThenUnwind() {
            PhysicalNode.PushedScan s = assertThat(planPushed(
                    MONGO_SETUP + "query { μ tags (σ id > 0 (Docs)) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery()).isEqualTo(
                    "{\"collection\": \"docs\", \"pipeline\": ["
                    + "{\"$match\": {\"id\": {\"$gt\": 0}}}, {\"$unwind\": \"$tags\"}]}");
        }

        @Test
        @DisplayName("projection of bare columns folds into a $project inclusion stage")
        void mongoProjectionToProject() {
            PhysicalNode.PushedScan s = assertThat(planPushed(MONGO_SETUP + "query { π id (Docs) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery()).isEqualTo(
                    "{\"collection\": \"docs\", \"pipeline\": [{\"$project\": {\"id\": 1, \"_id\": 0}}]}");
        }

        @Test
        @DisplayName("limit folds into $limit; an offset adds a preceding $skip")
        void mongoLimitToLimit() {
            assertThat(assertThat(planPushed(MONGO_SETUP + "query { λ 5 (Docs) };")).asNode(PhysicalNode.PushedScan.class).nativeQuery())
                    .isEqualTo("{\"collection\": \"docs\", \"pipeline\": [{\"$limit\": 5}]}");
            assertThat(assertThat(planPushed(MONGO_SETUP + "query { λ 5, 10 (Docs) };")).asNode(PhysicalNode.PushedScan.class).nativeQuery())
                    .isEqualTo("{\"collection\": \"docs\", \"pipeline\": [{\"$skip\": 5}, {\"$limit\": 10}]}");
        }

        @Test
        @DisplayName("the pushed scan's output schema is the sub-tree's schema")
        void mongoPushedSchema() {
            PhysicalNode.PushedScan s = assertThat(planPushed(MONGO_SETUP + "query { π id (Docs) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.schema()).hasColumnNames("id");
        }

        @Test
        @DisplayName("aggregation is not pushed (deferred) — in-engine Aggregate over the pushed scan")
        void mongoAggregationFallsBack() {
            // γ→$group is out of scope for this slice (ADR-0011), so the aggregation runs
            // in-engine over a bare pushed collection.
            PhysicalNode plan = planPushed(MONGO_SETUP + "query { γ id, COUNT(id) → n (Docs) };");
            assertThat(plan).isNode(PhysicalNode.Aggregate.class);
            assertThat(((PhysicalNode.Aggregate) plan).input()).isNode(PhysicalNode.PushedScan.class);
        }

        @Test
        @DisplayName("an untranslatable predicate is not pushed — in-engine Select over the pushed scan")
        void mongoUntranslatablePredicateFallsBack() {
            // A field-to-field comparison has no simple $match form (it needs $expr) → the
            // σ falls back to in-engine over the bare pushed collection.
            PhysicalNode plan = planPushed(
                    "connection mg from mongodb { uri: \"mongodb://localhost:27017\", database: \"app\" };\n" +
                    "source Docs from mg { table: \"docs\", schema: { a: NUMBER, b: NUMBER } };\n" +
                    "query { σ a > b (Docs) };");
            assertThat(plan).isNode(PhysicalNode.Select.class);
            assertThat(((PhysicalNode.Select) plan).input()).isNode(PhysicalNode.PushedScan.class);
        }

        // ── same-connection joins ───────────────────────────────────────────

        /** Two tables on one connection. */
        private static final String JOIN_SETUP =
                "connection db from database { url: \"jdbc:h2:mem:x\" };\n" +
                "source Orders from db { table: \"orders\", schema: { id: NUMBER, amount: NUMBER } };\n" +
                "source Customers from db { table: \"customers\", schema: { cid: NUMBER, name: STRING } };\n";

        @Test
        @DisplayName("an inner equi-join on one connection folds into a JOIN … ON")
        void sameConnectionJoinFolds() {
            assertThat(planPushed(
                    JOIN_SETUP + "query { Orders ⨝ Orders.id = Customers.cid Customers };"))
                    .isPushedTo("db",
                            "SELECT Orders.id, Orders.amount, Customers.cid, Customers.name "
                            + "FROM orders Orders JOIN customers Customers ON (Orders.id = Customers.cid)");
        }

        @Test
        @DisplayName("a selection above a pushed join folds into WHERE, resolving the column's table")
        void selectionAboveJoinFolds() {
            PhysicalNode.PushedScan s = assertThat(planPushed(JOIN_SETUP
                    + "query { σ amount > 0 (Orders ⨝ Orders.id = Customers.cid Customers) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery()).isEqualTo(
                    "SELECT Orders.id, Orders.amount, Customers.cid, Customers.name "
                    + "FROM orders Orders JOIN customers Customers ON (Orders.id = Customers.cid) "
                    + "WHERE (Orders.amount > 0)");
        }

        @Test
        @DisplayName("a projection above a pushed join folds into a qualified select list")
        void projectionAboveJoinFolds() {
            PhysicalNode.PushedScan s = assertThat(planPushed(JOIN_SETUP
                    + "query { π name (Orders ⨝ Orders.id = Customers.cid Customers) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery()).isEqualTo(
                    "SELECT Customers.name FROM orders Orders JOIN customers Customers "
                    + "ON (Orders.id = Customers.cid)");
        }

        @Test
        @DisplayName("an aggregate on a column ambiguous across the join can't render — in-engine Aggregate over the pushed join")
        void aggregateAmbiguousColumnOverJoinFallsBack() {
            // Both tables carry an `amount` column, so an unqualified SUM(amount) over the
            // pushed join is ambiguous to the join renderer → aggregateSql can't render the
            // column → the aggregation falls back to in-engine over the pushed-join PushedScan.
            String setup =
                    "connection db from database { url: \"jdbc:h2:mem:x\" };\n" +
                    "source L from db { table: \"l\", schema: { k: NUMBER, amount: NUMBER } };\n" +
                    "source R from db { table: \"r\", schema: { k: NUMBER, amount: NUMBER } };\n";
            PhysicalNode plan = planPushed(setup
                    + "query { γ SUM(amount) → t (L ⨝ L.k = R.k R) };");
            assertThat(plan).isNode(PhysicalNode.Aggregate.class);
            assertThat(((PhysicalNode.Aggregate) plan).input())
                    .isNode(PhysicalNode.PushedScan.class);
        }

        @Test
        @DisplayName("a join across two connections is not pushed — an in-engine join over two PushedScans")
        void crossConnectionJoinNotPushed() {
            PhysicalNode plan = planPushed(
                    "connection db1 from database { url: \"jdbc:h2:mem:a\" };\n" +
                    "connection db2 from database { url: \"jdbc:h2:mem:b\" };\n" +
                    "source Orders from db1 { table: \"orders\", schema: { id: NUMBER } };\n" +
                    "source Customers from db2 { table: \"customers\", schema: { cid: NUMBER } };\n" +
                    "query { Orders ⨝ Orders.id = Customers.cid Customers };");
            PhysicalNode.Join j = assertThat(plan).asNode(PhysicalNode.Join.class);
            assertThat(j.left()).isNode(PhysicalNode.PushedScan.class);
            assertThat(j.right()).isNode(PhysicalNode.PushedScan.class);
        }

        // ── dialects ────────────────────────────────────────────────────────

        @Test
        @DisplayName("a postgres dialect quotes identifiers in the pushed SQL")
        void postgresQuotesIdentifiers() {
            String setup =
                    "connection db from database { url: \"jdbc:h2:mem:x\", dialect: postgres };\n" +
                    "source Orders from db { table: \"orders\", schema: { id: NUMBER, amount: NUMBER } };\n";
            PhysicalNode.PushedScan s = assertThat(planPushed(setup + "query { σ amount > 0 (Orders) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery())
                    .isEqualTo("SELECT \"id\", \"amount\" FROM \"orders\" WHERE (\"amount\" > 0)");
        }

        @Test
        @DisplayName("a mysql dialect back-tick-quotes a pushed join")
        void mysqlQuotesJoin() {
            String setup =
                    "connection db from database { url: \"jdbc:h2:mem:x\", dialect: mysql };\n" +
                    "source Orders from db { table: \"orders\", schema: { id: NUMBER, amount: NUMBER } };\n" +
                    "source Customers from db { table: \"customers\", schema: { cid: NUMBER, name: STRING } };\n";
            PhysicalNode.PushedScan s = assertThat(planPushed(
                    setup + "query { Orders ⨝ Orders.id = Customers.cid Customers };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery()).isEqualTo(
                    "SELECT `Orders`.`id`, `Orders`.`amount`, `Customers`.`cid`, `Customers`.`name` "
                    + "FROM `orders` `Orders` JOIN `customers` `Customers` "
                    + "ON (`Orders`.`id` = `Customers`.`cid`)");
        }

        @Test
        @DisplayName("a σ on a join input is peeled into the joined statement's WHERE")
        void joinWithFilteredInputStillPushes() {
            // This used to decline, and the optimizer is what made that expensive:
            // SEL-005 pushes a conjunct into the input it belongs to, which is the right
            // rewrite for an in-engine join and used to cost a same-connection join its
            // whole-statement fold. An inner join commutes with a filter on either side.
            PhysicalNode.PushedScan s = assertThat(planPushed(JOIN_SETUP
                    + "query { (σ amount > 0 (Orders)) ⨝ Orders.id = Customers.cid Customers };"))
                    .asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery()).isEqualTo(
                    "SELECT Orders.id, Orders.amount, Customers.cid, Customers.name "
                    + "FROM orders Orders JOIN customers Customers "
                    + "ON (Orders.id = Customers.cid) WHERE (Orders.amount > 0)");
        }

        @Test
        @DisplayName("a σ on each side folds into one WHERE")
        void joinWithBothInputsFilteredStillPushes() {
            PhysicalNode.PushedScan s = assertThat(planPushed(JOIN_SETUP
                    + "query { (σ amount > 0 (Orders)) ⨝ Orders.id = Customers.cid "
                    + "(σ name = \"acme\" (Customers)) };"))
                    .asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery())
                    .contains("WHERE (Orders.amount > 0) AND (Customers.name = 'acme')");
        }

        @Test
        @DisplayName("only σ is peeled — a λ on an input still declines the join fold")
        void joinWithLimitedInputNotPushed() {
            // The limitation that remains, and it is a different one: the folded
            // statement reads each side's table directly, so an operator that changes
            // what the side *is* cannot be peeled the way a filter can. A λ is the
            // clearest case — it commutes with nothing.
            //
            // A *column-pruning* π is deliberately not the example: it never stopped the
            // fold, since isColumnPruning() already leaves the side a bare scan.
            PhysicalNode plan = planPushed(JOIN_SETUP
                    + "query { (λ 5 (Orders)) ⨝ Orders.id = Customers.cid Customers };");
            assertThat(plan).isNode(PhysicalNode.Join.class);
        }

        @Test
        @DisplayName("a merge join's unsorted side gets a pushed ORDER BY rather than an in-engine Sort (C4)")
        void mergeEnforcerPushesOrderByIntoSource() {
            PhysicalNode.Join j = assertThat(planPushed(JOIN_SETUP
                    + "query { (τ id ASC (Orders)) ⨝ Orders.id = Customers.cid Customers };")).asNode(PhysicalNode.Join.class);
            assertThat(j.algorithm()).isEqualTo(JoinAlgorithm.MERGE);
            // Left already delivers the order from its pushed τ.
            assertThat(assertThat(j.left()).asNode(PhysicalNode.PushedScan.class).nativeQuery())
                    .isEqualTo("SELECT id, amount FROM orders ORDER BY (id IS NULL) ASC, id ASC");
            // Right was unsorted — the required order is pushed into the source as ORDER BY,
            // not enforced by an in-engine Sort node.
            PhysicalNode.PushedScan right = assertThat(j.right()).asNode(PhysicalNode.PushedScan.class);
            assertThat(right.nativeQuery()).isEqualTo("SELECT cid, name FROM customers ORDER BY (cid IS NULL) ASC, cid ASC");
            assertThat(right.deliveredOrdering()).isEqualTo(
                    Ordering.of(List.of(asc("cid"))));
        }

        @Test
        @DisplayName("when a side can't take an ORDER BY (computed projection pushed) the enforcer is an in-engine Sort")
        void mergeEnforcerFallsBackToEngineSortWhenOrderByNotPushable() {
            // The right side pushes a *computed* select list, so `cid` is an alias rather
            // than a table column and an ORDER BY can no longer be folded over it; the
            // order is enforced in-engine instead. (A column-pruning π would still take
            // the ORDER BY — see `mergeOrderPushedIntoUnsortedSide`.)
            PhysicalNode.Join j = assertThat(planPushed(JOIN_SETUP
                    + "query { (τ id ASC (Orders)) ⨝ Orders.id = Customers.cid "
                    + "(π cid * 1 → cid (Customers)) };")).asNode(PhysicalNode.Join.class);
            assertThat(j.algorithm()).isEqualTo(JoinAlgorithm.MERGE);
            PhysicalNode.Sort sort = assertThat(j.right()).asNode(PhysicalNode.Sort.class);
            assertThat(assertThat(sort.input()).asNode(PhysicalNode.PushedScan.class).nativeQuery())
                    .isEqualTo("SELECT (cid * 1) FROM customers");
        }

        @Test
        @DisplayName("a non-pushable side (an inline relation) is order-enforced by an in-engine Sort even with pushdown on")
        void mergeEnforcerFallsBackForNonPushableSide() {
            PhysicalNode.Join j = assertThat(planPushed(JOIN_SETUP
                    + "R := [| cid | tag |\n       | 1 | a |];\n"
                    + "query { (τ id ASC (Orders)) ⨝ Orders.id = R.cid R };")).asNode(PhysicalNode.Join.class);
            assertThat(j.algorithm()).isEqualTo(JoinAlgorithm.MERGE);
            // The inline side can't push an ORDER BY into any source → in-engine Sort.
            assertThat(j.right()).isNode(PhysicalNode.Sort.class);
        }

        // ── universal quantification (∀) pushdown ────────────────────────────

        @Test
        @DisplayName("∀ with grouping keys folds into GROUP BY … HAVING on a GENERIC connection")
        void universalToGroupByHaving() {
            PhysicalNode.PushedScan s = assertThat(planPushed(
                    ORDERS_SETUP + "query { ∀ id : amount > 0 (Orders) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery()).isEqualTo(
                    "SELECT id FROM orders GROUP BY id "
                    + "HAVING COUNT(*) = COUNT(CASE WHEN (amount > 0) THEN 1 END)");
        }

        @Test
        @DisplayName("∀ output schema contains only the grouping keys")
        void universalOutputSchemaIsKeys() {
            PhysicalNode.PushedScan s = assertThat(planPushed(
                    ORDERS_SETUP + "query { ∀ id : amount > 0 (Orders) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.schema().columns().stream().map(c -> c.name()).toList())
                    .containsExactly("id");
        }

        @Test
        @DisplayName("∀ with a σ below folds WHERE before GROUP BY … HAVING")
        void universalWithFilterBelow() {
            PhysicalNode.PushedScan s = assertThat(planPushed(
                    ORDERS_SETUP + "query { ∀ id : amount > 0 (σ amount < 1000 (Orders)) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery()).isEqualTo(
                    "SELECT id FROM orders WHERE (amount < 1000) GROUP BY id "
                    + "HAVING COUNT(*) = COUNT(CASE WHEN (amount > 0) THEN 1 END)");
        }

        @Test
        @DisplayName("∀ on a POSTGRES connection uses the strict form, like every other dialect")
        void universalPostgresUsesBoolAnd() {
            String setup =
                    "connection db from database { url: \"jdbc:h2:mem:x\", dialect: postgres };\n" +
                    "source Orders from db { table: \"orders\", schema: { id: NUMBER, amount: NUMBER } };\n";
            PhysicalNode.PushedScan s = assertThat(planPushed(
                    setup + "query { ∀ id : amount > 0 (Orders) };")).asNode(PhysicalNode.PushedScan.class);
            assertThat(s.nativeQuery()).isEqualTo(
                    "SELECT \"id\" FROM \"orders\" GROUP BY \"id\" "
                            + "HAVING COUNT(*) = COUNT(CASE WHEN (\"amount\" > 0) THEN 1 END)");
        }

        @Test
        @DisplayName("no-key ∀ (whole-relation form) falls back to in-engine Universal")
        void universalNoKeyFallsBack() {
            PhysicalNode plan = planPushed(
                    ORDERS_SETUP + "query { ∀ : amount > 0 (Orders) };");
            assertThat(plan).isNode(PhysicalNode.Universal.class);
            assertThat(((PhysicalNode.Universal) plan).input())
                    .isNode(PhysicalNode.PushedScan.class);
        }

        @Test
        @DisplayName("∀ on a MYSQL connection folds too — the HAVING form is SQL-92")
        void universalMysqlFolds() {
            String setup =
                    "connection db from database { url: \"jdbc:h2:mem:x\", dialect: mysql };\n" +
                    "source Orders from db { table: \"orders\", schema: { id: NUMBER, amount: NUMBER } };\n";
            PhysicalNode plan = planPushed(
                    setup + "query { ∀ id : amount > 0 (Orders) };");
            assertThat(plan).isNode(PhysicalNode.PushedScan.class);
            assertThat(((PhysicalNode.PushedScan) plan).nativeQuery())
                    .contains("HAVING COUNT(*) = COUNT(CASE WHEN (`amount` > 0) THEN 1 END)");
        }

        @Test
        @DisplayName("∀ with an untranslatable predicate falls back to in-engine Universal")
        void universalUntranslatablePredicateFallsBack() {
            PhysicalNode plan = planPushed(
                    // Sqr has no spelling: it returns a backend's own double, which its
                    // driver rounds to a decimal that disagrees with this JVM's.
                    ORDERS_SETUP + "query { ∀ id : Sqr(amount) > 0 (Orders) };");
            assertThat(plan).isNode(PhysicalNode.Universal.class);
            assertThat(((PhysicalNode.Universal) plan).input())
                    .isNode(PhysicalNode.PushedScan.class);
        }
    }

    // ── event emission ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("event emission")
    class Events {

        /** Plans the first query with a listener and returns the collected events. */
        private static List<QueryEvent> eventsFor(String src) {
            SemanticModel model = model(src);
            RelNode logical =
                    ((ExpressionQueryTarget) model.rootQueries().get(0).target()).expression();
            List<QueryEvent> events = new ArrayList<>();
            new Planner(model.symbolTable(), model.nodeSchemas(), model.statistics(),
                    model.sources(), model.connections(), events::add).plan(logical);
            return events;
        }

        @Test
        @DisplayName("planning a join emits a PLAN/JOIN event with its strategy")
        void joinEmitsEvent() {
            List<QueryEvent> events = eventsFor(
                    "A := [| k | a |\n       | 1 | x |];\n" +
                    "B := [| k | b |\n       | 1 | y |];\n" +
                    "query { A ⋈ B };");
            assertThat(events).anySatisfy(e -> {
                assertThat(e.stage()).isEqualTo(QueryEvent.Stage.PLAN);
                assertThat(e.code()).isEqualTo("JOIN");
                assertThat(e.description()).contains("NATURAL").contains("build=");
            });
        }

        @Test
        @DisplayName("a shared sub-expression emits a PLAN/SPOOL event naming it")
        void spoolEmitsEvent() {
            List<QueryEvent> events = eventsFor(
                    "A := [| id |\n       | 1  |];\n" +
                    "B := [| id |\n       | 2  |];\n" +
                    "query { (σ id > 0 (A)) ∆ (σ id > 0 (B)) };");

            assertThat(events).filteredOn(e -> e.code().equals("SPOOL"))
                    .as("one per shared input of the ∆")
                    .hasSize(2)
                    .allSatisfy(e -> {
                        assertThat(e.stage()).isEqualTo(QueryEvent.Stage.PLAN);
                        assertThat(e.description())
                                .contains("shared sub-expression #")
                                .contains("(2 sites)")
                                .doesNotContain("\n");
                    });
        }

        @Test
        @DisplayName("a pushed sub-tree emits a PLAN/PUSHDOWN event carrying the SQL")
        void pushdownEmitsEvent() {
            List<QueryEvent> events = eventsFor(
                    "connection db from database { url: \"jdbc:h2:mem:x\" };\n" +
                    "source Orders from db { table: \"orders\", schema: { id: NUMBER, amount: NUMBER } };\n" +
                    "query { σ amount > 0 (Orders) };");
            assertThat(events).anySatisfy(e -> {
                assertThat(e.code()).isEqualTo("PUSHDOWN");
                assertThat(e.description()).contains("db").contains("SELECT id, amount FROM orders");
            });
        }

        @Test
        @DisplayName("a plain scan emits no events")
        void plainScanEmitsNothing() {
            assertThat(eventsFor("A := [| k |\n       | 1 |];\nquery { A };")).isEmpty();
        }

        // ── the nested-loop fallback (#544) ──────────────────────────────────
        //
        // `JoinExecutor.hashable(join)` is exactly `algorithm() == HASH`, and MERGE is
        // dispatched before it, so the executor walks the whole right input per left row
        // precisely when the planner chose NESTED_LOOP. The plan is therefore honest
        // already — what was missing is the reason, which only the planner knows.

        /** Two relations joined on an inequality: no equi-key, so no hash join. */
        private static final String INEQUALITY_JOIN =
                "A := [| k | a |\n       | 1 | x |];\n" +
                "B := [| j | b |\n       | 1 | y |];\n" +
                "query { A ⨝ A.k < B.j B };";

        @Test
        @DisplayName("a join with no equi-key emits a PLAN/JOIN-NESTED-LOOP event saying why")
        void nestedLoopJoinEmitsEvent() {
            assertThat(eventsFor(INEQUALITY_JOIN)).anySatisfy(e -> {
                assertThat(e.stage()).isEqualTo(QueryEvent.Stage.PLAN);
                assertThat(e.code()).isEqualTo("JOIN-NESTED-LOOP");
                assertThat(e.description())
                        .contains("no usable equi-join key")
                        .contains("O(n×m)");
            });
        }

        @Test
        @DisplayName("the event names the algorithm the executor will actually run")
        void nestedLoopEventMatchesThePlan() {
            SemanticModel model = model(INEQUALITY_JOIN);
            RelNode logical =
                    ((ExpressionQueryTarget) model.rootQueries().get(0).target()).expression();
            PhysicalNode plan = new Planner(model.symbolTable(), model.nodeSchemas(),
                    model.statistics(), model.sources(), model.connections(),
                    QueryEventListener.NONE, BoundednessSource.ALL_BOUNDED, model.functions())
                    .plan(logical);

            assertThat(plan).isNode(PhysicalNode.Join.class);
            assertThat(((PhysicalNode.Join) plan).algorithm())
                    .isEqualTo(PhysicalNode.JoinAlgorithm.NESTED_LOOP);
        }

        @Test
        @DisplayName("an equi-join plans HASH and emits no nested-loop event")
        void hashJoinEmitsNoNestedLoopEvent() {
            List<QueryEvent> events = eventsFor(
                    "A := [| k | a |\n       | 1 | x |];\n" +
                    "B := [| j | b |\n       | 1 | y |];\n" +
                    "query { A ⨝ A.k = B.j B };");
            assertThat(events).noneSatisfy(e -> assertThat(e.code()).isEqualTo("JOIN-NESTED-LOOP"));
        }

        @Test
        @DisplayName("a × emits no nested-loop event — quadratic is what it means")
        void productEmitsNoNestedLoopEvent() {
            List<QueryEvent> events = eventsFor(
                    "A := [| k | a |\n       | 1 | x |];\n" +
                    "B := [| j | b |\n       | 1 | y |];\n" +
                    "query { A × B };");
            assertThat(events).noneSatisfy(e -> assertThat(e.code()).isEqualTo("JOIN-NESTED-LOOP"));
        }

        // ── TRACE algorithm switch (ADR-0020 / #328 slice 2) ─────────────────
        //
        // Endpoint bounds are added by the optimizer (no surface syntax), so these
        // build a bounded TraceNode directly, re-annotate it, and plan it.

        private static java.util.Optional<com.darkcollective.relix.ast.Operand> lit(String v) {
            return java.util.Optional.of(new com.darkcollective.relix.ast.NumberOperand(v));
        }

        private static List<QueryEvent> traceEvents(String sense,
                java.util.Optional<com.darkcollective.relix.ast.Operand> from,
                java.util.Optional<com.darkcollective.relix.ast.Operand> to) {
            String src = "Edges := [| src | dst | cost |\n"
                    + "           | 1   | 2   | 3    |];\n"
                    + "query { TRACE src, dst VIA cost " + sense + " AS route (Edges) };";
            SemanticModel model = model(src);
            var raw = (com.darkcollective.relix.ast.TraceNode)
                    ((ExpressionQueryTarget) model.rootQueries().get(0).target()).expression();
            RelNode bounded = raw.withBounds(from, to);
            var ann = com.darkcollective.relix.semantic.SchemaInference.annotate(
                    model.symbolTable(), bounded, model.nodeSchemas(), model.functions());
            List<QueryEvent> events = new ArrayList<>();
            new Planner(model.symbolTable(), ann, model.statistics(),
                    model.sources(), model.connections(), events::add).plan(bounded);
            return events;
        }

        @Test
        @DisplayName("a bounded source+target MINIMIZE trace emits a PLAN/TRACE Dijkstra event")
        void boundedMinimizeEmitsTraceEvent() {
            List<QueryEvent> events = traceEvents("MINIMIZE", lit("1"), lit("2"));
            assertThat(events).anySatisfy(e -> {
                assertThat(e.stage()).isEqualTo(QueryEvent.Stage.PLAN);
                assertThat(e.code()).isEqualTo("TRACE");
                assertThat(e.description()).contains("Dijkstra").contains("non-negative");
            });
        }

        @Test
        @DisplayName("a source-only bound trace emits no PLAN/TRACE event (no algorithm switch)")
        void sourceOnlyEmitsNoTraceEvent() {
            assertThat(traceEvents("MINIMIZE", lit("1"), java.util.Optional.empty()))
                    .noneMatch(e -> e.code().equals("TRACE"));
        }

        @Test
        @DisplayName("a both-bounds MAXIMIZE trace emits no PLAN/TRACE event (Dijkstra is MINIMIZE-only)")
        void maximizeEmitsNoTraceEvent() {
            assertThat(traceEvents("MAXIMIZE", lit("1"), lit("2")))
                    .noneMatch(e -> e.code().equals("TRACE"));
        }
    }

    // ── Covering reduction ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Covering reduction (COVER)")
    class Cover {

        @Test
        @DisplayName("COVER translates to a PhysicalNode.Cover over its planned input")
        void translatesToPhysicalCover() {
            PhysicalNode plan = planFirstQuery(
                    "Params := [| x | y |\n" +
                    "            | a | 1 |\n" +
                    "            | b | 2 |];\n" +
                    "query { COVER 2 (Params) };");

            PhysicalNode.Cover c = assertThat(plan).asNode(PhysicalNode.Cover.class);
            assertThat(c.strength()).isEqualTo(2);
            assertThat(c.input()).isNode(PhysicalNode.Scan.class);
            // Output schema equals the input schema (windowed filter, no new columns).
            assertThat(c.schema().columns().stream().map(col -> col.name()).toList())
                    .containsExactly("x", "y");
        }

        @Test
        @DisplayName("COVER over an unbounded input is rejected at plan time (BoundednessChecker)")
        void coverOverUnboundedRejected() {
            String src =
                    "A := [| x | y |\n" +
                    "       | a | 1 |];\n" +
                    "query { COVER 2 (A) };\n";
            assertThatExceptionOfType(BoundednessException.class)
                    .isThrownBy(() -> planWithBoundedness(src, unbounded("A")))
                    .withMessageContaining("COVER");
        }
    }

    // ── constructive cover (ADR-0012 D3-B) ─────────────────────────────────────

    @Nested
    @DisplayName("constructive cover (pattern-match)")
    class ConstructiveCover {

        @Test
        @DisplayName("COVER over σ(Product) produces ConstructiveCover with factors + conjuncts")
        void productPatternProducesConstructiveCover() {
            PhysicalNode plan = planFirstQuery(
                    "A := [| x |\n" +
                    "       | a |];\n" +
                    "B := [| y |\n" +
                    "       | 1 |];\n" +
                    "query { COVER 2 (σ x != \"z\" (A × B)) };");

            PhysicalNode.ConstructiveCover cc = assertThat(plan).asNode(PhysicalNode.ConstructiveCover.class);
            assertThat(cc.strength()).isEqualTo(2);
            assertThat(cc.factors()).hasSize(2);
            assertThat(cc.conjuncts()).hasSize(1);
            assertThat(cc.schema().columns().stream().map(col -> col.name()).toList())
                    .containsExactly("x", "y");
        }

        @Test
        @DisplayName("COVER over bare Product (no selection) produces ConstructiveCover with empty conjuncts")
        void bareProductProducesConstructiveCoverNoConjuncts() {
            PhysicalNode plan = planFirstQuery(
                    "A := [| x |\n" +
                    "       | a |];\n" +
                    "B := [| y |\n" +
                    "       | 1 |];\n" +
                    "C := [| z |\n" +
                    "       | p |];\n" +
                    "query { COVER 2 (A × B × C) };");

            PhysicalNode.ConstructiveCover cc = assertThat(plan).asNode(PhysicalNode.ConstructiveCover.class);
            assertThat(cc.strength()).isEqualTo(2);
            assertThat(cc.factors()).hasSize(3);
            assertThat(cc.conjuncts()).isEmpty();
        }

        @Test
        @DisplayName("multiple AND conjuncts are split into individual conjuncts")
        void andConjunctsAreSplit() {
            PhysicalNode plan = planFirstQuery(
                    "A := [| x |\n" +
                    "       | a |];\n" +
                    "B := [| y |\n" +
                    "       | 1 |];\n" +
                    "query { COVER 2 (σ x != \"z\" ∧ y != \"9\" (A × B)) };");

            PhysicalNode.ConstructiveCover cc = assertThat(plan).asNode(PhysicalNode.ConstructiveCover.class);
            assertThat(cc.conjuncts()).hasSize(2);
        }

        @Test
        @DisplayName("stacked selection layers are all stripped — conjuncts collected from each")
        void stackedSelectionsStripped() {
            PhysicalNode plan = planFirstQuery(
                    "A := [| x |\n" +
                    "       | a |];\n" +
                    "B := [| y |\n" +
                    "       | 1 |];\n" +
                    "query { COVER 2 (σ x != \"z\" (σ y != \"9\" (A × B))) };");

            PhysicalNode.ConstructiveCover cc = assertThat(plan).asNode(PhysicalNode.ConstructiveCover.class);
            assertThat(cc.conjuncts()).hasSize(2);
        }

        @Test
        @DisplayName("COVER over a non-product input falls back to materialized PhysicalNode.Cover")
        void nonProductFallsBackToMaterialized() {
            PhysicalNode plan = planFirstQuery(
                    "Params := [| x | y |\n" +
                    "            | a | 1 |\n" +
                    "            | b | 2 |];\n" +
                    "query { COVER 2 (Params) };");

            assertThat(plan).isNode(PhysicalNode.Cover.class);
        }

        @Test
        @DisplayName("COVER over σ(non-product) falls back to materialized PhysicalNode.Cover")
        void selectionOverNonProductFallsBack() {
            PhysicalNode plan = planFirstQuery(
                    "Params := [| x | y |\n" +
                    "            | a | 1 |\n" +
                    "            | b | 2 |];\n" +
                    "query { COVER 2 (σ x = \"a\" (Params)) };");

            assertThat(plan).isNode(PhysicalNode.Cover.class);
        }
    }

    // ── boundedness enforcement (ADR-0008, #60a) ────────────────────────────────

    @Nested
    @DisplayName("boundedness enforcement")
    class BoundednessRule {

        private static final String JOIN = """
                A := [| k | a |
                       | 1 | x |];
                B := [| k | b |
                       | 1 | y |];
                query { A ⨝ A.k = B.k B };
                """;

        @Test
        @DisplayName("an unbounded join input is forced to the probe (the bounded side is built)")
        void unboundedInputForcedToProbe() {
            // B unbounded → build A (LEFT); A unbounded → build B (RIGHT).
            assertThat(assertThat(planWithBoundedness(JOIN, unbounded("B"))).asNode(PhysicalNode.Join.class).buildSide())
                    .isEqualTo(BuildSide.LEFT);
            assertThat(assertThat(planWithBoundedness(JOIN, unbounded("A"))).asNode(PhysicalNode.Join.class).buildSide())
                    .isEqualTo(BuildSide.RIGHT);
        }

        @Test
        @DisplayName("a join of two unbounded inputs is rejected")
        void bothUnboundedRejected() {
            assertThatExceptionOfType(BoundednessException.class)
                    .isThrownBy(() -> planWithBoundedness(JOIN, unbounded("A", "B")))
                    .withMessageContaining("unbounded");
        }

        @Test
        @DisplayName("a blocking operator over an unbounded input is rejected")
        void blockingOverUnboundedRejected() {
            String src = "A := [| k |\n       | 1 |];\nquery { τ k DESC (A) };";
            assertThatExceptionOfType(BoundednessException.class)
                    .isThrownBy(() -> planWithBoundedness(src, unbounded("A")))
                    .withMessageContaining("materialise");
        }

        @Test
        @DisplayName("λ rescues an unbounded input — planning succeeds")
        void limitRescuesUnbounded() {
            String src = "A := [| k |\n       | 1 |];\nquery { λ 1 (A) };";
            PhysicalNode plan = planWithBoundedness(src, unbounded("A"));
            assertThat(plan).isNode(PhysicalNode.Limit.class);
        }

        @Test
        @DisplayName("all-bounded leaves: no rejection, build side unchanged")
        void allBoundedIsNoOp() {
            assertThat(assertThat(planWithBoundedness(JOIN, BoundednessSource.ALL_BOUNDED)).asNode(PhysicalNode.Join.class).buildSide())
                    .isEqualTo(BuildSide.RIGHT);   // equal cost → historical right tie-break
        }
    }

    // ── AS-OF join (ADR-0014) ─────────────────────────────────────────────────

    @Nested
    @DisplayName("AS-OF join")
    class AsOf {

        private static final String TQ =
                "Trades := [| sym | t  |\n       | A | 10 |];\n" +
                "Quotes := [| qsym | qt |\n       | A | 5  |];\n";

        @Test
        @DisplayName("backward (>=) plans to a PhysicalNode.AsOfJoin with decomposed keys")
        void backwardDecomposes() {
            PhysicalNode plan = planFirstQuery(TQ +
                    "query { Trades ASOF Trades.sym = Quotes.qsym ∧ Trades.t >= Quotes.qt Quotes };");
            PhysicalNode.AsOfJoin a = assertThat(plan).asNode(PhysicalNode.AsOfJoin.class);
            assertThat(a.backward()).isTrue();
            assertThat(a.strict()).isFalse();
            assertThat(a.partitionKeys().left()).containsExactly(0);    // Trades.sym
            assertThat(a.partitionKeys().right()).containsExactly(0);   // Quotes.qsym
            assertThat(a.leftMatchIndex()).isEqualTo(1);   // Trades.t
            assertThat(a.rightMatchIndex()).isEqualTo(1);  // Quotes.qt
        }

        @Test
        @DisplayName("forward (<=), strict, and reversed-operand orientation are decoded")
        void forwardStrictReversed() {
            // Reversed operand order (Quotes.qt > Trades.t) is normalised to a forward strict probe.
            PhysicalNode plan = planFirstQuery(TQ +
                    "query { Trades ASOF Trades.sym = Quotes.qsym ∧ Quotes.qt > Trades.t Quotes };");
            PhysicalNode.AsOfJoin a = (PhysicalNode.AsOfJoin) plan;
            assertThat(a.backward()).isFalse();
            assertThat(a.strict()).isTrue();
        }

        @Test
        @DisplayName("AS-OF preserves the left input's delivered ordering")
        void preservesLeftOrdering() {
            PhysicalNode plan = planFirstQuery(TQ +
                    "query { (τ t (Trades)) ASOF Trades.sym = Quotes.qsym ∧ Trades.t >= Quotes.qt Quotes };");
            assertThat(plan.deliveredOrdering().keys()).isNotEmpty();
        }

        /** Two Postgres tables on one connection — enables the LATERAL pushdown (#207). */
        private static final String TQ_JDBC =
                "connection db from database { url: \"jdbc:postgresql://h/db\" };\n" +
                "source Trades from db { table: \"trades\", " +
                "schema: { sym: STRING, t: TIMESTAMP, px: NUMBER } };\n" +
                "source Quotes from db { table: \"quotes\", " +
                "schema: { qsym: STRING, qt: TIMESTAMP, bid: NUMBER } };\n";

        @Test
        @DisplayName("a Postgres-backed AS-OF folds into a PushedScan with a LATERAL lookup (ADR-0014 slice 5)")
        void jdbcAsOfPushedDown() {
            PhysicalNode plan = planPushed(TQ_JDBC +
                    "query { Trades ASOF Trades.sym = Quotes.qsym ∧ Trades.t >= Quotes.qt Quotes };");
            assertThat(plan).isNode(PhysicalNode.PushedScan.class);
            assertThat(((PhysicalNode.PushedScan) plan).nativeQuery())
                    .contains("LEFT JOIN LATERAL").contains("ORDER BY \"Quotes\".\"qt\" DESC LIMIT 1");
        }

        @Test
        @DisplayName("an H2 (GENERIC) AS-OF is not pushed — it runs as an in-engine PhysicalNode.AsOfJoin")
        void h2AsOfStaysInEngine() {
            String h2 =
                    "connection db from database { url: \"jdbc:h2:mem:x\" };\n" +
                    "source Trades from db { table: \"trades\", " +
                    "schema: { sym: STRING, t: TIMESTAMP, px: NUMBER } };\n" +
                    "source Quotes from db { table: \"quotes\", " +
                    "schema: { qsym: STRING, qt: TIMESTAMP, bid: NUMBER } };\n";
            PhysicalNode plan = planPushed(h2 +
                    "query { Trades ASOF Trades.sym = Quotes.qsym ∧ Trades.t >= Quotes.qt Quotes };");
            assertThat(plan).isNode(PhysicalNode.AsOfJoin.class);
        }
    }

    // ── Interval join (ADR-0015) ──────────────────────────────────────────────

    @Nested
    @DisplayName("Interval join")
    class IntervalJoin {

        private static final String EBQ =
                "Events   := [| name | estart | eend |\n       | \"A\" | 1 | 5 |];\n" +
                "Bookings := [| bid  | bstart | bend |\n       | 1   | 3 | 7 |];\n";

        @Test
        @DisplayName("IJOIN plans to a PhysicalNode.IntervalJoin")
        void plansToPhysicalIntervalJoin() {
            PhysicalNode plan = planFirstQuery(EBQ +
                    "query { Events IJOIN INTERSECTS (Events.estart, Events.eend, Bookings.bstart, Bookings.bend) Bookings };");
            PhysicalNode.IntervalJoin ij = assertThat(plan).asNode(PhysicalNode.IntervalJoin.class);
            assertThat(ij.relation()).isEqualTo(com.darkcollective.relix.ast.AllenRelation.INTERSECTS);
        }

        @Test
        @DisplayName("column indices are resolved to the correct positions in their schemas")
        void resolveColumnIndicesCorrectly() {
            // Events schema: name(0), estart(1), eend(2)
            // Bookings schema: bid(0), bstart(1), bend(2)
            PhysicalNode plan = planFirstQuery(EBQ +
                    "query { Events IJOIN INTERSECTS (Events.estart, Events.eend, Bookings.bstart, Bookings.bend) Bookings };");
            PhysicalNode.IntervalJoin ij = (PhysicalNode.IntervalJoin) plan;
            assertThat(ij.leftStartIdx()).isEqualTo(1);   // estart
            assertThat(ij.leftEndIdx()).isEqualTo(2);     // eend
            assertThat(ij.rightStartIdx()).isEqualTo(1);  // bstart
            assertThat(ij.rightEndIdx()).isEqualTo(2);    // bend
        }

        @Test
        @DisplayName("plain IJOIN (neither side sorted) uses the plane-sweep variant")
        void neitherSortedUsesSweep() {
            PhysicalNode plan = planFirstQuery(EBQ +
                    "query { Events IJOIN INTERSECTS (Events.estart, Events.eend, Bookings.bstart, Bookings.bend) Bookings };");
            assertThat(((PhysicalNode.IntervalJoin) plan).merge()).isFalse();
        }

        @Test
        @DisplayName("both sides start-sorted with an overlap relation selects the merge variant")
        void bothSortedSelectsMerge() {
            PhysicalNode plan = planFirstQuery(EBQ +
                    "query { (τ estart (Events)) IJOIN INTERSECTS " +
                    "(Events.estart, Events.eend, Bookings.bstart, Bookings.bend) (τ bstart (Bookings)) };");
            assertThat(((PhysicalNode.IntervalJoin) plan).merge()).isTrue();
        }

        @Test
        @DisplayName("only one side sorted keeps the plane-sweep variant (no inserted Sort)")
        void oneSortedStaysSweep() {
            PhysicalNode plan = planFirstQuery(EBQ +
                    "query { (τ estart (Events)) IJOIN INTERSECTS " +
                    "(Events.estart, Events.eend, Bookings.bstart, Bookings.bend) Bookings };");
            assertThat(((PhysicalNode.IntervalJoin) plan).merge()).isFalse();
        }

        @Test
        @DisplayName("both sides sorted but a band relation (PRECEDES) keeps the sorted-band variant")
        void bandRelationNeverMerges() {
            PhysicalNode plan = planFirstQuery(EBQ +
                    "query { (τ estart (Events)) IJOIN PRECEDES " +
                    "(Events.estart, Events.eend, Bookings.bstart, Bookings.bend) (τ bstart (Bookings)) };");
            assertThat(((PhysicalNode.IntervalJoin) plan).merge()).isFalse();
        }

        /** Two JDBC interval tables on one connection — enables endpoint-predicate pushdown (#207). */
        private static final String EB_JDBC =
                "connection db from database { url: \"jdbc:h2:mem:x\" };\n" +
                "source Events from db { table: \"events\", " +
                "schema: { name: STRING, estart: TIMESTAMP, eend: TIMESTAMP } };\n" +
                "source Bookings from db { table: \"bookings\", " +
                "schema: { bid: NUMBER, bstart: TIMESTAMP, bend: TIMESTAMP } };\n";

        @Test
        @DisplayName("a JDBC-backed IJOIN folds into a PushedScan with the endpoint ON predicate (ADR-0014 slice 5)")
        void jdbcIntervalPushedDown() {
            PhysicalNode plan = planPushed(EB_JDBC +
                    "query { Events IJOIN OVERLAPS " +
                    "(Events.estart, Events.eend, Bookings.bstart, Bookings.bend) Bookings };");
            assertThat(plan).isNode(PhysicalNode.PushedScan.class);
            assertThat(((PhysicalNode.PushedScan) plan).nativeQuery())
                    .contains("JOIN bookings Bookings ON (Events.estart < Bookings.bstart");
        }
    }

    /** Plans the first query with pushdown enabled (sources + connections threaded in). */
    private static PhysicalNode planPushed(String src) {
        SemanticModel m = model(src);
        RelNode logical = ((ExpressionQueryTarget) m.rootQueries().get(0).target()).expression();
        return new Planner(m.symbolTable(), m.nodeSchemas(),
                m.statistics(), m.sources(), m.connections(), QueryEventListener.NONE,
                BoundednessSource.ALL_BOUNDED, m.functions()).plan(logical);
    }

    // =========================================================================
    // The planner's own invariants
    // =========================================================================

    /**
     * What the planner does when the symbol table cannot answer a name.
     *
     * <p>These are internal assertions: analysis has already resolved every reference, so
     * reaching one means the model and the tree disagree. They are worth pinning anyway
     * because the message is the whole diagnosis — the planner is past the point where a
     * {@code SemanticError} with a source position is available, so the exception text is
     * all a maintainer gets. Each is reached by planning a tree against a symbol table
     * that never knew the name, which is the shape a hand-built AST takes.
     */
    @Nested
    @DisplayName("unresolvable names at plan time")
    class UnresolvableNames {

        private static Planner emptyPlanner() {
            return new Planner(new InMemorySymbolTable(), SchemaAnnotations.empty());
        }

        @Test
        @DisplayName("a relation the symbol table does not hold names itself in the failure")
        void unknownRelation() {
            assertThatThrownBy(() -> emptyPlanner().plan(rel("Ghost")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unknown relation")
                    .hasMessageContaining("Ghost");
        }

        @Test
        @DisplayName("an unknown TVF reports its name and its argument count")
        void unknownTableValuedFunction() {
            // The arity is in the message because a TVF is resolved by name *and* arity:
            // "unknown" may mean the name is absent or that no overload takes this many.
            assertThatThrownBy(() -> emptyPlanner().plan(
                    tvf("ordersFor",num("1"))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ordersFor")
                    .hasMessageContaining("1 argument");
        }

        @Test
        @DisplayName("an unknown TVF under a LATERAL says so, and says it is the lateral one")
        void unknownLateralFunction() {
            // The left input has to resolve, or the relation check fires first and the
            // lateral arm is never reached — which is exactly what a Ghost left does.
            var schema = new Schema(List.of(new ColumnDefinition("k", ScalarType.NUMBER)));
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("A", schema));
            var input = rel("A");
            var lateral = lateral(input, "explode",num("1"));
            // The node also has to carry a schema annotation, which the planner demands
            // before it looks the function up at all.
            var planner = new Planner(table,
                    new SchemaAnnotations(Map.of(input, schema, lateral, schema)));

            assertThatThrownBy(() -> planner.plan(lateral))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("explode")
                    .hasMessageContaining("LATERAL");
        }
    }
}

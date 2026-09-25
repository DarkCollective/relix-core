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
package com.darkcollective.relix.plan.internal;

import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SortDirection;
import com.darkcollective.relix.ast.SortSpecification;
import com.darkcollective.relix.ast.Ordering;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.semantic.CatalogProvider;
import com.darkcollective.relix.semantic.SemanticFixtures;
import com.darkcollective.relix.semantic.internal.SemanticResult;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.StructType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import com.darkcollective.relix.function.FunctionContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit tests for {@link SqlPushdownPlanner}, exercising each fold rule in
 * isolation by constructing the planner from a {@link SemanticModel} and calling
 * {@link SqlPushdownPlanner#tryPush}/{@link SqlPushdownPlanner#tryPushOrdered}
 * straight on the logical tree — no cost model, join planning or
 * view-inlining in the way.
 *
 * <p>The integration counterpart lives in {@code PlannerTest.Pushdown} (which
 * drives the same logic through the full {@link Planner}); these tests pin down
 * the individual {@code WHERE}/select-list/{@code GROUP BY}/{@code ORDER BY}/
 * {@code LIMIT}/join/{@code HAVING}/dialect fold paths and the fallbacks so a
 * regression in one of them is diagnosed without navigating the integration
 * suite.
 */
@DisplayName("SqlPushdownPlanner — per-rule SQL folding")
final class SqlPushdownPlannerTest {

    /** A connection + connection-table source with a declared schema (no catalog needed). */
    private static final String ORDERS =
            "connection db from database { url: \"jdbc:h2:mem:x\" };\n" +
            "source Orders from db { table: \"orders\", schema: { id: NUMBER, amount: NUMBER } };\n";

    /** A table with a temporal column, for the clock-substitution cases. */
    private static final String ORDERS_TS =
            "connection db from database { url: \"jdbc:h2:mem:x\" };\n" +
            "source Events from db { table: \"events\", schema: { id: NUMBER, at: TIMESTAMP } };\n";

    /** A second connection + table on the same database for join folding. */
    private static final String CUSTOMERS_SAME_DB =
            "source Customers from db { table: \"customers\", schema: { id: NUMBER, name: STRING } };\n";

    /** Builds the planner from a model and returns the pushed scan (or empty) for its first query. */
    private static Optional<PhysicalNode.PushedScan> push(String src) {
        SemanticModel model = model(src);
        RelNode logical =
                ((ExpressionQueryTarget) model.rootQueries().get(0).target()).expression();
        return new SqlPushdownPlanner(model.nodeSchemas(), model.sources(), model.connections(), model.functions())
                .tryPush(logical);
    }

    /** Builds the planner and pushes {@code keys} as an injected {@code ORDER BY}. */
    private static Optional<PhysicalNode.PushedScan> pushOrdered(String src, List<SortSpecification> keys) {
        SemanticModel model = model(src);
        RelNode logical =
                ((ExpressionQueryTarget) model.rootQueries().get(0).target()).expression();
        return new SqlPushdownPlanner(model.nodeSchemas(), model.sources(), model.connections(), model.functions())
                .tryPushOrdered(logical, keys);
    }

    /** Asserts a scan was produced and returns it. */
    private static PhysicalNode.PushedScan scanOf(Optional<PhysicalNode.PushedScan> pushed) {
        assertThat(pushed).isPresent();
        return pushed.get();
    }

    // ── individual folds ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("single-operator folds")
    class SingleFold {

        @Test
        @DisplayName("base: a bare connection table → SELECT of its columns FROM the table")
        void baseTable() {
            PhysicalNode.PushedScan s = scanOf(push(ORDERS + "query { Orders };"));
            assertThat(s.connection()).isEqualTo("db");
            assertThat(s.nativeQuery()).isEqualTo("SELECT id, amount FROM orders");
            assertThat(s.deliveredOrdering()).isEqualTo(Ordering.none());
        }

        @Test
        @DisplayName("σ folds into WHERE")
        void selectionToWhere() {
            assertThat(scanOf(push(ORDERS + "query { σ amount > 0 (Orders) };")).nativeQuery())
                    .isEqualTo("SELECT id, amount FROM orders WHERE (amount > 0)");
        }

        @Test
        @DisplayName("π folds into the select list")
        void projectionToSelectList() {
            assertThat(scanOf(push(ORDERS + "query { π amount (Orders) };")).nativeQuery())
                    .isEqualTo("SELECT amount FROM orders");
        }

        @Test
        @DisplayName("grouped γ folds into select list + GROUP BY")
        void groupedAggregationToGroupBy() {
            assertThat(scanOf(push(ORDERS + "query { γ id, SUM(amount) → total (Orders) };")).nativeQuery())
                    .isEqualTo("SELECT id, SUM(amount) FROM orders GROUP BY id");
        }

        @Test
        @DisplayName("COUNT(*) folds to COUNT(1) — the constant argument renders as a literal")
        void countStarPushesDown() {
            // COUNT(*) parses to COUNT(1), and aggregateSql renders the argument as
            // a general expression, so the row count reaches the database rather
            // than falling back to an in-engine scan-and-count.
            assertThat(scanOf(push(ORDERS + "query { γ id, COUNT(*) → n (Orders) };")).nativeQuery())
                    .isEqualTo("SELECT id, COUNT(1) FROM orders GROUP BY id");
        }

        @Test
        @DisplayName("scalar γ (no grouping keys) folds with no GROUP BY clause")
        void scalarAggregationNoGroupBy() {
            assertThat(scanOf(push(ORDERS + "query { γ SUM(amount) → total (Orders) };")).nativeQuery())
                    .isEqualTo("SELECT SUM(amount) FROM orders");
        }

        @Test
        @DisplayName("τ folds into ORDER BY (default ASC, explicit DESC) and advertises the order")
        void sortToOrderBy() {
            PhysicalNode.PushedScan s = scanOf(push(
                    ORDERS + "query { τ amount DESC, id ASC (Orders) };"));
            assertThat(s.nativeQuery()).isEqualTo("SELECT id, amount FROM orders ORDER BY (amount IS NULL) ASC, amount DESC, (id IS NULL) ASC, id ASC");
            assertThat(s.deliveredOrdering()).isEqualTo(Ordering.of(List.of(
                    desc("amount"),
                    asc("id"))));
        }

        @Test
        @DisplayName("λ folds into LIMIT, and an offset into LIMIT … OFFSET")
        void limitToLimit() {
            assertThat(scanOf(push(ORDERS + "query { λ 5 (Orders) };")).nativeQuery())
                    .isEqualTo("SELECT id, amount FROM orders LIMIT 5");
            assertThat(scanOf(push(ORDERS + "query { λ 5, 10 (Orders) };")).nativeQuery())
                    .isEqualTo("SELECT id, amount FROM orders LIMIT 10 OFFSET 5");
        }
    }

    @Nested
    @DisplayName("stacked folds keep canonical SQL clause order")
    class StackedFold {

        @Test
        @DisplayName("σ + π + λ fold as WHERE … (select list) … LIMIT")
        void selectionProjectionLimit() {
            assertThat(scanOf(push(
                    ORDERS + "query { λ 5 (π amount (σ amount > 0 (Orders))) };")).nativeQuery())
                    .isEqualTo("SELECT amount FROM orders WHERE (amount > 0) LIMIT 5");
        }

        @Test
        @DisplayName("σ + τ + λ fold as WHERE … ORDER BY … LIMIT (top-N)")
        void filterSortLimit() {
            assertThat(scanOf(push(
                    ORDERS + "query { λ 3 (τ amount DESC (σ amount > 0 (Orders))) };")).nativeQuery())
                    .isEqualTo("SELECT id, amount FROM orders WHERE (amount > 0) ORDER BY (amount IS NULL) ASC, amount DESC LIMIT 3");
        }
    }

    // ── column-pruning π (the shape PROJ-004 inserts) ───────────────────────────

    @Nested
    @DisplayName("a column-pruning π does not fix the select list (PROJ-004)")
    class PruningProjection {

        // The optimizer's PROJ-004 inserts `π <read columns> (Table)` directly above a
        // base relation. If that π counted as "the select list is now fixed", every one
        // of these queries would stop pushing altogether — a narrower SELECT traded for
        // no SELECT at all. A pruning π names only real table columns, so it does not.

        @Test
        @DisplayName("a σ above it still becomes a WHERE")
        void selectionFoldsAbovePruning() {
            assertThat(scanOf(push(
                    ORDERS + "query { σ amount > 0 (π amount (Orders)) };")).nativeQuery())
                    .isEqualTo("SELECT amount FROM orders WHERE (amount > 0)");
        }

        @Test
        @DisplayName("a γ above it still becomes a GROUP BY")
        void aggregationFoldsAbovePruning() {
            assertThat(scanOf(push(
                    ORDERS + "query { γ id, SUM(amount) → total (π id, amount (Orders)) };"))
                    .nativeQuery())
                    .isEqualTo("SELECT id, SUM(amount) FROM orders GROUP BY id");
        }

        @Test
        @DisplayName("a τ above it still becomes an ORDER BY")
        void sortFoldsAbovePruning() {
            assertThat(scanOf(push(
                    ORDERS + "query { τ amount DESC (π amount (Orders)) };")).nativeQuery())
                    .isEqualTo("SELECT amount FROM orders ORDER BY (amount IS NULL) ASC, amount DESC");
        }

        @Test
        @DisplayName("an aliased π is not pruning — it fixes the select list and blocks the σ")
        void aliasedProjectionStillBlocks() {
            assertThat(push(ORDERS + "query { σ paid > 0 (π amount → paid (Orders)) };"))
                    .isEmpty();
        }
    }

    // ── δ → SELECT DISTINCT (#540) ──────────────────────────────────────────────

    @Nested
    @DisplayName("δ folds into SELECT DISTINCT")
    class DistinctFold {

        @Test
        @DisplayName("δ over a bare table → SELECT DISTINCT of its columns")
        void distinctOverBaseTable() {
            assertThat(scanOf(push(ORDERS + "query { δ (Orders) };")).nativeQuery())
                    .isEqualTo("SELECT DISTINCT id, amount FROM orders");
        }

        @Test
        @DisplayName("δ deduplicates the *projected* rows — it folds after the select list")
        void distinctOverProjection() {
            assertThat(scanOf(push(ORDERS + "query { δ (π amount (Orders)) };")).nativeQuery())
                    .isEqualTo("SELECT DISTINCT amount FROM orders");
        }

        @Test
        @DisplayName("δ over a computed π still folds (DISTINCT applies to the expression)")
        void distinctOverComputedProjection() {
            assertThat(scanOf(push(ORDERS + "query { δ (π amount * 2 → doubled (Orders)) };"))
                    .nativeQuery())
                    .isEqualTo("SELECT DISTINCT (amount * 2) FROM orders");
        }

        @Test
        @DisplayName("a σ above the δ becomes a WHERE — filtering commutes with dedup")
        void selectionAboveDistinct() {
            assertThat(scanOf(push(ORDERS + "query { σ amount > 0 (δ (Orders)) };")).nativeQuery())
                    .isEqualTo("SELECT DISTINCT id, amount FROM orders WHERE (amount > 0)");
        }

        @Test
        @DisplayName("τ and λ above the δ apply to the deduplicated result")
        void sortAndLimitAboveDistinct() {
            assertThat(scanOf(push(
                    ORDERS + "query { λ 5 (τ amount DESC (δ (Orders))) };")).nativeQuery())
                    .isEqualTo("SELECT DISTINCT id, amount FROM orders ORDER BY (amount IS NULL) ASC, amount DESC LIMIT 5");
        }

        @Test
        @DisplayName("δ(δ …) renders one DISTINCT — the fold is idempotent")
        void stackedDistinctsRenderOnce() {
            assertThat(scanOf(push(ORDERS + "query { δ (δ (Orders)) };")).nativeQuery())
                    .isEqualTo("SELECT DISTINCT id, amount FROM orders");
        }

        @Test
        @DisplayName("δ over a GROUP BY is not rendered — γ already emits one row per key")
        void distinctOverGroupByNotPushed() {
            // DIST-001 removes this δ before planning; if one survives, the renderer bails
            // rather than emit `SELECT DISTINCT … GROUP BY …`. The planner then pushes the
            // γ alone and keeps an in-engine Distinct above it.
            assertThat(push(ORDERS + "query { δ (γ id, SUM(amount) → total (Orders)) };"))
                    .isEmpty();
        }

        @Test
        @DisplayName("a δ scan advertises no ordering to a merge join")
        void distinctScanIsNotPushedOrdered() {
            assertThat(pushOrdered(ORDERS + "query { δ (Orders) };",
                    List.of(asc("amount"))))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("same-connection inner-join fold")
    class JoinFold {

        @Test
        @DisplayName("two bare tables on one connection fold into a single SELECT … JOIN … ON")
        void joinFold() {
            PhysicalNode.PushedScan s = scanOf(push(
                    ORDERS + CUSTOMERS_SAME_DB
                    + "query { Orders ⨝ Orders.id = Customers.id Customers };"));
            assertThat(s.connection()).isEqualTo("db");
            assertThat(s.nativeQuery()).contains("FROM orders").contains("JOIN customers")
                    .contains("ON (").contains(".id = ");
        }

        @Test
        @DisplayName("σ on top of a pushed join folds into the join's WHERE")
        void selectionOnJoinFold() {
            PhysicalNode.PushedScan s = scanOf(push(
                    ORDERS + CUSTOMERS_SAME_DB
                    + "query { σ amount > 0 (Orders ⨝ Orders.id = Customers.id Customers) };"));
            // the join renderer qualifies a column to its owning table alias
            assertThat(s.nativeQuery()).contains("JOIN customers").contains("WHERE (Orders.amount > 0)");
        }

        @Test
        @DisplayName("a qualified collided column above the join renders to its owning table alias (#454)")
        void qualifiedProjectionOverJoinFold() {
            // Orders(id, amount) and Customers(id, name) collide on `id`. A qualified
            // π Customers.id renders to Customers.id — the correct side, not a first-match.
            PhysicalNode.PushedScan s = scanOf(push(
                    ORDERS + CUSTOMERS_SAME_DB
                    + "query { π Customers.id → cid (Orders ⨝ Orders.id = Customers.id Customers) };"));
            assertThat(s.nativeQuery()).contains("JOIN customers")
                    .contains("SELECT Customers.id");
        }
    }

    @Nested
    @DisplayName("What composes above a γ — HAVING, ORDER BY, LIMIT")
    class AboveAggregation {


        @Test
        @DisplayName("σ above γ becomes HAVING, over an aggregate")
        void selectionOverAggregateBecomesHaving() {
            assertThat(scanOf(push(ORDERS
                    + "query { σ total ≥ 100 (γ id, SUM(amount) → total (Orders)) };")).nativeQuery())
                    .isEqualTo("SELECT id, SUM(amount) FROM orders GROUP BY id "
                            + "HAVING (SUM(amount) >= 100)");
        }

        /**
         * The aggregate expression is repeated rather than named by its alias, because
         * standard SQL does not let a {@code HAVING} clause reference a select-list
         * alias at all — MySQL does, and relying on that would make the fold a dialect
         * branch for no gain.
         */
        @Test
        @DisplayName("σ above γ becomes HAVING, over a grouping key")
        void selectionOverKeyBecomesHaving() {
            assertThat(scanOf(push(ORDERS
                    + "query { σ id = 1 (γ id, COUNT(*) → n (Orders)) };")).nativeQuery())
                    .isEqualTo("SELECT id, COUNT(1) FROM orders GROUP BY id HAVING (id = 1)");
        }

        @Test
        @DisplayName("two selections above one γ are one HAVING")
        void twoSelectionsAreOneHaving() {
            assertThat(scanOf(push(ORDERS
                    + "query { σ id = 1 (σ total ≥ 100 (γ id, SUM(amount) → total (Orders))) };"))
                    .nativeQuery())
                    .isEqualTo("SELECT id, SUM(amount) FROM orders GROUP BY id "
                            + "HAVING (SUM(amount) >= 100) AND (id = 1)");
        }

        /**
         * The ordering key is the aggregate expression, for the reason the HAVING one is
         * — and here the alias would not do even where SQL allows it. {@code ORDER BY}
         * accepts an alias only as a bare output-column reference, and NULL placement
         * puts it inside an expression.
         */
        @Test
        @DisplayName("τ above γ becomes ORDER BY over the aggregate, NULLs placed as the engine places them")
        void sortOverAggregateBecomesOrderBy() {
            PhysicalNode.PushedScan s = scanOf(push(ORDERS
                    + "query { τ total DESC, id ASC (γ id, SUM(amount) → total (Orders)) };"));
            assertThat(s.nativeQuery()).isEqualTo(
                    "SELECT id, SUM(amount) FROM orders GROUP BY id "
                    + "ORDER BY (SUM(amount) IS NULL) ASC, SUM(amount) DESC, (id IS NULL) ASC, id ASC");
        }

        @Test
        @DisplayName("λ above that becomes the LIMIT — group, rank, take the top few, in one statement")
        void limitOverSortOverAggregate() {
            assertThat(scanOf(push(ORDERS
                    + "query { λ 3 (τ total DESC, id ASC (γ id, SUM(amount) → total (Orders))) };"))
                    .nativeQuery())
                    .isEqualTo("SELECT id, SUM(amount) FROM orders GROUP BY id "
                            + "ORDER BY (SUM(amount) IS NULL) ASC, SUM(amount) DESC, "
                            + "(id IS NULL) ASC, id ASC LIMIT 3");
        }

        @Test
        @DisplayName("all three at once, with a WHERE below the γ")
        void theWholeReport() {
            assertThat(scanOf(push(ORDERS
                    + "query { λ 3 (τ total DESC (σ total > 10 "
                    + "(γ id, SUM(amount) → total (σ amount ≠ NULL (Orders))))) };"))
                    .nativeQuery())
                    .isEqualTo("SELECT id, SUM(amount) FROM orders WHERE amount IS NOT NULL "
                            + "GROUP BY id HAVING (SUM(amount) > 10) "
                            + "ORDER BY (SUM(amount) IS NULL) ASC, SUM(amount) DESC LIMIT 3");
        }

        @Test
        @DisplayName("a reference the γ does not output declines the whole fold")
        void unknownOutputColumnDeclines() {
            // `amount` is an input column, not one of the γ's outputs, so nothing above
            // the aggregation can name it — and a renderer that resolved it against the
            // table would silently produce a HAVING over an ungrouped column.
            assertThat(push(ORDERS
                    + "query { σ amount > 1 (γ id, SUM(amount) → total (Orders)) };")).isEmpty();
        }

        @Test
        @DisplayName("a π above a γ still declines — it would have to rewrite the select list")
        void projectionAboveAggregationDeclines() {
            assertThat(push(ORDERS
                    + "query { π id (γ id, SUM(amount) → total (Orders)) };")).isEmpty();
        }

        @Test
        @DisplayName("a γ above a γ declines")
        void aggregationAboveAggregationDeclines() {
            assertThat(push(ORDERS
                    + "query { γ id, COUNT(*) → groups (γ id, SUM(amount) → total (Orders)) };"))
                    .isEmpty();
        }
    }

    /**
     * SQL Server, whose row limiting is legal only after an {@code ORDER BY} — the one
     * dialect where what folds, rather than only how it is written, differs.
     */
    @Nested
    @DisplayName("SQL Server's OFFSET … FETCH, which needs an ORDER BY")
    class SqlServer {

        private static final String SQLSERVER_ORDERS =
                "connection db from database { url: \"jdbc:sqlserver://h:1433\" };\n"
                + "source Orders from db { table: \"orders\", "
                + "schema: { id: NUMBER, name: STRING, amount: NUMBER } };\n";

        @Test
        @DisplayName("a limit over a sort folds, as OFFSET … FETCH after the ORDER BY")
        void limitOverSort() {
            assertThat(scanOf(push(SQLSERVER_ORDERS + "query { λ 3 (τ amount DESC (Orders)) };")).nativeQuery())
                    .isEqualTo("SELECT [id], [name], [amount] FROM [orders] ORDER BY "
                            + "CASE WHEN [amount] IS NULL THEN 1 ELSE 0 END ASC, [amount] DESC "
                            + "OFFSET 0 ROWS FETCH NEXT 3 ROWS ONLY");
            assertThat(scanOf(push(SQLSERVER_ORDERS + "query { λ 3, 2 (τ id (Orders)) };")).nativeQuery())
                    .endsWith("OFFSET 3 ROWS FETCH NEXT 2 ROWS ONLY");
        }

        @Test
        @DisplayName("a limit over nothing that orders it declines, where every other dialect folds it")
        void limitWithoutSortDeclines() {
            assertThat(push(SQLSERVER_ORDERS + "query { λ 3 (Orders) };")).isEmpty();
            assertThat(push(SQLSERVER_ORDERS + "query { λ 3 (σ amount > 0 (Orders)) };")).isEmpty();
            // What sits beneath it still folds on its own.
            assertThat(scanOf(push(SQLSERVER_ORDERS + "query { σ amount > 0 (Orders) };")).nativeQuery())
                    .isEqualTo("SELECT [id], [name], [amount] FROM [orders] WHERE ([amount] > 0)");
        }

        @Test
        @DisplayName("a string is compared under a binary collation, against a national literal")
        void stringEquality() {
            assertThat(scanOf(push(SQLSERVER_ORDERS + "query { σ name = 'ada' (Orders) };")).nativeQuery())
                    .isEqualTo("SELECT [id], [name], [amount] FROM [orders] WHERE "
                            + "(([name]) COLLATE Latin1_General_100_BIN2 = N'ada')");
        }

        @Test
        @DisplayName("a LIKE pattern's [ is bracketed so it matches itself")
        void likeBracket() {
            assertThat(scanOf(push(SQLSERVER_ORDERS + "query { σ name LIKE '[a%' (Orders) };")).nativeQuery())
                    .endsWith("WHERE (([name]) COLLATE Latin1_General_100_BIN2 LIKE N'[[]a%')");
        }
    }

    @Nested
    @DisplayName("A backend whose collation is not the engine's comparison")
    class StringCollation {

        /** A MySQL connection, whose default collation is case- and accent-insensitive. */
        private static final String MYSQL_ORDERS =
                "connection db from database { url: \"jdbc:h2:mem:x\", dialect: mysql };\n"
                + "source Orders from db { table: \"orders\", "
                + "schema: { id: NUMBER, name: STRING, amount: NUMBER } };\n";

        /** The same, declaring that its string columns really do compare exactly. */
        private static final String MYSQL_ORDERS_EXACT =
                "connection db from database { url: \"jdbc:h2:mem:x\", dialect: mysql, "
                + "collation: exact };\n"
                + "source Orders from db { table: \"orders\", "
                + "schema: { id: NUMBER, name: STRING, amount: NUMBER } };\n";

        /**
         * The {@code CONVERT} is not decoration. A collation belongs to a character set,
         * so {@code COLLATE utf8mb4_0900_bin} applied to a {@code latin1} column is a
         * rejected query rather than a wrong answer; transcoding first removes the
         * question, and was checked against a server.
         */
        @Test
        @DisplayName("a string comparison is collated so the backend compares as the engine does")
        void stringEqualityIsCollated() {
            assertThat(scanOf(push(MYSQL_ORDERS + "query { σ name = 'ada' (Orders) };")).nativeQuery())
                    .isEqualTo("SELECT `id`, `name`, `amount` FROM `orders` WHERE "
                            + "(CONVERT(`name` USING utf8mb4) COLLATE utf8mb4_0900_bin = 'ada')");
        }

        @Test
        @DisplayName("a non-string comparison is untouched — nothing to make exact")
        void numberComparisonIsPlain() {
            assertThat(scanOf(push(MYSQL_ORDERS + "query { σ amount > 100 (Orders) };")).nativeQuery())
                    .isEqualTo("SELECT `id`, `name`, `amount` FROM `orders` WHERE (`amount` > 100)");
        }

        @Test
        @DisplayName("LIKE and IN are collated too — both compare for equality")
        void patternAndMembership() {
            assertThat(scanOf(push(MYSQL_ORDERS + "query { σ name LIKE 'a%' (Orders) };")).nativeQuery())
                    .contains("CONVERT(`name` USING utf8mb4) COLLATE utf8mb4_0900_bin LIKE 'a%'");
            assertThat(scanOf(push(MYSQL_ORDERS + "query { σ name ∈ {'a', 'b'} (Orders) };")).nativeQuery())
                    .contains("CONVERT(`name` USING utf8mb4) COLLATE utf8mb4_0900_bin IN ('a', 'b')");
        }

        /**
         * The grouping key is selected as the same expression it is grouped by, which is
         * both what makes the deduplication exact and what MySQL's {@code
         * ONLY_FULL_GROUP_BY} requires of a grouped select list.
         */
        @Test
        @DisplayName("a GROUP BY selects the same collated expression it groups by")
        void groupingIsCollated() {
            assertThat(scanOf(push(MYSQL_ORDERS
                    + "query { γ name, COUNT(*) → n (Orders) };")).nativeQuery())
                    .isEqualTo("SELECT CONVERT(`name` USING utf8mb4) COLLATE utf8mb4_0900_bin, "
                            + "COUNT(1) FROM `orders` "
                            + "GROUP BY CONVERT(`name` USING utf8mb4) COLLATE utf8mb4_0900_bin");
        }

        @Test
        @DisplayName("SELECT DISTINCT collates its select list — it has no clause of its own")
        void distinctIsCollated() {
            assertThat(scanOf(push(MYSQL_ORDERS + "query { δ (π name (Orders)) };")).nativeQuery())
                    .isEqualTo("SELECT DISTINCT CONVERT(`name` USING utf8mb4) "
                            + "COLLATE utf8mb4_0900_bin FROM `orders`");
        }

        /**
         * Ordering is collated like the rest, and can be because the engine orders
         * strings by code point — which is the order a binary collation gives. It was
         * declined while the engine ordered by UTF-16 code unit, when no collation could
         * have agreed with it.
         */
        @Test
        @DisplayName("ordering a string is collated too, so it still folds")
        void orderingIsCollated() {
            assertThat(scanOf(push(MYSQL_ORDERS + "query { τ name ASC (Orders) };")).nativeQuery())
                    .contains("ORDER BY (CONVERT(`name` USING utf8mb4) COLLATE utf8mb4_0900_bin "
                            + "IS NULL) ASC, CONVERT(`name` USING utf8mb4) COLLATE utf8mb4_0900_bin ASC");
            assertThat(scanOf(push(MYSQL_ORDERS + "query { σ name > 'B' (Orders) };")).nativeQuery())
                    .contains("(CONVERT(`name` USING utf8mb4) COLLATE utf8mb4_0900_bin > 'B')");
            // MIN and MAX order their argument; COUNT does not, and is left plain.
            assertThat(scanOf(push(MYSQL_ORDERS + "query { γ MIN(name) → lo (Orders) };")).nativeQuery())
                    .isEqualTo("SELECT MIN(CONVERT(`name` USING utf8mb4) COLLATE utf8mb4_0900_bin) "
                            + "FROM `orders`");
            assertThat(scanOf(push(MYSQL_ORDERS + "query { γ COUNT(name) → n (Orders) };")).nativeQuery())
                    .isEqualTo("SELECT COUNT(`name`) FROM `orders`");
        }

        /**
         * The one ordering shape that still declines. MySQL matches an {@code ORDER BY}
         * term against the {@code GROUP BY} expression syntactically, and NULL placement
         * wraps the key — so a collated grouping key is no longer the expression MySQL is
         * looking for, and {@code ONLY_FULL_GROUP_BY} rejects the query. It accepts the
         * same shape over a bare column, which is why this bites only where the key had
         * to be wrapped.
         */
        @Test
        @DisplayName("ordering by a grouping key that had to be collated declines")
        void orderingByCollatedGroupingKeyDeclines() {
            assertThat(push(MYSQL_ORDERS
                    + "query { τ name ASC (γ name, COUNT(*) → n (Orders)) };")).isEmpty();
            // The same query on a connection that needs no collating folds.
            assertThat(push(MYSQL_ORDERS_EXACT
                    + "query { τ name ASC (γ name, COUNT(*) → n (Orders)) };")).isPresent();
            // And ordering a grouped result by its aggregate is unaffected either way.
            assertThat(push(MYSQL_ORDERS
                    + "query { τ n DESC (γ name, COUNT(*) → n (Orders)) };")).isPresent();
        }

        @Test
        @DisplayName("a connection declaring collation: exact is left alone entirely")
        void declaredExactCollation() {
            assertThat(scanOf(push(MYSQL_ORDERS_EXACT
                    + "query { σ name = 'ada' (Orders) };")).nativeQuery())
                    .isEqualTo("SELECT `id`, `name`, `amount` FROM `orders` WHERE (`name` = 'ada')");
            assertThat(scanOf(push(MYSQL_ORDERS_EXACT
                    + "query { τ name ASC (Orders) };")).nativeQuery())
                    .contains("ORDER BY (`name` IS NULL) ASC, `name` ASC");
        }

        @Test
        @DisplayName("a dialect that already compares exactly renders nothing extra")
        void genericIsUntouched() {
            assertThat(scanOf(push(ORDERS + CUSTOMERS_SAME_DB
                    + "query { σ name = 'ada' (Customers) };")).nativeQuery())
                    .isEqualTo("SELECT id, name FROM customers WHERE (name = 'ada')");
        }
    }

    /**
     * Postgres, the backend that separates the two questions a collation answers.
     *
     * <p>Its default collation is <em>deterministic</em>, so it decides equality by
     * comparing bytes and {@code name = 'ada'} means there what it means here. It
     * decides <em>order</em> by the locale, and a locale sorts the way a dictionary
     * does — so {@code τ name}, {@code name > 'B'} and {@code MIN(name)} do not. That
     * was measured by running the agreement corpus against a real server, where those
     * three cases were the ones that disagreed and every equality case passed.
     *
     * <p>The consequence is that Postgres is wrapped in ordering position and left alone
     * in comparison position, which is what makes this worth a nested class of its own:
     * on MySQL the two positions have always had the same answer.
     */
    @Nested
    @DisplayName("A backend that compares strings exactly and orders them by locale")
    class PostgresStringOrdering {

        private static final String PG_ORDERS =
                "connection db from database { url: \"jdbc:h2:mem:x\", dialect: postgres };\n"
                + "source Orders from db { table: \"orders\", "
                + "schema: { id: NUMBER, name: STRING, amount: NUMBER } };\n";

        @Test
        @DisplayName("equality is left alone — it is already exact, and wrapping costs the index")
        void equalityIsUntouched() {
            assertThat(scanOf(push(PG_ORDERS + "query { σ name = 'ada' (Orders) };")).nativeQuery())
                    .isEqualTo("SELECT \"id\", \"name\", \"amount\" FROM \"orders\" "
                            + "WHERE (\"name\" = 'ada')");
            assertThat(scanOf(push(PG_ORDERS + "query { σ name LIKE 'a%' (Orders) };")).nativeQuery())
                    .contains("(\"name\" LIKE 'a%')");
            assertThat(scanOf(push(PG_ORDERS + "query { δ (π name (Orders)) };")).nativeQuery())
                    .isEqualTo("SELECT DISTINCT \"name\" FROM \"orders\"");
        }

        @Test
        @DisplayName("ordering is wrapped in the C collation, which orders by code point")
        void orderingIsCollated() {
            assertThat(scanOf(push(PG_ORDERS + "query { τ name ASC (Orders) };")).nativeQuery())
                    .contains("ORDER BY (\"name\") COLLATE \"C\" ASC NULLS LAST");
            assertThat(scanOf(push(PG_ORDERS + "query { σ name > 'B' (Orders) };")).nativeQuery())
                    .contains("((\"name\") COLLATE \"C\" > 'B')");
            assertThat(scanOf(push(PG_ORDERS + "query { γ MIN(name) → lo (Orders) };")).nativeQuery())
                    .isEqualTo("SELECT MIN((\"name\") COLLATE \"C\") FROM \"orders\"");
        }

        @Test
        @DisplayName("a non-string key is untouched in either position")
        void numbersAreUntouched() {
            assertThat(scanOf(push(PG_ORDERS + "query { τ amount ASC (Orders) };")).nativeQuery())
                    .contains("ORDER BY \"amount\" ASC NULLS LAST");
            assertThat(scanOf(push(PG_ORDERS + "query { σ amount > 100 (Orders) };")).nativeQuery())
                    .contains("(\"amount\" > 100)");
        }

        /**
         * The declaration answers both questions at once, which is right rather than a
         * shortcut: a binary collation is exact <em>and</em> code-point ordered.
         */
        @Test
        @DisplayName("a connection declaring collation: exact drops the ordering wrap too")
        void declaredExactCollation() {
            String exact = PG_ORDERS.replace("dialect: postgres",
                    "dialect: postgres, collation: exact");
            assertThat(scanOf(push(exact + "query { τ name ASC (Orders) };")).nativeQuery())
                    .contains("ORDER BY \"name\" ASC NULLS LAST");
        }
    }

    @Nested
    @DisplayName("A call that is constant for the run travels as its value")
    class StableCallSubstitution {

        private static final Instant PINNED = Instant.parse("2026-03-01T12:00:00Z");

        /** The renderer as an execution gives it one: told the run's ambient state. */
        private static Optional<PhysicalNode.PushedScan> pushAtPinnedClock(String src) {
            SemanticModel model = model(src);
            RelNode logical =
                    ((ExpressionQueryTarget) model.rootQueries().get(0).target()).expression();
            SqlPushdownPlanner renderer = new SqlPushdownPlanner(
                    model.nodeSchemas(), model.sources(), model.connections(), model.functions());
            renderer.useFunctionContext(
                    FunctionContext.of(Clock.fixed(PINNED, ZoneOffset.UTC), (a, b) -> 0));
            return renderer.tryPush(logical);
        }

        /**
         * The point of the whole thing: {@code NOW()} may not be handed to a database —
         * it would answer from its own clock — but its <em>value</em> may, and then the
         * predicate folds like any other comparison against a literal.
         */
        @Test
        @DisplayName("NOW() in a predicate becomes the run's instant, and the σ folds")
        void nowInAPredicateFolds() {
            PhysicalNode.PushedScan s = scanOf(pushAtPinnedClock(ORDERS_TS
                    + "query { σ at < NOW() (Events) };"));
            assertThat(s.nativeQuery())
                    .isEqualTo("SELECT id, at FROM events WHERE (at < '2026-03-01 12:00:00')");
            assertThat(s.nativeQuery()).doesNotContain("NOW");
        }

        @Test
        @DisplayName("and in a projection, where it used to cost the fold as well")
        void nowInAProjectionFolds() {
            assertThat(scanOf(pushAtPinnedClock(ORDERS_TS
                    + "query { π id, NOW() → t (Events) };")).nativeQuery())
                    .isEqualTo("SELECT id, '2026-03-01 12:00:00' FROM events");
        }

        @Test
        @DisplayName("each clock function renders as a literal of its own type")
        void eachClockFunction() {
            assertThat(scanOf(pushAtPinnedClock(ORDERS_TS
                    + "query { π id, CURRENT_DATE() → d (Events) };")).nativeQuery())
                    .contains("'2026-03-01'");
            assertThat(scanOf(pushAtPinnedClock(ORDERS_TS
                    + "query { π id, CURRENT_TIME() → t (Events) };")).nativeQuery())
                    .contains("'12:00'");
        }

        /**
         * A planner that was not told the run's clock substitutes nothing. That is the
         * safe direction and the reason the context is passed rather than made: an
         * instant invented here would be one the execution never agreed to, and the
         * folded half of a query would compare against a different moment from the
         * unfolded half.
         */
        @Test
        @DisplayName("a planner not told the run's clock substitutes nothing and declines")
        void withoutAContextNothingIsSubstituted() {
            assertThat(push(ORDERS_TS + "query { σ at < NOW() (Events) };")).isEmpty();
        }

        /**
         * {@code Rand} declares nothing, which is the safe default: its value may differ
         * per row, so there is no single value to send. It is the reason the middle class
         * had to be declared rather than inferred from "reads ambient state".
         */
        @Test
        @DisplayName("a per-row volatile call is not substituted — there is no one value")
        void randIsNotSubstituted() {
            assertThat(pushAtPinnedClock(ORDERS_TS + "query { σ id > Rand() (Events) };"))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("∀ → GROUP BY … HAVING (boolean-and)")
    class UniversalFold {

        @Test
        @DisplayName("keyed ∀ folds into GROUP BY … HAVING on a GENERIC connection")
        void genericHaving() {
            PhysicalNode.PushedScan s = scanOf(push(ORDERS + "query { ∀ id : amount > 0 (Orders) };"));
            assertThat(s.nativeQuery()).isEqualTo(
                    "SELECT id FROM orders GROUP BY id "
                    + "HAVING COUNT(*) = COUNT(CASE WHEN (amount > 0) THEN 1 END)");
            // output schema = the grouping keys only
            assertThat(s.schema().columns()).singleElement()
                    .satisfies(c -> assertThat(c.name()).isEqualTo("id"));
        }

        @Test
        @DisplayName("σ below a keyed ∀ folds WHERE before GROUP BY … HAVING")
        void selectionBelowUniversal() {
            assertThat(scanOf(push(
                    ORDERS + "query { ∀ id : amount > 0 (σ amount < 1000 (Orders)) };")).nativeQuery())
                    .isEqualTo("SELECT id FROM orders WHERE (amount < 1000) GROUP BY id "
                            + "HAVING COUNT(*) = COUNT(CASE WHEN (amount > 0) THEN 1 END)");
        }
    }

    @Nested
    @DisplayName("dialect rendering")
    class Dialects {

        @Test
        @DisplayName("a POSTGRES connection double-quotes every emitted identifier")
        void postgresQuoting() {
            String setup =
                    "connection db from database { url: \"jdbc:h2:mem:x\", dialect: postgres };\n" +
                    "source Orders from db { table: \"orders\", schema: { id: NUMBER, amount: NUMBER } };\n";
            assertThat(scanOf(push(setup + "query { σ amount > 0 (Orders) };")).nativeQuery())
                    .isEqualTo("SELECT \"id\", \"amount\" FROM \"orders\" WHERE (\"amount\" > 0)");
        }

        @Test
        @DisplayName("a POSTGRES ∀ uses the strict HAVING, not the native bool_and")
        void postgresBoolAnd() {
            String setup =
                    "connection db from database { url: \"jdbc:h2:mem:x\", dialect: postgres };\n" +
                    "source Orders from db { table: \"orders\", schema: { id: NUMBER, amount: NUMBER } };\n";
            assertThat(scanOf(push(setup + "query { ∀ id : amount > 0 (Orders) };")).nativeQuery())
                    .isEqualTo("SELECT \"id\" FROM \"orders\" GROUP BY \"id\" "
                            + "HAVING COUNT(*) = COUNT(CASE WHEN (\"amount\" > 0) THEN 1 END)");
        }
    }

    @Nested
    @DisplayName("window function folds (ROLLING / WINDOW RANK / WINDOW LAG)")
    class WindowFold {

        /** A JDBC-backed source with ticker, t, price columns. */
        private static final String TICKS =
                "connection db from database { url: \"jdbc:h2:mem:x\" };\n" +
                "source Ticks from db { table: \"ticks\", " +
                "schema: { ticker: STRING, t: NUMBER, price: NUMBER } };\n";

        /** A JDBC-backed source with dept, emp_id, salary columns. */
        private static final String EMPLOYEES =
                "connection db from database { url: \"jdbc:h2:mem:x\" };\n" +
                "source Employees from db { table: \"employees\", " +
                "schema: { dept: STRING, emp_id: NUMBER, salary: NUMBER } };\n";

        /**
         * AVG is the one reducer with no SQL spelling, so a window over it declines and
         * takes the whole fold with it.
         *
         * <p>It is the only aggregate that <em>divides</em>, and so has division's problem
         * exactly: a relix average is exact decimal to ten places and no backend's AVG
         * carries that scale — H2 answers {@code 152.33333333333334} where the engine
         * answers {@code 152.3333333333}. These window tests used AVG as their example
         * aggregate until it stopped folding, which is why they now read SUM.
         */
        @Test
        @DisplayName("a window over AVG declines, because AVG has no SQL spelling")
        void avgWindowDeclines() {
            assertThat(push(
                    TICKS + "query { ROLLING AVG(price) OVER 3 ROWS SORT t ASC PER ticker AS a (Ticks) };"))
                    .as("AVG divides, so no backend carries the engine's scale")
                    .isEmpty();
        }

        @Test
        @DisplayName("ROLLING SUM with bounded frame folds to AGG() OVER (PARTITION BY … ORDER BY … ROWS …)")
        void rollingBoundedFrame() {
            PhysicalNode.PushedScan s = scanOf(push(
                    TICKS + "query { ROLLING SUM(price) OVER 3 ROWS SORT t ASC PER ticker AS run3 (Ticks) };"));
            assertThat(s.nativeQuery()).isEqualTo(
                    "SELECT ticker, t, price, "
                    + "SUM(price) OVER (PARTITION BY ticker ORDER BY (t IS NULL) ASC, t ASC ROWS BETWEEN 2 PRECEDING AND CURRENT ROW) AS run3 "
                    + "FROM ticks");
        }

        @Test
        @DisplayName("ROLLING SUM with cumulative frame folds to AGG() OVER (… ROWS BETWEEN UNBOUNDED PRECEDING …)")
        void rollingCumulativeFrame() {
            PhysicalNode.PushedScan s = scanOf(push(
                    TICKS + "query { ROLLING SUM(price) OVER ALL ROWS SORT t ASC PER ticker AS run (Ticks) };"));
            assertThat(s.nativeQuery()).isEqualTo(
                    "SELECT ticker, t, price, "
                    + "SUM(price) OVER (PARTITION BY ticker ORDER BY (t IS NULL) ASC, t ASC ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS run "
                    + "FROM ticks");
        }

        @Test
        @DisplayName("WINDOW ROW_NUMBER folds to ROW_NUMBER() OVER (PARTITION BY … ORDER BY …)")
        void rankingRowNumber() {
            PhysicalNode.PushedScan s = scanOf(push(
                    EMPLOYEES + "query { WINDOW ROW_NUMBER() SORT salary DESC PER dept AS rn (Employees) };"));
            assertThat(s.nativeQuery()).isEqualTo(
                    "SELECT dept, emp_id, salary, "
                    + "ROW_NUMBER() OVER (PARTITION BY dept ORDER BY (salary IS NULL) ASC, salary DESC) AS rn "
                    + "FROM employees");
        }

        @Test
        @DisplayName("WINDOW LAG without default folds to LAG(col, n) OVER (…)")
        void lagWithoutDefault() {
            PhysicalNode.PushedScan s = scanOf(push(
                    EMPLOYEES + "query { WINDOW LAG(salary, 1) SORT salary ASC PER dept AS prev (Employees) };"));
            assertThat(s.nativeQuery()).isEqualTo(
                    "SELECT dept, emp_id, salary, "
                    + "LAG(salary, 1) OVER (PARTITION BY dept ORDER BY (salary IS NULL) ASC, salary ASC) AS prev "
                    + "FROM employees");
        }

        @Test
        @DisplayName("WINDOW LAG with default folds to LAG(col, n, default) OVER (…)")
        void lagWithDefault() {
            PhysicalNode.PushedScan s = scanOf(push(
                    EMPLOYEES + "query { WINDOW LAG(salary, 1, 0) SORT salary ASC PER dept AS prev (Employees) };"));
            assertThat(s.nativeQuery()).isEqualTo(
                    "SELECT dept, emp_id, salary, "
                    + "LAG(salary, 1, 0) OVER (PARTITION BY dept ORDER BY (salary IS NULL) ASC, salary ASC) AS prev "
                    + "FROM employees");
        }

        @Test
        @DisplayName("two stacked windows over the same base fold into two OVER items in one select-list")
        void stackedWindows() {
            PhysicalNode.PushedScan s = scanOf(push(
                    EMPLOYEES
                    + "query { WINDOW ROW_NUMBER() SORT salary DESC PER dept AS rn ("
                    + "        ROLLING SUM(salary) OVER ALL ROWS SORT salary ASC PER dept AS run_s (Employees)) };"));
            assertThat(s.nativeQuery())
                    .contains("SUM(salary) OVER (PARTITION BY dept ORDER BY (salary IS NULL) ASC, salary ASC ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS run_s")
                    .contains("ROW_NUMBER() OVER (PARTITION BY dept ORDER BY (salary IS NULL) ASC, salary DESC) AS rn");
        }

        @Test
        @DisplayName("σ above a pushed window is not pushed (WHERE cannot reference a window result)")
        void selectionAboveWindowNotPushed() {
            assertThat(push(
                    EMPLOYEES + "query { σ salary > 100 (WINDOW ROW_NUMBER() SORT salary DESC PER dept AS rn (Employees)) };"))
                    .isEmpty();
        }

        @Test
        @DisplayName("window over a LIMIT-applied scan is not pushed")
        void windowOverLimitNotPushed() {
            assertThat(push(
                    TICKS + "query { ROLLING SUM(price) OVER 2 ROWS SORT t ASC AS s (λ 10 (Ticks)) };"))
                    .isEmpty();
        }

        @Test
        @DisplayName("window on a MYSQL connection is not pushed (dialect does not support windows)")
        void mysqlFallsBack() {
            String mysqlTicks =
                    "connection db from database { url: \"jdbc:mysql://host/db\" };\n" +
                    "source Ticks from db { table: \"ticks\", " +
                    "schema: { ticker: STRING, t: NUMBER, price: NUMBER } };\n";
            assertThat(push(
                    mysqlTicks + "query { ROLLING SUM(price) OVER 2 ROWS SORT t ASC AS s (Ticks) };"))
                    .isEmpty();
        }

        @Test
        @DisplayName("pushed window carries the output schema with the appended column")
        void pushedWindowSchema() {
            PhysicalNode.PushedScan s = scanOf(push(
                    TICKS + "query { ROLLING SUM(price) OVER 3 ROWS SORT t ASC PER ticker AS run3 (Ticks) };"));
            assertThat(s.schema().columns()).hasSize(4);
            assertThat(s.schema().column("run3")).isPresent();
        }
    }

    @Nested
    @DisplayName("AS-OF join → LATERAL nearest-row lookup (ADR-0014 slice 5, #207)")
    class AsOfFold {

        /** Two Postgres tables on one connection (LATERAL is Postgres-only). */
        private static final String TRADES_QUOTES =
                "connection db from database { url: \"jdbc:postgresql://h/db\" };\n" +
                "source Trades from db { table: \"trades\", " +
                "schema: { sym: STRING, t: TIMESTAMP, px: NUMBER } };\n" +
                "source Quotes from db { table: \"quotes\", " +
                "schema: { qsym: STRING, qt: TIMESTAMP, bid: NUMBER } };\n";

        @Test
        @DisplayName("backward (>=) folds into LEFT JOIN LATERAL (… ORDER BY qt DESC LIMIT 1)")
        void backwardLeftOuter() {
            PhysicalNode.PushedScan s = scanOf(push(TRADES_QUOTES
                    + "query { Trades ASOF Trades.sym = Quotes.qsym ∧ Trades.t >= Quotes.qt Quotes };"));
            assertThat(s.connection()).isEqualTo("db");
            assertThat(s.nativeQuery()).isEqualTo(
                    "SELECT \"Trades\".\"sym\", \"Trades\".\"t\", \"Trades\".\"px\", "
                    + "\"Quotes\".\"qsym\", \"Quotes\".\"qt\", \"Quotes\".\"bid\" "
                    + "FROM \"trades\" \"Trades\" LEFT JOIN LATERAL ("
                    + "SELECT \"Quotes\".\"qsym\", \"Quotes\".\"qt\", \"Quotes\".\"bid\" FROM \"quotes\" \"Quotes\" "
                    + "WHERE ((\"Trades\".\"sym\" = \"Quotes\".\"qsym\") AND (\"Trades\".\"t\" >= \"Quotes\".\"qt\")) "
                    + "ORDER BY \"Quotes\".\"qt\" DESC LIMIT 1) \"Quotes\" ON TRUE");
        }

        @Test
        @DisplayName("forward (<=) folds with ORDER BY qt ASC")
        void forwardOrderAsc() {
            assertThat(scanOf(push(TRADES_QUOTES
                    + "query { Trades ASOF Trades.sym = Quotes.qsym ∧ Trades.t <= Quotes.qt Quotes };"))
                    .nativeQuery())
                    .contains("ORDER BY \"Quotes\".\"qt\" ASC LIMIT 1");
        }

        @Test
        @DisplayName("the inner variant folds into a plain JOIN LATERAL (unmatched probes dropped)")
        void innerVariantUsesInnerLateral() {
            // The parser produces the left-outer form from ASOF; pushing the AST built with
            // the inner flag is exercised through the planner — here we assert the default
            // (left-outer) keeps LEFT JOIN LATERAL.
            assertThat(scanOf(push(TRADES_QUOTES
                    + "query { Trades ASOF Trades.sym = Quotes.qsym ∧ Trades.t >= Quotes.qt Quotes };"))
                    .nativeQuery())
                    .contains("LEFT JOIN LATERAL");
        }

        @Test
        @DisplayName("the pushed scan output schema is left ⊕ right")
        void outputSchemaIsConcatenation() {
            PhysicalNode.PushedScan s = scanOf(push(TRADES_QUOTES
                    + "query { Trades ASOF Trades.sym = Quotes.qsym ∧ Trades.t >= Quotes.qt Quotes };"));
            assertThat(s.schema().columns()).hasSize(6);
        }

        @Test
        @DisplayName("a GENERIC (H2) connection has no LATERAL and falls back to in-engine")
        void genericFallsBack() {
            String h2 =
                    "connection db from database { url: \"jdbc:h2:mem:x\" };\n" +
                    "source Trades from db { table: \"trades\", " +
                    "schema: { sym: STRING, t: TIMESTAMP, px: NUMBER } };\n" +
                    "source Quotes from db { table: \"quotes\", " +
                    "schema: { qsym: STRING, qt: TIMESTAMP, bid: NUMBER } };\n";
            assertThat(push(h2
                    + "query { Trades ASOF Trades.sym = Quotes.qsym ∧ Trades.t >= Quotes.qt Quotes };"))
                    .isEmpty();
        }

        @Test
        @DisplayName("a MYSQL connection (LATERAL version-gated) falls back to in-engine")
        void mysqlFallsBack() {
            String mysql =
                    "connection db from database { url: \"jdbc:mysql://host/db\" };\n" +
                    "source Trades from db { table: \"trades\", " +
                    "schema: { sym: STRING, t: TIMESTAMP, px: NUMBER } };\n" +
                    "source Quotes from db { table: \"quotes\", " +
                    "schema: { qsym: STRING, qt: TIMESTAMP, bid: NUMBER } };\n";
            assertThat(push(mysql
                    + "query { Trades ASOF Trades.sym = Quotes.qsym ∧ Trades.t >= Quotes.qt Quotes };"))
                    .isEmpty();
        }

        @Test
        @DisplayName("a WITHIN tolerance has no portable SQL form and falls back to in-engine")
        void toleranceFallsBack() {
            assertThat(push(TRADES_QUOTES
                    + "query { Trades ASOF Trades.sym = Quotes.qsym ∧ Trades.t >= Quotes.qt "
                    + "WITHIN DURATION 'PT1H' Quotes };"))
                    .isEmpty();
        }

        @Test
        @DisplayName("an unpushable side blocks the fold, from either side")
        void anUnpushableSideFallsBack() {
            // Each fold builds both sides and tests `left.isEmpty() || right.isEmpty()`;
            // an unpushable *left* short-circuits and never shows the right is built.
            String withInline = TRADES_QUOTES
                    + "Probe := [| sym | t |\n"
                    + "          | \"A\" | 1 |];\n";
            assertThat(push(withInline
                    + "query { Probe ASOF Probe.sym = Quotes.qsym ∧ Probe.t >= Quotes.qt Quotes };"))
                    .as("left unpushable").isEmpty();
            assertThat(push(withInline
                    + "query { Trades ASOF Trades.sym = Probe.sym ∧ Trades.t >= Probe.t Probe };"))
                    .as("right unpushable").isEmpty();
        }

        @Test
        @DisplayName("a join across two connections is not folded")
        void crossConnectionFallsBack() {
            String setup =
                    "connection db from database { url: \"jdbc:postgresql://h/a\" };\n" +
                    "source Trades from db { table: \"trades\", " +
                    "schema: { sym: STRING, t: TIMESTAMP, px: NUMBER } };\n" +
                    "connection db2 from database { url: \"jdbc:postgresql://h/b\" };\n" +
                    "source Quotes from db2 { table: \"quotes\", " +
                    "schema: { qsym: STRING, qt: TIMESTAMP, bid: NUMBER } };\n";
            assertThat(push(setup
                    + "query { Trades ASOF Trades.sym = Quotes.qsym ∧ Trades.t >= Quotes.qt Quotes };"))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("interval join → JOIN … ON endpoint predicate (ADR-0014 slice 5, #207)")
    class IntervalFold {

        /** Two JDBC-backed interval tables on one connection. */
        private static final String STAYS_BOOKINGS =
                "connection db from database { url: \"jdbc:h2:mem:x\" };\n" +
                "source Stays from db { table: \"stays\", " +
                "schema: { sid: NUMBER, checkin: TIMESTAMP, checkout: TIMESTAMP } };\n" +
                "source Bookings from db { table: \"bookings\", " +
                "schema: { bid: NUMBER, bfrom: TIMESTAMP, bto: TIMESTAMP } };\n";

        @Test
        @DisplayName("OVERLAPS folds into JOIN … ON with the strict three-term endpoint condition")
        void overlaps() {
            PhysicalNode.PushedScan s = scanOf(push(STAYS_BOOKINGS
                    + "query { Stays IJOIN OVERLAPS "
                    + "(Stays.checkin, Stays.checkout, Bookings.bfrom, Bookings.bto) Bookings };"));
            assertThat(s.connection()).isEqualTo("db");
            assertThat(s.nativeQuery()).isEqualTo(
                    "SELECT Stays.sid, Stays.checkin, Stays.checkout, Bookings.bid, Bookings.bfrom, Bookings.bto "
                    + "FROM stays Stays JOIN bookings Bookings ON "
                    + "(Stays.checkin < Bookings.bfrom AND Stays.checkout > Bookings.bfrom "
                    + "AND Stays.checkout < Bookings.bto)");
        }

        @Test
        @DisplayName("INTERSECTS folds into the two-term any-overlap condition")
        void intersects() {
            assertThat(scanOf(push(STAYS_BOOKINGS
                    + "query { Stays IJOIN INTERSECTS "
                    + "(Stays.checkin, Stays.checkout, Bookings.bfrom, Bookings.bto) Bookings };"))
                    .nativeQuery())
                    .endsWith("ON (Stays.checkin < Bookings.bto AND Bookings.bfrom < Stays.checkout)");
        }

        @Test
        @DisplayName("DURING, CONTAINS, MEETS, EQUALS, PRECEDES each render their endpoint predicate")
        void otherRelations() {
            assertThat(intervalCondition("DURING"))
                    .isEqualTo("(Bookings.bfrom < Stays.checkin AND Stays.checkout < Bookings.bto)");
            assertThat(intervalCondition("CONTAINS"))
                    .isEqualTo("(Stays.checkin < Bookings.bfrom AND Bookings.bto < Stays.checkout)");
            assertThat(intervalCondition("MEETS"))
                    .isEqualTo("(Stays.checkout = Bookings.bfrom)");
            assertThat(intervalCondition("EQUALS"))
                    .isEqualTo("(Stays.checkin = Bookings.bfrom AND Stays.checkout = Bookings.bto)");
            assertThat(intervalCondition("PRECEDES"))
                    .isEqualTo("(Stays.checkout < Bookings.bfrom)");
        }

        /** Pushes an interval join with {@code relation} and returns just its ON condition. */
        private static String intervalCondition(String relation) {
            String sql = scanOf(push(STAYS_BOOKINGS
                    + "query { Stays IJOIN " + relation + " "
                    + "(Stays.checkin, Stays.checkout, Bookings.bfrom, Bookings.bto) Bookings };"))
                    .nativeQuery();
            return sql.substring(sql.indexOf(" ON ") + 4);
        }

        @Test
        @DisplayName("a POSTGRES connection quotes every identifier in the pushed interval join")
        void postgresQuoting() {
            String pg =
                    "connection db from database { url: \"jdbc:h2:mem:x\", dialect: postgres };\n" +
                    "source Stays from db { table: \"stays\", " +
                    "schema: { sid: NUMBER, checkin: TIMESTAMP, checkout: TIMESTAMP } };\n" +
                    "source Bookings from db { table: \"bookings\", " +
                    "schema: { bid: NUMBER, bfrom: TIMESTAMP, bto: TIMESTAMP } };\n";
            assertThat(scanOf(push(pg
                    + "query { Stays IJOIN MEETS "
                    + "(Stays.checkin, Stays.checkout, Bookings.bfrom, Bookings.bto) Bookings };"))
                    .nativeQuery())
                    .endsWith("ON (\"Stays\".\"checkout\" = \"Bookings\".\"bfrom\")");
        }

        @Test
        @DisplayName("an unpushable side blocks the fold, from either side")
        void anUnpushableSideFallsBack() {
            String withInline = STAYS_BOOKINGS
                    + "Probe := [| pfrom | pto |\n"
                    + "          | 1     | 2   |];\n";
            assertThat(push(withInline + "query { Probe IJOIN OVERLAPS "
                    + "(Probe.pfrom, Probe.pto, Bookings.bfrom, Bookings.bto) Bookings };"))
                    .as("left unpushable").isEmpty();
            assertThat(push(withInline + "query { Stays IJOIN OVERLAPS "
                    + "(Stays.checkin, Stays.checkout, Probe.pfrom, Probe.pto) Probe };"))
                    .as("right unpushable").isEmpty();
        }

        @Test
        @DisplayName("a join across two connections is not folded")
        void crossConnectionFallsBack() {
            String setup =
                    "connection db from database { url: \"jdbc:h2:mem:x\" };\n" +
                    "source Stays from db { table: \"stays\", " +
                    "schema: { sid: NUMBER, checkin: TIMESTAMP, checkout: TIMESTAMP } };\n" +
                    "connection db2 from database { url: \"jdbc:h2:mem:y\" };\n" +
                    "source Bookings from db2 { table: \"bookings\", " +
                    "schema: { bid: NUMBER, bfrom: TIMESTAMP, bto: TIMESTAMP } };\n";
            assertThat(push(setup
                    + "query { Stays IJOIN OVERLAPS "
                    + "(Stays.checkin, Stays.checkout, Bookings.bfrom, Bookings.bto) Bookings };"))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("tryPushOrdered injects an ORDER BY")
    class PushOrdered {

        @Test
        @DisplayName("injected keys fold onto a base + WHERE scan and are advertised as delivered")
        void injectsOrderBy() {
            List<SortSpecification> keys = List.of(asc("amount"));
            PhysicalNode.PushedScan s = scanOf(pushOrdered(
                    ORDERS + "query { σ amount > 0 (Orders) };", keys));
            assertThat(s.nativeQuery())
                    .isEqualTo("SELECT id, amount FROM orders WHERE (amount > 0) ORDER BY (amount IS NULL) ASC, amount ASC");
            assertThat(s.deliveredOrdering()).isEqualTo(Ordering.of(keys));
        }

        @Test
        @DisplayName("falls back (empty) when the input applied a computed projection")
        void fallbackWhenProjected() {
            List<SortSpecification> keys = List.of(asc("doubled"));
            assertThat(pushOrdered(ORDERS + "query { π amount * 2 → doubled (Orders) };", keys))
                    .isEmpty();
        }

        @Test
        @DisplayName("every clause that fixes the row order refuses an injected ORDER BY")
        void everyBlockingClauseRefusesTheInjection() {
            // This is what the merge-join planner asks a source when it wants sorted rows.
            // A wrong "yes" hands the merge join rows it believes are sorted and are not,
            // so each blocking clause is its own conjunct and each has to be checked:
            // a δ (the dedup is unordered), an existing τ, and a λ (an ORDER BY appended
            // after a LIMIT would sort the wrong rows).
            List<SortSpecification> keys = List.of(asc("amount"));
            assertThat(pushOrdered(ORDERS + "query { Orders };", keys))
                    .as("control — a bare scan takes the injected order").isPresent();
            assertThat(pushOrdered(ORDERS + "query { δ (Orders) };", keys))
                    .as("δ").isEmpty();
            assertThat(pushOrdered(ORDERS + "query { τ id DESC (Orders) };", keys))
                    .as("τ").isEmpty();
            assertThat(pushOrdered(ORDERS + "query { λ 5 (Orders) };", keys))
                    .as("λ").isEmpty();
        }

        @Test
        @DisplayName("a derived key refuses the injection — an ORDER BY names a column")
        void derivedKeyRefusesTheInjection() {
            // The last conjunct of the guard: the scan itself is perfectly pushable and
            // only the key declines, because a computed key has no column name to render.
            List<SortSpecification> derived = List.of(sortKey(
                    new com.darkcollective.relix.ast.BinaryArithmeticExpression(
                            new com.darkcollective.relix.ast.AttributeOperand("amount"),
                            com.darkcollective.relix.ast.ArithmeticOperator.MULTIPLY,
                            new com.darkcollective.relix.ast.NumberOperand("2")),
                    SortDirection.ASC));
            assertThat(pushOrdered(ORDERS + "query { Orders };", derived)).isEmpty();
        }

        @Test
        @DisplayName("a column-pruning π still takes an ORDER BY — its keys are real columns")
        void ordersOverPruningProjection() {
            List<SortSpecification> keys = List.of(asc("amount"));
            PhysicalNode.PushedScan s = scanOf(pushOrdered(
                    ORDERS + "query { π amount (Orders) };", keys));
            assertThat(s.nativeQuery()).isEqualTo("SELECT amount FROM orders ORDER BY (amount IS NULL) ASC, amount ASC");
            assertThat(s.deliveredOrdering()).isEqualTo(Ordering.of(keys));
        }
    }

    @Nested
    @DisplayName("non-pushable trees fall back to empty")
    class Fallback {

        @Test
        @DisplayName("an inline (non-connection) relation is not pushable")
        void inlineRelationNotPushed() {
            assertThat(push(
                    "Inline := [| id | amount |\n" +
                    "            | 1  | 100    |];\n" +
                    "query { Inline };")).isEmpty();
        }

        @Test
        @DisplayName("a τ above a computed π is not pushed (the key would be a select-list alias)")
        void sortAboveProjectionNotPushed() {
            assertThat(push(ORDERS + "query { τ doubled (π amount * 2 → doubled (Orders)) };"))
                    .isEmpty();
        }

        @Test
        @DisplayName("a join across two connections is not folded into one SQL scan")
        void crossConnectionJoinNotPushed() {
            String setup =
                    ORDERS +
                    "connection db2 from database { url: \"jdbc:h2:mem:b\" };\n" +
                    "source Customers from db2 { table: \"customers\", schema: { id: NUMBER, name: STRING } };\n";
            assertThat(push(setup
                    + "query { Orders ⨝ Orders.id = Customers.id Customers };")).isEmpty();
        }

        @Test
        @DisplayName("the no-key whole-relation ∀ has no SQL representation and is not pushed")
        void noKeyUniversalNotPushed() {
            assertThat(push(ORDERS + "query { ∀ : amount > 0 (Orders) };")).isEmpty();
        }
    }

    // =========================================================================
    // Clause order — every guard, from every clause it guards against
    // =========================================================================

    /**
     * The fold-order guards, exercised once per blocking clause rather than once per rule.
     *
     * <p>SQL clause order is fixed, and the logical plan's order is not: a {@code σ} may
     * legally sit above a {@code λ}, but a {@code WHERE} cannot be added to a query that
     * has already emitted a {@code LIMIT} — it would filter <em>before</em> the limit
     * instead of after it. Every fold arm therefore opens with a guard listing the clauses
     * that must not already be present, and a guard that failed to name one would not make
     * the plan slower: it would emit a query returning <strong>different rows</strong>.
     *
     * <p>Each guard is a chain of {@code ||}, so testing one blocking clause per rule
     * leaves the rest of that chain unentered. These cases set one blocker at a time, and
     * each group carries the control — the same operator over an input that blocks
     * nothing — so a decline can be attributed to the guard rather than to the fixture.
     */
    @Nested
    @DisplayName("clause-order guards")
    class ClauseOrder {

        /** A projection that computes — the kind that genuinely fixes the select list. */
        private static final String COMPUTED_PI = "π id, amount, amount * 2 → doubled (Orders)";
        private static final String SORTED = "τ amount DESC (Orders)";
        private static final String LIMITED = "λ 5 (Orders)";

        private static void notPushed(String query) {
            assertThat(push(ORDERS + "query { " + query + " };"))
                    .as("%s", query)
                    .isEmpty();
        }

        private static void pushed(String query) {
            assertThat(push(ORDERS + "query { " + query + " };"))
                    .as("%s", query)
                    .isPresent();
        }

        @Test
        @DisplayName("σ: a WHERE cannot follow a select list, an ORDER BY or a LIMIT")
        void selectionGuards() {
            pushed("σ amount > 0 (Orders)");
            notPushed("σ amount > 0 (" + COMPUTED_PI + ")");
            notPushed("σ amount > 0 (" + SORTED + ")");
            notPushed("σ amount > 0 (" + LIMITED + ")");
        }

        @Test
        @DisplayName("π: a select list cannot be re-fixed, nor follow an ORDER BY or LIMIT")
        void projectionGuards() {
            pushed("π amount (Orders)");
            notPushed("π amount (" + COMPUTED_PI + ")");
            notPushed("π amount (" + SORTED + ")");
            notPushed("π amount (" + LIMITED + ")");
        }

        @Test
        @DisplayName("δ: DISTINCT cannot follow an ORDER BY, a LIMIT or a GROUP BY")
        void distinctGuards() {
            pushed("δ (Orders)");
            notPushed("δ (" + SORTED + ")");
            notPushed("δ (" + LIMITED + ")");
            notPushed("δ (γ id, SUM(amount) → total (Orders))");
        }

        @Test
        @DisplayName("γ: a GROUP BY fixes the select list, so it cannot follow one")
        void aggregationGuards() {
            pushed("γ id, SUM(amount) → total (Orders)");
            notPushed("γ id, SUM(amount) → total (" + COMPUTED_PI + ")");
            notPushed("γ id, SUM(amount) → total (" + SORTED + ")");
            notPushed("γ id, SUM(amount) → total (" + LIMITED + ")");
        }

        @Test
        @DisplayName("∀: GROUP BY … HAVING is bound by the same rule as a plain GROUP BY")
        void universalGuards() {
            pushed("∀ id : amount > 0 (Orders)");
            notPushed("∀ id : amount > 0 (" + COMPUTED_PI + ")");
            notPushed("∀ id : amount > 0 (" + SORTED + ")");
            notPushed("∀ id : amount > 0 (" + LIMITED + ")");
        }

        @Test
        @DisplayName("τ: an ORDER BY cannot be re-fixed, nor name a select-list alias")
        void sortGuards() {
            pushed("τ amount DESC (Orders)");
            notPushed("τ amount DESC (" + COMPUTED_PI + ")");
            notPushed("τ amount DESC (" + SORTED + ")");
            notPushed("τ amount DESC (" + LIMITED + ")");
        }

        @Test
        @DisplayName("λ: a LIMIT cannot be applied twice")
        void limitGuards() {
            pushed("λ 3 (Orders)");
            pushed("λ 3 (" + SORTED + ")");
            notPushed("λ 3 (" + LIMITED + ")");
        }

        @Test
        @DisplayName("TOP … PER is never pushed — a per-group limit has no plain SQL form")
        void perGroupTopKIsNeverPushed() {
            // The grammar requires PER, so every *written* TOP bails here. The clause-order
            // guard below that bail is reached only by the empty-PER TopK the optimizer
            // builds when LIMIT-001 folds a λ over a τ, which this text-driven harness
            // does not run — PlannerTest is where that shape arrives.
            notPushed("TOP 3 amount DESC PER id (Orders)");
        }

        @Test
        @DisplayName("a window cannot follow a fixed select list or a LIMIT")
        void windowGuards() {
            String ticks = postgresTicks();
            assertThat(push(ticks + "query { " + rollingSum("Ticks") + " };"))
                    .as("control").isPresent();
            assertThat(push(ticks + "query { "
                    + rollingSum("π ticker, t, price, price * 2 → doubled (Ticks)") + " };"))
                    .as("over a computed π").isEmpty();
            assertThat(push(ticks + "query { " + rollingSum("λ 10 (Ticks)") + " };"))
                    .as("over a λ").isEmpty();
        }

        private static String postgresTicks() {
            return "connection db from database { url: \"jdbc:postgresql://h/x\", dialect: \"postgres\" };\n"
                    + "source Ticks from db { table: \"ticks\", "
                    + "schema: { ticker: STRING, t: NUMBER, price: NUMBER } };\n";
        }

        private static String rollingSum(String input) {
            return "ROLLING SUM(price) OVER 2 ROWS SORT t ASC PER ticker AS s (" + input + ")";
        }

        @Test
        @DisplayName("a WHERE cannot follow a window OVER expression either")
        void selectionAboveAWindow() {
            // A predicate over a window result needs a subquery, not a WHERE clause —
            // the arm no π / τ / λ fixture reaches, since only a window sets that flag.
            String ticks = postgresTicks();
            assertThat(push(ticks + "query { " + rollingSum("Ticks") + " };"))
                    .as("the window alone pushes").isPresent();
            assertThat(push(ticks + "query { σ price > 0 (" + rollingSum("Ticks") + ") };"))
                    .as("a σ above it does not").isEmpty();
        }
    }

    // =========================================================================
    // Join-fold eligibility
    // =========================================================================

    /**
     * The conditions under which two pushed sides may be folded into one {@code JOIN}.
     *
     * <p>The fold renders {@code table alias JOIN table alias ON …}, so it needs two
     * <em>bare</em> scans, on one connection, with distinct identifier-shaped aliases.
     * Each of those is a separate conjunct and each failure has to fall back to an
     * in-engine join rather than emit SQL that means something else.
     */
    @Nested
    @DisplayName("join-fold eligibility")
    class JoinEligibility {

        private static final String TWO_TABLES = ORDERS + CUSTOMERS_SAME_DB;

        private static void notPushed(String query) {
            assertThat(push(TWO_TABLES + "query { " + query + " };")).as("%s", query).isEmpty();
        }

        @Test
        @DisplayName("two bare scans on one connection fold")
        void bothBareScansFold() {
            assertThat(push(TWO_TABLES
                    + "query { Orders ⨝ Orders.id = Customers.id Customers };")).isPresent();
        }

        @Test
        @DisplayName("a side that is no longer a bare scan blocks the fold, from either side")
        void aNonBareSideBlocksTheFold() {
            // δ and a computed π each stop being `table AS alias`, so the FROM clause the
            // fold wants to render does not exist. Both sides are separate conjuncts.
            notPushed("δ (Orders) ⨝ Orders.id = Customers.id Customers");
            notPushed("Orders ⨝ Orders.id = Customers.id δ (Customers)");
            notPushed("π id, amount * 2 → doubled (Orders) ⨝ Orders.id = Customers.id Customers");
            notPushed("Orders ⨝ Orders.id = Customers.id π id, name → who (Customers)");
        }

        @Test
        @DisplayName("an unpushable side blocks the fold, from either side")
        void anUnpushableSideBlocksTheFold() {
            String withInline = TWO_TABLES
                    + "Inline := [| id | tag |\n"
                    + "            | 1  | \"a\" |];\n";
            assertThat(push(withInline
                    + "query { Inline ⨝ Inline.id = Customers.id Customers };"))
                    .as("left unpushable").isEmpty();
            assertThat(push(withInline
                    + "query { Customers ⨝ Customers.id = Inline.id Inline };"))
                    .as("right unpushable").isEmpty();
        }

        @Test
        @DisplayName("a self-join is not folded — one alias cannot name both sides")
        void aSelfJoinIsNotFolded() {
            // The fold renders `table alias JOIN table alias ON …`, and both sides of a
            // self-join carry the same alias. Emitting it would produce SQL whose ON
            // clause is ambiguous about which occurrence it means — so the fold declines
            // and the join stays in-engine, where the two sides are distinct row streams.
            notPushed("Orders ⨝ Orders.id = Orders.id Orders");
        }

        @Test
        @DisplayName("a condition SQL cannot render blocks the fold")
        void anUnrenderableConditionBlocksTheFold() {
            // Both sides are bare scans on one connection; only the ON expression fails.
            notPushed("Orders ⨝ Orders.id = [Customers.id] Customers");
        }
    }

    // =========================================================================
    // Natural join (#981)
    // =========================================================================

    /** A natural join over two tables on one connection folds into one {@code JOIN … ON}. */
    @Nested
    @DisplayName("natural join → JOIN … ON the shared columns")
    class NaturalJoinFold {

        private static final String TWO_TABLES = ORDERS + CUSTOMERS_SAME_DB;

        @Test
        @DisplayName("the shared column is equated, and appears once, from the left")
        void foldsOnTheSharedColumn() {
            assertThat(scanOf(push(TWO_TABLES + "query { Orders ⋈ Customers };")).nativeQuery())
                    .isEqualTo("SELECT Orders.id, Orders.amount, Customers.name "
                            + "FROM orders Orders JOIN customers Customers ON (Orders.id = Customers.id)");
        }

        @Test
        @DisplayName("every shared column is equated, in the left side's order")
        void foldsOnEverySharedColumn() {
            String src = "connection db from database { url: \"jdbc:h2:mem:x\" };\n"
                    + "source Stock from db { table: \"stock\", "
                    + "schema: { site: STRING, sku: STRING, qty: NUMBER } };\n"
                    + "source Prices from db { table: \"prices\", "
                    + "schema: { sku: STRING, price: NUMBER, site: STRING } };\n"
                    + "query { Stock JOIN Prices };";
            assertThat(scanOf(push(src)).nativeQuery())
                    .isEqualTo("SELECT Stock.site, Stock.sku, Stock.qty, Prices.price "
                            + "FROM stock Stock JOIN prices Prices "
                            + "ON (Stock.site = Prices.site) AND (Stock.sku = Prices.sku)");
        }

        @Test
        @DisplayName("a σ above renders a shared column from the left, bare or left-qualified")
        void selectionAboveTheJoin() {
            assertThat(scanOf(push(TWO_TABLES + "query { σ id = 1 (Orders ⋈ Customers) };"))
                    .nativeQuery()).endsWith("WHERE (Orders.id = 1)");
            assertThat(scanOf(push(TWO_TABLES + "query { σ Orders.id = 1 (Orders ⋈ Customers) };"))
                    .nativeQuery()).endsWith("WHERE (Orders.id = 1)");
            assertThat(scanOf(push(TWO_TABLES
                    + "query { σ name = 'x' ∧ Orders.amount > 2 (Orders ⋈ Customers) };"))
                    .nativeQuery()).endsWith("WHERE ((Customers.name = 'x') AND (Orders.amount > 2))");
        }

        @Test
        @DisplayName("a qualifier naming the wrong side, or neither, is not rendered")
        void unrenderableReferences() {
            SemanticModel model = model(TWO_TABLES + "query { Orders ⋈ Customers };");
            RelNode join = ((ExpressionQueryTarget) model.rootQueries().get(0).target()).expression();
            SqlPushdownPlanner planner = new SqlPushdownPlanner(model.nodeSchemas(), model.sources(),
                    model.connections(), model.functions());
            // Built by hand: analysis refuses each of these before a planner could see it.
            for (String reference : List.of("Customers.id", "Customers.amount", "Orders.name", "Other.id", "missing")) {
                assertThat(planner.tryPush(select(com.darkcollective.relix.ast.Expr.eq(attr(reference), num("1")), join)))
                        .as("%s", reference).isEmpty();
            }
        }

        @Test
        @DisplayName("a shared column typed differently on each side is not folded")
        void differentlyTypedSharedColumn() {
            String src = ORDERS
                    + "source Tags from db { table: \"tags\", schema: { id: STRING, tag: STRING } };\n"
                    + "query { Orders ⋈ Tags };";
            assertThat(push(src)).isEmpty();
        }

        @Test
        @DisplayName("headings sharing no column are not folded")
        void noSharedColumn() {
            // Analysis reports this as an error and still hands back a model, which is
            // how the planner can be asked at all.
            String src = ORDERS
                    + "source Notes from db { table: \"notes\", schema: { note: STRING } };\n"
                    + "query { Orders ⋈ Notes };";
            SemanticModel model = SemanticFixtures.analyze(src).model().orElseThrow();
            RelNode logical = ((ExpressionQueryTarget) model.rootQueries().get(0).target()).expression();
            assertThat(new SqlPushdownPlanner(model.nodeSchemas(), model.sources(), model.connections(),
                    model.functions()).tryPush(logical)).isEmpty();
        }

        @Test
        @DisplayName("a self-join is not folded, and neither is a side that is not a bare scan")
        void ineligibleSides() {
            assertThat(push(TWO_TABLES + "query { Orders ⋈ Orders };")).isEmpty();
            assertThat(push(TWO_TABLES + "query { δ (Orders) ⋈ Customers };")).isEmpty();
            assertThat(push(TWO_TABLES + "query { Orders ⋈ δ (Customers) };")).isEmpty();
        }

        @Test
        @DisplayName("on a collated backend a string key is compared exactly")
        void collatedStringKey() {
            String src = "connection db from database { url: \"jdbc:mysql://h/db\" };\n"
                    + "source Stock from db { table: \"stock\", schema: { sku: STRING, qty: NUMBER } };\n"
                    + "source Prices from db { table: \"prices\", schema: { sku: STRING, price: NUMBER } };\n"
                    + "query { Stock ⋈ Prices };";
            assertThat(scanOf(push(src)).nativeQuery()).endsWith(
                    "ON (CONVERT(`Stock`.`sku` USING utf8mb4) COLLATE utf8mb4_0900_bin "
                            + "= CONVERT(`Prices`.`sku` USING utf8mb4) COLLATE utf8mb4_0900_bin)");
        }
    }

    // =========================================================================
    // connection.table references (#982)
    // =========================================================================

    /**
     * Joins over tables named as {@code connection.table} rather than declared as sources.
     *
     * <p>A dotted name is not a SQL identifier, and the fold used to name each side of the
     * statement by its relation name — so none of these folded. The statement now names
     * the sides by aliases of its own, and a condition still qualifies by relation name.
     */
    @Nested
    @DisplayName("joins over connection.table references")
    class DottedReferences {

        private static final Map<String, Schema> TABLES = Map.of(
                "customers", new Schema(List.of(
                        new ColumnDefinition("id", ScalarType.NUMBER),
                        new ColumnDefinition("name", ScalarType.STRING),
                        new ColumnDefinition("addr", new StructType(List.of(
                                new StructType.Field("city", ScalarType.STRING)))))),
                "orders", new Schema(List.of(
                        new ColumnDefinition("oid", ScalarType.NUMBER),
                        new ColumnDefinition("cid", ScalarType.NUMBER),
                        new ColumnDefinition("city", ScalarType.STRING))),
                "order-lines", new Schema(List.of(
                        new ColumnDefinition("lid", ScalarType.NUMBER),
                        new ColumnDefinition("line-oid", ScalarType.NUMBER))),
                "trades", new Schema(List.of(
                        new ColumnDefinition("sym", ScalarType.STRING),
                        new ColumnDefinition("t", ScalarType.TIMESTAMP))),
                "quotes", new Schema(List.of(
                        new ColumnDefinition("qsym", ScalarType.STRING),
                        new ColumnDefinition("qt", ScalarType.TIMESTAMP))),
                "stays", new Schema(List.of(
                        new ColumnDefinition("checkin", ScalarType.TIMESTAMP),
                        new ColumnDefinition("checkout", ScalarType.TIMESTAMP))),
                "bookings", new Schema(List.of(
                        new ColumnDefinition("bfrom", ScalarType.TIMESTAMP),
                        new ColumnDefinition("bto", ScalarType.TIMESTAMP))));

        private static final CatalogProvider CATALOG =
                (connection, table) -> Optional.ofNullable(TABLES.get(table));

        private static final String SHOP =
                "connection shop from database { url: \"jdbc:h2:mem:x\" };\n";

        /** Pushes the first query of {@code src}, with dotted references resolved by the catalog. */
        private static Optional<PhysicalNode.PushedScan> pushDotted(String src) {
            SemanticResult result = SemanticFixtures.analyze(src, CATALOG);
            assertThat(result.errors()).as("analysis of: %s", src).isEmpty();
            SemanticModel model = result.model().orElseThrow();
            RelNode logical =
                    ((ExpressionQueryTarget) model.rootQueries().get(0).target()).expression();
            return new SqlPushdownPlanner(model.nodeSchemas(), model.sources(), model.connections(),
                    model.functions()).tryPush(logical);
        }

        @Test
        @DisplayName("a theta join folds, each side aliased by its table and qualified by its dotted name")
        void thetaJoinFolds() {
            assertThat(scanOf(pushDotted(SHOP + "query { shop.customers "
                    + "⨝ shop.customers.id = shop.orders.cid shop.orders };")).nativeQuery())
                    .isEqualTo("SELECT customers.id, customers.name, customers.addr, "
                            + "orders.oid, orders.cid, orders.city "
                            + "FROM customers customers JOIN orders orders "
                            + "ON (customers.id = orders.cid)");
        }

        @Test
        @DisplayName("an unqualified reference renders against the one side that has it")
        void unqualifiedReferencesFold() {
            assertThat(scanOf(pushDotted(SHOP
                    + "query { shop.customers ⨝ id = cid shop.orders };")).nativeQuery())
                    .endsWith("ON (customers.id = orders.cid)");
        }

        @Test
        @DisplayName("a derived alias that clashes with the other side's is suffixed, and each "
                + "qualifier still reaches its own side")
        void clashingAliasesAreSuffixed() {
            // `Orders` is a declared source over another table; `shop.orders` derives the
            // alias `orders`, which differs from `Orders` only in case.
            String src = SHOP + "source Orders from shop { table: \"archive\", "
                    + "schema: { oid: NUMBER, cid: NUMBER } };\n"
                    + "query { Orders ⨝ Orders.oid = shop.orders.oid shop.orders };";
            assertThat(scanOf(pushDotted(src)).nativeQuery())
                    .isEqualTo("SELECT Orders.oid, Orders.cid, orders_1.oid, orders_1.cid, orders_1.city "
                            + "FROM archive Orders JOIN orders orders_1 "
                            + "ON (Orders.oid = orders_1.oid)");
        }

        @Test
        @DisplayName("a table whose name is no identifier is aliased by position")
        void nonIdentifierTableIsAliasedByPosition() {
            String pg = "connection shop from database { url: \"jdbc:postgresql://h/db\" };\n";
            assertThat(scanOf(pushDotted(pg + "query { shop.orders "
                    + "⨝ shop.orders.oid = shop.`order-lines`.`line-oid` shop.`order-lines` };")).nativeQuery())
                    .isEqualTo("SELECT \"orders\".\"oid\", \"orders\".\"cid\", \"orders\".\"city\", "
                            + "\"t1\".\"lid\", \"t1\".\"line-oid\" "
                            + "FROM \"orders\" \"orders\" JOIN \"order-lines\" \"t1\" "
                            + "ON (\"orders\".\"oid\" = \"t1\".\"line-oid\")");
        }

        @Test
        @DisplayName("on a generic connection a name that is no identifier is delimited, and the rest stay bare")
        void genericDelimitsNonIdentifierNames() {
            assertThat(scanOf(pushDotted(SHOP + "query { shop.orders "
                    + "⨝ shop.orders.oid = shop.`order-lines`.`line-oid` shop.`order-lines` };")).nativeQuery())
                    .isEqualTo("SELECT orders.oid, orders.cid, orders.city, t1.lid, t1.\"line-oid\" "
                            + "FROM orders orders JOIN \"order-lines\" t1 ON (orders.oid = t1.\"line-oid\")");
            assertThat(scanOf(pushDotted(SHOP + "query { σ `line-oid` > 1 (shop.`order-lines`) };"))
                    .nativeQuery())
                    .isEqualTo("SELECT lid, \"line-oid\" FROM \"order-lines\" WHERE (\"line-oid\" > 1)");
        }

        @Test
        @DisplayName("a self-join is still not folded — one qualifier cannot say which side")
        void selfJoinIsNotFolded() {
            // A qualified reference is refused by analysis as ambiguous, so the condition
            // names no column; the fold must still decline on the names alone.
            assertThat(pushDotted(SHOP + "query { shop.orders ⨝ 1 = 1 shop.orders };")).isEmpty();
        }

        @Test
        @DisplayName("a path into a nested column is not resolved by its tail")
        void nestedPathIsNotResolvedByItsTail() {
            // `addr.city` is a path into the left's struct; the right has a column named
            // `city`. Binding the reference by its tail would compare the right's `city`.
            assertThat(pushDotted(SHOP + "query { shop.customers "
                    + "⨝ addr.city = shop.orders.city shop.orders };")).isEmpty();
        }

        @Test
        @DisplayName("an AS-OF join folds into the LATERAL lookup, the sub-select aliased too")
        void asOfFolds() {
            String pg = "connection shop from database { url: \"jdbc:postgresql://h/db\" };\n";
            assertThat(scanOf(pushDotted(pg + "query { shop.trades ASOF "
                    + "shop.trades.sym = shop.quotes.qsym ∧ shop.trades.t >= shop.quotes.qt "
                    + "shop.quotes };")).nativeQuery())
                    .isEqualTo("SELECT \"trades\".\"sym\", \"trades\".\"t\", "
                            + "\"quotes\".\"qsym\", \"quotes\".\"qt\" "
                            + "FROM \"trades\" \"trades\" LEFT JOIN LATERAL ("
                            + "SELECT \"quotes\".\"qsym\", \"quotes\".\"qt\" FROM \"quotes\" \"quotes\" "
                            + "WHERE ((\"trades\".\"sym\" = \"quotes\".\"qsym\") "
                            + "AND (\"trades\".\"t\" >= \"quotes\".\"qt\")) "
                            + "ORDER BY \"quotes\".\"qt\" DESC LIMIT 1) \"quotes\" ON TRUE");
        }

        @Test
        @DisplayName("an interval join folds into JOIN … ON")
        void intervalJoinFolds() {
            assertThat(scanOf(pushDotted(SHOP + "query { shop.stays IJOIN MEETS "
                    + "(shop.stays.checkin, shop.stays.checkout, shop.bookings.bfrom, shop.bookings.bto) "
                    + "shop.bookings };")).nativeQuery())
                    .isEqualTo("SELECT stays.checkin, stays.checkout, bookings.bfrom, bookings.bto "
                            + "FROM stays stays JOIN bookings bookings ON (stays.checkout = bookings.bfrom)");
        }
    }
}

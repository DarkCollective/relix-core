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
import com.darkcollective.relix.processor.DataSourceConnector;
import com.darkcollective.relix.processor.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;

@DisplayName("RelNodeExecutor — binary join operators")
final class JoinOperatorsTest extends ProcessorTestSupport {

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

    private static List<Row> collectWith(String src, DataSourceConnector connector) {
        SemanticModel model = model(src);
        ExecutionContext ctx = ExecutionContext.of(model, connector);
        var query = model.rootQueries().getFirst();
        try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
            return stream.toList();
        }
    }

    // ── a schema-on-read side ─────────────────────────────────────────────────

    @Nested
    @DisplayName("a join with a schema-on-read (open) side")
    class OpenInput {

        /**
         * A JSON-shaped source: no declared columns, and rows that carry their own
         * fields. The connector is asked for the relation under an open schema.
         */
        private static final DataSourceConnector DOCS = (_, _) -> Stream.of(
                document("account", 1002, "holder", "Ada"),
                document("account", 1009, "holder", "Bo"));

        private static Row document(String k1, int v1, String k2, String v2) {
            java.util.Map<String, com.darkcollective.relix.value.Value> fields =
                    new java.util.LinkedHashMap<>();
            fields.put(k1, num(v1));
            fields.put(k2, str(v2));
            return new com.darkcollective.relix.processor.DocumentRow(
                    new com.darkcollective.relix.value.StructValue(fields));
        }

        private static final String SCRIPT = """
                source Docs from json("docs.json");
                Depths := [| dst  | depth |
                           | 1002 | 1     |
                           | 1009 | 2     |];
                query { Docs ⨝ account = dst Depths };
                """;

        @Test
        @DisplayName("the document's own fields survive the join")
        void openSideJoinsAndKeepsItsFields() {
            // This died with "Value count 5 does not match schema width 2" until the
            // joined heading kept its openness: the executor concatenates both rows,
            // and the heading claimed only the declared side's width. `account` is a
            // field of the document and is named in the join condition, which is the
            // whole point — a theta join is what the natural-join arm tells you to
            // write over a schema-on-read input.
            List<Row> rows = collectWith(SCRIPT, DOCS);

            assertThat(rows).hasSize(2);
            assertThat(rows.getFirst())
                    .hasValue("holder", "Ada")
                    .hasValue("depth", "1");
            assertThat(rows.get(1))
                    .hasValue("holder", "Bo")
                    .hasValue("depth", "2");
        }
    }

    /**
     * A qualified reference <em>above</em> a join with an open side (#970). Both inputs
     * carry {@code account}, so the joined document renames one of them — and before the
     * fix a qualified name read as a path into a field named after the relation, found
     * nothing, and returned NULL on every row.
     */
    @Nested
    @DisplayName("a qualified reference above a join with a schema-on-read side (#970)")
    class QualifiedAboveOpenJoin {

        private static final DataSourceConnector DOCS = (_, _) -> Stream.of(
                document(1002, "Ada"),
                document(1009, "Bo"),
                document(1050, "Cy"));

        private static Row document(int account, String holder) {
            java.util.Map<String, com.darkcollective.relix.value.Value> fields =
                    new java.util.LinkedHashMap<>();
            fields.put("account", num(account));
            fields.put("holder", str(holder));
            return new com.darkcollective.relix.processor.DocumentRow(
                    new com.darkcollective.relix.value.StructValue(fields));
        }

        private static final String SETUP = """
                source Docs from json("docs.json");
                Ledger := [| account | depth |
                           | 1002    | 1     |
                           | 1009    | 2     |
                           | 2000    | 3     |];
                """;

        private static List<String> column(List<Row> rows, String name) {
            return rows.stream().map(r -> r.get(name).asDisplayString()).toList();
        }

        private static List<String> pairs(List<Row> rows) {
            return rows.stream()
                    .map(r -> r.get("l").asDisplayString() + "/" + r.get("d").asDisplayString())
                    .sorted().toList();
        }

        @Test
        @DisplayName("declared ⨝ open: each qualified name reads its own side")
        void declaredLeft() {
            List<Row> rows = collectWith(SETUP + """
                    query { π Ledger.account → l, Docs.account → d, Docs.holder → h
                            (Ledger ⨝ Ledger.account = Docs.account Docs) };
                    """, DOCS);

            assertThat(rows).hasSize(2);
            assertThat(column(rows, "l")).containsExactly("1002", "1009");
            assertThat(column(rows, "d")).containsExactly("1002", "1009");
            assertThat(column(rows, "h")).containsExactly("Ada", "Bo");
        }

        @Test
        @DisplayName("open ⨝ declared: the renamed field is found under its own relation")
        void openLeft() {
            List<Row> rows = collectWith(SETUP + """
                    query { π Ledger.depth → x, Ledger.account → l, Docs.account → d
                            (Docs ⨝ Docs.account = Ledger.account Ledger) };
                    """, DOCS);

            assertThat(column(rows, "x")).containsExactly("1", "2");
            assertThat(column(rows, "l")).containsExactly("1002", "1009");
        }

        @Test
        @DisplayName("a non-equi join, where the two sides' values differ, keeps them apart")
        void nonEquiJoin() {
            // The case where reading the wrong side is visible: in an equi-join the two
            // values agree, and a wrong answer looks right.
            List<Row> rows = collectWith(SETUP + """
                    query { π Ledger.account → l, Docs.account → d
                            (Ledger ⨝ Ledger.account < Docs.account Docs) };
                    """, DOCS);

            assertThat(pairs(rows)).containsExactly(
                    "1002/1009", "1002/1050", "1009/1050");
        }

        @Test
        @DisplayName("a qualified grouping key groups by its own side")
        void groupingKey() {
            List<Row> rows = collectWith(SETUP + """
                    query { γ Ledger.account → acct, COUNT(*) → n
                            (Ledger ⨝ Ledger.account ≤ Docs.account Docs) };
                    """, DOCS);

            assertThat(rows)
                    .extracting(r -> r.get("acct").asDisplayString() + ":" + r.get("n").asDisplayString())
                    .containsExactlyInAnyOrder("1002:3", "1009:2");
        }

        @Test
        @DisplayName("a cross product keeps both sides")
        void product() {
            List<Row> rows = collectWith(SETUP + """
                    query { π Ledger.account → l, Docs.account → d (Ledger × Docs) };
                    """, DOCS);

            assertThat(rows).hasSize(9);
            assertThat(pairs(rows)).contains("2000/1002", "1002/1050");
        }

        @Test
        @DisplayName("left outer: an unmatched row still reads its own side")
        void leftOuter() {
            List<Row> rows = collectWith(SETUP + """
                    query { π Ledger.account → l, Docs.account → d
                            (Ledger ⟕ Ledger.account = Docs.account Docs) };
                    """, DOCS);

            assertThat(pairs(rows)).containsExactly("1002/1002", "1009/1009", "2000/NULL");
        }

        @Test
        @DisplayName("right outer: an unmatched row still reads its own side")
        void rightOuter() {
            List<Row> rows = collectWith(SETUP + """
                    query { π Ledger.account → l, Docs.account → d
                            (Ledger ⟖ Ledger.account = Docs.account Docs) };
                    """, DOCS);

            assertThat(pairs(rows)).containsExactly("1002/1002", "1009/1009", "NULL/1050");
        }

        @Test
        @DisplayName("full outer: both kinds of unmatched row read their own side")
        void fullOuter() {
            List<Row> rows = collectWith(SETUP + """
                    query { π Ledger.account → l, Docs.account → d
                            (Ledger ⟗ Ledger.account = Docs.account Docs) };
                    """, DOCS);

            assertThat(pairs(rows)).containsExactly(
                    "1002/1002", "1009/1009", "2000/NULL", "NULL/1050");
        }

        @Test
        @DisplayName("full outer with a non-equi condition takes the same path")
        void fullOuterNonEqui() {
            List<Row> rows = collectWith(SETUP + """
                    query { π Ledger.account → l, Docs.account → d
                            (Ledger ⟗ Ledger.account > Docs.account Docs) };
                    """, DOCS);

            // 1002 is greater than no document; every document is less than some account.
            assertThat(pairs(rows)).containsExactly(
                    "1002/NULL", "1009/1002", "2000/1002", "2000/1009", "2000/1050");
        }

        @Test
        @DisplayName("a rename over the whole join re-anchors every field, the open side's included (#971)")
        void renameOverTheJoin() {
            // The shape an inlined view takes: `J := { Ledger ⨝ … Docs }` becomes
            // `ρ J (Ledger ⨝ … Docs)`. The rename used to restate each document row
            // under its heading, which knows only Ledger's columns, and failed on width.
            List<Row> rows = collectWith(SETUP + """
                    query { π J.account → l, J.account_r → d, J.holder → h
                            (ρ J (Ledger ⨝ Ledger.account = Docs.account Docs)) };
                    """, DOCS);

            assertThat(column(rows, "l")).containsExactly("1002", "1009");
            assertThat(column(rows, "d")).containsExactly("1002", "1009");
            assertThat(column(rows, "h")).containsExactly("Ada", "Bo");
        }

        @Test
        @DisplayName("a relation hidden by the rename no longer answers")
        void hiddenRelationIsAPathAgain() {
            List<Row> rows = collectWith(SETUP + """
                    query { π Ledger.account → l (ρ J (Ledger ⨝ Ledger.account = Docs.account Docs)) };
                    """, DOCS);

            assertThat(column(rows, "l")).containsOnly("NULL");
        }

        @Test
        @DisplayName("a column-only rename leaves a document's origins as they were")
        void columnOnlyRenameKeepsOrigins() {
            List<Row> rows = collectWith(SETUP + """
                    query { π Docs.holder → h (ρ (depth → level) (Ledger ⨝ Ledger.account = Docs.account Docs)) };
                    """, DOCS);

            assertThat(column(rows, "h")).containsExactly("Ada", "Bo");
        }

        @Test
        @DisplayName("a renamed open side answers to its new name")
        void renamedOpenSide() {
            List<Row> rows = collectWith(SETUP + """
                    query { π Ledger.account → l, D.holder → d
                            (Ledger ⨝ Ledger.account = D.account (ρ D (Docs))) };
                    """, DOCS);

            assertThat(pairs(rows)).containsExactly("1002/Ada", "1009/Bo");
        }
    }

    // ── ProductNode ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ProductNode (× / CROSS)")
    class Product {

        @Test
        @DisplayName("cross product of 2×2 tables produces 4 rows")
        void basicProduct() {
            var rows = collect(
                    "A := [| a_id | a_val |\n" +
                    "       | 1    | x     |\n" +
                    "       | 2    | y     |];\n" +
                    "B := [| b_id | b_val |\n" +
                    "       | 10   | p     |\n" +
                    "       | 20   | q     |];\n" +
                    "query { A × B };");

            assertThat(rows).hasSize(4);
        }

        @Test
        @DisplayName("output schema concatenates left and right columns")
        void outputSchema() {
            var rows = collect(
                    "A := [| a_id | a_val |\n" +
                    "       | 1    | x     |];\n" +
                    "B := [| b_id | b_val |\n" +
                    "       | 10   | p     |];\n" +
                    "query { A × B };");

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).schema().columns())
                    .extracting(c -> c.name())
                    .containsExactly("a_id", "a_val", "b_id", "b_val");
        }

        @Test
        @DisplayName("values from both sides are correctly placed")
        void valuesCorrect() {
            var rows = collect(
                    "A := [| a_id | a_val |\n" +
                    "       | 1    | x     |\n" +
                    "       | 2    | y     |];\n" +
                    "B := [| b_id | b_val |\n" +
                    "       | 10   | p     |\n" +
                    "       | 20   | q     |];\n" +
                    "query { A × B };");

            // Row order: (1,x,10,p), (1,x,20,q), (2,y,10,p), (2,y,20,q)
            assertThat(rows).extracting(r ->
                    r.get("a_id").asDisplayString() + "+" + r.get("b_id").asDisplayString())
                    .containsExactly("1+10", "1+20", "2+10", "2+20");
        }

        @Test
        @DisplayName("product with empty left yields empty result")
        void emptyLeft() {
            var rows = collect(
                    "A := [| a_id |];\n" +
                    "B := [| b_id |\n" +
                    "       | 1    |];\n" +
                    "query { A × B };");

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("product with empty right yields empty result")
        void emptyRight() {
            var rows = collect(
                    "A := [| a_id |\n" +
                    "       | 1    |];\n" +
                    "B := [| b_id |];\n" +
                    "query { A × B };");

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("1×N product has N rows")
        void singleLeftRow() {
            var rows = collect(
                    "A := [| a_id |\n" +
                    "       | 1    |];\n" +
                    "B := [| b_id |\n" +
                    "       | 10   |\n" +
                    "       | 20   |\n" +
                    "       | 30   |];\n" +
                    "query { A × B };");

            assertThat(rows).hasSize(3);
        }
    }

    // ── ThetaJoinNode ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ThetaJoinNode (⨝ / ><)")
    class ThetaJoin {

        @Test
        @DisplayName("inner equi-join matches rows on key")
        void equiJoin() {
            var rows = collect(
                    "Users := [| id | name  |\n" +
                    "           | 1  | Alice |\n" +
                    "           | 2  | Bob   |\n" +
                    "           | 3  | Carol |];\n" +
                    "Orders := [| user_id | amount |\n" +
                    "            | 1       | 100    |\n" +
                    "            | 1       | 200    |\n" +
                    "            | 2       | 50     |];\n" +
                    "query { Users ⨝ Users.id = Orders.user_id Orders };");

            assertThat(rows).hasSize(3);
            assertThat(rows).extracting(r -> r.get("name").asDisplayString())
                    .containsExactly("Alice", "Alice", "Bob");
        }

        @Test
        @DisplayName("no matching rows produces empty result")
        void noMatches() {
            var rows = collect(
                    "A := [| id | val |\n" +
                    "       | 1  | 10  |];\n" +
                    "B := [| fk | val2 |\n" +
                    "       | 99 | 99   |];\n" +
                    "query { A ⨝ A.id = B.fk B };");

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("theta join on inequality keeps rows satisfying condition")
        void inequalityCondition() {
            var rows = collect(
                    "Prices := [| pid | price |\n" +
                    "            | 1   | 10    |\n" +
                    "            | 2   | 20    |\n" +
                    "            | 3   | 30    |];\n" +
                    "Limits := [| lid | max_price |\n" +
                    "            | A   | 25        |];\n" +
                    "query { Prices ⨝ Prices.price <= Limits.max_price Limits };");

            // price <= 25: rows with price 10 and 20
            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(r -> r.get("price").asDisplayString())
                    .containsExactlyInAnyOrder("10", "20");
        }

        @Test
        @DisplayName("output schema contains columns from both sides")
        void outputSchemaContainsBothSides() {
            var rows = collect(
                    "A := [| a_id | a_name |\n" +
                    "       | 1    | alpha  |];\n" +
                    "B := [| b_fk | b_desc |\n" +
                    "       | 1    | beta   |];\n" +
                    "query { A ⨝ A.a_id = B.b_fk B };");

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).schema().columns())
                    .extracting(c -> c.name())
                    .containsExactly("a_id", "a_name", "b_fk", "b_desc");
        }

        @Test
        @DisplayName("theta join on empty left returns empty")
        void emptyLeft() {
            var rows = collect(
                    "A := [| id | val |];\n" +
                    "B := [| fk | val2 |\n" +
                    "       | 1  | 10   |];\n" +
                    "query { A ⨝ A.id = B.fk B };");

            assertThat(rows).isEmpty();
        }
    }

    // ── NaturalJoinNode ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("NaturalJoinNode (⋈ / JOIN)")
    class NaturalJoin {

        @Test
        @DisplayName("natural join on shared column drops duplicate column")
        void dropsSharedColumn() {
            var rows = collect(
                    "Employees := [| emp_id | name  |\n" +
                    "               | 1      | Alice |\n" +
                    "               | 2      | Bob   |\n" +
                    "               | 3      | Carol |];\n" +
                    "Depts := [| emp_id | dept |\n" +
                    "           | 1      | eng  |\n" +
                    "           | 2      | mkt  |];\n" +
                    "query { Employees ⋈ Depts };");

            // Carol (emp_id=3) has no match; output schema has 3 cols not 4
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).schema().columns())
                    .extracting(c -> c.name())
                    .containsExactly("emp_id", "name", "dept");
        }

        @Test
        @DisplayName("matches a temporal-shaped string against the TIMESTAMP it denotes")
        void matchesTemporalStringAgainstTimestamp() {
            // Inline-table cells infer as STRING (issue #277), so a natural join can
            // put an ISO-8601 string opposite a real TIMESTAMP. ValueComparator
            // coerces the string and calls the two equal; the hash bucket has to
            // agree, or the row is dropped with no error.
            var rows = collect(
                    "Events := [| eid | at                  |\n" +
                    "            | 1   | 2024-01-15T10:00:00 |];\n" +
                    "Marks := [| mid | m |\n" +
                    "           | 9   | x |];\n" +
                    "Typed := { \u03c0 mid, TIMESTAMP '2024-01-15T10:00:00Z' -> at (Marks) };\n" +
                    "query { Events \u22c8 Typed };");

            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()).hasValue("mid", "9");
        }

        @Test
        @DisplayName("a non-matching timestamp still does not match")
        void nonMatchingTimestampDoesNotMatch() {
            var rows = collect(
                    "Events := [| eid | at                  |\n" +
                    "            | 1   | 2024-01-15T11:00:00 |];\n" +
                    "Marks := [| mid | m |\n" +
                    "           | 9   | x |];\n" +
                    "Typed := { \u03c0 mid, TIMESTAMP '2024-01-15T10:00:00Z' -> at (Marks) };\n" +
                    "query { Events \u22c8 Typed };");

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("natural join values are correct")
        void valuesCorrect() {
            var rows = collect(
                    "A := [| id | val |\n" +
                    "       | 1  | 10  |\n" +
                    "       | 2  | 20  |];\n" +
                    "B := [| id | extra |\n" +
                    "       | 1  | 100   |\n" +
                    "       | 3  | 300   |];\n" +
                    "query { A ⋈ B };");

            // Only id=1 matches
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("id", "1")
                    .hasValue("val", "10")
                    .hasValue("extra", "100");
        }

        @Test
        @DisplayName("natural join on multiple shared columns requires all to match")
        void multipleSharedColumns() {
            var rows = collect(
                    "X := [| dept | role | name  |\n" +
                    "       | eng  | dev  | Alice |\n" +
                    "       | mkt  | mgr  | Bob   |];\n" +
                    "Y := [| dept | role | bonus |\n" +
                    "       | eng  | dev  | 1000  |\n" +
                    "       | eng  | mgr  | 500   |];\n" +
                    "query { X ⋈ Y };");

            // Only eng/dev row matches
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("name", "Alice")
                    .hasValue("bonus", "1000");
        }

        @Test
        @DisplayName("empty left returns empty result")
        void emptyLeft() {
            var rows = collect(
                    "A := [| id | val |];\n" +
                    "B := [| id | extra |\n" +
                    "       | 1  | 100  |];\n" +
                    "query { A ⋈ B };");

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("empty right returns empty result")
        void emptyRight() {
            var rows = collect(
                    "A := [| id | val |\n" +
                    "       | 1  | 10  |];\n" +
                    "B := [| id | extra |];\n" +
                    "query { A ⋈ B };");

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("no common columns produces empty result")
        void noCommonColumns() {
            var rows = collect(
                    "A := [| a_id | a_val |\n" +
                    "       | 1    | x     |];\n" +
                    "B := [| b_id | b_val |\n" +
                    "       | 1    | y     |];\n" +
                    "query { A ⋈ B };");

            // naturalJoinMatches returns false when there are no common columns
            assertThat(rows).isEmpty();
        }
    }

    // ── LeftOuterJoinNode ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("LeftOuterJoinNode (⟕ / |><)")
    class LeftOuterJoin {

        @Test
        @DisplayName("matched left rows are included with right data")
        void matchedRowsIncluded() {
            var rows = collect(
                    "Users := [| id | name  |\n" +
                    "           | 1  | Alice |\n" +
                    "           | 2  | Bob   |];\n" +
                    "Orders := [| user_id | amount |\n" +
                    "            | 1       | 100    |\n" +
                    "            | 2       | 200    |];\n" +
                    "query { Users ⟕ Users.id = Orders.user_id Orders };");

            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(r -> r.get("name").asDisplayString())
                    .containsExactly("Alice", "Bob");
            assertThat(rows).extracting(r -> r.get("amount").asDisplayString())
                    .containsExactly("100", "200");
        }

        @Test
        @DisplayName("unmatched left rows appear with NULLs for right columns")
        void unmatchedLeftHasNulls() {
            var rows = collect(
                    "Users := [| id | name  |\n" +
                    "           | 1  | Alice |\n" +
                    "           | 2  | Bob   |\n" +
                    "           | 3  | Carol |];\n" +
                    "Orders := [| user_id | amount |\n" +
                    "            | 1       | 100    |];\n" +
                    "query { Users ⟕ Users.id = Orders.user_id Orders };");

            assertThat(rows).hasSize(3);
            // Alice has a match
            Row alice = rows.get(0);
            assertThat(alice).hasValue("name", "Alice");
            assertThat(alice.get("amount").isNull()).isFalse();
            // Bob has no match → NULLs for right columns
            Row bob = rows.get(1);
            assertThat(bob).hasValue("name", "Bob");
            assertThat(bob.get("user_id").isNull()).isTrue();
            assertThat(bob.get("amount").isNull()).isTrue();
            // Carol same
            Row carol = rows.get(2);
            assertThat(carol).hasValue("name", "Carol");
            assertThat(carol.get("amount").isNull()).isTrue();
        }

        @Test
        @DisplayName("one left row with multiple right matches produces multiple output rows")
        void oneToMany() {
            var rows = collect(
                    "Users := [| id | name  |\n" +
                    "           | 1  | Alice |];\n" +
                    "Orders := [| user_id | amount |\n" +
                    "            | 1       | 100    |\n" +
                    "            | 1       | 200    |\n" +
                    "            | 1       | 300    |];\n" +
                    "query { Users ⟕ Users.id = Orders.user_id Orders };");

            assertThat(rows).hasSize(3);
            assertThat(rows).allMatch(r -> r.get("name").asDisplayString().equals("Alice"));
        }

        @Test
        @DisplayName("empty right side pads all left rows with NULLs")
        void emptyRight() {
            var rows = collect(
                    "Users := [| id | name  |\n" +
                    "           | 1  | Alice |\n" +
                    "           | 2  | Bob   |];\n" +
                    "Orders := [| user_id | amount |];\n" +
                    "query { Users ⟕ Users.id = Orders.user_id Orders };");

            assertThat(rows).hasSize(2);
            assertThat(rows).allMatch(r -> r.get("user_id").isNull() && r.get("amount").isNull());
        }

        @Test
        @DisplayName("empty left side produces empty result")
        void emptyLeft() {
            var rows = collect(
                    "Users := [| id | name |];\n" +
                    "Orders := [| user_id | amount |\n" +
                    "            | 1       | 100    |];\n" +
                    "query { Users ⟕ Users.id = Orders.user_id Orders };");

            assertThat(rows).isEmpty();
        }
    }

    // ── RightOuterJoinNode ────────────────────────────────────────────────────

    @Nested
    @DisplayName("RightOuterJoinNode (⟖ / ><|)")
    class RightOuterJoin {

        @Test
        @DisplayName("matched right rows are included with left data")
        void matchedRowsIncluded() {
            var rows = collect(
                    "Users := [| id | name  |\n" +
                    "           | 1  | Alice |\n" +
                    "           | 2  | Bob   |];\n" +
                    "Orders := [| user_id | amount |\n" +
                    "            | 1       | 100    |\n" +
                    "            | 2       | 200    |];\n" +
                    "query { Users ⟖ Users.id = Orders.user_id Orders };");

            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(r -> r.get("amount").asDisplayString())
                    .containsExactlyInAnyOrder("100", "200");
        }

        @Test
        @DisplayName("unmatched right rows appear with NULLs for left columns")
        void unmatchedRightHasNulls() {
            var rows = collect(
                    "Users := [| id | name  |\n" +
                    "           | 1  | Alice |];\n" +
                    "Orders := [| user_id | amount |\n" +
                    "            | 1       | 100    |\n" +
                    "            | 99      | 999    |];\n" +
                    "query { Users ⟖ Users.id = Orders.user_id Orders };");

            assertThat(rows).hasSize(2);
            // Find the unmatched order row
            Row unmatched = rows.stream()
                    .filter(r -> r.get("user_id").asDisplayString().equals("99"))
                    .findFirst().orElseThrow();
            assertThat(unmatched.get("id").isNull()).isTrue();
            assertThat(unmatched.get("name").isNull()).isTrue();
            assertThat(unmatched).hasValue("amount", "999");
        }

        @Test
        @DisplayName("empty left side pads all right rows with NULLs")
        void emptyLeft() {
            var rows = collect(
                    "Users := [| id | name |];\n" +
                    "Orders := [| user_id | amount |\n" +
                    "            | 1       | 100    |\n" +
                    "            | 2       | 200    |];\n" +
                    "query { Users ⟖ Users.id = Orders.user_id Orders };");

            assertThat(rows).hasSize(2);
            assertThat(rows).allMatch(r -> r.get("id").isNull() && r.get("name").isNull());
        }

        @Test
        @DisplayName("empty right side produces empty result")
        void emptyRight() {
            var rows = collect(
                    "Users := [| id | name  |\n" +
                    "           | 1  | Alice |];\n" +
                    "Orders := [| user_id | amount |];\n" +
                    "query { Users ⟖ Users.id = Orders.user_id Orders };");

            assertThat(rows).isEmpty();
        }
    }

    // ── FullOuterJoinNode ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("FullOuterJoinNode (⟗ / |><|)")
    class FullOuterJoin {

        @Test
        @DisplayName("matched rows appear once with all columns populated")
        void matchedRowsPopulated() {
            var rows = collect(
                    "Users := [| id | name  |\n" +
                    "           | 1  | Alice |\n" +
                    "           | 2  | Bob   |];\n" +
                    "Orders := [| user_id | amount |\n" +
                    "            | 1       | 100    |\n" +
                    "            | 2       | 200    |];\n" +
                    "query { Users ⟗ Users.id = Orders.user_id Orders };");

            assertThat(rows).hasSize(2);
            assertThat(rows).noneMatch(r -> r.get("name").isNull() || r.get("amount").isNull());
        }

        @Test
        @DisplayName("unmatched left rows padded right, unmatched right rows padded left")
        void bothSidesPreserved() {
            var rows = collect(
                    "Users := [| id | name  |\n" +
                    "           | 1  | Alice |\n" +
                    "           | 2  | Bob   |];\n" +
                    "Orders := [| user_id | amount |\n" +
                    "            | 1       | 100    |\n" +
                    "            | 99      | 999    |];\n" +
                    "query { Users ⟗ Users.id = Orders.user_id Orders };");

            assertThat(rows).hasSize(3);

            // Matched row: Alice + 100
            Row alice = rows.stream()
                    .filter(r -> "Alice".equals(r.get("name").asDisplayString()))
                    .findFirst().orElseThrow();
            assertThat(alice).hasValue("amount", "100");
            assertThat(alice.get("id").isNull()).isFalse();

            // Left-only row: Bob (no matching order)
            Row bob = rows.stream()
                    .filter(r -> "Bob".equals(r.get("name").asDisplayString()))
                    .findFirst().orElseThrow();
            assertThat(bob.get("user_id").isNull()).isTrue();
            assertThat(bob.get("amount").isNull()).isTrue();

            // Right-only row: unmatched order 99
            Row orphanOrder = rows.stream()
                    .filter(r -> r.get("id").isNull())
                    .findFirst().orElseThrow();
            assertThat(orphanOrder).hasValue("user_id", "99")
                    .hasValue("amount", "999");
        }

        @Test
        @DisplayName("empty left: all right rows preserved with NULL left columns")
        void emptyLeft() {
            var rows = collect(
                    "Users := [| id | name |];\n" +
                    "Orders := [| user_id | amount |\n" +
                    "            | 1       | 100    |\n" +
                    "            | 2       | 200    |];\n" +
                    "query { Users ⟗ Users.id = Orders.user_id Orders };");

            assertThat(rows).hasSize(2);
            assertThat(rows).allMatch(r -> r.get("id").isNull() && r.get("name").isNull());
        }

        @Test
        @DisplayName("empty right: all left rows preserved with NULL right columns")
        void emptyRight() {
            var rows = collect(
                    "Users := [| id | name  |\n" +
                    "           | 1  | Alice |\n" +
                    "           | 2  | Bob   |];\n" +
                    "Orders := [| user_id | amount |];\n" +
                    "query { Users ⟗ Users.id = Orders.user_id Orders };");

            assertThat(rows).hasSize(2);
            assertThat(rows).allMatch(r -> r.get("user_id").isNull() && r.get("amount").isNull());
        }

        @Test
        @DisplayName("full outer join on disjoint sets preserves all rows")
        void disjointSets() {
            var rows = collect(
                    "A := [| aid | aval |\n" +
                    "       | 1   | x    |\n" +
                    "       | 2   | y    |];\n" +
                    "B := [| bfk | bval |\n" +
                    "       | 3   | p    |\n" +
                    "       | 4   | q    |];\n" +
                    "query { A ⟗ A.aid = B.bfk B };");

            // No matches → 2 left rows (null right) + 2 right rows (null left) = 4
            assertThat(rows).hasSize(4);
            assertThat(rows.stream().filter(r -> r.get("aid").isNull()).count()).isEqualTo(2);
            assertThat(rows.stream().filter(r -> r.get("bfk").isNull()).count()).isEqualTo(2);
        }
    }

    // ── SemiJoinNode ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SemiJoinNode (⋉ / SEMI)")
    class SemiJoin {

        @Test
        @DisplayName("returns only left rows that have at least one match")
        void returnsMatchingLeft() {
            var rows = collect(
                    "Customers := [| cust_id | name  |\n" +
                    "               | 1       | Alice |\n" +
                    "               | 2       | Bob   |\n" +
                    "               | 3       | Carol |];\n" +
                    "Orders := [| order_id | customer_id | amount |\n" +
                    "            | 101      | 1           | 100    |\n" +
                    "            | 102      | 1           | 200    |\n" +
                    "            | 103      | 3           | 300    |];\n" +
                    "query { Customers ⋉ Customers.cust_id = Orders.customer_id Orders };");

            // Alice (1) and Carol (3) have orders; Bob (2) does not
            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(r -> r.get("name").asDisplayString())
                    .containsExactlyInAnyOrder("Alice", "Carol");
        }

        @Test
        @DisplayName("output schema is left schema only (no right columns)")
        void outputIsLeftSchemaOnly() {
            var rows = collect(
                    "Customers := [| cust_id | name  |\n" +
                    "               | 1       | Alice |];\n" +
                    "Orders := [| order_id | customer_id | amount |\n" +
                    "            | 101      | 1           | 100    |];\n" +
                    "query { Customers ⋉ Customers.cust_id = Orders.customer_id Orders };");

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).width()).isEqualTo(2);  // cust_id, name only
            assertThat(rows.get(0).schema().columns())
                    .extracting(c -> c.name())
                    .containsExactly("cust_id", "name");
        }

        @Test
        @DisplayName("each matching left row appears exactly once (no duplicates from multiple right matches)")
        void noDuplicatesFromMultipleMatches() {
            var rows = collect(
                    "Users := [| uid | uname |\n" +
                    "           | 1   | Alice |];\n" +
                    "Tags := [| tag_uid | tag |\n" +
                    "          | 1       | a   |\n" +
                    "          | 1       | b   |\n" +
                    "          | 1       | c   |];\n" +
                    "query { Users ⋉ Users.uid = Tags.tag_uid Tags };");

            // Alice matches 3 times but semi-join deduplicates to 1
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("uname", "Alice");
        }

        @Test
        @DisplayName("no matches returns empty result")
        void noMatchesEmpty() {
            var rows = collect(
                    "Users := [| uid | uname |\n" +
                    "           | 1   | Alice |\n" +
                    "           | 2   | Bob   |];\n" +
                    "Tags := [| tag_uid | tag |\n" +
                    "          | 99      | x   |];\n" +
                    "query { Users ⋉ Users.uid = Tags.tag_uid Tags };");

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("empty right returns empty result")
        void emptyRightEmpty() {
            var rows = collect(
                    "Users := [| uid | uname |\n" +
                    "           | 1   | Alice |];\n" +
                    "Tags := [| tag_uid | tag |];\n" +
                    "query { Users ⋉ Users.uid = Tags.tag_uid Tags };");

            assertThat(rows).isEmpty();
        }
    }

    // ── AntiJoinNode ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AntiJoinNode (▷ / ANTI)")
    class AntiJoin {

        @Test
        @DisplayName("returns only left rows with no match in right")
        void returnsUnmatchedLeft() {
            var rows = collect(
                    "Customers := [| cust_id | name  |\n" +
                    "               | 1       | Alice |\n" +
                    "               | 2       | Bob   |\n" +
                    "               | 3       | Carol |];\n" +
                    "Orders := [| order_id | customer_id | amount |\n" +
                    "            | 101      | 1           | 100    |\n" +
                    "            | 103      | 3           | 300    |];\n" +
                    "query { Customers ▷ Customers.cust_id = Orders.customer_id Orders };");

            // Bob (2) has no orders
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("name", "Bob");
        }

        @Test
        @DisplayName("output schema is left schema only")
        void outputIsLeftSchemaOnly() {
            var rows = collect(
                    "Users := [| uid | uname |\n" +
                    "           | 1   | Alice |\n" +
                    "           | 2   | Bob   |];\n" +
                    "Tags := [| tag_uid | tag |\n" +
                    "          | 1       | a   |];\n" +
                    "query { Users ▷ Users.uid = Tags.tag_uid Tags };");

            assertThat(rows).hasSize(1); // only Bob
            assertThat(rows.get(0).schema().columns())
                    .extracting(c -> c.name())
                    .containsExactly("uid", "uname");
        }

        @Test
        @DisplayName("all left rows match: empty result")
        void allMatchEmpty() {
            var rows = collect(
                    "Users := [| uid | uname |\n" +
                    "           | 1   | Alice |\n" +
                    "           | 2   | Bob   |];\n" +
                    "Tags := [| tag_uid | tag |\n" +
                    "          | 1       | a   |\n" +
                    "          | 2       | b   |];\n" +
                    "query { Users ▷ Users.uid = Tags.tag_uid Tags };");

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("empty right: all left rows returned")
        void emptyRightReturnsAll() {
            var rows = collect(
                    "Users := [| uid | uname |\n" +
                    "           | 1   | Alice |\n" +
                    "           | 2   | Bob   |];\n" +
                    "Tags := [| tag_uid | tag |];\n" +
                    "query { Users ▷ Users.uid = Tags.tag_uid Tags };");

            assertThat(rows).hasSize(2);
        }

        @Test
        @DisplayName("anti-join is complement of semi-join")
        void complementOfSemiJoin() {
            String data =
                    "Employees := [| eid | ename |\n" +
                    "               | 1   | Alice |\n" +
                    "               | 2   | Bob   |\n" +
                    "               | 3   | Carol |\n" +
                    "               | 4   | Dave  |];\n" +
                    "Projects := [| pid | employee_id |\n" +
                    "              | 10  | 1           |\n" +
                    "              | 20  | 3           |];\n";

            var semiRows  = collect(data + "query { Employees ⋉ Employees.eid = Projects.employee_id Projects };");
            var antiRows  = collect(data + "query { Employees ▷ Employees.eid = Projects.employee_id Projects };");

            // Together they must cover all 4 employees, with no overlap
            assertThat(semiRows.size() + antiRows.size()).isEqualTo(4);
            var semiIds = semiRows.stream()
                    .map(r -> r.get("eid").asDisplayString())
                    .collect(Collectors.toSet());
            var antiIds = antiRows.stream()
                    .map(r -> r.get("eid").asDisplayString())
                    .collect(Collectors.toSet());
            assertThat(semiIds).doesNotContainAnyElementsOf(antiIds);
        }
    }

    // ── Composed join scenarios ───────────────────────────────────────────────

    @Nested
    @DisplayName("Composed join scenarios")
    class Composed {

        @Test
        @DisplayName("theta join followed by selection")
        void joinThenFilter() {
            var rows = collect(
                    "Users := [| id | name  |\n" +
                    "           | 1  | Alice |\n" +
                    "           | 2  | Bob   |];\n" +
                    "Orders := [| user_id | amount |\n" +
                    "            | 1       | 50     |\n" +
                    "            | 1       | 200    |\n" +
                    "            | 2       | 100    |];\n" +
                    "Joined := { Users ⨝ Users.id = Orders.user_id Orders };\n" +
                    "query { σ amount > 75 (Joined) };");

            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(r -> r.get("amount").asDisplayString())
                    .containsExactlyInAnyOrder("200", "100");
        }

        @Test
        @DisplayName("theta join followed by projection")
        void joinThenProject() {
            var rows = collect(
                    "Users := [| id | name | age |\n" +
                    "           | 1  | Alice | 30  |\n" +
                    "           | 2  | Bob   | 25  |];\n" +
                    "Orders := [| user_id | amount |\n" +
                    "            | 1       | 100    |\n" +
                    "            | 2       | 200    |];\n" +
                    "Joined := { Users ⨝ Users.id = Orders.user_id Orders };\n" +
                    "query { π name, amount (Joined) };");

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).width()).isEqualTo(2);
            assertThat(rows).extracting(r -> r.get("name").asDisplayString())
                    .containsExactly("Alice", "Bob");
        }

        @Test
        @DisplayName("three-way product: rows = A×B×C")
        void threeWayProduct() {
            var rows = collect(
                    "A := [| a |\n" +
                    "       | 1 |\n" +
                    "       | 2 |];\n" +
                    "B := [| b |\n" +
                    "       | 3 |\n" +
                    "       | 4 |];\n" +
                    "C := [| c |\n" +
                    "       | 5 |];\n" +
                    "AB := { A × B };\n" +
                    "query { AB × C };");

            assertThat(rows).hasSize(4);  // 2 × 2 × 1
        }

        @Test
        @DisplayName("semi-join then projection reduces columns further")
        void semiJoinThenProject() {
            var rows = collect(
                    "Customers := [| cust_id | name  | city   |\n" +
                    "               | 1       | Alice | London |\n" +
                    "               | 2       | Bob   | Paris  |\n" +
                    "               | 3       | Carol | Berlin |];\n" +
                    "Orders := [| order_id | customer_id |\n" +
                    "            | 101      | 1           |\n" +
                    "            | 102      | 3           |];\n" +
                    "HasOrders := { Customers ⋉ Customers.cust_id = Orders.customer_id Orders };\n" +
                    "query { π name, city (HasOrders) };");

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).width()).isEqualTo(2);
            assertThat(rows).extracting(r -> r.get("name").asDisplayString())
                    .containsExactlyInAnyOrder("Alice", "Carol");
        }

        @Test
        @DisplayName("semi-join with clashing column names still evaluates predicate correctly")
        void semiJoinRenamesClashingColumnInEvalSchema() {
            // Both A and B have an 'id' column.
            // concatSchemas must rename B.id → 'id_r' (exercises lines 744-749).
            // Predicate uses A.name (non-clashing) so it resolves correctly in the concat row.
            var rows = collect(
                    "A := [| id | name  |\n" +
                    "       | 1  | Alice |\n" +
                    "       | 2  | Bob   |];\n" +
                    "B := [| id | score |\n" +
                    "       | 1  | 100   |];\n" +
                    "query { A ⋉ A.name = \"Alice\" B };");

            // Only Alice matches the predicate (name = "Alice")
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("name", "Alice");
        }

        @Test
        @DisplayName("semi-join with double-clash uses counter suffix (id_r1) in eval schema")
        void semiJoinDoubleClashUsesCounterSuffix() {
            // A has (id, id_r, name); B has (id, score).
            // concatSchemas tries 'id_r' for B.id, but id_r already exists in A → uses 'id_r1'
            // (exercises the counter-suffix loop on line 747).
            // Predicate uses A.name (non-clashing) so it resolves correctly in the concat row.
            var rows = collect(
                    "A := [| id | id_r | name  |\n" +
                    "       | 1  | 99   | Alice |\n" +
                    "       | 2  | 88   | Bob   |];\n" +
                    "B := [| id | score |\n" +
                    "       | 1  | 100   |];\n" +
                    "query { A ⋉ A.name = \"Alice\" B };");

            // Only Alice matches the predicate (name = "Alice")
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("name", "Alice");
        }
    }

    // ── Hash-join semantics (equi-join correctness corner cases) ───────────────

    @Nested
    @DisplayName("Hash-join semantics — equi-join corner cases")
    class HashJoinSemantics {

        @Test
        @DisplayName("numeric keys of differing scale (1 vs 1.0) still join")
        void numericScaleMatches() {
            // The hash key normalises numbers via compareTo (1 == 1.0), unlike
            // scale-sensitive BigDecimal.equals — this is the key correctness guard.
            // Distinct key column names so the predicate truly compares ak vs bk.
            var rows = collect(
                    "A := [| ak  | a_val |\n" +
                    "       | 1   | x     |];\n" +
                    "B := [| bk  | b_val |\n" +
                    "       | 1.0 | y     |];\n" +
                    "query { A ⨝ A.ak = B.bk B };");

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("a_val", "x")
                    .hasValue("b_val", "y");
        }

        @Test
        @DisplayName("duplicate keys on both sides produce the full bucket cross-product")
        void duplicateKeyMultiplicity() {
            var rows = collect(
                    "A := [| k | a |\n" +
                    "       | 1 | a1 |\n" +
                    "       | 1 | a2 |];\n" +
                    "B := [| fk | b |\n" +
                    "       | 1  | b1 |\n" +
                    "       | 1  | b2 |\n" +
                    "       | 2  | b3 |];\n" +
                    "query { A ⨝ A.k = B.fk B };");

            // 2 left × 2 right (key 1) = 4 rows; key-2 right row has no left partner.
            assertThat(rows).hasSize(4);
        }

        @Test
        @DisplayName("mixed predicate: hashes on equality, residual applies the inequality")
        void mixedEqualityAndResidual() {
            var rows = collect(
                    "Users := [| id | name  |\n" +
                    "           | 1  | Alice |\n" +
                    "           | 2  | Bob   |];\n" +
                    "Orders := [| user_id | amount |\n" +
                    "            | 1       | 50     |\n" +
                    "            | 1       | 200    |\n" +
                    "            | 2       | 200    |];\n" +
                    "query { Users ⨝ Users.id = Orders.user_id ∧ amount >= 100 Orders };");

            // id match AND amount >= 100: (Alice,200) and (Bob,200)
            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(r -> r.get("amount").asDisplayString())
                    .containsOnly("200");
        }

        @Test
        @DisplayName("OR condition is not hashable and falls back to nested-loop semantics")
        void orConditionFallback() {
            var rows = collect(
                    "A := [| x | y |\n" +
                    "       | 1 | 9 |\n" +
                    "       | 2 | 8 |];\n" +
                    "B := [| p | q |\n" +
                    "       | 1 | 7 |\n" +
                    "       | 5 | 8 |];\n" +
                    "query { A ⨝ A.x = B.p ∨ A.y = B.q B };");

            // x=p: (1,·,1,·). y=q: (2,8,·,8). → 2 rows.
            assertThat(rows).hasSize(2);
        }

        @Test
        @DisplayName("natural join matches numeric keys of differing scale")
        void naturalJoinScaleMatches() {
            var rows = collect(
                    "A := [| k   | a_val |\n" +
                    "       | 1   | x     |];\n" +
                    "B := [| k   | b_val |\n" +
                    "       | 1.0 | y     |];\n" +
                    "query { A ⋈ B };");

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("a_val", "x");
        }

        @Test
        @DisplayName("left outer join: hashed match plus null-padding for unmatched left rows")
        void leftOuterHashWithNullPad() {
            var rows = collect(
                    "A := [| k | a |\n" +
                    "       | 1 | a1 |\n" +
                    "       | 2 | a2 |];\n" +
                    "B := [| fk | b |\n" +
                    "       | 1  | b1 |];\n" +
                    "query { A ⟕ A.k = B.fk B };");

            assertThat(rows).hasSize(2);
            // one matched row carries b1; the unmatched row (k=2) has a NULL b
            assertThat(rows).filteredOn(r -> !r.get("b").isNull())
                    .extracting(r -> r.get("b").asDisplayString())
                    .containsExactly("b1");
            assertThat(rows).filteredOn(r -> r.get("b").isNull()).hasSize(1);
        }

        @Test
        @DisplayName("semi-join over numeric scale keys matches")
        void semiJoinScaleMatches() {
            var rows = collect(
                    "A := [| ak | a |\n" +
                    "       | 1  | a1 |];\n" +
                    "B := [| bk  | b |\n" +
                    "       | 1.0 | b1 |];\n" +
                    "query { A ⋉ A.ak = B.bk B };");

            assertThat(rows).hasSize(1);
        }

        @Test
        @DisplayName("equi-join on a shared column name (A.k = B.k) compares the two sides, not A.k with itself")
        void sharedColumnNameComparesCorrectSides() {
            // Both inputs expose a column named "k"; the qualifier must route B.k to
            // the right input.  Only A.k=1 truly equals B.k=1 → exactly one match.
            var rows = collect(
                    "A := [| k | a  |\n" +
                    "       | 1 | a1 |\n" +
                    "       | 2 | a2 |];\n" +
                    "B := [| k | b  |\n" +
                    "       | 1 | b1 |\n" +
                    "       | 3 | b3 |];\n" +
                    "query { A ⨝ A.k = B.k B };");

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("a", "a1")
                    .hasValue("b", "b1");
        }

        @Test
        @DisplayName("build side follows the cost estimator: the cheaper inline side is built, the source side is streamed")
        void buildSideFollowsCostEstimator() {
            // Left A is inline (cheapest tier); right B is an external source (costlier).
            // The cost estimator must pick the inline side as the build side and stream
            // the source side — so output order follows B's (connector) row order.
            var bSchema = schema(col("bk", ScalarType.NUMBER), col("b", ScalarType.STRING));
            DataSourceConnector connector = (name, schema) -> Stream.of(
                    row(bSchema, num(2), str("b2")),   // emitted first by the source
                    row(bSchema, num(1), str("b1")));
            List<Row> rows = collectWith(
                    "A := [| k | a  |\n" +
                    "       | 1 | a1 |\n" +
                    "       | 2 | a2 |];\n" +
                    "source B from database {\n" +
                    "    url: \"jdbc:h2:mem\", table: \"b\",\n" +
                    "    schema: { bk: NUMBER, b: STRING }\n" +
                    "};\n" +
                    "query { A ⨝ A.k = B.bk B };", connector);

            // Right-driven order (source streamed) ⇒ the bk=2 row (a2) comes first.
            // Were the source built and the inline side streamed instead, a1 would lead.
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)).hasValue("a", "a2");
            assertThat(rows.get(1)).hasValue("a", "a1");
        }

        @Test
        @DisplayName("full outer join with the build side on the cheaper input preserves both sides' unmatched rows")
        void fullOuterBuildsCheaperSide() {
            // Inline left is cheaper than the source right → build left, stream right.
            var bSchema = schema(col("bk", ScalarType.NUMBER), col("b", ScalarType.STRING));
            DataSourceConnector connector = (name, schema) -> Stream.of(
                    row(bSchema, num(2), str("b2")),
                    row(bSchema, num(3), str("b3")));
            List<Row> rows = collectWith(
                    "A := [| k | a  |\n" +
                    "       | 1 | a1 |\n" +
                    "       | 2 | a2 |];\n" +
                    "source B from database {\n" +
                    "    url: \"jdbc:h2:mem\", table: \"b\",\n" +
                    "    schema: { bk: NUMBER, b: STRING }\n" +
                    "};\n" +
                    "query { A ⟗ A.k = B.bk B };", connector);

            // a2↔b2 match; a1 unmatched (null right); b3 unmatched (null left) → 3 rows.
            assertThat(rows).hasSize(3);
            assertThat(rows).filteredOn(r -> !r.get("a").isNull() && !r.get("b").isNull())
                    .extracting(r -> r.get("a").asDisplayString() + r.get("b").asDisplayString())
                    .containsExactly("a2b2");
            assertThat(rows).filteredOn(r -> !r.get("a").isNull() && r.get("b").isNull())
                    .extracting(r -> r.get("a").asDisplayString()).containsExactly("a1");
            assertThat(rows).filteredOn(r -> r.get("a").isNull() && !r.get("b").isNull())
                    .extracting(r -> r.get("b").asDisplayString()).containsExactly("b3");
        }

        @Test
        @DisplayName("natural join with the build side on the cheaper input is correct")
        void naturalJoinBuildsCheaperSide() {
            // Inline left cheaper than source right → build left, stream right.
            var bSchema = schema(col("k", ScalarType.NUMBER), col("b", ScalarType.STRING));
            DataSourceConnector connector = (name, schema) -> Stream.of(
                    row(bSchema, num(2), str("b2")),
                    row(bSchema, num(1), str("b1")));
            List<Row> rows = collectWith(
                    "A := [| k | a  |\n" +
                    "       | 1 | a1 |\n" +
                    "       | 2 | a2 |\n" +
                    "       | 9 | a9 |];\n" +
                    "source B from database {\n" +
                    "    url: \"jdbc:h2:mem\", table: \"b\",\n" +
                    "    schema: { k: NUMBER, b: STRING }\n" +
                    "};\n" +
                    "query { A ⋈ B };", connector);

            // k=1 and k=2 match; k=9 has no partner. Output columns: k, a, b.
            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(r -> r.get("a").asDisplayString() + r.get("b").asDisplayString())
                    .containsExactlyInAnyOrder("a1b1", "a2b2");
        }
    }

    // ── Non-equi (nested-loop) joins and NULL join keys ────────────────────────

    @Nested
    @DisplayName("Nested-loop joins (non-equi conditions) and NULL keys")
    class NestedLoopAndNullKeys {

        @Test
        @DisplayName("full outer join on an inequality (nested-loop) preserves both sides")
        void fullOuterNonEqui() {
            var rows = collect(
                    "A := [| a |\n" +
                    "       | 1 |\n" +
                    "       | 2 |];\n" +
                    "B := [| b |\n" +
                    "       | 1 |\n" +
                    "       | 3 |];\n" +
                    "query { A ⟗ A.a > B.b B };");

            // a=2>b=1 matches; a=1 unmatched (null b); b=3 unmatched (null a) → 3 rows.
            assertThat(rows).hasSize(3);
            assertThat(rows).filteredOn(r -> !r.get("a").isNull() && !r.get("b").isNull())
                    .extracting(r -> r.get("a").asDisplayString() + ">" + r.get("b").asDisplayString())
                    .containsExactly("2>1");
            assertThat(rows).filteredOn(r -> r.get("b").isNull())
                    .extracting(r -> r.get("a").asDisplayString()).containsExactly("1");
            assertThat(rows).filteredOn(r -> r.get("a").isNull())
                    .extracting(r -> r.get("b").asDisplayString()).containsExactly("3");
        }

        @Test
        @DisplayName("anti-join on an inequality (nested-loop) keeps left rows with no partner")
        void antiNonEqui() {
            var rows = collect(
                    "A := [| a |\n" +
                    "       | 1 |\n" +
                    "       | 2 |\n" +
                    "       | 3 |];\n" +
                    "B := [| b |\n" +
                    "       | 2 |];\n" +
                    "query { A ▷ A.a > B.b B };");

            // a>2 holds only for a=3 → a=3 dropped; a=1, a=2 kept.
            assertThat(rows).extracting(r -> r.get("a").asDisplayString())
                    .containsExactlyInAnyOrder("1", "2");
        }

        @Test
        @DisplayName("semi-join on an inequality (nested-loop) keeps left rows with a partner")
        void semiNonEqui() {
            var rows = collect(
                    "A := [| a |\n" +
                    "       | 1 |\n" +
                    "       | 2 |\n" +
                    "       | 3 |];\n" +
                    "B := [| b |\n" +
                    "       | 2 |];\n" +
                    "query { A ⋉ A.a > B.b B };");

            // a>2 holds only for a=3.
            assertThat(rows).extracting(r -> r.get("a").asDisplayString()).containsExactly("3");
        }

        @Test
        @DisplayName("a NULL join key never matches (left-outer NULLs excluded from a later hash join)")
        void nullKeyNeverJoins() {
            // A.k=2 has no B partner, so the left outer leaves bk NULL for that row;
            // the subsequent equi-join on bk must skip it (NULL never joins).
            var rows = collect(
                    "A  := [| k | a  |\n" +
                    "        | 1 | a1 |\n" +
                    "        | 2 | a2 |];\n" +
                    "B  := [| bk | b  |\n" +
                    "        | 1  | b1 |];\n" +
                    "LO := { A ⟕ A.k = B.bk B };\n" +
                    "C  := [| ck | c  |\n" +
                    "        | 1  | c1 |];\n" +
                    "query { LO ⨝ LO.bk = C.ck C };");

            // Only the a1 row (bk=1) joins C; the a2 row (bk NULL) is excluded.
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("a", "a1")
                    .hasValue("c", "c1");
        }
    }

    // ── PairwiseUniversalNode ─────────────────────────────────────────────────

    @Nested
    @DisplayName("PairwiseUniversalNode (USEMI)")
    class PairwiseUniversal {

        @Test
        @DisplayName("left row kept only when ALL right rows satisfy the condition")
        void allMatchPasses() {
            var rows = collect(
                    "Products := [| pid | price |\n" +
                    "               | 1   | 5     |\n" +
                    "               | 2   | 20    |\n" +
                    "               | 3   | 8     |];\n" +
                    "Limits := [| lid | max_price |\n" +
                    "            | A   | 10        |\n" +
                    "            | B   | 15        |];\n" +
                    "query { Products USEMI Products.price < Limits.max_price Limits };");

            // Products.price < ALL Limits.max_price means price < 10 (the min)
            // price=5 → 5<10 ✓ and 5<15 ✓ → kept
            // price=20 → 20<10 ✗ → filtered
            // price=8 → 8<10 ✓ and 8<15 ✓ → kept
            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(r -> r.get("price").asDisplayString())
                    .containsExactlyInAnyOrder("5", "8");
        }

        @Test
        @DisplayName("left row filtered when even one right row fails the condition")
        void oneFalseFilters() {
            var rows = collect(
                    "Offers := [| oid | offer_price |\n" +
                    "             | 1   | 12          |\n" +
                    "             | 2   | 9           |];\n" +
                    "Thresholds := [| tid | thresh |\n" +
                    "               | X   | 10     |\n" +
                    "               | Y   | 20     |];\n" +
                    "query { Offers USEMI Offers.offer_price < Thresholds.thresh Thresholds };");

            // offer_price=12: 12<10 ✗ → filtered (fails thresh X)
            // offer_price=9: 9<10 ✓ AND 9<20 ✓ → kept
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("offer_price", "9");
        }

        @Test
        @DisplayName("empty right relation: all left rows pass (vacuous truth)")
        void emptyRightAllPass() {
            var rows = collect(
                    "L := [| id | val |\n" +
                    "       | 1  | 10  |\n" +
                    "       | 2  | 20  |];\n" +
                    "R := [| fk | val2 |];\n" +
                    "query { L USEMI L.id = R.fk R };");

            // No right rows → vacuously true → all left rows returned
            assertThat(rows).hasSize(2);
        }

        @Test
        @DisplayName("empty left relation: result is empty")
        void emptyLeftEmpty() {
            var rows = collect(
                    "L := [| id | val |];\n" +
                    "R := [| fk | val2 |\n" +
                    "       | 1  | 10   |];\n" +
                    "query { L USEMI L.id = R.fk R };");

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("output schema contains only left columns")
        void outputSchemaIsLeftOnly() {
            var rows = collect(
                    "L := [| lid | lname |\n" +
                    "       | 1   | alpha |];\n" +
                    "R := [| rid | rname |\n" +
                    "       | 1   | beta  |];\n" +
                    "query { L USEMI L.lid = R.rid R };");

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).schema().columns())
                    .extracting(c -> c.name())
                    .containsExactly("lid", "lname");
        }

        @Test
        @DisplayName("strict NULL: a NULL comparison result disqualifies that left row")
        void strictNullDisqualifies() {
            // A left row is kept only if ALL right rows satisfy the condition.
            // A NULL result (e.g. comparing against a NULL value) is treated as UNKNOWN = false.
            // Use an outer join to introduce NULLs into the right side.
            var rows = collect(
                    "Vals := [| v |\n" +
                    "          | 5 |\n" +
                    "          | 1 |];\n" +
                    "Caps := [| base |\n" +
                    "          | 3   |];\n" +
                    // NullCaps has: base=3 and base=NULL (via left outer join with no-match)
                    "Extras := [| extra |\n" +
                    "            | 99    |];\n" +
                    "NullCaps := { Caps ⟕ Caps.base = Extras.extra Extras };\n" +
                    // NullCaps now has rows: {base=3, extra=99}... wait, that would match.
                    // Let me construct NULLs differently: outer join Caps (base=3) against
                    // EmptyExtras to get extra=NULL.
                    "EmptyExtras := [| extra |];\n" +
                    "NullRight := { Caps ⟕ Caps.base = EmptyExtras.extra EmptyExtras };\n" +
                    // NullRight now has: {base=3, extra=NULL}
                    // Vals.v = NullRight.extra compares against NULL → UNKNOWN → false
                    // So all Vals rows are filtered (none pass allMatch over a single NULL row)
                    "query { Vals USEMI Vals.v = NullRight.extra NullRight };");

            // NullRight has one row with extra=NULL; NULL comparison → UNKNOWN → not satisfied.
            // All left rows are filtered out.
            assertThat(rows).isEmpty();
        }
    }
}

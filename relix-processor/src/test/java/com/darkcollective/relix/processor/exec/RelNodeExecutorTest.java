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
import com.darkcollective.relix.processor.internal.DataSourceConnector;
import com.darkcollective.relix.processor.internal.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RelNodeExecutor — streaming operator tests")
final class RelNodeExecutorTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    // ── helpers ───────────────────────────────────────────────────────────────

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget named -> rel(named.name());
            case ExpressionQueryTarget expr -> expr.expression();
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

    // ── RelationNode — inline rows ─────────────────────────────────────────────

    @Nested
    @DisplayName("RelationNode — inline")
    class InlineRelation {

        @Test
        @DisplayName("returns all rows from an inline table")
        void allRows() {
            var rows = collect(
                    "Users := [| id | name |\n" +
                    "           | 1  | Alice |\n" +
                    "           | 2  | Bob   |];\n" +
                    "query Users;");

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)).hasValue("id", "1")
                    .hasValue("name", "Alice");
            assertThat(rows.get(1)).hasValue("id", "2")
                    .hasValue("name", "Bob");
        }

        @Test
        @DisplayName("returns empty stream for an empty inline table")
        void emptyTable() {
            var rows = collect(
                    "Empty := [| id | name |];\n" +
                    "query Empty;");

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("row schema reflects declared column names")
        void rowSchemaColumns() {
            var rows = collect(
                    "T := [| x | y | z |\n" +
                    "       | 1 | 2 | 3 |];\n" +
                    "query T;");

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).schema().columns())
                    .extracting(c -> c.name())
                    .containsExactly("x", "y", "z");
        }

        @Test
        @DisplayName("boolean-like strings in inline tables are stored as strings")
        void booleanLiteralStoredAsString() {
            var rows = collect(
                    "Flags := [| flag |\n" +
                    "           | true  |\n" +
                    "           | false |];\n" +
                    "query Flags;");

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).get("flag")).isEqualTo(str("true"));
            assertThat(rows.get(1).get("flag")).isEqualTo(str("false"));
        }
    }

    // ── RelationNode — query symbol (view) ────────────────────────────────────

    @Nested
    @DisplayName("RelationNode — query-relation (view)")
    class QueryRelation {

        @Test
        @DisplayName("executes view body recursively")
        void viewBody() {
            var rows = collect(
                    "Users := [| id | name |\n" +
                    "           | 1  | Alice |\n" +
                    "           | 2  | Bob   |];\n" +
                    "Active := { Users };\n" +
                    "query Active;");

            assertThat(rows).hasSize(2);
        }

        @Test
        @DisplayName("view composed of a selection")
        void viewWithSelection() {
            var rows = collect(
                    "Users := [| id | name |\n" +
                    "           | 1  | Alice |\n" +
                    "           | 2  | Bob   |];\n" +
                    "Alice := { σ name = \"Alice\" (Users) };\n" +
                    "query Alice;");

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("name", "Alice");
        }
    }

    // ── SelectionNode ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SelectionNode (σ / SELECT)")
    class Selection {

        @Test
        @DisplayName("filters rows by equality predicate")
        void equality() {
            var rows = collect(
                    "Users := [| id | name |\n" +
                    "           | 1  | Alice |\n" +
                    "           | 2  | Bob   |\n" +
                    "           | 3  | Alice |];\n" +
                    "query { σ name = \"Alice\" (Users) };");

            assertThat(rows).hasSize(2);
            assertThat(rows).allMatch(r -> r.get("name").asDisplayString().equals("Alice"));
        }

        @Test
        @DisplayName("returns empty stream when no rows match")
        void noMatch() {
            var rows = collect(
                    "Users := [| id | name |\n" +
                    "           | 1  | Alice |];\n" +
                    "query { σ name = \"Charlie\" (Users) };");

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("passes all rows when all match")
        void allMatch() {
            var rows = collect(
                    "Items := [| id | status |\n" +
                    "           | 1  | 1      |\n" +
                    "           | 2  | 1      |];\n" +
                    "query { σ status = 1 (Items) };");

            assertThat(rows).hasSize(2);
        }

        @Test
        @DisplayName("numeric comparison filters correctly")
        void numericComparison() {
            var rows = collect(
                    "Items := [| id | price |\n" +
                    "           | 1  | 10    |\n" +
                    "           | 2  | 20    |\n" +
                    "           | 3  | 30    |];\n" +
                    "query { σ price > 15 (Items) };");

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)).hasValue("id", "2");
            assertThat(rows.get(1)).hasValue("id", "3");
        }

        @Test
        @DisplayName("AND predicate combines conditions")
        void andPredicate() {
            var rows = collect(
                    "Items := [| id | price | qty |\n" +
                    "           | 1  | 10    | 5   |\n" +
                    "           | 2  | 20    | 2   |\n" +
                    "           | 3  | 30    | 5   |];\n" +
                    "query { σ price > 15 ∧ qty = 5 (Items) };");

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("id", "3");
        }
    }

    // ── ProjectionNode ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ProjectionNode (π / PROJECT)")
    class Projection {

        @Test
        @DisplayName("selects a subset of columns")
        void columnSubset() {
            var rows = collect(
                    "Users := [| id | name | age |\n" +
                    "           | 1  | Alice | 30  |\n" +
                    "           | 2  | Bob   | 25  |];\n" +
                    "query { π name (Users) };");

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).schema().columns())
                    .extracting(c -> c.name())
                    .containsExactly("name");
            assertThat(rows.get(0)).hasValue("name", "Alice");
            assertThat(rows.get(1)).hasValue("name", "Bob");
        }

        @Test
        @DisplayName("computes aliased expression")
        void computedAlias() {
            var rows = collect(
                    "Items := [| id | price | qty |\n" +
                    "           | 1  | 10    | 3   |\n" +
                    "           | 2  | 20    | 2   |];\n" +
                    "query { π id, price * qty → total (Items) };");

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)).hasValue("total", "30");
            assertThat(rows.get(1)).hasValue("total", "40");
        }

        @Test
        @DisplayName("projects multiple columns")
        void multipleColumns() {
            var rows = collect(
                    "Users := [| id | name | age |\n" +
                    "           | 1  | Alice | 30  |];\n" +
                    "query { π id, name (Users) };");

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).width()).isEqualTo(2);
            assertThat(rows.get(0)).hasValue("id", "1")
                    .hasValue("name", "Alice");
        }
    }

    // ── RenameNode ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("RenameNode (ρ / RENAME)")
    class Rename {

        @Test
        @DisplayName("renames relation only (empty attribute list) passes through data")
        void relationOnlyRename() {
            var rows = collect(
                    "Users := [| id | name |\n" +
                    "           | 1  | Alice |\n" +
                    "           | 2  | Bob   |];\n" +
                    "People := { ρ People (Users) };\n" +
                    "query People;");

            assertThat(rows).hasSize(2);
        }

        @Test
        @DisplayName("renames columns to new names")
        void columnRename() {
            var rows = collect(
                    "Users := [| id | name |\n" +
                    "           | 1  | Alice |];\n" +
                    "query { ρ Users(user_id, user_name) (Users) };");

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).schema().columns())
                    .extracting(c -> c.name())
                    .containsExactly("user_id", "user_name");
            assertThat(rows.get(0)).hasValue("user_id", "1")
                    .hasValue("user_name", "Alice");
        }

        @Test
        @DisplayName("values are preserved through rename")
        void valuesPreserved() {
            var rows = collect(
                    "T := [| x | y |\n" +
                    "       | 42 | hello |];\n" +
                    "query { ρ T(a, b) (T) };");

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("a", "42")
                    .hasValue("b", "hello");
        }
    }

    // ── LimitNode ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("LimitNode (λ / LIMIT)")
    class Limit {

        @Test
        @DisplayName("limits result to count rows")
        void limitCount() {
            var rows = collect(
                    "T := [| id |\n" +
                    "       | 1  |\n" +
                    "       | 2  |\n" +
                    "       | 3  |\n" +
                    "       | 4  |\n" +
                    "       | 5  |];\n" +
                    "query { λ 3 (T) };");

            assertThat(rows).hasSize(3);
            assertThat(rows.get(0)).hasValue("id", "1");
            assertThat(rows.get(1)).hasValue("id", "2");
            assertThat(rows.get(2)).hasValue("id", "3");
        }

        @Test
        @DisplayName("limit with offset skips rows")
        void limitWithOffset() {
            var rows = collect(
                    "T := [| id |\n" +
                    "       | 1  |\n" +
                    "       | 2  |\n" +
                    "       | 3  |\n" +
                    "       | 4  |\n" +
                    "       | 5  |];\n" +
                    "query { λ 2, 2 (T) };");

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)).hasValue("id", "3");
            assertThat(rows.get(1)).hasValue("id", "4");
        }

        @Test
        @DisplayName("limit larger than row count returns all rows")
        void limitExceedsRows() {
            var rows = collect(
                    "T := [| id |\n" +
                    "       | 1  |\n" +
                    "       | 2  |];\n" +
                    "query { λ 100 (T) };");

            assertThat(rows).hasSize(2);
        }

        @Test
        @DisplayName("limit zero returns empty stream")
        void limitZero() {
            var rows = collect(
                    "T := [| id |\n" +
                    "       | 1  |\n" +
                    "       | 2  |];\n" +
                    "query { λ 0 (T) };");

            assertThat(rows).isEmpty();
        }
    }

    // ── DistinctNode ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DistinctNode (δ / DISTINCT)")
    class Distinct {

        @Test
        @DisplayName("removes duplicate rows")
        void removeDuplicates() {
            var rows = collect(
                    "T := [| name |\n" +
                    "       | Alice |\n" +
                    "       | Bob   |\n" +
                    "       | Alice |\n" +
                    "       | Alice |];\n" +
                    "query { δ (T) };");

            assertThat(rows).hasSize(2);
        }

        @Test
        @DisplayName("preserves unique rows")
        void preservesUnique() {
            var rows = collect(
                    "T := [| id | name |\n" +
                    "       | 1  | Alice |\n" +
                    "       | 2  | Bob   |\n" +
                    "       | 3  | Carol |];\n" +
                    "query { δ (T) };");

            assertThat(rows).hasSize(3);
        }

        @Test
        @DisplayName("distinct on empty input returns empty")
        void distinctEmpty() {
            var rows = collect(
                    "T := [| id |];\n" +
                    "query { δ (T) };");

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("all-duplicate table returns one row")
        void allDuplicates() {
            var rows = collect(
                    "T := [| x |\n" +
                    "       | 1 |\n" +
                    "       | 1 |\n" +
                    "       | 1 |];\n" +
                    "query { δ (T) };");

            assertThat(rows).hasSize(1);
        }
    }

    // ── Composed operators ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Composed operators")
    class Composed {

        @Test
        @DisplayName("selection then projection")
        void selectionThenProjection() {
            var rows = collect(
                    "Users := [| id | name | age |\n" +
                    "           | 1  | Alice | 30  |\n" +
                    "           | 2  | Bob   | 17  |\n" +
                    "           | 3  | Carol | 25  |];\n" +
                    "query { π name (σ age >= 18 (Users)) };");

            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(r -> r.get("name").asDisplayString())
                    .containsExactly("Alice", "Carol");
        }

        @Test
        @DisplayName("projection then distinct")
        void projectionThenDistinct() {
            var rows = collect(
                    "Orders := [| id | customer |\n" +
                    "            | 1  | Alice    |\n" +
                    "            | 2  | Bob      |\n" +
                    "            | 3  | Alice    |];\n" +
                    "query { δ (π customer (Orders)) };");

            assertThat(rows).hasSize(2);
        }

        @Test
        @DisplayName("limit applied after selection")
        void limitAfterSelection() {
            var rows = collect(
                    "T := [| id | val |\n" +
                    "       | 1  | 10  |\n" +
                    "       | 2  | 20  |\n" +
                    "       | 3  | 30  |\n" +
                    "       | 4  | 40  |];\n" +
                    "query { λ 2 (σ val > 5 (T)) };");

            assertThat(rows).hasSize(2);
        }
    }

    // ── Cross product and joins ────────────────────────────────────────────────

    @Nested
    @DisplayName("Cross product (×) and natural join (⋈)")
    class ProductAndJoin {

        @Test
        @DisplayName("cross product builds all row combinations")
        void crossProduct() {
            var rows = collect(
                    "A := [| id |\n" +
                    "       | 1  |\n" +
                    "       | 2  |];\n" +
                    "B := [| code |\n" +
                    "       | X    |\n" +
                    "       | Y    |];\n" +
                    "query { A × B };");

            assertThat(rows).hasSize(4);  // 2 × 2
        }

        @Test
        @DisplayName("cross product renames clashing column with _r suffix")
        void crossProductRenamesClashingColumn() {
            var rows = collect(
                    "A := [| id | name |\n" +
                    "       | 1  | Alice |];\n" +
                    "B := [| name | score |\n" +
                    "       | Alice | 100  |];\n" +
                    "query { A × B };");

            // Both A and B have 'name'; the right-side 'name' becomes 'name_r'
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("name")).isNotNull();
            assertThat(rows.get(0).get("name_r")).isNotNull();
        }

        @Test
        @DisplayName("cross product applies counter suffix when _r also clashes")
        void crossProductRenameCounterSuffix() {
            // Left has both 'name' and 'name_r'; right has 'name'
            // Expected: right's 'name' → first tries 'name_r' (clashes) → uses 'name_r1'
            var rows = collect(
                    "A := [| name | name_r |\n" +
                    "       | Alice | Alias |];\n" +
                    "B := [| name | score |\n" +
                    "       | Alice | 99   |];\n" +
                    "query { A × B };");

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("name_r1")).isNotNull();
        }

        @Test
        @DisplayName("natural join matches on shared column name")
        void naturalJoin() {
            var rows = collect(
                    "A := [| id | dept |\n" +
                    "       | 1  | eng   |\n" +
                    "       | 2  | sales |];\n" +
                    "B := [| dept | budget |\n" +
                    "       | eng  | 1000   |\n" +
                    "       | hr   | 500    |];\n" +
                    "query { A ⋈ B };");

            // Only row where A.dept = B.dept (= 'eng')
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("dept", "eng");
        }
    }

    // ── External relation ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("External relation via DataSourceConnector")
    class ExternalRelation {

        @Test
        @DisplayName("connector is called for source relations")
        void connectorCalledForSource() {
            var schema = schema(col("id", ScalarType.NUMBER), col("name", ScalarType.STRING));
            var connectorRows = List.of(
                    row(schema, num(1), str("Alice")),
                    row(schema, num(2), str("Bob")));

            String src =
                    "source Users from database { url: \"jdbc:h2:mem\", table: \"users\"," +
                    " schema: { id: NUMBER, name: STRING } };\n" +
                    "query Users;";

            SemanticModel model = model(src);
            DataSourceConnector connector = (name, s) -> connectorRows.stream();
            ExecutionContext ctx = ExecutionContext.of(model, connector);

            var query = model.rootQueries().getFirst();
            List<Row> rows;
            try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
                rows = stream.toList();
            }

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)).hasValue("id", "1");
            assertThat(rows.get(1)).hasValue("name", "Bob");
        }

        /**
         * A schema-on-read source: rows are documents, and the heading names nothing, so
         * the scan is what tells a row which relation it came from (#972).
         */
        private List<Row> documents(String query) {
            String src = "source Docs from json(\"docs.json\");\n" + query;
            SemanticModel model = model(src);
            DataSourceConnector connector = (name, s) -> Stream.of(
                    document(1, "Ada", "Oslo"), document(2, "Bo", "Rome"));
            var q = model.rootQueries().getFirst();
            try (Stream<Row> stream = EXECUTOR.execute(queryNode(q), ExecutionContext.of(model, connector))) {
                return stream.toList();
            }
        }

        private static Row document(int id, String name, String city) {
            var fields = new java.util.LinkedHashMap<String, com.darkcollective.relix.value.Value>();
            fields.put("id", num(id));
            fields.put("name", str(name));
            fields.put("addr", new com.darkcollective.relix.value.StructValue(
                    java.util.Map.of("city", str(city))));
            return new com.darkcollective.relix.processor.internal.DocumentRow(
                    new com.darkcollective.relix.value.StructValue(fields));
        }

        @Test
        @DisplayName("a qualified reference to a schema-on-read source's own field reads it (#972)")
        void qualifiedReferenceIntoAnOpenSource() {
            List<Row> rows = documents(
                    "query { π Docs.name → n, docs.addr.city → c (σ Docs.id = 2 (Docs)) };");

            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()).hasValue("n", "Bo").hasValue("c", "Rome");
        }

        @Test
        @DisplayName("a renamed schema-on-read source answers to its new name, and not its old one")
        void renamedOpenSource() {
            List<Row> rows = documents("query { π D.name → n, Docs.name → old (ρ D (Docs)) };");

            assertThat(rows).hasSize(2);
            assertThat(rows.getFirst()).hasValue("n", "Ada");
            assertThat(rows.getFirst().get("old"))
                    .isEqualTo(com.darkcollective.relix.value.NullValue.INSTANCE);
        }

        @Test
        @DisplayName("a pair rename over a schema-on-read source renames the field (#977)")
        void pairRenameOverAnOpenSource() {
            List<Row> rows = documents(
                    "query { π full_name → n, D.full_name → q, name → old (ρ D (name → full_name) (Docs)) };");

            assertThat(rows).hasSize(2);
            assertThat(rows.getFirst()).hasValue("n", "Ada").hasValue("q", "Ada");
            assertThat(rows.getFirst().get("old"))
                    .isEqualTo(com.darkcollective.relix.value.NullValue.INSTANCE);
        }

        @Test
        @DisplayName("a pair rename onto a field the document carries is refused as it runs")
        void pairRenameCollisionIsRefused() {
            assertThatThrownBy(() -> documents("query { ρ (name → id) (Docs) };"))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessage("Rename ρ: renaming 'name' to 'id' collides with the field 'id' "
                            + "the document carries");
        }

        @Test
        @DisplayName("an open scan the planner gave no qualifier leaves its documents unanchored")
        void openScanWithoutQualifier() {
            // Every scan the planner builds for a relation reference names it; a plan
            // assembled by hand need not, and then there is nothing to anchor to.
            SemanticModel model = model("source Docs from json(\"docs.json\");\nquery Docs;");
            var symbol = model.symbolTable().lookupRelation("Docs").orElseThrow();
            var scan = new com.darkcollective.relix.plan.PhysicalNode.Scan(
                    com.darkcollective.relix.symbol.Schema.open(), symbol,
                    java.util.Optional.empty(), java.util.Optional.empty());
            Row document = document(1, "Ada", "Oslo");
            try (Stream<Row> stream = new PhysicalExecutor().execute(scan,
                    ExecutionContext.of(model, (name, s) -> Stream.of(document)))) {
                assertThat(stream.toList()).containsExactly(document);
            }
        }

        @Test
        @DisplayName("a declared source's rows are passed through untouched")
        void declaredSourceRowsUntouched() {
            var schema = schema(col("id", ScalarType.NUMBER));
            Row declared = row(schema, num(1));
            SemanticModel model = model(
                    "source Users from database { url: \"jdbc:h2:mem\", table: \"users\","
                    + " schema: { id: NUMBER } };\nquery Users;");
            var q = model.rootQueries().getFirst();
            try (Stream<Row> stream = EXECUTOR.execute(queryNode(q),
                    ExecutionContext.of(model, (name, s) -> Stream.of(declared)))) {
                assertThat(stream.toList()).containsExactly(declared);
            }
        }

        @Test
        @DisplayName("inlineOnly connector throws EvaluationException for source relations")
        void inlineOnlyThrowsForSource() {
            String src =
                    "source Users from database { url: \"jdbc:h2:mem\", table: \"users\"," +
                    " schema: { id: NUMBER, name: STRING } };\n" +
                    "query Users;";

            SemanticModel model = model(src);
            ExecutionContext ctx = ExecutionContext.inlineOnly(model);
            var query = model.rootQueries().getFirst();

            assertThatThrownBy(() -> {
                try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
                    stream.toList();
                }
            })
            .isInstanceOf(EvaluationException.class)
            .hasMessageContaining("users");
        }
    }

}

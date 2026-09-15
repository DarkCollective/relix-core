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
package com.darkcollective.relix.processor;

import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.events.EventMetrics;
import com.darkcollective.relix.events.QueryEvent;
import com.darkcollective.relix.events.QueryEventListener;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.plan.BoundednessException;
import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.processor.eval.EvaluationException;
import org.junit.jupiter.api.Timeout;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.semantic.SemanticResult;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static com.darkcollective.relix.semantic.SemanticFixtures.analyzeObserving;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@DisplayName("QueryExecutor — top-level orchestrator")
final class QueryExecutorTest extends ProcessorTestSupport {

    private static final QueryExecutor EXECUTOR = new QueryExecutor();

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Analyzes and executes inline-only. Asserts no semantic errors. */
    private static List<QueryResult> run(String src) {
        SemanticResult result = analyze(src);
        assertThat(result.errors()).as("semantic errors").isEmpty();
        return EXECUTOR.execute(result);
    }

    /**
     * As {@link #run}, but analysing with {@code feed} as the previous run's
     * observability events — the extent {@code relix.events} is built from.
     */
    private static List<QueryResult> runWithFeed(String src, List<QueryEvent> feed) {
        SemanticResult result = analyzeObserving(src, feed);
        assertThat(result.errors()).as("semantic errors").isEmpty();
        return EXECUTOR.execute(result);
    }

    // ── QueryResult record ────────────────────────────────────────────────────

    @Nested
    @DisplayName("QueryResult record")
    class QueryResultTests {

        @Test
        @DisplayName("label, schema, and rows are accessible")
        void accessors() {
            var results = run(
                    "Users := [| id | name |\n" +
                    "           | 1  | Alice |\n" +
                    "           | 2  | Bob   |];\n" +
                    "query Users;");

            assertThat(results).hasSize(1);
            QueryResult r = results.get(0);
            assertThat(r.label()).isEqualTo("Users");
            assertThat(r.schema()).hasColumnNames("id", "name");
            assertThat(r.rows()).hasSize(2);
        }

        @Test
        @DisplayName("isEmpty() returns true for empty result")
        void isEmptyTrue() {
            var results = run(
                    "T := [| id |];\n" +
                    "query T;");

            assertThat(results.get(0).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("isEmpty() returns false when rows exist")
        void isEmptyFalse() {
            var results = run(
                    "T := [| id |\n" +
                    "       | 1  |];\n" +
                    "query T;");

            assertThat(results.get(0).isEmpty()).isFalse();
        }

        @Test
        @DisplayName("rowCount() matches actual row count")
        void rowCount() {
            var results = run(
                    "T := [| id |\n" +
                    "       | 1  |\n" +
                    "       | 2  |\n" +
                    "       | 3  |];\n" +
                    "query T;");

            assertThat(results.get(0).rowCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("rows list is unmodifiable")
        void rowsUnmodifiable() {
            var results = run(
                    "T := [| id |\n" +
                    "       | 1  |];\n" +
                    "query T;");

            assertThatThrownBy(() -> results.get(0).rows().add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("blank label throws IllegalArgumentException")
        void blankLabelThrows() {
            var schema = new com.darkcollective.relix.symbol.Schema(
                    List.of(new com.darkcollective.relix.symbol.ColumnDefinition(
                            "id", com.darkcollective.relix.symbol.ScalarType.NUMBER)));
            assertThatThrownBy(() -> new QueryResult("  ", schema, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }
    }

    // ── Named query target ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Named query target (query MyRelation;)")
    class NamedQuery {

        @Test
        @DisplayName("executes inline relation and returns rows")
        void inlineRelation() {
            var results = run(
                    "Users := [| id | name |\n" +
                    "           | 1  | Alice |\n" +
                    "           | 2  | Bob   |];\n" +
                    "query Users;");

            assertThat(results).hasSize(1);
            assertThat(results.get(0).rows()).hasSize(2);
            assertThat(results.get(0).rows().get(0).get("name").asDisplayString())
                    .isEqualTo("Alice");
        }

        @Test
        @DisplayName("label is the relation name")
        void labelIsRelationName() {
            var results = run(
                    "MyTable := [| x |\n" +
                    "             | 1  |];\n" +
                    "query MyTable;");

            assertThat(results.get(0).label()).isEqualTo("MyTable");
        }

        @Test
        @DisplayName("executes a query-relation (view) body recursively")
        void queryRelationView() {
            var results = run(
                    "Users := [| id | name |\n" +
                    "           | 1  | Alice |\n" +
                    "           | 2  | Bob   |];\n" +
                    "Adults := { σ id >= 1 (Users) };\n" +
                    "query Adults;");

            assertThat(results.get(0).rows()).hasSize(2);
        }

        @Test
        @DisplayName("schema is correct for named query on empty inline table")
        void schemaForEmptyInlineTable() {
            var results = run(
                    "Empty := [| id | name |];\n" +
                    "query Empty;");

            assertThat(results.get(0).isEmpty()).isTrue();
            // Schema should still reflect the declared columns
            assertThat(results.get(0).schema()).hasColumnNames("id", "name");
        }
    }

    // ── Inline-table NULLs ────────────────────────────────────────────────────
    @Nested
    @DisplayName("Inline-table NULLs (⊥ / blank in a numeric column)")
    class InlineNulls {

        @Test
        @DisplayName("SOLVE fills a ⊥ hole in a numeric column (per-row inversion)")
        void solveOverInlineNulls() {
            var results = run(
                    "Invoice := [| item | qty | unit_price | line_total |\n" +
                    "             | Widget | 3 | 5.00 | ⊥ |\n" +
                    "             | Gadget | ⊥ | 12.00 | 60.00 |];\n" +
                    "Completed := { SOLVE line_total = qty * unit_price (Invoice) };\n" +
                    "query Completed;");

            var rows = results.get(0).rows();
            assertThat(rows).hasSize(2);
            // Widget: line_total = 3 * 5.00 = 15
            assertThat(Double.parseDouble(rows.get(0).get("line_total").asDisplayString()))
                    .isEqualTo(15.0);
            // Gadget: qty = 60.00 / 12.00 = 5
            assertThat(Double.parseDouble(rows.get(1).get("qty").asDisplayString()))
                    .isEqualTo(5.0);
        }

        @Test
        @DisplayName("A blank cell is NULL: excluded by a numeric filter, no parse error")
        void blankCellIsNull() {
            var results = run(
                    "T := [| n |\n" +
                    "       | 3 |\n" +
                    "       |   |\n" +   // blank -> NULL
                    "       | 5 |];\n" +
                    "query { σ n > 4 (T) };");

            var rows = results.get(0).rows();
            // The blank row is NULL → n > 4 is UNKNOWN → excluded; only n = 5 survives.
            assertThat(rows).hasSize(1);
            assertThat(Double.parseDouble(rows.get(0).get("n").asDisplayString()))
                    .isEqualTo(5.0);
        }
    }

    // ── Expression query target ───────────────────────────────────────────────

    @Nested
    @DisplayName("Expression query target (query { expr };)")
    class ExpressionQuery {

        @Test
        @DisplayName("executes inline RA expression and returns correct rows")
        void inlineExpression() {
            var results = run(
                    "Users := [| id | name | age |\n" +
                    "           | 1  | Alice | 30  |\n" +
                    "           | 2  | Bob   | 17  |\n" +
                    "           | 3  | Carol | 25  |];\n" +
                    "query { σ age >= 18 (Users) };");

            assertThat(results).hasSize(1);
            assertThat(results.get(0).rows()).hasSize(2);
        }

        @Test
        @DisplayName("label uses 1-based index for first expression")
        void labelForFirstExpression() {
            var results = run(
                    "T := [| x |\n" +
                    "       | 1  |];\n" +
                    "query { T };");

            assertThat(results.get(0).label()).isEqualTo("<expression 1>");
        }

        @Test
        @DisplayName("label index increments for successive expression queries")
        void labelIndexIncrements() {
            var results = run(
                    "A := [| x |\n" +
                    "       | 1  |];\n" +
                    "B := [| y |\n" +
                    "       | 2  |];\n" +
                    "query { A };\n" +
                    "query { B };");

            assertThat(results.get(0).label()).isEqualTo("<expression 1>");
            assertThat(results.get(1).label()).isEqualTo("<expression 2>");
        }

        @Test
        @DisplayName("schema matches expression output schema")
        void expressionOutputSchema() {
            var results = run(
                    "Users := [| id | name | age |\n" +
                    "           | 1  | Alice | 30  |];\n" +
                    "query { π name, age (Users) };");

            assertThat(results.get(0).schema()).hasColumnNames("name", "age");
        }
    }

    @Nested
    @DisplayName("Expression grouping keys and sort keys (issue #375)")
    class ExpressionGroupingAndSorting {

        private static final String EVENTS =
                "Events := [| id | amt |\n" +
                "           | 1  | 10  |\n" +
                "           | 2  | 10  |\n" +
                "           | 3  | 20  |\n" +
                "           | 4  | -5  |];\n";

        @Test
        @DisplayName("γ groups by a function-call key aliased to a new column")
        void groupsByFunctionExpression() {
            var results = run(EVENTS + "query { γ Sgn(amt) → sign, COUNT(id) → n (Events) };");

            assertThat(results.get(0).schema()).hasColumnNames("sign", "n");
            // Sgn(amt): three positive (10,10,20) → n=3; one negative (-5) → n=1.
            assertThat(results.get(0).rows())
                    .extracting(r -> r.get("sign").asDisplayString() + ":" + r.get("n").asDisplayString())
                    .containsExactlyInAnyOrder("1:3", "-1:1");
        }

        @Test
        @DisplayName("γ groups by an arithmetic key")
        void groupsByArithmeticExpression() {
            var results = run(EVENTS + "query { γ amt * 2 → dbl, COUNT(id) → n (Events) };");

            assertThat(results.get(0).rows())
                    .extracting(r -> r.get("dbl").asDisplayString() + ":" + r.get("n").asDisplayString())
                    .containsExactlyInAnyOrder("20:2", "40:1", "-10:1");
        }

        @Test
        @DisplayName("τ sorts by a function-call key, preserving the input schema")
        void sortsByFunctionExpression() {
            var results = run(EVENTS + "query { τ Abs(amt) DESC (Events) };");

            assertThat(results.get(0).schema()).hasColumnNames("id", "amt");
            // |amt| descending: 20, then the two 10s, then |-5| = 5 last.
            assertThat(results.get(0).rows())
                    .extracting(r -> r.get("amt").asDisplayString())
                    .containsExactly("20", "10", "10", "-5");
        }
    }

    // ── Multiple query statements ─────────────────────────────────────────────

    @Nested
    @DisplayName("Multiple query statements in one script")
    class MultipleQueries {

        @Test
        @DisplayName("results are returned in declaration order")
        void declarationOrder() {
            var results = run(
                    "A := [| id |\n" +
                    "       | 1  |];\n" +
                    "B := [| id |\n" +
                    "       | 2  |];\n" +
                    "query A;\n" +
                    "query B;");

            assertThat(results).hasSize(2);
            assertThat(results.get(0).rows().get(0)).hasValue("id", "1");
            assertThat(results.get(1).rows().get(0)).hasValue("id", "2");
        }

        @Test
        @DisplayName("mixed named and expression queries both executed")
        void mixedTargets() {
            var results = run(
                    "T := [| id | val |\n" +
                    "       | 1  | 10  |\n" +
                    "       | 2  | 20  |];\n" +
                    "query T;\n" +
                    "query { π val (T) };");

            assertThat(results).hasSize(2);
            assertThat(results.get(0).label()).isEqualTo("T");
            assertThat(results.get(1).label()).isEqualTo("<expression 2>");
            assertThat(results.get(1).schema()).hasColumnNames("val");
        }

        @Test
        @DisplayName("no query statements returns empty list")
        void noQueries() {
            // A script with only assignments and no query statements
            var result = analyze(
                    "T := [| id |\n" +
                    "       | 1  |];");
            // If there are no errors, the model exists but rootQueries is empty
            if (result.isFullyValid()) {
                var results = EXECUTOR.execute(result);
                assertThat(results).isEmpty();
            }
        }

        @Test
        @DisplayName("label index counts all query statements, not just expression ones")
        void labelIndexCountsAll() {
            var results = run(
                    "A := [| x |\n" +
                    "       | 1  |];\n" +
                    "B := [| y |\n" +
                    "       | 2  |];\n" +
                    "query A;\n" +                // index 1 (named → label = "A")
                    "query { B };\n" +            // index 2 (expression → "<expression 2>")
                    "query { A };\n");             // index 3 (expression → "<expression 3>")

            assertThat(results).hasSize(3);
            assertThat(results.get(0).label()).isEqualTo("A");
            assertThat(results.get(1).label()).isEqualTo("<expression 2>");
            assertThat(results.get(2).label()).isEqualTo("<expression 3>");
        }
    }

    // ── Precondition enforcement ──────────────────────────────────────────────

    @Nested
    @DisplayName("Precondition: model must be fully valid")
    class Preconditions {

        @Test
        @DisplayName("execute(SemanticResult) throws on invalid result")
        void throwsOnInvalidResult() {
            // Reference an undefined relation to trigger a semantic error
            SemanticResult badResult = analyze(
                    "query NonExistentRelation;");

            assertThat(badResult.isFullyValid()).isFalse();

            assertThatThrownBy(() -> EXECUTOR.execute(badResult))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("semantic errors");
        }

        @Test
        @DisplayName("execute(SemanticResult, connector) throws on invalid result")
        void throwsOnInvalidResultWithConnector() {
            SemanticResult badResult = analyze(
                    "query NonExistentRelation;");

            assertThat(badResult.isFullyValid()).isFalse();

            assertThatThrownBy(() -> EXECUTOR.execute(badResult, (name, schema) -> null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("semantic errors");
        }

        @Test
        @DisplayName("both executeStreaming(SemanticResult, …) overloads validate and then stream")
        void streamingOverloadsOverASemanticResult() {
            // The SemanticResult-taking overloads are the public front door — they check
            // the analysis is clean and hand the model to the SemanticModel form. Both the
            // connector-taking and the inline-only shape need showing, since each is its
            // own overload and each is what an embedder actually calls.
            SemanticResult good = analyze("""
                    R := [| id |
                           | 1  |
                           | 2  |];
                    query R;
                    """);
            assertThat(good).isFullyValid();

            List<Long> counts = new ArrayList<>();
            EXECUTOR.executeStreaming(good, (label, schema, rows) -> counts.add(rows.count()));
            assertThat(counts).as("inline-only overload").containsExactly(2L);

            counts.clear();
            EXECUTOR.executeStreaming(good, (name, schema) -> null,
                    (label, schema, rows) -> counts.add(rows.count()));
            assertThat(counts).as("connector-taking overload").containsExactly(2L);
        }

        @Test
        @DisplayName("executeStreaming(SemanticResult, …) rejects an invalid result too")
        void streamingOverloadsRejectAnInvalidResult() {
            SemanticResult bad = analyze("query NonExistentRelation;");
            assertThatThrownBy(() -> EXECUTOR.executeStreaming(bad, (l, sc, r) -> { }))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("semantic errors");
            assertThatThrownBy(() -> EXECUTOR.executeStreaming(
                    bad, (name, schema) -> null, (l, sc, r) -> { }))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("semantic errors");
        }

        @Test
        @DisplayName("execute(SemanticModel) accepts a model extracted from a valid result")
        void acceptsValidModel() {
            SemanticResult good = analyze(
                    "T := [| id |\n" +
                    "       | 42 |];\n" +
                    "query T;");

            assertThat(good).isFullyValid();

            SemanticModel model = good.model().orElseThrow();
            var results = EXECUTOR.execute(model);   // direct SemanticModel overload
            assertThat(results).hasSize(1);
            assertThat(results.get(0).rows().get(0)).hasValue("id", "42");
        }
    }

    // ── External connector ────────────────────────────────────────────────────

    @Nested
    @DisplayName("External data-source connector")
    class ExternalConnector {

        @Test
        @DisplayName("connector is called for source relations")
        void connectorCalledForSource() {
            var schema = schema(col("id", ScalarType.NUMBER), col("name", ScalarType.STRING));
            var stubRows = List.of(
                    row(schema, num(1), str("Alice")),
                    row(schema, num(2), str("Bob")));

            String src =
                    "source Users from database {\n" +
                    "    url: \"jdbc:h2:mem\", table: \"users\",\n" +
                    "    schema: { id: NUMBER, name: STRING }\n" +
                    "};\n" +
                    "query Users;";

            SemanticResult result = analyze(src);
            assertThat(result).isFullyValid();

            var results = EXECUTOR.execute(result, (name, s) -> stubRows.stream());

            assertThat(results).hasSize(1);
            assertThat(results.get(0).rows()).hasSize(2);
            assertThat(results.get(0).rows().get(0).get("name").asDisplayString())
                    .isEqualTo("Alice");
        }

        @Test
        @DisplayName("inline-only execution throws when external source is accessed")
        void inlineOnlyThrowsForExternalSource() {
            String src =
                    "source Users from database {\n" +
                    "    url: \"jdbc:h2:mem\", table: \"users\",\n" +
                    "    schema: { id: NUMBER, name: STRING }\n" +
                    "};\n" +
                    "query Users;";

            SemanticResult result = analyze(src);
            assertThat(result).isFullyValid();

            assertThatThrownBy(() -> EXECUTOR.execute(result))
                    .isInstanceOf(EvaluationException.class);
        }
    }

    // ── End-to-end: complete script execution ─────────────────────────────────

    @Nested
    @DisplayName("End-to-end execution")
    class EndToEnd {

        @Test
        @DisplayName("join then project then filter produces correct rows")
        void joinProjectFilter() {
            var results = run(
                    "Users := [| id | name  |\n" +
                    "           | 1  | Alice |\n" +
                    "           | 2  | Bob   |\n" +
                    "           | 3  | Carol |];\n" +
                    "Orders := [| user_id | amount |\n" +
                    "            | 1       | 100    |\n" +
                    "            | 1       | 50     |\n" +
                    "            | 2       | 200    |];\n" +
                    "Joined := { Users ⨝ Users.id = Orders.user_id Orders };\n" +
                    "BigOrders := { σ amount >= 100 (Joined) };\n" +
                    "query { π name, amount (BigOrders) };");

            assertThat(results).hasSize(1);
            QueryResult r = results.get(0);
            assertThat(r.rows()).hasSize(2);
            assertThat(r.schema()).hasColumnNames("name", "amount");
            assertThat(r.rows()).extracting(row -> row.get("name").asDisplayString())
                    .containsExactlyInAnyOrder("Alice", "Bob");
        }

        @Test
        @DisplayName("aggregation result accessible via named view")
        void aggregationViaView() {
            var results = run(
                    "Sales := [| dept | amount |\n" +
                    "           | eng  | 100    |\n" +
                    "           | eng  | 200    |\n" +
                    "           | mkt  | 150    |];\n" +
                    "Totals := { γ dept, SUM(amount) → total (Sales) };\n" +
                    "query Totals;");

            assertThat(results.get(0).rows()).hasSize(2);
            assertThat(results.get(0).label()).isEqualTo("Totals");
        }

        @Test
        @DisplayName("two query statements over different views return independent results")
        void twoIndependentViews() {
            var results = run(
                    "T := [| dept | score |\n" +
                    "       | eng  | 90    |\n" +
                    "       | mkt  | 70    |\n" +
                    "       | eng  | 80    |];\n" +
                    "High  := { σ score >= 85 (T) };\n" +
                    "Low   := { σ score < 85  (T) };\n" +
                    "query High;\n" +
                    "query Low;");

            assertThat(results).hasSize(2);
            assertThat(results.get(0).label()).isEqualTo("High");
            assertThat(results.get(0).rows()).hasSize(1);
            assertThat(results.get(1).label()).isEqualTo("Low");
            assertThat(results.get(1).rows()).hasSize(2);
        }
    }

    // ── Optimized-plan execution ──────────────────────────────────────────────

    @Nested
    @DisplayName("executeOptimized — runs rewritten trees with re-inferred schemas")
    class OptimizedExecution {

        /** Returns the QR body for {@code name}. */
        private static RelNode bodyOf(SemanticModel model, String name) {
            return ((QueryRelationSymbol) model.symbolTable()
                    .lookupRelation(name).orElseThrow()).body();
        }

        @Test
        @DisplayName("executes a freshly-rebuilt named-view tree (nodes absent from original annotations)")
        void freshNamedViewTree() {
            SemanticResult result = analyze(
                    "Users := [| id | name  | age |\n" +
                    "           | 1  | Alice | 30  |\n" +
                    "           | 2  | Bob   | 17  |\n" +
                    "           | 3  | Carol | 25  |];\n" +
                    "Adults := { σ age >= 18 (Users) };\n" +
                    "query Adults;");
            assertThat(result).isFullyValid();
            SemanticModel model = result.model().orElseThrow();

            // Rebuild σ age>=18 (Users) with brand-new node identities, reusing only
            // the parsed predicate. These instances are NOT in model.nodeSchemas().
            SelectionNode origBody = (SelectionNode) bodyOf(model, "Adults");
            RelNode fresh = select(origBody.predicate(), rel("Users"));

            var results = EXECUTOR.executeOptimized(model, List.of(fresh));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).label()).isEqualTo("Adults");
            assertThat(results.get(0).rows())
                    .extracting(r -> r.get("name").asDisplayString())
                    .containsExactlyInAnyOrder("Alice", "Carol");
        }

        @Test
        @DisplayName("executes a freshly-rebuilt expression-query tree with correct output schema")
        void freshExpressionTree() {
            SemanticResult result = analyze(
                    "Users := [| id | name  | age |\n" +
                    "           | 1  | Alice | 30  |\n" +
                    "           | 2  | Bob   | 17  |];\n" +
                    "query { σ age >= 18 (Users) };");
            SemanticModel model = result.model().orElseThrow();

            var target = (ExpressionQueryTarget) model.rootQueries().get(0).target();
            SelectionNode origExpr = (SelectionNode) target.expression();
            RelNode fresh = select(origExpr.predicate(), rel("Users"));

            var results = EXECUTOR.executeOptimized(model, List.of(fresh));

            assertThat(results.get(0).label()).isEqualTo("<expression 1>");
            assertThat(results.get(0).schema()).hasColumnNames("id", "name", "age");
            assertThat(results.get(0).rows()).hasSize(1);
        }

        @Test
        @DisplayName("rewritten root referencing an un-rewritten nested view still executes")
        void nestedViewRemainsExecutable() {
            SemanticResult result = analyze(
                    "Users := [| id | name  | age |\n" +
                    "           | 1  | Alice | 30  |\n" +
                    "           | 2  | Bob   | 17  |\n" +
                    "           | 3  | Carol | 25  |];\n" +
                    "Adults := { σ age >= 18 (Users) };\n" +
                    "Named  := { π name (Adults) };\n" +
                    "query Named;");
            SemanticModel model = result.model().orElseThrow();

            // Fresh π name (Adults): the Adults body is NOT re-walked, so its node
            // schemas must be carried over from the original annotations (the merge).
            ProjectionNode origProj = (ProjectionNode) bodyOf(model, "Named");
            RelNode fresh = project(origProj.attributes(), rel("Adults"));

            var results = EXECUTOR.executeOptimized(model, List.of(fresh));

            assertThat(results.get(0).schema()).hasColumnNames("name");
            assertThat(results.get(0).rows())
                    .extracting(r -> r.get("name").asDisplayString())
                    .containsExactlyInAnyOrder("Alice", "Carol");
        }

        @Test
        @DisplayName("optimized results match unoptimized results for the same query")
        void matchesUnoptimized() {
            String src =
                    "Users := [| id | name  | age |\n" +
                    "           | 1  | Alice | 30  |\n" +
                    "           | 2  | Bob   | 17  |\n" +
                    "           | 3  | Carol | 25  |];\n" +
                    "Adults := { σ age >= 18 (Users) };\n" +
                    "query Adults;";
            SemanticResult result = analyze(src);
            SemanticModel model = result.model().orElseThrow();

            var unoptimized = EXECUTOR.execute(result);
            // Pass the original body unchanged as the "optimized" tree.
            var optimized = EXECUTOR.executeOptimized(model, List.of(bodyOf(model, "Adults")));

            assertThat(optimized.get(0).rowCount()).isEqualTo(unoptimized.get(0).rowCount());
            assertThat(optimized.get(0).rows().stream().map(Object::toString).toList())
                    .isEqualTo(unoptimized.get(0).rows().stream().map(Object::toString).toList());
        }

        @Test
        @DisplayName("connector overload supplies rows for a source-relation leaf")
        void connectorOverloadForSource() {
            var schema = schema(col("id", ScalarType.NUMBER), col("name", ScalarType.STRING));
            var stubRows = List.of(row(schema, num(1), str("Alice")),
                                   row(schema, num(2), str("Bob")));
            SemanticResult result = analyze(
                    "source Users from database {\n" +
                    "    url: \"jdbc:h2:mem\", table: \"users\",\n" +
                    "    schema: { id: NUMBER, name: STRING }\n" +
                    "};\n" +
                    "query Users;");
            SemanticModel model = result.model().orElseThrow();

            // optimizer emits a RelationNode leaf for non-view named targets
            var results = EXECUTOR.executeOptimized(
                    model, List.of(rel("Users")), (name, s) -> stubRows.stream());

            assertThat(results.get(0).rows()).hasSize(2);
            assertThat(results.get(0).rows().get(0).get("name").asDisplayString())
                    .isEqualTo("Alice");
        }

        @Test
        @DisplayName("size mismatch between optimizedRoots and root queries throws")
        void sizeMismatchThrows() {
            SemanticResult result = analyze(
                    "T := [| id |\n" +
                    "       | 1  |];\n" +
                    "query T;");
            SemanticModel model = result.model().orElseThrow();

            assertThatThrownBy(() -> EXECUTOR.executeOptimized(model, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("root query count");
        }
    }

    // ── Streaming execution ───────────────────────────────────────────────────

    @Nested
    @DisplayName("executeStreaming — lazy, per-query row streams")
    class Streaming {

        @Test
        @DisplayName("invokes the consumer once per query, in declaration order, with correct labels")
        void invokesConsumerPerQueryInOrder() {
            SemanticModel model = analyze(
                    "T := [| id | val |\n" +
                    "       | 1  | 10  |\n" +
                    "       | 2  | 20  |];\n" +
                    "query T;\n" +
                    "query { π val (T) };").model().orElseThrow();

            List<String> labels = new ArrayList<>();
            List<Long>   counts = new ArrayList<>();
            EXECUTOR.executeStreaming(model, (label, schema, rows) -> {
                labels.add(label);
                counts.add(rows.count());
            });

            assertThat(labels).containsExactly("T", "<expression 2>");
            assertThat(counts).containsExactly(2L, 2L);
        }

        @Test
        @DisplayName("produces the same rows as the convenience list API")
        void matchesConvenienceList() {
            String src =
                    "Users := [| id | name  | age |\n" +
                    "           | 1  | Alice | 30  |\n" +
                    "           | 2  | Bob   | 17  |];\n" +
                    "query { σ age >= 18 (Users) };";
            SemanticResult result = analyze(src);

            List<String> streamed = new ArrayList<>();
            EXECUTOR.executeStreaming(result.model().orElseThrow(), (label, schema, rows) ->
                    rows.forEach(r -> streamed.add(r.get("name").asDisplayString())));

            List<String> listed = EXECUTOR.execute(result).get(0).rows().stream()
                    .map(r -> r.get("name").asDisplayString()).toList();
            assertThat(streamed).isEqualTo(listed).containsExactly("Alice");
        }

        @Test
        @Timeout(10)
        @DisplayName("is lazy: an unbounded source is not fully materialised when the consumer stops early")
        void lazyOverUnboundedSource() {
            // The source yields infinitely many rows; the eager .toList() path would
            // never terminate. Streaming + findFirst pulls a single row and stops.
            var srcSchema = schema(col("n", ScalarType.NUMBER));
            DataSourceConnector infinite = (name, schema) ->
                    Stream.iterate(0, i -> i + 1).map(i -> row(srcSchema, num(i)));

            SemanticModel model = analyze(
                    "source Big from database {\n" +
                    "    url: \"jdbc:h2:mem\", table: \"big\",\n" +
                    "    schema: { n: NUMBER }\n" +
                    "};\n" +
                    "query Big;").model().orElseThrow();

            List<String> first = new ArrayList<>();
            EXECUTOR.executeStreaming(model, infinite, (label, schema, rows) ->
                    rows.findFirst().ifPresent(r -> first.add(r.get("n").asDisplayString())));

            assertThat(first).containsExactly("0");
        }

        @Test
        @DisplayName("inline-only streaming throws when an external source is read")
        void inlineOnlyRejectsExternalSource() {
            SemanticModel model = analyze(
                    "source Users from database {\n" +
                    "    url: \"jdbc:h2:mem\", table: \"users\",\n" +
                    "    schema: { id: NUMBER }\n" +
                    "};\n" +
                    "query Users;").model().orElseThrow();

            assertThatThrownBy(() ->
                    EXECUTOR.executeStreaming(model, (label, schema, rows) -> rows.forEach(r -> { })))
                    .isInstanceOf(EvaluationException.class);
        }

        @Test
        @DisplayName("executeOptimizedStreaming runs the rewritten trees")
        void optimizedStreaming() {
            SemanticResult result = analyze(
                    "Users := [| id | name  | age |\n" +
                    "           | 1  | Alice | 30  |\n" +
                    "           | 2  | Bob   | 17  |];\n" +
                    "Adults := { σ age >= 18 (Users) };\n" +
                    "query Adults;");
            SemanticModel model = result.model().orElseThrow();
            SelectionNode body = (SelectionNode) ((QueryRelationSymbol) model.symbolTable()
                    .lookupRelation("Adults").orElseThrow()).body();
            RelNode fresh = select(body.predicate(), rel("Users"));

            List<String> names = new ArrayList<>();
            EXECUTOR.executeOptimizedStreaming(model, List.of(fresh), (label, schema, rows) -> {
                assertThat(label).isEqualTo("Adults");
                rows.forEach(r -> names.add(r.get("name").asDisplayString()));
            });
            assertThat(names).containsExactly("Alice");
        }
    }

    // ── explain (physical plan) ────────────────────────────────────────────────

    @Nested
    @DisplayName("explain (physical plan)")
    class Explain {

        private static Map<String, String> explain(String src) {
            SemanticResult result = analyze(src);
            assertThat(result.errors()).isEmpty();
            Map<String, String> plans = new LinkedHashMap<>();
            EXECUTOR.explain(result.model().orElseThrow(), plans::put);
            return plans;
        }

        @Test
        @DisplayName("renders a plan per query, keyed by query label")
        void perQueryPlans() {
            Map<String, String> plans = explain(
                    "Users := [| id | name |\n" +
                    "           | 1  | Alice |];\n" +
                    "query { σ id > 0 (Users) };\n" +
                    "query Users;");

            assertThat(plans).containsOnlyKeys("<expression 1>", "Users");
            // Every line carries the planner's cardinality estimate (#560). The inline
            // relation has one row, and a σ over it is floored at one.
            assertThat(plans.get("<expression 1>"))
                    .isEqualTo("Select  ~1 rows\n└─ Scan Users  ~1 rows\n");
            assertThat(plans.get("Users")).isEqualTo("Scan Users  ~1 rows\n");
        }

        /**
         * The other trace test runs an OPTIMIZE whose only group is infeasible, so it
         * emits its event and yields no rows — which means the drain that makes
         * {@code traceExecute} execute at all had never run. A trace exists to report
         * what a run did, and a run that produces rows is the ordinary case.
         */
        @Test
        @DisplayName("drains a query that produces rows, reporting what it scanned")
        void drainsRowsWithoutCollectingThem() {
            SemanticResult result = analyze(
                    "Users := [| id | name |\n" +
                    "          | 1  | ann  |\n" +
                    "          | 2  | bob  |];\n" +
                    "query { σ id > 0 (Users) };");
            assertThat(result.errors()).isEmpty();
            SemanticModel model = result.model().orElseThrow();
            List<RelNode> roots = model.rootQueries().stream()
                    .map(q -> ((ExpressionQueryTarget) q.target()).expression()).toList();

            List<com.darkcollective.relix.events.QueryEvent> events = new ArrayList<>();
            EXECUTOR.traceExecute(model, roots,
                    ExecutionContext.inlineOnly(model).connector(), events::add);

            // The rows are dropped, not returned — the feed is the whole output — but the
            // scan must have reached the end for its measured size to be reported at all.
            assertThat(events).anySatisfy(e -> {
                assertThat(e.stage())
                        .isEqualTo(com.darkcollective.relix.events.QueryEvent.Stage.EXECUTE);
                assertThat(e.code()).isEqualTo("SCAN");
                assertThat(e.metrics().rows()).hasValue(2L);
            });
        }

        @Test
        @DisplayName("rejects a roots list whose size differs from the query count")
        void mismatchedRootsRejected() {
            SemanticModel model = analyze(
                    "Users := [| id |\n       | 1 |];\nquery Users;").model().orElseThrow();
            assertThatThrownBy(() -> EXECUTOR.explain(model, List.of(), (label, text) -> { }))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── trace (planner events) ─────────────────────────────────────────────────

    @Nested
    @DisplayName("trace (planner events)")
    class Trace {

        @Test
        @DisplayName("emits PLAN events tagged with the owning query label")
        void emitsTaggedPlanEvents() {
            SemanticResult result = analyze(
                    "A := [| k | a |\n       | 1 | x |];\n" +
                    "B := [| k | b |\n       | 1 | y |];\n" +
                    "query { A ⋈ B };");
            assertThat(result.errors()).isEmpty();
            SemanticModel model = result.model().orElseThrow();
            List<RelNode> roots = model.rootQueries().stream()
                    .map(q -> ((ExpressionQueryTarget) q.target()).expression()).toList();

            List<com.darkcollective.relix.events.QueryEvent> events = new ArrayList<>();
            EXECUTOR.trace(model, roots, events::add);

            assertThat(events).anySatisfy(e -> {
                assertThat(e.stage())
                        .isEqualTo(com.darkcollective.relix.events.QueryEvent.Stage.PLAN);
                assertThat(e.code()).isEqualTo("JOIN");
                assertThat(e.target()).contains("<expression 1>");
            });
        }

        @Test
        @DisplayName("rejects a roots list whose size differs from the query count")
        void mismatchedRootsRejected() {
            SemanticModel model = analyze(
                    "Users := [| id |\n       | 1 |];\nquery Users;").model().orElseThrow();
            assertThatThrownBy(() -> EXECUTOR.trace(model, List.of(), event -> { }))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── traceExecute (execution-stage events) ────────────────────────────────────

    @Nested
    @DisplayName("traceExecute (execution events)")
    class TraceExecute {

        @Test
        @DisplayName("emits an EXECUTE event tagged with the query label for a skipped group")
        void emitsTaggedExecuteEvent() {
            SemanticResult result = analyze(
                    "Items := [| region | value | weight |\n" +
                    "           | a | 60 | 10 |];\n" +
                    "query { OPTIMIZE MAXIMIZE SUM(value) " +
                    "        SUBJECT TO SUM(weight) >= 1000 PER region (Items) };");
            assertThat(result.errors()).isEmpty();
            SemanticModel model = result.model().orElseThrow();
            List<RelNode> roots = model.rootQueries().stream()
                    .map(q -> ((ExpressionQueryTarget) q.target()).expression()).toList();

            List<com.darkcollective.relix.events.QueryEvent> events = new ArrayList<>();
            EXECUTOR.traceExecute(model, roots,
                    ExecutionContext.inlineOnly(model).connector(), events::add);

            // Filtered to the event under test: the feed also carries a SCAN event per
            // leaf the run read to the end, and a size assertion over the whole feed
            // would break every time an operator learns to report something.
            List<com.darkcollective.relix.events.QueryEvent> skipped = events.stream()
                    .filter(e -> e.code().equals("OPTIMIZE")).toList();
            assertThat(skipped).hasSize(1);
            assertThat(skipped).first().satisfies(e -> {
                assertThat(e.stage())
                        .isEqualTo(com.darkcollective.relix.events.QueryEvent.Stage.EXECUTE);
                assertThat(e.code()).isEqualTo("OPTIMIZE");
                assertThat(e.description()).contains("region=a");
                assertThat(e.target()).contains("<expression 1>");
            });
        }

        @Test
        @DisplayName("rejects a roots list whose size differs from the query count")
        void mismatchedRootsRejected() {
            SemanticModel model = analyze(
                    "Users := [| id |\n       | 1 |];\nquery Users;").model().orElseThrow();
            assertThatThrownBy(() -> EXECUTOR.traceExecute(
                    model, List.of(), ExecutionContext.inlineOnly(model).connector(), event -> { }))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── plan (physical plan object) ─────────────────────────────────────────────

    @Nested
    @DisplayName("plan (physical plan object)")
    class Plan {

        @Test
        @DisplayName("hands back a PhysicalNode per query (by label) and emits tagged PLAN events")
        void perQueryPhysicalPlansAndEvents() {
            SemanticResult result = analyze(
                    "A := [| k | a |\n       | 1 | x |];\n" +
                    "B := [| k | b |\n       | 1 | y |];\n" +
                    "query { A ⋈ B };");
            assertThat(result.errors()).isEmpty();
            SemanticModel model = result.model().orElseThrow();
            List<RelNode> roots = model.rootQueries().stream()
                    .map(q -> ((ExpressionQueryTarget) q.target()).expression()).toList();

            Map<String, PhysicalNode> plans = new LinkedHashMap<>();
            List<com.darkcollective.relix.events.QueryEvent> events = new ArrayList<>();
            EXECUTOR.plan(model, roots, events::add, (label, plan, rows) -> plans.put(label, plan));

            assertThat(plans).containsOnlyKeys("<expression 1>");
            assertThat(plans.get("<expression 1>")).isNode(PhysicalNode.Join.class);
            assertThat(events).anySatisfy(e -> {
                assertThat(e.stage())
                        .isEqualTo(com.darkcollective.relix.events.QueryEvent.Stage.PLAN);
                assertThat(e.target()).contains("<expression 1>");
            });
        }

        @Test
        @DisplayName("rejects a roots list whose size differs from the query count")
        void mismatchedRootsRejected() {
            SemanticModel model = analyze(
                    "Users := [| id |\n       | 1 |];\nquery Users;").model().orElseThrow();
            assertThatThrownBy(() ->
                    EXECUTOR.plan(model, List.of(), event -> { }, (label, plan, rows) -> { }))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── system catalog (relix.*) — ADR-0007 ─────────────────────────────────────

    @Nested
    @DisplayName("system catalog (relix.*)")
    class SystemCatalog {

        @Test
        @DisplayName("executes a query over relix.relations end-to-end")
        void executesRelations() {
            var results = run(
                    "Users := [| id | name  |\n           | 1  | Alice |];\n" +
                    "Adults := { σ id > 0 (Users) };\n" +
                    "query { τ name (relix.relations) };");

            assertThat(results).hasSize(1);
            QueryResult r = results.get(0);
            assertThat(r.schema()).hasColumnNames("name", "kind", "namespace", "materialization",
                    "row_count", "boundedness");
            // Catalog excludes itself; rows sorted by name.
            assertThat(r.rows()).extracting(row -> row.get("name").asDisplayString())
                    .containsExactly("Adults", "Users");
            // The inline relation's exact row count surfaces; the view's is NULL.
            assertThat(r.rows()).filteredOn(row -> "Users".equals(row.get("name").asDisplayString()))
                    .singleElement()
                    .satisfies(row -> assertThat(row.get("row_count").asDisplayString()).isEqualTo("1"));
        }

        @Test
        @DisplayName("relix.functions lists builtins end-to-end (Abs → math)")
        void executesFunctions() {
            var results = run("query { σ name = \"Abs\" (relix.functions) };");
            assertThat(results.get(0).rows()).anySatisfy(row ->
                    assertThat(row.get("category").asDisplayString()).isEqualTo("math"));
        }

        @Test
        @DisplayName("executes a query over relix.connections (redacted) end-to-end")
        void executesConnections() {
            var results = run(
                    "connection sales from database { url: \"jdbc:h2:mem:s\", dialect: postgres };\n" +
                    "query { relix.connections };");

            assertThat(results).hasSize(1);
            QueryResult r = results.get(0);
            assertThat(r.schema()).hasColumnNames("name", "dialect");   // url/user/password redacted
            assertThat(r.rows()).hasSize(1);
            assertThat(r.rows().get(0)).hasValue("name", "sales")
                    .hasValue("dialect", "postgres");
        }

        @Test
        @DisplayName("CLOSURE over relix.dependencies computes transitive lineage")
        void closureLineage() {
            var results = run(
                    "Users := [| id |\n           | 1  |];\n" +
                    "A := { σ id > 0 (Users) };\n" +
                    "B := { π id (A) };\n" +
                    "Lineage := { CLOSURE dependent, depends_on (relix.dependencies) };\n" +
                    "query { π depends_on (σ dependent = \"B\" (Lineage)) };");

            assertThat(results.get(0).rows())
                    .extracting(row -> row.get("depends_on").asDisplayString())
                    .containsExactlyInAnyOrder("A", "Users");
        }

        @Test
        @DisplayName("relix.dependencies (derived from relix.plan) yields one edge per referenced relation")
        void dependenciesDirectEdges() {
            var results = run(
                    "Users := [| id | name  |\n           | 1  | Alice |];\n" +
                    "Adults := { σ id > 0 (Users) };\n" +
                    "ByName := { τ name (Users) };\n" +
                    "query { relix.dependencies };");

            assertThat(results.get(0).rows())
                    .extracting(row -> row.get("dependent").asDisplayString()
                            + " -> " + row.get("depends_on").asDisplayString())
                    .containsExactlyInAnyOrder("Adults -> Users", "ByName -> Users");
        }

        @Test
        @DisplayName("relix.dependencies collapses repeated references to a single edge (δ)")
        void dependenciesDeduplicates() {
            var results = run(
                    "A := [| id |\n           | 1  |];\n" +
                    "Dup := { A ∪ A };\n" +
                    "query { relix.dependencies };");

            assertThat(results.get(0).rows())
                    .extracting(row -> row.get("dependent").asDisplayString()
                            + " -> " + row.get("depends_on").asDisplayString())
                    .containsExactly("Dup -> A");
        }

        @Test
        @DisplayName("relix.dependencies never lists a base relation as a dependent")
        void dependenciesBasesAreNotDependents() {
            var results = run(
                    "Users := [| id | name  |\n           | 1  | Alice |];\n" +
                    "Adults := { σ id > 0 (Users) };\n" +
                    "query { relix.dependencies };");

            assertThat(results.get(0).rows())
                    .extracting(row -> row.get("dependent").asDisplayString())
                    .doesNotContain("Users");
        }

        @Test
        @DisplayName("executes a query over relix.plan end-to-end (one row per IR node)")
        void executesPlan() {
            var results = run(
                    "Users := [| id | name  |\n           | 1  | Alice |];\n" +
                    "Adults := { σ id > 0 (Users) };\n" +
                    "query { τ node_id (σ query = \"Adults\" (relix.plan)) };");

            assertThat(results).hasSize(1);
            QueryResult r = results.get(0);
            assertThat(r.schema()).hasColumnNames("query", "node_id", "parent_id", "ordinal", "op", "label", "relation", "schema_code", "materialization", "depth");
            // σ over the Users leaf → two nodes, root then leaf.
            assertThat(r.rows()).extracting(row -> row.get("op").asDisplayString())
                    .containsExactly("Selection", "Relation");
            assertThat(r.rows().get(0).get("parent_id").isNull()).isTrue();   // root
            assertThat(r.rows().get(1).get("parent_id").asDisplayString())
                    .isEqualTo(r.rows().get(0).get("node_id").asDisplayString());
            // Only the Relation leaf names a relation; non-leaf nodes carry NULL.
            assertThat(r.rows().get(0).get("relation").isNull()).isTrue();    // Selection
            assertThat(r.rows().get(1).get("relation").asDisplayString())
                    .isEqualTo("Users");
        }

        @Test
        @DisplayName("CLOSURE over relix.plan's parent edge walks a query's subtree")
        void closureSubtree() {
            var results = run(
                    "Users := [| id | name  |\n           | 1  | Alice |];\n" +
                    "Adults := { σ id > 0 (Users) };\n" +
                    "Edges := { π parent_id, node_id (σ query = \"Adults\" (relix.plan)) };\n" +
                    "Subtree := { CLOSURE parent_id, node_id (Edges) };\n" +
                    "query { Subtree };");

            // The root (node_id 0) reaches its single descendant (node_id 1).
            assertThat(results.get(0).rows())
                    .anySatisfy(row -> {
                        assertThat(row).hasValue("parent_id", "0")
                                .hasValue("node_id", "1");
                    });
        }

        @Test
        @DisplayName("a catalog relation plans to an in-engine Scan, never a PushedScan (no pushdown)")
        void noPushdown() {
            SemanticModel model = analyze(
                    "Users := [| id |\n           | 1  |];\nquery { relix.relations };")
                    .model().orElseThrow();
            List<RelNode> roots = model.rootQueries().stream()
                    .map(q -> ((ExpressionQueryTarget) q.target()).expression()).toList();

            Map<String, PhysicalNode> plans = new LinkedHashMap<>();
            EXECUTOR.plan(model, roots, event -> { }, (label, plan, rows) -> plans.put(label, plan));

            PhysicalNode plan = plans.get("<expression 1>");
            assertThat(plan).isNode(PhysicalNode.Scan.class);
            assertThat(plan).isNotInstanceOf(PhysicalNode.PushedScan.class);
        }

        @Test
        @DisplayName("executes a query over relix.events end-to-end (the previous run's feed)")
        void executesEvents() {
            var results = runWithFeed(
                    "query { τ seq (relix.events) };",
                    List.of(QueryEvent.of(QueryEvent.Stage.OPTIMIZE, "SEL-001",
                                    "selection pushed below join", "Adults"),
                            QueryEvent.of(QueryEvent.Stage.PLAN, "JOIN",
                                    "hash join, build side left", "Adults"),
                            QueryEvent.of(QueryEvent.Stage.EXECUTE, "OPTIMIZE",
                                    "group skipped as infeasible")));

            assertThat(results).hasSize(1);
            QueryResult r = results.get(0);
            assertThat(r.schema()).hasColumnNames(
                    "seq", "stage", "code", "description", "target", "rows", "elapsed");
            assertThat(r.rows()).extracting(row -> row.get("code").asDisplayString())
                    .containsExactly("SEL-001", "JOIN", "OPTIMIZE");
            // An event whose emitter knew no owning query surfaces as a NULL target.
            assertThat(r.rows().get(0)).hasValue("target", "Adults");
            assertThat(r.rows().get(2).get("target").isNull()).isTrue();
        }

        @Test
        @DisplayName("an event's elapsed time reaches the feed as a DURATION, NULL where none was measured")
        void eventsCarryDuration() {
            // The Java feed and the relation are the same feed, so a measurement readable
            // through metrics() and not through relix.events would be two surfaces
            // disagreeing about what a run observed. DURATION rather than a count of
            // milliseconds: the engine has the type, so SECONDS(elapsed) and
            // τ elapsed DESC work without a unit having to be agreed with the reader.
            // Named `elapsed` and not `duration` because DURATION opens a temporal
            // literal, so a column of that name could never be referenced at all.
            var results = runWithFeed(
                    "query { τ seq (π seq, code, elapsed (relix.events)) };",
                    List.of(QueryEvent.of(QueryEvent.Stage.EXECUTE, "ROWS",
                                    "query delivered 2 rows", "Adults")
                                    .withMetrics(EventMetrics.of(2, Duration.ofMillis(4))),
                            QueryEvent.of(QueryEvent.Stage.OPTIMIZE, "SEL-001",
                                    "selection pushed below join", "Adults")));

            QueryResult r = results.get(0);
            assertThat(r.rows().get(0)).hasValue("elapsed", "PT0.004S");
            assertThat(r.rows().get(1).get("elapsed").isNull())
                    .as("a rule firing takes no time worth reporting, so it measures none")
                    .isTrue();
        }

        @Test
        @DisplayName("relix.events is empty when nothing was observed (the batch-run case)")
        void eventsEmptyWithoutAFeed() {
            var results = run("query { relix.events };");
            assertThat(results.get(0).rows()).isEmpty();
        }

        @Test
        @DisplayName("relix.rules keeps only the optimizer's slice of the feed")
        void rulesFiltersOptimizeStage() {
            var results = runWithFeed(
                    "query { τ seq (relix.rules) };",
                    List.of(QueryEvent.of(QueryEvent.Stage.OPTIMIZE, "SEL-001", "pushed", "Q"),
                            QueryEvent.of(QueryEvent.Stage.PLAN, "JOIN", "hash join", "Q"),
                            QueryEvent.of(QueryEvent.Stage.OPTIMIZE, "PROJ-003", "merged", "Q")));

            QueryResult r = results.get(0);
            assertThat(r.schema()).hasColumnNames("seq", "code", "description", "target");
            assertThat(r.rows()).extracting(row -> row.get("code").asDisplayString())
                    .containsExactly("SEL-001", "PROJ-003");
        }

        @Test
        @DisplayName("γ over the feed counts rule firings by code — the motivating query")
        void aggregatesRuleFirings() {
            var results = runWithFeed(
                    "query { γ code, COUNT(*) → fired (σ stage = \"OPTIMIZE\" (relix.events)) };",
                    List.of(QueryEvent.of(QueryEvent.Stage.OPTIMIZE, "SEL-001", "pushed", "Q"),
                            QueryEvent.of(QueryEvent.Stage.OPTIMIZE, "SEL-001", "pushed again", "Q"),
                            QueryEvent.of(QueryEvent.Stage.PLAN, "JOIN", "hash join", "Q")));

            assertThat(results.get(0).rows()).singleElement().satisfies(row -> {
                assertThat(row).hasValue("code", "SEL-001")
                        .hasValue("fired", "2");
            });
        }
    }

    // ── observing an ordinary run (the producer side of relix.events) — #35 ─────

    @Nested
    @DisplayName("Observed execution — executeStreaming(…, listener)")
    class ObservedExecution {

        @Test
        @DisplayName("reports the run's decisions, tagged with the owning query where the "
                + "event names no subject of its own")
        void reportsTaggedEvents() {
            SemanticModel model = analyze(
                    "Users := [| id | name  |\n           | 1  | Alice |];\n"
                    + "Orders := [| id | amount |\n           | 1  | 10     |];\n"
                    + "Joined := { Users ⋈ Orders };\n"
                    + "query Joined;").model().orElseThrow();

            List<QueryEvent> observed = new ArrayList<>();
            EXECUTOR.executeStreaming(model, ExecutionContext.inlineOnly(model).connector(),
                    (label, schema, rows) -> rows.forEach(row -> { }),
                    ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS, observed::add);

            assertThat(observed).isNotEmpty();
            // The label answers "which query did this come from", which is worth
            // supplying only where the event has nothing more specific to say.
            assertThat(observed.stream()
                    .filter(e -> !e.code().equals("SCAN") && !e.code().equals("MATERIALIZE"))
                    .toList())
                    .isNotEmpty()
                    .allSatisfy(e -> assertThat(e.target()).contains("Joined"));
            // A scan names the relation it read, and keeps it: that is strictly more
            // informative than the label that would otherwise have replaced it.
            assertThat(observed.stream().filter(e -> e.code().equals("SCAN")).toList())
                    .extracting(e -> e.target().orElseThrow())
                    .containsExactlyInAnyOrder("users", "orders");
            // And a materialising operator names itself, for the same reason: "which
            // operator buffered this" is the question the event exists to answer.
            assertThat(observed.stream().filter(e -> e.code().equals("MATERIALIZE")).toList())
                    .isNotEmpty()
                    .allSatisfy(e -> assertThat(e.target()).contains("Join"));
            assertThat(observed).anySatisfy(e ->
                    assertThat(e.stage()).isEqualTo(QueryEvent.Stage.PLAN));
        }

        @Test
        @DisplayName("every executed query reports the rows it delivered")
        void reportsRowCount() {
            SemanticModel model = analyze(
                    "Users := [| id | name  |\n           | 1  | Alice |\n           | 2  | Bob   |];\n"
                    + "query { Users };").model().orElseThrow();

            List<QueryEvent> observed = new ArrayList<>();
            EXECUTOR.executeStreaming(model, ExecutionContext.inlineOnly(model).connector(),
                    (label, schema, rows) -> rows.forEach(row -> { }),
                    ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS, observed::add);

            assertThat(observed)
                    .as("a drained query reports its cardinality as a number, not only as prose")
                    .anySatisfy(e -> {
                        assertThat(e.code()).isEqualTo("ROWS");
                        assertThat(e.stage()).isEqualTo(QueryEvent.Stage.EXECUTE);
                        assertThat(e.metrics().rows()).hasValue(2L);
                    });
        }

        @Test
        @DisplayName("an empty result reports 0, which is a measurement rather than an absence")
        void reportsZeroForAnEmptyResult() {
            SemanticModel model = analyze(
                    "Users := [| id | name  |\n           | 1  | Alice |];\n"
                    + "query { σ id > 99 (Users) };").model().orElseThrow();

            List<QueryEvent> observed = new ArrayList<>();
            EXECUTOR.executeStreaming(model, ExecutionContext.inlineOnly(model).connector(),
                    (label, schema, rows) -> rows.forEach(row -> { }),
                    ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS, observed::add);

            // The #524 distinction, on the feed: "returned nothing" and "counted nothing"
            // are different facts, and relix.events shows 0 for the first, NULL for the
            // second. A query that matched no rows still reports.
            assertThat(observed).anySatisfy(e -> {
                assertThat(e.code()).isEqualTo("ROWS");
                assertThat(e.metrics().rows()).hasValue(0L);
            });
        }

        @Test
        @DisplayName("the count is rows delivered, so a consumer that stops early reports what it took")
        void countsRowsDelivered() {
            SemanticModel model = analyze(
                    "Users := [| id | name  |\n           | 1  | Alice |\n           | 2  | Bob   |];\n"
                    + "query { Users };").model().orElseThrow();

            List<QueryEvent> observed = new ArrayList<>();
            EXECUTOR.executeStreaming(model, ExecutionContext.inlineOnly(model).connector(),
                    (label, schema, rows) -> rows.limit(1).forEach(row -> { }),
                    ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS, observed::add);

            // Deliberately 1, not 2: the tally counts what crossed to the consumer, which
            // is why the column is documented as rows delivered rather than cardinality —
            // a partial read must not later be mistaken for the relation's size.
            assertThat(observed).anySatisfy(e -> {
                assertThat(e.code()).isEqualTo("ROWS");
                assertThat(e.metrics().rows()).hasValue(1L);
            });
        }

        @Test
        @DisplayName("the tally survives a consumer that reads the stream in parallel")
        void countsCorrectlyUnderParallelConsumption() {
            // The stream is handed to an arbitrary RowStreamConsumer, so nothing stops one
            // calling parallel() on it. A plain counter under-reports there *silently* —
            // the event still fires and still looks plausible — which is the worst shape
            // for a number EventMetrics exists so consumers can compute with.
            int rows = 2000;
            StringBuilder table = new StringBuilder("Users := [| id |");
            for (int i = 1; i <= rows; i++) {
                table.append("\n           | ").append(i).append(" |");
            }
            table.append("];\nquery { Users };");
            SemanticModel model = analyze(table.toString()).model().orElseThrow();

            List<QueryEvent> observed = Collections.synchronizedList(new ArrayList<>());
            EXECUTOR.executeStreaming(model, ExecutionContext.inlineOnly(model).connector(),
                    (label, schema, stream) -> stream.parallel().forEach(row -> { }),
                    ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS, observed::add);

            assertThat(observed).anySatisfy(e -> {
                assertThat(e.code()).isEqualTo("ROWS");
                assertThat(e.metrics().rows()).hasValue((long) rows);
            });
        }

        @Test
        @DisplayName("a consumer that throws produces no row count")
        void reportsNothingWhenTheConsumerFails() {
            // Deliberate, not incidental: the event is emitted after the try-with-resources
            // and so is skipped when the consumer throws. A query that failed part-way
            // delivered no result, and a count for it would be read downstream as one
            // relation's size — the opposite of what ADR-0027 D10 wants the feed for.
            SemanticModel model = analyze(
                    "Users := [| id | name  |\n           | 1  | Alice |\n           | 2  | Bob   |];\n"
                    + "query { Users };").model().orElseThrow();

            List<QueryEvent> observed = new ArrayList<>();
            assertThatThrownBy(() ->
                    EXECUTOR.executeStreaming(model, ExecutionContext.inlineOnly(model).connector(),
                            (label, schema, rows) -> rows.forEach(row -> {
                                throw new IllegalStateException("consumer failed");
                            }),
                            ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS, observed::add))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("consumer failed");

            assertThat(observed)
                    .as("a failed query must not report a row count at all")
                    .noneSatisfy(e -> assertThat(e.code()).isEqualTo("ROWS"));
        }

        @Test
        @DisplayName("an executed query reports how long it took, and its scan how long it took")
        void reportsElapsedTime() {
            // #788: the feed is the only profiler an embedded engine has, so the events
            // that measure rows measure time too. What is asserted here is presence and
            // sign, never a threshold — a wall-clock number is for a person diagnosing a
            // slow query, and asserting on one is the flaky gate the benchmark tier
            // exists to avoid.
            SemanticModel model = analyze(
                    "Users := [| id | name  |\n           | 1  | Alice |\n           | 2  | Bob   |];\n"
                    + "query { Users };").model().orElseThrow();

            List<QueryEvent> observed = new ArrayList<>();
            EXECUTOR.executeStreaming(model, ExecutionContext.inlineOnly(model).connector(),
                    (label, schema, rows) -> rows.forEach(row -> { }),
                    ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS, observed::add);

            assertThat(observed).anySatisfy(e -> {
                assertThat(e.code()).isEqualTo("ROWS");
                assertThat(e.metrics().duration()).isPresent();
            });
            assertThat(observed).anySatisfy(e -> {
                assertThat(e.code()).isEqualTo("SCAN");
                assertThat(e.metrics().duration()).isPresent();
            });
            assertThat(observed)
                    .filteredOn(e -> e.metrics().duration().isPresent())
                    .allSatisfy(e -> assertThat(e.metrics().duration().orElseThrow().isNegative())
                            .as("%s reported a negative elapsed time", e.code())
                            .isFalse());
        }

        @Test
        @DisplayName("a scan's elapsed time is nested inside the query's, never larger")
        void scanTimeIsNestedInsideTheQueryTime() {
            // An invariant rather than a threshold, and therefore safe to assert: every
            // interval the scan times happens while the query's own interval is open, so
            // the arithmetic holds on any machine at any speed. It says nothing about the
            // stronger claim the scan makes — that its number *excludes* the consumer's
            // work — which is a property of where the timed window closes rather than
            // something a clock reading can demonstrate without a timing assertion.
            SemanticModel model = analyze(
                    "Users := [| id |\n           | 1  |\n           | 2  |];\n"
                    + "query { τ id DESC (Users) };").model().orElseThrow();

            List<QueryEvent> observed = new ArrayList<>();
            EXECUTOR.executeStreaming(model, ExecutionContext.inlineOnly(model).connector(),
                    (label, schema, rows) -> rows.forEach(row -> { }),
                    ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS, observed::add);

            Duration query = elapsedOf(observed, "ROWS");
            assertThat(elapsedOf(observed, "SCAN")).isLessThanOrEqualTo(query);
            assertThat(elapsedOf(observed, "MATERIALIZE")).isLessThanOrEqualTo(query);
        }

        /** The elapsed time of the one event with {@code code}, which must carry one. */
        private static Duration elapsedOf(List<QueryEvent> observed, String code) {
            return observed.stream()
                    .filter(e -> e.code().equals(code))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no " + code + " event in the feed"))
                    .metrics().duration()
                    .orElseThrow(() -> new AssertionError(code + " carried no duration"));
        }

        @Test
        @DisplayName("observes nothing, and changes nothing, with the NONE listener")
        void noneListenerIsInert() {
            SemanticModel model = analyze(
                    "Users := [| id |\n           | 1  |];\nquery { Users };")
                    .model().orElseThrow();

            List<String> rendered = new ArrayList<>();
            EXECUTOR.executeStreaming(model, ExecutionContext.inlineOnly(model).connector(),
                    (label, schema, rows) -> rows.forEach(row -> rendered.add(label)),
                    ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS, QueryEventListener.NONE);

            assertThat(rendered).hasSize(1);
        }
    }

    // ── introspection standard library (relix.* stdlib) — #301 ──────────────────

    @Nested
    @DisplayName("introspection stdlib (relix.unused / deps / impact)")
    class IntrospectionStdlib {

        @Test
        @DisplayName("relix.unused lists relations nothing else references")
        void unused() {
            var results = run(
                    "Users := [| id | name  |\n           | 1  | Alice |];\n" +
                    "Adults := { σ id > 0 (Users) };\n" +
                    "query { relix.unused };");

            // Users is referenced by Adults; Adults is referenced by nothing.
            assertThat(results.get(0).rows())
                    .extracting(row -> row.get("name").asDisplayString())
                    .containsExactly("Adults");
        }

        @Test
        @DisplayName("relix.impact(r) returns the transitive dependents of r (reverse lineage)")
        void impact() {
            var results = run(
                    "Users := [| id |\n           | 1  |];\n" +
                    "Adults := { σ id > 0 (Users) };\n" +
                    "B := { π id (Adults) };\n" +
                    "query { relix.impact(\"Users\") };");

            assertThat(results.get(0).rows())
                    .extracting(row -> row.get("dependent").asDisplayString())
                    .containsExactlyInAnyOrder("Adults", "B");
        }

        @Test
        @DisplayName("relix.deps(r) returns the transitive dependencies of r (upstream lineage)")
        void deps() {
            var results = run(
                    "Users := [| id |\n           | 1  |];\n" +
                    "Adults := { σ id > 0 (Users) };\n" +
                    "B := { π id (Adults) };\n" +
                    "query { relix.deps(\"B\") };");

            assertThat(results.get(0).rows())
                    .extracting(row -> row.get("depends_on").asDisplayString())
                    .containsExactlyInAnyOrder("Adults", "Users");
        }

        @Test
        @DisplayName("relix.schema(rel) lists one relation's columns, with type and position")
        void schemaOfARelation() {
            var results = run(
                    "Users := [| id | name  | dept |\n           | 1  | Alice | ops  |];\n" +
                    "query { relix.schema(\"Users\") };");

            assertThat(results.get(0).rows())
                    .extracting(
                            row -> row.get("column").asDisplayString(),
                            row -> row.get("type").asDisplayString(),
                            row -> row.get("ordinal").asDisplayString())
                    .containsExactlyInAnyOrder(
                            tuple("id", "N", "0"),
                            tuple("name", "S", "1"),
                            tuple("dept", "S", "2"));
        }

        @Test
        @DisplayName("relix.schema(rel) reads a derived relation's inferred heading too")
        void schemaOfADerivedRelation() {
            var results = run(
                    "Users := [| id | name  |\n           | 1  | Alice |];\n" +
                    "Names := { π name (Users) };\n" +
                    "query { relix.schema(\"Names\") };");

            assertThat(results.get(0).rows())
                    .extracting(row -> row.get("column").asDisplayString())
                    .containsExactly("name");
        }

        @Test
        @DisplayName("relix.schema(rel) of an unknown relation is empty, not an error")
        void schemaOfAnUnknownRelation() {
            var results = run(
                    "Users := [| id |\n           | 1  |];\n" +
                    "query { relix.schema(\"NoSuchRelation\") };");

            assertThat(results.get(0).rows()).isEmpty();
        }

        @Test
        @DisplayName("relix.find(col) lists the relations that have a given column, with its type")
        void find() {
            var results = run(
                    "Users := [| id | name  |\n           | 1  | Alice |];\n" +
                    "Adults := { σ id > 0 (Users) };\n" +
                    "query { relix.find(\"name\") };");

            // Both Users and the derived Adults carry a STRING `name` column.
            assertThat(results.get(0).rows())
                    .extracting(
                            row -> row.get("relation").asDisplayString(),
                            row -> row.get("type").asDisplayString())
                    .containsExactlyInAnyOrder(
                            tuple("Users", "S"),
                            tuple("Adults", "S"));
        }

        @Test
        @DisplayName("relix.cycles is empty for an acyclic script")
        void cyclesEmptyWhenAcyclic() {
            var results = run(
                    "Users := [| id |\n           | 1  |];\n" +
                    "Adults := { σ id > 0 (Users) };\n" +
                    "query { relix.cycles };");

            assertThat(results.get(0).rows()).isEmpty();
        }

        @Test
        @DisplayName("relix.funcs(category) filters the function library by category")
        void funcs() {
            var results = run("query { relix.funcs(\"math\") };");

            // Every row is a Math builtin; Abs is one of them (arity 1).
            assertThat(results.get(0).rows())
                    .extracting(row -> row.get("name").asDisplayString())
                    .contains("Abs");
        }
    }
}

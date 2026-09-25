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

import com.darkcollective.relix.processor.internal.QueryExecutor;
import com.darkcollective.relix.processor.internal.QueryResult;
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.semantic.internal.SemanticResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end tests for the {@code LATERAL} correlated TVF join: parse →
 * semantic analysis → planner → execution.
 *
 * <p>Unlike a plain TVF call (task 58), arguments to a LATERAL join may reference
 * columns from the outer (left) relation, so the TVF is re-invoked once per outer row.
 */
@DisplayName("LATERAL join — end-to-end")
final class LateralJoinIntegrationTest extends ProcessorTestSupport {

    private static final QueryExecutor EXECUTOR = new QueryExecutor();

    private static final String DEF_ORDERS_FOR =
            "def ordersFor(cid: NUMBER): RELATION := { σ customer_id = cid (Orders) };\n";
    private static final String ORDERS =
            "Orders := [| order_id | customer_id | amount |\n" +
            "           | 1        | 2           | 100    |\n" +
            "           | 2        | 3           | 50     |\n" +
            "           | 3        | 2           | 75     |];\n";
    private static final String CUSTOMERS =
            "Customers := [| customer_id | name  |\n" +
            "              | 2           | Alice |\n" +
            "              | 3           | Bob   |];\n";

    private static List<QueryResult> run(String src) {
        SemanticResult result = analyze(src);
        assertThat(result.errors()).as("semantic errors").isEmpty();
        return EXECUTOR.execute(result);
    }

    @Nested
    @DisplayName("Basic correlated join")
    class BasicCorrelated {

        @Test
        @DisplayName("Customers LATERAL ordersFor(customer_id) — the golden path")
        void goldenPath() {
            var results = run(DEF_ORDERS_FOR + ORDERS + CUSTOMERS +
                    "query { Customers LATERAL ordersFor(customer_id) };");
            assertThat(results).hasSize(1);
            List<Row> rows = results.get(0).rows();
            // Alice (cid=2) has orders 1 + 3; Bob (cid=3) has order 2 → 3 total rows
            assertThat(rows).hasSize(3);
            assertThat(rows).extracting(r -> r.get("name").asDisplayString())
                    .containsExactlyInAnyOrder("Alice", "Alice", "Bob");
            assertThat(rows).extracting(r -> r.get("amount").asDisplayString())
                    .containsExactlyInAnyOrder("100", "75", "50");
        }

        @Test
        @DisplayName("output schema includes left and TVF columns")
        void outputSchemaContainsAllColumns() {
            var results = run(DEF_ORDERS_FOR + ORDERS + CUSTOMERS +
                    "query { Customers LATERAL ordersFor(customer_id) };");
            Row first = results.get(0).rows().get(0);
            // All columns from both sides should be accessible
            assertThat(first.get("customer_id")).isNotNull();
            assertThat(first.get("name")).isNotNull();
            assertThat(first.get("order_id")).isNotNull();
            assertThat(first.get("amount")).isNotNull();
        }

        @Test
        @DisplayName("constant argument in LATERAL — same as non-LATERAL TVF call")
        void constantArgument() {
            var results = run(DEF_ORDERS_FOR + ORDERS + CUSTOMERS +
                    "query { Customers LATERAL ordersFor(2) };");
            List<Row> rows = results.get(0).rows();
            // ordersFor(2) always returns 2 rows; 2 customers × 2 orders = 4 rows
            assertThat(rows).hasSize(4);
        }
    }

    @Nested
    @DisplayName("Outer customer with no matching orders")
    class NoMatchingOrders {

        @Test
        @DisplayName("customer with no orders produces no output rows (inner join semantics)")
        void customerWithNoOrders() {
            var results = run(DEF_ORDERS_FOR + ORDERS +
                    "Customers := [| customer_id | name  |\n" +
                    "              | 2           | Alice |\n" +
                    "              | 99          | Dave  |];\n" +   // Dave has no orders
                    "query { Customers LATERAL ordersFor(customer_id) };");
            List<Row> rows = results.get(0).rows();
            // Dave (cid=99) matches no orders; Alice has 2 → 2 total rows
            assertThat(rows).hasSize(2);
            assertThat(rows).allSatisfy(r ->
                    assertThat(r.get("name").asDisplayString()).isEqualTo("Alice"));
        }
    }

    @Nested
    @DisplayName("Selection applied to LATERAL output")
    class SelectionOnLateral {

        @Test
        @DisplayName("σ amount > 60 (Customers LATERAL ordersFor(customer_id))")
        void selectionFiltersLateralOutput() {
            var results = run(DEF_ORDERS_FOR + ORDERS + CUSTOMERS +
                    "query { σ amount > 60 (Customers LATERAL ordersFor(customer_id)) };");
            List<Row> rows = results.get(0).rows();
            // Alice orders: 100 (pass), 75 (pass); Bob orders: 50 (fail) → 2 rows
            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(r -> r.get("amount").asDisplayString())
                    .containsExactlyInAnyOrder("100", "75");
        }
    }

    @Nested
    @DisplayName("Projection applied to LATERAL output")
    class ProjectionOnLateral {

        @Test
        @DisplayName("π name, amount (Customers LATERAL ordersFor(customer_id))")
        void projectionOnLateral() {
            var results = run(DEF_ORDERS_FOR + ORDERS + CUSTOMERS +
                    "query { π name, amount (Customers LATERAL ordersFor(customer_id)) };");
            List<Row> rows = results.get(0).rows();
            assertThat(rows).hasSize(3);
            assertThat(rows).extracting(r -> r.get("name").asDisplayString())
                    .containsExactlyInAnyOrder("Alice", "Alice", "Bob");
        }
    }

    @Nested
    @DisplayName("Two-parameter correlated TVF")
    class TwoParamCorrelated {

        @Test
        @DisplayName("LATERAL with both column-ref and constant argument")
        void mixedArguments() {
            var results = run(
                    "def topOrders(cid: NUMBER, lim: NUMBER): RELATION :=\n" +
                    "    { σ customer_id = cid ∧ amount >= lim (Orders) };\n" +
                    ORDERS +
                    "Customers := [| customer_id | name  | min_amount |\n" +
                    "              | 2           | Alice | 80         |\n" +
                    "              | 3           | Bob   | 40         |];\n" +
                    "query { Customers LATERAL topOrders(customer_id, min_amount) };"
            );
            List<Row> rows = results.get(0).rows();
            // Alice (cid=2, min=80): order 100 → 1 row; Bob (cid=3, min=40): order 50 → 1 row
            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(r -> r.get("name").asDisplayString())
                    .containsExactlyInAnyOrder("Alice", "Bob");
            assertThat(rows).extracting(r -> r.get("amount").asDisplayString())
                    .containsExactlyInAnyOrder("100", "50");
        }
    }

    @Nested
    @DisplayName("Argument kinds other than NUMBER")
    class ArgumentKinds {

        // Every lateral case in this suite passed a numeric id, so LateralExecutor's
        // valueToLiteral had run its NumberValue arm and no other — including the string
        // one, which is the most ordinary lateral there is. Lifting the value is only half
        // of it: the literal then has to survive being substituted into the body and
        // re-planned, which a unit test of the lift alone cannot show.
        //
        // The TVF therefore *projects its parameter*. The assertion is on the value that
        // came back out of the planned body, not on a row count — a body that ignores its
        // parameter would pass a count assertion while proving nothing about substitution.
        private static final String ECHO =
                "def echo(p: %s): RELATION := { π order_id, p → arg (Orders) };\n";
        private static final String TAG =
                "Tag := [| tag |\n        | x   |];\n";

        /** Parameter type → the argument to pass, and the value it must echo back. */
        private static final Map<String, String[]> LITERALS = Map.of(
                "STRING",    new String[] {"\"Alice\"",                       "Alice"},
                "DATE",      new String[] {"DATE '2026-06-15'",               "2026-06-15"},
                "TIMESTAMP", new String[] {"TIMESTAMP '2026-06-15T13:40:00Z'", "2026-06-15T13:40:00Z"},
                "DURATION",  new String[] {"DURATION 'PT30M'",                "PT30M"},
                // LocalTime renders a whole minute without its seconds, so the echoed form
                // is 13:40 rather than 13:40:00. A display convention, not a lost value.
                "TIME",      new String[] {"TIME '13:40:00'",                 "13:40"},
                // BOOLEAN is not a parameter type the grammar accepts, so the boolean arm
                // is reached through an ANY parameter and a boolean-valued function.
                "ANY",       new String[] {"IsNull(tag)",                     "false"});

        @TestFactory
        @DisplayName("a lifted literal survives substitution into the planned body")
        Stream<DynamicTest> everyLiteralKindReachesTheBody() {
            return LITERALS.entrySet().stream().map(entry -> {
                String type = entry.getKey();
                String argument = entry.getValue()[0];
                String echoed = entry.getValue()[1];
                return DynamicTest.dynamicTest(type + " ← " + argument, () -> {
                    var results = run(String.format(ECHO, type) + ORDERS + TAG
                            + "query { Tag LATERAL echo(" + argument + ") };");

                    assertThat(results.get(0).rows())
                            .as("%s argument must reach the body as the value it was", type)
                            .isNotEmpty()
                            .allSatisfy(row -> assertThat(row.get("arg").asDisplayString())
                                    .isEqualTo(echoed));
                });
            });
        }

        @Test
        @DisplayName("a correlated STRING argument varies per outer row")
        void correlatedStringArgument() {
            // The realistic case, and the one the memo keys on: two outer rows must invoke
            // the function with different arguments, not share one.
            var results = run(String.format(ECHO, "STRING") + ORDERS + CUSTOMERS
                    + "query { Customers LATERAL echo(name) };");

            assertThat(results.get(0).rows())
                    .extracting(r -> r.get("arg").asDisplayString())
                    .as("each customer's own name must reach its own invocation")
                    .containsExactlyInAnyOrder("Alice", "Alice", "Alice", "Bob", "Bob", "Bob");
        }

        @Test
        @DisplayName("a NULL argument is refused, with a diagnostic naming the rule")
        void nullArgumentIsRefused() {
            // Worth pinning because it is ordinary data rather than a programming error: a
            // correlated column with a missing value stops the query. There is no literal
            // that denotes NULL, and inventing one would silently invoke the function on a
            // value the row never held — so refusing is right, and stating it is the point.
            assertThatThrownBy(() -> run(String.format(ECHO, "STRING") + ORDERS
                    + "Missing := [| name |\n            |      |];\n"
                    + "query { Missing LATERAL echo(name) };"))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("NullValue")
                    .hasMessageContaining("only scalar non-null values");
        }

        @Test
        @DisplayName("a nested argument is refused, with a diagnostic naming the rule")
        void structArgumentIsRefused() {
            assertThatThrownBy(() -> run(String.format(ECHO, "ANY") + ORDERS + CUSTOMERS
                    + "query { Customers LATERAL echo({who: name}) };"))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("StructValue")
                    .hasMessageContaining("only scalar non-null values");
        }
    }

    @Nested
    @DisplayName("Repeated argument tuples (memoized bodies, #539)")
    class RepeatedArguments {

        /**
         * Two left rows carrying the same correlated value share one planned body and one
         * execution ({@code LateralMemo}).  What must not change is the answer: each of
         * them still gets its own copy of the TVF rows, concatenated with its own left
         * row.
         */
        @Test
        @DisplayName("two customers with the same id each get the full TVF result")
        void duplicateCorrelationValue() {
            var results = run(DEF_ORDERS_FOR + ORDERS +
                    "Customers := [| customer_id | name  |\n" +
                    "              | 2           | Alice |\n" +
                    "              | 2           | Alicia |\n" +   // same id, different name
                    "              | 3           | Bob   |];\n" +
                    "query { Customers LATERAL ordersFor(customer_id) };");
            List<Row> rows = results.get(0).rows();

            // cid=2 has 2 orders and appears on 2 left rows; cid=3 has 1 → 5 rows.
            assertThat(rows).hasSize(5);
            assertThat(rows).extracting(r -> r.get("name").asDisplayString() + ":"
                            + r.get("amount").asDisplayString())
                    .containsExactlyInAnyOrder("Alice:100", "Alice:75",
                            "Alicia:100", "Alicia:75", "Bob:50");
        }

        @Test
        @DisplayName("a repeated tuple in a two-argument TVF is keyed on the whole tuple")
        void duplicateArgumentTuple() {
            var results = run(
                    "def topOrders(cid: NUMBER, lim: NUMBER): RELATION :=\n" +
                    "    { σ customer_id = cid ∧ amount >= lim (Orders) };\n" +
                    ORDERS +
                    "Customers := [| customer_id | name  | min_amount |\n" +
                    "              | 2           | Alice | 80         |\n" +
                    "              | 2           | Alicia | 80        |\n" +   // same tuple
                    "              | 2           | Amy   | 10         |];\n" + // same cid only
                    "query { Customers LATERAL topOrders(customer_id, min_amount) };");
            List<Row> rows = results.get(0).rows();

            // (2, 80) → order 100 for Alice and Alicia; (2, 10) → orders 100 + 75 for Amy.
            assertThat(rows).extracting(r -> r.get("name").asDisplayString() + ":"
                            + r.get("amount").asDisplayString())
                    .containsExactlyInAnyOrder("Alice:100", "Alicia:100", "Amy:100", "Amy:75");
        }
    }
}

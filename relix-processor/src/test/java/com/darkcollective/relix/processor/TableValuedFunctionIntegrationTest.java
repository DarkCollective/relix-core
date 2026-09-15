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

import com.darkcollective.relix.semantic.SemanticResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for table-valued (relation-returning) functions: parse →
 * semantic analysis → planner inlining → execution.
 *
 * <p>Arguments are constant expressions (the supported binding mode): the planner
 * inlines the body with each parameter substituted by its argument.
 */
@DisplayName("Table-valued functions — end-to-end")
final class TableValuedFunctionIntegrationTest extends ProcessorTestSupport {

    private static final QueryExecutor EXECUTOR = new QueryExecutor();

    /** Analyzes, asserts no semantic errors, then executes inline-only. */
    private static List<QueryResult> run(String src) {
        SemanticResult result = analyze(src);
        assertThat(result.errors()).as("semantic errors").isEmpty();
        return EXECUTOR.execute(result);
    }

    @Nested
    @DisplayName("Parameterized selection")
    class ParameterizedSelection {

        @Test
        @DisplayName("ordersFor(cid) filters Orders by a constant argument")
        void filtersByConstant() {
            var results = run(
                    "def ordersFor(cid: NUMBER): RELATION := { σ customer_id = cid (Orders) };\n" +
                    "Orders := [| order_id | customer_id | amount |\n" +
                    "           | 1        | 2           | 100    |\n" +
                    "           | 2        | 3           | 50     |\n" +
                    "           | 3        | 2           | 75     |];\n" +
                    "query { ordersFor(2) };"
            );
            assertThat(results).hasSize(1);
            List<Row> rows = results.get(0).rows();
            assertThat(rows).hasSize(2);
            assertThat(rows).allSatisfy(r ->
                    assertThat(r.get("customer_id").asDisplayString()).isEqualTo("2"));
            assertThat(rows).extracting(r -> r.get("amount").asDisplayString())
                    .containsExactlyInAnyOrder("100", "75");
        }

        @Test
        @DisplayName("RELATION return-type keyword is case-insensitive (relation / Relation)")
        void returnTypeKeywordCaseInsensitive() {
            var results = run(
                    "def evens(): relation := { σ n = 2 ∨ n = 4 (Nums) };\n" +
                    "Nums := [| n |\n" +
                    "         | 1 |\n" +
                    "         | 2 |\n" +
                    "         | 4 |];\n" +
                    "query { evens() };"
            );
            assertThat(results.get(0).rows()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Zero-parameter functions")
    class ZeroParam {

        @Test
        @DisplayName("activeUsers() — a parameterless table-valued function")
        void parameterless() {
            var results = run(
                    "def activeUsers(): RELATION := { σ status = \"active\" (Users) };\n" +
                    "Users := [| id | status   |\n" +
                    "          | 1  | active   |\n" +
                    "          | 2  | inactive |\n" +
                    "          | 3  | active   |];\n" +
                    "query { activeUsers() };"
            );
            assertThat(results.get(0).rows()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Parameter used in a projection expression")
    class ParameterInProjection {

        @Test
        @DisplayName("scaledBy(factor) multiplies a column by the parameter")
        void parameterInProjectionOperand() {
            var results = run(
                    "def scaledBy(factor: NUMBER): RELATION := { π n * factor -> scaled (Nums) };\n" +
                    "Nums := [| n |\n" +
                    "         | 3 |\n" +
                    "         | 5 |];\n" +
                    "query { scaledBy(10) };"
            );
            List<Row> rows = results.get(0).rows();
            assertThat(rows).extracting(r -> r.get("scaled").asDisplayString())
                    .containsExactly("30", "50");
        }
    }

    @Nested
    @DisplayName("Multiple parameters")
    class MultiParam {

        @Test
        @DisplayName("inRange(lo, hi) filters Nums to a constant [lo, hi] window")
        void twoParameters() {
            var results = run(
                    "def inRange(lo: NUMBER, hi: NUMBER): RELATION := { σ n >= lo ∧ n <= hi (Nums) };\n" +
                    "Nums := [| n |\n" +
                    "         | 1 |\n" +
                    "         | 5 |\n" +
                    "         | 9 |];\n" +
                    "query { inRange(2, 8) };"
            );
            assertThat(results.get(0).rows()).extracting(r -> r.get("n").asDisplayString())
                    .containsExactly("5");
        }
    }

    @Nested
    @DisplayName("Used as a join input")
    class AsJoinInput {

        @Test
        @DisplayName("Customers ⋈ ordersFor(2) — TVF call as a natural-join operand")
        void naturalJoinWithTvf() {
            var results = run(
                    "def ordersFor(cid: NUMBER): RELATION := { σ customer_id = cid (Orders) };\n" +
                    "Orders := [| order_id | customer_id | amount |\n" +
                    "           | 1        | 2           | 100    |\n" +
                    "           | 2        | 3           | 50     |\n" +
                    "           | 3        | 2           | 75     |];\n" +
                    "Customers := [| customer_id | name  |\n" +
                    "              | 2           | Alice |\n" +
                    "              | 3           | Bob   |];\n" +
                    "query { π name, amount (Customers ⋈ ordersFor(2)) };"
            );
            List<Row> rows = results.get(0).rows();
            assertThat(rows).hasSize(2);
            assertThat(rows).allSatisfy(r ->
                    assertThat(r.get("name").asDisplayString()).isEqualTo("Alice"));
            assertThat(rows).extracting(r -> r.get("amount").asDisplayString())
                    .containsExactlyInAnyOrder("100", "75");
        }
    }

    @Nested
    @DisplayName("Calls and wraps another table-valued function")
    class NestedCalls {

        @Test
        @DisplayName("a TVF whose body calls another TVF inlines transitively")
        void nestedTvf() {
            var results = run(
                    "def ordersFor(cid: NUMBER): RELATION := { σ customer_id = cid (Orders) };\n" +
                    "def bigOrdersFor(cid: NUMBER): RELATION := { σ amount > 60 (ordersFor(cid)) };\n" +
                    "Orders := [| order_id | customer_id | amount |\n" +
                    "           | 1        | 2           | 100    |\n" +
                    "           | 2        | 2           | 50     |\n" +
                    "           | 3        | 2           | 75     |];\n" +
                    "query { bigOrdersFor(2) };"
            );
            assertThat(results.get(0).rows()).extracting(r -> r.get("amount").asDisplayString())
                    .containsExactlyInAnyOrder("100", "75");
        }
    }
}

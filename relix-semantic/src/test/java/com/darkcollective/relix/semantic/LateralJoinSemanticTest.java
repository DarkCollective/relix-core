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
package com.darkcollective.relix.semantic;

import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.QueryTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;


import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;

/**
 * Semantic-analysis tests for {@code LATERAL} correlated TVF joins:
 * schema inference, validation, and IR rendering.
 */
@DisplayName("LATERAL join — semantic analysis")
final class LateralJoinSemanticTest {

    private static final String DEF_ORDERS_FOR =
            "def ordersFor(cid: NUMBER): RELATION := { σ customer_id = cid (Orders) };\n";
    private static final String ORDERS =
            "Orders := [| order_id | customer_id | amount |\n" +
            "           | 1        | 2           | 100    |];\n";
    private static final String CUSTOMERS =
            "Customers := [| customer_id | name  |\n" +
            "              | 2           | Alice |];\n";
    @Nested
    @DisplayName("Schema inference")
    class Inference {

        @Test
        @DisplayName("output schema is left schema ++ TVF return schema")
        void outputSchemaIsConcat() {
            SemanticModel m = model(DEF_ORDERS_FOR + ORDERS + CUSTOMERS +
                    "query { Customers LATERAL ordersFor(customer_id) };");
            var nodeSchemas = m.nodeSchemas();
            // The root query schema should have all Customers columns + all Orders columns
            m.rootQueries().forEach(stmt -> {
                if (stmt.target() instanceof ExpressionQueryTarget eqt) {
                    var schema = nodeSchemas.get(eqt.expression());
                    assertThat(schema).isPresent();
                    schema.ifPresent(s -> {
                        var colNames = s.columns().stream()
                                .map(c -> c.name()).toList();
                        assertThat(colNames).contains("customer_id", "name", "order_id", "amount");
                    });
                }
            });
        }

        @Test
        @DisplayName("a valid LATERAL join produces no semantic errors")
        void validLateral() {
            SemanticResult r = analyze(DEF_ORDERS_FOR + ORDERS + CUSTOMERS +
                    "query { Customers LATERAL ordersFor(customer_id) };");
            assertThat(r).messages().isEmpty();
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("referencing an undefined TVF is an error")
        void unknownFunction() {
            SemanticResult r = analyze(CUSTOMERS +
                    "query { Customers LATERAL undefinedFn(customer_id) };");
            assertThat(r).messages().anyMatch(msg -> msg.contains("undefinedFn"));
        }

        @Test
        @DisplayName("wrong argument count for TVF is an error")
        void wrongArgumentCount() {
            SemanticResult r = analyze(DEF_ORDERS_FOR + ORDERS + CUSTOMERS +
                    "query { Customers LATERAL ordersFor(customer_id, name) };");
            // ordersFor takes 1 argument, passing 2
            assertThat(r).messages().anyMatch(msg ->
                    msg.contains("ordersFor") && (msg.contains("argument") || msg.contains("arity")));
        }

        @Test
        @DisplayName("argument referencing a non-existent left column is an error")
        void unknownColumnInArgument() {
            SemanticResult r = analyze(DEF_ORDERS_FOR + ORDERS + CUSTOMERS +
                    "query { Customers LATERAL ordersFor(nonexistent_col) };");
            assertThat(r).messages().anyMatch(msg ->
                    msg.contains("nonexistent_col"));
        }

        @Test
        @DisplayName("constant argument in LATERAL is valid")
        void constantArgument() {
            SemanticResult r = analyze(DEF_ORDERS_FOR + ORDERS + CUSTOMERS +
                    "query { Customers LATERAL ordersFor(2) };");
            assertThat(r).messages().isEmpty();
        }
    }

    @Nested
    @DisplayName("IR rendering")
    class IrRendering {

        @Test
        @DisplayName("IR report contains LATERAL label with function name")
        void irContainsLateral() {
            SemanticModel m = model(DEF_ORDERS_FOR + ORDERS + CUSTOMERS +
                    "query { Customers LATERAL ordersFor(customer_id) };");
            String ir = IrReport.generate(m);
            assertThat(ir).contains("LATERAL ordersFor");
        }
    }
}

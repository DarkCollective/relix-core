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

import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.function.RelationFunctionSymbol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;

/**
 * Semantic-analysis tests for table-valued (relation-returning) functions:
 * symbol collection, schema inference, validation, and IR rendering.
 */
@DisplayName("Table-valued functions — semantic analysis")
final class TableValuedFunctionSemanticTest {

    private static final String ORDERS =
            "Orders := [| order_id | customer_id | amount |\n" +
            "           | 1        | 2           | 100    |];\n";
    private static Optional<RelationFunctionSymbol> tvf(SemanticModel m, String name) {
        return m.symbolTable().lookupFunction(name).stream()
                .filter(RelationFunctionSymbol.class::isInstance)
                .map(RelationFunctionSymbol.class::cast)
                .findFirst();
    }

    @Nested
    @DisplayName("Symbol collection & inference")
    class Collection {

        @Test
        @DisplayName("a relation-returning def registers a RelationFunctionSymbol")
        void registersSymbol() {
            SemanticModel m = model(
                    "def ordersFor(cid: NUMBER): RELATION := { σ customer_id = cid (Orders) };\n" + ORDERS
                    + "query { ordersFor(2) };");
            assertThat(tvf(m, "ordersFor")).isPresent();
            assertThat(tvf(m, "ordersFor").get().parameters()).hasSize(1);
        }

        @Test
        @DisplayName("the body's output schema is inferred as the function return schema")
        void infersReturnSchema() {
            SemanticModel m = model(
                    "def ordersFor(cid: NUMBER): RELATION := { σ customer_id = cid (Orders) };\n" + ORDERS
                    + "query { ordersFor(2) };");
            Optional<Schema> ret = tvf(m, "ordersFor").flatMap(RelationFunctionSymbol::returnSchema);
            assertThat(ret).isPresent();
            assertThat(ret.get()).hasColumnNames("order_id", "customer_id", "amount");
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("a valid parameterized definition produces no errors")
        void validDefinition() {
            SemanticResult r = analyze(
                    "def ordersFor(cid: NUMBER): RELATION := { σ customer_id = cid (Orders) };\n" + ORDERS
                    + "query { ordersFor(2) };");
            assertThat(r.errors()).isEmpty();
        }

        @Test
        @DisplayName("calling an undefined table-valued function is an error")
        void unknownFunction() {
            SemanticResult r = analyze(ORDERS + "query { mystery(2) };");
            assertThat(r).messages().anyMatch(e -> e.contains("Undefined table-valued function")
                    || e.contains("Unknown table-valued function"));
        }

        @Test
        @DisplayName("calling with the wrong number of arguments is an error")
        void arityMismatch() {
            SemanticResult r = analyze(
                    "def ordersFor(cid: NUMBER): RELATION := { σ customer_id = cid (Orders) };\n" + ORDERS
                    + "query { ordersFor(2, 3) };");
            assertThat(r).messages().anyMatch(e -> e.contains("expects 1"));
        }

        @Test
        @DisplayName("a column reference as an argument (correlated) is rejected")
        void columnArgumentRejected() {
            SemanticResult r = analyze(
                    "def ordersFor(cid: NUMBER): RELATION := { σ customer_id = cid (Orders) };\n" + ORDERS
                    + "Customers := [| customer_id | name |\n" +
                    "              | 2           | A    |];\n" +
                    "query { Customers ⋈ ordersFor(customer_id) };");
            assertThat(r).messages().anyMatch(e -> e.contains("must be a constant expression"));
        }

        @Test
        @DisplayName("a body referencing an unknown column (not a parameter) is reported")
        void bodyUnknownColumn() {
            SemanticResult r = analyze(
                    "def bad(cid: NUMBER): RELATION := { σ nope = cid (Orders) };\n" + ORDERS
                    + "query { bad(2) };");
            assertThat(r).messages().anyMatch(e -> e.contains("nope"));
        }
    }

    @Nested
    @DisplayName("IR rendering")
    class Ir {

        @Test
        @DisplayName("the symbol is listed with the TVF kind and a relation signature")
        void irShowsTvf() {
            String ir = IrReport.generate(model(
                    "def ordersFor(cid: NUMBER): RELATION := { σ customer_id = cid (Orders) };\n" + ORDERS
                    + "query { ordersFor(2) };"));
            assertThat(ir).contains("TVF");
            assertThat(ir).contains("(cid:N)→relation");
        }

        @Test
        @DisplayName("a call renders as name(args) in an expression tree")
        void irShowsCallLabel() {
            String ir = IrReport.generate(model(
                    "def ordersFor(cid: NUMBER): RELATION := { σ customer_id = cid (Orders) };\n" + ORDERS
                    + "query { ordersFor(2) };"));
            assertThat(ir).contains("ordersFor(2)");
        }
    }
}

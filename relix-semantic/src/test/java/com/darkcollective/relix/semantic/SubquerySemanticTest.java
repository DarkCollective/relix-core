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
import com.darkcollective.relix.semantic.internal.SemanticResult;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;
import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;

/** Subqueries as values, EXISTS, ANY/ALL and IN over a relation, and LATERAL over an expression (#261). */
@DisplayName("subqueries — semantic analysis")
final class SubquerySemanticTest {

    private static final String DATA = """
            Customers := [| customer_id | name  |
                          | 1           | Alice |];
            Orders := [| order_id | customer_id | amount |
                       | 10       | 1           | 100    |];
            """;

    private static List<ColumnDefinition> heading(String query) {
        SemanticModel m = model(DATA + "query { " + query + " };");
        ExpressionQueryTarget target = (ExpressionQueryTarget) m.rootQueries().getFirst().target();
        return m.nodeSchemas().get(target.expression()).orElseThrow().columns();
    }

    @Nested
    @DisplayName("valid forms")
    class Valid {

        @Test
        @DisplayName("a correlated count is a NUMBER column, read through a qualified outer name")
        void correlatedCount() {
            List<ColumnDefinition> columns = heading("π name, (γ COUNT(*) → n (σ customer_id = Customers.customer_id"
                    + " (Orders))) → order_count (Customers)");
            assertThat(columns.stream().map(ColumnDefinition::name).toList()).containsExactly("name", "order_count");
            assertThat(columns.get(1).type()).isEqualTo(ScalarType.NUMBER);
        }

        @Test
        @DisplayName("EXISTS, IN, ANY and ALL over a relation analyse clean")
        void predicates() {
            for (String query : List.of(
                    "σ EXISTS (σ customer_id = Customers.customer_id (Orders)) (Customers)",
                    "σ NOT EXISTS (σ customer_id = Customers.customer_id (Orders)) (Customers)",
                    "σ customer_id IN (π customer_id (Orders)) (Customers)",
                    "σ customer_id NOT IN (π customer_id (Orders)) (Customers)",
                    "σ amount > ALL (π amount (σ order_id ≠ Orders.order_id (ρ O (Orders)))) (Orders)",
                    "π customer_id, EXISTS (σ customer_id = Customers.customer_id (Orders)) → has (Customers)")) {
                assertThat(analyze(DATA + "query { " + query + " };")).as(query).messages().isEmpty();
            }
        }

        @Test
        @DisplayName("LATERAL over an expression, inner and outer, joins the right side's heading")
        void lateral() {
            assertThat(heading("Customers OUTER LATERAL (σ customer_id = Customers.customer_id (Orders))").stream()
                    .map(ColumnDefinition::name).toList())
                    .containsExactly("customer_id", "name", "order_id", "customer_id_r", "amount");
            assertThat(analyze(DATA + "query { Customers LATERAL (λ 1 (σ customer_id = Customers.customer_id"
                    + " (Orders))) };")).messages().isEmpty();
        }

        @Test
        @DisplayName("a table correlated with itself reads the outer copy through ρ")
        void selfCorrelation() {
            assertThat(analyze(DATA + "query { σ amount = (γ MAX(amount) → m (σ customer_id = Orders.customer_id"
                    + " (ρ O (Orders)))) (Orders) };")).messages().isEmpty();
        }

        @Test
        @DisplayName("an outer reference may be projected, or passed to a function call, inside the subquery")
        void outerReferenceInsideProjectionAndCall() {
            assertThat(analyze(DATA + """
                    def ordersFor(cid: NUMBER): RELATION := { σ customer_id = cid (Orders) };
                    query { π Customers.name, (λ 1 (π Customers.name (Orders))) → x (Customers) };
                    query { σ EXISTS (Orders LATERAL ordersFor(Customers.name)) (Customers) };
                    """)).messages().isEmpty();
        }

        @Test
        @DisplayName("a subquery inside a subquery reads the outermost row")
        void nested() {
            assertThat(analyze(DATA + "query { σ EXISTS (σ EXISTS (σ customer_id = Customers.customer_id (ρ O2"
                    + " (Orders))) (Orders)) (Customers) };")).messages().isEmpty();
        }
    }

    @Nested
    @DisplayName("errors")
    class Errors {

        @Test
        @DisplayName("an unqualified outer column is not read from outside, and the error says how to")
        void unqualifiedOuterColumn() {
            SemanticResult r = analyze(DATA + "query { σ EXISTS (σ order_id = name (Orders)) (Customers) };");
            assertThat(r).messages().anyMatch(m -> m.contains("attribute 'name' not found in input schema")
                    && m.contains("to read the row around the subquery write Customers.name"));
        }

        @Test
        @DisplayName("a qualified name that is neither inside nor outside is reported at the name")
        void unknownOuterName() {
            SemanticResult r = analyze(DATA + "query { σ EXISTS (σ customer_id = Nobody.customer_id (Orders))"
                    + " (Customers) };");
            assertThat(r).messages().anyMatch(m -> m.contains("'Nobody.customer_id' names no relation the"
                    + " subquery reads and no column of the row around it"));
        }

        @Test
        @DisplayName("a value and a quantifier take one column, EXISTS any")
        void width() {
            assertThat(analyze(DATA + "query { π name, (π order_id, amount (Orders)) → x (Customers) };"))
                    .messages().anyMatch(m -> m.contains("A subquery used as a value has one column; this one"
                            + " has 2: order_id, amount"));
            assertThat(analyze(DATA + "query { σ customer_id IN (π order_id, customer_id (Orders)) (Customers) };"))
                    .messages().anyMatch(m -> m.contains("ANY, ALL and IN compare with a relation of one column"));
            assertThat(analyze(DATA + "query { σ EXISTS (Orders ⋈ Customers) (Customers) };")).messages().isEmpty();
        }

        @Test
        @DisplayName("a subquery anywhere but a PROJECT or a SELECT is refused")
        void placement() {
            assertThat(analyze(DATA + "query { τ (γ COUNT(*) → n (Orders)) (Customers) };"))
                    .messages().anyMatch(m -> m.contains("A subquery is written in a PROJECT's columns or a"
                            + " SELECT's condition; for one in τ"));
            assertThat(analyze(DATA + "query { Customers ⨝ EXISTS (σ amount > 1 (Orders)) Orders };"))
                    .messages().anyMatch(m -> m.contains("A subquery is written in a PROJECT's columns"));
        }

        @Test
        @DisplayName("over a row whose columns know no relation, a qualified name is read by its column")
        void rowWithoutProvenance() {
            String outer = " (π customer_id + 0 → k (Customers)) };";
            assertThat(analyze(DATA + "query { σ EXISTS (σ customer_id = Customers.k (Orders))" + outer))
                    .messages().isEmpty();
            assertThat(analyze(DATA + "query { σ EXISTS (σ customer_id = Customers.zz (Orders))" + outer))
                    .messages().anyMatch(m -> m.contains("'Customers.zz' names no relation the subquery reads"));
        }

        @Test
        @DisplayName("a function's parameters stay bound beside a subquery's outer references")
        void parametersBesideOuterReferences() {
            assertThat(analyze(DATA + """
                    def ordersFor(cid: NUMBER): RELATION := { σ customer_id = cid (Orders) };
                    def viaLateral(cid: NUMBER): RELATION := { Customers LATERAL ordersFor(cid) };
                    def projected(cid: NUMBER): RELATION := { π cid + 1 → next (Orders) };
                    def counted(cid: NUMBER): RELATION := {
                        π (γ COUNT(*) → n (σ customer_id = cid ∧ order_id = Customers.customer_id (Orders))) → n (Customers)
                    };
                    query { viaLateral(1) };
                    """)).messages().isEmpty();
        }

        @Test
        @DisplayName("a lateral expression reads the left row qualified, as a subquery does")
        void lateralOuterName() {
            assertThat(analyze(DATA + "query { Customers LATERAL (σ customer_id = Nobody.customer_id (Orders)) };"))
                    .messages().anyMatch(m -> m.contains("LATERAL: 'Nobody.customer_id' names no relation"));
        }
    }
}

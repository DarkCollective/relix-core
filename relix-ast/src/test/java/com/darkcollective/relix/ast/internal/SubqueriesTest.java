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
package com.darkcollective.relix.ast.internal;

import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.Quantifier;
import com.darkcollective.relix.ast.RelNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.ast.ComparisonOperator.EQUAL;
import static org.assertj.core.api.Assertions.assertThat;

/** The outer references of a relation that reads the row around it, and binding values into them (#261). */
final class SubqueriesTest {

    private static final RelNode ORDERS_OF_CUSTOMER =
            select(cmp(attr("customer_id"), EQUAL, attr("Customers.customer_id")), rel("Orders"));

    private static List<String> outer(RelNode query) {
        return Subqueries.outerReferences(query).stream().map(AttributeOperand::name).toList();
    }

    @Test
    @DisplayName("a qualified name whose relation is not in scope is an outer reference; an unqualified one never is")
    void outerReferences() {
        assertThat(outer(ORDERS_OF_CUSTOMER)).containsExactly("Customers.customer_id");
        assertThat(outer(select(cmp(attr("Orders.customer_id"), EQUAL, attr("id")), rel("Orders")))).isEmpty();
        // Once each, case-insensitively, a call's arguments included.
        assertThat(outer(select(and(cmp(func("Abs", attr("customers.x")), EQUAL, attr("Customers.X")),
                cmp(attr("a"), EQUAL, num("1"))), rel("Orders")))).containsExactly("customers.x");
    }

    @Test
    @DisplayName("ρ hides the relation it renames, so a self-correlation reads the outer copy")
    void renameHides() {
        assertThat(outer(select(cmp(attr("grp"), EQUAL, attr("T.grp")), rename("U", List.of(), rel("T")))))
                .containsExactly("T.grp");
    }

    @Test
    @DisplayName("a nested subquery's reference is the enclosing relation's when it has the name in scope")
    void nested() {
        RelNode inner = select(exists(select(cmp(attr("k"), EQUAL, attr("Orders.k")), rel("Items"))), rel("Orders"));
        assertThat(outer(inner)).isEmpty();
        RelNode reachingOut = select(exists(ORDERS_OF_CUSTOMER), rel("Items"));
        assertThat(outer(reachingOut)).containsExactly("Customers.customer_id");
    }

    @Test
    @DisplayName("a lateral's right side reads its left; what neither has is read from outside")
    void lateralSides() {
        RelNode readsLeft = lateral(rel("Customers"), ORDERS_OF_CUSTOMER);
        assertThat(outer(readsLeft)).isEmpty();
        RelNode readsOutside = lateral(rel("Items"), ORDERS_OF_CUSTOMER);
        assertThat(outer(readsOutside)).containsExactly("Customers.customer_id");
    }

    @Test
    @DisplayName("the subqueries an operator carries, each with its kind")
    void subqueriesOf() {
        RelNode node = select(and(exists(rel("A")),
                quantified(subquery(rel("B")), ComparisonOperator.GREATER, Quantifier.ALL, rel("C"))), rel("R"));
        assertThat(Subqueries.subqueriesOf(node).stream().map(Subqueries.Subquery::kind).toList())
                .containsExactly(Subqueries.Kind.EXISTS, Subqueries.Kind.VALUE, Subqueries.Kind.QUANTIFIED);
        assertThat(Subqueries.carriesSubquery(node)).isTrue();
        assertThat(Subqueries.carriesSubquery(rel("R"))).isFalse();
    }

    @Test
    @DisplayName("binding replaces an outer reference everywhere it is written, nested subqueries included")
    void bind() {
        Operand one = num("1");
        RelNode query = project(List.of(projected(arith(attr("Customers.k"), com.darkcollective.relix.ast.ArithmeticOperator.PLUS,
                        func("Abs", attr("Customers.k"))), "a"),
                projected(structOf(field("f", attr("Customers.k"))), "s"),
                projected(arrayOf(attr("Customers.k")), "r"),
                projected(set(attr("Customers.k")), "t"),
                projected(unary(attr("Customers.k")), "u"),
                projected(collate(attr("Customers.k"), "NOCASE"), "v"),
                projected(condition(not(or(nullPred(attr("Customers.k"), true),
                        and(elementOf(attr("Customers.k"), set(num("2"))),
                                like(attr("Customers.k"), str("x")))))), "w"),
                projected(subquery(select(exists(select(cmp(attr("Customers.k"), EQUAL, attr("z")), rel("S"))),
                        rel("S"))), "x"),
                projected(condition(quantified(attr("Customers.k"), EQUAL, Quantifier.ANY, rel("S"))), "y"),
                projected(param("p"), "pp"), projected(str("s"), "ss"), projected(bool(true), "bb"),
                projected(nullValue(), "nn")),
                rel("Orders"));
        RelNode bound = Subqueries.bind(query, Map.of("customers.k", one));
        assertThat(outer(bound)).isEmpty();
        assertThat(bound.prettyPrint()).doesNotContain("Customers.k").contains("1 + Abs(1)");
        assertThat(Subqueries.bind(query, Map.of())).isSameAs(query);
    }

    @Test
    @DisplayName("binding leaves a name the relation has in scope, and a lateral's left row, alone")
    void bindRespectsScope() {
        RelNode own = select(cmp(attr("Customers.k"), EQUAL, num("2")), rel("Customers"));
        assertThat(Subqueries.bind(own, Map.of("customers.k", num("1"))).prettyPrint()).contains("Customers.k");
        RelNode lateral = lateral(rel("Customers"), ORDERS_OF_CUSTOMER);
        assertThat(Subqueries.bind(lateral, Map.of("customers.customer_id", num("1"))).prettyPrint())
                .contains("Customers.customer_id");
        RelNode outside = lateral(rel("Items"), ORDERS_OF_CUSTOMER);
        assertThat(Subqueries.bind(outside, Map.of("customers.customer_id", num("1"))).prettyPrint())
                .doesNotContain("Customers.customer_id");
        RelNode leftReadsOutside = lateral(select(cmp(attr("Customers.k"), EQUAL, num("2")), rel("Items")), rel("Z"));
        assertThat(Subqueries.bind(leftReadsOutside, Map.of("customers.k", num("1"))).prettyPrint())
                .doesNotContain("Customers.k");
        // A placeholder, with no dot, is replaced wherever it is written.
        assertThat(Subqueries.bind(select(cmp(attr("h"), EQUAL, num("2")), rel("Customers")), Map.of("h", num("1")))
                .prettyPrint()).contains("1 = 2");
    }
}

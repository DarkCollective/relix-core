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
package com.darkcollective.relix.optimizer.internal;

import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.Quantifier;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.optimizer.OptimizationCode;
import com.darkcollective.relix.optimizer.OptimizerFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.ast.ComparisonOperator.EQUAL;
import static com.darkcollective.relix.ast.ComparisonOperator.GREATER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SUBQ-001 to SUBQ-006 on trees built directly, including shapes analysis would refuse,
 * so that every guard is seen to decline (#261). What each rewrite returns is held to the
 * unrewritten query's rows by {@code SubqueryDecorrelationEquivalenceTest}.
 */
@DisplayName("Subquery decorrelation — SUBQ-001 to SUBQ-006")
final class SubqueryDecorrelationPassTest {

    private static final RelNode C = rel("Customers");
    private static final RelNode O = rel("Orders");
    private static final Predicate CORRELATED = cmp(attr("cid"), EQUAL, attr("Customers.id"));

    private OptimizationContext ctx;

    @BeforeEach
    void setUp() {
        ctx = OptimizerFixtures.context();
    }

    private RelNode apply(RelNode node) {
        return SubqueryDecorrelationPass.apply(node, ctx, "Q");
    }

    private void unchanged(RelNode node) {
        assertThat(apply(node)).as(node.prettyPrint()).isSameAs(node);
    }

    private static AggregateFunction count(Operand argument) {
        return AggregateFunction.aliased(AggregateOperator.COUNT, argument, "n");
    }

    private static RelNode value(RelNode query) {
        return project(List.of(projected(subquery(query), "v")), C);
    }

    @Test
    @DisplayName("names in a correlated condition are qualified through calls, sets and arithmetic")
    void qualifiesThroughOperands() {
        Predicate theta = and(cmp(func("Abs", attr("cid")), EQUAL, attr("Customers.id")),
                elementOf(attr("Customers.id"), set(attr("cid"), unary(attr("amount")))));
        RelNode rewritten = apply(select(exists(select(theta, O)), C));
        assertThat(rewritten.prettyPrint()).contains("Abs(orders.cid)", "{orders.cid, -orders.amount}");
        assertThat(ctx.recordsFor(OptimizationCode.SUBQ_001)).hasSize(1);
    }

    @Test
    @DisplayName("the compared value's names are the outer row's, through a call")
    void qualifiesTheComparedValue() {
        RelNode rewritten = apply(select(quantified(func("Abs", attr("id")), GREATER, Quantifier.ANY,
                project(List.of(projected(attr("cid"))), O)), C));
        assertThat(rewritten.prettyPrint()).contains("Abs(customers.id) > orders.cid");
    }

    @Test
    @DisplayName("ANY and ALL decline a relation that is not one column read by name")
    void quantifiedShapes() {
        unchanged(select(quantified(attr("id"), EQUAL, Quantifier.ANY, O), C));
        unchanged(select(quantified(attr("id"), EQUAL, Quantifier.ANY,
                project(List.of(projected(attr("cid")), projected(attr("oid"))), O)), C));
        unchanged(select(quantified(attr("id"), EQUAL, Quantifier.ALL,
                project(List.of(projected(num("1"), "one")), O)), C));
        // The compared value is unqualified and the outer row has two relation names.
        unchanged(select(quantified(attr("id"), EQUAL, Quantifier.ANY, project(List.of(projected(attr("cid"))), O)),
                product(C, rel("Items"))));
        // The relation answers to two names.
        unchanged(select(quantified(attr("Customers.id"), EQUAL, Quantifier.ANY,
                project(List.of(projected(attr("cid"))), product(O, rel("Items")))), C));
    }

    @Test
    @DisplayName("EXISTS declines a relation that reads the outer row outside a top selection, or a subquery in it")
    void existsShapes() {
        unchanged(select(exists(project(List.of(projected(attr("Customers.id"))), O)), C));
        unchanged(select(exists(select(CORRELATED, select(cmp(attr("oid"), EQUAL, attr("Customers.id")), O))), C));
        unchanged(select(exists(select(and(CORRELATED, exists(rel("Items"))), O)), C));
        unchanged(select(exists(select(cmp(attr("cid"), EQUAL, attr("Elsewhere.id")), O)), C));
        unchanged(select(exists(product(O, rel("Items"))), C));
    }

    @Test
    @DisplayName("a value declines every shape but an ungrouped COUNT, SUM, MIN, MAX or AVG over a correlated σ")
    void valueShapes() {
        unchanged(value(project(List.of(projected(attr("cid"))), O)));
        unchanged(value(groupByKeys(List.of(GroupingKey.column("cid")), List.of(count(num("1"))), select(CORRELATED, O))));
        unchanged(value(groupingSets(List.of(GroupingKey.column("oid")), List.of(List.of(0), List.of()),
                List.of(count(num("1"))), select(CORRELATED, O))));
        unchanged(value(groupByKeys(List.of(), List.of(count(num("1")),
                AggregateFunction.aliased(AggregateOperator.MAX, attr("amount"), "m")),
                select(CORRELATED, O))));
        unchanged(value(groupByKeys(List.of(), List.of(count(num("1"))), O)));
        unchanged(value(groupByKeys(List.of(), List.of(orderedAgg(AggregateOperator.COUNT, attr("amount"),
                attr("amount"), List.of(asc("oid")), "n")), select(CORRELATED, O))));
        // The relation answers to two names, reads the outer row below its σ, or counts it.
        unchanged(value(groupByKeys(List.of(), List.of(count(num("1"))), select(CORRELATED, product(O, rel("Items"))))));
        unchanged(value(groupByKeys(List.of(), List.of(count(num("1"))),
                select(CORRELATED, select(cmp(attr("oid"), EQUAL, attr("Customers.id")), O)))));
        unchanged(value(groupByKeys(List.of(), List.of(count(func("Abs", attr("Customers.id")))),
                select(CORRELATED, O))));
        // A correlation that is not an equality of an inner value with an outer column.
        unchanged(value(groupByKeys(List.of(), List.of(count(num("1"))),
                select(nullPred(attr("Customers.id"), true), O))));
        unchanged(value(groupByKeys(List.of(), List.of(count(num("1"))),
                select(cmp(arith(attr("cid"), com.darkcollective.relix.ast.ArithmeticOperator.PLUS, num("0")), EQUAL,
                        arith(attr("Customers.id"), com.darkcollective.relix.ast.ArithmeticOperator.PLUS, num("0"))), O))));
        unchanged(value(groupByKeys(List.of(), List.of(count(num("1"))),
                select(cmp(attr("cid"), EQUAL, attr("Elsewhere.id")), O))));
        unchanged(value(groupByKeys(List.of(), List.of(count(num("1"))),
                select(cmp(func("Abs", attr("Customers.name")), EQUAL, attr("Customers.id")), O))));
    }

    @Test
    @DisplayName("a value's inner key may be computed, through a call")
    void computedInnerKey() {
        RelNode rewritten = apply(value(groupByKeys(List.of(), List.of(count(num("1"))),
                select(cmp(func("Abs", attr("cid")), EQUAL, attr("Customers.id")), O))));
        assertThat(rewritten.prettyPrint()).contains("Coalesce(`$group1`.`$v`, 0)");
        assertThat(ctx.recordsFor(OptimizationCode.SUBQ_005)).hasSize(1);
    }

    @Test
    @DisplayName("a lateral declines a function call, an uncorrelated expression, and an ambiguous relation")
    void lateralShapes() {
        unchanged(lateral(C, "ordersFor", attr("id")));
        unchanged(lateral(C, select(cmp(attr("cid"), EQUAL, num("1")), O)));
        unchanged(lateral(C, select(CORRELATED, product(O, rel("Items")))));
    }
}

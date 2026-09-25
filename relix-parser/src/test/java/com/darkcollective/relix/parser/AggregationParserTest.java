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
package com.darkcollective.relix.parser;

import org.junit.jupiter.api.Test;
import com.darkcollective.relix.ast.*;
import com.darkcollective.relix.ast.internal.*;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;

/**
 * Comprehensive tests for Feature 3: Aggregation Operator (γ).
 * Tests parsing of aggregation operations with grouping and aggregate functions.
 */
final class AggregationParserTest extends ParserTestSupport {

    // ==================== Expression grouping keys (issue #375) ====================

    @Test
    public void parsesFunctionCallGroupingKeyWithAlias() {
        assertParsesTo("γ YEAR(order_date) → yr, SUM(amount) → revenue (Orders)",
                groupByKeys(
                        List.of(GroupingKey.aliased(func("YEAR", attr("order_date")), "yr")),
                        List.of(agg(AggregateOperator.SUM, "amount", "revenue")),
                        rel("Orders")));
    }

    @Test
    public void parsesArithmeticGroupingKeyWithAlias() {
        assertParsesTo("γ amt * 2 → dbl, COUNT(id) → n (Sales)",
                groupByKeys(
                        List.of(GroupingKey.aliased(
                                arith(attr("amt"), ArithmeticOperator.MULTIPLY, num("2")), "dbl")),
                        List.of(agg(AggregateOperator.COUNT, "id", "n")),
                        rel("Sales")));
    }

    @Test
    public void parsesNestedFunctionGroupingKey() {
        assertParsesTo("γ DATE_TRUNC('day', ts) → day, COUNT(id) → hits (Events)",
                groupByKeys(
                        List.of(GroupingKey.aliased(
                                func("DATE_TRUNC", str("day"), attr("ts")), "day")),
                        List.of(agg(AggregateOperator.COUNT, "id", "hits")),
                        rel("Events")));
    }

    @Test
    public void parsesMixedPlainAndExpressionGroupingKeys() {
        assertParsesTo("γ region, HOUR(occurred_at) → hr, COUNT(id) (Events)",
                groupByKeys(
                        List.of(GroupingKey.column("region"),
                                GroupingKey.aliased(func("HOUR", attr("occurred_at")), "hr")),
                        List.of(agg(AggregateOperator.COUNT, "id")),
                        rel("Events")));
    }

    // ==================== Category 1: Basic Grouping with Aggregation (5 tests) ====================

    @Test
    public void parsesSingleGroupingWithSingleAggregate() {
        assertParsesTo("γ dept, SUM(salary) (Employees)",
                groupBy(
                        List.of("dept"),
                        List.of(agg(AggregateOperator.SUM, "salary")),
                        rel("Employees")));
    }

    @Test
    public void parsesMultipleGroupingWithSingleAggregate() {
        assertParsesTo("γ dept, location, COUNT(id) (Employees)",
                groupBy(
                        List.of("dept", "location"),
                        List.of(agg(AggregateOperator.COUNT, "id")),
                        rel("Employees")));
    }

    @Test
    public void parsesSingleGroupingWithMultipleAggregates() {
        assertParsesTo("γ dept, SUM(salary), AVG(age) (Employees)",
                groupBy(
                        List.of("dept"),
                        List.of(
                                agg(AggregateOperator.SUM, "salary"),
                                agg(AggregateOperator.AVG, "age")
                        ),
                        rel("Employees")));
    }

    @Test
    public void parsesMultipleGroupingWithMultipleAggregates() {
        assertParsesTo("γ dept, category, SUM(salary) → total, COUNT(id) → cnt (Employees)",
                groupBy(
                        List.of("dept", "category"),
                        List.of(
                                agg(AggregateOperator.SUM, "salary", "total"),
                                agg(AggregateOperator.COUNT, "id", "cnt")
                        ),
                        rel("Employees")));
    }

    @Test
    public void parsesGroupingAttributeWithUnderscoresAndNumbers() {
        assertParsesTo("γ dept_id, SUM(salary_2024) (Employees)",
                groupBy(
                        List.of("dept_id"),
                        List.of(agg(AggregateOperator.SUM, "salary_2024")),
                        rel("Employees")));
    }

    // ==================== Category 2: Aggregation Without Grouping (4 tests) ====================

    @Test
    public void parsesSingleAggregateWithoutGrouping() {
        assertParsesTo("γ SUM(salary) (Employees)",
                groupBy(
                        List.of(),
                        List.of(agg(AggregateOperator.SUM, "salary")),
                        rel("Employees")));
    }

    @Test
    public void parsesMultipleAggregatesWithoutGrouping() {
        assertParsesTo("γ SUM(salary), AVG(age), COUNT(id) (Employees)",
                groupBy(
                        List.of(),
                        List.of(
                                agg(AggregateOperator.SUM, "salary"),
                                agg(AggregateOperator.AVG, "age"),
                                agg(AggregateOperator.COUNT, "id")
                        ),
                        rel("Employees")));
    }

    @Test
    public void parsesAggregateWithAliasNoGrouping() {
        assertParsesTo("γ AVG(price) → avg_price (Orders)",
                groupBy(
                        List.of(),
                        List.of(agg(AggregateOperator.AVG, "price", "avg_price")),
                        rel("Orders")));
    }

    @Test
    public void parsesMultipleAggregatesWithAliasesNoGrouping() {
        assertParsesTo("γ SUM(amount) → total, COUNT(id) → cnt (Transactions)",
                groupBy(
                        List.of(),
                        List.of(
                                agg(AggregateOperator.SUM, "amount", "total"),
                                agg(AggregateOperator.COUNT, "id", "cnt")
                        ),
                        rel("Transactions")));
    }

    // ==================== Category 3: Aggregate Functions with Aliases (5 tests) ====================

    @Test
    public void parsesSingleAggregateWithAlias() {
        assertParsesTo("γ SUM(salary) → total_salary (Employees)",
                groupBy(
                        List.of(),
                        List.of(agg(AggregateOperator.SUM, "salary", "total_salary")),
                        rel("Employees")));
    }

    @Test
    public void parsesMultipleAggregatesWithAliases() {
        assertParsesTo("γ dept, SUM(salary) → tsalary, AVG(age) → avg_age (Employees)",
                groupBy(
                        List.of("dept"),
                        List.of(
                                agg(AggregateOperator.SUM, "salary", "tsalary"),
                                agg(AggregateOperator.AVG, "age", "avg_age")
                        ),
                        rel("Employees")));
    }

    @Test
    public void parsesMixedAliasedAndNonAliasedAggregates() {
        assertParsesTo("γ dept, SUM(salary) → total, COUNT(id) (Employees)",
                groupBy(
                        List.of("dept"),
                        List.of(
                                agg(AggregateOperator.SUM, "salary", "total"),
                                agg(AggregateOperator.COUNT, "id")
                        ),
                        rel("Employees")));
    }

    @Test
    public void parsesAliasWithUnderscoresAndNumbers() {
        assertParsesTo("γ SUM(salary_2024) → total_2024_salary (Employees)",
                groupBy(
                        List.of(),
                        List.of(agg(AggregateOperator.SUM, "salary_2024", "total_2024_salary")),
                        rel("Employees")));
    }

    @Test
    public void parsesAliasThatLooksLikeKeyword() {
        assertParsesTo("γ SUM(value) → sum_total (Data)",
                groupBy(
                        List.of(),
                        List.of(agg(AggregateOperator.SUM, "value", "sum_total")),
                        rel("Data")));
    }

    // ==================== Category 4: All Aggregate Functions (5 tests) ====================

    @Test
    public void parsesSUMFunction() {
        assertParsesTo("γ SUM(amount) (Orders)",
                groupBy(
                        List.of(),
                        List.of(agg(AggregateOperator.SUM, "amount")),
                        rel("Orders")));
    }

    @Test
    public void parsesAVGFunction() {
        assertParsesTo("γ AVG(price) (Products)",
                groupBy(
                        List.of(),
                        List.of(agg(AggregateOperator.AVG, "price")),
                        rel("Products")));
    }

    @Test
    public void parsesCOUNTFunction() {
        assertParsesTo("γ COUNT(id) (Orders)",
                groupBy(
                        List.of(),
                        List.of(agg(AggregateOperator.COUNT, "id")),
                        rel("Orders")));
    }

    @Test
    public void parsesMINFunction() {
        assertParsesTo("γ dept, MIN(salary) (Employees)",
                groupBy(
                        List.of("dept"),
                        List.of(agg(AggregateOperator.MIN, "salary")),
                        rel("Employees")));
    }

    @Test
    public void parsesMAXFunction() {
        assertParsesTo("γ dept, MAX(salary) (Employees)",
                groupBy(
                        List.of("dept"),
                        List.of(agg(AggregateOperator.MAX, "salary")),
                        rel("Employees")));
    }

    // ==================== Category 5: Complex Input Expressions (4 tests) ====================

    @Test
    public void parsesAggregationWithSelectionInput() {
        assertParsesTo("γ dept, SUM(salary) (σ active = true (Employees))",
                groupBy(
                        List.of("dept"),
                        List.of(agg(AggregateOperator.SUM, "salary")),
                        select(
                                cmp(attr("active"), ComparisonOperator.EQUAL, bool(true)),
                                rel("Employees"))));
    }

    @Test
    public void parsesAggregationWithProjectionInput() {
        assertParsesTo("γ SUM(value) (π id, value (Orders))",
                groupBy(
                        List.of(),
                        List.of(agg(AggregateOperator.SUM, "value")),
                        project(
                                List.of(projected(attr("id")), projected(attr("value"))),
                                rel("Orders"))));
    }

    @Test
    public void parsesAggregationWithJoinInput() {
        assertParsesTo("γ dept, COUNT(id) ((Employees) ⋈ (Departments))",
                groupBy(
                        List.of("dept"),
                        List.of(agg(AggregateOperator.COUNT, "id")),
                        naturalJoin(
                                rel("Employees"),
                                rel("Departments"))));
    }

    @Test
    public void parsesNestedAggregationsInProjection() {
        assertParsesTo("π name (γ SUM(salary) → total (Employees))",
                project(
                        List.of(projected(attr("name"))),
                        groupBy(
                                List.of(),
                                List.of(agg(AggregateOperator.SUM, "salary", "total")),
                                rel("Employees"))));
    }

    // ==================== Category 6: Edge Cases (4 tests) ====================

    @Test
    public void parsesSingleLetterAttributeAndGroupNames() {
        assertParsesTo("γ d, SUM(s) → t (E)",
                groupBy(
                        List.of("d"),
                        List.of(agg(AggregateOperator.SUM, "s", "t")),
                        rel("E")));
    }

    @Test
    public void parsesAttributeNamesWithNumbers() {
        assertParsesTo("γ dept1, SUM(sal2) → total3 (EMP4)",
                groupBy(
                        List.of("dept1"),
                        List.of(agg(AggregateOperator.SUM, "sal2", "total3")),
                        rel("EMP4")));
    }

    @Test
    public void parsesLongIdentifierNames() {
        assertParsesTo("γ department_code, SUM(annual_salary_amount) → total_salary (Employees)",
                groupBy(
                        List.of("department_code"),
                        List.of(agg(AggregateOperator.SUM, "annual_salary_amount", "total_salary")),
                        rel("Employees")));
    }

    @Test
    public void parsesMultipleConsecutiveAggregations() {
        assertParsesTo("γ COUNT(id) (γ dept, SUM(salary) (Employees))",
                groupBy(
                        List.of(),
                        List.of(agg(AggregateOperator.COUNT, "id")),
                        groupBy(
                                List.of("dept"),
                                List.of(agg(AggregateOperator.SUM, "salary")),
                                rel("Employees"))));
    }

    // ==================== Category 7: Pretty Printing (3 tests) ====================

    @Test
    public void prettyPrintsAggregationWithGroupingAndAliases() {
        RelNode node = groupBy(
                List.of("dept"),
                List.of(agg(AggregateOperator.SUM, "salary", "tsalary")),
                rel("Employees"));

        assertPrettyPrints(node, "γ dept, SUM(salary) → tsalary (Employees)");
    }

    @Test
    public void prettyPrintsMultipleAggregates() {
        RelNode node = groupBy(
                List.of("dept"),
                List.of(
                        agg(AggregateOperator.SUM, "salary"),
                        agg(AggregateOperator.COUNT, "id", "cnt")
                ),
                rel("Employees"));

        assertPrettyPrints(node, "γ dept, SUM(salary), COUNT(id) → cnt (Employees)");
    }

    @Test
    public void prettyPrintsAggregationWithComplexInput() {
        RelNode node = groupBy(
                List.of(),
                List.of(agg(AggregateOperator.SUM, "amount")),
                select(
                        cmp(attr("status"), ComparisonOperator.EQUAL, str("active")),
                        rel("Orders")));

        assertPrettyPrints(node, "γ SUM(amount) (σ status = \"active\" (Orders))");
    }

    // ==================== Category 8: Error Cases (5 tests) ====================

    @Test
    public void failsOnMissingAggregateFunction() {
        assertParseError("γ dept, (Employees)")
                .hasMessageContaining("Expected aggregate function");
    }

    @Test
    public void failsOnMissingParenthesesAroundAttribute() {
        assertParseError("γ SUM salary (Employees)")
                .hasMessageContaining("Expected '(' after aggregate function name");
    }

    @Test
    public void failsOnMissingAttributeNameInAggregate() {
        // An aggregate now takes an operand expression, so an empty SUM() reports
        // the operand-expectation error.
        assertParseError("γ SUM() (Employees)")
                .hasMessageContaining("Expected attribute, string, number");
    }

    @Test
    public void failsOnNoAggregateFunctions() {
        assertParseError("γ dept, (Employees)")
                .hasMessageContaining("Expected aggregate function");
    }

    @Test
    public void failsOnMultipleAttributesInFunction() {
        assertParseError("γ SUM(salary, bonus) (Employees)")
                .hasMessageContaining("Expected ')'");
    }

    // ==================== COLLECT (NEST) aggregate ====================

    @Test
    public void parsesCollectAggregate() {
        assertParsesTo("γ customer_id, COLLECT(order_id) (Orders)",
                groupBy(
                        List.of("customer_id"),
                        List.of(agg(AggregateOperator.COLLECT, "order_id")),
                        rel("Orders")));
    }

    @Test
    public void parsesAliasedCollectAggregate() {
        assertParsesTo("γ customer_id, COLLECT(order_id) → order_ids (Orders)",
                groupBy(
                        List.of("customer_id"),
                        List.of(agg(AggregateOperator.COLLECT, "order_id", "order_ids")),
                        rel("Orders")));
    }

    @Test
    public void parsesCollectAlongsideScalarAggregate() {
        assertParsesTo("γ customer_id, COUNT(order_id) → n, COLLECT(amount) → amounts (Orders)",
                groupBy(
                        List.of("customer_id"),
                        List.of(
                                agg(AggregateOperator.COUNT, "order_id", "n"),
                                agg(AggregateOperator.COLLECT, "amount", "amounts")
                        ),
                        rel("Orders")));
    }

    @Test
    public void prettyPrintsCollectAggregate() {
        RelNode node = groupBy(
                List.of("customer_id"),
                List.of(agg(AggregateOperator.COLLECT, "order_id", "order_ids")),
                rel("Orders"));

        assertPrettyPrints(node, "γ customer_id, COLLECT(order_id) → order_ids (Orders)");
    }

    // ==================== Category: ARGMAX / ARGMIN (two-column aggregates) ====================

    @Test
    public void parsesArgmaxWithTwoColumns() {
        assertParsesTo("γ customer_id, ARGMAX(amount, order_id) (Orders)",
                groupBy(
                        List.of("customer_id"),
                        List.of(AggregateFunction.arg(
                                AggregateOperator.ARGMAX, "amount", "order_id")),
                        rel("Orders")));
    }

    @Test
    public void parsesArgmaxWithAlias() {
        assertParsesTo("γ customer_id, ARGMAX(amount, order_id) → biggest (Orders)",
                groupBy(
                        List.of("customer_id"),
                        List.of(AggregateFunction.argAliased(
                                AggregateOperator.ARGMAX, "amount", "order_id", "biggest")),
                        rel("Orders")));
    }

    @Test
    public void parsesArgmin() {
        assertParsesTo("γ customer_id, ARGMIN(amount, order_id) (Orders)",
                groupBy(
                        List.of("customer_id"),
                        List.of(AggregateFunction.arg(
                                AggregateOperator.ARGMIN, "amount", "order_id")),
                        rel("Orders")));
    }

    @Test
    public void prettyPrintsArgmaxAggregate() {
        RelNode node = groupBy(
                List.of("customer_id"),
                List.of(AggregateFunction.argAliased(
                        AggregateOperator.ARGMAX, "amount", "order_id", "biggest")),
                rel("Orders"));

        assertPrettyPrints(node, "γ customer_id, ARGMAX(amount, order_id) → biggest (Orders)");
    }

    @Test
    public void rejectsArgmaxWithSingleColumn() {
        // ARGMAX requires two columns; a single-arg form is a parse error.
        assertParseError("γ customer_id, ARGMAX(amount) (Orders)")
                .hasMessageContaining("ARGMAX");
    }

    // ─── Operand-valued aggregate arguments (E8) ──────────────────────────────

    @Test
    public void parsesAggregateOverArithmeticExpression() {
        assertParsesTo("γ SUM(price * qty) → revenue (Sales)",
                groupBy(
                        List.of(),
                        List.of(AggregateFunction.aliased(AggregateOperator.SUM,
                                arith(attr("price"), ArithmeticOperator.MULTIPLY, attr("qty")),
                                "revenue")),
                        rel("Sales")));
    }

    @Test
    public void parsesAggregateOverFunctionCall() {
        assertParsesTo("γ MIN(Abs(delta)) (R)",
                groupBy(
                        List.of(),
                        List.of(AggregateFunction.of(AggregateOperator.MIN, func("Abs", attr("delta")))),
                        rel("R")));
    }

    @Test
    public void parsesArgmaxOverExpressions() {
        assertParsesTo("γ ARGMAX(score * 2, name) → top (Players)",
                groupBy(
                        List.of(),
                        List.of(argAgg(AggregateOperator.ARGMAX,
                                arith(attr("score"), ArithmeticOperator.MULTIPLY, num("2")),attr("name"),"top")),
                        rel("Players")));
    }

    @Test
    public void prettyPrintsAggregateOverArithmetic() {
        RelNode node = groupBy(
                List.of(),
                List.of(AggregateFunction.aliased(AggregateOperator.SUM,
                        arith(attr("price"), ArithmeticOperator.MULTIPLY, attr("qty")), "revenue")),
                rel("Sales"));
        assertPrettyPrints(node, "γ SUM(price * qty) → revenue (Sales)");
    }

    // ==================== COUNT(*) — SQL row-count synonym ====================

    /**
     * {@code COUNT(*)} is a pure synonym for {@code COUNT(1)}: a literal argument
     * is never NULL, so it counts every row. Like every other alternative spelling
     * in the language it parses to the <em>identical</em> AST, which is why no
     * downstream phase needs a {@code COUNT(*)} case.
     */
    @Test
    public void countStarParsesToTheSameAstAsCountOne() {
        assertParsesTo("γ dept, COUNT(*) → n (Employees)",
                parse("γ dept, COUNT(1) → n (Employees)"));
    }

    @Test
    public void parsesCountStarAsACountOverTheConstantOne() {
        assertParsesTo("γ dept, COUNT(*) → n (Employees)",
                groupBy(
                        List.of("dept"),
                        List.of(AggregateFunction.aliased(
                                AggregateOperator.COUNT, num("1"), "n")),
                        rel("Employees")));
    }

    @Test
    public void parsesScalarCountStarWithNoGroupingKey() {
        assertParsesTo("γ COUNT(*) → total (Users)",
                groupBy(
                        List.of(),
                        List.of(AggregateFunction.aliased(
                                AggregateOperator.COUNT, num("1"), "total")),
                        rel("Users")));
    }

    /** `*` keeps its ordinary multiplication reading inside COUNT. */
    @Test
    public void countOverAProductIsStillMultiplication() {
        assertParsesTo("γ COUNT(price * qty) → n (Sales)",
                groupBy(
                        List.of(),
                        List.of(AggregateFunction.aliased(AggregateOperator.COUNT,
                                arith(attr("price"), ArithmeticOperator.MULTIPLY, attr("qty")), "n")),
                        rel("Sales")));
    }

    /** The star form is COUNT-only — SUM(*)/MIN(*) have no meaning. */
    @Test
    public void starIsRejectedForOtherAggregates() {
        assertParseError("γ dept, SUM(*) → x (Employees)");
        assertParseError("γ dept, MIN(*) → x (Employees)");
        assertParseError("γ dept, COLLECT(*) → x (Employees)");
    }

    /** A lone `*` is only special immediately before the closing paren. */
    @Test
    public void starFollowedByAnythingElseIsStillRejected() {
        assertParseError("γ dept, COUNT(* + 1) → x (Employees)");
    }
}



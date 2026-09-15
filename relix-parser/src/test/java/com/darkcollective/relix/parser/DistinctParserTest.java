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

import java.util.List;
import java.util.Optional;

/**
 * Comprehensive tests for Distinct Operator (δ).
 * Tests parsing of duplicate-elimination across composition,
 * precedence, complex inputs, and error cases.
 */
final class DistinctParserTest extends ParserTestSupport {

    // ==================== Category 1: Basic Distinct (5 tests) ====================

    @Test
    public void parsesBasicDistinct() {
        assertParsesTo("δ (Users)",
                distinct(rel("Users")));
    }

    @Test
    public void parsesDistinctOnQualifiedRelation() {
        assertParsesTo("δ (schema.Users)",
                distinct(rel("schema.Users")));
    }

    @Test
    public void parsesDistinctOnUnderscoreRelation() {
        assertParsesTo("δ (user_data)",
                distinct(rel("user_data")));
    }

    @Test
    public void parsesDistinctOnSingleLetterRelation() {
        assertParsesTo("δ (A)",
                distinct(rel("A")));
    }

    @Test
    public void parsesDistinctWithExtraWhitespace() {
        assertParsesTo("  δ   (  Users  )  ",
                distinct(rel("Users")));
    }

    // ==================== Category 2: Distinct on Unary Inputs (6 tests) ====================

    @Test
    public void distinctOnProjectionInput() {
        assertParsesTo("δ (π city (Users))",
                distinct(
                        project(
                                List.of(projected(attr("city"))),
                                rel("Users"))));
    }

    @Test
    public void distinctOnSelectionInput() {
        assertParsesTo("δ (σ active = true (Users))",
                distinct(
                        select(
                                cmp(attr("active"), ComparisonOperator.EQUAL, bool(true)),
                                rel("Users"))));
    }

    @Test
    public void distinctOnRenameInput() {
        assertParsesTo("δ (ρ U(id, name) (Users))",
                distinct(
                        rename("U", List.of("id", "name"), rel("Users"))));
    }

    @Test
    public void distinctOnAggregationInput() {
        assertParsesTo("δ (γ dept, SUM(salary) (Employees))",
                distinct(
                        groupBy(
                                List.of("dept"),
                                List.of(agg(AggregateOperator.SUM, "salary")),
                                rel("Employees"))));
    }

    @Test
    public void distinctOnSortInput() {
        assertParsesTo("δ (τ name DESC (Users))",
                distinct(
                        sort(
                                List.of(desc("name")),
                                rel("Users"))));
    }

    @Test
    public void distinctOnLimitInput() {
        assertParsesTo("δ (λ 100 (Orders))",
                distinct(
                        limit(
                                Optional.empty(),
                                100L,
                                rel("Orders"))));
    }

    // ==================== Category 3: Distinct on Binary Inputs (6 tests) ====================

    @Test
    public void distinctOnNaturalJoinInput() {
        assertParsesTo("δ (Users ⋈ Orders)",
                distinct(
                        naturalJoin(rel("Users"), rel("Orders"))));
    }

    @Test
    public void distinctOnThetaJoinInput() {
        assertParsesTo("δ (Users ⨝ Users.id = Orders.user_id Orders)",
                distinct(
                        join(
                                rel("Users"),
                                rel("Orders"),
                                cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id")))));
    }

    @Test
    public void distinctOnUnionInput() {
        assertParsesTo("δ (Users ∪ Admins)",
                distinct(
                        union(rel("Users"), rel("Admins"))));
    }

    @Test
    public void distinctOnUnionAllInput() {
        assertParsesTo("δ (Users ⊎ Admins)",
                distinct(
                        unionAll(rel("Users"), rel("Admins"))));
    }

    @Test
    public void distinctOnProductInput() {
        assertParsesTo("δ (A × B)",
                distinct(
                        product(rel("A"), rel("B"))));
    }

    @Test
    public void distinctOnDifferenceInput() {
        assertParsesTo("δ (A − B)",
                distinct(
                        difference(rel("A"), rel("B"))));
    }

    // ==================== Category 4: Composition with Other Operators (8 tests) ====================

    @Test
    public void distinctNestedInsideProjection() {
        assertParsesTo("π id (δ (Users))",
                project(
                        List.of(projected(attr("id"))),
                        distinct(rel("Users"))));
    }

    @Test
    public void distinctNestedInsideSelection() {
        assertParsesTo("σ active = true (δ (Users))",
                select(
                        cmp(attr("active"), ComparisonOperator.EQUAL, bool(true)),
                        distinct(rel("Users"))));
    }

    @Test
    public void distinctNestedInsideRename() {
        assertParsesTo("ρ U (δ (Users))",
                rename(
                        "U",
                        List.of(),
                        distinct(rel("Users"))));
    }

    @Test
    public void distinctNestedInsideSort() {
        assertParsesTo("τ name (δ (Users))",
                sort(
                        List.of(asc("name")),
                        distinct(rel("Users"))));
    }

    @Test
    public void distinctNestedInsideLimit() {
        assertParsesTo("λ 10 (δ (Orders))",
                limit(
                        Optional.empty(),
                        10L,
                        distinct(rel("Orders"))));
    }

    @Test
    public void distinctAsLeftOperandOfBinaryOperator() {
        assertParsesTo("δ (Users) ∪ Admins",
                union(
                        distinct(rel("Users")),
                        rel("Admins")));
    }

    @Test
    public void distinctAsRightOperandOfBinaryOperator() {
        assertParsesTo("Users ∪ δ (Admins)",
                union(
                        rel("Users"),
                        distinct(rel("Admins"))));
    }

    @Test
    public void distinctOnBothSidesOfBinaryOperator() {
        assertParsesTo("δ (Users) ⋈ δ (Orders)",
                naturalJoin(
                        distinct(rel("Users")),
                        distinct(rel("Orders"))));
    }

    // ==================== Category 5: Nested and Stacked Distinct (4 tests) ====================

    @Test
    public void nestedDistinct() {
        assertParsesTo("δ (δ (Users))",
                distinct(distinct(rel("Users"))));
    }

    @Test
    public void deeplyNestedDistinct() {
        assertParsesTo("δ (δ (δ (Users)))",
                distinct(
                        distinct(
                                distinct(rel("Users")))));
    }

    @Test
    public void distinctInsideComplexPipeline() {
        assertParsesTo("π name (σ active = true (δ (Users ∪ Admins)))",
                project(
                        List.of(projected(attr("name"))),
                        select(
                                cmp(attr("active"), ComparisonOperator.EQUAL, bool(true)),
                                distinct(
                                        union(rel("Users"), rel("Admins"))))));
    }

    @Test
    public void distinctWrappingComplexPipeline() {
        assertParsesTo("δ (τ total DESC (γ category, SUM(price) → total (σ available = true (Products))))",
                distinct(
                        sort(
                                List.of(desc("total")),
                                groupBy(
                                        List.of("category"),
                                        List.of(agg(AggregateOperator.SUM, "price", "total")),
                                        select(
                                                cmp(attr("available"), ComparisonOperator.EQUAL, bool(true)),
                                                rel("Products"))))));
    }

    // ==================== Category 6: Pretty Printing (5 tests) ====================

    @Test
    public void prettyPrintsBasicDistinct() {
        RelNode node = distinct(rel("Users"));
        assertPrettyPrints(node, "δ (Users)");
    }

    @Test
    public void prettyPrintsDistinctOnProjection() {
        RelNode node = distinct(
                project(List.of(projected(attr("city"))), rel("Users")));
        assertPrettyPrints(node, "δ (π city (Users))");
    }

    @Test
    public void prettyPrintsDistinctOnBinaryOperation() {
        RelNode node = distinct(
                unionAll(rel("Users"), rel("Admins")));
        assertPrettyPrints(node, "δ ((Users) ⊎ (Admins))");
    }

    @Test
    public void prettyPrintsNestedDistinct() {
        RelNode node = distinct(distinct(rel("Users")));
        assertPrettyPrints(node, "δ (δ (Users))");
    }

    @Test
    public void prettyPrintsDistinctInsidePipeline() {
        RelNode node = project(
                List.of(projected(attr("name"))),
                distinct(
                        select(
                                cmp(attr("active"), ComparisonOperator.EQUAL, bool(true)),
                                rel("Users"))));
        assertPrettyPrints(node, "π name (δ (σ active = true (Users)))");
    }

    // ==================== Category 7: Error Cases (6 tests) ====================

    @Test
    public void failsOnMissingOpeningParenthesis() {
        assertParseError("δ Users")
                .hasMessageContaining("Expected '(' after 'δ'");
    }

    @Test
    public void failsOnMissingClosingParenthesis() {
        assertParseError("δ (Users")
                .hasMessageContaining("Expected ')' after relational expression");
    }

    @Test
    public void failsOnMissingInputRelation() {
        assertParseError("δ ()")
                .hasMessageContaining("Expected relation name, unary operator, or '('");
    }

    @Test
    public void failsOnStandaloneDistinct() {
        assertParseError("δ")
                .hasMessageContaining("Expected '(' after 'δ'");
    }

    @Test
    public void failsOnDistinctWithoutInput() {
        assertParseError("δ ⊎ Users")
                .hasMessageContaining("Expected '(' after 'δ'");
    }

    @Test
    public void failsWhenInputIsTrailingComma() {
        assertParseError("δ (Users,)")
                .hasMessageContaining("Expected ')' after relational expression");
    }
}

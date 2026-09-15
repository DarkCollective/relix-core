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
 * Comprehensive tests for Union All Operator (⊎).
 * Tests parsing of multiset union operations across precedence,
 * associativity, complex inputs, and error cases.
 */
final class UnionAllParserTest extends ParserTestSupport {

    // ==================== Category 1: Basic Union All (5 tests) ====================

    @Test
    public void parsesBasicUnionAll() {
        assertParsesTo("A ⊎ B",
                unionAll(rel("A"), rel("B")));
    }

    @Test
    public void parsesUnionAllWithQualifiedNames() {
        assertParsesTo("schema.A ⊎ schema.B",
                unionAll(rel("schema.A"), rel("schema.B")));
    }

    @Test
    public void parsesUnionAllWithUnderscoreNames() {
        assertParsesTo("user_data ⊎ admin_data",
                unionAll(rel("user_data"), rel("admin_data")));
    }

    @Test
    public void parsesUnionAllWithNumericSuffixedNames() {
        assertParsesTo("Orders1 ⊎ Orders2",
                unionAll(rel("Orders1"), rel("Orders2")));
    }

    @Test
    public void parsesUnionAllSurroundedByWhitespace() {
        assertParsesTo("  Users   ⊎   Admins  ",
                unionAll(rel("Users"), rel("Admins")));
    }

    // ==================== Category 2: Associativity (4 tests) ====================

    @Test
    public void unionAllIsLeftAssociative() {
        assertParsesTo("A ⊎ B ⊎ C",
                unionAll(
                        unionAll(rel("A"), rel("B")),
                        rel("C")));
    }

    @Test
    public void unionAllChainsLeftAssociativelyAcrossManyOperands() {
        assertParsesTo("A ⊎ B ⊎ C ⊎ D",
                unionAll(
                        unionAll(
                                unionAll(rel("A"), rel("B")),
                                rel("C")),
                        rel("D")));
    }

    @Test
    public void parenthesesOverrideUnionAllAssociativity() {
        assertParsesTo("A ⊎ (B ⊎ C)",
                unionAll(
                        rel("A"),
                        unionAll(rel("B"), rel("C"))));
    }

    @Test
    public void unionAllMixesLeftAssociativelyWithUnionAndDifference() {
        // Same precedence tier as ∪ and −, all left-associative.
        assertParsesTo("A ∪ B ⊎ C − D",
                difference(
                        unionAll(
                                union(rel("A"), rel("B")),
                                rel("C")),
                        rel("D")));
    }

    // ==================== Category 3: Precedence Interactions (8 tests) ====================

    @Test
    public void joinBindsTighterThanUnionAll() {
        assertParsesTo("A ⊎ B ⋈ C",
                unionAll(
                        rel("A"),
                        naturalJoin(rel("B"), rel("C"))));
    }

    @Test
    public void productBindsTighterThanUnionAll() {
        assertParsesTo("A ⊎ B × C",
                unionAll(
                        rel("A"),
                        product(rel("B"), rel("C"))));
    }

    @Test
    public void divisionBindsTighterThanUnionAll() {
        assertParsesTo("A ⊎ B ÷ C",
                unionAll(
                        rel("A"),
                        division(rel("B"), rel("C"))));
    }

    @Test
    public void intersectionBindsTighterThanUnionAll() {
        assertParsesTo("A ⊎ B ∩ C",
                unionAll(
                        rel("A"),
                        intersection(rel("B"), rel("C"))));
    }

    @Test
    public void thetaJoinBindsTighterThanUnionAll() {
        assertParsesTo("A ⊎ B ⨝ B.id = C.id C",
                unionAll(
                        rel("A"),
                        join(
                                rel("B"),
                                rel("C"),
                                cmp(attr("B.id"), ComparisonOperator.EQUAL, attr("C.id")))));
    }

    @Test
    public void outerJoinBindsTighterThanUnionAll() {
        assertParsesTo("A ⊎ B ⟕ B.id = C.id C",
                unionAll(
                        rel("A"),
                        leftJoin(
                                rel("B"),
                                rel("C"),
                                cmp(attr("B.id"), ComparisonOperator.EQUAL, attr("C.id")))));
    }

    @Test
    public void unaryOperationBindsTighterThanUnionAll() {
        assertParsesTo("π id (Users) ⊎ Admins",
                unionAll(
                        project(List.of(projected(attr("id"))), rel("Users")),
                        rel("Admins")));
    }

    @Test
    public void parenthesesOverridePrecedenceWithUnionAll() {
        assertParsesTo("(A ⊎ B) ⋈ C",
                naturalJoin(
                        unionAll(rel("A"), rel("B")),
                        rel("C")));
    }

    // ==================== Category 4: Complex Inputs (8 tests) ====================

    @Test
    public void unionAllOnProjectionInputs() {
        assertParsesTo("π id (Users) ⊎ π id (Admins)",
                unionAll(
                        project(List.of(projected(attr("id"))), rel("Users")),
                        project(List.of(projected(attr("id"))), rel("Admins"))));
    }

    @Test
    public void unionAllOnSelectionInputs() {
        assertParsesTo("σ active = true (Users) ⊎ σ active = true (Admins)",
                unionAll(
                        select(
                                cmp(attr("active"), ComparisonOperator.EQUAL, bool(true)),
                                rel("Users")),
                        select(
                                cmp(attr("active"), ComparisonOperator.EQUAL, bool(true)),
                                rel("Admins"))));
    }

    @Test
    public void unionAllOnRenameInputs() {
        assertParsesTo("ρ U(id, name) (Users) ⊎ ρ A(id, name) (Admins)",
                unionAll(
                        rename("U", List.of("id", "name"), rel("Users")),
                        rename("A", List.of("id", "name"), rel("Admins"))));
    }

    @Test
    public void unionAllOnAggregationInputs() {
        assertParsesTo("γ dept, SUM(salary) (Employees) ⊎ γ dept, SUM(salary) (Contractors)",
                unionAll(
                        groupBy(
                                List.of("dept"),
                                List.of(agg(AggregateOperator.SUM, "salary")),
                                rel("Employees")),
                        groupBy(
                                List.of("dept"),
                                List.of(agg(AggregateOperator.SUM, "salary")),
                                rel("Contractors"))));
    }

    @Test
    public void unionAllOnSortAndLimitInputs() {
        assertParsesTo("τ name (Users) ⊎ λ 100 (Admins)",
                unionAll(
                        sort(
                                List.of(asc("name")),
                                rel("Users")),
                        limit(
                                Optional.empty(),
                                100L,
                                rel("Admins"))));
    }

    @Test
    public void unionAllOnJoinInputs() {
        assertParsesTo("(Users ⋈ Orders) ⊎ (Customers ⋈ Receipts)",
                unionAll(
                        naturalJoin(rel("Users"), rel("Orders")),
                        naturalJoin(rel("Customers"), rel("Receipts"))));
    }

    @Test
    public void deeplyNestedUnionAllPipeline() {
        assertParsesTo("π id (σ active = true (Users)) ⊎ π id (σ active = true (Admins))",
                unionAll(
                        project(
                                List.of(projected(attr("id"))),
                                select(
                                        cmp(attr("active"), ComparisonOperator.EQUAL, bool(true)),
                                        rel("Users"))),
                        project(
                                List.of(projected(attr("id"))),
                                select(
                                        cmp(attr("active"), ComparisonOperator.EQUAL, bool(true)),
                                        rel("Admins")))));
    }

    @Test
    public void unionAllInsideUnaryOperation() {
        assertParsesTo("π id (Users ⊎ Admins)",
                project(
                        List.of(projected(attr("id"))),
                        unionAll(rel("Users"), rel("Admins"))));
    }

    // ==================== Category 5: Mixed with ∪ and − (4 tests) ====================

    @Test
    public void unionAllAndUnionAreLeftAssociativeTogether() {
        assertParsesTo("A ⊎ B ∪ C",
                union(
                        unionAll(rel("A"), rel("B")),
                        rel("C")));
    }

    @Test
    public void unionAndUnionAllAreLeftAssociativeTogether() {
        assertParsesTo("A ∪ B ⊎ C",
                unionAll(
                        union(rel("A"), rel("B")),
                        rel("C")));
    }

    @Test
    public void unionAllAndDifferenceAreLeftAssociativeTogether() {
        assertParsesTo("A ⊎ B − C",
                difference(
                        unionAll(rel("A"), rel("B")),
                        rel("C")));
    }

    @Test
    public void differenceAndUnionAllAreLeftAssociativeTogether() {
        assertParsesTo("A − B ⊎ C",
                unionAll(
                        difference(rel("A"), rel("B")),
                        rel("C")));
    }

    // ==================== Category 6: Pretty Printing (5 tests) ====================

    @Test
    public void prettyPrintsBasicUnionAll() {
        RelNode node = unionAll(rel("Users"), rel("Admins"));
        assertPrettyPrints(node, "(Users) ⊎ (Admins)");
    }

    @Test
    public void prettyPrintsUnionAllWithComplexInputs() {
        RelNode node = unionAll(
                project(List.of(projected(attr("id"))), rel("Users")),
                project(List.of(projected(attr("id"))), rel("Admins")));
        assertPrettyPrints(node, "(π id (Users)) ⊎ (π id (Admins))");
    }

    @Test
    public void prettyPrintsNestedUnionAll() {
        RelNode node = unionAll(
                unionAll(rel("A"), rel("B")),
                rel("C"));
        assertPrettyPrints(node, "((A) ⊎ (B)) ⊎ (C)");
    }

    @Test
    public void prettyPrintsUnionAllRoundTripsThroughParser() {
        RelNode original = unionAll(rel("A"), rel("B"));
        RelNode reparsed = parse(original.prettyPrint());
        assertParsesTo(original.prettyPrint(), reparsed);
    }

    @Test
    public void prettyPrintsMixedUnionAndUnionAll() {
        RelNode node = unionAll(
                union(rel("A"), rel("B")),
                rel("C"));
        assertPrettyPrints(node, "((A) ∪ (B)) ⊎ (C)");
    }

    // ==================== Category 7: Error Cases (5 tests) ====================

    @Test
    public void failsOnMissingRightOperand() {
        assertParseError("Users ⊎")
                .hasMessageContaining("Expected relation name");
    }

    @Test
    public void failsOnMissingLeftOperand() {
        assertParseError("⊎ Users")
                .hasMessageContaining("Expected relation name");
    }

    @Test
    public void failsOnDoubleUnionAll() {
        assertParseError("A ⊎ ⊎ B")
                .hasMessageContaining("Expected relation name");
    }

    @Test
    public void failsOnTrailingUnionAll() {
        assertParseError("A ⊎ B ⊎")
                .hasMessageContaining("Expected relation name");
    }

    @Test
    public void failsOnUnclosedParenthesizedUnionAll() {
        assertParseError("(A ⊎ B")
                .hasMessageContaining("Expected ')' after relational expression");
    }
}

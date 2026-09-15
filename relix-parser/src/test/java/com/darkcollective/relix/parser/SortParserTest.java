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

import static com.darkcollective.relix.ast.AstBuilders.*;

/**
 * Comprehensive tests for Sort Operator (τ).
 * Tests parsing of sort operations with various specifications and directions.
 */
final class SortParserTest extends ParserTestSupport {

    // ==================== Expression sort keys (issue #375) ====================

    @Test
    public void parsesFunctionCallSortKeyDESC() {
        assertParsesTo("τ to_timestamp(logged) DESC (RawLogs)",
                sort(
                        List.of(sortKey(
                                func("to_timestamp", attr("logged")), SortDirection.DESC)),
                        rel("RawLogs")));
    }

    @Test
    public void parsesArithmeticSortKeyDefaultDirection() {
        assertParsesTo("τ price * qty (Orders)",
                sort(
                        List.of(sortKey(
                                arith(attr("price"), ArithmeticOperator.MULTIPLY, attr("qty")),
                                SortDirection.ASC)),
                        rel("Orders")));
    }

    @Test
    public void parsesMixedPlainAndExpressionSortKeys() {
        assertParsesTo("τ region, Abs(delta) DESC (Metrics)",
                sort(
                        List.of(asc("region"),
                                sortKey(func("Abs", attr("delta")), SortDirection.DESC)),
                        rel("Metrics")));
    }

    @Test
    public void bareColumnSortKeyBeforeInputStillParsesAsColumn() {
        // The adjacency rule keeps `τ name (Users)` a bare column, not a call name(Users).
        assertParsesTo("τ name (Users)",
                sort(
                        List.of(asc("name")),
                        rel("Users")));
    }

    // ==================== Category 1: Basic Sort Specifications (6 tests) ====================

    @Test
    public void parsesSingleAttributeDefaultDirection() {
        assertParsesTo("τ name (Users)",
                sort(
                        List.of(asc("name")),
                        rel("Users")));
    }

    @Test
    public void parsesSingleAttributeExplicitASC() {
        assertParsesTo("τ name ASC (Users)",
                sort(
                        List.of(asc("name")),
                        rel("Users")));
    }

    @Test
    public void parsesSingleAttributeDESC() {
        assertParsesTo("τ salary DESC (Users)",
                sort(
                        List.of(desc("salary")),
                        rel("Users")));
    }

    @Test
    public void parsesMultipleAttributesMixedDirections() {
        assertParsesTo("τ dept, salary DESC, name ASC (Employees)",
                sort(
                        List.of(
                                asc("dept"),
                                desc("salary"),
                                asc("name")
                        ),
                        rel("Employees")));
    }

    @Test
    public void parsesAttributeNamesWithUnderscoresAndNumbers() {
        assertParsesTo("τ dept_id, salary_2024 DESC (Employees)",
                sort(
                        List.of(
                                asc("dept_id"),
                                desc("salary_2024")
                        ),
                        rel("Employees")));
    }

    @Test
    public void parsesLongIdentifierNames() {
        assertParsesTo("τ department_name, employee_salary DESC (Organization)",
                sort(
                        List.of(
                                asc("department_name"),
                                desc("employee_salary")
                        ),
                        rel("Organization")));
    }

    // ==================== Category 2: Sort Direction Handling (4 tests) ====================

    @Test
    public void defaultDirectionIsASCWhenNotSpecified() {
        assertParsesTo("τ id, name (Data)",
                sort(
                        List.of(
                                asc("id"),
                                asc("name")
                        ),
                        rel("Data")));
    }

    @Test
    public void allAttributesWithASC() {
        assertParsesTo("τ a ASC, b ASC, c ASC (T)",
                sort(
                        List.of(
                                asc("a"),
                                asc("b"),
                                asc("c")
                        ),
                        rel("T")));
    }

    @Test
    public void allAttributesWithDESC() {
        assertParsesTo("τ x DESC, y DESC (TableA)",
                sort(
                        List.of(
                                desc("x"),
                                desc("y")
                        ),
                        rel("TableA")));
    }

    @Test
    public void alternatingDirections() {
        assertParsesTo("τ a ASC, b DESC, c ASC, d DESC (Data)",
                sort(
                        List.of(
                                asc("a"),
                                desc("b"),
                                asc("c"),
                                desc("d")
                        ),
                        rel("Data")));
    }

    // ==================== Category 3: Complex Input Expressions (5 tests) ====================

    @Test
    public void sortOnSelectionInput() {
        assertParsesTo("τ age (σ active = true (Users))",
                sort(
                        List.of(asc("age")),
                        select(
                                cmp(attr("active"), ComparisonOperator.EQUAL, bool(true)),
                                rel("Users"))));
    }

    @Test
    public void sortOnProjectionInput() {
        assertParsesTo("τ salary DESC (π id, salary (Employees))",
                sort(
                        List.of(desc("salary")),
                        project(
                                List.of(projected(attr("id")), projected(attr("salary"))),
                                rel("Employees"))));
    }

    @Test
    public void sortOnJoinInput() {
        assertParsesTo("τ name (Users ⋈ Orders)",
                sort(
                        List.of(asc("name")),
                        naturalJoin(
                                rel("Users"),
                                rel("Orders"))));
    }

    @Test
    public void nestedSorts() {
        assertParsesTo("τ name (τ age DESC (Users))",
                sort(
                        List.of(asc("name")),
                        sort(
                                List.of(desc("age")),
                                rel("Users"))));
    }

    @Test
    public void sortOnAggregationInput() {
        assertParsesTo("τ total DESC (γ dept, SUM(salary) → total (Employees))",
                sort(
                        List.of(desc("total")),
                        groupBy(
                                List.of("dept"),
                                List.of(agg(AggregateOperator.SUM, "salary", "total")),
                                rel("Employees"))));
    }

    // ==================== Category 4: Edge Cases (5 tests) ====================

    @Test
    public void singleLetterAttributeName() {
        assertParsesTo("τ x DESC (T)",
                sort(
                        List.of(desc("x")),
                        rel("T")));
    }

    @Test
    public void attributesWithNumbers() {
        assertParsesTo("τ col1, col2 DESC, col3 (Table)",
                sort(
                        List.of(
                                asc("col1"),
                                desc("col2"),
                                asc("col3")
                        ),
                        rel("Table")));
    }

    @Test
    public void manySortSpecifications() {
        assertParsesTo("τ a, b DESC, c, d ASC, e DESC, f (Data)",
                sort(
                        List.of(
                                asc("a"),
                                desc("b"),
                                asc("c"),
                                asc("d"),
                                desc("e"),
                                asc("f")
                        ),
                        rel("Data")));
    }

    @Test
    public void keywordLikeAttributeNames() {
        assertParsesTo("τ sum, count DESC, max (FilteredData)",
                sort(
                        List.of(
                                asc("sum"),
                                desc("count"),
                                asc("max")
                        ),
                        rel("FilteredData")));
    }


    // ==================== Category 5: Pretty Printing (3 tests) ====================

    @Test
    public void prettyPrintsSortWithoutExplicitDirections() {
        RelNode node = sort(
                List.of(asc("name")),
                rel("Users"));

        assertPrettyPrints(node, "τ name (Users)");
    }

    @Test
    public void prettyPrintsSortWithDESC() {
        RelNode node = sort(
                List.of(
                        desc("salary"),
                        asc("name")
                ),
                rel("Employees"));

        assertPrettyPrints(node, "τ salary DESC, name (Employees)");
    }

    @Test
    public void prettyPrintsSortWithComplexInput() {
        RelNode node = sort(
                List.of(desc("total")),
                select(
                        cmp(attr("status"), ComparisonOperator.EQUAL, str("active")),
                        rel("Orders")));

        assertPrettyPrints(node, "τ total DESC (σ status = \"active\" (Orders))");
    }

    // ==================== Category 6: Error Cases (6 tests) ====================

    @Test
    public void descIsValidAsSortAttributeName() {
        // Keywords like DESC are valid as column names in sort-attribute position
        assertParsesTo("τ DESC (Users)",
                sort(
                        List.of(asc("DESC")),
                        rel("Users")));
    }

    @Test
    public void failsOnMissingAttributeName() {
        // `τ (Users)` now consumes `(Users)` as a parenthesized sort-key expression,
        // leaving no input relation — the parser reports the missing input.
        assertParseError("τ (Users)")
                .hasMessageContaining("Expected '(' after sort specifications");
    }

    @Test
    public void failsOnMissingOpeningParenthesis() {
        assertParseError("τ name Users")
                .hasMessageContaining("Expected '(' after sort specifications");
    }

    @Test
    public void failsOnMissingClosingParenthesis() {
        assertParseError("τ name (Users")
                .hasMessageContaining("Expected ')' after relational expression");
    }

    @Test
    public void failsOnEmptySortList() {
        assertParseError("τ (Users)")
                .hasMessageContaining("Expected '(' after sort specifications");
    }

    @Test
    public void failsOnInvalidSortDirection() {
        assertParseError("τ name INVALID (Users)")
                .hasMessageContaining("Expected '(' after sort specifications");
    }

    @Test
    public void failsOnTrailingComma() {
        // After the comma, `(Users)` parses as a parenthesized sort-key expression,
        // consuming the would-be input relation.
        assertParseError("τ name, (Users)")
                .hasMessageContaining("Expected '(' after sort specifications");
    }
}


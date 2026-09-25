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
import java.util.Optional;

/**
 * Comprehensive tests for Limit Operator (λ).
 * Tests parsing of limit operations with count-only and offset+count specifications.
 */
final class LimitParserTest extends ParserTestSupport {

    // ==================== Category 1: Count-Only Limit (5 tests) ====================

    @Test
    public void parsesLimitWithSingleDigitCount() {
        assertParsesTo("λ 5 (Orders)",
                limit(
                        Optional.empty(),
                        5L,
                        rel("Orders")));
    }

    @Test
    public void parsesLimitWithLargeCount() {
        assertParsesTo("λ 1000000 (Orders)",
                limit(
                        Optional.empty(),
                        1000000L,
                        rel("Orders")));
    }

    @Test
    public void parsesLimitWithCountOne() {
        assertParsesTo("λ 1 (Orders)",
                limit(
                        Optional.empty(),
                        1L,
                        rel("Orders")));
    }

    @Test
    public void parsesLimitWithCountZero() {
        assertParsesTo("λ 0 (Orders)",
                limit(
                        Optional.empty(),
                        0L,
                        rel("Orders")));
    }

    @Test
    public void parsesLimitWithMultiDigitCount() {
        assertParsesTo("λ 99 (Data)",
                limit(
                        Optional.empty(),
                        99L,
                        rel("Data")));
    }

    // ==================== Category 2: Offset and Count Limit (6 tests) ====================

    @Test
    public void parsesLimitWithZeroOffsetAndCount() {
        assertParsesTo("λ 0, 5 (Orders)",
                limit(
                        Optional.of(0L),
                        5L,
                        rel("Orders")));
    }

    @Test
    public void parsesLimitWithOffsetAndCount() {
        assertParsesTo("λ 10, 20 (Orders)",
                limit(
                        Optional.of(10L),
                        20L,
                        rel("Orders")));
    }

    @Test
    public void parsesLimitWithLargeOffsetAndCount() {
        assertParsesTo("λ 1000, 50 (Orders)",
                limit(
                        Optional.of(1000L),
                        50L,
                        rel("Orders")));
    }

    @Test
    public void parsesLimitWithOffsetEqualToCount() {
        assertParsesTo("λ 5, 5 (Orders)",
                limit(
                        Optional.of(5L),
                        5L,
                        rel("Orders")));
    }

    @Test
    public void parsesLimitWithOffsetGreaterThanCount() {
        assertParsesTo("λ 100, 10 (Orders)",
                limit(
                        Optional.of(100L),
                        10L,
                        rel("Orders")));
    }

    @Test
    public void parsesLimitWithLargeNumbers() {
        assertParsesTo("λ 999999, 888888 (Orders)",
                limit(
                        Optional.of(999999L),
                        888888L,
                        rel("Orders")));
    }

    // ==================== Category 3: Complex Input Expressions (5 tests) ====================

    @Test
    public void limitOnSelectionInput() {
        assertParsesTo("λ 5 (σ active = true (Orders))",
                limit(
                        Optional.empty(),
                        5L,
                        select(
                                cmp(attr("active"), ComparisonOperator.EQUAL, bool(true)),
                                rel("Orders"))));
    }

    @Test
    public void limitOnProjectionInput() {
        assertParsesTo("λ 10 (π name, salary (Employees))",
                limit(
                        Optional.empty(),
                        10L,
                        project(
                                List.of(projected(attr("name")), projected(attr("salary"))),
                                rel("Employees"))));
    }

    @Test
    public void limitOnJoinInput() {
        assertParsesTo("λ 20 (Users ⋈ Orders)",
                limit(
                        Optional.empty(),
                        20L,
                        naturalJoin(
                                rel("Users"),
                                rel("Orders"))));
    }

    @Test
    public void limitOnSortInput() {
        assertParsesTo("λ 5 (τ salary DESC (Employees))",
                limit(
                        Optional.empty(),
                        5L,
                        sort(
                                List.of(desc("salary")),
                                rel("Employees"))));
    }

    @Test
    public void limitOnAggregationInput() {
        assertParsesTo("λ 100, 50 (γ dept, SUM(salary) (Employees))",
                limit(
                        Optional.of(100L),
                        50L,
                        groupBy(
                                List.of("dept"),
                                List.of(agg(AggregateOperator.SUM, "salary")),
                                rel("Employees"))));
    }

    // ==================== Category 4: Nested and Stacked Operations (5 tests) ====================

    @Test
    public void nestedLimits() {
        assertParsesTo("λ 5 (λ 10 (Orders))",
                limit(
                        Optional.empty(),
                        5L,
                        limit(
                                Optional.empty(),
                                10L,
                                rel("Orders"))));
    }

    @Test
    public void limitAfterSort() {
        assertParsesTo("λ 10 (τ name DESC (Users))",
                limit(
                        Optional.empty(),
                        10L,
                        sort(
                                List.of(desc("name")),
                                rel("Users"))));
    }

    @Test
    public void sortAfterLimit() {
        assertParsesTo("τ id (λ 100 (Orders))",
                sort(
                        List.of(asc("id")),
                        limit(
                                Optional.empty(),
                                100L,
                                rel("Orders"))));
    }

    @Test
    public void limitOnComplexPipeline() {
        assertParsesTo("λ 1, 5 (τ total DESC (γ category, SUM(price) → total (σ available = true (Products))))",
                limit(
                        Optional.of(1L),
                        5L,
                        sort(
                                List.of(desc("total")),
                                groupBy(
                                        List.of("category"),
                                        List.of(agg(AggregateOperator.SUM, "price", "total")),
                                        select(
                                                cmp(attr("available"), ComparisonOperator.EQUAL, bool(true)),
                                                rel("Products"))))));
    }

    @Test
    public void multipleConsecutiveLimits() {
        assertParsesTo("λ 20 (λ 10 (λ 5 (Orders)))",
                limit(
                        Optional.empty(),
                        20L,
                        limit(
                                Optional.empty(),
                                10L,
                                limit(
                                        Optional.empty(),
                                        5L,
                                        rel("Orders")))));
    }

    // ==================== Category 5: Pretty Printing (3 tests) ====================

    @Test
    public void prettyPrintsLimitWithCountOnly() {
        RelNode node = limit(
                Optional.empty(),
                10L,
                rel("Orders"));

        assertPrettyPrints(node, "λ 10 (Orders)");
    }

    @Test
    public void prettyPrintsLimitWithOffsetAndCount() {
        RelNode node = limit(
                Optional.of(5L),
                10L,
                rel("Orders"));

        assertPrettyPrints(node, "λ 5, 10 (Orders)");
    }

    @Test
    public void prettyPrintsLimitWithComplexInput() {
        RelNode node = limit(
                Optional.of(0L),
                20L,
                sort(
                        List.of(desc("salary")),
                        rel("Employees")));

        assertPrettyPrints(node, "λ 0, 20 (τ salary DESC (Employees))");
    }

    @Test
    public void prettyPrintsZeroOffsetAndZeroCount() {
        RelNode node = limit(
                Optional.of(0L),
                0L,
                rel("Data"));

        assertPrettyPrints(node, "λ 0, 0 (Data)");
    }

    @Test
    public void prettyPrintsLimitOnSelection() {
        RelNode node = limit(
                Optional.empty(),
                5L,
                select(
                        cmp(attr("status"), ComparisonOperator.EQUAL, str("active")),
                        rel("Orders")));

        assertPrettyPrints(node, "λ 5 (σ status = \"active\" (Orders))");
    }

    // ==================== Category 6: Error Cases (6 tests) ====================

    @Test
    public void failsOnMissingCount() {
        assertParseError("λ (Orders)")
                .hasMessageContaining("Expected number");
    }

    @Test
    public void failsOnNonNumericCount() {
        assertParseError("λ abc (Orders)")
                .hasMessageContaining("Expected number for limit or offset");
    }

    @Test
    public void failsOnMissingNumberAfterComma() {
        assertParseError("λ 5, (Orders)")
                .hasMessageContaining("Expected");
    }

    @Test
    public void failsOnMissingOpeningParenthesis() {
        assertParseError("λ 5 Orders")
                .hasMessageContaining("Expected '(' after limit specification");
    }

    @Test
    public void failsOnMissingClosingParenthesis() {
        assertParseError("λ 5 (Orders")
                .hasMessageContaining("Expected ')' after relational expression");
    }

    @Test
    public void failsOnTrailingComma() {
        assertParseError("λ 5, (Orders)")
                .hasMessageContaining("Expected");
    }
}


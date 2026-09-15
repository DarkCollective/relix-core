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

/**
 * Comprehensive tests for Feature 2: Expression Renaming (Arrow Operator).
 * Tests parsing of aliased attributes in projections using the → operator.
 */
final class ExpressionAliasingTest extends ParserTestSupport {

    // ==================== Basic Aliasing Tests ====================

    @Test
    public void parsesSimpleAttributeAlias() {
        assertParsesTo("π id → user_id (Users)",
                project(
                        List.of(projected(attr("id"), "user_id")),
                        rel("Users")));
    }

    @Test
    public void parsesSimpleAttributeAliasWithUnderscores() {
        assertParsesTo("π user_id → user_identifier (Users)",
                project(
                        List.of(projected(attr("user_id"), "user_identifier")),
                        rel("Users")));
    }

    @Test
    public void parsesSimpleAttributeAliasWithNumbers() {
        assertParsesTo("π col1 → col2 (R)",
                project(
                        List.of(projected(attr("col1"), "col2")),
                        rel("R")));
    }

    @Test
    public void parsesMultipleSimpleAliases() {
        assertParsesTo("π id → user_id, name → user_name (Users)",
                project(
                        List.of(
                                projected(attr("id"), "user_id"),
                                projected(attr("name"), "user_name")
                        ),
                        rel("Users")));
    }

    // ==================== Arithmetic Expression Aliasing Tests ====================

    @Test
    public void parsesArithmeticExpressionWithAlias() {
        assertParsesTo("π price * 1.05 → adjusted_price (Products)",
                project(
                        List.of(projected(
                                arith(attr("price"), ArithmeticOperator.MULTIPLY, num("1.05")),
                                "adjusted_price"
                        )),
                        rel("Products")));
    }

    @Test
    public void parsesComplexArithmeticExpressionWithAlias() {
        assertParsesTo("π (price + tax) * 0.8 → total (Orders)",
                project(
                        List.of(projected(
                                arith(
                                        arith(attr("price"), ArithmeticOperator.PLUS, attr("tax")),
                                        ArithmeticOperator.MULTIPLY,
                                        num("0.8")
                                ),
                                "total"
                        )),
                        rel("Orders")));
    }

    @Test
    public void parsesArithmeticDivisionWithAlias() {
        assertParsesTo("π salary / 12 → monthly_salary (Employees)",
                project(
                        List.of(projected(
                                arith(attr("salary"), ArithmeticOperator.DIVIDE, num("12")),
                                "monthly_salary"
                        )),
                        rel("Employees")));
    }

    @Test
    public void parsesMultipleArithmeticExpressionsWithAliases() {
        assertParsesTo("π a + b → sum, c * d → product (R)",
                project(
                        List.of(
                                projected(
                                        arith(attr("a"), ArithmeticOperator.PLUS, attr("b")),
                                        "sum"
                                ),
                                projected(
                                        arith(attr("c"), ArithmeticOperator.MULTIPLY, attr("d")),
                                        "product"
                                )
                        ),
                        rel("R")));
    }

    @Test
    public void parsesNestedArithmeticWithAlias() {
        assertParsesTo("π a * b + c → result (R)",
                project(
                        List.of(projected(
                                arith(
                                        arith(attr("a"), ArithmeticOperator.MULTIPLY, attr("b")),
                                        ArithmeticOperator.PLUS,
                                        attr("c")
                                ),
                                "result"
                        )),
                        rel("R")));
    }

    // ==================== Mixed Simple and Aliased Attributes Tests ====================

    @Test
    public void parsesMixedSimpleAndAliasedAttributes() {
        assertParsesTo("π id, name → user_name (Users)",
                project(
                        List.of(
                                projected(attr("id")),
                                projected(attr("name"), "user_name")
                        ),
                        rel("Users")));
    }

    @Test
    public void parsesAliasedThenSimpleAttributes() {
        assertParsesTo("π id → user_id, name (Users)",
                project(
                        List.of(
                                projected(attr("id"), "user_id"),
                                projected(attr("name"))
                        ),
                        rel("Users")));
    }

    @Test
    public void parsesMixedComplex() {
        assertParsesTo("π id, name → user_name, salary / 12 → monthly, dept (Employees)",
                project(
                        List.of(
                                projected(attr("id")),
                                projected(attr("name"), "user_name"),
                                projected(
                                        arith(attr("salary"), ArithmeticOperator.DIVIDE, num("12")),
                                        "monthly"
                                ),
                                projected(attr("dept"))
                        ),
                        rel("Employees")));
    }

    @Test
    public void parsesArithmeticWithoutAliasMixed() {
        assertParsesTo("π id, price * 1.05, name → user_name (Products)",
                project(
                        List.of(
                                projected(attr("id")),
                                projected(arith(attr("price"), ArithmeticOperator.MULTIPLY, num("1.05"))),
                                projected(attr("name"), "user_name")
                        ),
                        rel("Products")));
    }

    // ==================== Edge Cases Tests ====================

    @Test
    public void parsesAliasWithKeywordLikeName() {
        // Alias name can be similar to keywords but is still an identifier
        assertParsesTo("π id → total (R)",
                project(
                        List.of(projected(attr("id"), "total")),
                        rel("R")));
    }

    @Test
    public void parsesMultiCharacterAlias() {
        assertParsesTo("π a → business_logic_result_2024 (R)",
                project(
                        List.of(projected(attr("a"), "business_logic_result_2024")),
                        rel("R")));
    }

    @Test
    public void parsesNoAliasesBackwardCompatibility() {
        // Backward compatibility: projections without aliases should still work
        assertParsesTo("π id, name, age (Users)",
                project(
                        List.of(
                                projected(attr("id")),
                                projected(attr("name")),
                                projected(attr("age"))
                        ),
                        rel("Users")));
    }

    @Test
    public void parsesLongIdentifierAlias() {
        assertParsesTo("π x → long_identifier_name_for_column (R)",
                project(
                        List.of(projected(attr("x"), "long_identifier_name_for_column")),
                        rel("R")));
    }

    // ==================== Integration Tests ====================

    @Test
    public void parsesAliasedProjectionWithSelection() {
        assertParsesTo("π id → user_id, price * qty → total (σ total > 100 (Orders))",
                project(
                        List.of(
                                projected(attr("id"), "user_id"),
                                projected(
                                        arith(attr("price"), ArithmeticOperator.MULTIPLY, attr("qty")),
                                        "total"
                                )
                        ),
                        select(
                                cmp(attr("total"), ComparisonOperator.GREATER, num("100")),
                                rel("Orders"))));
    }

    @Test
    public void parsesAliasedProjectionWithNaturalJoin() {
        assertParsesTo("π Users.id → user_id, Orders.id → order_id ((Users) ⋈ (Orders))",
                project(
                        List.of(
                                projected(attr("Users.id"), "user_id"),
                                projected(attr("Orders.id"), "order_id")
                        ),
                        naturalJoin(
                                rel("Users"),
                                rel("Orders"))));
    }

    @Test
    public void parsesAliasedProjectionWithUnion() {
        assertParsesTo("π name → full_name (Users) ∪ π name → full_name (Customers)",
                union(
                        project(
                                List.of(projected(attr("name"), "full_name")),
                                rel("Users")),
                        project(
                                List.of(projected(attr("name"), "full_name")),
                                rel("Customers"))));
    }

    // ==================== Error Cases Tests ====================

    @Test
    public void failsOnMissingAliasName() {
        assertParseError("π id → (Users)")
                .hasMessageContaining("Expected alias");
    }


    @Test
    public void failsOnMultipleArrowsInExpression() {
        // The second arrow is treated as next operand separator
        assertParseError("π id → x → y (Users)")
                .hasMessageContaining("Expected");
    }

    @Test
    public void failsOnArrowAtEndOfProjection() {
        assertParseError("π id, name → (Users)")
                .hasMessageContaining("Expected alias");
    }

    @Test
    public void failsOnArrowWithoutParenthesizedRelation() {
        assertParseError("π id → user_id")
                .hasMessageContaining("Expected");
    }
}


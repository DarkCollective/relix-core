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
 * Comprehensive tests for function call parsing.
 */
final class FunctionParserTest extends ParserTestSupport {

    // ==================== Basic Function Calls ====================

    @Test
    public void parsesFunctionCallWithNoArguments() {
        assertParsesTo("π FUNC() (R)",
                project(
                        List.of(projected(func("FUNC"))),
                        rel("R")));
    }

    @Test
    public void parsesFunctionCallWithSingleArgument() {
        assertParsesTo("π COS(1.0) (R)",
                project(
                        List.of(projected(func("COS", num("1.0")))),
                        rel("R")));
    }

    @Test
    public void parsesFunctionCallWithMultipleArguments() {
        assertParsesTo("π FUNC(a, b, c) (R)",
                project(
                        List.of(projected(func("FUNC", attr("a"), attr("b"), attr("c")))),
                        rel("R")));
    }

    @Test
    public void parsesFunctionCallWithComplexArguments() {
        assertParsesTo("π FUNC(a + b, COS(x)) (R)",
                project(
                        List.of(projected(func("FUNC",
                                arith(attr("a"), ArithmeticOperator.PLUS, attr("b")),
                                func("COS", attr("x"))))),
                        rel("R")));
    }

    // ==================== Function Calls in Predicates ====================

    @Test
    public void parsesFunctionCallInPredicate() {
        assertParsesTo("σ FUNC(x) = 5 (R)",
                select(
                        cmp(func("FUNC", attr("x")), ComparisonOperator.EQUAL, num("5")),
                        rel("R")));
    }

    // ==================== Function Calls with Aliases ====================

    @Test
    public void parsesFunctionCallWithAlias() {
        assertParsesTo("π FUNC(a) → result (R)",
                project(
                        List.of(projected(func("FUNC", attr("a")), "result")),
                        rel("R")));
    }

    // ==================== Nested Function Calls ====================

    @Test
    public void parsesNestedFunctionCalls() {
        assertParsesTo("π OUTER(INNER(x)) (R)",
                project(
                        List.of(projected(func("OUTER", func("INNER", attr("x"))))),
                        rel("R")));
    }

    // ==================== Pretty Printing ====================

    @Test
    public void prettyPrintsFunctionCall() {
        RelNode node = project(
                List.of(projected(func("COS", num("1.0")))),
                rel("R"));
        assertPrettyPrints(node, "π COS(1.0) (R)");
    }

    @Test
    public void prettyPrintsFunctionCallWithMultipleArgs() {
        RelNode node = project(
                List.of(projected(func("FUNC", attr("a"), num("1"), str("hello")))),
                rel("R"));
        assertPrettyPrints(node, "π FUNC(a, 1, \"hello\") (R)");
    }

    // ==================== Condition (boolean) Arguments ====================

    @Test
    public void parsesComparisonAsFunctionArgument() {
        // IIf's whole purpose: a comparison in argument position, wrapped as a
        // boolean-valued ConditionOperand.
        assertParsesTo("π IIf(price > 100, \"e\", \"c\") (R)",
                project(
                        List.of(projected(func("IIf",
                                condition(
                                        cmp(attr("price"),
                                        ComparisonOperator.GREATER, num("100"))),
                                str("e"), str("c")))),
                        rel("R")));
    }

    @Test
    public void parsesConjunctionAsFunctionArgument() {
        assertParsesTo("π IIf(a > 2 ∧ b < 20, 1, 0) (R)",
                project(
                        List.of(projected(func("IIf",
                                condition(
                                        and(
                                        cmp(attr("a"), ComparisonOperator.GREATER, num("2")),
                                        cmp(attr("b"), ComparisonOperator.LESS, num("20")))),
                                num("1"), num("0")))),
                        rel("R")));
    }

    @Test
    public void bareOperandArgumentStaysAnOperand() {
        // A plain operand argument must NOT be wrapped in a ConditionOperand.
        assertParsesTo("π IIf(active, 1, 0) (R)",
                project(
                        List.of(projected(func("IIf", attr("active"), num("1"), num("0")))),
                        rel("R")));
    }

    @Test
    public void parsesLikeConditionAsFunctionArgument() {
        assertParsesTo("π IIf(name LIKE \"A%\", 1, 0) (R)",
                project(
                        List.of(projected(func("IIf",
                                condition(
                                        like(
                                        attr("name"), str("A%"))),
                                num("1"), num("0")))),
                        rel("R")));
    }

    @Test
    public void prettyPrintsConditionArgumentRoundTrip() {
        RelNode node = project(
                List.of(projected(func("IIf",
                        condition(
                                cmp(attr("price"),
                                ComparisonOperator.GREATER, num("100"))),
                        str("e"), str("c")))),
                rel("R"));
        // Parenthesised: a condition in operand position always is, so that the one
        // rule covers the positions where the bare form is ambiguous. The bare form
        // still parses here, and to the identical tree.
        assertPrettyPrints(node, "π IIf((price > 100), \"e\", \"c\") (R)");
    }

    // ==================== Error Cases ====================

    @Test
    public void failsOnMissingClosingParenthesis() {
        assertParseError("π FUNC(a (R)")
                .hasMessageContaining("Expected ')' after function arguments");
    }

    @Test
    public void failsOnInvalidArgument() {
        assertParseError("π FUNC(a, ) (R)")
                .hasMessageContaining("Expected attribute, string, number, boolean literal, function call, or '('");
    }
}

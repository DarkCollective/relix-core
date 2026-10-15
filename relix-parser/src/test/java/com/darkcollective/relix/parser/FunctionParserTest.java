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

    // ==================== CAST and EXTRACT (#135) ====================

    @org.junit.jupiter.api.Test
    public void extractIsTheFieldsFunction() {
        assertParsesTo("π EXTRACT(week FROM d) → w (R)",
                AstBuilders.project(java.util.List.of(AstBuilders.projected(
                        AstBuilders.func("WEEK", AstBuilders.attr("d")), "w")), AstBuilders.rel("R")));
        assertParsesTo("π extract(Epoch from t + 1) → e (R)", parse("π EPOCH(t + 1) → e (R)"));
        // Without FROM it is an ordinary call.
        assertParsesTo("π EXTRACT(a, b) → x (R)",
                AstBuilders.project(java.util.List.of(AstBuilders.projected(
                        AstBuilders.func("EXTRACT", AstBuilders.attr("a"), AstBuilders.attr("b")), "x")),
                        AstBuilders.rel("R")));
        assertParsesTo("π EXTRACT(1, d) → x (R)",
                AstBuilders.project(java.util.List.of(AstBuilders.projected(
                        AstBuilders.func("EXTRACT", AstBuilders.num("1"), AstBuilders.attr("d")), "x")),
                        AstBuilders.rel("R")));
        assertParseError("π EXTRACT(FORTNIGHT FROM d) → x (R)").hasMessageContaining("EXTRACT has no field 'FORTNIGHT'");
        assertParseError("π EXTRACT(WEEK FROM d → x (R)").hasMessageContaining("Expected ')' after the EXTRACT operand");
    }

    @org.junit.jupiter.api.Test
    public void castToATemporalKindIsItsConversion() {
        assertParsesTo("π CAST(s AS date) → d, CAST(s AS TIME) → t, cast(s as Timestamp) → ts (R)",
                parse("π to_date(s) → d, to_time(s) → t, to_timestamp(s) → ts (R)"));
    }

    @org.junit.jupiter.api.Test
    public void castToNumberStringOrBooleanPassesNullThrough() {
        assertParsesTo("π CAST(s AS NUMBER) → n (R)", parse("π IIf(s IS NULL, NULL, CDbl(s)) → n (R)"));
        assertParsesTo("π CAST(s AS string) → n (R)", parse("π IIf(s IS NULL, NULL, CStr(s)) → n (R)"));
        assertParsesTo("π CAST(s AS BOOLEAN) → n (R)", parse("π IIf(s IS NULL, NULL, CBool(s)) → n (R)"));
    }

    @org.junit.jupiter.api.Test
    public void castErrors() {
        assertParseError("π CAST(s AS MONEY) → x (R)").hasMessageContaining("CAST has no type 'MONEY'");
        assertParseError("π CAST(s) → x (R)").hasMessageContaining("Expected 'AS' and a type");
        assertParseError("π CAST(s AS 1) → x (R)").hasMessageContaining("Expected a type after 'AS'");
        assertParseError("π CAST(s AS DATE → x (R)").hasMessageContaining("Expected ')' after the CAST type");
    }

    // ==================== SUBSTRING … FROM … FOR, POSITION … IN (#136) ====================

    @org.junit.jupiter.api.Test
    public void substringWithWords() {
        assertParsesTo("π SUBSTRING(s FROM 2 FOR 3) → x (R)", parse("π SUBSTRING(s, 2, 3) → x (R)"));
        assertParsesTo("π substring(s from 2) → x (R)", parse("π substring(s, 2) → x (R)"));
        assertParsesTo("π SUBSTRING(s FOR 3) → x (R)", parse("π SUBSTRING(s, 1, 3) → x (R)"));
        assertParsesTo("π SUBSTRING(s, 2) → x (R)",
                AstBuilders.project(java.util.List.of(AstBuilders.projected(
                        AstBuilders.func("SUBSTRING", AstBuilders.attr("s"), AstBuilders.num("2")), "x")),
                        AstBuilders.rel("R")));
        assertParseError("π SUBSTRING(s FROM 2 FOR 3 → x (R)").hasMessageContaining("Expected ')' after SUBSTRING");
    }

    @org.junit.jupiter.api.Test
    public void positionIn() {
        assertParsesTo("π POSITION('@' IN email) → x (R)", parse("π STRPOS(email, '@') → x (R)"));
        assertParsesTo("π position(a, b) → x (R)",
                AstBuilders.project(java.util.List.of(AstBuilders.projected(
                        AstBuilders.func("position", AstBuilders.attr("a"), AstBuilders.attr("b")), "x")),
                        AstBuilders.rel("R")));
        assertParseError("π POSITION('@' IN email → x (R)").hasMessageContaining("Expected ')' after POSITION");
    }
}

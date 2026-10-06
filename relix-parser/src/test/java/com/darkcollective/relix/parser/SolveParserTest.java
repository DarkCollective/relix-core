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

import java.util.List;
import java.util.Optional;
import com.darkcollective.relix.ast.*;
import com.darkcollective.relix.ast.internal.*;

/**
 * Tests for the goal-seek operator (SOLVE): {@code SOLVE left = right (input)}.
 */
final class SolveParserTest extends ParserTestSupport {

    @Test
    void parsesBasicEquation() {
        assertParsesTo("SOLVE total = principal * rate (Loans)",
                solve(
                        attr("total"),
                        arith(attr("principal"), ArithmeticOperator.MULTIPLY, attr("rate")),
                        rel("Loans")));
    }

    @Test
    void parsesAdditionWithLiteral() {
        assertParsesTo("SOLVE gross = net + 5 (Invoices)",
                solve(
                        attr("gross"),
                        arith(attr("net"), ArithmeticOperator.PLUS, num("5")),
                        rel("Invoices")));
    }

    @Test
    void parsesMultiTermRightHandSide() {
        assertParsesTo("SOLVE total = principal * rate + fee (Loans)",
                solve(
                        attr("total"),
                        arith(
                                arith(attr("principal"), ArithmeticOperator.MULTIPLY, attr("rate")),
                                ArithmeticOperator.PLUS,
                                attr("fee")),
                        rel("Loans")));
    }

    @Test
    void nestsInsideAnotherOperator() {
        assertParsesTo("δ (SOLVE a = b (R))",
                distinct(
                        solve(attr("a"), attr("b"), rel("R"))));
    }

    @Test
    void capturesBothSides() {
        SolveNode node = (SolveNode) parse("SOLVE x = y (R)");
        org.assertj.core.api.Assertions.assertThat(node.equations().getFirst().left())
                .isInstanceOfSatisfying(AttributeOperand.class,
                        a -> org.assertj.core.api.Assertions.assertThat(a.name()).isEqualTo("x"));
        org.assertj.core.api.Assertions.assertThat(node.equations().getFirst().right())
                .isInstanceOfSatisfying(AttributeOperand.class,
                        a -> org.assertj.core.api.Assertions.assertThat(a.name()).isEqualTo("y"));
    }

    // ─── Systems ────────────────────────────────────────────────────────────

    @Test
    void parsesABracedSystem() {
        assertParsesTo("SOLVE { total = a + b, diff = a - b } (Pairs)",
                solve(List.of(
                        equation(attr("total"),
                                arith(attr("a"), ArithmeticOperator.PLUS, attr("b"))),
                        equation(attr("diff"),
                                arith(attr("a"), ArithmeticOperator.MINUS, attr("b")))),
                        rel("Pairs")));
    }

    @Test
    void oneBracedEquationIsThePlainForm() {
        assertParsesTo("SOLVE { x = y } (R)", solve(attr("x"), attr("y"), rel("R")));
    }

    @Test
    void prettyPrintsASystemInBraces() {
        RelNode system = solve(List.of(
                        equation(attr("x"), attr("y")),
                        equation(attr("u"), arith(attr("v"), ArithmeticOperator.MULTIPLY, num("2")))),
                rel("R"));
        assertPrettyPrints(system, "SOLVE { x = y, u = v * 2 } (R)");
        assertParsesTo(system.prettyPrint(), system);
    }

    @Test
    void parsesPerKeysAfterOneEquation() {
        assertParsesTo("SOLVE y = a * x PER series, run (Points)",
                solve(List.of(equation(attr("y"),
                                arith(attr("a"), ArithmeticOperator.MULTIPLY, attr("x")))),
                        List.of("series", "run"), rel("Points")));
    }

    @Test
    void parsesPerKeysAfterASystem() {
        RelNode fitted = solve(List.of(equation(attr("x"), attr("y")), equation(attr("u"), attr("v"))),
                List.of("g"), rel("R"));
        assertParsesTo("SOLVE { x = y, u = v } PER g (R)", fitted);
        assertPrettyPrints(fitted, "SOLVE { x = y, u = v } PER g (R)");
        assertParsesTo(fitted.prettyPrint(), fitted);
    }

    @Test
    void parsesTheIterationLimits() {
        RelNode limited = solve(List.of(equation(attr("area"),
                        arith(attr("side"), ArithmeticOperator.MULTIPLY, attr("side")))),
                List.of("shape"), Optional.of(new java.math.BigDecimal("0.000001")), Optional.of(50),
                rel("Squares"));
        assertParsesTo("SOLVE area = side * side PER shape WITHIN 0.000001 MAX 50 ROUNDS (Squares)",
                limited);
        assertPrettyPrints(limited,
                "SOLVE area = side * side PER shape WITHIN 0.000001 MAX 50 ROUNDS (Squares)");
        assertParsesTo(limited.prettyPrint(), limited);
    }

    @Test
    void parsesEitherLimitAlone() {
        RelNode within = solve(List.of(equation(attr("x"), attr("y"))), List.of(),
                Optional.of(new java.math.BigDecimal("0.01")), Optional.empty(), rel("R"));
        assertParsesTo("SOLVE x = y WITHIN 0.01 (R)", within);
        RelNode capped = solve(List.of(equation(attr("x"), attr("y"))), List.of(),
                Optional.empty(), Optional.of(7), rel("R"));
        assertParsesTo("SOLVE x = y MAX 7 ROUNDS (R)", capped);
    }

    @Test
    void parsesStartsAfterTheLimits() {
        RelNode started = solve(List.of(equation(num("0"),
                        arith(attr("y0"), ArithmeticOperator.PLUS,
                                arith(attr("vy"), ArithmeticOperator.MULTIPLY, attr("t"))))),
                List.of(), Optional.empty(), Optional.of(50),
                List.of(start("t", arith(num("2"), ArithmeticOperator.MULTIPLY, attr("vy"))),
                        start("y0", num("0"))),
                rel("Shots"));
        assertParsesTo("SOLVE 0 = y0 + vy * t MAX 50 ROUNDS START t = 2 * vy, y0 = 0 (Shots)",
                started);
        assertPrettyPrints(started,
                "SOLVE 0 = y0 + vy * t MAX 50 ROUNDS START t = 2 * vy, y0 = 0 (Shots)");
        assertParsesTo(started.prettyPrint(), started);
    }

    @Test
    void startIsAContextualWord() {
        RelNode started = solve(List.of(equation(attr("start"), attr("y"))), List.of(),
                Optional.empty(), Optional.empty(), List.of(start("start", attr("guess"))),
                rel("R"));
        assertParsesTo("SOLVE start = y start start = guess (R)", started);
    }

    @Test
    void failsOnAStartNamedTwice() {
        assertParseError("SOLVE x = y START x = 1, X = 2 (R)")
                .hasMessageContaining("SOLVE START names 'X' more than once");
    }

    @Test
    void failsOnAStartWithoutAValue() {
        assertParseError("SOLVE x = y START x (R)")
                .hasMessageContaining("Expected '=' after the START column 'x'");
    }

    @Test
    void failsOnAStartWithoutAColumn() {
        assertParseError("SOLVE x = y START = 1 (R)")
                .hasMessageContaining("Expected an unknown column name after 'START'");
    }

    @Test
    void failsOnAZeroTolerance() {
        assertParseError("SOLVE x = y WITHIN 0 (R)").hasMessageContaining("greater than 0");
    }

    @Test
    void failsOnAZeroRoundCap() {
        assertParseError("SOLVE x = y MAX 0 ROUNDS (R)")
                .hasMessageContaining("A SOLVE round count must be at least 1");
    }

    @Test
    void failsOnAFractionalRoundCap() {
        assertParseError("SOLVE x = y MAX 2.5 ROUNDS (R)")
                .hasMessageContaining("A SOLVE round count must be a whole number");
    }

    @Test
    void failsOnACapWithoutRounds() {
        assertParseError("SOLVE x = y MAX 5 (R)").hasMessageContaining("'ROUNDS'");
    }

    @Test
    void failsOnPerWithoutAKey() {
        assertParseError("SOLVE x = y PER (R)").hasMessageContaining("PER");
    }

    @Test
    void failsOnAnUnclosedSystem() {
        assertParseError("SOLVE { x = y (R)").hasMessageContaining("'}'");
    }

    @Test
    void failsOnAnEmptySystem() {
        assertParseError("SOLVE { } (R)");
    }

    // ─── Pretty printing ────────────────────────────────────────────────────

    @Test
    void prettyPrintsBasic() {
        assertPrettyPrints(
                solve(
                        attr("total"),
                        arith(attr("principal"), ArithmeticOperator.MULTIPLY, attr("rate")),
                        rel("Loans")),
                "SOLVE total = principal * rate (Loans)");
    }

    @Test
    void prettyPrintRoundTrips() {
        RelNode original = solve(
                attr("total"),
                arith(attr("principal"), ArithmeticOperator.MULTIPLY, attr("rate")),
                rel("R"));
        assertParsesTo(original.prettyPrint(), original);
    }

    // ─── Errors ─────────────────────────────────────────────────────────────

    @Test
    void failsWithoutEquals() {
        assertParseError("SOLVE total (Loans)")
                .hasMessageContaining("Expected '=' between the two sides");
    }

    @Test
    void failsWithoutInput() {
        assertParseError("SOLVE a = b").hasMessageContaining("Expected '('");
    }
}

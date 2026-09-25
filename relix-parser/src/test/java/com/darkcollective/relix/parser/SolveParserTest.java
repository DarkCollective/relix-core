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
        org.assertj.core.api.Assertions.assertThat(node.left())
                .isInstanceOfSatisfying(AttributeOperand.class,
                        a -> org.assertj.core.api.Assertions.assertThat(a.name()).isEqualTo("x"));
        org.assertj.core.api.Assertions.assertThat(node.right())
                .isInstanceOfSatisfying(AttributeOperand.class,
                        a -> org.assertj.core.api.Assertions.assertThat(a.name()).isEqualTo("y"));
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

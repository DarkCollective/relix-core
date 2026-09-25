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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.DisplayName;
import com.darkcollective.relix.ast.*;
import com.darkcollective.relix.ast.internal.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;


/**
 * Comprehensive test suite for unary minus operator support.
 *
 * Verifies:
 * 1. Unary minus works on all operand types
 * 2. Binary minus continues to work correctly (regression)
 * 3. Unary and binary minus don't conflict
 * 4. Unary minus integrates properly with projections and selections
 * 5. Pretty printing and round-trip parsing work correctly
 */
@DisplayName("Unary Minus Operator Tests")
final class UnaryMinusTest extends ParserTestSupport {

    @Nested
    @DisplayName("Basic Unary Minus on Operands")
    class BasicUnaryMinusTests {

        @Test
        @DisplayName("Parses unary minus on number literal")
        public void parsesUnaryMinusNumber() {
            assertParsesTo("π -42 (R)",
                project(
                        List.of(projected(unary(num("42")))),
                        rel("R"))
            );
        }

        @Test
        @DisplayName("Parses unary minus on attribute")
        public void parsesUnaryMinusAttribute() {
            assertParsesTo("π -price (Products)",
                project(
                        List.of(projected(unary(attr("price")))),
                        rel("Products"))
            );
        }

        @Test
        @DisplayName("Parses unary minus on qualified attribute")
        public void parsesUnaryMinusQualifiedAttribute() {
            assertParsesTo("π -Orders.price (Orders)",
                project(
                        List.of(projected(unary(attr("Orders.price")))),
                        rel("Orders"))
            );
        }

        @Test
        @DisplayName("Parses unary minus on function call")
        public void parsesUnaryMinusFunction() {
            assertParsesTo("π -ROUND(1.5, 0) (R)",
                project(
                        List.of(projected(unary(func("ROUND", num("1.5"), num("0"))))),
                        rel("R"))
            );
        }

        @Test
        @DisplayName("Parses unary minus on parenthesized expression")
        public void parsesUnaryMinusParenthesized() {
            assertParsesTo("π -(a + b) (R)",
                project(
                        List.of(projected(unary(arith(attr("a"), ArithmeticOperator.PLUS, attr("b"))))),
                        rel("R"))
            );
        }
    }

    @Nested
    @DisplayName("Unary Minus in Arithmetic Expressions")
    class UnaryMinusArithmeticTests {

        @Test
        @DisplayName("Unary minus on left of binary plus")
        public void unaryMinusLeftOfPlus() {
            // -a + b  =>  BinArith(UnaryMinus(a), PLUS, b)
            assertParsesTo("π -a + b (R)",
                project(
                        List.of(projected(arith(unary(attr("a")), ArithmeticOperator.PLUS, attr("b")))),
                        rel("R"))
            );
        }

        @Test
        @DisplayName("Unary minus on right of binary plus")
        public void unaryMinusRightOfPlus() {
            // a + -b  =>  BinArith(a, PLUS, UnaryMinus(b))
            assertParsesTo("π a + -b (R)",
                project(
                        List.of(projected(arith(attr("a"), ArithmeticOperator.PLUS, unary(attr("b"))))),
                        rel("R"))
            );
        }

        @Test
        @DisplayName("Unary minus with multiplication")
        public void unaryMinusWithMultiplication() {
            // -a * b  =>  BinArith(UnaryMinus(a), MULTIPLY, b)  — unary binds tighter than binary
            assertParsesTo("π -a * b (R)",
                project(
                        List.of(projected(arith(unary(attr("a")), ArithmeticOperator.MULTIPLY, attr("b")))),
                        rel("R"))
            );
        }

        @Test
        @DisplayName("Double unary minus")
        public void doubleUnaryMinus() {
            assertParsesTo("π - -a (R)",
                project(
                        List.of(projected(unary(unary(attr("a"))))),
                        rel("R"))
            );
        }

        @Test
        @DisplayName("Triple unary minus")
        public void tripleUnaryMinus() {
            assertParsesTo("π - - -a (R)",
                project(
                        List.of(projected(unary(unary(unary(attr("a")))))),
                        rel("R"))
            );
        }

        @Test
        @DisplayName("Unary minus with complex precedence")
        public void unaryMinusComplexPrecedence() {
            // -a + b * -c  =>  BinArith(UnaryMinus(a), PLUS, BinArith(b, MULTIPLY, UnaryMinus(c)))
            assertParsesTo("π -a + b * -c (R)",
                project(
                        List.of(projected(
                        arith(
                            unary(attr("a")),
                            ArithmeticOperator.PLUS,
                            arith(attr("b"), ArithmeticOperator.MULTIPLY, unary(attr("c")))
                        )
                    )),
                        rel("R"))
            );
        }
    }

    @Nested
    @DisplayName("Regression: Binary Minus Still Works")
    class BinaryMinusRegressionTests {

        @Test
        @DisplayName("Binary minus between two attributes")
        public void binaryMinusAttributes() {
            assertParsesTo("π a - b (R)",
                project(
                        List.of(projected(arith(attr("a"), ArithmeticOperator.MINUS, attr("b")))),
                        rel("R"))
            );
        }

        @Test
        @DisplayName("Binary minus between numbers")
        public void binaryMinusNumbers() {
            assertParsesTo("π 100 - 50 (R)",
                project(
                        List.of(projected(arith(num("100"), ArithmeticOperator.MINUS, num("50")))),
                        rel("R"))
            );
        }

        @Test
        @DisplayName("Binary minus respects precedence over addition")
        public void binaryMinusPrecedence() {
            // a + b - c  =>  (a + b) - c  (left-associative)
            assertParsesTo("π a + b - c (R)",
                project(
                        List.of(projected(
                        arith(
                            arith(attr("a"), ArithmeticOperator.PLUS, attr("b")),
                            ArithmeticOperator.MINUS,
                            attr("c")
                        )
                    )),
                        rel("R"))
            );
        }

        @Test
        @DisplayName("Binary minus respects precedence under multiplication")
        public void binaryMinusUnderMultiplication() {
            // a - b * c  =>  a - (b * c)
            assertParsesTo("π a - b * c (R)",
                project(
                        List.of(projected(
                        arith(
                            attr("a"),
                            ArithmeticOperator.MINUS,
                            arith(attr("b"), ArithmeticOperator.MULTIPLY, attr("c"))
                        )
                    )),
                        rel("R"))
            );
        }
    }

    @Nested
    @DisplayName("Integrations: Unary Minus in Projections")
    class UnaryMinusProjectionTests {

        @Test
        @DisplayName("Unary minus in projection")
        public void unaryMinusInProjection() {
            assertParsesTo("π -price (Products)",
                project(
                        List.of(projected(unary(attr("price")))),
                        rel("Products"))
            );
        }

        @Test
        @DisplayName("Unary minus with alias in projection")
        public void unaryMinusWithAlias() {
            assertParsesTo("π -price → negative (Products)",
                project(
                        List.of(projected(unary(attr("price")), "negative")),
                        rel("Products"))
            );
        }

        @Test
        @DisplayName("Multiple projections, one with unary minus")
        public void multipleProjectionsWithUnaryMinus() {
            assertParsesTo("π id, -price, quantity (Orders)",
                project(
                        List.of(
                        projected(attr("id")),
                        projected(unary(attr("price"))),
                        projected(attr("quantity"))
                    ),
                        rel("Orders"))
            );
        }

        @Test
        @DisplayName("Unary minus in complex projection expression")
        public void unaryMinusComplexProjection() {
            // -price * quantity → loss  =>  BinArith(UnaryMinus(price), MULTIPLY, quantity) aliased "loss"
            assertParsesTo("π id, -price * quantity → loss, name (Orders)",
                project(
                        List.of(
                        projected(attr("id")),
                        projected(arith(unary(attr("price")), ArithmeticOperator.MULTIPLY, attr("quantity")), "loss"),
                        projected(attr("name"))
                    ),
                        rel("Orders"))
            );
        }
    }

    @Nested
    @DisplayName("Integration: Unary Minus in Selections")
    class UnaryMinusSelectionTests {

        @Test
        @DisplayName("Unary minus in selection predicate")
        public void unaryMinusInSelection() {
            assertParsesTo("σ balance = -100 (Accounts)",
                select(
                        cmp(attr("balance"), ComparisonOperator.EQUAL, unary(num("100"))),
                        rel("Accounts"))
            );
        }

        @Test
        @DisplayName("Unary minus in selection comparison")
        public void unaryMinusInSelectionComparison() {
            assertParsesTo("σ value > -50 (Data)",
                select(
                        cmp(attr("value"), ComparisonOperator.GREATER, unary(num("50"))),
                        rel("Data"))
            );
        }

        @Test
        @DisplayName("Unary minus in complex selection")
        public void unaryMinusComplexSelection() {
            assertParsesTo("σ balance < -1000 ∧ status = \"overdrawn\" (Accounts)",
                select(
                        and(
                            cmp(attr("balance"), ComparisonOperator.LESS, unary(num("1000"))),
                            cmp(attr("status"), ComparisonOperator.EQUAL, str("overdrawn"))),
                        rel("Accounts"))
            );
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Unary minus on deeply nested expression")
        public void unaryMinusDeepNesting() {
            // -(a + (b * (c - d)))
            assertParsesTo("π -(a + (b * (c - d))) (R)",
                project(
                        List.of(projected(
                        unary(arith(
                            attr("a"),
                            ArithmeticOperator.PLUS,
                            arith(
                                attr("b"),
                                ArithmeticOperator.MULTIPLY,
                                arith(attr("c"), ArithmeticOperator.MINUS, attr("d"))
                            )
                        ))
                    )),
                        rel("R"))
            );
        }

        @Test
        @DisplayName("Mixed unary and binary minus in complex expr")
        public void mixedUnaryAndBinary() {
            // a + -b - -c * d  =>  (a + (-b)) - ((-c) * d)
            assertParsesTo("π a + -b - -c * d (R)",
                project(
                        List.of(projected(
                        arith(
                            arith(attr("a"), ArithmeticOperator.PLUS, unary(attr("b"))),
                            ArithmeticOperator.MINUS,
                            arith(unary(attr("c")), ArithmeticOperator.MULTIPLY, attr("d"))
                        )
                    )),
                        rel("R"))
            );
        }

        @Test
        @DisplayName("Unary minus on function with unary minus arguments")
        public void unaryMinusFunctionWithUnaryArgs() {
            assertParsesTo("π FUNC(a, -b, -c) (R)",
                project(
                        List.of(projected(func("FUNC", attr("a"), unary(attr("b")), unary(attr("c"))))),
                        rel("R"))
            );
        }
    }

    @Nested
    @DisplayName("Pretty Printing")
    class PrettyPrintingTests {

        @Test
        @DisplayName("Pretty prints unary minus on number")
        public void prettyPrintUnaryMinusNumber() {
            assertPrettyPrints(
                project(List.of(projected(unary(num("42")))), rel("R")),
                "π -42 (R)"
            );
        }

        @Test
        @DisplayName("Pretty prints unary minus on binary expression with parentheses")
        public void prettyPrintUnaryMinusBinary() {
            // -(a + b) must print with parens so it doesn't re-parse as (-a) + b
            assertPrettyPrints(
                project(
                        List.of(projected(unary(arith(attr("a"), ArithmeticOperator.PLUS, attr("b"))))),
                        rel("R")),
                "π -(a + b) (R)"
            );
        }
    }

    @Nested
    @DisplayName("Round-Trip: Parse → Print → Parse")
    class RoundTripTests {

        @Test
        @DisplayName("Round trip simple unary minus")
        public void roundTripSimpleUnaryMinus() {
            RelNode original = parse("π -42 (R)");
            String printed = original.prettyPrint();
            RelNode reparsed = parse(printed);
            assertThat(reparsed).isEqualTo(original);
        }

        @Test
        @DisplayName("Round trip complex expression with unary minus")
        public void roundTripComplexUnaryMinus() {
            RelNode original = parse("π -(a + b) (R)");
            String printed = original.prettyPrint();
            RelNode reparsed = parse(printed);
            assertThat(reparsed).isEqualTo(original);
        }
    }
}

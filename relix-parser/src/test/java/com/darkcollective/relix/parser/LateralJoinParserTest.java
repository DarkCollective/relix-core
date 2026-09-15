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

import com.darkcollective.relix.ast.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;

/**
 * Tests for parsing the {@code LATERAL} correlated TVF join syntax.
 *
 * <p>Syntax: {@code left LATERAL fn(arg, …)} where arguments may reference
 * columns from {@code left}.
 */
@DisplayName("LATERAL join parser")
final class LateralJoinParserTest extends ParserTestSupport {

    @Nested
    @DisplayName("Basic syntax")
    class Basic {

        @Test
        @DisplayName("single column reference as argument")
        void singleColumnArgument() {
            assertParsesTo("Customers LATERAL ordersFor(customer_id)",
                    lateral(
                            rel("Customers"),
                            "ordersFor",attr("customer_id")));
        }

        @Test
        @DisplayName("constant numeric argument")
        void constantArgument() {
            assertParsesTo("Customers LATERAL ordersFor(2)",
                    lateral(
                            rel("Customers"),
                            "ordersFor",num("2")));
        }

        @Test
        @DisplayName("zero arguments")
        void zeroArguments() {
            assertParsesTo("Customers LATERAL allOrders()",
                    lateral(
                            rel("Customers"),
                            "allOrders"));
        }

        @Test
        @DisplayName("multiple arguments")
        void multipleArguments() {
            assertParsesTo("Customers LATERAL inRange(lo, hi)",
                    lateral(
                            rel("Customers"),
                            "inRange",attr("lo"), attr("hi")));
        }

        @Test
        @DisplayName("arithmetic expression as argument")
        void arithmeticArgument() {
            assertParsesTo("T LATERAL f(n + 1)",
                    lateral(
                            rel("T"),
                            "f",arith(attr("n"), ArithmeticOperator.PLUS, num("1"))));
        }
    }

    @Nested
    @DisplayName("Composition with other operators")
    class Composition {

        @Test
        @DisplayName("selection applied to LATERAL output")
        void selectionOnLateral() {
            assertParsesTo("σ amount > 50 (Customers LATERAL ordersFor(customer_id))",
                    select(
                            cmp(attr("amount"), ComparisonOperator.GREATER, num("50")),
                            lateral(
                                    rel("Customers"),
                                    "ordersFor",attr("customer_id"))));
        }

        @Test
        @DisplayName("projection applied to LATERAL output")
        void projectionOnLateral() {
            assertParsesTo("π name, amount (Customers LATERAL ordersFor(customer_id))",
                    project(
                            List.of(projected(attr("name")), projected(attr("amount"))),
                            lateral(
                                    rel("Customers"),
                                    "ordersFor",attr("customer_id"))));
        }

        @Test
        @DisplayName("LATERAL is left-associative with joins: (A ⋈ B) LATERAL f(x)")
        void leftAssociativeWithJoin() {
            // LATERAL shares the same precedence as ⋈ and is left-associative:
            // A ⋈ B LATERAL f(x) → (A ⋈ B) LATERAL f(x)
            assertParsesTo("A ⋈ B LATERAL f(x)",
                    lateral(
                            naturalJoin(
                                    rel("A"),
                                    rel("B")),
                            "f",attr("x")));
        }

        @Test
        @DisplayName("left side can be a parenthesised subexpression")
        void parenthesisedLeft() {
            assertParsesTo("(A ⋈ B) LATERAL f(x)",
                    lateral(
                            naturalJoin(
                                    rel("A"),
                                    rel("B")),
                            "f",attr("x")));
        }
    }

    @Nested
    @DisplayName("Parse errors")
    class Errors {

        @Test
        @DisplayName("missing opening paren after function name is an error")
        void missingOpenParen() {
            assertParseError("Customers LATERAL ordersFor")
                    .hasMessageContaining("'(' after function name in LATERAL");
        }

        @Test
        @DisplayName("missing closing paren is an error")
        void missingCloseParen() {
            assertParseError("Customers LATERAL ordersFor(customer_id")
                    .hasMessageContaining("')' after LATERAL function arguments");
        }

        @Test
        @DisplayName("missing function name after LATERAL is an error")
        void missingFunctionName() {
            assertParseError("Customers LATERAL (customer_id)")
                    .hasMessageContaining("table-valued function name after");
        }
    }
}

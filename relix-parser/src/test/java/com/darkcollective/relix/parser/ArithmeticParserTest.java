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


final class ArithmeticParserTest extends ParserTestSupport {

    @Test
    public void parsesArithmeticAddition() {
        assertParsesTo("σ price + 10 = 100 (Products)",
                select(cmp(arith(attr("price"), ArithmeticOperator.PLUS, num("10")), ComparisonOperator.EQUAL, num("100")), rel("Products")));
    }

    @Test
    public void parsesArithmeticSubtraction() {
        assertParsesTo("σ price - 5 = 95 (Products)",
                select(cmp(arith(attr("price"), ArithmeticOperator.MINUS, num("5")), ComparisonOperator.EQUAL, num("95")), rel("Products")));
    }

    @Test
    public void parsesArithmeticMultiplication() {
        assertParsesTo("σ price * 1.05 > 100 (Products)",
                select(cmp(arith(attr("price"), ArithmeticOperator.MULTIPLY, num("1.05")), ComparisonOperator.GREATER, num("100")), rel("Products")));
    }

    @Test
    public void parsesArithmeticDivision() {
        assertParsesTo("σ salary / 12 ≥ 5000 (Employees)",
                select(cmp(arith(attr("salary"), ArithmeticOperator.DIVIDE, num("12")), ComparisonOperator.GREATER_EQUAL, num("5000")), rel("Employees")));
    }

    @Test
    public void multiplicationBindsTighterThanAddition() {
        assertParsesTo("σ a + b * c = 10 (R)",
                select(cmp(arith(attr("a"), ArithmeticOperator.PLUS, arith(attr("b"), ArithmeticOperator.MULTIPLY, attr("c"))), ComparisonOperator.EQUAL, num("10")), rel("R")));
    }

    @Test
    public void divisionBindsTighterThanSubtraction() {
        assertParsesTo("σ a - b / c = 5 (R)",
                select(cmp(arith(attr("a"), ArithmeticOperator.MINUS, arith(attr("b"), ArithmeticOperator.DIVIDE, attr("c"))), ComparisonOperator.EQUAL, num("5")), rel("R")));
    }

    @Test
    public void parenthesesOverrideArithmeticPrecedence() {
        assertParsesTo("σ (a + b) * c = 20 (R)",
                select(cmp(arith(arith(attr("a"), ArithmeticOperator.PLUS, attr("b")), ArithmeticOperator.MULTIPLY, attr("c")), ComparisonOperator.EQUAL, num("20")), rel("R")));
    }

    @Test
    public void parsesArithmeticInProjection() {
        assertParsesTo("π price * 1.05 (Products)",
                project(List.of(projected(arith(attr("price"), ArithmeticOperator.MULTIPLY, num("1.05")))), rel("Products")));
    }

    @Test
    public void parsesParenthesizedArithmeticInProjection() {
        assertParsesTo("π (price + tax) * 0.8 (Products)",
                project(List.of(projected(arith(arith(attr("price"), ArithmeticOperator.PLUS, attr("tax")), ArithmeticOperator.MULTIPLY, num("0.8")))), rel("Products")));
    }

    @Test
    public void parsesMultipleProjectionExpressions() {
        assertParsesTo("π price * qty, tax + fee (Orders)",
                project(List.of(projected(arith(attr("price"), ArithmeticOperator.MULTIPLY, attr("qty"))), projected(arith(attr("tax"), ArithmeticOperator.PLUS, attr("fee")))), rel("Orders")));
    }

    @Test
    public void parsesComplexArithmeticExpression() {
        assertParsesTo("σ (price + tax) * 0.8 > 50 (Products)",
                select(cmp(arith(arith(attr("price"), ArithmeticOperator.PLUS, attr("tax")), ArithmeticOperator.MULTIPLY, num("0.8")), ComparisonOperator.GREATER, num("50")), rel("Products")));
    }
}

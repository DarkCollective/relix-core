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

import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.RelNode;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Tests for a bound query parameter, {@code $name}, in operand position.
 */
final class ParameterParserTest extends ParserTestSupport {

    @Test
    void parsesAParameterInAComparison() {
        assertParsesTo("σ order_id = $id (Orders)",
                select(cmp(attr("order_id"), ComparisonOperator.EQUAL, param("id")),
                        rel("Orders")));
    }

    @Test
    void parsesAParameterWhereverAnOperandGoes() {
        assertParsesTo("π total * $rate → taxed (Orders)",
                project(List.of(projected(
                        arith(attr("total"), ArithmeticOperator.MULTIPLY, param("rate")),
                        "taxed")), rel("Orders")));
    }

    @Test
    void keepsTheNameAsWrittenAndPrintsItBack() {
        RelNode node = select(cmp(param("Since_2"), ComparisonOperator.LESS, attr("at")),
                rel("Events"));
        assertParsesTo("σ $Since_2 < at (Events)", node);
        assertPrettyPrints(node, "σ $Since_2 < at (Events)");
        assertParsesTo(node.prettyPrint(), node);
    }

    @Test
    void aParameterMayShareAColumnsName() {
        assertParsesTo("σ id = $id (R)",
                select(cmp(attr("id"), ComparisonOperator.EQUAL, param("id")), rel("R")));
    }

    @Test
    void readsANameThatStartsWithAnUnderscoreOrEndsTheInput() {
        assertParsesTo("σ $_x = 1 (R)",
                select(cmp(param("_x"), ComparisonOperator.EQUAL, num("1")), rel("R")));
        assertParseError("σ id = $id").hasMessageContaining("Expected '('");
    }

    @Test
    void failsOnADollarWithNoName() {
        assertParseError("σ id = $ (R)").hasMessageContaining("Expected a parameter name after '$'");
        assertParseError("σ id = $ id (R)").hasMessageContaining("Expected a parameter name after '$'");
        assertParseError("σ id = $1 (R)").hasMessageContaining("Expected a parameter name after '$'");
        assertParseError("σ id = $").hasMessageContaining("Expected a parameter name after '$'");
    }
}

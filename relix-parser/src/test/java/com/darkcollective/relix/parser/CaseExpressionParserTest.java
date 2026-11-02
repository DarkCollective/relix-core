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

import com.darkcollective.relix.ast.ComparisonOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;

/** SQL's CASE expression, in both its forms, read as the Switch it means (#257). */
final class CaseExpressionParserTest extends ParserTestSupport {

    @Test
    @DisplayName("a searched CASE is Switch over its conditions, with the ELSE as the default")
    void searchedCase() {
        assertParsesTo("PROJECT id, CASE WHEN v > 1 THEN 'big' WHEN v > 0 THEN 'mid' ELSE 'small' END -> size (R)",
                project(List.of(projected(attr("id")), projected(func("Switch",
                        condition(cmp(attr("v"), ComparisonOperator.GREATER, num("1"))), str("big"),
                        condition(cmp(attr("v"), ComparisonOperator.GREATER, num("0"))), str("mid"),
                        str("small")), "size")), rel("R")));
    }

    @Test
    @DisplayName("one WHEN is Switch too, not IIf, so a NULL condition takes the ELSE as in SQL")
    void singleWhenIsSwitch() {
        assertParsesTo("PROJECT CASE WHEN v > 1 THEN 1 ELSE 0 END -> k (R)",
                project(List.of(projected(func("Switch",
                        condition(cmp(attr("v"), ComparisonOperator.GREATER, num("1"))), num("1"),
                        num("0")), "k")), rel("R")));
    }

    @Test
    @DisplayName("a CASE with no ELSE has no default, so an unmatched row is NULL")
    void noElse() {
        assertParsesTo("PROJECT CASE WHEN v > 1 THEN 'big' END -> size (R)",
                project(List.of(projected(func("Switch",
                        condition(cmp(attr("v"), ComparisonOperator.GREATER, num("1"))), str("big")),
                        "size")), rel("R")));
    }

    @Test
    @DisplayName("a simple CASE compares its subject with each WHEN value")
    void simpleCase() {
        assertParsesTo("PROJECT CASE kind WHEN 'a' THEN 1 WHEN 'b' THEN 2 ELSE 0 END -> k (R)",
                project(List.of(projected(func("Switch",
                        condition(cmp(attr("kind"), ComparisonOperator.EQUAL, str("a"))), num("1"),
                        condition(cmp(attr("kind"), ComparisonOperator.EQUAL, str("b"))), num("2"),
                        num("0")), "k")), rel("R")));
    }

    @Test
    @DisplayName("a simple CASE's subject may be an expression")
    void simpleCaseOverAnExpression() {
        assertParsesTo("PROJECT CASE a + b WHEN 2 THEN 'two' END -> k (R)",
                project(List.of(projected(func("Switch",
                        condition(cmp(arith(attr("a"), com.darkcollective.relix.ast.ArithmeticOperator.PLUS,
                                attr("b")), ComparisonOperator.EQUAL, num("2"))), str("two")), "k")),
                        rel("R")));
    }

    @Test
    @DisplayName("a CASE is an operand wherever one goes: a condition, a sort key, an aggregate")
    void caseIsAnOperand() {
        assertParsesTo("SELECT CASE WHEN v > 1 THEN 1 ELSE 0 END = 1 (R)",
                select(cmp(func("Switch",
                        condition(cmp(attr("v"), ComparisonOperator.GREATER, num("1"))), num("1"), num("0")),
                        ComparisonOperator.EQUAL, num("1")), rel("R")));
        parse("SORT CASE WHEN v > 1 THEN 0 ELSE 1 END, v (R)");
        parse("GROUP CASE WHEN v > 1 THEN 'big' ELSE 'small' END -> size, COUNT(*) -> n (R)");
        parse("GROUP SUM(CASE WHEN v > 1 THEN v ELSE 0 END) -> total (R)");
        parse("PROJECT CASE WHEN v > 1 THEN CASE WHEN v > 9 THEN 'huge' ELSE 'big' END ELSE 'small' END -> s (R)");
    }
}

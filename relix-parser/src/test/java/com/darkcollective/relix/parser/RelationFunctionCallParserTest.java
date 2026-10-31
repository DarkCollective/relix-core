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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.darkcollective.relix.ast.*;
import com.darkcollective.relix.ast.internal.*;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;

/**
 * Tests for parsing table-valued (relation-returning) function calls in relation
 * position, e.g. {@code recentOrders(2)}.
 */
final class RelationFunctionCallParserTest extends ParserTestSupport {

    @Test
    void parsesCallWithSingleNumericArgument() {
        assertParsesTo("recentOrders(2)",
                tvf("recentOrders",num("2")));
    }

    @Test
    void parsesCallWithNoArguments() {
        assertParsesTo("activeUsers()",
                tvf("activeUsers"));
    }

    @Test
    void parsesCallWithMultipleArguments() {
        assertParsesTo("inRange(2, 8)",
                tvf("inRange",num("2"), num("8")));
    }

    @Test
    void parsesCallWithExpressionArgument() {
        assertParsesTo("scaled(1 + 1)",
                tvf("scaled",arith(num("1"), ArithmeticOperator.PLUS, num("1"))));
    }

    @Test
    void parsesCallWithStringArgument() {
        assertParsesTo("byStatus(\"active\")",
                tvf("byStatus",str("active")));
    }

    @Test
    void parsesCallAsSelectionInput() {
        assertParsesTo("σ amount > 10 (ordersFor(2))",
                select(
                        cmp(attr("amount"), ComparisonOperator.GREATER, num("10")),
                        tvf("ordersFor",num("2"))));
    }

    @Test
    void parsesCallAsJoinOperand() {
        assertParsesTo("Customers ⋈ ordersFor(2)",
                naturalJoin(
                        rel("Customers"),
                        tvf("ordersFor",num("2"))));
    }

    @Test
    void bareRelationReferenceIsStillARelationNode() {
        assertParsesTo("Orders", rel("Orders"));
    }

    @Test
    @DisplayName("an expression as an argument is told to be named as a view first (#247)")
    void expressionArgumentIsNamedAsAView() {
        assertParseError("out_degree(σ kind = \"blocks\" (RENAME (from_issue -> src, to_issue -> dst) (issue_links)))")
                .hasMessageContaining("out_degree takes a relation's name as its argument, not an expression:"
                        + " name the expression as a view first, Input := { σ kind = \"blocks\""
                        + " (RENAME (from_issue -> src, to_issue -> dst) (issue_links)) }; then out_degree(Input)")
                .at(1, 12);
        // In parentheses, beside other arguments, and as an operator's input.
        assertParseError("out_degree((σ kind = \"blocks\" (E)))")
                .hasMessageContaining("Input := { σ kind = \"blocks\" (E) }; then out_degree(Input)");
        assertParseError("σ n > 1 (top_n(2, A ∪ B))")
                .hasMessageContaining("top_n takes a relation's name as its argument, not an expression:"
                        + " name the expression as a view first, Input := { A ∪ B }; then top_n(2, Input)")
                .at(1, 19);
        // Parentheses that do not hold the whole argument stay in it.
        assertParseError("f((A) ∪ B)").hasMessageContaining("Input := { (A) ∪ B }; then f(Input)");
        assertParseError("f((A) ∪ (B))").hasMessageContaining("Input := { (A) ∪ (B) }; then f(Input)");
        // Each suggested view parses.
        assertParsesTo("σ kind = \"blocks\" (RENAME (from_issue -> src, to_issue -> dst) (issue_links))",
                parse("σ kind = \"blocks\" (RENAME (from_issue -> src, to_issue -> dst) (issue_links))"));
        // A name, a call, a scalar expression or a column stays an argument.
        assertParsesTo("f(Blocks)", tvf("f", attr("Blocks")));
        assertParsesTo("f(Len(x), t.a, -1, (2))", parse("f(Len(x), t.a, -1, (2))"));
        // An argument that fails as a relation too keeps its own error.
        assertParseError("f(SELECT a FROM T)").hasMessageNotContaining("name the expression");
    }

    @Test
    void unterminatedArgumentListIsAParseError() {
        assertParseError("recentOrders(2").hasMessageContaining(")");
    }
}

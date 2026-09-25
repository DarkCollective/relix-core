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
    void unterminatedArgumentListIsAParseError() {
        assertParseError("recentOrders(2").hasMessageContaining(")");
    }
}

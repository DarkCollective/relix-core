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


final class JoinParserTest extends ParserTestSupport {

    @Test
    public void parsesNaturalJoin() {
        assertParsesTo("Users ⋈ Orders",
                naturalJoin(rel("Users"), rel("Orders")));
    }

    @Test
    public void parsesThetaJoin() {
        assertParsesTo("Users ⨝ Users.id = Orders.user_id Orders",
                join(rel("Users"), rel("Orders"), cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id"))));
    }

    @Test
    public void parsesLeftOuterJoin() {
        assertParsesTo("Users ⟕ Users.id = Orders.user_id Orders",
                leftJoin(rel("Users"), rel("Orders"), cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id"))));
    }

    @Test
    public void parsesRightOuterJoin() {
        assertParsesTo("Users ⟖ Users.id = Orders.user_id Orders",
                rightJoin(rel("Users"), rel("Orders"), cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id"))));
    }

    @Test
    public void parsesFullOuterJoin() {
        assertParsesTo("Users ⟗ Users.id = Orders.user_id Orders",
                fullJoin(rel("Users"), rel("Orders"), cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id"))));
    }

    @Test
    public void parsesLeftOuterJoinWithComplexPredicate() {
        assertParsesTo("Users ⟕ Users.id = Orders.user_id ∧ Orders.total > 100 Orders",
                leftJoin(rel("Users"), rel("Orders"), and(cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id")), cmp(attr("Orders.total"), ComparisonOperator.GREATER, num("100")))));
    }

    @Test
    public void parsesRightOuterJoinWithComplexPredicate() {
        assertParsesTo("Users ⟖ Users.id = Orders.user_id ∧ Orders.total > 100 Orders",
                rightJoin(rel("Users"), rel("Orders"), and(cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id")), cmp(attr("Orders.total"), ComparisonOperator.GREATER, num("100")))));
    }

    @Test
    public void parsesFullOuterJoinWithComplexPredicate() {
        assertParsesTo("Users ⟗ Users.id = Orders.user_id ∧ Orders.total > 100 Orders",
                fullJoin(rel("Users"), rel("Orders"), and(cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id")), cmp(attr("Orders.total"), ComparisonOperator.GREATER, num("100")))));
    }

    @Test
    public void parsesThetaJoinWithComplexPredicate() {
        assertParsesTo("Users ⨝ Users.id = Orders.user_id ∧ Orders.total > 100 Orders",
                join(rel("Users"), rel("Orders"), and(cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id")), cmp(attr("Orders.total"), ComparisonOperator.GREATER, num("100")))));
    }

    @Test
    public void parsesThetaJoinWithParenthesizedPredicate() {
        assertParsesTo("Users ⨝ (Users.id = Orders.user_id ∧ Orders.total > 100) Orders",
                join(rel("Users"), rel("Orders"), and(cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id")), cmp(attr("Orders.total"), ComparisonOperator.GREATER, num("100")))));
    }
}

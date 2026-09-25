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


final class SemiAntiJoinParserTest extends ParserTestSupport {

    // ==================== Semi-Join Tests ====================

    @Test
    public void parsesSemiJoinWithSimplePredicate() {
        assertParsesTo("Users ⋉ Users.id = Orders.user_id Orders",
                semiJoin(rel("Users"), rel("Orders"), cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id"))));
    }

    @Test
    public void parsesSemiJoinWithQualifiedRelationNames() {
        assertParsesTo("schema.Users ⋉ Users.id = Orders.user_id schema.Orders",
                semiJoin(rel("schema.Users"), rel("schema.Orders"), cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id"))));
    }

    @Test
    public void parsesSemiJoinWithComplexPredicate() {
        assertParsesTo("Users ⋉ Users.id = Orders.user_id ∧ Orders.total > 100 Orders",
                semiJoin(rel("Users"), rel("Orders"), and(cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id")), cmp(attr("Orders.total"), ComparisonOperator.GREATER, num("100")))));
    }

    @Test
    public void parsesSemiJoinWithParenthesizedPredicate() {
        assertParsesTo("Users ⋉ (Users.id = Orders.user_id ∧ Orders.total > 100) Orders",
                semiJoin(rel("Users"), rel("Orders"), and(cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id")), cmp(attr("Orders.total"), ComparisonOperator.GREATER, num("100")))));
    }

    @Test
    public void parsesSemiJoinWithOrPredicate() {
        assertParsesTo("A ⋉ A.x = B.x ∨ A.y = B.y B",
                semiJoin(rel("A"), rel("B"), or(cmp(attr("A.x"), ComparisonOperator.EQUAL, attr("B.x")), cmp(attr("A.y"), ComparisonOperator.EQUAL, attr("B.y")))));
    }

    @Test
    public void parsesSemiJoinWithNotPredicate() {
        assertParsesTo("Users ⋉ ¬(Users.id = Orders.user_id) Orders",
                semiJoin(rel("Users"), rel("Orders"), not(cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id")))));
    }

    // ==================== Anti-Join Tests ====================

    @Test
    public void parsesAntiJoinWithSimplePredicate() {
        assertParsesTo("Users ▷ Users.id = Orders.user_id Orders",
                antiJoin(rel("Users"), rel("Orders"), cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id"))));
    }

    @Test
    public void parsesAntiJoinWithQualifiedRelationNames() {
        assertParsesTo("schema.Users ▷ Users.id = Orders.user_id schema.Orders",
                antiJoin(rel("schema.Users"), rel("schema.Orders"), cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id"))));
    }

    @Test
    public void parsesAntiJoinWithComplexPredicate() {
        assertParsesTo("Users ▷ Users.id = Orders.user_id ∧ Orders.status = \"cancelled\" Orders",
                antiJoin(rel("Users"), rel("Orders"), and(cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id")), cmp(attr("Orders.status"), ComparisonOperator.EQUAL, str("cancelled")))));
    }

    @Test
    public void parsesAntiJoinWithParenthesizedPredicate() {
        assertParsesTo("Users ▷ (Users.id = Orders.user_id ∧ Orders.status = \"cancelled\") Orders",
                antiJoin(rel("Users"), rel("Orders"), and(cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id")), cmp(attr("Orders.status"), ComparisonOperator.EQUAL, str("cancelled")))));
    }

    @Test
    public void parsesAntiJoinWithOrPredicate() {
        assertParsesTo("A ▷ A.x = B.x ∨ A.y = B.y B",
                antiJoin(rel("A"), rel("B"), or(cmp(attr("A.x"), ComparisonOperator.EQUAL, attr("B.x")), cmp(attr("A.y"), ComparisonOperator.EQUAL, attr("B.y")))));
    }

    // ==================== Precedence Tests ====================

    @Test
    public void semiJoinAndAntiJoinHaveSamePrecedenceAsOtherJoins() {
        // Both should bind before union
        assertParsesTo("A ⋉ A.id = B.id B ∪ C",
                union(
                        semiJoin(rel("A"), rel("B"), cmp(attr("A.id"), ComparisonOperator.EQUAL, attr("B.id"))),
                        rel("C")));
    }

    @Test
    public void antiJoinPrecedenceWithUnion() {
        assertParsesTo("A ▷ A.id = B.id B ∪ C",
                union(
                        antiJoin(rel("A"), rel("B"), cmp(attr("A.id"), ComparisonOperator.EQUAL, attr("B.id"))),
                        rel("C")));
    }

    @Test
    public void semiJoinAndProductPrecedence() {
        assertParsesTo("(A ⋉ A.id = B.id B) × C",
                product(
                        semiJoin(rel("A"), rel("B"), cmp(attr("A.id"), ComparisonOperator.EQUAL, attr("B.id"))),
                        rel("C")));
    }

    @Test
    public void parenthesesOverrideSemiJoinPrecedence() {
        assertParsesTo("(A ⋉ A.id = B.id B) ∪ C",
                union(
                        semiJoin(rel("A"), rel("B"), cmp(attr("A.id"), ComparisonOperator.EQUAL, attr("B.id"))),
                        rel("C")));
    }

    @Test
    public void parenthesesOverrideAntiJoinPrecedence() {
        assertParsesTo("(A ▷ A.id = B.id B) ∪ C",
                union(
                        antiJoin(rel("A"), rel("B"), cmp(attr("A.id"), ComparisonOperator.EQUAL, attr("B.id"))),
                        rel("C")));
    }

    // ==================== Complex Input Tests ====================

    @Test
    public void semiJoinWithProjectionInputs() {
        assertParsesTo("π id (Users) ⋉ Users.id = Orders.user_id π user_id (Orders)",
                semiJoin(
                        project(java.util.List.of(projected(attr("id"))), rel("Users")),
                        project(java.util.List.of(projected(attr("user_id"))), rel("Orders")),
                        cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id"))));
    }

    @Test
    public void antiJoinWithSelectionInputs() {
        assertParsesTo("σ active = true (Users) ▷ Users.id = Orders.user_id σ status ≠ \"cancelled\" (Orders)",
                antiJoin(
                        select(cmp(attr("active"), ComparisonOperator.EQUAL, bool(true)), rel("Users")),
                        select(cmp(attr("status"), ComparisonOperator.NOT_EQUAL, str("cancelled")), rel("Orders")),
                        cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id"))));
    }

    @Test
    public void semiJoinWithRenameInputs() {
        assertParsesTo("ρ U(id) (Users) ⋉ U.id = O.user_id ρ O(user_id) (Orders)",
                semiJoin(
                        rename("U", java.util.List.of("id"), rel("Users")),
                        rename("O", java.util.List.of("user_id"), rel("Orders")),
                        cmp(attr("U.id"), ComparisonOperator.EQUAL, attr("O.user_id"))));
    }

    // ==================== Pretty Printing Tests ====================

    @Test
    public void prettyPrintsSemiJoin() {
        RelNode node = semiJoin(rel("Users"), rel("Orders"), cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id")));
        assertPrettyPrints(node, "(Users) ⋉ Users.id = Orders.user_id (Orders)");
    }

    @Test
    public void prettyPrintsAntiJoin() {
        RelNode node = antiJoin(rel("Users"), rel("Orders"), cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id")));
        assertPrettyPrints(node, "(Users) ▷ Users.id = Orders.user_id (Orders)");
    }

    @Test
    public void prettyPrintsSemiJoinRoundTrip() {
        RelNode original = semiJoin(rel("A"), rel("B"), cmp(attr("A.id"), ComparisonOperator.EQUAL, attr("B.id")));
        RelNode reparsed = parse(original.prettyPrint());
        assertParsesTo(original.prettyPrint(), reparsed);
    }

    @Test
    public void prettyPrintsAntiJoinRoundTrip() {
        RelNode original = antiJoin(rel("A"), rel("B"), cmp(attr("A.id"), ComparisonOperator.EQUAL, attr("B.id")));
        RelNode reparsed = parse(original.prettyPrint());
        assertParsesTo(original.prettyPrint(), reparsed);
    }

    // ==================== Error Cases ====================

    @Test
    public void failsOnMissingPredicateInSemiJoin() {
        assertParseError("Users ⋉ Orders")
                .hasMessageContaining("'⋉' needs a join condition")
                .at(1, 7);
    }

    @Test
    public void failsOnMissingRightOperandInSemiJoin() {
        assertParseError("Users ⋉ Users.id = Orders.user_id")
                .hasMessageContaining("Expected relation name");
    }

    @Test
    public void failsOnMissingPredicateInAntiJoin() {
        assertParseError("Users ▷ Orders")
                .hasMessageContaining("'▷' needs a join condition")
                .at(1, 7);
    }

    @Test
    public void failsOnMissingRightOperandInAntiJoin() {
        assertParseError("Users ▷ Users.id = Orders.user_id")
                .hasMessageContaining("Expected relation name");
    }

    @Test
    public void failsOnUnclosedParenthesizedSemiJoin() {
        assertParseError("Users ⋉ (Users.id = Orders.user_id Orders")
                .hasMessageContaining("Expected ')' after predicate");
    }
}


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

    // ==================== JOIN … USING (#134) ====================

    @org.junit.jupiter.api.Test
    public void usingBetweenTheInputs() {
        assertParsesTo("Orders ⟕ USING (customer_id) Customers",
                AstBuilders.usingJoin(AstBuilders.rel("Orders"), AstBuilders.rel("Customers"), UsingJoinNode.Kind.LEFT, "customer_id"));
        assertParsesTo("A |><| USING (a, b) B",
                AstBuilders.usingJoin(AstBuilders.rel("A"), AstBuilders.rel("B"), UsingJoinNode.Kind.FULL, "a", "b"));
        assertParsesTo("A JOIN USING (a) B",
                AstBuilders.usingJoin(AstBuilders.rel("A"), AstBuilders.rel("B"), UsingJoinNode.Kind.INNER, "a"));
    }

    @org.junit.jupiter.api.Test
    public void usingAfterTheRightInputAsSqlWritesIt() {
        assertParsesTo("A JOIN B USING (a, b)", parse("A ⋈ USING (a, b) B"));
        assertParsesTo("A LJOIN B USING (a)", parse("A ⟕ USING (a) B"));
        assertParsesTo("A RJOIN B using (a)", parse("A ⟖ USING (a) B"));
        assertParsesTo("A FJOIN (σ x = 1 (B)) USING (a)", parse("A ⟗ USING (a) σ x = 1 (B)"));
        assertParsesTo("A ⟕ σ x = 1 (B) USING (a)", parse("A ⟕ USING (a) σ x = 1 (B)"));
        assertParsesTo("A ⋈ σ x = 1 (B) USING (a)", parse("A ⋈ USING (a) σ x = 1 (B)"));
    }

    @org.junit.jupiter.api.Test
    public void usingChainsLeftToRight() {
        assertParsesTo("A JOIN B USING (a) JOIN C USING (c)",
                AstBuilders.usingJoin(AstBuilders.usingJoin(AstBuilders.rel("A"), AstBuilders.rel("B"), UsingJoinNode.Kind.INNER, "a"), AstBuilders.rel("C"), UsingJoinNode.Kind.INNER, "c"));
    }

    @org.junit.jupiter.api.Test
    public void aConditionIsStillACondition() {
        assertParsesTo("A ⟕ A.id = B.id B",
                AstBuilders.leftJoin(AstBuilders.rel("A"), AstBuilders.rel("B"),
                        AstBuilders.cmp(AstBuilders.attr("A.id"), ComparisonOperator.EQUAL, AstBuilders.attr("B.id"))));
    }

    @org.junit.jupiter.api.Test
    public void usingErrors() {
        assertParseError("A JOIN B USING ()").hasMessageContaining("Expected a column name in USING");
        assertParseError("A JOIN B USING (a, a)").hasMessageContaining("USING names column 'a' twice");
        assertParseError("A ⟕ USING a B");
        assertParseError("A ⨝ USING (a) B");
    }

    @org.junit.jupiter.api.Test
    public void usingRoundTrips() {
        org.assertj.core.api.Assertions.assertThat(parse("A LJOIN B USING (a, b)").prettyPrint())
                .isEqualTo("(A) ⟕ USING (a, b) (B)");
        assertParsesTo(parse("A LJOIN B USING (a, b)").prettyPrint(), parse("A ⟕ USING (a, b) B"));
    }

    @org.junit.jupiter.api.Test
    public void everyConditionShapeIsStillReadAsACondition() {
        for (String condition : java.util.List.of("x = y", "NOT (x = y)", "x IS NULL", "x LIKE 'a%'",
                "x ∈ {1}", "x ∉ {1}", "x != y AND y = 1")) {
            AstAssertions.assertThat(parse("A ⟕ " + condition + " B")).isNode(LeftOuterJoinNode.class);
        }
        // A right input that is no condition, and no USING after it, is a missing condition.
        assertParseError("A ⟕ B ⋈ C").hasMessageContaining("needs a join condition");
        assertParseError("(A ⟕ B)").hasMessageContaining("needs a join condition");
        assertParseError("A ⟕ B").hasMessageContaining("needs a join condition");
    }

    @org.junit.jupiter.api.Test
    public void aNaturalJoinsRightInputMayHoldAComparisonBeforeUsing() {
        // FORALL opens with no unary-operator token, so its comparison is met first.
        assertParsesTo("A ⋈ FORALL k : x > 0 (R) USING (k)",
                AstBuilders.usingJoin(AstBuilders.rel("A"), parse("FORALL k : x > 0 (R)"),
                        UsingJoinNode.Kind.INNER, "k"));
    }
}

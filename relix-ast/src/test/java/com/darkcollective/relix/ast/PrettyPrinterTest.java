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
package com.darkcollective.relix.ast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.darkcollective.relix.ast.*;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;


@DisplayName("PrettyPrinter — AST to relational algebra string")
final class PrettyPrinterTest extends AstTestSupport {

    @Test
    @DisplayName("Pretty-prints left outer join")
    public void prettyPrintsLeftOuterJoin() {
        RelNode node = leftJoin(rel("Users"), rel("Orders"), cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id")));

        assertPrettyPrints(node, "(Users) ⟕ Users.id = Orders.user_id (Orders)");
    }

    @Test
    @DisplayName("Pretty-prints right outer join")
    public void prettyPrintsRightOuterJoin() {
        RelNode node = rightJoin(rel("Users"), rel("Orders"), cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id")));

        assertPrettyPrints(node, "(Users) ⟖ Users.id = Orders.user_id (Orders)");
    }

    @Test
    @DisplayName("Pretty-prints full outer join")
    public void prettyPrintsFullOuterJoin() {
        RelNode node = fullJoin(rel("Users"), rel("Orders"), cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id")));

        assertPrettyPrints(node, "(Users) ⟗ Users.id = Orders.user_id (Orders)");
    }

    @Test
    @DisplayName("Pretty-prints rename with attribute list")
    public void prettyPrintsRenameWithAttributes() {
        RelNode node = rename("U", List.of("id", "name"), rel("Users"));

        assertPrettyPrints(node, "ρ U (id, name) (Users)");
    }

    @Test
    @DisplayName("Pretty-prints rename with old → new pairs")
    public void prettyPrintsRenamePairs() {
        RelNode node = rename(java.util.Optional.of("U"), List.of(),
                List.of(new RenameNode.RenamePair("id", "uid"),
                        new RenameNode.RenamePair("name", "uname")),
                rel("Users"));

        assertPrettyPrints(node, "ρ U (id → uid, name → uname) (Users)");
    }

    @Test
    @DisplayName("Pretty-prints pair rename with no relation name")
    public void prettyPrintsRenamePairsNoRelationName() {
        RelNode node = rename(java.util.Optional.empty(), List.of(),
                List.of(new RenameNode.RenamePair("id", "uid")),
                rel("Users"));

        assertPrettyPrints(node, "ρ (id → uid) (Users)");
    }

    @Test
    @DisplayName("Pretty-prints selection with escaped string literal")
    public void prettyPrintsEscapedString() {
        RelNode node = select(cmp(attr("text"), ComparisonOperator.EQUAL, str("a\\b \"quoted\"\n\t")), rel("Messages"));

        assertPrettyPrints(node, "σ text = \"a\\\\b \\\"quoted\\\"\\n\\t\" (Messages)");
    }

    @Test
    @DisplayName("Pretty-prints arithmetic expression in selection")
    public void prettyPrintsArithmeticExpression() {
        RelNode node = select(cmp(arith(attr("price"), ArithmeticOperator.PLUS, num("10")), ComparisonOperator.EQUAL, num("100")), rel("Products"));

        assertPrettyPrints(node, "σ price + 10 = 100 (Products)");
    }

    @Test
    @DisplayName("Pretty-prints arithmetic respecting operator precedence (no unnecessary parens)")
    public void prettyPrintsArithmeticWithPrecedence() {
        RelNode node = select(cmp(arith(attr("a"), ArithmeticOperator.PLUS, arith(attr("b"), ArithmeticOperator.MULTIPLY, attr("c"))), ComparisonOperator.EQUAL, num("10")), rel("R"));

        assertPrettyPrints(node, "σ a + b * c = 10 (R)");
    }

    @Test
    @DisplayName("Pretty-prints arithmetic with parentheses for lower-precedence sub-expression")
    public void prettyPrintsArithmeticWithParentheses() {
        RelNode node = select(cmp(arith(arith(attr("a"), ArithmeticOperator.PLUS, attr("b")), ArithmeticOperator.MULTIPLY, attr("c")), ComparisonOperator.EQUAL, num("20")), rel("R"));

        assertPrettyPrints(node, "σ (a + b) * c = 20 (R)");
    }

    @Test
    @DisplayName("Pretty-prints aliased projection attribute")
    public void prettyPrintsAliasedProjection() {
        RelNode node = project(
                List.of(projected(attr("id"), "user_id")),
                rel("Users"));

        assertPrettyPrints(node, "π id → user_id (Users)");
    }

    @Test
    @DisplayName("Pretty-prints multiple aliased attributes in projection")
    public void prettyPrintsMultipleAliasedAttributes() {
        RelNode node = project(
                List.of(
                        projected(attr("id"), "user_id"),
                        projected(attr("name"), "user_name")
                ),
                rel("Users"));

        assertPrettyPrints(node, "π id → user_id, name → user_name (Users)");
    }

    @Test
    @DisplayName("Pretty-prints aliased arithmetic expression in projection")
    public void prettyPrintsAliasedArithmeticExpression() {
        RelNode node = project(
                List.of(projected(
                        arith(attr("price"), ArithmeticOperator.MULTIPLY, num("1.05")),
                        "adjusted_price"
                )),
                rel("Products"));

        assertPrettyPrints(node, "π price * 1.05 → adjusted_price (Products)");
    }

    @Test
    @DisplayName("Pretty-prints mixed aliased and simple projection attributes")
    public void prettyPrintsMixedAliasedAndSimpleAttributes() {
        RelNode node = project(
                List.of(
                        projected(attr("id")),
                        projected(attr("name"), "user_name"),
                        projected(arith(attr("salary"), ArithmeticOperator.DIVIDE, num("12")), "monthly")
                ),
                rel("Employees"));

        assertPrettyPrints(node, "π id, name → user_name, salary / 12 → monthly (Employees)");
    }

    @Test
    @DisplayName("Pretty-prints typed temporal literals (DATE/TIME/TIMESTAMP/DURATION)")
    public void prettyPrintsTemporalLiterals() {
        RelNode node = project(
                List.of(
                        projected(date("2026-06-15")),
                        projected(time("13:40")),
                        projected(timestamp("2026-06-15T13:40:00Z")),
                        projected(duration("PT30M"))),
                rel("R"));

        assertPrettyPrints(node,
                "π DATE '2026-06-15', TIME '13:40', TIMESTAMP '2026-06-15T13:40:00Z', DURATION 'PT30M' (R)");
    }

    @Test
    @DisplayName("Pretty-prints element-of predicate with string set")
    public void prettyPrintsElementOfPredicate() {
        RelNode node = select(elementOf(attr("status"), set(str("active"), str("pending"))), rel("Users"));

        assertPrettyPrints(node, "σ status ∈ {\"active\", \"pending\"} (Users)");
    }

    @Test
    @DisplayName("Pretty-prints not-element-of predicate")
    public void prettyPrintsNotElementOfPredicate() {
        RelNode node = select(notElementOf(attr("status"), set(str("inactive"), str("banned"))), rel("Users"));

        assertPrettyPrints(node, "σ status ∉ {\"inactive\", \"banned\"} (Users)");
    }

    @Test
    @DisplayName("Pretty-prints element-of predicate with numeric set")
    public void prettyPrintsElementOfPredicateWithNumbers() {
        RelNode node = select(elementOf(attr("age"), set(num("18"), num("21"), num("25"))), rel("Users"));

        assertPrettyPrints(node, "σ age ∈ {18, 21, 25} (Users)");
    }

    @Test
    @DisplayName("Pretty-prints element-of predicate with single-element set")
    public void prettyPrintsElementOfPredicateWithSingleElement() {
        RelNode node = select(elementOf(attr("role"), set(str("admin"))), rel("Users"));

        assertPrettyPrints(node, "σ role ∈ {\"admin\"} (Users)");
    }

    @Test
    @DisplayName("Pretty-prints element-of predicate with qualified attribute")
    public void prettyPrintsElementOfPredicateWithQualifiedAttribute() {
        RelNode node = select(elementOf(attr("Users.status"), set(str("active"))), rel("Users"));

        assertPrettyPrints(node, "σ Users.status ∈ {\"active\"} (Users)");
    }

    @Test
    @DisplayName("Pretty-prints or predicate with parenthesised sides")
    public void prettyPrintsOrPredicate() {
        RelNode node = select(
                or(
                        cmp(attr("age"), ComparisonOperator.GREATER, num("65")),
                        cmp(attr("status"), ComparisonOperator.EQUAL, str("vip"))
                ),
                rel("Users"));

        assertPrettyPrints(node, "σ (age > 65) ∨ (status = \"vip\") (Users)");
    }

    @Test
    @DisplayName("Pretty-prints null predicate (IS NULL)")
    public void prettyPrintsNullPredicateIsNull() {
        RelNode node = select(nullPred(attr("email"), true), rel("Users"));

        assertPrettyPrints(node, "σ email = ⊥ (Users)");
    }

    @Test
    @DisplayName("Pretty-prints null predicate (IS NOT NULL)")
    public void prettyPrintsNullPredicateIsNotNull() {
        RelNode node = select(nullPred(attr("email"), false), rel("Users"));

        assertPrettyPrints(node, "σ email ≠ ⊥ (Users)");
    }

    @Test
    @DisplayName("Pretty-prints not-equal comparison operator (≠)")
    public void prettyPrintsNotEqualOperator() {
        RelNode node = select(cmp(attr("status"), ComparisonOperator.NOT_EQUAL, str("inactive")), rel("Users"));

        assertPrettyPrints(node, "σ status ≠ \"inactive\" (Users)");
    }

    @Test
    @DisplayName("Pretty-prints less-than comparison operator (<)")
    public void prettyPrintsLessOperator() {
        RelNode node = select(cmp(attr("age"), ComparisonOperator.LESS, num("18")), rel("Users"));

        assertPrettyPrints(node, "σ age < 18 (Users)");
    }

    @Test
    @DisplayName("Pretty-prints less-than-or-equal comparison operator (≤)")
    public void prettyPrintsLessEqualOperator() {
        RelNode node = select(cmp(attr("price"), ComparisonOperator.LESS_EQUAL, num("100")), rel("Products"));

        assertPrettyPrints(node, "σ price ≤ 100 (Products)");
    }

    @Test
    @DisplayName("Pretty-prints greater-than-or-equal comparison operator (≥)")
    public void prettyPrintsGreaterEqualOperator() {
        RelNode node = select(cmp(attr("salary"), ComparisonOperator.GREATER_EQUAL, num("50000")), rel("Employees"));

        assertPrettyPrints(node, "σ salary ≥ 50000 (Employees)");
    }

    @Test
    @DisplayName("Pretty-prints unary negation of a simple attribute as -attr")
    public void prettyPrintsUnaryNegationSimple() {
        RelNode node = select(
                cmp(unary(attr("price")), ComparisonOperator.GREATER, num("0")),
                rel("Products"));

        assertPrettyPrints(node, "σ -price > 0 (Products)");
    }

    @Test
    @DisplayName("Pretty-prints unary negation of a binary expression with parentheses -(a + b)")
    public void prettyPrintsUnaryNegationOfBinaryExpr() {
        RelNode node = select(
                cmp(unary(arith(attr("a"), ArithmeticOperator.PLUS, attr("b"))),
                        ComparisonOperator.GREATER, num("0")),
                rel("R"));

        // -(a + b) > 0  — parentheses must appear around the binary sub-expression
        assertPrettyPrints(node, "σ -(a + b) > 0 (R)");
    }

    @Test
    @DisplayName("Pretty-prints subtraction (MINUS) operator")
    public void prettyPrintsSubtractionOperator() {
        RelNode node = select(
                cmp(arith(attr("price"), ArithmeticOperator.MINUS, num("5")),
                        ComparisonOperator.GREATER, num("0")),
                rel("Products"));

        assertPrettyPrints(node, "σ price - 5 > 0 (Products)");
    }

    @Test
    @DisplayName("Pretty-prints group-wise universal quantification (∀) with keys")
    public void prettyPrintsUniversalWithKeys() {
        RelNode node = universal(
                List.of("customer_id"),
                cmp(attr("status"), ComparisonOperator.EQUAL, str("done")),
                rel("Orders"));

        assertPrettyPrints(node, "∀ customer_id : status = \"done\" (Orders)");
    }

    @Test
    @DisplayName("Pretty-prints no-key whole-relation ∀ without a stray space")
    public void prettyPrintsUniversalNoKey() {
        RelNode node = universal(
                List.of(),
                cmp(attr("status"), ComparisonOperator.EQUAL, str("done")),
                rel("Orders"));

        assertPrettyPrints(node, "∀ : status = \"done\" (Orders)");
    }

    @Test
    @DisplayName("Backtick-delimits a relation name that collides with a reserved word")
    public void prettyPrintsReservedRelationNameDelimited() {
        assertPrettyPrints(rel("order"), "`order`");
    }

    @Test
    @DisplayName("Leaves an ordinary relation name un-delimited")
    public void prettyPrintsOrdinaryRelationNameBare() {
        assertPrettyPrints(rel("Orders"), "Orders");
    }

    @Test
    @DisplayName("Backtick-delimits a column reference that collides with a reserved word")
    public void prettyPrintsReservedColumnNameDelimited() {
        RelNode node = select(
                cmp(attr("order"), ComparisonOperator.EQUAL, num("1")),
                rel("Events"));

        assertPrettyPrints(node, "σ `order` = 1 (Events)");
    }

    @Test
    @DisplayName("Delimits only the reserved segment of a qualified column name")
    public void prettyPrintsQualifiedReservedSegment() {
        RelNode node = select(
                cmp(attr("order.total"), ComparisonOperator.GREATER, num("0")),
                rel("Sales"));

        assertPrettyPrints(node, "σ `order`.total > 0 (Sales)");
    }
}

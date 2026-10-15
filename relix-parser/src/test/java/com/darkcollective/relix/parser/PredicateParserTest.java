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

final class PredicateParserTest extends ParserTestSupport {

    @Test
    public void parsesSelectionWithGreaterThan() {
        assertParsesTo("σ age > 18 (Users)",
                select(cmp(attr("age"), ComparisonOperator.GREATER, num("18")), rel("Users")));
    }

    @Test
    public void parsesSelectionWithGreaterEqualAscii() {
        assertParsesTo("σ age >= 18 (Users)",
                select(cmp(attr("age"), ComparisonOperator.GREATER_EQUAL, num("18")), rel("Users")));
    }

    @Test
    public void parsesSelectionWithUnicodeGreaterEqual() {
        assertParsesTo("σ age ≥ 18 (Users)",
                select(cmp(attr("age"), ComparisonOperator.GREATER_EQUAL, num("18")), rel("Users")));
    }

    @Test
    public void parsesSelectionWithLessThan() {
        assertParsesTo("σ age < 18 (Users)",
                select(cmp(attr("age"), ComparisonOperator.LESS, num("18")), rel("Users")));
    }

    @Test
    public void parsesSelectionWithUnicodeLessEqual() {
        assertParsesTo("σ age ≤ 18 (Users)",
                select(cmp(attr("age"), ComparisonOperator.LESS_EQUAL, num("18")), rel("Users")));
    }

    @Test
    public void parsesSelectionWithLessEqualAscii() {
        assertParsesTo("σ age <= 18 (Users)",
                select(cmp(attr("age"), ComparisonOperator.LESS_EQUAL, num("18")), rel("Users")));
    }

    @Test
    public void parsesSelectionWithNotEqual() {
        assertParsesTo("σ status ≠ \"inactive\" (Users)",
                select(cmp(attr("status"), ComparisonOperator.NOT_EQUAL, str("inactive")), rel("Users")));
    }

    @Test
    public void parsesSelectionWithStringLiteral() {
        assertParsesTo("σ name = \"Alice\" (Users)",
                select(cmp(attr("name"), ComparisonOperator.EQUAL, str("Alice")), rel("Users")));
    }

    @Test
    public void parsesSelectionWithEscapedStringLiteral() {
        assertParsesTo("σ text = \"hello \\\"world\\\"\" (Messages)",
                select(cmp(attr("text"), ComparisonOperator.EQUAL, str("hello \"world\"")), rel("Messages")));
    }

    @Test
    public void parsesSelectionWithNumberLiteral() {
        assertParsesTo("σ total = 42 (Orders)",
                select(cmp(attr("total"), ComparisonOperator.EQUAL, num("42")), rel("Orders")));
    }

    @Test
    public void parsesSelectionWithDecimalNumberLiteral() {
        assertParsesTo("σ total = 42.50 (Orders)",
                select(cmp(attr("total"), ComparisonOperator.EQUAL, num("42.50")), rel("Orders")));
    }

    @Test
    public void parsesSelectionWithBooleanLiteral() {
        assertParsesTo("σ active = true (Users)",
                select(cmp(attr("active"), ComparisonOperator.EQUAL, bool(true)), rel("Users")));
    }

    @Test
    public void parsesSelectionWithFalseLiteral() {
        assertParsesTo("σ active = false (Users)",
                select(cmp(attr("active"), ComparisonOperator.EQUAL, bool(false)), rel("Users")));
    }

    @Test
    public void parsesSelectionWithQualifiedAttributes() {
        assertParsesTo("σ Users.id = Orders.user_id (Users)",
                select(cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id")), rel("Users")));
    }

    @Test
    public void parsesSelectionWithMultiQualifiedAttributes() {
        assertParsesTo("σ schema.table.id = db.schema.table.user_id (Users)",
                select(cmp(attr("schema.table.id"), ComparisonOperator.EQUAL, attr("db.schema.table.user_id")), rel("Users")));
    }

    @Test
    public void parsesAndPredicate() {
        assertParsesTo("σ age > 18 ∧ active = true (Users)",
                select(and(cmp(attr("age"), ComparisonOperator.GREATER, num("18")), cmp(attr("active"), ComparisonOperator.EQUAL, bool(true))), rel("Users")));
    }

    @Test
    public void parsesOrPredicate() {
        assertParsesTo("σ role = \"admin\" ∨ role = \"owner\" (Users)",
                select(or(cmp(attr("role"), ComparisonOperator.EQUAL, str("admin")), cmp(attr("role"), ComparisonOperator.EQUAL, str("owner"))), rel("Users")));
    }

    @Test
    public void parsesNotPredicate() {
        assertParsesTo("σ ¬ active = false (Users)",
                select(not(cmp(attr("active"), ComparisonOperator.EQUAL, bool(false))), rel("Users")));
    }

    @Test
    public void parsesParenthesizedPredicate() {
        assertParsesTo("σ (age > 18) (Users)",
                select(cmp(attr("age"), ComparisonOperator.GREATER, num("18")), rel("Users")));
    }

    @Test
    public void parsesNestedPredicate() {
        assertParsesTo("σ (age > 18 ∧ active = true) ∨ role = \"admin\" (Users)",
                select(or(and(cmp(attr("age"), ComparisonOperator.GREATER, num("18")), cmp(attr("active"), ComparisonOperator.EQUAL, bool(true))), cmp(attr("role"), ComparisonOperator.EQUAL, str("admin"))), rel("Users")));
    }

    @Test
    public void parsesNotParenthesizedPredicate() {
        assertParsesTo("σ ¬ (active = false ∨ role = \"guest\") (Users)",
                select(not(or(cmp(attr("active"), ComparisonOperator.EQUAL, bool(false)), cmp(attr("role"), ComparisonOperator.EQUAL, str("guest")))), rel("Users")));
    }

    @Test
    public void andBindsTighterThanOr() {
        assertParsesTo("σ a = 1 ∨ b = 2 ∧ c = 3 (R)",
                select(or(cmp(attr("a"), ComparisonOperator.EQUAL, num("1")), and(cmp(attr("b"), ComparisonOperator.EQUAL, num("2")), cmp(attr("c"), ComparisonOperator.EQUAL, num("3")))), rel("R")));
    }

    @Test
    public void parsesDoubleNotPredicate() {
        assertParsesTo("σ ¬ ¬ active = true (Users)",
                select(not(not(cmp(attr("active"), ComparisonOperator.EQUAL, bool(true)))), rel("Users")));
    }

    @Test
    public void andPredicateIsLeftAssociative() {
        assertParsesTo("σ a = 1 ∧ b = 2 ∧ c = 3 (R)",
                select(and(and(cmp(attr("a"), ComparisonOperator.EQUAL, num("1")), cmp(attr("b"), ComparisonOperator.EQUAL, num("2"))), cmp(attr("c"), ComparisonOperator.EQUAL, num("3"))), rel("R")));
    }

    @Test
    public void orPredicateIsLeftAssociative() {
        assertParsesTo("σ a = 1 ∨ b = 2 ∨ c = 3 (R)",
                select(or(or(cmp(attr("a"), ComparisonOperator.EQUAL, num("1")), cmp(attr("b"), ComparisonOperator.EQUAL, num("2"))), cmp(attr("c"), ComparisonOperator.EQUAL, num("3"))), rel("R")));
    }

    // ==================== Null Predicate Tests ====================

    @Test
    public void parsesIsNullPredicate() {
        assertParsesTo("σ name = ⊥ (Users)",
                select(nullPred(attr("name"), true), rel("Users")));
    }

    @Test
    public void parsesIsNotNullPredicate() {
        assertParsesTo("σ email ≠ ⊥ (Users)",
                select(nullPred(attr("email"), false), rel("Users")));
    }

    @Test
    public void parsesNullPredicateWithQualifiedAttribute() {
        assertParsesTo("σ Users.status = ⊥ (Users)",
                select(nullPred(attr("Users.status"), true), rel("Users")));
    }

    @Test
    public void parsesNullPredicateWithComplexExpression() {
        assertParsesTo("σ age > 18 ∧ status = ⊥ (Users)",
                select(and(cmp(attr("age"), ComparisonOperator.GREATER, num("18")), nullPred(attr("status"), true)), rel("Users")));
    }

    @Test
    public void parsesNullPredicateWithNot() {
        assertParsesTo("σ ¬(status = ⊥) (Users)",
                select(not(nullPred(attr("status"), true)), rel("Users")));
    }

    @Test
    public void parsesNullPredicateWithParentheses() {
        assertParsesTo("σ (status = ⊥) (Users)",
                select(nullPred(attr("status"), true), rel("Users")));
    }

    @Test
    public void parsesMultipleNullPredicates() {
        assertParsesTo("σ name = ⊥ ∧ email ≠ ⊥ (Users)",
                select(and(nullPred(attr("name"), true), nullPred(attr("email"), false)), rel("Users")));
    }

    @Test
    public void parsesNullPredicateWithOr() {
        assertParsesTo("σ status = ⊥ ∨ age < 18 (Users)",
                select(or(nullPred(attr("status"), true), cmp(attr("age"), ComparisonOperator.LESS, num("18"))), rel("Users")));
    }

    @Test
    public void parsesElementOfPredicateWithSetLiteral() {
        assertParsesTo("σ status ∈ {\"active\", \"pending\"} (Users)",
                select(elementOf(attr("status"), set(str("active"), str("pending"))), rel("Users")));
    }

    @Test
    public void parsesNotElementOfPredicateWithSetLiteral() {
        assertParsesTo("σ status ∉ {\"inactive\", \"banned\"} (Users)",
                select(notElementOf(attr("status"), set(str("inactive"), str("banned"))), rel("Users")));
    }

    @Test
    public void parsesElementOfPredicateWithNumbers() {
        assertParsesTo("σ age ∈ {18, 21, 25} (Users)",
                select(elementOf(attr("age"), set(num("18"), num("21"), num("25"))), rel("Users")));
    }

    @Test
    public void parsesElementOfPredicateWithSingleElement() {
        assertParsesTo("σ role ∈ {\"admin\"} (Users)",
                select(elementOf(attr("role"), set(str("admin"))), rel("Users")));
    }

    @Test
    public void parsesElementOfPredicateWithQualifiedAttribute() {
        assertParsesTo("σ Users.status ∈ {\"active\"} (Users)",
                select(elementOf(attr("Users.status"), set(str("active"))), rel("Users")));
    }

    @Test
    public void parsesElementOfPredicateWithComplexExpression() {
        assertParsesTo("σ age > 18 ∧ status ∈ {\"active\", \"pending\"} (Users)",
                select(and(cmp(attr("age"), ComparisonOperator.GREATER, num("18")), elementOf(attr("status"), set(str("active"), str("pending")))), rel("Users")));
    }

    @Test
    public void parsesElementOfPredicateWithNot() {
        assertParsesTo("σ ¬(status ∈ {\"banned\"}) (Users)",
                select(not(elementOf(attr("status"), set(str("banned")))), rel("Users")));
    }

    @Test
    public void parsesElementOfPredicateWithParentheses() {
        assertParsesTo("σ (status ∈ {\"active\"}) (Users)",
                select(elementOf(attr("status"), set(str("active"))), rel("Users")));
    }

    @Test
    public void parsesMultipleElementOfPredicates() {
        assertParsesTo("σ status ∈ {\"active\"} ∧ role ∉ {\"guest\"} (Users)",
                select(and(elementOf(attr("status"), set(str("active"))), notElementOf(attr("role"), set(str("guest")))), rel("Users")));
    }

    @Test
    public void parsesElementOfPredicateWithOr() {
        assertParsesTo("σ status ∈ {\"active\"} ∨ age < 18 (Users)",
                select(or(elementOf(attr("status"), set(str("active"))), cmp(attr("age"), ComparisonOperator.LESS, num("18"))), rel("Users")));
    }

    @Test
    public void parsesElementOfPredicateWithEmptySet() {
        assertParsesTo("σ status ∈ {} (Users)",
                select(elementOf(attr("status"), set()), rel("Users")));
    }

    // ==================== SQL predicate spellings ====================

    @Test
    public void parsesSqlNotEqualAsNotEqual() {
        assertParsesTo("σ status <> 'open' (Orders)",
                select(cmp(attr("status"), ComparisonOperator.NOT_EQUAL, str("open")), rel("Orders")));
    }

    @Test
    public void parsesBetweenAsTwoComparisons() {
        assertParsesTo("σ amount BETWEEN 10 AND 100 (Orders)",
                select(and(cmp(attr("amount"), ComparisonOperator.GREATER_EQUAL, num("10")),
                           cmp(attr("amount"), ComparisonOperator.LESS_EQUAL, num("100"))),
                        rel("Orders")));
        assertParsesTo("σ amount NOT BETWEEN 10 AND 100 (Orders)",
                select(or(cmp(attr("amount"), ComparisonOperator.LESS, num("10")),
                          cmp(attr("amount"), ComparisonOperator.GREATER, num("100"))),
                        rel("Orders")));
    }

    @Test
    public void betweenOwnsItsAnd() {
        assertParsesTo("σ a between 1 and 5 AND b = 2 (R)",
                select(and(and(cmp(attr("a"), ComparisonOperator.GREATER_EQUAL, num("1")),
                               cmp(attr("a"), ComparisonOperator.LESS_EQUAL, num("5"))),
                           cmp(attr("b"), ComparisonOperator.EQUAL, num("2"))),
                        rel("R")));
    }

    @Test
    public void betweenIsAWordElsewhere() {
        assertParsesTo("σ between = 1 (R)",
                select(cmp(attr("between"), ComparisonOperator.EQUAL, num("1")), rel("R")));
    }

    @Test
    public void parsesIsDistinctFromAsNullSafeTests() {
        Predicate both = and(and(cmp(attr("a"), ComparisonOperator.NOT_EQUAL, attr("b")),
                nullPred(attr("a"), false)), nullPred(attr("b"), false));
        Predicate onlyA = and(nullPred(attr("a"), true), nullPred(attr("b"), false));
        Predicate onlyB = and(nullPred(attr("a"), false), nullPred(attr("b"), true));
        assertParsesTo("σ a IS DISTINCT FROM b (R)",
                select(or(or(both, onlyA), onlyB), rel("R")));

        Predicate equal = and(and(cmp(attr("a"), ComparisonOperator.EQUAL, attr("b")),
                nullPred(attr("a"), false)), nullPred(attr("b"), false));
        assertParsesTo("σ a IS NOT DISTINCT FROM b (R)",
                select(or(equal, and(nullPred(attr("a"), true), nullPred(attr("b"), true))), rel("R")));
    }

    @Test
    public void parsesLikeEscape() {
        assertParsesTo("σ label LIKE '%0!%%' ESCAPE '!' (D)",
                select(like(attr("label"), str("%0!%%"), false, '!'), rel("D")));
        assertParsesTo("σ label NOT LIKE \"a\\\\_%\" ESCAPE \"\\\\\" (D)",
                select(like(attr("label"), str("a\\_%"), true, '\\'), rel("D")));
    }

    @Test
    public void rejectsMalformedSqlSpellings() {
        assertParseError("σ a BETWEEN 1 (R)")
                .hasMessageContaining("Expected 'AND' between the two bounds of BETWEEN");
        assertParseError("σ a LIKE 'x' ESCAPE '!!' (R)")
                .hasMessageContaining("ESCAPE takes one character, written as a string");
        assertParseError("σ a LIKE 'x' ESCAPE c (R)")
                .hasMessageContaining("ESCAPE takes one character");
        assertParseError("σ a IS DISTINCT b (R)")
                .hasMessageContaining("Expected 'NULL' or 'DISTINCT FROM' after 'IS'");
        assertParseError("σ a < > b (R)");
    }

    @Test
    public void nullIsAValueInOperandPosition() {
        assertParsesTo("σ Nz(a, NULL) = 1 (R)",
                select(cmp(func("Nz", attr("a"), nullValue()), ComparisonOperator.EQUAL, num("1")), rel("R")));
        assertParsesTo("σ Nz(a, ⊥) = 1 (R)",
                select(cmp(func("Nz", attr("a"), nullValue()), ComparisonOperator.EQUAL, num("1")), rel("R")));
        // After = it is still the null test.
        assertParsesTo("σ a = NULL (R)", select(nullPred(attr("a"), true), rel("R")));
        assertParsesTo("σ `null` = 1 (R)",
                select(cmp(attr("null"), ComparisonOperator.EQUAL, num("1")), rel("R")));
        assertPrettyPrints(parse("σ Nz(a, NULL) = 1 (R)"), "σ Nz(a, ⊥) = 1 (R)");
    }

    @Test
    public void parenthesisedNullTestIsACondition() {
        assertParsesTo("σ ¬(a IS NULL) (R)", select(not(nullPred(attr("a"), true)), rel("R")));
    }

    @Test
    public void negatedTestsInAFunctionArgument() {
        assertParsesTo("π IIf(a NOT IN {1}, 1, 0) → x (R)",
                RelAlgebraParser.parse("π IIf(a ∉ {1}, 1, 0) → x (R)"));
        AstAssertions.assertThat(parse("π IIf(a NOT LIKE 'b%', 1, 0) → x (R)")).isNode(ProjectionNode.class);
    }

    @Test
    public void betweenInAFunctionArgument() {
        assertParsesTo("π IIf(a NOT BETWEEN 1 AND 2, 1, 0) → x (R)",
                RelAlgebraParser.parse("π IIf(a < 1 ∨ a > 2, 1, 0) → x (R)"));
    }

    // ==================== PatternPredicate (LIKE) Tests ====================

    @Test
    public void parsesLikePredicate() {
        assertParsesTo("σ name LIKE \"%smith%\" (Users)",
                select(like(attr("name"), str("%smith%")), rel("Users")));
    }

    @Test
    public void parsesNotLikePredicate() {
        assertParsesTo("σ name NOT LIKE \"%test%\" (Users)",
                select(notLike(attr("name"), str("%test%")), rel("Users")));
    }

    @Test
    public void parsesLikeWithUnderscorePattern() {
        assertParsesTo("σ code LIKE \"A_C\" (Items)",
                select(like(attr("code"), str("A_C")), rel("Items")));
    }

    @Test
    public void parsesLikeWithLeadingPercent() {
        assertParsesTo("σ email LIKE \"%@example.com\" (Users)",
                select(like(attr("email"), str("%@example.com")), rel("Users")));
    }

    @Test
    public void parsesLikeCombinedWithAnd() {
        assertParsesTo("σ age > 18 ∧ name LIKE \"%Alice%\" (Users)",
                select(
                        and(
                                cmp(attr("age"), ComparisonOperator.GREATER, num("18")),
                                like(attr("name"), str("%Alice%"))),
                        rel("Users")));
    }

    @Test
    public void parsesNotLikeCombinedWithOr() {
        assertParsesTo("σ name NOT LIKE \"%spam%\" ∨ active = true (Users)",
                select(
                        or(
                                notLike(attr("name"), str("%spam%")),
                                cmp(attr("active"), ComparisonOperator.EQUAL, bool(true))),
                        rel("Users")));
    }

    @Test
    public void parsesLikeWithParentheses() {
        assertParsesTo("σ (name LIKE \"A%\") (Users)",
                select(like(attr("name"), str("A%")), rel("Users")));
    }

    @Test
    public void parsesLikeWithQualifiedAttribute() {
        assertParsesTo("σ Users.name LIKE \"Alice%\" (Users)",
                select(like(attr("Users.name"), str("Alice%")), rel("Users")));
    }

    @Test
    public void parsesLikeWithAsciiKeywords() {
        assertParsesTo("SELECT name LIKE \"%smith%\" (Users)",
                select(like(attr("name"), str("%smith%")), rel("Users")));
    }

}

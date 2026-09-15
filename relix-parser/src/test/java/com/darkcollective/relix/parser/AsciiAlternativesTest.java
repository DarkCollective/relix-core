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

import com.darkcollective.relix.ast.AstBuilders;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import com.darkcollective.relix.ast.*;

import java.util.List;
import java.util.Optional;

/**
 * Tests for ASCII shorthand alternatives to Unicode relational algebra operators.
 *
 * <p>Each test verifies the AST an ASCII form builds — the concrete tree, asserted
 * node by node, which is a different claim per operator and so is written per
 * operator.
 *
 * <p>The other half of the contract — that the ASCII form and its Unicode counterpart
 * parse <em>alike</em> — is one claim repeated, and lives in
 * {@link SyntaxSpellingTableTest}, driven from the spellings table that declares the
 * pairs. It used to be 32 {@code *MatchesUnicode} methods here, which is 32 places to
 * remember: the table declared 38 pairs and no relationship existed between the two,
 * so seven spellings had no equivalence test and nothing could say which.
 */
final class AsciiAlternativesTest extends ParserTestSupport {

    @Nested
    class UnaryOperators {

        @Test
        void projectKeyword() {
            assertParsesTo("PROJECT name, age (Users)",
                    project(
                            List.of(projected(attr("name")), projected(attr("age"))),
                            rel("Users")));
        }

        @Test
        void selectKeyword() {
            assertParsesTo("SELECT age > 18 (Users)",
                    select(
                            cmp(attr("age"), ComparisonOperator.GREATER, num("18")),
                            rel("Users")));
        }

        @Test
        void renameKeyword() {
            assertParsesTo("RENAME NewName (Users)",
                    rename("NewName", List.of(), rel("Users")));
        }

        @Test
        void renameKeywordWithColumns() {
            assertParsesTo("RENAME U(uid, uname) (Users)",
                    rename("U", List.of("uid", "uname"), rel("Users")));
        }

        @Test
        void groupKeyword() {
            assertParsesTo("GROUP dept, SUM(salary) -> total (Employees)",
                    groupBy(
                            List.of("dept"),
                            List.of(agg(AggregateOperator.SUM, "salary", "total")),
                            rel("Employees")));
        }

        @Test
        void sortKeyword() {
            assertParsesTo("SORT name ASC (Users)",
                    sort(
                            List.of(asc("name")),
                            rel("Users")));
        }

        @Test
        void limitKeyword() {
            assertParsesTo("LIMIT 10 (Orders)",
                    limit(Optional.empty(), 10L, rel("Orders")));
        }

        @Test
        void limitKeywordWithOffset() {
            assertParsesTo("LIMIT 5, 10 (Orders)",
                    limit(Optional.of(5L), 10L, rel("Orders")));
        }

        @Test
        void distinctKeyword() {
            assertParsesTo("DISTINCT (Users)",
                    distinct(rel("Users")));
        }

        @Test
        void forallKeyword() {
            assertParsesTo("FORALL cid : amount > 0 (Orders)",
                    universal(
                            java.util.List.of("cid"),
                            cmp(attr("amount"), ComparisonOperator.GREATER, num("0")),
                            rel("Orders")));
        }

    }

    @Nested
    class JoinOperators {

        @Test
        void joinKeywordForNaturalJoin() {
            assertParsesTo("Users JOIN Orders",
                    naturalJoin(rel("Users"), rel("Orders")));
        }

        @Test
        void thetaJoinSymbol() {
            assertParsesTo("Users >< Users.id = Orders.user_id Orders",
                    join(
                            rel("Users"),
                            rel("Orders"),
                            cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id"))));
        }

        @Test
        void leftOuterJoinSymbol() {
            assertParsesTo("Users |>< Users.id = Orders.user_id Orders",
                    leftJoin(
                            rel("Users"),
                            rel("Orders"),
                            cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id"))));
        }

        @Test
        void rightOuterJoinSymbol() {
            assertParsesTo("Users ><| Users.id = Orders.user_id Orders",
                    rightJoin(
                            rel("Users"),
                            rel("Orders"),
                            cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id"))));
        }

        @Test
        void fullOuterJoinSymbol() {
            assertParsesTo("Users |><| Users.id = Orders.user_id Orders",
                    fullJoin(
                            rel("Users"),
                            rel("Orders"),
                            cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id"))));
        }

        @Test
        void semiJoinKeyword() {
            assertParsesTo("Users SEMI Users.id = Orders.user_id Orders",
                    semiJoin(
                            rel("Users"),
                            rel("Orders"),
                            cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id"))));
        }

        @Test
        void antiJoinKeyword() {
            assertParsesTo("Users ANTI Users.id = Orders.user_id Orders",
                    antiJoin(
                            rel("Users"),
                            rel("Orders"),
                            cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id"))));
        }

    }

    @Nested
    class SetOperations {

        @Test
        void crossProduct() {
            assertParsesTo("A CROSS B",
                    product(rel("A"), rel("B")));
        }

        // Each test below is named after the operator it covers, and a method declared
        // in a class hides every inherited method of that name whatever its signature —
        // so the set-operation factories are qualified here rather than inherited.
        @Test
        void union() {
            assertParsesTo("A UNION B",
                    AstBuilders.union(rel("A"), rel("B")));
        }

        @Test
        void unionAll() {
            assertParsesTo("A UALL B",
                    AstBuilders.unionAll(rel("A"), rel("B")));
        }

        @Test
        void difference() {
            assertParsesTo("A DIFF B",
                    AstBuilders.difference(rel("A"), rel("B")));
        }

        @Test
        void intersection() {
            assertParsesTo("A INTER B",
                    AstBuilders.intersection(rel("A"), rel("B")));
        }

        @Test
        void division() {
            assertParsesTo("A DIV B",
                    AstBuilders.division(rel("A"), rel("B")));
        }

        @Test
        void symmetricDifference() {
            assertParsesTo("A SYMDIFF B",
                    AstBuilders.symmetricDifference(rel("A"), rel("B")));
        }

        @Test
        void composition() {
            assertParsesTo("A COMPOSE B",
                    AstBuilders.composition(rel("A"), rel("B")));
        }

    }

    @Nested
    class LogicalOperators {

        @Test
        void andKeyword() {
            assertParsesTo("SELECT a = 1 AND b = 2 (R)",
                    select(
                            and(
                                    cmp(attr("a"), ComparisonOperator.EQUAL, num("1")),
                                    cmp(attr("b"), ComparisonOperator.EQUAL, num("2"))),
                            rel("R")));
        }

        @Test
        void orKeyword() {
            assertParsesTo("SELECT a = 1 OR b = 2 (R)",
                    select(
                            or(
                                    cmp(attr("a"), ComparisonOperator.EQUAL, num("1")),
                                    cmp(attr("b"), ComparisonOperator.EQUAL, num("2"))),
                            rel("R")));
        }

        @Test
        void notKeyword() {
            assertParsesTo("SELECT NOT (a = 1) (R)",
                    select(
                            not(cmp(attr("a"), ComparisonOperator.EQUAL, num("1"))),
                            rel("R")));
        }

    }

    @Nested
    class SpecialOperators {

        @Test
        void notEqualSymbol() {
            assertParsesTo("SELECT status != \"inactive\" (Users)",
                    select(
                            cmp(attr("status"), ComparisonOperator.NOT_EQUAL, str("inactive")),
                            rel("Users")));
        }

        @Test
        void arrowInAggregation() {
            assertParsesTo("GROUP dept, SUM(salary) -> total (Employees)",
                    groupBy(
                            List.of("dept"),
                            List.of(agg(AggregateOperator.SUM, "salary", "total")),
                            rel("Employees")));
        }

        @Test
        void arrowInProjectionAlias() {
            assertParsesTo("PROJECT name -> full_name (Users)",
                    project(
                            List.of(projected(attr("name"), "full_name")),
                            rel("Users")));
        }

        @Test
        void nullKeywordInIsNullCheck() {
            assertParsesTo("SELECT a = NULL (R)",
                    select(nullPred(attr("a"), true), rel("R")));
        }

        @Test
        void nullKeywordInIsNotNullCheck() {
            assertParsesTo("SELECT a != NULL (R)",
                    select(nullPred(attr("a"), false), rel("R")));
        }

        @Test
        void inKeyword() {
            assertParsesTo("SELECT dept IN {\"hr\", \"eng\"} (Employees)",
                    select(
                            elementOf(attr("dept"), set(str("hr"), str("eng"))),
                            rel("Employees")));
        }

        @Test
        void notInTwoTokens() {
            assertParsesTo("SELECT dept NOT IN {\"hr\", \"eng\"} (Employees)",
                    select(
                            notElementOf(attr("dept"), set(str("hr"), str("eng"))),
                            rel("Employees")));
        }

    }

    @Nested
    class MixedUsage {

        @Test
        void asciiSelectWithUnicodeAnd() {
            assertParsesTo("SELECT age > 18 ∧ active = true (Users)",
                    select(
                            and(
                                    cmp(attr("age"), ComparisonOperator.GREATER, num("18")),
                                    cmp(attr("active"), ComparisonOperator.EQUAL, bool(true))),
                            rel("Users")));
        }

        @Test
        void unicodeSelectWithAsciiAnd() {
            assertParsesTo("σ age > 18 AND active = true (Users)",
                    select(
                            and(
                                    cmp(attr("age"), ComparisonOperator.GREATER, num("18")),
                                    cmp(attr("active"), ComparisonOperator.EQUAL, bool(true))),
                            rel("Users")));
        }

        @Test
        void asciiJoinWithUnicodeProjection() {
            assertParsesTo("π name (Users JOIN Orders)",
                    project(
                            List.of(projected(attr("name"))),
                            naturalJoin(rel("Users"), rel("Orders"))));
        }

        @Test
        void asciiOperatorsChained() {
            assertParsesTo("PROJECT id (SELECT age > 18 (Users))",
                    project(
                            List.of(projected(attr("id"))),
                            select(
                                    cmp(attr("age"), ComparisonOperator.GREATER, num("18")),
                                    rel("Users"))));
        }

        @Test
        void joinKeywordWithAsciiOuterJoins() {
            assertParsesTo("(A JOIN B) UNION (A |>< A.id = B.id B)",
                    union(
                            naturalJoin(rel("A"), rel("B")),
                            leftJoin(
                                    rel("A"),
                                    rel("B"),
                                    cmp(attr("A.id"), ComparisonOperator.EQUAL, attr("B.id")))));
        }

        @Test
        void notInWithAsciiSelect() {
            assertParsesTo("SELECT dept NOT IN {\"mgmt\"} AND salary > 50000 (Employees)",
                    select(
                            and(
                                    notElementOf(attr("dept"), set(str("mgmt"))),
                                    cmp(attr("salary"), ComparisonOperator.GREATER, num("50000"))),
                            rel("Employees")));
        }
    }

    /**
     * SQL-prior ASCII aliases (issue #455): pure synonyms that parse to the same
     * AST as their existing canonical form. Each alias is verified against the
     * canonical parse so the AST — not just "it parses" — is proven identical.
     */
    @Nested
    class SqlPriorAliases {

        @Test
        void intersectKeyword() {
            assertParsesTo("A INTERSECT B", parse("A INTER B"));
            assertParsesTo("A INTERSECT B", parse("A ∩ B"));
        }

        @Test
        void minusKeyword() {
            assertParsesTo("A MINUS B", parse("A DIFF B"));
            assertParsesTo("A MINUS B", parse("A − B"));
        }

        @Test
        void exceptKeyword() {
            assertParsesTo("A EXCEPT B", parse("A DIFF B"));
        }

        @Test
        void minusKeywordDistinctFromArithmeticMinus() {
            // The `minus` keyword is set difference; the `-` char stays arithmetic.
            assertParsesTo("PROJECT a - b -> d (R)", parse("π a - b → d (R)"));
        }

        @Test
        void leftOuterJoinKeyword() {
            assertParsesTo("Users LJOIN Users.id = Orders.user_id Orders",
                    parse("Users |>< Users.id = Orders.user_id Orders"));
            assertParsesTo("Users LJOIN Users.id = Orders.user_id Orders",
                    parse("Users ⟕ Users.id = Orders.user_id Orders"));
        }

        @Test
        void rightOuterJoinKeyword() {
            assertParsesTo("Users RJOIN Users.id = Orders.user_id Orders",
                    parse("Users ><| Users.id = Orders.user_id Orders"));
        }

        @Test
        void fullOuterJoinKeyword() {
            assertParsesTo("Users FJOIN Users.id = Orders.user_id Orders",
                    parse("Users |><| Users.id = Orders.user_id Orders"));
        }

        @Test
        void isNullPredicate() {
            assertParsesTo("SELECT a IS NULL (R)", parse("σ a = ⊥ (R)"));
            assertParsesTo("SELECT a IS NULL (R)",
                    select(nullPred(attr("a"), true), rel("R")));
        }

        @Test
        void isNotNullPredicate() {
            assertParsesTo("SELECT a IS NOT NULL (R)", parse("σ a ≠ ⊥ (R)"));
            assertParsesTo("SELECT a IS NOT NULL (R)",
                    select(nullPred(attr("a"), false), rel("R")));
        }

        @Test
        void isNullInsideConjunction() {
            assertParsesTo("SELECT a IS NULL AND b > 0 (R)",
                    parse("σ a = ⊥ ∧ b > 0 (R)"));
        }

        @Test
        void orderKeyword() {
            assertParsesTo("ORDER name ASC (Users)", parse("SORT name ASC (Users)"));
            assertParsesTo("ORDER name ASC (Users)", parse("τ name ASC (Users)"));
        }

        @Test
        void orderByKeyword() {
            assertParsesTo("ORDER BY name ASC (Users)", parse("τ name ASC (Users)"));
        }

        @Test
        void orderByMultipleKeys() {
            assertParsesTo("ORDER BY dept ASC, salary DESC (Employees)",
                    parse("τ dept ASC, salary DESC (Employees)"));
        }

        @Test
        void groupByKeyword() {
            assertParsesTo("GROUP BY dept, SUM(salary) -> total (Employees)",
                    parse("γ dept, SUM(salary) → total (Employees)"));
        }
    }
}

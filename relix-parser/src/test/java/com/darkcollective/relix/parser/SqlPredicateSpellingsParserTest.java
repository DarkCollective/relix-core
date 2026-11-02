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

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.ast.ComparisonOperator.*;

/**
 * SQL's truth tests, row values and quantified comparisons over a list (#260), each
 * read into the predicates it means.
 */
final class SqlPredicateSpellingsParserTest extends ParserTestSupport {

    @Test
    @DisplayName("IS FALSE is the comparison and a null test, and IS NOT FALSE their complement")
    void isFalse() {
        assertParsesTo("σ b IS FALSE (R)",
                select(and(cmp(attr("b"), EQUAL, bool(false)), nullPred(attr("b"), false)), rel("R")));
        assertParsesTo("σ b IS NOT FALSE (R)",
                select(or(cmp(attr("b"), EQUAL, bool(true)), nullPred(attr("b"), true)), rel("R")));
    }

    @Test
    @DisplayName("IS NOT UNKNOWN is a non-null test")
    void isNotUnknown() {
        assertParsesTo("σ b IS NOT UNKNOWN (R)", select(nullPred(attr("b"), false), rel("R")));
    }

    @Test
    @DisplayName("a comparison before IS is tested whole, as in PostgreSQL")
    void truthTestOfAComparison() {
        var comparison = condition(cmp(attr("a"), GREATER, num("1")));
        assertParsesTo("σ a > 1 IS TRUE (R)",
                select(and(cmp(comparison, EQUAL, bool(true)), nullPred(comparison, false)), rel("R")));
        assertParsesTo("σ (a > 1) IS UNKNOWN (R)", select(nullPred(comparison, true), rel("R")));
    }

    @Test
    @DisplayName("a truth test may follow another, each testing what came before")
    void chainedTruthTests() {
        var isNull = condition(nullPred(attr("b"), true));
        var inner = condition(nullPred(isNull, true));
        assertParsesTo("σ b IS NULL IS UNKNOWN IS UNKNOWN (R)", select(nullPred(inner, true), rel("R")));
    }

    @Test
    @DisplayName("an ordering of row values is lexicographic, over any width")
    void lexicographicOrdering() {
        assertParsesTo("σ (a, b, c) >= (1, 2, 3) (R)",
                select(or(cmp(attr("a"), GREATER, num("1")),
                        and(cmp(attr("a"), EQUAL, num("1")),
                                or(cmp(attr("b"), GREATER, num("2")),
                                        and(cmp(attr("b"), EQUAL, num("2")),
                                                cmp(attr("c"), GREATER_EQUAL, num("3")))))), rel("R")));
        assertParsesTo("σ (a, b) <= (1, 2) (R)",
                select(or(cmp(attr("a"), LESS, num("1")),
                        and(cmp(attr("a"), EQUAL, num("1")), cmp(attr("b"), LESS_EQUAL, num("2")))), rel("R")));
        assertParsesTo("σ (a, b) > (1, 2) (R)",
                select(or(cmp(attr("a"), GREATER, num("1")),
                        and(cmp(attr("a"), EQUAL, num("1")), cmp(attr("b"), GREATER, num("2")))), rel("R")));
    }

    @Test
    @DisplayName("row inequality is the disjunction of the columns' inequalities")
    void rowInequality() {
        assertParsesTo("σ (a, b) <> (1, 2) (R)",
                select(or(cmp(attr("a"), NOT_EQUAL, num("1")), cmp(attr("b"), NOT_EQUAL, num("2"))), rel("R")));
    }

    @Test
    @DisplayName("NOT IN over rows negates the disjunction, and ∉ reads the same")
    void rowNotIn() {
        var match = select(not(or(and(cmp(attr("a"), EQUAL, num("1")), cmp(attr("b"), EQUAL, num("2"))),
                and(cmp(attr("a"), EQUAL, num("3")), cmp(attr("b"), EQUAL, num("4"))))), rel("R"));
        assertParsesTo("σ (a, b) NOT IN {(1, 2), (3, 4)} (R)", match);
        assertParsesTo("σ (a, b) ∉ ((1, 2), (3, 4)) (R)", match);
    }

    @Test
    @DisplayName("IN over an empty list of rows is false")
    void rowInEmpty() {
        assertParsesTo("σ (a, b) IN {} (R)", select(cmp(bool(false), EQUAL, bool(true)), rel("R")));
    }

    @Test
    @DisplayName("a row value inside a parenthesised predicate, and beside other conditions")
    void rowValueAmongConditions() {
        assertParsesTo("σ ((a, b) = (1, 2)) ∧ c > 0 (R)",
                select(and(and(cmp(attr("a"), EQUAL, num("1")), cmp(attr("b"), EQUAL, num("2"))),
                        cmp(attr("c"), GREATER, num("0"))), rel("R")));
    }

    @Test
    @DisplayName("ANY over an empty list is false, and ALL true")
    void quantifierOverEmptyList() {
        assertParsesTo("σ a > ANY {} (R)", select(cmp(bool(false), EQUAL, bool(true)), rel("R")));
        assertParsesTo("σ a > ALL () (R)", select(cmp(bool(true), EQUAL, bool(true)), rel("R")));
    }

    @Test
    @DisplayName("a quantifier over one literal in parentheses, or a negative one")
    void quantifierOverOneLiteral() {
        assertParsesTo("σ a < ALL (5) (R)", select(cmp(attr("a"), LESS, num("5")), rel("R")));
        assertParsesTo("σ a < ANY (-5, 'x') (R)", parse("σ a < -5 ∨ a < 'x' (R)"));
    }

    @Test
    @DisplayName("a parenthesised list after a quantifier may open on a name when it holds a comma")
    void quantifierOverNames() {
        assertParsesTo("σ a > ALL (b, 1) (R)", parse("σ a > b ∧ a > 1 (R)"));
    }

    @Test
    @DisplayName("a parenthesised list of names followed by no comparison is not a row value")
    void notARowValue() {
        assertParseError("σ (a, b) LIKE 'x' (R)").hasMessageContaining("Expected");
        assertParseError("σ (a, b) NOT LIKE 'x' (R)").hasMessageContaining("Expected");
    }

    @Test
    @DisplayName("a column called any, all or some is still a column before a relation in parentheses")
    void quantifierWordsAreNotReserved() {
        assertParsesTo("σ x = any (R)", select(cmp(attr("x"), EQUAL, attr("any")), rel("R")));
        assertParsesTo("σ x = all (R)", select(cmp(attr("x"), EQUAL, attr("all")), rel("R")));
        assertParsesTo("σ x = some + 1 (R)",
                select(cmp(attr("x"), EQUAL, arith(attr("some"), com.darkcollective.relix.ast.ArithmeticOperator.PLUS,
                        num("1"))), rel("R")));
        assertParsesTo("σ unknown IS NULL (R)", select(nullPred(attr("unknown"), true), rel("R")));
    }

    @Test
    @DisplayName("one value in parentheses after IN is still a single expression")
    void inOneParenthesisedValue() {
        assertParsesTo("σ x IN (tags) (R)", select(elementOf(attr("x"), attr("tags")), rel("R")));
        assertParsesTo("σ x NOT IN (1, 2) (R)", select(notElementOf(attr("x"), set(num("1"), num("2"))), rel("R")));
    }

    @Test
    @DisplayName("row values of different widths are refused, naming both widths")
    void widthMismatch() {
        assertParseError("σ (a, b) = (1, 2, 3) (R)")
                .hasMessageContaining("Row values compare only at the same width: one has 2 values and the other 3");
        assertParseError("σ (a, b) IN {(1, 2), (3)} (R)")
                .hasMessageContaining("one has 2 values and the other 1");
    }

    @Test
    @DisplayName("a row value compares only with another row, and IN takes only rows")
    void rowValueNeedsARow() {
        assertParseError("σ (a, b) = c (R)")
                .hasMessageContaining("compares with another row of the same width, such as (a, b) = (1, 2)");
        assertParseError("σ (a, b) IN {1, 2} (R)")
                .hasMessageContaining("A row value IN takes a list of rows of the same width");
        assertParseError("σ (a, b) IN tags (R)")
                .hasMessageContaining("Expected a list of rows such as {(1, 2), (3, 4)} after IN");
    }

    @Test
    @DisplayName("SQL's JSON arrows are refused with the call each one is")
    void jsonArrowsAreRefused() {
        for (String input : new String[] {"π doc ->> '$.a' (R)", "π doc -> '$.a' (R)"}) {
            assertParseError(input).hasMessageContaining(
                    "-> names a column in Relix, so it is not a JSON operator: write JSON_VALUE(doc, '$.a')"
                    + " for SQL's doc ->> '$.a', and JSON_EXTRACT(doc, '$.a') for doc -> '$.a'");
        }
        assertParseError("π doc -> > x (R)").hasMessageContaining("Expected alias after '→'");
    }

    @Test
    @DisplayName("SQL's RETURNING in a call is refused with the CAST that converts the text")
    void returningIsRefused() {
        assertParseError("π JSON_VALUE(doc, '$.a' RETURNING NUMBER) → v (R)").hasMessageContaining(
                "JSON_VALUE takes no RETURNING clause: its value is text, so convert it with CAST,"
                + " as in CAST(JSON_VALUE(doc, '$.qty') AS NUMBER)");
    }

    @Test
    @DisplayName("an unclosed list names what closes it")
    void unclosedLists() {
        assertParseError("σ a > ALL {1, 2 (R)").hasMessageContaining("Expected '}' to close the list");
        assertParseError("σ a > ALL (1, 2 (R)").hasMessageContaining("Expected ')' to close the list");
        assertParseError("σ (a, b) IN {(1, 2) (R)").hasMessageContaining("Expected '}' to close the list of rows");
        assertParseError("σ (a, b) IN ((1, 2) (R)").hasMessageContaining("Expected ')' to close the list of rows");
    }
}

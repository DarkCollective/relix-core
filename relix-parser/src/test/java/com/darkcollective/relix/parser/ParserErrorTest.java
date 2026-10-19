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

import com.darkcollective.relix.ast.AntiJoinNode;
import com.darkcollective.relix.ast.NaturalJoinNode;
import com.darkcollective.relix.ast.ThetaJoinNode;
import com.darkcollective.relix.ast.UnionNode;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.ast.AstAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ParserErrorTest extends ParserTestSupport {

    @Test
    public void reportsEmptyInput() {
        assertParseError("")
                .hasMessageContaining("Expected relation name")
                .at(1, 1);
    }

    @Test
    public void reportsUnexpectedCharacter() {
        assertParseError("@")
                .hasMessageContaining("Unexpected character")
                .at(1, 1);
    }

    @Test
    public void reportsMissingProjectionAttribute() {
        assertParseError("π (Users)")
                .hasMessageContaining("Expected '(' after projection attribute list")
                .at(1, 10);
    }

    @Test
    public void reportsTrailingProjectionComma() {
        assertParseError("π id, (Users)")
                .hasMessageContaining("Expected '(' after projection attribute list")
                .at(1, 15);
    }

    @Test
    public void reportsMissingProjectionInputParen() {
        assertParseError("π id Users")
                .hasMessageContaining("Expected '(' after projection attribute list")
                .at(1, 6);
    }

    @Test
    public void reportsMissingSelectionInputParen() {
        assertParseError("σ age > 18 Users")
                .hasMessageContaining("Expected '(' after selection predicate")
                .at(1, 12);
    }

    @Test
    public void reportsMissingSelectionComparison() {
        assertParseError("σ age (Users)")
                .hasMessageContaining("Expected comparison operator")
                .at(1, 7);
    }

    @Test
    @DisplayName("SELECT followed by SQL's column list says that SELECT filters, and names PROJECT")
    public void reportsColumnListAfterSelect() {
        assertParseError("SELECT 'TOTAL' -> Item, SUM(Value) -> Value (Selection)")
                .hasMessageContaining("'SELECT' (σ) filters rows by a condition, and found '->'"
                        + " where its comparison belongs. To choose or compute columns, use"
                        + " PROJECT (π): PROJECT a, b * 2 -> c (R)")
                .at(1, 16);
        assertParseError("σ a, b (R)")
                .hasMessageContaining("'σ' filters rows by a condition, and found ','")
                .at(1, 4);
        assertParseError("select a → b (R)")
                .hasMessageContaining("'select' (σ) filters rows by a condition, and found '→'");
    }

    @Test
    @DisplayName("the column-list hint is a selection's own: a join condition keeps the plain message")
    public void columnListHintStaysWithItsSelection() {
        assertParseError("π a, b (R) >< x -> y S")
                .hasMessageContaining("Expected comparison operator");
        assertParseError("σ a > 1 (R >< x -> y S)")
                .hasMessageContaining("Expected comparison operator");
        // A selection in another's input gets the hint for its own condition.
        assertParseError("σ a > 1 (σ b, c (R))")
                .hasMessageContaining("'σ' filters rows by a condition, and found ','")
                .at(1, 13);
    }

    @Test
    @DisplayName("OPTIMIZE constraints listed with a comma are told to join with AND")
    public void reportsCommaBetweenOptimizeConstraints() {
        assertParseError("OPTIMIZE MAXIMIZE SUM(v) SUBJECT TO SUM(w) <= 80, SUM(u) <= 60 (Items)")
                .hasMessageContaining("Join OPTIMIZE constraints with AND, not ',':"
                        + " SUBJECT TO SUM(a) <= 80 AND SUM(b) <= 60")
                .at(1, 49);
        // A comma before a PER key list is something else, and keeps its own message.
        assertParseError("OPTIMIZE MAXIMIZE SUM(v) SUBJECT TO SUM(w) <= 80, x (Items)")
                .hasMessageContaining("Expected '(' after the OPTIMIZE specification");
    }

    @Test
    @DisplayName("an operator written after its input, SQL's ORDER BY order, is rewritten into Relix's")
    public void reportsOperatorAfterItsInput() {
        assertParseError("PROJECT Item, Value (Selected) SORT Item ASC")
                .hasMessageContaining("'SORT' is written before its input, which it takes in"
                        + " parentheses: SORT Item ASC (PROJECT Item, Value (Selected))")
                .at(1, 32);
        assertParseError("R ⋈ S LIMIT 5")
                .hasMessageContaining("'LIMIT' is written before its input, which it takes in"
                        + " parentheses: LIMIT 5 (R ⋈ S)");
        assertParseError("π a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v, w, x, y, z (R) σ a > 1")
                .hasMessageContaining("parentheses: σ a > 1 (…)");
        // Anything else after a whole expression is still the end of input expected.
        assertParseError("R S")
                .hasMessageContaining("Expected end of input");
    }

    @Test
    @DisplayName("a binary operator written before its inputs is reported at the operator, with its infix form")
    public void reportsInfixOperatorWrittenFirst() {
        assertParseError("JOIN A >< A.id = B.a_id B")
                .hasMessageContaining("'JOIN' goes between the two relations it joins:"
                        + " A JOIN B, or A >< condition B")
                .at(1, 1);
        assertParseError("UNION A B")
                .hasMessageContaining("'UNION' goes between the two relations it combines: A UNION B")
                .at(1, 1);
        assertParseError("σ a > 1 (ljoin A B)")
                .hasMessageContaining("'ljoin' goes between the two relations it joins, with its"
                        + " condition after it: A ljoin A.id = B.a_id B")
                .at(1, 10);
        assertParseError("EXCEPT π a (R) S")
                .hasMessageContaining("'EXCEPT' goes between the two relations it combines");
        assertParseError("⋈ A B")
                .hasMessageContaining("'⋈' goes between the two relations it joins: A ⋈ B")
                .at(1, 1);
        assertParseError("∪ A B")
                .hasMessageContaining("'∪' goes between the two relations it combines: A ∪ B");
        assertParseError("× A B")
                .hasMessageContaining("'×' goes between its two inputs: A × B");
        // A unary operator's glyph after the word starts a relation as its keyword does.
        for (String unary : List.of("π a (R)", "μ tags (R)", "ω (R)", "∀ x (R)")) {
            assertParseError("union " + unary)
                    .hasMessageContaining("'union' goes between the two relations it combines")
                    .at(1, 1);
        }
    }

    @Test
    @DisplayName("a relation named like a binary operator still reads as a relation")
    public void relationNamedLikeInfixOperator() {
        assertThat(parse("diff ⋈ R")).isNode(NaturalJoinNode.class).left().isRelation("diff");
        assertThat(parse("union")).isRelation("union");
        assertThat(parse("R ∪ (minus)")).isNode(UnionNode.class).right().isRelation("minus");
        assertThat(parse("union union S")).isNode(UnionNode.class).left().isRelation("union");
    }

    @Test
    public void reportsMissingRightPredicateOperand() {
        assertParseError("σ age >")
                .hasMessageContaining("Expected attribute, string, number, boolean literal, function call, or '('")
                .at(1, 8);
    }

    @Test
    public void reportsMissingRightRelationOperand() {
        assertParseError("Users ⋈")
                .hasMessageContaining("Expected relation name")
                .at(1, 8);
    }

    @Test
    public void reportsJoinMissingItsCondition() {
        assertThatThrownBy(() -> parse("Customers ANTI Orders"))
                .hasMessage("'ANTI' needs a join condition between its two inputs, written after"
                        + " the operator: Customers ANTI <condition> Orders at line 1, column 11");
        assertParseError("Customers ANTI Orders")
                .at(1, 11)
                .found("'ANTI'");
    }

    @Test
    public void reportsEveryConditionedJoinMissingItsCondition() {
        for (String op : new String[] {"SEMI", "⋉", "▷", "LJOIN", "RJOIN", "FJOIN", "><",
                "|><", "><|", "|><|", "⨝", "ASOF"}) {
            assertParseError("A " + op + " B")
                    .hasMessageContaining("'" + op + "' needs a join condition")
                    .at(1, 3);
        }
    }

    @Test
    public void reportsJoinMissingItsConditionBeforeAnotherOperatorOrParen() {
        assertParseError("(A SEMI s.B) ∪ C")
                .hasMessageContaining("A SEMI <condition> s.B at line 1, column 4")
                .at(1, 4);
        assertParseError("(A ∪ B) SEMI C")
                .as("a left input with no single name is elided")
                .hasMessageContaining("… SEMI <condition> C")
                .at(1, 9);
        assertParseError("A ▷ B ∪ C")
                .hasMessageContaining("A ▷ <condition> B")
                .at(1, 3);
        assertParseError("A ASOF inner B")
                .hasMessageContaining("'ASOF' needs a join condition")
                .at(1, 3);
    }

    @Test
    public void conditionStartingWithANameStillParses() {
        assertThat(parse("A ▷ A.id = B.id B")).isNode(AntiJoinNode.class);
        assertParseError("σ x (A ▷ x(1) B)")
                .as("a name followed by '(' is a function call, so the condition is there")
                .hasMessageContaining("Expected comparison operator");
        assertParseError("A ⋉ x.")
                .as("a dot with no name after it is left to the condition's own error")
                .hasMessageContaining("Expected identifier after '.'");
    }

    @Test
    public void reportsNaturalJoinGivenACondition() {
        assertThatThrownBy(() -> parse("A ⋈ A.nope = A.id A"))
                .hasMessage("'⋈' is the natural join: it joins on the columns both inputs share"
                        + " and takes no condition; for an explicit condition use ⨝ (><)"
                        + " at line 1, column 3");
        assertParseError("A ⋈ A.nope = A.id A").at(1, 3);
        assertParseError("A JOIN x < 1 B")
                .hasMessageContaining("'JOIN' is the natural join")
                .at(1, 3);
    }

    @Test
    public void reportsConditionAfterNaturalJoinsRightInput() {
        String script = "Round3 JOIN Teams (Teams.team_id = home_id)";
        assertThatThrownBy(() -> parse(script))
                .hasMessage("'JOIN' is the natural join: it joins on the columns both inputs"
                        + " share and takes no condition; to join on a condition, use ⨝ (><)"
                        + " and write the condition between the inputs:"
                        + " Round3 >< Teams.team_id = home_id Teams at line 1, column 8");
        assertParseError(script).at(1, 8).found("'JOIN'");
        assertThat(parse("Round3 >< Teams.team_id = home_id Teams"))
                .as("the rewrite the message offers parses")
                .isNode(ThetaJoinNode.class);
        assertParseError("(A ∪ B) ⋈ s.B (x IN (1,2) AND NOT y LIKE 'a%')")
                .hasMessageContaining(": … ⨝ x IN (1, 2) AND NOT y LIKE 'a%' s.B")
                .at(1, 9);
        assertParseError("A JOIN B (x = y) ∪ C").hasMessageContaining(": A >< x = y B");
    }

    @Test
    public void reportsConditionAfterConditionedJoinsRightInput() {
        for (String op : new String[] {"LJOIN", "SEMI", "ANTI", "▷", "><", "|><|", "ASOF"}) {
            assertThatThrownBy(() -> parse("A " + op + " B (B.id <= A.id)"))
                    .hasMessage("'" + op + "' takes its join condition between its two inputs,"
                            + " not after them: A " + op + " B.id <= A.id B at line 1, column 3");
        }
        assertParseError("A ASOF INNER B (x = y)").hasMessageContaining(": A ASOF x = y B");
        assertParseError("σ z = 1 (A SEMI B (x ∉ (1, 2)))")
                .hasMessageContaining(": A SEMI x ∉ (1, 2) B")
                .at(1, 12);
        assertParseError("A ANTI B (x IS NULL) ∪ C").hasMessageContaining(": A ANTI x IS NULL B");
    }

    @Test
    public void callOnAJoinsRightStillParses() {
        assertThat(parse("A JOIN f(1)")).isNode(NaturalJoinNode.class);
        assertThat(parse("A JOIN B (x)")).as("a call with no condition in it").isNode(NaturalJoinNode.class);
        assertThat(parse("A JOIN f(g(x = 1))")).as("a condition inside a nested argument").isNode(NaturalJoinNode.class);
        assertThat(parse("A >< f(x) = 1 B")).as("a condition starting with a call").isNode(ThetaJoinNode.class);
        assertThat(parse("A >< IIf(x > 1, 1, 0) = y B"))
                .as("a condition starting with a call whose argument compares")
                .isNode(ThetaJoinNode.class);
        assertThat(parse("A SEMI IIf(x > 1, 1, 0) = y B ∪ C"))
                .as("the same, with an operator after the right input")
                .isNode(UnionNode.class);
        assertParseError("A JOIN B (x = y")
                .as("an unclosed group is left to the call's own error")
                .hasMessageContaining("Expected ')' after function call arguments");
    }

    @Test
    public void reportsTrailingInput() {
        assertParseError("Users Orders")
                .hasMessageContaining("Expected end of input")
                .at(1, 7);
    }

    @Test
    public void reportsMissingClosingParen() {
        assertParseError("(Users")
                .hasMessageContaining("Expected ')' after relational expression")
                .at(1, 7);
    }

    @Test
    public void reportsMissingPredicateClosingParen() {
        assertParseError("σ (age > 18 (Users)")
                .hasMessageContaining("Expected ')' after predicate")
                .at(1, 13);
    }

    @Test
    public void reportsMissingRenameTarget() {
        assertParseError("ρ (Users)")
                .hasMessageContaining("Expected a new relation name or a rename list after 'ρ'")
                .at(1, 3);
    }

    @Test
    public void reportsMissingRenamedAttribute() {
        assertParseError("ρ U(id,) (Users)")
                .hasMessageContaining("Expected ')' after relational expression")
                .at(1, 7);
    }

    @Test
    public void reportsMixedRenameBareAndPairForms() {
        assertParseError("ρ U (a, b → c) (Users)")
                .hasMessageContaining("cannot mix bare column names and 'old → new' pairs");
    }

    @Test
    public void reportsUnterminatedString() {
        assertParseError("σ name = \"Alice (Users)")
                .hasMessageContaining("Unterminated string literal")
                .at(1, 10);
    }

    @Test
    public void reportsInvalidDecimal() {
        assertParseError("σ price = 10. (Products)")
                .hasMessageContaining("Expected digit after decimal point")
                .at(1, 11);
    }

    @Test
    public void reportsLineAndColumn() {
        assertParseError("""
                π id (
                  Users
                """)
                .hasMessageContaining("Expected ')' after relational expression")
                .at(3, 1);
    }

    @Test
    public void reportsUnsupportedStringEscape() {
        assertParseError("σ text = \"hello\\z\" (Messages)")
                .hasMessageContaining("Unsupported string escape")
                .at(1, 17);
    }

    @Test
    public void reportsOnlyCommentInput() {
        assertParseError("/* unterminated comment")
                .hasMessageContaining("Expected relation name")
                .at(1, 24);
    }

    @Test
    public void reportsMissingIdentifierAfterDot() {
        assertParseError("σ Users. = Orders.user_id (Users)")
                .hasMessageContaining("Expected identifier after '.'")
                .at(1, 10);
    }

    @Test
    public void reportsUnterminatedStringEscape() {
        assertParseError("σ text = \"hello\\")
                .hasMessageContaining("Unterminated string escape")
                .at(1, 10);
    }

    @Test
    public void reportsGroupedArithmeticWithoutComparisonInPredicate() {
        assertParseError("σ (a + b) (R)")
                .hasMessageContaining("Expected comparison operator")
                .at(1, 11);
    }

    @Test
    public void reportsLineAndColumnAfterMultiLineComment() {
        assertParseError("""
                /* comment
                   still comment */
                @
                """)
                .hasMessageContaining("Unexpected character")
                .at(3, 1);
    }

    @Test
    public void reportsFoundTokenAndDiagnostic() {
        assertParseError("@")
                .found("'@'")
                .diagnostic("""
                    @
                    ^""");
    }
}

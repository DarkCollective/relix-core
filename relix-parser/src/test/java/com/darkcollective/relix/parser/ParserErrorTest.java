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

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

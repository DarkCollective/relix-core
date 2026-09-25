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
package com.darkcollective.relix.embed;

import com.darkcollective.relix.parser.TokenType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Record-invariant and mapping checks for the highlight value types. */
@DisplayName("Token + TokenKind")
final class TokenTest {

    @Test
    @DisplayName("length is end − start")
    void length() {
        assertThat(new Token(2, 7, TokenKind.IDENTIFIER).length()).isEqualTo(5);
        assertThat(new Token(3, 3, TokenKind.BRACKET).length()).isZero();
    }

    @Test
    @DisplayName("invalid bounds and a null kind are rejected")
    void guards() {
        assertThatThrownBy(() -> new Token(-1, 0, TokenKind.NUMBER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Token(5, 2, TokenKind.NUMBER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Token(0, 1, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("every TokenType maps to a class without throwing")
    void everyTokenTypeClassifies() {
        for (TokenType type : TokenType.values()) {
            assertThat(Tokens.kind(type)).isNotNull();
        }
    }

    @Test
    @DisplayName("representative mappings are stable")
    void representativeMappings() {
        assertThat(Tokens.kind(TokenType.PROJECT)).isEqualTo(TokenKind.OPERATOR);
        assertThat(Tokens.kind(TokenType.SUM)).isEqualTo(TokenKind.KEYWORD);
        assertThat(Tokens.kind(TokenType.AND)).isEqualTo(TokenKind.LOGICAL);
        assertThat(Tokens.kind(TokenType.LESS_EQUAL)).isEqualTo(TokenKind.COMPARISON);
        assertThat(Tokens.kind(TokenType.ELEMENT_OF)).isEqualTo(TokenKind.COMPARISON);
        assertThat(Tokens.kind(TokenType.LBRACKET)).isEqualTo(TokenKind.BRACKET);
        assertThat(Tokens.kind(TokenType.ARROW)).isEqualTo(TokenKind.PUNCTUATION);
        assertThat(Tokens.kind(TokenType.STRING)).isEqualTo(TokenKind.STRING);
    }
}

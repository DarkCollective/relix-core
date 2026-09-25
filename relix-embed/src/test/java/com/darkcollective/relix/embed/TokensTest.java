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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the fault-tolerant {@link Relix#tokens}: it must classify a complete
 * line exactly like the strict lexer would, and degrade gracefully — never throw
 * — on the partial input an interactive REPL feeds it on every keystroke.
 */
@DisplayName("Relix.tokens — fault-tolerant highlighting")
final class TokensTest {

    /** The substring each span covers, for readable assertions. */
    private static String text(String input, Token span) {
        return input.substring(span.start(), span.end());
    }

    private static void assertSpan(String input, Token span, String expected, TokenKind kind) {
        assertThat(text(input, span)).isEqualTo(expected);
        assertThat(span.kind()).isEqualTo(kind);
    }

    @Nested
    @DisplayName("complete, well-formed lines")
    final class Complete {

        @Test
        @DisplayName("a Unicode projection classifies each token")
        void unicodeProjection() {
            String src = "π name (Users)";
            List<Token> spans = Relix.tokens(src);

            assertThat(spans).hasSize(5);
            assertSpan(src, spans.get(0), "π", TokenKind.OPERATOR);
            assertSpan(src, spans.get(1), "name", TokenKind.IDENTIFIER);
            assertSpan(src, spans.get(2), "(", TokenKind.BRACKET);
            assertSpan(src, spans.get(3), "Users", TokenKind.IDENTIFIER);
            assertSpan(src, spans.get(4), ")", TokenKind.BRACKET);
        }

        @Test
        @DisplayName("ASCII keyword operators classify as operators")
        void asciiKeywordOperators() {
            String src = "A JOIN B";
            List<Token> spans = Relix.tokens(src);

            assertThat(spans).hasSize(3);
            assertSpan(src, spans.get(0), "A", TokenKind.IDENTIFIER);
            assertSpan(src, spans.get(1), "JOIN", TokenKind.OPERATOR);
            assertSpan(src, spans.get(2), "B", TokenKind.IDENTIFIER);
        }

        @Test
        @DisplayName("a selection mixes comparison, logical, string and number")
        void selectionMixesClasses() {
            String src = "σ age >= 18 AND name = \"bob\" (Users)";
            List<Token> spans = Relix.tokens(src);

            assertThat(spans).extracting(s -> text(src, s)).containsExactly(
                    "σ", "age", ">=", "18", "AND", "name", "=", "\"bob\"", "(", "Users", ")");
            assertThat(spans).extracting(Token::kind).containsExactly(
                    TokenKind.OPERATOR, TokenKind.IDENTIFIER, TokenKind.COMPARISON,
                    TokenKind.NUMBER, TokenKind.LOGICAL, TokenKind.IDENTIFIER,
                    TokenKind.COMPARISON, TokenKind.STRING, TokenKind.BRACKET,
                    TokenKind.IDENTIFIER, TokenKind.BRACKET);
        }

        @Test
        @DisplayName("aggregation arrow and braces classify correctly")
        void aggregationPunctuation() {
            String src = "γ id, SUM(x) → t (R)";
            List<Token> spans = Relix.tokens(src);

            assertThat(spans).extracting(s -> text(src, s)).containsExactly(
                    "γ", "id", ",", "SUM", "(", "x", ")", "→", "t", "(", "R", ")");
            assertThat(spans).extracting(Token::kind).containsExactly(
                    TokenKind.OPERATOR, TokenKind.IDENTIFIER, TokenKind.PUNCTUATION,
                    TokenKind.KEYWORD, TokenKind.BRACKET, TokenKind.IDENTIFIER,
                    TokenKind.BRACKET, TokenKind.PUNCTUATION, TokenKind.IDENTIFIER,
                    TokenKind.BRACKET, TokenKind.IDENTIFIER, TokenKind.BRACKET);
        }

        @Test
        @DisplayName("offsets are exact even after multi-byte glyphs")
        void offsetsAreExact() {
            String src = "π x (R)";
            List<Token> spans = Relix.tokens(src);

            // 'x' begins at index 2 (after "π ").
            assertThat(spans.get(1).start()).isEqualTo(2);
            assertThat(spans.get(1).end()).isEqualTo(3);
            assertThat(spans.get(1).length()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("partial / in-progress lines")
    final class Partial {

        @Test
        @DisplayName("an unterminated string becomes an in-progress span from the quote")
        void unterminatedString() {
            String src = "σ name = \"bo";
            List<Token> spans = Relix.tokens(src);

            assertSpan(src, spans.get(0), "σ", TokenKind.OPERATOR);
            assertSpan(src, spans.get(1), "name", TokenKind.IDENTIFIER);
            assertSpan(src, spans.get(2), "=", TokenKind.COMPARISON);
            Token last = spans.get(spans.size() - 1);
            assertSpan(src, last, "\"bo", TokenKind.INCOMPLETE);
        }

        @Test
        @DisplayName("a trailing operator is well-formed — no in-progress span")
        void trailingOperatorIsClean() {
            String src = "σ a >";
            List<Token> spans = Relix.tokens(src);

            assertThat(spans).extracting(Token::kind)
                    .doesNotContain(TokenKind.INCOMPLETE);
            assertSpan(src, spans.get(spans.size() - 1), ">", TokenKind.COMPARISON);
        }

        @Test
        @DisplayName("a stray character makes the remainder in-progress")
        void strayCharacter() {
            String src = "π name @ rest";
            List<Token> spans = Relix.tokens(src);

            assertSpan(src, spans.get(0), "π", TokenKind.OPERATOR);
            assertSpan(src, spans.get(1), "name", TokenKind.IDENTIFIER);
            Token last = spans.get(spans.size() - 1);
            assertThat(last.kind()).isEqualTo(TokenKind.INCOMPLETE);
            assertThat(text(src, last)).isEqualTo("@ rest");
        }

        @Test
        @DisplayName("a lone pipe (incomplete outer-join operator) is in-progress")
        void incompleteOuterJoinOperator() {
            String src = "A | B";
            List<Token> spans = Relix.tokens(src);

            assertSpan(src, spans.get(0), "A", TokenKind.IDENTIFIER);
            assertThat(spans.get(spans.size() - 1).kind())
                    .isEqualTo(TokenKind.INCOMPLETE);
        }
    }

    @Nested
    @DisplayName("comments")
    final class Comments {

        @Test
        @DisplayName("a line comment after a token is a COMMENT span")
        void lineComment() {
            String src = "π x (R) -- pick x";
            List<Token> spans = Relix.tokens(src);

            Token comment = spans.get(spans.size() - 1);
            assertThat(comment.kind()).isEqualTo(TokenKind.COMMENT);
            assertThat(text(src, comment)).isEqualTo("-- pick x");
        }

        @Test
        @DisplayName("a line comment on its own line is a COMMENT span")
        void ownLineComment() {
            String src = "-- pick x\nπ x (R)";
            List<Token> spans = Relix.tokens(src);

            assertThat(spans.get(0).kind()).isEqualTo(TokenKind.COMMENT);
            assertThat(text(src, spans.get(0))).isEqualTo("-- pick x");
        }

        @Test
        @DisplayName("two slashes are division, not a comment")
        void doubleSlashIsNotAComment() {
            // The claim the language makes, and the one this lexer used to contradict:
            // `//` is two operators. It reaches the highlighter as an unreadable
            // remainder rather than as a comment, which is what a reader should see.
            String src = "π x (R) // pick x";
            List<Token> spans = Relix.tokens(src);

            assertThat(spans).extracting(Token::kind)
                    .doesNotContain(TokenKind.COMMENT);
        }

        @Test
        @DisplayName("a block comment between tokens is a COMMENT span")
        void blockComment() {
            String src = "π /* the col */ x (R)";
            List<Token> spans = Relix.tokens(src);

            assertThat(spans).anySatisfy(s -> {
                assertThat(s.kind()).isEqualTo(TokenKind.COMMENT);
                assertThat(text(src, s)).isEqualTo("/* the col */");
            });
            // The identifier after the comment still classifies correctly.
            assertThat(spans).anySatisfy(s -> assertSpan(src, s, "x", TokenKind.IDENTIFIER));
        }

        @Test
        @DisplayName("an unterminated block comment runs to end of input")
        void unterminatedBlockComment() {
            String src = "π x (R) /* trailing";
            List<Token> spans = Relix.tokens(src);

            Token comment = spans.get(spans.size() - 1);
            assertThat(comment.kind()).isEqualTo(TokenKind.COMMENT);
            assertThat(text(src, comment)).isEqualTo("/* trailing");
        }
    }

    @Nested
    @DisplayName("edge cases")
    final class Edges {

        @Test
        @DisplayName("null and empty input yield no spans")
        void emptyInput() {
            assertThat(Relix.tokens(null)).isEmpty();
            assertThat(Relix.tokens("")).isEmpty();
            assertThat(Relix.tokens("   ")).isEmpty();
        }

        @Test
        @DisplayName("spans are non-overlapping and ordered")
        void spansNonOverlapping() {
            String src = "σ a = 1 ∧ b ≠ 2 (R)";
            List<Token> spans = Relix.tokens(src);

            int prevEnd = 0;
            for (Token s : spans) {
                assertThat(s.start()).isGreaterThanOrEqualTo(prevEnd);
                assertThat(s.end()).isGreaterThanOrEqualTo(s.start());
                prevEnd = s.end();
            }
        }
    }

    @Nested
    @DisplayName("backtick-delimited identifiers")
    final class DelimitedIdentifiers {

        @Test
        @DisplayName("the name inside backticks is coloured as an identifier")
        void delimitedNameIsIdentifier() {
            String src = "π x (`order`)";
            List<Token> spans = Relix.tokens(src);

            Token name = spans.stream()
                    .filter(s -> text(src, s).equals("order"))
                    .findFirst()
                    .orElseThrow();
            assertThat(name.kind()).isEqualTo(TokenKind.IDENTIFIER);
        }

        @Test
        @DisplayName("a half-typed delimited identifier degrades to IN_PROGRESS, never throws")
        void unterminatedDegradesGracefully() {
            String src = "π x (`ord";
            List<Token> spans = Relix.tokens(src);

            assertThat(spans).isNotEmpty();
            assertThat(spans.get(spans.size() - 1).kind())
                    .isEqualTo(TokenKind.INCOMPLETE);
        }
    }
}

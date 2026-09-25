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

import com.darkcollective.relix.parser.Lexer;
import com.darkcollective.relix.parser.ParseException;
import com.darkcollective.relix.parser.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link Relix#tokens}: the grammar's strict lexer, made lenient.
 *
 * <p>The lexer throws on an unterminated string, a half-typed operator or a stray
 * character, which an editor meets on every keystroke. This drives it token by token and,
 * the moment it throws, reports the rest of the text as one {@link TokenKind#INCOMPLETE}
 * token. The lexer also skips comments without reporting them, so the gaps between
 * tokens are scanned and their comments reported too.
 */
final class Tokens {

    private Tokens() {
    }

    static List<Token> of(String input) {
        List<Token> spans = new ArrayList<>();
        if (input == null || input.isEmpty()) {
            return spans;
        }

        int[] lineStarts = lineStarts(input);
        Lexer lexer = new Lexer(input);
        int cursor = 0; // offset of the first not-yet-emitted character

        while (true) {
            com.darkcollective.relix.parser.Token token;
            try {
                token = lexer.next();
            } catch (ParseException e) {
                int errorAt = clamp(offsetOf(e.line(), e.column(), lineStarts), cursor, input.length());
                // Comments may precede the error within the skipped gap.
                scanComments(input, cursor, errorAt, spans);
                if (errorAt < input.length()) {
                    spans.add(new Token(errorAt, input.length(), TokenKind.INCOMPLETE));
                }
                break;
            }

            if (token.type() == TokenType.EOF) {
                // Trailing whitespace/comments after the last real token.
                scanComments(input, cursor, input.length(), spans);
                break;
            }

            int start = clamp(offsetOf(token.line(), token.column(), lineStarts), cursor, input.length());
            int end = Math.min(start + token.lexeme().length(), input.length());

            // The gap between the previous token and this one is whitespace +
            // comments (the strict lexer accepts nothing else there).
            scanComments(input, cursor, start, spans);
            spans.add(new Token(start, end, kind(token.type())));
            cursor = end;
        }

        return spans;
    }

    /**
     * Appends a {@link TokenKind#COMMENT} token for each {@code --} line comment
     * and {@code /* *}{@code /} block comment found in {@code input[from, to)}.
     * Everything else in the range is whitespace and carries no span.
     */
    private static void scanComments(String input, int from, int to, List<Token> spans) {
        int i = from;
        while (i < to) {
            char c = input.charAt(i);
            if (c == '-' && i + 1 < to && input.charAt(i + 1) == '-') {
                int j = i + 2;
                while (j < to && input.charAt(j) != '\n') {
                    j++;
                }
                spans.add(new Token(i, j, TokenKind.COMMENT));
                i = j;
            } else if (c == '/' && i + 1 < to && input.charAt(i + 1) == '*') {
                int j = i + 2;
                int close = -1;
                while (j < to - 1) {
                    if (input.charAt(j) == '*' && input.charAt(j + 1) == '/') {
                        close = j + 2;
                        break;
                    }
                    j++;
                }
                int end = (close >= 0) ? close : to; // unterminated block comment
                spans.add(new Token(i, end, TokenKind.COMMENT));
                i = end;
            } else {
                i++;
            }
        }
    }

    /**
     * Converts a 1-based (line, column) — as carried by the lexer's tokens and its
     * {@link ParseException} — into a 0-based character offset. The lexer counts
     * line and column in UTF-16 code units, exactly as {@link String#charAt} indexes,
     * so the arithmetic is faithful.
     */
    private static int offsetOf(int line, int column, int[] lineStarts) {
        int base = (line >= 1 && line < lineStarts.length) ? lineStarts[line] : 0;
        return base + (column - 1);
    }

    /** The 0-based start offset of each 1-based line; index 0 is unused. */
    private static int[] lineStarts(String s) {
        int lines = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                lines++;
            }
        }
        int[] starts = new int[lines + 1];
        starts[1] = 0;
        int line = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                starts[++line] = i + 1;
            }
        }
        return starts;
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(value, hi));
    }

    /**
     * A lexer token type's highlighting category. Exhaustive, so a new token type fails
     * to compile until it is classified.
     */
    static TokenKind kind(TokenType type) {
        return switch (type) {
            case IDENTIFIER -> TokenKind.IDENTIFIER;
            case STRING -> TokenKind.STRING;
            case NUMBER -> TokenKind.NUMBER;

            // Reserved words that read as keywords rather than operators.
            case TRUE, FALSE, UNIT, EMPTY,
                 DATE, TIME, TIMESTAMP, DURATION,
                 AS, HOPS, OVER, ASC, DESC,
                 WITHIN, TIES, SEED, ALLOCATE, MAXIMIZE, MINIMIZE, SUBJECT, TO,
                 PER, ROWS, WITH, ORDINALITY, EXACT, BY, USING, FOR, GAP, VIA, ORDER,
                 NULL,
                 SUM, AVG, COUNT, MIN, MAX, COLLECT, ARGMAX, ARGMIN -> TokenKind.KEYWORD;

            // Relational-algebra operators (unary, binary, set, advanced) +
            // arithmetic glyphs.
            case PROJECT, SELECT, RENAME, AGGREGATION, SORT, LIMIT, DISTINCT, UNNEST,
                 CLOSURE, RCLOSURE, CLUSTER, PATH, FIX, KLEENE_PLUS,
                 NATURAL_JOIN, THETA_JOIN, LEFT_OUTER_JOIN, RIGHT_OUTER_JOIN,
                 FULL_OUTER_JOIN, SEMI_JOIN, ANTI_JOIN, UNIVERSAL_SEMI_JOIN,
                 ASOF_JOIN, IJOIN, LATERAL,
                 PRODUCT, UNION, UNION_ALL, OUTER_UNION, DIFFERENCE, INTERSECTION,
                 DIVISION, SYMMETRIC_DIFFERENCE, COMPOSITION, FORALL,
                 SAMPLE, SOLVE, OPTIMIZE, TOP, COVER, DOWNSAMPLE,
                 ROLLING, WINDOW, SESSIONIZE, TRACE, PIVOT, UNPIVOT, TREE, WHY,
                 PLUS, MINUS, MULTIPLY, DIVIDE -> TokenKind.OPERATOR;

            case AND, OR, NOT -> TokenKind.LOGICAL;

            case EQUAL, NOT_EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL,
                 LIKE, IS, ELEMENT_OF, NOT_ELEMENT_OF -> TokenKind.COMPARISON;

            case LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET -> TokenKind.BRACKET;

            case COMMA, DOT, ARROW, UNDIRECTED_EDGE, COLON -> TokenKind.PUNCTUATION;

            // The lexer wrapper never classifies EOF; map it for switch totality.
            case EOF -> TokenKind.PUNCTUATION;
        };
    }
}

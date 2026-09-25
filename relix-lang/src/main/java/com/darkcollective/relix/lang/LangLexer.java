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
package com.darkcollective.relix.lang;

import java.util.Locale;
import java.util.Map;

/**
 * Hand-written lexer for the relix scripting language.
 *
 * <p>The lexer operates on an in-memory string and produces {@link LangToken}
 * objects on demand via {@link #next()}.  It also exposes two raw-extraction
 * methods used by the parser to handle embedded sublanguages:
 * <ul>
 *   <li>{@link #consumeRawBraceBlock()} — called immediately after a
 *       {@link LangTokenType#LBRACE} token has been consumed; reads characters
 *       until the matching closing <code>&#125;</code> (tracking nested braces), and
 *       returns the enclosed text together with its start position.  Used to
 *       extract RA expression bodies for delegation to
 *       {@link com.darkcollective.relix.parser.RelAlgebraParser}.</li>
 *   <li>{@link #consumeRawBracketBlock()} — same but for {@code [ ... ]}
 *       bracket blocks; used to extract inline table content.</li>
 * </ul>
 *
 * <h2>Comment syntax</h2>
 * <ul>
 *   <li>Line comments: {@code -- ...} (SQL style, to end of line)</li>
 *   <li>Block comments: {@code /* ... *}{@code /} (C style, may span lines)</li>
 * </ul>
 *
 * <h2>Delimited names</h2>
 * A backtick-delimited name ({@code `unit-price`}) lexes as a
 * {@link LangTokenType#DELIMITED_IDENTIFIER}, a doubled backtick standing for one,
 * exactly as the expression grammar reads it.
 * <h2>String literals</h2>
 * Double-quoted strings; {@code \"} is the only recognised escape sequence
 * inside them. {@code ${VAR}} placeholders are preserved verbatim in the
 * returned value string — they are resolved at runtime by the execution layer.
 */
final class LangLexer {

    // Reserved keywords, keyed by lower-cased text.  Keyword matching is always
    // case-insensitive (the scanner lower-cases the lexeme before lookup), so every
    // key here is lower-cased and a keyword is reserved regardless of how it is
    // written — `RELATION`, `relation`, and `Relation` all yield the same token.
    // Casing in source is purely stylistic (e.g. UPPERCASE type keywords beside
    // values); it never affects matching.
    private static final Map<String, LangTokenType> KEYWORDS = Map.ofEntries(
            Map.entry("namespace", LangTokenType.NAMESPACE),
            Map.entry("env",       LangTokenType.ENV),
            Map.entry("from",      LangTokenType.FROM),
            Map.entry("using",     LangTokenType.USING),
            Map.entry("import",    LangTokenType.IMPORT),
            Map.entry("source",     LangTokenType.SOURCE),
            Map.entry("connection", LangTokenType.CONNECTION),
            Map.entry("relation",  LangTokenType.RELATION),
            Map.entry("function",  LangTokenType.FUNCTION),
            Map.entry("private",   LangTokenType.PRIVATE),
            Map.entry("def",       LangTokenType.DEF),
            Map.entry("query",     LangTokenType.QUERY),
            Map.entry("relate",     LangTokenType.RELATE),
            Map.entry("symmetric",  LangTokenType.SYMMETRIC),
            Map.entry("references", LangTokenType.REFERENCES),
            Map.entry("http",      LangTokenType.HTTP),
            Map.entry("database",  LangTokenType.DATABASE),
            Map.entry("csv",       LangTokenType.CSV),
            Map.entry("json",      LangTokenType.JSON),
            Map.entry("generator", LangTokenType.GENERATOR),
            Map.entry("in",        LangTokenType.IN),
            Map.entry("out",       LangTokenType.OUT),
            Map.entry("as",        LangTokenType.AS),
            Map.entry("at",        LangTokenType.AT),
            Map.entry("path",      LangTokenType.PATH),
            Map.entry("header",    LangTokenType.HEADER),
            Map.entry("headers",   LangTokenType.HEADERS),
            Map.entry("required",  LangTokenType.REQUIRED),
            Map.entry("default",   LangTokenType.DEFAULT),
            Map.entry("url",       LangTokenType.URL),
            Map.entry("method",    LangTokenType.METHOD),
            Map.entry("extract",   LangTokenType.EXTRACT),
            Map.entry("paginate",  LangTokenType.PAGINATE),
            Map.entry("schema",    LangTokenType.SCHEMA),
            Map.entry("table",     LangTokenType.TABLE),
            // HTTP read methods (conventionally written UPPERCASE).  Relix is
            // read-only by design — the mutating methods are intentionally absent,
            // so "put"/"patch"/"delete"/"head" lex as ordinary identifiers.
            Map.entry("get",    LangTokenType.GET),
            Map.entry("post",   LangTokenType.POST),
            // Scalar type keywords (conventionally written UPPERCASE)
            Map.entry("number", LangTokenType.NUMBER),
            Map.entry("string", LangTokenType.STRING),
            Map.entry("boolean", LangTokenType.BOOLEAN),
            Map.entry("any",    LangTokenType.ANY),
            Map.entry("date",      LangTokenType.DATE),
            Map.entry("time",      LangTokenType.TIME),
            Map.entry("timestamp", LangTokenType.TIMESTAMP),
            Map.entry("duration",  LangTokenType.DURATION),
            // Boolean literals
            Map.entry("true",  LangTokenType.TRUE),
            Map.entry("false", LangTokenType.FALSE)
    );

    private final String input;
    private final String filePath;
    private int pos;   // current character index
    private int line;  // 1-based
    private int col;   // 1-based

    LangLexer(String input) {
        this(input, "<stdin>");
    }

    LangLexer(String source, String filePath) {
        this.input    = source;
        this.filePath = java.util.Objects.requireNonNull(filePath, "filePath");
        this.pos      = 0;
        this.line     = 1;
        this.col      = 1;
    }

    /** Returns the full source text (used by raw-block extraction). */
    String input() {
        return input;
    }

    // =========================================================================
    // Token scanning
    // =========================================================================

    /**
     * Returns the next token, advancing past it.  Always returns
     * {@link LangTokenType#EOF} at end of input (never {@code null}).
     */
    LangToken next() {
        skipWhitespaceAndComments();

        if (pos >= input.length()) {
            return new LangToken(LangTokenType.EOF, "", line, col);
        }

        int tokLine = line;
        int tokCol = col;
        char c = input.charAt(pos);

        // String literal
        if (c == '"') {
            return scanString(tokLine, tokCol);
        }

        // Number literal (positive integers and decimals only in the scripting language;
        // '-' is always a DASH token and negative numbers are not part of this grammar)
        if (Character.isDigit(c)) {
            return scanNumber(tokLine, tokCol);
        }

        if (c == '`') {
            return scanDelimitedIdentifier(tokLine, tokCol);
        }

        // Identifier or keyword
        if (Character.isLetter(c) || c == '_') {
            return scanIdentifierOrKeyword(tokLine, tokCol);
        }

        // Single-char and two-char punctuation
        advance();
        return switch (c) {
            case ':' -> {
                if (pos < input.length() && input.charAt(pos) == '=') {
                    advance();
                    yield new LangToken(LangTokenType.ASSIGN, ":=", tokLine, tokCol);
                }
                yield new LangToken(LangTokenType.COLON, ":", tokLine, tokCol);
            }
            case ';' -> new LangToken(LangTokenType.SEMICOLON, ";", tokLine, tokCol);
            case ',' -> new LangToken(LangTokenType.COMMA, ",", tokLine, tokCol);
            case '(' -> new LangToken(LangTokenType.LPAREN, "(", tokLine, tokCol);
            case ')' -> new LangToken(LangTokenType.RPAREN, ")", tokLine, tokCol);
            case '{' -> new LangToken(LangTokenType.LBRACE, "{", tokLine, tokCol);
            case '}' -> new LangToken(LangTokenType.RBRACE, "}", tokLine, tokCol);
            case '[' -> new LangToken(LangTokenType.LBRACKET, "[", tokLine, tokCol);
            case ']' -> new LangToken(LangTokenType.RBRACKET, "]", tokLine, tokCol);
            case '|' -> new LangToken(LangTokenType.PIPE, "|", tokLine, tokCol);
            case '-' -> {
                // '--' line comments were already consumed by
                // skipWhitespaceAndComments(), so a '-' here is either the
                // relationship arrow '->' or a bare markdown DASH.
                if (pos < input.length() && input.charAt(pos) == '>') {
                    advance();
                    yield new LangToken(LangTokenType.ARROW, "->", tokLine, tokCol);
                }
                yield new LangToken(LangTokenType.DASH, "-", tokLine, tokCol);
            }
            case '.' -> {
                if (pos < input.length() && input.charAt(pos) == '.') {
                    advance();
                    yield new LangToken(LangTokenType.RANGE, "..", tokLine, tokCol);
                }
                yield new LangToken(LangTokenType.DOT, ".", tokLine, tokCol);
            }
            // '/*' block comments were already consumed by
            // skipWhitespaceAndComments(), so a '/' here is the name / inverse-name
            // separator of a relate statement.
            case '/' -> new LangToken(LangTokenType.SLASH, "/", tokLine, tokCol);
            case '*' -> new LangToken(LangTokenType.STAR, "*", tokLine, tokCol);
            default  -> throw new LangParseException(
                    "Unexpected character '" + c + "'", tokLine, tokCol);
        };
    }

    // =========================================================================
    // Raw block extraction
    // =========================================================================

    /**
     * A raw text block extracted from the source together with the position of
     * its first character (the character immediately after the opening
     * delimiter that was already consumed).
     *
     * @param text      the raw content between the delimiters, exclusive
     * @param startLine 1-based line of the first content character
     * @param startCol  1-based column of the first content character
     */
    record RawBlock(String text, int startLine, int startCol) {}

    /**
     * Extracts a brace-balanced block of raw source text, starting at the
     * current lexer position (which must be immediately after an already-
     * consumed <code>&#123;</code>).
     *
     * <p>Brace depth tracking honours nested braces but not string literals
     * (i.e. braces inside strings are counted — callers that need to delegate
     * to {@link com.darkcollective.relix.parser.RelAlgebraParser} pass the RA
     * grammar which itself handles strings correctly).
     *
     * @return the extracted block
     * @throws LangParseException if the input ends before the matching <code>&#125;</code>
     */
    RawBlock consumeRawBraceBlock() {
        int startLine = line;
        int startCol = col;
        int start = pos;
        int depth = 1;

        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    String text = input.substring(start, pos);
                    advance(); // consume '}'
                    return new RawBlock(text, startLine, startCol);
                }
            } else if (c == '"') {
                // Skip string literal content so its braces are not counted
                advance();
                while (pos < input.length() && input.charAt(pos) != '"') {
                    if (input.charAt(pos) == '\\') {
                        advance(); // skip escape character
                    }
                    advance();
                }
                // pos is now on the closing '"' or at end
            }
            advance();
        }
        throw new LangParseException("Unterminated brace block", startLine, startCol);
    }

    /**
     * Extracts a bracket-balanced block of raw source text, starting at the
     * current lexer position (immediately after an already-consumed {@code [}).
     *
     * @return the extracted block
     * @throws LangParseException if the input ends before the matching {@code ]}
     */
    RawBlock consumeRawBracketBlock() {
        int startLine = line;
        int startCol = col;
        int start = pos;
        int depth = 1;

        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    String text = input.substring(start, pos);
                    advance(); // consume ']'
                    return new RawBlock(text, startLine, startCol);
                }
            } else if (c == '"') {
                advance();
                while (pos < input.length() && input.charAt(pos) != '"') {
                    if (input.charAt(pos) == '\\') {
                        advance();
                    }
                    advance();
                }
            }
            advance();
        }
        throw new LangParseException("Unterminated bracket block", startLine, startCol);
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private LangToken scanString(int tokLine, int tokCol) {
        advance(); // consume opening '"'
        StringBuilder sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == '"') {
                advance(); // consume closing '"'
                return new LangToken(LangTokenType.STRING_LIT, sb.toString(), tokLine, tokCol);
            }
            if (c == '\\' && pos + 1 < input.length()) {
                char next = input.charAt(pos + 1);
                if (next == '"') {
                    sb.append('"');
                    advance();
                    advance();
                    continue;
                }
            }
            sb.append(c);
            advance();
        }
        throw new LangParseException("Unterminated string literal", tokLine, tokCol);
    }

    private LangToken scanNumber(int tokLine, int tokCol) {
        int start = pos;
        while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
            advance();
        }
        // Optional decimal part
        if (pos < input.length() && input.charAt(pos) == '.'
                && pos + 1 < input.length() && Character.isDigit(input.charAt(pos + 1))) {
            advance(); // consume '.'
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                advance();
            }
        }
        return new LangToken(LangTokenType.NUMBER_LIT, input.substring(start, pos), tokLine, tokCol);
    }

    /**
     * Scans a backtick-delimited name. The body runs to the closing backtick, and a
     * doubled backtick is a literal one. The name may not be empty or span a line.
     */
    private LangToken scanDelimitedIdentifier(int tokLine, int tokCol) {
        advance();   // opening backtick
        StringBuilder name = new StringBuilder();
        while (true) {
            if (pos >= input.length() || input.charAt(pos) == '\n') {
                throw new LangParseException("Unterminated delimited name", tokLine, tokCol);
            }
            char c = input.charAt(pos);
            advance();
            if (c == '`') {
                if (pos < input.length() && input.charAt(pos) == '`') {
                    advance();
                    name.append('`');
                    continue;
                }
                break;
            }
            name.append(c);
        }
        if (name.toString().isBlank()) {
            throw new LangParseException("Empty delimited name", tokLine, tokCol);
        }
        return new LangToken(LangTokenType.DELIMITED_IDENTIFIER, name.toString(), tokLine, tokCol);
    }

    private LangToken scanIdentifierOrKeyword(int tokLine, int tokCol) {
        int start = pos;
        while (pos < input.length()
                && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) {
            advance();
        }
        String word = input.substring(start, pos);
        // Keyword matching is always case-insensitive; the original lexeme is
        // preserved on the token so identifiers keep their source casing.
        LangTokenType type = KEYWORDS.getOrDefault(
                word.toLowerCase(Locale.ROOT), LangTokenType.IDENTIFIER);
        return new LangToken(type, word, tokLine, tokCol);
    }

    private void skipWhitespaceAndComments() {
        while (pos < input.length()) {
            char c = input.charAt(pos);

            // Whitespace
            if (Character.isWhitespace(c)) {
                advance();
                continue;
            }

            // Line comment  -- ...
            if (c == '-' && pos + 1 < input.length() && input.charAt(pos + 1) == '-') {
                advance();
                advance();
                while (pos < input.length() && input.charAt(pos) != '\n') {
                    advance();
                }
                continue;
            }

            // Block comment  /* ... */
            if (c == '/' && pos + 1 < input.length() && input.charAt(pos + 1) == '*') {
                int startLine = line;
                int startCol = col;
                advance(); // skip '/'
                advance(); // skip '*'
                boolean closed = false;
                while (pos < input.length()) {
                    if (input.charAt(pos) == '*' && pos + 1 < input.length()
                            && input.charAt(pos + 1) == '/') {
                        advance(); // skip '*'
                        advance(); // skip '/'
                        closed = true;
                        break;
                    }
                    advance();
                }
                if (!closed) {
                    throw new LangParseException("Unterminated block comment", startLine, startCol);
                }
                continue;
            }

            break;
        }
    }

    /** Advances {@link #pos} by one character, updating {@link #line}/{@link #col}. */
    private void advance() {
        if (pos < input.length() && input.charAt(pos) == '\n') {
            line++;
            col = 1;
        } else {
            col++;
        }
        pos++;
    }
}

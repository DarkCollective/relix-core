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

import com.darkcollective.relix.parser.ParseException;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Tokenizes a relational algebra expression string into a stream of
 * {@link Token}s consumed by {@link RelAlgebraParser}.
 *
 * <p>The lexer recognises all Unicode RA operator symbols (π σ ρ γ τ λ δ ω
 * ⋈ ⨝ ⟕ ⟖ ⟗ ⋉ ▷ ∪ ⊎ ⊔ − ∩ ÷ ∆ ∘ ∀ × ∧ ∨ ¬ → ⊥ ∈ ∉ ≠ ≤ ≥ ⁺) and their ASCII keyword
 * equivalents (PROJECT, SELECT, RENAME, …) as defined by {@link TokenType}.
 * The superscript-plus {@code ⁺} is the Kleene-plus postfix glyph (transitive
 * closure); the plain {@code *} stays {@code MULTIPLY} and is interpreted as the
 * Kleene-star postfix glyph contextually by the parser when it follows a relation
 * expression.
 * Single-quoted and double-quoted string literals are accepted; escape
 * sequences {@code \\}, {@code \"}, {@code \n}, {@code \t}, {@code \r} are
 * decoded inside string literals.
 *
 * <p>Comments are SQL's: {@code --} to the end of the line, and {@code /* *}{@code /}
 * for a block. They are the forms the script grammar uses, which matters because a
 * {@code .relix} script hands an embedded expression body to this lexer verbatim: a
 * comment written one way outside a brace and another way inside it would be a rule
 * about braces rather than about comments. {@code //} is therefore <em>not</em> a
 * comment but two division operators, and reads as a syntax error wherever it appears.
 *
 * <p>Line and column tracking is 1-based.  The optional {@code startLine} and
 * {@code startColumn} constructor parameters allow the lexer to continue a
 * line/column count from a mid-file offset, which is needed when the language
 * parser delegates an embedded RA body to this lexer.
 *
 * <p>The lexer is not thread-safe and is intended for single-use: construct,
 * call {@link #next()} repeatedly until {@link TokenType#EOF}, then
 * discard.
 */
public final class Lexer {

    // Reserved keywords, keyed by their lowercase form.  Keyword matching is
    // case-insensitive (same as {@code LangLexer}): the lexeme is lowercased via
    // {@code Locale.ROOT} before the map is consulted, so {@code SELECT},
    // {@code select}, and {@code Select} all produce the same token.  The original
    // source casing is preserved in the returned {@link Token}'s lexeme field.
    // Several keywords are aliases for Unicode operator token types (e.g.
    // {@code group} → AGGREGATION, {@code join} → NATURAL_JOIN, {@code in} →
    // ELEMENT_OF).  Anything not found falls through to an IDENTIFIER token.
    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
            Map.entry("true",  TokenType.TRUE),
            Map.entry("false", TokenType.FALSE),
            // Nullary truth-relation literals. The descriptive
            // spellings lead; DEE/DUM are the Tutorial D lineage aliases.
            Map.entry("unit",  TokenType.UNIT),
            Map.entry("dee",   TokenType.UNIT),
            Map.entry("empty", TokenType.EMPTY),
            Map.entry("dum",   TokenType.EMPTY),
            Map.entry("date",      TokenType.DATE),
            Map.entry("time",      TokenType.TIME),
            Map.entry("timestamp", TokenType.TIMESTAMP),
            Map.entry("duration",  TokenType.DURATION),
            Map.entry("sum",     TokenType.SUM),
            Map.entry("avg",     TokenType.AVG),
            Map.entry("count",   TokenType.COUNT),
            Map.entry("min",     TokenType.MIN),
            Map.entry("max",     TokenType.MAX),
            Map.entry("collect", TokenType.COLLECT),
            Map.entry("argmax",  TokenType.ARGMAX),
            Map.entry("argmin",  TokenType.ARGMIN),
            Map.entry("asc",     TokenType.ASC),
            Map.entry("desc",    TokenType.DESC),
            Map.entry("project",  TokenType.PROJECT),
            Map.entry("select",   TokenType.SELECT),
            Map.entry("rename",   TokenType.RENAME),
            Map.entry("group",    TokenType.AGGREGATION),
            Map.entry("sort",     TokenType.SORT),
            Map.entry("limit",    TokenType.LIMIT),
            Map.entry("distinct", TokenType.DISTINCT),
            Map.entry("unnest",   TokenType.UNNEST),
            Map.entry("with",       TokenType.WITH),
            Map.entry("ordinality", TokenType.ORDINALITY),
            Map.entry("closure",  TokenType.CLOSURE),
            Map.entry("rclosure", TokenType.RCLOSURE),
            Map.entry("cluster",  TokenType.CLUSTER),
            Map.entry("path",     TokenType.PATH),
            Map.entry("hops",     TokenType.HOPS),
            Map.entry("as",       TokenType.AS),
            Map.entry("over",     TokenType.OVER),
            Map.entry("fix",      TokenType.FIX),
            Map.entry("forall",   TokenType.FORALL),
            Map.entry("sample",   TokenType.SAMPLE),
            Map.entry("seed",     TokenType.SEED),
            Map.entry("solve",    TokenType.SOLVE),
            Map.entry("optimize", TokenType.OPTIMIZE),
            Map.entry("allocate", TokenType.ALLOCATE),
            Map.entry("maximize", TokenType.MAXIMIZE),
            Map.entry("minimize", TokenType.MINIMIZE),
            Map.entry("subject",  TokenType.SUBJECT),
            Map.entry("to",       TokenType.TO),
            Map.entry("top",      TokenType.TOP),
            Map.entry("per",      TokenType.PER),
            Map.entry("rows",     TokenType.ROWS),
            Map.entry("cover",      TokenType.COVER),
            Map.entry("exact",      TokenType.EXACT),
            Map.entry("downsample", TokenType.DOWNSAMPLE),
            Map.entry("by",         TokenType.BY),
            Map.entry("using",      TokenType.USING),
            Map.entry("for",        TokenType.FOR),
            Map.entry("lateral",    TokenType.LATERAL),
            Map.entry("rolling",    TokenType.ROLLING),
            Map.entry("window",     TokenType.WINDOW),
            Map.entry("sessionize", TokenType.SESSIONIZE),
            Map.entry("gap",        TokenType.GAP),
            Map.entry("trace",      TokenType.TRACE),
            Map.entry("via",        TokenType.VIA),
            Map.entry("pivot",      TokenType.PIVOT),
            Map.entry("unpivot",    TokenType.UNPIVOT),
            Map.entry("tree",       TokenType.TREE),
            Map.entry("order",      TokenType.ORDER),
            Map.entry("why",        TokenType.WHY),
            Map.entry("join",  TokenType.NATURAL_JOIN),
            Map.entry("semi",  TokenType.SEMI_JOIN),
            Map.entry("anti",  TokenType.ANTI_JOIN),
            // SQL-prior outer-join aliases: friendlier ASCII
            // synonyms for the |>< / ><| / |><| symbol forms, which the relix-ask
            // model spontaneously emits.
            Map.entry("ljoin", TokenType.LEFT_OUTER_JOIN),
            Map.entry("rjoin", TokenType.RIGHT_OUTER_JOIN),
            Map.entry("fjoin", TokenType.FULL_OUTER_JOIN),
            Map.entry("usemi", TokenType.UNIVERSAL_SEMI_JOIN),
            Map.entry("asof",  TokenType.ASOF_JOIN),
            Map.entry("ijoin", TokenType.IJOIN),
            Map.entry("within", TokenType.WITHIN),
            Map.entry("ties",  TokenType.TIES),
            Map.entry("cross", TokenType.PRODUCT),
            Map.entry("union", TokenType.UNION),
            Map.entry("uall",  TokenType.UNION_ALL),
            Map.entry("ounion", TokenType.OUTER_UNION),
            Map.entry("diff",  TokenType.DIFFERENCE),
            // SQL-prior aliases: pure synonyms parsing to the same
            // token type — MINUS/EXCEPT → set difference, INTERSECT → intersection.
            // `minus` the keyword is the set operator; the arithmetic `-` char
            // still lexes to its own MINUS token, so the two never collide.
            Map.entry("minus",  TokenType.DIFFERENCE),
            Map.entry("except", TokenType.DIFFERENCE),
            Map.entry("inter", TokenType.INTERSECTION),
            Map.entry("intersect", TokenType.INTERSECTION),
            Map.entry("div",   TokenType.DIVISION),
            Map.entry("symdiff", TokenType.SYMMETRIC_DIFFERENCE),
            Map.entry("compose", TokenType.COMPOSITION),
            Map.entry("and",  TokenType.AND),
            Map.entry("or",   TokenType.OR),
            Map.entry("not",  TokenType.NOT),
            Map.entry("null", TokenType.NULL),
            // `x IS NULL` / `x IS NOT NULL` — SQL-prior null predicate.
            Map.entry("is",   TokenType.IS),
            Map.entry("in",   TokenType.ELEMENT_OF),
            Map.entry("like", TokenType.LIKE)
    );

    /**
     * The reserved words that the lexer maps to a non-identifier token — i.e.
     * every word that cannot be used as a bare name and must be backtick-delimited
     * (or is auto-delimited by a pretty-printer) to appear in name position.
     * Case-insensitive lowercase forms. Exposed so pretty-printers and tooling can
     * agree with the lexer on what needs delimiting without duplicating the map.
     *
     * @return an unmodifiable view of the reserved keyword set (lowercase)
     */
    public static java.util.Set<String> reservedWords() {
        return KEYWORDS.keySet();
    }

    private final String input;
    private int index;
    private int line;
    private int column;

    public Lexer(String input) {
        this(input, 1, 1);
    }

    public Lexer(String input, int startLine, int startColumn) {
        this.input = input;
        this.line = startLine;
        this.column = startColumn;
    }

    public Lexer(InputStream input) throws IOException {
        this(input, 1, 1);
    }

    public Lexer(InputStream input, int startLine, int startColumn) throws IOException {
        this(readInputStream(input), startLine, startColumn);
    }

    private static String readInputStream(InputStream input) throws IOException {
        return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    public String input() {
        return input;
    }

    public Token next() {
        skipWhitespace();

        int startIndex = index;
        int startLine = line;
        int startColumn = column;

        if (isAtEnd()) {
            return new Token(TokenType.EOF, "", null, startLine, startColumn);
        }

        char c = advance();

        return switch (c) {
            case 'π' -> token(TokenType.PROJECT, startIndex, startLine, startColumn);
            case 'σ' -> token(TokenType.SELECT, startIndex, startLine, startColumn);
            case 'ρ' -> token(TokenType.RENAME, startIndex, startLine, startColumn);
            case 'γ' -> token(TokenType.AGGREGATION, startIndex, startLine, startColumn);
            case 'τ' -> token(TokenType.SORT, startIndex, startLine, startColumn);
            case 'λ' -> token(TokenType.LIMIT, startIndex, startLine, startColumn);
            case 'δ' -> token(TokenType.DISTINCT, startIndex, startLine, startColumn);
            case 'μ' -> token(TokenType.UNNEST, startIndex, startLine, startColumn);
            case 'ω' -> token(TokenType.WHY, startIndex, startLine, startColumn);
            case '⋈' -> token(TokenType.NATURAL_JOIN, startIndex, startLine, startColumn);
            case '⨝' -> token(TokenType.THETA_JOIN, startIndex, startLine, startColumn);
            case '⟕' -> token(TokenType.LEFT_OUTER_JOIN, startIndex, startLine, startColumn);
            case '⟖' -> token(TokenType.RIGHT_OUTER_JOIN, startIndex, startLine, startColumn);
            case '⟗' -> token(TokenType.FULL_OUTER_JOIN, startIndex, startLine, startColumn);
            case '⋉' -> token(TokenType.SEMI_JOIN, startIndex, startLine, startColumn);
            case '▷' -> token(TokenType.ANTI_JOIN, startIndex, startLine, startColumn);
            case '×' -> token(TokenType.PRODUCT, startIndex, startLine, startColumn);
            case '∪' -> token(TokenType.UNION, startIndex, startLine, startColumn);
            case '⊎' -> token(TokenType.UNION_ALL, startIndex, startLine, startColumn);
            case '⊔' -> token(TokenType.OUTER_UNION, startIndex, startLine, startColumn);
            case '−' -> token(TokenType.DIFFERENCE, startIndex, startLine, startColumn);
            case '∩' -> token(TokenType.INTERSECTION, startIndex, startLine, startColumn);
            case '÷' -> token(TokenType.DIVISION, startIndex, startLine, startColumn);
            case '∆' -> token(TokenType.SYMMETRIC_DIFFERENCE, startIndex, startLine, startColumn);
            case '∘' -> token(TokenType.COMPOSITION, startIndex, startLine, startColumn);
            case '⁺' -> token(TokenType.KLEENE_PLUS, startIndex, startLine, startColumn);
            case '∀' -> token(TokenType.FORALL, startIndex, startLine, startColumn);
            case '∧' -> token(TokenType.AND, startIndex, startLine, startColumn);
            case '∨' -> token(TokenType.OR, startIndex, startLine, startColumn);
            case '¬' -> token(TokenType.NOT, startIndex, startLine, startColumn);
            case '=' -> token(TokenType.EQUAL, startIndex, startLine, startColumn);
            case '≠' -> token(TokenType.NOT_EQUAL, startIndex, startLine, startColumn);
            case '!' -> {
                if (match('=')) {
                    yield token(TokenType.NOT_EQUAL, startIndex, startLine, startColumn);
                }
                throw new ParseException(
                        "Unexpected character",
                        input,
                        new Token(TokenType.IDENTIFIER, "!", null, startLine, startColumn)
                );
            }
            case '<' -> {
                if (match('=')) {
                    yield token(TokenType.LESS_EQUAL, startIndex, startLine, startColumn);
                }
                // Both characters are checked before either is consumed: `x < -1` is a
                // comparison against a negative literal, so eating the '-' on the strength
                // of seeing it would misread that as the start of an undirected edge.
                if (!isAtEnd() && peek() == '-' && peekNext() == '>') {
                    advance();
                    advance();
                    yield token(TokenType.UNDIRECTED_EDGE, startIndex, startLine, startColumn);
                }
                yield token(TokenType.LESS, startIndex, startLine, startColumn);
            }
            case '≤' -> token(TokenType.LESS_EQUAL, startIndex, startLine, startColumn);
            case '>' -> {
                if (match('=')) {
                    yield token(TokenType.GREATER_EQUAL, startIndex, startLine, startColumn);
                }
                if (match('<')) {
                    if (match('|')) {
                        yield token(TokenType.RIGHT_OUTER_JOIN, startIndex, startLine, startColumn);
                    }
                    yield token(TokenType.THETA_JOIN, startIndex, startLine, startColumn);
                }
                yield token(TokenType.GREATER, startIndex, startLine, startColumn);
            }
            case '≥' -> token(TokenType.GREATER_EQUAL, startIndex, startLine, startColumn);
            case '⊥' -> token(TokenType.NULL, startIndex, startLine, startColumn);
            case '+' -> token(TokenType.PLUS, startIndex, startLine, startColumn);
            case '-' -> {
                if (match('>')) {
                    yield token(TokenType.ARROW, startIndex, startLine, startColumn);
                }
                yield token(TokenType.MINUS, startIndex, startLine, startColumn);
            }
            case '|' -> {
                if (!match('>') || !match('<')) {
                    throw new ParseException(
                            "Unexpected character",
                            input,
                            new Token(TokenType.IDENTIFIER, "|", null, startLine, startColumn)
                    );
                }
                if (match('|')) {
                    yield token(TokenType.FULL_OUTER_JOIN, startIndex, startLine, startColumn);
                }
                yield token(TokenType.LEFT_OUTER_JOIN, startIndex, startLine, startColumn);
            }
            case '*' -> token(TokenType.MULTIPLY, startIndex, startLine, startColumn);
            case '/' -> token(TokenType.DIVIDE, startIndex, startLine, startColumn);
            case '(' -> token(TokenType.LPAREN, startIndex, startLine, startColumn);
            case ')' -> token(TokenType.RPAREN, startIndex, startLine, startColumn);
            case ',' -> token(TokenType.COMMA, startIndex, startLine, startColumn);
            case '.' -> token(TokenType.DOT, startIndex, startLine, startColumn);
            case '→' -> token(TokenType.ARROW, startIndex, startLine, startColumn);
            case '↔' -> token(TokenType.UNDIRECTED_EDGE, startIndex, startLine, startColumn);
            case '"' -> stringToken(startIndex, startLine, startColumn, '"');
            case '\'' -> stringToken(startIndex, startLine, startColumn, '\'');
            case '`' -> delimitedIdentifierToken(startLine, startColumn);
            case '∈' -> token(TokenType.ELEMENT_OF, startIndex, startLine, startColumn);
            case '∉' -> token(TokenType.NOT_ELEMENT_OF, startIndex, startLine, startColumn);
            case '{' -> token(TokenType.LBRACE, startIndex, startLine, startColumn);
            case '}' -> token(TokenType.RBRACE, startIndex, startLine, startColumn);
            case '[' -> token(TokenType.LBRACKET, startIndex, startLine, startColumn);
            case ']' -> token(TokenType.RBRACKET, startIndex, startLine, startColumn);
            case ':' -> token(TokenType.COLON, startIndex, startLine, startColumn);
            default -> {
                if (isIdentifierStart(c)) {
                    yield identifierToken(startIndex, startLine, startColumn);
                }

                if (Character.isDigit(c)) {
                    yield numberToken(startIndex, startLine, startColumn);
                }

                throw new ParseException(
                        "Unexpected character",
                        input,
                        new Token(TokenType.IDENTIFIER, Character.toString(c), null, startLine, startColumn)
                );
            }
        };
    }

    private Token identifierToken(int startIndex, int startLine, int startColumn) {
        while (!isAtEnd() && isIdentifierPart(peek())) {
            advance();
        }

        String lexeme = input.substring(startIndex, index);

        TokenType type = KEYWORDS.getOrDefault(lexeme.toLowerCase(Locale.ROOT), TokenType.IDENTIFIER);
        return new Token(type, lexeme, null, startLine, startColumn);
    }

    /**
     * Scans a backtick-delimited identifier — the escape hatch for using a
     * reserved operator word (or any non-identifier-shaped text) as a plain name,
     * e.g. {@code `order`} references a relation named {@code order} rather than
     * invoking the ORDER/τ operator. The opening backtick has already been
     * consumed. The body runs to the closing backtick; a doubled {@code ``}
     * escapes a literal backtick. The emitted token is a plain
     * {@link TokenType#IDENTIFIER} whose lexeme is the decoded name (no
     * backticks) — so every downstream name consumer treats it like any other
     * identifier — positioned at the first body character so live highlighting
     * colours the name (the delimiters stay uncoloured).
     *
     * @throws ParseException if the identifier is unterminated (end of input or a
     *                        newline before the closing backtick) or empty
     */
    private Token delimitedIdentifierToken(int startLine, int startColumn) {
        int nameLine = line;
        int nameColumn = column;
        StringBuilder value = new StringBuilder();

        while (true) {
            if (isAtEnd() || peek() == '\n') {
                throw new ParseException(
                        "Unterminated delimited identifier",
                        input,
                        new Token(TokenType.IDENTIFIER, "`" + value, null, startLine, startColumn)
                );
            }
            char c = advance();
            if (c == '`') {
                if (!isAtEnd() && peek() == '`') {
                    advance();          // doubled backtick → literal backtick
                    value.append('`');
                    continue;
                }
                break;                  // closing backtick
            }
            value.append(c);
        }

        if (value.isEmpty()) {
            throw new ParseException(
                    "Empty delimited identifier",
                    input,
                    new Token(TokenType.IDENTIFIER, "``", null, startLine, startColumn)
            );
        }

        return new Token(TokenType.IDENTIFIER, value.toString(), null, nameLine, nameColumn);
    }

    private Token numberToken(int startIndex, int startLine, int startColumn) {
        while (!isAtEnd() && Character.isDigit(peek())) {
            advance();
        }

        if (!isAtEnd() && peek() == '.') {
            advance();

            if (isAtEnd() || !Character.isDigit(peek())) {
                throw new ParseException(
                        "Expected digit after decimal point",
                        input,
                        new Token(TokenType.NUMBER, input.substring(startIndex, index), null, startLine, startColumn)
                );
            }

            while (!isAtEnd() && Character.isDigit(peek())) {
                advance();
            }
        }

        return token(TokenType.NUMBER, startIndex, startLine, startColumn);
    }

    private Token stringToken(int startIndex, int startLine, int startColumn, char quote) {
        StringBuilder value = new StringBuilder();

        while (!isAtEnd() && peek() != quote) {
            char c = advance();

            if (c == '\\') {
                if (isAtEnd()) {
                    throw new ParseException(
                            "Unterminated string escape",
                            input,
                            new Token(TokenType.STRING, input.substring(startIndex, index), null, startLine, startColumn)
                    );
                }

                char escaped = advance();
                value.append(switch (escaped) {
                    case '"' -> '"';
                    case '\'' -> '\'';
                    case '\\' -> '\\';
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    default -> throw new ParseException(
                            "Unsupported string escape",
                            input,
                            new Token(TokenType.STRING, "\\" + escaped, null, line, column - 1)
                    );
                });
            } else {
                value.append(c);
            }
        }

        if (isAtEnd()) {
            throw new ParseException(
                    "Unterminated string literal",
                    input,
                    new Token(TokenType.STRING, input.substring(startIndex), null, startLine, startColumn)
            );
        }

        advance();
        return new Token(TokenType.STRING, input.substring(startIndex, index), value.toString(), startLine, startColumn);
    }

    private void skipWhitespace() {
        while (!isAtEnd()) {
            char c = peek();
            if (Character.isWhitespace(c)) {
                advance();
            } else if (c == '-' && peekNext() == '-') {
                advance();
                advance();
                while (!isAtEnd() && peek() != '\n') {
                    advance();
                }
            } else if (c == '/' && peekNext() == '*') {
                advance();
                advance();
                while (!isAtEnd() && !(peek() == '*' && peekNext() == '/')) {
                    advance();
                }
                if (!isAtEnd()) {
                    advance();
                    advance();
                }
            } else {
                break;
            }
        }
    }

    private Token token(TokenType type, int startIndex, int startLine, int startColumn) {
        return new Token(type, input.substring(startIndex, index), null, startLine, startColumn);
    }

    private boolean match(char expected) {
        if (isAtEnd() || peek() != expected) {
            return false;
        }

        advance();
        return true;
    }

    private char advance() {
        char c = input.charAt(index++);

        if (c == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }

        return c;
    }

    private char peek() {
        return input.charAt(index);
    }

    private char peekNext() {
        if (index + 1 >= input.length()) {
            return '\0';
        }
        return input.charAt(index + 1);
    }

    private boolean isAtEnd() {
        return index >= input.length();
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}

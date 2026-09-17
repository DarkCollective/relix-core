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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LangLexer")
class LangLexerTest {

    private static List<LangToken> tokenize(String input) {
        LangLexer lexer = new LangLexer(input);
        List<LangToken> tokens = new ArrayList<>();
        LangToken tok;
        do {
            tok = lexer.next();
            tokens.add(tok);
        } while (tok.type() != LangTokenType.EOF);
        return tokens;
    }

    private static LangToken single(String input) {
        return tokenize(input).get(0);
    }

    // =========================================================================
    // ADR-0024 relationship tokens
    // =========================================================================

    @Nested
    @DisplayName("relationship tokens (ADR-0024)")
    class RelationshipTokens {

        @Test
        @DisplayName("relate and symmetric are keywords, case-insensitively")
        void relateAndSymmetricKeywords() {
            assertThat(single("relate").type()).isEqualTo(LangTokenType.RELATE);
            assertThat(single("RELATE").type()).isEqualTo(LangTokenType.RELATE);
            assertThat(single("symmetric").type()).isEqualTo(LangTokenType.SYMMETRIC);
            assertThat(single("Symmetric").type()).isEqualTo(LangTokenType.SYMMETRIC);
        }

        @Test
        @DisplayName("relate and symmetric remain name-compatible for column names")
        void relateAndSymmetricAreNameCompatible() {
            assertThat(LangTokenType.RELATE.isNameCompatible()).isTrue();
            assertThat(LangTokenType.SYMMETRIC.isNameCompatible()).isTrue();
        }

        @Test
        @DisplayName("'.' lexes as DOT, '..' as RANGE")
        void dotAndRange() {
            assertThat(single(".").type()).isEqualTo(LangTokenType.DOT);
            assertThat(single("..").type()).isEqualTo(LangTokenType.RANGE);
        }

        @Test
        @DisplayName("'->' lexes as ARROW, a lone '-' stays DASH")
        void arrowAndDash() {
            assertThat(single("->").type()).isEqualTo(LangTokenType.ARROW);
            assertThat(single("-").type()).isEqualTo(LangTokenType.DASH);
        }

        @Test
        @DisplayName("'/' lexes as SLASH; '/* */' is still a comment")
        void slashAndBlockComment() {
            assertThat(single("/").type()).isEqualTo(LangTokenType.SLASH);
            assertThat(single("/* hidden */ query").type()).isEqualTo(LangTokenType.QUERY);
        }

        @Test
        @DisplayName("'*' lexes as STAR")
        void star() {
            assertThat(single("*").type()).isEqualTo(LangTokenType.STAR);
        }

        @Test
        @DisplayName("'1..50' lexes as NUMBER RANGE NUMBER (no decimal confusion)")
        void integerRange() {
            List<LangToken> tokens = tokenize("1..50");
            assertThat(tokens).extracting(LangToken::type).containsExactly(
                    LangTokenType.NUMBER_LIT, LangTokenType.RANGE,
                    LangTokenType.NUMBER_LIT, LangTokenType.EOF);
            assertThat(tokens.get(0).value()).isEqualTo("1");
            assertThat(tokens.get(2).value()).isEqualTo("50");
        }

        @Test
        @DisplayName("decimal literals still lex as one NUMBER_LIT")
        void decimalUnaffected() {
            LangToken tok = single("1.5");
            assertThat(tok.type()).isEqualTo(LangTokenType.NUMBER_LIT);
            assertThat(tok.value()).isEqualTo("1.5");
        }

        @Test
        @DisplayName("'Orders.order_id -> OrderItems.order_id [1..50]' token stream")
        void fullEndpointTokenStream() {
            assertThat(tokenize("Orders.order_id -> OrderItems.order_id [1..50]"))
                    .extracting(LangToken::type).containsExactly(
                            LangTokenType.IDENTIFIER, LangTokenType.DOT, LangTokenType.IDENTIFIER,
                            LangTokenType.ARROW,
                            LangTokenType.IDENTIFIER, LangTokenType.DOT, LangTokenType.IDENTIFIER,
                            LangTokenType.LBRACKET, LangTokenType.NUMBER_LIT, LangTokenType.RANGE,
                            LangTokenType.NUMBER_LIT, LangTokenType.RBRACKET,
                            LangTokenType.EOF);
        }
    }

    // =========================================================================
    // input() accessor
    // =========================================================================

    @Test
    @DisplayName("input() returns the original source string")
    void inputReturnsOriginalString() {
        LangLexer lexer = new LangLexer("query Users;");
        assertThat(lexer.input()).isEqualTo("query Users;");
    }

    // =========================================================================
    // Keywords
    // =========================================================================

    @Nested
    @DisplayName("keyword tokens")
    class KeywordTokens {

        @Test
        @DisplayName("All statement-level keywords are recognised")
        void statementKeywords() {
            assertThat(single("namespace").type()).isEqualTo(LangTokenType.NAMESPACE);
            assertThat(single("env").type()).isEqualTo(LangTokenType.ENV);
            assertThat(single("from").type()).isEqualTo(LangTokenType.FROM);
            assertThat(single("using").type()).isEqualTo(LangTokenType.USING);
            assertThat(single("import").type()).isEqualTo(LangTokenType.IMPORT);
            assertThat(single("source").type()).isEqualTo(LangTokenType.SOURCE);
            assertThat(single("relation").type()).isEqualTo(LangTokenType.RELATION);
            assertThat(single("function").type()).isEqualTo(LangTokenType.FUNCTION);
            assertThat(single("private").type()).isEqualTo(LangTokenType.PRIVATE);
            assertThat(single("def").type()).isEqualTo(LangTokenType.DEF);
            assertThat(single("query").type()).isEqualTo(LangTokenType.QUERY);
        }

        @Test
        @DisplayName("Source-kind keywords")
        void sourceKindKeywords() {
            assertThat(single("http").type()).isEqualTo(LangTokenType.HTTP);
            assertThat(single("database").type()).isEqualTo(LangTokenType.DATABASE);
            assertThat(single("csv").type()).isEqualTo(LangTokenType.CSV);
            assertThat(single("json").type()).isEqualTo(LangTokenType.JSON);
        }

        @Test
        @DisplayName("Direction and binding keywords")
        void directionAndBindingKeywords() {
            assertThat(single("in").type()).isEqualTo(LangTokenType.IN);
            assertThat(single("out").type()).isEqualTo(LangTokenType.OUT);
            assertThat(single("as").type()).isEqualTo(LangTokenType.AS);
            assertThat(single("at").type()).isEqualTo(LangTokenType.AT);
            assertThat(single("path").type()).isEqualTo(LangTokenType.PATH);
            assertThat(single("header").type()).isEqualTo(LangTokenType.HEADER);
            assertThat(single("headers").type()).isEqualTo(LangTokenType.HEADERS);
            assertThat(single("required").type()).isEqualTo(LangTokenType.REQUIRED);
            assertThat(single("default").type()).isEqualTo(LangTokenType.DEFAULT);
        }

        @Test
        @DisplayName("Body field keywords")
        void bodyFieldKeywords() {
            assertThat(single("url").type()).isEqualTo(LangTokenType.URL);
            assertThat(single("method").type()).isEqualTo(LangTokenType.METHOD);
            assertThat(single("extract").type()).isEqualTo(LangTokenType.EXTRACT);
            assertThat(single("paginate").type()).isEqualTo(LangTokenType.PAGINATE);
            assertThat(single("schema").type()).isEqualTo(LangTokenType.SCHEMA);
            assertThat(single("table").type()).isEqualTo(LangTokenType.TABLE);
        }

        @Test
        @DisplayName("HTTP read-method tokens (uppercase)")
        void httpMethodTokens() {
            assertThat(single("GET").type()).isEqualTo(LangTokenType.GET);
            assertThat(single("POST").type()).isEqualTo(LangTokenType.POST);
        }

        @Test
        @DisplayName("Mutating HTTP methods lex as identifiers (Relix is read-only)")
        void mutatingMethodsAreNotKeywords() {
            assertThat(single("PUT").type()).isEqualTo(LangTokenType.IDENTIFIER);
            assertThat(single("PATCH").type()).isEqualTo(LangTokenType.IDENTIFIER);
            assertThat(single("DELETE").type()).isEqualTo(LangTokenType.IDENTIFIER);
            assertThat(single("HEAD").type()).isEqualTo(LangTokenType.IDENTIFIER);
        }

        @Test
        @DisplayName("Scalar type keywords (uppercase)")
        void scalarTypeKeywords() {
            assertThat(single("NUMBER").type()).isEqualTo(LangTokenType.NUMBER);
            assertThat(single("STRING").type()).isEqualTo(LangTokenType.STRING);
            assertThat(single("BOOLEAN").type()).isEqualTo(LangTokenType.BOOLEAN);
            assertThat(single("ANY").type()).isEqualTo(LangTokenType.ANY);
        }

        @Test
        @DisplayName("Boolean literal keywords")
        void booleanKeywords() {
            assertThat(single("true").type()).isEqualTo(LangTokenType.TRUE);
            assertThat(single("false").type()).isEqualTo(LangTokenType.FALSE);
        }

        @Test
        @DisplayName("Keyword matching is case-insensitive for every keyword")
        void keywordMatchingIsCaseInsensitive() {
            // Declaration keywords
            assertThat(single("DEF").type()).isEqualTo(LangTokenType.DEF);
            assertThat(single("Query").type()).isEqualTo(LangTokenType.QUERY);
            assertThat(single("NameSpace").type()).isEqualTo(LangTokenType.NAMESPACE);
            assertThat(single("Relation").type()).isEqualTo(LangTokenType.RELATION);
            // Type keywords in any case
            assertThat(single("number").type()).isEqualTo(LangTokenType.NUMBER);
            assertThat(single("String").type()).isEqualTo(LangTokenType.STRING);
            assertThat(single("boolean").type()).isEqualTo(LangTokenType.BOOLEAN);
            assertThat(single("any").type()).isEqualTo(LangTokenType.ANY);
            // HTTP methods in any case
            assertThat(single("get").type()).isEqualTo(LangTokenType.GET);
            assertThat(single("Post").type()).isEqualTo(LangTokenType.POST);
            // Boolean literals in any case
            assertThat(single("TRUE").type()).isEqualTo(LangTokenType.TRUE);
        }

        @Test
        @DisplayName("A matched keyword preserves the original source casing in its lexeme")
        void keywordPreservesOriginalLexeme() {
            assertThat(single("Relation").value()).isEqualTo("Relation");
            assertThat(single("DEF").value()).isEqualTo("DEF");
        }
    }

    // =========================================================================
    // Identifiers
    // =========================================================================

    @Test
    @DisplayName("Unknown word produces IDENTIFIER")
    void unknownWordProducesIdentifier() {
        LangToken tok = single("myRelation");
        assertThat(tok.type()).isEqualTo(LangTokenType.IDENTIFIER);
        assertThat(tok.value()).isEqualTo("myRelation");
    }

    @Test
    @DisplayName("Identifier with underscores")
    void identifierWithUnderscores() {
        LangToken tok = single("my_rel_1");
        assertThat(tok.type()).isEqualTo(LangTokenType.IDENTIFIER);
        assertThat(tok.value()).isEqualTo("my_rel_1");
    }

    @Nested
    @DisplayName("delimited names")
    class DelimitedNames {

        @Test
        @DisplayName("a backtick-delimited name lexes without its backticks")
        void delimitedName() {
            LangToken tok = single("`unit-price`");
            assertThat(tok.type()).isEqualTo(LangTokenType.DELIMITED_IDENTIFIER);
            assertThat(tok.value()).isEqualTo("unit-price");
        }

        @Test
        @DisplayName("a doubled backtick is a literal one, and a keyword inside is just a name")
        void doubledBacktickAndKeyword() {
            assertThat(single("`odd``name`").value()).isEqualTo("odd`name");
            assertThat(single("`schema`").type()).isEqualTo(LangTokenType.DELIMITED_IDENTIFIER);
        }

        @Test
        @DisplayName("the token after a delimited name is read normally")
        void followingToken() {
            assertThat(tokenize("`a b`: NUMBER").stream().map(LangToken::type).toList())
                    .containsExactly(LangTokenType.DELIMITED_IDENTIFIER, LangTokenType.COLON,
                            LangTokenType.NUMBER, LangTokenType.EOF);
        }

        @Test
        @DisplayName("an empty, unterminated or line-spanning name is refused")
        void malformedNames() {
            assertThatThrownBy(() -> single("``"))
                    .isInstanceOf(LangParseException.class).hasMessageContaining("Empty delimited name");
            assertThatThrownBy(() -> single("`open"))
                    .isInstanceOf(LangParseException.class).hasMessageContaining("Unterminated");
            assertThatThrownBy(() -> single("`two\nlines`"))
                    .isInstanceOf(LangParseException.class).hasMessageContaining("Unterminated");
        }
    }

    // =========================================================================
    // String literals
    // =========================================================================

    @Nested
    @DisplayName("string literals")
    class StringLiterals {

        @Test
        @DisplayName("Simple string literal")
        void simpleString() {
            LangToken tok = single("\"hello world\"");
            assertThat(tok.type()).isEqualTo(LangTokenType.STRING_LIT);
            assertThat(tok.value()).isEqualTo("hello world");
        }

        @Test
        @DisplayName("String with escaped quote")
        void stringWithEscapedQuote() {
            LangToken tok = single("\"say \\\"hi\\\"\"");
            assertThat(tok.type()).isEqualTo(LangTokenType.STRING_LIT);
            assertThat(tok.value()).isEqualTo("say \"hi\"");
        }

        @Test
        @DisplayName("String with ${VAR} placeholder preserved verbatim")
        void stringWithEnvVar() {
            LangToken tok = single("\"${DB_URL}\"");
            assertThat(tok.value()).isEqualTo("${DB_URL}");
        }

        @Test
        @DisplayName("Unterminated string throws LangParseException")
        void unterminatedString() {
            assertThatThrownBy(() -> tokenize("\"hello"))
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining("Unterminated string");
        }

        @Test
        @DisplayName("Empty string literal")
        void emptyString() {
            LangToken tok = single("\"\"");
            assertThat(tok.type()).isEqualTo(LangTokenType.STRING_LIT);
            assertThat(tok.value()).isEqualTo("");
        }
    }

    // =========================================================================
    // Number literals
    // =========================================================================

    @Nested
    @DisplayName("number literals")
    class NumberLiterals {

        @Test
        @DisplayName("Integer literal")
        void integerLiteral() {
            LangToken tok = single("42");
            assertThat(tok.type()).isEqualTo(LangTokenType.NUMBER_LIT);
            assertThat(tok.value()).isEqualTo("42");
        }

        @Test
        @DisplayName("Decimal literal")
        void decimalLiteral() {
            LangToken tok = single("3.14");
            assertThat(tok.type()).isEqualTo(LangTokenType.NUMBER_LIT);
            assertThat(tok.value()).isEqualTo("3.14");
        }

        @Test
        @DisplayName("Decimal scanning stops at dot not followed by a digit")
        void decimalScanningStopsAtDotNotFollowedByDigit() {
            // "3.x" → NUMBER_LIT("3"), then '.' is an unexpected char (not part of scripting grammar)
            // Just verify the lexer correctly ends the number before the dot
            LangLexer lexer = new LangLexer("3.x");
            LangToken numTok = lexer.next();
            assertThat(numTok.type()).isEqualTo(LangTokenType.NUMBER_LIT);
            assertThat(numTok.value()).isEqualTo("3");
            // The next call would throw on '.', which is expected and correct
        }
    }

    // =========================================================================
    // Punctuation
    // =========================================================================

    @Nested
    @DisplayName("punctuation tokens")
    class Punctuation {

        @Test
        @DisplayName(":= is ASSIGN")
        void assign() { assertThat(single(":=").type()).isEqualTo(LangTokenType.ASSIGN); }

        @Test
        @DisplayName(": is COLON")
        void colon() { assertThat(single(":").type()).isEqualTo(LangTokenType.COLON); }

        @Test
        @DisplayName("; is SEMICOLON")
        void semicolon() { assertThat(single(";").type()).isEqualTo(LangTokenType.SEMICOLON); }

        @Test
        @DisplayName(", is COMMA")
        void comma() { assertThat(single(",").type()).isEqualTo(LangTokenType.COMMA); }

        @Test
        @DisplayName("( is LPAREN")
        void lparen() { assertThat(single("(").type()).isEqualTo(LangTokenType.LPAREN); }

        @Test
        @DisplayName(") is RPAREN")
        void rparen() { assertThat(single(")").type()).isEqualTo(LangTokenType.RPAREN); }

        @Test
        @DisplayName("{ is LBRACE")
        void lbrace() { assertThat(single("{").type()).isEqualTo(LangTokenType.LBRACE); }

        @Test
        @DisplayName("} is RBRACE")
        void rbrace() { assertThat(single("}").type()).isEqualTo(LangTokenType.RBRACE); }

        @Test
        @DisplayName("[ is LBRACKET")
        void lbracket() { assertThat(single("[").type()).isEqualTo(LangTokenType.LBRACKET); }

        @Test
        @DisplayName("] is RBRACKET")
        void rbracket() { assertThat(single("]").type()).isEqualTo(LangTokenType.RBRACKET); }

        @Test
        @DisplayName("| is PIPE")
        void pipe() { assertThat(single("|").type()).isEqualTo(LangTokenType.PIPE); }

        @Test
        @DisplayName("- is DASH")
        void dash() { assertThat(single("-").type()).isEqualTo(LangTokenType.DASH); }

        @Test
        @DisplayName("Unexpected character throws LangParseException")
        void unexpectedChar() {
            assertThatThrownBy(() -> tokenize("@"))
                    .isInstanceOf(LangParseException.class);
        }
    }

    // =========================================================================
    // Comments
    // =========================================================================

    @Nested
    @DisplayName("comment handling")
    class Comments {

        @Test
        @DisplayName("Line comment skipped")
        void lineCommentSkipped() {
            List<LangToken> tokens = tokenize("-- comment\nquery");
            assertThat(tokens.get(0).type()).isEqualTo(LangTokenType.QUERY);
        }

        @Test
        @DisplayName("Block comment skipped")
        void blockCommentSkipped() {
            List<LangToken> tokens = tokenize("/* block */ query");
            assertThat(tokens.get(0).type()).isEqualTo(LangTokenType.QUERY);
        }

        @Test
        @DisplayName("Block comment at end of input")
        void blockCommentAtEndOfInput() {
            List<LangToken> tokens = tokenize("query /* trailing */");
            assertThat(tokens.get(0).type()).isEqualTo(LangTokenType.QUERY);
        }

        @Test
        @DisplayName("Unterminated block comment throws")
        void unterminatedBlockComment() {
            assertThatThrownBy(() -> tokenize("/* unterminated"))
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining("Unterminated block comment");
        }

        @Test
        @DisplayName("Block comment terminated exactly at end of input")
        void blockCommentTerminatedAtEndOfInput() {
            // "/* x */" — everything is comment, produces only EOF
            List<LangToken> tokens = tokenize("/* x */");
            assertThat(tokens).hasSize(1);
            assertThat(tokens.get(0).type()).isEqualTo(LangTokenType.EOF);
        }
    }

    // =========================================================================
    // Token positions
    // =========================================================================

    @Test
    @DisplayName("Token carries correct line and column")
    void tokenPosition() {
        List<LangToken> tokens = tokenize("query\nUsers");
        assertThat(tokens.get(0).line()).isEqualTo(1);
        assertThat(tokens.get(0).column()).isEqualTo(1);
        assertThat(tokens.get(1).line()).isEqualTo(2);
        assertThat(tokens.get(1).column()).isEqualTo(1);
    }

    @Test
    @DisplayName("EOF token at end of empty input")
    void eofOnEmpty() {
        LangToken tok = single("");
        assertThat(tok.type()).isEqualTo(LangTokenType.EOF);
        assertThat(tok.value()).isEqualTo("");
    }

    // =========================================================================
    // Raw block extraction
    // =========================================================================

    @Nested
    @DisplayName("raw brace block extraction")
    class RawBraceBlockTests {

        @Test
        @DisplayName("Extracts simple brace block")
        void extractsSimpleBraceBlock() {
            LangLexer lexer = new LangLexer("{ Users }");
            lexer.next(); // consume '{' — now lexer.pos is past '{'
            LangLexer.RawBlock block = lexer.consumeRawBraceBlock();
            assertThat(block.text().trim()).isEqualTo("Users");
        }

        @Test
        @DisplayName("Extracts nested brace block")
        void extractsNestedBraceBlock() {
            LangLexer lexer = new LangLexer("{ a { b } c }");
            lexer.next(); // consume '{'
            LangLexer.RawBlock block = lexer.consumeRawBraceBlock();
            assertThat(block.text().trim()).isEqualTo("a { b } c");
        }

        @Test
        @DisplayName("Braces inside string literals are not counted")
        void bracesInStringLiteralNotCounted() {
            LangLexer lexer = new LangLexer("{ \"foo{bar}\" }");
            lexer.next(); // consume '{'
            LangLexer.RawBlock block = lexer.consumeRawBraceBlock();
            assertThat(block.text()).contains("\"foo{bar}\"");
        }

        @Test
        @DisplayName("Unterminated brace block throws")
        void unterminatedBraceBlock() {
            LangLexer lexer = new LangLexer("{ unterminated");
            lexer.next(); // consume '{'
            assertThatThrownBy(lexer::consumeRawBraceBlock)
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining("Unterminated brace block");
        }

        @Test
        @DisplayName("Block records start position")
        void blockRecordsStartPosition() {
            LangLexer lexer = new LangLexer("{ x }");
            lexer.next(); // consume '{'
            LangLexer.RawBlock block = lexer.consumeRawBraceBlock();
            assertThat(block.startLine()).isGreaterThan(0);
            assertThat(block.startCol()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Escape sequence inside string inside brace block handled")
        void escapeInsideStringInsideBraceBlock() {
            LangLexer lexer = new LangLexer("{ \"a\\\"b\" }");
            lexer.next(); // consume '{'
            LangLexer.RawBlock block = lexer.consumeRawBraceBlock();
            assertThat(block.text()).contains("\"a\\\"b\"");
        }
    }

    @Nested
    @DisplayName("raw bracket block extraction")
    class RawBracketBlockTests {

        @Test
        @DisplayName("Extracts simple bracket block")
        void extractsSimpleBracketBlock() {
            LangLexer lexer = new LangLexer("[ id, name ]");
            lexer.next(); // consume '['
            LangLexer.RawBlock block = lexer.consumeRawBracketBlock();
            assertThat(block.text().trim()).isEqualTo("id, name");
        }

        @Test
        @DisplayName("Extracts nested bracket block")
        void extractsNestedBracketBlock() {
            LangLexer lexer = new LangLexer("[ a [b] c ]");
            lexer.next(); // consume '['
            LangLexer.RawBlock block = lexer.consumeRawBracketBlock();
            assertThat(block.text().trim()).isEqualTo("a [b] c");
        }

        @Test
        @DisplayName("Brackets inside string literals are not counted")
        void bracketsInStringLiteralNotCounted() {
            LangLexer lexer = new LangLexer("[ \"a[b]\" ]");
            lexer.next(); // consume '['
            LangLexer.RawBlock block = lexer.consumeRawBracketBlock();
            assertThat(block.text()).contains("\"a[b]\"");
        }

        @Test
        @DisplayName("Unterminated bracket block throws")
        void unterminatedBracketBlock() {
            LangLexer lexer = new LangLexer("[ unterminated");
            lexer.next(); // consume '['
            assertThatThrownBy(lexer::consumeRawBracketBlock)
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining("Unterminated bracket block");
        }

        @Test
        @DisplayName("Escape sequence inside string inside bracket block handled")
        void escapeInsideStringInsideBracketBlock() {
            LangLexer lexer = new LangLexer("[ \"a\\\"b\" ]");
            lexer.next(); // consume '['
            LangLexer.RawBlock block = lexer.consumeRawBracketBlock();
            assertThat(block.text()).contains("\"a\\\"b\"");
        }
    }

    // =========================================================================
    // LangToken.describe()
    // =========================================================================

    @Test
    @DisplayName("describe() for EOF")
    void describeEof() {
        LangToken tok = new LangToken(LangTokenType.EOF, "", 1, 1);
        assertThat(tok.describe()).isEqualTo("end of input");
    }

    @Test
    @DisplayName("describe() for normal token")
    void describeNormalToken() {
        LangToken tok = new LangToken(LangTokenType.QUERY, "query", 3, 5);
        assertThat(tok.describe()).contains("query").contains("3").contains("5");
    }

    // =========================================================================
    // LangTokenType.isNameCompatible()
    // =========================================================================

    @Test
    @DisplayName("IDENTIFIER is name-compatible")
    void identifierIsNameCompatible() {
        assertThat(LangTokenType.IDENTIFIER.isNameCompatible()).isTrue();
    }

    @Test
    @DisplayName("SEMICOLON is not name-compatible")
    void semicolonIsNotNameCompatible() {
        assertThat(LangTokenType.SEMICOLON.isNameCompatible()).isFalse();
    }

    @Test
    @DisplayName("HTTP method tokens are not name-compatible")
    void httpMethodNotNameCompatible() {
        // GET, POST, etc. are NOT in isNameCompatible() — they're distinct enum literals
        assertThat(LangTokenType.GET.isNameCompatible()).isFalse();
        assertThat(LangTokenType.POST.isNameCompatible()).isFalse();
    }
}

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

import com.darkcollective.relix.lang.ast.*;
import com.darkcollective.relix.lang.ast.source.DatabaseSourceConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ScriptParser} covering miscellaneous behaviour:
 * empty scripts, comments, string literals, keyword-as-name flexibility,
 * general error reporting, and token-helper error paths.
 */
@DisplayName("ScriptParser — miscellaneous")
class ScriptParserMiscTest {

    private static Script parse(String source) {
        return ScriptParser.parse(source);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Statement> T firstStatement(Script s) {
        return (T) s.statements().get(0);
    }

    // =========================================================================
    // Empty script
    // =========================================================================

    @Nested
    @DisplayName("Empty script")
    class EmptyScriptTests {

        @Test
        @DisplayName("Empty source produces empty script")
        void emptySourceProducesEmptyScript() {
            Script s = parse("");
            assertThat(s.namespace()).isEmpty();
            assertThat(s.statements()).isEmpty();
        }

        @Test
        @DisplayName("Only comments produces empty script")
        void onlyCommentsProducesEmptyScript() {
            Script s = parse("-- this is a comment\n/* block comment */");
            assertThat(s.statements()).isEmpty();
        }
    }

    // =========================================================================
    // Comment handling
    // =========================================================================

    @Nested
    @DisplayName("comment handling")
    class CommentTests {

        @Test
        @DisplayName("Line comments are ignored")
        void lineCommentsIgnored() {
            String src = """
                    -- This is a comment
                    query Users; -- trailing comment
                    """;
            Script s = parse(src);
            assertThat(s.statements()).hasSize(1);
        }

        @Test
        @DisplayName("Block comments are ignored")
        void blockCommentsIgnored() {
            Script s = parse("/* header */ query Users; /* trailing */");
            assertThat(s.statements()).hasSize(1);
        }
    }

    // =========================================================================
    // String literal handling
    // =========================================================================

    @Nested
    @DisplayName("string literal handling")
    class StringLiteralTests {

        @Test
        @DisplayName("String with ${VAR} placeholder is preserved")
        void stringWithEnvVarPlaceholder() {
            Script s = parse("source S from database { url: \"${DB_URL}\", table: \"t\", schema: { id: NUMBER } };");
            DatabaseSourceConfig cfg = (DatabaseSourceConfig)
                    ((SourceDeclaration) firstStatement(s)).config();
            assertThat(cfg.url()).isEqualTo("${DB_URL}");
        }

        @Test
        @DisplayName("String with escaped quote")
        void stringWithEscapedQuote() {
            Script s = parse("source S from database { url: \"it's \\\"quoted\\\"\", table: \"t\", schema: { id: NUMBER } };");
            DatabaseSourceConfig cfg = (DatabaseSourceConfig)
                    ((SourceDeclaration) firstStatement(s)).config();
            assertThat(cfg.url()).isEqualTo("it's \"quoted\"");
        }
    }

    // =========================================================================
    // Keyword-as-name flexibility
    // =========================================================================

    @Nested
    @DisplayName("keyword-as-name flexibility")
    class KeywordAsNameTests {

        @Test
        @DisplayName("Column named 'url' is accepted")
        void columnNamedUrl() {
            String src = """
                    source S from database {
                        url:   "${DB_URL}",
                        table: "links",
                        schema: {
                            url:  STRING,
                            name: STRING
                        }
                    };
                    """;
            Script s = parse(src);
            DatabaseSourceConfig cfg = (DatabaseSourceConfig)
                    ((SourceDeclaration) firstStatement(s)).config();
            assertThat(cfg.columns().get(0).name()).isEqualTo("url");
        }

        @Test
        @DisplayName("Column named 'schema' is accepted")
        void columnNamedSchema() {
            String src = """
                    source S from database {
                        url:   "${DB_URL}",
                        table: "t",
                        schema: {
                            schema: STRING
                        }
                    };
                    """;
            Script s = parse(src);
            DatabaseSourceConfig cfg = (DatabaseSourceConfig)
                    ((SourceDeclaration) firstStatement(s)).config();
            assertThat(cfg.columns().get(0).name()).isEqualTo("schema");
        }

        @Test
        @DisplayName("Assignment target named with field keyword is accepted")
        void assignmentTargetKeyword() {
            Script s = parse("schema := { Relations };");
            AssignmentStatement a = firstStatement(s);
            assertThat(a.name()).isEqualTo("schema");
        }
    }

    // =========================================================================
    // General error reporting
    // =========================================================================

    @Nested
    @DisplayName("error reporting")
    class ErrorReportingTests {

        @Test
        @DisplayName("Missing semicolon throws LangParseException")
        void missingSemicolon() {
            assertThatThrownBy(() -> parse("query Users"))
                    .isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("Invalid RA expression in assignment body throws")
        void invalidRaExpression() {
            assertThatThrownBy(() -> parse("X := { ??? };"))
                    .isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("Unterminated brace block throws")
        void unterminatedBraceBlock() {
            assertThatThrownBy(() -> parse("X := { Users"))
                    .isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("LangParseException has line and column")
        void exceptionHasLineAndColumn() {
            LangParseException ex = null;
            try {
                parse("query;");
            } catch (LangParseException e) {
                ex = e;
            }
            assertThat(ex).isNotNull();
            assertThat(ex.line()).isGreaterThan(0);
            assertThat(ex.column()).isGreaterThan(0);
        }

        @Test
        @DisplayName("NullPointerException for null source")
        void nullSourceThrowsNpe() {
            assertThatThrownBy(() -> ScriptParser.parse((String) null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("NullPointerException for null InputStream")
        void nullInputStreamThrowsNpe() {
            assertThatThrownBy(() -> ScriptParser.parse((java.io.InputStream) null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // Token helper error paths
    // =========================================================================

    @Nested
    @DisplayName("token helper error paths")
    class TokenHelperErrorTests {

        @Test
        @DisplayName("require '{' fails on non-brace")
        void requireBraceTokenFailsOnNonBrace() {
            assertThatThrownBy(() -> parse("X := 42;"))
                    .isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("require '[' fails on non-bracket after csv")
        void requireBracketTokenFailsAfterCsv() {
            assertThatThrownBy(() -> parse("X := csv { id, name };"))
                    .isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("Wrong token type for consume() throws")
        void wrongTokenForConsume() {
            assertThatThrownBy(() -> parse("namespace 42;"))
                    .isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("Non-string literal where string expected throws")
        void nonStringLiteral() {
            assertThatThrownBy(() -> parse("env from 42;"))
                    .isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("Non-number literal where number expected throws")
        void nonNumberLiteral() {
            assertThatThrownBy(() -> parse("""
                    source S from http {
                        url: "https://x",
                        method: GET,
                        extract: json("$."),
                        paginate: { limit: query("limit") [default: "abc"] },
                        schema: { id: out NUMBER }
                    };
                    """)).isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("Non-bool where bool expected throws")
        void nonBoolLiteral() {
            assertThatThrownBy(() -> parse("""
                    source S from csv("./f.csv") {
                        header: 42,
                        schema: { id: NUMBER }
                    };
                    """)).isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("Non-type where scalar type expected throws")
        void nonScalarType() {
            assertThatThrownBy(() -> parse("def f(x: BLOB) : ANY := { x };"))
                    .isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("Non-type for return type throws")
        void nonScalarReturnType() {
            assertThatThrownBy(() -> parse("def f() : BOOL := { 1 };"))
                    .isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("Invalid operand expression in def body throws")
        void invalidOperandInDef() {
            assertThatThrownBy(() -> parse("def f() : NUMBER := { ??? };"))
                    .isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("LangParseException from token carries token info")
        void langParseExceptionFromToken() {
            LangToken tok = new LangToken(LangTokenType.IDENTIFIER, "foo", 5, 10);
            LangParseException ex = new LangParseException("test error", tok);
            assertThat(ex.line()).isEqualTo(5);
            assertThat(ex.column()).isEqualTo(10);
            assertThat(ex.getMessage()).contains("test error");
        }
    }

    // =========================================================================
    // InputStream entry point
    // =========================================================================

    @Nested
    @DisplayName("InputStream entry point")
    class InputStreamTests {

        @Test
        @DisplayName("Parses from UTF-8 stream")
        void parsesFromInputStream() throws IOException {
            byte[] bytes = "query Users;".getBytes(StandardCharsets.UTF_8);
            Script s = ScriptParser.parse(new ByteArrayInputStream(bytes));
            assertThat(s.statements()).hasSize(1);
            assertThat(s.statements().get(0)).isInstanceOf(QueryStatement.class);
        }
    }
}

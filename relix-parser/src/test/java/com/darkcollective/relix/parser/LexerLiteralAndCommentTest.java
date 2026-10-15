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
import com.darkcollective.relix.ast.*;
import com.darkcollective.relix.ast.internal.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;


final class LexerLiteralAndCommentTest extends ParserTestSupport {

    @Test
    public void parsesSelectionWithEscapedStringNewline() {
        assertParsesTo("σ text = \"hello\\nworld\" (Messages)",
                select(cmp(attr("text"), ComparisonOperator.EQUAL, str("hello\nworld")), rel("Messages")));
    }

    @Test
    public void parsesSelectionWithEscapedStringTab() {
        assertParsesTo("σ text = \"hello\\tworld\" (Messages)",
                select(cmp(attr("text"), ComparisonOperator.EQUAL, str("hello\tworld")), rel("Messages")));
    }

    @Test
    public void parsesSelectionWithEscapedStringBackslash() {
        assertParsesTo("σ text = \"hello\\\\world\" (Messages)",
                select(cmp(attr("text"), ComparisonOperator.EQUAL, str("hello\\world")), rel("Messages")));
    }

    @Test
    public void parsesSelectionWithEmptyString() {
        assertParsesTo("σ name = \"\" (Users)",
                select(cmp(attr("name"), ComparisonOperator.EQUAL, str("")), rel("Users")));
    }

    @Test
    public void parsesSelectionWithZero() {
        assertParsesTo("σ age = 0 (Users)",
                select(cmp(attr("age"), ComparisonOperator.EQUAL, num("0")), rel("Users")));
    }

    @Test
    public void parsesSelectionWithZeroDecimal() {
        assertParsesTo("σ price = 0.0 (Products)",
                select(cmp(attr("price"), ComparisonOperator.EQUAL, num("0.0")), rel("Products")));
    }

    @Test
    public void parsesSingleLineCommentBefore() {
        assertParsesTo("-- This is a comment\nUsers",
                rel("Users"));
    }

    @Test
    public void parsesSingleLineCommentAfter() {
        assertParsesTo("Users -- This is a comment",
                rel("Users"));
    }

    @Test
    public void doubleSlashIsNotAComment() {
        // Two division operators, and a syntax error here. The claim is worth a test
        // because the lexer read it as a comment for as long as it existed, silently
        // discarding the rest of the line in an expression the script grammar would
        // have rejected outright.
        assertParseError("Users // This is not a comment");
    }

    @Test
    public void parsesMultiLineComment() {
        assertParsesTo("/* This is a\nmulti-line comment */Users",
                rel("Users"));
    }

    @Test
    public void parsesMultiLineCommentBetweenTokens() {
        assertParsesTo("π name /* comment */ (Users)",
                project(List.of(projected(attr("name"))), rel("Users")));
    }

    @Test
    public void parsesSelectionWithRawNewlineInString() {
        assertParsesTo("""
                σ text = "hello
                world" (Messages)
                """,
                select(cmp(attr("text"), ComparisonOperator.EQUAL, str("hello\nworld")), rel("Messages")));
    }

    @Test
    public void doubledQuoteIsOneQuote() {
        assertParsesTo("σ note = 'it''s' (R)",
                select(cmp(attr("note"), ComparisonOperator.EQUAL, str("it's")), rel("R")));
        assertParsesTo("σ note = \"say \"\"hi\"\"\" (R)",
                select(cmp(attr("note"), ComparisonOperator.EQUAL, str("say \"hi\"")), rel("R")));
        assertParsesTo("σ note = '''' (R)",
                select(cmp(attr("note"), ComparisonOperator.EQUAL, str("'")), rel("R")));
        assertParsesTo("σ note = '' (R)",
                select(cmp(attr("note"), ComparisonOperator.EQUAL, str("")), rel("R")));
        assertParsesTo("σ note = 'a''' (R)",
                select(cmp(attr("note"), ComparisonOperator.EQUAL, str("a'")), rel("R")));
    }

    @Test
    public void doubledQuoteMixesWithBackslashEscapes() {
        assertParsesTo("σ note = 'it''s \\'quoted\\'' (R)",
                select(cmp(attr("note"), ComparisonOperator.EQUAL, str("it's 'quoted'")), rel("R")));
        assertParseError("σ note = 'it''s (R)").hasMessageContaining("Unterminated string literal");
    }

    @Test
    public void exponentIsTheNumberWrittenOut() {
        assertParsesTo("π 1e6 → a, 2.5E-3 → b, 1.5e+2 → c, 7E0 → d (R)",
                project(List.of(projected(num("1000000"), "a"), projected(num("0.0025"), "b"),
                        projected(num("150"), "c"), projected(num("7"), "d")), rel("R")));
    }

    @Test
    public void anEWithoutDigitsIsNotAnExponent() {
        // 2e is a number then a name, as it was before exponents: the parser refuses it.
        assertParseError("π 2e → x (R)");
        assertParseError("π 2e+ → x (R)");
    }

    @Test
    public void anExponentAtTheEndOfInputIsLexed() {
        for (String[] c : new String[][]{{"1e5", "100000"}, {"1e+5", "100000"}, {"1E-1", "0.1"}}) {
            Token t = new Lexer(c[0]).next();
            assertThat(t.type()).isEqualTo(TokenType.NUMBER);
            assertThat(t.lexeme()).isEqualTo(c[0]);
            assertThat(t.literal()).isEqualTo(c[1]);
        }
        // An e with nothing, or only a sign, after it is not an exponent.
        for (String text : new String[]{"1e", "1e+", "1e-x", "1ex"}) {
            Token t = new Lexer(text).next();
            assertThat(t.lexeme()).as(text).isEqualTo("1");
            assertThat(t.literal()).as(text).isNull();
        }
    }

    @Test
    public void anExponentPastTheLimitIsRefused() {
        assertParseError("π 1e1001 → x (R)").hasMessageContaining("Exponent out of range");
        assertParseError("π 1e-1001 → x (R)").hasMessageContaining("Exponent out of range");
        assertParseError("π 1e99999999999 → x (R)").hasMessageContaining("Exponent out of range");
        assertParsesTo("π 1e-1000 → x (R)",
                project(List.of(projected(num("0." + "0".repeat(999) + "1"), "x")), rel("R")));
    }
}

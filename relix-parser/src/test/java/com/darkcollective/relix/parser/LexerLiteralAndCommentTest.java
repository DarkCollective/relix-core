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
}

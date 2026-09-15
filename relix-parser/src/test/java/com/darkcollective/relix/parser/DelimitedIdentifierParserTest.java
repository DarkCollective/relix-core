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

import com.darkcollective.relix.ast.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.ast.AstAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Backtick-delimited identifiers (issue #455 follow-up): the escape hatch for
 * using a reserved operator word — or any non-identifier-shaped text — as a plain
 * relation, column, or alias name. {@code `order`} references a relation named
 * {@code order} rather than invoking the ORDER/τ operator.
 */
final class DelimitedIdentifierParserTest extends ParserTestSupport {

    @Nested
    class AsNames {

        @Test
        void reservedWordAsRelationName() {
            RelNode node = parse("`order`");
            assertThat(node).isNode(RelationNode.class);
            assertThat(((RelationNode) node).name()).isEqualTo("order");
        }

        @Test
        void reservedWordAsRelationNameInsideOperator() {
            assertParsesTo("π name (`order`)",
                    project(
                            java.util.List.of(projected(attr("name"))),
                            rel("order")));
        }

        @Test
        void reservedWordAsColumnName() {
            // `order` here is a column reference, not the sort operator.
            assertParsesTo("σ `order` = 1 (Events)",
                    select(
                            cmp(attr("order"), ComparisonOperator.EQUAL, num("1")),
                            rel("Events")));
        }

        @Test
        void reservedWordAsProjectionAlias() {
            assertParsesTo("π amount → `sum` (Orders)",
                    project(
                            java.util.List.of(projected(attr("amount"), "sum")),
                            rel("Orders")));
        }

        @Test
        void ordinaryWordInBackticksIsUnchanged() {
            assertParsesTo("`Users`", parse("Users"));
        }

        @Test
        void nameWithSpaces() {
            RelNode node = parse("`my orders`");
            assertThat(((RelationNode) node).name()).isEqualTo("my orders");
        }

        @Test
        void nameWithReservedWordJoinsAndReferences() {
            // A relation literally named `select` joined with a normal one.
            RelNode node = parse("`select` ⋈ Users");
            assertThat(node).isNode(NaturalJoinNode.class);
            NaturalJoinNode join = (NaturalJoinNode) node;
            assertThat(((RelationNode) join.left()).name()).isEqualTo("select");
            assertThat(((RelationNode) join.right()).name()).isEqualTo("Users");
        }
    }

    @Nested
    class Escaping {

        @Test
        void doubledBacktickIsALiteralBacktick() {
            RelNode node = parse("`a``b`");
            assertThat(((RelationNode) node).name()).isEqualTo("a`b");
        }

        @Test
        void trailingDoubledBacktick() {
            // `weird``` = ` + weird + `` (escaped `) + ` (close)  ->  weird`
            RelNode node = parse("`weird```");
            assertThat(((RelationNode) node).name()).isEqualTo("weird`");
        }
    }

    @Nested
    class Errors {

        @Test
        void unterminatedIsAParseError() {
            assertThatThrownBy(() -> parse("`order"))
                    .isInstanceOf(ParseException.class)
                    .hasMessageContaining("Unterminated delimited identifier");
        }

        @Test
        void newlineBeforeCloseIsUnterminated() {
            assertThatThrownBy(() -> parse("`ord\ner`"))
                    .isInstanceOf(ParseException.class)
                    .hasMessageContaining("Unterminated delimited identifier");
        }

        @Test
        void emptyDelimitedIdentifierIsAParseError() {
            assertThatThrownBy(() -> parse("``"))
                    .isInstanceOf(ParseException.class)
                    .hasMessageContaining("Empty delimited identifier");
        }
    }

    @Nested
    class ReservedWordSet {

        /**
         * Guard: every word the lexer reserves must be flagged by the AST's
         * {@link Identifiers} as needing delimiting, so a pretty-printed name that
         * collides with a keyword is always backtick-wrapped (and a newly added
         * keyword can never silently start printing bare).
         */
        @Test
        void everyLexerKeywordNeedsDelimiting() {
            for (String word : Lexer.reservedWords()) {
                assertThat(Identifiers.needsDelimiting(word))
                        .as("reserved word '%s' must be delimited when printed as a name", word)
                        .isTrue();
            }
        }

        @Test
        void everyReservedWordRoundTripsAsADelimitedName() {
            for (String word : Lexer.reservedWords()) {
                RelNode node = parse("`" + word + "`");
                assertThat(node)
                        .as("`%s` parses as a relation name", word)
                        .isNode(RelationNode.class);
                assertThat(((RelationNode) node).name()).isEqualTo(word);
            }
        }
    }
}

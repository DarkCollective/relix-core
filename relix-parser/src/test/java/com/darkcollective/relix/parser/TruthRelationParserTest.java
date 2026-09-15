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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstAssertions.assertThat;

/**
 * Parsing of the nullary truth-relation literals (issue #51): {@code UNIT}/{@code DEE}
 * for the one-tuple relation and {@code EMPTY}/{@code DUM} for the zero-tuple one.
 */
@DisplayName("Truth-relation literals — UNIT / EMPTY (DEE / DUM)")
final class TruthRelationParserTest extends ParserTestSupport {

    @Nested
    @DisplayName("spellings")
    class Spellings {

        @Test
        void unitParsesToTheOneTupleLiteral() {
            assertParsesTo("UNIT", unitRel());
        }

        @Test
        void emptyParsesToTheZeroTupleLiteral() {
            assertParsesTo("EMPTY", emptyRel());
        }

        @Test
        @DisplayName("DEE is an alias for UNIT — the identical AST")
        void deeIsAnAliasForUnit() {
            assertParsesTo("DEE", unitRel());
        }

        @Test
        @DisplayName("DUM is an alias for EMPTY — the identical AST")
        void dumIsAnAliasForEmpty() {
            assertParsesTo("DUM", emptyRel());
        }

        @Test
        @DisplayName("keyword matching is case-insensitive")
        void caseInsensitive() {
            assertParsesTo("unit", unitRel());
            assertParsesTo("Dee", unitRel());
            assertParsesTo("eMpTy", emptyRel());
            assertParsesTo("dum", emptyRel());
        }
    }

    @Nested
    @DisplayName("in relation position")
    class InRelationPosition {

        @Test
        @DisplayName("as the right operand of ×")
        void asProductRightOperand() {
            assertParsesTo("Trades × UNIT",
                    product(rel("Trades"), unitRel()));
        }

        @Test
        @DisplayName("as the left operand of ×")
        void asProductLeftOperand() {
            assertParsesTo("UNIT × Trades",
                    product(unitRel(), rel("Trades")));
        }

        @Test
        @DisplayName("as the input of a unary operator")
        void asUnaryOperatorInput() {
            assertParsesTo("δ (EMPTY)", distinct(emptyRel()));
        }

        @Test
        @DisplayName("parenthesised, like any other relational expression")
        void parenthesised() {
            assertParsesTo("(UNIT)", unitRel());
        }

        @Test
        @DisplayName("round-trips through the pretty-printer")
        void roundTripsThroughPrettyPrint() {
            RelNode parsed = parse("Trades × DEE");
            assertThat(parsed.prettyPrint()).isEqualTo("(Trades) × (UNIT)");
            assertParsesTo(parsed.prettyPrint(),
                    product(rel("Trades"), unitRel()));
        }
    }

    @Nested
    @DisplayName("escaping")
    class Escaping {

        @Test
        @DisplayName("a backticked spelling is a relation name, not the literal")
        void backtickedSpellingIsARelationName() {
            RelNode node = parse("`unit`");
            assertThat(node).isNode(RelationNode.class);
            assertThat(((RelationNode) node).name()).isEqualTo("unit");
        }

        @Test
        @DisplayName("the spellings remain usable as column names")
        void usableAsColumnNames() {
            assertParsesTo("π unit, empty (Readings)",
                    project(
                            List.of(projected(attr("unit")), projected(attr("empty"))),
                            rel("Readings")));
        }
    }
}

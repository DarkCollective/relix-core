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
import com.darkcollective.relix.ast.internal.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstAssertions.assertThat;

/**
 * Tests for the lineage-reification operator WHY (ω) — ADR-0018, epic #317
 * slice 1 (AST + parser). Covers the glyph and ASCII surface forms, nesting and
 * composition with other operators, pretty-print round-tripping, source-location
 * tracking, and error positions.
 */
@DisplayName("RelAlgebraParser — WHY (ω)")
final class WhyParserTest extends ParserTestSupport {

    // ==================== Basic parsing (glyph + ASCII) ====================

    @Nested
    @DisplayName("Basic forms")
    class Basic {

        @Test
        void parsesGlyphForm() {
            assertParsesTo("ω (Orders)", why(rel("Orders")));
        }

        @Test
        void parsesAsciiKeywordForm() {
            assertParsesTo("WHY (Orders)", why(rel("Orders")));
        }

        @Test
        void asciiKeywordIsCaseInsensitive() {
            assertParsesTo("why (Orders)", why(rel("Orders")));
        }

        @Test
        @DisplayName("glyph and ASCII forms parse to an identical AST")
        void glyphAndAsciiAreEquivalent() {
            assertThat(stripLocations(parse("ω (Orders)")))
                    .isEqualTo(stripLocations(parse("WHY (Orders)")));
        }

        @Test
        void parsesWithExtraWhitespace() {
            assertParsesTo("  ω   (  Orders  )  ", why(rel("Orders")));
        }

        @Test
        void parsesOnQualifiedRelation() {
            assertParsesTo("ω (schema.Orders)", why(rel("schema.Orders")));
        }
    }

    // ==================== Composition over inputs ====================

    @Nested
    @DisplayName("WHY over composite inputs")
    class OverInputs {

        @Test
        void whyOverProjection() {
            assertParsesTo("ω (π region, amount (Orders))",
                    why(
                            project(
                                    List.of(projected(attr("region")), projected(attr("amount"))),
                                    rel("Orders"))));
        }

        @Test
        void whyOverThetaJoin() {
            assertParsesTo("ω (Orders ⨝ Orders.cid = Customers.cid Customers)",
                    why(
                            join(
                                    rel("Orders"),
                                    rel("Customers"),
                                    cmp(attr("Orders.cid"), ComparisonOperator.EQUAL,
                                        attr("Customers.cid")))));
        }

        @Test
        void whyOverSelection() {
            assertParsesTo("ω (σ region = \"west\" (Orders))",
                    why(
                            select(
                                    cmp(attr("region"), ComparisonOperator.EQUAL, str("west")),
                                    rel("Orders"))));
        }

        @Test
        @DisplayName("the marquee ADR-0018 example parses")
        void adrWorkedExample() {
            assertParsesTo("ω (π region, amount (Orders ⨝ Orders.cid = Customers.cid Customers))",
                    why(
                            project(
                                    List.of(projected(attr("region")), projected(attr("amount"))),
                                    join(
                                            rel("Orders"),
                                            rel("Customers"),
                                            cmp(attr("Orders.cid"), ComparisonOperator.EQUAL,
                                                attr("Customers.cid"))))));
        }
    }

    // ==================== Nesting and surrounding operators ====================

    @Nested
    @DisplayName("Nesting")
    class Nesting {

        @Test
        void unnestOverWhy() {
            // The expected downstream usage: μ explodes the reified provenance column.
            assertParsesTo("μ provenance (ω (Orders))",
                    unnest(
                            "provenance",
                            false,
                            why(rel("Orders"))));
        }

        @Test
        void projectionOverWhy() {
            assertParsesTo("π region (ω (Orders))",
                    project(
                            List.of(projected(attr("region"))),
                            why(rel("Orders"))));
        }

        @Test
        void nestedWhy() {
            assertParsesTo("ω (ω (Orders))",
                    why(why(rel("Orders"))));
        }

        @Test
        void whyAsBinaryOperand() {
            assertParsesTo("ω (Orders) ∪ ω (Archived)",
                    union(
                            why(rel("Orders")),
                            why(rel("Archived"))));
        }
    }

    // ==================== Pretty printing / round-trip ====================

    @Nested
    @DisplayName("Pretty printing")
    class PrettyPrinting {

        @Test
        void prettyPrintsGlyph() {
            assertPrettyPrints(why(rel("Orders")), "ω (Orders)");
        }

        @Test
        void prettyPrintsOverProjection() {
            RelNode node = why(
                    project(
                            List.of(projected(attr("region"))),
                            rel("Orders")));
            assertPrettyPrints(node, "ω (π region (Orders))");
        }

        @Test
        @DisplayName("ASCII source round-trips to the glyph pretty form and back")
        void roundTrips() {
            RelNode parsed = parse("WHY (π region (Orders))");
            String printed = parsed.prettyPrint();
            assertThat(printed).isEqualTo("ω (π region (Orders))");
            assertThat(stripLocations(parse(printed))).isEqualTo(stripLocations(parsed));
        }
    }

    // ==================== Source location ====================

    @Nested
    @DisplayName("Source location")
    class Location {

        @Test
        void operatorAtStartPosition() {
            RelNode node = RelAlgebraParser.parse("ω (Orders)");
            assertThat(node).isNode(WhyNode.class);
            assertThat(node.location().line()).isEqualTo(1);
            assertThat(node.location().column()).isEqualTo(1);
        }

        @Test
        void innerRelationCarriesOwnPosition() {
            WhyNode node = (WhyNode) RelAlgebraParser.parse("ω (Orders)");
            // "Orders" starts after "ω (" — column 4 (1-based, ω is one char).
            assertThat(node.input().location().column()).isEqualTo(4);
        }
    }

    // ==================== Error cases ====================

    @Nested
    @DisplayName("Error cases")
    class Errors {

        @Test
        void missingOpeningParenthesis() {
            assertParseError("ω Orders")
                    .hasMessageContaining("Expected '(' after 'ω'");
        }

        @Test
        void missingOpeningParenthesisAscii() {
            assertParseError("WHY Orders")
                    .hasMessageContaining("Expected '(' after 'ω'");
        }

        @Test
        void missingClosingParenthesis() {
            assertParseError("ω (Orders")
                    .hasMessageContaining("Expected ')' after relational expression");
        }

        @Test
        void missingInputRelation() {
            assertParseError("ω ()")
                    .hasMessageContaining("Expected relation name, unary operator, or '('");
        }

        @Test
        void standaloneOperator() {
            assertParseError("ω")
                    .hasMessageContaining("Expected '(' after 'ω'");
        }

        @Test
        @DisplayName("error reports the offending token position")
        void errorPosition() {
            assertParseError("ω Orders").at(1, 3);
        }
    }
}

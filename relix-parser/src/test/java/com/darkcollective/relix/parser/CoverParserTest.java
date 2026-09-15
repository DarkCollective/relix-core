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

import com.darkcollective.relix.ast.CoverNode;
import com.darkcollective.relix.ast.NaturalJoinNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SelectionNode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.ast.ComparisonOperator.GREATER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the covering-reduction operator {@code COVER t (R)} (ADR-0012,
 * epic #126, issue #127 — AST/parser slice).  Covers the happy path (strength ≥ 1,
 * nested body, compound expression), error positions for non-integer strength, out-of-
 * range strength (0), and missing-parenthesis errors, all via the
 * {@code .at(l,c)}/{@code .found(...)} fluent API.
 */
final class CoverParserTest extends ParserTestSupport {

    @Nested
    class HappyPath {

        @Test
        void parsesPairwiseCover() {
            assertParsesTo("COVER 2 (Params)",
                    cover(2, rel("Params")));
        }

        @Test
        void parsesStrengthOne() {
            assertParsesTo("COVER 1 (R)",
                    cover(1, rel("R")));
        }

        @Test
        void parsesStrengthThree() {
            assertParsesTo("COVER 3 (R)",
                    cover(3, rel("R")));
        }

        @Test
        void parsesWithSelectionBodyInsideParens() {
            // COVER 2 (σ active = true (Params))
            assertParsesTo("COVER 2 (σ active = true (Params))",
                    cover(
                            2,
                            select(
                                    cmp(attr("active"), com.darkcollective.relix.ast.ComparisonOperator.EQUAL, bool(true)),
                                    rel("Params"))));
        }

        @Test
        void parsesWithJoinBodyInsideParens() {
            // COVER 2 (A ⋈ B)
            assertParsesTo("COVER 2 (A ⋈ B)",
                    cover(
                            2,
                            naturalJoin(rel("A"), rel("B"))));
        }

        @Test
        void coverBindsTighterThanJoin() {
            // A ⋈ COVER 2 (B) parses as A ⋈ (COVER 2 (B))
            assertParsesTo("A ⋈ COVER 2 (B)",
                    naturalJoin(
                            rel("A"),
                            cover(2, rel("B"))));
        }
    }

    @Nested
    class PrettyPrint {

        @Test
        void prettyPrintsWithStrengthAndInput() {
            assertPrettyPrints(cover(2, rel("Params")),
                    "COVER 2 (Params)");
        }

        @Test
        void roundTripsThroughParseAndPrettyPrint() {
            assertPrettyPrints(parse("COVER 2 (Params)"), "COVER 2 (Params)");
        }

        @Test
        void roundTripsNestedExpression() {
            assertPrettyPrints(parse("COVER 2 (A ⋈ B)"),
                    "COVER 2 ((A) ⋈ (B))");
        }
    }

    @Nested
    class ExactMode {

        @Test
        void parsesExactPairwiseCover() {
            assertParsesTo("COVER EXACT 2 (Params)",
                    cover(2, true, rel("Params")));
        }

        @Test
        void parsesExactStrengthThree() {
            assertParsesTo("COVER EXACT 3 (R)",
                    cover(3, true, rel("R")));
        }

        @Test
        void greedyCoverHasExactFalse() {
            CoverNode node = (CoverNode) parse("COVER 2 (Params)");
            assertThat(node.exact()).isFalse();
        }

        @Test
        void exactCoverHasExactTrue() {
            CoverNode node = (CoverNode) parse("COVER EXACT 2 (Params)");
            assertThat(node.exact()).isTrue();
        }

        @Test
        void exactCoverPrettyPrints() {
            assertPrettyPrints(cover(2, true, rel("Params")),
                    "COVER EXACT 2 (Params)");
        }

        @Test
        void exactCoverRoundTrips() {
            assertPrettyPrints(parse("COVER EXACT 2 (Params)"), "COVER EXACT 2 (Params)");
        }

        @Test
        void rejectsMissingStrengthAfterExact() {
            assertParseError("COVER EXACT (Params)")
                    .hasMessageContaining("integer covering strength")
                    .at(1, 13)
                    .found("'('");
        }
    }

    @Nested
    class Errors {

        @Test
        void rejectsMissingStrength() {
            assertParseError("COVER (Params)")
                    .hasMessageContaining("integer covering strength")
                    .at(1, 7)
                    .found("'('");
        }

        @Test
        void rejectsNonIntegerStrengthDecimal() {
            // 1.5 is a NUMBER token but not an integer
            assertParseError("COVER 1.5 (Params)")
                    .hasMessageContaining("integer")
                    .at(1, 7)
                    .found("'1.5'");
        }

        @Test
        void rejectsStrengthZero() {
            assertParseError("COVER 0 (Params)")
                    .hasMessageContaining("≥ 1")
                    .at(1, 7)
                    .found("'0'");
        }

        @Test
        void rejectsMissingOpenParen() {
            assertParseError("COVER 2 Params")
                    .hasMessageContaining("(")
                    .at(1, 9)
                    .found("'Params'");
        }

        @Test
        void rejectsMissingClosingParen() {
            assertParseError("COVER 2 (Params")
                    .hasMessageContaining(")");
        }
    }
}

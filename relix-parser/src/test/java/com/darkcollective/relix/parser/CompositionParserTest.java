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

/**
 * Tests for the relational-composition operator (∘ / COMPOSE).  Covers basic
 * parsing, associativity, precedence (it binds in the join/division tier),
 * pretty printing, and error cases.
 */
final class CompositionParserTest extends ParserTestSupport {

    // ─── Basic ──────────────────────────────────────────────────────────────

    @Test
    void parsesBasicComposition() {
        assertParsesTo("R ∘ S",
                composition(rel("R"), rel("S")));
    }

    @Test
    void asciiKeywordMatchesUnicode() {
        assertParsesTo("R COMPOSE S", parse("R ∘ S"));
    }

    // ─── Associativity ──────────────────────────────────────────────────────

    @Test
    void isLeftAssociative() {
        assertParsesTo("R ∘ S ∘ T",
                composition(
                        composition(rel("R"), rel("S")),
                        rel("T")));
    }

    @Test
    void parenthesesOverrideAssociativity() {
        assertParsesTo("R ∘ (S ∘ T)",
                composition(
                        rel("R"),
                        composition(rel("S"), rel("T"))));
    }

    @Test
    void sharesJoinTierLeftAssociativelyWithNaturalJoin() {
        // ∘ and ⋈ are the same precedence tier, left-associative.
        assertParsesTo("R ∘ S ⋈ T",
                naturalJoin(
                        composition(rel("R"), rel("S")),
                        rel("T")));
    }

    // ─── Precedence ─────────────────────────────────────────────────────────

    @Test
    void compositionBindsTighterThanUnion() {
        assertParsesTo("A ∪ R ∘ S",
                union(
                        rel("A"),
                        composition(rel("R"), rel("S"))));
    }

    @Test
    void compositionBindsTighterThanIntersection() {
        assertParsesTo("A ∩ R ∘ S",
                intersection(
                        rel("A"),
                        composition(rel("R"), rel("S"))));
    }

    @Test
    void unaryOperationBindsTighterThanComposition() {
        assertParsesTo("π a, b (R) ∘ S",
                composition(
                        project(
                                List.of(projected(attr("a")), projected(attr("b"))),
                                rel("R")),
                        rel("S")));
    }

    @Test
    void parenthesesOverridePrecedence() {
        assertParsesTo("A ∪ (R ∘ S)",
                union(
                        rel("A"),
                        composition(rel("R"), rel("S"))));
    }

    // ─── Pretty printing ────────────────────────────────────────────────────

    @Test
    void prettyPrintsBasic() {
        RelNode node = composition(rel("R"), rel("S"));
        assertPrettyPrints(node, "(R) ∘ (S)");
    }

    @Test
    void prettyPrintRoundTrips() {
        RelNode original = composition(rel("R"), rel("S"));
        assertParsesTo(original.prettyPrint(), original);
    }

    // ─── Errors ─────────────────────────────────────────────────────────────

    @Test
    void failsOnMissingRightOperand() {
        assertParseError("R ∘").hasMessageContaining("Expected relation name");
    }

    @Test
    void failsOnMissingLeftOperand() {
        assertParseError("∘ S").hasMessageContaining("Expected relation name");
    }
}

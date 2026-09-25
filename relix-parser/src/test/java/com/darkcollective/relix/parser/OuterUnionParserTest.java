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
 * Tests for the outer-union operator (⊔ / OUNION) — schema-reconciling merge.
 * Covers glyph/keyword equivalence, precedence (same tier as ∪/⊎/−),
 * associativity, and error cases.
 */
final class OuterUnionParserTest extends ParserTestSupport {

    @Test
    public void parsesBasicOuterUnionGlyph() {
        assertParsesTo("A ⊔ B",
                outerUnion(rel("A"), rel("B")));
    }

    @Test
    public void parsesOuterUnionKeyword() {
        assertParsesTo("A OUNION B",
                outerUnion(rel("A"), rel("B")));
    }

    @Test
    public void keywordAndGlyphProduceIdenticalAst() {
        assertParsesTo("A OUNION B", parse("A ⊔ B"));
    }

    @Test
    public void keywordIsCaseInsensitiveAndLowercaseWorks() {
        assertParsesTo("Users ounion Admins",
                outerUnion(rel("Users"), rel("Admins")));
    }

    @Test
    public void outerUnionIsLeftAssociative() {
        assertParsesTo("A ⊔ B ⊔ C",
                outerUnion(
                        outerUnion(rel("A"), rel("B")),
                        rel("C")));
    }

    @Test
    public void joinBindsTighterThanOuterUnion() {
        assertParsesTo("A ⊔ B ⋈ C",
                outerUnion(
                        rel("A"),
                        naturalJoin(rel("B"), rel("C"))));
    }

    @Test
    public void outerUnionSharesTierWithUnionLeftAssociatively() {
        // Same precedence tier as ∪ / ⊎ / −, all left-associative.
        assertParsesTo("A ∪ B ⊔ C",
                outerUnion(
                        union(rel("A"), rel("B")),
                        rel("C")));
    }

    @Test
    public void parenthesesOverrideAssociativity() {
        assertParsesTo("A ⊔ (B ⊔ C)",
                outerUnion(
                        rel("A"),
                        outerUnion(rel("B"), rel("C"))));
    }

    @Test
    public void unaryOperationBindsTighterThanOuterUnion() {
        assertParsesTo("π id (Users) ⊔ Admins",
                outerUnion(
                        project(List.of(projected(attr("id"))), rel("Users")),
                        rel("Admins")));
    }

    @Test
    public void prettyPrintsOuterUnion() {
        RelNode node = outerUnion(rel("Users"), rel("Admins"));
        assertPrettyPrints(node, "(Users) ⊔ (Admins)");
    }

    @Test
    public void prettyPrintRoundTripsThroughParser() {
        RelNode original = outerUnion(rel("A"), rel("B"));
        assertParsesTo(original.prettyPrint(), original);
    }

    @Test
    public void failsOnMissingRightOperand() {
        assertParseError("Users ⊔")
                .hasMessageContaining("Expected relation name");
    }

    @Test
    public void failsOnMissingLeftOperand() {
        assertParseError("⊔ Users")
                .hasMessageContaining("Expected relation name");
    }
}

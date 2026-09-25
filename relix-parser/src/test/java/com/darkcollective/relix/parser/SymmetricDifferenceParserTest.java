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
 * Tests for the symmetric-difference operator (∆ / SYMDIFF).  Covers basic
 * parsing, associativity, precedence relative to the other set and join
 * operators, pretty printing, and error cases.
 */
final class SymmetricDifferenceParserTest extends ParserTestSupport {

    // ─── Basic ──────────────────────────────────────────────────────────────

    @Test
    void parsesBasicSymmetricDifference() {
        assertParsesTo("A ∆ B",
                symmetricDifference(rel("A"), rel("B")));
    }

    @Test
    void asciiKeywordMatchesUnicode() {
        assertParsesTo("A SYMDIFF B", parse("A ∆ B"));
    }

    // ─── Associativity ──────────────────────────────────────────────────────

    @Test
    void isLeftAssociative() {
        assertParsesTo("A ∆ B ∆ C",
                symmetricDifference(
                        symmetricDifference(rel("A"), rel("B")),
                        rel("C")));
    }

    @Test
    void parenthesesOverrideAssociativity() {
        assertParsesTo("A ∆ (B ∆ C)",
                symmetricDifference(
                        rel("A"),
                        symmetricDifference(rel("B"), rel("C"))));
    }

    @Test
    void sharesSetOperatorTierLeftAssociatively() {
        // ∆ sits in the same precedence tier as ∪ and −, all left-associative.
        assertParsesTo("A ∪ B ∆ C − D",
                difference(
                        symmetricDifference(
                                union(rel("A"), rel("B")),
                                rel("C")),
                        rel("D")));
    }

    // ─── Precedence ─────────────────────────────────────────────────────────

    @Test
    void joinBindsTighterThanSymmetricDifference() {
        assertParsesTo("A ∆ B ⋈ C",
                symmetricDifference(
                        rel("A"),
                        naturalJoin(rel("B"), rel("C"))));
    }

    @Test
    void intersectionBindsTighterThanSymmetricDifference() {
        assertParsesTo("A ∆ B ∩ C",
                symmetricDifference(
                        rel("A"),
                        intersection(rel("B"), rel("C"))));
    }

    @Test
    void unaryOperationBindsTighterThanSymmetricDifference() {
        assertParsesTo("π id (Users) ∆ Admins",
                symmetricDifference(
                        project(List.of(projected(attr("id"))), rel("Users")),
                        rel("Admins")));
    }

    @Test
    void parenthesesOverridePrecedence() {
        assertParsesTo("(A ∆ B) ⋈ C",
                naturalJoin(
                        symmetricDifference(rel("A"), rel("B")),
                        rel("C")));
    }

    // ─── Pretty printing ────────────────────────────────────────────────────

    @Test
    void prettyPrintsBasic() {
        RelNode node = symmetricDifference(rel("Users"), rel("Admins"));
        assertPrettyPrints(node, "(Users) ∆ (Admins)");
    }

    @Test
    void prettyPrintRoundTrips() {
        RelNode original = symmetricDifference(rel("A"), rel("B"));
        assertParsesTo(original.prettyPrint(), original);
    }

    // ─── Errors ─────────────────────────────────────────────────────────────

    @Test
    void failsOnMissingRightOperand() {
        assertParseError("Users ∆").hasMessageContaining("Expected relation name");
    }

    @Test
    void failsOnMissingLeftOperand() {
        assertParseError("∆ Users").hasMessageContaining("Expected relation name");
    }
}

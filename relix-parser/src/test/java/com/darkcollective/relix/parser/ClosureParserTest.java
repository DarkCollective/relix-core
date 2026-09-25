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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import com.darkcollective.relix.ast.*;
import com.darkcollective.relix.ast.internal.*;

/**
 * Tests for the transitive-closure operators CLOSURE (R⁺) and RCLOSURE (R*).
 *
 * <p>Two equivalent syntaxes produce identical ASTs:
 * <ul>
 *   <li>Prefix keywords: {@code CLOSURE from, to (R)} / {@code RCLOSURE from, to (R)}</li>
 *   <li>Postfix glyphs: {@code R⁺ OVER (from, to)} / {@code R* OVER (from, to)}</li>
 * </ul>
 */
final class ClosureParserTest extends ParserTestSupport {

    @Nested
    class PrefixKeywords {

        @Test
        void parsesTransitiveClosure() {
            assertParsesTo("CLOSURE src, dst (Edges)",
                    closure("src", "dst", false, rel("Edges")));
        }

        @Test
        void parsesReflexiveTransitiveClosure() {
            assertParsesTo("RCLOSURE src, dst (Edges)",
                    closure("src", "dst", true, rel("Edges")));
        }

        @Test
        void parsesClosureOverNestedOperator() {
            assertParsesTo("CLOSURE a, b (δ (Edges))",
                    closure("a", "b", false, distinct(rel("Edges"))));
        }

        @Test
        void closureBindsTighterThanJoin() {
            // A ⋈ CLOSURE … parses as A ⋈ (CLOSURE …), not (A ⋈ CLOSURE) …
            assertParsesTo("A ⋈ CLOSURE src, dst (Edges)",
                    naturalJoin(
                            rel("A"),
                            closure("src", "dst", false, rel("Edges"))));
        }

        @Test
        void prettyPrintsClosure() {
            assertPrettyPrints(
                    closure("src", "dst", false, rel("Edges")),
                    "CLOSURE src, dst (Edges)");
        }

        @Test
        void prettyPrintsReflexiveClosure() {
            assertPrettyPrints(
                    closure("src", "dst", true, rel("Edges")),
                    "RCLOSURE src, dst (Edges)");
        }

        @Test
        void rejectsMissingComma() {
            assertParseError("CLOSURE src dst (Edges)").hasMessageContaining(",");
        }

        @Test
        void rejectsMissingColumns() {
            assertParseError("CLOSURE (Edges)").hasMessageContaining("from");
        }
    }

    // ── Postfix Kleene-glyph syntax ───────────────────────────────────────────
    //   R⁺ OVER (from, to)  ≡  CLOSURE from, to (R)
    //   R* OVER (from, to)  ≡  RCLOSURE from, to (R)

    @Nested
    class PostfixGlyphs {

        /** {@code ⁺} (U+207A superscript plus) produces the same AST as {@code CLOSURE}. */
        @Test
        void kleenePlusProducesSameAstAsClosure() {
            assertParsesTo("Edges⁺ OVER (src, dst)",
                    closure("src", "dst", false, rel("Edges")));
        }

        /** ASCII {@code *} in postfix position produces the same AST as {@code RCLOSURE}. */
        @Test
        void kleeneStarProducesSameAstAsRclosure() {
            assertParsesTo("Edges* OVER (src, dst)",
                    closure("src", "dst", true, rel("Edges")));
        }

        /** Postfix glyph is tighter than binary join: {@code A ⋈ Edges⁺ OVER (s,d)} = {@code A ⋈ (Edges⁺ OVER (s,d))}. */
        @Test
        void postfixBindsTighterThanJoin() {
            assertParsesTo("A ⋈ Edges⁺ OVER (src, dst)",
                    naturalJoin(
                            rel("A"),
                            closure("src", "dst", false, rel("Edges"))));
        }

        /** Postfix glyph applies to a parenthesised expression, not just a bare name. */
        @Test
        void postfixAppliesToParenthesisedExpression() {
            assertParsesTo("(σ w = 1 (Edges))⁺ OVER (src, dst)",
                    closure(
                            "src",
                            "dst",
                            false,
                            select(
                                    cmp(attr("w"), ComparisonOperator.EQUAL, num("1")),
                                    rel("Edges"))));
        }

        /** Postfix star on a join expression — the star applies to the whole left-joined output. */
        @Test
        void postfixStarOnJoinedExpression() {
            assertParsesTo("(A ⋈ B)* OVER (from, to)",
                    closure(
                            "from",
                            "to",
                            true,
                            naturalJoin(rel("A"), rel("B"))));
        }

        // ── No ambiguity: * in predicate context is still multiplication ──────

        /** {@code *} inside a predicate is still arithmetic multiplication, not Kleene star. */
        @Test
        void starInPredicateContextIsMultiplication() {
            // σ x * 2 > 10 (A)  — the * is inside the predicate, not in relation position
            assertParsesTo("σ x * 2 > 10 (A)",
                    select(
                            cmp(arith(attr("x"), ArithmeticOperator.MULTIPLY, num("2")),
                                    ComparisonOperator.GREATER, num("10")),
                            rel("A")));
        }

        // ── Error cases ────────────────────────────────────────────────────────

        @Test
        void rejectsMissingOverKeyword() {
            assertParseError("Edges⁺ (src, dst)").hasMessageContaining("OVER");
        }

        @Test
        void rejectsMissingParenAfterOver() {
            assertParseError("Edges⁺ OVER src, dst").hasMessageContaining("(");
        }

        @Test
        void rejectsMissingCommaInOver() {
            assertParseError("Edges⁺ OVER (src dst)").hasMessageContaining(",");
        }

        @Test
        void rejectsMissingClosingParen() {
            assertParseError("Edges⁺ OVER (src, dst").hasMessageContaining(")");
        }
    }
}

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

import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ObjectiveSense;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.ast.AstBuilders.closure;
import static com.darkcollective.relix.ast.AstBuilders.path;
import static com.darkcollective.relix.ast.AstBuilders.rel;
import static com.darkcollective.relix.ast.AstBuilders.trace;

@DisplayName("The undirected-edge separator")
final class UndirectedEdgeParseTest extends ParserTestSupport {

    @Nested
    @DisplayName("the glyph and its ASCII spelling")
    final class Spellings {

        @Test
        @DisplayName("CLOSURE reads ↔ and <-> identically")
        void closureBothSpellings() {
            assertParsesTo("CLOSURE src ↔ dst (Edges)",
                    closure("src", "dst", true, false, rel("Edges")));
            assertParsesTo("CLOSURE src <-> dst (Edges)",
                    closure("src", "dst", true, false, rel("Edges")));
        }

        @Test
        @DisplayName("a comma is still the directed reading")
        void commaIsDirected() {
            assertParsesTo("CLOSURE src, dst (Edges)",
                    closure("src", "dst", false, false, rel("Edges")));
        }

        @Test
        @DisplayName("RCLOSURE takes it too")
        void reflexive() {
            assertParsesTo("RCLOSURE src <-> dst (Edges)",
                    closure("src", "dst", true, true, rel("Edges")));
        }

        @Test
        @DisplayName("the postfix Kleene form takes it, which a keyword modifier could not")
        void postfixKleene() {
            assertParsesTo("Edges⁺ OVER (src ↔ dst)",
                    closure("src", "dst", true, false, rel("Edges")));
            assertParsesTo("Edges* OVER (src <-> dst)",
                    closure("src", "dst", true, true, rel("Edges")));
        }

        @Test
        @DisplayName("PATH takes it")
        void pathTakesIt() {
            assertParsesTo("PATH person ↔ contact HOPS 1 TO 3 AS depth (Knows)",
                    path("person", "contact", true, 1, 3, "depth", rel("Knows")));
        }

        @Test
        @DisplayName("TRACE takes it")
        void traceTakesIt() {
            assertParsesTo("TRACE origin <-> dest VIA cost MINIMIZE AS route (Flights)",
                    trace("origin", "dest", true, "cost", ObjectiveSense.MINIMIZE, "route",
                            rel("Flights")));
        }
    }

    @Nested
    @DisplayName("the lexing hazard it introduces")
    final class LessThanMinus {

        // `<` is the first character of `<->`, so the lexer has to look two characters
        // ahead before consuming anything: reading `<-` on sight would turn a comparison
        // against a negative number into the start of an undirected edge.

        @Test
        @DisplayName("a comparison against a negative literal is unaffected, spaced")
        void spaced() {
            assertParsesTo("σ balance < -1000 (Accounts)",
                    select(cmp(attr("balance"), ComparisonOperator.LESS, unary(num("1000"))),
                            rel("Accounts")));
        }

        @Test
        @DisplayName("and unspaced, which is the adversarial form")
        void unspaced() {
            assertParsesTo("σ balance<-1000 (Accounts)",
                    select(cmp(attr("balance"), ComparisonOperator.LESS, unary(num("1000"))),
                            rel("Accounts")));
        }

        @Test
        @DisplayName("a `<` at the very end of the input does not read past it")
        void trailingLessThan() {
            // The two-character lookahead has to cope with there being no next character
            // at all, which is what an input ending mid-comparison gives it.
            assertParseError("σ balance <");
        }

        @Test
        @DisplayName("a bare `<` before an identifier is still a comparison")
        void beforeIdentifier() {
            assertParsesTo("σ low < high (Readings)",
                    select(cmp(attr("low"), ComparisonOperator.LESS, attr("high")),
                            rel("Readings")));
        }
    }

    @Nested
    @DisplayName("rejections")
    final class Rejections {

        @Test
        @DisplayName("CLUSTER does not take it — it is undirected already")
        void clusterRejectsIt() {
            assertParseError("CLUSTER src ↔ dst AS cid (Edges)");
        }

        @Test
        @DisplayName("a missing separator is still refused")
        void missingSeparator() {
            assertParseError("CLOSURE src dst (Edges)");
        }
    }
}

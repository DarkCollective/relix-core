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

import com.darkcollective.relix.ast.FixpointNode;
import com.darkcollective.relix.ast.NaturalJoinNode;
import com.darkcollective.relix.ast.RecursiveRefNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.UnionNode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.ast.ComparisonOperator.GREATER;

/**
 * Tests for the general-recursion fixpoint binder {@code FIX name (base, step)}
 * and its {@link RecursiveRefNode} occurrences (epic #46, issue #113 — AST/parser
 * slice).  Covers binder-aware name resolution (a bare reference to the bound name
 * inside {@code step} parses to a {@link RecursiveRefNode}), nested-{@code FIX}
 * shadowing, the base-is-not-in-scope rule, operator precedence, pretty-print
 * round-trips, and all error positions.
 */
final class FixpointParserTest extends ParserTestSupport {

    @Nested
    class HappyPath {

        @Test
        void resolvesBareStepReferenceToRecursiveRef() {
            // The step's bare `R` is the bound name → a RecursiveRefNode, not a RelationNode.
            assertParsesTo("FIX R (A, R)",
                    fixpoint("R", rel("A"), recRef("R")));
        }

        @Test
        void parsesUnionStepUnicode() {
            // The classic reachability shape: base ∪ recursive-step.
            assertParsesTo("FIX R (Edges, Edges ∪ R)",
                    fixpoint(
                            "R",
                            rel("Edges"),
                            union(rel("Edges"), recRef("R"))));
        }

        @Test
        void parsesUnionStepAscii() {
            // ASCII operand body parses to the identical AST as the Unicode form.
            assertParsesTo("FIX R (Edges, Edges UNION R)",
                    fixpoint(
                            "R",
                            rel("Edges"),
                            union(rel("Edges"), recRef("R"))));
        }

        @Test
        void resolvesRecursiveRefOnEitherJoinSide() {
            assertParsesTo("FIX R (Base, R ⋈ Step)",
                    fixpoint(
                            "R",
                            rel("Base"),
                            naturalJoin(recRef("R"), rel("Step"))));
            assertParsesTo("FIX R (Base, Step ⋈ R)",
                    fixpoint(
                            "R",
                            rel("Base"),
                            naturalJoin(rel("Step"), recRef("R"))));
        }

        @Test
        void resolvesRecursiveRefNestedUnderUnaryOperator() {
            assertParsesTo("FIX R (Base, σ a > 1 (R))",
                    fixpoint(
                            "R",
                            rel("Base"),
                            select(
                                    cmp(attr("a"), GREATER, num("1")),
                                    recRef("R"))));
        }

        @Test
        void fixBindsTighterThanJoin() {
            // A ⋈ FIX … parses as A ⋈ (FIX …), not (A ⋈ FIX) …
            assertParsesTo("A ⋈ FIX R (B, R)",
                    naturalJoin(
                            rel("A"),
                            fixpoint("R", rel("B"), recRef("R"))));
        }
    }

    @Nested
    class Scope {

        @Test
        void baseDoesNotBindTheRecursiveName() {
            // The bound name is in scope only within `step`; the same identifier in
            // `base` is an ordinary RelationNode (left to the validator, slice #114).
            assertParsesTo("FIX R (R, R)",
                    fixpoint("R", rel("R"), recRef("R")));
        }

        @Test
        void nameUnboundAfterTheFix() {
            // The binder pops on exit: the trailing `R` outside the FIX is a RelationNode.
            assertParsesTo("FIX R (A, R) ⋈ R",
                    naturalJoin(
                            fixpoint("R", rel("A"), recRef("R")),
                            rel("R")));
        }

        @Test
        void dottedReferenceIsNeverARecursiveRef() {
            // Only a *bare* identifier resolves to the binder; a dotted ref stays a
            // RelationNode (e.g. a connection-table reference shape).
            assertParsesTo("FIX R (A, R.x)",
                    fixpoint("R", rel("A"), rel("R.x")));
        }
    }

    @Nested
    class NestedFix {

        @Test
        void innerFixShadowsOuterBinderOfSameName() {
            // Inner `FIX R` shadows the outer `R`; the inner step's `R` binds to the
            // inner binder, and the outer binding is restored on exit.
            assertParsesTo("FIX R (A, FIX R (B, R))",
                    fixpoint(
                            "R",
                            rel("A"),
                            fixpoint("R", rel("B"), recRef("R"))));
        }

        @Test
        void distinctNestedBindersAreBothInScopeInInnerStep() {
            // Both the outer (R) and inner (S) names resolve to recursive refs inside
            // the inner step.
            assertParsesTo("FIX R (A, FIX S (B, R ⋈ S))",
                    fixpoint(
                            "R",
                            rel("A"),
                            fixpoint(
                                    "S",
                                    rel("B"),
                                    naturalJoin(
                                            recRef("R"),
                                            recRef("S")))));
        }
    }

    @Nested
    class PrettyPrint {

        @Test
        void prettyPrintsFixAndRecursiveRef() {
            assertPrettyPrints(
                    fixpoint(
                            "R",
                            rel("Edges"),
                            union(rel("Edges"), recRef("R"))),
                    "FIX R (Edges, (Edges) ∪ (R))");
        }

        @Test
        void roundTripsThroughParseAndPrettyPrint() {
            assertPrettyPrints(parse("FIX R (Edges, Edges ∪ R)"),
                    "FIX R (Edges, (Edges) ∪ (R))");
        }
    }

    @Nested
    class Errors {

        @Test
        void rejectsMissingName() {
            assertParseError("FIX (Edges, Edges)")
                    .hasMessageContaining("recursive relation name")
                    .at(1, 5)
                    .found("'('");
        }

        @Test
        void rejectsMissingOpenParen() {
            assertParseError("FIX R Edges")
                    .hasMessageContaining("(")
                    .at(1, 7)
                    .found("'Edges'");
        }

        @Test
        void rejectsMissingComma() {
            assertParseError("FIX R (Edges Edges)")
                    .hasMessageContaining(",")
                    .at(1, 14)
                    .found("'Edges'");
        }

        @Test
        void rejectsMissingStep() {
            assertParseError("FIX R (Edges, )")
                    .hasMessageContaining("Expected relation name")
                    .at(1, 15)
                    .found("')'");
        }

        @Test
        void rejectsMissingClosingParen() {
            assertParseError("FIX R (Edges, Edges")
                    .hasMessageContaining(")");
        }
    }
}

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

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.visitor.internal.PrettyPrinter;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

/**
 * The replace-each-round binder {@code ITERATE name (base, step) <stop>}: its three stop
 * clauses, its scoping (which is {@code FIX}'s), the contextual words that stay usable as
 * names, the printer's round trip, and every error position.
 */
final class IterateParserTest extends ParserTestSupport {

    @Nested
    class StopClauses {

        @Test
        void roundsIsAnExactCount() {
            assertParsesTo("ITERATE R (A, R) ROUNDS 4",
                    iterate("R", rel("A"), recRef("R"), rounds(4)));
        }

        @Test
        void roundsZeroIsAllowed() {
            assertParsesTo("ITERATE R (A, R) ROUNDS 0",
                    iterate("R", rel("A"), recRef("R"), rounds(0)));
        }

        @Test
        void untilStableTakesACap() {
            assertParsesTo("ITERATE R (A, R) UNTIL STABLE MAX 50 ROUNDS",
                    iterate("R", rel("A"), recRef("R"), untilStable(50)));
        }

        @Test
        void untilWithinTakesColumnsToleranceKeysAndACap() {
            assertParsesTo("ITERATE R (A, R) UNTIL rank WITHIN 0.0001 PER node MAX 100 ROUNDS",
                    iterate("R", rel("A"), recRef("R"),
                            untilConverged(List.of("rank"), new BigDecimal("0.0001"),
                                    List.of("node"), 100)));
        }

        @Test
        void untilWithinTakesListsOnBothSides() {
            assertParsesTo("ITERATE R (A, R) UNTIL hub, auth WITHIN 0.5 PER src, dst MAX 10 ROUNDS",
                    iterate("R", rel("A"), recRef("R"),
                            untilConverged(List.of("hub", "auth"), new BigDecimal("0.5"),
                                    List.of("src", "dst"), 10)));
        }

        @Test
        void aColumnNamedStableIsAnUntilColumn() {
            // STABLE is only the stable form when MAX follows it.
            assertParsesTo("ITERATE R (A, R) UNTIL stable WITHIN 1 PER k MAX 5 ROUNDS",
                    iterate("R", rel("A"), recRef("R"),
                            untilConverged(List.of("stable"), BigDecimal.ONE, List.of("k"), 5)));
            assertParsesTo("ITERATE R (A, R) UNTIL stable, x WITHIN 1 PER k MAX 5 ROUNDS",
                    iterate("R", rel("A"), recRef("R"),
                            untilConverged(List.of("stable", "x"), BigDecimal.ONE, List.of("k"), 5)));
        }

        @Test
        void theContextualWordsIgnoreCase() {
            assertParsesTo("iterate R (A, R) until stable max 3 rounds",
                    iterate("R", rel("A"), recRef("R"), untilStable(3)));
        }
    }

    @Nested
    class Scoping {

        @Test
        void theNameIsBoundInTheStepOnly() {
            // In the base, R is an ordinary relation; in the step, the previous round.
            assertParsesTo("ITERATE R (R, R ⋈ B) ROUNDS 1",
                    iterate("R", rel("R"), naturalJoin(recRef("R"), rel("B")), rounds(1)));
        }

        @Test
        void theStepMayReadTheNameMoreThanOnce() {
            assertParsesTo("ITERATE R (A, R − (R ⋈ B)) ROUNDS 1",
                    iterate("R", rel("A"),
                            difference(recRef("R"), naturalJoin(recRef("R"), rel("B"))),
                            rounds(1)));
        }

        @Test
        void aNestedFixOfTheSameNameShadowsIt() {
            assertParsesTo("ITERATE R (A, FIX R (B, R)) ROUNDS 1",
                    iterate("R", rel("A"), fixpoint("R", rel("B"), recRef("R")), rounds(1)));
        }

        @Test
        void theNameIsOutOfScopeAfterTheBinder() {
            assertParsesTo("ITERATE R (A, R) ROUNDS 1 ⋈ R",
                    naturalJoin(iterate("R", rel("A"), recRef("R"), rounds(1)), rel("R")));
        }
    }

    @Nested
    class ContextualWords {

        @Test
        void roundsUntilAndStableRemainColumnNames() {
            assertParsesTo("π rounds, until, stable (T)",
                    project(attrs("rounds", "until", "stable"), rel("T")));
        }

        @Test
        void theyRemainRelationNames() {
            assertParsesTo("rounds ⋈ stable",
                    naturalJoin(rel("rounds"), rel("stable")));
        }
    }

    @Nested
    class Printing {

        @Test
        void everyStopClauseRoundTrips() {
            for (String text : List.of(
                    "ITERATE R (A, R) ROUNDS 4",
                    "ITERATE R (A, R) UNTIL STABLE MAX 50 ROUNDS",
                    "ITERATE R (A, R) UNTIL hub, auth WITHIN 0.0001 PER node MAX 100 ROUNDS")) {
                RelNode parsed = parse(text);
                assertParsesTo(parsed.accept(new PrettyPrinter()), stripLocations(parsed));
            }
        }

        @Test
        void aReservedNameIsDelimited() {
            assertPrettyPrints(iterate("R", rel("iterate"), recRef("R"), rounds(1)),
                    "ITERATE R (`iterate`, R) ROUNDS 1");
        }
    }

    @Nested
    class Errors {

        @Test
        void rejectsAMissingStopClause() {
            assertParseError("ITERATE R (A, R)")
                    .hasMessageContaining("Expected 'ROUNDS n' or 'UNTIL …'")
                    .at(1, 17);
        }

        @Test
        void rejectsUntilStableWithoutACap() {
            assertParseError("ITERATE R (A, R) UNTIL STABLE")
                    .hasMessageContaining("needs a round cap");
        }

        @Test
        void rejectsUntilWithinWithoutACap() {
            assertParseError("ITERATE R (A, R) UNTIL rank WITHIN 0.1 PER node")
                    .hasMessageContaining("needs a round cap");
        }

        @Test
        void rejectsACapWithoutRounds() {
            assertParseError("ITERATE R (A, R) UNTIL STABLE MAX 5")
                    .hasMessageContaining("Expected 'ROUNDS' after 'MAX 5'");
        }

        @Test
        void rejectsAZeroCap() {
            assertParseError("ITERATE R (A, R) UNTIL STABLE MAX 0 ROUNDS")
                    .hasMessageContaining("at least 1, got 0")
                    .at(1, 35);
        }

        @Test
        void rejectsAFractionalRoundCount() {
            assertParseError("ITERATE R (A, R) ROUNDS 2.5")
                    .hasMessageContaining("whole number");
        }

        @Test
        void rejectsARoundCountTooLargeForAnInt() {
            assertParseError("ITERATE R (A, R) ROUNDS 99999999999")
                    .hasMessageContaining("whole number");
        }

        @Test
        void rejectsAMissingWithin() {
            assertParseError("ITERATE R (A, R) UNTIL rank PER node MAX 5 ROUNDS")
                    .hasMessageContaining("Expected 'WITHIN'");
        }

        @Test
        void rejectsAMissingTolerance() {
            assertParseError("ITERATE R (A, R) UNTIL rank WITHIN PER node MAX 5 ROUNDS")
                    .hasMessageContaining("Expected a tolerance");
        }

        @Test
        void rejectsAMissingPer() {
            assertParseError("ITERATE R (A, R) UNTIL rank WITHIN 0.1 MAX 5 ROUNDS")
                    .hasMessageContaining("Expected 'PER'");
        }

        @Test
        void rejectsAMissingKey() {
            assertParseError("ITERATE R (A, R) UNTIL rank WITHIN 0.1 PER , MAX 5 ROUNDS")
                    .hasMessageContaining("key column name");
        }

        @Test
        void rejectsAMissingUntilColumn() {
            assertParseError("ITERATE R (A, R) UNTIL WITHIN 0.1 PER node MAX 5 ROUNDS")
                    .hasMessageContaining("Expected 'WITHIN'");
        }

        @Test
        void rejectsAMissingName() {
            assertParseError("ITERATE (A, R) ROUNDS 1")
                    .hasMessageContaining("iterated relation name");
        }

        @Test
        void rejectsAMissingOpenParen() {
            assertParseError("ITERATE R A ROUNDS 1")
                    .hasMessageContaining("Expected '(' after the ITERATE relation name");
        }

        @Test
        void rejectsAMissingComma() {
            assertParseError("ITERATE R (A R) ROUNDS 1")
                    .hasMessageContaining("between the base and step of ITERATE R");
        }

        @Test
        void rejectsAMissingCloseParen() {
            assertParseError("ITERATE R (A, R ROUNDS 1")
                    .hasMessageContaining("Expected ')' after the ITERATE step expression");
        }
    }
}

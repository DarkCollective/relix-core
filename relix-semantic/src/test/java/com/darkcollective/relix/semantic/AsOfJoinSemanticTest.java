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
package com.darkcollective.relix.semantic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;


import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;

/** Tests for AS-OF join validation — the condition shape and the WITHIN tolerance. */
@DisplayName("AS-OF join — condition shape and WITHIN tolerance validation")
final class AsOfJoinSemanticTest {

    /** Inline tables: every column of a text cell infers as STRING. */
    private static final String INLINE =
            "Quotes := [| sym | ts                   | price |\n"
          + "           | ACME | 2026-01-02T09:00:00Z | 10.00 |];\n"
          + "Trades := [| sym | ts                   | qty |\n"
          + "           | ACME | 2026-01-02T09:02:00Z | 100 |];\n";

    /** The same two relations with the text columns converted to TIMESTAMP. */
    private static final String TYPED = INLINE
          + "QuotesT := { π sym, to_timestamp(ts) → ts, price (Quotes) };\n"
          + "TradesT := { π sym, to_timestamp(ts) → ts, qty (Trades) };\n";
    @Nested
    @DisplayName("WITHIN tolerance")
    class WithinTolerance {

        @Test
        @DisplayName("A STRING match column is rejected rather than silently un-bounded")
        void rejectsStringMatchColumn() {
            assertThat(analyze(INLINE + "query { Trades ASOF INNER Trades.sym = Quotes.sym "
                    + "∧ Trades.ts >= Quotes.ts WITHIN DURATION 'PT1M' Quotes };")).messages()
                    .anySatisfy(m -> assertThat(m)
                            .contains("WITHIN measures a temporal distance")
                            .contains("'Trades.ts' is STRING"));
        }

        @Test
        @DisplayName("A NUMBER match column is rejected — a duration is no distance between numbers")
        void rejectsNumericMatchColumn() {
            assertThat(analyze(
                    "Trades := [| sym | t  |\n| A | 20 |];\n"
                  + "Quotes := [| qsym | qt | bid |\n| A | 15 | 199 |];\n"
                  + "query { Trades ASOF Trades.sym = Quotes.qsym ∧ Trades.t >= Quotes.qt "
                  + "WITHIN DURATION 'PT30M' Quotes };")).messages()
                    .anySatisfy(m -> assertThat(m).contains("'Trades.t' is NUMBER"));
        }

        @Test
        @DisplayName("A TIMESTAMP match column is accepted")
        void acceptsTimestampMatchColumn() {
            assertThat(analyze(TYPED + "query { TradesT ASOF TradesT.sym = QuotesT.sym "
                    + "∧ TradesT.ts >= QuotesT.ts WITHIN DURATION 'PT1M' QuotesT };")).messages()
                    .isEmpty();
        }

        @Test
        @DisplayName("The same STRING column is accepted without a WITHIN clause")
        void acceptsStringMatchColumnWithoutTolerance() {
            assertThat(analyze(INLINE + "query { Trades ASOF Trades.sym = Quotes.sym "
                    + "∧ Trades.ts >= Quotes.ts Quotes };")).messages()
                    .isEmpty();
        }

        @Test
        @DisplayName("An ANY match column is left to the executor — its type is not known here")
        void acceptsDynamicMatchColumn() {
            assertThat(analyze(
                    "source Feed from database { url: \"jdbc:x\", table: \"feed\", "
                  + "schema: { sym: STRING, ts: ANY } };\n"
                  + "source Book from database { url: \"jdbc:x\", table: \"book\", "
                  + "schema: { sym: STRING, ts: ANY } };\n"
                  + "query { Feed ASOF Feed.sym = Book.sym ∧ Feed.ts >= Book.ts "
                  + "WITHIN DURATION 'PT1M' Book };")).messages()
                    .isEmpty();
        }

        @Test
        @DisplayName("A tolerance that is not a DURATION literal is a positioned error")
        void rejectsNonDurationTolerance() {
            assertThat(analyze(TYPED + "query { TradesT ASOF TradesT.sym = QuotesT.sym "
                    + "∧ TradesT.ts >= QuotesT.ts WITHIN 5 QuotesT };")).messages()
                    .anySatisfy(m -> assertThat(m).contains("WITHIN tolerance must be a DURATION literal"));
        }
    }

    @Nested
    @DisplayName("Condition shape")
    class ConditionShape {

        @Test
        @DisplayName("Exactly one ordering inequality is required")
        void requiresExactlyOneInequality() {
            assertThat(analyze(TYPED + "query { TradesT ASOF TradesT.sym = QuotesT.sym QuotesT };")).messages()
                    .anySatisfy(m -> assertThat(m).contains("exactly one ordering inequality"));
        }

        @Test
        @DisplayName("≠ is rejected as the match comparison")
        void rejectsNotEqual() {
            assertThat(analyze(TYPED + "query { TradesT ASOF TradesT.sym != QuotesT.sym QuotesT };")).messages()
                    .anySatisfy(m -> assertThat(m).contains("may not use ≠"));
        }

        @Test
        @DisplayName("A disjunction is not a valid AS-OF condition")
        void rejectsDisjunction() {
            assertThat(analyze(TYPED + "query { TradesT ASOF TradesT.sym = QuotesT.sym "
                    + "∨ TradesT.ts >= QuotesT.ts QuotesT };")).messages()
                    .anySatisfy(m -> assertThat(m).contains("must be a conjunction"));
        }

        @Test
        @DisplayName("A rejected shape suppresses the tolerance's match-column check")
        void shapeErrorSuppressesToleranceCheck() {
            SemanticResult result = analyze(INLINE + "query { Trades ASOF Trades.sym = Quotes.sym "
                    + "WITHIN DURATION 'PT1M' Quotes };");
            assertThat(result).hasErrorContaining("exactly one ordering inequality")
                    .hasNoErrorContaining("WITHIN measures a temporal distance");
        }
    }
}

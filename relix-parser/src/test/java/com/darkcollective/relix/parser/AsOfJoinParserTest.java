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

import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;

final class AsOfJoinParserTest extends ParserTestSupport {

    // ── new clause tests ──────────────────────────────────────────────────────

    @Test
    void parsesInnerModifier() {
        assertParsesTo("L ASOF INNER L.ts >= R.ts R",
                asOfJoin(
                        rel("L"),
                        rel("R"),
                        cmp(attr("L.ts"), ComparisonOperator.GREATER_EQUAL, attr("R.ts")),
                        Optional.empty(), true, TieBreak.LAST));
    }

    @Test
    void parsesWithinTolerance() {
        assertParsesTo("L ASOF L.ts >= R.ts WITHIN DURATION 'PT30M' R",
                asOfJoin(
                        rel("L"),
                        rel("R"),
                        cmp(attr("L.ts"), ComparisonOperator.GREATER_EQUAL, attr("R.ts")),
                        Optional.of(duration("PT30M")), false, TieBreak.LAST));
    }

    @Test
    void parsesTiesFirst() {
        assertParsesTo("L ASOF L.ts >= R.ts TIES(FIRST) R",
                asOfJoin(
                        rel("L"),
                        rel("R"),
                        cmp(attr("L.ts"), ComparisonOperator.GREATER_EQUAL, attr("R.ts")),
                        Optional.empty(), false, TieBreak.FIRST));
    }

    @Test
    void parsesTiesLast() {
        assertParsesTo("L ASOF L.ts >= R.ts TIES(LAST) R",
                asOfJoin(
                        rel("L"),
                        rel("R"),
                        cmp(attr("L.ts"), ComparisonOperator.GREATER_EQUAL, attr("R.ts")),
                        Optional.empty(), false, TieBreak.LAST));
    }

    @Test
    void parsesInnerWithToleranceAndTiesFirst() {
        assertParsesTo("L ASOF INNER L.ts >= R.ts WITHIN DURATION 'PT1H' TIES(FIRST) R",
                asOfJoin(
                        rel("L"),
                        rel("R"),
                        cmp(attr("L.ts"), ComparisonOperator.GREATER_EQUAL, attr("R.ts")),
                        Optional.of(duration("PT1H")), true, TieBreak.FIRST));
    }

    // ── existing tests ────────────────────────────────────────────────────────

    @Test
    void parsesBackwardAsOf() {
        assertParsesTo("Trades ASOF Trades.sym = Quotes.sym ∧ Trades.ts >= Quotes.ts Quotes",
                asOfJoin(
                        rel("Trades"),
                        rel("Quotes"),
                        and(
                                cmp(attr("Trades.sym"), ComparisonOperator.EQUAL, attr("Quotes.sym")),
                                cmp(attr("Trades.ts"), ComparisonOperator.GREATER_EQUAL, attr("Quotes.ts")))));
    }

    @Test
    void parsesAsciiAndConjunction() {
        assertParsesTo("Trades ASOF Trades.sym = Quotes.sym AND Trades.ts >= Quotes.ts Quotes",
                asOfJoin(
                        rel("Trades"),
                        rel("Quotes"),
                        and(
                                cmp(attr("Trades.sym"), ComparisonOperator.EQUAL, attr("Quotes.sym")),
                                cmp(attr("Trades.ts"), ComparisonOperator.GREATER_EQUAL, attr("Quotes.ts")))));
    }

    @Test
    void parsesKeywordCaseInsensitively() {
        assertParsesTo("L asof L.ts <= R.ts R",
                asOfJoin(
                        rel("L"),
                        rel("R"),
                        cmp(attr("L.ts"), ComparisonOperator.LESS_EQUAL, attr("R.ts"))));
    }

    @Test
    void parsesSingleInequalityNoPartition() {
        assertParsesTo("L ASOF L.ts > R.ts R",
                asOfJoin(
                        rel("L"),
                        rel("R"),
                        cmp(attr("L.ts"), ComparisonOperator.GREATER, attr("R.ts"))));
    }

    @Test
    void bindsTighterThanUnion() {
        assertParsesTo("A ASOF A.ts >= B.ts B ∪ C",
                union(
                        asOfJoin(
                                rel("A"),
                                rel("B"),
                                cmp(attr("A.ts"), ComparisonOperator.GREATER_EQUAL, attr("B.ts"))),
                        rel("C")));
    }

    @Test
    void prettyPrintRoundTrips() {
        RelNode original = asOfJoin(
                rel("Trades"),
                rel("Quotes"),
                cmp(attr("Trades.ts"), ComparisonOperator.GREATER_EQUAL, attr("Quotes.ts")));
        assertPrettyPrints(original, "(Trades) ASOF Trades.ts ≥ Quotes.ts (Quotes)");
        assertParsesTo(original.prettyPrint(), original);
    }

    @Test
    void failsOnMissingCondition() {
        assertParseError("L ASOF R")
                .hasMessageContaining("Expected comparison operator");
    }

    @Test
    void failsOnMissingRightOperand() {
        assertParseError("L ASOF L.ts >= R.ts")
                .hasMessageContaining("Expected relation name");
    }
}

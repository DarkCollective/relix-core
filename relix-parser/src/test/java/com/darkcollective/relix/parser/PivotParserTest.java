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

import java.util.List;

/**
 * Parser tests for the rows-to-columns operator
 * {@code PIVOT valueCol BY keyCol [PER groupKey1, ...] (R)} (issue #16).
 */
final class PivotParserTest extends ParserTestSupport {

    @Test
    void parsesBasicPivot() {
        assertParsesTo(
                "PIVOT revenue BY quarter (QuarterlySales)",
                pivot("revenue", "quarter", List.of(), rel("QuarterlySales")));
    }

    @Test
    void parsesWithPerKeys() {
        assertParsesTo(
                "PIVOT revenue BY quarter PER region (QuarterlySales)",
                pivot("revenue", "quarter", List.of("region"), rel("QuarterlySales")));
    }

    @Test
    void parsesWithMultiplePerKeys() {
        assertParsesTo(
                "PIVOT amount BY category PER year, region (Sales)",
                pivot("amount", "category", List.of("year", "region"), rel("Sales")));
    }

    @Test
    void parsesLowercaseKeywords() {
        assertParsesTo(
                "pivot amount by category per region (Sales)",
                pivot("amount", "category", List.of("region"), rel("Sales")));
    }

    @Test
    void parsesOverNestedOperator() {
        assertParsesTo(
                "PIVOT score BY subject (σ active = true (Results))",
                pivot(
                        "score",
                        "subject",
                        List.of(),
                        select(
                                cmp(attr("active"), ComparisonOperator.EQUAL, bool(true)),
                                rel("Results"))));
    }

    @Test
    void bindsTighterThanJoin() {
        assertParsesTo(
                "A ⋈ PIVOT revenue BY quarter (Sales)",
                naturalJoin(
                        rel("A"),
                        pivot("revenue", "quarter", List.of(), rel("Sales"))));
    }

    @Test
    void prettyPrintsWithoutPer() {
        assertPrettyPrints(
                pivot("revenue", "quarter", List.of(), rel("Sales")),
                "PIVOT revenue BY quarter (Sales)");
    }

    @Test
    void prettyPrintsWithPer() {
        assertPrettyPrints(
                pivot("revenue", "quarter", List.of("region"), rel("Sales")),
                "PIVOT revenue BY quarter PER region (Sales)");
    }

    @Test
    void prettyPrintsWithMultiplePerKeys() {
        assertPrettyPrints(
                pivot("amount", "category", List.of("year", "region"), rel("Sales")),
                "PIVOT amount BY category PER year, region (Sales)");
    }

    @Test
    void rejectsMissingBy() {
        assertParseError("PIVOT revenue quarter (Sales)").hasMessageContaining("BY");
    }

    @Test
    void rejectsMissingValueColumn() {
        assertParseError("PIVOT BY quarter (Sales)").hasMessageContaining("BY");
    }

    @Test
    void rejectsMissingKeyColumn() {
        assertParseError("PIVOT revenue BY (Sales)").hasMessageContaining("(");
    }

    @Test
    void rejectsMissingInputParen() {
        assertParseError("PIVOT revenue BY quarter Sales").hasMessageContaining("(");
    }

    @Test
    void rejectsMissingInputCloseParenAtEof() {
        assertParseError("PIVOT revenue BY quarter (Sales").hasMessageContaining(")");
    }
}

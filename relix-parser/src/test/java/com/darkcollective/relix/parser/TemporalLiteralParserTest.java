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

import com.darkcollective.relix.ast.*;
import com.darkcollective.relix.ast.internal.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parser tests for the typed temporal literals {@code DATE '…'}, {@code TIME '…'},
 * {@code TIMESTAMP '…'}, and {@code DURATION '…'} (ADR-0013, slice 2).
 */
final class TemporalLiteralParserTest extends ParserTestSupport {

    @Nested
    class HappyPath {
        @Test
        void parsesDateLiteral() {
            assertParsesTo("π DATE '2026-06-15' (R)",
                    project(List.of(projected(date("2026-06-15"))), rel("R")));
        }

        @Test
        void parsesTimeLiteral() {
            assertParsesTo("π TIME '13:40:00' (R)",
                    project(List.of(projected(time("13:40:00"))), rel("R")));
        }

        @Test
        void parsesTimestampLiteralWithZ() {
            assertParsesTo("π TIMESTAMP '2026-06-15T13:40:00Z' (R)",
                    project(
                            List.of(projected(timestamp("2026-06-15T13:40:00Z"))),
                            rel("R")));
        }

        @Test
        void parsesDurationLiteral() {
            assertParsesTo("π DURATION 'PT30M' (R)",
                    project(List.of(projected(duration("PT30M"))), rel("R")));
        }

        @Test
        void temporalLiteralWorksInSelectionPredicate() {
            // Unicode σ context.
            assertParsesTo("σ at >= TIMESTAMP '2026-06-01T00:00:00Z' (Trades)",
                    select(
                            cmp(attr("at"), ComparisonOperator.GREATER_EQUAL,
                                    timestamp("2026-06-01T00:00:00Z")),
                            rel("Trades")));
        }

        @Test
        void temporalLiteralWorksInAsciiKeywordContext() {
            // ASCII SELECT context + lowercase keyword (keyword matching is case-insensitive).
            assertParsesTo("SELECT day = date '2026-06-15' (Trades)",
                    select(
                            cmp(attr("day"), ComparisonOperator.EQUAL, date("2026-06-15")),
                            rel("Trades")));
        }

        @Test
        void doubleQuotedPayloadIsAlsoAccepted() {
            // The lexer accepts single- and double-quoted string literals.
            assertParsesTo("π DATE \"2026-06-15\" (R)",
                    project(List.of(projected(date("2026-06-15"))), rel("R")));
        }
    }

    @Nested
    class TimestampNormalisation {
        @Test
        void offsetIsNormalisedToUtc() {
            // 13:40+01:00 == 12:40Z
            RelNode parsed = parse("π TIMESTAMP '2026-06-15T13:40:00+01:00' (R)");
            TimestampOperand op = (TimestampOperand) ((ProjectionNode) parsed)
                    .attributes().get(0).expression();
            assertThat(op.value()).isEqualTo(Instant.parse("2026-06-15T12:40:00Z"));
        }

        @Test
        void offsetlessPayloadIsInterpretedAsUtc() {
            RelNode parsed = parse("π TIMESTAMP '2026-06-15T13:40:00' (R)");
            TimestampOperand op = (TimestampOperand) ((ProjectionNode) parsed)
                    .attributes().get(0).expression();
            assertThat(op.value()).isEqualTo(Instant.parse("2026-06-15T13:40:00Z"));
        }
    }

    @Nested
    class Errors {
        @Test
        void malformedDatePayloadIsAParseErrorAtThePayload() {
            // The offending token is the payload string; its lexeme already carries the
            // source quotes, so the diagnostic's quoting doubles them up ('' … '').
            assertParseError("π DATE 'nope' (R)")
                    .hasMessageContaining("Malformed DATE literal")
                    .at(1, 8)
                    .found("''nope''");
        }

        @Test
        void malformedDateValueReportsIso() {
            // Syntactically a string, but month 13 is not a valid calendar date.
            assertParseError("π DATE '2026-13-99' (R)")
                    .hasMessageContaining("not a valid ISO-8601 date");
        }

        @Test
        void malformedDurationPayloadIsAParseError() {
            assertParseError("π DURATION 'PT30Z' (R)")
                    .hasMessageContaining("Malformed DURATION literal");
        }

        @Test
        void missingPayloadIsAParseError() {
            assertParseError("π DATE (R)")
                    .hasMessageContaining("Expected a quoted ISO-8601 string after 'DATE'");
        }
    }

    @Nested
    class CalendarIntervals {

        private static RelNode projectionOf(Operand operand) {
            return project(List.of(projected(operand)), rel("R"));
        }

        @Test
        void durationTakesCalendarMonths() {
            assertParsesTo("π DURATION 'P1Y2M' (R)", projectionOf(duration("P14M")));
            assertThat(parse("π DURATION 'P1Y2MT3H' (R)").prettyPrint()).isEqualTo("π DURATION 'P1Y2MT3H' (R)");
        }

        @Test
        void intervalInEachEnginesSpelling() {
            assertParsesTo("π INTERVAL '1 month' (R)", projectionOf(duration("P1M")));
            assertParsesTo("π interval 'P1M' (R)", projectionOf(duration("P1M")));
            assertParsesTo("π INTERVAL '3' DAY (R)", projectionOf(duration("P3D")));
            assertParsesTo("π INTERVAL 2 YEARS (R)", projectionOf(duration("P2Y")));
            assertParsesTo("π INTERVAL -1 MONTH (R)", projectionOf(duration("-P1M")));
            assertParsesTo("π INTERVAL 1.5 month (R)", projectionOf(duration("P1MT360H")));
            assertParsesTo("π d + INTERVAL '1 day' (R)",
                    projectionOf(arith(attr("d"), ArithmeticOperator.PLUS, duration("P1D"))));
            // Not reserved: a column may be called interval, and have a number taken from it.
            assertParsesTo("π interval (R)", projectionOf(attr("interval")));
            assertParsesTo("π interval - 1 (R)",
                    projectionOf(arith(attr("interval"), ArithmeticOperator.MINUS, num("1"))));
            assertParsesTo("π interval - x (R)",
                    projectionOf(arith(attr("interval"), ArithmeticOperator.MINUS, attr("x"))));
        }

        @Test
        void intervalErrors() {
            assertParseError("π INTERVAL 1 (R)").hasMessageContaining("Expected a unit after INTERVAL 1");
            assertParseError("π INTERVAL 'a while' (R)").hasMessageContaining("Malformed INTERVAL");
            assertParseError("π INTERVAL '3' fortnight (R)").hasMessageContaining("Malformed INTERVAL");
        }

        @Test
        void aRangeOffsetIsExact() {
            assertParseError("ROLLING COUNT(x) OVER RANGE BETWEEN DURATION 'P1M' PRECEDING AND CURRENT ROW"
                    + " SORT t AS n (R)").hasMessageContaining("A RANGE offset is an exact DURATION");
            assertParseError("ROLLING COUNT(x) OVER RANGE BETWEEN DURATION '-P1M' PRECEDING AND CURRENT ROW"
                    + " SORT t AS n (R)").hasMessageContaining("cannot be negative");
        }
    }
}

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
 * Tests for the window operator's {@code ROLLING} (sliding / cumulative aggregate)
 * surface syntax and the {@code WINDOW} stub (ADR-0015, slice 2 / issue #232).
 */
final class WindowParserTest extends ParserTestSupport {

    @Test
    void parsesBoundedRollingAverage() {
        assertParsesTo("ROLLING AVG(price) OVER 3 ROWS SORT trade_time ASC PER ticker AS avg3 (Ticks)",
                window(
                        new WindowFunction.AggregateWindow(AggregateOperator.AVG, attr("price")),
                        List.of("ticker"),
                        List.of(asc("trade_time")),
                        new WindowFrame.BoundedFrame(3),
                        "avg3",
                        rel("Ticks")));
    }

    @Test
    void parsesCumulativeSum() {
        assertParsesTo("ROLLING SUM(revenue) OVER ALL ROWS SORT month ASC PER region AS running (Sales)",
                window(
                        new WindowFunction.AggregateWindow(AggregateOperator.SUM, attr("revenue")),
                        List.of("region"),
                        List.of(asc("month")),
                        new WindowFrame.CumulativeFrame(),
                        "running",
                        rel("Sales")));
    }

    @Test
    void parsesWithoutPerClause() {
        // No PER — one partition over the whole relation.
        assertParsesTo("ROLLING MAX(score) OVER ALL ROWS SORT ts ASC AS running_max (Events)",
                window(
                        new WindowFunction.AggregateWindow(AggregateOperator.MAX, attr("score")),
                        List.of(),
                        List.of(asc("ts")),
                        new WindowFrame.CumulativeFrame(),
                        "running_max",
                        rel("Events")));
    }

    @Test
    void parsesMultipleSortAndPartitionKeys() {
        assertParsesTo("ROLLING COUNT(id) OVER 5 ROWS SORT day ASC, seq DESC PER region, store AS c (R)",
                window(
                        new WindowFunction.AggregateWindow(AggregateOperator.COUNT, attr("id")),
                        List.of("region", "store"),
                        List.of(asc("day"), desc("seq")),
                        new WindowFrame.BoundedFrame(5),
                        "c",
                        rel("R")));
    }

    @Test
    void parsesAsciiLowercaseKeywords() {
        assertParsesTo("rolling sum(amount) over 2 rows sort t asc per k as total (R)",
                window(
                        new WindowFunction.AggregateWindow(AggregateOperator.SUM, attr("amount")),
                        List.of("k"),
                        List.of(asc("t")),
                        new WindowFrame.BoundedFrame(2),
                        "total",
                        rel("R")));
    }

    @Test
    void parsesExpressionArgument() {
        assertParsesTo("ROLLING SUM(price * qty) OVER 3 ROWS SORT t ASC AS rev (R)",
                window(
                        new WindowFunction.AggregateWindow(AggregateOperator.SUM,
                                arith(attr("price"), ArithmeticOperator.MULTIPLY, attr("qty"))),
                        List.of(),
                        List.of(asc("t")),
                        new WindowFrame.BoundedFrame(3),
                        "rev",
                        rel("R")));
    }

    @Test
    void prettyPrintsRolling() {
        assertPrettyPrints(
                window(
                        new WindowFunction.AggregateWindow(AggregateOperator.AVG, attr("price")),
                        List.of("ticker"),
                        List.of(asc("trade_time")),
                        new WindowFrame.BoundedFrame(3),
                        "avg3",
                        rel("Ticks")),
                "ROLLING AVG(price) OVER 3 ROWS SORT trade_time PER ticker AS avg3 (Ticks)");
    }

    @Test
    void windowBindsTighterThanJoin() {
        assertParsesTo("A ⋈ ROLLING SUM(x) OVER 2 ROWS SORT t ASC AS s (B)",
                naturalJoin(
                        rel("A"),
                        window(
                                new WindowFunction.AggregateWindow(AggregateOperator.SUM, attr("x")),
                                List.of(),
                                List.of(asc("t")),
                                new WindowFrame.BoundedFrame(2),
                                "s",
                                rel("B"))));
    }

    @Test
    void rejectsMissingOver() {
        assertParseError("ROLLING SUM(x) 3 ROWS SORT t ASC AS s (R)").hasMessageContaining("OVER");
    }

    @Test
    void rejectsMissingRows() {
        assertParseError("ROLLING SUM(x) OVER 3 SORT t ASC AS s (R)").hasMessageContaining("ROWS");
    }

    @Test
    void rejectsMissingSort() {
        assertParseError("ROLLING SUM(x) OVER 3 ROWS PER k AS s (R)").hasMessageContaining("SORT");
    }

    @Test
    void rejectsMissingAs() {
        assertParseError("ROLLING SUM(x) OVER 3 ROWS SORT t ASC (R)").hasMessageContaining("AS");
    }

    @Test
    void rejectsZeroFrameSize() {
        assertParseError("ROLLING SUM(x) OVER 0 ROWS SORT t ASC AS s (R)").hasMessageContaining("at least 1");
    }

    @Test
    void rejectsUnsupportedAggregate() {
        assertParseError("ROLLING COLLECT(x) OVER 3 ROWS SORT t ASC AS s (R)")
                .hasMessageContaining("SUM, AVG, COUNT, MIN, MAX");
    }

    @Test
    void rejectsBadFrame() {
        assertParseError("ROLLING SUM(x) OVER foo ROWS SORT t ASC AS s (R)")
                .hasMessageContaining("ALL ROWS");
    }

    // ─── ranking functions (slice 3, issue #233) ───────────────────────────────

    private static WindowNode ranking(RankingFunction fn, java.util.Optional<Operand> ntile,
                                      List<String> partitionKeys, List<SortSpecification> specs,
                                      String outputColumn, RelNode input) {
        return window(
                new WindowFunction.RankingWindow(fn, ntile),
                partitionKeys,
                specs,
                new WindowFrame.PartitionFrame(),
                outputColumn,
                input);
    }

    @Test
    void parsesRowNumber() {
        assertParsesTo("WINDOW ROW_NUMBER() SORT score DESC PER dept AS rn (Emp)",
                ranking(RankingFunction.ROW_NUMBER, java.util.Optional.empty(),
                        List.of("dept"), List.of(desc("score")), "rn", rel("Emp")));
    }

    @Test
    void parsesRank() {
        assertParsesTo("WINDOW RANK() SORT amount DESC PER customer_id AS rnk (Orders)",
                ranking(RankingFunction.RANK, java.util.Optional.empty(),
                        List.of("customer_id"), List.of(desc("amount")), "rnk",
                        rel("Orders")));
    }

    @Test
    void parsesDenseRank() {
        assertParsesTo("WINDOW DENSE_RANK() SORT units_sold DESC PER category_id AS dr (Products)",
                ranking(RankingFunction.DENSE_RANK, java.util.Optional.empty(),
                        List.of("category_id"), List.of(desc("units_sold")), "dr",
                        rel("Products")));
    }

    @Test
    void parsesPercentRank() {
        assertParsesTo("WINDOW PERCENT_RANK() SORT salary ASC PER dept_id AS pr (Employees)",
                ranking(RankingFunction.PERCENT_RANK, java.util.Optional.empty(),
                        List.of("dept_id"), List.of(asc("salary")), "pr",
                        rel("Employees")));
    }

    @Test
    void parsesNtileWithArgument() {
        assertParsesTo("WINDOW NTILE(4) SORT ytd_revenue DESC PER region AS qtile (Sales)",
                ranking(RankingFunction.NTILE, java.util.Optional.of(num("4")),
                        List.of("region"), List.of(desc("ytd_revenue")), "qtile",
                        rel("Sales")));
    }

    @Test
    void parsesRankingWithoutPerClause() {
        assertParsesTo("WINDOW ROW_NUMBER() SORT ts ASC AS rn (Events)",
                ranking(RankingFunction.ROW_NUMBER, java.util.Optional.empty(),
                        List.of(), List.of(asc("ts")), "rn", rel("Events")));
    }

    @Test
    void parsesRankingAsciiLowercase() {
        assertParsesTo("window rank() sort amount desc per c as rnk (R)",
                ranking(RankingFunction.RANK, java.util.Optional.empty(),
                        List.of("c"), List.of(desc("amount")), "rnk", rel("R")));
    }

    @Test
    void rejectsRankingMissingParens() {
        assertParseError("WINDOW ROW_NUMBER SORT score DESC PER dept AS rn (Emp)")
                .hasMessageContaining("(");
    }

    @Test
    void rejectsRankingMissingSort() {
        assertParseError("WINDOW RANK() PER dept AS rn (Emp)").hasMessageContaining("SORT");
    }

    @Test
    void rejectsRankingMissingAs() {
        assertParseError("WINDOW RANK() SORT score DESC PER dept (Emp)").hasMessageContaining("AS");
    }

    @Test
    void rejectsUnknownWindowFunction() {
        assertParseError("WINDOW BOGUS() SORT score DESC AS x (R)")
                .hasMessageContaining("Unknown window function");
    }

    // ─── offset functions (slice 4, issue #234) ────────────────────────────────

    private static WindowNode offset(OffsetFunction fn, Operand expr,
                                     java.util.Optional<Operand> off,
                                     java.util.Optional<Operand> dflt,
                                     List<String> partitionKeys, List<SortSpecification> specs,
                                     String outputColumn, RelNode input) {
        return window(
                new WindowFunction.OffsetWindow(fn, expr, off, dflt),
                partitionKeys,
                specs,
                new WindowFrame.PartitionFrame(),
                outputColumn,
                input);
    }

    @Test
    void parsesLagWithOffset() {
        assertParsesTo("WINDOW LAG(revenue, 1) SORT month ASC PER region AS prev (Monthly)",
                offset(OffsetFunction.LAG, attr("revenue"),
                        java.util.Optional.of(num("1")), java.util.Optional.empty(),
                        List.of("region"), List.of(asc("month")), "prev",
                        rel("Monthly")));
    }

    @Test
    void parsesLagWithDefaultOmittedOffset() {
        // LAG(expr) — offset defaults to 1, no explicit default value.
        assertParsesTo("WINDOW LAG(revenue) SORT month ASC PER region AS prev (Monthly)",
                offset(OffsetFunction.LAG, attr("revenue"),
                        java.util.Optional.empty(), java.util.Optional.empty(),
                        List.of("region"), List.of(asc("month")), "prev",
                        rel("Monthly")));
    }

    @Test
    void parsesLagWithExplicitDefault() {
        assertParsesTo("WINDOW LAG(revenue, 2, 0) SORT month ASC PER region AS prev (Monthly)",
                offset(OffsetFunction.LAG, attr("revenue"),
                        java.util.Optional.of(num("2")), java.util.Optional.of(num("0")),
                        List.of("region"), List.of(asc("month")), "prev",
                        rel("Monthly")));
    }

    @Test
    void parsesLead() {
        assertParsesTo("WINDOW LEAD(event_time, 1) SORT event_time ASC PER user_id AS next_time (Clicks)",
                offset(OffsetFunction.LEAD, attr("event_time"),
                        java.util.Optional.of(num("1")), java.util.Optional.empty(),
                        List.of("user_id"), List.of(asc("event_time")), "next_time",
                        rel("Clicks")));
    }

    @Test
    void parsesFirstValue() {
        assertParsesTo("WINDOW FIRST_VALUE(price) SORT trade_time ASC PER session_id AS open_price (Trades)",
                offset(OffsetFunction.FIRST_VALUE, attr("price"),
                        java.util.Optional.empty(), java.util.Optional.empty(),
                        List.of("session_id"), List.of(asc("trade_time")), "open_price",
                        rel("Trades")));
    }

    @Test
    void parsesLastValue() {
        assertParsesTo("WINDOW LAST_VALUE(price) SORT trade_time ASC PER session_id AS close_price (Trades)",
                offset(OffsetFunction.LAST_VALUE, attr("price"),
                        java.util.Optional.empty(), java.util.Optional.empty(),
                        List.of("session_id"), List.of(asc("trade_time")), "close_price",
                        rel("Trades")));
    }

    @Test
    void parsesOffsetAsciiLowercase() {
        assertParsesTo("window lag(v, 1) sort t asc per k as prev (R)",
                offset(OffsetFunction.LAG, attr("v"),
                        java.util.Optional.of(num("1")), java.util.Optional.empty(),
                        List.of("k"), List.of(asc("t")), "prev", rel("R")));
    }

    @Test
    void parsesOffsetExpressionArgument() {
        assertParsesTo("WINDOW LAG(price * qty) SORT t ASC AS prev (R)",
                offset(OffsetFunction.LAG,
                        arith(attr("price"), ArithmeticOperator.MULTIPLY, attr("qty")),
                        java.util.Optional.empty(), java.util.Optional.empty(),
                        List.of(), List.of(asc("t")), "prev", rel("R")));
    }

    @Test
    void rejectsOffsetMissingParens() {
        assertParseError("WINDOW LAG SORT t ASC AS prev (R)").hasMessageContaining("(");
    }

    @Test
    void rejectsOffsetMissingSort() {
        assertParseError("WINDOW LAG(v, 1) PER k AS prev (R)").hasMessageContaining("SORT");
    }

    @Test
    void rejectsOffsetMissingAs() {
        assertParseError("WINDOW LEAD(v) SORT t ASC PER k (R)").hasMessageContaining("AS");
    }

    @Test
    void prettyPrintsOffset() {
        assertPrettyPrints(
                offset(OffsetFunction.LAG, attr("revenue"),
                        java.util.Optional.of(num("1")), java.util.Optional.of(num("0")),
                        List.of("region"), List.of(asc("month")), "prev",
                        rel("Monthly")),
                "WINDOW LAG(revenue, 1, 0) SORT month PER region AS prev (Monthly)");
    }

    @Test
    void prettyPrintsRanking() {
        assertPrettyPrints(
                ranking(RankingFunction.NTILE, java.util.Optional.of(num("4")),
                        List.of("region"), List.of(desc("rev")), "qtile", rel("Sales")),
                "WINDOW NTILE(4) SORT rev DESC PER region AS qtile (Sales)");
    }
}

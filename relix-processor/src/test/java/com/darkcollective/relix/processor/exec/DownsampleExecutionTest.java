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
package com.darkcollective.relix.processor.exec;

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.processor.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;

@DisplayName("PhysicalExecutor — DOWNSAMPLE operator")
final class DownsampleExecutionTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    // ── helpers ───────────────────────────────────────────────────────────────

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget   named -> rel(named.name());
            case ExpressionQueryTarget e  -> e.expression();
        };
    }

    private static List<Row> collect(String src) {
        SemanticModel model = model(src);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        QueryStatement query = model.rootQueries().getFirst();
        try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
            return stream.toList();
        }
    }

    private static Row earliest(List<Row> rows) {
        return rows.stream()
                .min(Comparator.comparing(r -> ((TimestampValue) r.get("bucket")).value()))
                .orElseThrow();
    }

    private static Row latest(List<Row> rows) {
        return rows.stream()
                .max(Comparator.comparing(r -> ((TimestampValue) r.get("bucket")).value()))
                .orElseThrow();
    }

    private static BigDecimal bd(Row row, String col) {
        return new BigDecimal(row.get(col).asDisplayString());
    }

    /**
     * Three rows spanning two 5-minute buckets; ts converted from STRING via to_timestamp():
     *   - 2026-01-01T00:00:30Z  web1  cpu=10  mem=200   → bucket 2026-01-01T00:00Z
     *   - 2026-01-01T00:02:00Z  web1  cpu=20  mem=400   → bucket 2026-01-01T00:00Z
     *   - 2026-01-01T00:05:00Z  web1  cpu=30  mem=600   → bucket 2026-01-01T00:05Z
     */
    private static final String METRICS =
            "RawMetrics := [| ts | host | cpu | mem |\n" +
            "               | 2026-01-01T00:00:30Z | web1 | 10 | 200 |\n" +
            "               | 2026-01-01T00:02:00Z | web3 | 20 | 400 |\n" +
            "               | 2026-01-01T00:05:00Z | web2 | 30 | 600 |\n" +
            "];\n" +
            "Metrics := { π to_timestamp(ts) → ts, host, cpu, mem (RawMetrics) };\n";

    /**
     * Three rows in one 1-hour bucket, carrying a second TIMESTAMP column and a row
     * whose host and start time are both absent — the fixture for consolidating a
     * column that is not a number.
     */
    private static final String SPANS =
            "RawSpans := [| ts | host | started |\n" +
            "             | 2026-01-01T00:00:00Z | web1 | 2026-01-01T00:00:10Z |\n" +
            "             | 2026-01-01T00:01:00Z | web1 | 2026-01-01T00:02:10Z |\n" +
            "             | 2026-01-01T00:02:00Z | NULL | NULL |\n" +
            "];\n" +
            "Spans := { π to_timestamp(ts) → ts, host, to_timestamp(started) → started"
            + " (RawSpans) };\n";

    /**
     * Four rows across two 5-minute buckets and two regions:
     *   - 2026-01-01T00:00Z  eu  amount=100
     *   - 2026-01-01T00:01Z  us  amount=200
     *   - 2026-01-01T00:05Z  eu  amount=300
     *   - 2026-01-01T00:06Z  us  amount=400
     */
    private static final String EVENTS =
            "RawEvents := [| ts | region | amount |\n" +
            "               | 2026-01-01T00:00:00Z | eu | 100 |\n" +
            "               | 2026-01-01T00:01:00Z | us | 200 |\n" +
            "               | 2026-01-01T00:05:00Z | eu | 300 |\n" +
            "               | 2026-01-01T00:06:00Z | us | 400 |\n" +
            "];\n" +
            "Events := { π to_timestamp(ts) → ts, region, amount (RawEvents) };\n";

    // ── consolidation functions ───────────────────────────────────────────────

    @Nested
    @DisplayName("Consolidation functions")
    class ConsolidationFunctions {

        @Test
        @DisplayName("AVG averages numeric columns within each bucket")
        void avgAveragesWithinBucket() {
            var rows = collect(METRICS +
                    "query { DOWNSAMPLE ts BY '5m' USING AVG (Metrics) };");
            assertThat(rows).hasSize(2);
            // Earliest bucket has rows at 00:00:30 and 00:02:00 → avg cpu = (10+20)/2 = 15
            assertThat(bd(earliest(rows), "avg_cpu")).isEqualByComparingTo("15");
            assertThat(bd(earliest(rows), "avg_mem")).isEqualByComparingTo("300");
        }

        @Test
        @DisplayName("SUM sums numeric columns within each bucket")
        void sumSumsWithinBucket() {
            var rows = collect(METRICS +
                    "query { DOWNSAMPLE ts BY '5m' USING SUM (Metrics) };");
            assertThat(rows).hasSize(2);
            assertThat(bd(earliest(rows), "sum_cpu")).isEqualByComparingTo("30");  // 10+20
            assertThat(bd(earliest(rows), "sum_mem")).isEqualByComparingTo("600"); // 200+400
        }

        @Test
        @DisplayName("MIN picks the minimum value within each bucket")
        void minPicksMinimum() {
            var rows = collect(METRICS +
                    "query { DOWNSAMPLE ts BY '5m' USING MIN (Metrics) };");
            assertThat(rows).hasSize(2);
            assertThat(bd(earliest(rows), "min_cpu")).isEqualByComparingTo("10");
            assertThat(bd(latest(rows),   "min_cpu")).isEqualByComparingTo("30");
        }

        @Test
        @DisplayName("MAX picks the maximum value within each bucket")
        void maxPicksMaximum() {
            var rows = collect(METRICS +
                    "query { DOWNSAMPLE ts BY '5m' USING MAX (Metrics) };");
            assertThat(rows).hasSize(2);
            assertThat(bd(earliest(rows), "max_cpu")).isEqualByComparingTo("20");
        }

        @Test
        @DisplayName("COUNT counts rows within each bucket")
        void countCountsRows() {
            var rows = collect(METRICS +
                    "query { DOWNSAMPLE ts BY '5m' USING COUNT (Metrics) };");
            assertThat(rows).hasSize(2);
            assertThat(earliest(rows)).hasValue("count", "2");
            assertThat(latest(rows)).hasValue("count", "1");
        }

        @Test
        @DisplayName("COUNT counts every row, including one whose columns are all NULL")
        void countCountsRowsNotValues() {
            var rows = collect(SPANS +
                    "query { DOWNSAMPLE ts BY '1h' USING COUNT (Spans) };");
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()).hasValue("count", "3");
        }
    }

    // ── consolidating a column that is not a number ──────────────────────────

    @Nested
    @DisplayName("MIN/MAX over non-numeric columns")
    class NonNumericColumns {

        @Test
        @DisplayName("MIN/MAX rank strings the way an ascending sort does")
        void extremaOverStrings() {
            var minRows = collect(METRICS +
                    "query { DOWNSAMPLE ts BY '1h' USING MIN (Metrics) };");
            var maxRows = collect(METRICS +
                    "query { DOWNSAMPLE ts BY '1h' USING MAX (Metrics) };");
            assertThat(minRows.getFirst()).hasValue("min_host", "web1");
            assertThat(maxRows.getFirst()).hasValue("max_host", "web3");
        }

        @Test
        @DisplayName("MIN/MAX rank temporal values chronologically")
        void extremaOverTimestamps() {
            var minRows = collect(SPANS +
                    "query { DOWNSAMPLE ts BY '1h' USING MIN (Spans) };");
            var maxRows = collect(SPANS +
                    "query { DOWNSAMPLE ts BY '1h' USING MAX (Spans) };");
            assertThat(minRows.getFirst().get("min_started").asDisplayString())
                    .startsWith("2026-01-01T00:00:10");
            assertThat(maxRows.getFirst().get("max_started").asDisplayString())
                    .startsWith("2026-01-01T00:02:10");
        }

        @Test
        @DisplayName("MIN over a bucket whose values are all NULL yields NULL, not a row count")
        void extremumOverAllNullBucketIsNull() {
            var rows = collect(SPANS +
                    "query { DOWNSAMPLE ts BY '1h' USING MIN PER host (Spans) };");
            Row absent = rows.stream()
                    .filter(r -> r.get("host").isNull())
                    .findFirst()
                    .orElseThrow();
            assertThat(absent.get("min_started").isNull()).isTrue();
        }

        @Test
        @DisplayName("AVG and SUM consolidate the numeric columns only")
        void arithmeticStaysNumeric() {
            var rows = collect(METRICS +
                    "query { DOWNSAMPLE ts BY '1h' USING AVG (Metrics) };");
            assertThat(rows.getFirst().columnNames())
                    .containsExactly("bucket", "avg_cpu", "avg_mem");
        }
    }

    // ── PER grouping ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PER grouping")
    class PerGrouping {

        @Test
        @DisplayName("PER produces separate bucket rows per grouping-key value")
        void perProducesSeparateBucketsPerKey() {
            var rows = collect(EVENTS +
                    "query { DOWNSAMPLE ts BY '5m' USING SUM PER region (Events) };");
            // 2 regions × 2 buckets each = 4 output rows
            assertThat(rows).hasSize(4);
        }

        @Test
        @DisplayName("PER grouping keys appear in output with correct values")
        void perKeyValuesArePreserved() {
            var rows = collect(EVENTS +
                    "query { DOWNSAMPLE ts BY '5m' USING COUNT PER region (Events) };");
            assertThat(rows).allMatch(r -> r.get("region") != null);
            // Each group should have count = 1 (one row per region per bucket)
            assertThat(rows).allMatch(r -> r.get("count").asDisplayString().equals("1"));
        }

        @Test
        @DisplayName("PER SUM gives correct sums per (region, bucket)")
        void perSumGivesCorrectSums() {
            var rows = collect(EVENTS +
                    "query { DOWNSAMPLE ts BY '1h' USING SUM PER region (Events) };");
            // 1-hour buckets: eu = 100+300=400, us = 200+400=600
            assertThat(rows).hasSize(2);
            rows.forEach(r -> {
                String region = r.get("region").asDisplayString();
                BigDecimal sumAmount = bd(r, "sum_amount");
                if (region.equals("eu")) {
                    assertThat(sumAmount).isEqualByComparingTo("400");
                } else {
                    assertThat(sumAmount).isEqualByComparingTo("600");
                }
            });
        }
    }

    // ── FOR n ROWS (maxRows) ─────────────────────────────────────────────────

    @Nested
    @DisplayName("FOR n ROWS (maxRows)")
    class MaxRows {

        @Test
        @DisplayName("FOR 1 ROWS keeps only the most-recent bucket")
        void forOneRowKeepsMostRecent() {
            var rows = collect(EVENTS +
                    "query { DOWNSAMPLE ts BY '5m' USING COUNT FOR 1 ROWS (Events) };");
            assertThat(rows).hasSize(1);
            // Most-recent bucket is the 00:05 bucket, which has 2 rows (eu + us)
            assertThat(rows.getFirst()).hasValue("count", "2");
        }

        @Test
        @DisplayName("FOR n ROWS larger than result keeps all rows")
        void forNRowsLargerThanResultKeepsAll() {
            var rows = collect(EVENTS +
                    "query { DOWNSAMPLE ts BY '5m' USING COUNT FOR 100 ROWS (Events) };");
            assertThat(rows).hasSize(2);
        }
    }

    // ── interval formats ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Interval formats")
    class IntervalFormats {

        @Test
        @DisplayName("Shorthand '1h' buckets all three Metrics rows into a single hour bucket")
        void shorthandHourBucketsCombinesAll() {
            var rows = collect(METRICS +
                    "query { DOWNSAMPLE ts BY '1h' USING COUNT (Metrics) };");
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()).hasValue("count", "3");
        }

        @Test
        @DisplayName("ISO-8601 'PT5M' produces same bucket count as shorthand '5m'")
        void iso8601IntervalEquivalent() {
            var rowsShort = collect(METRICS +
                    "query { DOWNSAMPLE ts BY '5m' USING COUNT (Metrics) };");
            var rowsIso = collect(METRICS +
                    "query { DOWNSAMPLE ts BY 'PT5M' USING COUNT (Metrics) };");
            assertThat(rowsShort).hasSameSizeAs(rowsIso);
        }

        @Test
        @DisplayName("'1d' buckets all rows into a single day")
        void dayIntervalBucketsAll() {
            var rows = collect(METRICS +
                    "query { DOWNSAMPLE ts BY '1d' USING SUM (Metrics) };");
            assertThat(rows).hasSize(1);
            assertThat(bd(rows.getFirst(), "sum_cpu")).isEqualByComparingTo("60"); // 10+20+30
        }
    }

    // ── edge cases ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Empty input produces empty output")
        void emptyInputProducesEmptyOutput() {
            // Filter all rows so DOWNSAMPLE sees an empty stream
            var rows = collect(METRICS +
                    "query { DOWNSAMPLE ts BY '1h' USING AVG (σ ts = TIMESTAMP '2000-01-01T00:00:00Z' (Metrics)) };");
            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("Output bucket column has TimestampValue type")
        void bucketColumnIsTimestamp() {
            var rows = collect(METRICS +
                    "query { DOWNSAMPLE ts BY '5m' USING COUNT (Metrics) };");
            assertThat(rows).isNotEmpty();
            assertThat(rows.getFirst().get("bucket")).isInstanceOf(TimestampValue.class);
        }

        @Test
        @DisplayName("Bucket timestamps are aligned to interval boundaries")
        void bucketTimestampsAlignedToInterval() {
            var rows = collect(METRICS +
                    "query { DOWNSAMPLE ts BY '5m' USING COUNT (Metrics) };");
            // 5-minute intervals → epoch seconds divisible by 300
            rows.forEach(r -> {
                long epochSec = ((TimestampValue) r.get("bucket")).value().getEpochSecond();
                assertThat(epochSec % 300).isZero();
            });
        }
    }
}

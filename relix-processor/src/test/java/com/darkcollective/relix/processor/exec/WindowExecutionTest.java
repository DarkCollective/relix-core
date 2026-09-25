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
import com.darkcollective.relix.processor.internal.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RelNodeExecutor — window operator (ROLLING / WINDOW)")
final class WindowExecutionTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget named -> rel(named.name());
            case ExpressionQueryTarget e -> e.expression();
        };
    }

    /** Runs the first query and returns each row's {@code <col>} display value, in emitted order. */
    private static List<String> column(String src, String col) {
        SemanticModel model = model(src);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        var query = model.rootQueries().getFirst();
        try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
            return stream.map(r -> r.get(col).asDisplayString()).toList();
        }
    }

    private static final String TICKS =
            "Ticks := [| ticker | t | price |\n" +
            "           | A      | 1 | 10    |\n" +
            "           | A      | 2 | 20    |\n" +
            "           | A      | 3 | 30    |\n" +
            "           | A      | 4 | 40    |];\n";

    @Test
    @DisplayName("3-row moving average per partition")
    void movingAverage() {
        String q = TICKS + "query { ROLLING AVG(price) OVER 3 ROWS SORT t ASC PER ticker AS avg3 (Ticks) };";
        // t=1:avg(10)=10, t=2:avg(10,20)=15, t=3:avg(10,20,30)=20, t=4:avg(20,30,40)=30
        assertThat(column(q, "avg3")).containsExactly("10", "15", "20", "30");
    }

    @Test
    @DisplayName("cumulative SUM (OVER ALL ROWS) is a running total")
    void cumulativeSum() {
        String q = TICKS + "query { ROLLING SUM(price) OVER ALL ROWS SORT t ASC PER ticker AS run (Ticks) };";
        assertThat(column(q, "run")).containsExactly("10", "30", "60", "100");
    }

    @Test
    @DisplayName("bounded SUM slides a trailing window")
    void boundedSum() {
        String q = TICKS + "query { ROLLING SUM(price) OVER 2 ROWS SORT t ASC PER ticker AS s2 (Ticks) };";
        // t=1:10, t=2:10+20=30, t=3:20+30=50, t=4:30+40=70
        assertThat(column(q, "s2")).containsExactly("10", "30", "50", "70");
    }

    @Test
    @DisplayName("COUNT over a sliding frame counts the rows in the frame")
    void countWindow() {
        String q = TICKS + "query { ROLLING COUNT(price) OVER 3 ROWS SORT t ASC PER ticker AS c (Ticks) };";
        assertThat(column(q, "c")).containsExactly("1", "2", "3", "3");
    }

    @Test
    @DisplayName("MAX over a sliding frame")
    void maxWindow() {
        String q = TICKS + "query { ROLLING MAX(price) OVER 2 ROWS SORT t ASC PER ticker AS m (Ticks) };";
        assertThat(column(q, "m")).containsExactly("10", "20", "30", "40");
    }

    @Test
    @DisplayName("preserves the input columns alongside the appended column")
    void preservesInputColumns() {
        String q = TICKS + "query { ROLLING SUM(price) OVER 2 ROWS SORT t ASC PER ticker AS s2 (Ticks) };";
        assertThat(column(q, "price")).containsExactly("10", "20", "30", "40");
        assertThat(column(q, "ticker")).containsExactly("A", "A", "A", "A");
    }

    @Test
    @DisplayName("frame wider than the partition uses all available rows")
    void frameWiderThanPartition() {
        String q = TICKS + "query { ROLLING SUM(price) OVER 10 ROWS SORT t ASC PER ticker AS s (Ticks) };";
        // Every frame covers the whole partition prefix.
        assertThat(column(q, "s")).containsExactly("10", "30", "60", "100");
    }

    @Test
    @DisplayName("multiple partitions are computed independently")
    void multiplePartitions() {
        String src =
                "T := [| g | t | v |\n" +
                "       | A | 1 | 1 |\n" +
                "       | A | 2 | 2 |\n" +
                "       | B | 1 | 5 |\n" +
                "       | B | 2 | 6 |];\n";
        String q = src + "query { ROLLING SUM(v) OVER ALL ROWS SORT t ASC PER g AS run (T) };";
        // Partition A: 1,3 ; Partition B: 5,11 (groups in first-seen order: A then B)
        assertThat(column(q, "run")).containsExactly("1", "3", "5", "11");
    }

    @Test
    @DisplayName("single-row partition yields the value itself")
    void singleRowPartition() {
        String src =
                "T := [| g | t | v |\n" +
                "       | A | 1 | 7 |\n" +
                "       | B | 1 | 9 |];\n";
        String q = src + "query { ROLLING SUM(v) OVER 3 ROWS SORT t ASC PER g AS s (T) };";
        assertThat(column(q, "s")).containsExactly("7", "9");
    }

    @Test
    @DisplayName("global partition (no PER) is a single running window over the whole relation")
    void globalPartition() {
        String q = TICKS + "query { ROLLING SUM(price) OVER ALL ROWS SORT t ASC AS run (Ticks) };";
        assertThat(column(q, "run")).containsExactly("10", "30", "60", "100");
    }

    @Test
    @DisplayName("SUM skips NULL values within the frame")
    void nullsInFrame() {
        // A left outer join introduces a genuine NULL in the (NUMBER-typed) v column
        // for the unmatched t=2 row — inline literals cannot hold a NULL in a numeric
        // column, so the NULL is produced by the join.
        String src =
                "Days := [| g | t |\n" +
                "          | A | 1 |\n" +
                "          | A | 2 |\n" +
                "          | A | 3 |];\n" +
                "Vals := [| k | v  |\n" +
                "          | 1 | 10 |\n" +
                "          | 3 | 30 |];\n";
        String q = src + "query { ROLLING SUM(v) OVER ALL ROWS SORT t ASC PER g AS run "
                + "(Days ⟕ Days.t = Vals.k Vals) };";
        // t=1:10, t=2:10 (NULL skipped), t=3:40
        assertThat(column(q, "run")).containsExactly("10", "10", "40");
    }

    // ─── ranking functions (slice 3, issue #233) ───────────────────────────────

    /** Single partition, scores 30, 30, 20, 10 (a tie at the top) sorted DESC. */
    private static final String RANKS =
            "Ranks := [| g | s  |\n" +
            "           | A | 30 |\n" +
            "           | A | 30 |\n" +
            "           | A | 20 |\n" +
            "           | A | 10 |];\n";

    @Test
    @DisplayName("ROW_NUMBER assigns a unique 1-based position (ignores ties)")
    void rowNumber() {
        String q = RANKS + "query { WINDOW ROW_NUMBER() SORT s DESC PER g AS rn (Ranks) };";
        assertThat(column(q, "rn")).containsExactly("1", "2", "3", "4");
    }

    @Test
    @DisplayName("RANK shares a rank on ties and skips the next (1, 1, 3, 4)")
    void rankWithGaps() {
        String q = RANKS + "query { WINDOW RANK() SORT s DESC PER g AS rnk (Ranks) };";
        assertThat(column(q, "rnk")).containsExactly("1", "1", "3", "4");
    }

    @Test
    @DisplayName("DENSE_RANK shares a rank on ties without skipping (1, 1, 2, 3)")
    void denseRank() {
        String q = RANKS + "query { WINDOW DENSE_RANK() SORT s DESC PER g AS dr (Ranks) };";
        assertThat(column(q, "dr")).containsExactly("1", "1", "2", "3");
    }

    @Test
    @DisplayName("PERCENT_RANK over distinct values gives evenly spaced fractions")
    void percentRankDistinct() {
        String src =
                "T := [| g | s |\n" +
                "       | A | 1 |\n" +
                "       | A | 2 |\n" +
                "       | A | 3 |\n" +
                "       | A | 4 |\n" +
                "       | A | 5 |];\n";
        String q = src + "query { WINDOW PERCENT_RANK() SORT s ASC PER g AS pr (T) };";
        // ranks 1..5, denom 4: 0/4, 1/4, 2/4, 3/4, 4/4
        assertThat(column(q, "pr")).containsExactly("0", "0.25", "0.5", "0.75", "1");
    }

    @Test
    @DisplayName("PERCENT_RANK honours ties via the gapped rank")
    void percentRankWithTies() {
        String src =
                "T := [| g | s  |\n" +
                "       | A | 10 |\n" +
                "       | A | 10 |\n" +
                "       | A | 20 |];\n";
        String q = src + "query { WINDOW PERCENT_RANK() SORT s ASC PER g AS pr (T) };";
        // ranks 1,1,3 over denom 2: 0, 0, 1
        assertThat(column(q, "pr")).containsExactly("0", "0", "1");
    }

    @Test
    @DisplayName("PERCENT_RANK is 0.0 for a single-row partition (denominator guard)")
    void percentRankSingleRow() {
        String src =
                "T := [| g | s |\n" +
                "       | A | 7 |\n" +
                "       | B | 9 |];\n";
        String q = src + "query { WINDOW PERCENT_RANK() SORT s ASC PER g AS pr (T) };";
        assertThat(column(q, "pr")).containsExactly("0", "0");
    }

    @Test
    @DisplayName("NTILE splits an evenly divisible partition into equal buckets")
    void ntileEven() {
        String src =
                "T := [| g | s |\n" +
                "       | A | 1 |\n" +
                "       | A | 2 |\n" +
                "       | A | 3 |\n" +
                "       | A | 4 |];\n";
        String q = src + "query { WINDOW NTILE(2) SORT s ASC PER g AS b (T) };";
        assertThat(column(q, "b")).containsExactly("1", "1", "2", "2");
    }

    @Test
    @DisplayName("NTILE distributes remainder rows to the lower buckets")
    void ntileUneven() {
        String src =
                "T := [| g | s |\n" +
                "       | A | 1 |\n" +
                "       | A | 2 |\n" +
                "       | A | 3 |\n" +
                "       | A | 4 |\n" +
                "       | A | 5 |];\n";
        String q = src + "query { WINDOW NTILE(2) SORT s ASC PER g AS b (T) };";
        // 5 rows, 2 buckets: bucket 1 gets 3 rows, bucket 2 gets 2
        assertThat(column(q, "b")).containsExactly("1", "1", "1", "2", "2");
    }

    @Test
    @DisplayName("NTILE with more buckets than rows gives each row its own bucket")
    void ntileMoreBucketsThanRows() {
        String src =
                "T := [| g | s |\n" +
                "       | A | 1 |\n" +
                "       | A | 2 |];\n";
        String q = src + "query { WINDOW NTILE(4) SORT s ASC PER g AS b (T) };";
        assertThat(column(q, "b")).containsExactly("1", "2");
    }

    @Test
    @DisplayName("ranking is computed independently per partition")
    void rankingMultiplePartitions() {
        String src =
                "T := [| g | s  |\n" +
                "       | A | 30 |\n" +
                "       | A | 10 |\n" +
                "       | B | 50 |\n" +
                "       | B | 40 |\n" +
                "       | B | 40 |];\n";
        String q = src + "query { WINDOW RANK() SORT s DESC PER g AS rnk (T) };";
        // A: 1,2 ; B: 1,2,2
        assertThat(column(q, "rnk")).containsExactly("1", "2", "1", "2", "2");
    }

    @Test
    @DisplayName("global partition (no PER) ranks the whole relation")
    void rankingGlobalPartition() {
        String src =
                "T := [| s |\n" +
                "       | 3 |\n" +
                "       | 1 |\n" +
                "       | 2 |];\n";
        String q = src + "query { WINDOW ROW_NUMBER() SORT s ASC AS rn (T) };";
        assertThat(column(q, "rn")).containsExactly("1", "2", "3");
    }

    @Test
    @DisplayName("rank-then-filter pipeline keeps only the top rows")
    void rankThenFilter() {
        String q = RANKS
                + "Ranked := { WINDOW RANK() SORT s DESC PER g AS rnk (Ranks) };\n"
                + "query { σ rnk <= 2 (Ranked) };";
        // ranks 1,1,3,4 → keep the two rank-1 rows
        assertThat(column(q, "rnk")).containsExactly("1", "1");
    }

    // ─── offset functions (slice 4, issue #234) ─────────────────────────────────

    @Test
    @DisplayName("LAG reads the previous row, NULL at the partition's leading edge")
    void lagDefaultsToNull() {
        String q = TICKS + "query { WINDOW LAG(price, 1) SORT t ASC PER ticker AS prev (Ticks) };";
        assertThat(column(q, "prev")).containsExactly("NULL", "10", "20", "30");
    }

    @Test
    @DisplayName("LAG with an offset of 1 omitted defaults to the previous row")
    void lagDefaultOffsetIsOne() {
        String q = TICKS + "query { WINDOW LAG(price) SORT t ASC PER ticker AS prev (Ticks) };";
        assertThat(column(q, "prev")).containsExactly("NULL", "10", "20", "30");
    }

    @Test
    @DisplayName("LAG with an explicit default fills the out-of-bounds rows")
    void lagWithExplicitDefault() {
        String q = TICKS + "query { WINDOW LAG(price, 1, 0) SORT t ASC PER ticker AS prev (Ticks) };";
        assertThat(column(q, "prev")).containsExactly("0", "10", "20", "30");
    }

    @Test
    @DisplayName("LAG with an offset of 2 reaches two rows back")
    void lagOffsetTwo() {
        String q = TICKS + "query { WINDOW LAG(price, 2) SORT t ASC PER ticker AS prev (Ticks) };";
        assertThat(column(q, "prev")).containsExactly("NULL", "NULL", "10", "20");
    }

    @Test
    @DisplayName("LEAD reads the following row, NULL past the partition's end")
    void leadDefaultsToNull() {
        String q = TICKS + "query { WINDOW LEAD(price, 1) SORT t ASC PER ticker AS next (Ticks) };";
        assertThat(column(q, "next")).containsExactly("20", "30", "40", "NULL");
    }

    @Test
    @DisplayName("LEAD with an explicit default fills the out-of-bounds rows")
    void leadWithExplicitDefault() {
        String q = TICKS + "query { WINDOW LEAD(price, 1, 0) SORT t ASC PER ticker AS next (Ticks) };";
        assertThat(column(q, "next")).containsExactly("20", "30", "40", "0");
    }

    @Test
    @DisplayName("FIRST_VALUE repeats the partition's first row for every row")
    void firstValue() {
        String q = TICKS + "query { WINDOW FIRST_VALUE(price) SORT t ASC PER ticker AS open (Ticks) };";
        assertThat(column(q, "open")).containsExactly("10", "10", "10", "10");
    }

    @Test
    @DisplayName("LAST_VALUE repeats the partition's last row for every row")
    void lastValue() {
        String q = TICKS + "query { WINDOW LAST_VALUE(price) SORT t ASC PER ticker AS close (Ticks) };";
        assertThat(column(q, "close")).containsExactly("40", "40", "40", "40");
    }

    @Test
    @DisplayName("offset functions are computed independently per partition")
    void offsetMultiplePartitions() {
        String src =
                "T := [| g | t | v |\n" +
                "       | A | 1 | 1 |\n" +
                "       | A | 2 | 2 |\n" +
                "       | B | 1 | 5 |\n" +
                "       | B | 2 | 6 |];\n";
        String q = src + "query { WINDOW LAG(v, 1) SORT t ASC PER g AS prev (T) };";
        // A: NULL,1 ; B: NULL,5
        assertThat(column(q, "prev")).containsExactly("NULL", "1", "NULL", "5");
    }

    @Test
    @DisplayName("single-row partition: LAG/LEAD are NULL, FIRST/LAST are the row itself")
    void offsetSingleRowPartition() {
        String src =
                "T := [| g | t | v |\n" +
                "       | A | 1 | 7 |\n" +
                "       | B | 1 | 9 |];\n";
        String lagQ = src + "query { WINDOW LAG(v, 1) SORT t ASC PER g AS prev (T) };";
        assertThat(column(lagQ, "prev")).containsExactly("NULL", "NULL");
        String firstQ = src + "query { WINDOW FIRST_VALUE(v) SORT t ASC PER g AS f (T) };";
        assertThat(column(firstQ, "f")).containsExactly("7", "9");
    }

    @Test
    @DisplayName("offset preserves the input columns alongside the appended column")
    void offsetPreservesInputColumns() {
        String q = TICKS + "query { WINDOW LAG(price, 1) SORT t ASC PER ticker AS prev (Ticks) };";
        assertThat(column(q, "price")).containsExactly("10", "20", "30", "40");
        assertThat(column(q, "ticker")).containsExactly("A", "A", "A", "A");
    }

    @Test
    @DisplayName("month-over-month delta pipeline (LAG then arithmetic projection)")
    void monthOverMonthDelta() {
        String src =
                "Monthly := [| region | month | revenue |\n" +
                "             | E      | 1     | 100     |\n" +
                "             | E      | 2     | 150     |\n" +
                "             | E      | 3     | 120     |];\n";
        String q = src
                + "WithPrev := { WINDOW LAG(revenue, 1) SORT month ASC PER region AS prev (Monthly) };\n"
                + "query { π region, month, revenue - prev → delta (WithPrev) };";
        // month 1: 100 - NULL = NULL; month 2: 150-100=50; month 3: 120-150=-30
        assertThat(column(q, "delta")).containsExactly("NULL", "50", "-30");
    }
}

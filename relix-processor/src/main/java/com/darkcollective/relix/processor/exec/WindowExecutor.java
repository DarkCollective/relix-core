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

import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.WindowFrame;
import com.darkcollective.relix.ast.WindowFunction;
import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.processor.internal.ArrayRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.processor.eval.OperandEvaluator;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.Schema;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static com.darkcollective.relix.processor.exec.ExecSupport.rowComparator;

/**
 * Executes the window operator (ROLLING / WINDOW, ADR-0015): a non-collapsing
 * per-partition computation that appends one column to every input row.
 *
 * <p>The input is partitioned by the partition keys ({@link IndexedRelation}),
 * each partition is sorted by the sort specs, and for every row the window
 * function is evaluated over the rows in its {@link WindowFrame}:
 *
 * <ul>
 *   <li>{@link WindowFrame.BoundedFrame} — the current row plus the preceding
 *       {@code n − 1} rows (a trailing sliding window).</li>
 *   <li>{@link WindowFrame.CumulativeFrame} — the partition prefix up to and
 *       including the current row (running aggregate).</li>
 *   <li>{@link WindowFrame.PartitionFrame} — the whole partition (used by ranking
 *       and offset functions, slices 3–4).</li>
 * </ul>
 *
 * <p>Slices 2–4 implement {@link WindowFunction.AggregateWindow} (sliding /
 * cumulative aggregates), {@link WindowFunction.RankingWindow} (ROW_NUMBER, RANK,
 * DENSE_RANK, PERCENT_RANK, NTILE), and {@link WindowFunction.OffsetWindow}
 * (LAG, LEAD, FIRST_VALUE, LAST_VALUE).
 */
final class WindowExecutor {

    /** Precision for the {@code PERCENT_RANK} division of integer ranks. */
    private static final MathContext PERCENT_RANK_CONTEXT = MathContext.DECIMAL64;

    private final ChildDispatch dispatch;

    WindowExecutor(ChildDispatch dispatch) {
        this.dispatch = dispatch;
    }

    Stream<Row> executeWindow(PhysicalNode.Window node, EvalCtx ctx) {
        List<String> partitionKeys = node.partitionKeys();
        Comparator<Row> comparator = rowComparator(node.sortSpecs(), ctx.operandEval());
        Schema inputSchema = node.input().schema();
        Schema outputSchema = node.schema();
        OperandEvaluator eval = ctx.operandEval();

        IndexedRelation indexed;
        try (Stream<Row> input = dispatch.buffering(node.input(), ctx, node)) {
            indexed = IndexedRelation.build(inputSchema, input,
                    row -> partitionKeys.stream().map(row::get).toList());
        }

        List<Row> output = new ArrayList<>();
        for (var entry : indexed.groups().entrySet()) {
            List<Row> partition = new ArrayList<>(entry.getValue());
            partition.sort(comparator);
            List<Value> values = computePartition(node, partition, comparator, eval);
            for (int i = 0; i < partition.size(); i++) {
                output.add(ExecSupport.appendColumn(outputSchema, inputSchema, partition.get(i),
                        node.outputColumn(), values.get(i)));
            }
        }
        return BagRelation.of(outputSchema, output).stream();
    }

    /**
     * Computes the appended value for every row of a sorted, non-empty partition.
     * Aggregate windows evaluate per-row over their frame; ranking windows compute
     * positional values over the full partition (ties detected via {@code comparator}).
     */
    private static List<Value> computePartition(PhysicalNode.Window node, List<Row> partition,
                                                Comparator<Row> comparator, OperandEvaluator eval) {
        return switch (node.function()) {
            case WindowFunction.AggregateWindow a -> {
                AggregateFunction agg = AggregateFunction.of(a.operator(), a.argument());
                List<Value> values = new ArrayList<>(partition.size());
                for (int i = 0; i < partition.size(); i++) {
                    values.add(AggregateExecutor.computeAggregate(
                            agg, frameRows(partition, i, node.frame()), eval));
                }
                yield values;
            }
            case WindowFunction.RankingWindow r -> rankPartition(r, partition, comparator, eval);
            case WindowFunction.OffsetWindow o -> offsetPartition(o, partition, eval);
        };
    }

    /**
     * Computes the offset value for every row of a sorted partition (ADR-0015 §D7,
     * slice 4).  {@code LAG(expr, n, default)} reads {@code expr} from the row
     * {@code n} positions before the current one ({@code default} — {@code NULL} when
     * absent — at the partition's leading edge); {@code LEAD} reads {@code n}
     * positions after; {@code FIRST_VALUE}/{@code LAST_VALUE} read the first/last row
     * of the partition for every row.  The offset {@code n} defaults to 1.
     */
    private static List<Value> offsetPartition(WindowFunction.OffsetWindow offset,
                                               List<Row> partition, OperandEvaluator eval) {
        int size = partition.size();
        int n = offsetRows(offset.offset(), partition.get(0), eval);
        List<Value> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int target = switch (offset.function()) {
                case LAG -> i - n;
                case LEAD -> i + n;
                case FIRST_VALUE -> 0;
                case LAST_VALUE -> size - 1;
            };
            if (target >= 0 && target < size) {
                values.add(eval.evaluate(offset.expression(), partition.get(target)));
            } else {
                values.add(offset.defaultValue()
                        .map(d -> eval.evaluate(d, partition.get(0)))
                        .orElse(NullValue.INSTANCE));
            }
        }
        return values;
    }

    /** Resolves the {@code LAG}/{@code LEAD} row offset from its constant operand (defaults to 1). */
    private static int offsetRows(java.util.Optional<Operand> offset, Row sample,
                                  OperandEvaluator eval) {
        if (offset.isEmpty()) {
            return 1;
        }
        Value value = eval.evaluate(offset.get(), sample);
        if (!(value instanceof NumberValue num)) {
            throw new EvaluationException("Window offset must be a number");
        }
        int n = num.value().intValue();
        if (n < 1) {
            throw new EvaluationException("Window offset must be at least 1, got " + n);
        }
        return n;
    }

    /** The rows in {@code current}'s frame within its sorted partition. */
    private static List<Row> frameRows(List<Row> partition, int current, WindowFrame frame) {
        int start = switch (frame) {
            case WindowFrame.BoundedFrame b -> Math.max(0, current - b.n() + 1);
            case WindowFrame.CumulativeFrame ignored -> 0;
            case WindowFrame.PartitionFrame ignored -> 0;
        };
        int endExclusive = frame instanceof WindowFrame.PartitionFrame
                ? partition.size()
                : current + 1;
        return partition.subList(start, endExclusive);
    }

    /**
     * Computes the ranking value for every row of a sorted partition (ADR-0015 §D7):
     * {@code ROW_NUMBER} = 1-based position; {@code RANK} = ties share a rank, next
     * rank skips; {@code DENSE_RANK} = ties share a rank, no skip;
     * {@code PERCENT_RANK} = {@code (rank − 1) / (rows − 1)} (0.0 for a single row);
     * {@code NTILE(n)} = bucket 1..n, remainder rows to the lower buckets.
     */
    private static List<Value> rankPartition(WindowFunction.RankingWindow ranking,
                                             List<Row> partition, Comparator<Row> comparator,
                                             OperandEvaluator eval) {
        int size = partition.size();
        List<Value> values = new ArrayList<>(size);
        switch (ranking.function()) {
            case ROW_NUMBER -> {
                for (int i = 0; i < size; i++) {
                    values.add(intValue(i + 1));
                }
            }
            case RANK -> {
                int[] ranks = ranksWithGaps(partition, comparator);
                for (int rank : ranks) {
                    values.add(intValue(rank));
                }
            }
            case DENSE_RANK -> {
                int[] ranks = denseRanks(partition, comparator);
                for (int rank : ranks) {
                    values.add(intValue(rank));
                }
            }
            case PERCENT_RANK -> {
                int[] ranks = ranksWithGaps(partition, comparator);
                BigDecimal denominator = BigDecimal.valueOf(Math.max(size - 1, 1));
                for (int rank : ranks) {
                    BigDecimal pr = BigDecimal.valueOf(rank - 1L)
                            .divide(denominator, PERCENT_RANK_CONTEXT);
                    values.add(new NumberValue(pr));
                }
            }
            case NTILE -> {
                int buckets = ntileBuckets(ranking.ntileCount(), partition.get(0), eval);
                for (int i = 0; i < size; i++) {
                    values.add(intValue(ntileBucket(i, size, buckets)));
                }
            }
        }
        return values;
    }

    /** Ranks with gaps after ties (e.g. {@code 1, 1, 3}); the partition is pre-sorted. */
    private static int[] ranksWithGaps(List<Row> partition, Comparator<Row> comparator) {
        int[] ranks = new int[partition.size()];
        for (int i = 0; i < partition.size(); i++) {
            ranks[i] = (i > 0 && comparator.compare(partition.get(i), partition.get(i - 1)) == 0)
                    ? ranks[i - 1]
                    : i + 1;
        }
        return ranks;
    }

    /** Ranks without gaps after ties (e.g. {@code 1, 1, 2}); the partition is pre-sorted. */
    private static int[] denseRanks(List<Row> partition, Comparator<Row> comparator) {
        int[] ranks = new int[partition.size()];
        int dense = 0;
        for (int i = 0; i < partition.size(); i++) {
            if (i == 0 || comparator.compare(partition.get(i), partition.get(i - 1)) != 0) {
                dense++;
            }
            ranks[i] = dense;
        }
        return ranks;
    }

    /** Resolves the {@code NTILE} bucket count from its constant operand (validated ≥ 1). */
    private static int ntileBuckets(java.util.Optional<Operand> ntileCount, Row sample,
                                    OperandEvaluator eval) {
        Operand operand = ntileCount.orElseThrow(() -> new EvaluationException(
                "NTILE requires a bucket-count argument"));
        Value value = eval.evaluate(operand, sample);
        if (!(value instanceof NumberValue num)) {
            throw new EvaluationException("NTILE bucket count must be a number");
        }
        int buckets = num.value().intValue();
        if (buckets < 1) {
            throw new EvaluationException("NTILE bucket count must be at least 1, got " + buckets);
        }
        return buckets;
    }

    /**
     * The 1-based {@code NTILE} bucket for a row at {@code index} in a partition of
     * {@code size} rows divided into {@code buckets} groups.  The first
     * {@code size % buckets} buckets each take one extra row (remainder to the lower
     * buckets, matching SQL {@code NTILE}).
     */
    private static int ntileBucket(int index, int size, int buckets) {
        int base = size / buckets;
        int remainder = size % buckets;
        int largeBucketRows = (base + 1) * remainder;
        if (index < largeBucketRows) {
            return index / (base + 1) + 1;
        }
        return remainder + (index - largeBucketRows) / base + 1;
    }

    /** A {@link NumberValue} wrapping a non-negative integer. */
    private static NumberValue intValue(int n) {
        return new NumberValue(BigDecimal.valueOf(n));
    }

}

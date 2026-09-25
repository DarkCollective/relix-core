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

import com.darkcollective.relix.events.EventMetrics;
import com.darkcollective.relix.events.QueryEvent;
import com.darkcollective.relix.events.QueryEventListener;
import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.processor.internal.ExecutionContext;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.EvaluationException;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * The one place a blocking operator's input is counted as it is buffered — the row cap
 * that stops it, and the {@code MATERIALIZE} event that reports what it held.
 *
 * <p>Every operator on {@link PhysicalExecutor}'s dispatch that cannot emit a row before
 * it has read its whole input — {@code γ}, {@code τ}, the deduplicating set operations,
 * {@code ⊎}, {@code ÷}, {@code ⟗}, a hash join's build side — accumulates that input in
 * the JVM heap. Nothing in the plan says how large it is: {@code BoundednessChecker}
 * refuses a blocking operator over an <em>unbounded</em> input, which is a different
 * question, so a bounded fifty-million-row table plans happily and dies with an
 * {@code OutOfMemoryError} attributable to nothing.
 *
 * <h2>What the cap is, and what it is not</h2>
 *
 * <p>{@link ExecutionContext#maxMaterializedRows()} bounds <b>one operator's</b> buffer,
 * not the sum of every buffer in a run — the shape
 * {@link ExecutionContext#maxFixpointRounds()} already has, which likewise bounds one
 * fixpoint rather than a run's total rounds. That is the reading a running total cannot
 * give: a {@code FIX} that buffers a thousand rows per round for a hundred rounds holds
 * a thousand at a time, and a budget that summed them would refuse a query whose peak
 * memory never moved. Charging and releasing instead of summing would answer the peak
 * question, but the release point is genuinely unclear — a materialised relation stays
 * live for as long as the stream reading it does — and a guess there fails in the
 * direction that lets the heap go.
 *
 * <p><b>One operator's buffer is not always one read</b> (#874). {@code FIX} has two
 * buffers, and until that issue only the smaller was charged: each round's step output goes
 * through {@link #meter} like any blocking operator's input, while the <em>accumulator</em>
 * — every row derived so far, which is what the operator actually holds — went through
 * nothing. The two come apart in exactly the case the cap exists for. A step that computes
 * a value rather than carrying one through derives one new row per round forever, so every
 * round's read is a single row, the per-round charge never fires however low the cap, and
 * the accumulator takes the heap. It is charged by {@link #charge} now. Note this is not
 * the running total rejected above: the accumulator is one buffer that is live all at once,
 * so charging it answers the peak question rather than approximating it.
 *
 * <p>So it does not bound a run's total memory, and a plan with many blocking operators
 * can exceed the cap in aggregate while no single operator does. What it does is turn
 * the failure the issue is about — one operator swallowing a table — into a named error
 * before the heap is gone.
 *
 * <p>The count is <b>rows read into the buffer</b>. For an operator whose buffer is a
 * list ({@code τ}, a join's build side) that is exactly what it holds; for one that
 * dedupes as it fills ({@code ∪}, {@code ∩}) it is an upper bound, so the cap fires no
 * later than it should and never later than it must.
 *
 * <p>Unlimited by default: a cap that fires turns a slow answer into no answer, which
 * should be something a caller asked for.
 *
 * <h2>The event</h2>
 *
 * <p>A metered stream emits one {@code EXECUTE}/{@code MATERIALIZE} event carrying
 * {@link EventMetrics#rows} when its input is exhausted — the same shape, and for the
 * same reason, as {@code LeafExecutor}'s {@code SCAN}: the row count alone answers
 * "which operator ate the heap", which prose in a trace cannot be computed with. The
 * operator names itself as the event's target, so the per-query label a
 * {@code QueryExecutor} applies leaves it alone.
 */
final class MaterializationBudget {

    private MaterializationBudget() {
    }

    /**
     * Drains {@code rows} into a list, charging it to {@code owner}'s buffer and closing
     * the stream — the shape {@link ChildDispatch#materialize} takes.
     *
     * @throws EvaluationException if {@code owner} would buffer more rows than the cap
     */
    static List<Row> drain(PhysicalNode owner, Stream<Row> rows, EvalCtx ctx) {
        try (Stream<Row> metered = meter(owner, rows, ctx)) {
            return metered.toList();
        }
    }

    /**
     * Wraps {@code rows} so that every row passing through is charged to {@code owner}'s
     * buffer, for a blocking operator whose buffer is not a list — an
     * {@link IndexedRelation} built by group key, a {@code forEach} into a set.
     *
     * <p>The returned stream is lazy, so the cap fires on the row that crosses it rather
     * than after the input has been read, which is the whole point of checking at all.
     * Closing it closes {@code rows}: it is a stream derived from the child in the sense
     * {@link ChildDispatch}'s lifecycle rule means, so ownership passes on up.
     *
     * @throws EvaluationException if {@code owner} would buffer more rows than the cap
     */
    static Stream<Row> meter(PhysicalNode owner, Stream<Row> rows, EvalCtx ctx) {
        return metered(owner, rows, ctx, null);
    }

    /**
     * Wraps {@code rows} for an operator whose buffer is bounded by its own shape rather
     * than by the size of its input — {@code TOP} holds {@code offset + count} rows for
     * every group it sees, however many rows it reads to find them. The cap is charged
     * against, and the event reports, what {@code held} says the operator is holding.
     *
     * <p>That is not a relaxation of the cap but a correction to what it counts. Metering
     * the input refuses a {@code TOP 1} over a large table that would have held one row,
     * and passes a {@code TOP 1} over a small one that holds a row per distinct group —
     * both times answering about the wrong buffer.
     *
     * <p>The count is polled <em>after</em> the row has been handed on, because a stream
     * cannot see what its consumer did with a row until the consumer has done it. The
     * pipeline is sequential and the consumer runs inside {@code tryAdvance}, so the poll
     * is exact rather than one row behind.
     *
     * @throws EvaluationException if {@code held} reports more rows than the cap
     */
    static Stream<Row> holding(PhysicalNode owner, Stream<Row> rows, EvalCtx ctx,
                               LongSupplier held) {
        return metered(owner, rows, ctx, Objects.requireNonNull(held, "held"));
    }

    /**
     * The one wrapper behind {@link #meter} and {@link #holding}.
     *
     * @param held what the operator is holding, or {@code null} when its buffer is the
     *             input itself and the rows read are therefore the rows held
     */
    private static Stream<Row> metered(PhysicalNode owner, Stream<Row> rows, EvalCtx ctx,
                                       LongSupplier held) {
        int cap = ctx.maxMaterializedRows();
        boolean listening = ctx.listener() != QueryEventListener.NONE;
        if (cap == ExecutionContext.UNLIMITED_MATERIALIZED_ROWS && !listening) {
            // Nothing to enforce and nobody to tell — but a drain still has to be
            // cancellable. A blocking operator reads its whole input inside one
            // tryAdvance, so the root's own check is not reached again until it is done,
            // which for the query worth cancelling is far too late.
            return Cancellation.interruptible(rows);
        }
        Spliterator<Row> source = rows.spliterator();
        long[] buffered = {0};
        long[] nanos = {0};
        boolean[] exhausted = {false};
        Spliterator<Row> counting = new Spliterators.AbstractSpliterator<>(
                source.estimateSize(), source.characteristics()) {
            @Override
            public boolean tryAdvance(Consumer<? super Row> action) {
                // Per row, for the reason above: this is the pull a blocking operator is
                // inside for the whole of its drain.
                Cancellation.checkNotInterrupted();
                // Only the pull is timed, so the elapsed time is what filling the buffer
                // cost and not what the operator then did with it — the same structure,
                // and for the same reason, as LeafExecutor's scan.
                Row[] pulled = {null};
                long start = System.nanoTime();
                boolean advanced = source.tryAdvance(row -> pulled[0] = row);
                nanos[0] += System.nanoTime() - start;
                if (!advanced) {
                    exhausted[0] = true;
                    return false;
                }
                if (held == null) {
                    if (++buffered[0] > cap) {
                        throw refuse(owner, cap);
                    }
                    action.accept(pulled[0]);
                    return true;
                }
                action.accept(pulled[0]);
                buffered[0] = held.getAsLong();
                if (buffered[0] > cap) {
                    throw refuseHeld(owner, cap);
                }
                return true;
            }
        };
        return StreamSupport.stream(counting, false)
                .onClose(rows::close)
                .onClose(() -> report(owner, buffered[0], nanos[0], exhausted[0], ctx));
    }

    /**
     * Refuses when {@code owner} is holding more than the cap in a buffer <em>it</em> fills
     * across many child reads, rather than one filled by draining a child stream once.
     *
     * <p>{@link #meter} charges one pass over one input, which is what every blocking
     * operator but {@code FIX} does. A fixpoint's largest buffer is not any single round's
     * input — it is the accumulated result, which lives across every round and is the thing
     * that actually grows without bound. Charging only the per-round read (as this class did
     * until #874) meters the smallest buffer in the operator and misses the largest: a step
     * that computes new values derives one row per round forever, so each round's read is
     * one row, the per-round charge never fires, and the accumulator takes the heap.
     *
     * <p>The count is rows <em>held</em>, passed in by the caller, so the check is a
     * comparison rather than a wrapper — there is no stream to wrap.
     *
     * <p>No {@code MATERIALIZE} event: the caller already emits one per round through
     * {@link #meter}, under this same owner, and a second event of the same name for a
     * different quantity would make the feed ambiguous about which buffer it describes.
     *
     * @throws EvaluationException if {@code held} exceeds {@code ctx}'s cap
     */
    static void charge(PhysicalNode owner, long held, EvalCtx ctx) {
        int cap = ctx.maxMaterializedRows();
        if (cap != ExecutionContext.UNLIMITED_MATERIALIZED_ROWS && held > cap) {
            throw refuseAccumulated(owner, cap);
        }
    }

    /** One event per completed buffer; nothing for a buffer abandoned part-way. */
    private static void report(PhysicalNode owner, long buffered, long nanos,
                               boolean exhausted, EvalCtx ctx) {
        if (!exhausted || ctx.listener() == QueryEventListener.NONE) {
            return;
        }
        String operator = name(owner);
        ctx.listener().onEvent(QueryEvent.of(QueryEvent.Stage.EXECUTE, "MATERIALIZE",
                        "buffered " + buffered + " row" + (buffered == 1 ? "" : "s")
                                + " for " + operator, operator)
                .withMetrics(EventMetrics.of(buffered, Duration.ofNanos(nanos))));
    }

    private static EvaluationException refuse(PhysicalNode owner, int cap) {
        return new EvaluationException(
                name(owner) + " buffered more than " + cap + " row"
                + (cap == 1 ? "" : "s") + "; a blocking operator holds its whole input "
                + "in memory. Reduce what reaches it (a σ or λ below it, or a pushdown), "
                + "or raise maxMaterializedRows");
    }

    /**
     * A bounded buffer's refusal. Deliberately not {@link #refuse}'s wording either: that
     * one tells the reader to reduce what reaches the operator, and for a buffer that
     * keeps a fixed number of rows per group the rows reaching it are not the lever —
     * the number of groups is.
     */
    private static EvaluationException refuseHeld(PhysicalNode owner, int cap) {
        return new EvaluationException(
                name(owner) + " held more than " + cap + " row" + (cap == 1 ? "" : "s")
                + "; it keeps a bounded number of rows per group, so its buffer grows "
                + "with the number of groups rather than with the rows reaching it. "
                + "Reduce the groups, or raise maxMaterializedRows");
    }

    /**
     * The accumulator's refusal. Deliberately not {@link #refuse}'s wording: that one tells
     * the reader to reduce what <em>reaches</em> the operator, which is the right advice for
     * a buffer filled from a child and the wrong advice here — a fixpoint's accumulator is
     * filled by its own derivation, so the lever is the recursion rather than its input.
     */
    private static EvaluationException refuseAccumulated(PhysicalNode owner, int cap) {
        return new EvaluationException(
                name(owner) + " accumulated more than " + cap + " row"
                + (cap == 1 ? "" : "s") + "; a fixpoint holds every row it has derived so far, "
                + "and a step that computes new values derives one every round without ever "
                + "repeating itself. Bound the recursion (maxFixpointRounds, or a selection on "
                + "a frozen column that the optimizer can push into it), or raise "
                + "maxMaterializedRows");
    }

    /**
     * The operator's name, as the physical plan's own JSON already spells it
     * ({@code PhysicalPlanJson}'s {@code op} discriminator is the same expression), so a
     * name in an error or a trace matches the plan a user reads beside it — and a new
     * operator is named without being registered anywhere.
     */
    private static String name(PhysicalNode owner) {
        return owner.getClass().getSimpleName();
    }
}

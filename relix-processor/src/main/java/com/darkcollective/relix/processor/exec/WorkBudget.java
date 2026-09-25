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

import com.darkcollective.relix.processor.internal.ExecutionContext;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.EvaluationException;

import java.time.Duration;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * How much work one execution may do: a count of rows processed, and a deadline.
 *
 * <h2>What is counted</h2>
 *
 * A row is charged each time an operator hands one to the operator above it, so the
 * count is the sum of every operator's output. That is the measure of work a
 * pull-based engine can observe without instrumenting each operator: work that yields
 * rows passes a boundary, and a boundary is where this budget stands.
 *
 * <p>It catches the two shapes a cap on output cannot. A selection over an endless
 * generator that matches nothing yields no row to its consumer, but every row it rejects
 * crossed the boundary above the scan. An aggregate over a large product holds nothing
 * and emits one row, but every row of the product crossed the boundary above the join.
 *
 * <p>It is deterministic, where a deadline is not: the same query over the same data
 * charges the same count on any machine. A test asserts on this one.
 *
 * <h2>What the deadline adds</h2>
 *
 * The deadline is checked at the same boundaries, so it notices a query no sooner than
 * the next row crosses one. That covers loops between two rows as well, since a row is
 * pulled at least once per unit of outer work. It does not reach inside a call that
 * never returns to the engine: a JDBC driver waiting on the database, or a solver's
 * search, which runs to completion inside one operator before emitting anything.
 *
 * <p>The clock starts when the execution does. Planning is not charged.
 *
 * <p>One instance serves a whole execution, shared through every derived
 * {@link EvalCtx} the way the spool store is, so a recursion's rounds are charged to
 * the same budget as everything else.
 */
final class WorkBudget {

    /** A budget with no limits, which meters nothing. */
    static final WorkBudget UNLIMITED = new WorkBudget(
            ExecutionContext.UNLIMITED_PROCESSED_ROWS, ExecutionContext.UNLIMITED_TIMEOUT);

    private final long maxRows;
    private final Duration timeout;
    private final boolean timed;
    private final long deadline;
    private final boolean enforcing;
    private long charged;

    private WorkBudget(long maxRows, Duration timeout) {
        this.maxRows = maxRows;
        this.timeout = timeout;
        this.timed = !timeout.equals(ExecutionContext.UNLIMITED_TIMEOUT);
        // Only a timed budget has a deadline. nanoTime may be negative, so an "infinite"
        // deadline of Long.MAX_VALUE would overflow the comparison rather than never pass.
        this.deadline = timed ? System.nanoTime() + timeout.toNanos() : 0;
        this.enforcing = timed || maxRows != ExecutionContext.UNLIMITED_PROCESSED_ROWS;
    }

    /**
     * The budget for one execution under {@code ctx}'s limits, starting now.
     *
     * @param ctx the execution's context; must not be null
     * @return a fresh budget, or {@link #UNLIMITED} when the context sets no limit
     */
    static WorkBudget startingNow(ExecutionContext ctx) {
        if (ctx.maxProcessedRows() == ExecutionContext.UNLIMITED_PROCESSED_ROWS
                && ctx.timeout().equals(ExecutionContext.UNLIMITED_TIMEOUT)) {
            return UNLIMITED;
        }
        return new WorkBudget(ctx.maxProcessedRows(), ctx.timeout());
    }

    /** {@return how many rows this execution has processed so far} */
    long charged() {
        return charged;
    }

    /**
     * Wraps an operator's output so that each row it yields is charged to this budget.
     *
     * <p>With no limit set nothing is counted, but each row still passes an interrupt
     * check, so every execution can be cancelled wherever it is spending its time.
     *
     * @param rows the operator's output; must not be null
     * @return the metered stream, closing {@code rows} when it is closed
     */
    Stream<Row> meter(Stream<Row> rows) {
        if (!enforcing) {
            // Nothing to count, but the interrupt flag is still read per row: this seam is
            // the only place a streaming operator's input rows pass, so without it a
            // filter that never matches spins inside one pull from the root and cannot be
            // cancelled.
            return Cancellation.interruptible(rows);
        }
        Spliterator<Row> source = rows.spliterator();
        Spliterator<Row> metered = new Spliterators.AbstractSpliterator<>(
                source.estimateSize(), source.characteristics()) {
            @Override
            public boolean tryAdvance(Consumer<? super Row> action) {
                return source.tryAdvance(row -> {
                    charge();
                    action.accept(row);
                });
            }
        };
        return StreamSupport.stream(metered, false).onClose(rows::close);
    }

    /**
     * Charges one row, refusing when either limit has been crossed.
     *
     * <p>The interrupt flag is checked here too, because this is the one point every
     * operator's rows pass. Without it, an operator filtering an endless input runs inside
     * a single pull from the root and never reaches the root's check.
     *
     * @throws EvaluationException if the row count exceeds the budget, the deadline has
     *         passed, or the thread has been interrupted
     */
    void charge() {
        charged++;
        if (charged > maxRows) {
            throw new EvaluationException("query stopped: it processed more than " + maxRows
                    + " rows, the limit for one execution");
        }
        if (timed && System.nanoTime() - deadline > 0) {
            throw new EvaluationException("query stopped: it ran longer than " + describe(timeout)
                    + ", the limit for one execution");
        }
        Cancellation.checkNotInterrupted();
    }

    /** A timeout as the refusal names it: whole seconds as seconds, anything else in milliseconds. */
    static String describe(Duration timeout) {
        long millis = timeout.toMillis();
        return millis % 1000 == 0 ? (millis / 1000) + "s" : millis + "ms";
    }
}

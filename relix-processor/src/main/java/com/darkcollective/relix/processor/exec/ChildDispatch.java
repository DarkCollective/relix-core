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

import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.processor.Row;

import java.util.List;
import java.util.stream.Stream;

/**
 * The child-execution seam shared by the operator-group executors that
 * {@link PhysicalExecutor} delegates to.  Each executor holds a
 * {@code ChildDispatch} so it can recursively run its input sub-plans without
 * depending on the concrete {@link PhysicalExecutor} or the central dispatch
 * switch.
 *
 * <p>{@link PhysicalExecutor} supplies the implementation (its own central
 * {@code execute}); the single abstract method makes this a functional
 * interface so {@code this::execute} satisfies it.
 *
 * <h2>Child-stream lifecycle</h2>
 * <p><b>A row stream is a resource, and {@link #execute} transfers ownership of it
 * to the caller.</b>  Not every stream holds one — an inline scan, a CSV file, and
 * an HTTP source are all read into memory before their stream is built — but the
 * JDBC connector's is lazy over a live {@code ResultSet}, holding that
 * {@code ResultSet}, its {@code Statement}, and a pooled {@code Connection} until
 * the stream is closed (and the MongoDB connector plugin behaves the same way).
 * So an executor that consumes a child stream and abandons it leaks a cursor and a
 * pooled connection for the rest of the run.  This cannot be left to the caller
 * above: closing the <em>root</em> stream reaches only the streams still linked
 * into it, never a child some operator already drained mid-tree.
 *
 * <p>The rule is therefore one of two shapes at every call site, and there is no
 * third:
 * <ul>
 *   <li><b>Blocking</b> — the operator drains the child itself, so it must close
 *       it: call {@link #materialize}, or wrap the terminal operation in
 *       {@code try}-with-resources when it needs something other than a
 *       {@code List} (a {@code forEach} into an index, an {@code Iterator} loop).
 *       {@code Stream.toList()} does <b>not</b> close, so
 *       {@code execute(…).toList()} is always the bug.</li>
 *   <li><b>Streaming</b> — the operator returns a stream <em>derived</em> from the
 *       child ({@code map}/{@code filter}/{@code flatMap}/{@code limit}/…), which
 *       passes ownership on up: closing the derived stream closes its source, and
 *       {@code flatMap} closes each inner stream as it is exhausted.  An operator
 *       that instead hands back a stream not linked to the child — one built from
 *       an {@code Iterator} taken off it — must re-attach the child's close with
 *       {@code .onClose(child::close)} (see
 *       {@code AggregateExecutor.executeStreamingAggregate}).</li>
 * </ul>
 *
 * @see com.darkcollective.relix.processor.internal.DataSourceConnector
 */
@FunctionalInterface
interface ChildDispatch {

    /**
     * Runs {@code node} against {@code ctx}, returning a lazy, closeable stream.
     * The caller owns the returned stream and must close it — directly, or by
     * returning a stream derived from it.  See the lifecycle rule above.
     */
    Stream<Row> execute(PhysicalNode node, EvalCtx ctx);

    /**
     * Fully materialises {@code node}'s output on behalf of {@code owner}, closing the
     * underlying stream.  The default for a blocking operator's input: it makes the close
     * impossible to forget, which the equivalent {@code execute(…).toList()} does not.
     *
     * <p>{@code owner} is the operator doing the <em>buffering</em> — the join, not the
     * scan it is draining — because that is what a row cap has to name and what a
     * {@code MATERIALIZE} event has to be about.  The rows are counted against
     * {@link MaterializationBudget} on the way in, so a blocking operator cannot swallow
     * a table without either stopping at the cap or saying how much it held.
     *
     * @throws com.darkcollective.relix.processor.EvaluationException if {@code owner}
     *         would buffer more rows than
     *         {@link com.darkcollective.relix.processor.internal.ExecutionContext#maxMaterializedRows()}
     */
    default List<Row> materialize(PhysicalNode node, EvalCtx ctx, PhysicalNode owner) {
        return MaterializationBudget.drain(owner, execute(node, ctx), ctx);
    }

    /**
     * Runs {@code node} and wraps its stream so every row {@code owner} buffers is
     * counted — for a blocking operator whose buffer is not a list (an
     * {@link IndexedRelation} built by group key, a {@code forEach} into a set).
     *
     * <p>The caller still owns the returned stream and must close it, exactly as with
     * {@link #execute}: this adds metering, not lifecycle.
     *
     * @throws com.darkcollective.relix.processor.EvaluationException if {@code owner}
     *         would buffer more rows than the cap
     */
    default Stream<Row> buffering(PhysicalNode node, EvalCtx ctx, PhysicalNode owner) {
        return MaterializationBudget.meter(owner, execute(node, ctx), ctx);
    }

    /**
     * Runs {@code node} and wraps its stream so that what {@code owner} <em>holds</em> is
     * counted rather than what it reads — for an operator whose buffer is bounded by its
     * own shape, as {@code TOP} keeps a fixed number of rows per group however many it
     * reads to find them.
     *
     * <p>The caller still owns the returned stream and must close it, exactly as with
     * {@link #execute}.
     *
     * @param held what {@code owner} is currently holding, polled after each row is
     *             handed on
     * @throws com.darkcollective.relix.processor.EvaluationException if {@code owner}
     *         holds more rows than the cap
     */
    default Stream<Row> holding(PhysicalNode node, EvalCtx ctx, PhysicalNode owner,
                                java.util.function.LongSupplier held) {
        return MaterializationBudget.holding(owner, execute(node, ctx), ctx, held);
    }
}

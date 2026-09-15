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

import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.processor.Row;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * The per-execution cache behind {@link LateralExecutor}: argument tuple →
 * planned function body, and argument tuple → that body's rows.
 *
 * <p>A lateral join asks its {@code bodyBuilder} for a fresh physical body once per
 * <em>outer row</em>, and that lambda re-runs schema inference and the whole planner.
 * Both the plan and the rows it produces depend on nothing but the argument tuple, and
 * duplicate tuples are the norm rather than the exception — a lateral over a foreign key
 * with low cardinality hits the same values on row after row — so both are worth
 * keeping.
 *
 * <h2>Why two caches</h2>
 * <p>They have very different costs, and — the part that matters for correctness — very
 * different preconditions. A planned {@link PhysicalNode} is small, is what saves the
 * planner invocation (the expensive half), and is <em>always</em> safe to reuse: planning
 * binds arguments and chooses algorithms, it does not evaluate anything, so the same
 * arguments always yield an equivalent plan. It is cached for every tuple that fits under
 * {@link #maxEntries}.
 *
 * <p>Retained <b>rows</b> additionally save the re-execution, but reusing them asserts
 * that running the body twice would have given the same relation. That is only true of a
 * deterministic body, so row retention is gated on
 * {@link PhysicalNode.LateralJoin#deterministicBody()} — decided by the planner, which can
 * see the function body this layer only holds a {@code bodyBuilder} lambda for. A body
 * reading {@code Rand()} or {@code NOW()}, drawing an unseeded {@code SAMPLE}, or calling a
 * view or nested TVF that does, keeps its per-outer-row execution and loses only the
 * re-planning. Rows are also separately budgeted by {@link #maxRows}, being the part that
 * can grow without bound.
 *
 * <h2>Bounds</h2>
 * <p>An unbounded cache keyed on argument tuples is a memory leak on a high-cardinality
 * correlation, where nearly every tuple is a miss and the cache is pure overhead. Two
 * caps apply, and past either one this degrades to the un-memoized path rather than
 * growing:
 * <ul>
 *   <li>{@link #DEFAULT_MAX_ENTRIES} distinct argument tuples tracked;</li>
 *   <li>{@link #DEFAULT_MAX_ROWS} rows retained across all of them.</li>
 * </ul>
 * <p>A body too large to fit the remaining row budget is <em>streamed straight
 * through</em> — never fully materialised just to discover it does not fit, and never
 * executed twice — and its tuple is remembered so the next outer row carrying the same
 * arguments does not buffer it again.
 *
 * <p>Instances are confined to a single stream pipeline and are not thread-safe.
 */
final class LateralMemo {

    /** Distinct argument tuples tracked by default. */
    static final int DEFAULT_MAX_ENTRIES = 256;

    /** Rows retained across all entries by default. */
    static final int DEFAULT_MAX_ROWS = 20_000;

    private final Function<List<Operand>, PhysicalNode> bodyBuilder;
    private final boolean retainRows;
    private final ChildDispatch dispatch;
    private final EvalCtx ctx;
    private final int maxEntries;
    private final int maxRows;

    private final Map<List<Operand>, PhysicalNode> plans = new HashMap<>();
    private final Map<List<Operand>, List<Row>> rows = new HashMap<>();
    private final Set<List<Operand>> tooLarge = new HashSet<>();
    private int retainedRows;

    LateralMemo(PhysicalNode.LateralJoin node, ChildDispatch dispatch, EvalCtx ctx) {
        this(node.bodyBuilder(), node.deterministicBody(), dispatch, ctx,
                DEFAULT_MAX_ENTRIES, DEFAULT_MAX_ROWS);
    }

    LateralMemo(Function<List<Operand>, PhysicalNode> bodyBuilder, boolean retainRows,
                ChildDispatch dispatch, EvalCtx ctx, int maxEntries, int maxRows) {
        this.bodyBuilder = bodyBuilder;
        this.retainRows  = retainRows;
        this.dispatch    = dispatch;
        this.ctx         = ctx;
        this.maxEntries  = maxEntries;
        this.maxRows     = maxRows;
    }

    /**
     * Returns the function body's rows for {@code args}, reusing a retained result or a
     * retained plan where one is held.
     *
     * @param args the argument tuple, already lifted to literal operands; must be
     *             immutable, since it is used as a cache key
     */
    Stream<Row> rowsFor(List<Operand> args) {
        List<Row> memoized = rows.get(args);
        if (memoized != null) {
            return memoized.stream();
        }
        return execute(args, plan(args));
    }

    /** {@return the planned body for {@code args}, invoking the planner on a miss} */
    private PhysicalNode plan(List<Operand> args) {
        PhysicalNode cached = plans.get(args);
        if (cached != null) {
            return cached;
        }
        PhysicalNode body = bodyBuilder.apply(args);
        if (plans.size() < maxEntries) {
            plans.put(args, body);
        }
        return body;
    }

    /**
     * Runs {@code body}, retaining its rows under {@code args} when they fit in the
     * remaining budget.
     *
     * <p>Does nothing but run the body when the body is not deterministic: reusing its
     * rows for a later outer row would assert an equivalence that does not hold.
     *
     * <p>Otherwise the rows are buffered incrementally rather than with
     * {@link ChildDispatch#materialize}: a body larger than the budget must not be fully
     * materialised just to discover it does not fit, and it must not be re-executed
     * either. So at most {@code budget} rows are pulled, and if the body is still going
     * at that point the buffered prefix is concatenated with the live remainder — one
     * execution, bounded memory, nothing retained.
     */
    private Stream<Row> execute(List<Operand> args, PhysicalNode body) {
        int budget = maxRows - retainedRows;
        if (!retainRows || budget <= 0 || rows.size() >= maxEntries || tooLarge.contains(args)) {
            return dispatch.execute(body, ctx);
        }
        Stream<Row> stream = dispatch.execute(body, ctx);
        Iterator<Row> remaining = stream.iterator();
        List<Row> buffered = new ArrayList<>();
        while (buffered.size() < budget && remaining.hasNext()) {
            buffered.add(remaining.next());
        }
        if (!remaining.hasNext()) {
            stream.close();
            List<Row> retained = List.copyOf(buffered);
            rows.put(args, retained);
            retainedRows += retained.size();
            return retained.stream();
        }
        tooLarge.add(args);
        return Stream.concat(buffered.stream(), StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(remaining, Spliterator.ORDERED),
                        false))
                .onClose(stream::close);
    }
}

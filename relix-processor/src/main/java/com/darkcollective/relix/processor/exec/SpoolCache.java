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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@link SpoolBuffer}s of one execution, and the memory budget they share.
 *
 * <p>A physical plan is a tree except at a
 * {@link com.darkcollective.relix.plan.PhysicalNode.Spool}, where the same sub-plan is
 * read by more than one consumer.  Buffers are keyed by the planner's spool id rather
 * than by node identity, so a plan that was copied or rebuilt shares exactly what it
 * was planned to share.
 *
 * <p><b>Bounded, and it gives up rather than grows.</b>  At most
 * {@link #DEFAULT_MAX_ROWS} rows are retained across every spool in one execution, and
 * a buffer that reaches the limit stops growing: its readers carry on for themselves,
 * which costs what not sharing would have cost.  Nothing here changes an answer; it
 * only changes how many times the answer is computed.
 *
 * <p>Held by {@link EvalCtx} and deliberately shared across the contexts
 * {@link EvalCtx#withBinding} derives, so a spool above a fixpoint is filled once for
 * all of its iterations — and so is anything built over one (see {@link BuiltSide}).  That is also why the planner refuses to spool a sub-plan
 * that reads a recursive relation bound outside itself: this store would answer for
 * the first iteration ever after.
 *
 * <p>Not thread-safe — one execution, one thread.
 */
final class SpoolCache {

    /** Rows retained across all spools in one execution before buffers stop growing. */
    static final int DEFAULT_MAX_ROWS = 20_000;

    private final Map<Integer, SpoolBuffer> buffers = new LinkedHashMap<>();
    private final int maxRows;
    private int retainedRows;

    SpoolCache() {
        this(DEFAULT_MAX_ROWS);
    }

    SpoolCache(int maxRows) {
        this.maxRows = maxRows;
    }

    /** The buffer for spool {@code id}, created on first use. */
    SpoolBuffer buffer(int id, PhysicalNode input, ChildDispatch dispatch) {
        return buffers.computeIfAbsent(id, _ -> new SpoolBuffer(input, dispatch, this));
    }

    /** How many more rows may be retained before the budget is spent. */
    int remainingBudget() {
        return maxRows - retainedRows;
    }

    /** Records {@code rows} more rows held in memory. */
    void charge(int rows) {
        retainedRows += rows;
    }

    // ── artifacts built over a spool ──────────────────────────────────────────

    /**
     * A join's build side, prepared once: the rows, and the hash index over them
     * when the join hashes ({@code index} is null for a nested loop, which walks the
     * rows instead).
     *
     * @param rows  the build side's rows; the holder must treat them as read-only,
     *              since every later round is handed the same list
     * @param index key values → the rows carrying them, or null when not hashed
     */
    record BuiltSide(List<Row> rows, Map<List<String>, List<Row>> index) {
    }

    private final Map<String, BuiltSide> builtSides = new HashMap<>();

    /**
     * The build side already prepared over spool {@code id} for {@code keys}, or null
     * when there is none to reuse.
     *
     * <p>This is what a spool buys a <em>consumer</em>, beyond what it buys a reader.
     * A fixpoint step is re-executed on every round, so a join inside one rebuilds its
     * hash table per round even when the rows behind it never change — the spool
     * caches rows, and hashing them again is work it cannot avoid. Keyed by spool and
     * by key columns because two joins may hash the same sub-plan differently.
     *
     * <p>Sound for the reason the spool itself is: the planner shares only a sub-plan
     * it has proved reproducible, and it refuses one naming a recursive relation bound
     * outside itself — so rows behind a spool are the same rows on round ten as on
     * round one, and so is anything derived from them.
     */
    BuiltSide builtSide(int id, String keys) {
        return builtSides.get(id + "/" + keys);
    }

    /**
     * Offers a prepared build side for the rounds to come.  Kept only if the spool
     * holds the whole sub-plan: a buffer that stopped growing on the budget serves
     * later readers by re-running, so keeping this would pin rows the buffer itself
     * has given up on — spending the memory the budget had just declined.
     */
    void keepBuiltSide(int id, String keys, BuiltSide side) {
        SpoolBuffer buffer = buffers.get(id);
        if (buffer != null && buffer.isComplete()) {
            builtSides.put(id + "/" + keys, side);
        }
    }

    /**
     * Releases every shared read still open — the reads nobody exhausted and nobody
     * took over, because every consumer stopped early.  Called once, when the
     * execution's root stream closes.
     */
    void close() {
        buffers.values().forEach(SpoolBuffer::closeShared);
    }
}

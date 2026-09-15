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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * One shared sub-plan's rows, read by several consumers and evaluated once.
 *
 * <h2>Grown by demand, not up front</h2>
 * <p>The buffer holds a prefix of the sub-plan's output and extends it one row at a
 * time, when a reader asks for a row past its end.  So it grows to the <b>high-water
 * mark of what the readers actually want</b>, not to the size of the relation: two
 * readers that stop after five rows between them cost five rows, and a reader that
 * drains the sub-plan fills it completely, exactly as a blocking operator always did.
 *
 * <p>That distinction is the whole reason for the design.  Filling eagerly on the
 * first demand is simpler — the buffer is either complete or the spool is off, with
 * no half-filled state for a second reader to arrive into — but it is only free when
 * <em>some</em> reader drains the sub-plan anyway.  Where every reader is partial, an
 * eager fill does strictly more work than not sharing at all, which is the one thing
 * sharing must never do.
 *
 * <h2>When demand outruns the budget</h2>
 * <p>Retained rows are charged against {@link SpoolCache}'s budget, and once it is
 * spent the buffer stops growing.  A reader that has already emitted rows cannot be
 * told to start over, so instead it <b>continues on its own</b>: the first one to run
 * past the retained prefix takes over the shared read from exactly where the prefix
 * ends, and any other re-runs the sub-plan and skips what it has already emitted.
 * That skip is sound because the planner only shares a sub-plan it has proved
 * reproducible, and it costs what not sharing would have cost — never a wrong answer,
 * only the saving given up.
 *
 * <p>Not thread-safe: one execution, one thread, readers interleaved rather than
 * concurrent.
 */
final class SpoolBuffer {

    private final PhysicalNode input;
    private final ChildDispatch dispatch;
    private final SpoolCache cache;

    private final List<Row> retained = new ArrayList<>();
    /** The shared read of {@link #input}; opened on first demand, null once finished or taken over. */
    private Stream<Row> source;
    private Iterator<Row> cursor;
    /** The source ran out: {@link #retained} is the whole relation and no reader need look further. */
    private boolean complete;
    /** The budget is spent (or the source failed): {@link #retained} will not grow again. */
    private boolean abandoned;

    SpoolBuffer(PhysicalNode input, ChildDispatch dispatch, SpoolCache cache) {
        this.input = input;
        this.dispatch = dispatch;
        this.cache = cache;
    }

    /** A reader over this sub-plan's rows, for a consumer running under {@code ctx}. */
    Stream<Row> view(EvalCtx ctx) {
        View view = new View(ctx);
        return StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(view, Spliterator.ORDERED), false)
                .onClose(view::close);
    }

    /**
     * Whether {@link #retained} is the whole sub-plan — the source ran out and no
     * reader need look further.
     *
     * <p>What a consumer that wants to <em>keep</em> something derived from these
     * rows has to ask.  A buffer that stopped growing on the budget serves its
     * readers by re-running the sub-plan, so what one reader saw is not what this
     * buffer holds, and an artifact built from it would pin rows nothing else does.
     */
    boolean isComplete() {
        return complete;
    }

    /**
     * Closes the shared read if it is still open — the case where every reader stopped
     * early, so nobody exhausted it and nobody took it over.  Called once, when the
     * execution's root stream closes.
     */
    void closeShared() {
        if (source != null) {
            source.close();
            source = null;
            cursor = null;
        }
    }

    /** Pulls one more row into {@link #retained}; false when there is none to be had. */
    private boolean extend(EvalCtx ctx) {
        if (abandoned) {
            return false;
        }
        if (cache.remainingBudget() <= 0) {
            abandoned = true;
            return false;
        }
        if (source == null) {
            source = dispatch.execute(input, ctx);
            cursor = source.iterator();
        }
        try {
            if (!cursor.hasNext()) {
                complete = true;
                closeShared();
                return false;
            }
            retained.add(cursor.next());
            cache.charge(1);
            return true;
        } catch (RuntimeException e) {
            // Whatever the sub-plan did, this buffer cannot vouch for it; a later
            // reader re-runs it and meets the same failure for itself. Which is also why
            // the shared read is released here rather than left for the end of the
            // execution: nothing will read it again, and the resource it holds is a
            // connector's.
            abandoned = true;
            closeShared();
            throw e;
        }
    }

    /** One consumer's position in the shared row sequence. */
    private final class View implements Iterator<Row> {

        private final EvalCtx ctx;
        private int at;
        /** Set once this view has run past what the buffer will retain and reads for itself. */
        private Iterator<Row> own;
        private Stream<Row> ownStream;
        private boolean closed;

        View(EvalCtx ctx) {
            this.ctx = ctx;
        }

        @Override
        public boolean hasNext() {
            if (closed) {
                return false;
            }
            if (own != null) {
                return own.hasNext();
            }
            if (at < retained.size()) {
                return true;
            }
            if (complete) {
                return false;
            }
            if (extend(ctx)) {
                return true;
            }
            if (complete) {
                return false;
            }
            continueAlone();
            return own.hasNext();
        }

        @Override
        public Row next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return own != null ? own.next() : retained.get(at++);
        }

        /**
         * Leaves the shared buffer behind, having reached the end of what it will
         * retain.  Taking over the shared read is free and exact when this view sits
         * exactly at the prefix's end — which the first view to get here always does —
         * and otherwise the sub-plan is re-run and the emitted rows skipped.
         */
        private void continueAlone() {
            if (source != null && at == retained.size()) {
                ownStream = source;
                own = cursor;
                source = null;
                cursor = null;
            } else {
                ownStream = dispatch.execute(input, ctx).skip(at);
                own = ownStream.iterator();
            }
        }

        void close() {
            closed = true;
            if (ownStream != null) {
                ownStream.close();
                ownStream = null;
            }
        }
    }
}

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
package com.darkcollective.relix.events.internal;

import com.darkcollective.relix.events.QueryEvent;
import com.darkcollective.relix.events.QueryEventListener;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * A bounded, in-memory {@link QueryEventListener} that retains what it observes
 * so the feed can be <em>read back</em> instead of only printed.
 *
 * <p>The listener seam is a push port: a producer calls
 * {@link #onEvent(QueryEvent)} and the event is gone unless someone held on to
 * it. A buffer holds on to it, which is what lets a temporal feed be surfaced as
 * a relation — the bridge between "what happened during that run" and "what can
 * be queried now". Nothing here knows about relations or the catalog; it is a
 * listener that remembers, and the mapping to columns lives in the consumer.
 *
 * <h2>Bounded by construction</h2>
 * <p>A buffer never grows without limit: at {@link #capacity()} events the
 * oldest is evicted to make room, and {@link #droppedCount()} records how many
 * were lost that way. An unbounded collector attached to a long-lived session
 * would be a slow leak whose size is set by how much the user runs, so the cap
 * is not configurable per event — it is the point of the type. When events have
 * been dropped, the retained feed is the <em>most recent</em> window, not the
 * whole run.
 *
 * <h2>Ordering</h2>
 * <p>{@link #events()} returns the retained events oldest-first, in the order
 * they were observed. A {@link QueryEvent} carries no timestamp, so this arrival
 * order is the only temporal fact the feed has; consumers that need a stable
 * sort key derive it from the position.
 *
 * <h2>Thread safety</h2>
 * <p>Every method is synchronized on the buffer, so producers may emit from any
 * thread — a REPL evaluating on a worker while the shell reads back, or an
 * executor emitting as a stream is drained. {@link #events()} returns an
 * immutable snapshot, so a reader is never exposed to a concurrent append.
 */
public final class EventBuffer implements QueryEventListener {

    /** Retained-event cap used by {@link #EventBuffer()} ({@value}). */
    public static final int DEFAULT_CAPACITY = 1_000;

    private final int capacity;
    private final Deque<QueryEvent> retained;
    private long dropped;

    /** Creates a buffer retaining up to {@link #DEFAULT_CAPACITY} events. */
    public EventBuffer() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Creates a buffer retaining up to {@code capacity} events.
     *
     * @param capacity the maximum number of events retained; must be positive
     * @throws IllegalArgumentException if {@code capacity} is not positive
     */
    public EventBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, was " + capacity);
        }
        this.capacity = capacity;
        this.retained = new ArrayDeque<>(Math.min(capacity, 64));
    }

    @Override
    public synchronized void onEvent(QueryEvent event) {
        if (event == null) {
            return;
        }
        if (retained.size() == capacity) {
            retained.removeFirst();
            dropped++;
        }
        retained.addLast(event);
    }

    /**
     * Returns the retained events, oldest first.
     *
     * @return an immutable snapshot of the retained feed; never null
     */
    public synchronized List<QueryEvent> events() {
        return List.copyOf(retained);
    }

    /**
     * Discards every retained event and resets {@link #droppedCount()} — used to
     * start a fresh observation window.
     */
    public synchronized void clear() {
        retained.clear();
        dropped = 0;
    }

    /**
     * Returns how many events were evicted to stay within {@link #capacity()}
     * since the last {@link #clear()}.
     *
     * @return the number of dropped events; zero when nothing overflowed
     */
    public synchronized long droppedCount() {
        return dropped;
    }

    /**
     * Returns the number of events currently retained.
     *
     * @return the retained count, never greater than {@link #capacity()}
     */
    public synchronized int size() {
        return retained.size();
    }

    /**
     * Returns the maximum number of events this buffer retains.
     *
     * @return the capacity given at construction
     */
    public int capacity() {
        return capacity;
    }
}

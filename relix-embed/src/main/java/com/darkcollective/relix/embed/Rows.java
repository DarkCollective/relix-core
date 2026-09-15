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
package com.darkcollective.relix.embed;

import com.darkcollective.relix.events.QueryEvent;
import com.darkcollective.relix.symbol.Schema;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * The result of one run — the rows, their heading, and what the engine did to produce
 * them.
 *
 * <p>{@link Relation#toList()} answers the rows alone, which is what most callers want.
 * This is {@link Relation#run()}'s answer, for a caller who also wants to know
 * <em>how</em>: which rewrite rules fired, which join algorithm the planner chose, what
 * was pushed into a database and what was evaluated in-engine.
 *
 * <p>The last event is always the run's own cardinality — an {@code EXECUTE}/{@code ROWS}
 * event carrying {@code EventMetrics.rows}. It is stated as an event rather than as a
 * field so that a consumer reads one feed rather than one feed and a side channel, and it
 * is emitted by the terminal rather than by the executor because a row stream's length is
 * not known until something drains it.
 *
 * <p>The events are <strong>this run's own</strong>. That is worth stating because the
 * {@code relix.events} catalog relation cannot be: a statement's events postdate the
 * analysis that would have to register them, so in a session there they describe the
 * <em>previous</em> statement. An embedding has no such constraint — it runs the query and
 * then hands the caller the result — so what arrives here is the feed for the rows beside
 * it.
 *
 * @param schema the heading of every row
 * @param rows   the rows, in the order the query produced them
 * @param events the optimizer, planner and execution events of this run, in arrival order
 * @since 1.0
 */
public record Rows(Schema schema, List<Tuple> rows, List<QueryEvent> events)
        implements Iterable<Tuple> {

    /**
     * Canonical constructor; the lists are copied, so a result cannot change under its
     * holder.
     *
     * @since 1.0
     */
    public Rows {
        Objects.requireNonNull(schema, "schema");
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        events = List.copyOf(Objects.requireNonNull(events, "events"));
    }

    /**
     * {@return how many rows the run produced}
     *
     * @since 1.0
     */
    public int size() {
        return rows.size();
    }

    /**
     * {@return whether the run produced no rows}
     *
     * @since 1.0
     */
    public boolean isEmpty() {
        return rows.isEmpty();
    }

    @Override
    public Iterator<Tuple> iterator() {
        return rows.iterator();
    }
}

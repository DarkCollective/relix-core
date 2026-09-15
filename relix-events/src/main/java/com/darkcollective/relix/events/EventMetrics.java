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
package com.darkcollective.relix.events;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The numbers an observed decision can carry — what a {@link QueryEvent}'s text cannot say.
 *
 * <p>An event's {@code description} is prose for a human reading a trace. A consumer that
 * wants to <em>compute</em> with what a run observed — comparing an estimate against the
 * cardinality actually produced, or totalling the work a stage did — needs the quantity
 * itself, not a sentence containing it.
 *
 * <h2>Why this is a class and not a record</h2>
 *
 * <p>It exists so that measuring something new later does not change {@link QueryEvent}
 * again, and that only works if adding a measurement does not break <em>this</em> type
 * either. A record cannot deliver that: its canonical constructor is part of its public
 * API and cannot be made less accessible than the record itself, so a second component
 * would change a signature callers may already use. A final class with a private
 * constructor can — every construction goes through a named factory or wither, so a new
 * measurement is a new method and never a changed signature.
 *
 * <p>Instances are immutable and compare by value; every wither returns a new one.
 *
 * <p>Not being a record has a cost worth knowing about: {@code equals}, {@code hashCode},
 * {@code toString} and {@link #isEmpty()} are hand-written, and each one enumerates the
 * measurements — so a new measurement is four edits, three of which fail silently if
 * forgotten. {@code EventMetricsTest} enumerates the fields reflectively and checks all
 * four against each one, so the omission fails the build instead.
 */
public final class EventMetrics {

    /** Measures nothing — the shape an event carries when no quantity was observed. */
    public static final EventMetrics NONE =
            new EventMetrics(OptionalLong.empty(), Optional.empty());

    private final OptionalLong rows;
    private final Optional<Duration> duration;

    private EventMetrics(OptionalLong rows, Optional<Duration> duration) {
        this.rows = Objects.requireNonNull(rows, "rows");
        this.duration = Objects.requireNonNull(duration, "duration");
    }

    /**
     * Metrics recording a row count.
     *
     * @param rows the number of rows delivered; must not be negative
     * @return metrics carrying {@code rows}
     */
    public static EventMetrics rows(long rows) {
        return NONE.withRows(rows);
    }

    /**
     * Metrics recording elapsed time.
     *
     * @param elapsed how long the observed step took; must not be null or negative
     * @return metrics carrying {@code elapsed}
     */
    public static EventMetrics duration(Duration elapsed) {
        return NONE.withDuration(elapsed);
    }

    /**
     * Metrics recording both — the shape every step that counts rows as it runs produces,
     * since it knows the count and the elapsed time at the same moment.
     *
     * @param rows    the number of rows delivered; must not be negative
     * @param elapsed how long the observed step took; must not be null or negative
     * @return metrics carrying both quantities
     */
    public static EventMetrics of(long rows, Duration elapsed) {
        return NONE.withRows(rows).withDuration(elapsed);
    }

    /**
     * The number of rows the observed step delivered, when it counted them.
     *
     * <p>Empty and zero are different answers: a query that returned nothing measured
     * {@code 0}, while an event that counted nothing at all measures neither.
     *
     * @return the row count, or empty when nothing was counted
     */
    public OptionalLong rows() {
        return rows;
    }

    /**
     * How long the observed step took, when it was timed.
     *
     * <p>Wall clock, and therefore a number for a human diagnosing a slow query rather
     * than one a test asserts on: it varies with the machine, the cache and whatever else
     * the host is doing. What each producer <em>claims</em> to have timed differs and is
     * documented where it is emitted — a scan reports the time it spent producing rows,
     * excluding the work its consumer did with them, while a query's {@code ROWS} event
     * reports the run's own elapsed time from first pull to last.
     *
     * @return the elapsed time, or empty when nothing was timed
     */
    public Optional<Duration> duration() {
        return duration;
    }

    /**
     * Returns a copy also recording {@code rowCount}.
     *
     * @param rowCount the number of rows delivered; must not be negative
     * @return a new instance; this one is unchanged
     */
    public EventMetrics withRows(long rowCount) {
        if (rowCount < 0) {
            throw new IllegalArgumentException("rows must not be negative: " + rowCount);
        }
        return new EventMetrics(OptionalLong.of(rowCount), duration);
    }

    /**
     * Returns a copy also recording {@code elapsed}.
     *
     * @param elapsed how long the observed step took; must not be null or negative
     * @return a new instance; this one is unchanged
     */
    public EventMetrics withDuration(Duration elapsed) {
        Objects.requireNonNull(elapsed, "elapsed");
        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative: " + elapsed);
        }
        return new EventMetrics(rows, Optional.of(elapsed));
    }

    /**
     * Whether any measurement was recorded.
     *
     * @return {@code true} when nothing was measured
     */
    public boolean isEmpty() {
        return rows.isEmpty() && duration.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EventMetrics other
                && rows.equals(other.rows)
                && duration.equals(other.duration);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rows, duration);
    }

    @Override
    public String toString() {
        return "EventMetrics[rows=" + (rows.isPresent() ? rows.getAsLong() : "none")
                + ", duration=" + duration.map(Duration::toString).orElse("none") + "]";
    }
}

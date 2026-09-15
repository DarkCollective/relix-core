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

import java.util.Objects;
import java.util.Optional;

/**
 * One observed decision in the query pipeline — a rule that fired in the
 * optimizer, or a physical choice the planner made.
 *
 * <p>The event is deliberately neutral and display-oriented: it carries a
 * {@link Stage}, a short {@code code}, a human-readable {@code description}, and
 * an optional {@code target} (the query or relation it applies to).  It exposes
 * none of the producer's internal types, so a single
 * {@link QueryEventListener} can observe the optimizer and the planner as one
 * uniform feed.
 *
 * <p>It also carries optional {@link EventMetrics} — the quantities the prose cannot
 * express. A trace is read by a human, but a consumer that compares an estimate against
 * what a run actually produced has to compute with the number rather than parse it back
 * out of a sentence. Measurements live behind one optional component so that observing
 * something new later adds a factory to {@code EventMetrics} rather than a component
 * here, which a record in a published module cannot gain cheaply.
 *
 * @param stage       which part of the pipeline produced the event
 * @param code        a short identifier, e.g. {@code "SEL-001"}, {@code "JOIN"},
 *                    {@code "PUSHDOWN"}; never blank
 * @param description a human-readable summary of what happened; never blank
 * @param target      the query/relation the event applies to, if known
 * @param metrics     the quantities observed with the event; {@link EventMetrics#NONE}
 *                    when the producer measured nothing
 */
public record QueryEvent(Stage stage, String code, String description, Optional<String> target,
                         EventMetrics metrics) {

    /**
     * Which part of the pipeline an event came from.
     *
     * <p>The set is deliberately open-ended: {@link #OPTIMIZE} (logical rewrite
     * rules) and {@link #PLAN} (physical planning decisions) are emitted before a
     * query runs, while {@link #EXECUTE} covers decisions made <em>during</em>
     * execution (e.g. a declarative-optimisation group skipped as infeasible).
     * A single {@link QueryEventListener} observes every stage as one uniform feed.
     */
    public enum Stage { OPTIMIZE, PLAN, EXECUTE }

    public QueryEvent {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(metrics, "metrics");
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
    }

    /**
     * As the canonical constructor, but measuring nothing — the shape for a producer
     * that only describes what it did.
     *
     * @param stage       the pipeline stage
     * @param code        a short identifier; never blank
     * @param description a human-readable summary; never blank
     * @param target      the query/relation the event applies to, if known
     */
    public QueryEvent(Stage stage, String code, String description, Optional<String> target) {
        this(stage, code, description, target, EventMetrics.NONE);
    }

    /**
     * Creates an event with no target.
     *
     * @param stage       the pipeline stage
     * @param code        the short identifier
     * @param description the human-readable summary
     * @return a new event whose {@link #target()} is empty
     */
    public static QueryEvent of(Stage stage, String code, String description) {
        return new QueryEvent(stage, code, description, Optional.empty(), EventMetrics.NONE);
    }

    /**
     * Creates an event targeting a specific query/relation.
     *
     * @param stage       the pipeline stage
     * @param code        the short identifier
     * @param description the human-readable summary
     * @param target      the query/relation name
     * @return a new event with the given target
     */
    public static QueryEvent of(Stage stage, String code, String description, String target) {
        return new QueryEvent(stage, code, description, Optional.of(target), EventMetrics.NONE);
    }

    /**
     * Returns a copy of this event with {@code target} as its target — used by a
     * consumer that knows the owning query an emitter could not.
     *
     * @param target the query/relation name; must not be null
     * @return a copy carrying the given target
     */
    public QueryEvent withTarget(String target) {
        return new QueryEvent(stage, code, description, Optional.of(target), metrics);
    }

    /**
     * Returns a copy of this event carrying {@code measured}.
     *
     * <p>The wither shape matches {@link #withTarget(String)}, and for the same reason: a
     * producer writes the description when it knows what it did, and attaches what it
     * counted once it knows the number — which for a row count is only after the rows have
     * been delivered.
     *
     * @param measured the quantities observed; must not be null
     * @return a new event; this one is unchanged
     */
    public QueryEvent withMetrics(EventMetrics measured) {
        Objects.requireNonNull(measured, "measured");
        return new QueryEvent(stage, code, description, target, measured);
    }
}

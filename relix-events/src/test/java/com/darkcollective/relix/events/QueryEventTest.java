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

import com.darkcollective.relix.events.QueryEvent.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("QueryEvent — neutral pipeline event")
final class QueryEventTest {

    @Test
    @DisplayName("of(stage, code, description) has no target")
    void ofWithoutTarget() {
        QueryEvent e = QueryEvent.of(Stage.OPTIMIZE, "SEL-001", "split selection");
        assertThat(e.stage()).isEqualTo(Stage.OPTIMIZE);
        assertThat(e.code()).isEqualTo("SEL-001");
        assertThat(e.description()).isEqualTo("split selection");
        assertThat(e.target()).isEmpty();
    }

    @Test
    @DisplayName("of(..., target) carries the target")
    void ofWithTarget() {
        QueryEvent e = QueryEvent.of(Stage.PLAN, "JOIN", "hash, build=right", "Orders");
        assertThat(e.target()).contains("Orders");
    }

    @Test
    @DisplayName("withTarget returns a copy with the given target")
    void withTarget() {
        QueryEvent base = QueryEvent.of(Stage.PLAN, "PUSHDOWN", "pushed to db");
        QueryEvent tagged = base.withTarget("query[1]");
        assertThat(base.target()).isEmpty();              // original unchanged
        assertThat(tagged.target()).contains("query[1]");
        assertThat(tagged.code()).isEqualTo("PUSHDOWN");
    }

    @Test
    @DisplayName("blank code or description is rejected")
    void blankFieldsRejected() {
        assertThatThrownBy(() -> QueryEvent.of(Stage.PLAN, "  ", "desc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QueryEvent.of(Stage.PLAN, "JOIN", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the metric-less constructor measures nothing")
    void metricLessConstructorDefaultsToNone() {
        // The shape for a producer that only describes what it did; EventMetrics.NONE is
        // what distinguishes "measured nothing" from "measured zero".
        var event = new QueryEvent(Stage.PLAN, "JOIN", "chose a hash join", Optional.of("q1"));
        assertThat(event.metrics()).isEqualTo(EventMetrics.NONE);
        assertThat(event.metrics().isEmpty()).isTrue();
        assertThat(event.target()).hasValue("q1");
    }

    @Test
    @DisplayName("null code or description is rejected, on the same rule as a blank one")
    void nullCodeOrDescriptionRejected() {
        // Neither is guarded by requireNonNull, so the null arm of the same condition is
        // the one that reports -- and it reports IllegalArgumentException, not NPE.
        assertThatThrownBy(() -> QueryEvent.of(Stage.PLAN, null, "desc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code must not be blank");
        assertThatThrownBy(() -> QueryEvent.of(Stage.PLAN, "JOIN", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description must not be blank");
    }

    @Test
    @DisplayName("null stage or target is rejected")
    void nullFieldsRejected() {
        assertThatThrownBy(() -> new QueryEvent(null, "JOIN", "d", Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryEvent(Stage.PLAN, "JOIN", "d", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("the NONE listener accepts events without effect")
    void noneListener() {
        QueryEventListener.NONE.onEvent(QueryEvent.of(Stage.PLAN, "JOIN", "x"));   // no throw
    }

    @Test
    @DisplayName("EXECUTE is a recognised pipeline stage for execution-time events")
    void executeStage() {
        QueryEvent e = QueryEvent.of(Stage.EXECUTE, "OPTIMIZE",
                "skipped infeasible OPTIMIZE group region=north");
        assertThat(e.stage()).isEqualTo(Stage.EXECUTE);
        assertThat(Stage.values()).containsExactly(Stage.OPTIMIZE, Stage.PLAN, Stage.EXECUTE);
    }

    @org.junit.jupiter.api.Nested
    @org.junit.jupiter.api.DisplayName("metrics")
    final class Metrics {

        @Test
        @DisplayName("an event measures nothing unless told otherwise")
        void defaultsToNone() {
            assertThat(QueryEvent.of(Stage.PLAN, "JOIN", "hash join").metrics())
                    .isEqualTo(EventMetrics.NONE);
        }

        @Test
        @DisplayName("withMetrics attaches the measurement, leaving the original alone")
        void withMetricsAttaches() {
            QueryEvent original = QueryEvent.of(Stage.EXECUTE, "ROWS", "delivered 5 rows");
            QueryEvent measured = original.withMetrics(EventMetrics.rows(5));

            assertThat(measured.metrics().rows()).hasValue(5L);
            assertThat(original.metrics()).isEqualTo(EventMetrics.NONE);
        }

        @Test
        @DisplayName("withTarget preserves metrics already attached")
        void withTargetPreservesMetrics() {
            // The two withers compose in either order, so neither may drop what the
            // other set. This is a real trap: withTarget rebuilds the record, and an
            // omitted component silently reverts to NONE rather than failing.
            QueryEvent measured = QueryEvent.of(Stage.EXECUTE, "ROWS", "delivered 5 rows")
                    .withMetrics(EventMetrics.rows(5))
                    .withTarget("q1");

            assertThat(measured.target()).hasValue("q1");
            assertThat(measured.metrics().rows())
                    .as("withTarget must carry the measurement through")
                    .hasValue(5L);
        }

        @Test
        @DisplayName("null metrics are rejected")
        void rejectsNullMetrics() {
            assertThatThrownBy(() -> QueryEvent.of(Stage.PLAN, "JOIN", "d").withMetrics(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}

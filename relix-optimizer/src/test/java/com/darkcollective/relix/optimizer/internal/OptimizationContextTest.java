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
package com.darkcollective.relix.optimizer.internal;

import com.darkcollective.relix.optimizer.OptimizationCode;
import com.darkcollective.relix.optimizer.TransformationRecord;
import com.darkcollective.relix.ast.SourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OptimizationContext")
final class OptimizationContextTest {

    private OptimizationContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new OptimizationContext();
    }

    @Nested
    @DisplayName("Initial state")
    class InitialState {

        @Test
        @DisplayName("is empty on construction")
        void emptyOnConstruction() {
            assertThat(ctx.isEmpty()).isTrue();
            assertThat(ctx.size()).isZero();
            assertThat(ctx.records()).isEmpty();
        }
    }

    @Nested
    @DisplayName("record()")
    class RecordMethod {

        @Test
        @DisplayName("adds a record to the list")
        void addsRecord() {
            ctx.record(OptimizationCode.EXPR_001, "Q1", "folded 2*3 to 6",
                    SourceLocation.UNKNOWN);

            assertThat(ctx.size()).isEqualTo(1);
            assertThat(ctx.isEmpty()).isFalse();
            assertThat(ctx.records()).hasSize(1);

            TransformationRecord rec = ctx.records().get(0);
            assertThat(rec.code()).isEqualTo(OptimizationCode.EXPR_001);
            assertThat(rec.relationName()).isEqualTo("Q1");
            assertThat(rec.detail()).isEqualTo("folded 2*3 to 6");
        }

        @Test
        @DisplayName("records appear in insertion order")
        void insertionOrder() {
            ctx.record(OptimizationCode.EXPR_001, "Q1", "first",  SourceLocation.UNKNOWN);
            ctx.record(OptimizationCode.SEL_001,  "Q1", "second", SourceLocation.UNKNOWN);
            ctx.record(OptimizationCode.PROJ_001, "Q1", "third",  SourceLocation.UNKNOWN);

            assertThat(ctx.records()).extracting(TransformationRecord::detail)
                    .containsExactly("first", "second", "third");
        }

        @Test
        @DisplayName("null code throws NullPointerException")
        void nullCodeThrows() {
            assertThatThrownBy(() ->
                    ctx.record(null, "Q1", "detail", SourceLocation.UNKNOWN))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null relationName throws NullPointerException")
        void nullRelationNameThrows() {
            assertThatThrownBy(() ->
                    ctx.record(OptimizationCode.EXPR_001, null, "detail",
                            SourceLocation.UNKNOWN))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null detail throws NullPointerException")
        void nullDetailThrows() {
            assertThatThrownBy(() ->
                    ctx.record(OptimizationCode.EXPR_001, "Q1", null,
                            SourceLocation.UNKNOWN))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null location throws NullPointerException")
        void nullLocationThrows() {
            assertThatThrownBy(() ->
                    ctx.record(OptimizationCode.EXPR_001, "Q1", "detail", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("records() view")
    class RecordsView {

        @Test
        @DisplayName("returned list is unmodifiable")
        void listIsUnmodifiable() {
            ctx.record(OptimizationCode.EXPR_001, "Q1", "x", SourceLocation.UNKNOWN);

            assertThatThrownBy(() -> ctx.records().add(
                    new TransformationRecord(OptimizationCode.EXPR_002,
                            "Q2", "y", SourceLocation.UNKNOWN)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("countOf()")
    class CountOf {

        @Test
        @DisplayName("returns zero for a code that has not fired")
        void zeroForUnfiredCode() {
            assertThat(ctx).didNotFire(OptimizationCode.EXPR_001);
        }

        @Test
        @DisplayName("returns correct count for fired codes")
        void correctCount() {
            ctx.record(OptimizationCode.EXPR_001, "Q1", "a", SourceLocation.UNKNOWN);
            ctx.record(OptimizationCode.EXPR_001, "Q1", "b", SourceLocation.UNKNOWN);
            ctx.record(OptimizationCode.SEL_001,  "Q1", "c", SourceLocation.UNKNOWN);

            assertThat(ctx).fired(OptimizationCode.EXPR_001, 2);
            assertThat(ctx).fired(OptimizationCode.SEL_001, 1);
            assertThat(ctx).didNotFire(OptimizationCode.PROJ_001);
        }

        @Test
        @DisplayName("null code throws NullPointerException")
        void nullCodeThrows() {
            assertThatThrownBy(() -> ctx.countOf(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("recordsFor()")
    class RecordsFor {

        @Test
        @DisplayName("returns only records matching the given code")
        void filtersCorrectly() {
            ctx.record(OptimizationCode.EXPR_001, "Q1", "a", SourceLocation.UNKNOWN);
            ctx.record(OptimizationCode.SEL_001,  "Q1", "b", SourceLocation.UNKNOWN);
            ctx.record(OptimizationCode.EXPR_001, "Q1", "c", SourceLocation.UNKNOWN);

            var expr = ctx.recordsFor(OptimizationCode.EXPR_001);
            assertThat(expr).hasSize(2);
            assertThat(expr).extracting(TransformationRecord::detail)
                    .containsExactly("a", "c");
        }

        @Test
        @DisplayName("returns empty list for a code that has not fired")
        void emptyForUnfiredCode() {
            assertThat(ctx.recordsFor(OptimizationCode.JOIN_001)).isEmpty();
        }
    }

    @Nested
    @DisplayName("event emission")
    class EventEmission {

        @Test
        @DisplayName("record() emits an OPTIMIZE event mirroring the transformation")
        void recordEmitsEvent() {
            var events = new java.util.ArrayList<com.darkcollective.relix.events.QueryEvent>();
            var listening = new OptimizationContext(events::add);

            listening.record(OptimizationCode.SEL_001, "Orders",
                    "split conjunctive selection", SourceLocation.UNKNOWN);

            assertThat(events).hasSize(1);
            var event = events.get(0);
            assertThat(event.stage())
                    .isEqualTo(com.darkcollective.relix.events.QueryEvent.Stage.OPTIMIZE);
            assertThat(event.code()).isEqualTo(OptimizationCode.SEL_001.code());
            assertThat(event.description()).isEqualTo("split conjunctive selection");
            assertThat(event.target()).contains("Orders");
        }

        @Test
        @DisplayName("the default (no-arg) context emits nothing but still records")
        void defaultContextEmitsNothing() {
            ctx.record(OptimizationCode.PROJ_001, "V", "removed redundant projection",
                    SourceLocation.UNKNOWN);
            assertThat(ctx.size()).isEqualTo(1);   // recorded, no listener to notify
        }
    }
}

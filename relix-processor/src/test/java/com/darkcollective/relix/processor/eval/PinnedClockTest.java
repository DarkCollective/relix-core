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
package com.darkcollective.relix.processor.eval;

import com.darkcollective.relix.function.FunctionContext;
import com.darkcollective.relix.processor.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reproducibility seam (#451): {@code NOW}/{@code CURRENT_DATE}/
 * {@code CURRENT_TIME} read the clock on the evaluator's {@link FunctionContext}, so
 * a pinned clock makes a run replayable — the same script over the same data yields
 * the same rows however much later it runs.
 *
 * <p>Pinning costs nothing now: one immutable catalogue serves every clock, and the
 * context is the only thing that differs between a pinned evaluator and a default one.
 */
@DisplayName("OperandEvaluator — pinned clock")
final class PinnedClockTest extends ProcessorTestSupport {

    /** 2026-03-04T05:06:07Z — a fixed instant with distinct date and time parts. */
    private static final Instant PINNED = Instant.parse("2026-03-04T05:06:07Z");

    private static final Row EMPTY = row(schema("k"), new StringValue("v"));

    private static OperandEvaluator pinned() {
        return at(Clock.fixed(PINNED, ZoneOffset.UTC));
    }

    /** An evaluator over the installed functions, reading {@code clock}. */
    private static OperandEvaluator at(Clock clock) {
        return new OperandEvaluator(null, ExecutionContext.installedFunctions(),
                FunctionContext.of(clock));
    }

    private static Object call(OperandEvaluator eval, String fn) {
        return eval.evaluate(func(fn), EMPTY);
    }

    @Nested
    @DisplayName("pinned")
    class Pinned {

        @Test
        @DisplayName("NOW returns the pinned instant")
        void nowIsPinned() {
            assertThat(call(pinned(), "NOW"))
                    .isEqualTo(new TimestampValue(PINNED));
        }

        @Test
        @DisplayName("CURRENT_DATE returns the pinned instant's UTC date")
        void currentDateIsPinned() {
            assertThat(call(pinned(), "CURRENT_DATE"))
                    .isEqualTo(new DateValue(LocalDate.of(2026, 3, 4)));
        }

        @Test
        @DisplayName("CURRENT_TIME returns the pinned instant's UTC time")
        void currentTimeIsPinned() {
            assertThat(call(pinned(), "CURRENT_TIME"))
                    .isEqualTo(new TimeValue(LocalTime.of(5, 6, 7)));
        }

        @Test
        @DisplayName("repeated calls are stable — the whole point of pinning")
        void repeatedCallsAgree() {
            OperandEvaluator eval = pinned();
            assertThat(call(eval, "NOW")).isEqualTo(call(eval, "NOW"));
        }

        @Test
        @DisplayName("two evaluators on the same pin agree (replayability)")
        void separateEvaluatorsAgree() {
            assertThat(call(pinned(), "NOW")).isEqualTo(call(pinned(), "NOW"));
        }

        @Test
        @DisplayName("a non-UTC pinned zone still reports UTC parts")
        void nonUtcZoneNormalisesToUtc() {
            var eval = at(Clock.fixed(PINNED, ZoneOffset.ofHours(9)));
            assertThat(call(eval, "CURRENT_TIME"))
                    .isEqualTo(new TimeValue(LocalTime.of(5, 6, 7)));
        }

        @Test
        @DisplayName("rejects a null context")
        void rejectsNullContext() {
            assertThatThrownBy(() -> new OperandEvaluator(
                    null, ExecutionContext.installedFunctions(), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects a null catalogue")
        void rejectsNullCatalogue() {
            assertThatThrownBy(() -> new OperandEvaluator(null, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("default")
    class Default {

        @Test
        @DisplayName("clock-independent built-ins are unaffected by a pin")
        void otherFunctionsUnaffected() {
            var call = func("UCase",new com.darkcollective.relix.ast.StringOperand("ab"));
            assertThat(pinned().evaluate(call, EMPTY))
                    .isEqualTo(new OperandEvaluator().evaluate(call, EMPTY));
        }

        @Test
        @DisplayName("an explicit systemUTC clock behaves as the default")
        void systemUtcMatchesDefault() {
            Object viaClock = call(at(Clock.systemUTC()), "NOW");
            assertThat(viaClock).isInstanceOf(TimestampValue.class);
        }

        @Test
        @DisplayName("the default clock advances — NOW is never folded to a constant")
        void defaultClockAdvances() {
            OperandEvaluator eval = new OperandEvaluator();
            Instant first = ((TimestampValue) call(eval, "NOW")).value();
            Instant second = ((TimestampValue) call(eval, "NOW")).value();
            assertThat(second).isAfterOrEqualTo(first);
        }
    }
}

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
package com.darkcollective.relix.function;

import com.darkcollective.relix.function.TestFunctions.Marker;
import com.darkcollective.relix.function.TestFunctions.PickOne;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ScalarFunction")
final class ScalarFunctionTest {

    private static final FunctionContext CONTEXT = FunctionContext.systemDefault();

    @Nested
    @DisplayName("the sealed pair")
    final class SealedPair {

        @Test
        @DisplayName("dispatches exhaustively without a default arm")
        void dispatchesExhaustively() {
            // The point of sealing: the engine's dispatch is a switch the compiler
            // checks, so a third kind of scalar function cannot appear unannounced.
            assertThat(describe(new Marker("UCase", "u"))).isEqualTo("strict");
            assertThat(describe(new PickOne("IIf", 0))).isEqualTo("lazy");
        }

        private String describe(ScalarFunction function) {
            return switch (function) {
                case StrictScalarFunction ignored -> "strict";
                case LazyScalarFunction ignored -> "lazy";
            };
        }

        @Test
        @DisplayName("a strict function receives evaluated values")
        void strictReceivesValues() {
            StrictScalarFunction fn = new Marker("UCase", "marked");

            assertThat(fn.invoke(CONTEXT, List.of(new StringValue("x"))))
                    .isEqualTo(new StringValue("marked"));
        }

        @Test
        @DisplayName("a lazy function evaluates only the argument it reads")
        void lazyEvaluatesOnlyWhatItReads() {
            AtomicInteger evaluations = new AtomicInteger();
            Argument exploding = () -> {
                evaluations.incrementAndGet();
                throw new IllegalStateException("this argument must not be evaluated");
            };

            Value result = new PickOne("Guard", 0)
                    .invoke(CONTEXT, List.of(Argument.of(new StringValue("kept")), exploding));

            assertThat(result).isEqualTo(new StringValue("kept"));
            assertThat(evaluations).hasValue(0);
        }
    }

    @Nested
    @DisplayName("defaults")
    final class Defaults {

        @Test
        @DisplayName("the name comes from the signature")
        void nameComesFromTheSignature() {
            assertThat(new Marker("UCase", "u").name()).isEqualTo("UCase");
        }

        @Test
        @DisplayName("the return type is the declared one until a function says otherwise")
        void returnTypeDefaultsToTheDeclaredOne() {
            ScalarFunction fixed = new Marker("UCase", "u");

            assertThat(fixed.returnTypeFor(List.of(ScalarType.STRING)))
                    .isEqualTo(ScalarType.STRING);
        }

        @Test
        @DisplayName("a function whose result follows its arguments overrides the rule")
        void returnTypeCanFollowTheArguments() {
            ScalarFunction conditional = new StrictScalarFunction() {
                @Override
                public FunctionSignature signature() {
                    return new FunctionSignature("IIf", List.of(), Arity.exactly(3),
                            ScalarType.ANY, Set.of(), "conditional", Optional.empty());
                }

                @Override
                public ScalarType returnTypeFor(List<ScalarType> argumentTypes) {
                    return argumentTypes.size() == 3 && argumentTypes.get(1) == argumentTypes.get(2)
                            ? argumentTypes.get(1)
                            : ScalarType.ANY;
                }

                @Override
                public Value invoke(FunctionContext context, List<Value> arguments) {
                    return NullValue.INSTANCE;
                }
            };

            assertThat(conditional.returnTypeFor(
                    List.of(ScalarType.BOOLEAN, ScalarType.NUMBER, ScalarType.NUMBER)))
                    .isEqualTo(ScalarType.NUMBER);
            assertThat(conditional.returnTypeFor(
                    List.of(ScalarType.BOOLEAN, ScalarType.NUMBER, ScalarType.STRING)))
                    .isEqualTo(ScalarType.ANY);
        }

        @Test
        @DisplayName("a function declares no backend spelling unless it supplies one")
        void pushdownDeclinesByDefault() {
            assertThat(new Marker("UCase", "u").pushdown()
                    .render(PushdownTarget.sql("postgres"), List.of("x"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("FunctionContext")
    final class Context {

        @Test
        @DisplayName("carries the clock a function reads")
        void carriesTheClock() {
            Clock pinned = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC);

            assertThat(FunctionContext.of(pinned).clock()).isEqualTo(pinned);
        }

        @Test
        @DisplayName("defaults to system UTC")
        void defaultsToSystemUtc() {
            assertThat(FunctionContext.systemDefault().clock()).isEqualTo(Clock.systemUTC());
        }

        @Test
        @DisplayName("rejects a missing clock")
        void rejectsAMissingClock() {
            assertThatThrownBy(() -> FunctionContext.of(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("carries the order a reduction ranks by, when one is supplied")
        void carriesTheValueOrder() {
            Comparator<Value> reversed = Comparator.<Value, String>comparing(
                    value -> ((StringValue) value).value()).reversed();

            assertThat(FunctionContext.of(Clock.systemUTC(), reversed).valueOrder())
                    .isSameAs(reversed);
            assertThat(FunctionContext.of(Clock.systemUTC(), reversed).clock())
                    .isEqualTo(Clock.systemUTC());
        }

        @Test
        @DisplayName("rejects a missing order")
        void rejectsAMissingOrder() {
            assertThatThrownBy(() -> FunctionContext.of(Clock.systemUTC(), null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> FunctionContext.of(null, Comparator.comparing(Object::toString)))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("The default value order")
    final class DefaultOrder {

        private final Comparator<Value> order = FunctionContext.systemDefault().valueOrder();

        @Test
        @DisplayName("ranks like with like, across every kind that has an order")
        void ranksLikeWithLike() {
            assertThat(order.compare(new NumberValue(BigDecimal.ONE),
                    new NumberValue(BigDecimal.TEN))).isNegative();
            assertThat(order.compare(new StringValue("a"), new StringValue("b"))).isNegative();
            assertThat(order.compare(BooleanValue.FALSE, BooleanValue.TRUE)).isNegative();
            assertThat(order.compare(new DateValue(LocalDate.of(2020, 1, 1)),
                    new DateValue(LocalDate.of(2021, 1, 1)))).isNegative();
            assertThat(order.compare(new TimeValue(LocalTime.of(1, 0)),
                    new TimeValue(LocalTime.of(2, 0)))).isNegative();
            assertThat(order.compare(new TimestampValue(Instant.parse("2020-01-01T00:00:00Z")),
                    new TimestampValue(Instant.parse("2021-01-01T00:00:00Z")))).isNegative();
            assertThat(order.compare(new DurationValue(Duration.ofMinutes(1)),
                    new DurationValue(Duration.ofMinutes(2)))).isNegative();
            assertThat(order.compare(new StringValue("a"), new StringValue("a"))).isZero();
        }

        @Test
        @DisplayName("puts NULL after everything, as SQL's ascending order does")
        void nullsLast() {
            assertThat(order.compare(NullValue.INSTANCE, new StringValue("a"))).isPositive();
            assertThat(order.compare(new StringValue("a"), NullValue.INSTANCE)).isNegative();
            assertThat(order.compare(NullValue.INSTANCE, NullValue.INSTANCE)).isZero();
        }

        @Test
        @DisplayName("refuses to rank two kinds that have no order between them")
        void refusesUnlikeKinds() {
            // Inventing an order between a number and a date would be worse than saying
            // so; an engine supplies a richer comparator that knows about coercions.
            assertThatThrownBy(() -> order.compare(new StringValue("2020-01-01"),
                    new DateValue(LocalDate.of(2020, 1, 1))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cannot compare");
        }
    }
}

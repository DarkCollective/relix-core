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

import com.darkcollective.relix.function.TestFunctions.Counter;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AggregateFunction")
final class AggregateFunctionTest {

    private static final FunctionContext CONTEXT = FunctionContext.systemDefault();

    @Test
    @DisplayName("gives each group its own accumulator")
    void oneAccumulatorPerGroup() {
        Counter counter = new Counter("Tally");
        Accumulator first = counter.accumulator(CONTEXT);
        Accumulator second = counter.accumulator(CONTEXT);

        first.accumulate(List.of(new StringValue("a")));
        first.accumulate(List.of(new StringValue("b")));
        second.accumulate(List.of(new StringValue("c")));

        assertThat(first.finish()).isEqualTo(new NumberValue(BigDecimal.valueOf(2)));
        assertThat(second.finish()).isEqualTo(new NumberValue(BigDecimal.valueOf(1)));
    }

    @Test
    @DisplayName("an accumulator that saw no rows still answers")
    void anEmptyGroupStillAnswers() {
        assertThat(new Counter("Tally").accumulator(CONTEXT).finish())
                .isEqualTo(new NumberValue(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("takes a list per row, so a two-argument reduction needs nothing extra")
    void takesAListPerRow() {
        // ARGMAX-shaped: rank first, the value to yield second.
        AggregateFunction argmax = new AggregateFunction() {
            @Override
            public AggregateSignature signature() {
                return AggregateSignature.of("PickTop", ScalarType.ANY, java.util.Set.of());
            }

            @Override
            public Accumulator accumulator(FunctionContext context) {
                return new Accumulator() {
                    private BigDecimal best;
                    private Value yielded = com.darkcollective.relix.value.NullValue.INSTANCE;

                    @Override
                    public void accumulate(List<Value> arguments) {
                        BigDecimal rank = ((NumberValue) arguments.get(0)).value();
                        if (best == null || rank.compareTo(best) > 0) {
                            best = rank;
                            yielded = arguments.get(1);
                        }
                    }

                    @Override
                    public Value finish() {
                        return yielded;
                    }
                };
            }
        };

        Accumulator accumulator = argmax.accumulator(CONTEXT);
        accumulator.accumulate(List.of(new NumberValue(BigDecimal.ONE), new StringValue("low")));
        accumulator.accumulate(List.of(new NumberValue(BigDecimal.TEN), new StringValue("high")));

        assertThat(accumulator.finish()).isEqualTo(new StringValue("high"));
    }

    @Test
    @DisplayName("merging is declined unless the aggregate implements it")
    void mergeDeclinesByDefault() {
        Accumulator accumulator = new Counter("Tally").accumulator(CONTEXT);

        assertThatThrownBy(() -> accumulator.merge(accumulator))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("cannot be merged");
    }

    @Test
    @DisplayName("an aggregate that can be computed in parts implements merge")
    void mergeCombinesPartialStates() {
        List<Value> seen = new ArrayList<>();
        class Gather implements Accumulator {
            private final List<Value> values = new ArrayList<>();

            @Override
            public void accumulate(List<Value> arguments) {
                values.add(arguments.get(0));
            }

            @Override
            public void merge(Accumulator other) {
                values.addAll(((Gather) other).values);
            }

            @Override
            public Value finish() {
                seen.addAll(values);
                return new NumberValue(BigDecimal.valueOf(values.size()));
            }
        }

        Gather left = new Gather();
        Gather right = new Gather();
        left.accumulate(List.of(new StringValue("a")));
        right.accumulate(List.of(new StringValue("b")));
        left.merge(right);

        assertThat(left.finish()).isEqualTo(new NumberValue(BigDecimal.valueOf(2)));
        assertThat(seen).containsExactly(new StringValue("a"), new StringValue("b"));
    }

    @Test
    @DisplayName("defaults its name, return type and backend spelling from the signature")
    void defaultsComeFromTheSignature() {
        Counter counter = new Counter("Tally");

        assertThat(counter.name()).isEqualTo("Tally");
        assertThat(counter.returnTypeFor(List.of(ScalarType.STRING))).isEqualTo(ScalarType.NUMBER);
        assertThat(counter.pushdown().render(PushdownTarget.mongo(), List.of("$x"))).isEmpty();
    }
}

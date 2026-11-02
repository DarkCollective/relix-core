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
package com.darkcollective.relix.function.builtin;

import com.darkcollective.relix.function.Argument;
import com.darkcollective.relix.function.LazyScalarFunction;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.function.StrictScalarFunction;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.value.ZonedTimestampValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.darkcollective.relix.function.builtin.BuiltinCalls.CONTEXT;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.PINNED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A type a built-in's {@link ScalarFunction#argumentTypes(int)} leaves out is one the
 * function refuses when it runs.
 *
 * <p>Analysis refuses a call whose argument has a type the function does not accept, so
 * an accepted set narrower than what the function really takes would refuse a script
 * that runs. This calls every built-in with a value of each type its parameters leave
 * out, the other arguments drawn from values of the types they accept, and fails on any
 * call that returns a value.
 */
@DisplayName("a type a built-in does not accept fails when it runs")
final class ArgumentTypesTest {

    /** Values of each type; a string that reads as a date and as a unit, to give a call every chance. */
    private static final Map<ScalarType, List<Value>> SAMPLES = new EnumMap<>(Map.of(
            ScalarType.STRING, List.of(new StringValue("day"), new StringValue("2026-03-02"),
                    new StringValue("2026-03-02T10:15:00Z"), new StringValue("1"), new StringValue("a")),
            ScalarType.NUMBER, List.of(new NumberValue(BigDecimal.ONE), new NumberValue(BigDecimal.TEN)),
            ScalarType.BOOLEAN, List.of(BooleanValue.of(true)),
            ScalarType.DATE, List.of(new DateValue(LocalDate.of(2026, 3, 2))),
            ScalarType.TIME, List.of(new TimeValue(LocalTime.of(10, 15))),
            ScalarType.TIMESTAMP, List.of(new TimestampValue(PINNED)),
            ScalarType.DURATION, List.of(new DurationValue(Duration.ofDays(1))),
            ScalarType.TIMESTAMPTZ, List.of(new ZonedTimestampValue(
                    OffsetDateTime.of(2026, 3, 2, 10, 15, 0, 0, ZoneOffset.ofHours(2))))));

    /** Combinations tried per refused argument, so a function of many ANY parameters stays quick. */
    private static final int MAX_CALLS = 2000;

    @Test
    @DisplayName("no call with an argument of a type left out returns a value")
    void refusedTypesFail() {
        List<String> accepted = new ArrayList<>();
        for (ScalarFunction fn : BuiltinCalls.all()) {
            var arity = fn.signature().arity();
            int count = Math.max(arity.min(), fn.signature().parameters().size());
            if (!arity.accepts(count)) {
                count = arity.min();
            }
            for (int index = 0; index < count; index++) {
                Set<ScalarType> types = fn.argumentTypes(index);
                for (ScalarType refused : SAMPLES.keySet()) {
                    if (types.contains(refused)) {
                        continue;
                    }
                    for (Value value : SAMPLES.get(refused)) {
                        List<Value> success = succeeding(fn, count, index, value);
                        if (success != null) {
                            accepted.add(fn.name() + success + ": argument " + (index + 1)
                                    + " accepts " + refused);
                        }
                    }
                }
            }
        }
        assertThat(accepted)
                .as("built-ins whose argumentTypes leave out a type they accept at run time;"
                        + " analysis would refuse a call that runs")
                .isEmpty();
    }

    @Test
    @DisplayName("a function given narrower types is otherwise the function it was")
    void narrowedFunctionsDelegate() {
        ScalarFunction year = BuiltinCalls.function("YEAR");
        assertThat(year.argumentTypes(0))
                .containsExactlyInAnyOrder(ScalarType.DATE, ScalarType.TIMESTAMP, ScalarType.TIMESTAMPTZ);
        assertThat(year.signature().parameters().get(0).type()).isEqualTo(ScalarType.ANY);
        assertThat(year.blocking(com.darkcollective.relix.function.Blocking.Bound.AT_LEAST, BigDecimal.ONE))
                .isEmpty();
        assertThat(BuiltinCalls.function("DATE_TRUNC").argumentTypes(0)).containsExactly(ScalarType.STRING);
    }

    /** The arguments of a call with {@code value} at {@code index} that returns a value, or null. */
    private static List<Value> succeeding(ScalarFunction fn, int count, int index, Value value) {
        List<List<Value>> choices = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (i == index) {
                choices.add(List.of(value));
                continue;
            }
            List<Value> options = new ArrayList<>();
            for (ScalarType type : fn.argumentTypes(i)) {
                options.addAll(SAMPLES.getOrDefault(type, List.of()));
            }
            choices.add(options);
        }
        int[] at = new int[count];
        for (int calls = 0; calls < MAX_CALLS; calls++) {
            List<Value> arguments = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                if (choices.get(i).isEmpty()) {
                    return null;
                }
                arguments.add(choices.get(i).get(at[i]));
            }
            if (returns(fn, arguments)) {
                return arguments;
            }
            int i = 0;
            while (i < count && ++at[i] == choices.get(i).size()) {
                at[i++] = 0;
            }
            if (i == count) {
                return null;
            }
        }
        return null;
    }

    private static boolean returns(ScalarFunction fn, List<Value> arguments) {
        try {
            Value result = switch (fn) {
                case StrictScalarFunction strict -> strict.invoke(CONTEXT, arguments);
                case LazyScalarFunction lazy -> lazy.invoke(CONTEXT,
                        arguments.stream().map(Argument::of).toList());
            };
            return result != null;
        } catch (RuntimeException e) {
            return false;
        }
    }
}

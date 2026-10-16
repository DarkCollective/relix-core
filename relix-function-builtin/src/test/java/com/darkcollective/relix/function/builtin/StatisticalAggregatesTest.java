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

import com.darkcollective.relix.function.Accumulator;
import com.darkcollective.relix.function.AggregateFunction;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The statistical aggregates' accumulators, through the library's own SPI (#127). */
@DisplayName("The statistical aggregates")
final class StatisticalAggregatesTest {

    private static Accumulator accumulator(String name) {
        AggregateFunction fn = new BuiltinFunctionLibrary().aggregateFunctions().stream()
                .filter(f -> f.signature().name().equals(name)).findFirst().orElseThrow();
        return fn.accumulator(BuiltinCalls.CONTEXT);
    }

    private static Value n(String x) {
        return new NumberValue(new BigDecimal(x));
    }

    @Test
    @DisplayName("a variance computed in two parts and merged is the variance of the whole")
    void varianceMerges() {
        Accumulator left = accumulator("VAR_SAMP");
        Accumulator right = accumulator("VAR_SAMP");
        left.accumulate(List.of(n("1")));
        left.accumulate(List.of(n("2")));
        right.accumulate(List.of(n("3")));
        right.accumulate(List.of(n("4")));
        left.merge(right);
        assertThat(((NumberValue) left.finish()).value()).isEqualByComparingTo("1.6666666667");
    }

    @Test
    @DisplayName("a percentile's fraction must lie in [0, 1] and stay the same from row to row")
    void fraction() {
        Accumulator negative = accumulator("PERCENTILE_CONT");
        assertThatThrownBy(() -> negative.accumulate(List.of(n("1"), n("-0.1"))))
                .hasMessage("PERCENTILE_CONT: percentile value -0.1 is not between 0 and 1");
        Accumulator changing = accumulator("PERCENTILE_DISC");
        changing.accumulate(List.of(n("1"), n("0.5")));
        changing.accumulate(List.of(n("2"), n("0.50")));
        assertThatThrownBy(() -> changing.accumulate(List.of(n("3"), n("0.6"))))
                .hasMessage("PERCENTILE_DISC: the fraction must be the same for every row of a group");
    }

    @Test
    @DisplayName("a percentile cannot be merged, which is what keeps it from being computed in parts")
    void percentileIsNotDecomposable() {
        Accumulator a = accumulator("MEDIAN");
        assertThatThrownBy(() -> a.merge(accumulator("MEDIAN"))).isInstanceOf(UnsupportedOperationException.class);
    }
}

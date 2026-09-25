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
package com.darkcollective.relix.semantic.internal;

import com.darkcollective.relix.ast.ConsolidationFunction;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.StructType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Tests for the one rule schema inference and the executor both read. */
@DisplayName("DownsampleColumns — which columns a DOWNSAMPLE consolidates")
final class DownsampleColumnsTest {

    private static final Schema METRICS = new Schema(List.of(
            new ColumnDefinition("ts",      ScalarType.TIMESTAMP),
            new ColumnDefinition("host",    ScalarType.STRING),
            new ColumnDefinition("started", ScalarType.TIMESTAMP),
            new ColumnDefinition("cpu",     ScalarType.NUMBER)));

    private static List<DownsampleColumns.Consolidation> of(ConsolidationFunction fn,
                                                            List<String> keys) {
        return DownsampleColumns.of(fn, keys, "ts", METRICS);
    }

    private static List<String> outputNames(List<DownsampleColumns.Consolidation> cs) {
        return cs.stream().map(c -> c.output().name()).toList();
    }

    @Nested
    @DisplayName("Eligibility")
    class Eligibility {

        @Test
        @DisplayName("AVG and SUM take the numeric columns only")
        void arithmeticIsNumericOnly() {
            assertThat(outputNames(of(ConsolidationFunction.AVG, List.of())))
                    .containsExactly("avg_cpu");
            assertThat(outputNames(of(ConsolidationFunction.SUM, List.of())))
                    .containsExactly("sum_cpu");
        }

        @Test
        @DisplayName("MIN and MAX take every scalar column, in input order")
        void extremaTakeEveryScalarColumn() {
            assertThat(outputNames(of(ConsolidationFunction.MIN, List.of())))
                    .containsExactly("min_host", "min_started", "min_cpu");
            assertThat(outputNames(of(ConsolidationFunction.MAX, List.of())))
                    .containsExactly("max_host", "max_started", "max_cpu");
        }

        @Test
        @DisplayName("A nested column is consolidated by nothing")
        void nestedColumnsAreNeverEligible() {
            Schema nested = new Schema(List.of(
                    new ColumnDefinition("ts",   ScalarType.TIMESTAMP),
                    new ColumnDefinition("tags", array(ScalarType.STRING)),
                    new ColumnDefinition("meta", struct(
                            new StructType.Field("k", ScalarType.STRING)))));
            for (ConsolidationFunction fn : ConsolidationFunction.values()) {
                if (fn.countsRows()) {
                    continue;
                }
                assertThat(DownsampleColumns.of(fn, List.of(), "ts", nested)).isEmpty();
            }
        }

        @Test
        @DisplayName("The timestamp column and the grouping keys are never consolidated")
        void keysAndTimestampAreExcluded() {
            assertThat(outputNames(of(ConsolidationFunction.MIN, List.of("host"))))
                    .containsExactly("min_started", "min_cpu");
        }
    }

    @Nested
    @DisplayName("Output columns")
    class OutputColumns {

        @Test
        @DisplayName("MIN/MAX keep the input column's type; AVG/SUM always produce NUMBER")
        void producedTypes() {
            assertThat(of(ConsolidationFunction.MAX, List.of()).getFirst().output())
                    .isEqualTo(new ColumnDefinition("max_host", ScalarType.STRING));
            assertThat(of(ConsolidationFunction.AVG, List.of()).getFirst().output())
                    .isEqualTo(new ColumnDefinition("avg_cpu", ScalarType.NUMBER));
        }

        @Test
        @DisplayName("COUNT yields one count column reducing no input column")
        void countReducesTheRowsThemselves() {
            List<DownsampleColumns.Consolidation> counted =
                    of(ConsolidationFunction.COUNT, List.of("host"));
            assertThat(counted).hasSize(1);
            assertThat(counted.getFirst().inputColumn()).isEmpty();
            assertThat(counted.getFirst().output())
                    .isEqualTo(new ColumnDefinition("count", ScalarType.NUMBER));
        }

        @Test
        @DisplayName("Each consolidation names the input column it reduces")
        void consolidationsNameTheirInput() {
            assertThat(of(ConsolidationFunction.SUM, List.of()).getFirst().inputColumn())
                    .isEqualTo(Optional.of("cpu"));
        }
    }
}

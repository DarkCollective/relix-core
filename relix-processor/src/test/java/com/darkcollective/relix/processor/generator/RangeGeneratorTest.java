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
package com.darkcollective.relix.processor.generator;

import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Unit tests for the {@link RangeGenerator}. */
final class RangeGeneratorTest {

    private final RangeGenerator gen = new RangeGenerator();

    private List<String> values(Map<String, String> args) {
        Schema schema = gen.schema(args);
        try (Stream<Row> rows = gen.rows(args, schema)) {
            return rows.map(r -> r.get("n").asDisplayString()).toList();
        }
    }

    @Test
    void schemaIsSingleNumberColumnNamedN() {
        assertThat(gen.schema(Map.of()).columns()).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("n");
            assertThat(c.type()).isEqualTo(ScalarType.NUMBER);
        });
    }

    @Test
    void producesInclusiveRange() {
        assertThat(values(Map.of("lo", "1", "hi", "5"))).containsExactly("1", "2", "3", "4", "5");
    }

    @Test
    void honoursStep() {
        assertThat(values(Map.of("lo", "1", "hi", "10", "step", "2")))
                .containsExactly("1", "3", "5", "7", "9");
    }

    @Test
    void emptyWhenLoGreaterThanHi() {
        assertThat(values(Map.of("lo", "5", "hi", "1"))).isEmpty();
    }

    @Test
    void singletonWhenLoEqualsHi() {
        assertThat(values(Map.of("lo", "7", "hi", "7"))).containsExactly("7");
    }

    @Test
    void rejectsMissingBound() {
        Schema schema = gen.schema(Map.of("lo", "1"));
        assertThatExceptionOfType(EvaluationException.class)
                .isThrownBy(() -> gen.rows(Map.of("lo", "1"), schema))
                .withMessageContaining("'hi'");
    }

    @Test
    void rejectsNonNumericBound() {
        Schema schema = gen.schema(Map.of("lo", "x", "hi", "5"));
        assertThatExceptionOfType(EvaluationException.class)
                .isThrownBy(() -> gen.rows(Map.of("lo", "x", "hi", "5"), schema))
                .withMessageContaining("integer");
    }

    @Test
    void rejectsNonPositiveStep() {
        Schema schema = gen.schema(Map.of("lo", "1", "hi", "5", "step", "0"));
        assertThatExceptionOfType(EvaluationException.class)
                .isThrownBy(() -> gen.rows(Map.of("lo", "1", "hi", "5", "step", "0"), schema))
                .withMessageContaining("step");
    }

    // ─── cost + properties (ADR-0008) ─────────────────────────────────────────

    @Test
    void cardinalityIsExact() {
        assertThat(gen.cardinality(Map.of("lo", "1", "hi", "5"))).hasValue(5);
        assertThat(gen.cardinality(Map.of("lo", "1", "hi", "10", "step", "2"))).hasValue(5);
        assertThat(gen.cardinality(Map.of("lo", "7", "hi", "7"))).hasValue(1);
        assertThat(gen.cardinality(Map.of("lo", "5", "hi", "1"))).hasValue(0);
    }

    @Test
    void cardinalityEmptyForInvalidArgs() {
        assertThat(gen.cardinality(Map.of("lo", "1"))).isEmpty();              // missing hi
        assertThat(gen.cardinality(Map.of("lo", "x", "hi", "5"))).isEmpty();   // non-numeric
        assertThat(gen.cardinality(Map.of("lo", "1", "hi", "5", "step", "0"))).isEmpty();
    }

    @Test
    void isFiniteAndDuplicateFree() {
        assertThat(gen.unbounded()).isFalse();
        assertThat(gen.duplicateFree()).isTrue();
    }
}

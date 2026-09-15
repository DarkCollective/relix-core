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
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the {@link PrimesGenerator}. */
final class PrimesGeneratorTest {

    private final PrimesGenerator gen = new PrimesGenerator();

    /** Takes the first {@code n} primes without draining the infinite stream. */
    private List<String> firstValues(int n) {
        Schema schema = gen.schema(Map.of());
        try (Stream<Row> rows = gen.rows(Map.of(), schema)) {
            return rows.limit(n).map(r -> r.get("n").asDisplayString()).toList();
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
    void enumeratesPrimesInAscendingOrder() {
        assertThat(firstValues(8)).containsExactly("2", "3", "5", "7", "11", "13", "17", "19");
    }

    @Test
    void streamIsLazyAndKeepsProducing() {
        // The 25th prime is 97; proves the stream produces well beyond the first few.
        Schema schema = gen.schema(Map.of());
        try (Stream<Row> rows = gen.rows(Map.of(), schema)) {
            assertThat(rows.skip(24).findFirst()).get()
                    .extracting(r -> r.get("n").asDisplayString()).isEqualTo("97");
        }
    }

    @Test
    void isUnboundedAndDuplicateFree() {
        assertThat(gen.unbounded()).isTrue();
        assertThat(gen.duplicateFree()).isTrue();
    }

    /**
     * The property that makes an endless generator usable at all: because {@code n}
     * ascends, an upper bound on it is a stop condition, so {@code GEN-001} can push
     * {@code σ n < k} into the generator instead of filtering a stream that never ends.
     * Declaring it wrongly would not fail here — it would hang a query.
     */
    @Test
    void ascendsInN() {
        assertThat(gen.ascendingColumn()).contains("n");
    }
}

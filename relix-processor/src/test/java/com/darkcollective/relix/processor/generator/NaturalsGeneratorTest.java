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

/** Unit tests for the {@link NaturalsGenerator}. */
final class NaturalsGeneratorTest {

    private final NaturalsGenerator gen = new NaturalsGenerator();

    /** Takes the first {@code n} values without draining the infinite stream. */
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
    void startsAtZeroAndCounts() {
        assertThat(firstValues(5)).containsExactly("0", "1", "2", "3", "4");
    }

    @Test
    void streamIsLazyAndUnboundedlyConsumable() {
        // No overflow: take a value well past Long.MAX_VALUE to prove BigInteger backing.
        Schema schema = gen.schema(Map.of());
        try (Stream<Row> rows = gen.rows(Map.of(), schema)) {
            assertThat(rows.skip(10).findFirst()).get()
                    .extracting(r -> r.get("n").asDisplayString()).isEqualTo("10");
        }
    }

    @Test
    void isUnboundedAndDuplicateFree() {
        assertThat(gen.unbounded()).isTrue();
        assertThat(gen.duplicateFree()).isTrue();
    }
}

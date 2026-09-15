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

import com.darkcollective.relix.cost.Boundedness;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link GeneratorRegistry} lookup and the {@code GeneratorCatalog} adapter. */
final class GeneratorRegistryTest {

    private final GeneratorRegistry registry = new GeneratorRegistry();

    @Test
    void findsRangeCaseInsensitively() {
        assertThat(registry.find("Range")).isPresent();
        assertThat(registry.find("range")).isPresent();
        assertThat(registry.find("RANGE")).get().extracting(Generator::name).isEqualTo("Range");
    }

    @Test
    void findsUnboundedGeneratorsCaseInsensitively() {
        assertThat(registry.find("naturals")).get().extracting(Generator::name).isEqualTo("Naturals");
        assertThat(registry.find("PRIMES")).get().extracting(Generator::name).isEqualTo("Primes");
    }

    @Test
    void unknownGeneratorIsEmpty() {
        assertThat(registry.find("Fibonacci")).isEmpty();
    }

    @Test
    void generatorCatalogResolvesKnownSchema() {
        assertThat(registry.generatorSchema("Range", Map.of("lo", "1", "hi", "9")))
                .get()
                .satisfies(schema -> assertThat(schema.columns()).singleElement()
                        .satisfies(c -> {
                            assertThat(c.name()).isEqualTo("n");
                            assertThat(c.type()).isEqualTo(ScalarType.NUMBER);
                        }));
    }

    @Test
    void generatorCatalogEmptyForUnknown() {
        assertThat(registry.generatorSchema("Nope", Map.of())).isEmpty();
    }

    @Test
    void generatorCardinalityFromRegistry() {
        assertThat(registry.generatorCardinality("Range", Map.of("lo", "1", "hi", "9"))).hasValue(9);
        assertThat(registry.generatorCardinality("range", Map.of("lo", "0", "hi", "0"))).hasValue(1);
        assertThat(registry.generatorCardinality("Nope", Map.of())).isEmpty();
    }

    /**
     * The three answers, and the third is the one that matters: a generator nobody has
     * registered is {@code UNKNOWN}, not {@code BOUNDED}. {@code relix.relations} reports
     * this verbatim, because a catalog stating a relation is finite when it has never
     * heard of the thing producing its rows is making the fact up.
     */
    @Test
    void generatorBoundednessFromRegistry() {
        assertThat(registry.generatorBoundedness("Naturals", Map.of()))
                .isEqualTo(Boundedness.UNBOUNDED);
        assertThat(registry.generatorBoundedness("range", Map.of("lo", "1", "hi", "9")))
                .isEqualTo(Boundedness.BOUNDED);
        assertThat(registry.generatorBoundedness("Nope", Map.of()))
                .isEqualTo(Boundedness.UNKNOWN);
    }

    /** A generator supplied by a program is found by both halves — lookup and catalog. */
    @Test
    void registerAddsAGeneratorToBothViews() {
        registry.register(new FixedGenerator("Feed"));

        assertThat(registry.find("feed")).isPresent();
        assertThat(registry.generatorSchema("FEED", Map.of()))
                .get()
                .satisfies(schema -> assertThat(schema.columns()).singleElement()
                        .satisfies(c -> assertThat(c.name()).isEqualTo("n")));
    }

    /**
     * The built-ins are a documented language surface, so a name already taken is refused
     * rather than replaced — a session that silently redefined {@code Range} would answer
     * a question about the natural numbers with something else.
     */
    @Test
    void registerRefusesANameAlreadyTaken() {
        assertThatThrownBy(() -> registry.register(new FixedGenerator("range")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("range");
    }

    /** A generator of one column and no rows — enough to be looked up and asked. */
    private record FixedGenerator(String name) implements Generator {

        @Override
        public Schema schema(Map<String, String> args) {
            return new Schema(List.of(new ColumnDefinition("n", ScalarType.NUMBER)));
        }

        @Override
        public Stream<Row> rows(Map<String, String> args, Schema schema) {
            return Stream.empty();
        }
    }
}

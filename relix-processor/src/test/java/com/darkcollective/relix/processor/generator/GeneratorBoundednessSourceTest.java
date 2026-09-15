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
import com.darkcollective.relix.lang.ast.source.GeneratorSourceConfig;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Tests for {@link GeneratorBoundednessSource}. */
final class GeneratorBoundednessSourceTest {

    /** A stand-in unbounded generator (the first real one, Naturals, arrives in #82). */
    private static final class StubUnbounded implements Generator {
        @Override public String name() { return "Stub"; }
        @Override public Schema schema(Map<String, String> args) {
            return new Schema(List.of(new ColumnDefinition("n", ScalarType.NUMBER)));
        }
        @Override public Stream<Row> rows(Map<String, String> args, Schema schema) { return Stream.empty(); }
        @Override public boolean unbounded() { return true; }
    }

    private final GeneratorRegistry registry =
            new GeneratorRegistry(List.of(new RangeGenerator(), new StubUnbounded()));

    private GeneratorBoundednessSource sourceFor(String relation, GeneratorSourceConfig config) {
        var decl = source(true, relation, config);
        return new GeneratorBoundednessSource(Map.of(relation.toLowerCase(), decl), registry);
    }

    @Test
    void unboundedGeneratorLeafIsUnbounded() {
        var bs = sourceFor("S", generatorSource("Stub", Map.of()));
        assertThat(bs.boundednessOf("S")).isEqualTo(Boundedness.UNBOUNDED);
    }

    @Test
    void finiteGeneratorLeafIsBounded() {
        var bs = sourceFor("R", generatorSource("Range", Map.of("lo", "1", "hi", "9")));
        assertThat(bs.boundednessOf("R")).isEqualTo(Boundedness.BOUNDED);
    }

    @Test
    void nonGeneratorAndUnknownLeavesAreBounded() {
        var bs = new GeneratorBoundednessSource(Map.of(), registry);
        assertThat(bs.boundednessOf("Orders")).isEqualTo(Boundedness.BOUNDED);
    }

    /**
     * A source naming a generator this registry does not hold reads as {@code BOUNDED}
     * here, though the registry itself answers {@code UNKNOWN}: what consumes this source
     * refuses a query over an unbounded input, so an unrecognised name must not cost a
     * legal query its plan. {@code relix.relations.boundedness} keeps the {@code UNKNOWN},
     * because reporting a fact and deciding a plan are not the same job.
     */
    @Test
    void anUnregisteredGeneratorIsBoundedHereAndUnknownToTheRegistry() {
        var config = generatorSource("Fibonacci", Map.of());
        assertThat(sourceFor("F", config).boundednessOf("F")).isEqualTo(Boundedness.BOUNDED);
        assertThat(registry.generatorBoundedness("Fibonacci", Map.of()))
                .isEqualTo(Boundedness.UNKNOWN);
    }
}

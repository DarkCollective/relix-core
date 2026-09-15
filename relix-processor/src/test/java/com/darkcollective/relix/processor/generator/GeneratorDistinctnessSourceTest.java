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

/** Tests for {@link GeneratorDistinctnessSource}. */
final class GeneratorDistinctnessSourceTest {

    /** A generator that is NOT duplicate-free (the SPI default). */
    private static final class StubNonDistinct implements Generator {
        @Override public String name() { return "Stub"; }
        @Override public Schema schema(Map<String, String> args) {
            return new Schema(List.of(new ColumnDefinition("n", ScalarType.NUMBER)));
        }
        @Override public Stream<Row> rows(Map<String, String> args, Schema schema) { return Stream.empty(); }
    }

    private final GeneratorRegistry registry =
            new GeneratorRegistry(List.of(new RangeGenerator(), new StubNonDistinct()));

    private GeneratorDistinctnessSource sourceFor(String relation, GeneratorSourceConfig config) {
        var decl = source(true, relation, config);
        return new GeneratorDistinctnessSource(Map.of(relation.toLowerCase(), decl), registry);
    }

    @Test
    void duplicateFreeGeneratorLeafIsDistinct() {
        var src = sourceFor("R", generatorSource("Range", Map.of("lo", "1", "hi", "9")));
        assertThat(src.duplicateFreeLeaf("R")).isTrue();
    }

    @Test
    void nonDistinctGeneratorLeafIsNot() {
        var src = sourceFor("S", generatorSource("Stub", Map.of()));
        assertThat(src.duplicateFreeLeaf("S")).isFalse();
    }

    @Test
    void nonGeneratorAndUnknownLeavesAreNot() {
        var src = new GeneratorDistinctnessSource(Map.of(), registry);
        assertThat(src.duplicateFreeLeaf("Orders")).isFalse();
    }
}

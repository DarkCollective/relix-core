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

import com.darkcollective.relix.lang.ast.source.SourceConfig;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.processor.generator.GeneratorRegistry;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.table.InMemorySymbolTable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Tests for {@link GeneratorDataSourceConnector} — serving generator-relation rows. */
final class GeneratorDataSourceConnectorTest {

    private static final Schema N = new Schema(List.of(new ColumnDefinition("n", ScalarType.NUMBER)));

    private static SemanticModel modelWith(String relation, SourceConfig config) {
        var src = source(true, relation, config);
        return new SemanticModel("default", new InMemorySymbolTable(),
                Map.of(relation.toLowerCase(), src), Map.of(),
                SchemaAnnotations.empty(), List.of());
    }

    @Test
    void readsRangeRows() {
        var model = modelWith("R", generatorSource("Range", Map.of("lo", "1", "hi", "4")));
        var connector = new GeneratorDataSourceConnector(model, new GeneratorRegistry());

        try (Stream<Row> rows = connector.open("R", N)) {
            assertThat(rows.map(r -> r.get("n").asDisplayString()).toList())
                    .containsExactly("1", "2", "3", "4");
        }
    }

    @Test
    void rejectsNonGeneratorSource() {
        var model = modelWith("C", connectionTable("db", "t"));
        var connector = new GeneratorDataSourceConnector(model, new GeneratorRegistry());

        assertThatExceptionOfType(EvaluationException.class)
                .isThrownBy(() -> connector.open("C", N))
                .withMessageContaining("not a generator source");
    }

    @Test
    void rejectsUnknownGenerator() {
        var model = modelWith("F", generatorSource("Fibonacci", Map.of()));
        var connector = new GeneratorDataSourceConnector(model, new GeneratorRegistry());

        assertThatExceptionOfType(EvaluationException.class)
                .isThrownBy(() -> connector.open("F", N))
                .withMessageContaining("Unknown generator 'Fibonacci'");
    }
}

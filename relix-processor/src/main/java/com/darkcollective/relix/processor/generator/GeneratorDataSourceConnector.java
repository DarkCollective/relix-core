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

import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.source.GeneratorSourceConfig;
import com.darkcollective.relix.processor.DataSourceConnector;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.processor.generator.Generator;
import com.darkcollective.relix.processor.generator.GeneratorRegistry;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.Schema;

import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * A {@link DataSourceConnector} that serves generator relations — leaves whose rows
 * are produced by engine code.
 *
 * <p>For a relation declared as {@code source R from generator { name: "Range", … }},
 * this resolves the relation's {@link GeneratorSourceConfig} (from
 * {@link SemanticModel#sources()}), looks up the named {@link Generator} in the
 * {@link GeneratorRegistry}, and returns its lazy {@code Stream<Row>}. Argument
 * validation is the generator's responsibility and surfaces here at open time.
 */
public final class GeneratorDataSourceConnector implements DataSourceConnector {

    private final SemanticModel model;
    private final GeneratorRegistry registry;

    /**
     * @param model    the semantic model whose {@code sources} declare each generator relation
     * @param registry the catalogue of built-in generators
     */
    public GeneratorDataSourceConnector(SemanticModel model, GeneratorRegistry registry) {
        this.model = Objects.requireNonNull(model, "model");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public Stream<Row> open(String relationName, Schema schema) {
        SourceDeclaration declaration = model.sources().get(relationName.toLowerCase(Locale.ROOT));
        if (declaration == null || !(declaration.config() instanceof GeneratorSourceConfig config)) {
            throw new EvaluationException(
                    "Relation '" + relationName + "' is not a generator source");
        }
        Generator generator = registry.find(config.generatorName()).orElseThrow(() ->
                new EvaluationException("Unknown generator '" + config.generatorName()
                        + "' for relation '" + relationName + "'"));
        return generator.rows(config.args(), schema);
    }
}

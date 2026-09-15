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

import com.darkcollective.relix.cost.MonotoneGeneratorSource;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.source.GeneratorSourceConfig;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@link MonotoneGeneratorSource} backed by a model's sources and the generator registry: a leaf
 * relation reports an ascending value column iff it is a generator
 * source whose generator declares one via {@link Generator#ascendingColumn()} (the unbounded
 * {@code Naturals}/{@code Primes}). Every other leaf returns {@link Optional#empty()}.
 *
 * <p>Lets {@code SelectionIntoGeneratorPass} apply {@code GEN-001} ({@code σ n < k} →
 * production stop) so an otherwise non-terminating scan over an unbounded generator becomes
 * finite. Mirrors {@link GeneratorDistinctnessSource} / {@link GeneratorBoundednessSource}.
 */
public final class GeneratorMonotonicitySource implements MonotoneGeneratorSource {

    private final Map<String, SourceDeclaration> sources;
    private final GeneratorRegistry registry;

    /**
     * @param sources  canonical (lower-cased) relation name → source declaration
     *                 (as in {@code SemanticModel.sources()}); must not be null
     * @param registry the generator registry; must not be null
     */
    public GeneratorMonotonicitySource(Map<String, SourceDeclaration> sources, GeneratorRegistry registry) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public Optional<String> ascendingColumn(String relationName) {
        SourceDeclaration declaration = sources.get(relationName.toLowerCase(Locale.ROOT));
        if (declaration != null && declaration.config() instanceof GeneratorSourceConfig generator) {
            return registry.find(generator.generatorName())
                    .flatMap(Generator::ascendingColumn);
        }
        return Optional.empty();
    }
}

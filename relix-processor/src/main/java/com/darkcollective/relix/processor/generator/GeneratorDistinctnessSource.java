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

import com.darkcollective.relix.cost.DistinctnessSource;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.source.GeneratorSourceConfig;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link DistinctnessSource} backed by a model's sources and the generator registry: a leaf
 * relation is duplicate-free iff it is a generator source whose
 * generator declares {@link Generator#duplicateFree()} (e.g. {@code Range}). Every
 * other leaf is conservatively not known distinct.
 *
 * <p>Lets {@code DistinctEliminationPass} apply {@code DIST-001} (`δ(R) → R`) over an
 * inherently-distinct generator — which, for an unbounded one, also dissolves the
 * {@code δ} unbounded-retained-state hazard.
 */
public final class GeneratorDistinctnessSource implements DistinctnessSource {

    private final Map<String, SourceDeclaration> sources;
    private final GeneratorRegistry registry;

    /**
     * @param sources  canonical (lower-cased) relation name → source declaration
     *                 (as in {@code SemanticModel.sources()}); must not be null
     * @param registry the generator registry; must not be null
     */
    public GeneratorDistinctnessSource(Map<String, SourceDeclaration> sources, GeneratorRegistry registry) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public boolean duplicateFreeLeaf(String relationName) {
        SourceDeclaration declaration = sources.get(relationName.toLowerCase(Locale.ROOT));
        return declaration != null
                && declaration.config() instanceof GeneratorSourceConfig generator
                && registry.find(generator.generatorName())
                        .map(Generator::duplicateFree).orElse(false);
    }
}

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
import com.darkcollective.relix.cost.BoundednessSource;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.source.GeneratorSourceConfig;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link BoundednessSource} backed by a model's sources and the generator registry: a leaf
 * relation is {@link Boundedness#UNBOUNDED} iff it is a generator
 * source whose generator declares {@link Generator#unbounded()}; every other leaf
 * (CSV/JSON/JDBC/inline/finite generator) is {@link Boundedness#BOUNDED}.
 *
 * <p>This is the producer the planner's materialisation-safety check and join
 * build-side rule consume: {@code Naturals} and {@code Primes} report
 * {@code UNBOUNDED}, {@code Range} and every non-generator leaf {@code BOUNDED}.
 *
 * <p>Which generators are infinite is the registry's answer
 * ({@code GeneratorCatalog.generatorBoundedness}), asked here rather than re-read off
 * {@link Generator#unbounded()}, so the planner and {@code relix.relations.boundedness}
 * cannot come to different conclusions about the same generator. What differs between
 * them is only what an <em>unregistered</em> name means, and that difference is
 * deliberate — see below.
 */
public final class GeneratorBoundednessSource implements BoundednessSource {

    private final Map<String, SourceDeclaration> sources;
    private final GeneratorRegistry registry;

    /**
     * @param sources  canonical (lower-cased) relation name → source declaration
     *                 (as in {@code SemanticModel.sources()}); must not be null
     * @param registry the generator registry; must not be null
     */
    public GeneratorBoundednessSource(Map<String, SourceDeclaration> sources, GeneratorRegistry registry) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public Boundedness boundednessOf(String relationName) {
        SourceDeclaration declaration = sources.get(relationName.toLowerCase(Locale.ROOT));
        if (declaration != null && declaration.config() instanceof GeneratorSourceConfig generator) {
            Boundedness declared = registry.generatorBoundedness(
                    generator.generatorName(), generator.args());
            // A generator nobody has registered is UNKNOWN to the registry, and the
            // planner reads it as BOUNDED: the checks fed from here refuse an
            // unbounded input, so an unrecognised name would cost a legal query its
            // plan. Reported as a fact — relix.relations.boundedness — the same
            // silence stays UNKNOWN, because a catalog is not deciding anything.
            return declared == Boundedness.UNKNOWN ? Boundedness.BOUNDED : declared;
        }
        return Boundedness.BOUNDED;
    }
}

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
import com.darkcollective.relix.semantic.GeneratorCatalog;
import com.darkcollective.relix.symbol.Schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The catalogue of {@link Generator}s, indexed by name (case-insensitive) — the
 * built-in ones, plus any a program {@link #register registers}.
 *
 * <p>Also implements the {@link GeneratorCatalog} seam, so the same registry that
 * produces rows at execution supplies generator schemas to semantic analysis —
 * each generator stays defined in one place. The CLI wires this registry into both
 * the analyzer (as a {@code GeneratorCatalog}) and the
 * {@code GeneratorDataSourceConnector}.
 */
public final class GeneratorRegistry implements GeneratorCatalog {

    private final Map<String, Generator> byName;

    /**
     * A registry of the built-in generators: the finite {@code Range} and the
     * unbounded {@code Naturals}/{@code Primes}.
     */
    public GeneratorRegistry() {
        this(List.of(new RangeGenerator(), new NaturalsGenerator(), new PrimesGenerator()));
    }

    /** Package-private: index an explicit generator list (for tests). */
    GeneratorRegistry(List<Generator> generators) {
        this.byName = new LinkedHashMap<>();
        for (Generator g : generators) {
            byName.put(g.name().toLowerCase(Locale.ROOT), g);
        }
    }

    /**
     * Adds a generator to this registry, under its own {@link Generator#name() name}.
     *
     * <p>A generator is a code-backed leaf relation, which is exactly what a program
     * supplying rows of its own has: the registry is the one directory both halves of the
     * pipeline read, so registering here is what makes a name resolvable to a heading
     * during analysis <em>and</em> to rows during execution.
     *
     * <p>A name already taken is rejected rather than replaced. The built-ins are a
     * documented language surface, and a session that silently redefined {@code Range}
     * would answer a question about the natural numbers with something else.
     *
     * @param generator the generator to register; must not be null
     * @throws IllegalArgumentException if a generator of that name is already registered
     */
    public void register(Generator generator) {
        Objects.requireNonNull(generator, "generator");
        String key = generator.name().toLowerCase(Locale.ROOT);
        Generator existing = byName.putIfAbsent(key, generator);
        if (existing != null) {
            throw new IllegalArgumentException(
                    "a generator named '" + generator.name() + "' is already registered");
        }
    }

    /**
     * @param name the generator name (case-insensitive)
     * @return the generator, or empty if none is registered under that name
     */
    public Optional<Generator> find(String name) {
        Objects.requireNonNull(name, "name");
        return Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)));
    }

    @Override
    public Optional<Schema> generatorSchema(String name, Map<String, String> args) {
        return find(name).map(g -> g.schema(args));
    }

    @Override
    public OptionalLong generatorCardinality(String name, Map<String, String> args) {
        return find(name).map(g -> g.cardinality(args)).orElse(OptionalLong.empty());
    }

    @Override
    public Boundedness generatorBoundedness(String name, Map<String, String> args) {
        return find(name)
                .map(g -> g.unbounded() ? Boundedness.UNBOUNDED : Boundedness.BOUNDED)
                .orElse(Boundedness.UNKNOWN);
    }
}

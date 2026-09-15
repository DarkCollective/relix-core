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
package com.darkcollective.relix.semantic;

import com.darkcollective.relix.cost.Boundedness;
import com.darkcollective.relix.cost.BoundednessSource;
import com.darkcollective.relix.cost.PropertyDeriver;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.source.GeneratorSourceConfig;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.relation.RelationSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Answers one question about a <em>named</em> relation: <em>is it finite?</em> — the
 * extent of {@code relix.relations.boundedness}.
 *
 * <p>It is a {@link BoundednessSource}, which is what makes it one answer rather than
 * two. The lattice, the operator rules and the {@code λ}-is-a-rescue exception all live
 * in {@link PropertyDeriver}, and this supplies the part that walk cannot know: what a
 * <em>leaf</em> is. A leaf here may be a view, so the two call back and forth — the
 * deriver descends a body, this resolves each name it reaches, and a name that turns out
 * to be another view is derived in turn.
 *
 * <h2>What a name resolves to</h2>
 * <ul>
 *   <li>A <b>view</b> ({@code :=}) — derived from its body, so it is exactly as bounded
 *       as what it reads. This is the answer worth having: {@code σ n &lt; 100 (Naturals)}
 *       is still infinite, and {@code λ 10 (Naturals)} is not.</li>
 *   <li>A <b>generator source</b> — asked of the {@link GeneratorCatalog}, the only
 *       party that knows. {@code Naturals} and {@code Primes} are unbounded, {@code Range}
 *       is not.</li>
 *   <li>Any <b>other declared relation</b> — a file, an HTTP resource, a database table,
 *       an inline table, a catalog relation — is {@link Boundedness#BOUNDED}. Each is a
 *       finite extent that some other party is holding.</li>
 *   <li>A name that resolves to <b>nothing</b> — {@link Boundedness#UNKNOWN}. Unlike the
 *       planner's source, which reads an unrecognised leaf as bounded rather than risk
 *       refusing a legal query, a catalog has no such stake: it is stating a fact, and
 *       the fact about a relation it cannot see is that it does not know one.</li>
 * </ul>
 *
 * <h2>A cycle answers UNKNOWN</h2>
 * <p>Mutually recursive definitions ({@code A := σ … (B); B := σ … (A)}) terminate on a
 * visiting set, and re-entering a name in progress yields {@code UNKNOWN} rather than the
 * lattice's identity. {@link RelationDeterminism} makes the opposite choice on the same
 * shape for a good reason that does not hold here: its fold is a conjunction, so the walk
 * still in progress covers whatever the re-entry would have found. A least upper bound has
 * no such reading — returning {@code BOUNDED} would let a cycle claim to be finite, which
 * is precisely what nobody can show.
 *
 * <p>Not thread-safe, and not meant to be: an instance carries the walk it is on, and one
 * is built per analysis.
 */
public final class RelationBoundedness implements BoundednessSource {

    private final SymbolTable symbols;
    private final Map<String, SourceDeclaration> sources;
    private final GeneratorCatalog generators;

    /** Canonical names whose bodies are on the current walk, so a cycle terminates. */
    private final Set<String> visiting = new HashSet<>();

    /**
     * @param symbols    resolves a name to its symbol — dotted references included; must
     *                   not be null
     * @param sources    source declarations by canonical (lower-cased) name, as in
     *                   {@code SemanticModel.sources()}, which is where a generator
     *                   source's name and arguments are; must not be null
     * @param generators the installed generators, asked whether one of them is infinite;
     *                   must not be null ({@link GeneratorCatalog#NONE} answers
     *                   {@link Boundedness#UNKNOWN} for every generator, which is the
     *                   honest report from a session with no registry wired in)
     */
    public RelationBoundedness(SymbolTable symbols,
                               Map<String, SourceDeclaration> sources,
                               GeneratorCatalog generators) {
        this.symbols = Objects.requireNonNull(symbols, "symbols");
        this.sources = Map.copyOf(Objects.requireNonNull(sources, "sources"));
        this.generators = Objects.requireNonNull(generators, "generators");
    }

    /**
     * {@return the boundedness of the relation named {@code relationName}}
     *
     * <p>An unresolvable name is {@link Boundedness#UNKNOWN}; see {@link #of} for the
     * rest.
     *
     * @param relationName the relation name, qualified or not; must not be null
     */
    @Override
    public Boundedness boundednessOf(String relationName) {
        Objects.requireNonNull(relationName, "relationName");
        return symbols.resolveRelation(relationName)
                .map(this::of)
                .orElse(Boundedness.UNKNOWN);
    }

    /**
     * {@return the boundedness of {@code symbol}}
     *
     * <p>The entry point for a caller that already holds the symbol, which is every
     * caller building a row <em>about</em> a relation rather than following a reference
     * to one — it skips a name resolution that could land on a same-named relation in
     * another namespace.
     *
     * @param symbol the relation; must not be null
     */
    public Boundedness of(RelationSymbol symbol) {
        Objects.requireNonNull(symbol, "symbol");
        if (symbol instanceof QueryRelationSymbol view) {
            return derived(view);
        }
        SourceDeclaration declaration = sources.get(symbol.canonicalName());
        if (declaration != null
                && declaration.config() instanceof GeneratorSourceConfig generator) {
            return generators.generatorBoundedness(
                    generator.generatorName(), generator.args());
        }
        return Boundedness.BOUNDED;
    }

    /** Derives a view's boundedness from its body, guarding against a cycle. */
    private Boundedness derived(QueryRelationSymbol view) {
        String key = view.namespace().toLowerCase(Locale.ROOT) + "." + view.canonicalName();
        if (!visiting.add(key)) {
            return Boundedness.UNKNOWN;
        }
        try {
            return PropertyDeriver.boundedness(view.body(), this);
        } finally {
            visiting.remove(key);
        }
    }
}

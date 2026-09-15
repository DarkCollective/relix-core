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
package com.darkcollective.relix.provenance;

import java.util.Objects;
import java.util.Optional;

/**
 * A provenance variable — the token identifying one base-tuple occurrence in the
 * polynomial-lineage semiring {@link PolynomialSemiring ℕ[X]} (after Green,
 * Karvounarakis &amp; Tannen, PODS 2007).
 *
 * <p>Each occurrence of a tuple in a base relation is lifted to its own distinct
 * variable (so two structurally-equal source rows still receive different
 * variables, and the merged tuple's annotation becomes {@code x₁ ⊕ x₂}). The
 * {@code name} is a human-readable label for that occurrence — by convention
 * {@code <relation>#<ordinal>}, e.g. {@code Orders#1} — chosen at lift time, not by
 * the algebra.
 *
 * <p>A variable may additionally carry a structured {@link SourceRef source}: the
 * producing leaf, occurrence ordinal, and the base tuple's captured column values.
 * This is what makes lineage <em>machine-actionable</em> — a consuming tool can use
 * {@link #source()} to locate the exact source row a variable stands for, rather
 * than parsing the bare {@code name}. Variables minted from a raw label (e.g. in
 * tests, or the cheap weighted-closure path) simply have an
 * {@link Optional#empty() empty} source.
 *
 * <p>Variables are compared, and considered equal, <strong>by {@code name} alone</strong>,
 * so two variables with the same label are the same variable regardless of their
 * captured source — the canonical name is the identity that the {@code ⊕}/{@code ⊗}
 * algebra keys on.
 *
 * @param name   the variable's label; never {@code null} or blank
 * @param source the structured source identity, or {@link Optional#empty()} when
 *               minted from a bare label; never {@code null}
 */
public record ProvenanceVariable(String name, Optional<SourceRef> source)
        implements Comparable<ProvenanceVariable> {

    /**
     * Creates a provenance variable, validating its label and source.
     *
     * @param name   the variable's label; never {@code null} or blank
     * @param source the structured source identity; never {@code null} (may be empty)
     */
    public ProvenanceVariable {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("variable name must not be blank");
        }
        Objects.requireNonNull(source, "source");
    }

    /**
     * Creates a bare provenance variable from a label, with no structured source.
     *
     * @param name the variable's label; never {@code null} or blank
     */
    public ProvenanceVariable(String name) {
        this(name, Optional.empty());
    }

    /**
     * {@return a provenance variable carrying the structured {@code ref}} The
     * variable's {@link #name() name} is the reference's
     * {@link SourceRef#canonicalName() canonical name}, so its identity is unchanged
     * from a bare {@code <source>#<ordinal>} variable while it now carries the
     * captured columns.
     *
     * @param ref the structured source identity; never {@code null}
     */
    public static ProvenanceVariable of(SourceRef ref) {
        Objects.requireNonNull(ref, "ref");
        return new ProvenanceVariable(ref.canonicalName(), Optional.of(ref));
    }

    /**
     * {@return this variable's label, optionally enriched with its captured source
     * columns} When {@code detailed} and a {@link #source() source} with columns is
     * present, returns e.g. {@code Orders#1{id: 42}}; otherwise the bare
     * {@link #name() name}.
     *
     * @param detailed whether to append the captured source columns when available
     */
    public String label(boolean detailed) {
        return detailed
                ? source.map(SourceRef::detailedLabel).orElse(name)
                : name;
    }

    /** Orders variables by {@code name} — the canonical order used to render monomials. */
    @Override
    public int compareTo(ProvenanceVariable other) {
        return name.compareTo(other.name);
    }

    /** Two variables are equal iff their {@code name}s are equal (source is not part of identity). */
    @Override
    public boolean equals(Object obj) {
        return obj instanceof ProvenanceVariable other && name.equals(other.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}

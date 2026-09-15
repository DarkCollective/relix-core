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

import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A provenance polynomial — an element of the lineage semiring
 * {@link PolynomialSemiring ℕ[X]}. It is a sum of {@link Monomial monomials}, each
 * with a positive {@link BigInteger} coefficient, recording the <em>full
 * why-provenance</em> of a tuple: every distinct derivation (monomial) and its
 * multiplicity (coefficient).
 *
 * <p>Held in canonical form: a sorted map of monomial → non-zero coefficient (a
 * coefficient of zero means that derivation is absent, so it is never a key). The
 * empty polynomial is {@link #zero() 0}. The {@code truncated} flag records that a
 * {@link PolynomialSemiring} operation hit the monomial cap and dropped some terms —
 * the degraded mode (a sound under-approximation; the surviving terms are real
 * derivations, but the polynomial is incomplete).
 *
 * @param terms     canonical monomial → positive-coefficient map; never {@code null}
 * @param truncated whether terms were dropped to stay within the representation bound
 */
public record Polynomial(SortedMap<Monomial, BigInteger> terms, boolean truncated) {

    private static final Polynomial ZERO = new Polynomial(new TreeMap<>(), false);
    private static final Polynomial ONE =
            singleton(Monomial.CONSTANT, BigInteger.ONE);

    /**
     * Canonicalises {@code terms}: defensively copies into a {@link TreeMap} (so
     * iteration is in the deterministic {@link Monomial} order) and drops any
     * zero-coefficient term. Negative coefficients are rejected — ℕ[X] has
     * natural-number coefficients.
     *
     * @param terms     monomial → coefficient map; never {@code null}
     * @param truncated whether some terms were dropped to honour the bound
     */
    public Polynomial {
        Objects.requireNonNull(terms, "terms");
        SortedMap<Monomial, BigInteger> canonical = new TreeMap<>();
        for (Map.Entry<Monomial, BigInteger> e : terms.entrySet()) {
            BigInteger coeff = Objects.requireNonNull(e.getValue(), "coefficient");
            if (coeff.signum() < 0) {
                throw new IllegalArgumentException("coefficient must be non-negative: " + coeff);
            }
            if (coeff.signum() != 0) {
                canonical.put(Objects.requireNonNull(e.getKey(), "monomial"), coeff);
            }
        }
        terms = canonical;
    }

    /** {@return the zero polynomial {@code 0} (no derivations — an absent tuple)} */
    public static Polynomial zero() {
        return ZERO;
    }

    /** {@return the constant polynomial {@code 1} (the multiplicative identity)} */
    public static Polynomial one() {
        return ONE;
    }

    /**
     * {@return the single-variable polynomial {@code x}} The lift token for one
     * base-tuple occurrence.
     *
     * @param name the variable label (by convention {@code <relation>#<ordinal>})
     */
    public static Polynomial variable(String name) {
        return singleton(Monomial.of(new ProvenanceVariable(name)), BigInteger.ONE);
    }

    /**
     * {@return the single-variable polynomial {@code x} for a structured source} The
     * lift token for one base-tuple occurrence, carrying its
     * {@link SourceRef captured columns} so the resulting lineage is machine-actionable.
     *
     * @param ref the structured source identity; never {@code null}
     */
    public static Polynomial variable(SourceRef ref) {
        return singleton(Monomial.of(ProvenanceVariable.of(ref)), BigInteger.ONE);
    }

    /** Builds a one-term polynomial (internal helper; assumes a non-zero coefficient). */
    static Polynomial singleton(Monomial monomial, BigInteger coefficient) {
        SortedMap<Monomial, BigInteger> t = new TreeMap<>();
        t.put(monomial, coefficient);
        return new Polynomial(t, false);
    }

    /** {@return whether this is the zero polynomial} */
    public boolean isZero() {
        return terms.isEmpty();
    }

    /** {@return the number of distinct monomials (derivations) — the representation size} */
    public int size() {
        return terms.size();
    }

    /**
     * {@return the rendered polynomial, e.g. {@code 2·Orders#1·Customers#1 + Orders#2}}
     * {@code 0} for the zero polynomial; a trailing {@code  + ⋯} when {@link #truncated()}.
     */
    @Override
    public String toString() {
        return render(false);
    }

    /**
     * {@return this polynomial rendered with variables optionally enriched by their
     * captured source columns} {@code render(false)} is the compact form (the
     * {@link #toString()} default, e.g. {@code 2·Orders#1·Customers#1 + Orders#2});
     * {@code render(true)} expands each variable to its
     * {@link ProvenanceVariable#label(boolean) detailed label}, e.g.
     * {@code Orders#1{id: 42}}, when structured source columns are present.
     *
     * @param detailed whether to append captured source columns to each variable
     */
    public String render(boolean detailed) {
        if (terms.isEmpty()) {
            return truncated ? "0 + ⋯" : "0";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<Monomial, BigInteger> e : terms.entrySet()) {
            if (!first) sb.append(" + ");
            first = false;
            sb.append(renderTerm(e.getKey(), e.getValue(), detailed));
        }
        if (truncated) {
            sb.append(" + ⋯");
        }
        return sb.toString();
    }

    /** Renders one {@code coefficient · monomial} term with the natural elisions. */
    private static String renderTerm(Monomial monomial, BigInteger coefficient, boolean detailed) {
        boolean unitCoeff = coefficient.equals(BigInteger.ONE);
        if (monomial.isConstant()) {
            return coefficient.toString();          // 1, 2, 3 … (a constant term)
        }
        String rendered = monomial.render(detailed);
        return unitCoeff ? rendered                 // Orders#1
                         : coefficient + "·" + rendered;   // 2·Orders#1·Customers#1
    }
}

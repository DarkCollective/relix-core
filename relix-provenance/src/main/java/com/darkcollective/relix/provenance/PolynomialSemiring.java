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
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The provenance-polynomial semiring {@code ℕ[X]} — <em>full why-provenance</em>
 * (which input tuples produced a result, and how they were combined), after Green,
 * Karvounarakis &amp; Tannen (PODS 2007). It is the <strong>free</strong> commutative
 * semiring on the set of {@link ProvenanceVariable provenance variables}: the most
 * informative annotation, from which every other semiring (count, boolean, trust,
 * cost) is recovered by evaluating the polynomial under it.
 *
 * <p>Each base-tuple occurrence is lifted to its own variable {@code x} (a degree-one
 * {@link Polynomial}). Then {@link #plus ⊕} is polynomial addition (alternative
 * derivations become separate {@link Monomial monomials}, with like terms' coefficients
 * added), and {@link #times ⊗} is polynomial multiplication (a joint derivation
 * multiplies its inputs' monomials and coefficients). {@link #zero() 0} is the empty
 * polynomial; {@link #one() 1} the constant polynomial {@code 1}.
 *
 * <h2>Bounded representation (the degraded mode)</h2>
 * <p>Polynomials can blow up — a dense join multiplies term counts. To stay bounded,
 * every operation that would leave more than {@link #MAX_MONOMIALS} distinct monomials
 * keeps the smallest {@code MAX_MONOMIALS} of them (in {@link Monomial}'s deterministic
 * order) and marks the result {@link Polynomial#truncated() truncated}. Truncation is a
 * sound under-approximation (the surviving terms are real derivations) and is contagious
 * (any operation touching a truncated polynomial yields a truncated result).
 *
 * <p>Below the cap the full commutative-semiring contract holds. At and beyond it the two
 * operations diverge, because capping is a selection and only one of them is one.
 * {@code ⊕} keeps the smallest {@code MAX_MONOMIALS} monomials of a union, and the
 * smallest n of a union does not depend on how the union was bracketed — so {@code ⊕}
 * stays associative however far past the cap it goes. {@code ⊗} caps each intermediate
 * <em>product</em>, so which monomials survive depends on which pair was multiplied
 * first, and {@code (a ⊗ b) ⊗ c} and {@code a ⊗ (b ⊗ c)} can select different terms. Both
 * are sound under-approximations and both report
 * {@link Polynomial#truncated() truncated}; they are simply not the same approximation.
 *
 * <p>{@link Polynomial#truncated()} is a record of the derivation rather than of the
 * polynomial: it says some operation along the way hit the cap, so two polynomials with
 * identical terms, reached by different routes, can disagree on it.
 */
public enum PolynomialSemiring implements Semiring<Polynomial> {

    /** The singleton instance. */
    INSTANCE;

    /** Maximum distinct monomials retained before a result is truncated. */
    public static final int MAX_MONOMIALS = 256;

    /**
     * {@return the single-variable polynomial {@code x}} Convenience for the lift of a
     * base-tuple occurrence to its provenance variable.
     *
     * @param name the variable label (by convention {@code <relation>#<ordinal>})
     */
    public static Polynomial variable(String name) {
        return Polynomial.variable(name);
    }

    /**
     * {@return the single-variable polynomial {@code x} for a structured source} The
     * lift of a base-tuple occurrence to its provenance variable, carrying the
     * occurrence's {@link SourceRef captured columns} so the lineage is machine-actionable.
     *
     * @param ref the structured source identity; never {@code null}
     */
    public static Polynomial variable(SourceRef ref) {
        return Polynomial.variable(ref);
    }

    @Override
    public Polynomial zero() {
        return Polynomial.zero();
    }

    @Override
    public Polynomial one() {
        return Polynomial.one();
    }

    @Override
    public Polynomial plus(Polynomial a, Polynomial b) {
        SortedMap<Monomial, BigInteger> sum = new TreeMap<>(a.terms());
        b.terms().forEach((m, c) -> sum.merge(m, c, BigInteger::add));
        return bounded(sum, a.truncated() || b.truncated());
    }

    @Override
    public Polynomial times(Polynomial a, Polynomial b) {
        SortedMap<Monomial, BigInteger> product = new TreeMap<>();
        for (Map.Entry<Monomial, BigInteger> ta : a.terms().entrySet()) {
            for (Map.Entry<Monomial, BigInteger> tb : b.terms().entrySet()) {
                Monomial m = ta.getKey().times(tb.getKey());
                product.merge(m, ta.getValue().multiply(tb.getValue()), BigInteger::add);
            }
        }
        return bounded(product, a.truncated() || b.truncated());
    }

    /**
     * Builds a polynomial from {@code terms}, enforcing the monomial cap: drops zero
     * coefficients (via the {@link Polynomial} constructor) and, if more than
     * {@link #MAX_MONOMIALS} distinct monomials remain, keeps only the smallest
     * {@code MAX_MONOMIALS} (deterministic {@link Monomial} order) and flags truncation.
     */
    private static Polynomial bounded(SortedMap<Monomial, BigInteger> terms, boolean truncated) {
        // Drop zeros first so the cap counts only present derivations.
        terms.values().removeIf(c -> c.signum() == 0);
        if (terms.size() <= MAX_MONOMIALS) {
            return new Polynomial(terms, truncated);
        }
        SortedMap<Monomial, BigInteger> kept = new TreeMap<>();
        for (Map.Entry<Monomial, BigInteger> e : terms.entrySet()) {
            if (kept.size() == MAX_MONOMIALS) {
                break;
            }
            kept.put(e.getKey(), e.getValue());
        }
        return new Polynomial(kept, true);
    }
}

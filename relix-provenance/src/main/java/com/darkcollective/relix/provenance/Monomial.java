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

import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A monomial in the polynomial-lineage semiring {@link PolynomialSemiring ℕ[X]} — a
 * product of {@link ProvenanceVariable provenance variables} with positive integer
 * exponents (a multiset of variables). It records <em>one</em> joint derivation:
 * the set of base tuples that were combined (by {@code ⊗}/join), with an exponent
 * counting how many times each was used.
 *
 * <p>The empty monomial is the multiplicative constant {@code 1} (a derivation that
 * uses no input — the semiring's {@link PolynomialSemiring#one() one}). Monomials are
 * held in canonical form (a sorted map of variable → positive exponent), so equal
 * products compare equal regardless of construction order.
 *
 * @param exponents canonical variable → positive-exponent map; never {@code null}
 */
public record Monomial(SortedMap<ProvenanceVariable, Integer> exponents)
        implements Comparable<Monomial> {

    /** The constant monomial {@code 1} (no variables). */
    public static final Monomial CONSTANT = new Monomial(new TreeMap<>());

    /**
     * Canonicalises {@code exponents}: defensively copies into a {@link TreeMap} and
     * rejects non-positive exponents (a zero/negative exponent is not representable —
     * absent variables are simply not keys).
     *
     * @param exponents variable → exponent map; never {@code null}, all exponents &gt; 0
     */
    public Monomial {
        Objects.requireNonNull(exponents, "exponents");
        SortedMap<ProvenanceVariable, Integer> canonical = new TreeMap<>();
        for (Map.Entry<ProvenanceVariable, Integer> e : exponents.entrySet()) {
            int exp = Objects.requireNonNull(e.getValue(), "exponent");
            if (exp <= 0) {
                throw new IllegalArgumentException(
                        "exponent must be positive: " + e.getKey() + "^" + exp);
            }
            canonical.put(Objects.requireNonNull(e.getKey(), "variable"), exp);
        }
        exponents = canonical;
    }

    /** {@return the single-variable monomial {@code x}} */
    public static Monomial of(ProvenanceVariable variable) {
        SortedMap<ProvenanceVariable, Integer> m = new TreeMap<>();
        m.put(Objects.requireNonNull(variable, "variable"), 1);
        return new Monomial(m);
    }

    /**
     * Multiplies this monomial by {@code other}: the exponents of shared variables
     * add (the {@code ⊗} on the variable level).
     *
     * @param other the monomial to multiply by; never {@code null}
     * @return the product monomial
     */
    public Monomial times(Monomial other) {
        Objects.requireNonNull(other, "other");
        SortedMap<ProvenanceVariable, Integer> product = new TreeMap<>(exponents);
        other.exponents.forEach((v, e) -> product.merge(v, e, Integer::sum));
        return new Monomial(product);
    }

    /** {@return the total degree (sum of exponents); 0 for the constant monomial} */
    public int degree() {
        return exponents.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** {@return whether this is the constant monomial {@code 1}} */
    public boolean isConstant() {
        return exponents.isEmpty();
    }

    /**
     * Orders monomials deterministically — by ascending total degree, then
     * lexicographically by their {@code (variable, exponent)} entries. This is the
     * stable order used to render a polynomial and to choose which terms survive a
     * {@link PolynomialSemiring} truncation.
     */
    @Override
    public int compareTo(Monomial other) {
        int byDegree = Integer.compare(degree(), other.degree());
        if (byDegree != 0) {
            return byDegree;
        }
        var i = exponents.entrySet().iterator();
        var j = other.exponents.entrySet().iterator();
        while (i.hasNext() && j.hasNext()) {
            var a = i.next();
            var b = j.next();
            int byVar = a.getKey().compareTo(b.getKey());
            if (byVar != 0) return byVar;
            int byExp = Integer.compare(a.getValue(), b.getValue());
            if (byExp != 0) return byExp;
        }
        return Integer.compare(exponents.size(), other.exponents.size());
    }

    /** {@return the rendered product, e.g. {@code Orders#1·Customers#1^2}; {@code 1} when constant} */
    @Override
    public String toString() {
        return render(false);
    }

    /**
     * {@return the rendered product, with each variable optionally enriched by its
     * captured source columns} {@code render(false)} renders each variable by its bare
     * {@link ProvenanceVariable#name() name} (the {@link #toString()} default);
     * {@code render(true)} uses its {@link ProvenanceVariable#label(boolean) detailed
     * label}, e.g. {@code Orders#1{id: 42}·Customers#1{id: 7}}.
     *
     * @param detailed whether to append captured source columns to each variable
     */
    public String render(boolean detailed) {
        if (exponents.isEmpty()) {
            return "1";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<ProvenanceVariable, Integer> e : exponents.entrySet()) {
            if (!first) sb.append('·');
            first = false;
            sb.append(e.getKey().label(detailed));
            if (e.getValue() > 1) {
                sb.append('^').append(e.getValue());
            }
        }
        return sb.toString();
    }
}

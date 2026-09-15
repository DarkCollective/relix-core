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

/**
 * A commutative semiring {@code (K, ⊕, ⊗, 0, 1)} — the pluggable extension point
 * of the provenance / K-relation framework.
 *
 * <p>In a K-relation every tuple is annotated with an element of {@code K}, and
 * each positive-algebra operator is defined by these operations: union and
 * projection combine alternative derivations with {@link #plus ⊕}; join and
 * product combine joint requirements with {@link #times ⊗}; selection multiplies
 * a tuple's annotation by {@link #one() 1} (keep) or {@link #zero() 0} (drop); and
 * the annotation {@link #zero() 0} means the tuple is absent. Choosing the
 * semiring chooses the feature — existence, multiplicity, lineage, trust level, or
 * shortest path — without changing any operator code.
 *
 * <p>An implementation must satisfy the commutative-semiring laws for every
 * {@code a, b, c ∈ K}:
 * <ul>
 *   <li>{@code ⊕} is associative and commutative, with identity {@link #zero() 0}:
 *       {@code plus(a, zero()) == a};</li>
 *   <li>{@code ⊗} is associative and commutative, with identity {@link #one() 1}:
 *       {@code times(a, one()) == a};</li>
 *   <li>{@link #zero() 0} annihilates {@code ⊗}: {@code times(a, zero()) == zero()};</li>
 *   <li>{@code ⊗} distributes over {@code ⊕}:
 *       {@code times(a, plus(b, c)) == plus(times(a, b), times(a, c))}.</li>
 * </ul>
 *
 * <p>Implementations are expected to be immutable and stateless (the built-ins are
 * singletons). Equality is by the annotation values of {@code K}, not by the
 * {@code Semiring} instance.
 *
 * @param <K> the annotation type carried by each tuple
 * @see BooleanSemiring
 * @see CountingSemiring
 * @see TropicalSemiring
 * @see SecurityLattice
 * @see <a href="https://doi.org/10.1145/1265530.1265535">T. J. Green, G.
 *      Karvounarakis &amp; V. Tannen, <em>Provenance semirings</em>, PODS 2007</a>
 */
public interface Semiring<K> {

    /**
     * {@return the additive identity {@code 0}} It is the identity of
     * {@link #plus} and the annihilator of {@link #times}, and denotes an absent
     * tuple.
     */
    K zero();

    /** {@return the multiplicative identity {@code 1}} It is the identity of {@link #times}. */
    K one();

    /**
     * Combines two annotations with {@code ⊕} — the way alternative derivations of
     * the same tuple (union, projection) are merged. Associative and commutative,
     * with identity {@link #zero()}.
     *
     * @param a the first annotation
     * @param b the second annotation
     * @return {@code a ⊕ b}
     */
    K plus(K a, K b);

    /**
     * Combines two annotations with {@code ⊗} — the way the joint requirements of a
     * tuple (join, product) are merged. Associative and commutative, with identity
     * {@link #one()}, annihilated by {@link #zero()}.
     *
     * @param a the first annotation
     * @param b the second annotation
     * @return {@code a ⊗ b}
     */
    K times(K a, K b);
}

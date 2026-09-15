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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.BiPredicate;

/**
 * Reusable assertion helper: verifies that a {@link Semiring} satisfies the
 * commutative-semiring laws over a supplied sample of annotation values.
 *
 * <p>Each per-semiring test feeds a small but representative sample (including the
 * semiring's own {@link Semiring#zero()} and {@link Semiring#one()}); this helper
 * then checks every law on every relevant combination, so the algebraic contract
 * is asserted once and shared across all built-ins.
 */
final class SemiringLaws {

    private SemiringLaws() {
    }

    /**
     * Asserts the full commutative-semiring contract for {@code s} over
     * {@code samples}: closure under the operations, commutativity and
     * associativity of {@code ⊕}/{@code ⊗}, the additive and multiplicative
     * identities, annihilation by {@code zero}, and distributivity of {@code ⊗}
     * over {@code ⊕}.
     */
    static <K> void assertLaws(Semiring<K> s, List<K> samples) {
        assertLaws(s, samples, Object::equals);
    }

    /**
     * As {@link #assertLaws(Semiring, List)}, but comparing annotations with
     * {@code equivalent} rather than {@link Object#equals}.
     *
     * <p>For a {@code K} whose value carries a component outside the algebra, whole-value
     * equality is the wrong question. {@link Polynomial} is the case: its {@code truncated}
     * flag records that <em>some operation along the way</em> hit the monomial cap, so it
     * is a property of the derivation rather than of the polynomial, and it is
     * path-dependent where the terms are not — {@code (0 ⊗ b) ⊗ c} truncates nothing while
     * {@code 0 ⊗ (b ⊗ c)} truncates in the inner product and carries the flag out through
     * a zero result. Comparing terms alone asks whether the <em>algebra</em> holds.
     */
    static <K> void assertLaws(Semiring<K> s, List<K> samples, BiPredicate<K, K> equivalent) {
        K zero = s.zero();
        K one = s.one();

        for (K a : samples) {
            // identities
            assertEquivalent(equivalent, s.plus(a, zero), a, "plus identity: a ⊕ 0");
            assertEquivalent(equivalent, s.plus(zero, a), a, "plus identity: 0 ⊕ a");
            assertEquivalent(equivalent, s.times(a, one), a, "times identity: a ⊗ 1");
            assertEquivalent(equivalent, s.times(one, a), a, "times identity: 1 ⊗ a");
            // annihilation
            assertEquivalent(equivalent, s.times(a, zero), zero, "annihilation: a ⊗ 0");
            assertEquivalent(equivalent, s.times(zero, a), zero, "annihilation: 0 ⊗ a");

            for (K b : samples) {
                // commutativity
                assertEquivalent(equivalent, s.plus(a, b), s.plus(b, a), "plus commutes");
                assertEquivalent(equivalent, s.times(a, b), s.times(b, a), "times commutes");

                for (K c : samples) {
                    // associativity
                    assertEquivalent(equivalent, s.plus(s.plus(a, b), c),
                            s.plus(a, s.plus(b, c)), "plus associates");
                    assertEquivalent(equivalent, s.times(s.times(a, b), c),
                            s.times(a, s.times(b, c)), "times associates");
                    // distributivity
                    assertEquivalent(equivalent, s.times(a, s.plus(b, c)),
                            s.plus(s.times(a, b), s.times(a, c)),
                            "times distributes over plus (left)");
                }
            }
        }
    }

    private static <K> void assertEquivalent(BiPredicate<K, K> equivalent, K actual, K expected,
                                             String law) {
        assertThat(equivalent.test(actual, expected))
                .as("%s — expected <%s> but was <%s>", law, expected, actual)
                .isTrue();
    }
}

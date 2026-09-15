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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The canonical order of a derivation, and the arithmetic that builds one.
 *
 * <p>{@link Monomial#compareTo} is not a convenience: it is the order a polynomial
 * renders in and the order {@link PolynomialSemiring}'s truncation keeps terms by, so
 * which derivations a capped lineage reports is decided here. It was reached only
 * through the semiring's own suites, which compare rendered strings and so cannot say
 * which of two orderings produced one.
 */
@DisplayName("Monomial")
final class MonomialTest {

    private static ProvenanceVariable v(String name) {
        return new ProvenanceVariable(name);
    }

    private static Monomial mono(Object... varsAndExponents) {
        SortedMap<ProvenanceVariable, Integer> m = new TreeMap<>();
        for (int i = 0; i < varsAndExponents.length; i += 2) {
            m.put(v((String) varsAndExponents[i]), (Integer) varsAndExponents[i + 1]);
        }
        return new Monomial(m);
    }

    @Nested
    @DisplayName("orders by degree first, then by entry")
    final class Ordering {

        @Test
        @DisplayName("a smaller total degree comes first, however many variables it spends it on")
        void byDegree() {
            assertThat(mono("A", 1)).isLessThan(mono("A", 2));
            assertThat(mono("A", 1, "B", 1))
                    .as("degree 2 over two variables still outranks degree 1")
                    .isGreaterThan(mono("Z", 1));
        }

        @Test
        @DisplayName("at equal degree, the first differing variable decides")
        void byVariable() {
            assertThat(mono("A", 1, "B", 1)).isLessThan(mono("A", 1, "C", 1));
            assertThat(mono("B", 2)).isGreaterThan(mono("A", 2));
        }

        @Test
        @DisplayName("at the same variable, the smaller exponent decides")
        void byExponent() {
            assertThat(mono("A", 1, "B", 2)).isLessThan(mono("A", 2, "B", 1));
        }

        @Test
        @DisplayName("equal products compare equal however they were built")
        void equalProducts() {
            assertThat(mono("A", 1, "B", 1)).isEqualByComparingTo(mono("B", 1, "A", 1));
            assertThat(Monomial.CONSTANT).isEqualByComparingTo(mono());
        }

        @Test
        @DisplayName("the constant leads any product, being degree zero")
        void constantLeads() {
            assertThat(List.of(mono("B", 1), Monomial.CONSTANT, mono("A", 2), mono("A", 1))
                    .stream().sorted().map(Monomial::toString).toList())
                    .containsExactly("1", "A", "B", "A^2");
        }
    }

    @Nested
    @DisplayName("multiplication adds the exponents of shared variables")
    final class Multiplication {

        @Test
        @DisplayName("shared variables add, distinct ones join")
        void times() {
            assertThat(mono("A", 1, "B", 2).times(mono("B", 1, "C", 3)))
                    .isEqualTo(mono("A", 1, "B", 3, "C", 3));
        }

        @Test
        @DisplayName("the constant is the identity, on either side")
        void constantIsIdentity() {
            Monomial m = mono("A", 2, "B", 1);
            assertThat(m.times(Monomial.CONSTANT)).isEqualTo(m);
            assertThat(Monomial.CONSTANT.times(m)).isEqualTo(m);
            assertThat(Monomial.CONSTANT.isConstant()).isTrue();
            assertThat(m.isConstant()).isFalse();
        }

        @Test
        @DisplayName("degree is the sum of the exponents")
        void degree() {
            assertThat(mono("A", 2, "B", 3).degree()).isEqualTo(5);
            assertThat(Monomial.CONSTANT.degree()).isZero();
        }
    }

    @Nested
    @DisplayName("canonical form")
    final class Canonical {

        @Test
        @DisplayName("an absent variable is how a zero exponent is spelled, so one is refused")
        void nonPositiveExponent() {
            assertThatThrownBy(() -> mono("A", 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
            assertThatThrownBy(() -> mono("A", -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the map is copied, so the caller cannot edit a built monomial")
        void defensiveCopy() {
            SortedMap<ProvenanceVariable, Integer> source = new TreeMap<>();
            source.put(v("A"), 1);
            Monomial m = new Monomial(source);
            source.put(v("B"), 9);

            assertThat(m.exponents()).containsOnlyKeys(v("A"));
        }

        @Test
        @DisplayName("renders as a product, with an exponent only where it is above one")
        void render() {
            assertThat(mono("A", 1, "B", 2)).hasToString("A·B^2");
            assertThat(Monomial.CONSTANT).hasToString("1");
        }
    }
}

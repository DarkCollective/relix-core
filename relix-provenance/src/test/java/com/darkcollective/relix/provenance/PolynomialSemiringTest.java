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

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PolynomialSemiring (ℕ[X] lineage)")
final class PolynomialSemiringTest {

    private static final PolynomialSemiring S = PolynomialSemiring.INSTANCE;

    private static final Polynomial X = Polynomial.variable("x");
    private static final Polynomial Y = Polynomial.variable("y");
    private static final Polynomial Z = Polynomial.variable("z");

    @Nested
    @DisplayName("plus (⊕ = polynomial addition)")
    class Plus {

        @Test
        @DisplayName("like monomials add their coefficients")
        void likeTermsAdd() {
            assertThat(S.plus(X, X)).hasToString("2·x");
            assertThat(S.plus(S.plus(X, X), X)).hasToString("3·x");
        }

        @Test
        @DisplayName("unlike monomials become separate terms (rendered in canonical order)")
        void unlikeTerms() {
            assertThat(S.plus(X, Y)).hasToString("x + y");
        }

        @Test
        @DisplayName("zero is the additive identity")
        void identity() {
            assertThat(S.plus(X, S.zero())).isEqualTo(X);
        }
    }

    @Nested
    @DisplayName("times (⊗ = polynomial multiplication)")
    class Times {

        @Test
        @DisplayName("multiplying variables produces a product monomial")
        void productMonomial() {
            assertThat(S.times(X, Y)).hasToString("x·y");
            assertThat(S.times(X, X)).hasToString("x^2");
        }

        @Test
        @DisplayName("distributes over a sum")
        void distributes() {
            // x · (y + z) = x·y + x·z
            assertThat(S.times(X, S.plus(Y, Z))).hasToString("x·y + x·z");
        }

        @Test
        @DisplayName("coefficients multiply")
        void coefficientsMultiply() {
            // (2x) · (3y) = 6·x·y
            Polynomial twoX = S.plus(X, X);
            Polynomial threeY = S.plus(S.plus(Y, Y), Y);
            assertThat(S.times(twoX, threeY)).hasToString("6·x·y");
        }

        @Test
        @DisplayName("zero annihilates")
        void annihilates() {
            assertThat(S.times(X, S.zero())).isEqualTo(S.zero());
        }
    }

    @Nested
    @DisplayName("bounded representation (truncate + flag)")
    class Bounded {

        /** A sum of {@code n} distinct degree-one variables v0..v(n-1). */
        private static Polynomial sumOfVariables(int n) {
            Polynomial acc = S.zero();
            for (int i = 0; i < n; i++) {
                acc = S.plus(acc, Polynomial.variable("v" + i));
            }
            return acc;
        }

        @Test
        @DisplayName("a polynomial at the cap is not truncated")
        void atCapNotTruncated() {
            Polynomial p = sumOfVariables(PolynomialSemiring.MAX_MONOMIALS);
            assertThat(p.size()).isEqualTo(PolynomialSemiring.MAX_MONOMIALS);
            assertThat(p.truncated()).isFalse();
            assertThat(p.toString()).doesNotContain("⋯");
        }

        @Test
        @DisplayName("exceeding the cap keeps MAX_MONOMIALS terms and flags truncation")
        void overCapTruncates() {
            Polynomial p = sumOfVariables(PolynomialSemiring.MAX_MONOMIALS + 50);
            assertThat(p.size()).isEqualTo(PolynomialSemiring.MAX_MONOMIALS);
            assertThat(p.truncated()).isTrue();
            assertThat(p.toString()).endsWith("⋯");
        }

        @Test
        @DisplayName("truncation is contagious through further operations")
        void contagious() {
            Polynomial truncated = sumOfVariables(PolynomialSemiring.MAX_MONOMIALS + 1);
            assertThat(truncated.truncated()).isTrue();
            // contagious from either operand
            assertThat(S.plus(truncated, S.zero()).truncated()).isTrue();
            assertThat(S.plus(S.zero(), truncated).truncated()).isTrue();
            assertThat(S.times(truncated, S.one()).truncated()).isTrue();
            assertThat(S.times(S.one(), truncated).truncated()).isTrue();
        }
    }
    @Test
    @DisplayName("variable(name) is the semiring's own door to a single-variable polynomial")
    void variableDelegatesToPolynomial() {
        assertThat(PolynomialSemiring.variable("t1")).isEqualTo(Polynomial.variable("t1"));
        assertThat(PolynomialSemiring.variable("t1"))
                .as("distinct names are distinct polynomials")
                .isNotEqualTo(PolynomialSemiring.variable("t2"));
    }
}

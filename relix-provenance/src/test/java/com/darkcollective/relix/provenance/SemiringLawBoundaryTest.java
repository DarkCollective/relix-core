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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the commutative-semiring laws actually stop holding, found by feeding
 * {@link SemiringLaws} <em>generated</em> samples instead of hand-picked ones.
 *
 * <h2>What a fixed sample cannot do</h2>
 *
 * <p>Every built-in already asserts the five laws through {@link SemiringLaws}, so the
 * contract is checked — but two of the samples are short lists of tidy values.
 * {@code TropicalSemiring} gets {@code {+∞, 0.0, 2.0, 5.0}}; {@code PolynomialSemiring}
 * gets seven polynomials of at most two monomials against a cap of
 * {@value PolynomialSemiring#MAX_MONOMIALS}. A law quantifies over <em>all</em> values, and
 * a sample that never approaches a boundary cannot test the boundary. Both of those
 * semirings turn out to violate ⊗-associativity, and neither existing test could see it.
 *
 * <p>Two of the six are left alone deliberately: {@code BooleanSemiring} (two values) and
 * {@code SecurityLattice} (four) have finite domains and their existing samples are
 * <em>exhaustive</em>, so generation would add nothing at all.
 *
 * <h2>The shape both failures share</h2>
 *
 * <p>In each case ⊕ is exact and ⊗ is not, for the same underlying reason: <strong>⊕ is a
 * selection and ⊗ is a combination.</strong> Tropical ⊕ is {@code min} and Polynomial ⊕ is
 * a union capped at the smallest n — picking extremes from a set composes associatively, so
 * regrouping cannot change the answer. Tropical ⊗ is floating-point addition and Polynomial
 * ⊗ is a product that is then capped — both build new values whose rounding, or whose
 * surviving terms, depend on which operands were combined first.
 *
 * <h2>Determinism</h2>
 *
 * <p>Generators are seeded from {@link #SEED}, a constant. A generated-input test that
 * fails differently on each run is worse than no test, so the seed does not float: change
 * it deliberately to explore, and when a new seed finds a violation, pin the witness here
 * rather than leaving the search running in the build.
 */
@DisplayName("Semiring laws — the boundaries a fixed sample cannot reach")
final class SemiringLawBoundaryTest {

    /** Fixed, so a failure reproduces. See the class javadoc. */
    private static final long SEED = 20260828L;

    // =========================================================================

    @Nested
    @DisplayName("TropicalSemiring — ⊗ is floating-point addition")
    class Tropical {

        private static final TropicalSemiring S = TropicalSemiring.INSTANCE;

        /**
         * ⊗-associativity is <strong>false</strong>, and the witness is the point: three
         * weights a cost model would produce on any ordinary day.
         *
         * <p>It needs no {@code NaN}, no {@code −∞}, no negative weight — the three cases
         * the class already documents as invalid — and no extreme magnitudes either. It is
         * the plain binary-representation problem: {@code 0.01} and {@code 0.04} are not
         * exact doubles, so the two groupings round differently. This is the smallest
         * two-decimal witness there is; searching {@code a ≤ 20.00, b, c ≤ 2.00} finds it
         * first.
         */
        @Test
        @DisplayName("⊗ does not associate for ordinary decimal weights")
        void timesDoesNotAssociateForDecimalWeights() {
            double a = 0.01d, b = 0.01d, c = 0.04d;

            assertThat(S.times(S.times(a, b), c)).isEqualTo(0.06d);
            assertThat(S.times(a, S.times(b, c))).isEqualTo(0.060000000000000005d);
            assertThat(S.times(S.times(a, b), c)).isNotEqualTo(S.times(a, S.times(b, c)));
        }

        /**
         * The domain where the contract does hold: integral weights below 2^53, where
         * double addition is exact. Hop counts and integer costs are inside it; a weight
         * column of prices or durations in decimal is not.
         */
        @Test
        @DisplayName("the laws hold over generated integral weights")
        void lawsHoldForIntegralWeights() {
            Random random = new Random(SEED);
            List<Double> weights = new ArrayList<>(List.of(Double.POSITIVE_INFINITY, 0.0d));
            for (int i = 0; i < 8; i++) {
                weights.add((double) random.nextInt(1_000_000));
            }
            SemiringLaws.assertLaws(S, weights);
        }

        /**
         * ⊕ is exact regardless — {@code min} chooses an operand, it never computes a new
         * value, so there is nothing to round. Checked over magnitudes spanning 10^±15,
         * where ⊗ has long since failed.
         */
        @Test
        @DisplayName("⊕ associates exactly, at any magnitude")
        void plusAssociatesAtAnyMagnitude() {
            Random random = new Random(SEED);
            List<Double> wild = new ArrayList<>(
                    List.of(Double.POSITIVE_INFINITY, 0.0d, 1e16d, 1e-16d));
            for (int i = 0; i < 6; i++) {
                wild.add(random.nextDouble() * Math.pow(10, random.nextInt(30) - 15));
            }
            for (Double a : wild) {
                for (Double b : wild) {
                    for (Double c : wild) {
                        assertThat(S.plus(S.plus(a, b), c))
                                .as("min associates for %s, %s, %s", a, b, c)
                                .isEqualTo(S.plus(a, S.plus(b, c)));
                    }
                }
            }
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("PolynomialSemiring — the monomial cap")
    class Polynomials {

        private static final PolynomialSemiring S = PolynomialSemiring.INSTANCE;
        private static final int CAP = PolynomialSemiring.MAX_MONOMIALS;

        /** A polynomial of the distinct single-variable monomials {@code v(from) … v(to−1)}. */
        private static Polynomial spread(int from, int to) {
            Polynomial p = S.zero();
            for (int i = from; i < to; i++) {
                p = S.plus(p, Polynomial.variable("v" + i));
            }
            return p;
        }

        /**
         * ⊕ survives the cap intact. Capping keeps the smallest {@code MAX_MONOMIALS} in
         * {@link Monomial}'s deterministic total order, and "the smallest n of a union"
         * does not care how the union was bracketed — so re-associating a sum well past
         * the cap selects exactly the same surviving terms.
         */
        @Test
        @DisplayName("⊕ associates on the terms, well past the cap")
        void plusAssociatesAcrossTheCap() {
            Polynomial a = spread(0, 200), b = spread(150, 350), c = spread(300, 500);

            Polynomial left = S.plus(S.plus(a, b), c);
            Polynomial right = S.plus(a, S.plus(b, c));

            assertThat(left.terms()).hasSize(CAP);   // the cap is genuinely reached
            assertThat(left.terms()).isEqualTo(right.terms());
        }

        /**
         * ⊗ does <strong>not</strong>, and for a reason ⊕ does not share: the cap applies
         * to each intermediate <em>product</em>, so which 256 monomials survive depends on
         * which pair was multiplied first. {@code (A⊗A)} is 170² terms capped to 256 before
         * meeting {@code B}, while {@code (A⊗B)} caps a different 170×171 set — and the two
         * final selections differ.
         *
         * <p>Both sides are sound under-approximations flagged as truncated; they are not
         * the same approximation, which is what the law asserts.
         */
        @Test
        @DisplayName("⊗ does not associate once an intermediate product exceeds the cap")
        void timesDoesNotAssociateAcrossTheCap() {
            Polynomial a = spread(0, CAP * 2 / 3);
            Polynomial b = spread(CAP * 2 / 3, CAP * 4 / 3);

            Polynomial left = S.times(S.times(a, a), b);
            Polynomial right = S.times(a, S.times(a, b));

            assertThat(left.terms()).hasSize(CAP);
            assertThat(right.terms()).hasSize(CAP);
            assertThat(left.truncated()).isTrue();
            assertThat(right.truncated()).isTrue();
            assertThat(left.terms()).isNotEqualTo(right.terms());
        }

        /**
         * Even where the terms agree, whole-record equality does not satisfy the laws:
         * {@code truncated} records that some operation <em>along the way</em> hit the cap,
         * which is a fact about the derivation rather than about the polynomial.
         *
         * <p>The witness uses {@code zero}. On the left nothing is ever truncated, because
         * multiplying by {@code 0} yields the empty polynomial before the large product is
         * formed. On the right the inner product is formed first, truncates, and carries
         * the flag out through a zero result. Both sides are {@code 0}; only one of them
         * remembers passing through a truncation.
         */
        @Test
        @DisplayName("the truncated flag is path-dependent, so record equality is not the algebra")
        void truncationFlagIsPathDependent() {
            Polynomial big = spread(0, CAP * 2 / 3);
            Polynomial bigger = spread(CAP * 2 / 3, CAP * 4 / 3);

            Polynomial left = S.times(S.times(S.zero(), big), bigger);
            Polynomial right = S.times(S.zero(), S.times(big, bigger));

            assertThat(left.terms()).as("both sides are the zero polynomial").isEmpty();
            assertThat(right.terms()).isEmpty();
            assertThat(left.truncated()).isFalse();
            assertThat(right.truncated()).isTrue();
            assertThat(left).isNotEqualTo(right);
        }

        /** Below the cap there is nothing to approximate, so the full contract holds. */
        @Test
        @DisplayName("the laws hold in full while every intermediate stays under the cap")
        void lawsHoldBelowTheCap() {
            SemiringLaws.assertLaws(S, List.of(
                    S.zero(), S.one(), spread(0, 2), spread(2, 4), S.times(spread(0, 2), spread(2, 4))));
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("CountingSemiring — exact arithmetic, so generation finds nothing")
    class Counting {

        /**
         * A negative result worth recording: ℕ over {@link BigInteger} is exact, so unlike
         * the tropical case there is no magnitude at which the laws degrade. Without this,
         * the absence of a boundary above would read as an oversight rather than a finding.
         */
        @Test
        @DisplayName("the laws hold over generated naturals of wildly different sizes")
        void lawsHoldOverGeneratedNaturals() {
            Random random = new Random(SEED);
            List<BigInteger> counts = new ArrayList<>(List.of(BigInteger.ZERO, BigInteger.ONE));
            for (int i = 0; i < 6; i++) {
                counts.add(new BigInteger(1 + random.nextInt(160), random).add(BigInteger.ONE));
            }
            SemiringLaws.assertLaws(CountingSemiring.INSTANCE, counts);
        }
    }
}

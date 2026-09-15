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
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Polynomial / Monomial / ProvenanceVariable")
final class PolynomialTest {

    @Nested
    @DisplayName("ProvenanceVariable")
    class Variable {

        @Test
        @DisplayName("renders as its name and orders by name")
        void nameAndOrder() {
            assertThat(new ProvenanceVariable("Orders#1")).hasToString("Orders#1");
            assertThat(new ProvenanceVariable("a")).isLessThan(new ProvenanceVariable("b"));
        }

        @Test
        @DisplayName("rejects a null or blank name")
        void rejectsBlank() {
            assertThatThrownBy(() -> new ProvenanceVariable(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ProvenanceVariable("  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
            assertThatThrownBy(() -> new ProvenanceVariable("x", null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("a bare variable has no structured source")
        void bareHasNoSource() {
            assertThat(new ProvenanceVariable("Orders#1").source()).isEmpty();
        }

        @Test
        @DisplayName("of(SourceRef) carries the source and takes its canonical name")
        void structuredFromSource() {
            TreeMap<String, String> cols = new TreeMap<>();
            cols.put("id", "42");
            SourceRef ref = new SourceRef("Orders", 1, cols);
            ProvenanceVariable v = ProvenanceVariable.of(ref);
            assertThat(v.name()).isEqualTo("Orders#1");
            assertThat(v.source()).contains(ref);
            assertThat(v.label(false)).isEqualTo("Orders#1");
            assertThat(v.label(true)).isEqualTo("Orders#1{id: 42}");
        }

        @Test
        @DisplayName("identity is by name only — a structured and a bare variable with the same name are equal")
        void equalByNameRegardlessOfSource() {
            SourceRef ref = new SourceRef("Orders", 1, new TreeMap<>());
            ProvenanceVariable structured = ProvenanceVariable.of(ref);
            ProvenanceVariable bare = new ProvenanceVariable("Orders#1");
            assertThat(structured).isEqualTo(bare).hasSameHashCodeAs(bare);
            assertThat(structured).isEqualByComparingTo(bare);
        }

        @Test
        @DisplayName("label(true) falls back to the name when there is no source")
        void detailedLabelFallsBack() {
            assertThat(new ProvenanceVariable("Orders#1").label(true)).isEqualTo("Orders#1");
        }
    }

    @Nested
    @DisplayName("Monomial")
    class MonomialCases {

        private static final ProvenanceVariable A = new ProvenanceVariable("a");
        private static final ProvenanceVariable B = new ProvenanceVariable("b");

        @Test
        @DisplayName("the constant monomial renders as 1 and has degree 0")
        void constant() {
            assertThat(Monomial.CONSTANT).hasToString("1");
            assertThat(Monomial.CONSTANT.degree()).isZero();
            assertThat(Monomial.CONSTANT.isConstant()).isTrue();
        }

        @Test
        @DisplayName("multiplication adds exponents of shared variables")
        void timesAddsExponents() {
            Monomial ab = Monomial.of(A).times(Monomial.of(B));
            assertThat(ab).hasToString("a·b");
            assertThat(Monomial.of(A).times(Monomial.of(A))).hasToString("a^2");
            assertThat(ab.degree()).isEqualTo(2);
        }

        @Test
        @DisplayName("orders by degree then variables")
        void ordering() {
            assertThat(Monomial.of(A)).isLessThan(Monomial.of(A).times(Monomial.of(B)));  // deg 1 < 2
            assertThat(Monomial.of(A)).isLessThan(Monomial.of(B));                          // a < b
            // same degree, shared leading variable → compared on the next entry (a·b < a·c)
            ProvenanceVariable c = new ProvenanceVariable("c");
            assertThat(Monomial.of(A).times(Monomial.of(B)))
                    .isLessThan(Monomial.of(A).times(Monomial.of(c)));
        }

        @Test
        @DisplayName("rejects a non-positive exponent")
        void rejectsNonPositiveExponent() {
            TreeMap<ProvenanceVariable, Integer> bad = new TreeMap<>();
            bad.put(A, 0);
            assertThatThrownBy(() -> new Monomial(bad))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
        }
    }

    @Nested
    @DisplayName("Polynomial")
    class PolynomialCases {

        @Test
        @DisplayName("zero and one render as 0 and 1")
        void zeroAndOne() {
            assertThat(Polynomial.zero()).hasToString("0");
            assertThat(Polynomial.zero().isZero()).isTrue();
            assertThat(Polynomial.one()).hasToString("1");
        }

        @Test
        @DisplayName("a single variable renders as its name")
        void variable() {
            assertThat(Polynomial.variable("Orders#1")).hasToString("Orders#1");
            assertThat(Polynomial.variable("Orders#1").size()).isEqualTo(1);
        }

        @Test
        @DisplayName("variable(SourceRef) carries columns; render(true) expands them, render(false) does not")
        void structuredVariableRender() {
            TreeMap<String, String> oCols = new TreeMap<>();
            oCols.put("id", "42");
            TreeMap<String, String> cCols = new TreeMap<>();
            cCols.put("cid", "1");
            Polynomial joined = PolynomialSemiring.INSTANCE.times(
                    Polynomial.variable(new SourceRef("Orders", 1, oCols)),
                    Polynomial.variable(new SourceRef("Customers", 1, cCols)));
            // Compact form (the default toString) is unchanged.
            assertThat(joined.render(false)).isEqualTo("Customers#1·Orders#1");
            assertThat(joined).hasToString("Customers#1·Orders#1");
            // Detailed form expands each variable to its captured columns.
            assertThat(joined.render(true))
                    .isEqualTo("Customers#1{cid: 1}·Orders#1{id: 42}");
        }

        @Test
        @DisplayName("render(true) on a bare-variable polynomial equals its compact form")
        void detailedRenderBareVariables() {
            Polynomial p = PolynomialSemiring.INSTANCE.plus(
                    Polynomial.variable("a"), Polynomial.variable("b"));
            assertThat(p.render(true)).isEqualTo(p.render(false)).isEqualTo("a + b");
        }

        @Test
        @DisplayName("drops zero-coefficient terms during canonicalisation")
        void dropsZeroCoefficients() {
            TreeMap<Monomial, BigInteger> terms = new TreeMap<>();
            terms.put(Monomial.of(new ProvenanceVariable("a")), BigInteger.ZERO);
            terms.put(Monomial.of(new ProvenanceVariable("b")), BigInteger.valueOf(3));
            Polynomial p = new Polynomial(terms, false);
            assertThat(p.size()).isEqualTo(1);
            assertThat(p).hasToString("3·b");
        }

        @Test
        @DisplayName("rejects a negative coefficient")
        void rejectsNegativeCoefficient() {
            TreeMap<Monomial, BigInteger> terms = new TreeMap<>();
            terms.put(Monomial.CONSTANT, BigInteger.valueOf(-1));
            assertThatThrownBy(() -> new Polynomial(terms, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-negative");
        }

        @Test
        @DisplayName("a constant term renders as the bare coefficient")
        void constantTerm() {
            assertThat(Polynomial.singleton(Monomial.CONSTANT, BigInteger.valueOf(5)))
                    .hasToString("5");
        }

        @Test
        @DisplayName("a truncated zero polynomial renders with the truncation mark")
        void truncatedZero() {
            assertThat(new Polynomial(new TreeMap<>(), true)).hasToString("0 + ⋯");
        }
    }
}

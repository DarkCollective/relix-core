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
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What each semiring makes of a base tuple.
 *
 * <p>This is the half of the provider seam that decides whether an installed semiring can
 * do anything useful. The engine used to answer it by recognising four of its own
 * singletons, so a semiring it did not recognise fell through to {@code one()} and a
 * weighted closure over it computed nothing — the shape of vacuous pass that a discovery
 * mechanism alone would not have caught.
 */
@DisplayName("Semiring.base — how a base tuple becomes an annotation")
final class SemiringBaseTest {

    /** A base tuple standing in for one leaf row. */
    private record Tuple(String source, long ordinal, Optional<BigDecimal> weight,
                         SortedMap<String, String> columns) implements BaseTuple {
    }

    private static BaseTuple weighing(String weight) {
        SortedMap<String, String> columns = new TreeMap<>();
        columns.put("cost", weight);
        return new Tuple("Edges", 3L,
                weight == null ? Optional.empty() : Optional.of(new BigDecimal(weight)),
                columns);
    }

    private static final BaseTuple UNWEIGHTED = weighing(null);

    @Test
    @DisplayName("boolean ignores the tuple: every base tuple is present")
    void booleanIsConstant() {
        assertThat(BooleanSemiring.INSTANCE.base(weighing("7"))).isTrue();
        assertThat(BooleanSemiring.INSTANCE.base(UNWEIGHTED)).isTrue();
    }

    @Test
    @DisplayName("the security lattice ignores the tuple")
    void securityIsConstant() {
        assertThat(SecurityLattice.INSTANCE.base(weighing("7")))
                .isEqualTo(SecurityLattice.INSTANCE.one());
    }

    @Test
    @DisplayName("tropical reads the weight as a cost; an unweighted edge is free")
    void tropicalReadsCost() {
        assertThat(TropicalSemiring.INSTANCE.base(weighing("2.5"))).isEqualTo(2.5d);
        assertThat(TropicalSemiring.INSTANCE.base(UNWEIGHTED))
                .isEqualTo(TropicalSemiring.INSTANCE.one());
    }

    @Test
    @DisplayName("counting reads the weight as a multiplicity; an unweighted edge counts once")
    void countingReadsMultiplicity() {
        assertThat(CountingSemiring.INSTANCE.base(weighing("4")))
                .isEqualTo(java.math.BigInteger.valueOf(4));
        assertThat(CountingSemiring.INSTANCE.base(UNWEIGHTED))
                .isEqualTo(java.math.BigInteger.ONE);
    }

    @Test
    @DisplayName("counting truncates a fractional weight rather than rejecting it")
    void countingTruncates() {
        assertThat(CountingSemiring.INSTANCE.base(weighing("4.9")))
                .isEqualTo(java.math.BigInteger.valueOf(4));
    }

    @Test
    @DisplayName("cheapest-route mints a token naming the occurrence, and carries the cost")
    void pathCostMintsAToken() {
        PathCost annotated = PathCostSemiring.INSTANCE.base(weighing("2.5"));
        assertThat(annotated.cost()).isEqualTo(2.5d);
        assertThat(annotated.routes()).singleElement()
                .isEqualTo(Route.of("Edges#3"));
    }

    @Test
    @DisplayName("cheapest-route weighs an unweighted edge zero, keeping the witness")
    void pathCostUnweighted() {
        assertThat(PathCostSemiring.INSTANCE.base(UNWEIGHTED).cost()).isZero();
    }

    @Test
    @DisplayName("lineage mints a variable naming the occurrence and capturing its columns")
    void lineageMintsAVariable() {
        Polynomial p = PolynomialSemiring.INSTANCE.base(weighing("7"));
        assertThat(p.toString()).contains("Edges#3");
    }

    @Test
    @DisplayName("an installed semiring reaches the same shapes with no engine change")
    void installedSemiringReadsItsOwnWeight() {
        assertThat(KinshipSemiring.INSTANCE.base(weighing("0.25"))).isEqualTo(0.25d);
        // Its own answer for an absent weight — one generation — rather than one().
        assertThat(KinshipSemiring.INSTANCE.base(UNWEIGHTED)).isEqualTo(0.5d);
    }
}

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

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Semirings.byName")
final class SemiringsTest {

    @Test
    @DisplayName("resolves each canonical built-in name")
    void canonicalNames() {
        assertThat(Semirings.byName("boolean")).containsSame(BooleanSemiring.INSTANCE);
        assertThat(Semirings.byName("counting")).containsSame(CountingSemiring.INSTANCE);
        assertThat(Semirings.byName("tropical")).containsSame(TropicalSemiring.INSTANCE);
        assertThat(Semirings.byName("security")).containsSame(SecurityLattice.INSTANCE);
        assertThat(Semirings.byName("lineage")).containsSame(PolynomialSemiring.INSTANCE);
        assertThat(Semirings.byName("cheapest-route")).containsSame(PathCostSemiring.INSTANCE);
    }

    @Test
    @DisplayName("resolves the friendly aliases")
    void aliases() {
        assertThat(Semirings.byName("set")).containsSame(BooleanSemiring.INSTANCE);
        assertThat(Semirings.byName("bag")).containsSame(CountingSemiring.INSTANCE);
        assertThat(Semirings.byName("natural")).containsSame(CountingSemiring.INSTANCE);
        assertThat(Semirings.byName("shortest-path")).containsSame(TropicalSemiring.INSTANCE);
        assertThat(Semirings.byName("lattice")).containsSame(SecurityLattice.INSTANCE);
        assertThat(Semirings.byName("polynomial")).containsSame(PolynomialSemiring.INSTANCE);
        assertThat(Semirings.byName("why")).containsSame(PolynomialSemiring.INSTANCE);
        assertThat(Semirings.byName("best-path")).containsSame(PathCostSemiring.INSTANCE);
    }

    @Test
    @DisplayName("is case-insensitive")
    void caseInsensitive() {
        assertThat(Semirings.byName("BOOLEAN")).containsSame(BooleanSemiring.INSTANCE);
        assertThat(Semirings.byName("Counting")).containsSame(CountingSemiring.INSTANCE);
    }

    @Test
    @DisplayName("returns empty for an unknown or null name")
    void unknownOrNull() {
        assertThat(Semirings.byName("nonesuch")).isEmpty();
        assertThat(Semirings.byName("")).isEmpty();
        assertThat(Semirings.byName(null)).isEmpty();
    }

    @Test
    @DisplayName("names() lists the canonical built-ins in declaration order")
    void names() {
        assertThat(Semirings.names())
                .containsExactly("boolean", "counting", "tropical", "security", "lineage",
                        "cheapest-route");
    }
}

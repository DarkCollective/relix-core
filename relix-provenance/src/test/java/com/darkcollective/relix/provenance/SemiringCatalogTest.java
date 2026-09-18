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

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SemiringCatalog")
final class SemiringCatalogTest {

    /** A semiring that is not any of the built-ins, for clash and priority checks. */
    private static final Semiring<Double> OTHER = new Semiring<>() {
        @Override
        public Double zero() {
            return 0.0d;
        }

        @Override
        public Double one() {
            return 1.0d;
        }

        @Override
        public Double plus(Double a, Double b) {
            return Math.max(a, b);
        }

        @Override
        public Double times(Double a, Double b) {
            return Math.min(a, b);
        }
    };

    private static SemiringLibrary library(String name, int priority, NamedSemiring... entries) {
        return new SemiringLibrary() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public List<NamedSemiring> semirings() {
                return List.of(entries);
            }
        };
    }

    @Nested
    @DisplayName("discovery")
    final class Discovery {

        @Test
        @DisplayName("finds a semiring registered in META-INF/services")
        void findsRegistered() {
            assertThat(SemiringCatalog.installed().byName("kinship"))
                    .contains(KinshipSemiring.INSTANCE);
        }

        @Test
        @DisplayName("a discovered semiring resolves by its alias too")
        void alias() {
            assertThat(SemiringCatalog.installed().byName("consanguinity"))
                    .contains(KinshipSemiring.INSTANCE);
        }

        @Test
        @DisplayName("the bundled semirings survive discovery")
        void builtinsSurvive() {
            assertThat(SemiringCatalog.installed().names())
                    .contains("boolean", "counting", "tropical", "security", "lineage",
                            "cheapest-route");
        }

        @Test
        @DisplayName("a discovered entry carries the description it declared")
        void description() {
            assertThat(SemiringCatalog.installed().entry("kinship"))
                    .get()
                    .extracting(NamedSemiring::description, NamedSemiring::name)
                    .containsExactly(
                            "Coefficient of relationship — alternative lines of descent add, "
                                    + "successive generations halve.",
                            "kinship");
        }

        @Test
        @DisplayName("discover() re-runs the scan and agrees with installed()")
        void rediscovery() {
            assertThat(SemiringCatalog.discover().names())
                    .isEqualTo(SemiringCatalog.installed().names());
        }
    }

    @Nested
    @DisplayName("resolution")
    final class Resolution {

        @Test
        @DisplayName("is case-insensitive")
        void caseInsensitive() {
            SemiringCatalog catalog = SemiringCatalog.withBuiltins(List.of());
            assertThat(catalog.byName("TROPICAL")).contains(TropicalSemiring.INSTANCE);
            assertThat(catalog.byName("Shortest-Path")).contains(TropicalSemiring.INSTANCE);
        }

        @Test
        @DisplayName("an unknown name is empty, not an error")
        void unknown() {
            assertThat(SemiringCatalog.installed().byName("no-such-semiring")).isEmpty();
        }

        @Test
        @DisplayName("a null name is empty")
        void nullName() {
            assertThat(SemiringCatalog.installed().byName(null)).isEmpty();
        }

        @Test
        @DisplayName("names() lists canonical names only, never aliases")
        void namesOmitAliases() {
            assertThat(SemiringCatalog.withBuiltins(List.of()).names())
                    .contains("tropical")
                    .doesNotContain("shortest-path", "set", "bag", "why");
        }
    }

    @Nested
    @DisplayName("assembly")
    final class Assembly {

        @Test
        @DisplayName("empty() holds nothing at all")
        void empty() {
            assertThat(SemiringCatalog.empty().names()).isEmpty();
            assertThat(SemiringCatalog.empty().byName("boolean")).isEmpty();
        }

        @Test
        @DisplayName("of() holds exactly what it was given — the built-ins are not added")
        void ofIsExact() {
            SemiringCatalog catalog = SemiringCatalog.of(List.of(
                    library("only", 0, NamedSemiring.of("widest", OTHER, "Max-min."))));
            assertThat(catalog.names()).containsExactly("widest");
            assertThat(catalog.byName("boolean")).isEmpty();
        }

        @Test
        @DisplayName("withBuiltins() joins the bundled six")
        void withBuiltins() {
            SemiringCatalog catalog = SemiringCatalog.withBuiltins(List.of(
                    library("extra", 0, NamedSemiring.of("widest", OTHER, "Max-min."))));
            assertThat(catalog.names()).contains("widest", "boolean", "tropical");
        }
    }

    @Nested
    @DisplayName("clashes")
    final class Clashes {

        @Test
        @DisplayName("a higher-priority library replaces a bundled semiring")
        void higherPriorityWins() {
            SemiringCatalog catalog = SemiringCatalog.withBuiltins(List.of(
                    library("override", 10,
                            NamedSemiring.of("tropical", OTHER, "Not min-plus."))));
            assertThat(catalog.byName("tropical")).contains(OTHER);
        }

        @Test
        @DisplayName("a lower-priority library does not disturb a bundled semiring")
        void lowerPriorityLoses() {
            SemiringCatalog catalog = SemiringCatalog.withBuiltins(List.of(
                    library("filler", -10,
                            NamedSemiring.of("tropical", OTHER, "Not min-plus."))));
            assertThat(catalog.byName("tropical")).contains(TropicalSemiring.INSTANCE);
        }

        @Test
        @DisplayName("a clash on one name leaves the loser's other names alone")
        void partialClash() {
            SemiringCatalog catalog = SemiringCatalog.withBuiltins(List.of(
                    library("partial", -10, new NamedSemiring(
                            "tropical", List.of("max-min"), OTHER, "Not min-plus."))));
            assertThat(catalog.byName("tropical")).contains(TropicalSemiring.INSTANCE);
            assertThat(catalog.byName("max-min")).contains(OTHER);
        }

        @Test
        @DisplayName("a semiring that loses every one of its names is not listed")
        void fullyClashedIsUnlisted() {
            SemiringCatalog catalog = SemiringCatalog.withBuiltins(List.of(
                    library("shadow", -10, new NamedSemiring(
                            "tropical", List.of("shortest-path"), OTHER, "Not min-plus."))));
            assertThat(catalog.entries())
                    .extracting(NamedSemiring::semiring)
                    .doesNotContain(OTHER);
        }
    }
}

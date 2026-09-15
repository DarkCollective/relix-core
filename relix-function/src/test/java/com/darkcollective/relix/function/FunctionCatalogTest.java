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
package com.darkcollective.relix.function;

import com.darkcollective.relix.function.TestFunctions.Counter;
import com.darkcollective.relix.function.TestFunctions.Library;
import com.darkcollective.relix.function.TestFunctions.Marker;
import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("FunctionCatalog")
final class FunctionCatalogTest {

    @Nested
    @DisplayName("indexing")
    final class Indexing {

        @Test
        @DisplayName("finds a scalar function a library offered")
        void findsAScalarFunction() {
            Marker upper = new Marker("UCase", "u");
            FunctionCatalog catalog = FunctionCatalog.of(Library.of("test", upper));

            assertThat(catalog.scalar("UCase")).contains(upper);
            assertThat(catalog.scalars()).containsExactly(upper);
            assertThat(catalog.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("finds an aggregate a library offered")
        void findsAnAggregate() {
            Counter counter = new Counter("Tally");
            FunctionCatalog catalog = FunctionCatalog.of(Library.aggregates("test", counter));

            assertThat(catalog.aggregate("Tally")).contains(counter);
            assertThat(catalog.aggregates()).containsExactly(counter);
            assertThat(catalog.scalar("Tally"))
                    .as("the two namespaces are separate — an aggregate is not a scalar")
                    .isEmpty();
        }

        @Test
        @DisplayName("keeps the order the libraries offered functions in")
        void keepsOfferingOrder() {
            FunctionCatalog catalog = FunctionCatalog.of(
                    Library.of("first", new Marker("A", "a"), new Marker("B", "b")),
                    Library.of("second", new Marker("C", "c")));

            assertThat(catalog.scalars()).extracting(ScalarFunction::name)
                    .containsExactly("A", "B", "C");
        }

        @Test
        @DisplayName("reports the declared spelling of every name it holds")
        void reportsDeclaredNames() {
            FunctionCatalog catalog = FunctionCatalog.of(
                    Library.of("scalars", new Marker("UCase", "u")),
                    Library.aggregates("aggregates", new Counter("Tally")));

            assertThat(catalog.names()).containsExactly("UCase", "Tally");
        }

        @Test
        @DisplayName("rejects a library that offers a null list")
        void rejectsANullList() {
            FunctionLibrary broken = new Library("broken", 0, null, List.of());

            assertThatNullPointerException()
                    .isThrownBy(() -> FunctionCatalog.of(broken))
                    .withMessageContaining("broken")
                    .withMessageContaining("scalarFunctions");
        }
    }

    @Nested
    @DisplayName("case-insensitivity")
    final class CaseInsensitivity {

        @Test
        @DisplayName("resolves a name however it is spelled at the call site")
        void resolvesAnySpelling() {
            Marker upper = new Marker("UCase", "u");
            FunctionCatalog catalog = FunctionCatalog.of(Library.of("test", upper));

            assertThat(catalog.scalar("ucase")).contains(upper);
            assertThat(catalog.scalar("UCASE")).contains(upper);
            assertThat(catalog.scalar("uCaSe")).contains(upper);
        }

        @Test
        @DisplayName("applies to aggregates too")
        void appliesToAggregates() {
            Counter counter = new Counter("Tally");
            FunctionCatalog catalog = FunctionCatalog.of(Library.aggregates("test", counter));

            assertThat(catalog.aggregate("TALLY")).contains(counter);
        }

        @Test
        @DisplayName("keeps the declared spelling for display")
        void keepsTheDeclaredSpelling() {
            FunctionCatalog catalog = FunctionCatalog.of(
                    Library.of("test", new Marker("UCase", "u")));

            assertThat(catalog.names()).containsExactly("UCase");
        }

        @Test
        @DisplayName("two spellings of one name in one library are one function")
        void twoSpellingsAreOneFunction() {
            Marker first = new Marker("UCase", "first");
            FunctionCatalog catalog = FunctionCatalog.of(
                    Library.of("test", first, new Marker("ucase", "second")));

            assertThat(catalog.scalars()).containsExactly(first);
        }
    }

    @Nested
    @DisplayName("name clashes")
    final class Clashes {

        @Test
        @DisplayName("the higher priority wins, whichever order the libraries came in")
        void higherPriorityWins() {
            Marker builtin = new Marker("Sqrt", "builtin");
            Marker replacement = new Marker("Sqrt", "replacement");

            assertThat(FunctionCatalog.of(
                            Library.at("builtin", 0, builtin),
                            Library.at("acme", 10, replacement))
                    .scalar("Sqrt")).contains(replacement);

            assertThat(FunctionCatalog.of(
                            Library.at("acme", 10, replacement),
                            Library.at("builtin", 0, builtin))
                    .scalar("Sqrt")).contains(replacement);
        }

        @Test
        @DisplayName("a lower-priority library fills gaps without disturbing what it clashes with")
        void lowerPriorityFillsGaps() {
            Marker builtin = new Marker("Sqrt", "builtin");
            Marker shadowed = new Marker("Sqrt", "shadowed");
            Marker extra = new Marker("Haversine", "extra");

            FunctionCatalog catalog = FunctionCatalog.of(
                    Library.at("builtin", 0, builtin),
                    new Library("acme", -5, List.of(shadowed, extra), List.of()));

            assertThat(catalog.scalar("Sqrt")).contains(builtin);
            assertThat(catalog.scalar("Haversine")).contains(extra);
        }

        @Test
        @DisplayName("equal priorities are broken by discovery order")
        void equalPrioritiesKeepDiscoveryOrder() {
            Marker first = new Marker("Sqrt", "first");
            Marker second = new Marker("Sqrt", "second");

            assertThat(FunctionCatalog.of(Library.of("a", first), Library.of("b", second))
                    .scalar("Sqrt")).contains(first);
            assertThat(FunctionCatalog.of(Library.of("b", second), Library.of("a", first))
                    .scalar("Sqrt")).contains(second);
        }

        @Test
        @DisplayName("aggregates clash on their own names, not the scalar ones")
        void aggregatesClashSeparately() {
            Counter kept = new Counter("Tally");
            Counter ignored = new Counter("tally");

            FunctionCatalog catalog = FunctionCatalog.of(
                    Library.aggregates("a", kept),
                    Library.aggregates("b", ignored));

            assertThat(catalog.aggregates()).containsExactly(kept);
        }

        @Test
        @DisplayName("the warning names both libraries, their priorities, and the winner")
        void theWarningNamesBothLibraries() {
            // The clash is reported rather than passed over, and a warning is only
            // useful if it says enough to act on: which two libraries, and why this one.
            String message = FunctionCatalog.clashMessage("scalar function", "Sqrt",
                    Library.at("acme", 10), Library.at("builtin", 0));

            assertThat(message)
                    .contains("scalar function 'Sqrt'")
                    .contains("keeping the one from 'acme' (priority 10)")
                    .contains("ignoring 'builtin' (priority 0)");
        }
    }

    @Nested
    @DisplayName("empty")
    final class Empty {

        @Test
        @DisplayName("holds nothing and resolves nothing")
        void holdsNothing() {
            FunctionCatalog catalog = FunctionCatalog.empty();

            assertThat(catalog.isEmpty()).isTrue();
            assertThat(catalog.scalars()).isEmpty();
            assertThat(catalog.aggregates()).isEmpty();
            assertThat(catalog.names()).isEmpty();
            assertThat(catalog.scalar("UCase")).isEmpty();
            assertThat(catalog.aggregate("Tally")).isEmpty();
        }

        @Test
        @DisplayName("a library offering nothing leaves the catalogue empty")
        void anEmptyLibraryLeavesItEmpty() {
            // Written the smallest way the SPI allows: a library of scalar functions
            // should never have to mention aggregates, so both lists default to empty.
            FunctionLibrary minimal = new TestFunctions.MinimalLibrary("silent");

            assertThat(minimal.scalarFunctions()).isEmpty();
            assertThat(minimal.aggregateFunctions()).isEmpty();
            assertThat(minimal.priority()).isEqualTo(0);
            assertThat(FunctionCatalog.of(minimal).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("…and serves no documentation, which is the default's whole job")
        void aMinimalLibraryDocumentsNothing() {
            // A library written the smallest way the SPI allows must not have to
            // implement documentation() to be installable — the default answers empty
            // for every key, including one it might plausibly own.
            FunctionLibrary minimal = new TestFunctions.MinimalLibrary("silent");
            assertThat(minimal.documentation("functions/string/len.md")).isEmpty();
            assertThat(minimal.documentation("anything")).isEmpty();
        }

        @Test
        @DisplayName("a catalogue of aggregates alone is not empty")
        void aggregatesAloneAreNotEmpty() {
            // isEmpty is `scalars.isEmpty() && aggregates.isEmpty()`, so a library
            // offering only aggregates is the case the second half decides — and the
            // one a scalar-only test never reaches.
            FunctionCatalog catalog = FunctionCatalog.of(
                    TestFunctions.Library.aggregates("counting", new TestFunctions.Counter("Tally")));
            assertThat(catalog.scalars()).isEmpty();
            assertThat(catalog.aggregates()).isNotEmpty();
            assertThat(catalog.isEmpty()).isFalse();
        }
    }

    @Nested
    @DisplayName("resolve")
    final class Resolve {

        @Test
        @DisplayName("finds the function by name, whatever the argument types")
        void findsByName() {
            Marker upper = new Marker("UCase", "u");
            FunctionCatalog catalog = FunctionCatalog.of(Library.of("test", upper));

            assertThat(catalog.resolve("ucase", List.of(ScalarType.STRING))).contains(upper);
            assertThat(catalog.resolve("ucase", List.of())).contains(upper);
            assertThat(catalog.resolve("nosuch", List.of())).isEmpty();
        }

        @Test
        @DisplayName("requires the argument-type list, even when it holds nothing")
        void requiresTheTypeList() {
            FunctionCatalog catalog = FunctionCatalog.empty();

            assertThatNullPointerException()
                    .isThrownBy(() -> catalog.resolve("UCase", null));
        }
    }

    @Nested
    @DisplayName("discovery")
    final class Discovery {

        @Test
        @DisplayName("finds a library declared as a service provider")
        void findsADeclaredProvider() {
            FunctionCatalog catalog = FunctionCatalog.discover();

            assertThat(catalog.scalar("discoveredfn"))
                    .as("the library registered in META-INF/services — if this is empty, "
                            + "discovery is broken, which is the one failure that makes "
                            + "every function unknown at once")
                    .isPresent();
        }
    }

    @Nested
    @DisplayName("immutability")
    final class Immutability {

        @Test
        @DisplayName("a library's later changes do not reach a built catalogue")
        void isNotAViewOfTheLibraries() {
            List<FunctionLibrary> libraries = new java.util.ArrayList<>();
            libraries.add(Library.of("test", new Marker("UCase", "u")));

            FunctionCatalog catalog = FunctionCatalog.of(libraries);
            libraries.add(Library.of("late", new Marker("Late", "l")));

            assertThat(catalog.scalar("Late")).isEmpty();
        }
    }
}

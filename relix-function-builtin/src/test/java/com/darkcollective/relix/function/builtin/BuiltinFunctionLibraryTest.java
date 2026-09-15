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
package com.darkcollective.relix.function.builtin;

import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.function.FunctionLibrary;
import com.darkcollective.relix.function.LazyScalarFunction;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.symbol.FunctionProperty;
import com.darkcollective.relix.function.StrictScalarFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The library as a whole: what it offers, how it is found, and the claims that hold
 * across every definition in it.
 */
@DisplayName("The built-in function library")
final class BuiltinFunctionLibraryTest {

    /**
     * The 52 the port had to reproduce function for function, plus {@code Entries}, which
     * is the first built-in that was never in the library the port started from.
     */
    private static final int BUILTIN_COUNT = 53;

    /** The eight aggregates: five SQL reducers, one that gathers, two that pick a row. */
    private static final int AGGREGATE_COUNT = 8;

    private static final List<ScalarFunction> FUNCTIONS =
            new BuiltinFunctionLibrary().scalarFunctions();

    @Nested
    @DisplayName("Discovery")
    final class Discovery {

        @Test
        @DisplayName("is found through ServiceLoader, which is how the engine will find it")
        void isDiscovered() {
            List<String> names = ServiceLoader.load(FunctionLibrary.class).stream()
                    .map(provider -> provider.get().name())
                    .toList();

            assertThat(names).contains(BuiltinFunctionLibrary.NAME);
        }

        @Test
        @DisplayName("ships at the default priority, so any library can replace a function")
        void priority() {
            assertThat(new BuiltinFunctionLibrary().priority()).isZero();
        }

        @Test
        @DisplayName("offers the eight aggregates, each once")
        void aggregates() {
            List<String> names = new BuiltinFunctionLibrary().aggregateFunctions().stream()
                    .map(fn -> fn.signature().canonicalName())
                    .toList();

            assertThat(names).hasSize(AGGREGATE_COUNT).doesNotHaveDuplicates()
                    .containsExactlyInAnyOrder("count", "sum", "avg", "min", "max",
                            "collect", "argmax", "argmin");
        }

        @Test
        @DisplayName("indexes into a catalogue with no name lost to a clash")
        void catalogue() {
            FunctionCatalog catalog = FunctionCatalog.of(new BuiltinFunctionLibrary());

            assertThat(catalog.scalars()).hasSize(BUILTIN_COUNT);
            assertThat(catalog.aggregates()).hasSize(AGGREGATE_COUNT);
            assertThat(catalog.names()).hasSize(BUILTIN_COUNT + AGGREGATE_COUNT);
            assertThat(catalog.scalar("ucase")).isPresent();
            assertThat(catalog.scalar("UCASE")).isPresent();
            assertThat(catalog.scalar("nosuchfunction")).isEmpty();
        }
    }

    @Nested
    @DisplayName("What it offers")
    final class Contents {

        @Test
        @DisplayName("every built-in, and each of them once")
        void everyFunctionOnce() {
            List<String> names = FUNCTIONS.stream()
                    .map(fn -> fn.signature().canonicalName())
                    .toList();

            assertThat(names).hasSize(BUILTIN_COUNT).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the seven documented categories, complete")
        void categories() {
            Map<String, Long> byCategory = FUNCTIONS.stream()
                    .collect(Collectors.groupingBy(fn -> fn.signature().category(),
                            Collectors.counting()));

            assertThat(byCategory).containsOnly(
                    Map.entry("string", 13L),
                    Map.entry("math", 15L),
                    Map.entry("datetime", 16L),
                    Map.entry("conditional", 3L),
                    Map.entry("typecheck", 2L),
                    Map.entry("conversion", 3L),
                    Map.entry("nested", 1L));
        }

        @Test
        @DisplayName("exactly the three special forms are lazy")
        void specialForms() {
            Set<String> lazy = FUNCTIONS.stream()
                    .filter(fn -> fn instanceof LazyScalarFunction)
                    .map(fn -> fn.signature().canonicalName())
                    .collect(Collectors.toSet());

            assertThat(lazy)
                    .as("a lazy function skips work its meaning says to skip; every "
                            + "other built-in reads all of its arguments")
                    .containsExactlyInAnyOrder("iif", "nz", "coalesce");
        }

        @Test
        @DisplayName("every other definition is strict")
        void everythingElseIsStrict() {
            assertThat(FUNCTIONS).allMatch(fn -> fn instanceof StrictScalarFunction
                    || fn instanceof LazyScalarFunction);
        }
    }

    @Nested
    @DisplayName("Claims that hold across every definition")
    final class Invariants {

        @Test
        @DisplayName("the declared arity covers the declared parameters")
        void arityCoversParameters() {
            for (ScalarFunction fn : FUNCTIONS) {
                var signature = fn.signature();
                assertThat(signature.arity().min())
                        .as(signature.name() + " accepts fewer arguments than it has parameters")
                        .isLessThanOrEqualTo(signature.parameters().size());
            }
        }

        /**
         * The three volatility classes, which are what a function that reads ambient
         * state gets sorted into.
         *
         * <p>Declaring nothing is the safe default and means "may differ per row" — a
         * random source. {@code STABLE} means one value for a run and a different one
         * next time, which is a clock. {@code DETERMINISTIC} means the same answer
         * forever, which is what lets a call be handed to a backend at all. The middle
         * one exists because a clock call is neither: it may not be evaluated by the
         * database, and it may be evaluated <em>once</em> here and sent as a value.
         */
        @Test
        @DisplayName("a function that reads system state is stable or volatile, never deterministic")
        void ambientStateFunctionsAreClassified() {
            Set<String> volatilePerRow = FUNCTIONS.stream()
                    .filter(fn -> fn.signature().properties().isEmpty())
                    .map(fn -> fn.signature().canonicalName())
                    .collect(Collectors.toSet());
            assertThat(volatilePerRow)
                    .as("de-duplicating one of these would return the same random number twice")
                    .containsExactly("rand");

            Set<String> stable = FUNCTIONS.stream()
                    .filter(fn -> fn.signature().has(FunctionProperty.STABLE))
                    .map(fn -> fn.signature().canonicalName())
                    .collect(Collectors.toSet());
            assertThat(stable)
                    .as("one value for a run, a different one next time — so the engine "
                            + "may evaluate it once and push the value, never the call")
                    .containsExactlyInAnyOrder("now", "current_date", "current_time");
        }

        @Test
        @DisplayName("nothing claims to be both stable and deterministic")
        void stableIsNotDeterministic() {
            // The two are different claims and the stronger one swallows the weaker: a
            // deterministic call is already constant for any run, so declaring both says
            // nothing new and invites a reader to think it does. More to the point, a
            // deterministic call may be handed to the backend, and the whole reason a
            // stable one is substituted is that it may not.
            List<String> both = FUNCTIONS.stream()
                    .filter(fn -> fn.signature().has(FunctionProperty.STABLE))
                    .filter(fn -> fn.signature().has(FunctionProperty.DETERMINISTIC)
                            || fn.signature().has(FunctionProperty.PURE))
                    .map(fn -> fn.signature().name())
                    .toList();
            assertThat(both).isEmpty();
        }

        @Test
        @DisplayName("every documentation key names a reference page that exists")
        void documentationPagesExist() {
            Path reference = repoRoot().resolve("docs/reference");

            List<String> missing = FUNCTIONS.stream()
                    .map(fn -> fn.signature().docKey().orElse(""))
                    .filter(key -> !Files.isRegularFile(reference.resolve(key)))
                    .toList();

            assertThat(missing)
                    .as("a documentation key is only useful if it resolves; these name "
                            + "no page under docs/reference")
                    .isEmpty();
        }

        @Test
        @DisplayName("the documentation key follows the function's own name and category")
        void documentationKeyShape() {
            for (ScalarFunction fn : FUNCTIONS) {
                var signature = fn.signature();
                assertThat(signature.docKey()).contains("functions/" + signature.category()
                        + "/" + signature.name().toLowerCase(Locale.ROOT) + ".md");
            }
        }
    }

    private static Path repoRoot() {
        Path path = Paths.get("").toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("docs/reference"))) {
            path = path.getParent();
        }
        assertThat(path).as("repo root containing docs/reference").isNotNull();
        return path;
    }
}

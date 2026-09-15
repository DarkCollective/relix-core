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
package com.darkcollective.relix.solver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Discovery must reach the empty catalog {@link SolverCatalog} documents as supported,
 * whatever the reason a provider is missing.
 *
 * <p>The case that matters is not a runtime with no provider installed — that one is
 * covered by {@code SolverCatalogTest} — but a runtime with a provider installed that
 * cannot be loaded, which is what a host that removes the shipped solver's library has.
 * Aborting the scan there takes down every query rather than the two operators that need
 * a solver, and because {@link SolverCatalog#installed()} discovers inside a static
 * holder it does so for the life of the JVM.
 */
@DisplayName("Solver discovery — one unloadable provider must not take down the scan")
final class SolverDiscoveryGuardTest {

    private static final String SPI = "com.darkcollective.relix.solver.MathProgrammingSolver";
    private static final String GOOD = "com.darkcollective.relix.solver.DiscoverableTestSolver";
    private static final String UNLOADABLE = "com.darkcollective.relix.solver.UnloadableTestSolver";

    /**
     * Runs {@code SolverCatalog.discover()} against a services file declaring exactly
     * {@code providers}, by putting a synthetic one on the context class loader —
     * {@code ServiceLoader.load(Class)} resolves against it.
     */
    private static SolverCatalog discoverWithServices(String... providers) throws IOException {
        Path root = Files.createTempDirectory("relix-solver-discovery");
        Path services = root.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(SPI), String.join(System.lineSeparator(), providers));

        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        try (URLClassLoader loader =
                     new URLClassLoader(new URL[]{root.toUri().toURL()}, previous)) {
            current.setContextClassLoader(loader);
            return SolverCatalog.discover();
        } finally {
            current.setContextClassLoader(previous);
        }
    }

    private static List<String> namesOf(SolverCatalog catalog) {
        return catalog.solvers().stream().map(MathProgrammingSolver::name).toList();
    }

    @Nested
    @DisplayName("a provider that cannot be instantiated")
    final class Unloadable {

        @Test
        @DisplayName("is skipped, leaving the catalog empty rather than raising")
        void aloneLeavesAnEmptyCatalog() throws IOException {
            SolverCatalog catalog = discoverWithServices(UNLOADABLE);

            assertThat(catalog.isEmpty()).isTrue();
            assertThat(catalog.solver()).isEmpty();
        }

        @Test
        @DisplayName("does not hide the providers declared after it")
        void doesNotStopTheScan() throws IOException {
            SolverCatalog catalog = discoverWithServices(UNLOADABLE, GOOD);

            assertThat(namesOf(catalog)).containsExactly("discoverable-test-solver");
        }
    }

    @Nested
    @DisplayName("a services file naming a class that is absent")
    final class AbsentClass {

        @Test
        @DisplayName("is skipped, and the providers after it are still found")
        void doesNotStopTheScan() throws IOException {
            SolverCatalog catalog = discoverWithServices(
                    "com.darkcollective.relix.solver.NoSuchSolver", GOOD);

            assertThat(namesOf(catalog)).containsExactly("discoverable-test-solver");
        }
    }

    @Test
    @DisplayName("discovery is total, so the static holder behind installed() cannot be poisoned")
    void discoveryNeverRaises() {
        // An ExceptionInInitializerError in `installed()`'s holder is permanent: every
        // later read throws NoClassDefFoundError naming the holder rather than the
        // provider, so the failure outlives the diagnosis. Totality is what prevents it.
        assertThatCode(() -> discoverWithServices(UNLOADABLE, "not a class name!", GOOD))
                .doesNotThrowAnyException();
    }
}

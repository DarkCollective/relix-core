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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A library that cannot be loaded must fail as itself.
 *
 * <p>That is what the SPI's claim — anything the shipped library can do a third party can
 * do — requires of the failure path as well as the success one. A third-party library
 * compiled against a dependency the host does not have is the likeliest way to reach this,
 * and it must not be able to stop the engine from starting.
 */
@DisplayName("Function-library discovery — one unloadable library must not take down the scan")
final class FunctionDiscoveryGuardTest {

    private static final String SPI = "com.darkcollective.relix.function.FunctionLibrary";
    private static final String UNLOADABLE =
            "com.darkcollective.relix.function.UnloadableTestLibrary";
    private static final String ABSENT = "com.darkcollective.relix.function.NoSuchLibrary";

    /**
     * Runs {@code discovery} against a services file declaring exactly {@code providers}
     * <em>in addition</em> to the ones this module's own test resources register — a
     * child loader inherits its parent's services files, which is the arrangement a host
     * with a broken third-party library actually has.
     */
    private static <T> T withServices(Supplier<T> discovery, String... providers)
            throws IOException {
        Path root = Files.createTempDirectory("relix-function-discovery");
        Path services = root.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(SPI), String.join(System.lineSeparator(), providers));

        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        try (URLClassLoader loader =
                     new URLClassLoader(new URL[]{root.toUri().toURL()}, previous)) {
            current.setContextClassLoader(loader);
            return discovery.get();
        } finally {
            current.setContextClassLoader(previous);
        }
    }

    @Test
    @DisplayName("discover() skips it and still indexes the libraries that did load")
    void discoverSkipsAnUnloadableLibrary() throws IOException {
        FunctionCatalog catalog =
                withServices(FunctionCatalog::discover, UNLOADABLE, ABSENT);

        // The bundled test library is registered in this module's own test resources and
        // is reached through the parent loader, so it survives the broken entries.
        assertThat(catalog.scalar("DiscoveredFn")).isPresent();
    }

    @Test
    @DisplayName("discoverWith() skips it and still merges the caller's own libraries")
    void discoverWithSkipsAnUnloadableLibrary() throws IOException {
        FunctionLibrary supplied = new FunctionLibrary() {
            @Override public String name() { return "supplied"; }
            @Override public List<ScalarFunction> scalarFunctions() {
                return List.of(new TestFunctions.Marker("SuppliedFn", "supplied"));
            }
        };

        FunctionCatalog catalog = withServices(
                () -> FunctionCatalog.discoverWith(List.of(supplied)), UNLOADABLE, ABSENT);

        assertThat(catalog.scalar("SuppliedFn")).isPresent();
        assertThat(catalog.scalar("DiscoveredFn")).isPresent();
    }
}

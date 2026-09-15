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
package com.darkcollective.relix.solver.ojalgo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.net.URL;
import java.util.Enumeration;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The solver's half of ADR-0026's privileged-access claim, in the form
 * {@code FunctionBoundaryTest} states it for the default function library: the shipped
 * provider compiles against the published SPI and the library it wraps, and nothing
 * else.
 *
 * <p>If it could see an engine module, the SPI would be a description of how this
 * provider happens to be written rather than a contract a CBC or HiGHS binding could
 * be written against — and the difference would stay invisible until someone tried.
 * Reading the compiled descriptor means a stray {@code requires} and a convenience
 * Gradle dependency fail in the same place.
 */
@DisplayName("The shipped solver compiles against the SPI and ojAlgo, and nothing else")
final class SolverBoundaryTest {

    private static final String PROVIDER = "com.darkcollective.relix.solver.ojalgo";
    private static final String SPI = "com.darkcollective.relix.solver";

    @Test
    @DisplayName("requires the solver SPI and ojAlgo, and nothing else")
    void requiresOnlyTheSpiAndTheSolverLibrary() {
        Set<String> requires = descriptor().requires().stream()
                .map(ModuleDescriptor.Requires::name)
                .filter(name -> !"java.base".equals(name))
                .collect(Collectors.toSet());

        assertThat(requires)
                .as("anything beyond the SPI and the solver library is access a "
                        + "third-party provider would not have")
                .containsExactlyInAnyOrder(SPI, "ojalgo");
    }

    @Test
    @DisplayName("declares itself as a provider, so discovery on the module path finds it")
    void declaresTheProvider() {
        Set<String> provided = descriptor().provides().stream()
                .map(ModuleDescriptor.Provides::service)
                .collect(Collectors.toSet());

        assertThat(provided).containsExactly(SPI + ".MathProgrammingSolver");
        assertThat(descriptor().provides().iterator().next().providers())
                .containsExactly(PROVIDER + ".OjAlgoSolver");
    }

    /**
     * Reads the {@code module-info.class} the compiler emitted for this module. The
     * test classpath carries one per relix module on it, so every descriptor is read
     * and this module's picked out by name.
     */
    private static ModuleDescriptor descriptor() {
        try {
            Enumeration<URL> found = SolverBoundaryTest.class.getClassLoader()
                    .getResources("module-info.class");
            Optional<ModuleDescriptor> provider = Optional.empty();
            while (found.hasMoreElements()) {
                try (InputStream in = found.nextElement().openStream()) {
                    ModuleDescriptor descriptor = ModuleDescriptor.read(in);
                    if (PROVIDER.equals(descriptor.name())) {
                        provider = Optional.of(descriptor);
                    }
                }
            }
            return provider.orElseThrow(() -> new AssertionError(
                    "no compiled module-info for " + PROVIDER + " on the test classpath"));
        } catch (IOException e) {
            throw new AssertionError("could not read the compiled module descriptors", e);
        }
    }
}

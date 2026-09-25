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
package com.darkcollective.relix.value;

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
 * The point of this module, asserted against the artifact the compiler produced.
 *
 * <p>{@code Value} was pulled out of {@code relix-processor} so that code near the
 * bottom of the module graph could use it — a function implementation needs values,
 * and its metadata lives in {@code relix-symbol}, which cannot depend on the executor.
 * That only holds while this module stays at the bottom with it: one {@code requires}
 * on a module further up (rows, plans, the semantic model) and every dependent inherits
 * the coupling the move was meant to remove, with nothing to say so.
 *
 * <p>Reading the compiled {@code module-info} rather than the build script is what
 * makes this a check on the real thing — a stray {@code requires} and a re-added Gradle
 * dependency both show up here.
 */
@DisplayName("relix-value sits at the bottom of the module graph")
final class ValueBoundaryTest {

    private static final String VALUE  = "com.darkcollective.relix.value";
    private static final String SYMBOL = "com.darkcollective.relix.symbol";

    @Test
    @DisplayName("requires nothing but relix-symbol (and java.base)")
    void requiresOnlyTheSymbolModule() throws IOException {
        Set<String> requires = valueDescriptor().requires().stream()
                .map(ModuleDescriptor.Requires::name)
                .filter(name -> !"java.base".equals(name))
                .collect(Collectors.toSet());

        assertThat(requires)
                .as("relix-value's dependencies — ScalarType is the only thing it needs, "
                        + "and anything else here means the hierarchy has been pulled back up "
                        + "the graph it was moved out of")
                .containsExactly(SYMBOL);
    }

    @Test
    @DisplayName("exports the value package under its own name")
    void exportsTheValuePackage() throws IOException {
        // Unqualified exports only: value.internal is exported to named engine modules,
        // which is not publishing it.
        Set<String> exports = valueDescriptor().exports().stream()
                .filter(e -> !e.isQualified())
                .map(ModuleDescriptor.Exports::source)
                .collect(Collectors.toSet());

        // The package a module exports is the name its consumers, its javadoc and its
        // compatibility reports all read; a package left behind under the old module's
        // name would be a permanent misstatement of where these types live.
        assertThat(exports).containsExactly(VALUE);
    }

    /**
     * Reads the {@code module-info.class} the compiler emitted for this module.
     * The test classpath carries one per relix module on it, so every descriptor is
     * read and this module's picked out by name.
     */
    private static ModuleDescriptor valueDescriptor() throws IOException {
        Enumeration<URL> found =
                ValueBoundaryTest.class.getClassLoader().getResources("module-info.class");
        Optional<ModuleDescriptor> value = Optional.empty();
        while (found.hasMoreElements()) {
            try (InputStream in = found.nextElement().openStream()) {
                ModuleDescriptor descriptor = ModuleDescriptor.read(in);
                if (VALUE.equals(descriptor.name())) {
                    value = Optional.of(descriptor);
                }
            }
        }
        return value.orElseThrow(() -> new AssertionError(
                "no compiled module-info for " + VALUE + " on the test classpath"));
    }
}

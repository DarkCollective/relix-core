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
 * The claim the whole design rests on, asserted against the artifact the compiler
 * produced: the shipped library reaches nothing a third party could not.
 *
 * <p>If the default library could see an engine internal, the SPI would be a description
 * of how the built-ins happen to be written rather than a contract anyone can write
 * against — and the difference would be invisible until someone tried. One
 * {@code requires} on a module further up the graph is all it would take, so the single
 * allowed edge is checked rather than intended.
 *
 * <p>Reading the compiled descriptor rather than the build script means a stray
 * {@code requires} and a convenience Gradle dependency both fail in the same place. The
 * provider declaration is checked here too: a library that is not declared as a service
 * is simply never found, and nothing else in the build would notice.
 */
@DisplayName("The default library compiles against the SPI and nothing else")
final class FunctionBoundaryTest {

    private static final String BUILTIN = "com.darkcollective.relix.function.builtin";
    private static final String FUNCTION = "com.darkcollective.relix.function";

    @Test
    @DisplayName("requires the function SPI, and nothing else")
    void requiresOnlyTheSpi() {
        Set<String> requires = descriptor().requires().stream()
                .map(ModuleDescriptor.Requires::name)
                .filter(name -> !"java.base".equals(name))
                .collect(Collectors.toSet());

        assertThat(requires)
                .as("the default library's dependencies — anything beyond the SPI is "
                        + "privileged access a third-party library would not have")
                .containsExactly(FUNCTION);
    }

    @Test
    @DisplayName("declares itself as a provider, so discovery on the module path finds it")
    void declaresTheProvider() {
        Set<String> provided = descriptor().provides().stream()
                .map(ModuleDescriptor.Provides::service)
                .collect(Collectors.toSet());

        assertThat(provided).containsExactly(FUNCTION + ".FunctionLibrary");
        assertThat(descriptor().provides().iterator().next().providers())
                .containsExactly(BUILTIN + ".BuiltinFunctionLibrary");
    }

    @Test
    @DisplayName("exports the library package under its own name")
    void exportsTheLibraryPackage() {
        Set<String> exports = descriptor().exports().stream()
                .map(ModuleDescriptor.Exports::source)
                .collect(Collectors.toSet());

        assertThat(exports).containsExactly(BUILTIN);
    }

    /**
     * Reads the {@code module-info.class} the compiler emitted for this module. The test
     * classpath carries one per relix module on it, so every descriptor is read and this
     * module's picked out by name.
     */
    private static ModuleDescriptor descriptor() {
        try {
            Enumeration<URL> found = FunctionBoundaryTest.class.getClassLoader()
                    .getResources("module-info.class");
            Optional<ModuleDescriptor> builtin = Optional.empty();
            while (found.hasMoreElements()) {
                try (InputStream in = found.nextElement().openStream()) {
                    ModuleDescriptor descriptor = ModuleDescriptor.read(in);
                    if (BUILTIN.equals(descriptor.name())) {
                        builtin = Optional.of(descriptor);
                    }
                }
            }
            return builtin.orElseThrow(() -> new AssertionError(
                    "no compiled module-info for " + BUILTIN + " on the test classpath"));
        } catch (IOException e) {
            throw new AssertionError("could not read the compiled module descriptors", e);
        }
    }
}

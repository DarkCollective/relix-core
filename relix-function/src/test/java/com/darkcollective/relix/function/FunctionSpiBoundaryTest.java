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
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.net.URL;
import java.util.Enumeration;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shape of this module, asserted against the artifact the compiler produced.
 *
 * <p>The SPI is a module of its own for one reason: a signature is written in the type
 * system and an implementation is written in values, and the value hierarchy already
 * depends on the type system, so the SPI cannot live in either without a cycle. That
 * reason only holds while the module requires those two and nothing else — one
 * {@code requires} on a module further up the graph and a function library would be
 * compiling against the engine rather than against a seam.
 *
 * <p>The {@code uses} declaration is checked for the same reason it lives here: discovery
 * belongs to the module that owns the catalogue, so no consumer repeats it and there is
 * one catalogue rather than several. Reading the compiled descriptor rather than the
 * build script means a stray {@code requires} and a re-added Gradle dependency both show
 * up in the same place.
 */
@DisplayName("relix-function sits between the type system and the values")
final class FunctionSpiBoundaryTest {

    private static final String FUNCTION = "com.darkcollective.relix.function";
    private static final String SYMBOL   = "com.darkcollective.relix.symbol";
    private static final String VALUE    = "com.darkcollective.relix.value";

    @Test
    @DisplayName("requires relix-symbol and relix-value, and nothing else")
    void requiresOnlyTheTwoItIsBuiltFrom() throws IOException {
        Set<String> requires = descriptor().requires().stream()
                .map(ModuleDescriptor.Requires::name)
                .filter(name -> !"java.base".equals(name))
                .collect(Collectors.toSet());

        assertThat(requires)
                .as("the SPI's dependencies — anything beyond the type system and the "
                        + "values would put the engine into the contract a third-party "
                        + "library compiles against")
                .containsExactlyInAnyOrder(SYMBOL, VALUE);
    }

    @Test
    @DisplayName("exports the function package under its own name")
    void exportsTheFunctionPackage() throws IOException {
        Set<String> exports = descriptor().exports().stream()
                .map(ModuleDescriptor.Exports::source)
                .collect(Collectors.toSet());

        assertThat(exports).containsExactly(FUNCTION);
    }

    @Test
    @DisplayName("declares the service it discovers, so no consumer has to")
    void declaresTheService() {
        assertThat(descriptorOrFail().uses())
                .containsExactly(FUNCTION + ".FunctionLibrary");
    }

    private static ModuleDescriptor descriptor() throws IOException {
        return read();
    }

    private static ModuleDescriptor descriptorOrFail() {
        try {
            return read();
        } catch (IOException e) {
            throw new AssertionError("could not read the compiled module descriptors", e);
        }
    }

    /**
     * Reads the {@code module-info.class} the compiler emitted for this module. The test
     * classpath carries one per relix module on it, so every descriptor is read and this
     * module's picked out by name.
     */
    private static ModuleDescriptor read() throws IOException {
        Enumeration<URL> found =
                FunctionSpiBoundaryTest.class.getClassLoader().getResources("module-info.class");
        Optional<ModuleDescriptor> function = Optional.empty();
        while (found.hasMoreElements()) {
            try (InputStream in = found.nextElement().openStream()) {
                ModuleDescriptor descriptor = ModuleDescriptor.read(in);
                if (FUNCTION.equals(descriptor.name())) {
                    function = Optional.of(descriptor);
                }
            }
        }
        return function.orElseThrow(() -> new AssertionError(
                "no compiled module-info for " + FUNCTION + " on the test classpath"));
    }
}

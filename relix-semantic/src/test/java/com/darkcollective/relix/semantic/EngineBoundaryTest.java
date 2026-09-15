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
package com.darkcollective.relix.semantic;

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
 * ADR-0025 Decisions 2 + 3 — the core/frontend cut, asserted against the
 * artifact the compiler actually produced.
 *
 * <p>Decision 2's claim is not a convention but a <em>module graph</em>: the
 * engine analyses an AST, so the concrete-syntax grammar ({@code relix-lang},
 * whose {@code ScriptParser} turns text into a {@code Script}) must not be
 * reachable from {@code relix-semantic}'s {@code main} classpath.  Reading the
 * compiled {@code module-info} rather than the build script is what makes this a
 * check on the real thing: a stray {@code requires} or a re-added Gradle
 * {@code implementation} both show up here.
 *
 * <p>Decision 3's counter-claim — the grammar stays available to <em>tests</em> —
 * needs no assertion of its own: this test class sits beside a suite that calls
 * {@code ScriptParser} freely, and would not compile if it had gone away.
 */
@DisplayName("ADR-0025 — the engine's module graph carries no concrete syntax")
final class EngineBoundaryTest {

    private static final String ENGINE  = "com.darkcollective.relix.semantic";
    private static final String GRAMMAR = "com.darkcollective.relix.lang";
    private static final String AST     = "com.darkcollective.relix.lang.ast";

    @Test
    @DisplayName("relix-semantic's main module does not require the .relix grammar")
    void mainModuleDoesNotRequireTheGrammar() throws IOException {
        Set<String> requires = engineDescriptor().requires().stream()
                .map(ModuleDescriptor.Requires::name)
                .collect(Collectors.toSet());

        // The front-door AST contract stays; the grammar that produces one does not.
        assertThat(requires).contains(AST).doesNotContain(GRAMMAR);
    }

    /**
     * Reads the {@code module-info.class} the compiler emitted for this module.
     * The test classpath carries one per relix module, so every descriptor is
     * read and the engine's picked out by name.
     */
    private static ModuleDescriptor engineDescriptor() throws IOException {
        Enumeration<URL> found =
                EngineBoundaryTest.class.getClassLoader().getResources("module-info.class");
        Optional<ModuleDescriptor> engine = Optional.empty();
        while (found.hasMoreElements()) {
            try (InputStream in = found.nextElement().openStream()) {
                ModuleDescriptor descriptor = ModuleDescriptor.read(in);
                if (ENGINE.equals(descriptor.name())) {
                    engine = Optional.of(descriptor);
                }
            }
        }
        return engine.orElseThrow(() -> new AssertionError(
                "no compiled module-info for " + ENGINE + " on the test classpath"));
    }
}

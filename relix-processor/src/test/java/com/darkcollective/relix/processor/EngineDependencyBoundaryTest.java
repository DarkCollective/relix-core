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
package com.darkcollective.relix.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0026's second boundary claim: <strong>the engine depends on nothing outside
 * {@code java.base} and its own modules</strong> — so an embedder inherits neither a
 * JDBC stack, nor an HTTP client, nor a solver.
 *
 * <p>Every other engine module has always been {@code java.base}-only; this one was the
 * holdout — {@code java.sql}, {@code java.net.http}, {@code ojalgo} — and it is the
 * reason the claim is asserted from here: {@code relix-processor}
 * sits at the top of the engine graph, so its test classpath carries a compiled
 * descriptor for every engine module below it. Reading those descriptors rather than the
 * build scripts means a stray {@code requires} and a re-added Gradle dependency both fail
 * in the same place.
 *
 * <p>A <em>provider</em> is exempt by definition: {@code relix-connectors-std} exists to
 * hold {@code java.sql} and {@code java.net.http}, {@code relix-solver-ojalgo} to hold
 * {@code ojalgo}, and a function library may require whatever it computes with.
 * Providers are named rather than pattern-matched, so a new engine module is covered
 * the day it appears and cannot quietly opt out.
 */
@DisplayName("ADR-0026 — the engine requires only java.base and other relix modules")
final class EngineDependencyBoundaryTest {

    private static final String RELIX_PREFIX = "com.darkcollective.relix.";

    /** Provider modules: they exist precisely to carry a dependency the engine must not. */
    private static final Set<String> PROVIDERS = Set.of(
            "com.darkcollective.relix.function.builtin",
            "com.darkcollective.relix.connectors.std",
            "com.darkcollective.relix.solver.ojalgo");

    /**
     * Non-relix modules an engine module may still require. <strong>Empty</strong>, and
     * the endpoint of the whole ADR: the three extractions — the function library, the
     * connectors, the solver — leave an engine that names nothing but {@code java.base}
     * and itself. An entry added here is a claim being given up, so add one only with
     * the reason and the slice that removes it again.
     */
    private static final Set<String> PERMITTED_EXTERNAL = Set.of();

    @Test
    @DisplayName("no engine module requires a non-relix module beyond java.base")
    void engineModulesRequireOnlyRelixModules() {
        var offenders = new TreeMap<String, Set<String>>();
        for (ModuleDescriptor descriptor : engineDescriptors()) {
            Set<String> external = new TreeSet<>();
            for (ModuleDescriptor.Requires requires : descriptor.requires()) {
                String name = requires.name();
                if ("java.base".equals(name) || name.startsWith(RELIX_PREFIX)
                        || PERMITTED_EXTERNAL.contains(name)) {
                    continue;
                }
                external.add(name);
            }
            if (!external.isEmpty()) {
                offenders.put(descriptor.name(), external);
            }
        }

        assertThat(offenders)
                .as("engine modules requiring something outside java.base and relix — "
                        + "an embedder inherits every one of these")
                .isEmpty();
    }

    @Test
    @DisplayName("relix-processor carries neither a JDBC stack, an HTTP client, nor a solver")
    void theExecutorHasNoDatabaseNetworkOrSolverDependency() {
        Set<String> requires = new TreeSet<>();
        for (ModuleDescriptor descriptor : engineDescriptors()) {
            if (descriptor.name().equals("com.darkcollective.relix.processor")) {
                descriptor.requires().forEach(r -> requires.add(r.name()));
            }
        }

        assertThat(requires)
                .as("the connectors and the solver moved out behind their SPIs "
                        + "(ADR-0026 D8); reading a database or a URL, and searching a "
                        + "branch-and-bound tree, are a provider's job, not the engine's")
                .isNotEmpty()
                .doesNotContain("java.sql", "java.net.http", "ojalgo");
    }

    /**
     * Every relix module descriptor on the test classpath that is not a provider.
     *
     * <p>Providers are on this classpath too — the default function library arrives
     * through the shared semantic test fixtures — and are filtered out rather than
     * asserted about.
     */
    private static List<ModuleDescriptor> engineDescriptors() {
        List<ModuleDescriptor> engine = new ArrayList<>();
        try {
            Enumeration<URL> found = EngineDependencyBoundaryTest.class.getClassLoader()
                    .getResources("module-info.class");
            while (found.hasMoreElements()) {
                try (InputStream in = found.nextElement().openStream()) {
                    ModuleDescriptor descriptor = ModuleDescriptor.read(in);
                    if (descriptor.name().startsWith(RELIX_PREFIX)
                            && !PROVIDERS.contains(descriptor.name())) {
                        engine.add(descriptor);
                    }
                }
            }
        } catch (IOException e) {
            throw new AssertionError("could not read the compiled module descriptors", e);
        }
        assertThat(engine)
                .as("compiled engine descriptors on the test classpath")
                .isNotEmpty();
        return engine;
    }
}

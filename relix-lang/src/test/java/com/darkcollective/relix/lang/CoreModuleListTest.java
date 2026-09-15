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
package com.darkcollective.relix.lang;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the root build's {@code ext.coreModules} — the one list of what the
 * published engine contract contains, read by {@code javadocCore} and (when it
 * lands) by the core artifact's binary-compatibility gate.
 *
 * <p>A hand-kept list of modules is exactly the thing that goes stale silently:
 * a new engine module compiles, ships and is simply absent from the published
 * API docs, and nothing anywhere says so. So the list is not checked against a
 * second copy of itself — it is checked against the module graph, by one
 * invariant that makes both drifts impossible:
 *
 * <p><strong>Every module named in {@code coreModules} requires nothing but
 * {@code java.base} and other modules named in {@code coreModules}.</strong>
 *
 * <p>Forget to add a new engine module and some listed module requires an
 * unlisted one — the list is not closed, and the run fails. List a frontend or a
 * provider by mistake and it drags in the grammar, {@code ojalgo} or
 * {@code java.sql} — an external {@code requires}, and the run fails. The
 * invariant is the intersection of the two boundary claims the project already
 * makes: ADR-0025's (the {@code .relix} grammar is a frontend, not core) and
 * ADR-0026's (a provider supplies processing the engine only declares the form
 * of), which is why nothing here needs to name either kind.
 *
 * <p>Its counterpart {@code EngineDependencyBoundaryTest} (relix-processor)
 * asserts the same external-dependency rule against the <em>compiled</em>
 * descriptors, and is the stronger check of the two; this one reads the sources,
 * because what it is really checking is the membership of a Gradle list, and a
 * module missing from that list is a module missing from the classpath it would
 * otherwise have been read from.
 */
@DisplayName("ext.coreModules names a closed, dependency-free engine")
final class CoreModuleListTest {

    /** The `ext.coreModules = [ ... ]` literal in the root build. */
    private static final Pattern CORE_MODULES =
            Pattern.compile("(?s)\\bext\\.coreModules\\s*=\\s*\\[(.*?)]");

    private static final Pattern PROJECT_PATH = Pattern.compile("':([A-Za-z0-9._-]+)'");

    /** A `module x.y {` declaration at column 0 — the word elsewhere is prose. */
    private static final Pattern MODULE_DECL =
            Pattern.compile("(?m)^(?:open\\s+)?module\\s+([\\w.]+)\\s*\\{");

    /** A `requires [static] [transitive] x.y;` clause, comments and all. */
    private static final Pattern REQUIRES = Pattern.compile(
            "(?m)^\\s*requires\\s+(?:static\\s+|transitive\\s+)*([\\w.]+)\\s*;");

    private static final String RELIX_PREFIX = "com.darkcollective.relix.";

    @Test
    @DisplayName("every core module is a real module in settings.gradle")
    void coreModulesExist() {
        List<String> core = coreModules();
        assertThat(core).as("ext.coreModules was read from the root build.gradle").isNotEmpty();
        assertThat(core).as("a module listed twice in ext.coreModules").doesNotHaveDuplicates();

        List<String> missing = new ArrayList<>();
        for (String project : core) {
            if (!Files.isRegularFile(moduleInfo(project))) {
                missing.add(project);
            }
        }
        assertThat(missing)
                .as("ext.coreModules names a project with no src/main/java/module-info.java — "
                        + "either it was renamed, or it is not a module and cannot be documented "
                        + "by javadocCore")
                .isEmpty();
    }

    @Test
    @DisplayName("core requires only java.base and core")
    void coreIsClosedAndDependencyFree() {
        Map<String, String> moduleNames = new LinkedHashMap<>();
        for (String project : coreModules()) {
            moduleNames.put(project, declaredModuleName(project));
        }
        Set<String> core = new LinkedHashSet<>(moduleNames.values());

        Map<String, Set<String>> offenders = new TreeMap<>();
        moduleNames.forEach((project, module) -> {
            Set<String> outside = new LinkedHashSet<>();
            Matcher m = REQUIRES.matcher(read(moduleInfo(project)));
            while (m.find()) {
                String required = m.group(1);
                if (!"java.base".equals(required) && !core.contains(required)) {
                    outside.add(required);
                }
            }
            if (!outside.isEmpty()) {
                offenders.put(module, outside);
            }
        });

        assertThat(offenders)
                .as("a core module requires something that is not core. A "
                        + RELIX_PREFIX + "* name means ext.coreModules has gone stale — add "
                        + "the module. Anything else means a frontend or a provider is listed "
                        + "as core — remove it")
                .isEmpty();
    }

    private static List<String> coreModules() {
        Matcher block = CORE_MODULES.matcher(read(repoRoot().resolve("build.gradle")));
        assertThat(block.find()).as("ext.coreModules = [ … ] in the root build.gradle").isTrue();

        List<String> out = new ArrayList<>();
        Matcher m = PROJECT_PATH.matcher(block.group(1));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static String declaredModuleName(String project) {
        Path source = moduleInfo(project);
        Matcher m = MODULE_DECL.matcher(read(source));
        assertThat(m.find()).as("module declaration in " + source).isTrue();
        return m.group(1);
    }

    private static Path moduleInfo(String project) {
        return repoRoot().resolve(project).resolve("src/main/java/module-info.java");
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + p, e);
        }
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("docs/reference"))) {
            p = p.getParent();
        }
        assertThat(p).as("repo root containing docs/reference").isNotNull();
        return p;
    }
}

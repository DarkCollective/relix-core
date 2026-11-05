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
package com.darkcollective.relix.embed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The published module declares every provider that the modules merged into it declare.
 *
 * <p>On a class path a service is found through the merged {@code META-INF/services};
 * on the module path only through {@code provides}, which {@code relix-dist} has to
 * repeat because it is the one module a modular runtime reads. The CSV connector alone
 * was repeated for a release, so a GEDCOM or log source worked from a jar and was not
 * found in a jlink image.
 */
@DisplayName("relix-dist provides what the merged modules provide")
final class DistProvidersTest {

    private static final Path ROOT = Path.of("..");

    /** Modules with providers that are published as artifacts of their own, not merged. */
    private static final Set<String> NOT_MERGED = Set.of("relix-solver-ojalgo", "relix-dist");

    private static final Pattern PROVIDES =
            Pattern.compile("provides\\s+([\\w.]+)\\s+with\\s+([^;]+);");

    private static Map<String, Set<String>> providers(Path moduleInfo) throws IOException {
        String source = Files.readString(moduleInfo)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
        Map<String, Set<String>> byService = new TreeMap<>();
        Matcher m = PROVIDES.matcher(source);
        while (m.find()) {
            Set<String> implementations = byService.computeIfAbsent(m.group(1), k -> new TreeSet<>());
            for (String implementation : m.group(2).split(",")) {
                implementations.add(implementation.trim());
            }
        }
        return byService;
    }

    @Test
    @DisplayName("every provider of every merged module is repeated in the dist descriptor")
    void distRepeatsEveryProvider() throws IOException {
        Map<String, Set<String>> dist = providers(ROOT.resolve("relix-dist/src/main/java/module-info.java"));
        List<Path> descriptors = new ArrayList<>();
        try (Stream<Path> modules = Files.list(ROOT)) {
            modules.filter(d -> d.getFileName().toString().startsWith("relix-"))
                    .filter(d -> !NOT_MERGED.contains(d.getFileName().toString()))
                    .map(d -> d.resolve("src/main/java/module-info.java"))
                    .filter(Files::exists)
                    .forEach(descriptors::add);
        }
        assertThat(descriptors).isNotEmpty();
        Map<String, Set<String>> expected = new TreeMap<>();
        for (Path descriptor : descriptors) {
            providers(descriptor).forEach((service, implementations) ->
                    expected.computeIfAbsent(service, k -> new TreeSet<>()).addAll(implementations));
        }
        assertThat(expected).as("the merged modules declare providers").isNotEmpty();
        assertThat(dist).isEqualTo(expected);
    }

    @Test
    @DisplayName("a module's provides and its META-INF/services agree")
    void servicesFileAgreesWithDescriptor() throws IOException {
        Path module = ROOT.resolve("relix-connectors-std");
        Map<String, Set<String>> declared = providers(module.resolve("src/main/java/module-info.java"));
        Path services = module.resolve("src/main/resources/META-INF/services");
        Map<String, Set<String>> listed = new TreeMap<>();
        try (Stream<Path> files = Files.list(services)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Set<String> implementations = new TreeSet<>();
                for (String line : Files.readAllLines(file)) {
                    if (!line.isBlank() && !line.startsWith("#")) {
                        implementations.add(line.trim());
                    }
                }
                listed.put(file.getFileName().toString(), implementations);
            }
        }
        assertThat(listed).isEqualTo(declared);
    }
}

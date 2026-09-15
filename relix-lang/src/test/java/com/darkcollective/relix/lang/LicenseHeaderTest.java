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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every Java source file carries the Apache header.
 *
 * <p>Apache 2.0 recommends the per-file notice rather than requiring it, and the
 * argument for having one is about the single file rather than the repository: source
 * travels. A class pasted into an issue, vendored into another build, or read on its
 * own in a search result arrives with no {@code LICENSE.txt} beside it, and the header
 * is the only thing that says what may be done with it.
 *
 * <p>The text is read from {@code tools/license/HEADER.txt} rather than repeated here,
 * because the script that applies it reads the same file — a header that is right in
 * the sweep and wrong in the guard is the failure this arrangement exists to prevent.
 * {@code python3 tools/license/apply_headers.py} fixes anything this reports.
 *
 * <p>It checks the marker line rather than the whole block, deliberately. Matching the
 * text exactly would fail a file whose header someone had reflowed or whose copyright
 * year had moved, neither of which is a licensing problem; what matters is that the
 * grant is stated.
 */
@DisplayName("Every Java source file carries the Apache header")
final class LicenseHeaderTest {

    /** The one line that makes the file's licence unambiguous. */
    private static final String MARKER = "Licensed under the Apache License";

    @Test
    @DisplayName("no Java source is published without saying what may be done with it")
    void everyJavaFileCarriesTheHeader() {
        List<String> missing = new ArrayList<>();
        for (Path file : javaSources()) {
            if (!read(file).contains(MARKER)) {
                missing.add(repoRoot().relativize(file).toString().replace('\\', '/'));
            }
        }
        assertThat(missing)
                .as("Java sources with no Apache header — run "
                        + "`python3 tools/license/apply_headers.py`, which reads the text "
                        + "from tools/license/HEADER.txt so it cannot drift from this check")
                .isEmpty();
    }

    @Test
    @DisplayName("the header the tooling applies is the one this checks for")
    void theHeaderTextIsShared() {
        Path header = repoRoot().resolve("tools/license/HEADER.txt");
        assertThat(header).as("the shared header text").exists();
        assertThat(read(header))
                .as("tools/license/HEADER.txt states the Apache grant")
                .contains(MARKER)
                .contains("http://www.apache.org/licenses/LICENSE-2.0");
    }

    /** Every Java file in every module's source sets. */
    private static List<Path> javaSources() {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> modules = Files.list(repoRoot())) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path src = module.resolve("src");
                if (!Files.isDirectory(src)) {
                    continue;
                }
                try (Stream<Path> walk = Files.walk(src)) {
                    walk.filter(p -> p.toString().endsWith(".java"))
                            .filter(p -> !p.toString().contains("/build/"))
                            .forEach(out::add);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(out).as("Java sources were found to check").isNotEmpty();
        return out;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        return dir;
    }
}

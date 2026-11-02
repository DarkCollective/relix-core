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
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No string literal in the engine's main sources cites a design record by number.
 *
 * <p>An ADR is where a rule was decided, and a code comment may point there. A message
 * is what a user reads, and a user cannot look an ADR up: {@code "see ADR-0013"} tells
 * them a rule exists without telling them what it is. A message states the rule instead.
 * Comments are skipped; only the text inside {@code "…"} and text blocks is checked.
 */
@DisplayName("no message in src/main cites an ADR")
final class MessageAdrGuardTest {

    @Test
    @DisplayName("no string literal contains ADR-")
    void noLiteralCitesAnAdr() {
        List<String> found = new ArrayList<>();
        for (Path file : mainSources()) {
            String source = read(file);
            for (Literal literal : literals(source)) {
                if (literal.text().contains("ADR-")) {
                    found.add(repoRoot().relativize(file) + ":" + literal.line()
                            + "  " + literal.text().strip());
                }
            }
        }
        assertThat(found)
                .as("string literals in src/main that cite an ADR; state the rule instead")
                .isEmpty();
    }

    @Test
    @DisplayName("the scan reads literals and skips comments")
    void scanSkipsComments() {
        String source = """
                // see ADR-0001
                /* ADR-0002 */ String a = "x // y"; char c = '"';
                String b = "ADR-0003 \\" still";
                String t = \"""
                    ADR-0004
                    \""";
                """;
        assertThat(literals(source)).extracting(Literal::text)
                .containsExactly("x // y", "ADR-0003 \\\" still", "\n    ADR-0004\n    ");
    }

    private record Literal(int line, String text) {
    }

    /** The string literals and text blocks of {@code source}, comments and chars skipped. */
    private static List<Literal> literals(String source) {
        List<Literal> literals = new ArrayList<>();
        int line = 1;
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '\n') {
                line++;
                i++;
            } else if (source.startsWith("//", i)) {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                line += count(source, i, end);
                i = end;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end;
                literals.add(new Literal(line, source.substring(i + 3, end)));
                line += count(source, i, end);
                i = Math.min(n, end + 3);
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && source.charAt(j) != c && source.charAt(j) != '\n') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                if (c == '"') {
                    literals.add(new Literal(line, source.substring(i + 1, Math.min(j, n))));
                }
                i = j + 1;
            } else {
                i++;
            }
        }
        return literals;
    }

    private static int count(String source, int from, int to) {
        int lines = 0;
        for (int k = from; k < to; k++) {
            if (source.charAt(k) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private static List<Path> mainSources() {
        Path root = repoRoot();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> root.relativize(p).toString()
                            .replace('\\', '/').contains("/src/main/java/"))
                    .filter(p -> !root.relativize(p).startsWith("build"))
                    .filter(p -> !root.relativize(p).toString().contains("/build/"))
                    .filter(p -> !root.relativize(p).startsWith(".claude"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path repoRoot() {
        Path path = Paths.get("").toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("relix-ast/src/main/java"))) {
            path = path.getParent();
        }
        if (path == null) {
            throw new AssertionError("could not locate the repository root");
        }
        return path;
    }
}

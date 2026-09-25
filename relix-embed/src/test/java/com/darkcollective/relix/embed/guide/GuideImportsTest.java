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
package com.darkcollective.relix.embed.guide;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The programming guide teaches the published API and nothing else.
 *
 * <p>{@code ProgrammingGuideTest} runs the examples on a class path, where every package
 * in the jar is readable, so an example importing an engine package the jar does not
 * export compiles and runs there, and fails for a reader whose program is a module. A
 * mechanical package move once rewrote four pages' imports onto internal classes with
 * every example still green. This reads each example's imports against the jar's
 * exports.
 */
@DisplayName("the guide imports only packages the published jar exports")
final class GuideImportsTest {

    private static final Pattern JAVA_BLOCK = Pattern.compile("```java\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern IMPORT = Pattern.compile(
            "^import\\s+(?:static\\s+)?(com\\.darkcollective\\.relix\\.[\\w.]+);", Pattern.MULTILINE);
    private static final Pattern EXPORTS = Pattern.compile(
            "^\\s*exports\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    @Test
    @DisplayName("every relix import in an example is from an exported package")
    void onlyExportedPackages() throws IOException {
        Path root = repoRoot();
        Set<String> exported = new TreeSet<>();
        Matcher e = EXPORTS.matcher(Files.readString(root.resolve("relix-dist/src/main/java/module-info.java")));
        while (e.find()) {
            exported.add(e.group(1));
        }
        assertThat(exported).as("the jar's exports").isNotEmpty();

        List<Path> pages = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root.resolve("docs/guide"))) {
            walk.filter(p -> p.toString().endsWith(".md")).sorted().forEach(pages::add);
        }
        Path readme = root.resolve("tools/export/public/README.md");
        if (Files.exists(readme)) {
            pages.add(readme);
        }

        List<String> violations = new ArrayList<>();
        for (Path page : pages) {
            Matcher block = JAVA_BLOCK.matcher(Files.readString(page));
            while (block.find()) {
                Matcher imp = IMPORT.matcher(block.group(1));
                while (imp.find()) {
                    String pkg = packageOf(imp.group(1));
                    if (!exported.contains(pkg)) {
                        violations.add(root.relativize(page) + " imports " + imp.group(1));
                    }
                }
            }
        }
        assertThat(violations)
                .as("a guide example using an engine package the published jar does not export")
                .isEmpty();
    }

    private static String packageOf(String name) {
        List<String> parts = new ArrayList<>();
        for (String part : name.split("\\.")) {
            if (Character.isUpperCase(part.charAt(0))) {
                break;
            }
            parts.add(part);
        }
        return String.join(".", parts);
    }

    private static Path repoRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("settings.gradle"))) {
            path = path.getParent();
        }
        return path;
    }
}

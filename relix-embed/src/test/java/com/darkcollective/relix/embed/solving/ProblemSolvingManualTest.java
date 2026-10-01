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
package com.darkcollective.relix.embed.solving;

import static org.assertj.core.api.Assertions.assertThat;

import com.darkcollective.relix.embed.Diagnostic;
import com.darkcollective.relix.embed.Relation;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.embed.Sandbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The problem-solving manual's scripts all run.
 *
 * <p>Every recipe and case study in {@code docs/problem-solving} is a session: its
 * {@code relix} code blocks, read top to bottom, declare data, name views and ask
 * queries that build on one another. This test concatenates each page's {@code relix}
 * blocks and runs them through the embedding API against a closed {@link Sandbox} — the
 * pages carry only inline data, so nothing reaches outside the session — asserting the
 * page validates and every query in it executes without error.
 *
 * <p>It is the manual's counterpart to the {@code docs/reference} parse/analyse/execute
 * guards and the guide's {@code ProgrammingGuideTest}: an engine-side check that the
 * scripts a reader is invited to copy actually work. What a query <em>returns</em> — the
 * pasted result tables — is diffed by the console-side guard whose renderer is the one
 * the CLI prints with; that check follows the front-end formatter, so it lives there.
 *
 * <p>Only {@code recipes/} and {@code case-studies/} carry runnable blocks; the method,
 * engineering and finder chapters are prose. A page with no {@code relix} block is not a
 * failure — it simply produces no dynamic test.
 */
@DisplayName("The problem-solving manual's scripts all run")
final class ProblemSolvingManualTest {

    /** Generous limits: the examples are tiny, but a runaway recursion should still stop. */
    private static final Sandbox SANDBOX = Sandbox.builder()
            .maxFixpointRounds(1_000)
            .maxMaterializedRows(1_000_000)
            .maxProcessedRows(10_000_000)
            .timeout(Duration.ofSeconds(30))
            .build();

    @TestFactory
    @DisplayName("every recipe and case study runs")
    Stream<DynamicTest> everyPageRuns() throws IOException {
        Path manual = manualRoot();
        try (Stream<Path> walk = Files.walk(manual)) {
            List<Path> pages = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
            List<DynamicTest> tests = new ArrayList<>();
            for (Path page : pages) {
                String script = relixBlocks(page);
                if (!script.isBlank()) {
                    tests.add(DynamicTest.dynamicTest(manual.relativize(page).toString(),
                            () -> run(page, script)));
                }
            }
            assertThat(tests).as("runnable pages were found — an empty list means the "
                    + "manual moved or the block fences changed").isNotEmpty();
            return tests.stream();
        }
    }

    private static void run(Path page, String script) {
        try (Relix relix = Relix.builder().sandbox(SANDBOX).build()) {
            List<String> errors = relix.validate(script).stream()
                    .filter(Diagnostic::isError)
                    .map(Diagnostic::toString)
                    .toList();
            assertThat(errors).as("%s validates", page.getFileName()).isEmpty();
            for (Relation query : relix.script(script)) {
                query.run(); // force execution; a runtime failure throws
            }
        }
    }

    /** The {@code relix} fenced code blocks of a page, concatenated into one script. */
    private static String relixBlocks(Path page) {
        StringBuilder script = new StringBuilder();
        boolean inBlock = false;
        for (String line : read(page).split("\n", -1)) {
            String trimmed = line.strip();
            if (!inBlock && trimmed.equals("```relix")) {
                inBlock = true;
            } else if (inBlock && trimmed.equals("```")) {
                inBlock = false;
                script.append('\n');
            } else if (inBlock) {
                script.append(line).append('\n');
            }
        }
        return script.toString();
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path manualRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("docs/problem-solving"))) {
            p = p.getParent();
        }
        assertThat(p).as("repo root containing docs/problem-solving").isNotNull();
        return p.resolve("docs/problem-solving");
    }
}

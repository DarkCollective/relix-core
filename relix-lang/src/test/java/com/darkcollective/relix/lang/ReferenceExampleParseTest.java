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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parses every worked example in the language reference ({@code docs/reference})
 * <em>and</em> in the agent-facing notes ({@code .claude/*.md}) as part of the
 * regular build, so the docs can never again promise syntax the engine can't parse
 * (the {@code IIf(price > 100, …)} defect: the documented form failed to parse and
 * nothing checked it).
 *
 * <p>{@code .claude/CLAUDE.md} is included because it is
 * what an agent loads <em>first</em>: a stale claim there propagates into work
 * before anyone opens the reference page.
 *
 * <p>Two unambiguous kinds of unit are extracted and fed to {@link ScriptParser}:
 * <ul>
 *   <li>every fenced <code>```relix</code> block (a complete self-contained
 *       snippet), which must parse;</li>
 *   <li>every fenced <code>```relix-invalid</code> block, which must <em>not</em>
 *       parse — the way to write a "this form is rejected" claim as something the
 *       build checks rather than as a sentence nobody can verify. If the grammar
 *       later accepts it, this test fails and the doc must be corrected; and</li>
 *   <li>every single-line entry in a {@code # Examples:} section (a bare RA
 *       expression, wrapped in {@code query { … }}).</li>
 * </ul>
 * Multi-line indented {@code # Examples:} blocks used to be <em>skipped</em>: a
 * description may stack several independent one-liners or wrap one expression
 * across lines, and telling those apart heuristically would make the guard flaky.
 * That left 82 blocks on 31 pages unchecked — concentrated in the newest, most
 * intricate operators — and two of them documented syntax the grammar rejects
 * ({@code X := <expr>;} without braces, and {@code π dst AS src}, which is
 * {@code →} in Relix). So the ambiguity is now resolved in the docs rather than
 * guessed at here: a multi-line example must be fenced, which says explicitly
 * whether it is one Relix snippet ({@code ```relix}) or an illustration
 * (a plain fence). {@link #multiLineExamplesAreFenced()} enforces that.
 *
 * <p>{@link #KNOWN_UNPARSEABLE} quarantines the handful of examples that document
 * a genuine, tracked parser gap (so the build stays green) — each entry cites its
 * issue and must be removed when that issue is fixed; the test fails if a
 * quarantined entry starts parsing (stale) or a non-quarantined example stops
 * parsing (regression).
 *
 * <p>Quarantine versus {@code relix-invalid}: quarantine is for a form the docs
 * promise and the grammar does not <em>yet</em> support (a tracked bug);
 * {@code relix-invalid} is for a form that is <em>meant</em> to be rejected.
 *
 * <p>Parsing is where this guard stops: an example that parses can still be
 * <em>wrong</em>. The third member of the family,
 * {@code WorkedExampleOutputTest} (relix-console — the lowest module that can
 * execute a plan and render it with the shipped formatter), runs the worked
 * examples and diffs the rows against the result table each page prints.
 */
@DisplayName("Reference examples all parse")
final class ReferenceExampleParseTest {

    /**
     * Examples that document a real, tracked grammar gap and cannot parse yet.
     * Keyed by the exact extracted unit. REMOVE an entry when its issue is fixed.
     */
    private static final Set<String> KNOWN_UNPARSEABLE = Set.of();

    @Test
    @DisplayName("every documented example parses (and every relix-invalid one does not)")
    void referenceExamplesParse() {
        Path root = repoRoot();
        assertThat(root).as("repo root containing docs/reference").isNotNull();

        // relix-site/content is the third: the landing page shows the language before a
        // reader has read a word of the manual, so a sample that does not parse is the
        // worst place in the project to have one.
        List<ReferenceExamples.Example> examples = ReferenceExamples.extractAll(
                root.resolve("docs/reference"), root.resolve(".claude"),
                root.resolve("relix-site/content"));
        assertThat(examples).as("documented examples were found").isNotEmpty();
        // Each extra tree is asserted to have been reached, so that a sweep quietly
        // covering one directory is a failure rather than a smaller number nobody reads
        // — but only where the tree is there to reach. The public engine repository
        // carries docs/reference and neither of the other two, and a guard that fails
        // for material it was deliberately not given is a guard that gets deleted.
        wasScanned(examples, root, ".claude");
        wasScanned(examples, root, "relix-site/content");

        List<String> regressions = new ArrayList<>();
        List<String> nowParses = new ArrayList<>();
        Set<String> quarantineHits = new LinkedHashSet<>();
        Set<String> seenQuarantined = new LinkedHashSet<>();
        for (ReferenceExamples.Example ex : examples) {
            boolean parses = parses(ex.parseInput());
            if (!ex.expectParses()) {
                // A ```relix-invalid block: the doc claims this form is rejected.
                if (parses) {
                    nowParses.add(ex.location() + " :: " + norm(ex.unit()));
                }
                continue;
            }
            boolean known = KNOWN_UNPARSEABLE.contains(norm(ex.unit()));
            if (known) {
                seenQuarantined.add(norm(ex.unit()));
                if (parses) {
                    quarantineHits.add(norm(ex.unit()));   // now parses → un-quarantine it
                }
            } else if (!parses) {
                regressions.add(ex.location() + " :: " + norm(ex.unit()));
            }
        }

        Set<String> deadQuarantine = new LinkedHashSet<>(KNOWN_UNPARSEABLE);
        deadQuarantine.removeAll(seenQuarantined);

        assertThat(regressions)
                .as("documented examples that no longer parse — fix the engine or the doc "
                        + "(if this is a newly-documented, genuinely-unsupported form, file "
                        + "an issue and add it to KNOWN_UNPARSEABLE)")
                .isEmpty();
        assertThat(nowParses)
                .as("```relix-invalid examples that DO parse — the grammar now accepts a form "
                        + "the doc says it rejects; correct the prose around the block and move "
                        + "it to a plain ```relix fence")
                .isEmpty();
        assertThat(quarantineHits)
                .as("KNOWN_UNPARSEABLE entries that now parse — remove them from the "
                        + "quarantine (the tracked gap is fixed)")
                .isEmpty();
        assertThat(deadQuarantine)
                .as("KNOWN_UNPARSEABLE entries that match no documented example — the doc "
                        + "changed; remove the stale entry")
                .isEmpty();
    }

    @Test
    @DisplayName("multi-line examples are fenced, so none escapes the parse check")
    void multiLineExamplesAreFenced() {
        List<String> unfenced = new ArrayList<>();
        for (Path page : referencePages(repoRoot().resolve("docs/reference"))) {
            unfenced.addAll(unfencedMultiLineBlocks(page));
        }
        assertThat(unfenced)
                .as("""
                        multi-line indented blocks in a `# Examples:` section. Only single-line \
                        entries can be extracted unambiguously, so a block like this is silently \
                        skipped and nothing checks it. Fence it: ```relix when it is one Relix \
                        snippet (it will then be parsed), or a plain ``` fence when it is an \
                        illustration — sample data, a result table, an ASCII diagram""")
                .isEmpty();
    }

    /** Indented runs of 2+ lines inside a {@code # Examples:} section, outside any fence. */
    private static List<String> unfencedMultiLineBlocks(Path page) {
        List<String> out = new ArrayList<>();
        String[] lines = read(page).split("\n", -1);
        boolean inFence = false;
        boolean inExamples = false;
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String s = line.strip();
            if (s.startsWith("```")) {
                inFence = !inFence;
                i++;
                continue;
            }
            if (!inFence && line.startsWith("# ")) {
                inExamples = line.startsWith("# Examples:");
                i++;
                continue;
            }
            if (inFence || !inExamples || !ReferenceExamples.startsIndented(line) || s.isEmpty()) {
                i++;
                continue;
            }
            int start = i;
            while (i < lines.length && ReferenceExamples.startsIndented(lines[i]) && !lines[i].isBlank()) {
                i++;
            }
            if (i - start > 1) {
                out.add(page.getFileName() + ":" + (start + 1) + "  " + lines[start].strip());
            }
        }
        return out;
    }

    private static List<Path> referencePages(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().equals("README.md"))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── parsing ─────────────────────────────────────────────────────────────────

    /** Whitespace-normalised form, so quarantine entries are insensitive to doc reflow. */
    private static String norm(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }

    private static boolean parses(String scriptSource) {
        try {
            ScriptParser.parse(scriptSource);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ── repo-root discovery ─────────────────────────────────────────────────────

    /** Asserts that {@code tree} contributed at least one example, when this tree has it. */
    private static void wasScanned(List<ReferenceExamples.Example> examples, Path root, String tree) {
        if (!Files.exists(root.resolve(tree))) {
            return;
        }
        assertThat(examples).as("%s was scanned too", tree)
                .anyMatch(ex -> ex.page().toString().contains(tree));
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("docs/reference"))) {
            p = p.getParent();
        }
        return p;
    }
}

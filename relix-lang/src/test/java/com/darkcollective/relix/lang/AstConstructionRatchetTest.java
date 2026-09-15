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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct construction of an AST record in a test source only ever goes down.
 *
 * <p>{@link BuilderSurfaceGuardTest} forbids <em>re-declaring</em> a published factory,
 * which was a defect with a defensible end state — zero — and so could be a plain gate.
 * This is the larger and softer problem beside it: some 6,000 call sites that write
 * {@code new SelectionNode(p, r)} where {@code select(p, r)} now exists. Those are not
 * copies of anything — the canonical constructor is public API too — so a gate at zero
 * would start red, which {@code CLAUDE.md} is explicit is a gate people learn to skip.
 *
 * <p>So this is a <b>ratchet</b> rather than a gate, over
 * {@code tools/ast-builders/baseline.tsv}:
 *
 * <ol>
 *   <li><b>A file with no row must have none.</b> This is what stops new ones being
 *       written — a new test file has a budget of zero without anyone deciding so.</li>
 *   <li><b>A file with a row must not exceed it</b>, which stops the existing ones
 *       spreading within the files that still have them.</li>
 *   <li><b>A file below its row fails</b>, asking for the row to be tightened. That is
 *       the part that makes "migrate what you touch" something the build records rather
 *       than something people remember: a budget that can only ratchet down cannot drift
 *       back up, and the tightening lands in the same commit as the migration.</li>
 *   <li><b>A row naming a file that no longer exists fails</b> — the discipline
 *       {@code tools/coverage/register.tsv} follows, where an entry that outlives what it
 *       justified is reported rather than left standing.</li>
 * </ol>
 *
 * <p>The deliberate migration these budgets are counting down — what is mechanical, where
 * it stops, and in what order — is issue #780. Nothing here depends on it starting.
 *
 * <p><b>Some rows will never reach zero, and that is correct.</b> A test whose subject is
 * the record itself — its canonical constructor, its validation, its {@code SourceLocation}
 * component — has to call the constructor, and every factory defaults the location to
 * {@code UNKNOWN} with no overload taking one. The baseline is a record of where the
 * migration has got to, not a list of sins.
 */
@DisplayName("direct AST construction in tests only ratchets down")
final class AstConstructionRatchetTest {

    private static final Path BASELINE = Paths.get("tools/ast-builders/baseline.tsv");

    @Test
    @DisplayName("no test source exceeds its budget, and one without a row has none")
    void noFileExceedsItsBudget() {
        Map<String, Integer> budgets = baseline();
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, Integer> counted : counts().entrySet()) {
            int budget = budgets.getOrDefault(counted.getKey(), 0);
            if (counted.getValue() > budget) {
                offenders.add("%s  %d direct constructions, budget %d"
                        .formatted(counted.getKey(), counted.getValue(), budget));
            }
        }
        assertThat(offenders)
                .as("""
                        A test built an AST record with its constructor where the published \
                        builders have a factory — AstBuilders/Expr for a RelNode, Predicate or \
                        Operand, ScriptBuilders for a Statement. Use the factory; if the node \
                        genuinely needs a SourceLocation, or the record itself is the subject \
                        of the test, raise the file's row in tools/ast-builders/baseline.tsv \
                        and say which in the commit.""")
                .isEmpty();
    }

    @Test
    @DisplayName("a budget that is no longer needed is tightened, not left standing")
    void everyBudgetIsStillEarned() {
        Map<String, Integer> counts = counts();
        List<String> slack = new ArrayList<>();
        for (Map.Entry<String, Integer> budget : baseline().entrySet()) {
            Path file = repoRoot().resolve(budget.getKey());
            if (!Files.isRegularFile(file)) {
                slack.add(budget.getKey() + "  — no such file; delete the row");
                continue;
            }
            int actual = counts.getOrDefault(budget.getKey(), 0);
            if (actual < budget.getValue()) {
                slack.add("%s  %d left, budget still says %d — lower it to %d%s"
                        .formatted(budget.getKey(), actual, budget.getValue(), actual,
                                actual == 0 ? " (delete the row)" : ""));
            }
        }
        assertThat(slack)
                .as("""
                        A file now builds fewer nodes by hand than its budget allows. Lower the \
                        row — or delete it, if the file is done — in the same commit as the \
                        migration. This is the ratchet: a budget nobody tightens is one that \
                        lets the next edit put the calls back.""")
                .isEmpty();
    }

    @Test
    @DisplayName("the ratchet is measuring something")
    void baselineIsNotVacuous() {
        assertThat(recordKinds()).as("AST record kinds").hasSizeGreaterThan(80);
        assertThat(testSources()).as("test sources scanned").hasSizeGreaterThan(100);
        assertThat(baseline()).as("files with a remaining budget").isNotEmpty();
    }

    // =========================================================================

    /** {@code repo-relative path → direct constructions}, comments excluded. */
    private static Map<String, Integer> counts() {
        Pattern construction = Pattern.compile(
                "\\bnew (" + String.join("|", recordKinds()) + ")\\s*\\(");
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Path source : testSources()) {
            Matcher m = construction.matcher(codeOnly(read(source)));
            int n = 0;
            while (m.find()) {
                n++;
            }
            if (n > 0) {
                counts.put(relative(source), n);
            }
        }
        return counts;
    }

    private static Map<String, Integer> baseline() {
        Map<String, Integer> budgets = new LinkedHashMap<>();
        for (String line : read(repoRoot().resolve(BASELINE)).lines().toList()) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] row = line.split("\t");
            assertThat(row).as("a baseline row is <count>\\t<file>: " + line).hasSize(2);
            budgets.put(row[1].strip(), Integer.parseInt(row[0].strip()));
        }
        return budgets;
    }

    /** Every {@code public record} in the two AST modules — the hierarchies, not a list. */
    private static Set<String> recordKinds() {
        Set<String> kinds = new TreeSet<>();
        for (String dir : List.of("relix-ast/src/main/java/com/darkcollective/relix/ast",
                "relix-lang-ast/src/main/java/com/darkcollective/relix/lang/ast")) {
            try (Stream<Path> files = Files.list(repoRoot().resolve(dir))) {
                files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    Matcher record = Pattern.compile("public record (\\w+)").matcher(read(p));
                    while (record.find()) {
                        kinds.add(record.group(1));
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return kinds;
    }

    private static List<Path> testSources() {
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> modules = Files.list(repoRoot())) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                for (String set : List.of("src/test/java", "src/testFixtures/java")) {
                    Path dir = module.resolve(set);
                    if (!Files.isDirectory(dir)) {
                        continue;
                    }
                    try (Stream<Path> walk = Files.walk(dir)) {
                        walk.filter(Files::isRegularFile)
                                .filter(p -> p.toString().endsWith(".java"))
                                .forEach(sources::add);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        sources.sort(java.util.Comparator.comparing(AstConstructionRatchetTest::relative));
        return sources;
    }

    /** Comment lines blanked, so a javadoc showing the shape is not counted as one. */
    private static String codeOnly(String text) {
        StringBuilder out = new StringBuilder();
        boolean inBlockComment = false;
        for (String line : text.lines().toList()) {
            String trimmed = line.strip();
            boolean commented = inBlockComment
                    || trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*");
            if (trimmed.startsWith("/*") && !trimmed.contains("*/")) {
                inBlockComment = true;
            }
            if (inBlockComment && trimmed.endsWith("*/")) {
                inBlockComment = false;
            }
            out.append(commented ? "" : line).append('\n');
        }
        return out.toString();
    }

    private static String relative(Path source) {
        return repoRoot().relativize(source).toString();
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
            throw new AssertionError("could not locate the repository root from "
                    + Paths.get("").toAbsolutePath());
        }
        return path;
    }
}

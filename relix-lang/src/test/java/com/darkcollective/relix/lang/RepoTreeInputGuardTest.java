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
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A test that reads the repository tree says so in its module's build, or it does not run.
 *
 * <p>Gradle decides whether a {@code Test} task is up to date from its declared inputs,
 * and a file outside every source set is in none of them. A guard that reads one is
 * therefore UP-TO-DATE across exactly the edit it exists to catch: it passes, having
 * never run, over the file that changed. That is the one failure mode a guard must not
 * have, and it is invisible — a green build is what it looks like.
 *
 * <p>It is a guard rather than a convention because the convention had already failed
 * three times while being written down twice: {@code SchemaRepairParityTest} replayed
 * recorded cases that could be edited without it noticing, {@code ReplCommandRegistryTest}
 * was fresh only by accident because another module happened to copy
 * {@code docs/reference} into its resources, and {@code SiteBuildTest} asserted on the
 * capability guide's own heading while an edit to that page re-ran nothing.
 *
 * <p><b>The subject is a test that names a path</b>, and that boundary is worth stating
 * because the third of those falls outside it. A test reaching a tree through a class
 * that resolves it — {@code SiteBuildTest} hands {@code SiteBuilder} a repository root
 * and the builder finds {@code relix-site/content} for itself — names no such literal,
 * and nothing a scan of test sources can do will see it. What is checked is the shape
 * that can be: a literal path outside every source set, in the module that wrote it.
 *
 * <p>Within that, it is deliberately shallow. It cannot know that the literal is opened
 * rather than printed, and does not try — declaring an input that is only mentioned costs
 * a re-run nobody needed, while the reverse costs a guard.
 */
@DisplayName("A test that reads the repository tree declares it as an input")
final class RepoTreeInputGuardTest {

    /**
     * The trees a test can read that live outside every source set.
     *
     * <p>{@code build/} is not among them: it is a task's output, declared by naming the
     * task that writes it.
     */
    private static final Set<String> ROOTS =
            Set.of("docs", "tools", "examples", ".claude", "relix-site");

    /**
     * A string literal that opens with one of {@link #ROOTS}.
     *
     * <p>Matched against literals rather than against calls, since a path is assembled a
     * dozen ways — {@code resolve}, {@code Path.of}, a message naming the file it could
     * not find — and the literal is the one part they share.
     */
    private static final Pattern LITERAL = Pattern.compile("\"([^\"\\n]+)\"");

    /**
     * Characters that make a literal a pattern rather than a path.
     *
     * <p>{@code DocAudienceGuardTest} names the internal surfaces as one alternation,
     * {@code "docs/design/|\\.claude/|…"}, which opens with a root and is not a file.
     * Excluding by shape rather than by name is what keeps this free of a list of
     * exceptions, which is the place a guard goes to die.
     */
    private static final Pattern REGEX_METACHARACTER = Pattern.compile("[|\\\\*+\\[(?^$]");

    /** {@code inputs.dir(…)} / {@code inputs.file(…)} / {@code inputs.files(…)}. */
    private static final Pattern DECLARATION = Pattern.compile("inputs\\.(?:dir|file|files)\\b");

    /** A literal resolved against the repository root: {@code repoRoot().resolve("x")}. */
    private static final Pattern ROOT_RESOLVED =
            Pattern.compile("[Rr]oot\\(\\)\\.resolve\\(\"([^\"\n]+)\"\\)");

    /** The repository path inside a declaration: {@code inputs.dir("$rootDir/docs/guide")}. */
    private static final Pattern DECLARED_PATH =
            Pattern.compile("\\$rootDir/([^\"']+)");

    @Test
    @DisplayName("every module naming one declares it")
    void everyRepoTreeReadIsDeclared() {
        List<String> undeclared = new ArrayList<>();
        for (Path module : modules()) {
            String build = readIfPresent(module.resolve("build.gradle"));
            Set<String> read = new TreeSet<>(treesReadBy(module));
            read.addAll(rootPathsReadBy(module));
            for (String tree : read) {
                if (!declares(build, tree)) {
                    undeclared.add(module.getFileName() + " reads " + tree
                            + " in a test but its build.gradle does not declare it");
                }
            }
        }
        assertThat(undeclared)
                .as("add `inputs.dir(\"$rootDir/<tree>\")` to that module's test task — "
                        + "without it the guard passes by never running")
                .isEmpty();
    }

    // ── what a module reads ──────────────────────────────────────────────────

    /**
     * The trees this module's test sources name, each reduced to the unit a build
     * declares: {@code docs/reference} rather than {@code docs/reference/advanced/repl.md}.
     */
    private static Set<String> treesReadBy(Path module) {
        Set<String> trees = new TreeSet<>();
        Path tests = module.resolve("src/test/java");
        if (!Files.isDirectory(tests)) {
            return trees;
        }
        try (Stream<Path> walk = Files.walk(tests)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = LITERAL.matcher(read(file));
                while (m.find()) {
                    declarableUnit(m.group(1)).ifPresent(trees::add);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return trees;
    }

    /**
     * The declarable tree a literal names, if it names one.
     *
     * <p>Two segments deep, which is where the trees actually divide: {@code docs} holds
     * two manuals, a design directory and the model tooling, and a module reading one of
     * them should not be re-run by every change to the others.
     */
    private static java.util.Optional<String> declarableUnit(String literal) {
        if (REGEX_METACHARACTER.matcher(literal).find()) {
            return java.util.Optional.empty();
        }
        String[] parts = literal.split("/");
        // A bare root is a word, not a path: "docs" appears in prose, in a `:doc`
        // command and in a package name. Requiring a separator is what tells a path
        // from a noun without a list of the nouns.
        if (parts.length < 2 || !ROOTS.contains(parts[0])) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(parts[0] + "/" + parts[1]);
    }

    /**
     * Repository-root paths a module's tests name, which {@link #ROOTS} cannot see.
     *
     * <p>{@code ROOTS} is an allowlist of five directories, so a file at the repository
     * root is not judged and passed — it is never looked at. {@code settings.gradle} was
     * read by four tests and declared by nobody, and {@code ARCHITECTURE.md} was declared
     * only because a guard that read it was found vacuous by hand.
     *
     * <p>The signal is the call rather than the path: a literal resolved against the
     * repository root <em>is</em> a repository path, whatever it is called, and one that
     * is not is a basename. That distinction matters — {@code "README.md"} appears ten
     * times in test sources as a name being compared or resolved against a manual
     * directory, and treating it as a root file because a root file of that name exists
     * would be ten false demands.
     *
     * <p>A path inside a module directory is left to {@link #ROOTS} and to the module's
     * own compilation: a test reading its own module's sources is re-run by a change to
     * them already, and one reading another module's is the assembled-path case this
     * guard's contract already declines.
     */
    private static Set<String> rootPathsReadBy(Path module) {
        Set<String> paths = new TreeSet<>();
        Path tests = module.resolve("src/test/java");
        if (!Files.isDirectory(tests)) {
            return paths;
        }
        Set<String> moduleNames = modules().stream()
                .map(m -> m.getFileName().toString())
                .collect(java.util.stream.Collectors.toSet());
        try (Stream<Path> walk = Files.walk(tests)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = ROOT_RESOLVED.matcher(read(file));
                while (m.find()) {
                    String literal = m.group(1);
                    if (REGEX_METACHARACTER.matcher(literal).find()
                            || moduleNames.contains(literal.split("/")[0])
                            || ROOTS.contains(literal.split("/")[0])
                            // A literal naming nothing is not a path to declare. It is
                            // also what keeps this guard from reading its own javadoc:
                            // the example above is `resolve("x")`, and the scan cannot
                            // tell a comment from code.
                            || !Files.exists(repoRoot().resolve(literal))) {
                        continue;
                    }
                    paths.add(literal);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return paths;
    }

    /**
     * Whether the build names {@code tree}, or any tree containing it, inside an
     * {@code inputs.…} declaration.
     *
     * <p>An ancestor counts: a module that declares {@code .claude} is re-run by a change
     * to a file beneath it, which is the whole question. Declaring more than is
     * read costs a re-run; the guard is about declaring less.
     */
    private static boolean declares(String build, String tree) {
        return build.lines()
                .filter(line -> DECLARATION.matcher(line).find())
                .flatMap(RepoTreeInputGuardTest::declaredPaths)
                .anyMatch(declared -> covers(declared, tree));
    }

    /** The repository paths one {@code inputs.…} line names. */
    private static Stream<String> declaredPaths(String line) {
        List<String> paths = new ArrayList<>();
        Matcher m = DECLARED_PATH.matcher(line);
        while (m.find()) {
            paths.add(m.group(1));
        }
        return paths.stream();
    }

    /**
     * Whether declaring {@code declared} re-runs a task that reads {@code tree}.
     *
     * <p>True when they are the same path or {@code declared} is an ancestor of it —
     * a module that declares {@code .claude} is re-run by a change to a file beneath it,
     * which is the whole question. Declaring more than is read costs a re-run; the guard
     * is about declaring less.
     *
     * <p>It compares whole segments, which is the correction this method exists for. It
     * used to ask whether the declaration's <em>line</em> contained the tree or any of its
     * ancestors, and the last ancestor of any path is its bare root — so a line declaring
     * {@code tools/ast-builders/baseline.tsv} contained the string {@code tools} and
     * thereby satisfied a read of {@code tools/license}, a directory it has nothing to do
     * with. Every {@code tools/…} declaration satisfied every {@code tools/…} read, and
     * the same held under {@code docs/} — which is most of what this guard watches.
     */
    private static boolean covers(String declared, String tree) {
        return declared.equals(tree) || declared.startsWith(tree + "/") || tree.startsWith(declared + "/");
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? null : path.substring(0, slash);
    }

    // ── the tree ─────────────────────────────────────────────────────────────

    private static List<Path> modules() {
        try (Stream<Path> children = Files.list(repoRoot())) {
            return children.filter(p -> Files.isRegularFile(p.resolve("build.gradle")))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readIfPresent(Path file) {
        return Files.isRegularFile(file) ? read(file) : "";
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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

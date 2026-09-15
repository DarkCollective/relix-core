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
package com.darkcollective.relix.semantic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>What a value means has one home, and these are the two ways it has been taken
 * elsewhere.</b>
 *
 * <p>The grep-style counterpart to {@code RowIdentityLawTest}, which holds the mechanisms
 * that decide row identity to one answer. That one can only check the mechanisms that
 * exist; this forbids the two shapes by which a new one arrives.
 *
 * <p>Both are drawn from defects rather than from taste:
 *
 * <ol>
 *   <li><b>A second spelling of the coercion.</b> {@code ValueComparator.canonical} reads
 *       a string that names a boolean or spells an instant as the value it denotes, and
 *       the rule is deliberately one-sided — it reads one value and no partner — because
 *       a pairwise rule breaks the substitutability a {@code Comparator} requires. A
 *       second copy of it somewhere else does not merely duplicate: it is a rule that can
 *       drift, and the operators reading the two would then disagree about which rows are
 *       one row.</li>
 *   <li><b>Comparing against zero to ask whether two values are equal.</b>
 *       {@code ValueComparator.equal}'s javadoc says why not: comparing raises on a pair
 *       it cannot order, so it conflates <em>different</em> with <em>unorderable</em>, and
 *       it calls NULL equal to itself where equality says NULL matches nothing. A closure
 *       endpoint did exactly this, and a bound of another type than the graph's nodes
 *       turned an answer of no rows into a failed query.</li>
 * </ol>
 *
 * <h2>What is deliberately not here</h2>
 *
 * The third recurring question — which side of a join a dotted reference belongs to —
 * was consolidated into {@code NestedPaths.ownerOf} after the same bug appeared five
 * times. It is not guarded here because it is not checkable by reading text:
 * {@code AttributeNames.stripQualifier} has twenty-two legitimate call sites across eight
 * files, and what separates a correct use from the bug is what the caller does with the
 * answer. A rule needing an exception list that long is not a rule. What holds that seam
 * together is that both consumers call {@code ownerOf}, and a test cannot say more.
 */
@DisplayName("The meaning of a value has one home")
final class ValueSemanticsGuardTest {

    /** The modules an embedder gets without a front end. */
    private static final List<String> ENGINE_MODULES = List.of(
            "relix-ast", "relix-symbol", "relix-value", "relix-function", "relix-lang-ast",
            "relix-semantic", "relix-cost", "relix-plan", "relix-optimizer", "relix-events",
            "relix-json", "relix-provenance", "relix-processor");

    /** The one file allowed to say what a string denotes. */
    private static final String COERCION_HOME = "ValueComparator.java";

    /**
     * A string being read as the boolean it names — {@code canonical}'s first arm, and the
     * half of the coercion any file could write for itself. The temporal half needs
     * {@code coerceToAnyTemporal}, which is package-private and so already confined.
     */
    private static final Pattern BOOLEAN_COERCION = Pattern.compile(
            "equalsIgnoreCase\\(\"(?i:true|false)\"\\)"
            + "|\"(?i:true|false)\"\\s*\\.\\s*equals(IgnoreCase)?\\s*\\(");

    /**
     * A <em>sorting</em> comparator asked whether two values are equal.
     *
     * <p>The line is drawn at the instance comparators and not at {@code compareNonNull},
     * and the reason is what each one is for. {@code NULLS_LAST} and {@code NULLS_FIRST}
     * exist to sort, so they carry a policy about where NULL goes; reading {@code == 0}
     * off one imports that policy into an equality test, which is how a closure endpoint
     * came to treat NULL as equal to itself.
     *
     * <p>{@code compareNonNull} is the ordering question asked directly, and asking it is
     * sometimes exactly right. {@code JoinExecutor.allenTest} is the worked example: every
     * Allen relation is defined in terms of where two interval endpoints fall relative to
     * each other, so its {@code == 0} sits beside a {@code < 0} as one question asked
     * thirteen ways. Rewriting those to {@code equal} would put two different rules on the
     * same pair of values, which is the thing this file exists to prevent.
     */
    private static final Pattern COMPARE_AS_EQUALITY = Pattern.compile(
            "ValueComparator\\s*\\.\\s*NULLS_(LAST|FIRST)\\s*\\.\\s*compare\\s*\\(.*\\)\\s*[!=]=\\s*0");

    /** One offending line. */
    private record Offence(Path file, int line, String text) {
        @Override public String toString() {
            return file + ":" + line + "  " + text.strip();
        }
    }

    @Test
    @DisplayName("only ValueComparator says what a string denotes")
    void theCoercionHasOneHome() {
        List<Offence> found = scan(BOOLEAN_COERCION, COERCION_HOME);

        assertThat(found)
                .as("a string read as the boolean it names, outside %s. That rule is "
                    + "ValueComparator.canonical's, and a second copy of it is a second "
                    + "rule to drift: the operators reading the two then disagree about "
                    + "which rows are one row.%n%s", COERCION_HOME, render(found))
                .isEmpty();
    }

    @Test
    @DisplayName("a sorting comparator is not asked whether two values are equal")
    void orderingIsNotUsedAsEquality() {
        List<Offence> found = scan(COMPARE_AS_EQUALITY, null);

        assertThat(found)
                .as("a sorting comparator read for equality, standing in for "
                    + "ValueComparator.equal. It raises on a pair it cannot order, so it "
                    + "conflates 'different' with 'unorderable', and its NULL placement "
                    + "makes NULL equal to itself where equality says NULL matches "
                    + "nothing.%n%s", render(found))
                .isEmpty();
    }

    // =========================================================================

    /** Every line of engine main source matching {@code pattern}, outside {@code allowed}. */
    private static List<Offence> scan(Pattern pattern, String allowed) {
        List<Offence> found = new ArrayList<>();
        for (String module : ENGINE_MODULES) {
            Path main = repositoryRoot().resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(main)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(main)) {
                files.filter(f -> f.toString().endsWith(".java"))
                        .filter(f -> allowed == null || !f.getFileName().toString().equals(allowed))
                        .forEach(f -> collect(f, pattern, found));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + main, e);
            }
        }
        return found;
    }

    /**
     * Matches within {@code file}, ignoring comments — the rule is about what the code
     * does, and a file explaining why it does not do this must be able to say so.
     */
    private static void collect(Path file, Pattern pattern, List<Offence> into) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        boolean inBlockComment = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.strip();
            if (inBlockComment) {
                if (trimmed.contains("*/")) {
                    inBlockComment = false;
                }
                continue;
            }
            if (trimmed.startsWith("/*")) {
                inBlockComment = !trimmed.contains("*/");
                continue;
            }
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                continue;
            }
            if (pattern.matcher(line).find()) {
                into.add(new Offence(file, i + 1, line));
            }
        }
    }

    private static String render(List<Offence> found) {
        return found.stream().map(Offence::toString).reduce("", (a, b) -> a + b + "\n");
    }

    /** Walks up from the working directory to the repository root. */
    private static Path repositoryRoot() {
        Path path = Paths.get("").toAbsolutePath();
        while (path != null && !Files.isRegularFile(path.resolve("settings.gradle"))) {
            path = path.getParent();
        }
        if (path == null) {
            throw new IllegalStateException(
                    "cannot find the repository root above " + Paths.get("").toAbsolutePath());
        }
        return path;
    }
}

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
package com.darkcollective.relix.embed.equivalence;

import com.darkcollective.relix.optimizer.OptimizationCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@link OptimizationCode} must have an <strong>execution-equivalence</strong> test
 * or a recorded reason why it cannot — the guard that keeps this from silently regressing.
 *
 * <h2>Why a guard rather than a convention</h2>
 *
 * <p>{@code CLAUDE.md} already says a new rule owes an {@code OptimizerEquivalence} test.
 * Nothing enforced it, and the drift was not small: of 59 rule codes, <strong>34</strong>
 * had a pass unit test proving the rewrite's <em>shape</em> and nothing proving it
 * preserved the <em>answer</em>. They were not the exotic ones — they were the oldest and
 * most load-bearing: {@code SEL-001..006}, {@code PROJ-001..003}, the join rules, the NF²
 * nest/unnest laws. A convention that is checked by review alone decays at exactly the
 * rate that reviews get busy.
 *
 * <h2>What counts as covered</h2>
 *
 * <p>A code is covered when some test in this package that uses the
 * {@link OptimizerEquivalence} harness names it. That is deliberately a source scan rather
 * than a registry: a registry is a second place to update, and the failure mode this
 * guard exists to prevent is precisely someone updating one place and not the other.
 */
@DisplayName("Optimizer rules — every code has an equivalence test or a recorded reason")
final class OptimizerRuleCoverageTest {

    /**
     * Rules no source text can reach, and why. Each is a claim reviewed like any other,
     * and — as with the coverage register — a reason that no longer applies is reported
     * rather than left to outlive what it justified.
     *
     * <p>"Hard to write a query for" is not a reason. Both entries here are the two
     * shapes that genuinely admit no equivalence test: the grammar cannot produce the
     * input, or there is no unoptimized run to compare against.
     */
    private static final Map<OptimizationCode, String> NO_EQUIVALENCE_TEST =
            new LinkedHashMap<>(Map.of(
                    OptimizationCode.AGG_001,
                    "the rule needs a γ with grouping keys and NO aggregates over another γ, "
                            + "and the grammar cannot express one — `γ cust (R)` is a parse error "
                            + "(\"Expected aggregate function\"). RedundantGroupingPassTest builds "
                            + "the node directly, which is the only way to reach it.",

                    OptimizationCode.GEN_001,
                    "there is no unoptimized run to compare against: σ n < 10 over ℕ emits ten "
                            + "rows and then scans forever, since nothing tells the generator to "
                            + "stop. Making that scan finite IS the rule. Covered instead by "
                            + "OptimizerRuleEquivalenceTest.Generators, which checks the optimized "
                            + "rows against the rule's specification."));

    /** {@code OptimizationCode.SEL_001} and friends, as written in a test source. */
    private static final Pattern CODE_REFERENCE =
            Pattern.compile("OptimizationCode\\.([A-Z][A-Z0-9]*_\\d{3})\\b");

    @Test
    @DisplayName("no rule is left without an equivalence test or a recorded reason")
    void everyRuleIsAccountedFor() {
        Set<String> covered = codesNamedByEquivalenceTests();
        Set<String> excused = NO_EQUIVALENCE_TEST.keySet().stream()
                .map(Enum::name).collect(Collectors.toCollection(TreeSet::new));

        Set<String> unaccounted = Arrays.stream(OptimizationCode.values())
                .map(Enum::name)
                .filter(code -> !covered.contains(code) && !excused.contains(code))
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(unaccounted)
                .as("a rule with a pass unit test but no execution-equivalence test proves "
                        + "the rewrite's shape and not that it preserves the answer. Add a case "
                        + "to OptimizerRuleEquivalenceTest, or record why none is possible in "
                        + "NO_EQUIVALENCE_TEST — 'hard to reach' is not a reason.")
                .isEmpty();
    }

    @Test
    @DisplayName("no recorded reason outlives the rule it justified")
    void noStaleExclusions() {
        Set<String> real = Arrays.stream(OptimizationCode.values())
                .map(Enum::name).collect(Collectors.toSet());

        assertThat(NO_EQUIVALENCE_TEST.keySet().stream().map(Enum::name).toList())
                .as("an exclusion naming a rule that no longer exists")
                .allMatch(real::contains);
    }

    @Test
    @DisplayName("AGG-001 is still unreachable from source text")
    void aggregateFreeGroupingRemainsInexpressible() {
        // The two exclusions are excused for different reasons and behave differently
        // here. GEN-001 IS named by an equivalence test — one that checks the optimized
        // rows against the rule's specification, because no unoptimized baseline exists.
        // AGG-001 is named by none, because no query can reach it; if that ever changes,
        // the grammar gained an aggregate-free γ and the exclusion is owed a re-think.
        assertThat(codesNamedByEquivalenceTests())
                .as("a test naming AGG-001 means either the grammar changed or the test is "
                        + "not what it appears to be")
                .doesNotContain(OptimizationCode.AGG_001.name());
    }

    // =========================================================================

    /**
     * Rule codes named by a test in this package that drives the equivalence harness.
     * A file counts only if it references {@link OptimizerEquivalence}, so a code merely
     * mentioned in a comment elsewhere does not read as covered.
     */
    private static Set<String> codesNamedByEquivalenceTests() {
        Path dir = repoRoot().resolve(
                "relix-embed/src/test/java/com/darkcollective/relix/embed/equivalence");
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> sources = files
                    .filter(p -> p.getFileName().toString().endsWith("Test.java"))
                    .toList();

            Set<String> codes = new TreeSet<>();
            for (Path source : sources) {
                String text = Files.readString(source);
                boolean usesHarness = text.contains("OptimizerEquivalence")
                        || text.contains("assertEquivalent");
                if (!usesHarness || source.getFileName().toString().equals(
                        "OptimizerRuleCoverageTest.java")) {
                    continue;
                }
                Matcher m = CODE_REFERENCE.matcher(text);
                while (m.find()) {
                    codes.add(m.group(1));
                }
            }
            assertThat(codes)
                    .as("the scan found no rule codes at all — it is looking in the wrong "
                            + "place, and would pass vacuously")
                    .isNotEmpty();
            return codes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path repoRoot() {
        Path path = Paths.get("").toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("relix-embed"))) {
            path = path.getParent();
        }
        assertThat(path).as("repo root containing relix-embed/").isNotNull();
        return path;
    }
}

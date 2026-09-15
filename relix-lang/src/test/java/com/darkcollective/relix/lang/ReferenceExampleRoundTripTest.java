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

import com.darkcollective.relix.ast.AstEquivalence;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.lang.ast.AssignmentStatement;
import com.darkcollective.relix.lang.ast.DefRelationStatement;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.QueryAssignmentBody;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.lang.ast.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RelNode#prettyPrint()} must re-parse to the same tree.
 *
 * <p>That property is relied on in three places and was checked in none of them: it is
 * how {@code AstEquivalence} compares {@code RelNode}s (its digest <em>is</em> the
 * pretty-printed form, so the shared-sub-expression detector keys on it), and it is what
 * the REPL's {@code :source} / {@code :save} / {@code :ask} output promises — the engine
 * hands a user text it claims can be read back.  Round-tripping was asserted per operator
 * by whoever wrote that operator's parser test, and never enumerated, so an operator whose
 * printer disagreed with the grammar broke it silently.
 *
 * <p>One did.  {@code IntervalJoinNode} printed its four endpoints as a {@code ";"}
 * separated pair-of-pairs — the form ADR-0014 sketched — while the grammar shipped a
 * comma-separated four-tuple, so no {@code IJOIN} query survived a save-and-load.  This
 * test is what found it, on its first run.
 *
 * <h2>Why the corpus is the reference manual</h2>
 *
 * <p>Building one node of each kind reflectively is not possible from
 * {@code AstBuilderCoverageTest}'s enumeration alone — that test checks a factory
 * <em>exists</em>, not what arguments it takes.  The documented examples are a corpus that
 * already exists, is already required to parse ({@code ReferenceExampleParseTest}), and is
 * already kept complete per operator by {@code ProjectDocsGuardTest} — so coverage rides on
 * guards that are enforced rather than on a list maintained here.
 */
@DisplayName("Every documented example's AST re-parses from its own prettyPrint()")
final class ReferenceExampleRoundTripTest {

    /**
     * Kinds with no surface syntax, so no example can reach them.
     *
     * <p>{@code EmptyRelationNode} (∅) is optimizer-only — it is produced by
     * {@code EMPTY-001} and never written.
     */
    private static final Set<String> NO_SURFACE_SYNTAX = Set.of("EmptyRelationNode");

    /**
     * The floor on how many distinct node kinds the corpus exercises.
     *
     * <p>A lower bound rather than an exact count: adding an example that reaches a new
     * kind should never fail a build.  It ratchets — raise it when the corpus grows, and a
     * drop means examples were deleted or an operator lost its documented example.
     */
    private static final int MIN_KINDS_COVERED = 50;

    @Test
    @DisplayName("prettyPrint() re-parses to an equivalent tree, for every documented example")
    void everyDocumentedExampleRoundTrips() {
        List<String> failures = new ArrayList<>();
        Set<String> kinds = new TreeSet<>();
        int checked = 0;

        for (Path page : referencePages()) {
            for (String block : relixFences(read(page))) {
                Script parsed;
                try {
                    parsed = ScriptParser.parse(block);
                } catch (RuntimeException e) {
                    continue;   // ReferenceExampleParseTest owns "does it parse at all"
                }
                for (RelNode node : roots(parsed)) {
                    checked++;
                    collectKinds(node, kinds);
                    String printed = node.prettyPrint();

                    Script reparsed;
                    try {
                        reparsed = ScriptParser.parse("query { " + printed + " };");
                    } catch (RuntimeException e) {
                        failures.add(page.getFileName() + ": prettyPrint() does not parse — " + printed);
                        continue;
                    }
                    List<RelNode> back = roots(reparsed);
                    if (back.size() != 1) {
                        failures.add(page.getFileName() + ": prettyPrint() did not yield one query — " + printed);
                    } else if (!AstEquivalence.digest(node).equals(AstEquivalence.digest(back.get(0)))) {
                        failures.add(page.getFileName() + ": re-parsed to a different tree — " + printed);
                    }
                }
            }
        }

        assertThat(failures)
                .as("documented examples whose prettyPrint() does not round-trip. The printer "
                        + "and the grammar disagree: fix the printer arm, since the grammar and "
                        + "the reference page are the contract")
                .isEmpty();
        assertThat(checked)
                .as("no examples were checked — the corpus or the harvester is broken")
                .isPositive();
    }

    @Test
    @DisplayName("the corpus reaches every node kind that has surface syntax")
    void corpusCoversTheHierarchy() {
        Set<String> kinds = new TreeSet<>();
        for (Path page : referencePages()) {
            for (String block : relixFences(read(page))) {
                try {
                    roots(ScriptParser.parse(block)).forEach(n -> collectKinds(n, kinds));
                } catch (RuntimeException e) {
                    // not this test's concern
                }
            }
        }
        assertThat(kinds.size())
                .as("distinct RelNode kinds reached by a documented example (%s). A drop means an "
                        + "operator lost its example, which silently narrows the round-trip check "
                        + "above; raise the floor when the corpus grows", kinds)
                .isGreaterThanOrEqualTo(MIN_KINDS_COVERED);

        assertThat(kinds)
                .as("a kind marked as having no surface syntax was reached by an example after all")
                .doesNotContainAnyElementsOf(NO_SURFACE_SYNTAX);
    }

    // ── harvesting ────────────────────────────────────────────────────────────

    /** Every relational expression a script states: a query, a view body, or a TVF body. */
    private static List<RelNode> roots(Script script) {
        List<RelNode> roots = new ArrayList<>();
        for (Statement st : script.statements()) {
            if (st instanceof QueryStatement q && q.target() instanceof ExpressionQueryTarget e) {
                roots.add(e.expression());
            } else if (st instanceof AssignmentStatement a && a.body() instanceof QueryAssignmentBody b) {
                roots.add(b.expression());
            } else if (st instanceof DefRelationStatement d) {
                roots.add(d.body());
            }
        }
        return roots;
    }

    private static void collectKinds(RelNode node, Set<String> into) {
        into.add(node.getClass().getSimpleName());
        node.children().forEach(child -> collectKinds(child, into));
    }

    /** The contents of every <code>```relix</code> fence on a page. */
    private static List<String> relixFences(String markdown) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = null;
        for (String line : markdown.split("\n", -1)) {
            String trimmed = line.trim();
            if (current == null) {
                if (trimmed.equals("```relix")) {
                    current = new StringBuilder();
                }
            } else if (trimmed.startsWith("```")) {
                blocks.add(current.toString());
                current = null;
            } else {
                current.append(line).append('\n');
            }
        }
        return blocks;
    }

    private static List<Path> referencePages() {
        Path dir = Path.of("..", "docs", "reference");
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + dir.toAbsolutePath(), e);
        }
    }

    private static String read(Path page) {
        try {
            return Files.readString(page);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + page, e);
        }
    }
}

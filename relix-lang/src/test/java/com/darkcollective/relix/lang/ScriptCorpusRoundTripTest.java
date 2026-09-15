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

import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.lang.ast.ScriptBuilders;
import com.darkcollective.relix.lang.ast.ScriptCorpus;
import com.darkcollective.relix.lang.ast.ScriptPrinter;
import com.darkcollective.relix.lang.ast.Statement;
import com.darkcollective.relix.lang.ast.source.SourceConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trips <strong>every</strong> concrete {@link Statement} and {@link SourceConfig}
 * kind through {@code ScriptPrinter → ScriptParser}.
 *
 * <p>The statement-level counterpart of {@code RelNodeCorpusRoundTripTest}, and the half
 * that did not exist: expressions have been round-tripped over the whole hierarchy since
 * #716, while {@code Script} and its statements had no printer at all — the REPL's
 * {@code :save} replays the source text it captured, which a script assembled from an AST
 * does not have.
 *
 * <h2>What is compared</h2>
 *
 * <p>The parsed-back script must equal the original <em>modulo source locations</em>,
 * compared componentwise over the records themselves. That is deliberately stronger than
 * comparing printed forms: a printer that silently drops a component prints without it,
 * parses back to a value lacking it, and prints identically — so print-stability would
 * hold while the component was lost. Comparing the values catches it, which is the same
 * choice {@code RelNodeCorpusRoundTripTest} makes one level down.
 *
 * <p>Formatting is deliberately not compared. The printer normalises — one statement per
 * line, canonical keyword spelling, comments gone, since a comment is not in the AST.
 * What is promised is that the text reads back as the same script.
 */
@DisplayName("ScriptCorpus — print → parse over every statement and source kind")
final class ScriptCorpusRoundTripTest {

    /** Parses one statement's printed form back, wrapped in a script. */
    private static Script reparse(String text) {
        return ScriptParser.parse(text);
    }

    private static void assertRoundTrips(Statement original) {
        String printed = ScriptPrinter.print(original);
        Script parsed = reparse(printed);

        assertThat(parsed.statements()).as("printed as: %s", printed).hasSize(1);

        List<String> differences = new ArrayList<>();
        compare(original, parsed.statements().getFirst(), original.getClass().getSimpleName(),
                differences);
        assertThat(differences)
                .as("printed as: %s", printed)
                .isEmpty();
    }

    /**
     * Compares two AST values componentwise, ignoring {@link SourceLocation}, recording a
     * path-qualified line per difference.
     *
     * <p>Reflective over record components rather than a hand-written comparison per kind,
     * for the reason the corpus is reflective: a new component on an existing record is
     * exactly the change a hand-written comparison stops noticing.
     *
     * <p>This is the property that makes the test a gate. Comparing <em>printed forms</em>
     * would not: a printer that silently drops a component prints without it, parses to a
     * value lacking it, and prints identically — so print-stability holds while the
     * component is lost. Comparing the values catches it.
     */
    private static void compare(Object expected, Object actual, String path,
                                List<String> differences) {
        if (expected instanceof SourceLocation || actual instanceof SourceLocation) {
            return;   // locations differ by construction: UNKNOWN out, a real position back
        }
        if (expected == null || actual == null) {
            if (expected != actual) {
                differences.add(path + ": expected " + expected + " but was " + actual);
            }
            return;
        }
        if (expected instanceof Optional<?> e && actual instanceof Optional<?> a) {
            if (e.isPresent() != a.isPresent()) {
                differences.add(path + ": expected " + e + " but was " + a);
            } else {
                e.ifPresent(v -> compare(v, a.orElseThrow(), path, differences));
            }
            return;
        }
        if (expected instanceof List<?> e && actual instanceof List<?> a) {
            if (e.size() != a.size()) {
                differences.add(path + ": expected " + e.size() + " elements but was " + a.size());
                return;
            }
            for (int i = 0; i < e.size(); i++) {
                compare(e.get(i), a.get(i), path + "[" + i + "]", differences);
            }
            return;
        }
        if (expected.getClass() != actual.getClass()) {
            differences.add(path + ": expected a " + expected.getClass().getSimpleName()
                    + " but was a " + actual.getClass().getSimpleName());
            return;
        }
        if (expected.getClass().isRecord()) {
            for (RecordComponent component : expected.getClass().getRecordComponents()) {
                try {
                    compare(component.getAccessor().invoke(expected),
                            component.getAccessor().invoke(actual),
                            path + "." + component.getName(), differences);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("reading " + path + "." + component.getName(), e);
                }
            }
            return;
        }
        if (!expected.equals(actual)) {
            differences.add(path + ": expected " + expected + " but was " + actual);
        }
    }

    @Nested
    @DisplayName("the comparator itself")
    final class Comparator {

        // A gate rests on its comparison actually failing. These are the cases the
        // round-trip would silently pass if compare() were vacuous.

        @Test
        @DisplayName("a differing component is reported, with its path")
        void reportsDifferingComponent() {
            List<String> differences = new ArrayList<>();
            compare(ScriptCorpus.everyStatement().get(2),
                    com.darkcollective.relix.lang.ast.ScriptBuilders.connection(
                            "other", "jdbc", java.util.Map.of("url", "jdbc:h2:mem:x")),
                    "connection", differences);

            assertThat(differences).isNotEmpty();
            assertThat(differences.toString()).contains("connection.name");
        }

        @Test
        @DisplayName("a component dropped inside a nested record is reported")
        void reportsNestedDifference() {
            List<String> differences = new ArrayList<>();
            compare(ScriptBuilders.assign("Open", ScriptBuilders.rel("Orders")),
                    ScriptBuilders.assign("Open", ScriptBuilders.rel("Archived")),
                    "assign", differences);

            assertThat(differences).isNotEmpty();
            assertThat(differences.toString()).contains("assign.body.expression.name");
        }

        @Test
        @DisplayName("a differing kind is reported rather than compared componentwise")
        void reportsDifferingKind() {
            List<String> differences = new ArrayList<>();
            compare(ScriptBuilders.query("Open"), ScriptBuilders.query(ScriptBuilders.rel("Open")),
                    "query", differences);

            assertThat(differences).isNotEmpty();
            assertThat(differences.toString()).contains("but was a");
        }

        @Test
        @DisplayName("a source location difference alone is not a difference")
        void ignoresLocations() {
            List<String> differences = new ArrayList<>();
            compare(new com.darkcollective.relix.lang.ast.QueryStatement(
                            new com.darkcollective.relix.lang.ast.NamedQueryTarget("Open"),
                            SourceLocation.UNKNOWN),
                    new com.darkcollective.relix.lang.ast.QueryStatement(
                            new com.darkcollective.relix.lang.ast.NamedQueryTarget("Open"),
                            new SourceLocation("x.relix", 7, 3)),
                    "query", differences);

            assertThat(differences).isEmpty();
        }
    }

    @Nested
    @DisplayName("every statement kind")
    final class Statements {

        @TestFactory
        Stream<DynamicTest> roundTrip() {
            return ScriptCorpus.everyStatement().stream()
                    .map(s -> DynamicTest.dynamicTest(
                            s.getClass().getSimpleName(), () -> assertRoundTrips(s)));
        }
    }

    @Nested
    @DisplayName("every source configuration kind")
    final class SourceConfigs {

        @TestFactory
        Stream<DynamicTest> roundTrip() {
            // Named by the config, not the statement: all six are SourceDeclarations, so
            // the statement's own name would collide six ways and hide which one failed.
            return ScriptCorpus.everySourceDeclaration().stream()
                    .map(s -> DynamicTest.dynamicTest(
                            ((com.darkcollective.relix.lang.ast.SourceDeclaration) s)
                                    .config().getClass().getSimpleName(),
                            () -> assertRoundTrips(s)));
        }
    }

    @Nested
    @DisplayName("every assignment body form")
    final class AssignmentForms {

        @TestFactory
        Stream<DynamicTest> roundTrip() {
            return ScriptCorpus.everyAssignmentForm().stream()
                    .map(s -> DynamicTest.dynamicTest(
                            ((com.darkcollective.relix.lang.ast.AssignmentStatement) s)
                                    .body().getClass().getSimpleName(),
                            () -> assertRoundTrips(s)));
        }
    }

    @Nested
    @DisplayName("every optional component, populated")
    final class PopulatedComponents {

        @TestFactory
        Stream<DynamicTest> roundTrip() {
            return ScriptCorpus.everyPopulatedComponent().stream()
                    .map(s -> DynamicTest.dynamicTest(
                            s.getClass().getSimpleName(), () -> assertRoundTrips(s)));
        }
    }

    @Nested
    @DisplayName("a configuration with every optional component absent")
    final class NothingOptionalSet {

        @Test
        @DisplayName("prints an empty block and reads back as itself")
        void bareJsonSource() {
            // The other end of the populated cases: a JSON source names no records path
            // and no references, so every entry of its block is dropped and what is
            // printed is the empty block. That has to parse — the alternative is a
            // configuration the printer can produce and the grammar cannot read.
            assertRoundTrips(source("Bare", ScriptBuilders.jsonSource("./bare.json")));
        }
    }

    @Nested
    @DisplayName("a whole script")
    final class WholeScript {

        @Test
        @DisplayName("namespace and every statement survive together")
        void scriptRoundTrips() {
            Script original = new Script(
                    java.util.Optional.of("analytics"), ScriptCorpus.everyStatement());

            String printed = ScriptPrinter.print(original);
            Script parsed = reparse(printed);

            assertThat(parsed.namespace()).contains("analytics");
            assertThat(parsed.statements()).hasSameSizeAs(original.statements());

            List<String> differences = new ArrayList<>();
            for (int i = 0; i < original.statements().size(); i++) {
                compare(original.statements().get(i), parsed.statements().get(i),
                        "statement[" + i + "]", differences);
            }
            assertThat(differences).as("printed as:%n%s", printed).isEmpty();
        }

        @Test
        @DisplayName("an empty script prints to nothing and parses back to nothing")
        void emptyScript() {
            Script empty = script();
            assertThat(ScriptPrinter.print(empty)).isEmpty();
            assertThat(reparse(ScriptPrinter.print(empty)).statements()).isEmpty();
        }
    }
}

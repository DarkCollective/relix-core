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
package com.darkcollective.relix.embed;

import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.lang.ScriptParser;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;
import static com.darkcollective.relix.embed.EmbedAssertions.assertThatThrownBy;
import static com.darkcollective.relix.ast.Expr.attr;
import static com.darkcollective.relix.ast.Expr.eq;
import static com.darkcollective.relix.ast.Expr.rel;
import static com.darkcollective.relix.ast.Expr.select;
import static com.darkcollective.relix.ast.Expr.str;

@DisplayName("Relix — the session")
final class RelixSessionTest {

    /** A source with a declared schema, so the session needs nothing reachable. */
    private static final String ORDERS = """
            source Orders from csv("./orders.csv") {
                header: true,
                schema: { order_id: NUMBER, customer_id: NUMBER, status: STRING, amount: NUMBER }
            };
            """;

    @Nested
    @DisplayName("declarations accumulate")
    final class Declarations {

        @Test
        @DisplayName("a source declared in one call is visible to the next")
        void declarationsAccumulate() {
            try (Relix relix = Relix.open()) {
                relix.define(ORDERS);
                relix.define("Open := { σ status = 'OPEN' (Orders) };");

                assertThat(relix.relation("Open").node()).isNode(RelationNode.class);
                assertThat(relix.statements()).hasSize(2);
            }
        }

        @Test
        @DisplayName("define chains")
        void defineChains() {
            try (Relix relix = Relix.open()) {
                relix.define(ORDERS).define("Open := { σ status = 'OPEN' (Orders) };");
                assertThat(relix.statements()).hasSize(2);
            }
        }

        @Test
        @DisplayName("a query statement is refused by define, and named as the reason")
        void defineRefusesQueries() {
            try (Relix relix = Relix.open()) {
                relix.define(ORDERS);
                assertThatThrownBy(() -> relix.define("query { Orders };"))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("script(");
            }
        }

        @Test
        @DisplayName("text that does not analyse is refused, and the session is unchanged")
        void rejectedTextLeavesSessionIntact() {
            try (Relix relix = Relix.open()) {
                relix.define(ORDERS);

                assertThatThrownBy(() -> relix.define("Bad := { σ status = 'OPEN' (NoSuchThing) };"))
                        .isInstanceOf(RelixException.class);

                // The session must not be left holding text it cannot analyse: every later
                // call would then fail for a reason no longer on screen.
                assertThat(relix.statements()).hasSize(1);
                assertThat(relix.relation("Orders").node()).isRelation("Orders");
            }
        }

        @Test
        @DisplayName("a namespace declared in the text is kept")
        void namespaceIsKept() {
            try (Relix relix = Relix.open()) {
                relix.define("namespace analytics;\n" + ORDERS);
                assertThat(relix.definitions()).contains("namespace analytics;");
            }
        }
    }

    @Nested
    @DisplayName("relations are pinned to the model they were built against")
    final class Pinning {

        @Test
        @DisplayName("redefining a view does not change a relation already built over it")
        void redefinitionDoesNotReachBack() {
            try (Relix relix = Relix.open()) {
                relix.define(ORDERS);
                relix.define("Interesting := { σ status = 'OPEN' (Orders) };");
                Relation before = relix.relation("Interesting");

                relix.define("Interesting := { σ status = 'CLOSED' (Orders) };");
                Relation after = relix.relation("Interesting");

                // Both name the same view; they differ in the analysis they carry, which is
                // what "a relation is a value" has to mean to be worth anything.
                assertThat(before.model()).isNotSameAs(after.model());
                assertThat(bodyOf(before.model(), "Interesting"))
                        .isNotEqualTo(bodyOf(after.model(), "Interesting"));
            }
        }

        @Test
        @DisplayName("the session's own model is cached until the next define")
        void sessionModelIsCached() {
            try (Relix relix = Relix.open()) {
                relix.define(ORDERS);
                SemanticModel first = relix.model();
                assertThat(relix.model()).isSameAs(first);

                relix.define("Open := { σ status = 'OPEN' (Orders) };");
                assertThat(relix.model()).isNotSameAs(first);
            }
        }

        @Test
        @DisplayName("a relation carries its own expression's annotations, not just the session's")
        void relationModelAnnotatesItsOwnExpression() {
            try (Relix relix = Relix.open()) {
                relix.define(ORDERS);
                Relation r = relix.relation("σ status = 'OPEN' (Orders)");

                // This is why a relation's model is its own rather than the session's: the
                // session has never seen this expression, so nothing else could annotate it.
                assertThat(r.model().nodeSchemas().get(r.node())).isPresent();
            }
        }

        private static String bodyOf(SemanticModel model, String name) {
            return model.symbolTable().lookupRelation(name)
                    .map(Object::toString)
                    .orElseThrow();
        }
    }

    @Nested
    @DisplayName("creating relations")
    final class Creating {

        @Test
        @DisplayName("a bare name resolves to the relation it names")
        void bareName() {
            try (Relix relix = Relix.open()) {
                relix.define(ORDERS);
                assertThat(relix.relation("Orders").node()).isRelation("Orders");
            }
        }

        @Test
        @DisplayName("an expression parses in either spelling to the identical tree")
        void bothSpellings() {
            try (Relix relix = Relix.open()) {
                relix.define(ORDERS);

                Relation unicode = relix.relation("σ status = 'OPEN' (Orders)");
                Relation ascii = relix.relation("SELECT status = 'OPEN' (Orders)");

                assertThat(unicode.node()).isNode(SelectionNode.class);
                assertThat(unicode.node()).isEquivalentTo(ascii.node());
            }
        }

        @Test
        @DisplayName("a tree built with Expr needs no text at all")
        void fromBuiltTree() {
            try (Relix relix = Relix.open()) {
                relix.define(ORDERS);

                Relation built = relix.relation(
                        select(eq(attr("status"), str("OPEN")), rel("Orders")));

                assertThat(built.node())
                        .isEquivalentTo(relix.relation("σ status = 'OPEN' (Orders)").node());
            }
        }

        @Test
        @DisplayName("an unparseable expression is refused")
        void unparseable() {
            try (Relix relix = Relix.open()) {
                assertThatThrownBy(() -> relix.relation("σ σ σ"))
                        .isInstanceOf(RelixException.class);
            }
        }

        @Test
        @DisplayName("a reference to nothing is refused")
        void unresolvable() {
            try (Relix relix = Relix.open()) {
                relix.define(ORDERS);
                assertThatThrownBy(() -> relix.relation("σ status = 'OPEN' (NoSuchThing)"))
                        .isInstanceOf(RelixException.class);
            }
        }
    }

    @Nested
    @DisplayName("script")
    final class Scripts {

        @Test
        @DisplayName("installs the declarations and returns one relation per query, in order")
        void oneRelationPerQuery() {
            try (Relix relix = Relix.open()) {
                List<Relation> results = relix.script(ORDERS + """
                        Open := { σ status = 'OPEN' (Orders) };
                        query { Open };
                        query { σ amount > 100 (Orders) };
                        """);

                assertThat(results).hasSize(2);
                assertThat(results.getFirst().node()).isRelation("Open");
                assertThat(results.get(1).node()).isNode(SelectionNode.class);
                assertThat(relix.statements()).hasSize(2);   // the declarations, not the queries
            }
        }

        @Test
        @DisplayName("a script with no query installs its declarations and returns nothing")
        void declarationsOnly() {
            try (Relix relix = Relix.open()) {
                assertThat(relix.script(ORDERS)).isEmpty();
                assertThat(relix.statements()).hasSize(1);
            }
        }

        @Test
        @DisplayName("query by name and query by expression both yield a relation")
        void bothQueryForms() {
            try (Relix relix = Relix.open()) {
                List<Relation> results = relix.script(ORDERS + """
                        Open := { σ status = 'OPEN' (Orders) };
                        query Open;
                        query { Open };
                        """);

                assertThat(results).hasSize(2);
                assertThat(results.getFirst().node()).isRelation("Open");
                assertThat(results.get(1).node()).isRelation("Open");
            }
        }
    }

    @Nested
    @DisplayName("the session renders back as text")
    final class Definitions {

        @Test
        @DisplayName("what a session declares parses back to the same declarations")
        void definitionsRoundTrip() {
            try (Relix relix = Relix.open()) {
                relix.define(ORDERS);
                relix.define("Open := { σ status = 'OPEN' (Orders) };");

                String text = relix.definitions();
                assertThat(ScriptParser.parse(text, "<test>").statements())
                        .hasSameSizeAs(relix.statements());

                // The strong form: a fresh session that takes the text is the same session.
                try (Relix reloaded = Relix.open()) {
                    reloaded.define(text);
                    assertThat(reloaded.definitions()).isEqualTo(text);
                }
            }
        }

        @Test
        @DisplayName("a session with nothing declared renders to nothing")
        void emptySession() {
            try (Relix relix = Relix.open()) {
                assertThat(relix.definitions()).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("diagnostics as data")
    final class Diagnostics {

        @Test
        @DisplayName("validate reports what is wrong without throwing or installing")
        void validateReports() {
            try (Relix relix = Relix.open()) {
                relix.define(ORDERS);

                List<Diagnostic> problems =
                        relix.validate("Bad := { σ status = 'OPEN' (NoSuchThing) };");

                assertThat(problems).isNotEmpty();
                assertThat(problems.getFirst().message()).contains("NoSuchThing");
                assertThat(relix.statements()).hasSize(1);   // nothing was installed
            }
        }

        /**
         * {@code isNotEmpty()} was the whole of this assertion, which is what let the
         * position go missing unnoticed: a diagnostic that says only <em>something is
         * wrong</em> passes it. This is the one method on the session meant for text the
         * program did not write, so a caller rendering diagnostics needs to place the
         * failure, and the grammar knows where it is.
         */
        @Test
        @DisplayName("validate reports a parse failure rather than raising it, and places it")
        void validateReportsParseFailure() {
            try (Relix relix = Relix.open()) {
                List<Diagnostic> found = relix.validate("Orders := [| a |\n| 1 |];\nquery { σ σ };\n");

                assertThat(found).hasSize(1);
                Diagnostic only = found.getFirst();
                assertThat(only.isError()).isTrue();
                assertThat(only.message()).isNotBlank();
                assertThat(only.location())
                        .as("a syntax error the grammar placed: %s", only)
                        .isPresent();
                assertThat(only.location().orElseThrow().line())
                        .as("the third line is the one holding the malformed query")
                        .isEqualTo(3);
                assertThat(only.location().orElseThrow().column()).isPositive();
            }
        }

        /**
         * The position is stated once in each rendering. A thrown message is often all a
         * caller prints, so it carries the position in words; a placed diagnostic already
         * carries it as a location, so its message does not repeat it.
         */
        @Test
        @DisplayName("a parse failure states its position once: in the thrown message, or as the location")
        void parseFailurePositionStatedOnce() {
            String bad = "Orders := [| a |\n| 1 |];\nquery { π a.+1 (Orders) };\n";
            try (Relix relix = Relix.open()) {
                Diagnostic placed = relix.validate(bad).getFirst();
                assertThat(placed.location().orElseThrow().line()).isEqualTo(3);
                assertThat(placed.location().orElseThrow().column()).isEqualTo(13);
                assertThat(placed.message())
                        .isEqualTo("Syntax error in RA expression: Expected identifier after '.'; found '+'");

                assertThatThrownBy(() -> relix.define(bad))
                        .isInstanceOf(RelixException.class)
                        .hasMessageEndingWith("found '+' (line 3, col 13)");
            }
        }

        /**
         * {@code of(String)} and {@code toString()} are published members that nothing
         * called — the shape a caller reaches for when it has a complaint no position can
         * be attached to, and the shape every caller reaches for when it prints one. A
         * rendering nothing asserts is a rendering free to change under a reader relying
         * on it. The placed diagnostic comes from the analyser rather than a hand-built
         * one, so the rendering is asserted over a location the engine actually produced.
         */
        @Test
        @DisplayName("a diagnostic renders its severity, and its position when it has one")
        void diagnosticRenders() {
            try (Relix relix = Relix.open()) {
                Diagnostic placed =
                        relix.validate("Orders := [| a |\n| 1 |];\nquery { σ σ };\n").getFirst();
                SourceLocation where = placed.location().orElseThrow();
                assertThat(placed.toString())
                        .isEqualTo(where + ": error: " + placed.message());
            }

            Diagnostic unplaced = Diagnostic.of("analysis produced no model");
            assertThat(unplaced.location())
                    .as("of(String) is the no-position form: %s", unplaced)
                    .isEmpty();
            assertThat(unplaced.isError()).isTrue();
            assertThat(unplaced.toString()).isEqualTo("error: analysis produced no model");
        }

        @Test
        @DisplayName("a semantic error keeps its position too, which it always did")
        void validateReportsSemanticErrorPosition() {
            try (Relix relix = Relix.open()) {
                assertThat(relix.validate("query { σ x = 1 (NoSuchThing) };\n"))
                        .isNotEmpty()
                        .allSatisfy(d -> assertThat(d.location()).isPresent());
            }
        }

        @Test
        @DisplayName("valid text validates clean")
        void validTextIsClean() {
            try (Relix relix = Relix.open()) {
                relix.define(ORDERS);
                assertThat(relix.validate("Open := { σ status = 'OPEN' (Orders) };")).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("relix.version — what this process has installed")
    final class ComponentInventory {

        /**
         * The half the engine cannot answer for itself: connectors, solvers and drivers
         * live above it, and a JDBC driver behind a dependency it is not allowed to have.
         * The facade is where they become visible, so this is the test that the seam is
         * filled rather than merely present.
         */
        @Test
        @DisplayName("the facade reports itself and the providers the engine cannot see")
        void reportsTheHostsProviders() {
            try (Relix relix = Relix.open()) {
                assertThat(relix.relation("π kind (relix.version)")).tuples()
                        .as("the engine's own rows, plus what only the facade can see")
                        .extracting(row -> row.string("kind"))
                        .contains("engine", "facade", "function-library", "solver");
            }
        }

        @Test
        @DisplayName("the engine's row carries the version the build stamped")
        void engineVersionIsReal() {
            try (Relix relix = Relix.open()) {
                assertThat(relix.relation("σ kind = 'engine' (relix.version)")).tuples()
                        .singleElement()
                        .satisfies(row -> {
                            assertThat(row.string("component")).isEqualTo("relix-engine");
                            assertThat(row.string("version")).isNotBlank()
                                    .isNotEqualTo("unknown");
                        });
            }
        }

        @Test
        @DisplayName("it is a relation, so it composes like one")
        void composesLikeARelation() {
            try (Relix relix = Relix.open()) {
                assertThat(relix.relation(
                        "γ kind, COUNT(*) → installed (relix.version)")).tuples()
                        .isNotEmpty()
                        .allSatisfy(row -> assertThat(row.longValue("installed"))
                                .isGreaterThan(0));
            }
        }
    }
}

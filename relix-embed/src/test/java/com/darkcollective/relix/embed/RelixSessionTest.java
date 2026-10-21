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
import com.darkcollective.relix.semantic.ScriptLoader;
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
        @DisplayName("SQL's SELECT column list, an aggregate outside GROUP, comma-listed constraints and a trailing SORT are each told what to write instead")
        void sqlHabitsDiagnosed() {
            String table = "Items := [| Item | Value |\n| Tent | 3 |];\n";
            try (Relix relix = Relix.open()) {
                Diagnostic select = relix.validate(table
                        + "Totals := { SELECT 'TOTAL' -> Item, SUM(Value) -> Value (Items) };\n")
                        .getFirst();
                assertThat(select.message()).isEqualTo("Syntax error in RA expression:"
                        + " 'SELECT' (σ) filters rows by a condition, and found '->' where its"
                        + " comparison belongs. To choose or compute columns, use PROJECT (π):"
                        + " PROJECT a, b * 2 -> c (R)");
                assertThat(select.location().orElseThrow().line()).isEqualTo(3);
                assertThat(select.location().orElseThrow().column()).isEqualTo(28);

                Diagnostic aggregate = relix.validate(table
                        + "query { PROJECT 'TOTAL' -> Item, SUM(Value) -> Value (Items) };\n")
                        .getFirst();
                assertThat(aggregate.message()).isEqualTo("'SUM' is an aggregate and can only be"
                        + " used inside GROUP (γ). For a total over the whole relation, write"
                        + " GROUP SUM(x) -> total (R)");

                Diagnostic constraints = relix.validate(table
                        + "S := { OPTIMIZE MAXIMIZE SUM(Value) SUBJECT TO SUM(Value) <= 80,"
                        + " SUM(Value) <= 60 (Items) };\n").getFirst();
                assertThat(constraints.message()).isEqualTo("Syntax error in RA expression:"
                        + " Join OPTIMIZE constraints with AND, not ',':"
                        + " SUBJECT TO SUM(a) <= 80 AND SUM(b) <= 60");

                Diagnostic sort = relix.validate(table
                        + "query { PROJECT Item, Value (Items) SORT Item ASC };\n").getFirst();
                assertThat(sort.message()).isEqualTo("Syntax error in RA expression:"
                        + " 'SORT' is written before its input, which it takes in parentheses:"
                        + " SORT Item ASC (PROJECT Item, Value (Items))");
            }
        }

        @Test
        @DisplayName("a join or set operator written before its inputs is one error, at the operator, naming its infix form")
        void infixOperatorWrittenFirstDiagnosed() {
            String tables = "A := [| id |\n| 1 |];\nB := [| a_id |\n| 1 |];\n";
            try (Relix relix = Relix.open()) {
                List<Diagnostic> join = relix.validate(tables
                        + "J := { JOIN A >< A.id = B.a_id B };\n");
                assertThat(join).hasSize(1);
                assertThat(join.getFirst().message()).isEqualTo("Syntax error in RA expression:"
                        + " 'JOIN' goes between the two relations it joins: A JOIN B,"
                        + " or A >< condition B");
                assertThat(join.getFirst().location().orElseThrow().line()).isEqualTo(5);
                assertThat(join.getFirst().location().orElseThrow().column()).isEqualTo(8);

                List<Diagnostic> union = relix.validate(tables + "U := { UNION A B };\n");
                assertThat(union).hasSize(1);
                assertThat(union.getFirst().message()).isEqualTo("Syntax error in RA expression:"
                        + " 'UNION' goes between the two relations it combines: A UNION B");
                assertThat(union.getFirst().location().orElseThrow().column()).isEqualTo(8);
            }
        }

        @Test
        @DisplayName("a column renamed with =, : or AS in PROJECT or GROUP is one error, at the sign, naming expr -> name")
        void foreignAliasDiagnosed() {
            String tables = "loans := [| loan_id | member_id | name |\n| 1 | 2 | 3 |];\n";
            String[][] cases = {
                    {"PROJECT loan_id, days = loan_id * 2 (loans)", "23", "write loan_id * 2 -> days"},
                    {"PROJECT member_id, book_name: name (loans)", "29", "write name -> book_name"},
                    {"PROJECT member_id, name AS book_name (loans)", "25", "write name -> book_name"},
                    {"GROUP member_id, n = COUNT(*) (loans)", "20", "write COUNT(*) -> n"},
                    {"GROUP member_id, COUNT(*) AS n (loans)", "27", "write COUNT(*) -> n"},
            };
            try (Relix relix = Relix.open()) {
                for (String[] c : cases) {
                    List<Diagnostic> found = relix.validate(tables + "Q := { " + c[0] + " };\n");
                    assertThat(found).as(c[0]).hasSize(1);
                    assertThat(found.getFirst().message()).as(c[0])
                            .startsWith("Syntax error in RA expression: a column is named with expr -> name")
                            .endsWith(c[2]);
                    assertThat(found.getFirst().location().orElseThrow().line()).as(c[0]).isEqualTo(3);
                    assertThat(found.getFirst().location().orElseThrow().column()).as(c[0])
                            .isEqualTo(Integer.parseInt(c[1]) + "Q := { ".length());
                }
            }
        }

        @Test
        @DisplayName("a join written with SQL's ON or LEFT/INNER JOIN is one error, at ON or the keyword, naming the Relix join")
        void sqlJoinDiagnosed() {
            String tables = "loans := [| loan_id | member_id |\n| 1 | 2 |];\n"
                    + "members := [| member_id | name |\n| 2 | a |];\n";
            String[][] cases = {
                    {"loans JOIN members ON loans.member_id = members.member_id", "20",
                            "write loans >< loans.member_id = members.member_id members"},
                    {"members LJOIN loans ON members.member_id = loans.member_id", "21",
                            "write members LJOIN members.member_id = loans.member_id loans"},
                    {"loans >< members ON loans.member_id = members.member_id", "18",
                            "write loans >< loans.member_id = members.member_id members"},
                    {"members LEFT JOIN loans ON members.member_id = loans.member_id", "9",
                            "write members LJOIN members.member_id = loans.member_id loans"},
                    {"loans INNER JOIN members ON loans.member_id = members.member_id", "7",
                            "write loans >< loans.member_id = members.member_id members"},
            };
            try (Relix relix = Relix.open()) {
                for (String[] c : cases) {
                    List<Diagnostic> found = relix.validate(tables + "Q := { " + c[0] + " };\n");
                    assertThat(found).as(c[0]).hasSize(1);
                    assertThat(found.getFirst().message()).as(c[0])
                            .startsWith("Syntax error in RA expression: '")
                            .endsWith(c[2]);
                    assertThat(found.getFirst().location().orElseThrow().line()).as(c[0]).isEqualTo(5);
                    assertThat(found.getFirst().location().orElseThrow().column()).as(c[0])
                            .isEqualTo(Integer.parseInt(c[1]) + "Q := { ".length());
                    String fixed = c[2].substring("write ".length());
                    assertThat(relix.validate(tables + "Q := { " + fixed + " };\n"))
                            .as("the rewrite of " + c[0] + " validates").isEmpty();
                }
            }
        }

        @Test
        @DisplayName("aggregates in parentheses after GROUP's keys, and WITH TIES after TOP's sort keys, are one error naming the fix")
        void misplacedAggregatesAndTiesDiagnosed() {
            String tables = "T := [| age_group | name | seconds |\n| 1 | a | 2 |];\n";
            String[][] cases = {
                    {"GROUP age_group (COUNT(*) -> n) (T)", "17",
                            "write GROUP age_group, COUNT(*) -> n (T)"},
                    {"GROUP age_group, seconds (COUNT(*) -> n) (T)", "26",
                            "write GROUP age_group, seconds, COUNT(*) -> n (T)"},
                    {"GROUP age_group (COUNT(*) -> n, MIN(seconds) -> best) (T)", "17",
                            "write GROUP age_group, COUNT(*) -> n, MIN(seconds) -> best (T)"},
                    {"γ age_group (COUNT(*) -> n) (T)", "13",
                            "write γ age_group, COUNT(*) -> n (T)"},
                    {"GROUP BY age_group (COUNT(*) -> n) (T)", "20",
                            "write GROUP BY age_group, COUNT(*) -> n (T)"},
                    {"TOP 2 seconds ASC WITH TIES PER age_group (T)", "19",
                            "write TOP 2 WITH TIES seconds ASC PER age_group (T)"},
                    {"TOP 2 seconds ASC PER age_group WITH TIES (T)", "33",
                            "write TOP 2 WITH TIES seconds ASC PER age_group (T)"},
                    {"TOP 2 seconds ASC WITH TIES (T)", "19",
                            "write TOP 2 WITH TIES seconds ASC (T)"},
            };
            try (Relix relix = Relix.open()) {
                for (String[] c : cases) {
                    List<Diagnostic> found = relix.validate(tables + "Q := { " + c[0] + " };\n");
                    assertThat(found).as(c[0]).hasSize(1);
                    assertThat(found.getFirst().message()).as(c[0])
                            .startsWith("Syntax error in RA expression: ")
                            .endsWith(c[2]);
                    assertThat(found.getFirst().location().orElseThrow().line()).as(c[0]).isEqualTo(3);
                    assertThat(found.getFirst().location().orElseThrow().column()).as(c[0])
                            .isEqualTo(Integer.parseInt(c[1]) + "Q := { ".length());
                    String fixed = c[2].substring("write ".length());
                    assertThat(relix.validate(tables + "Q := { " + fixed + " };\n"))
                            .as("the rewrite of " + c[0] + " validates").isEmpty();
                }
            }
        }

        @Test
        @DisplayName("RENAME's pairs without parentheses, and a join's right input inside its condition, are one error naming the fix")
        void unparenthesizedRenameAndRightInputInConditionDiagnosed() {
            String tables = "T := [| age_group | name | seconds |\n| 1 | a | 2 |];\n"
                    + "M := [| age_group | best |\n| 1 | 2 |];\n";
            String[][] cases = {
                    {"RENAME age_group -> grp (T)", "18",
                            "write RENAME (age_group -> grp) (T)"},
                    {"RENAME age_group -> grp, name -> who (T)", "18",
                            "write RENAME (age_group -> grp, name -> who) (T)"},
                    {"T >< (T.age_group = M.age_group M)", "33",
                            "write T >< (T.age_group = M.age_group) M"},
                    {"T LJOIN (T.age_group = M.age_group M)", "36",
                            "write T LJOIN (T.age_group = M.age_group) M"},
            };
            try (Relix relix = Relix.open()) {
                for (String[] c : cases) {
                    List<Diagnostic> found = relix.validate(tables + "Q := { " + c[0] + " };\n");
                    assertThat(found).as(c[0]).hasSize(1);
                    assertThat(found.getFirst().message()).as(c[0])
                            .startsWith("Syntax error in RA expression: ")
                            .endsWith(c[2]);
                    assertThat(found.getFirst().location().orElseThrow().line()).as(c[0]).isEqualTo(5);
                    assertThat(found.getFirst().location().orElseThrow().column()).as(c[0])
                            .isEqualTo(Integer.parseInt(c[1]) + "Q := { ".length());
                    String fixed = c[2].substring("write ".length());
                    assertThat(relix.validate(tables + "Q := { " + fixed + " };\n"))
                            .as("the rewrite of " + c[0] + " validates").isEmpty();
                }
            }
        }

        @Test
        @DisplayName("SQL's ways of asking for distinct rows are one error naming DISTINCT (PROJECT …)")
        void sqlDistinctDiagnosed() {
            String tables = "T := [| age_group | name | seconds |\n| 1 | a | 2 |];\n";
            String[][] cases = {
                    {"SELECT DISTINCT age_group, seconds (T)", "8",
                            "write DISTINCT (PROJECT age_group, seconds (T))"},
                    {"SELECT DISTINCT * (T)", "8", "write DISTINCT (T)"},
                    {"DISTINCT age_group, seconds (T)", "10",
                            "write DISTINCT (PROJECT age_group, seconds (T))"},
                    {"PROJECT DISTINCT age_group, seconds (T)", "9",
                            "write DISTINCT (PROJECT age_group, seconds (T))"},
                    {"GROUP age_group, seconds (T)", "26",
                            "write DISTINCT (PROJECT age_group, seconds (T))"},
                    {"GROUP age_group, seconds -> n (T)", "31",
                            "write DISTINCT (PROJECT age_group, seconds -> n (T))"},
            };
            try (Relix relix = Relix.open()) {
                for (String[] c : cases) {
                    List<Diagnostic> found = relix.validate(tables + "Q := { " + c[0] + " };\n");
                    assertThat(found).as(c[0]).hasSize(1);
                    assertThat(found.getFirst().message()).as(c[0])
                            .startsWith("Syntax error in RA expression: ")
                            .endsWith(c[2]);
                    assertThat(found.getFirst().location().orElseThrow().line()).as(c[0]).isEqualTo(3);
                    assertThat(found.getFirst().location().orElseThrow().column()).as(c[0])
                            .isEqualTo(Integer.parseInt(c[1]) + "Q := { ".length());
                    String fixed = c[2].substring("write ".length());
                    assertThat(relix.validate(tables + "Q := { " + fixed + " };\n"))
                            .as("the rewrite of " + c[0] + " validates").isEmpty();
                }
            }
        }

        @Test
        @DisplayName("an expression as a statement, a ';' in braces and an unclosed brace name the fix (#187)")
        void statementStructureDiagnosed() {
            String tables = "orders := [| customer_id | total |\n| 1 | 120 |];\n"
                    + "customers := [| customer_id | name |\n| 1 | a |];\n";
            // script, line, column, the message's end, the rewrite that validates
            String[][] cases = {
                    {"customers ANTI customers.customer_id = orders.customer_id orders;", "5", "11",
                            "a result is written query { … }: write"
                                    + " query { customers ANTI customers.customer_id = orders.customer_id orders };"},
                    {"orders;", "5", "7", "a result is written query { … }: write query orders;"},
                    {"query { SELECT total > 100 (orders); };", "5", "36",
                            "a ';' ends a statement and goes after the closing brace:"
                                    + " write query { SELECT total > 100 (orders) };"},
                    {"Avg := { GROUP SUM(total) -> s, COUNT(*) -> n (orders); PROJECT s / n -> avg (Avg) };\n"
                            + "query Avg;", "5", "55",
                            "a view holds one expression; give the first its own name:"
                                    + " Avg_1 := { GROUP SUM(total) -> s, COUNT(*) -> n (orders) };"
                                    + " Avg := { PROJECT s / n -> avg (Avg_1) };"},
                    {"query { SELECT total > 100 (orders) ;", "5", "7",
                            "Unterminated brace block; put '}' before the ';':"
                                    + " write query { SELECT total > 100 (orders) };"},
                    {"Big := { SELECT total > 100 (orders) ;\nquery Big;", "5", "8",
                            "Unterminated brace block; put '}' before the ';':"
                                    + " write Big := { SELECT total > 100 (orders) };"},
            };
            try (Relix relix = Relix.open()) {
                for (String[] c : cases) {
                    List<Diagnostic> found = relix.validate(tables + c[0] + "\n");
                    assertThat(found).as(c[0]).hasSize(1);
                    assertThat(found.getFirst().message()).as(c[0]).endsWith(c[3]);
                    assertThat(found.getFirst().location().orElseThrow().line()).as(c[0])
                            .isEqualTo(Integer.parseInt(c[1]));
                    assertThat(found.getFirst().location().orElseThrow().column()).as(c[0])
                            .isEqualTo(Integer.parseInt(c[2]));
                    String fixed = c[3].substring(c[3].indexOf(": ") + 2).replaceFirst("^write ", "");
                    String rest = c[0].contains("\n") ? c[0].substring(c[0].indexOf('\n')) : "";
                    assertThat(relix.validate(tables + fixed + rest + "\n"))
                            .as("the rewrite of " + c[0]).isEmpty();
                }
            }
        }

        @Test
        @DisplayName("*, an aggregate in SELECT and IN (SELECT …) name GROUP, the relation or SEMI (#188)")
        void starAggregateAndSubqueryDiagnosed() {
            String tables = "orders := [| customer_id | total |\n| 1 | 120 |];\n"
                    + "customers := [| customer_id | name |\n| 1 | a |];\n";
            // expression, column, the message's end, the rewrite that validates (or null)
            String[][] cases = {
                    {"PROJECT COUNT(*) -> n (orders)", "15",
                            "COUNT(*) is an aggregate and belongs in GROUP: write GROUP COUNT(*) -> n (orders)",
                            "GROUP COUNT(*) -> n (orders)"},
                    {"COUNT(*) (orders)", "7",
                            "COUNT(*) is an aggregate and belongs in GROUP: write GROUP COUNT(*) -> n (orders)",
                            "GROUP COUNT(*) -> n (orders)"},
                    {"SELECT SUM(total) / COUNT(*) -> avg (orders)", "27",
                            "'SELECT' filters rows; an aggregate belongs in GROUP:"
                                    + " write PROJECT s / n -> avg (GROUP SUM(total) -> s, COUNT(*) -> n (orders))",
                            "PROJECT s / n -> avg (GROUP SUM(total) -> s, COUNT(*) -> n (orders))"},
                    {"GROUP SUM(*) -> n (orders)", "11",
                            "SUM needs a column, and only COUNT takes *: write GROUP SUM(column) -> n (orders),"
                                    + " naming the column, or COUNT(*) to count rows", null},
                    {"SELECT * (orders)", "8",
                            "a relation already has all its columns: write orders, or SELECT a condition (orders)",
                            "orders"},
                    {"PROJECT * (orders)", "9", "a relation already has all its columns: write orders", "orders"},
                    {"SELECT AVG(total) -> avg (orders)", "19",
                            "'SELECT' filters rows; an aggregate belongs in GROUP: write GROUP AVG(total) -> avg (orders)",
                            "GROUP AVG(total) -> avg (orders)"},
                    {"SELECT customer_id IN (SELECT customer_id FROM orders) (customers)", "24",
                            "IN takes a set such as {1, 2}; to keep rows with a match in another relation write"
                                    + " customers SEMI customers.customer_id = orders.customer_id orders",
                            "customers SEMI customers.customer_id = orders.customer_id orders"},
                    {"SELECT customer_id IN (SELECT customer_id (orders)) (customers)", "24",
                            "write customers SEMI customers.customer_id = orders.customer_id orders",
                            "customers SEMI customers.customer_id = orders.customer_id orders"},
                    {"SELECT customer_id IN { SELECT customer_id (orders) } (customers)", "25",
                            "write customers SEMI customers.customer_id = orders.customer_id orders",
                            "customers SEMI customers.customer_id = orders.customer_id orders"},
                    {"SELECT customer_id NOT IN (SELECT customer_id FROM orders) (customers)", "28",
                            "IN takes a set such as {1, 2}; to keep rows with no match in another relation write"
                                    + " customers ANTI customers.customer_id = orders.customer_id orders",
                            "customers ANTI customers.customer_id = orders.customer_id orders"},
            };
            try (Relix relix = Relix.open()) {
                for (String[] c : cases) {
                    List<Diagnostic> found = relix.validate(tables + "Q := { " + c[0] + " };\n");
                    assertThat(found).as(c[0]).hasSize(1);
                    assertThat(found.getFirst().message()).as(c[0])
                            .startsWith("Syntax error in RA expression: ")
                            .endsWith(c[2]);
                    assertThat(found.getFirst().location().orElseThrow().line()).as(c[0]).isEqualTo(5);
                    assertThat(found.getFirst().location().orElseThrow().column()).as(c[0])
                            .isEqualTo(Integer.parseInt(c[1]) + "Q := { ".length());
                    if (c[3] != null) {
                        assertThat(relix.validate(tables + "Q := { " + c[3] + " };\n"))
                                .as("the rewrite of " + c[0] + " validates").isEmpty();
                    }
                }
                assertThat(relix.validate(tables + "Q := { GROUP AVG(total) -> avg, COUNT(*) -> n (orders) };\n"))
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("a join's right relation written before its condition is one error naming the fix")
        void rightInputBeforeConditionDiagnosed() {
            String tables = "customers := [| customer_id | name |\n| 1 | a |];\n"
                    + "orders := [| order_id | customer_id |\n| 7 | 1 |];\n";
            String qualified = "customers.customer_id = orders.customer_id";
            String[][] cases = {
                    {"customers LJOIN orders customer_id = customer_id", "17",
                            "write customers LJOIN customer_id = customer_id orders"},
                    {"customers LJOIN orders " + qualified, "17",
                            "write customers LJOIN " + qualified + " orders"},
                    {"customers LJOIN orders " + qualified + " orders", "17",
                            "write customers LJOIN " + qualified + " orders"},
                    {"customers >< orders " + qualified, "14",
                            "write customers >< " + qualified + " orders"},
                    {"customers SEMI orders " + qualified, "16",
                            "write customers SEMI " + qualified + " orders"},
                    {"customers ANTI orders " + qualified, "16",
                            "write customers ANTI " + qualified + " orders"},
            };
            try (Relix relix = Relix.open()) {
                for (String[] c : cases) {
                    List<Diagnostic> found = relix.validate(tables + "Q := { " + c[0] + " };\n");
                    assertThat(found).as(c[0]).hasSize(1);
                    assertThat(found.getFirst().message()).as(c[0])
                            .startsWith("Syntax error in RA expression: ")
                            .endsWith(c[2]);
                    assertThat(found.getFirst().location().orElseThrow().line()).as(c[0]).isEqualTo(5);
                    assertThat(found.getFirst().location().orElseThrow().column()).as(c[0])
                            .isEqualTo(Integer.parseInt(c[1]) + "Q := { ".length());
                    // An unqualified condition still draws the self-comparison warning,
                    // which names the qualified form; the rewrite has no error.
                    String fixed = c[2].substring("write ".length());
                    assertThat(relix.validate(tables + "Q := { " + fixed + " };\n"))
                            .as("the rewrite of " + c[0] + " validates").noneMatch(Diagnostic::isError);
                }
            }
        }

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

        /**
         * π and τ placed an unknown column and σ and γ did not, so a script with several
         * selections gave no hint which one was meant.
         */
        @Test
        @DisplayName("an unknown attribute in a σ predicate or a γ aggregate is placed at the reference")
        void unknownAttributeIsPlaced() {
            String script = """
                    A := [
                    | id | x |
                    |----|---|
                    | 1  | 2 |
                    ];
                    query { σ nope > 1 (A) };
                    query { γ id, SUM(nope) -> s (A) };
                    """;
            try (Relix relix = Relix.open()) {
                List<Diagnostic> found = relix.validate(script);

                assertThat(found).extracting(Diagnostic::message).containsExactly(
                        "Selection σ: attribute 'nope' not found in input schema (available: id, x)",
                        "Aggregation γ SUM: attribute 'nope' not found in input schema (available: id, x)");
                assertThat(found).extracting(d -> d.location().orElseThrow().line())
                        .containsExactly(6, 7);
                assertThat(found).extracting(d -> d.location().orElseThrow().column())
                        .containsExactly(11, 19);
            }
        }

        /**
         * An import is parsed by the loader part-way through analysis rather than at the
         * front door, so its syntax error once escaped {@code validate} as an exception
         * naming no file. It is an error in that file, and is reported as one.
         */
        @Test
        @DisplayName("a syntax error in an imported file is a diagnostic placed in that file")
        void validateReportsImportedParseFailure() {
            ScriptLoader loader = path -> Relix.parse("Orders := { SELECT a >> 1 (X) };", path);
            try (Relix relix = Relix.builder().scriptLoader(loader).build()) {
                List<Diagnostic> found = relix.validate("import \"lib.relix\";\nquery { Orders };");

                assertThat(found).isNotEmpty();
                Diagnostic first = found.getFirst();
                assertThat(first.isError()).isTrue();
                assertThat(first.message())
                        .startsWith("Syntax error in RA expression:")
                        .doesNotContain("(line ");
                SourceLocation where = first.location().orElseThrow();
                assertThat(where.filePath()).isEqualTo("lib.relix");
                assertThat(where.line()).isEqualTo(1);
                assertThat(where.column()).isEqualTo(23);

                assertThatThrownBy(() -> relix.define("import \"lib.relix\";"))
                        .as("the throwing API names the file, having no location to carry it")
                        .isInstanceOf(RelixException.class)
                        .hasMessageStartingWith("lib.relix:1:23: Syntax error in RA expression:");
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

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

import com.darkcollective.relix.semantic.internal.SemanticResult;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.relation.RelationSymbol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;
import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;

/**
 * Relation parameters of a table-valued function: the body is checked once, against the
 * declared heading; a call is checked against it; and a view over a call is typed.
 */
@DisplayName("Table-valued functions — relation parameters")
final class RelationParameterSemanticTest {

    private static final String EDGES = """
            Edges := [
            | src | dst | w |
            |-----|-----|---|
            | 1   | 2   | 5 |
            ];
            Names := [
            | src | dst |
            |-----|-----|
            | a   | b   |
            ];
            """;

    private static final String SRCS = "def srcs(E: RELATION(src: NUMBER, dst)) : RELATION := { π src (E) };\n";

    @Nested
    @DisplayName("the body, checked at the def")
    final class Body {

        @Test
        @DisplayName("reads the parameter as a relation with its heading, and nothing more")
        void readsTheHeading() {
            SemanticResult r = analyze(EDGES + SRCS + "query { srcs(Edges) };");
            assertThat(r).hasNoErrors();

            // w is in the argument but not in the heading, so the body cannot see it.
            assertThat(analyze(EDGES
                    + "def ws(E: RELATION(src, dst)) : RELATION := { π w (E) };\nquery { UNIT };"))
                    .hasErrorContaining("w");
        }

        @Test
        @DisplayName("may qualify a column by the parameter's name")
        void qualifiesByName() {
            assertThat(analyze(EDGES
                    + "def firsts(E: RELATION(src, dst)) : RELATION := { π E.src (E) };\n"
                    + "query { firsts(Edges) };")).hasNoErrors();
        }

        @Test
        @DisplayName("means the parameter where a script relation has the same name")
        void shadowsAScriptRelation() {
            assertThat(analyze(EDGES + """
                    E := [
                    | z |
                    |---|
                    | 1 |
                    ];
                    def f(E: RELATION(src)) : RELATION := { π src (E) };
                    query { f(Edges) };
                    """)).hasNoErrors();
        }

        @Test
        @DisplayName("passes a parameter on to another function, checked against that one's heading")
        void passesItOn() {
            assertThat(analyze(EDGES + SRCS
                    + "def both(E: RELATION(src: NUMBER, dst)) : RELATION := { srcs(E) };\n"
                    + "query { both(Edges) };")).hasNoErrors();
            assertThat(analyze(EDGES + SRCS
                    + "def narrow(E: RELATION(dst)) : RELATION := { srcs(E) };\nquery { UNIT };"))
                    .hasErrorContaining("relation parameter 'E' needs a column 'src', and 'E' has none");
        }
    }

    @Nested
    @DisplayName("a call")
    final class Call {

        @Test
        @DisplayName("needs every declared column")
        void needsEveryColumn() {
            assertThat(analyze(EDGES + """
                    T := [
                    | src |
                    |-----|
                    | 1   |
                    ];
                    """ + SRCS + "query { srcs(T) };"))
                    .hasErrorContaining("Table-valued function 'srcs': relation parameter 'E' needs a "
                            + "column 'dst', and 'T' has none");
        }

        @Test
        @DisplayName("needs each typed column at a compatible type")
        void needsCompatibleTypes() {
            assertThat(analyze(EDGES + SRCS + "query { srcs(Names) };"))
                    .hasErrorContaining("needs column 'src' to be NUMBER, and in 'Names' it is STRING");
        }

        @Test
        @DisplayName("takes a relation's name, not an expression")
        void takesAName() {
            assertThat(analyze(EDGES + SRCS + "query { srcs(1 + 2) };"))
                    .hasErrorContaining("takes the name of a relation, not an expression");
        }

        @Test
        @DisplayName("refuses a name that is not a relation, and suggests one that is")
        void refusesAnUnknownName() {
            assertThat(analyze(EDGES + SRCS + "query { srcs(Edgse) };"))
                    .hasErrorContaining("is given 'Edgse', which is not a relation")
                    .hasErrorContaining("Edges");
        }

        @Test
        @DisplayName("accepts a relation whose schema is read from the data")
        void acceptsAnOpenSchema() {
            assertThat(analyze("source Feed from json(\"feed.json\");\n" + SRCS + "query { srcs(Feed) };"))
                    .hasNoErrorContaining("relation parameter");
        }

        @Test
        @DisplayName("is not checked against a view that did not resolve, which reports its own error")
        void skipsAnUnresolvedView() {
            assertThat(analyze(SRCS + "V := { Missing };\nquery { srcs(V) };"))
                    .hasErrorContaining("Missing")
                    .hasNoErrorContaining("relation parameter");
        }

        @Test
        @DisplayName("keeps a scalar argument constant")
        void keepsScalarsConstant() {
            assertThat(analyze(EDGES
                    + "def after(E: RELATION(src), k: NUMBER) : RELATION := { σ src > k (E) };\n"
                    + "query { after(Edges, 1) };")).hasNoErrors();
        }

        @Test
        @DisplayName("cannot be made through LATERAL, whose arguments are row values")
        void notThroughLateral() {
            assertThat(analyze(EDGES + SRCS + "query { (Edges) LATERAL srcs(src) };"))
                    .hasErrorContaining("takes a relation parameter 'E', which LATERAL cannot supply");
        }
    }

    @Nested
    @DisplayName("an ITERATE step passing its relation")
    final class InsideIterate {

        @Test
        @DisplayName("counts as reading it, and is checked against the binder's heading")
        void isAReference() {
            assertThat(analyze(EDGES
                    + "def keep(E: RELATION(src: NUMBER, dst)) : RELATION := { E };\n"
                    + "query { ITERATE R (π src, dst (Edges), keep(R)) ROUNDS 2 };")).hasNoErrors();
        }

        @Test
        @DisplayName("needs the binder to have the declared columns")
        void checksTheBinder() {
            assertThat(analyze(EDGES
                    + "def keep(E: RELATION(w)) : RELATION := { E };\n"
                    + "query { ITERATE R (π src, dst (Edges), keep(R)) ROUNDS 2 };"))
                    .hasErrorContaining("relation parameter 'E' needs a column 'w', and 'R' has none");
        }

        @Test
        @DisplayName("a FIX step may call a function over other relations")
        void aFixStepMayCallOtherwise() {
            assertThat(analyze(EDGES
                    + "def after(E: RELATION(src: NUMBER, dst), k: NUMBER) : RELATION := { σ src > k (E) };\n"
                    + "query { FIX F (π src, dst (Edges), π src, dst (F ⋈ after(Edges, 0))) };"))
                    .hasNoErrorContaining("is passed to table-valued function");
        }

        @Test
        @DisplayName("a FIX step may not do the same")
        void notFromAFix() {
            assertThat(analyze(EDGES
                    + "def keep(E: RELATION(src, dst)) : RELATION := { E };\n"
                    + "query { FIX F (π src, dst (Edges), keep(F)) };"))
                    .hasErrorContaining("Recursive relation 'F' is passed to table-valued function 'keep'");
        }
    }

    @Nested
    @DisplayName("views over calls")
    final class Views {

        @Test
        @DisplayName("are typed, however views and functions refer to one another")
        void areTyped() {
            // First is a call over a table; Second a call over a view that is itself defined
            // after the function — both orders a single pass got wrong.
            SemanticModel m = model(EDGES + SRCS + """
                    First := { srcs(Edges) };
                    Pairs := { π src, dst (Edges) };
                    Second := { srcs(Pairs) };
                    """);
            for (String view : new String[] {"First", "Second"}) {
                RelationSymbol symbol = m.symbolTable().lookupRelation(view).orElseThrow();
                org.assertj.core.api.Assertions.assertThat(symbol.schema().columns())
                        .as(view).extracting(ColumnDefinition::name).containsExactly("src");
            }
        }

        @Test
        @DisplayName("an unresolvable view reports its error once")
        void reportsOnce() {
            assertThat(analyze("V := { Missing };\nquery V;")).hasDiagnosticCount(1);
        }
    }
}

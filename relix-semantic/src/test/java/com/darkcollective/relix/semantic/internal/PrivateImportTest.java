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
package com.darkcollective.relix.semantic.internal;

import com.darkcollective.relix.lang.ScriptParser;
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.semantic.SemanticFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code private} keeps a declaration away from every file that imports the one declaring it,
 * whichever import form is used, while the declaring file keeps using it freely.
 */
@DisplayName("private declarations across import forms")
final class PrivateImportTest {

    private static final String TABLE = "[\n| a |\n|---|\n| 1 |\n]";

    private static SemanticResult analyse(String root, String... pathsAndTexts) {
        Map<String, Script> files = new HashMap<>();
        for (int i = 0; i < pathsAndTexts.length; i += 2) {
            files.put(pathsAndTexts[i], ScriptParser.parse(pathsAndTexts[i + 1], pathsAndTexts[i]));
        }
        Script main = ScriptParser.parse(root, "main.relix");
        return new SemanticAnalyzer(new InMemoryScriptLoader(files)).analyze(main, "main.relix");
    }

    private static List<String> messages(SemanticResult result) {
        return SemanticFixtures.errorMessages(result);
    }

    /** One private declaration of each kind a file can hold, in {@code lib.relix}. */
    private static final String LIB = """
            private Hid := %s;
            private source Gen from csv("gen.csv") { schema: { n: NUMBER } };
            private def hidden(x: NUMBER): NUMBER := { x + 1 };
            private def hiddenRel(): RELATION := { Hid };
            Pub := { Hid };
            PubGen := { Gen };
            def pubFn(x: NUMBER): NUMBER := { hidden(x) };
            """.formatted(TABLE);

    @Nested
    @DisplayName("whole-file import")
    class WholeFile {

        @Test
        @DisplayName("public names work, including ones built on private ones")
        void publicNamesWork() {
            SemanticResult result = analyse("""
                    import "lib.relix";
                    query { Pub × PubGen };
                    """, "lib.relix", LIB);
            // PubGen has column "n" or similar; only the absence of privacy errors matters
            assertThat(messages(result)).noneMatch(m -> m.contains("private"));
        }

        @Test
        @DisplayName("a public def that calls a private def still works for the importer")
        void publicDefOverPrivateDef() {
            SemanticResult result = analyse("""
                    import "lib.relix";
                    query { π pubFn(a) -> z (Pub) };
                    """, "lib.relix", LIB);
            assertThat(result.errors()).isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "query { Hid };",
                "query { Gen };",
                "query { π hidden(a) -> z (Pub) };",
                "query { hiddenRel() };",
                "V := { Hid }; query { V };",
                "def f(x: NUMBER): NUMBER := { hidden(x) }; query { π f(a) -> z (Pub) };",
                "query Hid;",
                "query { σ a IN (π a (Hid)) (Pub) };",
        })
        @DisplayName("a private declaration is rejected with a positioned, specific error")
        void privateIsRejected(String use) {
            SemanticResult result = analyse("import \"lib.relix\";\n" + use, "lib.relix", LIB);
            assertThat(result.errors()).anySatisfy(e -> {
                assertThat(e.message()).contains("private to 'lib.relix'");
                assertThat(e.line()).isEqualTo(2);
                assertThat(e.filePath()).isEqualTo("main.relix");
            });
        }
    }

    @Nested
    @DisplayName("named import")
    class Named {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"Hid", "Gen", "hidden", "hiddenRel"})
        @DisplayName("naming a private declaration says it is private")
        void namingPrivate(String name) {
            SemanticResult result = analyse(
                    "import { " + name + " } from \"lib.relix\";\nquery { Pub };", "lib.relix", LIB);
            assertThat(messages(result)).anyMatch(m -> m.contains("'" + name + "' is private to 'lib.relix'"));
        }

        @Test
        @DisplayName("an unknown name is still reported as not found")
        void unknownName() {
            SemanticResult result = analyse(
                    "import { Nope } from \"lib.relix\";\nquery { Pub };", "lib.relix", LIB);
            assertThat(messages(result)).anyMatch(m -> m.contains("not found or not exported"));
        }

        @Test
        @DisplayName("a private name is rejected even when something else is imported by name")
        void privateStillHiddenAfterNamedImport() {
            SemanticResult result = analyse(
                    "import { Pub } from \"lib.relix\";\nquery { Hid };", "lib.relix", LIB);
            assertThat(messages(result)).anyMatch(m -> m.contains("private to 'lib.relix'"));
        }
    }

    @Nested
    @DisplayName("transitive imports and namespaces")
    class Transitive {

        @Test
        @DisplayName("A imports B imports C: C's private name stays hidden from A")
        void transitive() {
            SemanticResult result = analyse("""
                    import "b.relix";
                    query { Hid };
                    """, "b.relix", "import \"lib.relix\";\nBPub := { Pub };", "lib.relix", LIB);
            assertThat(messages(result)).anyMatch(m -> m.contains("private to 'lib.relix'"));
        }

        @Test
        @DisplayName("a view that reads a private name of an imported file works through two hops")
        void viewsThroughTwoHops() {
            SemanticResult result = analyse("""
                    import "b.relix";
                    query { BPub };
                    """, "b.relix", "import \"lib.relix\";\nBPub := { Pub };", "lib.relix", LIB);
            assertThat(result.errors()).isEmpty();
        }

        @Test
        @DisplayName("privacy holds when the imported file is in another namespace")
        void namespaces() {
            SemanticResult result = analyse("""
                    import "lib.relix";
                    query { Hid };
                    """, "lib.relix", "namespace lib;\n" + LIB);
            assertThat(messages(result)).anyMatch(m -> m.contains("private to 'lib.relix'"));
        }

        @Test
        @DisplayName("a private declaration is usable inside its own file, in a query too")
        void ownFile() {
            SemanticResult result = analyse("""
                    private Hid := %s;
                    query { Hid };
                    """.formatted(TABLE));
            assertThat(result.errors()).isEmpty();
        }
    }

    @Nested
    @DisplayName("private connections")
    class Connections {

        private static final String CONN = """
                private connection db from database { url: "jdbc:h2:mem:x" };
                """;

        @Test
        @DisplayName("an importer cannot name a private connection of another file")
        void connectionHidden() {
            SemanticResult result = analyse("""
                    import "conn.relix";
                    query { db.t };
                    """, "conn.relix", CONN);
            // The use may fail for other reasons (no such table); the privacy error is what matters
            assertThat(messages(result)).anyMatch(m -> m.contains("connection 'db' is private to 'conn.relix'"));
        }
    }

    @Test
    @DisplayName("a private connection can be named in the file that declares it")
    void ownConnectionIsUsable() {
        SemanticResult result = analyse("""
                private connection db from database { url: "jdbc:h2:mem:x" };
                query { db.t };
                """);
        assertThat(messages(result)).noneMatch(m -> m.contains("private"));
    }

    @Nested
    @DisplayName("a name declared in two files")
    class Clash {

        @Test
        @DisplayName("an imported name that a local declaration already holds is an error")
        void localThenImport() {
            SemanticResult result = analyse("""
                    Pub := %s;
                    import "lib.relix";
                    query { Pub };
                    """.formatted(TABLE), "lib.relix", LIB);
            assertThat(result.errors()).anySatisfy(e -> {
                assertThat(e.message()).contains("'Pub' is declared in both");
                assertThat(e.filePath()).isEqualTo("main.relix");
                assertThat(e.line()).isEqualTo(1);
            });
        }

        @Test
        @DisplayName("a local declaration after an import of the same name is an error")
        void importThenLocal() {
            SemanticResult result = analyse("""
                    import "lib.relix";
                    Pub := %s;
                    query { Pub };
                    """.formatted(TABLE), "lib.relix", LIB);
            assertThat(messages(result)).anyMatch(m -> m.contains("'Pub' is declared in both"));
        }

        @Test
        @DisplayName("importing one file by two routes is not a clash")
        void diamond() {
            SemanticResult result = analyse("""
                    import "lib.relix";
                    import "b.relix";
                    query { Pub };
                    """, "lib.relix", LIB, "b.relix", "import \"lib.relix\";\nBPub := { Pub };");
            assertThat(result.errors()).isEmpty();
        }

        @Test
        @DisplayName("redefining a name inside one file stays allowed")
        void sameFile() {
            SemanticResult result = analyse("V := %s;\nV := %s;\nquery { V };".formatted(TABLE, TABLE));
            assertThat(result.errors()).isEmpty();
        }
    }
}

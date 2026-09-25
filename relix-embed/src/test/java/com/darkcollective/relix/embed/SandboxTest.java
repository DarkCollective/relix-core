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

import com.darkcollective.relix.events.QueryEvent;
import com.darkcollective.relix.lang.ScriptParser;
import com.darkcollective.relix.lang.ast.Statement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;
import static com.darkcollective.relix.embed.EmbedAssertions.assertThatThrownBy;

/**
 * What a closed sandbox lets a session's users reach, and what it limits.
 *
 * <p>Every refusal here is checked twice: that the call is refused, and that the session
 * is unchanged afterwards. A refusal that still installed the declaration would pass the
 * first check and leave the source reachable.
 */
@DisplayName("A sandboxed session")
final class SandboxTest {

    /** Five rows, so a limit of three cuts and a limit of five does not. */
    private static final String FIVE = """
            Numbers := [| n |
                        | 1 |
                        | 2 |
                        | 3 |
                        | 4 |
                        | 5 |];
            """;

    private static Path csv(Path directory) throws IOException {
        Path file = directory.resolve("orders.csv");
        Files.writeString(file, "id,amount\n1,10\n2,20\n3,30\n");
        return file;
    }

    private static final String ORDERS_SOURCE =
            "source Orders from csv(\"orders.csv\") { schema: { id: NUMBER, amount: NUMBER } };";

    @Nested
    @DisplayName("open, which is the default")
    final class Open {

        @Test
        @DisplayName("enforces nothing: an external source is accepted")
        void acceptsExternal(@TempDir Path dir) throws IOException {
            csv(dir);
            try (Relix session = Relix.builder().baseDirectory(dir).build()) {
                session.define(ORDERS_SOURCE);
                assertThat(session.relation("Orders")).hasRowCount(3);
            }
        }

        @Test
        @DisplayName("is what a session built without one has")
        void isTheDefault() {
            assertThat(Sandbox.open().isOpen()).isTrue();
            assertThat(Sandbox.builder().build().isOpen()).isFalse();
            assertThat(Sandbox.open()).hasToString("Sandbox[open]");
        }
    }

    @Nested
    @DisplayName("closed")
    final class Closed {

        private Relix closed() {
            return Relix.builder().sandbox(Sandbox.builder().build()).build();
        }

        @Test
        @DisplayName("accepts inline tables, views, functions and generator sources")
        void acceptsInternal() {
            try (Relix session = closed()) {
                session.define(FIVE);
                session.define("Big := { σ n > 3 (Numbers) };");
                session.define("def twice(x: NUMBER): NUMBER := { x * 2 };");
                session.define("source Few from generator { name: \"Range\", lo: \"1\", hi: \"3\" };");
                assertThat(session.relation("π twice(n) → m (Big)")).hasRowCount(2);
                assertThat(session.relation("Few")).hasRowCount(3);
            }
        }

        @Test
        @DisplayName("refuses a file source, and installs nothing")
        void refusesFileSource() {
            try (Relix session = closed()) {
                String before = session.definitions();
                assertThatThrownBy(() -> session.define(ORDERS_SOURCE))
                        .isInstanceOf(SandboxViolationException.class)
                        .hasMessageContaining("the source 'Orders'");
                assertThat(session.definitions()).isEqualTo(before);
            }
        }

        @Test
        @DisplayName("refuses the whole script when one statement is external")
        void refusesMixedScript() {
            try (Relix session = closed()) {
                assertThatThrownBy(() -> session.script(FIVE + ORDERS_SOURCE + "query Numbers;"))
                        .isInstanceOf(SandboxViolationException.class);
                assertThatThrownBy(() -> session.relation("Numbers"))
                        .isInstanceOf(RelixException.class);
            }
        }

        @Test
        @DisplayName("refuses a connection, an import and an env statement")
        void refusesOtherExternalKinds() {
            try (Relix session = closed()) {
                assertThatThrownBy(() -> session.define("connection wh from jdbc { url: \"jdbc:h2:mem:x\" };"))
                        .isInstanceOf(SandboxViolationException.class)
                        .hasMessageContaining("the connection 'wh'");
                assertThatThrownBy(() -> session.define("import \"other.relix\";"))
                        .isInstanceOf(SandboxViolationException.class)
                        .hasMessageContaining("an import");
                assertThatThrownBy(() -> session.script("env from \"dev.json\"; query { UNIT };"))
                        .isInstanceOf(SandboxViolationException.class)
                        .hasMessageContaining("an env statement");
            }
        }

        @Test
        @DisplayName("refuses an external declaration given as a statement, not only as text")
        void refusesTypedStatement() {
            try (Relix session = closed()) {
                Statement orders = ScriptParser.parse(ORDERS_SOURCE).statements().getFirst();
                assertThatThrownBy(() -> session.define(orders))
                        .isInstanceOf(SandboxViolationException.class);
            }
        }

        @Test
        @DisplayName("reports a refusal from validate() as a diagnostic, not a throw")
        void validateReports() {
            try (Relix session = closed()) {
                List<Diagnostic> diagnostics = session.validate(ORDERS_SOURCE);
                assertThat(diagnostics).singleElement()
                        .satisfies(d -> {
                            assertThat(d.isError()).isTrue();
                            assertThat(d.message()).contains("does not permit the source 'Orders'");
                        });
                assertThat(session.validate(FIVE)).isEmpty();
            }
        }

        @Test
        @DisplayName("leaves the host's Java registrations alone")
        void hostRegistrationsAllowed() {
            try (Relix session = closed()) {
                session.table("People", List.of("name"), List.of(Map.of("name", "Ada")));
                assertThat(session.relation("People")).hasRowCount(1);
            }
        }
    }

    @Nested
    @DisplayName("with declarations of its own")
    final class Declared {

        private Relix withOrders(Path dir) {
            return Relix.builder()
                    .sandbox(Sandbox.builder().baseDirectory(dir).declarations(ORDERS_SOURCE).build())
                    .build();
        }

        @Test
        @DisplayName("installs them, resolving relative paths against its own directory")
        void installsDeclarations(@TempDir Path dir) throws IOException {
            csv(dir);
            try (Relix session = withOrders(dir)) {
                assertThat(session.relation("σ amount > 10 (Orders)")).hasRowCount(2);
            }
        }

        @Test
        @DisplayName("accepts a user's copy of a permitted declaration, whatever its layout")
        void acceptsIdenticalCopy(@TempDir Path dir) throws IOException {
            csv(dir);
            try (Relix session = withOrders(dir)) {
                session.define("""
                        -- the same source, written out again
                        source Orders from csv("orders.csv") {
                            schema: { id: NUMBER, amount: NUMBER }
                        };
                        """);
                assertThat(session.relation("Orders")).hasRowCount(3);
            }
        }

        @Test
        @DisplayName("refuses one that differs in any value")
        void refusesDifferentCopy(@TempDir Path dir) throws IOException {
            csv(dir);
            try (Relix session = withOrders(dir)) {
                assertThatThrownBy(() -> session.define(
                        "source Orders from csv(\"/etc/passwd\") { schema: { id: NUMBER, amount: NUMBER } };"))
                        .isInstanceOf(SandboxViolationException.class);
                assertThatThrownBy(() -> session.define(
                        "source Other from csv(\"orders.csv\") { schema: { id: NUMBER, amount: NUMBER } };"))
                        .isInstanceOf(SandboxViolationException.class);
            }
        }

        @Test
        @DisplayName("lets a connection it declares be queried, and refuses any other")
        void connection() throws Exception {
            String url = "jdbc:h2:mem:sandbox;DB_CLOSE_DELAY=-1";
            try (var c = DriverManager.getConnection(url); var st = c.createStatement()) {
                st.execute("CREATE TABLE orders (id INT, amount INT)");
                st.execute("INSERT INTO orders VALUES (1, 10), (2, 20)");
            }
            Sandbox sandbox = Sandbox.builder()
                    .declarations("connection wh from jdbc { url: \"${WH_URL}\" };")
                    .build();
            try (Relix session = Relix.builder()
                    .placeholders(name -> name.equals("WH_URL") ? java.util.Optional.of(url)
                            : java.util.Optional.empty())
                    .sandbox(sandbox)
                    .build()) {
                assertThat(session.relation("σ AMOUNT > 10 (wh.orders)")).hasRowCount(1);
                assertThatThrownBy(() -> session.define(
                        "connection other from jdbc { url: \"" + url + "\" };"))
                        .isInstanceOf(SandboxViolationException.class);
            }
        }
    }

    @Nested
    @DisplayName("limits")
    final class Limits {

        private Relix limited(Sandbox.Builder sandbox) {
            Relix session = Relix.builder().sandbox(sandbox.build()).build();
            session.define(FIVE);
            return session;
        }

        @Test
        @DisplayName("cuts a longer result and reports the cut")
        void truncates() {
            try (Relix session = limited(Sandbox.builder().maxOutputRows(3))) {
                Rows rows = session.relation("Numbers").run();
                assertThat(rows.size()).isEqualTo(3);
                assertThat(rows.truncated()).isTrue();
                assertThat(rows.events()).anySatisfy(e -> {
                    assertThat(e.code()).isEqualTo("TRUNCATED");
                    assertThat(e.metrics().rows()).hasValue(3);
                });
                assertThat(session.relation("Numbers")).hasRowCount(3);
            }
        }

        @Test
        @DisplayName("does not report a result exactly at the limit as cut")
        void exactlyAtLimit() {
            try (Relix session = limited(Sandbox.builder().maxOutputRows(5))) {
                Rows rows = session.relation("Numbers").run();
                assertThat(rows.size()).isEqualTo(5);
                assertThat(rows.truncated()).isFalse();
            }
        }

        @Test
        @DisplayName("cuts a stream too, telling a listener")
        void truncatesStream() {
            try (Relix session = limited(Sandbox.builder().maxOutputRows(2))) {
                List<QueryEvent> events = new ArrayList<>();
                try (Stream<Tuple> rows = session.relation("Numbers").stream(events::add)) {
                    assertThat(rows.toList()).hasSize(2);
                }
                assertThat(events).anyMatch(Relation::isTruncation);
                assertThat(session.openStreams()).isZero();
            }
        }

        @Test
        @DisplayName("stops an endless stream at the limit")
        void endlessStream() {
            try (Relix session = limited(Sandbox.builder().maxOutputRows(4))) {
                session.define("source Naturals from generator { name: \"Naturals\" };");
                try (Stream<Tuple> rows = session.relation("Naturals").stream()) {
                    assertThat(rows.toList()).hasSize(4);
                }
            }
        }

        @Test
        @DisplayName("does not remember a cut result's size as the relation's")
        void truncatedNotObserved() {
            try (Relix session = limited(Sandbox.builder().maxOutputRows(3))) {
                Relation numbers = session.relation("Numbers");
                numbers.toList();
                numbers.run();
                assertThat(session.observedExpressions().forExpression(numbers.node())).isEmpty();
            }
        }

        @Test
        @DisplayName("counts a relation in full, since a count is one row")
        void countIsWhole() {
            try (Relix session = limited(Sandbox.builder().maxOutputRows(1))) {
                assertThat(session.relation("Numbers")).count().isEqualTo(5);
            }
        }

        @Test
        @DisplayName("refuses text longer than the input limit, before parsing it")
        void inputLimit() {
            try (Relix session = Relix.builder()
                    .sandbox(Sandbox.builder().maxInputChars(20).build()).build()) {
                // The host's own registration, which the input limit does not reach.
                session.table("Numbers", List.of("n"),
                        List.of(Map.of("n", 1), Map.of("n", 4), Map.of("n", 5),
                                Map.of("n", 2), Map.of("n", 3)));
                String longView = "Big := { σ n > 3 (Numbers) };";
                assertThatThrownBy(() -> session.define(longView))
                        .isInstanceOf(SandboxViolationException.class)
                        .hasMessageContaining("at most 20 characters");
                assertThatThrownBy(() -> session.script("query { σ n > 3 (Numbers) };"))
                        .isInstanceOf(SandboxViolationException.class);
                assertThatThrownBy(() -> session.relation("σ n > 3 ∧ n < 5 (Numbers)"))
                        .isInstanceOf(SandboxViolationException.class);
                assertThat(session.validate(longView)).singleElement()
                        .satisfies(d -> assertThat(d.message()).contains("at most 20 characters"));
                assertThat(session.relation("Numbers")).hasRowCount(5);
            }
        }

        @Test
        @DisplayName("applies its materialization cap, the stricter of its own and the builder's")
        void materializationCap() {
            try (Relix session = Relix.builder().maxMaterializedRows(100)
                    .sandbox(Sandbox.builder().maxMaterializedRows(2).build()).build()) {
                session.define(FIVE);
                assertThatThrownBy(() -> session.relation("τ n (Numbers)").toList())
                        .hasMessageContaining("2");
            }
            try (Relix session = Relix.builder().maxMaterializedRows(2)
                    .sandbox(Sandbox.builder().maxMaterializedRows(100).build()).build()) {
                session.define(FIVE);
                assertThatThrownBy(() -> session.relation("τ n (Numbers)").toList())
                        .hasMessageContaining("2");
            }
        }

        @Test
        @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
        @DisplayName("stops an endless stream that yields nothing, by rows processed")
        void workBudget() {
            try (Relix session = Relix.builder()
                    .sandbox(Sandbox.builder().maxOutputRows(10).maxProcessedRows(5_000).build()).build()) {
                session.define("source Naturals from generator { name: \"Naturals\" };");
                assertThatThrownBy(() -> {
                    try (Stream<Tuple> rows = session.relation("σ Len(CStr(n)) = 0 (Naturals)").stream()) {
                        rows.toList();
                    }
                }).isInstanceOf(QueryExecutionException.class)
                        .hasMessageContaining("processed more than 5000 rows");
                assertThat(session.openStreams()).isZero();
            }
        }

        @Test
        @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
        @DisplayName("stops an endless stream that yields nothing, by its deadline")
        void timeout() {
            try (Relix session = Relix.builder()
                    .sandbox(Sandbox.builder().timeout(java.time.Duration.ofMillis(100)).build()).build()) {
                session.define("source Naturals from generator { name: \"Naturals\" };");
                assertThatThrownBy(() -> {
                    try (Stream<Tuple> rows = session.relation("σ Len(CStr(n)) = 0 (Naturals)").stream()) {
                        rows.toList();
                    }
                }).isInstanceOf(QueryExecutionException.class)
                        .hasMessageContaining("ran longer than 100ms");
            }
        }

        @Test
        @DisplayName("applies the stricter of its work limits and the builder's")
        void stricterWorkLimits() {
            String query = "query { σ n > 3 (Numbers) };";
            // Numbers is five rows and two pass, so the query processes seven.
            try (Relix session = Relix.builder().maxProcessedRows(6)
                    .sandbox(Sandbox.builder().maxProcessedRows(1_000).build()).build()) {
                session.define(FIVE);
                assertThatThrownBy(() -> session.script(query).getFirst().toList())
                        .hasMessageContaining("processed more than 6 rows");
            }
            try (Relix session = Relix.builder().maxProcessedRows(1_000)
                    .sandbox(Sandbox.builder().maxProcessedRows(6).build()).build()) {
                session.define(FIVE);
                assertThatThrownBy(() -> session.script(query).getFirst().toList())
                        .hasMessageContaining("processed more than 6 rows");
            }
            try (Relix session = Relix.builder().timeout(java.time.Duration.ofHours(1))
                    .sandbox(Sandbox.builder().timeout(java.time.Duration.ofMinutes(1)).build()).build()) {
                session.define(FIVE);
                assertThat(session.script(query).getFirst()).hasRowCount(2);
            }
        }

        @Test
        @DisplayName("applies its fixpoint cap")
        void fixpointCap() {
            try (Relix session = Relix.builder()
                    .sandbox(Sandbox.builder().maxFixpointRounds(1).build()).build()) {
                List<Relation> reach = session.script("""
                        Edges := [| src | dst |
                                   | 1   | 2   |
                                   | 2   | 3   |
                                   | 3   | 4   |];
                        query { FIX R (Edges,
                          Edges ∪ π src, dst (ρ X(src, via) (R) ⋈ ρ Y(via, dst) (Edges))) };
                        """);
                assertThatThrownBy(() -> reach.getFirst().toList()).hasMessageContaining("exceeded 1");
            }
        }

        @Test
        @DisplayName("refuses a limit below one")
        void positiveOnly() {
            assertThatThrownBy(() -> Sandbox.builder().maxOutputRows(0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Sandbox.builder().maxInputChars(-1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Sandbox.builder().maxMaterializedRows(0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Sandbox.builder().maxFixpointRounds(0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Sandbox.builder().maxProcessedRows(0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Sandbox.builder().timeout(java.time.Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Sandbox.builder().timeout(java.time.Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Relix.builder().maxProcessedRows(0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Relix.builder().timeout(java.time.Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Relix.builder().timeout(java.time.Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("read from a configuration file")
    final class ConfigurationFile {

        @Test
        @DisplayName("reads its declarations and limits, relative to the file")
        void loads(@TempDir Path dir) throws IOException {
            csv(dir);
            Files.writeString(dir.resolve("sandbox.relix"), ORDERS_SOURCE);
            Path config = dir.resolve("sandbox.json");
            Files.writeString(config, """
                    {
                      "declarations": "sandbox.relix",
                      "limits": {
                        "maxInputChars": 500,
                        "maxOutputRows": 2,
                        "maxMaterializedRows": 1000,
                        "maxFixpointRounds": 10,
                        "maxProcessedRows": 5000000000,
                        "timeoutMillis": 1500
                      }
                    }
                    """);
            Sandbox sandbox = Sandbox.load(config);
            assertThat(sandbox.isOpen()).isFalse();
            assertThat(sandbox.maxInputChars()).hasValue(500);
            assertThat(sandbox.maxOutputRows()).hasValue(2);
            assertThat(sandbox.maxMaterializedRows()).hasValue(1000);
            assertThat(sandbox.maxFixpointRounds()).hasValue(10);
            assertThat(sandbox.maxProcessedRows()).hasValue(5_000_000_000L);
            assertThat(sandbox.timeout()).hasValue(java.time.Duration.ofMillis(1500));
            assertThat(sandbox.baseDirectory()).hasValue(dir.toAbsolutePath());
            assertThat(sandbox).hasToString("Sandbox[closed, maxInputChars=500, maxOutputRows=2, "
                    + "maxMaterializedRows=1000, maxFixpointRounds=10, maxProcessedRows=5000000000, "
                    + "timeout=PT1.5S]");
            try (Relix session = Relix.builder().sandbox(sandbox).build()) {
                Rows rows = session.relation("Orders").run();
                assertThat(rows.size()).isEqualTo(2);
                assertThat(rows.truncated()).isTrue();
            }
        }

        @Test
        @DisplayName("an empty object is a closed sandbox with nothing permitted and no limits")
        void empty(@TempDir Path dir) throws IOException {
            Path config = dir.resolve("sandbox.json");
            Files.writeString(config, "{}");
            Sandbox sandbox = Sandbox.load(config);
            assertThat(sandbox.isOpen()).isFalse();
            assertThat(sandbox.declarations()).isEmpty();
            assertThat(sandbox.maxOutputRows()).isEmpty();
            assertThat(sandbox.maxProcessedRows()).isEmpty();
            assertThat(sandbox.timeout()).isEmpty();
            assertThat(sandbox).hasToString("Sandbox[closed, maxInputChars=unlimited, "
                    + "maxOutputRows=unlimited, maxMaterializedRows=unlimited, maxFixpointRounds=unlimited, "
                    + "maxProcessedRows=unlimited, timeout=unlimited]");
        }

        @Test
        @DisplayName("refuses what it cannot read exactly")
        void refusals(@TempDir Path dir) throws IOException {
            assertRefused(dir, "{ \"limit\": {} }", "unknown key \"limit\"");
            assertRefused(dir, "{ \"limits\": { \"maxOutputRow\": 5 } }", "unknown key \"limits.maxOutputRow\"");
            assertRefused(dir, "{ \"limits\": { \"maxOutputRows\": 0 } }", "must be a positive integer");
            assertRefused(dir, "{ \"limits\": { \"maxOutputRows\": 2.5 } }", "must be a positive integer");
            assertRefused(dir, "{ \"limits\": [] }", "\"limits\" must be a JSON object");
            assertRefused(dir, "{ \"limits\": { \"maxProcessedRows\": 0 } }", "must be a positive integer");
            assertRefused(dir, "{ \"limits\": { \"timeoutMillis\": 1.5 } }", "must be a positive integer");
            assertRefused(dir, "[]", "must be a JSON object");
            assertRefused(dir, "{ \"declarations\": [] }", "must be a file name");
            assertRefused(dir, "{ \"declarations\": \"missing.relix\" }", "cannot read sandbox file");
            assertRefused(dir, "{ nope", "is not valid JSON");
            assertThatThrownBy(() -> Sandbox.load(dir.resolve("absent.json")))
                    .isInstanceOf(RelixException.class)
                    .hasMessageContaining("cannot read sandbox file");
        }

        private static void assertRefused(Path dir, String json, String message) throws IOException {
            Path config = dir.resolve("sandbox.json");
            Files.writeString(config, json);
            assertThatThrownBy(() -> Sandbox.load(config))
                    .isInstanceOf(RelixException.class)
                    .hasMessageContaining(message);
        }
    }
}

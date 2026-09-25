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

import com.darkcollective.relix.lang.ast.source.BearerAuth;
import com.darkcollective.relix.lang.ast.source.HttpMethod;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static com.darkcollective.relix.ast.AstBuilders.rel;
import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;
import static com.darkcollective.relix.lang.ast.ScriptBuilders.httpSource;
import static com.darkcollective.relix.lang.ast.ScriptBuilders.query;
import static com.darkcollective.relix.lang.ast.ScriptBuilders.source;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("placeholders are resolved per execution, and never enter the model")
final class PlaceholdersTest {

    /** A resolver over a map a test can change, recording each name it is asked for. */
    private static final class Vault implements Function<String, Optional<String>> {
        final Map<String, String> values = new HashMap<>();
        final List<String> asked = new ArrayList<>();

        Vault with(String name, String value) {
            values.put(name, value);
            return this;
        }

        @Override
        public Optional<String> apply(String name) {
            asked.add(name);
            return Optional.ofNullable(values.get(name));
        }
    }

    private static final String FAKE = """
            connection store from %s { key: "${STORE_KEY}", region: "eu" };
            source Items from store { table: "items", schema: { v: STRING } };
            """.formatted(FakeRegisteredConnector.TYPE);

    @BeforeEach
    void reset() {
        FakeRegisteredConnector.reset();
    }

    private static Relix session(Vault vault) {
        return Relix.builder().connector(new FakeRegisteredConnector()).placeholders(vault).build();
    }

    @Nested
    @DisplayName("a connection-based connector")
    class Connection {

        @Test
        @DisplayName("receives the resolved value in its config")
        void resolved() {
            try (Relix relix = session(new Vault().with("STORE_KEY", "s3cret"))) {
                relix.define(FAKE);
                relix.relation("Items").toList();
                assertThat(FakeRegisteredConnector.lastOpen.config().get("key")).contains("s3cret");
            }
        }

        @Test
        @DisplayName("the session's model and everything that prints it keep the placeholder")
        void modelKeepsThePlaceholder() {
            try (Relix relix = session(new Vault().with("STORE_KEY", "s3cret"))) {
                relix.define(FAKE);
                Relation items = relix.relation("Items");
                items.toList();
                assertThat(relix.definitions()).contains("${STORE_KEY}").doesNotContain("s3cret");
                assertThat(relix.ir()).doesNotContain("s3cret");
                assertThat(items.model().connections().get("store").properties())
                        .containsEntry("key", "${STORE_KEY}");
            }
        }

        @Test
        @DisplayName("a value is read again on each run, so a rotated one is picked up")
        void rotation() {
            Vault vault = new Vault().with("STORE_KEY", "first");
            try (Relix relix = session(vault)) {
                relix.define(FAKE);
                Relation items = relix.relation("Items");
                items.toList();
                vault.with("STORE_KEY", "second");
                items.toList();
                assertThat(FakeRegisteredConnector.lastOpen.config().get("key")).contains("second");
            }
        }

        @Test
        @DisplayName("the model an execution runs against carries the resolved connection")
        void resolvedModel() {
            try (Relix relix = session(new Vault().with("STORE_KEY", "s3cret"))) {
                relix.define(FAKE);
                // A pushed-down query reaches its connector through this model's
                // connections, which is why resolving the model covers it.
                var model = Placeholders.of(new Vault().with("STORE_KEY", "s3cret"))
                        .resolve(relix.relation("Items").model(), rel("Items"));
                assertThat(model.connections().get("store").properties()).containsEntry("key", "s3cret");
            }
        }

        @Test
        @DisplayName("a value with no answer fails the query, naming the placeholder and the connection")
        void unresolvable() {
            try (Relix relix = session(new Vault())) {
                relix.define(FAKE);
                assertThatThrownBy(() -> relix.relation("Items").toList())
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("${STORE_KEY}")
                        .hasMessageContaining("connection 'store'");
                assertThat(FakeRegisteredConnector.lastOpen).isNull();
            }
        }
    }

    @Nested
    @DisplayName("what is asked for")
    class Laziness {

        private static final String TWO = FAKE + """
                source Other from csv("${NOT_HERE}/other.csv") { header: true, schema: { a: NUMBER } };
                Wrapped := { Items };
                """;

        @Test
        @DisplayName("only the names the query's declarations hold, so another source's gap is harmless")
        void onlyWhatIsReached() {
            Vault vault = new Vault().with("STORE_KEY", "k");
            try (Relix relix = session(vault)) {
                relix.define(TWO);
                Relation items = relix.relation("Items");
                vault.asked.clear();   // analysis introspects the connection, which asks too
                assertThat(items).hasRowCount(1);
                assertThat(vault.asked).containsExactly("STORE_KEY");
            }
        }

        @Test
        @DisplayName("a source reached only through a view is resolved")
        void throughAView() {
            try (Relix relix = session(new Vault().with("STORE_KEY", "k"))) {
                relix.define(TWO);
                relix.relation("Wrapped").toList();
                assertThat(FakeRegisteredConnector.lastOpen.config().get("key")).contains("k");
            }
        }

        @Test
        @DisplayName("the walk follows views, table-valued functions and a lateral's function")
        void reachedFollowsBodies() {
            try (Relix relix = session(new Vault().with("STORE_KEY", "k"))) {
                relix.define(TWO + """
                        def Scoped(n: NUMBER) : RELATION := { Items };
                        Left := { π v (Items) };
                        """);
                var symbols = relix.model().symbolTable();
                assertThat(Placeholders.reached(rel("Wrapped"), symbols)).contains("wrapped", "items");
                assertThat(Placeholders.reached(relix.relation("Scoped(1)").node(), symbols))
                        .contains("items").doesNotContain("other");
                assertThat(Placeholders.reached(relix.relation("Other LATERAL Scoped(1)").node(), symbols))
                        .contains("other", "items");
            }
        }

        @Test
        @DisplayName("a name held twice is asked for once per run")
        void oncePerRun() {
            Vault vault = new Vault().with("K", "k");
            try (Relix relix = Relix.builder().connector(new FakeRegisteredConnector()).placeholders(vault).build()) {
                relix.define("""
                        connection store from %s { key: "${K}", again: "${K}-${K}" };
                        source Items from store { table: "items", schema: { v: STRING } };
                        """.formatted(FakeRegisteredConnector.TYPE));
                Relation items = relix.relation("Items");
                vault.asked.clear();
                items.toList();
                assertThat(vault.asked).containsExactly("K");
                assertThat(FakeRegisteredConnector.lastOpen.config().get("again")).contains("k-k");
            }
        }
    }

    @Nested
    @DisplayName("without a resolver")
    class NoResolver {

        @Test
        @DisplayName("placeholders are left as written")
        void untouched() {
            try (Relix relix = Relix.builder().connector(new FakeRegisteredConnector()).build()) {
                relix.define(FAKE);
                relix.relation("Items").toList();
                assertThat(FakeRegisteredConnector.lastOpen.config().get("key")).contains("${STORE_KEY}");
            }
        }
    }

    @Nested
    @DisplayName("a file source")
    class Files_ {

        @Test
        @DisplayName("resolves its path")
        void csvPath(@TempDir Path dir) throws IOException {
            Files.writeString(dir.resolve("orders.csv"), "id,amount\n1,10\n2,20\n");
            try (Relix relix = Relix.builder()
                    .placeholders(new Vault().with("DATA", dir.toString()))
                    .build()) {
                relix.define("""
                        source Orders from csv("${DATA}/orders.csv") {
                            header: true, schema: { id: NUMBER, amount: NUMBER }
                        };
                        """);
                assertThat(relix.relation("Orders")).hasRowCount(2);
            }
        }
    }

    @Nested
    @DisplayName("a JDBC connection")
    class Jdbc {

        @Test
        @DisplayName("opens, plans and introspects through a URL held in a placeholder")
        void urlPlaceholder() throws Exception {
            String url = "jdbc:h2:mem:placeholders;DB_CLOSE_DELAY=-1";
            try (var c = DriverManager.getConnection(url); var st = c.createStatement()) {
                st.execute("CREATE TABLE orders (id INT, amount INT)");
                st.execute("INSERT INTO orders VALUES (1, 10), (2, 20)");
            }
            try (Relix relix = Relix.builder().placeholders(new Vault().with("DB_URL", url)).build()) {
                relix.define("connection wh from jdbc { url: \"${DB_URL}\" };");
                // A dotted reference: the columns come from introspection at analysis,
                // which is where an unresolved URL would have failed.
                Relation orders = relix.relation("σ AMOUNT > 10 (wh.orders)");
                assertThat(orders).hasRowCount(1);
            }
        }
    }

    @Nested
    @DisplayName("an HTTP source")
    class Http {

        private HttpServer server;
        private volatile String authorization;

        @BeforeEach
        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/orders", exchange -> {
                authorization = exchange.getRequestHeaders().getFirst("Authorization");
                byte[] body = "[{\"id\": 1}]".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        }

        @AfterEach
        void stop() {
            server.stop(0);
        }

        private String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/orders";
        }

        @Test
        @DisplayName("sends the resolved header, which a quote in the value cannot break")
        void header() {
            String token = "a\"b\\c";
            try (Relix relix = Relix.builder().placeholders(new Vault().with("TOKEN", token)).build()) {
                relix.define("""
                        source Orders from http {
                            url: "%s",
                            headers: { "Authorization": "Bearer ${TOKEN}" }
                        };
                        """.formatted(url()));
                assertThat(relix.relation("Orders")).hasRowCount(1);
                assertThat(authorization).isEqualTo("Bearer " + token);
                assertThat(relix.definitions()).doesNotContain(token);
            }
        }

        @Test
        @DisplayName("resolves a credential in auth:")
        void auth() {
            try (Relix relix = Relix.builder().placeholders(new Vault().with("TOKEN", "t")).build()) {
                relix.define("source Orders from http { url: \"%s\", auth: bearer(\"${TOKEN}\") };"
                        .formatted(url()));
                relix.relation("Orders").toList();
                assertThat(authorization).isEqualTo("Bearer t");
            }
        }

        @Test
        @DisplayName("a value that breaks the header is an error naming the source and the header")
        void badValue() {
            try (Relix relix = Relix.builder().placeholders(new Vault().with("TOKEN", "a\nb")).build()) {
                relix.define("source Orders from http { url: \"%s\", auth: bearer(\"${TOKEN}\") };"
                        .formatted(url()));
                assertThatThrownBy(() -> relix.relation("Orders").toList())
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("'orders'")
                        .hasMessageContaining("header 'Authorization'");
            }
        }
    }

    @Nested
    @DisplayName("define(Statement...)")
    class Statements {

        @Test
        @DisplayName("takes a declaration built in Java, with nothing to escape")
        void builtDeclaration() {
            var http = httpSource("https://x", HttpMethod.GET,
                    Map.of("X-Key", "a\"b"), Optional.empty(), Optional.empty(), List.of(),
                    Optional.empty(), Optional.of(new BearerAuth("t")));
            try (Relix relix = Relix.open()) {
                relix.define(source("Api", http));
                assertThat(relix.statements()).hasSize(1);
                assertThat(relix.relation("Api").node()).isNotNull();
            }
        }

        @Test
        @DisplayName("refuses a query statement")
        void refusesAQuery() {
            try (Relix relix = Relix.open()) {
                assertThatThrownBy(() -> relix.define(query(rel("X"))))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("not query statements");
            }
        }

        @Test
        @DisplayName("refuses a declaration that does not analyse, and keeps the session as it was")
        void refusesInvalid() {
            var query = httpSource("https://x", HttpMethod.QUERY, Map.of(),
                    Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.empty());
            try (Relix relix = Relix.open()) {
                assertThatThrownBy(() -> relix.define(source("Api", query)))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("declares no 'body'");
                assertThat(relix.statements()).isEmpty();
            }
        }
    }
}

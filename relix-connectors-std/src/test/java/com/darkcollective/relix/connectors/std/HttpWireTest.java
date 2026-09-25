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
package com.darkcollective.relix.connectors.std;

import com.darkcollective.relix.connectors.std.internal.HttpDataSourceConnector;
import com.darkcollective.relix.processor.internal.QueryExecutor;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.semantic.SemanticModel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The HTTP connector's default transport against a real socket.
 *
 * <p>The injected-transport tests see an {@code HttpRequestSpec} and nothing after it,
 * so they cannot show that {@code HttpClient} puts a method it does not know on the wire,
 * sends its body, or carries both across a redirect. Those are claims about the client,
 * and a local server is the only thing that can check them.
 */
@DisplayName("HttpDataSourceConnector — on the wire")
final class HttpWireTest {

    private HttpServer server;
    private volatile String seen;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Answers with one record describing the request it received.
        server.createContext("/echo", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen = exchange.getRequestMethod() + " " + body + " "
                    + exchange.getRequestHeaders().getFirst("Content-Type");
            reply(exchange, 200, "[{\"ok\": true}]");
        });
        // Redirects to /echo with the status named by the query string.
        server.createContext("/moved", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", "/echo");
            exchange.sendResponseHeaders(Integer.parseInt(exchange.getRequestURI().getQuery()), -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static List<Row> exec(String script) {
        SemanticModel model = model(script);
        return new QueryExecutor().execute(model, new HttpDataSourceConnector(model)).stream()
                .flatMap(qr -> qr.rows().stream())
                .toList();
    }

    private String querySource(String path) {
        return """
                source Search from http {
                    url: "%s",
                    method: QUERY,
                    body: "{\\"status\\": \\"OPEN\\"}"
                };
                query Search;
                """.formatted(url(path));
    }

    @Test
    @DisplayName("QUERY reaches the server as QUERY, with its body and a JSON Content-Type")
    void queryOnTheWire() {
        assertThat(exec(querySource("/echo"))).hasSize(1);
        assertThat(seen).isEqualTo("QUERY {\"status\": \"OPEN\"} application/json");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = {301, 302, 307, 308})
    @DisplayName("a redirect resends the QUERY with its body")
    void redirectKeepsTheQuery(int status) {
        assertThat(exec(querySource("/moved?" + status))).hasSize(1);
        assertThat(seen).isEqualTo("QUERY {\"status\": \"OPEN\"} application/json");
    }

    @Test
    @DisplayName("a 303 fetches the result with GET")
    void seeOtherIsAGet() {
        assertThat(exec(querySource("/moved?303"))).hasSize(1);
        assertThat(seen).startsWith("GET  ");
    }

    @Test
    @DisplayName("a header the client refuses is an evaluation error naming the source and the header")
    void refusedHeader() {
        // Analysis refuses this declaration; the model still exists, which is the shape a
        // value only known at run time reaches the client in.
        String script = """
                source Api from http { url: "%s", headers: { "Host": "elsewhere" } };
                query Api;
                """.formatted(url("/echo"));
        assertThatThrownBy(() -> exec(script))
                .isInstanceOf(EvaluationException.class)
                .hasMessageContaining("'api'")
                .hasMessageContaining("header 'Host'");
    }
}

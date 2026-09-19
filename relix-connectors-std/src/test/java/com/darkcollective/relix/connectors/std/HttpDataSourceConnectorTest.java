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

import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.QueryExecutor;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.connectors.std.HttpDataSourceConnector.HttpFetchResult;
import com.darkcollective.relix.connectors.std.HttpDataSourceConnector.HttpRequestSpec;
import com.darkcollective.relix.connectors.std.HttpDataSourceConnector.HttpTransport;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("HttpDataSourceConnector — HTTP/JSON source execution")
final class HttpDataSourceConnectorTest extends ProcessorTestSupport {

    /**
     * A transport that records the last request and then either replies with a canned
     * response or fails the way the network fails.
     *
     * <p>The failing shapes are the two the connector declares it can be handed — an
     * {@link IOException} and an {@link InterruptedException} — because the seam exists
     * to be injected at and a double that can only succeed leaves the arms that translate
     * a transport failure into an evaluation error uncovered.
     */
    private static final class RecordingTransport implements HttpTransport {
        HttpRequestSpec lastRequest;
        private final HttpFetchResult response;
        private final Exception failure;

        RecordingTransport(int status, String body) {
            this.response = new HttpFetchResult(status, body);
            this.failure = null;
        }

        private RecordingTransport(Exception failure) {
            this.response = null;
            this.failure = failure;
        }

        /** A transport whose request never completes — a refused connection, a reset, a timeout. */
        static RecordingTransport failingWith(IOException e) {
            return new RecordingTransport(e);
        }

        /** A transport interrupted while waiting for the response. */
        static RecordingTransport interrupted() {
            return new RecordingTransport(new InterruptedException("interrupted while waiting"));
        }

        @Override
        public HttpFetchResult fetch(HttpRequestSpec request) throws IOException, InterruptedException {
            this.lastRequest = request;
            switch (failure) {
                case null -> { return response; }
                case IOException io -> throw io;
                case InterruptedException ie -> throw ie;
                default -> throw new IllegalStateException("unreachable");
            }
        }
    }

    private static List<Row> exec(String script, HttpTransport transport) {
        SemanticModel model = model(script);
        var connector = new HttpDataSourceConnector(model, transport);
        return new QueryExecutor().execute(model, connector).stream()
                .flatMap(qr -> qr.rows().stream())
                .toList();
    }

    // ── open (schema-on-read) sources ───────────────────────────────────────────

    @Nested
    @DisplayName("open sources")
    class OpenSources {

        @Test
        @DisplayName("top-level array yields one open document row per element")
        void topLevelArray() {
            var t = new RecordingTransport(200,
                    "[ {\"id\": 1, \"name\": \"Alice\"}, {\"id\": 2, \"name\": \"Bob\"} ]");
            var rows = exec("source Api from http { url: \"https://x/users\" };\nquery Api;", t);

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).schema().isOpen()).isTrue();
            assertThat(rows.get(0)).hasValue("name", "Alice");
            assertThat(rows.get(1)).hasValue("id", "2");
        }

        @Test
        @DisplayName("extract path locates a nested records array")
        void nestedExtractPath() {
            var t = new RecordingTransport(200,
                    "{\"items\": [ {\"sku\": \"A\"}, {\"sku\": \"B\"} ], \"page\": 1}");
            var rows = exec("""
                    source Api from http {
                        url: "https://x/items",
                        extract: json("$.items[*]")
                    };
                    query Api;
                    """, t);

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)).hasValue("sku", "A");
        }

        @Test
        @DisplayName("a single top-level object is one record")
        void singleObject() {
            var t = new RecordingTransport(200, "{\"id\": 7, \"name\": \"Solo\"}");
            var rows = exec("source Api from http { url: \"https://x/one\" };\nquery Api;", t);

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("name", "Solo");
        }
    }

    // ── closed (typed) sources ────────────────────────────────────────────────

    @Nested
    @DisplayName("closed typed sources")
    class ClosedSources {

        @Test
        @DisplayName("columns are extracted by their 'at' paths and coerced to type")
        void typedColumnsByPath() {
            var t = new RecordingTransport(200, """
                    [ {"main": {"temp": 21.5}, "weather": [ {"description": "clear"} ]} ]
                    """);
            var rows = exec("""
                    source Weather from http {
                        url: "https://x/weather",
                        schema: {
                            temp:      NUMBER at "$.main.temp",
                            condition: STRING at "$.weather[0].description"
                        }
                    };
                    query Weather;
                    """, t);

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).schema().isOpen()).isFalse();
            assertThat(rows.get(0)).hasValue("temp", "21.5")
                    .hasValue("condition", "clear");
        }

        @Test
        @DisplayName("a column with no binding reads the top-level field of its own name")
        void columnByName() {
            var t = new RecordingTransport(200, "[ {\"id\": 3, \"name\": \"Carol\"} ]");
            var rows = exec("""
                    source People from http {
                        url: "https://x/people",
                        schema: { id: NUMBER, name: STRING }
                    };
                    query People;
                    """, t);

            assertThat(rows.get(0)).hasValue("id", "3")
                    .hasValue("name", "Carol");
        }

        @Test
        @DisplayName("a missing path yields NULL (optional-chaining navigation)")
        void missingPathIsNull() {
            var t = new RecordingTransport(200, "[ {\"id\": 1} ]");
            var rows = exec("""
                    source People from http {
                        url: "https://x/people",
                        schema: { id: NUMBER, name: STRING at "$.missing" }
                    };
                    query People;
                    """, t);

            assertThat(rows.get(0).get("name").isNull()).isTrue();
        }
    }

    // ── request building ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("request building")
    class RequestBuilding {

        @Test
        @DisplayName("POST sends the configured body")
        void postBody() {
            var t = new RecordingTransport(200, "[]");
            exec("""
                    source Gql from http {
                        url: "https://x/graphql",
                        method: POST,
                        body: "{ \\"query\\": \\"{ ping }\\" }"
                    };
                    query Gql;
                    """, t);

            assertThat(t.lastRequest.method().name()).isEqualTo("POST");
            assertThat(t.lastRequest.body()).contains("{ \"query\": \"{ ping }\" }");
        }

        @Test
        @DisplayName("bearer auth becomes an Authorization header")
        void bearerHeader() {
            var t = new RecordingTransport(200, "[]");
            exec("source Api from http { url: \"https://x\", auth: bearer(\"tok\") };\nquery Api;", t);
            assertThat(t.lastRequest.headers()).containsEntry("Authorization", "Bearer tok");
        }

        @Test
        @DisplayName("basic auth becomes a base64 Authorization header")
        void basicHeader() {
            var t = new RecordingTransport(200, "[]");
            exec("source Api from http { url: \"https://x\", auth: basic(\"alice\", \"pw\") };\nquery Api;", t);
            // base64("alice:pw")
            assertThat(t.lastRequest.headers()).containsEntry("Authorization", "Basic YWxpY2U6cHc=");
        }

        @Test
        @DisplayName("apikey header auth becomes the named header")
        void apiKeyHeader() {
            var t = new RecordingTransport(200, "[]");
            exec("source Api from http { url: \"https://x\", auth: apikey(\"X-API-Key\", \"k\") };\nquery Api;", t);
            assertThat(t.lastRequest.headers()).containsEntry("X-API-Key", "k");
        }

        @Test
        @DisplayName("apikey query auth is appended to the URL")
        void apiKeyQuery() {
            var t = new RecordingTransport(200, "[]");
            exec("source Api from http { url: \"https://x\", auth: apikey(query(\"api_key\"), \"k\") };\nquery Api;", t);
            assertThat(t.lastRequest.url()).isEqualTo("https://x?api_key=k");
        }

        @Test
        @DisplayName("static headers are passed through")
        void staticHeaders() {
            var t = new RecordingTransport(200, "[]");
            exec("""
                    source Api from http {
                        url: "https://x",
                        headers: { "Accept": "application/json" }
                    };
                    query Api;
                    """, t);
            assertThat(t.lastRequest.headers()).containsEntry("Accept", "application/json");
        }

        @Test
        @DisplayName("pagination defaults become query parameters")
        void paginationDefaults() {
            var t = new RecordingTransport(200, "[]");
            exec("""
                    source Api from http {
                        url: "https://x/items",
                        paginate: { limit: query("limit") [default: 100] }
                    };
                    query Api;
                    """, t);
            assertThat(t.lastRequest.url()).isEqualTo("https://x/items?limit=100");
        }

        @Test
        @DisplayName("an IN column default is applied to its binding and echoed as a column")
        void inColumnDefault() {
            var t = new RecordingTransport(200, "[ {\"temp\": 1} ]");
            var rows = exec("""
                    source Api from http {
                        url: "https://x/weather",
                        schema: {
                            units: in STRING as query("units") [default: "metric"],
                            temp:  out NUMBER
                        }
                    };
                    query Api;
                    """, t);
            assertThat(t.lastRequest.url()).isEqualTo("https://x/weather?units=metric");
            assertThat(rows.get(0)).hasValue("units", "metric")
                    .hasValue("temp", "1");
        }

        @Test
        @DisplayName("a path parameter default is substituted into the URL template")
        void pathParamDefault() {
            var t = new RecordingTransport(200, "{\"title\": \"x\"}");
            exec("""
                    source Item from http {
                        url: "https://x/items/{id}",
                        schema: {
                            id:    in NUMBER as path("id") [default: "42"],
                            title: out STRING
                        }
                    };
                    query Item;
                    """, t);
            assertThat(t.lastRequest.url()).isEqualTo("https://x/items/42");
        }
    }

    // ── errors ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("error handling")
    class Errors {

        @Test
        @DisplayName("a non-2xx status raises an error with the body snippet")
        void nonSuccessStatus() {
            var t = new RecordingTransport(404, "not found");
            assertThatThrownBy(() -> exec("source Api from http { url: \"https://x\" };\nquery Api;", t))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("status 404")
                    .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("malformed JSON raises an error")
        void malformedJson() {
            var t = new RecordingTransport(200, "{ not json");
            assertThatThrownBy(() -> exec("source Api from http { url: \"https://x\" };\nquery Api;", t))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("malformed JSON");
        }

        @Test
        @DisplayName("a required IN column with no default is rejected (no predicate pushdown yet)")
        void requiredInWithoutDefault() {
            var t = new RecordingTransport(200, "[]");
            assertThatThrownBy(() -> exec("""
                    source Api from http {
                        url: "https://x",
                        schema: {
                            q:    in STRING as query("q") [required],
                            name: out STRING
                        }
                    };
                    query Api;
                    """, t))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("predicate pushdown");
        }

        @Test
        @DisplayName("CSV extract is not yet executable")
        void csvExtractDeferred() {
            var t = new RecordingTransport(200, "id\n1\n");
            assertThatThrownBy(() -> exec("""
                    source Api from http {
                        url: "https://x",
                        extract: csv(header: true),
                        schema: { id: out NUMBER }
                    };
                    query Api;
                    """, t))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("CSV extract");
        }

        @Test
        @DisplayName("a non-object record is rejected")
        void nonObjectRecord() {
            var t = new RecordingTransport(200, "[ 1, 2, 3 ]");
            assertThatThrownBy(() -> exec("source Api from http { url: \"https://x\" };\nquery Api;", t))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("must be a JSON object");
        }

        @Test
        @DisplayName("a transport failure names the relation, the URL and the cause")
        void transportFailure() {
            var t = RecordingTransport.failingWith(new IOException("connection refused"));
            assertThatThrownBy(() -> exec(
                    "source Api from http { url: \"https://x/users\" };\nquery Api;", t))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("api")
                    .hasMessageContaining("https://x/users")
                    .hasMessageContaining("connection refused");
        }

        @Test
        @DisplayName("an interrupted request raises and leaves the thread interrupted")
        void interruptedRequest() {
            var t = RecordingTransport.interrupted();
            try {
                assertThatThrownBy(() -> exec(
                        "source Api from http { url: \"https://x\" };\nquery Api;", t))
                        .isInstanceOf(EvaluationException.class)
                        .hasMessageContaining("interrupted");
                // The flag is what carries the cancellation onward: swallowing it here would
                // leave a thread that was asked to stop with nothing left saying so.
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();   // clear it, or the next test on this thread inherits it
            }
        }
    }

    // ── path normalisation ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("JSONPath normalisation")
    class PathNormalisation {

        @Test
        @DisplayName("strips $, leading dot, and trailing [*]")
        void normalises() {
            assertThat(HttpDataSourceConnector.normalisePath("$.items[*]")).contains("items");
            assertThat(HttpDataSourceConnector.normalisePath("$.data.items")).contains("data.items");
            assertThat(HttpDataSourceConnector.normalisePath("$.")).isEmpty();
            assertThat(HttpDataSourceConnector.normalisePath("$")).isEmpty();
        }
    }

    @Nested
    @DisplayName("GraphQL projection pushdown")
    class GraphQlProjection {

        /** A GraphQL endpoint's response, wrapped in the envelope the spec requires. */
        private static final String RESPONSE = """
                {"data": {"users": [
                    {"id": 1, "name": "Ada", "email": "ada@x", "bio": "…"},
                    {"id": 2, "name": "Bob", "email": "bob@x", "bio": "…"}
                ]}}
                """;

        private static final String SOURCE = """
                source Api from http {
                    url: "https://x/graphql",
                    method: POST,
                    extract: json("$.data.users"),
                    schema: { id: NUMBER, name: STRING, email: STRING, bio: STRING }
                };
                """;

        @Test
        @DisplayName("a query reading two of four columns asks for two fields")
        void projectionNarrowsTheSelectionSet() {
            // The whole point: the selection set is the field list, so a narrower scan is a
            // narrower request rather than a narrower result.
            var t = new RecordingTransport(200, RESPONSE);
            var rows = exec(SOURCE + "query { π id, name (Api) };", t);

            assertThat(t.lastRequest.body().orElseThrow()).contains("{\"query\":\"{ users { id name } }\"}");
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)).hasValue("id", "1").hasValue("name", "Ada");
        }

        @Test
        @DisplayName("a query reading everything asks for everything")
        void noProjectionAsksForAll() {
            var t = new RecordingTransport(200, RESPONSE);
            exec(SOURCE + "query Api;", t);

            assertThat(t.lastRequest.body().orElseThrow())
                    .contains("{ users { id name email bio } }");
        }

        @Test
        @DisplayName("a declared body is sent verbatim — the user's query is not rewritten")
        void aDeclaredBodyIsUntouched() {
            // Sending a different query than the one someone wrote is the failure this
            // feature must not have, so generation only ever fills an absence.
            var t = new RecordingTransport(200, RESPONSE);
            exec("""
                    source Api from http {
                        url: "https://x/graphql",
                        method: POST,
                        body: "{ users { id name email bio } }",
                        extract: json("$.data.users"),
                        schema: { id: NUMBER, name: STRING, email: STRING, bio: STRING }
                    };
                    query { π id (Api) };
                    """, t);

            assertThat(t.lastRequest.body().orElseThrow()).contains("{ users { id name email bio } }");
            assertThat(t.lastRequest.body().orElseThrow()).doesNotContain("\"query\"");
        }

        @Test
        @DisplayName("a GET source sends no body, projection or not")
        void getIsUnaffected() {
            var t = new RecordingTransport(200, "[ {\"id\": 1, \"name\": \"Ada\"} ]");
            exec("""
                    source Api from http {
                        url: "https://x/users",
                        schema: { id: NUMBER, name: STRING }
                    };
                    query { π id (Api) };
                    """, t);

            assertThat(t.lastRequest.body()).isEmpty();
        }

        @Test
        @DisplayName("without an extract path there is nowhere to select from, so nothing is sent")
        void noExtractDeclinesRatherThanGuessing() {
            var t = new RecordingTransport(200, "[ {\"id\": 1} ]");
            exec("""
                    source Api from http {
                        url: "https://x/graphql",
                        method: POST,
                        schema: { id: NUMBER }
                    };
                    query Api;
                    """, t);

            assertThat(t.lastRequest.body()).isEmpty();
        }

        @Test
        @DisplayName("naming every column explicitly asks for the same thing as naming none")
        void projectingAllColumnsChangesNothing() {
            // There is nothing to drop, so the scan is not narrowed and the request is the
            // one an unprojected query sends.
            var t = new RecordingTransport(200, RESPONSE);
            exec(SOURCE + "query { π id, name, email, bio (Api) };", t);

            assertThat(t.lastRequest.body().orElseThrow())
                    .contains("{ users { id name email bio } }");
        }

        @Test
        @DisplayName("an open source has no declared columns, so there is nothing to select")
        void openSourceGeneratesNothing() {
            // Schema-on-read means the heading is whatever the document turns out to hold,
            // and a GraphQL request has to name its fields up front. The two are
            // incompatible, so this declines rather than sending a guess.
            var t = new RecordingTransport(200, RESPONSE);
            exec("""
                    source Api from http {
                        url: "https://x/graphql",
                        method: POST,
                        extract: json("$.data.users")
                    };
                    query { π id (Api) };
                    """, t);

            assertThat(t.lastRequest.body()).isEmpty();
        }

        @Test
        @DisplayName("an IN column is a request parameter, not a field to select")
        void inColumnsAreNotSelected() {
            var t = new RecordingTransport(200, RESPONSE);
            exec("""
                    source Api from http {
                        url: "https://x/graphql",
                        method: POST,
                        extract: json("$.data.users"),
                        schema: { team: in STRING [default: "core"],
                                  id: NUMBER, name: STRING }
                    };
                    query Api;
                    """, t);

            assertThat(t.lastRequest.body().orElseThrow()).contains("{ users { id name } }");
        }

        @Test
        @DisplayName("a dotted column binding becomes a nested selection")
        void nestedBinding() {
            var t = new RecordingTransport(200, """
                    {"data": {"posts": [ {"id": 1, "author": {"name": "Ada"}} ]}}
                    """);
            var rows = exec("""
                    source Posts from http {
                        url: "https://x/graphql",
                        method: POST,
                        extract: json("$.data.posts"),
                        schema: { id: NUMBER, author_name: STRING at "$.author.name" }
                    };
                    query Posts;
                    """, t);

            assertThat(t.lastRequest.body().orElseThrow()).contains("{ posts { id author { name } } }");
            assertThat(rows.get(0)).hasValue("author_name", "Ada");
        }
    }
}

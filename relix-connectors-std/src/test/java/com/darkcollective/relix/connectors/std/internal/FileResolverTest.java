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
package com.darkcollective.relix.connectors.std.internal;

import com.darkcollective.relix.processor.connector.ConnectorConfig;
import com.darkcollective.relix.processor.connector.RelixConnector;
import com.darkcollective.relix.processor.EvaluationException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FileResolver — one answer to where a connection's file is")
final class FileResolverTest {

    private static final RelixConnector CSV = new CsvConnector();

    private static ConnectorConfig config(Map<String, String> values) {
        return new ConnectorConfig(values);
    }

    /** A transport that answers from a queue and records each request's headers. */
    private static final class Server implements FileResolver.Transport {
        final List<FileResolver.Response> answers = new ArrayList<>();
        final List<Map<String, String>> requests = new ArrayList<>();
        IOException failure;

        Server answer(int status, String body, String etag) {
            return answer(status, body, etag, "Sat, 10 Oct 2026 13:00:00 GMT");
        }

        Server answer(int status, String body, String etag, String lastModified) {
            answers.add(new FileResolver.Response(status, body.getBytes(StandardCharsets.UTF_8),
                    Optional.ofNullable(etag), Optional.ofNullable(lastModified)));
            return this;
        }

        @Override
        public FileResolver.Response get(URI uri, Map<String, String> headers) throws IOException {
            requests.add(Map.copyOf(headers));
            if (failure != null) {
                throw failure;
            }
            return answers.removeFirst();
        }
    }

    @Nested
    @DisplayName("path or url")
    class Location {

        @Test
        @DisplayName("a relative path resolves against the base directory")
        void relative(@TempDir Path base) {
            var prepared = FileResolver.create(base.resolve("cache"), false)
                    .prepare(CSV, config(Map.of("path", "data/a.csv", "header", "true")), base);
            assertThat(prepared.get("path")).contains(base.resolve("data/a.csv").toString());
            assertThat(prepared.get("header")).contains("true");
        }

        @Test
        @DisplayName("an absolute path is kept")
        void absolute(@TempDir Path base) {
            Path file = base.resolve("a.csv").toAbsolutePath();
            var prepared = FileResolver.create(base, false)
                    .prepare(CSV, config(Map.of("path", file.toString())), Path.of("elsewhere"));
            assertThat(prepared.get("path")).contains(file.toString());
        }

        @Test
        @DisplayName("a file: URL is a path")
        void fileUrl(@TempDir Path base) {
            Path file = base.resolve("a.csv").toAbsolutePath();
            assertThat(FileResolver.localPath(config(Map.of("url", file.toUri().toString())))).isEqualTo(file);
            assertThat(FileResolver.localPath(config(Map.of("url", "file:rel/a.csv"))))
                    .isEqualTo(Path.of("rel/a.csv"));
            var prepared = FileResolver.create(base, false)
                    .prepare(CSV, config(Map.of("url", "file:a.csv")), base);
            assertThat(prepared.values()).doesNotContainKey("url");
            assertThat(prepared.get("path")).contains(file.toString());
        }

        @Test
        @DisplayName("naming the file twice, or not at all, is refused")
        void exactlyOne() {
            assertThatThrownBy(() -> FileResolver.location(config(Map.of("path", "a", "url", "file:b"))))
                    .isInstanceOf(EvaluationException.class).hasMessageContaining("not both");
            assertThatThrownBy(() -> FileResolver.location(config(Map.of("path", " "))))
                    .isInstanceOf(EvaluationException.class).hasMessageContaining("needs 'path' or 'url'");
        }

        @Test
        @DisplayName("a URL of another scheme is refused, plain http included")
        void otherSchemes() {
            for (String url : List.of("http://example.org/a.csv", "ftp://x/a", "no-scheme")) {
                assertThatThrownBy(() -> FileResolver.location(config(Map.of("url", url))))
                        .as(url).isInstanceOf(EvaluationException.class)
                        .hasMessageContaining("must be https: or file:");
            }
        }

        @Test
        @DisplayName("a connector reading its config directly cannot be handed a remote URL")
        void unfetched() {
            assertThatThrownBy(() -> FileResolver.localPath(config(Map.of("url", "https://x/a.csv"))))
                    .isInstanceOf(EvaluationException.class).hasMessageContaining("unfetched");
        }

        @Test
        @DisplayName("a connector that reads no file is handed its config unchanged")
        void notAFileConnector(@TempDir Path base) {
            RelixConnector other = new RelixConnector() {
                @Override public java.util.Set<String> handles() { return java.util.Set.of("x"); }
                @Override public java.util.stream.Stream<com.darkcollective.relix.processor.Row> open(
                        ConnectorConfig c, String t, com.darkcollective.relix.symbol.Schema s) {
                    return java.util.stream.Stream.empty();
                }
            };
            ConnectorConfig original = config(Map.of("url", "https://x"));
            assertThat(FileResolver.create(base, true).prepare(other, original, base)).isSameAs(original);
        }
    }

    @Nested
    @DisplayName("fetching")
    class Fetching {

        private static final ConnectorConfig REMOTE = new ConnectorConfig(Map.of("url", "https://x/data/a.csv"));

        @Test
        @DisplayName("a URL is fetched into the cache, under its own file name")
        void fetched(@TempDir Path cache) throws IOException {
            Server server = new Server().answer(200, "id\n1\n", "\"v1\"");
            var prepared = new FileResolver(cache, true, server).prepare(CSV, REMOTE, cache);
            Path local = Path.of(prepared.get("path").orElseThrow());
            assertThat(local.getFileName()).hasToString("a.csv");
            assertThat(Files.readString(local)).isEqualTo("id\n1\n");
            assertThat(server.requests.getFirst()).isEmpty();
        }

        @Test
        @DisplayName("within one session a URL is fetched once")
        void pinned(@TempDir Path cache) {
            Server server = new Server().answer(200, "id\n1\n", null);
            var resolver = new FileResolver(cache, true, server);
            resolver.prepare(CSV, REMOTE, cache);
            resolver.prepare(CSV, REMOTE, cache);
            assertThat(server.requests).hasSize(1);
        }

        @Test
        @DisplayName("a later session revalidates, and keeps the cached copy on 304")
        void revalidated(@TempDir Path cache) throws IOException {
            new FileResolver(cache, true, new Server().answer(200, "id\n1\n", "\"v1\"")).prepare(CSV, REMOTE, cache);
            Server second = new Server().answer(304, "", null);
            var prepared = new FileResolver(cache, true, second).prepare(CSV, REMOTE, cache);
            assertThat(second.requests.getFirst())
                    .containsEntry("If-None-Match", "\"v1\"")
                    .containsEntry("If-Modified-Since", "Sat, 10 Oct 2026 13:00:00 GMT");
            assertThat(Files.readString(Path.of(prepared.get("path").orElseThrow()))).isEqualTo("id\n1\n");
        }

        @Test
        @DisplayName("only the validators the server gave are sent back")
        void partialValidators(@TempDir Path cache) {
            new FileResolver(cache, true, new Server().answer(200, "a", null)).prepare(CSV, REMOTE, cache);
            Server second = new Server().answer(304, "", null);
            new FileResolver(cache, true, second).prepare(CSV, REMOTE, cache);
            assertThat(second.requests.getFirst()).containsOnlyKeys("If-Modified-Since");

            var other = new ConnectorConfig(Map.of("url", "https://x/other.csv"));
            new FileResolver(cache, true, new Server().answer(200, "a", "\"e\"", null)).prepare(CSV, other, cache);
            Server third = new Server().answer(304, "", null);
            new FileResolver(cache, true, third).prepare(CSV, other, cache);
            assertThat(third.requests.getFirst()).containsOnlyKeys("If-None-Match");
        }

        @Test
        @DisplayName("a damaged or short metadata file makes the request unconditional, or partly so")
        void damagedMeta(@TempDir Path cache) throws IOException {
            new FileResolver(cache, true, new Server().answer(200, "a", "\"e\"")).prepare(CSV, REMOTE, cache);
            Path meta;
            try (var walk = Files.walk(cache)) {
                meta = walk.filter(f -> f.getFileName().toString().equals(".relix-meta")).findFirst().orElseThrow();
            }
            Files.writeString(meta, "");
            Server empty = new Server().answer(304, "", null);
            new FileResolver(cache, true, empty).prepare(CSV, REMOTE, cache);
            assertThat(empty.requests.getFirst()).isEmpty();

            Files.writeString(meta, "\"e\"");
            Server oneLine = new Server().answer(304, "", null);
            new FileResolver(cache, true, oneLine).prepare(CSV, REMOTE, cache);
            assertThat(oneLine.requests.getFirst()).containsOnlyKeys("If-None-Match");

            Files.writeString(meta, "\"e\"\n \n");
            Server blankSecond = new Server().answer(304, "", null);
            new FileResolver(cache, true, blankSecond).prepare(CSV, REMOTE, cache);
            assertThat(blankSecond.requests.getFirst()).containsOnlyKeys("If-None-Match");

            Files.delete(meta);
            Server none = new Server().answer(304, "", null);
            new FileResolver(cache, true, none).prepare(CSV, REMOTE, cache);
            assertThat(none.requests.getFirst()).isEmpty();
        }

        @Test
        @DisplayName("a 304 with nothing cached is an error, not an empty file")
        void notModifiedWithoutCache(@TempDir Path cache) {
            assertThatThrownBy(() -> new FileResolver(cache, true, new Server().answer(304, "", null))
                    .prepare(CSV, REMOTE, cache))
                    .isInstanceOf(EvaluationException.class).hasMessageContaining("HTTP 304");
        }

        @Test
        @DisplayName("a later session replaces a file that changed")
        void replaced(@TempDir Path cache) throws IOException {
            new FileResolver(cache, true, new Server().answer(200, "old", null)).prepare(CSV, REMOTE, cache);
            var prepared = new FileResolver(cache, true, new Server().answer(200, "new", null))
                    .prepare(CSV, REMOTE, cache);
            assertThat(Files.readString(Path.of(prepared.get("path").orElseThrow()))).isEqualTo("new");
        }

        @Test
        @DisplayName("offline, a cached copy is used; with none, the error names the URL")
        void offline(@TempDir Path cache) {
            Server down = new Server();
            down.failure = new IOException("connection refused");
            assertThatThrownBy(() -> new FileResolver(cache, true, down).prepare(CSV, REMOTE, cache))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("https://x/data/a.csv").hasMessageContaining("connection refused");
            new FileResolver(cache, true, new Server().answer(200, "id\n", null)).prepare(CSV, REMOTE, cache);
            assertThat(new FileResolver(cache, true, down).prepare(CSV, REMOTE, cache).get("path")).isPresent();
        }

        @Test
        @DisplayName("a status other than 200 or 304 is an error")
        void failedStatus(@TempDir Path cache) {
            assertThatThrownBy(() -> new FileResolver(cache, true, new Server().answer(404, "", null))
                    .prepare(CSV, REMOTE, cache))
                    .isInstanceOf(EvaluationException.class).hasMessageContaining("HTTP 404");
        }

        @Test
        @DisplayName("an interrupted fetch keeps the thread's interrupt")
        void interrupted(@TempDir Path cache) {
            FileResolver.Transport t = (u, h) -> { throw new InterruptedException(); };
            try {
                assertThatThrownBy(() -> new FileResolver(cache, true, t).prepare(CSV, REMOTE, cache))
                        .isInstanceOf(EvaluationException.class).hasMessageContaining("interrupted");
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }

        @Test
        @DisplayName("a session that does not fetch refuses a URL")
        void refused(@TempDir Path cache) {
            assertThatThrownBy(() -> new FileResolver(cache, false, new Server()).prepare(CSV, REMOTE, cache))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("does not fetch remote files");
        }

        @Test
        @DisplayName("sha256 pins the content")
        void pinnedDigest(@TempDir Path cache) throws Exception {
            String digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest("id\n".getBytes(StandardCharsets.UTF_8)));
            var matching = new ConnectorConfig(Map.of("url", "https://x/a.csv", "sha256", digest.toUpperCase()));
            assertThat(new FileResolver(cache, true, new Server().answer(200, "id\n", null))
                    .prepare(CSV, matching, cache).get("path")).isPresent();
            var wrong = new ConnectorConfig(Map.of("url", "https://x/b.csv", "sha256", "00"));
            assertThatThrownBy(() -> new FileResolver(cache, true, new Server().answer(200, "id\n", null))
                    .prepare(CSV, wrong, cache))
                    .isInstanceOf(EvaluationException.class).hasMessageContaining("does not match its sha256");
        }

        @Test
        @DisplayName("a URL with no file name is cached as 'file'")
        void fileName() {
            assertThat(FileResolver.fileName(URI.create("https://x/"))).isEqualTo("file");
            assertThat(FileResolver.fileName(URI.create("https://x"))).isEqualTo("file");
            assertThat(FileResolver.fileName(URI.create("https://x/.relix-meta"))).isEqualTo("file");
            assertThat(FileResolver.fileName(URI.create("https://x/a/b.log?v=1"))).isEqualTo("b.log");
        }

        @Test
        @DisplayName("the HTTP transport sends the validators and reads them back")
        void transport() throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            List<String> seen = new ArrayList<>();
            server.createContext("/a.csv", exchange -> {
                seen.add(exchange.getRequestHeaders().getFirst("If-None-Match"));
                exchange.getResponseHeaders().add("ETag", "\"v2\"");
                byte[] body = "id\n".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            try {
                var response = FileResolver.over(HttpClient.newHttpClient()).get(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/a.csv"),
                        Map.of("If-None-Match", "\"v1\""));
                assertThat(response.status()).isEqualTo(200);
                assertThat(response.etag()).contains("\"v2\"");
                assertThat(response.lastModified()).isEmpty();
                assertThat(seen).containsExactly("\"v1\"");
            } finally {
                server.stop(0);
            }
        }
    }

    @Nested
    @DisplayName("reading")
    class Reading {

        @Test
        @DisplayName("a gzip file reads as its content, whatever its name")
        void gzip(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("rotated.1");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (var gz = new GZIPOutputStream(bytes)) {
                gz.write("a\nb\n".getBytes(StandardCharsets.UTF_8));
            }
            Files.write(file, bytes.toByteArray());
            assertThat(FileResolver.readLines(file)).containsExactly("a", "b");
        }

        @Test
        @DisplayName("a plain file, including a one-byte one, reads as itself")
        void plain(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("one");
            Files.writeString(file, "x");
            try (BufferedReader reader = FileResolver.openText(file)) {
                assertThat(reader.readLine()).isEqualTo("x");
            }
        }

        @Test
        @DisplayName("a file whose first byte is gzip's but second is not reads as text")
        void halfMagic(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("odd");
            Files.write(file, new byte[] {0x1f, 'a', '\n'});
            assertThat(FileResolver.readLines(file)).containsExactly("\u001fa");
        }

        @Test
        @DisplayName("a missing file is an I/O error")
        void missing(@TempDir Path dir) {
            assertThatThrownBy(() -> FileResolver.openText(dir.resolve("absent")))
                    .isInstanceOf(UncheckedIOException.class);
            assertThatThrownBy(() -> FileResolver.readLines(dir.resolve("absent")))
                    .isInstanceOf(IOException.class);
        }
    }
}

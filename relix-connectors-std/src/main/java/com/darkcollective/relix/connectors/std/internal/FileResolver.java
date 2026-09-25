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

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * The one answer to <em>give me a local file for this connection</em>, for every
 * connector that reads a file.
 *
 * <p>A file-backed connection names its file by {@code path} or by {@code url}, never
 * both. A {@code path} is resolved against the session's base directory, which for a
 * script is the script's own directory, the rule a {@code csv("…")} source already
 * follows. A {@code url} is either {@code file:}, which is a path spelled as a URL, or
 * {@code https:}, which is fetched. A connector is handed an absolute {@code path} and
 * never learns which kind it was given.
 *
 * <h2>A fetched file is pinned for the session</h2>
 * The engine treats a base relation as the same relation every time it is read: the
 * planner shares a sub-plan on that strength, and a measured row count is replayed into
 * the cost model. A URL can change underneath a running program, so each URL is fetched
 * at most once per resolver, and a session owns one resolver: every scan in a session
 * reads the same bytes. Between sessions the cached copy is revalidated with the
 * server's {@code ETag} or {@code Last-Modified}, so an unchanged file is not
 * downloaded again. A session that cannot reach the server uses the cached copy when it
 * has one.
 *
 * <p>A connection may pin the file's content with {@code sha256: "<hex>"}. A download,
 * or a cached copy, whose digest differs is refused.
 *
 * <p>Only {@code https} is fetched. A session can refuse fetching entirely, in which
 * case a connection naming an {@code https} URL is an error rather than a download.
 *
 * <h2>Compressed files</h2>
 * {@link #openText} reads a gzip file transparently, recognising it by its content
 * rather than its name, so a rotated {@code access.log.2.gz} reads like any other file.
 */
public final class FileResolver {

    /** The cache directory a session uses by default. */
    public static final Path DEFAULT_CACHE = Path.of(System.getProperty("user.home"), ".relix", "cache", "files");

    private static final String META = ".relix-meta";

    /** One HTTP GET, reduced to what revalidation needs. */
    @FunctionalInterface
    interface Transport {
        Response get(URI uri, Map<String, String> headers) throws IOException, InterruptedException;
    }

    /** A response: its status, body, and the two validators a later request can send back. */
    record Response(int status, byte[] body, Optional<String> etag, Optional<String> lastModified) {}

    /** Where a connection's file is: on this machine, or at a URL to fetch. */
    sealed interface Location permits Local, Remote {}

    /** A file on this machine; the path may be relative. */
    record Local(Path path) implements Location {}

    /** A file to fetch. */
    record Remote(URI uri) implements Location {}

    private final Path cacheDir;
    private final boolean fetch;
    private final Transport transport;
    private final Map<URI, Path> pinned = new ConcurrentHashMap<>();

    FileResolver(Path cacheDir, boolean fetch, Transport transport) {
        this.cacheDir = cacheDir;
        this.fetch = fetch;
        this.transport = transport;
    }

    /**
     * A resolver for one session.
     *
     * @param cacheDir where fetched files are kept between sessions; must not be null
     * @param fetch    whether an {@code https} URL may be downloaded at all
     * @return the resolver
     */
    public static FileResolver create(Path cacheDir, boolean fetch) {
        return new FileResolver(cacheDir, fetch, over(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build()));
    }

    /**
     * {@return {@code config} with its file named by an absolute local {@code path}} A
     * connector that does not read a file is handed {@code config} unchanged.
     *
     * @param connector the connector the config is for
     * @param config    the connection's settings
     * @param baseDir   what a relative {@code path} resolves against
     * @throws EvaluationException when the settings name no file, name one twice, or name
     *                             a URL that cannot be fetched
     */
    public ConnectorConfig prepare(RelixConnector connector, ConnectorConfig config, Path baseDir) {
        if (!(connector instanceof FileBacked)) {
            return config;
        }
        Path local = switch (location(config)) {
            case Local l -> baseDir.resolve(l.path()).toAbsolutePath().normalize();
            case Remote r -> fetched(r.uri(), config.get("sha256"));
        };
        Map<String, String> values = new LinkedHashMap<>(config.values());
        values.remove("url");
        values.put("path", local.toString());
        return new ConnectorConfig(values);
    }

    /**
     * {@return the file {@code config} names, for a connector reading it directly} A
     * relative path resolves against the working directory; a session hands its
     * connectors an absolute one.
     *
     * @throws EvaluationException for a URL only a session can fetch
     */
    static Path localPath(ConnectorConfig config) {
        return switch (location(config)) {
            case Local l -> l.path();
            case Remote r -> throw new EvaluationException("'" + r.uri() + "' is a remote file; "
                    + "it is fetched by the session that runs the query, and this connector was "
                    + "handed it unfetched");
        };
    }

    /** {@return where {@code config}'s file is} The one statement of the path-or-url rule. */
    static Location location(ConnectorConfig config) {
        Optional<String> path = config.get("path").filter(p -> !p.isBlank());
        Optional<String> url = config.get("url").filter(u -> !u.isBlank());
        if (path.isPresent() && url.isPresent()) {
            throw new EvaluationException("a file connection names its file by 'path' or by 'url', not both");
        }
        if (path.isPresent()) {
            return new Local(Path.of(path.get()));
        }
        String spelled = url.orElseThrow(() -> new EvaluationException(
                "a file connection needs 'path' or 'url' naming its file"));
        String scheme = spelled.contains(":") ? spelled.substring(0, spelled.indexOf(':')).toLowerCase(Locale.ROOT) : "";
        return switch (scheme) {
            case "file" -> new Local(filePath(spelled));
            case "https" -> new Remote(URI.create(spelled));
            default -> throw new EvaluationException("'" + spelled + "' cannot be read: a file "
                    + "connection's url must be https: or file:");
        };
    }

    /** {@code file:/abs}, {@code file:///abs} or {@code file:relative}. */
    private static Path filePath(String url) {
        String rest = url.substring("file:".length());
        if (rest.startsWith("//")) {
            return Path.of(URI.create(url));
        }
        return Path.of(rest);
    }

    /**
     * Opens {@code path} as UTF-8 text, decompressing it when it is gzip.
     *
     * @param path the file
     * @return a reader the caller closes
     * @throws UncheckedIOException when the file cannot be read
     */
    static BufferedReader openText(Path path) {
        try {
            InputStream in = new BufferedInputStream(Files.newInputStream(path));
            in.mark(2);
            int first = in.read();
            int second = in.read();
            in.reset();
            if (first == 0x1f && second == 0x8b) {
                in = new GZIPInputStream(in);
            }
            return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@return every line of {@code path}, decompressed when it is gzip} */
    static List<String> readLines(Path path) throws IOException {
        try (BufferedReader reader = openText(path)) {
            return new java.util.ArrayList<>(reader.lines().toList());
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    // ── fetching ──────────────────────────────────────────────────────────────

    private Path fetched(URI uri, Optional<String> sha256) {
        if (!fetch) {
            throw new EvaluationException("'" + uri + "' is a remote file, and this session "
                    + "does not fetch remote files");
        }
        Path local = pinned.computeIfAbsent(uri, this::download);
        sha256.ifPresent(expected -> verify(uri, local, expected));
        return local;
    }

    private Path download(URI uri) {
        Path dir = cacheDir.resolve(digest(uri.toString().getBytes(StandardCharsets.UTF_8)));
        Path body = dir.resolve(fileName(uri));
        Path meta = dir.resolve(META);
        boolean cached = Files.isRegularFile(body);
        Map<String, String> headers = new HashMap<>();
        if (cached) {
            validators(meta).forEach(headers::put);
        }
        Response response;
        try {
            response = transport.get(uri, headers);
        } catch (IOException e) {
            if (cached) {
                return body;    // offline, but this machine has seen the file before
            }
            throw new EvaluationException("could not fetch '" + uri + "': " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EvaluationException("fetching '" + uri + "' was interrupted");
        }
        if (response.status() == 304 && cached) {
            return body;
        }
        if (response.status() != 200) {
            throw new EvaluationException("could not fetch '" + uri + "': HTTP " + response.status());
        }
        try {
            Files.createDirectories(dir);
            Path partial = Files.createTempFile(dir, "download", ".part");
            Files.write(partial, response.body());
            Files.move(partial, body, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(meta, response.etag().orElse("") + "\n" + response.lastModified().orElse(""));
        } catch (IOException e) {
            throw new EvaluationException("could not cache '" + uri + "': " + e.getMessage());
        }
        return body;
    }

    /** {@return the validators a cached copy was saved with, as request headers} */
    private static Map<String, String> validators(Path meta) {
        Map<String, String> headers = new HashMap<>();
        try {
            List<String> lines = Files.readAllLines(meta, StandardCharsets.UTF_8);
            if (!lines.isEmpty() && !lines.get(0).isBlank()) {
                headers.put("If-None-Match", lines.get(0));
            }
            if (lines.size() > 1 && !lines.get(1).isBlank()) {
                headers.put("If-Modified-Since", lines.get(1));
            }
        } catch (IOException ignored) {
            // No validators: the request is unconditional, which is only slower.
        }
        return headers;
    }

    private static void verify(URI uri, Path local, String expected) {
        String actual;
        try {
            actual = digest(Files.readAllBytes(local));
        } catch (IOException e) {
            throw new EvaluationException("could not read '" + local + "': " + e.getMessage());
        }
        if (!actual.equalsIgnoreCase(expected.strip())) {
            throw new EvaluationException("'" + uri + "' does not match its sha256: expected "
                    + expected.strip() + ", got " + actual);
        }
    }

    /** The URL's last path segment, which is what a connection's {@code table} can name. */
    static String fileName(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        String last = path.substring(path.lastIndexOf('/') + 1);
        return last.isBlank() || last.equals(META) ? "file" : last;
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM provides SHA-256", e);
        }
    }

    /** {@return a transport over {@code client}} Scheme-agnostic; the resolver refuses non-https. */
    static Transport over(HttpClient client) {
        return (uri, headers) -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).GET();
            headers.forEach(request::header);
            HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Response(response.statusCode(), response.body(),
                    response.headers().firstValue("ETag"),
                    response.headers().firstValue("Last-Modified"));
        };
    }
}

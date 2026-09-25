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

import com.darkcollective.relix.ast.internal.TemporalLiterals;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.source.ApiKeyAuth;
import com.darkcollective.relix.lang.ast.source.AuthSpec;
import com.darkcollective.relix.lang.ast.source.BasicAuth;
import com.darkcollective.relix.lang.ast.source.BearerAuth;
import com.darkcollective.relix.lang.ast.source.ColumnBinding;
import com.darkcollective.relix.lang.ast.source.ColumnDirection;
import com.darkcollective.relix.lang.ast.source.ColumnSpec;
import com.darkcollective.relix.lang.ast.source.CsvExtractSpec;
import com.darkcollective.relix.lang.ast.source.ExtractPathBinding;
import com.darkcollective.relix.lang.ast.source.ExtractSpec;
import com.darkcollective.relix.lang.ast.source.HeaderBinding;
import com.darkcollective.relix.json.JsonWriter;
import com.darkcollective.relix.lang.ast.source.HttpMethod;
import com.darkcollective.relix.lang.ast.source.HttpSourceConfig;
import com.darkcollective.relix.lang.ast.source.JsonExtractSpec;
import com.darkcollective.relix.lang.ast.source.PaginateEntry;
import com.darkcollective.relix.lang.ast.source.PathParamBinding;
import com.darkcollective.relix.lang.ast.source.QueryParamBinding;
import com.darkcollective.relix.processor.internal.ArrayRow;
import com.darkcollective.relix.processor.internal.DataSourceConnector;
import com.darkcollective.relix.processor.internal.DocumentRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.internal.JsonValues;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.value.internal.ValuePath;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.ArrayType;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.StructType;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Schema;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Executes {@code source … from http { … }} relations: it issues the configured
 * HTTP request, parses the JSON response, and yields one {@link Row} per record.
 *
 * <p><strong>Read-only by design.</strong> Only {@code GET}, {@code QUERY} and
 * {@code POST} are possible (the grammar rejects the mutating methods). {@code QUERY}
 * is the RFC 10008 read whose query is the request body; {@code POST} is used solely
 * to carry a read query in the request body for APIs that do not accept
 * {@code QUERY} (e.g. GraphQL/search APIs).
 *
 * <p>A request that carries a body is sent with {@code Content-Type: application/json}
 * unless the declaration names a {@code Content-Type} of its own.
 *
 * <h2>Schema</h2>
 * A source with no declared columns is <em>open</em> (schema-on-read): each JSON
 * record becomes a {@link DocumentRow} navigated by path at query time. A declared
 * {@code schema { col: TYPE at "$.path" }} makes the source closed and typed: each
 * column is extracted from the record by its {@code at} path (or the column name)
 * and coerced to the declared scalar type.
 *
 * <h2>Authentication</h2>
 * {@code auth: bearer/basic/apikey(…)} is translated to the appropriate header
 * (or query parameter for {@code apikey(query(…))}); a raw {@code Authorization}
 * header works too. A {@code ${NAME}} placeholder reaches this connector already
 * resolved: the session resolves the declaration before handing it over.
 *
 * <h2>Request determinism (v1)</h2>
 * The request is fully determined by the declaration: static URL/headers/auth/body
 * plus pagination defaults and any {@code IN}-column {@code [default: …]}. Pushing a
 * query predicate ({@code σ city = "X"}) into a request parameter is a planned
 * follow-on; a {@code [required]} {@code IN} column with no default is therefore a
 * clear error today.
 */
public final class HttpDataSourceConnector implements DataSourceConnector {

    /** The result of an HTTP exchange: the status code and the response body. */
    public record HttpFetchResult(int statusCode, String body) {
        public HttpFetchResult {
            Objects.requireNonNull(body, "body");
        }
    }

    /** The fully-resolved request the connector wants to send. */
    public record HttpRequestSpec(HttpMethod method, String url,
                                  Map<String, String> headers, Optional<String> body) {
        public HttpRequestSpec {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(url, "url");
            headers = Map.copyOf(headers);
            Objects.requireNonNull(body, "body");
        }
    }

    /**
     * The I/O boundary — sends a request and returns the response. The default
     * implementation uses {@link java.net.http.HttpClient}; tests inject a fake.
     */
    @FunctionalInterface
    public interface HttpTransport {
        /**
         * Sends {@code request} and returns the response.
         *
         * @param request the resolved request
         * @return the status code + body
         * @throws IOException          on a transport failure
         * @throws InterruptedException if the calling thread is interrupted
         */
        HttpFetchResult fetch(HttpRequestSpec request) throws IOException, InterruptedException;
    }

    private final SemanticModel model;
    private final HttpTransport transport;

    /**
     * Creates a connector backed by a real {@link java.net.http.HttpClient}.
     *
     * @param model the semantic model holding the source declarations; must not be null
     */
    public HttpDataSourceConnector(SemanticModel model) {
        this(model, defaultTransport());
    }

    /**
     * Creates a connector with an injected transport (for testing).
     *
     * @param model     the semantic model; must not be null
     * @param transport the transport seam; must not be null
     */
    public HttpDataSourceConnector(SemanticModel model, HttpTransport transport) {
        this.model = Objects.requireNonNull(model, "model");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public Stream<Row> open(String relationName, Schema schema) {
        SourceDeclaration declaration = model.sources().get(relationName.toLowerCase(Locale.ROOT));
        if (declaration == null || !(declaration.config() instanceof HttpSourceConfig http)) {
            throw new EvaluationException(
                    "No HTTP source declaration for external relation '" + relationName + "'");
        }
        HttpRequestSpec request = buildRequest(relationName, http, schema);
        HttpFetchResult result = send(request, relationName);
        if (result.statusCode() < 200 || result.statusCode() >= 300) {
            throw new EvaluationException(
                    "HTTP source '" + relationName + "' returned status " + result.statusCode()
                    + ": " + snippet(result.body()));
        }
        List<Value> records = extractRecords(relationName, http, result.body());
        return toRows(relationName, http, schema, records).stream();
    }

    // ── Request building ──────────────────────────────────────────────────────

    private HttpRequestSpec buildRequest(String relationName, HttpSourceConfig http,
                                         Schema schema) {
        Map<String, String> headers = new LinkedHashMap<>(http.headers());
        Map<String, String> pathParams = new LinkedHashMap<>();
        List<String> queryParams = new ArrayList<>();

        http.auth().ifPresent(auth -> applyAuth(auth, headers, queryParams));

        // Pagination parameters from their declared defaults.
        http.paginate().ifPresent(p -> {
            for (PaginateEntry entry : p.entries()) {
                entry.defaultValue().ifPresent(
                        def -> queryParams.add(encode(entry.paramName()) + "=" + encode(Long.toString(def))));
            }
        });

        // IN columns from their [default: …]; a required IN column with no default
        // cannot be satisfied without predicate pushdown (a planned follow-on).
        for (ColumnSpec col : http.columns()) {
            if (col.direction() != ColumnDirection.IN) {
                continue;
            }
            Optional<String> value = resolveInputValue(relationName, http, col);
            value.ifPresent(v -> applyInputBinding(col, v, headers, pathParams, queryParams));
        }

        String url = substitutePathParams(http.url(), pathParams);
        url = appendQuery(url, queryParams);
        Optional<String> body = body(http, schema);
        if (body.isPresent() && !hasHeader(headers, CONTENT_TYPE)) {
            headers.put(CONTENT_TYPE, "application/json");
        }
        return new HttpRequestSpec(http.method(), url, headers, body);
    }

    private static final String CONTENT_TYPE = "Content-Type";

    /** {@return whether {@code headers} names {@code name}, compared as HTTP compares it} */
    private static boolean hasHeader(Map<String, String> headers, String name) {
        return headers.keySet().stream().anyMatch(name::equalsIgnoreCase);
    }

    /** Resolves an IN column's value: its default, or empty (required-without-default errors). */
    private Optional<String> resolveInputValue(String relationName, HttpSourceConfig http, ColumnSpec col) {
        if (col.defaultValue().isPresent()) {
            return col.defaultValue();
        }
        if (col.required()) {
            throw new EvaluationException(
                    "HTTP source '" + relationName + "': required input column '" + col.name()
                    + "' has no value — predicate pushdown into HTTP requests is not yet supported, "
                    + "so an IN column must declare a [default: …]");
        }
        return Optional.empty();
    }

    private static void applyAuth(AuthSpec auth, Map<String, String> headers, List<String> queryParams) {
        switch (auth) {
            case BearerAuth bearer -> headers.put("Authorization", "Bearer " + bearer.token());
            case BasicAuth basic -> {
                String raw = basic.username() + ":" + basic.password();
                String encoded = java.util.Base64.getEncoder()
                        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
                headers.put("Authorization", "Basic " + encoded);
            }
            case ApiKeyAuth key -> {
                switch (key.location()) {
                    case HEADER -> headers.put(key.name(), key.value());
                    case QUERY -> queryParams.add(encode(key.name()) + "=" + encode(key.value()));
                }
            }
        }
    }

    private static void applyInputBinding(ColumnSpec col, String value, Map<String, String> headers,
                                          Map<String, String> pathParams, List<String> queryParams) {
        ColumnBinding binding = col.binding().orElse(new QueryParamBinding(col.name()));
        switch (binding) {
            case QueryParamBinding q -> queryParams.add(encode(q.paramName()) + "=" + encode(value));
            case HeaderBinding h -> headers.put(h.headerName(), value);
            case PathParamBinding p -> pathParams.put(p.paramName(), value);
            // An OUT-only binding on an IN column is a no-op (the validator owns shape checks).
            case ExtractPathBinding ignored -> { }
        }
    }

    private static String substitutePathParams(String url, Map<String, String> pathParams) {
        String result = url;
        for (Map.Entry<String, String> e : pathParams.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", encode(e.getValue()));
        }
        return result;
    }

    private static String appendQuery(String url, List<String> queryParams) {
        if (queryParams.isEmpty()) {
            return url;
        }
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + String.join("&", queryParams);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private HttpFetchResult send(HttpRequestSpec request, String relationName) {
        try {
            return transport.fetch(request);
        } catch (IOException e) {
            throw new EvaluationException(
                    "HTTP source '" + relationName + "' request to " + request.url()
                    + " failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EvaluationException(
                    "HTTP source '" + relationName + "' request was interrupted");
        } catch (IllegalArgumentException e) {
            // HttpClient refuses a restricted or malformed header, or a malformed URL,
            // before anything is sent. Analysis refuses what it can see; this is what a
            // value only known at run time can still produce.
            throw new EvaluationException(
                    "HTTP source '" + relationName + "' request could not be sent: " + e.getMessage());
        }
    }

    private static String snippet(String body) {
        String trimmed = body.strip();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "…";
    }

    // ── Response → records ──────────────────────────────────────────────────────

    private List<Value> extractRecords(String relationName, HttpSourceConfig http, String body) {
        Optional<ExtractSpec> extract = http.extract();
        if (extract.isPresent() && extract.get() instanceof CsvExtractSpec) {
            throw new EvaluationException(
                    "HTTP source '" + relationName + "': CSV extract is not yet executable — "
                    + "use 'extract: json(...)' or an open (schema-less) JSON source");
        }
        Value document = parseJson(relationName, body);
        Optional<String> recordsPath = extract
                .filter(JsonExtractSpec.class::isInstance)
                .map(JsonExtractSpec.class::cast)
                .map(JsonExtractSpec::jsonPath)
                .flatMap(HttpDataSourceConnector::normalisePath);
        Value located = recordsPath.map(p -> ValuePath.navigate(document, p)).orElse(document);
        return recordsOf(relationName, located);
    }

    private static Value parseJson(String relationName, String body) {
        try {
            return JsonValues.parse(body);
        } catch (JsonValues.JsonParseException e) {
            throw new EvaluationException(
                    "HTTP source '" + relationName + "': malformed JSON response: " + e.getMessage());
        }
    }

    /**
     * Normalises a JSONPath extract expression (e.g. {@code "$.items[*]"}) into the
     * dotted path {@link ValuePath} understands ({@code "items"}). A root expression
     * ({@code "$"}/{@code "$."}) yields empty, meaning "use the document itself".
     */
    static Optional<String> normalisePath(String jsonPath) {
        String p = jsonPath.strip();
        if (p.startsWith("$")) {
            p = p.substring(1);
        }
        if (p.startsWith(".")) {
            p = p.substring(1);
        }
        if (p.endsWith("[*]")) {
            p = p.substring(0, p.length() - 3);
        }
        if (p.endsWith(".")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.isBlank() ? Optional.empty() : Optional.of(p);
    }

    /** A located array yields its elements; a single object yields one record. */
    private static List<Value> recordsOf(String relationName, Value located) {
        if (located instanceof ArrayValue array) {
            return new ArrayList<>(array.elements());
        }
        if (located instanceof StructValue) {
            return List.of(located);
        }
        throw new EvaluationException(
                "HTTP source '" + relationName + "': response records must be a JSON array or object "
                + "(use 'extract: json(\"$.path\")' to locate them)");
    }

    // ── Records → rows ────────────────────────────────────────────────────────

    private List<Row> toRows(String relationName, HttpSourceConfig http, Schema schema, List<Value> records) {
        List<Row> rows = new ArrayList<>(records.size());
        for (Value record : records) {
            if (!(record instanceof StructValue document)) {
                throw new EvaluationException(
                        "HTTP source '" + relationName + "': each record must be a JSON object");
            }
            rows.add(http.isOpen() ? new DocumentRow(document) : closedRow(relationName, http, schema, document));
        }
        return rows;
    }

    private Row closedRow(String relationName, HttpSourceConfig http, Schema schema, StructValue record) {
        // Driven by the schema rather than by the declaration, because the two can differ:
        // a planner that narrowed this scan asks for a subset, and a row has to be what it
        // was asked for. A column the declaration does not carry is NULL rather than an
        // error, which is the same rule an undeclared column already got.
        List<Value> values = new ArrayList<>(schema.columns().size());
        for (ColumnDefinition column : schema.columns()) {
            ColumnSpec col = declaredColumn(http, column.name());
            if (col == null) {
                values.add(NullValue.INSTANCE);
            } else if (col.direction() == ColumnDirection.IN) {
                // The input value echoed back as a column (constant across rows).
                values.add(resolveInputValue(relationName, http, col)
                        .map(v -> coerceString(relationName, v, col))
                        .orElse(NullValue.INSTANCE));
            } else {
                Value raw = ValuePath.navigate(record, outputPath(col));
                values.add(coerceValue(relationName, raw, col));
            }
        }
        return ArrayRow.of(schema, values);
    }

    /** {@return the declared column of this name, or {@code null} when there is none} */
    private static ColumnSpec declaredColumn(HttpSourceConfig http, String name) {
        for (ColumnSpec col : http.columns()) {
            if (col.name().equalsIgnoreCase(name)) {
                return col;
            }
        }
        return null;
    }

    /**
     * {@return the request body to send} A declared {@code body} is sent verbatim: the user
     * wrote a query, and sending a different one is the failure this must not have.
     *
     * <p>When there is none and the method is {@code POST}, the body is generated as a
     * GraphQL document selecting exactly the columns this scan was asked for — which is
     * where projection pushdown happens, the selection set being the field list. A
     * {@code POST} with no body cannot have been reaching a GraphQL endpoint before, so no
     * configuration that worked changes meaning.
     *
     * <p>Generation needs the {@code extract} path to say where the records sit, and
     * declines rather than guesses when it cannot build a valid selection.
     */
    private static Optional<String> body(HttpSourceConfig http, Schema schema) {
        if (http.body().isPresent() || http.method() != HttpMethod.POST) {
            return http.body();
        }
        Optional<String> records = http.extract()
                .filter(JsonExtractSpec.class::isInstance)
                .map(JsonExtractSpec.class::cast)
                .map(JsonExtractSpec::jsonPath)
                .flatMap(HttpDataSourceConnector::normalisePath);
        if (records.isEmpty()) {
            return Optional.empty();
        }
        // Driven by the declaration and filtered by the schema, rather than the other way
        // round: an IN column is a request parameter and not a field to select, so it is
        // skipped here even when the query reads it back.
        List<String> fields = new ArrayList<>();
        for (ColumnSpec col : http.columns()) {
            if (col.direction() != ColumnDirection.IN && schema.indexOf(col.name()) >= 0) {
                fields.add(outputPath(col));
            }
        }
        return GraphQlQuery.build(records.get(), fields)
                .map(document -> new JsonWriter()
                        .beginObject().name("query").value(document).endObject()
                        .toJson());
    }

    /** The dotted path an OUT column reads from each record: its {@code at} binding, else its name. */
    private static String outputPath(ColumnSpec col) {
        return col.binding()
                .filter(ExtractPathBinding.class::isInstance)
                .map(ExtractPathBinding.class::cast)
                .map(ExtractPathBinding::path)
                .flatMap(HttpDataSourceConnector::normalisePath)
                .orElse(col.name());
    }

    // ── Coercion ──────────────────────────────────────────────────────────────

    /**
     * Coerces an extracted JSON {@link Value} to a column's declared type.
     *
     * <p>A <em>nested</em> declaration is taken as read: the payload was parsed by
     * {@code JsonValues}, so a sub-object is already a {@code StructValue} and a list an
     * {@code ArrayValue} — the declared shape documents what the endpoint returns and types
     * the path for analysis, and re-walking it here would only be able to disagree with the
     * value the parser already built.  A value whose shape contradicts the declaration is
     * a coercion error, in the same way a non-numeric string is one for {@code NUMBER}.
     */
    private static Value coerceValue(String relationName, Value raw, ColumnSpec col) {
        if (raw instanceof NullValue) {
            return NullValue.INSTANCE;
        }
        if (col.type() instanceof StructType) {
            if (raw instanceof StructValue) {
                return raw;
            }
            throw new EvaluationException(coercionError(relationName, raw.asDisplayString(), col));
        }
        if (col.type() instanceof ArrayType) {
            if (raw instanceof ArrayValue) {
                return raw;
            }
            throw new EvaluationException(coercionError(relationName, raw.asDisplayString(), col));
        }
        ScalarType type = (ScalarType) col.type();
        return switch (type) {
            case ANY -> raw;
            case NUMBER -> raw instanceof NumberValue ? raw : coerceString(relationName, raw.asDisplayString(), col);
            case BOOLEAN -> raw instanceof BooleanValue ? raw : coerceString(relationName, raw.asDisplayString(), col);
            case STRING -> raw instanceof StringValue ? raw : new StringValue(raw.asDisplayString());
            case DATE, TIME, TIMESTAMP, DURATION -> coerceString(relationName, raw.asDisplayString(), col);
        };
    }

    /** Coerces a string (IN default, or a stringified scalar) to a column's declared type. */
    private static Value coerceString(String relationName, String text, ColumnSpec col) {
        if (!(col.type() instanceof ScalarType type)) {
            // Only reached through an IN default, which is text: no text spells a struct.
            throw new EvaluationException(coercionError(relationName, text, col));
        }
        try {
            return switch (type) {
                case STRING -> new StringValue(text);
                case ANY -> {
                    try {
                        yield new NumberValue(new BigDecimal(text.strip()));
                    } catch (NumberFormatException ignored) {
                        yield new StringValue(text);
                    }
                }
                case NUMBER -> new NumberValue(new BigDecimal(text.strip()));
                case BOOLEAN -> {
                    String lower = text.strip().toLowerCase(Locale.ROOT);
                    if (lower.equals("true") || lower.equals("1")) yield BooleanValue.of(true);
                    if (lower.equals("false") || lower.equals("0")) yield BooleanValue.of(false);
                    throw new EvaluationException(coercionError(relationName, text, col));
                }
                case DATE -> new DateValue(TemporalLiterals.parseDate(text.strip()));
                case TIME -> new TimeValue(TemporalLiterals.parseTime(text.strip()));
                case TIMESTAMP -> new TimestampValue(TemporalLiterals.parseTimestamp(text.strip()));
                case DURATION -> new DurationValue(TemporalLiterals.parseDuration(text.strip()));
            };
        } catch (NumberFormatException | DateTimeException e) {
            throw new EvaluationException(coercionError(relationName, text, col));
        }
    }

    private static String coercionError(String relationName, String text, ColumnSpec col) {
        return "HTTP source '" + relationName + "': cannot parse '" + text
                + "' as " + col.type().display() + " for column '" + col.name() + "'";
    }

    // ── Default transport ───────────────────────────────────────────────────────

    private static HttpTransport defaultTransport() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return request -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(request.url()))
                    .timeout(Duration.ofSeconds(60));
            request.headers().forEach((name, value) -> {
                try {
                    builder.header(name, value);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("header '" + name + "': " + e.getMessage(), e);
                }
            });
            HttpRequest.BodyPublisher publisher = request.body()
                    .map(b -> HttpRequest.BodyPublishers.ofString(b, StandardCharsets.UTF_8))
                    .orElse(HttpRequest.BodyPublishers.noBody());
            builder.method(request.method().name(), publisher);
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new HttpFetchResult(response.statusCode(), response.body());
        };
    }
}

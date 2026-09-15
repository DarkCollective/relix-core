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
package com.darkcollective.relix.lang.ast.source;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Configuration for an HTTP/REST API source.
 *
 * <p>Full example:
 * <pre>
 *   source current_weather from http {
 *       url:     "https://api.openweathermap.org/data/2.5/weather",
 *       method:  GET,
 *       headers: { "Accept": "application/json" },
 *       auth:    apikey("X-API-Key", "${WEATHER_API_KEY}"),
 *       extract: json("$.items"),
 *       schema: {
 *           temp:      NUMBER at "$.main.temp",
 *           condition: STRING at "$.weather[0].description"
 *       }
 *   };
 * </pre>
 *
 * <p>The {@code schema} block is <strong>optional</strong>: when omitted the
 * source is <em>open</em> (schema-on-read) — each record becomes a document row
 * navigated by JSONPath at query time. When present, the source is a closed,
 * typed relation whose columns are extracted by their {@code at "$.path"}
 * bindings.
 *
 * <p>The {@code extract} spec is also optional: when omitted the response body
 * is parsed as JSON and a top-level array (or single object) is treated as the
 * records, exactly like a local JSON file source.
 *
 * <p>Header, URL, body, and auth values may contain {@code ${VAR}} placeholders;
 * these are resolved against the active environment before the script is parsed.
 *
 * @param url      the URL; may contain {@code ${ENV_VAR}} references; must not be blank
 * @param method   the HTTP method (GET or POST — Relix is read-only); must not be null
 * @param headers  static request headers; may be empty
 * @param extract  how to locate records in the response body; absent means the
 *                 JSON-file default (top-level array or object)
 * @param paginate optional pagination parameter mapping; absent means unpaginated
 * @param columns  the ordered column specifications; <strong>may be empty</strong>,
 *                 which selects an open (schema-on-read) source
 * @param body     the request body (for {@code POST} reads, e.g. a GraphQL/search
 *                 query); absent means no body
 * @param auth     an authentication shorthand; absent means none (or expressed via
 *                 a raw {@code Authorization} header)
 */
public record HttpSourceConfig(
        String url,
        HttpMethod method,
        Map<String, String> headers,
        Optional<ExtractSpec> extract,
        Optional<PaginateSpec> paginate,
        List<ColumnSpec> columns,
        Optional<String> body,
        Optional<AuthSpec> auth
) implements SourceConfig {

    public HttpSourceConfig {
        Objects.requireNonNull(url, "url");
        if (url.isBlank()) {
            throw new IllegalArgumentException("url must not be blank");
        }
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(extract, "extract");
        Objects.requireNonNull(paginate, "paginate");
        Objects.requireNonNull(columns, "columns");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(auth, "auth");
        headers = Map.copyOf(headers);
        columns = List.copyOf(columns);
    }

    /**
     * Back-compatible convenience constructor with a required (non-optional)
     * extract spec and no body/auth — retained so existing callers and fixtures
     * compile unchanged.
     *
     * @param url      the URL
     * @param method   the HTTP method
     * @param headers  static request headers
     * @param extract  the extract spec
     * @param paginate optional pagination mapping
     * @param columns  the column specifications (may be empty for an open source)
     */
    public HttpSourceConfig(String url, HttpMethod method, Map<String, String> headers,
                            ExtractSpec extract, Optional<PaginateSpec> paginate,
                            List<ColumnSpec> columns) {
        this(url, method, headers, Optional.of(extract), paginate, columns,
                Optional.empty(), Optional.empty());
    }

    /**
     * Returns {@code true} if this source declares no columns and is therefore an
     * open (schema-on-read) source whose rows are navigated by JSONPath.
     *
     * @return whether the source is open
     */
    public boolean isOpen() {
        return columns.isEmpty();
    }
}

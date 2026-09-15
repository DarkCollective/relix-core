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

import java.util.Objects;

/**
 * API-key authentication: the connector sends a named key either as a request
 * header or as a URL query parameter.
 *
 * <p>Syntax:
 * <pre>
 *   auth: apikey("X-API-Key", "${KEY}")        // header (default)
 *   auth: apikey(query("api_key"), "${KEY}")   // URL query parameter
 * </pre>
 *
 * @param name     the header name or query-parameter name; must not be blank
 * @param value    the key value (typically a {@code ${ENV}} reference, resolved
 *                 before parsing); must not be null
 * @param location whether {@code name}/{@code value} is sent as a header or a
 *                 query parameter; must not be null
 */
public record ApiKeyAuth(String name, String value, ApiKeyLocation location)
        implements AuthSpec {

    public ApiKeyAuth {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("apikey name must not be blank");
        }
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(location, "location");
    }

    /** Where an {@link ApiKeyAuth} key is placed in the request. */
    public enum ApiKeyLocation {
        /** Sent as a request header. */
        HEADER,
        /** Appended as a URL query parameter. */
        QUERY
    }
}

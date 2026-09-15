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

/**
 * HTTP request method for an {@link HttpSourceConfig}.
 *
 * <p>Relix is <strong>read-only by design</strong>: only the two methods that
 * fetch data are permitted. {@link #GET} is the ordinary read; {@link #POST} is
 * accepted <em>solely</em> as a read mechanism — many APIs (GraphQL, search
 * endpoints) carry the query in a request body and answer with the result set.
 * The mutating methods {@code PUT}/{@code PATCH}/{@code DELETE} are deliberately
 * absent, as is {@code HEAD} (which returns no body and so cannot produce rows).
 */
public enum HttpMethod {
    /** An ordinary HTTP read. */
    GET,
    /** A read whose query travels in the request body (e.g. GraphQL/search). */
    POST
}

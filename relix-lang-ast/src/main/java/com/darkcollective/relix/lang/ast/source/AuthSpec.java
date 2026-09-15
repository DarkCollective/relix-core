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
 * Sealed root for an HTTP source's authentication shorthand.
 *
 * <p>Authentication can always be expressed by hand as a raw header (for example
 * {@code headers: { "Authorization": "Bearer ${TOKEN}" }}); these shorthands
 * exist so the common schemes read clearly and so the connector — not the
 * script author — owns the encoding (base64 for basic, the {@code Bearer }
 * prefix, the header-vs-query placement for an API key).
 *
 * <p>Surface syntax (consistent with the {@code json(…)}/{@code query(…)} style):
 * <pre>
 *   auth: bearer("${TOKEN}")
 *   auth: basic("${USER}", "${PASS}")
 *   auth: apikey("X-API-Key", "${KEY}")        // sent as a header (default)
 *   auth: apikey(query("api_key"), "${KEY}")   // sent as a URL query parameter
 * </pre>
 *
 * <p>All string payloads may contain {@code ${ENV}} placeholders; these are
 * resolved against the active environment before the script is parsed, so a
 * secret never appears literally in the source.
 */
public sealed interface AuthSpec
        permits BearerAuth, BasicAuth, ApiKeyAuth {
}

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
import java.util.Optional;

/**
 * Maps a logical pagination concept to a URL query parameter.
 *
 * <p>Recognised logical names and their typical uses:
 * <ul>
 *   <li>{@code "limit"} — maximum number of rows per page.</li>
 *   <li>{@code "offset"} — zero-based row offset (for offset-based APIs).</li>
 *   <li>{@code "page"} — one-based page number (for page-based APIs).</li>
 *   <li>{@code "pagesize"} — rows per page (for page-based APIs).</li>
 * </ul>
 *
 * <p>Syntax: {@code limit: query("limit") [default: 100]}
 *
 * @param logicalName  the pagination role ({@code "limit"}, {@code "offset"},
 *                     {@code "page"}, or {@code "pagesize"}); must not be blank
 * @param paramName    the actual URL query-parameter name sent in the request;
 *                     must not be blank
 * @param defaultValue the value used when the query contains no {@code LIMIT} /
 *                     {@code OFFSET} node; absent means the parameter is omitted
 */
public record PaginateEntry(
        String logicalName,
        String paramName,
        Optional<Long> defaultValue
) {

    public PaginateEntry {
        Objects.requireNonNull(logicalName, "logicalName");
        if (logicalName.isBlank()) {
            throw new IllegalArgumentException("logicalName must not be blank");
        }
        Objects.requireNonNull(paramName, "paramName");
        if (paramName.isBlank()) {
            throw new IllegalArgumentException("paramName must not be blank");
        }
        Objects.requireNonNull(defaultValue, "defaultValue");
    }
}

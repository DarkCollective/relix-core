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
import java.util.Objects;
import java.util.Optional;

/**
 * Declares how an HTTP source's pagination maps to URL parameters.
 *
 * <p>When the query planner encounters a {@code LimitNode} or infers offset
 * information, it consults this spec to construct the correct URL parameters.
 *
 * <p>Syntax (offset-based API):
 * <pre>
 *   paginate: {
 *       limit:  query("limit")  [default: 100],
 *       offset: query("offset") [default: 0]
 *   }
 * </pre>
 *
 * <p>Syntax (page-based API):
 * <pre>
 *   paginate: {
 *       page:     query("page")     [default: 1],
 *       pagesize: query("per_page") [default: 50]
 *   }
 * </pre>
 *
 * @param entries the pagination parameter mappings; must not be null or empty
 */
public record PaginateSpec(List<PaginateEntry> entries) {

    public PaginateSpec {
        Objects.requireNonNull(entries, "entries");
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("PaginateSpec must have at least one entry");
        }
        entries = List.copyOf(entries);
    }

    /**
     * Finds the entry with the given logical name (e.g. {@code "limit"}).
     *
     * @param logicalName the logical name to search for
     * @return the matching entry, or empty if not declared
     */
    public Optional<PaginateEntry> entry(String logicalName) {
        return entries.stream()
                .filter(e -> e.logicalName().equals(logicalName))
                .findFirst();
    }
}

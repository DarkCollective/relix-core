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
 * Configuration for a named database <em>connection</em> — the JDBC coordinates
 * shared by every table referenced through it.
 *
 * <p>Introduced by a {@code connection NAME from database { … }} declaration.
 * Unlike a {@link DatabaseSourceConfig} (which names a single table inline), a
 * connection is referenced by many tables, either as {@code conn.table} or via a
 * {@code source T from conn { … }} binding.
 *
 * <p>{@code url}, {@code user}, and {@code password} may contain {@code ${VAR}}
 * environment references (substituted before parsing).  {@code user} and
 * {@code password} are optional (some drivers, e.g. embedded H2, need neither);
 * {@code dialect} is an optional hint that later drives SQL generation for
 * pushdown.
 *
 * @param url      the JDBC connection URL; must not be blank
 * @param user     the connection user, if any
 * @param password the connection password, if any
 * @param dialect  the SQL dialect hint (e.g. {@code "postgres"}), if given
 */
public record DatabaseConnectionConfig(
        String url,
        Optional<String> user,
        Optional<String> password,
        Optional<String> dialect
) {
    public DatabaseConnectionConfig {
        Objects.requireNonNull(url, "url");
        if (url.isBlank()) {
            throw new IllegalArgumentException("connection url must not be blank");
        }
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(dialect, "dialect");
    }
}

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
package com.darkcollective.relix.lang.ast;

import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.lang.ast.source.SourceConfig;

import java.util.Objects;

/**
 * A {@code source} declaration — names a virtual relation backed by an
 * external data source.
 *
 * <p>Syntax:
 * <pre>
 *   source current_weather from http {
 *       url:    "https://api.example.com/weather",
 *       method: GET,
 *       headers: { "X-API-Key": "${WEATHER_API_KEY}" },
 *       extract: json("$."),
 *       schema: {
 *           city: in  STRING as query("q") [required],
 *           temp: out NUMBER at "$.main.temp"
 *       }
 *   };
 * </pre>
 *
 * <p>Multiple {@code source} declarations with the same {@link #name()} but
 * different {@code in}-column sets represent overloads of the same virtual
 * relation — the query planner selects the overload whose required parameters
 * are satisfied by equality predicates in the query.
 *
 * <p>The {@code exported} flag is {@code false} when the declaration is
 * prefixed with {@code private} — the source is usable within the file but
 * not importable by other files.
 *
 * @param exported  {@code true} if the source is importable by other files
 * @param name      the relation name; must not be blank
 * @param config    the transport and schema configuration; must not be null
 * @param location  the source location of this statement; never null
 */
public record SourceDeclaration(
        boolean exported,
        String name,
        SourceConfig config,
        SourceLocation location
) implements Statement {

    public SourceDeclaration {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public SourceDeclaration(boolean exported, String name, SourceConfig config) {
        this(exported, name, config, SourceLocation.UNKNOWN);
    }

    /** Creates an exported source declaration (the common case). */
    public static SourceDeclaration of(String name, SourceConfig config) {
        return new SourceDeclaration(true, name, config, SourceLocation.UNKNOWN);
    }
}

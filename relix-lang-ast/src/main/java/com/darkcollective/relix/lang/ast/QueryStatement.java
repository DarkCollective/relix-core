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

import java.util.Objects;

/**
 * A {@code query} statement — produces output from a named symbol or an
 * inline relational algebra expression.
 *
 * <p>Syntax forms:
 * <pre>
 *   query USCities;
 *   query { π name, country (Cities) };
 * </pre>
 *
 * <p>Multiple {@code query} statements in a single script produce sequential
 * outputs labelled by the target name (or a generated label for inline
 * expressions).
 *
 * @param target   the query target — a name reference or an inline expression
 * @param location the source location of this statement; never null
 */
public record QueryStatement(QueryTarget target, SourceLocation location) implements Statement {

    public QueryStatement {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public QueryStatement(QueryTarget target) {
        this(target, SourceLocation.UNKNOWN);
    }
}

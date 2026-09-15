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
 * An assignment statement — gives a name to a relational algebra expression or
 * an inline table.
 *
 * <p>Syntax forms:
 * <pre>
 *   // Named view (query assignment body)
 *   ActiveUsers := { σ status = "active" (Users) };
 *
 *   // Inline Markdown table
 *   Cities := [
 *   | name         | country |
 *   |--------------|---------|
 *   | Chicago, IL  | US      |
 *   | London       | UK      |
 *   ];
 *
 *   // Inline CSV table
 *   Cities := csv[
 *       name, country
 *       "Chicago, IL", US
 *       London, UK
 *   ];
 *
 *   // Private — not importable by other files
 *   private Temp := { σ deleted = false (RawData) };
 * </pre>
 *
 * @param exported {@code true} if this name is importable by other files;
 *                 {@code false} when the {@code private} modifier is present
 * @param name     the symbol name; must not be blank
 * @param body     the right-hand side (RA expression or inline table)
 * @param location the source location of this statement; never null
 */
public record AssignmentStatement(
        boolean exported,
        String name,
        AssignmentBody body,
        SourceLocation location
) implements Statement {

    public AssignmentStatement {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public AssignmentStatement(boolean exported, String name, AssignmentBody body) {
        this(exported, name, body, SourceLocation.UNKNOWN);
    }

    /** Creates an exported assignment (the common case). */
    public static AssignmentStatement of(String name, AssignmentBody body) {
        return new AssignmentStatement(true, name, body, SourceLocation.UNKNOWN);
    }
}

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

import java.util.List;
import java.util.Objects;

/**
 * An {@code import} statement — brings symbols from another {@code .relix}
 * file into the current namespace.
 *
 * <p>Syntax forms and their resulting records:
 * <pre>
 *   import './common.relix';
 *   → ImportStatement(BULK, [], "./common.relix")
 *
 *   import { Users, Orders } from './db.relix';
 *   → ImportStatement(UNQUALIFIED, ["Users","Orders"], "./db.relix")
 *
 *   import source current_weather from './weather-api.relix';
 *   → ImportStatement(SOURCE, ["current_weather"], "./weather-api.relix")
 *
 *   import relation Users, Orders from './db.relix';
 *   → ImportStatement(RELATION, ["Users","Orders"], "./db.relix")
 *
 *   import function tax, discount from './calc.relix';
 *   → ImportStatement(FUNCTION, ["tax","discount"], "./calc.relix")
 * </pre>
 *
 * <p>The {@code sourcePath} is always relative to the importing file's directory.
 * Circular import detection is handled by the semantic layer.
 *
 * @param kind       the declared import kind; {@link ImportKind#BULK} when no
 *                   names are specified
 * @param names      the symbols to import; empty for {@link ImportKind#BULK}
 * @param sourcePath the file path of the target {@code .relix} file; must not
 *                   be blank
 * @param location   the source location of this statement; never null
 */
public record ImportStatement(
        ImportKind kind,
        List<String> names,
        String sourcePath,
        SourceLocation location
) implements Statement {

    public ImportStatement {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(names, "names");
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(location, "location");
        if (sourcePath.isBlank()) {
            throw new IllegalArgumentException("sourcePath must not be blank");
        }
        names = List.copyOf(names);
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public ImportStatement(ImportKind kind, List<String> names, String sourcePath) {
        this(kind, names, sourcePath, SourceLocation.UNKNOWN);
    }
}

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
package com.darkcollective.relix.ast;

import java.util.Objects;

/**
 * The source location of an AST node — file path, 1-based line, and 1-based
 * column of the first character of the construct.
 *
 * <p>All three components are carried together so that nodes remain fully
 * self-describing after semantic analysis assembles a {@code SemanticModel}
 * from multiple files.
 *
 * <p>{@link #UNKNOWN} is the sentinel used when a node was constructed
 * programmatically (e.g. in tests) without access to a source position.
 *
 * @param filePath the path of the source file; never null
 * @param line     1-based source line (0 = no position available)
 * @param column   1-based source column (0 = no position available)
 */
public record SourceLocation(String filePath, int line, int column) {

    /**
     * Sentinel used when no real source location is available.
     */
    public static final SourceLocation UNKNOWN = new SourceLocation("<unknown>", 0, 0);

    public SourceLocation {
        Objects.requireNonNull(filePath, "filePath");
        if (line < 0) throw new IllegalArgumentException("line must be >= 0");
        if (column < 0) throw new IllegalArgumentException("column must be >= 0");
    }

    /**
     * Returns a human-readable {@code filePath:line:column} string.
     * When line is 0 the positional part is omitted.
     */
    @Override
    public String toString() {
        return line == 0 ? filePath : filePath + ":" + line + ":" + column;
    }
}

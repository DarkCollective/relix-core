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
package com.darkcollective.relix.semantic;

import java.util.Objects;

/**
 * A single diagnostic produced during semantic analysis of a {@code .relix} script.
 *
 * <p>Every error carries the path of the file it originated from together with
 * the line and column of the problematic source construct.  Line and column are
 * 1-based; a value of {@code 0} indicates that no meaningful position is available
 * (e.g. for file-level errors such as a missing import file).
 *
 * <p>Factory methods cover the two common cases:
 * <pre>
 *   SemanticError.error("./api.relix", 12, 5, "Unknown relation 'Foo'");
 *   SemanticError.warning("./api.relix", 3, 1, "Unused import 'bar'");
 * </pre>
 *
 * @param filePath the path of the source file; must not be null
 * @param line     the 1-based source line (0 = no position)
 * @param column   the 1-based source column (0 = no position)
 * @param message  a human-readable description of the problem; must not be blank
 * @param severity whether this is an error or a warning
 */
public record SemanticError(
        String filePath,
        int line,
        int column,
        String message,
        Severity severity
) {
    public SemanticError {
        Objects.requireNonNull(filePath, "filePath");
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        Objects.requireNonNull(severity, "severity");
    }

    /**
     * Creates an {@link Severity#ERROR}-severity diagnostic.
     *
     * @param filePath the source file path
     * @param line     1-based line number (0 = no position)
     * @param column   1-based column number (0 = no position)
     * @param message  the error description; must not be blank
     * @return a new {@code SemanticError}
     */
    public static SemanticError error(String filePath, int line, int column, String message) {
        return new SemanticError(filePath, line, column, message, Severity.ERROR);
    }

    /**
     * Creates a {@link Severity#WARNING}-severity diagnostic.
     *
     * @param filePath the source file path
     * @param line     1-based line number (0 = no position)
     * @param column   1-based column number (0 = no position)
     * @param message  the warning description; must not be blank
     * @return a new {@code SemanticError}
     */
    public static SemanticError warning(String filePath, int line, int column, String message) {
        return new SemanticError(filePath, line, column, message, Severity.WARNING);
    }

    /**
     * Returns a human-readable representation in the form
     * {@code <file>:<line>:<col>: <severity>: <message>}.
     */
    @Override
    public String toString() {
        return filePath + ":" + line + ":" + column + ": "
                + severity.name().toLowerCase() + ": " + message;
    }
}

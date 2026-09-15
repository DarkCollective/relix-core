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

/**
 * Thrown when a frontend cannot turn its input into a {@link Script}.
 *
 * <p>The engine's front door is the AST: a {@code Script} is the
 * currency, and <em>how</em> one is produced — the {@code .relix} text grammar,
 * a serialized query format, a programmatic builder — is a frontend concern the
 * core does not name. This exception is the core-level counterpart: the seam
 * types that ask a frontend for a {@code Script} (notably
 * {@code ScriptLoader.load}) declare <em>this</em> type, and each frontend
 * throws a subtype carrying its own diagnostic detail.
 *
 * <p>This is a {@link RuntimeException} so that producing an AST does not force
 * checked-exception handling at every call site.
 *
 * <p>The message is used verbatim — subclasses that decorate it with position
 * information (as the {@code .relix} grammar's exception does) must do so
 * themselves before calling {@code super}.
 */
public class ScriptParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int line;
    private final int column;

    /**
     * Constructs an exception with no known source position.
     *
     * @param message a human-readable description of the problem
     */
    public ScriptParseException(String message) {
        this(message, 0, 0);
    }

    /**
     * Constructs an exception at a known source position.
     *
     * @param message a human-readable description of the problem
     * @param line    1-based source line, or 0 when the position is unknown
     * @param column  1-based source column, or 0 when the position is unknown
     */
    public ScriptParseException(String message, int line, int column) {
        super(message);
        this.line = line;
        this.column = column;
    }

    /**
     * Returns the 1-based source line the failure occurred at, or {@code 0} when
     * the producing frontend has no line/column notion (a builder API) or could
     * not attribute one.
     *
     * @return the 1-based line, or 0 if unknown
     */
    public final int line() {
        return line;
    }

    /**
     * Returns the 1-based source column the failure occurred at, or {@code 0}
     * when unknown; see {@link #line()}.
     *
     * @return the 1-based column, or 0 if unknown
     */
    public final int column() {
        return column;
    }
}

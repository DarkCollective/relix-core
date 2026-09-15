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
package com.darkcollective.relix.lang;

import com.darkcollective.relix.lang.ast.ScriptParseException;

/**
 * Thrown when the {@link ScriptParser} encounters a syntax error in a
 * {@code .relix} source file.
 *
 * <p>The exception message includes the human-readable description of the
 * unexpected token together with the source location.  Use {@link #line()} and
 * {@link #column()} (inherited from {@link ScriptParseException}) for
 * programmatic access to the error position.
 *
 * <p>This is the {@code .relix} text grammar's specialisation of the core-level
 * {@link ScriptParseException} — the type the engine's "where does a
 * {@code Script} come from" seam declares.  Callers that do not care
 * which frontend failed should catch the supertype.
 *
 * <p>It is a {@link RuntimeException} so that the parser API does not require
 * checked-exception handling at every call site.
 */
public final class LangParseException extends ScriptParseException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs a parse exception with a descriptive message.
     *
     * @param message a human-readable description of the problem
     * @param line    1-based source line of the offending token
     * @param column  1-based source column of the offending token
     */
    public LangParseException(String message, int line, int column) {
        super(message + " (line " + line + ", col " + column + ")", line, column);
    }

    /**
     * Constructs a parse exception from the offending token.
     *
     * @param message human-readable problem description
     * @param token   the token at which parsing failed
     */
    public LangParseException(String message, LangToken token) {
        this(message + "; found " + token.describe(), token.line(), token.column());
    }
}

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
 * <p>The exception message is the human-readable description of the problem
 * followed by its source location, stated once as {@code (line N, col M)} — the
 * message is often shown on its own, and without the position it does not say
 * where the error is.  Use {@link #line()} and {@link #column()} (inherited from
 * {@link ScriptParseException}) for programmatic access to the same position;
 * {@link #suffix(int, int)} is the exact text appended, for a caller that already
 * reports the position and wants the message without it.
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
        super(message + suffix(line, column), line, column);
    }

    /**
     * Constructs a parse exception from the offending token.
     *
     * @param message human-readable problem description
     * @param token   the token at which parsing failed
     */
    public LangParseException(String message, LangToken token) {
        this(message + "; found " + found(token), token.line(), token.column());
    }

    /**
     * Returns the position text the message ends with, {@code " (line N, col M)"}.
     *
     * @param line   1-based source line
     * @param column 1-based source column
     * @return the suffix a message at that position carries
     */
    public static String suffix(int line, int column) {
        return " (line " + line + ", col " + column + ")";
    }

    /**
     * Returns the message without the {@link #suffix(int, int)} it ends with.
     *
     * @return the description of the problem, without its position
     */
    @Override
    public String description() {
        // Every constructor appends the suffix, so the message always ends with it.
        String message = getMessage();
        return message.substring(0, message.length() - suffix(line(), column()).length());
    }

    /** The token as the message names it — without a position, which the suffix states. */
    private static String found(LangToken token) {
        return token.type() == LangTokenType.EOF ? "end of input" : "'" + token.value() + "'";
    }
}

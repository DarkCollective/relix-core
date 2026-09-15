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
package com.darkcollective.relix.parser;

import com.darkcollective.relix.parser.Token;
import com.darkcollective.relix.parser.TokenType;

/**
 * Thrown by {@link RelAlgebraParser} when the input does not conform to the
 * relational algebra grammar.
 *
 * <p>In addition to the standard exception message, a {@code ParseException}
 * exposes the precise source position ({@link #line()}, {@link #column()}) and
 * the offending token ({@link #found()}) so callers can produce IDE-quality
 * diagnostics.  The {@link #diagnostic()} method returns a multi-line string
 * that reproduces the source line with a {@code ^} caret under the error position.
 *
 * <p>Example message:
 * <pre>
 *   Expected ')' at line 3, column 12; found ','
 *   π name, (Users)
 *              ^
 * </pre>
 */
public final class ParseException extends RuntimeException {
    private final int line;
    private final int column;
    private final String found;
    private final String diagnostic;

    public ParseException(String message, String input, Token token) {
        super(message + " at line " + token.line() + ", column " + token.column()
                + "; found " + describe(token));
        this.line = token.line();
        this.column = token.column();
        this.found = describe(token);
        this.diagnostic = buildDiagnostic(input, token);
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    public String found() {
        return found;
    }

    public String diagnostic() {
        return diagnostic;
    }

    private static String describe(Token token) {
        if (token.type() == TokenType.EOF) {
            return "end of input";
        }

        return "'" + token.lexeme() + "'";
    }

    private static String buildDiagnostic(String input, Token token) {
        String[] lines = input.split("\\R", -1);
        String sourceLine = token.line() <= lines.length ? lines[token.line() - 1] : "";
        return sourceLine + System.lineSeparator()
                + " ".repeat(Math.max(0, token.column() - 1))
                + "^";
    }
}

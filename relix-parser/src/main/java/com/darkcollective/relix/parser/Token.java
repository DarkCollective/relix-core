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

/**
 * An immutable token produced by the {@link Lexer}.
 *
 * <p>Each token records its syntactic category ({@link #type}), the raw source
 * text it was scanned from ({@link #lexeme}), an optional processed literal
 * value ({@link #literal} — used for string tokens with escape sequences
 * decoded), and the 1-based source position where it begins.
 *
 * @param type    the syntactic category of this token
 * @param lexeme  the raw characters from the source input
 * @param literal the processed value (e.g. decoded string body); may be null
 *                for tokens that carry no additional value
 * @param line    1-based line number within the source text
 * @param column  1-based column number within the line
 */
public record Token(
        TokenType type,
        String lexeme,
        String literal,
        int line,
        int column
) {
}

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

/**
 * A single token produced by the {@link LangLexer}.
 *
 * @param type   the token category
 * @param value  the raw lexeme text as it appeared in the source (for
 *               {@link LangTokenType#STRING_LIT} this is the content without
 *               the surrounding quotes, with escape sequences resolved)
 * @param line   1-based source line number of the first character
 * @param column 1-based source column number of the first character
 */
public record LangToken(LangTokenType type, String value, int line, int column) {

    /**
     * Returns a human-readable description of this token suitable for use in
     * error messages (e.g. {@code "'import' at line 3, col 1"}).
     */
    public String describe() {
        if (type == LangTokenType.EOF) {
            return "end of input";
        }
        return "'" + value + "' at line " + line + ", col " + column;
    }
}

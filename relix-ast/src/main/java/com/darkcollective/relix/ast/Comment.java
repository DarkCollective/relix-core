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
 * A comment as it was written in a script, and where.
 *
 * <p>The lexers skip comments, so nothing above them reads one; a parser keeps them only
 * so that a printer can put them back. The text is the comment exactly as written,
 * markers included: {@code -- keep the large orders}, or {@code /* … *}{@code /} with
 * every line it spans.
 *
 * @param text     the comment, from its opening marker to the end of the line for
 *                 {@code --} (the newline excluded) or to its closing marker for
 *                 {@code /* … *}{@code /}; never null
 * @param location where its opening marker is; never null
 * @since 1.0
 */
public record Comment(String text, SourceLocation location) {

    /**
     * Checks both components.
     *
     * @since 1.0
     */
    public Comment {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(location, "location");
    }

    /** {@return whether this runs to the end of its line, so code cannot follow it there} */
    public boolean isLineComment() {
        return text.startsWith("--");
    }
}

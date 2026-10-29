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

import java.util.Objects;

/**
 * A slip the parser accepted in a script, and says what to write instead.
 *
 * <p>A few mistakes are common and unambiguous enough to read as what was meant rather
 * than refuse: a {@code ;} inside a statement's braces, {@code query { R; };}, and a
 * view written without its braces, {@code V := PROJECT a (R);}. The script parses as
 * if it had been written correctly, and analysis reports each slip as a warning.
 *
 * @param message  what was accepted, and how it is written; never null
 * @param location where the slip is; never null
 * @since 1.0
 */
public record ParseWarning(String message, SourceLocation location) {

    /**
     * Checks both components.
     *
     * @since 1.0
     */
    public ParseWarning {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(location, "location");
    }
}

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
package com.darkcollective.relix.embed;

import java.util.Objects;

/**
 * A run of {@code .relix} text and what kind of token it is, as {@link Relix#tokens}
 * reports it.
 *
 * <p>Offsets are 0-based {@code char} indices into the text that was tokenized:
 * {@code start} inclusive, {@code end} exclusive, so {@code text.substring(start, end)}
 * is the token's text.
 *
 * @param start the offset of the token's first character; not negative
 * @param end the offset just past its last character; not less than {@code start}
 * @param kind what the token is; never null
 * @since 1.0
 */
public record Token(int start, int end, TokenKind kind) {

    /**
     * Checks the offsets and the kind.
     *
     * @throws IllegalArgumentException if {@code start} is negative or {@code end} is
     *         less than {@code start}
     * @since 1.0
     */
    public Token {
        if (start < 0) {
            throw new IllegalArgumentException("start must be non-negative, got " + start);
        }
        if (end < start) {
            throw new IllegalArgumentException(
                    "end (" + end + ") must be >= start (" + start + ")");
        }
        Objects.requireNonNull(kind, "kind");
    }

    /**
     * The token's length in {@code char}s.
     *
     * @return {@code end - start}
     * @since 1.0
     */
    public int length() {
        return end - start;
    }
}

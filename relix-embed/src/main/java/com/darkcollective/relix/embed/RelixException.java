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

/**
 * Thrown when text will not parse, or a relation will not analyse against its session.
 *
 * <p>Unchecked, and the default across the API: a caller writing a query in Java has made
 * a mistake, and a stack trace at the call site is what locates it. The exception is
 * {@code Relix.validate}, which returns {@link Diagnostic}s instead — for the caller
 * assembling text or trees from <em>user</em> input, where a bad query is an expected
 * outcome to render rather than a bug to raise.
 *
 * @since 1.0
 */
public class RelixException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with the given message.
     *
     * @param message what went wrong
     * @since 1.0
     */
    public RelixException(String message) {
        super(message);
    }

    /**
     * Creates an exception with the given message and cause.
     *
     * @param message what went wrong
     * @param cause   the underlying failure
     * @since 1.0
     */
    public RelixException(String message, Throwable cause) {
        super(message, cause);
    }
}

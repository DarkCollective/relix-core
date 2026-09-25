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
package com.darkcollective.relix.processor;

/**
 * Thrown when a query cannot be evaluated at run time.
 *
 * <p>Examples: unknown column name, type mismatch in arithmetic, division by
 * zero, unknown function name, wrong function arity, a connector's configuration
 * missing a required key. A connector reports a failure reading its source by throwing
 * it too; the embedding API reports it to a caller as the cause of its own exception.
 *
 * <p>This is an unchecked exception because evaluation errors result from
 * schema mismatches or semantic bugs that should have been caught during
 * {@code relix-semantic} analysis; they are not expected in normal operation.
 */
public final class EvaluationException extends RuntimeException {

    /**
     * Constructs an {@code EvaluationException} with the given detail message.
     *
     * @param message the detail message
     */
    public EvaluationException(String message) {
        super(message);
    }

    /**
     * Constructs an {@code EvaluationException} with a detail message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public EvaluationException(String message, Throwable cause) {
        super(message, cause);
    }
}

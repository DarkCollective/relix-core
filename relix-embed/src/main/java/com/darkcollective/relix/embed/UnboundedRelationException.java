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
 * Thrown when a terminal that must collect every row is asked for one that never ends.
 *
 * <p>{@link Relation#toList()} and {@link Relation#run()} return when the rows are all in
 * hand, so over an unbounded relation they would not return at all. The refusal is made
 * before anything runs, and the remedy is in the caller's hands: bound the relation with
 * {@code limit(n)}, or take it lazily with {@link Relation#stream()}, which is what an
 * endless relation is for.
 *
 * <p>The planner raises the same refusal for its own reason: an operator that must buffer
 * its whole input — a sort, a grouping — cannot do so over an input that never ends, and it
 * says so before reading a row. Both are the one fact a caller acts on, so both arrive as
 * this type rather than as two.
 *
 * <p>Distinct from {@link QueryExecutionException} because nothing failed. The query is
 * sound and the engine is willing; it is the shape of the question that cannot be answered
 * by a list.
 *
 * @since 1.0
 */
public class UnboundedRelationException extends RelixException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with the given message.
     *
     * @param message what went wrong
     * @since 1.0
     */
    public UnboundedRelationException(String message) {
        super(message);
    }

    /**
     * Creates an exception with the given message and cause.
     *
     * @param message what went wrong
     * @param cause   the underlying refusal
     * @since 1.0
     */
    public UnboundedRelationException(String message, Throwable cause) {
        super(message, cause);
    }
}

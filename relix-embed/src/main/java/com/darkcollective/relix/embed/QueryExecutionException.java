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
 * Thrown when a query started and did not finish.
 *
 * <p>The counterpart to a plain {@link RelixException}, which says the query was never
 * runnable: this one says it was, and something outside the query gave way — a database
 * refusing connections, an HTTP endpoint answering 500, a file that moved, a value the
 * engine could not evaluate when it reached it.
 *
 * <p>The distinction is the one a caller acts on. A malformed query is a defect in the
 * program and retrying it changes nothing; a backend that is down is a fact about the
 * world at one moment, and retrying, failing over or reporting are all reasonable.
 *
 * <p>The message is the underlying failure's own, so nothing is buried by the wrapping:
 * for a source it names the source, and the cause carries the driver's exception for a
 * caller that wants to look further.
 *
 * <p>It arrives from a collecting terminal as that terminal fails, and from
 * {@link Relation#stream()} <em>while the caller is iterating</em> — a lazy stream does
 * its work when pulled, so a failure reaches the loop rather than the call that opened it.
 *
 * @since 1.0
 */
public class QueryExecutionException extends RelixException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with the given message and cause.
     *
     * <p>There is no message-only form. Something always gave way for this to be raised,
     * and that something is worth keeping: a constructor that discarded it would make the
     * driver's own exception unreachable from the one the caller catches.
     *
     * @param message what went wrong
     * @param cause   the underlying failure
     * @since 1.0
     */
    public QueryExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}

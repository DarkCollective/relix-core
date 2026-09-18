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
 * <h2>The root of everything this API throws</h2>
 *
 * <p>Catching this catches all of it. That is a claim a caller writes code against, so it
 * is one the engine's own exception types cannot be allowed to break: a query is executed
 * by modules that know nothing of this package, and their failures are restated here
 * rather than passed through, the same adaptation that turns a {@code Row} into a
 * {@link Tuple}. The original is kept as the cause and its message is carried unchanged.
 *
 * <p>Two subtypes say what kind of wrong it is, because a caller acts on the difference:
 *
 * <ul>
 *   <li>{@link QueryExecutionException} — the query ran and something outside it gave way.
 *       A fact about the world at one moment, so retrying, failing over or alerting are all
 *       reasonable.</li>
 *   <li>{@link UnboundedRelationException} — a collecting terminal was asked for a relation
 *       that never ends, or the planner met an operator that would have to buffer one.
 *       Nothing failed; bound it, or take it with {@code stream}.</li>
 * </ul>
 *
 * <p>This type raised on its own means the query was never runnable — it would not parse,
 * it names something nothing declares, or the session is closed. Retrying changes nothing;
 * the program has to.
 *
 * <p>What is <em>not</em> restated is a programming error in the call itself. A null
 * argument is an {@code NullPointerException} from the null check that found it, because
 * that is what a Java caller expects and wrapping it would say the engine had an opinion
 * about it.
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

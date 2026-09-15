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
package com.darkcollective.relix.function;

import java.util.List;
import java.util.Optional;

/**
 * How a function call is written in a backend that could evaluate it itself.
 *
 * <p>The engine keeps everything structural — walking the expression tree, resolving
 * column references, quoting identifiers — and hands over the arguments already
 * rendered as text. A spelling therefore does nothing but assemble a string:
 *
 * <pre>{@code
 * (target, args) -> target.isFamily(PushdownTarget.SQL)
 *         ? Optional.of("upper(" + args.get(0) + ")")
 *         : Optional.empty();
 * }</pre>
 *
 * <p>Returning empty <em>declines</em>, and declining is always safe: the engine
 * evaluates the call itself instead. A spelling that cannot be written for one backend,
 * or for one argument count, says so by returning empty for that case rather than
 * emitting text the backend would misread.
 *
 * <p>The rendered arguments are already in the target's syntax, so a MongoDB spelling
 * composes JSON fragments and a SQL one composes SQL. A function whose spelling differs
 * per dialect branches on {@link PushdownTarget#variant()}.
 */
@FunctionalInterface
public interface PushdownSpelling {

    /** A spelling that declines every backend — the default for a function with none. */
    PushdownSpelling NONE = (target, renderedArguments) -> Optional.empty();

    /**
     * Renders a call for one backend.
     *
     * @param target            the backend being rendered for
     * @param renderedArguments the call's arguments, already rendered in that backend's
     *                          syntax, in call order
     * @return the rendered call, or empty to decline — in which case the engine
     *         evaluates the call itself
     */
    Optional<String> render(PushdownTarget target, List<String> renderedArguments);
}

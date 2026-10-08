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

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The root AST node for a parsed {@code .relix} script file.
 *
 * <p>A script is an ordered sequence of {@link Statement}s, optionally prefixed
 * by a {@code namespace} declaration.  The namespace defaults to {@code "default"}
 * if absent.
 *
 * <p>Example source and its AST:
 * <pre>
 *   namespace weather;
 *
 *   env from './.relix-env.json' using 'development';
 *   import source current_weather from './weather-api.relix';
 *   query { σ city = "Chicago, IL" (current_weather) };
 * </pre>
 * produces:
 * <pre>{@code
 *   Script(
 *     namespace = Optional.of("weather"),
 *     statements = [
 *       EnvStatement(...),
 *       ImportStatement(SOURCE, ["current_weather"], "./weather-api.relix"),
 *       QueryStatement(ExpressionQueryTarget(...))
 *     ]
 *   )
 * }</pre>
 *
 * @param namespace  the declared namespace, or empty if the {@code namespace}
 *                   keyword was absent (the semantic layer treats absent as
 *                   {@code "default"})
 * @param statements the ordered list of top-level statements; never null,
 *                   may be empty
 * @param comments   the comments the parsed text carried, placed by the statements
 *                   they were written next to, for {@link ScriptPrinter} to put back;
 *                   {@link ScriptComments#NONE} for a script that was not parsed
 */
public record Script(Optional<String> namespace, List<Statement> statements,
                     ScriptComments comments) {

    public Script {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(statements, "statements");
        Objects.requireNonNull(comments, "comments");
        statements = List.copyOf(statements);
    }

    /**
     * A script with no comments: one built from statements rather than parsed.
     *
     * @param namespace  the declared namespace, or empty
     * @param statements the statements, in order
     */
    public Script(Optional<String> namespace, List<Statement> statements) {
        this(namespace, statements, ScriptComments.NONE);
    }
}

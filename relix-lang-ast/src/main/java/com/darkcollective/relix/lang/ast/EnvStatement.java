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
import java.util.Optional;

/**
 * An {@code env} statement — declares the environment file and active profile
 * used for credential and configuration variable resolution.
 *
 * <p>Syntax forms:
 * <pre>
 *   env from './.relix-env.json' using 'development';
 *   env from './.relix-env.json';                      // activeEnv absent
 *   env using 'production';                             // filePath absent (use default path)
 *   env;                                                // both absent (all defaults)
 * </pre>
 *
 * <p>When {@link #filePath()} is absent the runtime looks for
 * {@code .relix-env.json} in the same directory as the script.  When
 * {@link #activeEnv()} is absent the environment is selected by the
 * {@code --env} CLI flag or defaults to {@code "development"}.
 *
 * <p>{@code ${VAR}} placeholders in the environment file are resolved against
 * OS environment variables.
 *
 * @param filePath  path to the JSON environment file; absent uses the default
 * @param activeEnv name of the active environment profile within the file;
 *                  absent defers to the CLI flag or {@code "development"}
 * @param location  the source location of this statement; never null
 */
public record EnvStatement(
        Optional<String> filePath,
        Optional<String> activeEnv,
        SourceLocation location
) implements Statement {

    public EnvStatement {
        Objects.requireNonNull(filePath, "filePath");
        Objects.requireNonNull(activeEnv, "activeEnv");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public EnvStatement(Optional<String> filePath, Optional<String> activeEnv) {
        this(filePath, activeEnv, SourceLocation.UNKNOWN);
    }

    /** Returns an env statement with no path or environment specified (all defaults). */
    public static EnvStatement defaults() {
        return new EnvStatement(Optional.empty(), Optional.empty());
    }
}

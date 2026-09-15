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
package com.darkcollective.relix.symbol;

/**
 * Records whether a symbol was provided by the runtime or declared by a user.
 *
 * <p>Provenance affects display (error messages distinguish built-in vs user
 * symbols), default shadow policy, and tooling (e.g. documentation generators
 * may suppress built-in symbols from user-facing output).
 */
public enum Provenance {

    /**
     * The symbol is supplied by the relix runtime — a built-in function or a
     * predefined relation.  Built-in symbols default to
     * {@link ShadowPolicy#FORBIDDEN} to prevent accidental redefinition.
     */
    BUILTIN,

    /**
     * The symbol was declared by the user in a {@code .relix} source file.
     * User symbols default to {@link ShadowPolicy#PERMITTED}.
     */
    USER
}

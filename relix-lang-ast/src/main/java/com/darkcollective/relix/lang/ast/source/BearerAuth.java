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
package com.darkcollective.relix.lang.ast.source;

import java.util.Objects;

/**
 * Bearer-token authentication: the connector sends
 * {@code Authorization: Bearer <token>}.
 *
 * <p>Syntax: {@code auth: bearer("${TOKEN}")}
 *
 * @param token the bearer token (typically a {@code ${ENV}} reference, resolved
 *              before parsing); must not be blank
 */
public record BearerAuth(String token) implements AuthSpec {

    public BearerAuth {
        Objects.requireNonNull(token, "token");
        if (token.isBlank()) {
            throw new IllegalArgumentException("bearer token must not be blank");
        }
    }
}

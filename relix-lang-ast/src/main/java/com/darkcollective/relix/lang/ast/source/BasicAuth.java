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
 * HTTP basic authentication: the connector sends
 * {@code Authorization: Basic <base64(username:password)>}.
 *
 * <p>Syntax: {@code auth: basic("${USER}", "${PASS}")}
 *
 * @param username the user name (may be a {@code ${ENV}} reference); must not be null
 * @param password the password (may be a {@code ${ENV}} reference); must not be null
 */
public record BasicAuth(String username, String password) implements AuthSpec {

    public BasicAuth {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
    }
}

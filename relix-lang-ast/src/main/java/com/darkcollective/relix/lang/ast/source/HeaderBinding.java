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
 * Binds an {@code IN} column to an HTTP request header.
 *
 * <p>Syntax: {@code as header("X-City")}
 *
 * <p>At request time, the equality predicate value is sent as the named
 * HTTP header.  This is useful for APIs that accept filter criteria in
 * headers rather than URL parameters.
 *
 * @param headerName the HTTP header name; must not be blank
 */
public record HeaderBinding(String headerName) implements ColumnBinding {

    public HeaderBinding {
        Objects.requireNonNull(headerName, "headerName");
        if (headerName.isBlank()) {
            throw new IllegalArgumentException("headerName must not be blank");
        }
    }
}

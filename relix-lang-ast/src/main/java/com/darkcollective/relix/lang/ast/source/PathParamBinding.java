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
 * Binds an {@code IN} column to a URL path segment.
 *
 * <p>Syntax: {@code as path("id")}
 *
 * <p>At request time, the URL template placeholder {@code {id}} is replaced by
 * the column value from the equality predicate.
 *
 * @param paramName the path-template parameter name (matching the placeholder
 *                  in the URL, e.g. {@code "id"} for {@code /items/{id}});
 *                  must not be blank
 */
public record PathParamBinding(String paramName) implements ColumnBinding {

    public PathParamBinding {
        Objects.requireNonNull(paramName, "paramName");
        if (paramName.isBlank()) {
            throw new IllegalArgumentException("paramName must not be blank");
        }
    }
}

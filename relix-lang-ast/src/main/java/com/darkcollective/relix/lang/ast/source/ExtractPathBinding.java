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
 * Binds an {@code OUT} column to a JSONPath expression within the response body.
 *
 * <p>Syntax: {@code at "$.main.temp"}
 *
 * <p>At response-processing time the JSONPath expression is evaluated against
 * each extracted JSON object and the result assigned to the column.
 *
 * @param path the JSONPath expression; must not be blank
 */
public record ExtractPathBinding(String path) implements ColumnBinding {

    public ExtractPathBinding {
        Objects.requireNonNull(path, "path");
        if (path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
    }
}

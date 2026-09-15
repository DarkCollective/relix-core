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
 * Extracts tabular data from a JSON response body.
 *
 * <p>The {@link #jsonPath()} expression locates the array of objects that
 * becomes the rows of the relation.  Use {@code "$."} for a top-level object
 * (single-row result), {@code "$.items[*]"} for an array field, or
 * {@code "$[*]"} for a root-level array.
 *
 * <p>Syntax: {@code extract: json("$.items[*]")}
 *
 * @param jsonPath the JSONPath root expression; must not be blank
 */
public record JsonExtractSpec(String jsonPath) implements ExtractSpec {

    public JsonExtractSpec {
        Objects.requireNonNull(jsonPath, "jsonPath");
        if (jsonPath.isBlank()) {
            throw new IllegalArgumentException("jsonPath must not be blank");
        }
    }
}

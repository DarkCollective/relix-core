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

import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Type;

import java.util.Objects;
import java.util.Optional;

/**
 * Describes one column in a source declaration's schema.
 *
 * <p>Full syntax examples:
 * <pre>
 *   city:  in  STRING as query("q")          [required]
 *   units: in  STRING as query("units")       [default: "metric"]
 *   temp:  out NUMBER at "$.main.temp"
 *   name:  out STRING                          // binding inferred from column name
 * </pre>
 *
 * @param direction    whether the column is pushed into the request ({@code IN})
 *                     or extracted from the response ({@code OUT}); defaults to
 *                     {@code OUT} when omitted in source
 * @param name         the column name as it appears in RA queries; must not be blank
 * @param type         the scalar type; must not be null
 * @param binding      how the column maps to the transport; absent means the runtime
 *                     uses the column name as the direct field or parameter name
 * @param required     {@code true} if a query must supply an equality predicate on
 *                     this column; only meaningful for {@code IN} columns
 * @param defaultValue a literal string default used when no predicate is provided
 *                     and {@code required} is false; only meaningful for {@code IN}
 *                     columns
 */
public record ColumnSpec(
        ColumnDirection direction,
        String name,
        Type type,
        Optional<ColumnBinding> binding,
        boolean required,
        Optional<String> defaultValue
) {

    public ColumnSpec {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Column name must not be blank");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(defaultValue, "defaultValue");
    }

    /**
     * Creates a simple {@code OUT} column with no explicit binding.
     *
     * @param name the column name
     * @param type the scalar type
     * @return a new spec
     */
    public static ColumnSpec out(String name, ScalarType type) {
        return new ColumnSpec(ColumnDirection.OUT, name, type,
                Optional.empty(), false, Optional.empty());
    }

    /**
     * Creates an {@code OUT} column with an explicit JSONPath binding.
     *
     * @param name the column name
     * @param type the scalar type
     * @param path the JSONPath expression
     * @return a new spec
     */
    public static ColumnSpec out(String name, ScalarType type, String path) {
        return new ColumnSpec(ColumnDirection.OUT, name, type,
                Optional.of(new ExtractPathBinding(path)), false, Optional.empty());
    }

    /**
     * Creates a required {@code IN} column bound to a URL query parameter.
     *
     * @param name      the column name
     * @param type      the scalar type
     * @param paramName the URL query-parameter name
     * @return a new spec
     */
    public static ColumnSpec requiredIn(String name, ScalarType type, String paramName) {
        return new ColumnSpec(ColumnDirection.IN, name, type,
                Optional.of(new QueryParamBinding(paramName)), true, Optional.empty());
    }

    /**
     * Creates an optional {@code IN} column with a default value.
     *
     * @param name         the column name
     * @param type         the scalar type
     * @param paramName    the URL query-parameter name
     * @param defaultValue the default literal value when no predicate is given
     * @return a new spec
     */
    public static ColumnSpec optionalIn(String name, ScalarType type,
                                        String paramName, String defaultValue) {
        return new ColumnSpec(ColumnDirection.IN, name, type,
                Optional.of(new QueryParamBinding(paramName)),
                false, Optional.of(defaultValue));
    }
}

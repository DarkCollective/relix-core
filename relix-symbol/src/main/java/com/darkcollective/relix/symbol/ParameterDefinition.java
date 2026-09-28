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

import java.util.Objects;
import java.util.Optional;

/**
 * A single parameter in a function signature: a scalar, or — for a table-valued
 * function only — a relation.
 *
 * <p>A <b>scalar</b> parameter has a {@link #type()} and no {@link #heading()}. A
 * <b>relation</b> parameter ({@code E: RELATION(src, dst)}) has a heading — the columns
 * the function body may read from it, each with a type or {@link ScalarType#ANY} — and
 * reports {@link ScalarType#ANY} as its type, so that {@link
 * com.darkcollective.relix.symbol.function.FunctionSymbol#parameterSignature()} stays a
 * list of scalar types. A relation argument is a relation's name; the body sees it
 * narrowed to the heading.
 *
 * <p>Parameter names are used only for documentation and error messages; overload
 * resolution is based solely on the ordered list of {@link #type()} values (the
 * <em>parameter signature</em>).
 *
 * @param name    the parameter name as declared; must not be blank
 * @param type    the scalar type of this parameter; {@link ScalarType#ANY} for a relation
 *                parameter; must not be null
 * @param heading the columns of a relation parameter; empty for a scalar one; must not
 *                be null
 */
public record ParameterDefinition(String name, ScalarType type, Optional<Schema> heading) {

    public ParameterDefinition {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Parameter name must not be blank");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(heading, "heading");
        heading.ifPresent(h -> {
            if (type != ScalarType.ANY) {
                throw new IllegalArgumentException(
                        "A relation parameter's type is ANY, not " + type);
            }
            if (h.isOpen() || h.columns().isEmpty()) {
                throw new IllegalArgumentException(
                        "A relation parameter declares at least one column");
            }
        });
    }

    /**
     * A scalar parameter.
     *
     * @param name the parameter name; must not be blank
     * @param type its scalar type; must not be null
     */
    public ParameterDefinition(String name, ScalarType type) {
        this(name, type, Optional.empty());
    }

    /**
     * A relation parameter — {@code name: RELATION(columns…)}.
     *
     * @param name    the parameter name; must not be blank
     * @param heading the columns the body may read; closed, and at least one
     * @return the parameter
     */
    public static ParameterDefinition relation(String name, Schema heading) {
        return new ParameterDefinition(name, ScalarType.ANY, Optional.of(heading));
    }

    /** {@return whether this is a relation parameter} */
    public boolean isRelation() {
        return heading.isPresent();
    }
}

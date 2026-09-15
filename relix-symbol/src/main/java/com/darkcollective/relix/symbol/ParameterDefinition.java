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

/**
 * A single typed parameter in a function signature.
 *
 * <p>Parameter names are used only for documentation and error messages; overload
 * resolution is based solely on the ordered list of {@link #type()} values (the
 * <em>parameter signature</em>).
 *
 * @param name the parameter name as declared; must not be blank
 * @param type the scalar type of this parameter; must not be null
 */
public record ParameterDefinition(String name, ScalarType type) {

    public ParameterDefinition {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Parameter name must not be blank");
        }
        Objects.requireNonNull(type, "type");
    }
}

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
 * A nested array type — an ordered collection of elements of a single
 * {@link #element() element type} (which may itself be nested).
 *
 * <p>An array of heterogeneous or unknown elements is modelled as
 * {@code ArrayType(ScalarType.ANY)}, consistent with schema-on-read: the element
 * type is then resolved per element at runtime.
 *
 * @param element the element type; must not be null
 */
public record ArrayType(Type element) implements Type {

    public ArrayType {
        Objects.requireNonNull(element, "element");
    }

    @Override
    public String display() {
        return "array<" + element.display() + ">";
    }

    @Override
    public String code() {
        return "[" + element.code() + "]";
    }
}

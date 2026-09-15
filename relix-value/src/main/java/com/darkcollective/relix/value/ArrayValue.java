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
package com.darkcollective.relix.value;

import com.darkcollective.relix.symbol.ScalarType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A runtime array value — an ordered list of {@link Value} elements.
 *
 * <p>Equality is structural and order-sensitive. Like every nested value, its
 * {@link #type()} reports {@link ScalarType#ANY} (the dynamic-document type);
 * pattern-match the {@link Value} itself to discover the structural kind.  Elements
 * are themselves {@link Value}s (including {@link NullValue} for JSON {@code null}).
 *
 * @param elements the element list; never null, copied defensively
 */
public record ArrayValue(List<Value> elements) implements Value {

    public ArrayValue {
        Objects.requireNonNull(elements, "elements");
        elements = List.copyOf(elements);
    }

    @Override
    public boolean isNull() {
        return false;
    }

    @Override
    public ScalarType type() {
        return ScalarType.ANY;   // a nested value is the dynamic ANY document type
    }

    @Override
    public String asDisplayString() {
        return elements.stream()
                .map(Value::asDisplayString)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * Returns the element at {@code index}, or empty if the index is out of range.
     *
     * @param index a zero-based index
     * @return the element, or empty if {@code index < 0} or {@code index >= size}
     */
    public Optional<Value> at(int index) {
        return (index >= 0 && index < elements.size())
                ? Optional.of(elements.get(index))
                : Optional.empty();
    }
}

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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A runtime struct (nested object) value — an ordered map of field name to
 * {@link Value}.
 *
 * <p>Field names are case-preserved but looked up case-insensitively via
 * {@link #field(String)}.  Equality is structural (two structs are equal when they
 * have the same fields and values, regardless of field order). Like every nested
 * value, its {@link #type()} reports {@link ScalarType#ANY} (the dynamic-document
 * type); pattern-match the {@link Value} itself to discover the structural kind.
 *
 * @param fields the field map; never null, copied defensively and made unmodifiable
 */
public record StructValue(Map<String, Value> fields) implements Value {

    public StructValue {
        Objects.requireNonNull(fields, "fields");
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
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
        return fields.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue().asDisplayString())
                .collect(Collectors.joining(", ", "{", "}"));
    }

    /**
     * Returns the value of the field named {@code name}, matched case-insensitively.
     *
     * @param name the field name
     * @return the field's value, or empty if the struct has no such field
     */
    public Optional<Value> field(String name) {
        for (Map.Entry<String, Value> entry : fields.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }
}

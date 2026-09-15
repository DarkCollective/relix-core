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

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A nested struct type — an ordered set of named, typed {@link Field}s.
 *
 * <p>A struct is how relix represents a nested object (a JSON object, a Mongo
 * sub-document).  Conceptually a {@link Schema} is the top-level struct of a row,
 * so the flat relational model is the special case where every field is a
 * {@link ScalarType}.
 *
 * <p>Field names are case-preserved but must be unique case-insensitively, and
 * {@link #field(String)} looks them up case-insensitively — matching {@link Schema}.
 *
 * @param fields the ordered fields; never null, copied defensively
 */
public record StructType(List<Field> fields) implements Type {

    /**
     * A single named, typed field of a {@link StructType}.
     *
     * @param name the field name; must not be blank
     * @param type the field's type (possibly itself nested); must not be null
     */
    public record Field(String name, Type type) {
        public Field {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("Field name must not be blank");
            }
            Objects.requireNonNull(type, "type");
        }
    }

    public StructType {
        Objects.requireNonNull(fields, "fields");
        fields = List.copyOf(fields);
        Set<String> seen = new HashSet<>();
        for (Field field : fields) {
            if (!seen.add(field.name().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "Duplicate struct field name (case-insensitive): '" + field.name() + "'");
            }
        }
    }

    /**
     * Returns the field named {@code name}, matched case-insensitively.
     *
     * @param name the field name to look up
     * @return the matching field, or empty if none
     */
    public Optional<Field> field(String name) {
        String target = name.toLowerCase(Locale.ROOT);
        return fields.stream().filter(f -> f.name().toLowerCase(Locale.ROOT).equals(target)).findFirst();
    }

    @Override
    public String display() {
        return fields.stream()
                .map(f -> f.name() + ": " + f.type().display())
                .collect(Collectors.joining(", ", "struct{", "}"));
    }

    @Override
    public String code() {
        return fields.stream()
                .map(f -> f.name() + ":" + f.type().code())
                .collect(Collectors.joining(",", "{", "}"));
    }
}

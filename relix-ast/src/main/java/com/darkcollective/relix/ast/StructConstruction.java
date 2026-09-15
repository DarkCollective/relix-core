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
package com.darkcollective.relix.ast;

import com.darkcollective.relix.ast.visitor.OperandVisitor;

import java.util.List;
import java.util.Objects;

/**
 * Constructs a nested struct value in a projection — {@code { name: expr, … }}.
 *
 * <p>Each {@link Field} pairs a field name with an operand expression.  The
 * shorthand {@code { id, name }} (a bare identifier with no {@code :}) is sugar
 * for {@code { id: id, name: name }} and is desugared by the parser into explicit
 * fields, so this node always carries explicit {@code name → value} pairs.
 *
 * <p>Together with {@link ArrayConstruction} and the {@code COLLECT} aggregate
 * this lets a query reshape data nested → flat → nested.
 *
 * @param fields   the ordered struct fields; must not be null
 * @param location the source location of this operand; never null
 */
public record StructConstruction(List<Field> fields, SourceLocation location) implements Operand {

    /**
     * A single struct field: a name paired with its value expression.
     *
     * @param name  the field name; must not be blank
     * @param value the value expression; must not be null
     */
    public record Field(String name, Operand value) {
        public Field {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            if (name.isBlank()) {
                throw new IllegalArgumentException("field name must not be blank");
            }
        }
    }

    public StructConstruction {
        Objects.requireNonNull(location, "location");
        fields = List.copyOf(fields);
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public StructConstruction(List<Field> fields) {
        this(fields, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(OperandVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

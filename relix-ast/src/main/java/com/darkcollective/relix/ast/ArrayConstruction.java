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
 * Constructs a nested array value in a projection — {@code [ expr, expr, … ]}.
 *
 * <p>The empty array {@code []} is permitted.  Together with
 * {@link StructConstruction} and the {@code COLLECT} aggregate this lets a query
 * reshape data nested → flat → nested.
 *
 * @param elements the ordered element expressions; must not be null
 * @param location the source location of this operand; never null
 */
public record ArrayConstruction(List<Operand> elements, SourceLocation location) implements Operand {

    public ArrayConstruction {
        Objects.requireNonNull(location, "location");
        elements = List.copyOf(elements);
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public ArrayConstruction(List<Operand> elements) {
        this(elements, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(OperandVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

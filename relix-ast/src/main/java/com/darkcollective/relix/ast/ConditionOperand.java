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
import java.util.Objects;

/**
 * A boolean-valued operand: a {@link Predicate} used in operand position.
 *
 * <p>A comparison (or any predicate) yields a boolean <em>value</em>, so it can
 * appear anywhere an {@link Operand} is expected — most usefully as a function
 * argument. This is what lets {@code IIf(price > 100, "expensive", "cheap")}
 * parse: the condition {@code price > 100} is a {@link ComparisonPredicate}
 * wrapped as an operand. Without this node the {@code Operand} grammar could only
 * express arithmetic, so the conditional {@code IIf} — whose whole purpose is a
 * boolean test — could not receive one.
 *
 * <p>It infers as {@code BOOLEAN} and evaluates by running the wrapped predicate
 * against the row (the same semantics {@code σ} uses), so it reuses the existing
 * predicate validation / evaluation / rendering machinery rather than duplicating
 * it.
 *
 * @param predicate the wrapped boolean condition; must not be null
 * @param location  the source location of this operand; never null
 */
public record ConditionOperand(Predicate predicate, SourceLocation location) implements Operand {
    public ConditionOperand {
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public ConditionOperand(Predicate predicate) {
        this(predicate, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(OperandVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

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

import java.util.Objects;
import java.util.Optional;

/**
 * Represents a projected attribute in a projection operation.
 * An attribute can have an optional alias using the → operator.
 *
 * Examples:
 *   - Simple attribute: id
 *   - Aliased simple attribute: id → user_id
 *   - Aliased arithmetic expression: price * 1.05 → adjusted_price
 */
public record ProjectedAttribute(
        Operand expression,
        Optional<String> alias
) {
    public ProjectedAttribute {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(alias, "alias");
    }

    /**
     * Creates a projected attribute without an alias.
     */
    public static ProjectedAttribute simple(Operand expression) {
        return new ProjectedAttribute(expression, Optional.empty());
    }

    /**
     * Creates a projected attribute with an alias.
     */
    public static ProjectedAttribute aliased(Operand expression, String alias) {
        Objects.requireNonNull(alias, "alias");
        if (alias.isBlank()) {
            throw new IllegalArgumentException("alias cannot be blank");
        }
        return new ProjectedAttribute(expression, Optional.of(alias));
    }
}


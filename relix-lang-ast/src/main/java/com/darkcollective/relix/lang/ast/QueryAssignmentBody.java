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
package com.darkcollective.relix.lang.ast;

import com.darkcollective.relix.ast.RelNode;

import java.util.Objects;

/**
 * An assignment body that holds a parsed relational algebra expression.
 *
 * <p>The expression is represented as a fully-parsed {@link RelNode}, produced
 * by delegating the content of the {@code { ... }} block to the existing
 * {@code RelAlgebraParser}.
 *
 * <p>Example: {@code ActiveUsers := { σ status = "active" (Users) };}
 *
 * @param expression the parsed RA expression; must not be null
 */
public record QueryAssignmentBody(RelNode expression) implements AssignmentBody {

    public QueryAssignmentBody {
        Objects.requireNonNull(expression, "expression");
    }
}

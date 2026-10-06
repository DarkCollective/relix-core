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

/**
 * Where a {@link SolveNode}'s iterative search starts for one unknown:
 * {@code START column = value}.
 *
 * <p>{@code value} is evaluated over the row being solved, so the start may differ from
 * row to row. It matters only when the unknown is found by search; an equation that is
 * inverted or eliminated has one answer and ignores it.
 *
 * @param column the unknown column the start is for; never blank
 * @param value  the starting value, an expression over the row's known columns; never null
 */
public record SolveStart(String column, Operand value) {
    public SolveStart {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(value, "value");
        if (column.isBlank()) {
            throw new IllegalArgumentException("SOLVE START column must not be blank");
        }
    }
}

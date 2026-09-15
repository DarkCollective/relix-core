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
package com.darkcollective.relix.processor.eval;

import com.darkcollective.relix.processor.Row;

/**
 * A candidate row paired with its continuous LP allocation value, as returned by
 * {@link SubsetOptimizer#allocate}.  The {@code allocation} is the solver's chosen
 * value for this row's decision variable, in the {@code [lo, hi]} range specified
 * by the {@code OPTIMIZE ALLOCATE} operator.
 *
 * @param row        the original input row; never null
 * @param allocation the solver-assigned allocation value for this row
 */
public record AllocationRow(Row row, double allocation) {

    public AllocationRow {
        java.util.Objects.requireNonNull(row, "row");
    }
}

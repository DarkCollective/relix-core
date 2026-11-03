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

/**
 * The offset window functions carried by a
 * {@link WindowFunction.OffsetWindow}.
 *
 * <p>Each reads a value from another row in the partition's sort order; the
 * output type is the type of the offset expression.  The permit set is established
 * in slice 2 so the exhaustive-switch enforcement guides slice 4; only
 * {@link WindowFunction.AggregateWindow} is executable in slice 2.
 */
public enum OffsetFunction {
    /** The expression's value {@code n} rows before the current row. */
    LAG,
    /** The expression's value {@code n} rows after the current row. */
    LEAD,
    /** The expression's value in the first row of its frame. */
    FIRST_VALUE,
    /** The expression's value in the last row of its frame. */
    LAST_VALUE,
    /** The expression's value in the {@code n}th row of its frame, from 1. */
    NTH_VALUE;

    /**
     * Whether the function reads its row from a frame: {@code FIRST_VALUE},
     * {@code LAST_VALUE} and {@code NTH_VALUE} read the first, last or {@code n}th row
     * of theirs, {@link WindowFrame#toCurrentPeers()} unless they name another. As in
     * SQL, {@code LAG} and {@code LEAD} count from the current row and take none.
     *
     * @return true for the three value functions
     * @since 1.0
     */
    public boolean takesFrame() {
        return this == FIRST_VALUE || this == LAST_VALUE || this == NTH_VALUE;
    }
}

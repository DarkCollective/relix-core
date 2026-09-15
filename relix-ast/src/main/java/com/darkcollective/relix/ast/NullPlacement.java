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
 * Controls where {@code NULL} values appear when a column is used as a sort key.
 *
 * <p>SQL specifies two placements: {@link #NULLS_FIRST} places all {@code NULL}
 * values before any non-{@code NULL} value; {@link #NULLS_LAST} places them after.
 * The SQL standard defaults are {@link #NULLS_LAST} for {@link SortDirection#ASC}
 * and {@link #NULLS_FIRST} for {@link SortDirection#DESC}.  These defaults apply
 * when no explicit placement is specified — see {@link SortSpecification#nullPlacement()}.
 *
 * <p>Used by the merge-join comparator to ensure both merge
 * inputs are compared with the same {@code NULL} ordering, and by the sort executor
 * to honour explicit {@code NULLS FIRST}/{@code NULLS LAST} clauses (Phase C4+).
 */
public enum NullPlacement {

    /** {@code NULL} values sort before all non-{@code NULL} values. */
    NULLS_FIRST,

    /** {@code NULL} values sort after all non-{@code NULL} values. */
    NULLS_LAST
}

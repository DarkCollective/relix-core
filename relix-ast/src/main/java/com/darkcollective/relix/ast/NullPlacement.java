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
 * Where {@code NULL} values land when a column is used as a sort key.
 *
 * <p>{@link #NULLS_FIRST} places every {@code NULL} before any non-{@code NULL} value;
 * {@link #NULLS_LAST} places them after. A sort key holds the placement in the output,
 * whichever way the key runs ({@link SortSpecification#nulls()}); Relix's default is
 * {@link #NULLS_LAST} in both directions, and SQL's {@code NULLS FIRST} and
 * {@code NULLS LAST} choose. {@link SortSpecification#nullPlacement()} gives the same
 * placement as it reads before a descending key is reversed.
 */
public enum NullPlacement {

    /** {@code NULL} values sort before all non-{@code NULL} values. */
    NULLS_FIRST,

    /** {@code NULL} values sort after all non-{@code NULL} values. */
    NULLS_LAST
}

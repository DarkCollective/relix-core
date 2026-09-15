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
package com.darkcollective.relix.lang.ast.source;

/**
 * Indicates whether a column spec describes an input parameter or an output value.
 *
 * <p>{@code IN} columns carry values from the query (predicate push-down) into
 * the data source request.  {@code OUT} columns carry values from the response
 * back to the query.
 *
 * <p>When the direction is omitted in the source schema, it defaults to
 * {@link #OUT}.
 */
public enum ColumnDirection {

    /**
     * The column is an input parameter — an equality predicate on this column
     * in the query is pushed into the HTTP request (URL query param, path param,
     * or header).
     */
    IN,

    /**
     * The column is an output value — its content is extracted from the response
     * and returned as part of the relation's tuples.
     */
    OUT
}

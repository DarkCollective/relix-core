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
package com.darkcollective.relix.symbol.relation;

import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.Symbol;

/**
 * Sealed sub-interface of {@link Symbol} for all relation-typed symbols.
 *
 * <p>A relation symbol describes a named entity that, when evaluated, produces
 * a set (or bag) of tuples conforming to the associated {@link Schema}.
 *
 * <p>Five concrete kinds exist:
 * <ul>
 *   <li>{@link DatabaseRelationSymbol} — backed by an external JDBC store.</li>
 *   <li>{@link InlineRelationSymbol} — rows declared inline in source.</li>
 *   <li>{@link QueryRelationSymbol} — defined by a relational algebra expression.</li>
 *   <li>{@link SourceRelationSymbol} — backed by an external HTTP, CSV, or other
 *       transport; the full source configuration lives in the semantic model.</li>
 *   <li>{@link SystemRelationSymbol} — a read-only engine-computed catalog relation
 *       in the reserved {@code relix.*} namespace.</li>
 * </ul>
 *
 * @see Schema
 */
public sealed interface RelationSymbol extends Symbol
        permits DatabaseRelationSymbol, InlineRelationSymbol, QueryRelationSymbol,
                SourceRelationSymbol, SystemRelationSymbol {

    /**
     * The structural description of this relation's columns.
     *
     * @return the schema; never null
     */
    Schema schema();
}

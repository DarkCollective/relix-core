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
/**
 * Relation symbol hierarchy — the five concrete kinds of named relation.
 *
 * <p>All five implement {@link com.darkcollective.relix.symbol.relation.RelationSymbol},
 * which in turn implements {@link com.darkcollective.relix.symbol.Symbol}.
 *
 * <ul>
 *   <li>{@link com.darkcollective.relix.symbol.relation.DatabaseRelationSymbol} —
 *       a relation backed by an external JDBC store.  Only its schema is stored here;
 *       the actual data lives in the database.</li>
 *   <li>{@link com.darkcollective.relix.symbol.relation.InlineRelationSymbol} —
 *       a relation whose rows are declared inline in a {@code .relix} file, typically
 *       as a Markdown or CSV table.  Column types are inferred from cell content:
 *       columns whose every cell parses as a number are typed {@link
 *       com.darkcollective.relix.symbol.ScalarType#NUMBER}; all others are
 *       {@link com.darkcollective.relix.symbol.ScalarType#STRING}.</li>
 *   <li>{@link com.darkcollective.relix.symbol.relation.QueryRelationSymbol} —
 *       a named relational algebra expression (a {@code RelNode} body).  Acts as a
 *       view: the body is stored for later semantic analysis and pretty-printing.</li>
 *   <li>{@link com.darkcollective.relix.symbol.relation.SourceRelationSymbol} —
 *       a relation backed by an external transport (HTTP API, CSV file, etc.).  Only
 *       the schema is stored here for name-resolution purposes; the full source
 *       configuration lives in the semantic model produced by {@code relix-semantic}.</li>
 *   <li>{@link com.darkcollective.relix.symbol.relation.SystemRelationSymbol} —
 *       a read-only engine-computed catalog relation in the reserved {@code relix.*}
 *       namespace.  Rows are populated by {@code CatalogBuilder};
 *       the type itself enforces that catalog relations are structurally distinct from
 *       user-declared inline data.</li>
 * </ul>
 *
 * <p>All five types are records and are therefore immutable.  The user-facing types
 * ({@code InlineRelationSymbol}, {@code SourceRelationSymbol}) provide a convenience
 * factory method ({@code of(...)}) that applies the standard defaults:
 * namespace {@code "default"} and provenance {@link
 * com.darkcollective.relix.symbol.Provenance#USER}.
 */
package com.darkcollective.relix.symbol.relation;

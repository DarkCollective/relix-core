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
 * The schema graph — relationship metadata between relations.
 *
 * <p>A {@link com.darkcollective.relix.symbol.graph.SchemaGraph} is a set of
 * {@link com.darkcollective.relix.symbol.graph.Relationship} edges, each a
 * named, multiplicity-bounded connection between two
 * {@link com.darkcollective.relix.symbol.graph.Endpoint endpoints} (a relation
 * plus an ordered column list). The graph makes join-path resolution mechanical
 * — for a set of relations a query touches, the join is the minimal connected
 * subgraph spanning them — and its bounds are join-selectivity facts for the
 * cost model.
 *
 * <p>Every edge carries an {@link com.darkcollective.relix.symbol.graph.EdgeOrigin}
 * recording how it was acquired (declared in source, learned in conversation or
 * from observed usage, or inferred from data), which keeps non-declared edges
 * visibly provisional and correctable.
 *
 * <p>{@link com.darkcollective.relix.symbol.graph.SchemaGraphSearch} is the
 * mechanical core of §3: given the terminal relations a request touches, it
 * enumerates the minimal connected subgraph(s) — a Steiner tree over the small
 * graph — spanning them, returning a unique
 * {@link com.darkcollective.relix.symbol.graph.JoinPath}, several enumerated
 * alternatives (the "did you mean?" ambiguity case), or a disconnected outcome.
 *
 * <p>This package is pure representation and search: assembly from declarations
 * and validation against inferred schemas live in {@code relix-semantic}; the
 * graph is exposed on the {@code SemanticModel}.
 */
package com.darkcollective.relix.symbol.graph;

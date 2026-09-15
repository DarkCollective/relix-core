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
 * Core query-execution types for the relix processor.
 *
 * <p>This package contains the central runtime data structures:
 *
 * <ul>
 *   <li>{@link com.darkcollective.relix.processor.Row} — a single tuple produced
 *       during query execution; an ordered sequence of
 *       {@link com.darkcollective.relix.value.Value}s described by a
 *       {@link com.darkcollective.relix.symbol.Schema}.</li>
 *   <li>{@link com.darkcollective.relix.processor.ArrayRow} — the standard
 *       positional implementation of {@code Row}, backed by a fixed-size
 *       {@code Value[]} array.</li>
 *   <li>{@link com.darkcollective.relix.processor.DataSourceConnector} — the
 *       functional interface that bridges external data sources (JDBC, HTTP)
 *       to the {@code Row} stream model.</li>
 *   <li>{@link com.darkcollective.relix.processor.ExecutionContext} — the shared
 *       context threaded through all operators: symbol table, per-node schema
 *       annotations, and the data-source connector.</li>
 * </ul>
 *
 * <p>Scalar values are in the sub-package
 * {@link com.darkcollective.relix.value}.
 * Expression and predicate evaluators are in
 * {@link com.darkcollective.relix.processor.eval}.
 * Execution operators that transform {@code Stream<Row>} pipelines are in
 * {@link com.darkcollective.relix.processor.exec}.
 */
package com.darkcollective.relix.processor;

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
 * Query execution engine for the relix relational algebra system.
 *
 * <p>This module evaluates a validated {@link com.darkcollective.relix.semantic.SemanticModel}
 * against in-memory and external data sources, producing lazy {@code Stream<Row>} pipelines.
 * Operators fuse naturally via Java Streams: only aggregation, sort, and set operations
 * require intermediate materialisation.
 *
 * <p>Key packages:
 * <ul>
 *   <li>{@code com.darkcollective.relix.processor} — {@code Row} interface and
 *       {@code ArrayRow} implementation; the central runtime data types.</li>
 * </ul>
 *
 * <p>The scalar values a {@code Row} holds are not defined here: the sealed
 * {@code Value} hierarchy lives in its own module, {@code relix-value}, which this
 * module re-exports so a consumer of a row also sees the values in it.
 *
 * <p>Neither are the source connectors. This module declares the seam a connector
 * plugs into — {@code DataSourceConnector}, the {@code connector} package's
 * {@code RelixConnector} SPI, and the {@code Fetcher} a download goes through — and
 * ships no implementation of any of them, so an embedder inherits no JDBC stack and
 * no HTTP client. The standard CSV/JSON/HTTP/JDBC set is a discovered provider
 * ({@code relix-connectors-std}) on exactly the footing an out-of-tree plugin has.
 */
module com.darkcollective.relix.processor {
    requires transitive com.darkcollective.relix.symbol;
    // Value — every Row is a tuple of them, so a consumer of this module's API
    // cannot use it without them.
    requires transitive com.darkcollective.relix.value;
    requires transitive com.darkcollective.relix.semantic;
    // Planner + PhysicalNode — execution runs physical plans; transitive because
    // PhysicalNode appears in the PhysicalPlanConsumer / QueryExecutor.plan API.
    requires transitive com.darkcollective.relix.plan;
    requires transitive com.darkcollective.relix.events;  // QueryEventListener in QueryExecutor.trace
    requires transitive com.darkcollective.relix.provenance;  // Semiring<K> in AnnotatedRelation API
    // The function SPI: FunctionCatalog is an ExecutionContext component and the
    // ScalarFunction the evaluator dispatches over. The default library is absent
    // here on purpose — a provider is discovered, never required (ADR-0026 D2).
    requires transitive com.darkcollective.relix.function;
    // The solver SPI: SubsetOptimizer builds a LinearProgram and reads the answer;
    // the search is a discovered provider's job, never required here (ADR-0026 D8).
    requires com.darkcollective.relix.solver;
    requires com.darkcollective.relix.json;   // JsonReader — the connector-plugin manifest

    // ConnectorRegistry discovers source connectors — the standard ones
    // (relix-connectors-std, on the module path) and external plugin JARs alike —
    // via ServiceLoader (ADR-0010 Decision 2). The engine declares the seam and
    // provides nothing: no source connector, and hence neither a JDBC stack nor an
    // HTTP client, ships inside it (ADR-0026 Decision 8).
    uses com.darkcollective.relix.processor.connector.RelixConnector;

    exports com.darkcollective.relix.processor;
    exports com.darkcollective.relix.processor.internal to com.darkcollective.relix.connectors.std, com.darkcollective.relix.embed;
    exports com.darkcollective.relix.processor.eval;
    exports com.darkcollective.relix.processor.exec;
    exports com.darkcollective.relix.processor.connector;
    exports com.darkcollective.relix.processor.connector.internal to com.darkcollective.relix.connectors.std, com.darkcollective.relix.embed;
    exports com.darkcollective.relix.processor.generator;
    exports com.darkcollective.relix.processor.provenance;
    exports com.darkcollective.relix.processor.provenance.internal to com.darkcollective.relix.embed;
}

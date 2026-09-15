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
 * The standard library of {@link com.darkcollective.relix.processor.DataSourceConnector}
 * implementations — CSV, JSON files, HTTP/JSON endpoints and JDBC databases — plus the
 * JDBC driver provisioning they need.
 *
 * <p>Each connector bridges one external data-source kind to the query executor's
 * {@link com.darkcollective.relix.processor.Row} model:
 * <ul>
 *   <li>{@link com.darkcollective.relix.connectors.std.CsvDataSourceConnector} —
 *       local CSV files declared with
 *       {@code source X from csv("path/to/file.csv") { … }};</li>
 *   <li>{@link com.darkcollective.relix.connectors.std.JsonFileDataSourceConnector} —
 *       local JSON documents;</li>
 *   <li>{@link com.darkcollective.relix.connectors.std.HttpDataSourceConnector} —
 *       read-only HTTP/JSON endpoints;</li>
 *   <li>{@link com.darkcollective.relix.connectors.std.JdbcDataSourceConnector} —
 *       relational databases over JDBC, with
 *       {@link com.darkcollective.relix.connectors.std.JdbcCatalogProvider} supplying
 *       table schemas and statistics.</li>
 * </ul>
 *
 * <p>All connectors receive a fully-validated
 * {@link com.darkcollective.relix.semantic.SemanticModel} at construction time so they
 * can look up the transport-level configuration (path, column schema, header flag, etc.)
 * stored in {@link com.darkcollective.relix.semantic.SemanticModel#sources()}.
 *
 * <p>This is a <em>provider</em>, not part of the engine: it compiles against the
 * connector SPI the engine publishes and is discovered at runtime, exactly as an
 * out-of-tree connector plugin is. Nothing here is privileged, which is why the engine
 * itself needs neither a JDBC stack nor an HTTP client.
 */
package com.darkcollective.relix.connectors.std;

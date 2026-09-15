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
 * Source-declaration AST — the configuration types that describe how a
 * virtual relation retrieves its data.
 *
 * <h2>Source hierarchy</h2>
 * <pre>
 * SourceConfig (sealed)
 * ├── HttpSourceConfig     — HTTP/REST API (JSON or CSV response)
 * ├── DatabaseSourceConfig — JDBC-accessible table or view
 * └── CsvFileSourceConfig  — local CSV file
 * </pre>
 *
 * <h2>Column specs</h2>
 * <p>Each source type carries a list of {@link
 * com.darkcollective.relix.lang.ast.source.ColumnSpec}s
 * that describe the relation's schema as seen by queries.  A column is either:
 * <ul>
 *   <li>{@link com.darkcollective.relix.lang.ast.source.ColumnDirection#IN} —
 *       an input parameter pushed into the request (e.g. a URL query parameter).
 *       Only meaningful for {@code HttpSourceConfig}.</li>
 *   <li>{@link com.darkcollective.relix.lang.ast.source.ColumnDirection#OUT} —
 *       a value extracted from the response or result set.</li>
 * </ul>
 *
 * <h2>Column bindings</h2>
 * <p>{@link com.darkcollective.relix.lang.ast.source.ColumnBinding} is a sealed
 * interface describing how a column maps to the transport:
 * <ul>
 *   <li>{@link com.darkcollective.relix.lang.ast.source.QueryParamBinding} — URL
 *       query parameter ({@code as query("q")}).</li>
 *   <li>{@link com.darkcollective.relix.lang.ast.source.PathParamBinding} — URL
 *       path segment ({@code as path("id")}).</li>
 *   <li>{@link com.darkcollective.relix.lang.ast.source.HeaderBinding} — request
 *       header ({@code as header("X-City")}).</li>
 *   <li>{@link com.darkcollective.relix.lang.ast.source.ExtractPathBinding} —
 *       JSONPath into the response body ({@code at "$.main.temp"}).</li>
 * </ul>
 */
package com.darkcollective.relix.lang.ast.source;

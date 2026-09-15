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
 * Sealed root interface for the transport configuration of a source declaration.
 *
 * <p>The three permitted implementations correspond to the three source kinds
 * supported in v1:
 * <ul>
 *   <li>{@link HttpSourceConfig} — HTTP/REST API.</li>
 *   <li>{@link DatabaseSourceConfig} — JDBC-accessible table or view.</li>
 *   <li>{@link CsvFileSourceConfig} — local CSV file.</li>
 *   <li>{@link JsonFileSourceConfig} — local JSON file (open / schema-on-read).</li>
 *   <li>{@link ConnectionTableSourceConfig} — a table within a declared connection.</li>
 *   <li>{@link GeneratorSourceConfig} — a code-backed generator relation.</li>
 * </ul>
 */
public sealed interface SourceConfig
        permits HttpSourceConfig, DatabaseSourceConfig, CsvFileSourceConfig,
                JsonFileSourceConfig, ConnectionTableSourceConfig, GeneratorSourceConfig {
}

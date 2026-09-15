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
 * The standard source connectors — CSV, JSON files, HTTP/JSON endpoints and JDBC
 * databases — shipped as a provider behind the connector SPI the engine publishes,
 * not as part of the engine itself.
 *
 * <p>This module is where {@code java.sql} and {@code java.net.http} live. An engine
 * that reads a JDBC catalog and downloads driver JARs over HTTP carries a database
 * stack and an HTTP client into every embedding; moving the implementations out
 * behind the SPI leaves the engine with the seam it reasons about — which sources
 * exist, what schema each has — and nothing that talks to a network.
 *
 * <p>The default library gets no privileged access: it compiles against the same
 * exported SPI an out-of-tree plugin does ({@code relix-mongo-connector} is the
 * out-of-tree case). {@code CsvConnector} is declared as a {@code RelixConnector}
 * provider twice — here and in {@code META-INF/services} — because a module path
 * reads the first and Gradle's classpath {@code test} reads the second.
 */
module com.darkcollective.relix.connectors.std {
    // The connector SPI plus the Row/Schema/Value types a connector produces. The
    // engine exports all of it; nothing here reaches past what a third party can.
    requires transitive com.darkcollective.relix.processor;
    requires com.darkcollective.relix.semantic;   // SemanticModel — the source declarations
    requires com.darkcollective.relix.lang.ast;   // SourceConfig — the declared transport config
    requires com.darkcollective.relix.symbol;     // Schema, ColumnDefinition, ScalarType
    requires com.darkcollective.relix.value;      // the Value a column maps to
    requires com.darkcollective.relix.ast;        // TemporalLiterals — parsing a temporal column
    requires com.darkcollective.relix.json;   // JsonReader — the JDBC driver manifest
    requires com.darkcollective.relix.plan;   // Dialect — the session time zone a connection is pinned to
    requires java.sql;                            // JDBC connector + catalog introspection
    requires java.net.http;                       // HTTP source + on-demand driver download

    // RelixDriverLoader discovers java.sql.Driver services in externally-loaded
    // driver JARs via ServiceLoader; a named module must declare the `uses`.
    uses java.sql.Driver;

    provides com.darkcollective.relix.processor.connector.RelixConnector
            with com.darkcollective.relix.connectors.std.CsvConnector;

    exports com.darkcollective.relix.connectors.std;
}

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
package com.darkcollective.relix.embed;

import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.lang.ast.source.ConnectionTableSourceConfig;
import com.darkcollective.relix.lang.ast.source.CsvFileSourceConfig;
import com.darkcollective.relix.lang.ast.source.DatabaseSourceConfig;
import com.darkcollective.relix.lang.ast.source.GeneratorSourceConfig;
import com.darkcollective.relix.lang.ast.source.HttpSourceConfig;
import com.darkcollective.relix.lang.ast.source.JsonFileSourceConfig;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.processor.DataSourceConnector;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.connector.ConnectorConfig;
import com.darkcollective.relix.processor.connector.ConnectorProvisioner;
import com.darkcollective.relix.processor.connector.ConnectorRegistry;
import com.darkcollective.relix.processor.connector.RelixConnector;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.connectors.std.DriverProvisioner;
import com.darkcollective.relix.processor.generator.GeneratorDataSourceConnector;
import com.darkcollective.relix.connectors.std.HttpDataSourceConnector;
import com.darkcollective.relix.connectors.std.ConnectionPool;
import com.darkcollective.relix.connectors.std.JdbcDataSourceConnector;
import com.darkcollective.relix.connectors.std.JsonFileDataSourceConnector;
import com.darkcollective.relix.processor.generator.GeneratorRegistry;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.Schema;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The runtime data-source connector: it dispatches each external relation to the
 * connector for its source kind.
 *
 * <p>Connector-backed relations are routed through the {@link ConnectorRegistry}
 * (the open connector SPI): the source's config kind is mapped to a type token,
 * and the matching {@link RelixConnector} — built-in or an external plugin from
 * {@code ~/.relix/connectors/} — is invoked with a type-agnostic
 * {@link ConnectorConfig}.  CSV already flows through the registry's built-in
 * {@code CsvConnector}.  JSON, HTTP, and JDBC have not yet been migrated to the
 * SPI, so they fall back to their existing connectors; the legacy {@code database}
 * source kind has no connector wired in and raises an {@link EvaluationException}
 * naming the kind.
 *
 * <p>Package-private: {@link Relix} builds one per execution over the session's own
 * connection pool, and {@code openConnector} hands it back as a plain
 * {@link DataSourceConnector}. It was public while it lived in {@code relix-console},
 * where nothing exported it either — here that would put {@code GeneratorRegistry} on
 * the published surface to no caller's benefit, since assembling this stack by hand is
 * exactly the work the facade exists to spare an embedder.
 */
final class CompositeDataSourceConnector implements DataSourceConnector {

    private final SemanticModel model;
    private final Path baseDir;
    private ConnectorRegistry registry;
    private final List<RelixConnector> supplied;
    private final ConnectorProvisioner connectorProvisioner;
    private final JsonFileDataSourceConnector jsonConnector;
    private final HttpDataSourceConnector httpConnector;
    private final JdbcDataSourceConnector jdbcConnector;
    private final GeneratorDataSourceConnector generatorConnector;

    /**
     * Creates the composite connector for one model, over a {@link ConnectionPool} and a
     * list of connectors the caller owns.
     *
     * <p>The connector is built per query while the pool outlives every one of them, and
     * a pool is where a live handle enters execution: one built over a
     * {@code ConnectionProvider} that serves a bound {@code DataSource} is what lets a
     * connection be referenced without a URL. {@link #close()} therefore leaves it open —
     * closing it is the owner's business.
     *
     * <p>A supplied connector is likewise <strong>not</strong> closed with this one, and
     * is dispatched to ahead of the discovered ones. It is what a program holding a
     * connector instance needs, rather than a {@code META-INF/services} declaration: the
     * connector SPI's claim is that anything a shipped connector can do a third party can
     * do, and requiring a service declaration to exercise that inside one's own program
     * would be a gap in it.
     *
     * @param model                the semantic model whose {@code sources} declare each relation's kind
     * @param baseDir              directory used to resolve relative CSV and JSON paths
     * @param driverProvisioner    the on-demand JDBC driver provisioner (a missing driver is
     *                             downloaded from the catalog when permitted); must not be null
     * @param connectorProvisioner the on-demand connector-plugin provisioner (a missing connector
     *                             plugin is downloaded from the catalog when permitted); must not
     *                             be null
     * @param generators           the registry of built-in generators; must not be null
     * @param pool                 the caller's connection pool; must not be null
     * @param connectors           the caller's own connectors, in priority order; must not be null
     */
    CompositeDataSourceConnector(SemanticModel model, Path baseDir,
                                 DriverProvisioner driverProvisioner,
                                 ConnectorProvisioner connectorProvisioner,
                                 GeneratorRegistry generators,
                                 ConnectionPool pool,
                                 List<RelixConnector> connectors) {
        this(model, baseDir, connectorProvisioner, generators,
                new JdbcDataSourceConnector(model, Objects.requireNonNull(pool, "pool"),
                        Objects.requireNonNull(driverProvisioner, "driverProvisioner")),
                connectors);
    }

    /** The one real constructor. */
    private CompositeDataSourceConnector(SemanticModel model, Path baseDir,
                                         ConnectorProvisioner connectorProvisioner,
                                         GeneratorRegistry generators,
                                         JdbcDataSourceConnector jdbcConnector,
                                         List<RelixConnector> supplied) {
        this.model = Objects.requireNonNull(model, "model");
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
        this.supplied = List.copyOf(Objects.requireNonNull(supplied, "connectors"));
        this.registry = ConnectorRegistry.createWith(this.supplied);
        this.connectorProvisioner = Objects.requireNonNull(connectorProvisioner, "connectorProvisioner");
        this.jsonConnector = new JsonFileDataSourceConnector(model, baseDir);
        this.httpConnector = new HttpDataSourceConnector(model);
        this.jdbcConnector = jdbcConnector;
        this.generatorConnector = new GeneratorDataSourceConnector(model,
                Objects.requireNonNull(generators, "generators"));
    }

    @Override
    public Stream<Row> open(String relationName, Schema schema) {
        SourceDeclaration declaration = model.sources().get(relationName.toLowerCase(Locale.ROOT));
        if (declaration == null) {
            throw new EvaluationException(
                    "No source declaration for external relation '" + relationName + "'");
        }
        return switch (declaration.config()) {
            case CsvFileSourceConfig csv -> openCsv(relationName, csv, schema);
            case JsonFileSourceConfig _ -> jsonConnector.open(relationName, schema);
            case DatabaseSourceConfig _ -> throw noConnector(relationName, "database");
            case HttpSourceConfig _     -> httpConnector.open(relationName, schema);
            case ConnectionTableSourceConfig table -> openConnectionTable(relationName, table, schema);
            case GeneratorSourceConfig _ -> generatorConnector.open(relationName, schema);
        };
    }

    /**
     * Opens a connection-table relation, dispatching by the connection's
     * {@code connectorType} (ADR-0010, Decision 3): {@code jdbc} connections go to
     * the JDBC connector; any other type is routed through the {@link ConnectorRegistry}
     * to the matching {@link RelixConnector} (e.g. a MongoDB plugin), with the
     * connection's raw properties passed as the {@link ConnectorConfig}.
     */
    private Stream<Row> openConnectionTable(String relationName, ConnectionTableSourceConfig table,
                                            Schema schema) {
        ConnectionDeclaration connection = model.connections().get(table.connection().toLowerCase(Locale.ROOT));
        String type = connection != null ? connection.connectorType() : "jdbc";
        if (type.equals("jdbc")) {
            return jdbcConnector.open(relationName, schema);
        }
        RelixConnector connector = resolveOrProvision(type,
                "connection '" + table.connection() + "', relation '" + relationName + "'");
        return connector.open(new ConnectorConfig(connection.properties()), table.table(), schema);
    }

    /**
     * Resolves the connector for a non-JDBC type, provisioning it on demand (when
     * downloads are permitted) if it is not already installed: a download success is
     * followed by rebuilding the registry to pick up the new plugin.
     *
     * @param type    the connector type token
     * @param context a human-readable description of the relation/query for error messages
     */
    private RelixConnector resolveOrProvision(String type, String context) {
        Optional<RelixConnector> found = registry.forType(type);
        if (found.isPresent()) {
            return found.get();
        }
        ConnectorProvisioner.Result result = connectorProvisioner.provision(type);
        if (result.outcome() == ConnectorProvisioner.Outcome.PROVISIONED) {
            // Rescan to discover the downloaded plugin, keeping the caller's connectors.
            registry = ConnectorRegistry.createWith(supplied);
            found = registry.forType(type);
            if (found.isPresent()) {
                return found.get();
            }
        }
        throw new EvaluationException(
                "No connector registered for type '" + type + "' (" + context + "): " + result.message());
    }

    /** Opens a CSV relation through the registry's built-in {@code "csv"} connector. */
    private Stream<Row> openCsv(String relationName, CsvFileSourceConfig csv, Schema schema) {
        RelixConnector connector = registry.forType("csv").orElseThrow(() -> new EvaluationException(
                "No connector registered for CSV sources (relation '" + relationName + "')"));
        ConnectorConfig config = new ConnectorConfig(Map.of(
                "path", baseDir.resolve(csv.path()).toString(),
                "header", String.valueOf(csv.hasHeader())));
        return connector.open(config, relationName, schema);
    }

    /**
     * Runs a pushed-down native query, dispatching by {@code connectorType}:
     * a {@code jdbc} query goes to the JDBC connector; any other type is routed through
     * the {@link ConnectorRegistry} to the matching {@link RelixConnector}'s
     * {@code openQuery} (e.g. a MongoDB aggregation pipeline), with the connection's raw
     * properties passed as the {@link ConnectorConfig}.
     */
    @Override
    public Stream<Row> openQuery(String connectorType, String connection, String nativeQuery, Schema schema) {
        if (connectorType.equals("jdbc")) {
            return jdbcConnector.openQuery(connection, nativeQuery, schema);
        }
        ConnectionDeclaration declaration = model.connections().get(connection.toLowerCase(Locale.ROOT));
        if (declaration == null) {
            throw new EvaluationException(
                    "No connection declaration for pushed query on '" + connection + "'");
        }
        RelixConnector connector = resolveOrProvision(connectorType, "connection '" + connection + "'");
        return connector.openQuery(new ConnectorConfig(declaration.properties()), nativeQuery, schema);
    }

    @Override
    public void close() {
        registry.close();
        jdbcConnector.close();   // releases the JDBC connection pool
    }

    private static EvaluationException noConnector(String relationName, String kind) {
        return new EvaluationException(
                "No connector configured for " + kind + " relation '" + relationName
                + "' — only CSV, JSON, and connection-table sources can be executed currently");
    }
}

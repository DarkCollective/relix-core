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

import com.darkcollective.relix.ast.LateralJoinNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationFunctionCall;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.source.ApiKeyAuth;
import com.darkcollective.relix.lang.ast.source.AuthSpec;
import com.darkcollective.relix.lang.ast.source.BasicAuth;
import com.darkcollective.relix.lang.ast.source.BearerAuth;
import com.darkcollective.relix.lang.ast.source.ColumnSpec;
import com.darkcollective.relix.lang.ast.source.ConnectionTableSourceConfig;
import com.darkcollective.relix.lang.ast.source.CsvFileSourceConfig;
import com.darkcollective.relix.lang.ast.source.DatabaseSourceConfig;
import com.darkcollective.relix.lang.ast.source.GeneratorSourceConfig;
import com.darkcollective.relix.lang.ast.source.HttpSourceConfig;
import com.darkcollective.relix.lang.ast.source.JsonFileSourceConfig;
import com.darkcollective.relix.lang.ast.source.SourceConfig;
import com.darkcollective.relix.semantic.CatalogProvider;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.RelationStatistics;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.table.SymbolTable;
import com.darkcollective.relix.symbol.function.RelationFunctionSymbol;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the {@code ${NAME}} placeholders in source and connection declarations, for
 * one execution, through the resolver the session was built with (#1102).
 *
 * <p><strong>The model keeps the placeholder.</strong> Resolution produces a copy of the
 * declarations a query reaches, and that copy is what planning and the connector stack
 * are handed for one execution. So a value never passes through the lexer (nothing to
 * escape), never lands in the session's model (nothing for {@code definitions()},
 * {@code ir()} or an analysis error to print), and is asked for again on the next run,
 * which is what lets a rotated token reach the next request.
 *
 * <p><strong>Every connector gets the same treatment by construction.</strong> A
 * connection-based connector — JDBC, a plugin — receives a {@code ConnectorConfig} built
 * from the model's {@code ConnectionDeclaration}, the HTTP and JSON connectors read their
 * declaration from the model, and the planner reads a connection's URL to choose a SQL
 * dialect. Resolving the model resolves all of them, and no connector can opt out.
 * {@link #resolve(SourceConfig, Function)} is an exhaustive switch over the sealed
 * {@code SourceConfig}, so a new kind does not compile until it says what it resolves.
 *
 * <p>Only string <em>values</em> are resolved: URLs, paths, table names, header values,
 * bodies, credentials, column defaults, generator arguments and connection properties.
 * Names — of a header, a column, a property — are not, since analysis has already checked
 * them. The resolver is asked only for the names a query's declarations actually hold,
 * and once per name per execution.
 */
final class Placeholders {

    /** A placeholder: {@code ${NAME}}, where a name may also hold {@code . / -}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_./-]*)}");

    /** The resolver of a session built without one: placeholders stay as written. */
    static final Placeholders NONE = new Placeholders(null);

    private final Function<String, Optional<String>> resolver;

    private Placeholders(Function<String, Optional<String>> resolver) {
        this.resolver = resolver;
    }

    /** {@return placeholders resolved through {@code resolver}} */
    static Placeholders of(Function<String, Optional<String>> resolver) {
        return new Placeholders(resolver);
    }

    /** {@return whether this session resolves placeholders at all} */
    boolean active() {
        return resolver != null;
    }

    /**
     * {@return {@code model} with every declaration {@code root} reaches resolved}
     *
     * @throws RelixException naming the placeholder and the declaration when the
     *                             resolver has no value for one
     */
    SemanticModel resolve(SemanticModel model, RelNode root) {
        if (!active()) {
            return model;
        }
        Execution run = new Execution();
        Map<String, SourceDeclaration> sources = new LinkedHashMap<>(model.sources());
        Map<String, ConnectionDeclaration> connections = new LinkedHashMap<>(model.connections());
        Set<String> reachedConnections = new HashSet<>();
        for (String name : reached(root, model.symbolTable())) {
            SourceDeclaration source = sources.get(name);
            if (source == null) {
                continue;
            }
            sources.put(name, run.resolve(source));
            if (source.config() instanceof ConnectionTableSourceConfig table) {
                reachedConnections.add(table.connection().toLowerCase(Locale.ROOT));
            }
        }
        for (String name : reachedConnections) {
            ConnectionDeclaration connection = connections.get(name);
            if (connection != null) {
                connections.put(name, run.resolve(connection));
            }
        }
        return new SemanticModel(model.namespace(), model.symbolTable(), sources, connections,
                model.statistics(), model.nodeSchemas(), model.schemaGraph(), model.rootQueries(),
                model.functions());
    }

    /**
     * {@return {@code catalog}, asked about each connection with its placeholders resolved}
     *
     * <p>Introspection runs during analysis, so a dotted reference to a table behind
     * {@code ${DB_URL}} would otherwise fail to resolve while the same table opened fine
     * at run time. A placeholder with no value is reported as no answer, which is how the
     * catalog already reports a declaration it cannot open.
     */
    CatalogProvider catalog(CatalogProvider catalog) {
        if (!active()) {
            return catalog;
        }
        return new CatalogProvider() {
            @Override
            public Optional<Schema> tableSchema(ConnectionDeclaration connection, String table) {
                return resolvedOrEmpty(connection).flatMap(c -> catalog.tableSchema(c, table));
            }

            @Override
            public Optional<RelationStatistics> tableStatistics(ConnectionDeclaration connection,
                                                                String table) {
                return resolvedOrEmpty(connection).flatMap(c -> catalog.tableStatistics(c, table));
            }
        };
    }

    private Optional<ConnectionDeclaration> resolvedOrEmpty(ConnectionDeclaration connection) {
        try {
            return Optional.of(new Execution().resolve(connection));
        } catch (RelixException unresolvable) {
            return Optional.empty();
        }
    }

    /**
     * {@return the lower-cased names of every relation {@code root} reaches}, following
     * views and table-valued functions into their bodies, since a source used only inside
     * a view is as much a part of the query as one named in it
     */
    static Set<String> reached(RelNode root, SymbolTable symbols) {
        Set<String> names = new HashSet<>();
        Set<String> expanded = new HashSet<>();
        Deque<RelNode> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            RelNode node = pending.pop();
            switch (node) {
                case RelationNode relation -> {
                    String name = relation.name().toLowerCase(Locale.ROOT);
                    names.add(name);
                    if (expanded.add(name)) {
                        symbols.lookupRelation(relation.name())
                                .filter(QueryRelationSymbol.class::isInstance)
                                .map(QueryRelationSymbol.class::cast)
                                .ifPresent(view -> pending.push(view.body()));
                    }
                }
                case RelationFunctionCall call -> function(call.functionName(), symbols, expanded, pending);
                case LateralJoinNode lateral -> function(lateral.functionName(), symbols, expanded, pending);
                default -> { }
            }
            node.children().forEach(pending::push);
        }
        return names;
    }

    private static void function(String name, SymbolTable symbols, Set<String> expanded,
                                 Deque<RelNode> pending) {
        if (expanded.add("()" + name.toLowerCase(Locale.ROOT))) {
            symbols.resolveFunction(name).stream()
                    .filter(RelationFunctionSymbol.class::isInstance)
                    .map(RelationFunctionSymbol.class::cast)
                    .findFirst()
                    .ifPresent(fn -> pending.push(fn.body()));
        }
    }

    /** One execution: each name is asked for once, however many values hold it. */
    private final class Execution {

        private final Map<String, String> answers = new HashMap<>();

        SourceDeclaration resolve(SourceDeclaration source) {
            String owner = "source '" + source.name() + "'";
            try {
                return new SourceDeclaration(source.exported(), source.name(),
                        Placeholders.this.resolve(source.config(), text -> value(text, owner)),
                        source.location());
            } catch (IllegalArgumentException e) {
                // A placeholder whose value leaves a required field blank.
                throw new RelixException(owner + " is not valid once its placeholders are "
                        + "substituted: " + e.getMessage());
            }
        }

        ConnectionDeclaration resolve(ConnectionDeclaration connection) {
            String owner = "connection '" + connection.name() + "'";
            Map<String, String> properties = new LinkedHashMap<>();
            connection.properties().forEach((k, v) -> properties.put(k, value(v, owner)));
            return new ConnectionDeclaration(connection.exported(), connection.name(),
                    connection.connectorType(), properties, connection.location());
        }

        /** {@return {@code text} with each placeholder replaced by its value} */
        String value(String text, String owner) {
            if (text.indexOf("${") < 0) {
                return text;
            }
            Matcher m = PLACEHOLDER.matcher(text);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String name = m.group(1);
                String answer = answers.computeIfAbsent(name, n -> resolver.apply(n).orElse(null));
                if (answer == null) {
                    throw new RelixException("placeholder ${" + name + "} in " + owner
                            + " has no value: the session's placeholder resolver returned none");
                }
                m.appendReplacement(out, Matcher.quoteReplacement(answer));
            }
            m.appendTail(out);
            return out.toString();
        }
    }

    /**
     * {@return {@code config} with every string value passed through {@code value}}
     *
     * <p>Exhaustive over {@code SourceConfig}: a new kind of source does not compile until
     * it states what it resolves.
     */
    SourceConfig resolve(SourceConfig config, Function<String, String> value) {
        return switch (config) {
            case HttpSourceConfig http -> new HttpSourceConfig(
                    value.apply(http.url()), http.method(),
                    values(http.headers(), value), http.extract(), http.paginate(),
                    columns(http.columns(), value), http.body().map(value),
                    http.auth().map(auth -> auth(auth, value)));
            case DatabaseSourceConfig db -> new DatabaseSourceConfig(
                    value.apply(db.url()), value.apply(db.table()),
                    columns(db.columns(), value), db.references());
            case CsvFileSourceConfig csv -> new CsvFileSourceConfig(
                    value.apply(csv.path()), csv.hasHeader(), columns(csv.columns(), value),
                    csv.references());
            case JsonFileSourceConfig json -> new JsonFileSourceConfig(
                    value.apply(json.path()), json.records(), json.references());
            case ConnectionTableSourceConfig table -> new ConnectionTableSourceConfig(
                    table.connection(), value.apply(table.table()),
                    columns(table.columns(), value), table.references());
            case GeneratorSourceConfig generator -> new GeneratorSourceConfig(
                    generator.generatorName(), values(generator.args(), value));
        };
    }

    private static AuthSpec auth(AuthSpec auth, Function<String, String> value) {
        return switch (auth) {
            case BearerAuth bearer -> new BearerAuth(value.apply(bearer.token()));
            case BasicAuth basic -> new BasicAuth(value.apply(basic.username()), value.apply(basic.password()));
            case ApiKeyAuth key -> new ApiKeyAuth(key.name(), value.apply(key.value()), key.location());
        };
    }

    private static Map<String, String> values(Map<String, String> map, Function<String, String> value) {
        Map<String, String> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(k, value.apply(v)));
        return out;
    }

    private static List<ColumnSpec> columns(List<ColumnSpec> columns, Function<String, String> value) {
        return columns.stream()
                .map(c -> c.defaultValue().isEmpty() ? c : new ColumnSpec(c.direction(), c.name(),
                        c.type(), c.binding(), c.required(), c.defaultValue().map(value)))
                .toList();
    }
}

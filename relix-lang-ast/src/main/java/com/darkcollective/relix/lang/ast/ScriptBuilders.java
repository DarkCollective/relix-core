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
package com.darkcollective.relix.lang.ast;

import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.lang.ast.source.ColumnDirection;
import com.darkcollective.relix.lang.ast.source.ColumnReference;
import com.darkcollective.relix.lang.ast.source.ColumnSpec;
import com.darkcollective.relix.lang.ast.source.ConnectionTableSourceConfig;
import com.darkcollective.relix.lang.ast.source.CsvFileSourceConfig;
import com.darkcollective.relix.lang.ast.source.DatabaseSourceConfig;
import com.darkcollective.relix.lang.ast.source.GeneratorSourceConfig;
import com.darkcollective.relix.lang.ast.source.HttpMethod;
import com.darkcollective.relix.lang.ast.source.HttpSourceConfig;
import com.darkcollective.relix.lang.ast.source.JsonFileSourceConfig;
import com.darkcollective.relix.lang.ast.source.SourceConfig;
import com.darkcollective.relix.lang.ast.table.CsvInlineTable;
import com.darkcollective.relix.lang.ast.table.MarkdownInlineTable;
import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.ArrayType;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.StructType;
import com.darkcollective.relix.symbol.Type;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@link Script} authoring surface — the statement-level half of the AST contract,
 * for tests and embedders that assemble a whole script without the {@code .relix} grammar.
 *
 * <p>{@code SemanticAnalyzer.analyze(Script, rootPath)} is the engine's front door, so a
 * caller who can build a {@code Script} needs no parser at all. These factories are how
 * that is done readably, and {@code ScriptBuilderCoverageTest} fails the build if a new
 * {@link Statement} or {@link SourceConfig} kind arrives without one.
 *
 * <p>It <strong>extends {@link AstBuilders}</strong>, so one {@code extends ScriptBuilders}
 * — or a static import of this class — reaches the relational factories too:
 *
 * {@snippet lang = "java":
 * Script script = script(
 *         source("Orders", csvSource("orders.csv", column("id", ScalarType.NUMBER))),
 *         assign("Open", select(cmp(attr("status"), ComparisonOperator.EQUAL, str("OPEN")),
 *                               rel("Orders"))),
 *         query("Open"));
 * }
 *
 * <p>The same rule as {@code AstBuilders} applies: a factory takes the record's own
 * components in the record's own order, minus {@link SourceLocation}, which defaults to
 * {@link SourceLocation#UNKNOWN}. Statements that carry an {@code exported} flag get a
 * two-form treatment — the short factory is the un-exported common case, and an explicit
 * overload takes the flag.
 */
public abstract class ScriptBuilders extends AstBuilders {

    // -------------------------------------------------------------------------
    // Script
    // -------------------------------------------------------------------------

    /** A script in the default namespace. */
    public static Script script(Statement... statements) {
        return new Script(Optional.empty(), List.of(statements));
    }

    /** A script declaring {@code namespace <name>;}. */
    public static Script script(String namespace, Statement... statements) {
        return new Script(Optional.of(namespace), List.of(statements));
    }

    // -------------------------------------------------------------------------
    // Statements — one factory per Statement kind
    // -------------------------------------------------------------------------

    /** {@code env} with the default file and no active environment. */
    public static EnvStatement env() {
        return EnvStatement.defaults();
    }

    /** {@code env "<file>" [as <name>]}. */
    public static EnvStatement env(Optional<String> filePath, Optional<String> activeEnv) {
        return new EnvStatement(filePath, activeEnv);
    }

    /** {@code import "<path>"} — a bulk import of everything the script declares. */
    public static ImportStatement importAll(String sourcePath) {
        return new ImportStatement(ImportKind.BULK, List.of(), sourcePath);
    }

    /** A named import, e.g. {@code import relation Orders from "orders.relix"}. */
    public static ImportStatement importNames(ImportKind kind, List<String> names, String sourcePath) {
        return new ImportStatement(kind, names, sourcePath);
    }

    /** {@code connection <name> from <type> { … }}. */
    public static ConnectionDeclaration connection(String name, String connectorType,
                                                   Map<String, String> properties) {
        return new ConnectionDeclaration(false, name, connectorType, properties,
                SourceLocation.UNKNOWN);
    }

    /**
     * {@code connection <name> from jdbc { url: … }} — the JDBC shorthand the record
     * itself offers, exported.
     */
    public static ConnectionDeclaration connection(
            String name, com.darkcollective.relix.lang.ast.source.DatabaseConnectionConfig config) {
        return new ConnectionDeclaration(name, config);
    }

    /** {@code connection}, exported or not. */
    public static ConnectionDeclaration connection(boolean exported, String name,
                                                   String connectorType,
                                                   Map<String, String> properties) {
        return new ConnectionDeclaration(exported, name, connectorType, properties,
                SourceLocation.UNKNOWN);
    }

    /** {@code source <name> from <config>}. */
    public static SourceDeclaration source(String name, SourceConfig config) {
        return new SourceDeclaration(false, name, config);
    }

    /** {@code source}, exported or not. */
    public static SourceDeclaration source(boolean exported, String name, SourceConfig config) {
        return new SourceDeclaration(exported, name, config);
    }

    /** {@code relate <name> …} — one directed relationship edge of the schema graph. */
    public static RelateStatement relate(String name, EndpointSpec source, EndpointSpec target) {
        return new RelateStatement(name, Optional.empty(), false, source, target,
                SourceLocation.UNKNOWN);
    }

    /** {@code relate}, with an inverse name and/or symmetry. */
    public static RelateStatement relate(String name, Optional<String> inverseName, boolean symmetric,
                                         EndpointSpec source, EndpointSpec target) {
        return new RelateStatement(name, inverseName, symmetric, source, target,
                SourceLocation.UNKNOWN);
    }

    /** An endpoint of a {@code relate}, with the default {@code [0..*]} bounds. */
    public static EndpointSpec endpoint(String relationRef, List<String> columns) {
        return EndpointSpec.unbounded(relationRef, columns, SourceLocation.UNKNOWN);
    }

    /** An endpoint with explicit cardinality bounds. */
    public static EndpointSpec endpoint(String relationRef, List<String> columns,
                                        long min, java.util.OptionalLong max) {
        return new EndpointSpec(relationRef, columns, min, max, SourceLocation.UNKNOWN);
    }

    /** {@code Name := { <expression> };} — a view binding. */
    public static AssignmentStatement assign(String name, RelNode expression) {
        return new AssignmentStatement(false, name, new QueryAssignmentBody(expression));
    }

    /** A view binding, exported or not. */
    public static AssignmentStatement assign(boolean exported, String name, RelNode expression) {
        return new AssignmentStatement(exported, name, new QueryAssignmentBody(expression));
    }

    /** {@code Name := [ … ];} — an inline-table binding. */
    public static AssignmentStatement assign(String name,
                                             com.darkcollective.relix.lang.ast.table.InlineTable table) {
        return new AssignmentStatement(false, name, new InlineTableBody(table));
    }

    /** An inline-table binding, exported or not. */
    public static AssignmentStatement assign(boolean exported, String name,
                                             com.darkcollective.relix.lang.ast.table.InlineTable table) {
        return new AssignmentStatement(exported, name, new InlineTableBody(table));
    }

    /** {@code def <name>(…): <type> = <operand>} — a scalar function. */
    public static DefStatement def(String name, List<ParameterDefinition> parameters,
                                   ScalarType returnType, Operand body) {
        return new DefStatement(false, name, parameters, returnType, body);
    }

    /** A scalar {@code def}, exported or not. */
    public static DefStatement def(boolean exported, String name, List<ParameterDefinition> parameters,
                                   ScalarType returnType, Operand body) {
        return new DefStatement(exported, name, parameters, returnType, body);
    }

    /** {@code def relation <name>(…) = { … }} — a table-valued function. */
    public static DefRelationStatement defRelation(String name, List<ParameterDefinition> parameters,
                                                   RelNode body) {
        return new DefRelationStatement(false, name, parameters, body);
    }

    /** A relation {@code def}, exported or not. */
    public static DefRelationStatement defRelation(boolean exported, String name,
                                                   List<ParameterDefinition> parameters, RelNode body) {
        return new DefRelationStatement(exported, name, parameters, body);
    }

    /** {@code query { <Name> };} — a query naming an already-bound relation. */
    public static QueryStatement query(String name) {
        return new QueryStatement(new NamedQueryTarget(name));
    }

    /** {@code query { <expression> };} — a query over an anonymous expression. */
    public static QueryStatement query(RelNode expression) {
        return new QueryStatement(new ExpressionQueryTarget(expression));
    }

    // -------------------------------------------------------------------------
    // Statement members
    // -------------------------------------------------------------------------

    /** A function parameter, {@code name: type}. */
    public static ParameterDefinition param(String name, ScalarType type) {
        return new ParameterDefinition(name, type);
    }

    /** An output column of a source schema. */
    public static ColumnSpec column(String name, ScalarType type) {
        return ColumnSpec.out(name, type);
    }

    /**
     * An output column of a nested type — a struct, or an array of anything.
     *
     * <p>Separate from the {@link ScalarType} overload rather than replacing it, so the
     * common case still reads {@code column("id", ScalarType.NUMBER)} without a cast.
     */
    public static ColumnSpec column(String name, Type type) {
        return new ColumnSpec(ColumnDirection.OUT, name, type,
                java.util.Optional.empty(), false, java.util.Optional.empty());
    }

    /** A struct field, for building a nested column type. */
    public static StructType.Field field(String name, Type type) {
        return new StructType.Field(name, type);
    }

    /** A struct type over the given fields. */
    public static StructType struct(StructType.Field... fields) {
        return new StructType(List.of(fields));
    }

    /** An array type over {@code element}. */
    public static ArrayType array(Type element) {
        return new ArrayType(element);
    }

    /** An output column bound to an extraction path. */
    public static ColumnSpec column(String name, ScalarType type, String path) {
        return ColumnSpec.out(name, type, path);
    }

    /** A markdown inline table — the pipe-delimited literal form. */
    public static MarkdownInlineTable markdownTable(List<String> headers, List<List<String>> rows) {
        return new MarkdownInlineTable(headers, rows);
    }

    /** A CSV inline table. */
    public static CsvInlineTable csvTable(List<String> headers, List<List<String>> rows) {
        return new CsvInlineTable(headers, rows);
    }

    // -------------------------------------------------------------------------
    // Source configs — one factory per SourceConfig kind
    // -------------------------------------------------------------------------

    /** {@code from csv { … }} with a header row. */
    public static CsvFileSourceConfig csvSource(String path, ColumnSpec... columns) {
        return new CsvFileSourceConfig(path, true, List.of(columns));
    }

    /** {@code from csv { … }}, header row optional. */
    public static CsvFileSourceConfig csvSource(String path, boolean hasHeader,
                                                List<ColumnSpec> columns) {
        return new CsvFileSourceConfig(path, hasHeader, columns);
    }

    /** {@code from json { … }} reading the whole document. */
    public static JsonFileSourceConfig jsonSource(String path) {
        return new JsonFileSourceConfig(path, Optional.empty());
    }

    /** {@code from json { … }} reading a records path. */
    public static JsonFileSourceConfig jsonSource(String path, Optional<String> records) {
        return new JsonFileSourceConfig(path, records);
    }

    /** {@code from database { url: …, table: … }}. */
    public static DatabaseSourceConfig databaseSource(String url, String table,
                                                      ColumnSpec... columns) {
        return new DatabaseSourceConfig(url, table, List.of(columns));
    }

    /** {@code from <connection>.<table>}. */
    public static ConnectionTableSourceConfig connectionTable(String connection, String table,
                                                              ColumnSpec... columns) {
        return new ConnectionTableSourceConfig(connection, table, List.of(columns));
    }

    /**
     * A foreign-key edge for a source's {@code references:} block, {@code cols ->
     * Target.cols} — one edge of the schema graph a script declares.
     *
     * @param sourceColumns this source's columns; never empty
     * @param targetRelation the relation the edge points at
     * @param targetColumns the target's columns, paired with {@code sourceColumns}
     * @return the reference
     */
    public static ColumnReference reference(List<String> sourceColumns, String targetRelation,
                                            List<String> targetColumns) {
        return new ColumnReference(sourceColumns, targetRelation, targetColumns,
                SourceLocation.UNKNOWN);
    }

    /** The single-column form of {@link #reference(List, String, List)}. */
    public static ColumnReference reference(String sourceColumn, String targetRelation,
                                            String targetColumn) {
        return reference(List.of(sourceColumn), targetRelation, List.of(targetColumn));
    }

    /**
     * {@code from csv { …, references: { … } }}.
     *
     * <p>The three configurations that can carry a {@code references:} block each take
     * one, so the schema graph a script declares is expressible from here. Without them
     * the component existed on the record and on no factory, which is how it came to be
     * dropped by the printer with a full round-trip corpus in place.
     */
    public static CsvFileSourceConfig csvSource(String path, boolean hasHeader,
                                                List<ColumnSpec> columns,
                                                List<ColumnReference> references) {
        return new CsvFileSourceConfig(path, hasHeader, columns, references);
    }

    /** {@code from database { …, references: { … } }}. */
    public static DatabaseSourceConfig databaseSource(String url, String table,
                                                      List<ColumnSpec> columns,
                                                      List<ColumnReference> references) {
        return new DatabaseSourceConfig(url, table, columns, references);
    }

    /** {@code from <connection>.<table> { references: { … } }}. */
    public static ConnectionTableSourceConfig connectionTable(String connection, String table,
                                                              List<ColumnSpec> columns,
                                                              List<ColumnReference> references) {
        return new ConnectionTableSourceConfig(connection, table, columns, references);
    }

    /** {@code from json { …, references: { … } }}. */
    public static JsonFileSourceConfig jsonSource(String path, Optional<String> records,
                                                  List<ColumnReference> references) {
        return new JsonFileSourceConfig(path, records, references);
    }

    /** {@code from http { … }} — a GET with no headers or extraction. */
    public static HttpSourceConfig httpSource(String url, ColumnSpec... columns) {
        return new HttpSourceConfig(url, HttpMethod.GET, Map.of(), Optional.empty(),
                Optional.empty(), List.of(columns), Optional.empty(), Optional.empty());
    }

    /** {@code from http { … }}, fully specified. */
    public static HttpSourceConfig httpSource(String url, HttpMethod method,
                                              Map<String, String> headers,
                                              Optional<com.darkcollective.relix.lang.ast.source.ExtractSpec> extract,
                                              Optional<com.darkcollective.relix.lang.ast.source.PaginateSpec> paginate,
                                              List<ColumnSpec> columns, Optional<String> body,
                                              Optional<com.darkcollective.relix.lang.ast.source.AuthSpec> auth) {
        return new HttpSourceConfig(url, method, headers, extract, paginate, columns, body, auth);
    }

    /** {@code from generator <name> { … }}. */
    public static GeneratorSourceConfig generatorSource(String generatorName, Map<String, String> args) {
        return new GeneratorSourceConfig(generatorName, args);
    }
}

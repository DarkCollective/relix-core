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

import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.visitor.internal.OperandPrettyPrinter;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.lang.ast.source.ColumnBinding;
import com.darkcollective.relix.lang.ast.source.ApiKeyAuth;
import com.darkcollective.relix.lang.ast.source.AuthSpec;
import com.darkcollective.relix.lang.ast.source.BasicAuth;
import com.darkcollective.relix.lang.ast.source.BearerAuth;
import com.darkcollective.relix.lang.ast.source.PaginateEntry;
import com.darkcollective.relix.lang.ast.source.PaginateSpec;
import com.darkcollective.relix.lang.ast.source.ColumnReference;
import com.darkcollective.relix.lang.ast.source.ColumnSpec;
import com.darkcollective.relix.lang.ast.source.ConnectionTableSourceConfig;
import com.darkcollective.relix.lang.ast.source.CsvExtractSpec;
import com.darkcollective.relix.lang.ast.source.CsvFileSourceConfig;
import com.darkcollective.relix.lang.ast.source.DatabaseSourceConfig;
import com.darkcollective.relix.lang.ast.source.ExtractPathBinding;
import com.darkcollective.relix.lang.ast.source.ExtractSpec;
import com.darkcollective.relix.lang.ast.source.GeneratorSourceConfig;
import com.darkcollective.relix.lang.ast.source.HeaderBinding;
import com.darkcollective.relix.lang.ast.source.HttpSourceConfig;
import com.darkcollective.relix.lang.ast.source.JsonExtractSpec;
import com.darkcollective.relix.lang.ast.source.JsonFileSourceConfig;
import com.darkcollective.relix.lang.ast.source.PathParamBinding;
import com.darkcollective.relix.lang.ast.source.QueryParamBinding;
import com.darkcollective.relix.lang.ast.source.SourceConfig;
import com.darkcollective.relix.lang.ast.table.CsvInlineTable;
import com.darkcollective.relix.lang.ast.table.MarkdownInlineTable;
import com.darkcollective.relix.symbol.ArrayType;
import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.StructType;
import com.darkcollective.relix.symbol.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Stream;

/**
 * Renders a {@link Script} — or one {@link Statement} — back as {@code .relix} text.
 *
 * <p>The statement-level counterpart of {@code PrettyPrinter}, which does the same for
 * a relational expression and which this delegates to for every {@link RelNode} and
 * {@link Operand} it reaches.
 *
 * <h2>The property this exists for</h2>
 *
 * <p>Printing is only useful if the text reads back as the same script:
 * {@code parse(print(s))} must equal {@code s}, modulo source locations. That is what
 * makes a script a thing a program can hold, transform and hand back — and it is the
 * same promise {@code PrettyPrinter} already carries for an expression, where it is
 * load-bearing enough that {@code AstEquivalence} compares two nodes <em>through</em> it.
 *
 * <p>Without this, a session assembled by building an AST has no text form at all: the
 * REPL's {@code :save} works only because it replays the source it captured, which a
 * script that was never parsed does not have.
 *
 * <h2>Formatting is not part of the promise</h2>
 *
 * <p>The output is normalised, not preserved: one statement per line, a canonical
 * spelling of each keyword, and comments dropped — a comment is not in the AST, so no
 * printer can return one. What is promised is that the text parses back to an equal
 * script, never that it matches the text someone originally wrote.
 */
public final class ScriptPrinter {

    private ScriptPrinter() {
    }

    /**
     * Renders a whole script, namespace first, one statement per line.
     *
     * @param script the script to render; must not be null
     * @return the {@code .relix} text
     */
    public static String print(Script script) {
        StringBuilder out = new StringBuilder();
        script.namespace().ifPresent(ns -> out.append("namespace ").append(ns).append(";\n"));
        for (Statement statement : script.statements()) {
            out.append(print(statement)).append('\n');
        }
        return out.toString();
    }

    /**
     * Renders one statement, including its trailing semicolon.
     *
     * @param statement the statement to render; must not be null
     * @return the {@code .relix} text for that statement
     */
    public static String print(Statement statement) {
        return switch (statement) {
            case EnvStatement s -> env(s);
            case ImportStatement s -> importStatement(s);
            case ConnectionDeclaration s -> connection(s);
            case SourceDeclaration s -> source(s);
            case RelateStatement s -> relate(s);
            case AssignmentStatement s -> assignment(s);
            case DefStatement s -> def(s);
            case DefRelationStatement s -> defRelation(s);
            case QueryStatement s -> query(s);
        };
    }

    // -------------------------------------------------------------------------
    // Statements
    // -------------------------------------------------------------------------

    private static String env(EnvStatement s) {
        StringBuilder sb = new StringBuilder("env");
        s.filePath().ifPresent(p -> sb.append(" from ").append(quote(p)));
        s.activeEnv().ifPresent(e -> sb.append(" using ").append(quote(e)));
        return sb.append(';').toString();
    }

    private static String importStatement(ImportStatement s) {
        StringBuilder sb = new StringBuilder("import ");
        switch (s.kind()) {
            case BULK -> { }
            case UNQUALIFIED -> sb.append(namedList(s.names())).append(" from ");
            case SOURCE -> sb.append("source ").append(namedList(s.names())).append(" from ");
            case RELATION -> sb.append("relation ").append(namedList(s.names())).append(" from ");
            case FUNCTION -> sb.append("function ").append(namedList(s.names())).append(" from ");
        }
        return sb.append(quote(s.sourcePath())).append(';').toString();
    }

    /** A one-name import needs no braces; several do. */
    private static String namedList(List<String> names) {
        if (names.size() == 1) {
            return names.getFirst();
        }
        StringJoiner joiner = new StringJoiner(", ", "{ ", " }");
        names.forEach(joiner::add);
        return joiner.toString();
    }

    private static String connection(ConnectionDeclaration s) {
        return (s.exported() ? "" : "private ")
                + "connection " + s.name() + " from " + s.connectorType()
                + " " + properties(s.properties()) + ";";
    }

    private static String source(SourceDeclaration s) {
        return (s.exported() ? "" : "private ")
                + "source " + s.name() + " from " + config(s.config()) + ";";
    }

    private static String relate(RelateStatement s) {
        StringBuilder sb = new StringBuilder("relate ");
        if (s.symmetric()) {
            sb.append("symmetric ");
        }
        sb.append(quote(s.name()));
        s.inverseName().ifPresent(i -> sb.append(" / ").append(quote(i)));
        return sb.append(' ').append(endpoint(s.source()))
                .append(" -> ").append(endpoint(s.target()))
                .append(cardinality(s.target()))
                .append(';').toString();
    }

    private static String endpoint(EndpointSpec e) {
        if (e.columns().size() == 1) {
            return e.relationRef() + "." + e.columns().getFirst();
        }
        StringJoiner joiner = new StringJoiner(", ", "(", ")");
        e.columns().forEach(joiner::add);
        return e.relationRef() + joiner;
    }

    /** {@code [min..max]}, printed only when it is not the implicit default. */
    private static String cardinality(EndpointSpec target) {
        if (target.min() == 0 && target.max().isEmpty()) {
            return "";
        }
        return " [" + target.min() + ".."
                + (target.max().isPresent() ? Long.toString(target.max().getAsLong()) : "*") + "]";
    }

    private static String assignment(AssignmentStatement s) {
        String prefix = (s.exported() ? "" : "private ") + s.name() + " := ";
        return switch (s.body()) {
            case QueryAssignmentBody b -> prefix + "{ " + expression(b.expression()) + " };";
            case InlineTableBody b -> prefix + inlineTable(b) + ";";
        };
    }

    private static String inlineTable(InlineTableBody body) {
        String table = switch (body.table()) {
            case MarkdownInlineTable t -> markdown(t);
            case CsvInlineTable t -> csv(t);
        };
        return table + references(body.references(), " references ");
    }

    private static String markdown(MarkdownInlineTable t) {
        StringBuilder sb = new StringBuilder("[\n");
        sb.append(row(t.headers()));
        StringJoiner rule = new StringJoiner("|", "|", "|");
        t.headers().forEach(h -> rule.add("-".repeat(Math.max(3, h.length() + 2))));
        sb.append(rule).append('\n');
        t.rows().forEach(r -> sb.append(row(r)));
        return sb.append(']').toString();
    }

    private static String row(List<String> cells) {
        StringJoiner joiner = new StringJoiner(" | ", "| ", " |\n");
        cells.forEach(joiner::add);
        return joiner.toString();
    }

    private static String csv(CsvInlineTable t) {
        StringBuilder sb = new StringBuilder("csv[\n");
        sb.append("  ").append(String.join(", ", t.headers())).append('\n');
        for (List<String> r : t.rows()) {
            sb.append("  ").append(String.join(", ", r)).append('\n');
        }
        return sb.append(']').toString();
    }

    private static String def(DefStatement s) {
        return (s.exported() ? "" : "private ")
                + "def " + s.name() + parameters(s.parameters())
                + " : " + s.returnType().display()
                + " := { " + expression(s.body()) + " };";
    }

    private static String defRelation(DefRelationStatement s) {
        return (s.exported() ? "" : "private ")
                + "def " + s.name() + parameters(s.parameters())
                + " : RELATION := { " + expression(s.body()) + " };";
    }

    private static String parameters(List<ParameterDefinition> parameters) {
        StringJoiner joiner = new StringJoiner(", ", "(", ")");
        parameters.forEach(p -> joiner.add(p.name() + ": " + p.type().display()));
        return joiner.toString();
    }

    private static String query(QueryStatement s) {
        return switch (s.target()) {
            case NamedQueryTarget t -> "query " + t.name() + ";";
            case ExpressionQueryTarget t -> "query { " + expression(t.expression()) + " };";
        };
    }

    // -------------------------------------------------------------------------
    // Source configurations
    // -------------------------------------------------------------------------

    private static String config(SourceConfig config) {
        return switch (config) {
            case DatabaseSourceConfig c -> "database " + block(
                    entry("url", quote(c.url())),
                    entry("table", quote(c.table())),
                    schemaEntry(c.columns()),
                    references(c.references(), "references: "));
            case ConnectionTableSourceConfig c -> c.connection() + " " + block(
                    entry("table", quote(c.table())),
                    schemaEntry(c.columns()),
                    references(c.references(), "references: "));
            case CsvFileSourceConfig c -> "csv(" + quote(c.path()) + ") " + block(
                    entry("header", Boolean.toString(c.hasHeader())),
                    schemaEntry(c.columns()),
                    references(c.references(), "references: "));
            case JsonFileSourceConfig c -> "json(" + quote(c.path()) + ") " + block(
                    c.records().map(r -> entry("records", quote(r))).orElse(null),
                    references(c.references(), "references: "));
            // The generator's name is a field of the block, not a token before it.
            case GeneratorSourceConfig c -> "generator " + block(
                    Stream.concat(
                            Stream.of(entry("name", quote(c.generatorName()))),
                            c.args().entrySet().stream()
                                    .map(a -> entry(a.getKey(), quote(a.getValue()))))
                            .toArray(String[]::new));
            case HttpSourceConfig c -> http(c);
        };
    }

    private static String http(HttpSourceConfig c) {
        List<String> entries = new ArrayList<>();
        entries.add(entry("url", quote(c.url())));
        entries.add(entry("method", c.method().name()));
        if (!c.headers().isEmpty()) {
            entries.add(entry("headers", headers(c.headers())));
        }
        c.body().ifPresent(b -> entries.add(entry("body", quote(b))));
        c.auth().ifPresent(a -> entries.add(entry("auth", auth(a))));
        c.extract().ifPresent(e -> entries.add(entry("extract", extract(e))));
        c.paginate().ifPresent(pg -> entries.add(entry("paginate", paginate(pg))));
        String schema = schemaEntry(c.columns());
        if (schema != null) {
            entries.add(schema);
        }
        return "http " + block(entries.toArray(String[]::new));
    }

    /**
     * {@code bearer("…")} / {@code basic("…", "…")} / {@code apikey(…, "…")}.
     *
     * <p>The credential is printed as it was given. A printed script is the script the
     * session holds, and a session that reads back without its credentials is one whose
     * source cannot be opened — redacting here would produce text that parses and does
     * not work, which is worse than either alternative.
     */
    private static String auth(AuthSpec spec) {
        return switch (spec) {
            case BearerAuth a -> "bearer(" + quote(a.token()) + ")";
            case BasicAuth a -> "basic(" + quote(a.username()) + ", " + quote(a.password()) + ")";
            case ApiKeyAuth a -> "apikey("
                    + (a.location() == ApiKeyAuth.ApiKeyLocation.QUERY
                            ? "query(" + quote(a.name()) + ")"
                            : quote(a.name()))
                    + ", " + quote(a.value()) + ")";
        };
    }

    /** {@code { name: query("param") [default: n], … }}. */
    private static String paginate(PaginateSpec spec) {
        StringJoiner joiner = new StringJoiner(", ", "{ ", " }");
        for (PaginateEntry entry : spec.entries()) {
            joiner.add(entry.logicalName() + ": query(" + quote(entry.paramName()) + ")"
                    + entry.defaultValue()
                            .map(d -> " [default: " + d + "]")
                            .orElse(""));
        }
        return joiner.toString();
    }

    private static String extract(ExtractSpec spec) {
        return switch (spec) {
            case JsonExtractSpec s -> "json(" + quote(s.jsonPath()) + ")";
            case CsvExtractSpec s -> "csv(header: " + s.hasHeader() + ")";
        };
    }

    /** {@code schema: { name: TYPE at "path", … }}, or null when no columns are declared. */
    private static String schemaEntry(List<ColumnSpec> columns) {
        if (columns.isEmpty()) {
            return null;
        }
        StringJoiner joiner = new StringJoiner(", ", "{ ", " }");
        for (ColumnSpec column : columns) {
            joiner.add(columnName(column.name()) + ": " + type(column.type()) + binding(column));
        }
        return entry("schema", joiner.toString());
    }

    /**
     * A column or struct-field name, backtick-delimited when it is not a plain
     * identifier. A database names its own columns, so {@code unit-price} is as legal
     * here as {@code price}.
     */
    private static String columnName(String name) {
        boolean plain = !name.isEmpty()
                && (Character.isLetter(name.charAt(0)) || name.charAt(0) == '_')
                && name.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_');
        return plain ? name : "`" + name.replace("`", "``") + "`";
    }

    /**
     * A column type in the grammar's own spelling: {@code { field: type }} for a struct
     * and {@code [type]} for an array. {@code Type.display()} is a spelling for people,
     * and the parser reads neither {@code struct{…}} nor {@code array<…>}.
     */
    private static String type(Type type) {
        return switch (type) {
            case ScalarType scalar -> scalar.display();
            case StructType struct -> {
                StringJoiner fields = new StringJoiner(", ", "{ ", " }");
                struct.fields().forEach(f -> fields.add(columnName(f.name()) + ": " + type(f.type())));
                yield fields.toString();
            }
            case ArrayType array -> "[" + type(array.element()) + "]";
        };
    }

    private static String binding(ColumnSpec column) {
        return column.binding().map(b -> switch (b) {
            case ExtractPathBinding p -> " at " + quote(p.path());
            case QueryParamBinding p -> " from query(" + quote(p.paramName()) + ")";
            case PathParamBinding p -> " from path(" + quote(p.paramName()) + ")";
            case HeaderBinding p -> " from header(" + quote(p.headerName()) + ")";
        }).orElse("");
    }

    private static String references(List<ColumnReference> references, String prefix) {
        if (references.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner(", ", "{ ", " }");
        for (ColumnReference reference : references) {
            joiner.add(sourceColumns(reference.sourceColumns()) + " -> "
                    + target(reference.targetRelation(), reference.targetColumns()));
        }
        return prefix + joiner;
    }

    /** The referencing side: one bare column, or a parenthesised list of them. */
    private static String sourceColumns(List<String> columns) {
        return columns.size() == 1 ? columns.getFirst() : "(" + String.join(", ", columns) + ")";
    }

    /**
     * The referenced side, in the two forms the grammar spells it: {@code Rel.col} for a
     * single column, and {@code Rel(col, …)} for a composite key.
     *
     * <p>The dot belongs to the single-column form alone. Printing {@code Rel.(a, b)} —
     * the dot form with a list after it — is not a spelling the parser has, so a composite
     * foreign key printed that way could not be read back at all.
     */
    private static String target(String relation, List<String> columns) {
        return columns.size() == 1
                ? relation + "." + columns.getFirst()
                : relation + "(" + String.join(", ", columns) + ")";
    }

    // -------------------------------------------------------------------------
    // Shared pieces
    // -------------------------------------------------------------------------

    /**
     * A {@code connection}'s {@code { key: "value" }} block, whose keys are bare names.
     */
    private static String properties(Map<String, String> properties) {
        StringJoiner joiner = new StringJoiner(", ", "{ ", " }");
        properties.forEach((key, value) -> joiner.add(key + ": " + quote(value)));
        return joiner.toString();
    }

    /**
     * An HTTP source's {@code headers:} block, whose keys are <em>quoted</em>.
     *
     * <p>Not the same block as a connection's, though they read alike: a header name is
     * {@code "Content-Type"}, which is not a name the lexer can produce, so the grammar
     * takes a string literal on both sides. Printing a header the way a connection
     * property is printed produces a block that cannot be parsed at all.
     */
    private static String headers(Map<String, String> headers) {
        StringJoiner joiner = new StringJoiner(", ", "{ ", " }");
        headers.forEach((key, value) -> joiner.add(quote(key) + ": " + quote(value)));
        return joiner.toString();
    }

    /** A brace block of the given entries, skipping the null ones. */
    private static String block(String... entries) {
        StringJoiner joiner = new StringJoiner(", ", "{ ", " }");
        for (String entry : entries) {
            if (entry != null && !entry.isEmpty()) {
                joiner.add(entry);
            }
        }
        return joiner.toString();
    }

    private static String entry(String key, String value) {
        return key + ": " + value;
    }

    private static String expression(RelNode node) {
        return node.prettyPrint();
    }

    private static String expression(Operand operand) {
        return operand.accept(new OperandPrettyPrinter());
    }

    /** Double-quotes a value, escaping embedded quotes and backslashes. */
    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}

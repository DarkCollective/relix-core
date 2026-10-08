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

import com.darkcollective.relix.ast.Comment;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.Spelling;
import com.darkcollective.relix.ast.visitor.internal.OperandPrettyPrinter;
import com.darkcollective.relix.ast.visitor.internal.PrettyPrinter;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.lang.ast.source.ColumnBinding;
import com.darkcollective.relix.lang.ast.source.ColumnDirection;
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
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.StructType;
import com.darkcollective.relix.symbol.Type;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.TreeMap;
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
 * <p>The output is normalised, not preserved: one statement per line, and a canonical
 * spelling of each keyword. What is promised is that the text reads back as the same
 * script, never that it matches the text someone originally wrote.
 *
 * <h2>Comments</h2>
 *
 * <p>A parsed script keeps its comments ({@link Script#comments()}), and printing it puts
 * each one back by the code it was next to: on its own line before the statement it
 * preceded, after the {@code ;} it followed on the same line, or — for one inside a
 * statement — immediately before the expression it preceded. A comment comes back as it
 * was written, and in the order it was written; the code around it is normalised as
 * ever. A blank line between statements is kept, as one. A comment after a row of a
 * Markdown inline table comes back after that row. Inside a statement with no
 * expression to stand by, a comment is printed just before the {@code ;}, which keeps it
 * inside the statement.
 *
 * <h2>Spelling</h2>
 *
 * <p>Operators are written as glyphs by default ({@code σ}, {@code ⋈}). With
 * {@link Spelling#KEYWORDS} they are written in ASCII ({@code SELECT}, {@code JOIN}), the
 * form {@code language/spellings.md} recommends for generated code; either form parses
 * back to the same script.
 */
public final class ScriptPrinter {

    /** How each operator is written. */
    private final Spelling spelling;

    /** The statement's own comments, still to be placed by {@link #statement}. */
    private final List<Comment> inside;

    /** Each node of the statement, and the comments to write immediately before it. */
    private final Map<RelNode, List<Comment>> placed = new IdentityHashMap<>();

    /** The statement's comments after its last expression starts, written after it. */
    private final List<Comment> trailing = new ArrayList<>();

    private ScriptPrinter(Spelling spelling, List<Comment> inside) {
        this.spelling = Objects.requireNonNull(spelling, "spelling");
        this.inside = inside;
    }

    /**
     * Renders a whole script, namespace first, one statement per line, its comments with
     * it, and every operator as a glyph.
     *
     * @param script the script to render; must not be null
     * @return the {@code .relix} text
     */
    public static String print(Script script) {
        return print(script, Spelling.GLYPHS);
    }

    /**
     * Renders a whole script, namespace first, one statement per line, its comments with
     * it, and every operator as {@code spelling} spells it.
     *
     * @param script   the script to render; must not be null
     * @param spelling glyphs or ASCII keywords; must not be null
     * @return the {@code .relix} text
     * @since 1.0
     */
    public static String print(Script script, Spelling spelling) {
        Objects.requireNonNull(spelling, "spelling");
        ScriptComments comments = script.comments();
        StringBuilder out = new StringBuilder();
        script.namespace().ifPresent(ns -> {
            ScriptComments.StatementComments around = comments.namespace();
            around.before().forEach(comment -> out.append(comment.text()).append('\n'));
            out.append("namespace ").append(ns);
            if (!around.inside().isEmpty()) {
                out.append(' ').append(comments(around.inside()));
            }
            out.append(';');
            around.after().forEach(comment -> out.append(' ').append(comment.text()));
            out.append('\n');
        });
        List<Statement> statements = script.statements();
        for (int i = 0; i < statements.size(); i++) {
            ScriptComments.StatementComments around = comments.statement(i);
            if (around.blankLineBefore()) {
                out.append('\n');
            }
            around.before().forEach(comment -> out.append(comment.text()).append('\n'));
            out.append(new ScriptPrinter(spelling, around.inside()).statement(statements.get(i)));
            around.after().forEach(comment -> out.append(' ').append(comment.text()));
            out.append('\n');
        }
        if (comments.footerSpaced()) {
            out.append('\n');
        }
        comments.footer().forEach(comment -> out.append(comment.text()).append('\n'));
        return out.toString();
    }

    /**
     * Renders one statement, including its trailing semicolon.
     *
     * @param statement the statement to render; must not be null
     * @return the {@code .relix} text for that statement
     */
    public static String print(Statement statement) {
        return print(statement, Spelling.GLYPHS);
    }

    /**
     * Renders one statement, including its trailing semicolon, every operator as
     * {@code spelling} spells it.
     *
     * @param statement the statement to render; must not be null
     * @param spelling  glyphs or ASCII keywords; must not be null
     * @return the {@code .relix} text for that statement
     * @since 1.0
     */
    public static String print(Statement statement, Spelling spelling) {
        return new ScriptPrinter(spelling, List.of()).statement(statement);
    }

    /**
     * {@code statement}'s text, with its {@link #inside} comments in it: each before the
     * first node of its expression that starts after the comment, or after the expression
     * when none does. A statement with no expression has them before its {@code ;}.
     */
    private String statement(Statement statement) {
        List<RelNode> nodes = new ArrayList<>();
        expressionOf(statement).ifPresent(root -> collect(root, nodes));
        nodes.sort(Comparator.comparingInt((RelNode n) -> n.location().line())
                .thenComparingInt(n -> n.location().column()));
        for (Comment comment : inside) {
            nodes.stream()
                    .filter(node -> startsAfter(node.location(), comment.location()))
                    .findFirst()
                    .ifPresentOrElse(node -> placed.computeIfAbsent(node, n -> new ArrayList<>()).add(comment),
                            () -> trailing.add(comment));
        }
        String text = dispatch(statement);
        if (nodes.isEmpty() && !(statement instanceof DefStatement) && !trailing.isEmpty()) {
            return text.substring(0, text.length() - 1) + " " + comments(trailing) + ";";
        }
        return text;
    }

    /** The relational expression a statement holds, if it holds one. */
    private static Optional<RelNode> expressionOf(Statement statement) {
        return switch (statement) {
            case AssignmentStatement s when s.body() instanceof QueryAssignmentBody b ->
                    Optional.of(b.expression());
            case DefRelationStatement s -> Optional.of(s.body());
            case QueryStatement s when s.target() instanceof ExpressionQueryTarget t ->
                    Optional.of(t.expression());
            default -> Optional.empty();
        };
    }

    /** Every node of {@code node}'s tree that the parser placed, in no particular order. */
    private static void collect(RelNode node, List<RelNode> into) {
        if (node.location().line() > 0) {
            into.add(node);
        }
        node.children().forEach(child -> collect(child, into));
    }

    private static boolean startsAfter(SourceLocation node, SourceLocation comment) {
        return node.line() != comment.line() ? node.line() > comment.line()
                : node.column() > comment.column();
    }

    /**
     * Comments as code may follow them: a line comment ends its line, so the next token
     * starts on the next; a block comment is followed by a space.
     */
    private static String comments(List<Comment> comments) {
        StringBuilder sb = new StringBuilder();
        for (Comment comment : comments) {
            sb.append(comment.text()).append(comment.isLineComment() ? "\n" : " ");
        }
        return sb.toString();
    }

    private String dispatch(Statement statement) {
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

    private String assignment(AssignmentStatement s) {
        String prefix = (s.exported() ? "" : "private ") + s.name() + " := ";
        return switch (s.body()) {
            case QueryAssignmentBody b -> prefix + braced(expression(b.expression())) + ";";
            case InlineTableBody b -> prefix + inlineTable(b) + ";";
        };
    }

    private String inlineTable(InlineTableBody body) {
        String table = switch (body.table()) {
            case MarkdownInlineTable t -> markdown(t);
            case CsvInlineTable t -> csv(t);
        };
        return table + references(body.references(), " references ");
    }

    /**
     * A Markdown table, each comment written after a row back on that row. A comment the
     * table placed is taken from {@link #trailing}, so it is not written again before the
     * statement's {@code ;}.
     */
    private String markdown(MarkdownInlineTable t) {
        StringBuilder sb = new StringBuilder("[\n");
        sb.append(row(t.headers()));
        StringJoiner rule = new StringJoiner("|", "|", "|");
        t.headers().forEach(h -> rule.add("-".repeat(Math.max(3, h.length() + 2))));
        sb.append(rule).append('\n');
        for (int i = 0; i < t.rows().size(); i++) {
            String row = row(t.rows().get(i));
            if (i < t.rowLocations().size()) {
                row = withComments(row, t.rowLocations().get(i));
            }
            sb.append(row);
        }
        return sb.append(']').toString();
    }

    /** {@code row} with the line comments written on its line, after its last {@code |}. */
    private String withComments(String row, SourceLocation location) {
        StringBuilder sb = new StringBuilder(row.substring(0, row.length() - 1));
        for (var it = trailing.iterator(); it.hasNext(); ) {
            Comment comment = it.next();
            // A block comment can share a row's line only after the table's ], and written
            // after the row's last | it would read back as text, not a comment.
            if (comment.isLineComment() && comment.location().line() == location.line()) {
                sb.append("  ").append(comment.text());
                it.remove();
            }
        }
        return sb.append('\n').toString();
    }

    private static String row(List<String> cells) {
        StringJoiner joiner = new StringJoiner(" | ", "| ", " |\n");
        cells.forEach(joiner::add);
        return joiner.toString();
    }

    private static String csv(CsvInlineTable t) {
        StringBuilder sb = new StringBuilder("csv[\n");
        sb.append("  ").append(csvLine(t.headers())).append('\n');
        for (List<String> r : t.rows()) {
            sb.append("  ").append(csvLine(r)).append('\n');
        }
        return sb.append(']').toString();
    }

    /**
     * One CSV line, a field quoted when it holds a comma or a quote — the reason a table
     * is written as csv[ ] at all — with an embedded quote doubled, as the reader takes it.
     */
    private static String csvLine(List<String> fields) {
        StringJoiner line = new StringJoiner(", ");
        for (String field : fields) {
            line.add(field.contains(",") || field.contains("\"")
                    ? '"' + field.replace("\"", "\"\"") + '"'
                    : field);
        }
        return line.toString();
    }

    private String def(DefStatement s) {
        return (s.exported() ? "" : "private ")
                + "def " + s.name() + parameters(s.parameters())
                + " : " + s.returnType().display()
                + " := " + braced(expression(s.body())) + ";";
    }

    private String defRelation(DefRelationStatement s) {
        return (s.exported() ? "" : "private ")
                + "def " + s.name() + parameters(s.parameters())
                + " : RELATION := " + braced(expression(s.body())) + ";";
    }

    /**
     * A relation parameter's type — {@code RELATION(src, weight: NUMBER)}. An untyped
     * column is ANY and is printed with no type, which is how it is written.
     */
    private static String relationType(Schema heading) {
        StringJoiner columns = new StringJoiner(", ", "RELATION(", ")");
        heading.columns().forEach(c -> columns.add(c.type() == ScalarType.ANY
                ? c.name() : c.name() + ": " + c.type().display()));
        return columns.toString();
    }

    private static String parameters(List<ParameterDefinition> parameters) {
        StringJoiner joiner = new StringJoiner(", ", "(", ")");
        parameters.forEach(p -> joiner.add(p.name() + ": " + p.heading()
                .map(ScriptPrinter::relationType)
                .orElseGet(() -> p.type().display())));
        return joiner.toString();
    }

    private String query(QueryStatement s) {
        return switch (s.target()) {
            case NamedQueryTarget t -> "query " + t.name() + ";";
            case ExpressionQueryTarget t -> "query " + braced(expression(t.expression())) + ";";
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
                    c.names().isEmpty() ? null : entry("columns", c.names().stream()
                            .map(ScriptPrinter::columnName)
                            .collect(java.util.stream.Collectors.joining(", ", "[", "]"))),
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
            joiner.add(columnName(column.name()) + ": "
                    + (column.direction() == ColumnDirection.IN ? "in " : "")
                    + type(column.type()) + binding(column) + modifier(column));
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
            case QueryParamBinding p -> " as query(" + quote(p.paramName()) + ")";
            case PathParamBinding p -> " as path(" + quote(p.paramName()) + ")";
            case HeaderBinding p -> " as header(" + quote(p.headerName()) + ")";
        }).orElse("");
    }

    /** A column's {@code [required]} or {@code [default: "…"]}, when it has one. */
    private static String modifier(ColumnSpec column) {
        if (column.required()) {
            return " [required]";
        }
        return column.defaultValue().map(value -> " [default: " + quote(value) + "]").orElse("");
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
        // In key order: the map's own order is no order, and printing must be a fixed point.
        new TreeMap<>(properties).forEach((key, value) -> joiner.add(key + ": " + quote(value)));
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
        new TreeMap<>(headers).forEach((key, value) -> joiner.add(quote(key) + ": " + quote(value)));
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

    /** The statement's expression, each comment before the node it preceded, the rest after. */
    private String expression(RelNode node) {
        String text = PrettyPrinter.source(spelling,
                n -> comments(placed.getOrDefault(n, List.of()))).print(node);
        return trailing.isEmpty() ? text : text + " " + comments(trailing);
    }

    /**
     * {@code inner} between braces, a space inside each — but no space before the closing
     * brace when {@code inner} ends a line, as it does after a trailing line comment.
     */
    private static String braced(String inner) {
        return "{ " + inner + (inner.endsWith("\n") ? "}" : " }");
    }

    /** A {@code def}'s body, its comments before it: an operand has no nodes to place them by. */
    private String expression(Operand operand) {
        return comments(trailing) + operand.accept(new OperandPrettyPrinter(spelling));
    }

    /** Double-quotes a value, escaping embedded quotes and backslashes. */
    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}

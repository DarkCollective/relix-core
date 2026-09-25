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
package com.darkcollective.relix.lang;

import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.lang.ast.*;
import com.darkcollective.relix.lang.ast.source.*;
import com.darkcollective.relix.lang.ast.table.CsvInlineTable;
import com.darkcollective.relix.lang.ast.table.MarkdownInlineTable;
import com.darkcollective.relix.parser.ParseException;
import com.darkcollective.relix.parser.RelAlgebraParser;
import com.darkcollective.relix.symbol.ArrayType;
import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.StructType;
import com.darkcollective.relix.symbol.Type;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Recursive-descent parser for {@code .relix} scripting language source files.
 *
 * <h2>Usage</h2>
 * <pre>
 *   Script script = ScriptParser.parse(sourceText);
 *   Script script = ScriptParser.parse(sourceText, "path/to/file.relix");
 * </pre>
 *
 * <h2>Script structure</h2>
 * <pre>
 *   [namespace IDENTIFIER ;]
 *   [env [from STRING] [using STRING] ;]
 *   (import | source | connection | relate | IDENTIFIER := ... | def | query) *
 * </pre>
 *
 * <h2>Embedded sub-languages</h2>
 * Relational-algebra expression blocks ({@code \{ … \}}) in assignment
 * and query statements are extracted as raw text and delegated eagerly to
 * {@link RelAlgebraParser#parse(String, String, int, int)}.
 * Function-body expressions in {@code def} statements are similarly
 * delegated to {@link RelAlgebraParser#parseOperand(String, String, int, int)}.
 *
 * <h2>Raw block extraction invariant</h2>
 * When a delimiter token ({@link LangTokenType#LBRACE} or
 * {@link LangTokenType#LBRACKET}) is the current lookahead, the lexer's
 * character position is already <em>past</em> that delimiter character.
 * Raw block extraction must therefore be performed <strong>without</strong>
 * calling {@link #advance()} first — doing so would skip the first content
 * character.  The {@link LangLexer#consumeRawBraceBlock()} and
 * {@link LangLexer#consumeRawBracketBlock()} helpers enforce this contract.
 */
public final class ScriptParser {

    private final LangLexer lexer;
    private final String filePath;
    LangToken current;

    private ScriptParser(String source) {
        this(source, "<stdin>");
    }

    private ScriptParser(String source, String filePath) {
        this.lexer    = new LangLexer(source, filePath);
        this.filePath = Objects.requireNonNull(filePath, "filePath");
        this.current  = lexer.next();
    }

    // =========================================================================
    // Public entry points
    // =========================================================================

    /**
     * Parses a relix scripting language source from the given string.
     *
     * @param source the full script source text
     * @return the parsed {@link Script} AST
     * @throws LangParseException   if the source contains syntax errors
     * @throws NullPointerException if {@code source} is null
     */
    public static Script parse(String source) {
        Objects.requireNonNull(source, "source");
        return new ScriptParser(source).parseScript();
    }

    /**
     * Parses a relix scripting language source from the given string,
     * embedding {@code filePath} in every statement's source location.
     *
     * @param source   the full script source text
     * @param filePath the file path to embed in source locations
     * @return the parsed {@link Script} AST
     * @throws LangParseException   if the source contains syntax errors
     * @throws NullPointerException if {@code source} or {@code filePath} is null
     */
    public static Script parse(String source, String filePath) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(filePath, "filePath");
        return new ScriptParser(source, filePath).parseScript();
    }

    /**
     * Parses a relix scripting language source from the given input stream
     * (UTF-8 encoded).
     *
     * @param input the source stream
     * @return the parsed {@link Script} AST
     * @throws IOException        if reading from the stream fails
     * @throws LangParseException if the source contains syntax errors
     * @throws NullPointerException if {@code input} is null
     */
    public static Script parse(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        return parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }

    /**
     * Parses a relix scripting language source from the given input stream
     * (UTF-8 encoded), embedding {@code filePath} in every statement's source
     * location.
     *
     * @param input    the source stream
     * @param filePath the file path to embed in source locations
     * @return the parsed {@link Script} AST
     * @throws IOException        if reading from the stream fails
     * @throws LangParseException if the source contains syntax errors
     * @throws NullPointerException if {@code input} or {@code filePath} is null
     */
    public static Script parse(InputStream input, String filePath) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(filePath, "filePath");
        return parse(new String(input.readAllBytes(), StandardCharsets.UTF_8), filePath);
    }

    // =========================================================================
    // Source location helper
    // =========================================================================

    /**
     * Creates a {@link SourceLocation} from the given token's position and this
     * parser's file path.
     *
     * @param tok the token whose position to use
     * @return the source location; never null
     */
    SourceLocation loc(LangToken tok) {
        return new SourceLocation(filePath, tok.line(), tok.column());
    }

    // =========================================================================
    // Script
    // =========================================================================

    private Script parseScript() {
        Optional<String> namespace = Optional.empty();
        List<Statement> statements = new ArrayList<>();

        // Optional namespace declaration (must be first)
        if (current.type() == LangTokenType.NAMESPACE) {
            namespace = Optional.of(parseNamespaceDecl());
        }

        // Optional env declaration (must come before other statements)
        if (current.type() == LangTokenType.ENV) {
            statements.add(parseEnvDecl());
        }

        // Remaining statements
        while (current.type() != LangTokenType.EOF) {
            statements.add(parseStatement());
        }

        return new Script(namespace, Collections.unmodifiableList(statements));
    }

    private String parseNamespaceDecl() {
        consume(LangTokenType.NAMESPACE);
        String name = requireName("namespace name");
        consumeSemicolon();
        return name;
    }

    private EnvStatement parseEnvDecl() {
        LangToken startTok = current;
        consume(LangTokenType.ENV);
        Optional<String> filePath = Optional.empty();
        Optional<String> activeEnv = Optional.empty();
        if (current.type() == LangTokenType.FROM) {
            advance();
            filePath = Optional.of(requireStringLit("env file path"));
        }
        if (current.type() == LangTokenType.USING) {
            advance();
            activeEnv = Optional.of(requireStringLit("env profile name"));
        }
        consumeSemicolon();
        return new EnvStatement(filePath, activeEnv, loc(startTok));
    }

    // =========================================================================
    // Statements
    // =========================================================================

    private Statement parseStatement() {
        boolean exported = true;
        LangToken exportedTok = current;
        if (current.type() == LangTokenType.PRIVATE) {
            exported = false;
            advance();
            exportedTok = current;
        }

        return switch (current.type()) {
            case IMPORT -> parseImportStatement();
            case SOURCE -> parseSourceDeclaration(exported, exportedTok);
            case CONNECTION -> parseConnectionDeclaration(exported, exportedTok);
            case RELATE -> parseRelateStatement();
            case DEF    -> parseDefStatement(exported, exportedTok);
            case QUERY  -> parseQueryStatement();
            default     -> parseAssignmentStatement(exported, exportedTok);
        };
    }

    // -------------------------------------------------------------------------
    // Import
    // -------------------------------------------------------------------------

    private Statement parseImportStatement() {
        LangToken startTok = current;
        consume(LangTokenType.IMPORT);

        // import STRING_LIT ;  — bulk import of an entire file
        if (current.type() == LangTokenType.STRING_LIT) {
            String path = current.value();
            advance();
            consumeSemicolon();
            return new ImportStatement(ImportKind.BULK, List.of(), path, loc(startTok));
        }

        // import source/relation/function NAME from STRING ;
        if (current.type() == LangTokenType.SOURCE
                || current.type() == LangTokenType.RELATION
                || current.type() == LangTokenType.FUNCTION) {
            ImportKind kind = switch (current.type()) {
                case SOURCE   -> ImportKind.SOURCE;
                case RELATION -> ImportKind.RELATION;
                case FUNCTION -> ImportKind.FUNCTION;
                default -> throw error("Unexpected import kind token");
            };
            advance();
            String name = requireName("import name");
            consume(LangTokenType.FROM);
            String path = requireStringLit("import source path");
            consumeSemicolon();
            return new ImportStatement(kind, List.of(name), path, loc(startTok));
        }

        // import { NAME, ... } from STRING ;  — unqualified named imports
        if (current.type() == LangTokenType.LBRACE) {
            advance();
            List<String> names = new ArrayList<>();
            names.add(requireName("import name"));
            while (current.type() == LangTokenType.COMMA) {
                advance();
                if (current.type() == LangTokenType.RBRACE) {
                    break; // trailing comma allowed
                }
                names.add(requireName("import name"));
            }
            consume(LangTokenType.RBRACE);
            consume(LangTokenType.FROM);
            String path = requireStringLit("import source path");
            consumeSemicolon();
            return new ImportStatement(ImportKind.UNQUALIFIED,
                    Collections.unmodifiableList(names), path, loc(startTok));
        }

        // import NAME from STRING ;  — unqualified single import
        String name = requireName("import name");
        consume(LangTokenType.FROM);
        String path = requireStringLit("import source path");
        consumeSemicolon();
        return new ImportStatement(ImportKind.UNQUALIFIED, List.of(name), path, loc(startTok));
    }

    // -------------------------------------------------------------------------
    // Source declaration
    // -------------------------------------------------------------------------

    private Statement parseSourceDeclaration(boolean exported, LangToken startTok) {
        consume(LangTokenType.SOURCE);
        String name = requireName("source name");
        consume(LangTokenType.FROM);

        SourceConfig config = new SourceConfigParser(this).parse(current.type());

        consumeSemicolon();
        return new SourceDeclaration(exported, name, config, loc(startTok));
    }

    // -------------------------------------------------------------------------
    // Connection
    // -------------------------------------------------------------------------

    /** JDBC connections accept only these config keys; other connector types accept any key. */
    private static final Set<String> JDBC_CONFIG_KEYS =
            Set.of("url", "user", "password", "dialect", "collation");

    private Statement parseConnectionDeclaration(boolean exported, LangToken startTok) {
        consume(LangTokenType.CONNECTION);
        String name = requireName("connection name");
        consume(LangTokenType.FROM);
        String connectorType = parseConnectorType();
        Map<String, String> properties = parseConnectionProperties(connectorType);
        consumeSemicolon();
        return new ConnectionDeclaration(exported, name, connectorType, properties, loc(startTok));
    }

    /**
     * Reads the connector type token after {@code from} (ADR-0010, Decision 3).
     * {@code database} is a permanent alias for {@code jdbc}; any other identifier
     * ({@code jdbc}, {@code mongodb}, {@code http}, …) is the registry dispatch key.
     */
    private String parseConnectorType() {
        String token = requireName("connector type (e.g. jdbc, mongodb)").toLowerCase(Locale.ROOT);
        return token.equals("database") ? "jdbc" : token;
    }

    /**
     * Parses the {@code { key: value, … }} block into a raw property map.  Values
     * may be string literals or bare identifiers (e.g. {@code dialect: postgres}).
     * JDBC connections still validate their key set and require {@code url}; other
     * connector types accept any keys (the connector validates its own).
     */
    private Map<String, String> parseConnectionProperties(String connectorType) {
        boolean jdbc = connectorType.equals("jdbc");
        consume(LangTokenType.LBRACE);

        Map<String, String> properties = new LinkedHashMap<>();
        while (current.type() != LangTokenType.RBRACE && current.type() != LangTokenType.EOF) {
            LangToken fieldTok = current;
            String field = requireName("connection config field name");
            consume(LangTokenType.COLON);
            String value = parseConnectionValue();
            if (jdbc && !JDBC_CONFIG_KEYS.contains(field)) {
                throw new LangParseException(
                        "Unknown connection config field '" + field + "'",
                        fieldTok.line(), fieldTok.column());
            }
            properties.put(field, value);
            if (current.type() == LangTokenType.COMMA) {
                advance();
            }
        }
        int line = current.line(), col = current.column();
        consume(LangTokenType.RBRACE);

        if (jdbc && !properties.containsKey("url")) {
            throw new LangParseException("connection missing 'url'", line, col);
        }
        return properties;
    }

    /** A connection config value: a string literal or a bare identifier. */
    private String parseConnectionValue() {
        if (current.type() == LangTokenType.STRING_LIT) {
            return requireStringLit("connection config value");
        }
        return requireName("connection config value");
    }

    // -------------------------------------------------------------------------
    // Relate — schema relationship declaration (ADR-0024)
    // -------------------------------------------------------------------------

    /**
     * Parses a standalone relationship declaration:
     * <pre>
     *   relate [symmetric] "Name" [/ "Inverse Name"]
     *       endpoint -&gt; endpoint ;
     * </pre>
     * where each endpoint is {@code Rel.col} or {@code Rel(col, …)} optionally
     * followed by multiplicity bounds {@code [min..max]} ({@code *} = unbounded).
     */
    private Statement parseRelateStatement() {
        LangToken startTok = current;
        consume(LangTokenType.RELATE);

        boolean symmetric = false;
        if (current.type() == LangTokenType.SYMMETRIC) {
            symmetric = true;
            advance();
        }

        String name = requireStringLit("relationship name");

        Optional<String> inverseName = Optional.empty();
        if (current.type() == LangTokenType.SLASH) {
            advance();
            inverseName = Optional.of(requireStringLit("inverse relationship name"));
        }

        EndpointSpec source = parseEndpointSpec();
        consume(LangTokenType.ARROW);
        EndpointSpec target = parseEndpointSpec();

        consumeSemicolon();
        return new RelateStatement(name, inverseName, symmetric, source, target, loc(startTok));
    }

    /** Parses one relationship endpoint: a relation/column reference plus optional bounds. */
    private EndpointSpec parseEndpointSpec() {
        LangToken startTok = current;
        RelationColumns ref = parseRelationColumns("relationship endpoint");

        long min = 0;
        OptionalLong max = OptionalLong.empty();
        if (current.type() == LangTokenType.LBRACKET) {
            advance();
            min = requireBoundValue("lower multiplicity bound");
            consume(LangTokenType.RANGE);
            if (current.type() == LangTokenType.STAR) {
                advance();
            } else {
                max = OptionalLong.of(requireBoundValue("upper multiplicity bound"));
            }
            consume(LangTokenType.RBRACKET);
        }

        return new EndpointSpec(ref.relation(), ref.columns(), min, max, loc(startTok));
    }

    /** Reads a multiplicity bound, which must be a non-negative integer literal. */
    private long requireBoundValue(String context) {
        LangToken tok = current;
        String num = requireNumberLit(context);
        try {
            return Long.parseLong(num);
        } catch (NumberFormatException e) {
            throw new LangParseException(
                    "Expected integer " + context + ", got '" + num + "'",
                    tok.line(), tok.column());
        }
    }

    /**
     * A relation reference plus the columns named on it — the shared shape of a
     * relate-statement endpoint and a {@code references:} entry target.
     */
    record RelationColumns(String relation, List<String> columns) {}

    /**
     * Parses a relation/column reference in either form:
     * <ul>
     *   <li>{@code Rel(col, …)} — everything before {@code (} is the (possibly
     *       namespace-qualified, dotted) relation reference;</li>
     *   <li>{@code Rel.col} — dot form; the <em>last</em> dotted segment is the
     *       single column, everything before it the relation reference.</li>
     * </ul>
     */
    RelationColumns parseRelationColumns(String context) {
        List<String> segments = new ArrayList<>();
        segments.add(requireName(context + " relation name"));
        while (current.type() == LangTokenType.DOT) {
            advance();
            segments.add(requireName(context + " name segment"));
        }

        if (current.type() == LangTokenType.LPAREN) {
            advance();
            List<String> columns = new ArrayList<>();
            columns.add(requireName(context + " column name"));
            while (current.type() == LangTokenType.COMMA) {
                advance();
                columns.add(requireName(context + " column name"));
            }
            consume(LangTokenType.RPAREN);
            return new RelationColumns(String.join(".", segments), List.copyOf(columns));
        }

        if (segments.size() < 2) {
            throw error("Expected '.' or '(column, ...)' after relation name in " + context);
        }
        String column = segments.removeLast();
        return new RelationColumns(String.join(".", segments), List.of(column));
    }

    /**
     * Parses a {@code { … }} references block (ADR-0024) of foreign-key style
     * arrows from columns of the declaring relation to another relation:
     * <pre>
     *   {
     *       customer_id -&gt; Customers.customer_id,
     *       (tenant_id, product_id) -&gt; Product(tenant_id, id)
     *   }
     * </pre>
     * Used both for the {@code references:} field on source configs and the
     * trailing {@code references} clause on inline tables; the caller has
     * already consumed the field name / keyword, this method starts at the
     * opening brace.
     */
    List<ColumnReference> parseReferencesBlock() {
        consume(LangTokenType.LBRACE);
        List<ColumnReference> references = new ArrayList<>();
        while (current.type() != LangTokenType.RBRACE
                && current.type() != LangTokenType.EOF) {
            LangToken startTok = current;

            List<String> sourceColumns = new ArrayList<>();
            if (current.type() == LangTokenType.LPAREN) {
                advance();
                sourceColumns.add(requireName("referencing column name"));
                while (current.type() == LangTokenType.COMMA) {
                    advance();
                    sourceColumns.add(requireName("referencing column name"));
                }
                consume(LangTokenType.RPAREN);
            } else {
                sourceColumns.add(requireName("referencing column name"));
            }

            consume(LangTokenType.ARROW);
            RelationColumns target = parseRelationColumns("reference target");

            references.add(new ColumnReference(sourceColumns, target.relation(),
                    target.columns(), loc(startTok)));

            if (current.type() == LangTokenType.COMMA) {
                advance();
            }
        }
        consume(LangTokenType.RBRACE);
        return Collections.unmodifiableList(references);
    }

    // -------------------------------------------------------------------------
    // Assignment
    // -------------------------------------------------------------------------

    private Statement parseAssignmentStatement(boolean exported, LangToken startTok) {
        String name = requireName("assignment target name");
        consume(LangTokenType.ASSIGN);

        AssignmentBody body;

        if (current.type() == LangTokenType.CSV) {
            // csv[ ... ] — CSV inline table
            // After consuming CSV, the next token scanned will be '['.
            // The lexer's pos will then be past '[', ready for bracket-block extraction.
            advance(); // consume 'csv', lexer scans '[' → current = LBRACKET
            LangToken bracket = requireBracketToken();
            LangLexer.RawBlock raw = lexer.consumeRawBracketBlock();
            current = lexer.next();
            body = new InlineTableBody(parseCsvInlineTable(raw, bracket),
                    parseInlineReferencesSuffix());
        } else if (current.type() == LangTokenType.LBRACKET) {
            // [ ... ] — Markdown inline table
            // current = LBRACKET; lexer.pos is past '['.  Do NOT advance first.
            LangToken bracket = current;
            LangLexer.RawBlock raw = lexer.consumeRawBracketBlock();
            current = lexer.next();
            body = new InlineTableBody(parseMarkdownInlineTable(raw, bracket),
                    parseInlineReferencesSuffix());
        } else {
            // { ... } — relational algebra expression
            // current = LBRACE; lexer.pos is past '{'.  Do NOT advance first.
            LangToken brace = requireBraceToken();
            LangLexer.RawBlock raw = lexer.consumeRawBraceBlock();
            current = lexer.next();
            body = new QueryAssignmentBody(delegateToRaParser(raw, brace));
            if (current.type() == LangTokenType.REFERENCES) {
                throw error("A references clause applies to inline tables and sources;"
                        + " relate a view with a standalone 'relate' statement instead");
            }
        }

        consumeSemicolon();
        return new AssignmentStatement(exported, name, body, loc(startTok));
    }

    /**
     * Parses the optional trailing {@code references { … }} clause of an inline
     * table (ADR-0024) — the inline-table counterpart of the {@code references:}
     * field on source configs:
     * <pre>
     *   Cities := [ … ] references { country -&gt; Countries.code };
     * </pre>
     */
    private List<ColumnReference> parseInlineReferencesSuffix() {
        if (current.type() != LangTokenType.REFERENCES) {
            return List.of();
        }
        advance();
        return parseReferencesBlock();
    }

    // -------------------------------------------------------------------------
    // Def
    // -------------------------------------------------------------------------

    private Statement parseDefStatement(boolean exported, LangToken startTok) {
        consume(LangTokenType.DEF);
        String name = requireName("function name");

        consume(LangTokenType.LPAREN);
        List<ParameterDefinition> params = new ArrayList<>();
        if (current.type() != LangTokenType.RPAREN) {
            params.add(parseParameterDefinition());
            while (current.type() == LangTokenType.COMMA) {
                advance();
                params.add(parseParameterDefinition());
            }
        }
        consume(LangTokenType.RPAREN);

        consume(LangTokenType.COLON);

        // `: relation` (case-insensitive) marks a table-valued function whose body is
        // a relational-algebra expression; any other return type is a scalar function.
        if (current.type() == LangTokenType.RELATION) {
            advance();
            consume(LangTokenType.ASSIGN);

            // current = LBRACE; lexer.pos is past '{'.  Do NOT advance first.
            LangToken relBrace = requireBraceToken();
            LangLexer.RawBlock relRaw = lexer.consumeRawBraceBlock();
            current = lexer.next();
            RelNode relBody = delegateToRaParser(relRaw, relBrace);

            consumeSemicolon();
            return new DefRelationStatement(exported, name,
                    Collections.unmodifiableList(params), relBody, loc(startTok));
        }

        ScalarType returnType = requireScalarType();
        consume(LangTokenType.ASSIGN);

        // current = LBRACE; lexer.pos is past '{'.  Do NOT advance first.
        LangToken brace = requireBraceToken();
        LangLexer.RawBlock raw = lexer.consumeRawBraceBlock();
        current = lexer.next();
        Operand body = delegateToOperandParser(raw, brace);

        consumeSemicolon();
        return new DefStatement(exported, name,
                Collections.unmodifiableList(params), returnType, body, loc(startTok));
    }

    private ParameterDefinition parseParameterDefinition() {
        String name = requireName("parameter name");
        consume(LangTokenType.COLON);
        ScalarType type = requireScalarType();
        return new ParameterDefinition(name, type);
    }

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    private Statement parseQueryStatement() {
        LangToken startTok = current;
        consume(LangTokenType.QUERY);

        QueryTarget target;
        if (current.type() == LangTokenType.LBRACE) {
            // query { ra-expression } ;
            // current = LBRACE; lexer.pos is past '{'.  Do NOT advance first.
            LangToken brace = current;
            LangLexer.RawBlock raw = lexer.consumeRawBraceBlock();
            current = lexer.next();
            target = new ExpressionQueryTarget(delegateToRaParser(raw, brace));
        } else {
            // query NAME ;
            target = new NamedQueryTarget(requireName("query target name"));
        }

        consumeSemicolon();
        return new QueryStatement(target, loc(startTok));
    }

    // =========================================================================
    // Inline table parsing
    // =========================================================================

    /**
     * Parses a Markdown-style pipe-delimited inline table from a raw block.
     *
     * <p>Lines starting with {@code |} are data lines; separator lines
     * (cells containing only {@code -} characters) are discarded.  The first
     * non-separator data line becomes the header row.
     */
    private MarkdownInlineTable parseMarkdownInlineTable(LangLexer.RawBlock raw,
                                                          LangToken openToken) {
        List<String> headers = null;
        List<List<String>> rows = new ArrayList<>();

        for (String line : raw.text().split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || !trimmed.startsWith("|")) {
                continue;
            }
            List<String> cells = splitPipeCells(trimmed);
            if (cells.isEmpty()) {
                continue;
            }
            // Separator row — all cells are only dashes (e.g. "---")
            if (cells.stream().allMatch(c -> c.matches("-+"))) {
                continue;
            }
            if (headers == null) {
                headers = Collections.unmodifiableList(new ArrayList<>(cells));
            } else {
                rows.add(Collections.unmodifiableList(new ArrayList<>(cells)));
            }
        }

        if (headers == null || headers.isEmpty()) {
            throw new LangParseException(
                    "Markdown inline table has no header row",
                    openToken.line(), openToken.column());
        }
        return new MarkdownInlineTable(headers, rows);
    }

    private List<String> splitPipeCells(String line) {
        String inner = line;
        if (inner.startsWith("|")) {
            inner = inner.substring(1);
        }
        if (inner.endsWith("|")) {
            inner = inner.substring(0, inner.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : inner.split("\\|", -1)) {
            cells.add(cell.trim());
        }
        return cells;
    }

    /**
     * Parses a CSV-style inline table from a raw block.
     *
     * <p>The first non-blank line is the header row.  Standard CSV quoting
     * applies: double-quoted fields, {@code ""} for a literal quote.
     */
    private CsvInlineTable parseCsvInlineTable(LangLexer.RawBlock raw, LangToken openToken) {
        List<String> headers = null;
        List<List<String>> rows = new ArrayList<>();

        for (String line : raw.text().split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            List<String> cells = parseCsvLine(trimmed);
            if (headers == null) {
                headers = Collections.unmodifiableList(new ArrayList<>(cells));
            } else {
                rows.add(Collections.unmodifiableList(new ArrayList<>(cells)));
            }
        }

        if (headers == null || headers.isEmpty()) {
            throw new LangParseException(
                    "CSV inline table has no header row",
                    openToken.line(), openToken.column());
        }
        return new CsvInlineTable(headers, rows);
    }

    private List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        int i = 0;
        while (i <= line.length()) {
            StringBuilder field = new StringBuilder();
            // Skip leading whitespace before checking for quoted field
            while (i < line.length() && line.charAt(i) == ' ') {
                i++;
            }
            if (i < line.length() && line.charAt(i) == '"') {
                // Quoted field
                i++; // skip opening '"'
                while (i < line.length()) {
                    char c = line.charAt(i);
                    if (c == '"') {
                        if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                            field.append('"');
                            i += 2;
                        } else {
                            i++; // skip closing '"'
                            break;
                        }
                    } else {
                        field.append(c);
                        i++;
                    }
                }
                if (i < line.length() && line.charAt(i) == ',') {
                    i++; // skip separating comma
                }
            } else {
                // Unquoted field: read until ',' or end
                while (i < line.length() && line.charAt(i) != ',') {
                    field.append(line.charAt(i));
                    i++;
                }
                if (i < line.length()) {
                    i++; // skip ','
                }
            }
            fields.add(field.toString().trim());
            if (i >= line.length()) {
                break;
            }
        }
        return fields;
    }

    // =========================================================================
    // Delegation to sub-parsers
    // =========================================================================

    private RelNode delegateToRaParser(LangLexer.RawBlock raw, LangToken openBrace) {
        try {
            return RelAlgebraParser.parse(raw.text(), filePath, raw.startLine(), raw.startCol());
        } catch (ParseException e) {
            throw new LangParseException(
                    "Syntax error in RA expression: " + withoutPosition(e),
                    e.line(), e.column());
        }
    }

    private Operand delegateToOperandParser(LangLexer.RawBlock raw, LangToken openBrace) {
        try {
            return RelAlgebraParser.parseOperand(raw.text(), filePath, raw.startLine(), raw.startCol());
        } catch (ParseException e) {
            throw new LangParseException(
                    "Syntax error in operand expression: " + withoutPosition(e),
                    e.line(), e.column());
        }
    }

    /**
     * The sub-parser's message without the position it embeds, since the exception
     * wrapping it states that position itself — once, in the grammar's own form.
     */
    private static String withoutPosition(ParseException e) {
        return e.getMessage().replace(" at line " + e.line() + ", column " + e.column(), "");
    }

    // =========================================================================
    // Raw block helpers
    // =========================================================================

    /**
     * Verifies that the current token is {@link LangTokenType#LBRACE} and
     * returns it <em>without</em> advancing the token stream.
     *
     * <p>Callers must use this instead of {@code consume(LBRACE)} before a
     * raw-brace-block extraction.  After this call, the lexer's character
     * position is already past {@code '{'} and
     * {@link LangLexer#consumeRawBraceBlock()} may be called immediately.
     */
    private LangToken requireBraceToken() {
        if (current.type() != LangTokenType.LBRACE) {
            throw new LangParseException("Expected '{'", current);
        }
        return current;
    }

    /**
     * Verifies that the current token is {@link LangTokenType#LBRACKET} and
     * returns it <em>without</em> advancing the token stream.
     */
    private LangToken requireBracketToken() {
        if (current.type() != LangTokenType.LBRACKET) {
            throw new LangParseException("Expected '['", current);
        }
        return current;
    }

    // =========================================================================
    // Token helpers
    // =========================================================================

    /** Asserts the current token type and advances. */
    LangToken consume(LangTokenType expected) {
        if (current.type() != expected) {
            throw new LangParseException(
                    "Expected '" + expected.name().toLowerCase().replace('_', ' ') + "'", current);
        }
        LangToken tok = current;
        advance();
        return tok;
    }

    /** Advances to the next token. */
    void advance() {
        current = lexer.next();
    }

    private void consumeSemicolon() {
        if (current.type() != LangTokenType.SEMICOLON) {
            throw new LangParseException("Expected ';' to terminate statement", current);
        }
        advance();
    }

    /**
     * Reads the current token as a name (accepts identifiers and any
     * {@link LangTokenType#isNameCompatible() name-compatible} keyword).
     */
    String requireName(String context) {
        if (!current.type().isNameCompatible()) {
            throw new LangParseException("Expected " + context, current);
        }
        String value = current.value();
        advance();
        return value;
    }

    /**
     * Reads the current token as a column or struct-field name: anything
     * {@link #requireName} accepts, or a backtick-delimited name, since a table's
     * columns are named by the database rather than by this grammar.
     */
    String requireColumnName(String context) {
        if (current.type() == LangTokenType.DELIMITED_IDENTIFIER) {
            String value = current.value();
            advance();
            return value;
        }
        return requireName(context);
    }

    String requireStringLit(String context) {
        if (current.type() != LangTokenType.STRING_LIT) {
            throw new LangParseException(
                    "Expected string literal for " + context, current);
        }
        String value = current.value();
        advance();
        return value;
    }

    String requireNumberLit(String context) {
        if (current.type() != LangTokenType.NUMBER_LIT) {
            throw new LangParseException(
                    "Expected numeric literal for " + context, current);
        }
        String value = current.value();
        advance();
        return value;
    }

    boolean requireBool(String context) {
        if (current.type() == LangTokenType.TRUE) {
            advance();
            return true;
        }
        if (current.type() == LangTokenType.FALSE) {
            advance();
            return false;
        }
        throw new LangParseException(
                "Expected 'true' or 'false' for " + context, current);
    }

    /**
     * Parses a column type, which may be nested.
     *
     * <pre>
     *   type   := scalar | struct | array
     *   struct := '{' name ':' type (',' name ':' type)* '}'
     *   array  := '[' type ']'
     * </pre>
     *
     * <p>Only a <em>column</em> may be nested. A {@code def}'s parameters and return type
     * stay scalar ({@link #requireScalarType()}), because {@code ParameterDefinition} holds
     * a {@code ScalarType} — widening that is a separate change with its own consequences
     * for every function signature.
     *
     * <p>There is no ambiguity with the {@code [required]} / {@code [default: …]} modifiers
     * a column spec may carry: those follow the type, and an array is only read where a type
     * is expected.
     *
     * @return the parsed type; never null
     */
    Type requireType() {
        if (current.type() == LangTokenType.LBRACE) {
            return structType();
        }
        if (current.type() == LangTokenType.LBRACKET) {
            advance();
            Type element = requireType();
            consume(LangTokenType.RBRACKET);
            return new ArrayType(element);
        }
        return requireScalarType();
    }

    private Type structType() {
        consume(LangTokenType.LBRACE);
        List<StructType.Field> fields = new ArrayList<>();
        while (current.type() != LangTokenType.RBRACE && current.type() != LangTokenType.EOF) {
            String name = requireColumnName("struct field name");
            consume(LangTokenType.COLON);
            fields.add(new StructType.Field(name, requireType()));
            if (current.type() == LangTokenType.COMMA) {
                advance();
            }
        }
        consume(LangTokenType.RBRACE);
        if (fields.isEmpty()) {
            // A struct with no fields describes nothing and would infer as an empty
            // heading, so it is a mistake rather than a degenerate case worth supporting.
            throw error("A struct type must declare at least one field");
        }
        return new StructType(fields);
    }

    ScalarType requireScalarType() {
        ScalarType type = switch (current.type()) {
            case NUMBER    -> ScalarType.NUMBER;
            case STRING    -> ScalarType.STRING;
            case BOOLEAN   -> ScalarType.BOOLEAN;
            case ANY       -> ScalarType.ANY;
            case DATE      -> ScalarType.DATE;
            case TIME      -> ScalarType.TIME;
            case TIMESTAMP -> ScalarType.TIMESTAMP;
            case DURATION  -> ScalarType.DURATION;
            default -> throw new LangParseException(
                    "Expected scalar type (NUMBER, STRING, BOOLEAN, ANY, DATE, TIME, "
                            + "TIMESTAMP, DURATION)",
                    current);
        };
        advance();
        return type;
    }

    LangParseException error(String message) {
        return new LangParseException(message, current);
    }
}

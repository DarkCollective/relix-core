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

import com.darkcollective.relix.lang.ast.source.*;
import com.darkcollective.relix.symbol.Type;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parses source-declaration config blocks on behalf of {@link ScriptParser}.
 *
 * <p>Handles all three source types: {@code http}, {@code database}, and
 * {@code csv}. This class is package-private and intended only for use by
 * {@link ScriptParser}.
 *
 * <p>All token access and mutation is delegated to the parent parser via the
 * back-reference supplied at construction time; this class owns no parser state
 * of its own.
 */
final class SourceConfigParser {

    private final ScriptParser parser;

    SourceConfigParser(ScriptParser parser) {
        this.parser = parser;
    }

    /**
     * Dispatches to the appropriate source-config parser based on the current
     * lookahead token.
     *
     * @param type the current lookahead token type
     * @return the parsed {@link SourceConfig}
     * @throws LangParseException if the token is not a recognised source type
     */
    SourceConfig parse(LangTokenType type) {
        return switch (type) {
            case HTTP     -> parseHttpSourceConfig();
            case DATABASE -> parseDatabaseSourceConfig();
            case CSV      -> parseCsvFileSourceConfig();
            case JSON     -> parseJsonFileSourceConfig();
            case GENERATOR -> parseGeneratorSourceConfig();
            // Any other name token is read as a connection reference:
            //   source T from <connection> { table: "...", schema: { … } }
            default -> {
                if (type.isNameCompatible()) {
                    yield parseConnectionTableSourceConfig();
                }
                throw parser.error(
                        "Expected 'http', 'database', 'csv', or a connection name after 'from'");
            }
        };
    }

    /**
     * Parses {@code generator { name: "<gen>", <key>: "<val>", … }} — the generator
     * source form (ADR-0008). The generator owns its schema (resolved from the
     * registry by {@code name}), so there is no {@code schema:} field; every other
     * key/value pair is a raw generator argument.
     */
    private GeneratorSourceConfig parseGeneratorSourceConfig() {
        parser.consume(LangTokenType.GENERATOR);
        parser.consume(LangTokenType.LBRACE);

        String name = null;
        Map<String, String> args = new LinkedHashMap<>();
        FieldLoop loop = new FieldLoop("generator");
        for (String field; (field = loop.next()) != null; ) {
            String value = parser.requireStringLit(field + " value");
            if (field.equals("name")) {
                name = value;
            } else {
                args.put(field, value);
            }
        }
        LangToken end = loop.end();

        if (name == null) {
            throw new LangParseException("generator source missing 'name'", end.line(), end.column());
        }
        return new GeneratorSourceConfig(name, args);
    }

    private ConnectionTableSourceConfig parseConnectionTableSourceConfig() {
        String connection = parser.requireName("connection name");
        parser.consume(LangTokenType.LBRACE);

        String table = null;
        List<ColumnSpec> columns = null;
        List<ColumnReference> references = List.of();
        FieldLoop loop = new FieldLoop("connection-table");
        for (String field; (field = loop.next()) != null; ) {
            switch (field) {
                case "table"      -> table      = parser.requireStringLit("table name");
                case "schema"     -> columns    = parseSchemaBlock(false);
                case "references" -> references = parseReferencesBlock();
                default -> throw loop.unknownField(field);
            }
        }
        LangToken end = loop.end();

        if (table == null)   throw new LangParseException("connection-table source missing 'table'",  end.line(), end.column());
        if (columns == null) throw new LangParseException("connection-table source missing 'schema'", end.line(), end.column());

        return new ConnectionTableSourceConfig(connection, table, columns, references);
    }

    // ── HTTP source ───────────────────────────────────────────────────────────

    private HttpSourceConfig parseHttpSourceConfig() {
        parser.consume(LangTokenType.HTTP);
        parser.consume(LangTokenType.LBRACE);

        String url = null;
        HttpMethod method = HttpMethod.GET;
        Map<String, String> headers = Map.of();
        Optional<ExtractSpec> extract = Optional.empty();
        Optional<PaginateSpec> paginate = Optional.empty();
        List<ColumnSpec> columns = List.of();
        Optional<String> body = Optional.empty();
        Optional<AuthSpec> auth = Optional.empty();

        FieldLoop loop = new FieldLoop("HTTP");
        for (String field; (field = loop.next()) != null; ) {
            switch (field) {
                case "url"      -> url      = parser.requireStringLit("url value");
                case "method"   -> method   = parseHttpMethod();
                case "headers"  -> headers  = parseHeadersMap();
                case "auth"     -> auth     = Optional.of(parseAuthSpec());
                case "body"     -> body     = Optional.of(parser.requireStringLit("body value"));
                case "extract"  -> extract  = Optional.of(parseExtractSpec());
                case "paginate" -> paginate = Optional.of(parsePaginateBlock());
                case "schema"   -> columns  = parseSchemaBlock(true);
                default -> throw loop.unknownField(field);
            }
        }
        LangToken end = loop.end();

        // url is the only required field.  An absent extract defaults to the
        // JSON-file record extraction (top-level array/object); an absent schema
        // selects an open (schema-on-read) source.
        if (url == null) throw new LangParseException("HTTP source missing 'url'", end.line(), end.column());

        return new HttpSourceConfig(url, method, headers, extract, paginate, columns, body, auth);
    }

    /**
     * Parses an HTTP method, restricted to the two read methods {@code GET} and
     * {@code POST} (Relix is read-only by design — see {@link HttpMethod}).
     */
    private HttpMethod parseHttpMethod() {
        HttpMethod m = switch (parser.current.type()) {
            case GET  -> HttpMethod.GET;
            case POST -> HttpMethod.POST;
            default -> throw parser.error(
                    "Expected HTTP method GET or POST (Relix is read-only; "
                    + "PUT/PATCH/DELETE/HEAD are not supported)");
        };
        parser.advance();
        return m;
    }

    /**
     * Parses an {@code auth: …} shorthand: {@code bearer("…")},
     * {@code basic("…", "…")}, or {@code apikey(name|query("…"), "…")}.
     */
    private AuthSpec parseAuthSpec() {
        String kind = parser.requireName("auth type (bearer, basic, or apikey)");
        parser.consume(LangTokenType.LPAREN);
        AuthSpec spec;
        if (kind.equalsIgnoreCase("bearer")) {
            spec = new BearerAuth(parser.requireStringLit("bearer token"));
        } else if (kind.equalsIgnoreCase("basic")) {
            String user = parser.requireStringLit("basic-auth username");
            parser.consume(LangTokenType.COMMA);
            String pass = parser.requireStringLit("basic-auth password");
            spec = new BasicAuth(user, pass);
        } else if (kind.equalsIgnoreCase("apikey")) {
            ApiKeyAuth.ApiKeyLocation location = ApiKeyAuth.ApiKeyLocation.HEADER;
            String name;
            if (parser.current.type() == LangTokenType.QUERY) {
                parser.advance();
                parser.consume(LangTokenType.LPAREN);
                name = parser.requireStringLit("apikey query-parameter name");
                parser.consume(LangTokenType.RPAREN);
                location = ApiKeyAuth.ApiKeyLocation.QUERY;
            } else {
                name = parser.requireStringLit("apikey header name");
            }
            parser.consume(LangTokenType.COMMA);
            String value = parser.requireStringLit("apikey value");
            spec = new ApiKeyAuth(name, value, location);
        } else {
            throw parser.error("Expected 'bearer', 'basic', or 'apikey' auth type");
        }
        parser.consume(LangTokenType.RPAREN);
        return spec;
    }

    private Map<String, String> parseHeadersMap() {
        parser.consume(LangTokenType.LBRACE);
        Map<String, String> map = new LinkedHashMap<>();
        while (parser.current.type() != LangTokenType.RBRACE
                && parser.current.type() != LangTokenType.EOF) {
            String key   = parser.requireStringLit("header name");
            parser.consume(LangTokenType.COLON);
            String value = parser.requireStringLit("header value");
            map.put(key, value);
            if (parser.current.type() == LangTokenType.COMMA) {
                parser.advance();
            }
        }
        parser.consume(LangTokenType.RBRACE);
        return Collections.unmodifiableMap(map);
    }

    private ExtractSpec parseExtractSpec() {
        if (parser.current.type() == LangTokenType.JSON) {
            parser.advance();
            parser.consume(LangTokenType.LPAREN);
            String path = parser.requireStringLit("JSON path");
            parser.consume(LangTokenType.RPAREN);
            return new JsonExtractSpec(path);
        }
        if (parser.current.type() == LangTokenType.CSV) {
            parser.advance();
            parser.consume(LangTokenType.LPAREN);
            boolean hasHeader = true;
            if (parser.current.type() == LangTokenType.HEADER) {
                parser.advance();
                parser.consume(LangTokenType.COLON);
                hasHeader = parser.requireBool("CSV extract header flag");
            }
            parser.consume(LangTokenType.RPAREN);
            return new CsvExtractSpec(hasHeader);
        }
        throw parser.error("Expected 'json(...)' or 'csv(...)' extract specification");
    }

    private PaginateSpec parsePaginateBlock() {
        parser.consume(LangTokenType.LBRACE);
        List<PaginateEntry> entries = new ArrayList<>();
        while (parser.current.type() != LangTokenType.RBRACE
                && parser.current.type() != LangTokenType.EOF) {
            String logicalName = parser.requireName("pagination entry name");
            parser.consume(LangTokenType.COLON);
            parser.consume(LangTokenType.QUERY);
            parser.consume(LangTokenType.LPAREN);
            String paramName = parser.requireStringLit("pagination parameter name");
            parser.consume(LangTokenType.RPAREN);

            Optional<Long> defaultValue = Optional.empty();
            if (parser.current.type() == LangTokenType.LBRACKET) {
                parser.advance();
                parser.consume(LangTokenType.DEFAULT);
                parser.consume(LangTokenType.COLON);
                String num = parser.requireNumberLit("pagination default value");
                defaultValue = Optional.of(Long.parseLong(num));
                parser.consume(LangTokenType.RBRACKET);
            }
            entries.add(new PaginateEntry(logicalName, paramName, defaultValue));
            if (parser.current.type() == LangTokenType.COMMA) {
                parser.advance();
            }
        }
        parser.consume(LangTokenType.RBRACE);
        return new PaginateSpec(entries);
    }

    /**
     * Parses a schema block {@code \{ colName: [in|out] TYPE [binding] [modifier] , … \}}.
     *
     * @param allowDirection if {@code true}, {@code in}/{@code out} prefixes are
     *                       accepted (HTTP schemas); if {@code false}, all columns
     *                       default to {@link ColumnDirection#OUT} (database/CSV)
     */
    private List<ColumnSpec> parseSchemaBlock(boolean allowDirection) {
        parser.consume(LangTokenType.LBRACE);
        List<ColumnSpec> specs = new ArrayList<>();
        while (parser.current.type() != LangTokenType.RBRACE
                && parser.current.type() != LangTokenType.EOF) {
            specs.add(parseColumnSpec(allowDirection));
            if (parser.current.type() == LangTokenType.COMMA) {
                parser.advance();
            }
        }
        parser.consume(LangTokenType.RBRACE);
        return Collections.unmodifiableList(specs);
    }

    private ColumnSpec parseColumnSpec(boolean allowDirection) {
        String name = parser.requireColumnName("column name");
        parser.consume(LangTokenType.COLON);

        ColumnDirection direction = ColumnDirection.OUT;
        if (allowDirection) {
            if (parser.current.type() == LangTokenType.IN) {
                direction = ColumnDirection.IN;
                parser.advance();
            } else if (parser.current.type() == LangTokenType.OUT) {
                parser.advance(); // explicit 'out' — direction is already OUT
            }
        }

        Type type = parser.requireType();

        // Optional binding
        Optional<ColumnBinding> binding = Optional.empty();
        if (parser.current.type() == LangTokenType.AS) {
            parser.advance();
            binding = Optional.of(parseColumnBinding());
        } else if (parser.current.type() == LangTokenType.AT) {
            parser.advance();
            String path = parser.requireStringLit("extract path");
            binding = Optional.of(new ExtractPathBinding(path));
        }

        // Optional modifier: [required] or [default: "value"]
        boolean required = false;
        Optional<String> defaultValue = Optional.empty();
        if (parser.current.type() == LangTokenType.LBRACKET) {
            parser.advance();
            if (parser.current.type() == LangTokenType.REQUIRED) {
                required = true;
                parser.advance();
            } else if (parser.current.type() == LangTokenType.DEFAULT) {
                parser.advance();
                parser.consume(LangTokenType.COLON);
                defaultValue = Optional.of(parser.requireStringLit("column default value"));
            } else {
                throw parser.error(
                        "Expected 'required' or 'default' inside column modifier brackets");
            }
            parser.consume(LangTokenType.RBRACKET);
        }

        return new ColumnSpec(direction, name, type, binding, required, defaultValue);
    }

    private ColumnBinding parseColumnBinding() {
        return switch (parser.current.type()) {
            case QUERY -> {
                parser.advance();
                parser.consume(LangTokenType.LPAREN);
                String param = parser.requireStringLit("query parameter name");
                parser.consume(LangTokenType.RPAREN);
                yield new QueryParamBinding(param);
            }
            case PATH -> {
                parser.advance();
                parser.consume(LangTokenType.LPAREN);
                String param = parser.requireStringLit("path parameter name");
                parser.consume(LangTokenType.RPAREN);
                yield new PathParamBinding(param);
            }
            case HEADER -> {
                parser.advance();
                parser.consume(LangTokenType.LPAREN);
                String hdr = parser.requireStringLit("header name");
                parser.consume(LangTokenType.RPAREN);
                yield new HeaderBinding(hdr);
            }
            default -> throw parser.error(
                    "Expected 'query', 'path', or 'header' column binding type");
        };
    }

    // ── Database source ───────────────────────────────────────────────────────

    private DatabaseSourceConfig parseDatabaseSourceConfig() {
        parser.consume(LangTokenType.DATABASE);
        parser.consume(LangTokenType.LBRACE);

        String url = null;
        String table = null;
        List<ColumnSpec> columns = null;
        List<ColumnReference> references = List.of();

        FieldLoop loop = new FieldLoop("database");
        for (String field; (field = loop.next()) != null; ) {
            switch (field) {
                case "url"        -> url        = parser.requireStringLit("url value");
                case "table"      -> table      = parser.requireStringLit("table name");
                case "schema"     -> columns    = parseSchemaBlock(false);
                case "references" -> references = parseReferencesBlock();
                default -> throw loop.unknownField(field);
            }
        }
        LangToken end = loop.end();

        if (url == null)     throw new LangParseException("Database source missing 'url'",    end.line(), end.column());
        if (table == null)   throw new LangParseException("Database source missing 'table'",  end.line(), end.column());
        if (columns == null) throw new LangParseException("Database source missing 'schema'", end.line(), end.column());

        return new DatabaseSourceConfig(url, table, columns, references);
    }

    /** Delegates to the shared block parser (also used by inline-table suffixes). */
    private List<ColumnReference> parseReferencesBlock() {
        return parser.parseReferencesBlock();
    }

    // ── CSV file source ───────────────────────────────────────────────────────

    private CsvFileSourceConfig parseCsvFileSourceConfig() {
        parser.consume(LangTokenType.CSV);
        parser.consume(LangTokenType.LPAREN);
        String path = parser.requireStringLit("CSV file path");
        parser.consume(LangTokenType.RPAREN);
        parser.consume(LangTokenType.LBRACE);

        boolean hasHeader = true;
        List<ColumnSpec> columns = null;
        List<ColumnReference> references = List.of();

        FieldLoop loop = new FieldLoop("CSV");
        for (String field; (field = loop.next()) != null; ) {
            switch (field) {
                case "header"     -> hasHeader  = parser.requireBool("CSV header flag");
                case "schema"     -> columns    = parseSchemaBlock(false);
                case "references" -> references = parseReferencesBlock();
                default -> throw loop.unknownField(field);
            }
        }
        LangToken end = loop.end();

        if (columns == null) throw new LangParseException("CSV source missing 'schema'", end.line(), end.column());

        return new CsvFileSourceConfig(path, hasHeader, columns, references);
    }

    // ── JSON file source ──────────────────────────────────────────────────────

    /**
     * Parses {@code json("path") [ { records: "a.b.items" } ]}.
     *
     * <p>The configuration block is optional: a JSON source declares no schema
     * (it is open / schema-on-read); the recognised fields are the optional
     * {@code records} path to the array of record objects and an optional
     * {@code references:} block (ADR-0024).
     */
    private JsonFileSourceConfig parseJsonFileSourceConfig() {
        parser.consume(LangTokenType.JSON);
        parser.consume(LangTokenType.LPAREN);
        String path = parser.requireStringLit("JSON file path");
        parser.consume(LangTokenType.RPAREN);

        Optional<String> records = Optional.empty();
        List<ColumnReference> references = List.of();
        if (parser.current.type() == LangTokenType.LBRACE) {
            parser.advance();
            FieldLoop loop = new FieldLoop("JSON");
            for (String field; (field = loop.next()) != null; ) {
                switch (field) {
                    case "records"    -> records    = Optional.of(parser.requireStringLit("records path"));
                    case "references" -> references = parseReferencesBlock();
                    default -> throw loop.unknownField(field);
                }
            }
        }

        return new JsonFileSourceConfig(path, records, references);
    }

    // ── Shared { field: value, … } loop ───────────────────────────────────────

    /**
     * Drives the {@code field: value, …} loop body that every source-config block
     * repeats. It reads field names until <code>&#125;</code>/EOF, consumes the {@code :}
     * after each name and the optional {@code ,} between fields, and owns the
     * closing-brace and unknown-field handling. The caller consumes the opening
     * <code>&#123;</code> first (some blocks are optional or follow a {@code (path)}), then
     * drives only its own field-name switch:
     *
     * <pre>{@code
     * FieldLoop loop = new FieldLoop("HTTP");
     * for (String field; (field = loop.next()) != null; ) {
     *     switch (field) {
     *         case "url" -> url = parser.requireStringLit("url value");
     *         …
     *         default -> throw loop.unknownField(field);
     *     }
     * }
     * LangToken end = loop.end();   // the consumed '}', for required-field errors
     * }</pre>
     */
    private final class FieldLoop {

        private final String label;
        private boolean first = true;
        private LangToken fieldTok;
        private LangToken end;

        FieldLoop(String label) {
            this.label = label;
        }

        /**
         * Advances to the next field, consuming the inter-field comma, the field
         * name, and its trailing colon.
         *
         * @return the next field name, or {@code null} once the block ends (having
         *         consumed the closing <code>&#125;</code>)
         */
        String next() {
            if (!first && parser.current.type() == LangTokenType.COMMA) {
                parser.advance();
            }
            first = false;
            if (parser.current.type() == LangTokenType.RBRACE
                    || parser.current.type() == LangTokenType.EOF) {
                end = parser.current;
                parser.consume(LangTokenType.RBRACE);
                return null;
            }
            fieldTok = parser.current;
            String field = parser.requireName(label + " config field name");
            parser.consume(LangTokenType.COLON);
            return field;
        }

        /** Builds a positioned error for an unrecognised field name. */
        LangParseException unknownField(String field) {
            return new LangParseException(
                    "Unknown " + label + " config field '" + field + "'",
                    fieldTok.line(), fieldTok.column());
        }

        /** The closing <code>&#125;</code> token (already consumed); for required-field errors. */
        LangToken end() {
            return end;
        }
    }
}

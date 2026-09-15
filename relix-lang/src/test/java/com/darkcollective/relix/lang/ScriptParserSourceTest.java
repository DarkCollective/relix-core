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

import com.darkcollective.relix.lang.ast.*;
import com.darkcollective.relix.lang.ast.source.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ScriptParser} covering source declarations:
 * HTTP sources, database sources, CSV file sources, HTTP methods,
 * and source-config error paths.
 */
@DisplayName("ScriptParser — source declarations")
class ScriptParserSourceTest {

    private static Script parse(String source) {
        return ScriptParser.parse(source);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Statement> T firstStatement(Script s) {
        return (T) s.statements().get(0);
    }

    // =========================================================================
    // HTTP source declarations
    // =========================================================================

    @Nested
    @DisplayName("source declaration — HTTP")
    class HttpSourceTests {

        private static final String HTTP_SOURCE = """
                source WeatherAPI from http {
                    url: "https://api.example.com/weather",
                    method: GET,
                    headers: {
                        "Authorization": "Bearer ${TOKEN}"
                    },
                    extract: json("$."),
                    schema: {
                        city:  in  STRING as query("q")   [required],
                        units: in  STRING as query("units") [default: "metric"],
                        temp:  out NUMBER at "$.main.temp"
                    }
                };
                """;

        @Test
        @DisplayName("Parses complete HTTP source declaration")
        void parsesCompleteHttpSource() {
            Script s = parse(HTTP_SOURCE);
            SourceDeclaration sd = firstStatement(s);
            assertThat(sd.name()).isEqualTo("WeatherAPI");
            assertThat(sd.exported()).isTrue();
            assertThat(sd.config()).isInstanceOf(HttpSourceConfig.class);

            HttpSourceConfig cfg = (HttpSourceConfig) sd.config();
            assertThat(cfg.url()).isEqualTo("https://api.example.com/weather");
            assertThat(cfg.method()).isEqualTo(HttpMethod.GET);
            assertThat(cfg.headers()).containsKey("Authorization");
            assertThat(cfg.extract().orElseThrow()).isInstanceOf(JsonExtractSpec.class);
            assertThat(cfg.paginate()).isEmpty();
            assertThat(cfg.columns()).hasSize(3);
        }

        @Test
        @DisplayName("IN column with required modifier")
        void inColumnWithRequired() {
            Script s = parse(HTTP_SOURCE);
            HttpSourceConfig cfg = (HttpSourceConfig)
                    ((SourceDeclaration) firstStatement(s)).config();
            ColumnSpec city = cfg.columns().get(0);
            assertThat(city.name()).isEqualTo("city");
            assertThat(city.direction()).isEqualTo(ColumnDirection.IN);
            assertThat(city.type()).isEqualTo(com.darkcollective.relix.symbol.ScalarType.STRING);
            assertThat(city.binding()).isPresent();
            assertThat(city.binding().get()).isInstanceOf(QueryParamBinding.class);
            assertThat(city.required()).isTrue();
        }

        @Test
        @DisplayName("IN column with default modifier")
        void inColumnWithDefault() {
            Script s = parse(HTTP_SOURCE);
            HttpSourceConfig cfg = (HttpSourceConfig)
                    ((SourceDeclaration) firstStatement(s)).config();
            ColumnSpec units = cfg.columns().get(1);
            assertThat(units.direction()).isEqualTo(ColumnDirection.IN);
            assertThat(units.required()).isFalse();
            assertThat(units.defaultValue()).isEqualTo(Optional.of("metric"));
        }

        @Test
        @DisplayName("OUT column with extract-path binding")
        void outColumnWithExtractPath() {
            Script s = parse(HTTP_SOURCE);
            HttpSourceConfig cfg = (HttpSourceConfig)
                    ((SourceDeclaration) firstStatement(s)).config();
            ColumnSpec temp = cfg.columns().get(2);
            assertThat(temp.direction()).isEqualTo(ColumnDirection.OUT);
            assertThat(temp.binding()).isPresent();
            assertThat(temp.binding().get()).isInstanceOf(ExtractPathBinding.class);
            assertThat(((ExtractPathBinding) temp.binding().get()).path())
                    .isEqualTo("$.main.temp");
        }

        @Test
        @DisplayName("Private HTTP source sets exported to false")
        void privateHttpSource() {
            String src = """
                    private source InternalAPI from http {
                        url: "http://internal",
                        method: POST,
                        extract: json("$."),
                        schema: { result: out STRING }
                    };
                    """;
            Script s = parse(src);
            SourceDeclaration sd = firstStatement(s);
            assertThat(sd.exported()).isFalse();
        }

        @Test
        @DisplayName("HTTP source with paginate block")
        void httpSourceWithPaginate() {
            String src = """
                    source API from http {
                        url: "https://example.com/items",
                        method: GET,
                        extract: json("$.items[*]"),
                        paginate: {
                            limit:  query("limit")  [default: 100],
                            offset: query("offset") [default: 0]
                        },
                        schema: {
                            id:   out NUMBER,
                            name: out STRING
                        }
                    };
                    """;
            Script s = parse(src);
            HttpSourceConfig cfg = (HttpSourceConfig)
                    ((SourceDeclaration) firstStatement(s)).config();
            assertThat(cfg.paginate()).isPresent();
            PaginateSpec pg = cfg.paginate().get();
            assertThat(pg.entry("limit")).isPresent();
            assertThat(pg.entry("limit").get().defaultValue())
                    .isEqualTo(Optional.of(100L));
            assertThat(pg.entry("offset").get().defaultValue())
                    .isEqualTo(Optional.of(0L));
        }

        @Test
        @DisplayName("HTTP source with path binding")
        void httpSourceWithPathBinding() {
            String src = """
                    source ItemDetail from http {
                        url: "https://example.com/items/{id}",
                        method: GET,
                        extract: json("$."),
                        schema: {
                            id:    in  NUMBER as path("id") [required],
                            title: out STRING
                        }
                    };
                    """;
            Script s = parse(src);
            HttpSourceConfig cfg = (HttpSourceConfig)
                    ((SourceDeclaration) firstStatement(s)).config();
            ColumnSpec id = cfg.columns().get(0);
            assertThat(id.binding().get()).isInstanceOf(PathParamBinding.class);
        }

        @Test
        @DisplayName("HTTP source with header binding")
        void httpSourceWithHeaderBinding() {
            String src = """
                    source API from http {
                        url: "https://api.example.com/data",
                        method: GET,
                        extract: json("$."),
                        schema: {
                            city:   in STRING as header("X-City") [required],
                            result: out STRING
                        }
                    };
                    """;
            Script s = parse(src);
            HttpSourceConfig cfg = (HttpSourceConfig)
                    ((SourceDeclaration) firstStatement(s)).config();
            ColumnSpec city = cfg.columns().get(0);
            assertThat(city.binding().get()).isInstanceOf(HeaderBinding.class);
            assertThat(((HeaderBinding) city.binding().get()).headerName()).isEqualTo("X-City");
        }

        @Test
        @DisplayName("HTTP POST method is parsed")
        void httpPostMethod() {
            String src = """
                    source S from http {
                        url: "https://example.com/",
                        method: POST,
                        extract: json("$."),
                        schema: { id: out NUMBER }
                    };
                    """;
            Script s = parse(src);
            HttpSourceConfig cfg = (HttpSourceConfig)
                    ((SourceDeclaration) firstStatement(s)).config();
            assertThat(cfg.method()).isEqualTo(HttpMethod.POST);
        }

        @Test
        @DisplayName("HTTP source with CSV extract")
        void httpSourceWithCsvExtract() {
            String src = """
                    source S from http {
                        url: "https://example.com/",
                        method: GET,
                        extract: csv(header: true),
                        schema: { id: out NUMBER }
                    };
                    """;
            Script s = parse(src);
            HttpSourceConfig cfg = (HttpSourceConfig)
                    ((SourceDeclaration) firstStatement(s)).config();
            assertThat(cfg.extract().orElseThrow()).isInstanceOf(CsvExtractSpec.class);
            assertThat(((CsvExtractSpec) cfg.extract().orElseThrow()).hasHeader()).isTrue();
        }

        @Test
        @DisplayName("Pagination default 0 is accepted")
        void paginationDefaultZero() {
            Script s = parse("""
                    source API from http {
                        url: "https://x",
                        method: GET,
                        extract: json("$."),
                        paginate: { offset: query("offset") [default: 0] },
                        schema: { id: out NUMBER }
                    };
                    """);
            HttpSourceConfig cfg = (HttpSourceConfig)
                    ((SourceDeclaration) firstStatement(s)).config();
            assertThat(cfg.paginate().get().entry("offset").get().defaultValue())
                    .isEqualTo(Optional.of(0L));
        }

        private HttpSourceConfig parseHttpBlock(String body) {
            Script s = parse("source S from http { url: \"https://x\", " + body + " };");
            return (HttpSourceConfig) ((SourceDeclaration) firstStatement(s)).config();
        }

        @Test
        @DisplayName("Open source — no schema, no extract")
        void openSourceNoSchema() {
            HttpSourceConfig cfg = parseHttpBlock("method: GET");
            assertThat(cfg.isOpen()).isTrue();
            assertThat(cfg.extract()).isEmpty();
        }

        @Test
        @DisplayName("POST body is parsed")
        void postBody() {
            HttpSourceConfig cfg = parseHttpBlock(
                    "method: POST, body: \"{ \\\"query\\\": \\\"{ users { id } }\\\" }\"");
            assertThat(cfg.method()).isEqualTo(HttpMethod.POST);
            assertThat(cfg.body()).contains("{ \"query\": \"{ users { id } }\" }");
        }

        @Test
        @DisplayName("auth: bearer(...)")
        void bearerAuth() {
            HttpSourceConfig cfg = parseHttpBlock("auth: bearer(\"tok123\")");
            assertThat(cfg.auth()).contains(new BearerAuth("tok123"));
        }

        @Test
        @DisplayName("auth: basic(user, pass)")
        void basicAuth() {
            HttpSourceConfig cfg = parseHttpBlock("auth: basic(\"alice\", \"s3cret\")");
            assertThat(cfg.auth()).contains(new BasicAuth("alice", "s3cret"));
        }

        @Test
        @DisplayName("auth: apikey(name, value) defaults to a header")
        void apiKeyHeaderAuth() {
            HttpSourceConfig cfg = parseHttpBlock("auth: apikey(\"X-API-Key\", \"k\")");
            assertThat(cfg.auth()).contains(
                    new ApiKeyAuth("X-API-Key", "k", ApiKeyAuth.ApiKeyLocation.HEADER));
        }

        @Test
        @DisplayName("auth: apikey(query(name), value) places the key in the query string")
        void apiKeyQueryAuth() {
            HttpSourceConfig cfg = parseHttpBlock("auth: apikey(query(\"api_key\"), \"k\")");
            assertThat(cfg.auth()).contains(
                    new ApiKeyAuth("api_key", "k", ApiKeyAuth.ApiKeyLocation.QUERY));
        }

        @Test
        @DisplayName("Unknown auth type throws")
        void unknownAuthType() {
            assertThatThrownBy(() -> parseHttpBlock("auth: oauth(\"x\")"))
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining("bearer");
        }
    }

    // =========================================================================
    // Database source declarations
    // =========================================================================

    @Nested
    @DisplayName("source declaration — Database")
    class DatabaseSourceTests {

        private static final String DB_SOURCE = """
                source Users from database {
                    url:   "${DB_URL}",
                    table: "users",
                    schema: {
                        id:    NUMBER,
                        name:  STRING,
                        email: STRING
                    }
                };
                """;

        @Test
        @DisplayName("Parses database source declaration")
        void parsesDatabaseSource() {
            Script s = parse(DB_SOURCE);
            SourceDeclaration sd = firstStatement(s);
            assertThat(sd.name()).isEqualTo("Users");
            assertThat(sd.config()).isInstanceOf(DatabaseSourceConfig.class);

            DatabaseSourceConfig cfg = (DatabaseSourceConfig) sd.config();
            assertThat(cfg.url()).isEqualTo("${DB_URL}");
            assertThat(cfg.table()).isEqualTo("users");
            assertThat(cfg.columns()).hasSize(3);
        }

        @Test
        @DisplayName("Database columns are all OUT direction")
        void databaseColumnsAreOut() {
            Script s = parse(DB_SOURCE);
            DatabaseSourceConfig cfg = (DatabaseSourceConfig)
                    ((SourceDeclaration) firstStatement(s)).config();
            assertThat(cfg.columns()).allMatch(c -> c.direction() == ColumnDirection.OUT);
        }

        @Test
        @DisplayName("Temporal column types parse (DATE / TIME / TIMESTAMP / DURATION)")
        void temporalColumnTypes() {
            Script s = parse("""
                    source Trades from database {
                        url: "${DB}", table: "trades",
                        schema: { at: TIMESTAMP, day: DATE, clock: TIME, held: DURATION }
                    };
                    """);
            DatabaseSourceConfig cfg = (DatabaseSourceConfig)
                    ((SourceDeclaration) firstStatement(s)).config();
            assertThat(cfg.columns())
                    .extracting(ColumnSpec::type)
                    .containsExactly(
                            com.darkcollective.relix.symbol.ScalarType.TIMESTAMP,
                            com.darkcollective.relix.symbol.ScalarType.DATE,
                            com.darkcollective.relix.symbol.ScalarType.TIME,
                            com.darkcollective.relix.symbol.ScalarType.DURATION);
        }

        @Test
        @DisplayName("BOOLEAN is a column type, as the catalog already reports one")
        void booleanColumnType() {
            Script s = parse("""
                    source Flags from database {
                        url: "${DB}", table: "flags",
                        schema: { id: NUMBER, active: BOOLEAN }
                    };
                    """);
            DatabaseSourceConfig cfg = (DatabaseSourceConfig)
                    ((SourceDeclaration) firstStatement(s)).config();
            assertThat(cfg.columns())
                    .extracting(ColumnSpec::type)
                    .containsExactly(
                            com.darkcollective.relix.symbol.ScalarType.NUMBER,
                            com.darkcollective.relix.symbol.ScalarType.BOOLEAN);
        }

        @Test
        @DisplayName("'boolean' is still usable as a column name — the keyword is name-compatible")
        void booleanIsStillAName() {
            Script s = parse("""
                    source Flags from database {
                        url: "${DB}", table: "flags",
                        schema: { boolean: STRING }
                    };
                    """);
            DatabaseSourceConfig cfg = (DatabaseSourceConfig)
                    ((SourceDeclaration) firstStatement(s)).config();
            assertThat(cfg.columns())
                    .extracting(ColumnSpec::name)
                    .containsExactly("boolean");
        }
    }

    // =========================================================================
    // CSV file source declarations
    // =========================================================================

    @Nested
    @DisplayName("source declaration — CSV file")
    class CsvFileSourceTests {

        @Test
        @DisplayName("Parses CSV file source declaration")
        void parsesCsvFileSource() {
            String src = """
                    source Products from csv("./products.csv") {
                        header: true,
                        schema: {
                            id:    NUMBER,
                            name:  STRING,
                            price: NUMBER
                        }
                    };
                    """;
            Script s = parse(src);
            SourceDeclaration sd = firstStatement(s);
            assertThat(sd.config()).isInstanceOf(CsvFileSourceConfig.class);
            CsvFileSourceConfig cfg = (CsvFileSourceConfig) sd.config();
            assertThat(cfg.path()).isEqualTo("./products.csv");
            assertThat(cfg.hasHeader()).isTrue();
            assertThat(cfg.columns()).hasSize(3);
        }

        @Test
        @DisplayName("CSV source with header false")
        void csvSourceWithHeaderFalse() {
            String src = """
                    source Raw from csv("./raw.csv") {
                        header: false,
                        schema: { val: NUMBER }
                    };
                    """;
            Script s = parse(src);
            CsvFileSourceConfig cfg = (CsvFileSourceConfig)
                    ((SourceDeclaration) firstStatement(s)).config();
            assertThat(cfg.hasHeader()).isFalse();
        }

        @Test
        @DisplayName("CSV source default header is true")
        void csvSourceDefaultHeaderIsTrue() {
            String src = """
                    source Raw from csv("./data.csv") {
                        schema: { id: NUMBER }
                    };
                    """;
            Script s = parse(src);
            CsvFileSourceConfig cfg = (CsvFileSourceConfig)
                    ((SourceDeclaration) firstStatement(s)).config();
            assertThat(cfg.hasHeader()).isTrue();
        }
    }

    @Nested
    @DisplayName("source declaration — JSON file (open)")
    class JsonFileSourceTests {

        @Test
        @DisplayName("Parses a bare JSON source with no config block")
        void parsesBareJsonSource() {
            Script s = parse("source Docs from json(\"docs.json\");");
            SourceDeclaration sd = firstStatement(s);
            assertThat(sd.config()).isInstanceOf(JsonFileSourceConfig.class);
            JsonFileSourceConfig cfg = (JsonFileSourceConfig) sd.config();
            assertThat(cfg.path()).isEqualTo("docs.json");
            assertThat(cfg.records()).isEmpty();
        }

        @Test
        @DisplayName("Parses a JSON source with a nested 'records' path")
        void parsesJsonSourceWithRecordsPath() {
            Script s = parse(
                    "source Items from json(\"wrapped.json\") { records: \"data.items\" };");
            JsonFileSourceConfig cfg = (JsonFileSourceConfig)
                    ((SourceDeclaration) firstStatement(s)).config();
            assertThat(cfg.path()).isEqualTo("wrapped.json");
            assertThat(cfg.records()).contains("data.items");
        }

        @Test
        @DisplayName("Unknown JSON config field is rejected")
        void rejectsUnknownJsonField() {
            assertThatThrownBy(() ->
                    parse("source D from json(\"d.json\") { bogus: \"x\" };"))
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining("Unknown JSON config field");
        }
    }

    // =========================================================================
    // HTTP method tokens
    // =========================================================================

    @Nested
    @DisplayName("HTTP method tokens")
    class HttpMethodTests {

        private HttpSourceConfig parseHttp(String method) {
            String src = "source S from http { url: \"https://x\", method: " + method +
                    ", extract: json(\"$.\"), schema: { id: out NUMBER } };";
            Script s = parse(src);
            return (HttpSourceConfig) ((SourceDeclaration) firstStatement(s)).config();
        }

        @Test @DisplayName("GET")  void get()  { assertThat(parseHttp("GET").method()).isEqualTo(HttpMethod.GET); }
        @Test @DisplayName("POST") void post() { assertThat(parseHttp("POST").method()).isEqualTo(HttpMethod.POST); }

        // Relix is read-only by design: the mutating methods (and the bodyless
        // HEAD) are rejected at parse time — they lex as ordinary identifiers,
        // which are not valid where a method keyword is expected.
        @Test @DisplayName("PUT rejected")    void put()    { assertReadOnlyRejected("PUT"); }
        @Test @DisplayName("PATCH rejected")  void patch()  { assertReadOnlyRejected("PATCH"); }
        @Test @DisplayName("DELETE rejected") void delete() { assertReadOnlyRejected("DELETE"); }
        @Test @DisplayName("HEAD rejected")   void head()   { assertReadOnlyRejected("HEAD"); }

        private void assertReadOnlyRejected(String method) {
            assertThatThrownBy(() -> parseHttp(method))
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining("read-only");
        }
    }

    // =========================================================================
    // Source config error paths
    // =========================================================================

    @Nested
    @DisplayName("source config error paths")
    class SourceConfigErrorTests {

        @Test
        @DisplayName("HTTP source missing url throws")
        void httpMissingUrl() {
            assertThatThrownBy(() -> parse("""
                    source S from http {
                        method: GET,
                        extract: json("$."),
                        schema: { id: out NUMBER }
                    };
                    """)).isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("HTTP source without schema is an open (schema-on-read) source")
        void httpMissingSchemaIsOpen() {
            Script s = parse("""
                    source S from http {
                        url: "https://x",
                        method: GET,
                        extract: json("$.")
                    };
                    """);
            HttpSourceConfig cfg = (HttpSourceConfig)
                    ((SourceDeclaration) firstStatement(s)).config();
            assertThat(cfg.isOpen()).isTrue();
        }

        @Test
        @DisplayName("HTTP source without extract defaults to JSON record extraction")
        void httpMissingExtractIsAllowed() {
            Script s = parse("""
                    source S from http {
                        url: "https://x"
                    };
                    """);
            HttpSourceConfig cfg = (HttpSourceConfig)
                    ((SourceDeclaration) firstStatement(s)).config();
            assertThat(cfg.extract()).isEmpty();
            assertThat(cfg.isOpen()).isTrue();
        }

        @Test
        @DisplayName("HTTP source unknown field throws")
        void httpUnknownField() {
            assertThatThrownBy(() -> parse("""
                    source S from http {
                        url: "https://x",
                        method: GET,
                        extract: json("$."),
                        badfield: 42,
                        schema: { id: out NUMBER }
                    };
                    """)).isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("Database source missing url throws")
        void databaseMissingUrl() {
            assertThatThrownBy(() -> parse("""
                    source S from database {
                        table: "users",
                        schema: { id: NUMBER }
                    };
                    """)).isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("Database source missing table throws")
        void databaseMissingTable() {
            assertThatThrownBy(() -> parse("""
                    source S from database {
                        url: "${DB_URL}",
                        schema: { id: NUMBER }
                    };
                    """)).isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("Database source missing schema throws")
        void databaseMissingSchema() {
            assertThatThrownBy(() -> parse("""
                    source S from database {
                        url: "${DB_URL}",
                        table: "users"
                    };
                    """)).isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("Database source unknown field throws")
        void databaseUnknownField() {
            assertThatThrownBy(() -> parse("""
                    source S from database {
                        url: "${DB}",
                        table: "t",
                        unknown: "x",
                        schema: { id: NUMBER }
                    };
                    """)).isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("CSV source missing schema throws")
        void csvMissingSchema() {
            assertThatThrownBy(() -> parse("""
                    source S from csv("./file.csv") {
                        header: true
                    };
                    """)).isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("CSV source unknown field throws")
        void csvUnknownField() {
            assertThatThrownBy(() -> parse("""
                    source S from csv("./file.csv") {
                        badfield: true,
                        schema: { id: NUMBER }
                    };
                    """)).isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("Invalid HTTP method throws")
        void invalidHttpMethod() {
            assertThatThrownBy(() -> parse("""
                    source S from http {
                        url: "https://x",
                        method: CONNECT,
                        extract: json("$."),
                        schema: { id: out NUMBER }
                    };
                    """)).isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("Invalid extract spec throws")
        void invalidExtractSpec() {
            assertThatThrownBy(() -> parse("""
                    source S from http {
                        url: "https://x",
                        method: GET,
                        extract: xml("./root"),
                        schema: { id: out NUMBER }
                    };
                    """)).isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("Invalid column binding type throws")
        void invalidColumnBindingType() {
            assertThatThrownBy(() -> parse("""
                    source S from http {
                        url: "https://x",
                        method: GET,
                        extract: json("$."),
                        schema: { id: in NUMBER as form("id") [required] }
                    };
                    """)).isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("Invalid column modifier throws")
        void invalidColumnModifier() {
            assertThatThrownBy(() -> parse("""
                    source S from http {
                        url: "https://x",
                        method: GET,
                        extract: json("$."),
                        schema: { id: out NUMBER [optional] }
                    };
                    """)).isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("Explicit 'out' direction is accepted")
        void explicitOutDirection() {
            Script s = parse("""
                    source S from http {
                        url: "https://x",
                        method: GET,
                        extract: json("$."),
                        schema: { id: out NUMBER }
                    };
                    """);
            HttpSourceConfig cfg = (HttpSourceConfig)
                    ((SourceDeclaration) firstStatement(s)).config();
            assertThat(cfg.columns().get(0).direction()).isEqualTo(ColumnDirection.OUT);
        }

        @Test
        @DisplayName("Headers map can be empty")
        void emptyHeadersMap() {
            Script s = parse("""
                    source S from http {
                        url: "https://x",
                        method: GET,
                        headers: {},
                        extract: json("$."),
                        schema: { id: out NUMBER }
                    };
                    """);
            HttpSourceConfig cfg = (HttpSourceConfig)
                    ((SourceDeclaration) firstStatement(s)).config();
            assertThat(cfg.headers()).isEmpty();
        }

        @Test
        @DisplayName("Paginate entry without default")
        void paginateEntryWithoutDefault() {
            Script s = parse("""
                    source API from http {
                        url: "https://x",
                        method: GET,
                        extract: json("$."),
                        paginate: {
                            page: query("page")
                        },
                        schema: { id: out NUMBER }
                    };
                    """);
            HttpSourceConfig cfg = (HttpSourceConfig)
                    ((SourceDeclaration) firstStatement(s)).config();
            assertThat(cfg.paginate().get().entry("page").get().defaultValue()).isEmpty();
        }

        @Test
        @DisplayName("HTTP source with a declared schema but no extract parses (extract optional)")
        void httpSchemaWithoutExtract() {
            String src = """
                    source S from http {
                        url: "https://x",
                        schema: { id: out NUMBER }
                    };
                    """;
            HttpSourceConfig cfg = (HttpSourceConfig)
                    ((SourceDeclaration) firstStatement(parse(src))).config();
            assertThat(cfg.extract()).isEmpty();
            assertThat(cfg.isOpen()).isFalse();
            assertThat(cfg.columns()).hasSize(1);
        }

        @Test
        @DisplayName("Unknown source type throws LangParseException")
        void unknownSourceType() {
            assertThatThrownBy(() -> parse("source X from ftp { };"))
                    .isInstanceOf(LangParseException.class);
        }
    }

    // =========================================================================
    // Generator source declarations (ADR-0008)
    // =========================================================================

    @Nested
    @DisplayName("source declaration — generator")
    class GeneratorSourceTests {

        @Test
        @DisplayName("Parses a generator source: name + args, no schema block")
        void parsesGeneratorSource() {
            Script s = parse("""
                    source R from generator { name: "Range", lo: "1", hi: "10", step: "2" };
                    """);
            SourceDeclaration sd = firstStatement(s);
            assertThat(sd.config()).isInstanceOf(GeneratorSourceConfig.class);
            GeneratorSourceConfig cfg = (GeneratorSourceConfig) sd.config();
            assertThat(cfg.generatorName()).isEqualTo("Range");
            assertThat(cfg.args())
                    .containsEntry("lo", "1")
                    .containsEntry("hi", "10")
                    .containsEntry("step", "2")
                    .doesNotContainKey("name");
        }

        @Test
        @DisplayName("A generator source without 'name' is rejected")
        void generatorSourceMissingName() {
            assertThatThrownBy(() -> parse("source R from generator { lo: \"1\" };"))
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining("name");
        }
    }
}

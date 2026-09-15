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
package com.darkcollective.relix.connectors.std;

import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.source.ColumnSpec;
import com.darkcollective.relix.lang.ast.source.CsvFileSourceConfig;
import com.darkcollective.relix.processor.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.QueryExecutor;
import com.darkcollective.relix.processor.QueryResult;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.processor.exec.RelNodeExecutor;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.semantic.SemanticFixtures;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.semantic.SemanticResult;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Stream;

import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThatRows;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CsvDataSourceConnector")
final class CsvDataSourceConnectorTest extends ProcessorTestSupport {

    // ── helpers ───────────────────────────────────────────────────────────────

    private static SemanticModel analyze(String src, Path baseDir) {
        SemanticResult result = SemanticFixtures.analyze(src);
        if (!result.errors().isEmpty()) {
            throw new IllegalStateException("Script has errors: " + result.errors());
        }
        return result.model().orElseThrow(
                () -> new IllegalStateException("No model produced"));
    }

    private static List<Row> exec(String script, Path baseDir) {
        SemanticModel model = analyze(script, baseDir);
        var connector = new CsvDataSourceConnector(model, baseDir);
        var executor  = new QueryExecutor();
        return executor.execute(model, connector).stream()
                .flatMap(qr -> qr.rows().stream())
                .toList();
    }

    private static Path writeFile(Path dir, String filename, String content) throws IOException {
        Path file = dir.resolve(filename);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    // ── parseCsvLine (unit tests) ─────────────────────────────────────────────

    @Nested
    @DisplayName("parseCsvLine")
    class ParseCsvLine {

        @Test
        @DisplayName("splits a simple line on commas")
        void simpleFields() {
            assertThat(CsvDataSourceConnector.parseCsvLine("a,b,c"))
                    .containsExactly("a", "b", "c");
        }

        @Test
        @DisplayName("empty line returns one empty field")
        void emptyLine() {
            assertThat(CsvDataSourceConnector.parseCsvLine(""))
                    .containsExactly("");
        }

        @Test
        @DisplayName("single field, no comma")
        void singleField() {
            assertThat(CsvDataSourceConnector.parseCsvLine("hello"))
                    .containsExactly("hello");
        }

        @Test
        @DisplayName("trailing comma produces trailing empty field")
        void trailingComma() {
            assertThat(CsvDataSourceConnector.parseCsvLine("a,b,"))
                    .containsExactly("a", "b", "");
        }

        @Test
        @DisplayName("empty fields in the middle")
        void middleEmptyFields() {
            assertThat(CsvDataSourceConnector.parseCsvLine("a,,c"))
                    .containsExactly("a", "", "c");
        }

        @Test
        @DisplayName("quoted field containing a comma")
        void quotedComma() {
            assertThat(CsvDataSourceConnector.parseCsvLine("\"hello, world\",42"))
                    .containsExactly("hello, world", "42");
        }

        @Test
        @DisplayName("doubled quote inside quoted field becomes literal quote")
        void escapedQuote() {
            assertThat(CsvDataSourceConnector.parseCsvLine("\"she said \"\"hi\"\"\",ok"))
                    .containsExactly("she said \"hi\"", "ok");
        }

        @Test
        @DisplayName("empty quoted field")
        void emptyQuotedField() {
            assertThat(CsvDataSourceConnector.parseCsvLine("\"\",b"))
                    .containsExactly("", "b");
        }

        @Test
        @DisplayName("unquoted field content is not trimmed")
        void noTrimming() {
            assertThat(CsvDataSourceConnector.parseCsvLine(" a , b "))
                    .containsExactly(" a ", " b ");
        }
    }

    // ── header-based mapping ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Header-based column mapping")
    class HeaderMapping {

        @Test
        @DisplayName("reads rows when header names match schema columns")
        void basicHeaderMapping(@TempDir Path dir) throws IOException {
            writeFile(dir, "products.csv",
                    "id,name,price\n" +
                    "1,Apple,0.99\n" +
                    "2,Banana,0.49\n");

            var rows = exec(
                    "source Products from csv(\"products.csv\") {\n" +
                    "    schema: { id: NUMBER, name: STRING, price: NUMBER }\n" +
                    "};\n" +
                    "query Products;",
                    dir);

            assertThatRows(rows)
                    .hasColumns("id", "name", "price")
                    .hasRowAt(0, "1", "Apple", "0.99")
                    .hasRowAt(1, "2", "Banana", "0.49");
        }

        @Test
        @DisplayName("header matching is case-insensitive")
        void caseInsensitiveHeaders(@TempDir Path dir) throws IOException {
            writeFile(dir, "data.csv",
                    "ID,Name\n" +
                    "42,Charlie\n");

            var rows = exec(
                    "source T from csv(\"data.csv\") {\n" +
                    "    schema: { id: NUMBER, name: STRING }\n" +
                    "};\n" +
                    "query T;",
                    dir);

            assertThatRows(rows).hasRowCount(1).hasRowAt(0, "42", "Charlie");
        }

        @Test
        @DisplayName("columns can appear in any order in the CSV file")
        void arbitraryColumnOrder(@TempDir Path dir) throws IOException {
            // CSV has price before name, schema declares name before price
            writeFile(dir, "data.csv",
                    "price,id,name\n" +
                    "9.99,10,Widget\n");

            var rows = exec(
                    "source Items from csv(\"data.csv\") {\n" +
                    "    schema: { id: NUMBER, name: STRING, price: NUMBER }\n" +
                    "};\n" +
                    "query Items;",
                    dir);

            assertThatRows(rows).hasRowCount(1).hasRowAt(0, "10", "Widget", "9.99");
        }

        @Test
        @DisplayName("missing header column throws EvaluationException")
        void missingHeaderColumn(@TempDir Path dir) throws IOException {
            writeFile(dir, "data.csv", "id,other\n1,x\n");

            SemanticModel model = analyze(
                    "source T from csv(\"data.csv\") { schema: { id: NUMBER, name: STRING } };\n" +
                    "query T;",
                    dir);
            var connector = new CsvDataSourceConnector(model, dir);
            var schema = new Schema(List.of(
                    new ColumnDefinition("id",   ScalarType.NUMBER),
                    new ColumnDefinition("name", ScalarType.STRING)));

            assertThatThrownBy(() -> connector.open("t", schema).toList())
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("name");
        }
    }

    // ── positional mapping ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Positional column mapping (header: false)")
    class PositionalMapping {

        @Test
        @DisplayName("maps CSV columns positionally when header is false")
        void positionalBasic(@TempDir Path dir) throws IOException {
            writeFile(dir, "data.csv",
                    "1,Alice\n" +
                    "2,Bob\n");

            var rows = exec(
                    "source People from csv(\"data.csv\") {\n" +
                    "    header: false,\n" +
                    "    schema: { id: NUMBER, name: STRING }\n" +
                    "};\n" +
                    "query People;",
                    dir);

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)).hasValue("id", "1")
                    .hasValue("name", "Alice");
            assertThat(rows.get(1)).hasValue("name", "Bob");
        }
    }

    // ── type coercion ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Type coercion")
    class TypeCoercion {

        @Test
        @DisplayName("empty cell becomes NullValue for all column types")
        void emptyIsNull(@TempDir Path dir) throws IOException {
            writeFile(dir, "data.csv",
                    "id,name\n" +
                    "1,\n" +
                    ",Bob\n");

            var rows = exec(
                    "source T from csv(\"data.csv\") { schema: { id: NUMBER, name: STRING } };\n" +
                    "query T;",
                    dir);

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).get("name")).isInstanceOf(NullValue.class);
            assertThat(rows.get(1).get("id")).isInstanceOf(NullValue.class);
            assertThat(rows.get(1)).hasValue("name", "Bob");
        }

        @Test
        @DisplayName("NUMBER column parses integers and decimals")
        void numberParsing(@TempDir Path dir) throws IOException {
            writeFile(dir, "data.csv", "val\n42\n3.14\n-7\n");

            var rows = exec(
                    "source T from csv(\"data.csv\") { schema: { val: NUMBER } };\n" +
                    "query T;",
                    dir);

            assertThat(rows).hasSize(3);
            assertThat(rows.get(0).get("val")).isInstanceOf(NumberValue.class);
            assertThat(rows.get(0)).hasValue("val", "42");
            assertThat(rows.get(1)).hasValue("val", "3.14");
            assertThat(rows.get(2)).hasValue("val", "-7");
        }

        @Test
        @DisplayName("NUMBER column with non-numeric cell throws EvaluationException")
        void badNumberThrows(@TempDir Path dir) throws IOException {
            writeFile(dir, "data.csv", "val\nnot-a-number\n");

            SemanticModel model = analyze(
                    "source T from csv(\"data.csv\") { schema: { val: NUMBER } };\n" +
                    "query T;",
                    dir);
            var connector = new CsvDataSourceConnector(model, dir);
            var schema = new Schema(List.of(new ColumnDefinition("val", ScalarType.NUMBER)));

            assertThatThrownBy(() -> connector.open("t", schema).toList())
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("not-a-number")
                    .hasMessageContaining("NUMBER");
        }

        @Test
        @DisplayName("BOOLEAN column reads true/false and their 1/0 spellings")
        void booleanParsing(@TempDir Path dir) throws IOException {
            writeFile(dir, "data.csv", "flag\ntrue\nFALSE\n1\n0\n");

            var rows = exec(
                    "source T from csv(\"data.csv\") { schema: { flag: BOOLEAN } };\n" +
                    "query T;",
                    dir);

            assertThat(rows).hasSize(4);
            assertThat(rows.get(0).get("flag")).isInstanceOf(BooleanValue.class);
            assertThat(rows.get(0)).hasValue("flag", "true");
            assertThat(rows.get(1)).hasValue("flag", "false");
            assertThat(rows.get(2)).hasValue("flag", "true");
            assertThat(rows.get(3)).hasValue("flag", "false");
        }

        @Test
        @DisplayName("BOOLEAN column with a cell that is neither throws EvaluationException")
        void badBooleanThrows(@TempDir Path dir) throws IOException {
            writeFile(dir, "data.csv", "flag\nyes\n");

            SemanticModel model = analyze(
                    "source T from csv(\"data.csv\") { schema: { flag: BOOLEAN } };\n" +
                    "query T;",
                    dir);
            var connector = new CsvDataSourceConnector(model, dir);
            var schema = new Schema(List.of(new ColumnDefinition("flag", ScalarType.BOOLEAN)));

            assertThatThrownBy(() -> connector.open("t", schema).toList())
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("yes")
                    .hasMessageContaining("BOOLEAN");
        }

        @Test
        @DisplayName("STRING column preserves whitespace verbatim")
        void stringPreservesWhitespace(@TempDir Path dir) throws IOException {
            writeFile(dir, "data.csv", "name\n  hello  \n");

            var rows = exec(
                    "source T from csv(\"data.csv\") { schema: { name: STRING } };\n" +
                    "query T;",
                    dir);

            assertThat(rows.get(0).get("name")).isInstanceOf(StringValue.class);
            assertThat(rows.get(0)).hasValue("name", "  hello  ");
        }

        @Test
        @DisplayName("ANY column infers NUMBER for numeric cells, STRING otherwise")
        void anyInference(@TempDir Path dir) throws IOException {
            writeFile(dir, "data.csv", "val\n42\nhello\n");

            var rows = exec(
                    "source T from csv(\"data.csv\") { schema: { val: ANY } };\n" +
                    "query T;",
                    dir);

            assertThat(rows.get(0).get("val")).isInstanceOf(NumberValue.class);
            assertThat(rows.get(1).get("val")).isInstanceOf(StringValue.class);
        }
    }

    // ── quoted fields ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Quoted CSV fields")
    class QuotedFields {

        @Test
        @DisplayName("quoted field with embedded comma is read as a single value")
        void quotedComma(@TempDir Path dir) throws IOException {
            writeFile(dir, "data.csv",
                    "city,country\n" +
                    "\"London, UK\",England\n");

            var rows = exec(
                    "source T from csv(\"data.csv\") { schema: { city: STRING, country: STRING } };\n" +
                    "query T;",
                    dir);

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("city", "London, UK");
        }

        @Test
        @DisplayName("doubled quote inside quoted field produces literal double-quote")
        void escapedQuote(@TempDir Path dir) throws IOException {
            writeFile(dir, "data.csv",
                    "note\n" +
                    "\"say \"\"hello\"\"\"\n");

            var rows = exec(
                    "source T from csv(\"data.csv\") { schema: { note: STRING } };\n" +
                    "query T;",
                    dir);

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("note", "say \"hello\"");
        }
    }

    // ── blank lines and BOM ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Blank lines and BOM")
    class BlankLinesAndBom {

        @Test
        @DisplayName("blank data lines are silently skipped")
        void blankLinesSkipped(@TempDir Path dir) throws IOException {
            writeFile(dir, "data.csv",
                    "id,name\n" +
                    "1,Alice\n" +
                    "\n" +
                    "   \n" +
                    "2,Bob\n");

            var rows = exec(
                    "source T from csv(\"data.csv\") { schema: { id: NUMBER, name: STRING } };\n" +
                    "query T;",
                    dir);

            assertThat(rows).hasSize(2);
        }

        @Test
        @DisplayName("UTF-8 BOM on the first line is stripped before header parsing")
        void bomStripped(@TempDir Path dir) throws IOException {
            // Write file with UTF-8 BOM (EF BB BF) before the header
            byte[] bom  = { (byte)0xEF, (byte)0xBB, (byte)0xBF };
            byte[] rest = "id,name\n1,Alice\n".getBytes(StandardCharsets.UTF_8);
            byte[] all  = new byte[bom.length + rest.length];
            System.arraycopy(bom,  0, all, 0,           bom.length);
            System.arraycopy(rest, 0, all, bom.length,  rest.length);
            Files.write(dir.resolve("data.csv"), all);

            var rows = exec(
                    "source T from csv(\"data.csv\") { schema: { id: NUMBER, name: STRING } };\n" +
                    "query T;",
                    dir);

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("id", "1");
        }

        @Test
        @DisplayName("empty CSV file (header-mode) returns no rows")
        void emptyFileNoRows(@TempDir Path dir) throws IOException {
            writeFile(dir, "data.csv", "");

            var rows = exec(
                    "source T from csv(\"data.csv\") { schema: { id: NUMBER } };\n" +
                    "query T;",
                    dir);

            assertThat(rows).isEmpty();
        }
    }

    // ── error cases ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("missing CSV file throws UncheckedIOException")
        void missingFile(@TempDir Path dir) {
            // Intentionally do NOT create the file
            SemanticModel model = analyze(
                    "source T from csv(\"missing.csv\") { schema: { id: NUMBER } };\n" +
                    "query T;",
                    dir);
            var connector = new CsvDataSourceConnector(model, dir);
            var schema = new Schema(List.of(new ColumnDefinition("id", ScalarType.NUMBER)));

            assertThatThrownBy(() -> connector.open("t", schema).toList())
                    .isInstanceOf(UncheckedIOException.class)
                    .hasMessageContaining("missing.csv");
        }

        /**
         * A row that runs out of fields used to be padded, and an absent field coerces to
         * NULL — so a malformed file produced NULL <em>data</em> and said nothing. Nothing
         * asserted the behaviour either way, so it was not clear it had been chosen.
         */
        @Test
        @DisplayName("a row with fewer fields than the schema is refused, naming the line")
        void shortRowIsRefused(@TempDir Path dir) throws IOException {
            writeFile(dir, "t.csv", "id,name,tier\n1,Ada,gold\n2,Grace\n");

            assertThatThrownBy(() -> exec(
                    "source T from csv(\"t.csv\") "
                  + "{ schema: { id: NUMBER, name: STRING, tier: STRING } };\n"
                  + "query T;", dir))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("line 3")
                    .hasMessageContaining("tier")
                    .hasMessageContaining("2 field(s)");
        }

        @Test
        @DisplayName("an empty field is still a NULL, which is what a short row is not")
        void emptyFieldIsStillNull(@TempDir Path dir) throws IOException {
            writeFile(dir, "t.csv", "id,name,tier\n1,Ada,gold\n2,Grace,\n");

            List<Row> rows = exec(
                    "source T from csv(\"t.csv\") "
                  + "{ schema: { id: NUMBER, name: STRING, tier: STRING } };\n"
                  + "query T;", dir);

            assertThat(rows).hasSize(2);
            assertThat(rows.get(1).get("tier").isNull())
                    .as("the field is present and empty, so it is a NULL")
                    .isTrue();
        }

        @Test
        @DisplayName("a row with more fields than the schema keeps reading")
        void extraFieldsAreIgnored(@TempDir Path dir) throws IOException {
            writeFile(dir, "t.csv", "id,name\n1,Ada,spare\n");

            assertThat(exec("source T from csv(\"t.csv\") "
                          + "{ schema: { id: NUMBER, name: STRING } };\nquery T;", dir))
                    .as("a schema may name fewer columns than the file carries")
                    .hasSize(1);
        }

        @Test
        @DisplayName("unknown relation throws EvaluationException")
        void unknownRelation(@TempDir Path dir) {
            SemanticModel model = analyze(
                    "source T from csv(\"x.csv\") { schema: { id: NUMBER } };\n" +
                    "query T;",
                    dir);
            var connector = new CsvDataSourceConnector(model, dir);
            var schema    = new Schema(List.of(new ColumnDefinition("id", ScalarType.NUMBER)));

            assertThatThrownBy(() -> connector.open("no_such_relation", schema).toList())
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("no_such_relation");
        }

        @Test
        @DisplayName("opening a non-CSV source throws EvaluationException")
        void nonCsvSource(@TempDir Path dir) {
            // Manually build a model that has a SourceDeclaration with CsvFileSourceConfig
            // but we swap it for something unexpected by using a database source instead.
            // Easiest: build connector for a DB-source script and try to open it.
            SemanticModel model = analyze(
                    "source T from database {\n" +
                    "    url: \"jdbc:h2:mem:test\",\n" +
                    "    table: \"T\",\n" +
                    "    schema: { id: NUMBER }\n" +
                    "};\n" +
                    "query T;",
                    dir);
            var connector = new CsvDataSourceConnector(model, dir);
            var schema = new Schema(List.of(new ColumnDefinition("id", ScalarType.NUMBER)));

            assertThatThrownBy(() -> connector.open("t", schema).toList())
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("not a CSV source");
        }
    }

    // ── end-to-end query ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("End-to-end query execution")
    class EndToEnd {

        @Test
        @DisplayName("selection on CSV source returns filtered rows")
        void selectionOnCsvSource(@TempDir Path dir) throws IOException {
            writeFile(dir, "orders.csv",
                    "order_id,amount,status\n" +
                    "1,100.00,completed\n" +
                    "2,50.00,pending\n" +
                    "3,200.00,completed\n");

            var rows = exec(
                    "source Orders from csv(\"orders.csv\") {\n" +
                    "    schema: { order_id: NUMBER, amount: NUMBER, status: STRING }\n" +
                    "};\n" +
                    "Completed := { σ status = \"completed\" (Orders) };\n" +
                    "query Completed;",
                    dir);

            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(r -> r.get("order_id").asDisplayString())
                    .containsExactlyInAnyOrder("1", "3");
        }

        @Test
        @DisplayName("projection on CSV source returns only selected columns")
        void projectionOnCsvSource(@TempDir Path dir) throws IOException {
            writeFile(dir, "users.csv",
                    "id,name,email\n" +
                    "1,Alice,alice@example.com\n" +
                    "2,Bob,bob@example.com\n");

            var rows = exec(
                    "source Users from csv(\"users.csv\") {\n" +
                    "    schema: { id: NUMBER, name: STRING, email: STRING }\n" +
                    "};\n" +
                    "Names := { π id, name (Users) };\n" +
                    "query Names;",
                    dir);

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).width()).isEqualTo(2);
            assertThat(rows).extracting(r -> r.get("name").asDisplayString())
                    .containsExactlyInAnyOrder("Alice", "Bob");
        }

        @Test
        @DisplayName("join between CSV source and inline relation works")
        void joinCsvWithInline(@TempDir Path dir) throws IOException {
            writeFile(dir, "orders.csv",
                    "order_id,customer_id,amount\n" +
                    "101,1,50.00\n" +
                    "102,2,75.00\n" +
                    "103,1,30.00\n");

            var rows = exec(
                    "source Orders from csv(\"orders.csv\") {\n" +
                    "    schema: { order_id: NUMBER, customer_id: NUMBER, amount: NUMBER }\n" +
                    "};\n" +
                    "Customers := [| customer_id | name  |\n" +
                    "               | 1           | Alice |\n" +
                    "               | 2           | Bob   |];\n" +
                    "Result := { Orders ⋈ Customers };\n" +
                    "query Result;",
                    dir);

            assertThat(rows).hasSize(3);
            assertThat(rows).extracting(r -> r.get("name").asDisplayString())
                    .containsExactlyInAnyOrder("Alice", "Bob", "Alice");
        }
    }

    // ── Temporal columns (ADR-0013 slice 5) ───────────────────────────────────

    @Nested
    @DisplayName("Temporal-typed columns parse against ISO-8601")
    class TemporalColumns {

        @Test
        @DisplayName("DATE / TIME / TIMESTAMP / DURATION cells parse to typed values")
        void parsesTemporalColumns(@TempDir Path dir) throws IOException {
            writeFile(dir, "events.csv",
                    "id,day,clock,at,held\n"
                    + "1,2026-06-15,13:40:00,2026-06-15T13:40:00Z,PT30M\n");

            var rows = exec(
                    "source Events from csv(\"events.csv\") {\n"
                    + "    schema: { id: NUMBER, day: DATE, clock: TIME, at: TIMESTAMP, held: DURATION }\n"
                    + "};\n"
                    + "query Events;",
                    dir);

            assertThat(rows).hasSize(1);
            Row r = rows.get(0);
            assertThat(r.get("day")).isEqualTo(new DateValue(LocalDate.parse("2026-06-15")));
            assertThat(r.get("clock")).isEqualTo(new TimeValue(LocalTime.parse("13:40:00")));
            assertThat(r.get("at")).isEqualTo(new TimestampValue(Instant.parse("2026-06-15T13:40:00Z")));
            assertThat(r.get("held")).isEqualTo(new DurationValue(Duration.parse("PT30M")));
        }

        @Test
        @DisplayName("a TIMESTAMP cell carrying an offset is normalised to UTC")
        void timestampOffsetNormalisedToUtc(@TempDir Path dir) throws IOException {
            writeFile(dir, "t.csv", "at\n2026-06-15T14:40:00+01:00\n");
            var rows = exec(
                    "source T from csv(\"t.csv\") { schema: { at: TIMESTAMP } };\nquery T;", dir);
            assertThat(rows.get(0).get("at"))
                    .isEqualTo(new TimestampValue(Instant.parse("2026-06-15T13:40:00Z")));
        }

        @Test
        @DisplayName("an empty temporal cell is NULL")
        void emptyTemporalCellIsNull(@TempDir Path dir) throws IOException {
            writeFile(dir, "t.csv", "id,at\n1,\n");
            var rows = exec(
                    "source T from csv(\"t.csv\") { schema: { id: NUMBER, at: TIMESTAMP } };\nquery T;",
                    dir);
            assertThat(rows.get(0).get("at")).isEqualTo(NullValue.INSTANCE);
        }

        @Test
        @DisplayName("a malformed temporal cell is an error")
        void malformedTemporalCellThrows(@TempDir Path dir) throws IOException {
            writeFile(dir, "t.csv", "at\nnot-a-timestamp\n");
            assertThatThrownBy(() -> exec(
                    "source T from csv(\"t.csv\") { schema: { at: TIMESTAMP } };\nquery T;", dir))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("cannot parse")
                    .hasMessageContaining("TIMESTAMP");
        }
    }
}

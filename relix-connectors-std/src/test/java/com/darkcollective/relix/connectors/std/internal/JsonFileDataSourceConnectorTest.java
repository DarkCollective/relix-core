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
package com.darkcollective.relix.connectors.std.internal;

import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.internal.QueryExecutor;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JsonFileDataSourceConnector — open JSON file source")
final class JsonFileDataSourceConnectorTest extends ProcessorTestSupport {

    // ── helpers ───────────────────────────────────────────────────────────────

    private static List<Row> exec(String script, Path baseDir) {
        SemanticModel model = model(script);
        var connector = new JsonFileDataSourceConnector(model, baseDir);
        var executor  = new QueryExecutor();
        return executor.execute(model, connector).stream()
                .flatMap(qr -> qr.rows().stream())
                .toList();
    }

    private static void writeFile(Path dir, String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    // ── record extraction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("record extraction")
    class RecordExtraction {

        @Test
        @DisplayName("top-level array yields one open document row per element")
        void topLevelArray(@TempDir Path dir) throws IOException {
            writeFile(dir, "docs.json", """
                    [ {"id": 1, "name": "Alice"},
                      {"id": 2, "name": "Bob"} ]
                    """);

            var rows = exec("source Docs from json(\"docs.json\");\nquery Docs;", dir);

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).schema().isOpen()).isTrue();
            assertThat(rows.get(0)).hasValue("id", "1")
                    .hasValue("name", "Alice");
            assertThat(rows.get(1)).hasValue("name", "Bob");
        }

        @Test
        @DisplayName("a nested 'records' path locates the array of records")
        void nestedRecordsPath(@TempDir Path dir) throws IOException {
            writeFile(dir, "wrapped.json", """
                    {"data": {"items": [ {"sku": "A"}, {"sku": "B"} ]}, "page": 1}
                    """);

            var rows = exec(
                    "source Items from json(\"wrapped.json\") { records: \"data.items\" };\n"
                    + "query Items;",
                    dir);

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)).hasValue("sku", "A");
            assertThat(rows.get(1)).hasValue("sku", "B");
        }

        @Test
        @DisplayName("a top-level object is read as a single record")
        void topLevelObject(@TempDir Path dir) throws IOException {
            writeFile(dir, "one.json", "{\"id\": 7, \"name\": \"solo\"}");

            var rows = exec("source D from json(\"one.json\");\nquery D;", dir);

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("id", "7");
        }
    }

    // ── unnest over native arrays (the marquee case) ──────────────────────────

    @Nested
    @DisplayName("unnest over a native JSON array")
    class Unnest {

        @Test
        @DisplayName("μ explodes an array field into one row per element")
        void unnestArrayField(@TempDir Path dir) throws IOException {
            writeFile(dir, "orders.json", """
                    [ {"order": 1, "items": [10, 20, 30]},
                      {"order": 2, "items": [40]} ]
                    """);

            var rows = exec(
                    "source Orders from json(\"orders.json\");\n"
                    + "query { μ items (Orders) };",
                    dir);

            // 3 items from order 1 + 1 item from order 2 = 4 rows.
            assertThat(rows).hasSize(4);
            assertThat(rows).extracting(r -> r.get("items").asDisplayString())
                    .containsExactly("10", "20", "30", "40");
            assertThat(rows).extracting(r -> r.get("order").asDisplayString())
                    .containsExactly("1", "1", "1", "2");
        }

        @Test
        @DisplayName("inner μ drops rows whose array is empty")
        void innerUnnestDropsEmpty(@TempDir Path dir) throws IOException {
            writeFile(dir, "orders.json", """
                    [ {"order": 1, "items": [10]},
                      {"order": 2, "items": []} ]
                    """);

            var rows = exec(
                    "source Orders from json(\"orders.json\");\n"
                    + "query { μ items (Orders) };",
                    dir);

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("order", "1");
        }
    }

    // ── errors ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("errors")
    class Errors {

        @Test
        @DisplayName("records path that is not an array is rejected")
        void recordsPathNotArray(@TempDir Path dir) throws IOException {
            writeFile(dir, "bad.json", "{\"data\": {\"items\": 42}}");

            assertThatThrownBy(() -> exec(
                    "source Items from json(\"bad.json\") { records: \"data.items\" };\n"
                    + "query Items;", dir))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("did not resolve to an array");
        }

        @Test
        @DisplayName("a non-object record element is rejected")
        void nonObjectRecord(@TempDir Path dir) throws IOException {
            writeFile(dir, "scalars.json", "[1, 2, 3]");

            assertThatThrownBy(() -> exec(
                    "source D from json(\"scalars.json\");\nquery D;", dir))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("not an object");
        }

        @Test
        @DisplayName("malformed JSON surfaces as an EvaluationException")
        void malformedJson(@TempDir Path dir) throws IOException {
            writeFile(dir, "broken.json", "{\"a\": }");

            assertThatThrownBy(() -> exec(
                    "source D from json(\"broken.json\");\nquery D;", dir))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("Malformed JSON");
        }

        @Test
        @DisplayName("opening an unknown relation fails")
        void unknownRelation(@TempDir Path dir) throws IOException {
            writeFile(dir, "docs.json", "[]");
            SemanticModel model = model("source Docs from json(\"docs.json\");\nquery Docs;");
            var connector = new JsonFileDataSourceConnector(model, dir);

            assertThatThrownBy(() -> connector.open("Nope", Schema.open()))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("No source declaration");
        }
    }
}

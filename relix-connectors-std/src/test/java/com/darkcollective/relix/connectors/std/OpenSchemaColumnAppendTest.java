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

import com.darkcollective.relix.processor.QueryExecutor;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;

/**
 * The operators that append a column, run over a schema-on-read relation.
 *
 * <p>Every one of them was unusable over a JSON, HTTP or MongoDB source (#649), for
 * two reasons that had to be fixed separately and are both checked here. Analysis
 * rejected the query — an open schema resolves any name, and the clash checks read
 * that as "the name is taken". Execution then failed anyway, because appending to a
 * fixed-width tuple cannot work when the schema's width is zero.
 *
 * <p>A JSON file is the cheapest real open source, so these run end to end: the
 * unit halves ({@code Schema.declares}, {@code ExecSupport.appendColumn}) each have
 * their own tests, and neither would have caught the other's failure.
 */
@DisplayName("Operators that append a column work over a schema-on-read source")
final class OpenSchemaColumnAppendTest {

    private static final String CLICKS = """
            [ {"user_id": 1, "ts": "2026-06-01T10:00:00Z", "url": "/a"},
              {"user_id": 1, "ts": "2026-06-01T10:05:00Z", "url": "/b"},
              {"user_id": 1, "ts": "2026-06-01T12:00:00Z", "url": "/c"} ]
            """;

    private static List<Row> run(Path dir, String json, String query) throws IOException {
        Files.writeString(dir.resolve("clicks.json"), json, StandardCharsets.UTF_8);
        String script = "source Clicks from json(\"clicks.json\");\n\nquery { " + query + " };\n";
        SemanticModel model = model(script);
        return new QueryExecutor()
                .execute(model, new JsonFileDataSourceConnector(model, dir)).stream()
                .flatMap(qr -> qr.rows().stream())
                .toList();
    }

    @Test
    @DisplayName("SESSIONIZE — the gap opens a second session")
    void sessionize(@TempDir Path dir) throws IOException {
        List<Row> rows = run(dir, CLICKS,
                "SESSIONIZE ts GAP DURATION 'PT30M' PER user_id AS visit (Clicks)");
        assertThat(rows).hasSize(3);
        assertThat(rows.stream().map(r -> r.get("visit").asDisplayString()).toList())
                .containsExactly("1", "1", "2");
    }

    @Test
    @DisplayName("WINDOW ranking numbers the rows")
    void window(@TempDir Path dir) throws IOException {
        List<Row> rows = run(dir, CLICKS,
                "WINDOW ROW_NUMBER() SORT ts ASC PER user_id AS rn (Clicks)");
        assertThat(rows.stream().map(r -> r.get("rn").asDisplayString()).toList())
                .containsExactly("1", "2", "3");
    }

    @Test
    @DisplayName("ROLLING accumulates over the open rows")
    void rolling(@TempDir Path dir) throws IOException {
        List<Row> rows = run(dir, CLICKS,
                "ROLLING COUNT(user_id) OVER ALL ROWS SORT ts ASC AS seen (Clicks)");
        assertThat(rows.stream().map(r -> r.get("seen").asDisplayString()).toList())
                .containsExactly("1", "2", "3");
    }

    @Test
    @DisplayName("μ … WITH ORDINALITY — the one that already worked, kept working")
    void unnestWithOrdinality(@TempDir Path dir) throws IOException {
        List<Row> rows = run(dir,
                "[ {\"id\": 1, \"tags\": [\"a\", \"b\"]} ]",
                "μ tags WITH ORDINALITY pos (Clicks)");
        assertThat(rows.stream().map(r -> r.get("pos").asDisplayString()).toList())
                .containsExactly("1", "2");
    }

    @Test
    @DisplayName("TRACE finds the cheap route rather than the direct one")
    void trace(@TempDir Path dir) throws IOException {
        List<Row> rows = run(dir,
                "[ {\"src\":\"a\",\"dst\":\"b\",\"cost\":1},"
                        + " {\"src\":\"b\",\"dst\":\"c\",\"cost\":2},"
                        + " {\"src\":\"a\",\"dst\":\"c\",\"cost\":9} ]",
                "TRACE src, dst VIA cost MINIMIZE AS route (Clicks)");
        assertThat(rows).hasSize(3);
        Row aToC = rows.stream()
                .filter(r -> r.get("src").asDisplayString().equals("a")
                        && r.get("dst").asDisplayString().equals("c"))
                .findFirst().orElseThrow();
        assertThat(aToC).hasValue("cost", "3");   // a→b→c, not the direct 9
    }

    @Test
    @DisplayName("TREE folds the adjacency into one root row")
    void tree(@TempDir Path dir) throws IOException {
        List<Row> rows = run(dir,
                "[ {\"id\":1,\"manager_id\":0,\"name\":\"Ada\"},"
                        + " {\"id\":2,\"manager_id\":1,\"name\":\"Bob\"} ]",
                "TREE id BY manager_id ORDER id ASC AS reports (Clicks)");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("reports").isNull()).isFalse();
    }

    @Test
    @DisplayName("WHY reifies lineage over an open source")
    void why(@TempDir Path dir) throws IOException {
        List<Row> rows = run(dir, CLICKS, "WHY (Clicks)");
        assertThat(rows).hasSize(3);
        assertThat(rows.getFirst().get("provenance").isNull()).isFalse();
    }

    @Test
    @DisplayName("a document field of the same name is overwritten, not duplicated")
    void appendedColumnWins(@TempDir Path dir) throws IOException {
        // The operator's output is defined to carry its column; an incoming field
        // cannot decide whether it does. There is no rename escape hatch here, which
        // is why the rule has to be predictable rather than an error nobody can avoid.
        List<Row> rows = run(dir,
                "[ {\"user_id\": 1, \"ts\": \"2026-06-01T10:00:00Z\", \"visit\": \"from the document\"},"
                        + " {\"user_id\": 1, \"ts\": \"2026-06-01T12:00:00Z\", \"visit\": \"also\"} ]",
                "SESSIONIZE ts GAP DURATION 'PT30M' PER user_id AS visit (Clicks)");
        assertThat(rows.stream().map(r -> r.get("visit").asDisplayString()).toList())
                .containsExactly("1", "2");
        assertThat(rows.getFirst().columnNames()).containsOnlyOnce("visit");
    }
}

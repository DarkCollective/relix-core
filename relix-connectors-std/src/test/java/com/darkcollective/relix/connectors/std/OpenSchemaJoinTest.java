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

import com.darkcollective.relix.connectors.std.internal.JsonFileDataSourceConnector;
import com.darkcollective.relix.processor.internal.QueryExecutor;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.semantic.SemanticFixtures;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.semantic.internal.SemanticResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Joining schema-on-read relations, end to end.
 *
 * <p>A qualified reference in a join condition resolved by <em>source-relation
 * provenance</em>, which a document does not carry, so {@code L ⨝ L.id = R.id R}
 * over two JSON sources threw "Unknown column 'L.id' in row" — after the query had
 * started (#657). Behind it sat a second failure of the same kind as #649: the
 * output row was built as a fixed-width tuple against a heading of width zero.
 *
 * <p>Both halves are exercised here, because fixing either alone leaves the query
 * broken.
 */
@DisplayName("Joins over schema-on-read relations")
final class OpenSchemaJoinTest {

    private static final String LEFT  = """
            [ {"id": 1, "name": "Ada"}, {"id": 2, "name": "Bob"} ]
            """;
    private static final String RIGHT = """
            [ {"id": 1, "city": "London"}, {"id": 3, "city": "Lisbon"} ]
            """;

    private static SemanticResult analyse(Path dir, String query) throws IOException {
        Files.writeString(dir.resolve("l.json"), LEFT, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("r.json"), RIGHT, StandardCharsets.UTF_8);
        return SemanticFixtures.analyze("""
                source L from json("l.json");
                source R from json("r.json");

                query { %s };
                """.formatted(query));
    }

    private static List<String> run(Path dir, String query) throws IOException {
        SemanticResult analysis = analyse(dir, query);
        assertThat(analysis.errors()).as("analysing: %s", query).isEmpty();
        SemanticModel model = analysis.model().orElseThrow();
        return new QueryExecutor()
                .execute(model, new JsonFileDataSourceConnector(model, dir)).stream()
                .flatMap(qr -> qr.rows().stream())
                .map(OpenSchemaJoinTest::render)
                .toList();
    }

    private static String render(Row row) {
        return IntStream.range(0, row.width())
                .mapToObj(i -> row.columnNames().get(i) + "=" + describe(row.get(i)))
                .reduce((a, b) -> a + "," + b).orElse("");
    }

    private static String describe(com.darkcollective.relix.value.Value v) {
        return v.isNull() ? "NULL" : v.asDisplayString();
    }

    @Test
    @DisplayName("a theta join resolves its qualified references and joins the matching rows")
    void thetaJoin(@TempDir Path dir) throws IOException {
        // The collision is renamed exactly as a declared join heading renames it, so
        // the same query reads the same whether the relations declare their columns.
        assertThat(run(dir, "L ⨝ L.id = R.id R"))
                .containsExactly("id=1,name=Ada,id_r=1,city=London");
    }

    @Test
    @DisplayName("a left outer join keeps the unmatched left row, carrying its own fields")
    void leftOuterJoin(@TempDir Path dir) throws IOException {
        // Nothing to pad *with*, and nothing needing padding: a field a document does
        // not carry already reads as NULL.
        assertThat(run(dir, "L ⟕ L.id = R.id R"))
                .containsExactly("id=1,name=Ada,id_r=1,city=London", "id=2,name=Bob");
    }

    @Test
    @DisplayName("semi and anti joins filter the left side")
    void semiAndAnti(@TempDir Path dir) throws IOException {
        assertThat(run(dir, "L ⋉ L.id = R.id R")).containsExactly("id=1,name=Ada");
        assertThat(run(dir, "L ▷ L.id = R.id R")).containsExactly("id=2,name=Bob");
    }

    @Test
    @DisplayName("a product pairs every row with every row")
    void product(@TempDir Path dir) throws IOException {
        assertThat(run(dir, "L × R")).hasSize(4);
    }

    @Test
    @DisplayName("a condition on a field neither document carries is NULL, not an error")
    void absentFieldIsNull(@TempDir Path dir) throws IOException {
        // Schema-on-read: absent is NULL, the same answer σ gives over one relation.
        // Only a declared heading can promise a column exists, and there the validator
        // has already checked it.
        assertThat(run(dir, "L ⨝ L.id = R.nope R")).isEmpty();
    }

    @Test
    @DisplayName("right and full outer joins are rejected — an unmatched right row cannot be named")
    void rightAndFullOuterAreRejected(@TempDir Path dir) throws IOException {
        for (String query : List.of("L ⟖ L.id = R.id R", "L ⟗ L.id = R.id R")) {
            assertThat(analyse(dir, query).errors())
                    .as("analysing: %s", query)
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.type(
                            com.darkcollective.relix.semantic.SemanticError.class))
                    .extracting(com.darkcollective.relix.semantic.SemanticError::message,
                            org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("open (schema-on-read) input is not supported");
        }
    }
}

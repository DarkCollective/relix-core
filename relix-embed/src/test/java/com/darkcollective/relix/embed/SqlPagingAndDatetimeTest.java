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
package com.darkcollective.relix.embed;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** SQL's paging clauses (#259) and datetime keywords (#258), evaluated. */
@DisplayName("SQL paging and datetime keywords, evaluated")
final class SqlPagingAndDatetimeTest {

    private static final String URL = "jdbc:h2:mem:embed_sql_paging;DB_CLOSE_DELAY=-1";

    private static final String NUMBERS = """
            N := [| k |
            | 1 |
            | 2 |
            | 3 |
            | 4 |
            | 5 |];
            """;

    private static final String DATABASE = "connection db from jdbc { url: \"" + URL + "\" };\n"
            + "source Remote from db { table: \"numbers\", schema: { k: NUMBER } };\n";

    @BeforeAll
    static void seed() throws SQLException {
        try (Connection c = DriverManager.getConnection(URL)) {
            var st = c.createStatement();
            st.execute("DROP TABLE IF EXISTS numbers");
            st.execute("CREATE TABLE numbers (k INT)");
            st.execute("INSERT INTO numbers VALUES (1), (2), (3), (4), (5)");
        }
    }

    private static List<String> rows(Relix relix, String query) {
        return relix.relation(query).toList().stream()
                .map(t -> t.schema().columns().stream().map(c -> t.get(c.name()).asDisplayString())
                        .collect(Collectors.joining(" ")))
                .toList();
    }

    @Test
    @DisplayName("every paging spelling returns the page LIMIT m, n does, in the engine and pushed down")
    void pagingSpellingsAgree() {
        try (Relix relix = Relix.open()) {
            relix.define(NUMBERS + DATABASE);
            for (String input : List.of("N", "Remote")) {
                String sorted = "(SORT k (" + input + "))";
                List<String> page = rows(relix, "LIMIT 1, 2 " + sorted);
                assertThat(page).as(input).containsExactly("2", "3");
                assertThat(rows(relix, "LIMIT 2 OFFSET 1 " + sorted)).as(input).isEqualTo(page);
                assertThat(rows(relix, "OFFSET 1 ROWS FETCH NEXT 2 ROWS ONLY " + sorted)).as(input).isEqualTo(page);
                assertThat(rows(relix, "FETCH FIRST 2 ROWS ONLY " + sorted)).as(input).containsExactly("1", "2");
                assertThat(rows(relix, "OFFSET 3 ROWS " + sorted)).as(input).containsExactly("4", "5");
            }
        }
    }

    @Test
    @DisplayName("an OFFSET with no FETCH is pushed to the database as a page with no end")
    void offsetAlonePushesDown() {
        try (Relix relix = Relix.open()) {
            relix.define(DATABASE);
            EmbedAssertions.assertThat(relix.relation("OFFSET 3 ROWS (SORT k (Remote))"))
                    .explains().contains("PushedScan").contains("OFFSET 3");
        }
    }

    @Test
    @DisplayName("the datetime keywords read the session's clock, bare or called")
    void datetimeKeywords() {
        Instant pinned = Instant.parse("2026-03-01T12:34:56Z");
        try (Relix relix = Relix.builder().clock(Clock.fixed(pinned, ZoneOffset.UTC)).build()) {
            assertThat(rows(relix, "π CURRENT_TIMESTAMP → a, CURRENT_TIMESTAMP() → b, LOCALTIMESTAMP → c,"
                    + " NOW() → d (UNIT)"))
                    .containsExactly("2026-03-01T12:34:56Z 2026-03-01T12:34:56Z 2026-03-01T12:34:56Z 2026-03-01T12:34:56Z");
            assertThat(rows(relix, "π CURRENT_DATE → d, CURRENT_TIME → t (UNIT)"))
                    .containsExactly("2026-03-01 12:34:56");
            relix.define("""
                    Due := [| id | due        |
                    | 1  | 2026-03-01 |
                    | 2  | 2026-03-02 |];
                    """);
            assertThat(rows(relix, "π id (σ CAST(due AS DATE) = CURRENT_DATE (Due))")).containsExactly("1");
        }
    }

    @Test
    @DisplayName("a column named current_date is still readable, qualified or delimited")
    void columnOfTheKeywordsName() {
        try (Relix relix = Relix.open()) {
            relix.define("""
                    Log := [| id | current_date |
                    | 1  | yesterday    |];
                    """);
            assertThat(rows(relix, "π `current_date` → v (Log)")).as("delimited").containsExactly("yesterday");
            assertThat(rows(relix, "π Log.current_date → v (Log)")).as("qualified").containsExactly("yesterday");
        }
    }
}

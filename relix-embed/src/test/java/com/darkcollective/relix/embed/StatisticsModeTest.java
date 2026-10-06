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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;

import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;
import static com.darkcollective.relix.embed.EmbedAssertions.assertThatThrownBy;

/**
 * A database table's statistics are the database's estimates unless the session asks
 * for counts, and what {@code relix.columns} reports follows the choice.
 */
@DisplayName("A session reads a table's statistics as estimates, or counts them on request")
final class StatisticsModeTest {

    private static final String SOURCE = """
            connection db from database { url: "%s" };
            source People from db { table: "PEOPLE", schema: { ID: NUMBER, REGION: STRING } };
            """;

    private static final String DISTINCT_REGIONS =
            "π distinct_count (σ relation = \"People\" ∧ column = \"REGION\" (relix.columns))";

    private static String people(String name) throws SQLException {
        String url = "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url)) {
            var st = c.createStatement();
            st.execute("CREATE TABLE people (id INT, region VARCHAR(10))");
            st.execute("INSERT INTO people VALUES (1,'E'),(2,'W'),(3,'E'),(4,NULL)");
        }
        return url;
    }

    @Test
    @DisplayName("by default a column's distinct count is what the database estimates, here nothing")
    void estimates() throws SQLException {
        try (Relix relix = Relix.open()) {
            relix.define(SOURCE.formatted(people("mode_estimates")));
            assertThat(relix.relation(DISTINCT_REGIONS)).rows().hasRow("NULL");
        }
    }

    @Test
    @DisplayName("with exact statistics it is counted")
    void exact() throws SQLException {
        try (Relix relix = Relix.builder().exactStatistics(Duration.ofSeconds(30)).build()) {
            relix.define(SOURCE.formatted(people("mode_exact")));
            assertThat(relix.relation(DISTINCT_REGIONS)).rows().hasRow("2");
        }
    }

    @Test
    @DisplayName("the counting timeout must be positive")
    void positiveTimeout() {
        assertThatThrownBy(() -> Relix.builder().exactStatistics(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Relix.builder().exactStatistics(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

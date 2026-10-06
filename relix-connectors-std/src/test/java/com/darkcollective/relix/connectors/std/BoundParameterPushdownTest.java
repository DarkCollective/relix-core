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

import com.darkcollective.relix.embed.EmbedAssertions;
import com.darkcollective.relix.embed.Relation;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.embed.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

/**
 * Bound parameters sent to a real database as JDBC bind parameters.
 *
 * <p>H2 and DuckDB accept a {@code ?} almost anywhere; the question this answers is
 * whether the two servers that infer a placeholder's type from its context — Postgres
 * strictly, MySQL loosely — take each kind of value the engine can bind, in each position
 * the renderer puts one. Every case runs the bound query and the same query with the value
 * written as a literal, and the two must return the same rows: binding changes how the
 * value travels, never the answer.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("A bound parameter pushed to a database returns what its literal does")
final class BoundParameterPushdownTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36");

    /**
     * One query with a parameter, the value bound, and the same query written with that
     * value; {@code pushed} says whether the comparison is sent to the database at all.
     */
    private record Case(String query, String name, Object value, String literal, boolean pushed) {
        Case(String query, String name, Object value, String literal) {
            this(query, name, value, literal, true);
        }
    }

    private static final List<Case> CASES = List.of(
            new Case("σ id = $v (People)", "v", 2, "σ id = 2 (People)"),
            new Case("σ id > $v (People)", "v", new BigDecimal("1.5"), "σ id > 1.5 (People)"),
            new Case("σ name = $v (People)", "v", "o'brien", "σ name = \"o'brien\" (People)"),
            new Case("σ name = $v (People)", "v", "x' OR '1'='1", "σ name = \"x' OR '1'='1\" (People)"),
            new Case("σ name > $v (People)", "v", "B", "σ name > \"B\" (People)"),
            // Not sent: a LIKE whose pattern is not a literal does not fold on a backend
            // whose LIKE could disagree with the engine's, and the engine answers instead.
            new Case("σ name LIKE $v (People)", "v", "o%", "σ name LIKE \"o%\" (People)", false),
            new Case("σ active = $v (People)", "v", true, "σ active = true (People)"),
            new Case("σ born < $v (People)", "v", LocalDate.parse("1990-01-01"),
                    "σ born < DATE '1990-01-01' (People)"),
            new Case("σ joined ≥ $v (People)", "v", Instant.parse("2026-02-01T00:00:00Z"),
                    "σ joined ≥ TIMESTAMP '2026-02-01T00:00:00Z' (People)"));

    @TestFactory
    @DisplayName("on Postgres")
    Stream<DynamicTest> postgres() throws SQLException {
        return cases(POSTGRES, "");
    }

    @TestFactory
    @DisplayName("on MySQL")
    Stream<DynamicTest> mysql() throws SQLException {
        return cases(MYSQL, "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
    }

    private static Stream<DynamicTest> cases(JdbcDatabaseContainer<?> db, String urlSuffix)
            throws SQLException {
        String url = db.getJdbcUrl() + urlSuffix;
        try (Connection c = DriverManager.getConnection(url, db.getUsername(), db.getPassword());
             Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS people");
            st.execute("CREATE TABLE people (id INT, name VARCHAR(32), active BOOLEAN,"
                    + " born DATE, joined TIMESTAMP NULL)");
            st.execute("INSERT INTO people VALUES"
                    + " (1, 'ada', TRUE, DATE '1985-12-10', TIMESTAMP '2026-01-01 00:00:00'),"
                    + " (2, 'o''brien', FALSE, DATE '1992-06-01', TIMESTAMP '2026-02-01 00:00:00'),"
                    + " (3, 'Grace', TRUE, NULL, NULL)");
        }
        String preamble = "connection db from database { url: \"" + url + "\", user: \""
                + db.getUsername() + "\", password: \"" + db.getPassword() + "\" };\n"
                + "source People from db { table: \"people\", schema: { id: NUMBER,"
                + " name: STRING, active: BOOLEAN, born: DATE, joined: TIMESTAMP } };\n";
        return CASES.stream().map(test -> DynamicTest.dynamicTest(
                test.query() + " with " + test.value(), () -> {
                    try (Relix relix = Relix.open()) {
                        relix.define(preamble);
                        Relation bound = relix.relation(test.query()).bind(test.name(), test.value());
                        if (test.pushed()) {
                            EmbedAssertions.assertThat(bound).explains()
                                    .as("the parameter is sent as a placeholder")
                                    .startsWith("PushedScan").contains("?");
                        } else {
                            EmbedAssertions.assertThat(bound).explains().doesNotContain("?");
                        }
                        List<Tuple> expected = relix.relation(test.literal()).toList();
                        EmbedAssertions.assertThat(bound).tuples()
                                .containsExactlyInAnyOrderElementsOf(expected);
                    }
                }));
    }
}

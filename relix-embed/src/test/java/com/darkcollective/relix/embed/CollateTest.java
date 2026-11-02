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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code COLLATE} (#262): comparisons, membership, patterns, sorting and grouping under
 * {@code BINARY}, {@code NOCASE} and a locale, computed in the engine and over a
 * database — each held to H2's own answer for the {@code LOWER} form a database writes
 * it in. Only {@code BINARY} is pushed; under the others the engine compares the rows
 * the database returns.
 */
@DisplayName("COLLATE, evaluated")
final class CollateTest {

    private static final String T = """
            T := [| k | s     |
            | 1 | Ada   |
            | 2 | ada   |
            | 3 | Bob   |
            | 4 | NULL  |
            | 5 | ADA   |
            | 6 | bob   |
            | 7 | Émile |
            | 8 | Zed   |];
            """;

    private static final H2 DB = seeded();

    private static H2 seeded() {
        H2 h2 = new H2("jdbc:h2:mem:embed_collate;DB_CLOSE_DELAY=-1");
        try (Connection c = h2.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE t (k INT, s VARCHAR(10))");
            s.execute("INSERT INTO t VALUES (1, 'Ada'), (2, 'ada'), (3, 'Bob'), (4, NULL), (5, 'ADA'),"
                    + " (6, 'bob'), (7, 'Émile'), (8, 'Zed')");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return h2;
    }

    private static List<String> rows(Relix relix, String query) {
        return relix.relation(query).toList().stream()
                .map(t -> t.schema().columns().stream().map(c -> t.get(c.name()).asDisplayString())
                        .collect(Collectors.joining(" ")))
                .toList();
    }

    private static List<String> h2(String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = DB.getConnection(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            int width = r.getMetaData().getColumnCount();
            while (r.next()) {
                List<String> cells = new ArrayList<>();
                for (int i = 1; i <= width; i++) {
                    cells.add(r.getString(i) == null ? "NULL" : r.getString(i));
                }
                out.add(String.join(" ", cells));
            }
        }
        return out;
    }

    /** A condition under NOCASE, and the WHERE clause that means it in H2. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            s COLLATE NOCASE = 'ADA'                 | LOWER(s) = LOWER('ADA')
            'bob' = s COLLATE NOCASE                 | LOWER('bob') = LOWER(s)
            s COLLATE NOCASE <> 'ada'                | LOWER(s) <> LOWER('ada')
            s COLLATE NOCASE < 'b'                   | LOWER(s) < LOWER('b')
            s COLLATE NOCASE IN {'ADA', 'zed'}        | LOWER(s) IN (LOWER('ADA'), LOWER('zed'))
            s COLLATE NOCASE NOT IN {'bob'}          | LOWER(s) NOT IN (LOWER('bob'))
            s COLLATE NOCASE LIKE 'a%'               | LOWER(s) LIKE LOWER('a%')
            s COLLATE BINARY = 'Ada'                 | s = 'Ada'
            s COLLATE BINARY > 'a'                   | s > 'a'
            """)
    void conditionsAgreeWithTheDatabase(String condition, String where) throws SQLException {
        List<String> expected = h2("SELECT k FROM t WHERE " + where + " ORDER BY k");
        try (Relix engine = Relix.open()) {
            engine.define(T);
            assertThat(rows(engine, "τ k (π k (σ " + condition + " (T)))"))
                    .as("computed in the engine").containsExactlyElementsOf(expected);
        }
        try (Relix pushed = Relix.builder().jdbc("db", DB).build()) {
            String upper = condition.replace("s COLLATE", "S COLLATE").replace("= s", "= S");
            Relation query = pushed.relation("σ " + upper + " (db.t)");
            if (condition.contains("BINARY")) {
                EmbedAssertions.assertThat(query).explains().contains("WHERE");
            } else {
                EmbedAssertions.assertThat(query).explains().doesNotContain("WHERE");
            }
            assertThat(rows(pushed, "τ K (π K (σ " + upper + " (db.t)))"))
                    .as("over the database").containsExactlyElementsOf(expected);
        }
    }

    @Test
    @DisplayName("a NOCASE sort orders ignoring case, keeping tied rows in the order they came")
    void sortIgnoringCase() {
        try (Relix relix = Relix.open()) {
            relix.define(T);
            assertThat(rows(relix, "π k, s (τ s COLLATE NOCASE (T))"))
                    .containsExactly("1 Ada", "2 ada", "5 ADA", "3 Bob", "6 bob", "8 Zed", "7 Émile", "4 NULL");
            assertThat(rows(relix, "π k, s (τ s (T))"))
                    .as("without a collation, by code point")
                    .containsExactly("5 ADA", "1 Ada", "3 Bob", "8 Zed", "2 ada", "6 bob", "7 Émile", "4 NULL");
        }
    }

    @Test
    @DisplayName("a locale sorts as its dictionary does, É beside E")
    void sortByLocale() {
        try (Relix relix = Relix.open()) {
            relix.define(T);
            assertThat(rows(relix, "π s (τ s COLLATE 'en-US' (σ k > 5 ∨ k = 1 (T)))"))
                    .containsExactly("Ada", "bob", "Émile", "Zed");
            assertThat(rows(relix, "π s (σ s COLLATE 'en-US' < 'F' (T))"))
                    .containsExactlyInAnyOrder("Ada", "ada", "Bob", "ADA", "bob", "Émile");
        }
    }

    @Test
    @DisplayName("a NOCASE group gathers every case of a name, and shows the least by code point")
    void groupIgnoringCase() throws SQLException {
        List<String> expected = h2("SELECT MIN(s), COUNT(*) FROM t GROUP BY LOWER(s) ORDER BY LOWER(s)");
        try (Relix relix = Relix.open()) {
            relix.define(T);
            assertThat(rows(relix, "τ s COLLATE NOCASE (γ s COLLATE NOCASE, COUNT(*) → n (T))"))
                    .containsExactlyInAnyOrderElementsOf(expected)
                    .containsExactly("ADA 3", "Bob 2", "Zed 1", "Émile 1", "NULL 1");
        }
        try (Relix pushed = Relix.builder().jdbc("db", DB).build()) {
            assertThat(rows(pushed, "τ S COLLATE NOCASE (γ S COLLATE NOCASE, COUNT(*) → n (db.t))"))
                    .as("over a database, computed in the engine").containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    @Test
    @DisplayName("a collated join condition matches every case, row by row")
    void joinIgnoringCase() {
        try (Relix relix = Relix.open()) {
            relix.define(T + "U := [| name | team |\n| ada | red |\n| BOB | blue |];\n");
            assertThat(rows(relix, "τ k (π k, team (T ⨝ T.s COLLATE NOCASE = U.name U))"))
                    .containsExactly("1 red", "2 red", "3 blue", "5 red", "6 blue");
        }
    }

    @Test
    @DisplayName("a collated pattern, a BINARY one, and the same collation written on both sides")
    void patternsAndBothSides() {
        try (Relix relix = Relix.open()) {
            relix.define(T);
            assertThat(rows(relix, "τ k (π k (σ s LIKE 'a%' COLLATE NOCASE (T)))")).containsExactly("1", "2", "5");
            assertThat(rows(relix, "τ k (π k (σ s COLLATE BINARY LIKE 'a%' (T)))")).containsExactly("2");
            assertThat(rows(relix, "τ k (π k (σ s COLLATE NOCASE = 'BOB' COLLATE nocase (T)))"))
                    .containsExactly("3", "6");
        }
    }

    @Test
    @DisplayName("a value of no fixed type that is not a string compares as it would without the collation")
    void nonStringValues() {
        try (Relix relix = Relix.open()) {
            relix.define("D := [| k | doc |\n| 1 | {\"a\": \"Ada\"} |\n| 2 | {\"a\": 7} |];\n");
            assertThat(rows(relix, "π k (σ JSON_EXTRACT(doc, '$.a') COLLATE NOCASE = 'ada' (D))")).containsExactly("1");
            assertThat(rows(relix, "π k (σ 'ADA' = JSON_EXTRACT(doc, '$.a') COLLATE NOCASE (D))")).containsExactly("1");
            assertThat(rows(relix, "π k (σ JSON_EXTRACT(doc, '$.a') COLLATE NOCASE = 7 (D))")).containsExactly("2");
            assertThat(rows(relix, "π k (σ JSON_EXTRACT(doc, '$.a') COLLATE NOCASE ∈ {7, 'ADA'} (D))"))
                    .containsExactlyInAnyOrder("1", "2");
        }
    }

    @Test
    @DisplayName("a locale and NOCASE are computed in the engine, never pushed")
    void localeIsNotPushed() {
        try (Relix pushed = Relix.builder().jdbc("db", DB).build()) {
            EmbedAssertions.assertThat(pushed.relation("σ S COLLATE 'de' = 'ada' (db.t)")).explains()
                    .doesNotContain("WHERE");
            EmbedAssertions.assertThat(pushed.relation("τ S COLLATE NOCASE (db.t)")).explains()
                    .doesNotContain("ORDER BY");
        }
    }

    @Test
    @DisplayName("analysis refuses an unknown collation, a non-string, two collations, and COLLATE elsewhere")
    void refusals() {
        try (Relix relix = Relix.open()) {
            relix.define(T);
            assertThatThrownBy(() -> relix.relation("σ s COLLATE Latin1_General_CI_AS = 'a' (T)").toList())
                    .hasMessageContaining("unknown collation 'Latin1_General_CI_AS': COLLATE takes BINARY"
                            + " (code-point order), NOCASE (ignoring case), or a locale such as 'en-US'");
            assertThatThrownBy(() -> relix.relation("σ k COLLATE NOCASE = 1 (T)").toList())
                    .hasMessageContaining("COLLATE applies to a string, and k is a NUMBER");
            assertThatThrownBy(() -> relix.relation("σ s COLLATE NOCASE = s COLLATE BINARY (T)").toList())
                    .hasMessageContaining("the two sides are collated differently, NOCASE and BINARY");
            assertThatThrownBy(() -> relix.relation("π s COLLATE NOCASE → t (T)").toList())
                    .hasMessageContaining("COLLATE applies where strings are compared");
            assertThatThrownBy(() -> relix.relation("σ s COLLATE 'en-US' LIKE 'a%' (T)").toList())
                    .hasMessageContaining("LIKE matches under BINARY or NOCASE only");
        }
    }

    /** A minimal {@link DataSource} over {@code DriverManager}. */
    private record H2(String url) implements DataSource {
        @Override public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }
        @Override public Connection getConnection(String u, String p) throws SQLException {
            return getConnection();
        }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}

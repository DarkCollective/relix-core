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

/** SQL's CASE expression, evaluated in the engine and pushed to a database (#257). */
@DisplayName("CASE, evaluated")
final class CaseExpressionTest {

    private static final String T = """
            T := [| k | x    | kind |
            | 1 | 5    | a    |
            | 2 | 2    | b    |
            | 3 | NULL | NULL |];
            """;

    private static List<String> rows(Relix relix, String query) {
        return relix.relation(query).toList().stream()
                .map(t -> t.schema().columns().stream().map(c -> t.get(c.name()).asDisplayString())
                        .collect(Collectors.joining(" ")))
                .toList();
    }

    @Test
    @DisplayName("a NULL condition is not a match, so the ELSE is taken, as in SQL")
    void nullConditionTakesTheElse() {
        try (Relix relix = Relix.open()) {
            relix.define(T);
            assertThat(rows(relix, "τ k (π k, CASE WHEN x > 3 THEN 'big' ELSE 'small' END → c (T))"))
                    .containsExactly("1 big", "2 small", "3 small");
        }
    }

    @Test
    @DisplayName("with no ELSE an unmatched row is NULL")
    void noElseIsNull() {
        try (Relix relix = Relix.open()) {
            relix.define(T);
            assertThat(rows(relix, "τ k (π k, CASE WHEN x > 3 THEN 'big' END → c (T))"))
                    .containsExactly("1 big", "2 NULL", "3 NULL");
        }
    }

    @Test
    @DisplayName("a simple CASE over a NULL subject matches no WHEN")
    void simpleCaseOverNull() {
        try (Relix relix = Relix.open()) {
            relix.define(T);
            assertThat(rows(relix, "τ k (π k, CASE kind WHEN 'a' THEN 1 WHEN 'b' THEN 2 ELSE 0 END → c (T))"))
                    .containsExactly("1 1", "2 2", "3 0");
        }
    }

    @Test
    @DisplayName("a CASE as a GROUP key, inside an aggregate, and as a SORT key")
    void caseInEveryOperandPosition() {
        try (Relix relix = Relix.open()) {
            relix.define(T);
            assertThat(rows(relix, "τ size (γ CASE WHEN x > 3 THEN 'big' ELSE 'small' END → size, COUNT(*) → n (T))"))
                    .containsExactly("big 1", "small 2");
            assertThat(rows(relix, "γ SUM(CASE WHEN x > 3 THEN x ELSE 0 END) → total (T)"))
                    .containsExactly("5");
            assertThat(rows(relix, "π k (τ CASE WHEN kind = 'b' THEN 0 ELSE 1 END, k (T))"))
                    .containsExactly("2", "1", "3");
            assertThat(rows(relix, "π k (σ CASE kind WHEN 'a' THEN 1 ELSE 0 END = 1 (T))"))
                    .containsExactly("1");
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

    @Test
    @DisplayName("pushed to a database, a CASE is the database's own CASE and gives its answer")
    void pushedCaseMatchesTheDatabase() throws SQLException {
        H2 h2 = new H2("jdbc:h2:mem:embed_case;DB_CLOSE_DELAY=-1");
        try (Connection c = h2.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE t (k INT, x INT, kind VARCHAR(4))");
            s.execute("INSERT INTO t VALUES (1, 5, 'a'), (2, 2, 'b'), (3, NULL, NULL)");
        }
        List<String> expected = new ArrayList<>();
        try (Connection c = h2.getConnection(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT k, CASE WHEN x > 3 THEN 'big' WHEN x > 1 THEN 'mid' END,"
                     + " CASE kind WHEN 'a' THEN 'A' ELSE 'other' END FROM t ORDER BY k")) {
            while (r.next()) {
                expected.add(r.getInt(1) + " " + (r.getString(2) == null ? "NULL" : r.getString(2))
                        + " " + r.getString(3));
            }
        }
        try (Relix relix = Relix.builder().jdbc("db", h2).build()) {
            Relation query = relix.relation("τ K (π K, CASE WHEN X > 3 THEN 'big' WHEN X > 1 THEN 'mid' END → band,"
                    + " CASE KIND WHEN 'a' THEN 'A' ELSE 'other' END → label (db.t))");
            EmbedAssertions.assertThat(query).explains().contains("CASE WHEN");
            assertThat(rows(relix, "τ K (π K, CASE WHEN X > 3 THEN 'big' WHEN X > 1 THEN 'mid' END → band,"
                    + " CASE KIND WHEN 'a' THEN 'A' ELSE 'other' END → label (db.t))"))
                    .containsExactlyElementsOf(expected)
                    .containsExactly("1 big A", "2 mid other", "3 NULL other");
        }
    }
}

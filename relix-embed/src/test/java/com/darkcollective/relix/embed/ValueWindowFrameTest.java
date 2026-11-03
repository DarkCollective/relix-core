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

import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIRST_VALUE, LAST_VALUE and NTH_VALUE read SQL's default frame, from the partition's
 * start to the current row's last peer, unless they name another (#279). Each is held
 * to H2's answer for the SQL it stands for, run in the engine over an inline table and
 * pushed down to H2's own table.
 *
 * <p>The pushed run is checked to send the window in its OVER clause where H2 can be told
 * the frame.
 *
 * <p>{@code O} ties at 2, so the default frame's peers matter; the tied rows hold one
 * value, so which of them a database reads first does not change the answer. A frame
 * counted in rows sorts by {@code ID} too, for the same reason. {@code V} is NULL once.
 */
@DisplayName("value window functions over their frame, evaluated and pushed")
final class ValueWindowFrameTest {

    private static final String DATA = """
            T := [| ID | K | O | V    |
                  | 1  | a | 1 | 10   |
                  | 2  | a | 2 | 20   |
                  | 3  | a | 2 | 20   |
                  | 4  | a | 3 | 30   |
                  | 5  | a | 4 | NULL |
                  | 6  | b | 1 | 7    |];
            """;

    private static final H2 DB = seeded();

    private static H2 seeded() {
        H2 h2 = new H2("jdbc:h2:mem:embed_value_windows;DB_CLOSE_DELAY=-1");
        try (Connection c = h2.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE t (id INT, k VARCHAR(1), o INT, v INT)");
            s.execute("INSERT INTO t VALUES (1, 'a', 1, 10), (2, 'a', 2, 20), (3, 'a', 2, 20),"
                    + " (4, 'a', 3, 30), (5, 'a', 4, NULL), (6, 'b', 1, 7)");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return h2;
    }

    private static List<String> rows(Relation relation) {
        List<String> rows = new ArrayList<>();
        relation.toList().forEach(t -> {
            List<String> cells = new ArrayList<>();
            t.schema().columns().forEach(c -> {
                Value v = t.get(c.name());
                cells.add(v.isNull() ? "NULL" : v.asDisplayString());
            });
            rows.add(String.join("|", cells));
        });
        return rows.stream().sorted().toList();
    }

    private static List<String> h2(String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = DB.getConnection(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            int width = r.getMetaData().getColumnCount();
            while (r.next()) {
                List<String> cells = new ArrayList<>();
                for (int i = 1; i <= width; i++) {
                    Object v = r.getObject(i);
                    cells.add(v == null ? "NULL" : v.toString());
                }
                rows.add(String.join("|", cells));
            }
        }
        return rows.stream().sorted().toList();
    }

    /** Each row: the window, with {@code %s} for the relation, and the window function and OVER H2 checks it against. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '#', textBlock = """
            WINDOW FIRST_VALUE(V) SORT O PER K AS W (%s) \
              # FIRST_VALUE(v) OVER (PARTITION BY k ORDER BY o)
            WINDOW LAST_VALUE(V) SORT O PER K AS W (%s) \
              # LAST_VALUE(v) OVER (PARTITION BY k ORDER BY o)
            WINDOW NTH_VALUE(V, 2) SORT O PER K AS W (%s) \
              # NTH_VALUE(v, 2) OVER (PARTITION BY k ORDER BY o)
            WINDOW LAST_VALUE(V) SORT O DESC PER K AS W (%s) \
              # LAST_VALUE(v) OVER (PARTITION BY k ORDER BY o DESC NULLS LAST)
            WINDOW LAST_VALUE(V) OVER PARTITION SORT O PER K AS W (%s) \
              # LAST_VALUE(v) OVER (PARTITION BY k ORDER BY o ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)
            WINDOW NTH_VALUE(V, 2) OVER PARTITION SORT O PER K AS W (%s) \
              # NTH_VALUE(v, 2) OVER (PARTITION BY k ORDER BY o ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)
            WINDOW FIRST_VALUE(V) OVER PARTITION SORT O PER K AS W (%s) \
              # FIRST_VALUE(v) OVER (PARTITION BY k ORDER BY o ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)
            WINDOW LAST_VALUE(V) OVER ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING SORT O, ID PER K AS W (%s) \
              # LAST_VALUE(v) OVER (PARTITION BY k ORDER BY o, id ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING)
            WINDOW FIRST_VALUE(V) OVER ROWS BETWEEN 1 PRECEDING AND CURRENT ROW SORT O, ID PER K AS W (%s) \
              # FIRST_VALUE(v) OVER (PARTITION BY k ORDER BY o, id ROWS BETWEEN 1 PRECEDING AND CURRENT ROW)
            WINDOW NTH_VALUE(V, 2) OVER ROWS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING SORT O, ID PER K AS W (%s) \
              # NTH_VALUE(v, 2) OVER (PARTITION BY k ORDER BY o, id ROWS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING)
            WINDOW LAST_VALUE(V) OVER ROWS BETWEEN 2 FOLLOWING AND 3 FOLLOWING SORT O, ID PER K AS W (%s) \
              # LAST_VALUE(v) OVER (PARTITION BY k ORDER BY o, id ROWS BETWEEN 2 FOLLOWING AND 3 FOLLOWING)
            WINDOW LAST_VALUE(V) OVER 2 ROWS SORT O, ID PER K AS W (%s) \
              # LAST_VALUE(v) OVER (PARTITION BY k ORDER BY o, id ROWS BETWEEN 1 PRECEDING AND CURRENT ROW)
            WINDOW LAST_VALUE(V) OVER RANGE BETWEEN CURRENT ROW AND CURRENT ROW SORT O PER K AS W (%s) \
              # LAST_VALUE(v) OVER (PARTITION BY k ORDER BY o RANGE BETWEEN CURRENT ROW AND CURRENT ROW)
            WINDOW FIRST_VALUE(V) OVER RANGE BETWEEN 1 FOLLOWING AND UNBOUNDED FOLLOWING SORT O PER K AS W (%s) \
              # FIRST_VALUE(v) OVER (PARTITION BY k ORDER BY o RANGE BETWEEN 1 FOLLOWING AND UNBOUNDED FOLLOWING)
            """)
    void agreesWithH2(String window, String over) throws SQLException {
        List<String> expected = h2("SELECT id, k, o, v, " + over + " FROM t");
        try (Relix relix = Relix.open()) {
            relix.define(DATA);
            assertThat(rows(relix.relation(window.formatted("T")))).as("in the engine")
                    .containsExactlyElementsOf(expected);
        }
        try (Relix pushed = Relix.builder().jdbc("db", DB).build()) {
            Relation relation = pushed.relation(window.formatted("db.T"));
            // H2 is told a RANGE offset's frame by none of the dialects it may be, so that
            // window runs in the engine over the pushed scan; every other one is sent whole.
            String sent = window.contains("RANGE BETWEEN 1") ? "SELECT ID, K, O, V FROM T"
                    : window.substring(7, window.indexOf(')') + 1) + " OVER (PARTITION BY K";
            EmbedAssertions.assertThat(relation.optimized()).explains().contains("PushedScan").contains(sent);
            assertThat(rows(relation)).as("over H2's table").containsExactlyElementsOf(expected);
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

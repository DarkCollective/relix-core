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
 * SQL's truth tests, row-value comparisons and quantified comparisons over a list
 * (#260), each held to a database's own answer over rows with NULLs: computed in the
 * engine over the same rows, and pushed down.
 */
@DisplayName("IS TRUE, row values and ANY/ALL, evaluated")
final class TruthRowAndQuantifiedPredicatesTest {

    private static final String T = """
            T := [| k | a    | b    | f     |
            | 1 | 1    | 2    | true  |
            | 2 | 1    | NULL | false |
            | 3 | NULL | 2    | NULL  |
            | 4 | 2    | 1    | true  |
            | 5 | 0    | 5    | false |];
            """;

    private static final H2 DB = seeded();

    private static H2 seeded() {
        H2 h2 = new H2("jdbc:h2:mem:embed_truth_row_quantified;DB_CLOSE_DELAY=-1");
        try (Connection c = h2.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE t (k INT, a INT, b INT, f BOOLEAN)");
            s.execute("INSERT INTO t VALUES (1, 1, 2, TRUE), (2, 1, NULL, FALSE), (3, NULL, 2, NULL),"
                    + " (4, 2, 1, TRUE), (5, 0, 5, FALSE)");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return h2;
    }

    private static List<String> keys(Relix relix, String query) {
        return relix.relation(query).toList().stream()
                .map(t -> t.get(t.schema().columns().get(0).name()).asDisplayString())
                .toList();
    }

    private static List<String> h2Keys(String condition) throws SQLException {
        List<String> keys = new ArrayList<>();
        try (Connection c = DB.getConnection(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT k FROM t WHERE " + condition + " ORDER BY k")) {
            while (r.next()) {
                keys.add(String.valueOf(r.getInt(1)));
            }
        }
        return keys;
    }

    /**
     * Each row is the Relix condition and the SQL H2 runs for it. Where H2 has no
     * spelling of its own (a quantifier over a literal list) the SQL is the
     * standard's meaning written out.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            f IS TRUE                        | f IS TRUE
            f IS NOT TRUE                    | f IS NOT TRUE
            f IS FALSE                       | f IS FALSE
            f IS NOT FALSE                   | f IS NOT FALSE
            f IS UNKNOWN                     | f IS UNKNOWN
            f IS NOT UNKNOWN                 | f IS NOT UNKNOWN
            a > 0 IS NOT TRUE                | (a > 0) IS NOT TRUE
            (a = 1) IS UNKNOWN               | (a = 1) IS UNKNOWN
            NOT (a < b IS TRUE)              | NOT ((a < b) IS TRUE)
            (a, b) = (1, 2)                  | (a, b) = (1, 2)
            (a, b) <> (1, 2)                 | (a, b) <> (1, 2)
            (a, b) < (1, 3)                  | (a, b) < (1, 3)
            (a, b) <= (1, 2)                 | (a, b) <= (1, 2)
            (a, b) > (1, 1)                  | (a, b) > (1, 1)
            (a, b) >= (1, 2)                 | (a, b) >= (1, 2)
            (a, b) IN {(1, 2), (2, 1)}       | (a, b) IN ((1, 2), (2, 1))
            (a, b) IN ((1, 2), (0, 5))       | (a, b) IN ((1, 2), (0, 5))
            (a, b) NOT IN {(1, 2), (2, 1)}   | (a, b) NOT IN ((1, 2), (2, 1))
            (a, b) IN {}                     | 1 = 0
            a IN (1, 2)                      | a IN (1, 2)
            a NOT IN (0, 2)                  | a NOT IN (0, 2)
            a > ALL {0, 1}                   | a > 0 AND a > 1
            a > ALL (0, b)                   | a > 0 AND a > b
            b < ANY {2, 3}                   | b < 2 OR b < 3
            b = SOME (5, 7)                  | b = 5 OR b = 7
            a = ANY (1)                      | a = 1
            a <> ALL (1)                     | a <> 1
            a = ANY {1, b}                   | a = 1 OR a = b
            a <> ALL {1, 2}                  | a <> 1 AND a <> 2
            a > ALL {}                       | 1 = 1
            a = ANY {}                       | 1 = 0
            a IN {}                          | 1 = 0
            a NOT IN {}                      | 1 = 1
            """)
    void agreesWithTheDatabase(String relix, String sql) throws SQLException {
        List<String> expected = h2Keys(sql);
        try (Relix engine = Relix.open()) {
            engine.define(T);
            assertThat(keys(engine, "τ k (π k (σ " + relix + " (T)))"))
                    .as("computed in the engine").containsExactlyElementsOf(expected);
        }
        try (Relix pushed = Relix.builder().jdbc("db", DB).build()) {
            // H2 folds unquoted names to upper case
            assertThat(keys(pushed, "τ K (π K (σ " + relix.toUpperCase() + " (db.t)))"))
                    .as("pushed down").containsExactlyElementsOf(expected);
        }
    }

    /**
     * {@code IN} over one value in parentheses is no list, as a quantifier's is: it is
     * refused before it runs, naming the braces, where it once failed in the executor
     * naming a {@code StringOperand} (#290).
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            a IN (1)        | a IN {1}
            a NOT IN (1)    | a NOT IN {1}
            """)
    void inOverOneValueIsRefused(String relix, String rewrite) {
        try (Relix engine = Relix.open()) {
            engine.define(T);
            assertThatThrownBy(() -> engine.relation("σ " + relix + " (T)").toList())
                    .isNotInstanceOf(QueryExecutionException.class)
                    .hasMessageContaining("a list of one value is written in braces: " + rewrite)
                    .hasMessageNotContaining("Operand");
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

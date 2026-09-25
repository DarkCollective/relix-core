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

import com.darkcollective.relix.connectors.std.internal.JdbcDataSourceConnector;
import com.darkcollective.relix.processor.internal.QueryExecutor;
import com.darkcollective.relix.processor.internal.QueryResult;
import com.darkcollective.relix.semantic.SemanticFixtures;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.semantic.internal.SemanticResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds in-engine evaluation and pushed-down evaluation to the same answer about
 * NULL.
 *
 * <p>A predicate the planner folds into a {@code WHERE} clause is evaluated by the
 * database, not by {@code PredicateEvaluator}. The two must agree, or the same
 * script over the same rows returns different results depending on where the rows
 * happen to live — a CSV file and a table would disagree, and so would a query
 * before and after an optimizer change that made a σ pushable.
 *
 * <p>Each test runs one predicate twice over identical data: once against an H2
 * table (pushed) and once against an inline table (in-engine). An empty cell in an
 * inline table is a NULL, which is what makes the pair comparable.
 */
@DisplayName("In-engine and pushed-down evaluation agree about NULL")
final class NullSemanticsAgreementTest {

    /** Creates the shared fixture table: one ordinary row, one with a NULL. */
    private static void seed(String url) throws SQLException {
        try (Connection c = DriverManager.getConnection(url)) {
            var st = c.createStatement();
            st.execute("CREATE TABLE orders (order_id INT, amount INT, code VARCHAR(20))");
            st.execute("INSERT INTO orders VALUES (100, 120, 'AB-1'), (101, NULL, NULL)");
        }
    }

    /** The same two rows as {@link #seed}, written inline so they are read in-engine. */
    private static final String INLINE = """
            Local := [
            | order_id | amount | code |
            |----------|--------|------|
            | 100      | 120    | AB-1 |
            | 101      |        |      |
            ];
            """;

    private static String script(String url, String predicate) {
        return "connection db from database { url: \"" + url + "\" };\n"
                + "source DbOrders from db {\n"
                + "    table: \"orders\",\n"
                + "    schema: { order_id: NUMBER, amount: NUMBER, code: STRING }\n"
                + "};\n\n"
                + INLINE + "\n"
                + "query { σ " + predicate + " (DbOrders) };\n"
                + "query { σ " + predicate + " (Local) };\n";
    }

    /** Runs both queries and returns their row counts as (pushed, inEngine). */
    private static int[] rowCounts(String url, String predicate) {
        SemanticResult analysis = SemanticFixtures.analyze(script(url, predicate));
        assertThat(analysis.errors()).isEmpty();
        SemanticModel model = analysis.model().orElseThrow();
        List<QueryResult> results =
                new QueryExecutor().execute(model, new JdbcDataSourceConnector(model));
        assertThat(results).hasSize(2);
        return new int[] {results.get(0).rows().size(), results.get(1).rows().size()};
    }

    private static void assertAgree(String url, String predicate, int expected) throws SQLException {
        seed(url);
        int[] counts = rowCounts(url, predicate);
        assertThat(counts[0])
                .as("pushed to the database: σ %s", predicate)
                .isEqualTo(expected);
        assertThat(counts[1])
                .as("evaluated in-engine: σ %s", predicate)
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("¬(amount > 100) drops the NULL row on both sides")
    void negatedComparison() throws SQLException {
        // 120 > 100 is true, so ¬ drops it. NULL > 100 is UNKNOWN, and ¬UNKNOWN is
        // UNKNOWN, which σ also drops — leaving nothing.
        assertAgree("jdbc:h2:mem:nullsem_not;DB_CLOSE_DELAY=-1", "¬(amount > 100)", 0);
    }

    @Test
    @DisplayName("¬(amount = 120) drops the NULL row on both sides")
    void negatedEquality() throws SQLException {
        assertAgree("jdbc:h2:mem:nullsem_eq;DB_CLOSE_DELAY=-1", "¬(amount = 120)", 0);
    }

    @Test
    @DisplayName("a NULL row survives neither a predicate nor its negation")
    void predicateAndNegationArePartialNotComplementary() throws SQLException {
        String url = "jdbc:h2:mem:nullsem_partition;DB_CLOSE_DELAY=-1";
        seed(url);
        int[] positive = rowCounts(url, "amount > 100");
        int[] negative = rowCounts(url, "¬(amount > 100)");
        assertThat(positive[0] + negative[0])
                .as("pushed: the two halves do not add up to the input — the NULL row is in neither")
                .isEqualTo(1);
        assertThat(positive[1] + negative[1])
                .as("in-engine: the same")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("¬(code LIKE …) agrees with NOT LIKE, and both drop the NULL row")
    void negatedPatternMatchesTheBuiltInForm() throws SQLException {
        assertAgree("jdbc:h2:mem:nullsem_like;DB_CLOSE_DELAY=-1", "¬(code LIKE \"XY%\")", 1);
        assertAgree("jdbc:h2:mem:nullsem_notlike;DB_CLOSE_DELAY=-1", "code NOT LIKE \"XY%\"", 1);
    }

    @Test
    @DisplayName("¬(amount ∈ {…}) agrees with ∉, and both drop the NULL row")
    void negatedMembershipMatchesTheBuiltInForm() throws SQLException {
        assertAgree("jdbc:h2:mem:nullsem_in;DB_CLOSE_DELAY=-1", "¬(amount ∈ {120})", 0);
        assertAgree("jdbc:h2:mem:nullsem_notin;DB_CLOSE_DELAY=-1", "amount ∉ {120}", 0);
    }
}

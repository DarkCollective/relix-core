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
package com.darkcollective.relix.embed.equivalence;

import com.darkcollective.relix.embed.Relation;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.embed.Tuple;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A query the optimizer rewrote into one the database can answer whole returns what the
 * engine would have returned.
 *
 * <h2>Why this is not in the agreement suites</h2>
 * <p>{@code PushdownAgreement} plans <em>the resolved root tree with no optimizer pass</em>,
 * so every corpus case measures the query as it was written. A shape that exists only
 * <em>after</em> a rewrite is invisible to it — on H2 and in the container suites alike.
 * {@code SEL-010} and the {@code SET} family produce exactly such shapes: written, they
 * are a set operation no renderer spells; rewritten, they are a single {@code SELECT}.
 *
 * <p>{@code OptimizedPushdownTest} beside this one pins the SQL those rewrites emit, and
 * that is an assertion against the string the renderer was expected to produce. This one
 * asks a database. The distinction is the project's own: being right and being wrong are
 * indistinguishable from a renderer test until something executes the SQL.
 *
 * <h2>How the comparison is made</h2>
 * <p>The same rows are declared twice — once as an inline relation the engine evaluates,
 * once as an H2 table the query is pushed into — and the same expression is run over
 * each. So the two sides differ in <em>who computed the answer</em> and in nothing else.
 * The rows carry a duplicate and a NULL, because the rewrites turn on de-duplication and
 * on three-valued logic, and a fixture of distinct non-NULL rows would exercise neither.
 */
@DisplayName("A rewritten query the database answers whole returns what the engine would")
final class OptimizedPushdownExecutionTest {

    private static final String URL =
            "jdbc:h2:mem:optimized_pushdown_exec;DB_CLOSE_DELAY=-1";

    /** `(2, east, 500)` twice, and a NULL amount — the two properties the rules turn on. */
    private static final String ROWS = """
            | 1 | west | 10   |
            | 2 | east | 500  |
            | 2 | east | 500  |
            | 3 | west |      |
            """;

    private static final String PUSHED = """
            connection db from database { url: "%s" };
            source Orders from db {
                table: "orders",
                schema: { oid: NUMBER, region: STRING, amount: NUMBER }
            };
            """.formatted(URL);

    private static final String IN_ENGINE = """
            Orders := [
            | oid | region | amount |
            |-----|--------|--------|
            """ + ROWS + "];\n";

    @BeforeAll
    static void seed() throws SQLException {
        try (Connection c = DriverManager.getConnection(URL); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS orders");
            st.execute("CREATE TABLE orders (oid INT, region VARCHAR(16), amount INT)");
            st.execute("INSERT INTO orders VALUES "
                    + "(1,'west',10),(2,'east',500),(2,'east',500),(3,'west',NULL)");
        }
    }

    /** Runs {@code expression} over both declarations and asserts the answers match. */
    private static void agrees(String expression) {
        assertThat(rows(PUSHED, expression))
                .as("the database's answer to the rewritten query: %s", expression)
                .containsExactlyInAnyOrderElementsOf(rows(IN_ENGINE, expression));
    }

    private static List<String> rows(String preamble, String expression) {
        try (Relix session = Relix.builder().build()) {
            Relation r = session.script(preamble + "query { " + expression + " };").getFirst();
            return r.toList().stream().map(OptimizedPushdownExecutionTest::cells).toList();
        }
    }

    /** One row as "v,v,v" — column by column, so a NULL reads as NULL rather than vanishing. */
    private static String cells(Tuple tuple) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tuple.width(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(tuple.get(i).asDisplayString());
        }
        return sb.toString();
    }

    @Test
    @DisplayName("SEL-010 ∪ — one WHERE with an OR, de-duplicated by the database")
    void unionOfTwoSelections() {
        agrees("σ region = \"west\" (Orders) ∪ σ amount > 100 (Orders)");
    }

    @Test
    @DisplayName("SEL-010 ∩ — the same query with an AND")
    void intersectionOfTwoSelections() {
        agrees("σ region = \"west\" (Orders) ∩ σ amount > 100 (Orders)");
    }

    @Test
    @DisplayName("SET-001 — R ∪ R is one SELECT DISTINCT, and the duplicate does not survive")
    void idempotentUnion() {
        agrees("Orders ∪ Orders");
    }

    @Test
    @DisplayName("SET-002 — the complement keeps the row whose predicate was UNKNOWN")
    void complement() {
        // This one does NOT push (no dialect spells a condition's truth value), so what
        // it checks is that the in-engine rewrite still agrees with a database-backed
        // source — the NULL amount is undecided by `amount > 100`, so the difference
        // keeps it and a bare ¬ would not.
        agrees("Orders − σ amount > 100 (Orders)");
    }
}

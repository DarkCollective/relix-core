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

import com.darkcollective.relix.connectors.std.internal.ConnectionPool;
import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.plan.internal.Dialect;
import com.darkcollective.relix.semantic.SemanticFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pushdown corpus against a real DuckDB — in the <b>gate</b>, not the
 * {@code integration} tier.
 *
 * <p>DuckDB is embedded, so there is no container to start and nothing a clean checkout
 * lacks: this suite runs on every build, which is a better position than the MySQL and
 * Postgres suites occupy. The dialect it checks is PostgreSQL-shaped, so most of what it
 * asks has been asked of Postgres before; what it adds is a second witness to the
 * {@code LATERAL} AS-OF renderer and to {@code NULLS LAST}, and a backend that answers
 * both string-collation questions {@code true}.
 *
 * <p>The database is a file rather than {@code jdbc:duckdb:}, because an in-memory
 * DuckDB is private to the connection that opened it — the engine's connection pool
 * would open a second, empty one and every case would fail on a missing table.
 */
@DisplayName("A pushed-down query returns what the in-engine one returns (DuckDB)")
final class DuckDbPushdownAgreementTest {

    @TempDir
    static Path directory;

    private static String url;

    private static PushdownAgreement agreement;

    @BeforeAll
    static void seed() throws SQLException {
        url = "jdbc:duckdb:" + directory.resolve("pushdown.duckdb");
        try (Connection c = DriverManager.getConnection(url)) {
            PushdownFixture.seed(c, PushdownFixture.Flavour.DUCKDB);
        }
        agreement = new PushdownAgreement(PushdownFixture.preamble(url, null, null), Dialect.DUCKDB);
    }

    @TestFactory
    @DisplayName("the whole corpus")
    Stream<DynamicNode> agreement() {
        return PushdownCorpus.tests(agreement);
    }

    /**
     * That a connection the engine hands out is at UTC.
     *
     * <p>DuckDB takes its session {@code TimeZone} from the host, and a
     * {@code TIMESTAMPTZ} is rendered — and truncated — in it, so a folded
     * {@code date_trunc} over one buckets by the host's wall clock unless the session is
     * pinned. The corpus's {@code stamped} cases are what would disagree; this states the
     * mechanism directly, since on a host already at UTC those cases pass either way.
     */
    @Test
    @DisplayName("a pooled connection is at UTC")
    void sessionIsPinnedToUtc() throws SQLException {
        ConnectionDeclaration declaration = SemanticFixtures
                .model(PushdownFixture.preamble(url, null, null) + "query { Orders };")
                .connections().values().iterator().next();
        try (ConnectionPool pool = new ConnectionPool()) {
            Connection c = pool.borrow(declaration);
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT current_setting('TimeZone')")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualToIgnoringCase("UTC");
            } finally {
                pool.release(declaration, c);
            }
        }
    }
}

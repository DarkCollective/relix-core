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

import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.plan.internal.Dialect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.TimeZone;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pushdown corpus against a real SQL Server, in the {@code integration} tier.
 *
 * <p>SQL Server is the first backend in the corpus whose differences are structural
 * rather than spellings, and each one was found here or confirmed here: row limiting that
 * is legal only after an {@code ORDER BY}, so a limit over an unordered sub-tree must
 * decline; no {@code NULLS LAST} and no boolean to sort on; {@code APPLY} where the others
 * say {@code LATERAL}; a case-insensitive default collation; and no session time zone, so
 * an extraction is taken through {@code SWITCHOFFSET(…, '+00:00')} rather than trusted to
 * a setting.
 *
 * <p>The image is Microsoft's, whose licence has to be accepted explicitly; the Developer
 * edition it runs is free for testing.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("A pushed-down query returns what the in-engine one returns (SQL Server)")
final class SqlServerPushdownAgreementTest {

    @Container
    private static final MSSQLServerContainer<?> SQLSERVER =
            new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
                    .acceptLicense();

    private static PushdownAgreement agreement;

    @BeforeAll
    static void seed() throws SQLException {
        try (Connection c = DriverManager.getConnection(
                SQLSERVER.getJdbcUrl(), SQLSERVER.getUsername(), SQLSERVER.getPassword())) {
            PushdownFixture.seed(c, PushdownFixture.Flavour.SQLSERVER);
        }
        agreement = new PushdownAgreement(PushdownFixture.preamble(
                SQLSERVER.getJdbcUrl(), SQLSERVER.getUsername(), SQLSERVER.getPassword()),
                Dialect.SQLSERVER);
    }

    @TestFactory
    @DisplayName("the whole corpus")
    Stream<DynamicNode> agreement() {
        return PushdownCorpus.tests(agreement);
    }

    /**
     * That a {@code DATETIMEOFFSET} is read as the instant it holds, whatever zone the
     * JVM reading it is in.
     *
     * <p>The corpus cannot ask this: its {@code stamped} column is seeded from strings
     * with no offset, which SQL Server stores at {@code +00:00}, and at that offset every
     * way of reading the value agrees. Asked for a {@code LocalDateTime}, mssql-jdbc
     * converts to the <em>client JVM's</em> zone, so {@code 10:00 -05:00} read on a JVM at
     * {@code Asia/Tokyo} was a different instant from the same row read at UTC. It is read
     * as an {@code OffsetDateTime} now, and the JVM's zone is moved for the length of the
     * read to prove it.
     */
    @Test
    @DisplayName("a DATETIMEOFFSET is the instant it holds, whatever the JVM's zone")
    void datetimeOffsetIsAnInstant() throws SQLException {
        try (Connection c = DriverManager.getConnection(
                SQLSERVER.getJdbcUrl(), SQLSERVER.getUsername(), SQLSERVER.getPassword());
             Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS stamps");
            st.execute("CREATE TABLE stamps (id INT, at DATETIMEOFFSET)");
            st.execute("INSERT INTO stamps VALUES (1, '2024-01-01 10:00:00 -05:00')");
        }
        String script = "connection db from database { url: \"" + SQLSERVER.getJdbcUrl()
                + "\", user: \"" + SQLSERVER.getUsername() + "\", password: \""
                + SQLSERVER.getPassword() + "\" };\n"
                + "source Stamps from db { table: \"stamps\", schema: { id: NUMBER, at: TIMESTAMP } };\n";
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
        try (Relix relix = Relix.builder().build()) {
            relix.define(script);
            List<Instant> read = relix.relation("Stamps").toList().stream()
                    .map(t -> t.instant("at")).toList();
            List<Instant> introspected = relix.relation("db.stamps").toList().stream()
                    .map(t -> t.instant("at")).toList();
            assertThat(read).containsExactly(Instant.parse("2024-01-01T15:00:00Z"));
            assertThat(introspected).containsExactly(Instant.parse("2024-01-01T15:00:00Z"));
        } finally {
            TimeZone.setDefault(original);
        }
    }
}

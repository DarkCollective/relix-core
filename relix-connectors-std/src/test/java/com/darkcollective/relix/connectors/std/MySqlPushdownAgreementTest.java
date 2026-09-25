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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@link PushdownCorpus} against a real MySQL, which is the first check of
 * anything MySQL-specific this project has.
 *
 * <h2>Why H2 was not enough</h2>
 * The gate's agreement suite runs over H2, which resolves to {@link Dialect#GENERIC}
 * — so the {@link Dialect#MYSQL} arm of every method on that enum had never been
 * executed against a database that could reject it. That is four distinct claims
 * nothing checked: back-tick identifier quoting, the portable
 * {@code (expr IS NULL) ASC} form the NULL ordering leans on <em>because</em> MySQL
 * has no {@code NULLS LAST}, the {@code HAVING} spelling of ∀, and the temporal
 * literal forms. Each was written from the documentation and asserted against
 * itself.
 *
 * <p>The MySQL arms are also where a stale one hides longest, since nothing renders
 * them: {@link Dialect#boolAnd} declined MySQL for a reason that stopped being true
 * when the Postgres arm moved off {@code bool_and} onto the portable
 * {@code COUNT(CASE …)} form, and no test could notice.
 *
 * <h2>What it costs to run</h2>
 * A MySQL container, so this is in the {@code integration} tier rather than the
 * gate: {@code ./gradlew :relix-connectors-std:integrationTest}, or
 * {@code ./gradlew verifyAll}. With no Docker daemon it skips rather than fails.
 *
 * @see PushdownAgreementTest
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("A pushed-down query returns what the in-engine one returns (MySQL)")
final class MySqlPushdownAgreementTest {

    /** MySQL 8, which is what the dialect's capability answers are written for. */
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("relix")
            .withUsername("relix")
            .withPassword("relix");

    /**
     * A session at UTC, which is what a deployment that cares about instants sets and
     * what the whole corpus runs against.
     */
    private static final String UTC_SESSION = "UTC";

    /**
     * A session at a fixed non-UTC offset, for {@link #skewedSession()}. India is
     * chosen because it never observes daylight saving, so the offset is one number
     * rather than a function of the date — a test that failed only in summer would be
     * worse than no test.
     */
    private static final String SKEWED_SESSION = "Asia/Kolkata";

    private static PushdownAgreement utc;
    private static PushdownAgreement skewed;
    private static PushdownAgreement exactCollation;

    @BeforeAll
    static void seed() throws SQLException {
        try (Connection c = DriverManager.getConnection(
                url(UTC_SESSION), MYSQL.getUsername(), MYSQL.getPassword())) {
            PushdownFixture.seed(c);
        }
        utc = agreementFor(UTC_SESSION);
        skewed = agreementFor(SKEWED_SESSION);
        exactCollation = binaryCollationAgreement();
    }

    /** The table-name suffix the binary-collated copy of the fixture is seeded under. */
    private static final String BINARY_SUFFIX = "_bin";

    /**
     * The fixture again under {@code *_bin}, with every string column declared
     * {@code utf8mb4_0900_bin} — the shape a connection may honestly say
     * {@code collation: exact} about.
     *
     * <p>A suffix rather than a second database: the container grants its user only the
     * one it created, and the relix relation names are unchanged either way, so the same
     * corpus reads either copy.
     */
    private static PushdownAgreement binaryCollationAgreement() throws SQLException {
        try (Connection c = DriverManager.getConnection(
                url(UTC_SESSION), MYSQL.getUsername(), MYSQL.getPassword())) {
            PushdownFixture.seed(c, PushdownFixture.Flavour.DEFAULT, BINARY_SUFFIX,
                    "utf8mb4_0900_bin");
        }
        return new PushdownAgreement(
                PushdownFixture.preamble(url(UTC_SESSION), MYSQL.getUsername(),
                        MYSQL.getPassword(), "collation: exact", BINARY_SUFFIX),
                Dialect.MYSQL, true);
    }

    /**
     * A JDBC URL whose <em>server session</em> time zone is {@code zone}.
     *
     * <p>{@code forceConnectionTimeZoneToSession} is the load-bearing parameter:
     * {@code connectionTimeZone} alone tells the driver how to interpret the values it
     * carries, and a spelling that truncates or extracts runs on the <em>server</em>,
     * where only the session variable is visible.
     */
    private static String url(String zone) {
        return MYSQL.getJdbcUrl()
                + "?connectionTimeZone=" + zone
                + "&forceConnectionTimeZoneToSession=true"
                + "&preserveInstants=false";
    }

    private static PushdownAgreement agreementFor(String zone) {
        return new PushdownAgreement(
                PushdownFixture.preamble(url(zone), MYSQL.getUsername(), MYSQL.getPassword()),
                Dialect.MYSQL);
    }

    @TestFactory
    @DisplayName("the whole corpus, on a session at UTC")
    Stream<DynamicNode> agreement() {
        return PushdownCorpus.tests(utc);
    }

    /**
     * That the two URLs really do open sessions in different time zones — which is what
     * makes {@link #skewedSession()} a test of anything at all.
     *
     * <p>A URL parameter the driver ignored, or a version that spells it differently,
     * would leave both suites running the same connection twice — passing, and proving
     * nothing about time zones. Asserting the server's own session variable is the only
     * way to know, and the {@code TIMESTAMP} column shifting while the {@code DATETIME}
     * one does not is what makes the difference reach a value.
     *
     * <p>These connections are opened straight from the driver, deliberately: one the
     * engine hands out is pinned to UTC whatever the URL asked for, which is
     * {@link #sessionIsPinnedToUtc()}'s claim and would make this one vacuous.
     */
    @Test
    @DisplayName("the two sessions really are in different time zones")
    void sessionsDiffer() throws SQLException {
        assertThat(sessionZone(UTC_SESSION)).isNotEqualTo(sessionZone(SKEWED_SESSION));
        assertThat(read(UTC_SESSION, "stamped")).isNotEqualTo(read(SKEWED_SESSION, "stamped"));
        assertThat(read(UTC_SESSION, "placed")).isEqualTo(read(SKEWED_SESSION, "placed"));
    }

    /** The server's session {@code time_zone}, as that session reports it. */
    private static String sessionZone(String zone) throws SQLException {
        return queryOne(zone, "SELECT @@session.time_zone");
    }

    /** One temporal column of one row, as that session hands it back. */
    private static String read(String zone, String column) throws SQLException {
        return queryOne(zone, "SELECT `" + column + "` FROM orders WHERE oid = 1");
    }

    private static String queryOne(String zone, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(
                     url(zone), MYSQL.getUsername(), MYSQL.getPassword());
             ResultSet rs = c.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /**
     * The escape hatch, against columns that actually earn it.
     *
     * <p>A connection declaring {@code collation: exact} tells the planner that its
     * string columns compare the way the engine does, and the planner then folds string
     * comparison and string <em>ordering</em> without wrapping anything. That is the
     * user's claim rather than the engine's — a collation is a property of each column
     * and nothing the planner can reach reports it — so what this checks is that the
     * claim is <em>sound when true</em>: the same tables, declared with a binary
     * collation, agree under the declaration.
     *
     * <p>It is the more interesting direction of the two. The default is safe because it
     * collates or declines; the opt-in is safe only if binary really does mean what the
     * engine means, and the ordering cases are where that is least obvious.
     */
    @TestFactory
    @DisplayName("collation: exact, over columns declared with a binary collation")
    Stream<DynamicNode> declaredExactCollation() {
        return PushdownCorpus.tests(exactCollation, List.of(
                "string comparison, over values that differ only in case",
                "τ on a string key — ordering is what no collation fixes",
                "γ — an extremum over a string, which orders it"));
    }

    /**
     * The temporal half of the corpus again, over a connection whose URL asks for a
     * session at {@code +05:30}.
     *
     * <h2>The question this answers</h2>
     * MySQL converts a {@code TIMESTAMP} column from its UTC storage into the session
     * time zone on the way out, and a {@code DATETIME} not at all. A fold that truncates
     * or extracts server-side — {@code DATE_FORMAT}, {@code EXTRACT} — therefore reads a
     * wall clock the session decides, which is the engine's ambient state reaching an
     * answer that is supposed to be plan-independent.
     *
     * <p>What this group proves has changed, and the earlier reading is worth recording
     * because it was the weaker one. It used to pass because the <em>unpushed</em> path
     * read the same shifted wall clock: relix asked JDBC for a {@code LocalDateTime} and
     * labelled it UTC, so both paths were equally wrong about which moment the column
     * named, and agreeing was cheap. The Postgres suite is where that stopped being
     * survivable — a {@code timestamptz} is read as the instant it is, so the two paths
     * had nothing to be equally wrong about and simply disagreed.
     *
     * <p>So the engine now pins every session it opens to UTC, and this group is what
     * says the pin reaches a server that was asked for something else: the corpus is run
     * over a connection declaring {@code +05:30} and agrees anyway, which under the old
     * behaviour it would have done for the wrong reason and now does for the right one.
     *
     * @see #sessionIsPinnedToUtc()
     */
    @TestFactory
    @DisplayName("the temporal groups again, over a connection asking for +05:30")
    Stream<DynamicNode> skewedSession() {
        return PushdownCorpus.tests(skewed, List.of(
                "σ — temporal columns and literals",
                "σ — scalar functions the library spells for a backend",
                "DATE_TRUNC — one truncation, spelled differently per dialect"));
    }

    /**
     * That a connection the engine hands out is at UTC even when its URL asked for
     * {@code +05:30} — the direct form of what {@link #skewedSession()} shows through
     * the corpus.
     */
    @Test
    @DisplayName("a pooled connection is at UTC, though its URL asked for +05:30")
    void sessionIsPinnedToUtc() throws SQLException {
        assertThat(sessionZone(SKEWED_SESSION)).isNotEqualTo("+00:00");
        assertThat(pooledSessionZone(SKEWED_SESSION)).isEqualTo("+00:00");
    }

    /** The session zone on a connection borrowed the way execution borrows one. */
    private static String pooledSessionZone(String zone) throws SQLException {
        ConnectionDeclaration declaration = SemanticFixtures
                .model(PushdownFixture.preamble(url(zone), MYSQL.getUsername(), MYSQL.getPassword())
                        + "query { Orders };")
                .connections().values().iterator().next();
        try (ConnectionPool pool = new ConnectionPool()) {
            Connection c = pool.borrow(declaration);
            try (ResultSet rs = c.createStatement().executeQuery("SELECT @@session.time_zone")) {
                rs.next();
                return rs.getString(1);
            } finally {
                pool.release(declaration, c);
            }
        }
    }
}

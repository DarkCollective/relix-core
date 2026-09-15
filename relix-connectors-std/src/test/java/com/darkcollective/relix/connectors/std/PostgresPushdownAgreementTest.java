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

import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.plan.Dialect;
import com.darkcollective.relix.semantic.SemanticFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.TimeZone;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@link PushdownCorpus} against a real PostgreSQL — the second backend the corpus
 * is asked of, and the one with the most SQL nothing had ever executed.
 *
 * <h2>What only Postgres renders</h2>
 * Where the MySQL suite found arms that had been written from documentation, five of
 * {@link Dialect}'s arms are Postgres's <em>alone</em>: no other dialect emits them, so
 * until this ran, no database had ever been handed the SQL they produce.
 *
 * <ul>
 *   <li>{@code ORDER BY … NULLS LAST} — everything else gets the portable
 *       {@code (expr IS NULL) ASC} pair, which exists because MySQL has no such syntax.</li>
 *   <li>{@code TIMESTAMP WITH TIME ZONE '…Z'} — the only offset-aware literal form.</li>
 *   <li>{@code INTERVAL 'PT30M'} — the only {@code DURATION} literal that folds.</li>
 *   <li>The {@code postgres} variant of {@code date_trunc} and {@code TRUNC}.</li>
 *   <li>{@link Dialect#supportsLateralAsOf} — the AS-OF fold, which is a whole renderer
 *       rather than a spelling. It arrived one issue later than the other four, because
 *       the corpus had no AS-OF case to run: {@code orders} and {@code customers} have
 *       no key that join is about, so asking anything of it meant a second pair of
 *       tables in the fixture. See {@code PushdownCorpus.asOfJoins}.</li>
 * </ul>
 *
 * <h2>What it found</h2>
 * Three things, each of which had passed every test the project had.
 *
 * <p><b>A {@code timestamptz} column could not be read at all.</b> The connector decides
 * whether a timestamp carries its own offset from the JDBC type code, and PostgreSQL's
 * driver reports {@code timestamptz} as plain {@code Types.TIMESTAMP} — so the column
 * took the zone-less branch, the driver refused to hand it back as a
 * {@code LocalDateTime}, and the query failed outright. Nothing else in the project uses
 * that type, and the fixture only does because reproducing MySQL's converted/unconverted
 * pair on Postgres is what {@link PushdownFixture.Flavour} is for.
 *
 * <p><b>Postgres orders strings by locale.</b> Its default collation is
 * <em>deterministic</em>, so equality is decided by comparing bytes and every equality
 * case in the corpus passed; ordering is decided by the locale, and {@code τ} on a
 * string key, {@code name > 'B'} and {@code MIN(name)} all disagreed with the engine.
 * One boolean had been answering both questions, because on MySQL the answers coincide.
 *
 * <p><b>The session time zone was the client's.</b> See {@link #sessionIsPinnedToUtc()}.
 *
 * <p><b>And what the AS-OF renderer did not find</b>, which is worth recording because
 * it is the other outcome and the cheaper one to forget. Every case folded, and every
 * folded answer matched the engine's — the outer and inner variants over probes that
 * match nothing, the probe sitting exactly on a history row that tells {@code >=} from
 * {@code >}, both {@code ORDER BY} directions, and a match with no partition key. The
 * renderer was right. That is a fact this suite now holds rather than a fact anybody
 * knew: it was in exactly the position {@code boolAnd} was in, asserted only against
 * the string it was expected to produce, and the two ways that ends are indistinguishable
 * until a database is asked.
 *
 * <h2>What it costs to run</h2>
 * A Postgres container, so this is in the {@code integration} tier rather than the gate:
 * {@code ./gradlew :relix-connectors-std:integrationTest}, or {@code ./gradlew
 * verifyAll}. With no Docker daemon it skips rather than fails.
 *
 * @see PushdownAgreementTest
 * @see MySqlPushdownAgreementTest
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("A pushed-down query returns what the in-engine one returns (Postgres)")
final class PostgresPushdownAgreementTest {

    /**
     * Postgres 16 on the <b>glibc</b> image, deliberately not {@code -alpine}.
     *
     * <p>Alpine's musl stubs out locale support, so an {@code en_US.utf8} database there
     * sorts exactly as the {@code C} collation does — which would have made the string
     * ordering question disappear and this suite pass while asking nothing. The default
     * image initialises {@code en_US.utf8} and means it, which is what a deployment looks
     * like and what found the collation split.
     */
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16")
                    // Twenty, where the server's own default is a hundred, so that a
                    // connection this suite fails to release is a failure here rather
                    // than only on a busier machine. The leak this guards against —
                    // a connector per case owning a pool that was never closed — passed
                    // locally for as long as it was only run where a hundred was enough,
                    // and announced itself on a hosted runner as forty unrelated-looking
                    // assertion failures. Under twenty it reproduces in one run: 121
                    // failures with the fix reverted, none with it in place.
                    //
                    // It is deliberately close to what the suite honestly needs. If a
                    // legitimate change ever wants more, the message says exactly that
                    // and the number is one line.
                    .withCommand("postgres", "-c", "max_connections=20");

    private static PushdownAgreement agreement;

    @BeforeAll
    static void seed() throws SQLException {
        try (Connection c = open()) {
            PushdownFixture.seed(c, PushdownFixture.Flavour.POSTGRES);
        }
        agreement = new PushdownAgreement(preamble(), Dialect.POSTGRES);
    }

    @TestFactory
    @DisplayName("the whole corpus")
    Stream<DynamicNode> agreement() {
        return PushdownCorpus.tests(agreement);
    }

    /**
     * That a connection the engine hands out is at UTC, whatever zone the machine
     * running the tests is in.
     *
     * <h2>The question this answers</h2>
     * A relix {@code TIMESTAMP} is an instant the engine reads and computes at UTC. Work
     * the planner folds is computed by the <em>server</em>, in the session's time zone —
     * so a {@code date_trunc} or an {@code EXTRACT} that crossed the wire truncated a
     * different wall clock than the same operator truncated here, and the query returned
     * different rows depending on whether it folded.
     *
     * <p>On Postgres that is not a configuration mistake anyone could correct. The
     * driver sends the session's {@code TimeZone} in its startup packet, taken from the
     * <b>client JVM's default zone</b>, and a {@code TimeZone} named in the URL is
     * silently ignored — so the answers moved with the time zone of the machine the
     * engine happened to run on. It was found here rather than reasoned about: the
     * truncation cases failed on a laptop at {@code America/New_York} and would have
     * passed on one at UTC, which is the worst way for a suite to be wrong.
     *
     * <p>The fix is that the engine pins the session itself, and this is the assertion
     * that the pin reaches the server. The second half is what makes it a real check: if
     * the test machine were already at UTC, the first assertion would hold for the wrong
     * reason, so the case is skipped rather than passing vacuously.
     */
    @Test
    @DisplayName("a pooled connection is at UTC, though the client JVM is not")
    void sessionIsPinnedToUtc() throws SQLException {
        // An assumption, not an assertion. The javadoc above already says this case is
        // "skipped rather than passing vacuously" on a machine at UTC — but it was written
        // as an assertion, so on such a machine it *failed* instead, which is the one
        // outcome neither reading wants. Hosted runners are at UTC; developer laptops
        // mostly are not, which is why this stood.
        Assumptions.assumeFalse(
                TimeZone.getDefault().getID().equalsIgnoreCase("UTC")
                        || TimeZone.getDefault().getID().equalsIgnoreCase("Etc/UTC"),
                "this machine is at UTC, so nothing here distinguishes a pinned session "
                        + "from an unpinned one — run it somewhere else to check the pin");

        // What the driver would have given us, and what the engine gives us instead.
        assertThat(rawSessionZone()).isNotEqualToIgnoringCase("UTC");
        assertThat(pooledSessionZone()).isEqualToIgnoringCase("UTC");
    }

    /** The session zone on a connection opened straight from the driver. */
    private static String rawSessionZone() throws SQLException {
        try (Connection c = open()) {
            return sessionZone(c);
        }
    }

    /** The session zone on a connection borrowed the way execution borrows one. */
    private static String pooledSessionZone() throws SQLException {
        ConnectionDeclaration declaration = SemanticFixtures.model(preamble() + "query { Orders };")
                .connections().values().iterator().next();
        try (ConnectionPool pool = new ConnectionPool()) {
            Connection c = pool.borrow(declaration);
            try {
                return sessionZone(c);
            } finally {
                pool.release(declaration, c);
            }
        }
    }

    private static String sessionZone(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT current_setting('TimeZone')")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String preamble() {
        return PushdownFixture.preamble(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}

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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The session's clock governs a query over a database, not the database's clock.
 *
 * <p>A pinned {@code Clock} is what makes a query over "now" reproducible, and pushdown
 * is supposed to change where work happens and never what the answer is. Those two
 * claims are the same claim here: a folded {@code NOW()} would be evaluated by the
 * backend against a clock this session never saw, so the same script would return
 * different values depending on whether the planner happened to fold it.
 *
 * <p>The engine's answer is not to refuse the fold but to remove the question: the call
 * is evaluated once, here, against the run's own instant, and the resulting literal is
 * what travels. A database has no opinion about a timestamp, so the query folds whole and
 * the moment in it is the session's.
 */
@DisplayName("Relix — the session's clock survives pushdown")
final class SessionClockTest {

    private static final String URL = "jdbc:h2:mem:embed_session_clock;DB_CLOSE_DELAY=-1";

    private static final Instant PINNED = Instant.parse("2026-03-01T12:00:00Z");

    private static final String PREAMBLE =
            "connection db from jdbc { url: \"" + URL + "\" };\n"
            + "source Events from db {\n"
            + "    table: \"events\",\n"
            + "    schema: { id: NUMBER, label: STRING }\n"
            + "};\n";

    @BeforeAll
    static void seed() throws SQLException {
        try (Connection c = DriverManager.getConnection(URL)) {
            var st = c.createStatement();
            st.execute("DROP TABLE IF EXISTS events");
            st.execute("CREATE TABLE events (id INT, label VARCHAR(16))");
            st.execute("INSERT INTO events VALUES (1, 'first'), (2, 'second')");
        }
    }

    private static Relix pinned() {
        Relix relix = Relix.builder()
                .clock(Clock.fixed(PINNED, ZoneOffset.UTC))
                .build();
        relix.define(PREAMBLE);
        return relix;
    }

    @Test
    @DisplayName("a projected NOW() reads the pinned instant, not the backend's clock")
    void projectedNowReadsTheSessionClock() {
        try (Relix relix = pinned()) {
            List<Tuple> rows = relix.relation("π id, NOW() → t (Events)").toList();

            assertThat(rows).hasSize(2);
            assertThat(rows).allSatisfy(row -> assertThat(row.instant("t")).isEqualTo(PINNED));
        }
    }

    @Test
    @DisplayName("the whole query folds, carrying the pinned instant as a literal")
    void theWholeQueryFolds() {
        try (Relix relix = pinned()) {
            String plan = relix.relation("π id, NOW() → t (σ id > 1 (Events))").explain();

            // NOW() is gone — not because the call was left behind, but because it was
            // evaluated here and the session's instant went in its place.
            assertThat(plan).contains("PushedScan");
            assertThat(plan).doesNotContain("NOW()");
            assertThat(plan).contains("2026-03-01 12:00:00");
        }
    }

    /**
     * The predicate case, which used to cost the whole fold: a σ the renderer declined
     * could not fold at all, so the scan read the table and the filter ran here. It now
     * reaches the database as a comparison between two timestamps, one of which is the
     * session's — the same query the host program would have had to write by hand.
     */
    @Test
    @DisplayName("a predicate over NOW() is answered against the pinned instant")
    void predicateOverNowReadsTheSessionClock() {
        try (Relix relix = pinned()) {
            // TIMESTAMP '2026-03-01T13:00:00Z' is after the pinned instant and before any
            // wall-clock run of this test, so the two clocks disagree about every row.
            List<Tuple> rows = relix
                    .relation("σ NOW() < TIMESTAMP '2026-03-01T13:00:00Z' (Events)")
                    .toList();

            assertThat(rows)
                    .as("the pinned clock keeps every row; a wall clock would drop them all")
                    .hasSize(2);
        }
    }
}

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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;

/**
 * What happens to a row stream the caller never closes.
 *
 * <p>{@link Relation#stream()} hands the caller the lifecycle, which is the whole of the
 * opt-in: the stream owns the connector it reads through, and the connector holds the
 * connection it borrowed from the session's pool. Nothing released either of those if the
 * caller simply walked away — and {@code ConnectionPool.close} does not reach them, since
 * it closes what is <em>idle</em> and a borrowed connection is by definition not.
 *
 * <p>So the claims here are two: the session can say how many streams got away, and
 * closing it releases what they were holding rather than leaving it to the process.
 */
@DisplayName("Relix — a row stream the caller never closed")
final class AbandonedStreamTest {

    /** A {@link DataSource} that keeps every connection it hands out, so a test can inspect them. */
    private static final class RecordingDataSource implements DataSource {
        private final String url;
        private final List<Connection> handedOut = new ArrayList<>();

        RecordingDataSource(String url) {
            this.url = url;
        }

        @Override public synchronized Connection getConnection() throws SQLException {
            Connection c = DriverManager.getConnection(url);
            handedOut.add(c);
            return c;
        }

        synchronized List<Connection> handedOut() {
            return List.copyOf(handedOut);
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

    private static RecordingDataSource seeded(String name) throws SQLException {
        var ds = new RecordingDataSource("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        try (Connection c = DriverManager.getConnection(ds.url)) {
            var st = c.createStatement();
            st.execute("CREATE TABLE IF NOT EXISTS orders (order_id INT, amount DECIMAL(10,2))");
            st.execute("INSERT INTO orders VALUES (1, 10.00), (2, 20.00), (3, 30.00)");
        }
        return ds;
    }

    /** A session over three inline rows — enough to stream, with nothing external to hold. */
    private static Relix inlineSession() {
        Relix relix = Relix.builder().build();
        relix.table("Items",
                List.of("id", "name"),
                List.of(java.util.Map.of("id", 1, "name", "a"),
                        java.util.Map.of("id", 2, "name", "b")));
        return relix;
    }

    @Test
    @DisplayName("a session that has run nothing has no open streams")
    void nothingRunYet() {
        try (Relix relix = inlineSession()) {
            assertThat(relix.openStreams()).isZero();
        }
    }

    @Test
    @DisplayName("a collecting terminal closes what it drained")
    void collectingTerminalLeavesNothingOpen() {
        try (Relix relix = inlineSession()) {
            relix.relation("Items").toList();
            relix.relation("Items").count();
            relix.relation("Items").run();
            assertThat(relix.openStreams()).isZero();
        }
    }

    @Test
    @DisplayName("an abandoned stream is counted, and closing it clears the count")
    void abandonedThenClosed() {
        try (Relix relix = inlineSession()) {
            Stream<Tuple> rows = relix.relation("Items").stream();
            assertThat(rows.findFirst()).isPresent();

            // findFirst does not close: the stream still owns its connector.
            assertThat(relix.openStreams()).isOne();

            rows.close();
            assertThat(relix.openStreams()).isZero();

            rows.close();   // idempotent — a second close must not count twice
            assertThat(relix.openStreams()).isZero();
        }
    }

    @Test
    @DisplayName("two streams in flight are counted separately")
    void twoInFlight() {
        try (Relix relix = inlineSession()) {
            try (Stream<Tuple> a = relix.relation("Items").stream();
                 Stream<Tuple> b = relix.relation("Items").stream()) {
                assertThat(a.findFirst()).isPresent();
                assertThat(b.findFirst()).isPresent();
                assertThat(relix.openStreams()).isEqualTo(2);
            }
            assertThat(relix.openStreams()).isZero();
        }
    }

    @Test
    @DisplayName("closing the session closes the database connection an abandoned stream held")
    void sessionCloseReleasesTheConnection() throws SQLException {
        RecordingDataSource ds = seeded("embed_abandoned");
        Relix relix = Relix.builder().jdbc("warehouse", ds).build();
        try {
            Stream<Tuple> rows = relix.relation("warehouse.orders").stream();
            assertThat(rows.findFirst()).isPresent();
            assertThat(relix.openStreams()).isOne();

            // Borrowed, so the pool cannot reach it: this is the connection that used to be
            // lost for the life of the session, and beyond it for the life of the process.
            assertThat(ds.handedOut()).isNotEmpty();
        } finally {
            relix.close();
        }

        assertThat(relix.openStreams()).isZero();
        for (Connection c : ds.handedOut()) {
            assertThat(c.isClosed()).isTrue();
        }
    }
}

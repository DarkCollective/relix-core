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

import com.darkcollective.relix.semantic.CatalogProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;

/**
 * {@link Relix#cancel()} stops a query from another thread, including one waiting on a
 * database; and a result closed before its last row stops the statement behind it.
 */
@DisplayName("Cancelling a query stops the work in flight")
final class CancelTest {

    /**
     * Runs {@code query} on a worker and cancels the session until it ends.
     *
     * <p>Cancelling repeatedly rather than once, because {@code cancel()} reaches only a
     * query that has started, and the worker's start cannot be observed from here without a
     * hook into the engine. A query that ignored cancellation would run on until the
     * deadline below, which is what makes the test fail rather than hang.
     *
     * @return what the query raised
     */
    private static Throwable cancelWhileRunning(Relix relix, Runnable query) throws Exception {
        CompletableFuture<Void> running = CompletableFuture.runAsync(query);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!running.isDone() && System.nanoTime() < deadline) {
            relix.cancel();
            Thread.sleep(20);
        }
        try {
            running.get(1, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            return e.getCause();
        }
        throw new AssertionError("the query was not stopped");
    }

    private static final String NATURALS = "source Naturals from generator { name: \"Naturals\" };";

    @Nested
    @DisplayName("Relix.cancel()")
    final class SessionCancel {

        @Test
        @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
        @DisplayName("stops the engine's own work, mid-drain")
        void engineWork() throws Exception {
            try (Relix relix = Relix.open()) {
                relix.define(NATURALS);
                // A filter that never matches over an endless input: all of its work happens
                // inside one pull, which is the case a closed stream cannot reach.
                Throwable failure = cancelWhileRunning(relix, () -> {
                    try (Stream<Tuple> rows = relix.relation("σ Len(CStr(n)) = 0 (Naturals)").stream()) {
                        rows.findFirst();
                    }
                });
                assertThat(failure).isInstanceOf(QueryExecutionException.class)
                        .hasMessageContaining("query cancelled");
            }
        }

        @Test
        @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
        @DisplayName("cancels a statement the database is still executing")
        void databaseWork() throws Exception {
            Counting h2 = h2("cancel_running");
            try (Connection c = h2.getConnection(); Statement s = c.createStatement()) {
                // Effectively endless: H2 computes the whole result before executeQuery
                // returns, so the thread sits inside the driver, where an interrupt is not
                // read and no stream exists yet for anyone to close.
                s.execute("CREATE VIEW slow AS SELECT a.x AS x FROM SYSTEM_RANGE(1, 1000000) a, "
                        + "SYSTEM_RANGE(1, 1000000) b WHERE a.x + b.x < 0");
            }
            // No catalog: analysing a database table otherwise asks the database for its
            // statistics, and counting this view's rows takes as long as reading them.
            try (Relix relix = Relix.builder().jdbc("db", h2).catalog(CatalogProvider.NONE).build()) {
                relix.define("source Slow from db { table: \"SLOW\", schema: { x: NUMBER } };");
                Throwable failure = cancelWhileRunning(relix, () -> relix.relation("Slow").toList());
                assertThat(failure).isInstanceOf(QueryExecutionException.class)
                        .hasMessageContaining("query cancelled");
                assertThat(h2.cancels.get()).isPositive();
            }
        }

        @Test
        @DisplayName("leaves the session open, and a later query runs")
        void sessionStaysUsable() {
            try (Relix relix = Relix.open()) {
                relix.define(NATURALS);
                relix.cancel();
                assertThat(relix.relation("λ 3 (Naturals)")).hasRowCount(3);
            }
        }
    }

    @Nested
    @DisplayName("closing a result early")
    final class EarlyClose {

        @Test
        @DisplayName("cancels the statement behind it")
        void cancelsUnfinished() throws SQLException {
            Counting h2 = seededWithRows("cancel_early", 5000);
            try (Relix relix = Relix.builder().jdbc("db", h2).build()) {
                relix.define("source Nums from db { table: \"NUMS\", schema: { x: NUMBER } };");
                try (Stream<Tuple> rows = relix.relation("Nums").stream()) {
                    assertThat(rows.findFirst()).isPresent();
                }
                assertThat(h2.cancels.get()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("leaves a statement whose result was read to the end alone")
        void leavesFinished() throws SQLException {
            Counting h2 = seededWithRows("cancel_finished", 10);
            try (Relix relix = Relix.builder().jdbc("db", h2).build()) {
                relix.define("source Nums from db { table: \"NUMS\", schema: { x: NUMBER } };");
                assertThat(relix.relation("Nums")).hasRowCount(10);
                assertThat(h2.cancels.get()).isZero();
            }
        }
    }

    // -------------------------------------------------------------------------
    // An H2 DataSource that counts Statement.cancel()
    // -------------------------------------------------------------------------

    private static Counting h2(String name) {
        return new Counting("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    }

    private static Counting seededWithRows(String name, int rows) throws SQLException {
        Counting h2 = h2(name);
        try (Connection c = h2.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE nums AS SELECT x FROM SYSTEM_RANGE(1, " + rows + ")");
        }
        return h2;
    }

    /** Hands out H2 connections whose statements count the cancels they receive. */
    private static final class Counting implements DataSource {
        private final String url;
        final AtomicInteger cancels = new AtomicInteger();

        Counting(String url) {
            this.url = url;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection real = DriverManager.getConnection(url);
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        Object result = invoke(real, method, args);
                        return result instanceof Statement statement
                                && method.getName().equals("createStatement")
                                ? counting(statement) : result;
                    });
        }

        private Statement counting(Statement real) {
            return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(),
                    new Class<?>[]{Statement.class}, (proxy, method, args) -> {
                        if (method.getName().equals("cancel")) {
                            cancels.incrementAndGet();
                        }
                        return invoke(real, method, args);
                    });
        }

        private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
                throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        @Override public Connection getConnection(String user, String password) throws SQLException {
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

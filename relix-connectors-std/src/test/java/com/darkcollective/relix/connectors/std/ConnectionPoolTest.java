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
import com.darkcollective.relix.lang.ast.source.DatabaseConnectionConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ConnectionPool — JDBC connection reuse (H2)")
final class ConnectionPoolTest {

    private static ConnectionDeclaration config(String name) {
        return connection(name, new DatabaseConnectionConfig(
                "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1",
                Optional.empty(), Optional.empty(), Optional.empty()));
    }

    @Test
    @DisplayName("a released connection is handed back out on the next borrow")
    void reusesReleasedConnection() throws SQLException {
        ConnectionDeclaration config = config("pool_reuse");
        try (ConnectionPool pool = new ConnectionPool()) {
            Connection first = pool.borrow(config);
            pool.release(config, first);
            Connection second = pool.borrow(config);
            assertThat(second).isSameAs(first);   // reused, not reopened
            pool.release(config, second);
        }
    }

    @Test
    @DisplayName("close() closes idle connections")
    void closeClosesIdle() throws SQLException {
        ConnectionDeclaration config = config("pool_close");
        ConnectionPool pool = new ConnectionPool();
        Connection conn = pool.borrow(config);
        pool.release(config, conn);
        pool.close();
        assertThat(conn.isClosed()).isTrue();
    }

    @Test
    @DisplayName("borrowing from a closed pool fails")
    void borrowAfterCloseThrows() {
        ConnectionPool pool = new ConnectionPool();
        pool.close();
        assertThatThrownBy(() -> pool.borrow(config("pool_closed")))
                .isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("a broken connection is not pooled")
    void doesNotPoolBrokenConnection() throws SQLException {
        ConnectionDeclaration config = config("pool_broken");
        try (ConnectionPool pool = new ConnectionPool()) {
            Connection broken = pool.borrow(config);
            broken.close();                 // break it before releasing
            pool.release(config, broken);   // must discard, not pool
            Connection fresh = pool.borrow(config);
            assertThat(fresh).isNotSameAs(broken);
            assertThat(fresh.isClosed()).isFalse();
            pool.release(config, fresh);
        }
    }

    @Test
    @DisplayName("the per-config idle bound is respected; excess is closed")
    void respectsIdleBound() throws SQLException {
        ConnectionDeclaration config = config("pool_bound");
        try (ConnectionPool pool = new ConnectionPool(1)) {
            Connection a = pool.borrow(config);
            Connection b = pool.borrow(config);
            assertThat(b).isNotSameAs(a);
            pool.release(config, a);   // pooled (idle = 1)
            pool.release(config, b);   // bound reached → b closed
            assertThat(b.isClosed()).isTrue();
            Connection c = pool.borrow(config);
            assertThat(c).isSameAs(a);  // the pooled one
            pool.release(config, c);
        }
    }

    @Test
    @DisplayName("releasing null is a no-op")
    void releaseNullIsNoOp() {
        try (ConnectionPool pool = new ConnectionPool()) {
            pool.release(config("pool_null"), null);   // no exception
        }
    }

    @Test
    @DisplayName("a non-positive idle bound is rejected")
    void rejectsNonPositiveBound() {
        assertThatThrownBy(() -> new ConnectionPool(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

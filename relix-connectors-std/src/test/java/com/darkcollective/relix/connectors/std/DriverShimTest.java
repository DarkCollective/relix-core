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

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link DriverShim} — every {@link Driver} method must delegate to
 * the wrapped instance, forwarding arguments and returning the delegate's result.
 */
final class DriverShimTest {

    /** A recording {@link Driver} stub: captures the last call and returns sentinels. */
    private static final class RecordingDriver implements Driver {
        String lastUrl;
        Properties lastInfo;
        final Connection connection = (Connection) java.lang.reflect.Proxy.newProxyInstance(
                RecordingDriver.class.getClassLoader(), new Class<?>[]{Connection.class},
                (p, m, a) -> m.getName().equals("toString") ? "conn" : null);
        final DriverPropertyInfo[] propertyInfo = new DriverPropertyInfo[]{
                new DriverPropertyInfo("k", "v")};
        final Logger logger = Logger.getLogger("driver-shim-test");

        @Override public Connection connect(String url, Properties info) {
            this.lastUrl = url;
            this.lastInfo = info;
            return connection;
        }
        @Override public boolean acceptsURL(String url) {
            this.lastUrl = url;
            return true;
        }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            this.lastUrl = url;
            this.lastInfo = info;
            return propertyInfo;
        }
        @Override public int getMajorVersion() { return 7; }
        @Override public int getMinorVersion() { return 3; }
        @Override public boolean jdbcCompliant() { return true; }
        @Override public Logger getParentLogger() { return logger; }
    }

    private final RecordingDriver delegate = new RecordingDriver();
    private final DriverShim shim = new DriverShim(delegate);

    @Test
    void rejectsNullDelegate() {
        assertThatNullPointerException().isThrownBy(() -> new DriverShim(null));
    }

    @Test
    void connectDelegatesAndForwardsArguments() throws SQLException {
        Properties info = new Properties();
        info.setProperty("user", "alice");

        Connection result = shim.connect("jdbc:test://host/db", info);

        assertThat(result).isSameAs(delegate.connection);
        assertThat(delegate.lastUrl).isEqualTo("jdbc:test://host/db");
        assertThat(delegate.lastInfo).isSameAs(info);
    }

    @Test
    void acceptsUrlDelegates() throws SQLException {
        assertThat(shim.acceptsURL("jdbc:test://x")).isTrue();
        assertThat(delegate.lastUrl).isEqualTo("jdbc:test://x");
    }

    @Test
    void getPropertyInfoDelegates() throws SQLException {
        Properties info = new Properties();
        DriverPropertyInfo[] result = shim.getPropertyInfo("jdbc:test://y", info);

        assertThat(result).isSameAs(delegate.propertyInfo);
        assertThat(delegate.lastUrl).isEqualTo("jdbc:test://y");
        assertThat(delegate.lastInfo).isSameAs(info);
    }

    @Test
    void versionAndComplianceDelegate() {
        assertThat(shim.getMajorVersion()).isEqualTo(7);
        assertThat(shim.getMinorVersion()).isEqualTo(3);
        assertThat(shim.jdbcCompliant()).isTrue();
    }

    @Test
    void getParentLoggerDelegates() throws SQLException {
        assertThat(shim.getParentLogger()).isSameAs(delegate.logger);
    }

    @Test
    void delegateClassReportsTheWrappedType() {
        assertThat(shim.delegateClass()).isEqualTo(RecordingDriver.class);
    }
}

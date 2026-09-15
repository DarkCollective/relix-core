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

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Wrapper that lets a JDBC {@link Driver} loaded by a non-system class loader be
 * registered with {@link java.sql.DriverManager}.
 *
 * <p>{@code DriverManager} enforces a security check: a registered driver is only
 * usable from a caller whose class loader can load the driver's class.  A driver
 * loaded from an external JAR through a {@link RelixDriverLoader}'s
 * {@link java.net.URLClassLoader} fails that check, because the application code
 * calling {@code DriverManager.getConnection} was loaded by a different (the
 * system/application) class loader.
 *
 * <p>The canonical fix — used by DBeaver, SQuirreL SQL, DataGrip, and documented
 * for two decades — is to register a thin shim that <em>is</em> loaded by the
 * application class loader and delegates every {@link Driver} call to the real,
 * externally-loaded instance.  {@code DriverManager}'s check then passes against
 * the shim, while the actual work is done by the delegate.
 *
 * <p>Package-private: only {@link RelixDriverLoader} constructs these.
 */
final class DriverShim implements Driver {

    private final Driver delegate;

    DriverShim(Driver delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** The wrapped driver's implementation class — used for logging/description. */
    Class<?> delegateClass() {
        return delegate.getClass();
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        return delegate.connect(url, info);
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return delegate.acceptsURL(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return delegate.getPropertyInfo(url, info);
    }

    @Override
    public int getMajorVersion() {
        return delegate.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
        return delegate.getMinorVersion();
    }

    @Override
    public boolean jdbcCompliant() {
        return delegate.jdbcCompliant();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }
}

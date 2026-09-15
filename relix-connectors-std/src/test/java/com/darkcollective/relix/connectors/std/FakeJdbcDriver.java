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
import java.util.Properties;
import java.util.logging.Logger;

/**
 * A stand-in JDBC driver used by {@link DriverProvisionerTest}: its compiled class
 * is packaged into a JAR (with a {@code META-INF/services/java.sql.Driver} entry),
 * downloaded through the provisioner, and registered — exercising the full
 * download → load → register → resolve path.  It accepts {@code jdbc:faketest:}
 * URLs and nothing else.
 *
 * <p>Must reference only {@code java.sql} types (no relix/test-classpath classes),
 * because {@link RelixDriverLoader} loads it under the platform class loader.
 */
public final class FakeJdbcDriver implements Driver {

    public FakeJdbcDriver() {
    }

    @Override
    public Connection connect(String url, Properties info) {
        return null;   // never actually connected in the test
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith("jdbc:faketest:");
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger("faketest");
    }
}

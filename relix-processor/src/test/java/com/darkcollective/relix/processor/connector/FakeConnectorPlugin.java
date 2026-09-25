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
package com.darkcollective.relix.processor.connector;

import com.darkcollective.relix.processor.connector.internal.ConnectorPluginLoader;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.symbol.Schema;

import java.util.Set;
import java.util.stream.Stream;

/**
 * A stand-in external connector used by {@link ConnectorPluginLoaderTest}: its
 * compiled class file is packaged into a JAR (with a {@code META-INF/services}
 * entry) and loaded through {@link ConnectorPluginLoader} to exercise external
 * plugin discovery. Public with a no-arg constructor, as the SPI requires.
 */
public final class FakeConnectorPlugin implements RelixConnector {

    public FakeConnectorPlugin() {
    }

    @Override
    public Set<String> handles() {
        return Set.of("fake");
    }

    @Override
    public Stream<Row> open(ConnectorConfig config, String table, Schema schema) {
        return Stream.empty();
    }
}

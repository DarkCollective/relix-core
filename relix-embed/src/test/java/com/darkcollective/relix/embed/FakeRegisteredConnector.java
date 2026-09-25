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

import com.darkcollective.relix.processor.internal.ArrayRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.connector.ConnectorConfig;
import com.darkcollective.relix.processor.connector.RelixConnector;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A {@link RelixConnector} that records how it was called, registered under a token no
 * real connector claims.
 *
 * <p>{@code CompositeDataSourceConnector}'s non-JDBC routing — the path a MongoDB scan and
 * a pushed MongoDB pipeline both take — had no test at all, and could not easily get one:
 * the constructor builds its registry from {@code ConnectorRegistry.create()}, which scans
 * the machine's {@code ~/.relix/connectors/} directory as well as the service loader. A
 * test that installed a real plugin there would be testing the developer's filesystem.
 *
 * <p>Handing it to the constructor as a supplied connector instead makes the fake
 * reachable without touching that directory, and — unlike a {@code META-INF/services}
 * registration — without becoming visible to every other test in the module. The
 * {@code "faketype"} token no real connector claims.
 */
final class FakeRegisteredConnector implements RelixConnector {

    /** The type token this connector claims; no real connector uses it. */
    public static final String TYPE = "faketype";

    /** What the last {@code open} call was given, or {@code null} if there was none. */
    public static volatile Call lastOpen;

    /** What the last {@code openQuery} call was given, or {@code null} if there was none. */
    public static volatile Call lastQuery;

    /** One recorded invocation. */
    public record Call(ConnectorConfig config, String argument, Schema schema) {}

    /** Forgets both recorded calls, so a test starts from a known state. */
    public static void reset() {
        lastOpen = null;
        lastQuery = null;
    }

    @Override
    public Set<String> handles() {
        return Set.of(TYPE);
    }

    @Override
    public Stream<Row> open(ConnectorConfig config, String table, Schema schema) {
        lastOpen = new Call(config, table, schema);
        return Stream.of(row(schema, "opened:" + table));
    }

    @Override
    public Stream<Row> openQuery(ConnectorConfig config, String query, Schema schema) {
        lastQuery = new Call(config, query, schema);
        return Stream.of(row(schema, "queried:" + query));
    }

    /** One row whose first column carries {@code marker}; any other column is empty. */
    private static Row row(Schema schema, String marker) {
        List<Value> values = new ArrayList<>(schema.width());
        for (int i = 0; i < schema.width(); i++) {
            values.add(new StringValue(i == 0 ? marker : ""));
        }
        return ArrayRow.of(schema, values);
    }
}

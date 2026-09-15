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

import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Tests for the {@link RelixConnector} default methods — the capability defaults a
 * minimal connector inherits without overriding.
 */
final class RelixConnectorTest {

    /** A connector that overrides only the two required methods. */
    private static final class MinimalConnector implements RelixConnector {
        @Override public Set<String> handles() { return Set.of("minimal"); }
        @Override public Stream<Row> open(ConnectorConfig config, String table, Schema schema) {
            return Stream.empty();
        }
    }

    private final RelixConnector connector = new MinimalConnector();

    @Test
    void tableSchemaDefaultsToUnavailable() {
        assertThat(connector.tableSchema(ConnectorConfig.empty(), "t")).isEmpty();
    }

    @Test
    void tableStatisticsDefaultsToUnavailable() {
        assertThat(connector.tableStatistics(ConnectorConfig.empty(), "t")).isEmpty();
    }

    @Test
    void openQueryDefaultsToUnsupported() {
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> connector.openQuery(ConnectorConfig.empty(), "SELECT 1", Schema.open()))
                .withMessageContaining("MinimalConnector")
                .withMessageContaining("pushdown");
    }

    @Test
    void closeDefaultsToNoOp() {
        assertDoesNotThrow(connector::close);
    }
}

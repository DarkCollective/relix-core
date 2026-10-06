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
package com.darkcollective.relix.connectors.std.internal;

import com.darkcollective.relix.connectors.std.internal.StatisticsEstimates.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StatisticsEstimates — which catalogue a database keeps its estimates in")
final class StatisticsEstimatesTest {

    @Test
    @DisplayName("the driver's product name picks the catalogue, and anything else reads metadata")
    void sourceByProduct() {
        assertThat(Source.of("PostgreSQL")).isEqualTo(Source.POSTGRES);
        assertThat(Source.of("MySQL")).isEqualTo(Source.MYSQL);
        assertThat(Source.of("MariaDB")).isEqualTo(Source.MYSQL);
        assertThat(Source.of("H2")).isEqualTo(Source.H2);
        assertThat(Source.of("Microsoft SQL Server")).isEqualTo(Source.SQLSERVER);
        assertThat(Source.of("DB2/LINUXX8664")).isEqualTo(Source.DB2);
        assertThat(Source.of("DuckDB")).isEqualTo(Source.DUCKDB);
        assertThat(Source.of("SQLite")).isEqualTo(Source.METADATA);
        assertThat(Source.of(null)).isEqualTo(Source.METADATA);
    }

    @Test
    @DisplayName("a table the catalogue does not know has no estimate")
    void unknownTable() throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:est_unknown")) {
            assertThat(StatisticsEstimates.rows(c, "NO_SUCH_TABLE")).isEmpty();
            assertThat(StatisticsEstimates.columns(c, "NO_SUCH_TABLE", OptionalLong.of(5))).isEmpty();
        }
    }
}

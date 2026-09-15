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
package com.darkcollective.relix.semantic;

import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.symbol.RelationStatistics;
import com.darkcollective.relix.symbol.Schema;

import java.util.Optional;

/**
 * Supplies table metadata for database connections during semantic analysis.
 *
 * <p>This is the seam through which the analyzer learns the schema of a table
 * referenced by a connection (e.g. {@code sales.orders}) when no schema was
 * declared in the script.  A real implementation introspects the live database;
 * the default {@link #NONE} resolves nothing, so dotted references without a
 * declared schema produce a clear "schema unavailable" error in offline use.
 *
 * <p>The seam also supplies optional {@link RelationStatistics} (row counts,
 * keys) for the cost model via {@link #tableStatistics}; the default returns
 * {@link Optional#empty()}, so a schema-only provider needs no extra work.
 */
@FunctionalInterface
public interface CatalogProvider {

    /**
     * Returns the output schema of {@code table} within {@code connection}, or
     * {@link Optional#empty()} if it cannot be determined.
     *
     * @param connection the declared connection the table belongs to
     * @param table      the (possibly schema-qualified) remote table name
     * @return the table's schema, or empty if unavailable
     */
    Optional<Schema> tableSchema(ConnectionDeclaration connection, String table);

    /**
     * Returns optimizer statistics (row count, keys) for {@code table} within
     * {@code connection}, or {@link Optional#empty()} if none are available.
     *
     * <p>The default returns empty; introspecting providers override this to query
     * the live catalog.  Statistics are advisory: a missing or partial value never
     * affects correctness, only the quality of cost-based decisions.
     *
     * @param connection the declared connection the table belongs to
     * @param table      the (possibly schema-qualified) remote table name
     * @return the table's statistics, or empty if unavailable
     */
    default Optional<RelationStatistics> tableStatistics(
            ConnectionDeclaration connection, String table) {
        return Optional.empty();
    }

    /** A provider that resolves nothing — the default for offline analysis. */
    CatalogProvider NONE = (connection, table) -> Optional.empty();
}

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

import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ColumnStatistics;
import com.darkcollective.relix.symbol.RelationStatistics;
import com.darkcollective.relix.symbol.relation.InlineRelationSymbol;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Derives <em>exact</em> {@link RelationStatistics} for an
 * {@link InlineRelationSymbol} directly from its in-memory rows.
 *
 * <p>Unlike a database catalog, which hands back stale <em>estimates</em>, an
 * inline relation carries its full dataset in the symbol layer at analysis time,
 * so every figure here is ground truth computed in a single pass with no I/O:
 *
 * <ul>
 *   <li><b>Row count</b> — the exact number of declared rows.</li>
 *   <li><b>Per-column distinct count</b> — the number of distinct non-NULL
 *       {@link Operand} values in the column.  A NULL cell is stored as an absent
 *       map key (see {@code SymbolCollector.rowsFromInlineTable}), so an absent
 *       value is a NULL, never a distinct value.</li>
 *   <li><b>Per-column null count</b> — the number of rows whose value is absent.</li>
 *   <li><b>Single-column candidate keys</b> — every column whose values are all
 *       present (no NULL) and pairwise distinct uniquely identifies a row, exactly
 *       as a SQL {@code PRIMARY KEY} column would.  Multi-column keys are not
 *       enumerated (the search is exponential and the single-column case captures
 *       the common {@code id} key).</li>
 * </ul>
 *
 * <p>This is the offline counterpart to {@code JdbcCatalogProvider.tableStatistics}:
 * it lets the cost model size inline relations precisely without any connection,
 * and lets a {@code relix.statistics} catalog be non-empty in fully offline scripts.
 */
final class InlineStatistics {

    private InlineStatistics() {
    }

    /**
     * Computes exact statistics for {@code relation} from its rows and schema.
     *
     * @param relation the inline relation whose rows to summarise; must not be null
     * @return ground-truth statistics — row count, per-column distinct/null counts,
     *         and any single-column candidate keys
     */
    static RelationStatistics of(InlineRelationSymbol relation) {
        List<Map<String, Operand>> rows = relation.rows();
        long rowCount = rows.size();

        Map<String, ColumnStatistics> columnStats = new LinkedHashMap<>();
        List<List<String>> keys = new ArrayList<>();

        for (ColumnDefinition column : relation.schema().columns()) {
            String name = column.name();
            Set<Object> distinct = new HashSet<>();
            long nulls = 0L;
            for (Map<String, Operand> row : rows) {
                Operand value = row.get(name);
                if (value == null) {
                    nulls++;            // absent key == NULL cell
                } else {
                    distinct.add(cellIdentity(value));
                }
            }
            columnStats.put(name, new ColumnStatistics(
                    OptionalLong.of(distinct.size()), OptionalLong.of(nulls)));

            // A column with no NULLs whose values are all distinct is a candidate
            // key (the rowCount > 0 guard avoids calling an empty relation keyed).
            if (rowCount > 0 && nulls == 0 && distinct.size() == rowCount) {
                keys.add(List.of(name));
            }
        }

        return new RelationStatistics(OptionalLong.of(rowCount), columnStats, keys);
    }

    /**
     * What makes two cells the same value, as the engine will decide it once the rows
     * are materialised.
     *
     * <p>Counted on the literals themselves this was a claim about <em>spelling</em>: a
     * column holding {@code 5} and {@code 5.0} looked to have two distinct values and no
     * NULLs, so it was published as a candidate key, the relation was believed
     * duplicate-free, and {@code DIST-001} removed a δ that was doing real work — the
     * query then returned the same number twice. Every other literal kind already carries
     * a parsed value whose record equality is value equality; a number carries its text,
     * so it is the one that has to be normalised here.
     *
     * @param cell the literal in the inline table
     * @return a key equal for cells that will become equal values
     */
    private static Object cellIdentity(Operand cell) {
        return cell instanceof NumberOperand number
                ? new BigDecimal(number.value()).stripTrailingZeros()
                : cell;
    }
}

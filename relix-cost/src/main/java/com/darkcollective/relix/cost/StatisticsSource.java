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
package com.darkcollective.relix.cost;

import com.darkcollective.relix.symbol.RelationStatistics;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Supplies {@link RelationStatistics} for a leaf relation by name, the seam
 * through which the {@link CostEstimator} obtains cardinality metadata.
 *
 * <p>Implementations decide how a relation name maps to statistics (typically by
 * resolving the name to a symbol and consulting a statistics map collected during
 * semantic analysis).  The default {@link #NONE} resolves nothing, which makes the
 * estimator fall back to its tier-based heuristics.
 */
@FunctionalInterface
public interface StatisticsSource {

    /**
     * Returns the statistics for the relation named {@code relationName}, or
     * {@link Optional#empty()} if none are available.
     *
     * @param relationName the relation name as it appears in the expression tree
     * @return the statistics, or empty if unknown
     */
    Optional<RelationStatistics> forRelation(String relationName);

    /** A source that knows nothing about any relation. */
    StatisticsSource NONE = relationName -> Optional.empty();

    /**
     * Creates a source that resolves an expression-tree relation name through
     * {@code symbols} and looks the resulting canonical name up in {@code statistics}
     * — the standard resolution for statistics collected during semantic analysis
     * (as in {@code SemanticModel.statistics()}), which are keyed by canonical name
     * while a {@code RelationNode} carries the name as written.
     *
     * <p>Shared by every consumer of analysis-time statistics — the planner, the
     * optimizer's cost estimator, and {@link StatisticsDistinctnessSource} — so the
     * name-to-statistics mapping has exactly one definition.
     *
     * @param symbols    the symbol table resolving names to symbols; must not be null
     * @param statistics canonical relation name → statistics; must not be null
     * @return a source over {@code statistics}; never null
     */
    static StatisticsSource of(SymbolTable symbols,
                               Map<String, RelationStatistics> statistics) {
        Objects.requireNonNull(symbols, "symbols");
        Objects.requireNonNull(statistics, "statistics");
        return relationName -> symbols.lookupRelation(relationName)
                .flatMap(sym -> Optional.ofNullable(statistics.get(sym.canonicalName())));
    }
}

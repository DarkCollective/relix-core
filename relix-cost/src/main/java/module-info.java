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
/**
 * Cost estimation for relix relational algebra trees.
 *
 * <p>This module provides a lightweight cost model that maps a
 * {@link com.darkcollective.relix.ast.RelNode} expression — resolved against a
 * {@link com.darkcollective.relix.symbol.table.SymbolTable} — to either a coarse
 * {@link com.darkcollective.relix.cost.CostTier} or, when leaf relations carry
 * {@link com.darkcollective.relix.symbol.RelationStatistics} supplied through a
 * {@link com.darkcollective.relix.cost.StatisticsSource}, a numeric row-count
 * estimate.  It sits below both the query optimizer (which uses it for join-input
 * reordering) and the execution engine (which uses it to choose the build side of
 * a hash join), so neither needs to depend on the other.
 */
module com.darkcollective.relix.cost {
    requires transitive com.darkcollective.relix.ast;
    requires transitive com.darkcollective.relix.symbol;

    exports com.darkcollective.relix.cost;
}

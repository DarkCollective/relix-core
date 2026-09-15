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
package com.darkcollective.relix.ast;

import com.darkcollective.relix.ast.visitor.RelNodeVisitor;

import java.util.List;
import java.util.Objects;

/**
 * Represents an aggregation operation (γ) in relational algebra.
 * Groups tuples by the {@link GroupingKey grouping keys} and applies aggregate
 * functions.
 *
 * <p>A grouping key is a full {@link Operand} expression, not merely a column
 * reference, so a group may be formed over a derived value — e.g.
 * {@code γ YEAR(occurred_at) → yr, COUNT(id) (Events)} — with the common case
 * {@code γ customer_id, SUM(amount) (Orders)} simply grouping over a bare column.
 *
 * @param groupingKeys the keys to group by
 * @param aggregates   the aggregate functions to apply
 * @param input        the source relation
 * @param location     the source location of this node; never null
 */
public record AggregationNode(
        List<GroupingKey> groupingKeys,
        List<AggregateFunction> aggregates,
        RelNode input,
        SourceLocation location
) implements RelNode {
    public AggregationNode {
        Objects.requireNonNull(location, "location");
    }

    /**
     * Convenience constructor grouping by bare column names — the classic form,
     * used throughout call sites and tests that group by plain columns. Uses
     * {@link SourceLocation#UNKNOWN}. A {@code null} column list is preserved as a
     * null grouping (ungrouped aggregation).
     */
    public AggregationNode(List<String> groupingColumns,
                           List<AggregateFunction> aggregates,
                           RelNode input) {
        this(groupingColumns == null ? null : GroupingKey.columns(groupingColumns),
                aggregates, input, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

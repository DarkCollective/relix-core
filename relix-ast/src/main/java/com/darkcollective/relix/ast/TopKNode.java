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
import java.util.Optional;

/**
 * Top-k per group (TOP) — partitions {@code input} by {@code groupingAttributes}
 * and, within each group, keeps the {@code count} highest rows by
 * {@code sortSpecs} (after skipping {@code offset} rows).
 *
 * <p>This is the "N highest rows per group" operation — <em>top 3 orders by
 * amount per customer</em> — that SQL forces into a
 * {@code ROW_NUMBER() OVER (PARTITION BY … ORDER BY …) ≤ k} plus an outer filter,
 * or a lateral join.  Unlike a global {@link LimitNode} it cannot push down and
 * buffers each group; unlike an {@link AggregationNode} it returns whole rows.
 *
 * <p>The output schema is the input schema (it is a windowed filter — a row
 * subset).  At least one sort specification is required.
 *
 * <p>The surface syntax always names a partition key ({@code TOP … PER …}), but the
 * grouping list may be <strong>empty</strong>: that is the <em>global</em> top-N —
 * one group containing every row — which the optimizer produces by fusing a
 * {@code λ} over a {@code τ} ({@code LIM-003}).  It has no spelling of its own
 * because {@code λ}/{@code τ} already is one.
 *
 * <p>Example: {@code TOP 3 amount DESC PER customer_id (Orders)}.
 *
 * @param groupingAttributes the partition keys; empty means one global group
 * @param sortSpecs          within-group ordering; at least one required
 * @param offset             rows to skip within each group before taking {@code count}
 * @param count              number of rows to keep per group; non-negative
 * @param input              the source relation; never null
 * @param location           the source location of this node; never null
 */
public record TopKNode(
        List<String> groupingAttributes,
        List<SortSpecification> sortSpecs,
        Optional<Long> offset,
        long count,
        RelNode input,
        SourceLocation location
) implements RelNode {
    public TopKNode {
        Objects.requireNonNull(groupingAttributes, "groupingAttributes");
        Objects.requireNonNull(sortSpecs, "sortSpecs");
        Objects.requireNonNull(offset, "offset");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(location, "location");
        if (count < 0) {
            throw new IllegalArgumentException("Count cannot be negative");
        }
        if (offset.isPresent() && offset.get() < 0) {
            throw new IllegalArgumentException("Offset cannot be negative");
        }
        groupingAttributes = List.copyOf(groupingAttributes);
        sortSpecs = List.copyOf(sortSpecs);
    }

    /** Convenience constructor for tests; no offset, {@link SourceLocation#UNKNOWN}. */
    public TopKNode(List<String> groupingAttributes, List<SortSpecification> sortSpecs,
                    long count, RelNode input) {
        this(groupingAttributes, sortSpecs, Optional.empty(), count, input, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

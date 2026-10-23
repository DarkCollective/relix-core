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
 * and, within each group, keeps the first {@code count} rows in {@code sortSpecs}
 * order (after skipping {@code offset} rows). A key with no direction is ascending,
 * so {@code TOP 1 x} keeps the row with the lowest {@code x}.
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
 * <p>{@code withTies} keeps, past the {@code count}-th row of a group, every further row
 * that ties with it on all the sort keys, as SQL's {@code WITH TIES} does: rows the
 * order cannot tell apart are kept or dropped together. A tie is equality under the
 * sort's own comparison, so two NULLs tie and {@code 5} ties with {@code 5.0}.
 * {@code TOP 2 WITH TIES} over scores {@code 9, 8, 8, 7} keeps three rows.
 *
 * <p>Example: {@code TOP 3 amount DESC PER customer_id (Orders)}.
 *
 * @param groupingAttributes the partition keys; empty means one global group
 * @param sortSpecs          within-group ordering; at least one required
 * @param offset             rows to skip within each group before taking {@code count}
 * @param count              number of rows to keep per group; non-negative
 * @param withTies           whether rows tied with the last one kept are kept too; needs a sort key
 * @param leadingDirectionImplied whether the first sort key was written with neither
 *                           {@code ASC} nor {@code DESC}, so that it is ascending by
 *                           default rather than by request; validation warns about it,
 *                           since "top" reads as the highest
 * @param input              the source relation; never null
 * @param location           the source location of this node; never null
 */
public record TopKNode(
        List<String> groupingAttributes,
        List<SortSpecification> sortSpecs,
        Optional<Long> offset,
        long count,
        boolean withTies,
        boolean leadingDirectionImplied,
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
        if (withTies && sortSpecs.isEmpty()) {
            throw new IllegalArgumentException("WITH TIES needs a sort key to decide what ties");
        }
        if (leadingDirectionImplied
                && (sortSpecs.isEmpty() || sortSpecs.get(0).direction() != SortDirection.ASC)) {
            throw new IllegalArgumentException("Only an ascending first sort key can have its direction implied");
        }
        groupingAttributes = List.copyOf(groupingAttributes);
        sortSpecs = List.copyOf(sortSpecs);
    }

    /**
     * A top-k whose sort directions are all taken as requested, as one the optimizer
     * or a builder makes rather than one a person wrote.
     *
     * @param groupingAttributes the partition keys; empty means one global group
     * @param sortSpecs          within-group ordering; at least one required
     * @param offset             rows to skip within each group before taking {@code count}
     * @param count              number of rows to keep per group; non-negative
     * @param withTies           whether rows tied with the last one kept are kept too; needs a sort key
     * @param input              the source relation; never null
     * @param location           the source location of this node; never null
     */
    public TopKNode(List<String> groupingAttributes, List<SortSpecification> sortSpecs,
                    Optional<Long> offset, long count, boolean withTies, RelNode input,
                    SourceLocation location) {
        this(groupingAttributes, sortSpecs, offset, count, withTies, false, input, location);
    }

    /**
     * A top-k that cuts ties wherever the count falls.
     *
     * @param groupingAttributes the partition keys; empty means one global group
     * @param sortSpecs          within-group ordering; at least one required
     * @param offset             rows to skip within each group before taking {@code count}
     * @param count              number of rows to keep per group; non-negative
     * @param input              the source relation; never null
     * @param location           the source location of this node; never null
     */
    public TopKNode(List<String> groupingAttributes, List<SortSpecification> sortSpecs,
                    Optional<Long> offset, long count, RelNode input, SourceLocation location) {
        this(groupingAttributes, sortSpecs, offset, count, false, input, location);
    }

    /**
     * This node over {@code input}, every other component kept.
     *
     * @param input the new input; never null
     * @return the node
     * @since 1.0
     */
    public TopKNode withInput(RelNode input) {
        return new TopKNode(groupingAttributes, sortSpecs, offset, count, withTies, leadingDirectionImplied,
                input, location);
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

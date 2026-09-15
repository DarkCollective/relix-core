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

import java.util.Objects;

/**
 * Connected-components labelling of an undirected graph — the entity-resolution /
 * network-island operator.
 *
 * <p>The {@link #input()} relation is read as a set of <em>undirected</em> edges
 * over two columns, {@link #fromColumn()} and {@link #toColumn()} (the edge is
 * treated symmetrically — {@code from ↔ to}). The operator partitions the graph's
 * nodes into maximal connected components and emits one row per distinct node,
 * carrying the node together with the id of the component it belongs to. The
 * result is therefore the relational equivalent of an undirected
 * union-find / connected-components pass — what otherwise requires a hand-rolled
 * recursive self-join.
 *
 * <p>Output is a binary relation: the node-identifier column (keeping the
 * {@link #fromColumn()} name and type) and the component-label column
 * ({@link #labelColumn()}, type {@code NUMBER}). Component labels are
 * <em>canonical</em>: each component is identified by a dense, 1-based integer
 * assigned in ascending order of the component's minimum node id, so a given
 * graph always produces the same labels regardless of input row order.
 *
 * <p>Evaluation is an in-engine union-find over the whole edge set — a blocking
 * operator (it must see every edge before any node can be labelled), so it never
 * pushes down to a source and is subject to the boundedness check over unbounded
 * inputs.
 *
 * <p>Surface syntax: {@code CLUSTER from, to AS label (Edges)}.
 *
 * @param input       the edge relation; must not be null
 * @param fromColumn  the first edge-endpoint column; also names the output node column; must not be blank
 * @param toColumn    the second edge-endpoint column; must not be blank
 * @param labelColumn the name of the appended component-label column; must not be blank
 * @param location    the source location of this node; never null
 */
public record ClusterNode(RelNode input, String fromColumn, String toColumn,
                          String labelColumn, SourceLocation location)
        implements RelNode {

    public ClusterNode {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(fromColumn, "fromColumn");
        if (fromColumn.isBlank()) {
            throw new IllegalArgumentException("Cluster fromColumn must not be blank");
        }
        Objects.requireNonNull(toColumn, "toColumn");
        if (toColumn.isBlank()) {
            throw new IllegalArgumentException("Cluster toColumn must not be blank");
        }
        Objects.requireNonNull(labelColumn, "labelColumn");
        if (labelColumn.isBlank()) {
            throw new IllegalArgumentException("Cluster labelColumn must not be blank");
        }
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests: {@link SourceLocation#UNKNOWN}. */
    public ClusterNode(RelNode input, String fromColumn, String toColumn, String labelColumn) {
        this(input, fromColumn, toColumn, labelColumn, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

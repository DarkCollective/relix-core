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
 * Bounded variable-length path reachability — the RA-native graph-traversal
 * operator.
 *
 * <p>The {@link #input()} relation is read as a set of <em>directed</em> edges over
 * two columns, {@link #fromColumn()} and {@link #toColumn()}. The operator emits one
 * row {@code (a, b, d)} for every pair connected by a directed path whose length
 * {@code d} lies within the inclusive hop window {@code [minHops, maxHops]}, where
 * {@code d} is the <em>shortest</em> such length. It is therefore the bounded sibling
 * of {@link ClosureNode} (unbounded transitive closure) that also reports the hop
 * distance — the "everything within N hops" traversal investigators and analysts
 * need, which SQL expresses only with a {@code WITH RECURSIVE} CTE plus a depth guard.
 *
 * <p>The start node is <em>not</em> baked into the operator: scope a traversal to a
 * particular origin by composing an ordinary {@code σ} on the output (RA-native), e.g.
 * {@code σ from = 1001 (PATH …)}.
 *
 * <p>Output is a ternary relation: the source-endpoint column (keeping the
 * {@link #fromColumn()} name and type), the target-endpoint column (keeping the
 * {@link #toColumn()} name and type), and the hop-distance column
 * ({@link #depthColumn()}, type {@code NUMBER}). Because {@code d} is the minimal
 * length, {@code (from, to)} is a candidate key of the result (no pair appears at two
 * depths).
 *
 * <p>Evaluation is an in-engine bounded breadth-first traversal over the whole edge
 * set — a blocking operator (it must see every edge), so it never pushes down to a
 * source and is subject to the boundedness check over unbounded inputs. Null endpoints
 * are skipped; cyclic graphs terminate because the window caps the path length.
 *
 * <p>Surface syntax: {@code PATH from, to HOPS m..n AS depth (Edges)}.
 *
 * @param input       the edge relation; must not be null
 * @param fromColumn  the source-endpoint column; also names the output source column; must not be blank
 * @param toColumn    the target-endpoint column; must not be blank
 * @param minHops     the inclusive lower bound of the hop window; must be {@code >= 1}
 * @param maxHops     the inclusive upper bound of the hop window; must be {@code >= minHops}
 * @param depthColumn the name of the appended hop-distance column; must not be blank
 * @param location    the source location of this node; never null
 */
public record PathNode(RelNode input, String fromColumn, String toColumn,
                       int minHops, int maxHops, String depthColumn,
                       SourceLocation location)
        implements RelNode {

    public PathNode {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(fromColumn, "fromColumn");
        if (fromColumn.isBlank()) {
            throw new IllegalArgumentException("Path fromColumn must not be blank");
        }
        Objects.requireNonNull(toColumn, "toColumn");
        if (toColumn.isBlank()) {
            throw new IllegalArgumentException("Path toColumn must not be blank");
        }
        if (minHops < 1) {
            throw new IllegalArgumentException("Path minHops must be >= 1, got " + minHops);
        }
        if (maxHops < minHops) {
            throw new IllegalArgumentException(
                    "Path maxHops (" + maxHops + ") must be >= minHops (" + minHops + ")");
        }
        Objects.requireNonNull(depthColumn, "depthColumn");
        if (depthColumn.isBlank()) {
            throw new IllegalArgumentException("Path depthColumn must not be blank");
        }
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests: {@link SourceLocation#UNKNOWN}. */
    public PathNode(RelNode input, String fromColumn, String toColumn,
                    int minHops, int maxHops, String depthColumn) {
        this(input, fromColumn, toColumn, minHops, maxHops, depthColumn, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

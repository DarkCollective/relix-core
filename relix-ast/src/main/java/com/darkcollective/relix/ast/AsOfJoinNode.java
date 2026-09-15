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
import java.util.Optional;

/**
 * AS-OF join ({@code ASOF}) — a temporal "pick the nearest right row by time"
 * join.
 *
 * <p>For each left (probe) row, among the right rows that agree on the
 * <em>equality</em> conjuncts of {@code condition} (the partition keys) and
 * satisfy its single <em>ordering inequality</em> (the match column), the join
 * emits the probe concatenated with the <em>single nearest</em> such right row.
 * The inequality operator fixes the direction:
 *
 * <ul>
 *   <li>{@code ℓ.ts >= r.ts} / {@code ℓ.ts > r.ts} — <b>backward</b>: the
 *       greatest {@code r.ts} at-or-before (or strictly before) the probe.</li>
 *   <li>{@code ℓ.ts <= r.ts} / {@code ℓ.ts < r.ts} — <b>forward</b>: the least
 *       {@code r.ts} at-or-after (or strictly after) the probe.</li>
 * </ul>
 *
 * <p>By default the join is <b>left-outer</b>: every probe survives, with the
 * right columns NULL when no right row matches.  Setting {@link #inner} to
 * {@code true} switches to the <b>inner</b> variant, which drops unmatched
 * probes.
 *
 * <p>An optional {@link #tolerance} (a {@code WITHIN durExpr} clause) imposes a
 * maximum temporal distance between the probe and the matched right row; a
 * candidate whose distance exceeds the tolerance is treated as no-match.
 *
 * <p>The {@link #tieBreak} rule resolves ties when multiple right rows share
 * the nearest match value: {@link TieBreak#LAST} (default) keeps the last such
 * row in input order; {@link TieBreak#FIRST} keeps the first.
 *
 * @param left      the left (probe) relation; must not be null
 * @param right     the right (lookup) relation; must not be null
 * @param condition the match condition (equality keys + one ordering inequality); must not be null
 * @param tolerance optional WITHIN clause — a DURATION bound on the match distance
 * @param inner     when {@code true}, unmatched probes are dropped (inner variant);
 *                  when {@code false} (default), they are emitted with NULL right columns
 * @param tieBreak  how to break ties among right rows at the same nearest-match value
 * @param location  the source location of this node; never null
 */
public record AsOfJoinNode(RelNode left, RelNode right, Predicate condition,
                           Optional<Operand> tolerance, boolean inner, TieBreak tieBreak,
                           SourceLocation location) implements RelNode {
    public AsOfJoinNode {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(tolerance, "tolerance");
        Objects.requireNonNull(tieBreak, "tieBreak");
        Objects.requireNonNull(location, "location");
    }

    /**
     * Convenience constructor with no tolerance, left-outer behaviour, and
     * last-wins tie-break; uses the given source location.
     */
    public AsOfJoinNode(RelNode left, RelNode right, Predicate condition, SourceLocation location) {
        this(left, right, condition, Optional.empty(), false, TieBreak.LAST, location);
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public AsOfJoinNode(RelNode left, RelNode right, Predicate condition) {
        this(left, right, condition, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

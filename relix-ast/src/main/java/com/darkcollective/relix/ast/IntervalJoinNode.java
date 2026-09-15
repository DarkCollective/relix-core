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
 * Interval join ({@code IJOIN}) — a temporal join over two interval-valued
 * relations using an Allen's interval algebra relation.
 *
 * <p>For each pair of rows {@code (ℓ, r)} whose interval columns satisfy the
 * chosen {@link AllenRelation}, the join emits the concatenation of the two
 * rows.  This is always an <b>inner</b> join (unmatched rows are dropped).
 * The four endpoint columns are specified by name; all must be temporal
 * (DATE, TIME, or TIMESTAMP).
 *
 * <p>Example:
 * {@code Stays IJOIN OVERLAPS (Stays.checkin, Stays.checkout, Bookings.from, Bookings.to)
 * Bookings}
 *
 * @param left        the left relation; must not be null
 * @param right       the right relation; must not be null
 * @param relation    the Allen interval relation to test; must not be null
 * @param leftStart   the name of the left interval's start column
 * @param leftEnd     the name of the left interval's end column
 * @param rightStart  the name of the right interval's start column
 * @param rightEnd    the name of the right interval's end column
 * @param location    the source location of this node; never null
 */
public record IntervalJoinNode(RelNode left, RelNode right,
                               AllenRelation relation,
                               String leftStart, String leftEnd,
                               String rightStart, String rightEnd,
                               SourceLocation location) implements RelNode {
    public IntervalJoinNode {
        Objects.requireNonNull(left,       "left");
        Objects.requireNonNull(right,      "right");
        Objects.requireNonNull(relation,   "relation");
        Objects.requireNonNull(leftStart,  "leftStart");
        Objects.requireNonNull(leftEnd,    "leftEnd");
        Objects.requireNonNull(rightStart, "rightStart");
        Objects.requireNonNull(rightEnd,   "rightEnd");
        Objects.requireNonNull(location,   "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public IntervalJoinNode(RelNode left, RelNode right, AllenRelation relation,
                            String leftStart, String leftEnd,
                            String rightStart, String rightEnd) {
        this(left, right, relation, leftStart, leftEnd, rightStart, rightEnd, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

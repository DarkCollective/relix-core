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
 * Gap-and-island / sessionization operator — groups an ordered stream
 * into <em>sessions</em> separated by an idle gap, the famous SQL
 * {@code LAG}/running-sum incantation expressed as a single algebraic operator.
 *
 * <p>Within each partition (the optional {@link #partitionKeys()} — empty means one
 * partition over the whole relation), rows are ordered ascending by the
 * {@link #orderColumn()} and a new session begins whenever the gap between a row and
 * its predecessor exceeds the {@link #threshold()}:
 *
 * <pre>{@code orderColumnᵢ − orderColumnᵢ₋₁ > threshold}</pre>
 *
 * The operator appends a dense, 1-based session-id column ({@link #sessionColumn()},
 * type {@code NUMBER}) to every input row; the first row of each partition is session
 * {@code 1}, and the id increments at every boundary. No row is removed — this is a
 * non-collapsing, column-adding operator (a sibling of the window family).
 *
 * <p>The {@link #orderColumn()} is typically a {@code TIMESTAMP} (with the gap
 * expressed as a {@code DURATION}) or a {@code NUMBER} (with a {@code NUMBER} gap),
 * leaning on the first-class temporal arithmetic substrate
 * ({@code TIMESTAMP − TIMESTAMP → DURATION}).
 *
 * <p>Surface syntax:
 * <pre>
 *   SESSIONIZE ts GAP DURATION 'PT30M' PER user_id AS session (Events)
 *   SESSIONIZE seq GAP 5 AS run (Readings)
 * </pre>
 *
 * <p>Evaluation buffers and sorts each partition before emitting, so it is a blocking
 * operator ({@link MaterializationMode#BAG}); it never pushes down to a source and is
 * subject to the boundedness check over unbounded inputs.
 *
 * @param input          the source relation; must not be null
 * @param orderColumn    the column ordering the stream and defining the gap; must not be blank
 * @param threshold      the maximum in-session gap (a constant {@code NUMBER}/{@code DURATION} literal); must not be null
 * @param partitionKeys  the {@code PER} columns; empty = single global partition; must not be null
 * @param sessionColumn  the name of the appended session-id column; must not be blank
 * @param location       the source location of this node; never null
 */
public record SessionizeNode(
        RelNode input,
        String orderColumn,
        Operand threshold,
        List<String> partitionKeys,
        String sessionColumn,
        SourceLocation location
) implements RelNode {

    public SessionizeNode {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(orderColumn, "orderColumn");
        if (orderColumn.isBlank()) {
            throw new IllegalArgumentException("Sessionize orderColumn must not be blank");
        }
        Objects.requireNonNull(threshold, "threshold");
        Objects.requireNonNull(partitionKeys, "partitionKeys");
        Objects.requireNonNull(sessionColumn, "sessionColumn");
        if (sessionColumn.isBlank()) {
            throw new IllegalArgumentException("Sessionize sessionColumn must not be blank");
        }
        Objects.requireNonNull(location, "location");
        partitionKeys = List.copyOf(partitionKeys);
    }

    /** Convenience constructor for tests: {@link SourceLocation#UNKNOWN}. */
    public SessionizeNode(RelNode input, String orderColumn, Operand threshold,
                          List<String> partitionKeys, String sessionColumn) {
        this(input, orderColumn, threshold, partitionKeys, sessionColumn, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

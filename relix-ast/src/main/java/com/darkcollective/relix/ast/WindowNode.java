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
 * Window operator — a non-collapsing per-partition computation that
 * adds one column to every input row without removing or merging any.  It is the
 * algebraic, composable form of SQL's
 * {@code OVER (PARTITION BY … ORDER BY … ROWS …)} clause.
 *
 * <p>A single {@code WindowNode} covers all window families via the sealed
 * {@link WindowFunction} hierarchy; the {@link WindowFrame} sets the scope of rows
 * fed to the function.  The surface syntax has two keyword forms:
 *
 * <ul>
 *   <li>{@code ROLLING} — sliding / cumulative aggregates
 *       ({@link WindowFunction.AggregateWindow}), e.g.
 *       {@code ROLLING AVG(price) OVER 3 ROWS SORT trade_time ASC PER ticker AS avg3 (Ticks)};</li>
 *   <li>{@code WINDOW} — ranking and offset functions (slices 3–4).</li>
 * </ul>
 *
 * <p>The output schema is the input schema with {@link #outputColumn()} appended.
 * {@link #partitionKeys()} may be empty (one partition over the whole relation —
 * the {@code PER}-less form); at least one {@link #sortSpecs() sort key} is
 * required.  The operator buffers per partition ({@code BAG} materialisation) and
 * never pushes down in this slice.
 *
 * @param function      the window computation; never null
 * @param partitionKeys the {@code PER} columns; empty = single global partition
 * @param sortSpecs     the within-partition ordering; at least one required
 * @param frame         the row scope fed to {@code function}; never null
 * @param outputColumn  the name of the appended result column; never blank
 * @param input         the source relation; never null
 * @param location      the source location of this node; never null
 */
public record WindowNode(
        WindowFunction function,
        List<String> partitionKeys,
        List<SortSpecification> sortSpecs,
        WindowFrame frame,
        String outputColumn,
        RelNode input,
        SourceLocation location
) implements RelNode {

    public WindowNode {
        Objects.requireNonNull(function, "function");
        Objects.requireNonNull(partitionKeys, "partitionKeys");
        Objects.requireNonNull(sortSpecs, "sortSpecs");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(outputColumn, "outputColumn");
        if (outputColumn.isBlank()) {
            throw new IllegalArgumentException("Window outputColumn must not be blank");
        }
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(location, "location");
        partitionKeys = List.copyOf(partitionKeys);
        sortSpecs = List.copyOf(sortSpecs);
    }

    /** Convenience constructor for tests: {@link SourceLocation#UNKNOWN}. */
    public WindowNode(WindowFunction function, List<String> partitionKeys,
                      List<SortSpecification> sortSpecs, WindowFrame frame,
                      String outputColumn, RelNode input) {
        this(function, partitionKeys, sortSpecs, frame, outputColumn, input, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

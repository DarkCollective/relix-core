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
 * Optimal-path extraction over a directed, weighted graph — the cheapest (or longest)
 * path finder.
 *
 * <p>The {@link #input()} relation is read as a set of <em>directed</em> edges, each
 * carrying a numeric weight in {@link #weightColumn()}. The operator computes, for every
 * reachable {@code (origin, destination)} pair, the path that minimises (or maximises,
 * per {@link #sense()}) the total edge weight, and returns the traversed node sequence
 * as a first-class ordered array value in the {@link #pathColumn()}.
 *
 * <p>Output schema: {@code (fromColumn : T, toColumn : T, weightColumn : NUMBER, pathColumn :
 * ARRAY)},
 * where {@code T} is the type of the endpoint columns and {@code ARRAY} is an ordered
 * {@code array<T>} holding the sequence of nodes from origin to destination inclusive.
 *
 * <p>Evaluation is a Bellman-Ford–style all-pairs fixpoint: each iteration extends
 * known paths by one hop and keeps the optimal so far. The operator materialises
 * the complete edge set before iterating, so it is blocking ({@code [bag]} mode) and
 * is subject to the boundedness check. It never pushes down to a source.
 *
 * <p>MAXIMIZE over a graph with positive-weight cycles will not converge; the
 * {@code --max-fixpoint-rounds} guard applies (consistent with {@code FIX} and
 * weighted {@code CLOSURE}).
 *
 * <p>Surface syntax:
 * {@code TRACE from, to VIA weight MINIMIZE|MAXIMIZE AS path (Edges)}
 *
 * <h2>Pushed endpoint bounds</h2>
 * <p>{@link #boundSource()} and {@link #boundTarget()} are optional constant
 * endpoint bounds folded in by the optimizer's {@code SelectionIntoTracePass}
 * ({@code TRACE-001}) when a selection above the trace fixes the origin and/or
 * destination to a literal. They turn the all-pairs path search into
 * single-source / single-target / single-pair search — the magic-sets /
 * sideways-information-passing rewrite specialised to optimal paths. They are
 * always {@link Optional#empty()} on a parsed tree (no surface syntax); they
 * carry a <em>literal</em> {@link Operand} and never change the output schema, so
 * schema inference is unaffected.
 *
 * @param input        the edge relation; must not be null
 * @param fromColumn   the origin node column; must not be blank
 * @param toColumn     the destination node column; must not be blank
 * @param weightColumn the edge-weight column (must be NUMBER or ANY); must not be blank
 * @param sense        whether to minimise or maximise total path weight; must not be null
 * @param pathColumn   the name of the appended ordered-path array column; must not be blank
 * @param boundSource  optional literal bound on the {@code fromColumn} endpoint
 *                     (single-source); never null, possibly empty
 * @param boundTarget  optional literal bound on the {@code toColumn} endpoint
 *                     (single-target); never null, possibly empty
 * @param location     the source location of this node; never null
 */
public record TraceNode(RelNode input, String fromColumn, String toColumn,
                        String weightColumn, ObjectiveSense sense,
                        String pathColumn,
                        Optional<Operand> boundSource, Optional<Operand> boundTarget,
                        SourceLocation location)
        implements RelNode {

    public TraceNode {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(fromColumn, "fromColumn");
        if (fromColumn.isBlank()) {
            throw new IllegalArgumentException("Trace fromColumn must not be blank");
        }
        Objects.requireNonNull(toColumn, "toColumn");
        if (toColumn.isBlank()) {
            throw new IllegalArgumentException("Trace toColumn must not be blank");
        }
        Objects.requireNonNull(weightColumn, "weightColumn");
        if (weightColumn.isBlank()) {
            throw new IllegalArgumentException("Trace weightColumn must not be blank");
        }
        Objects.requireNonNull(sense, "sense");
        Objects.requireNonNull(pathColumn, "pathColumn");
        if (pathColumn.isBlank()) {
            throw new IllegalArgumentException("Trace pathColumn must not be blank");
        }
        Objects.requireNonNull(boundSource, "boundSource");
        Objects.requireNonNull(boundTarget, "boundTarget");
        Objects.requireNonNull(location, "location");
    }

    /**
     * Constructor without endpoint bounds (the parsed form): both bounds empty.
     *
     * @param input        the edge relation; must not be null
     * @param fromColumn   the origin node column; must not be blank
     * @param toColumn     the destination node column; must not be blank
     * @param weightColumn the edge-weight column; must not be blank
     * @param sense        the objective sense; must not be null
     * @param pathColumn   the appended path-array column; must not be blank
     * @param location     the source location of this node; never null
     */
    public TraceNode(RelNode input, String fromColumn, String toColumn,
                     String weightColumn, ObjectiveSense sense, String pathColumn,
                     SourceLocation location) {
        this(input, fromColumn, toColumn, weightColumn, sense, pathColumn,
             Optional.empty(), Optional.empty(), location);
    }

    /** Convenience constructor for tests: {@link SourceLocation#UNKNOWN}. */
    public TraceNode(RelNode input, String fromColumn, String toColumn,
                     String weightColumn, ObjectiveSense sense, String pathColumn) {
        this(input, fromColumn, toColumn, weightColumn, sense, pathColumn,
             SourceLocation.UNKNOWN);
    }

    /**
     * Returns a copy of this trace with the given endpoint bounds, preserving all
     * other fields.
     *
     * @param newSource the source-endpoint bound; must not be null
     * @param newTarget the target-endpoint bound; must not be null
     * @return a bounded copy
     */
    public TraceNode withBounds(Optional<Operand> newSource, Optional<Operand> newTarget) {
        return new TraceNode(input, fromColumn, toColumn, weightColumn, sense, pathColumn,
                newSource, newTarget, location);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

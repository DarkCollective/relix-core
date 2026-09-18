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
 * Transitive closure (least fixpoint) of a binary relation — the recursive
 * "reachability" operator.
 *
 * <p>The {@link #input()} relation is read as a set of directed edges over two
 * columns, {@link #fromColumn()} and {@link #toColumn()}. The operator emits the
 * pairs {@code (a, b)} connected by one or more edges (its <em>transitive</em>
 * closure {@code R⁺}); when {@link #reflexive()} is set it also emits the identity
 * pair {@code (n, n)} for every node, giving the <em>reflexive-transitive</em>
 * closure {@code R*}. The output is a binary relation carrying just the two edge
 * columns; any other input columns are dropped.
 *
 * <p>This is the groupwise reachability / ancestry / bill-of-materials operation
 * that SQL expresses only with a {@code WITH RECURSIVE} CTE. Evaluation is an
 * in-engine least-fixpoint iteration under set semantics, so a cyclic input graph
 * terminates (a pair already derived is never re-added). The operator never pushes
 * down to a source.
 *
 * <p>Surface syntax: {@code CLOSURE from, to (Edges)} (transitive) and
 * {@code RCLOSURE from, to (Edges)} (reflexive-transitive).
 *
 * <h2>Pushed endpoint bounds</h2>
 * <p>{@link #boundSource()} and {@link #boundTarget()} are optional constant
 * endpoint bounds folded in by the optimizer's {@code SelectionIntoClosurePass}
 * ({@code CLOSURE-001}) when a selection above the closure fixes the source
 * and/or target endpoint to a literal. They turn the all-pairs computation into
 * single-source / single-target / single-pair reachability — the canonical
 * magic-sets / sideways-information-passing rewrite. They are always
 * {@link Optional#empty()} on a parsed tree (no surface syntax); they carry a
 * <em>literal</em> {@link Operand} and never change the output schema, so schema
 * inference is unaffected.
 *
 * @param input       the edge relation; must not be null
 * @param fromColumn  the source-endpoint column; must not be blank
 * @param toColumn    the target-endpoint column; must not be blank
 * @param reflexive   {@code true} for {@code R*} (adds identity pairs), {@code false} for {@code R⁺}
 * @param undirected   {@code true} reads the two endpoint columns as an
 *                     undirected edge, so the relation is followed both ways from one
 *                     edge set ({@code a ↔ b}); {@code false} reads a directed edge
 *                     ({@code a, b})
 * @param boundSource optional literal bound on the {@code fromColumn} endpoint
 *                    (single-source); never null, possibly empty
 * @param boundTarget optional literal bound on the {@code toColumn} endpoint
 *                    (single-target); never null, possibly empty
 * @param location    the source location of this node; never null
 */
public record ClosureNode(RelNode input, String fromColumn, String toColumn,
                          boolean undirected,
                          boolean reflexive,
                          Optional<Operand> boundSource, Optional<Operand> boundTarget,
                          SourceLocation location)
        implements RelNode {

    public ClosureNode {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(fromColumn, "fromColumn");
        if (fromColumn.isBlank()) {
            throw new IllegalArgumentException("Closure fromColumn must not be blank");
        }
        Objects.requireNonNull(toColumn, "toColumn");
        if (toColumn.isBlank()) {
            throw new IllegalArgumentException("Closure toColumn must not be blank");
        }
        Objects.requireNonNull(boundSource, "boundSource");
        Objects.requireNonNull(boundTarget, "boundTarget");
        Objects.requireNonNull(location, "location");
    }

    /**
     * Constructor without endpoint bounds (the parsed form): both bounds empty.
     *
     * @param input      the edge relation; must not be null
     * @param fromColumn the source-endpoint column; must not be blank
     * @param toColumn   the target-endpoint column; must not be blank
     * @param reflexive  {@code true} for {@code R*}, {@code false} for {@code R⁺}
     * @param location   the source location of this node; never null
     */
    public ClosureNode(RelNode input, String fromColumn, String toColumn,
                       boolean reflexive, SourceLocation location) {
        this(input, fromColumn, toColumn, false, reflexive,
                Optional.empty(), Optional.empty(), location);
    }

    /** Convenience constructor for tests: transitive ({@code R⁺}), {@link SourceLocation#UNKNOWN}. */
    public ClosureNode(RelNode input, String fromColumn, String toColumn) {
        this(input, fromColumn, toColumn, false, SourceLocation.UNKNOWN);
    }

    /** Convenience constructor for tests: {@link SourceLocation#UNKNOWN}. */
    public ClosureNode(RelNode input, String fromColumn, String toColumn, boolean reflexive) {
        this(input, fromColumn, toColumn, reflexive, SourceLocation.UNKNOWN);
    }

    /**
     * Returns a copy of this closure with the given endpoint bounds, preserving
     * all other fields.
     *
     * @param newSource the source-endpoint bound; must not be null
     * @param newTarget the target-endpoint bound; must not be null
     * @return a bounded copy
     */
    public ClosureNode withBounds(Optional<Operand> newSource, Optional<Operand> newTarget) {
        return new ClosureNode(input, fromColumn, toColumn, undirected, reflexive,
                newSource, newTarget, location);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

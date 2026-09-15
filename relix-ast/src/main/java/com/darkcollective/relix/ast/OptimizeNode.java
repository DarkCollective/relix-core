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
 * Declarative optimisation (OPTIMIZE) — the goal-seeking sibling of {@code γ}. Within each group
 * (by {@code groupingKeys}) it either selects the
 * optimal subset (MIP mode) or assigns continuous allocations (LP mode).
 *
 * <p><b>MIP mode</b> (default, {@code allocation} is empty): each row carries a
 * binary decision (in or out); the output is the chosen input rows unchanged, so
 * the output schema equals the input schema.  A group whose constraints are
 * infeasible contributes no rows.
 * Example: {@code OPTIMIZE MAXIMIZE SUM(value) SUBJECT TO SUM(weight) <= 100 PER
 * region (Candidates)}.
 *
 * <p><b>LP mode</b> ({@code allocation} is present): each row receives a
 * continuous decision variable in {@code [lo, hi]}; every row is emitted with its
 * allocation value appended as a new column — output schema = input + 1 column.
 * Syntax: {@code OPTIMIZE ALLOCATE (lo, hi) MAXIMIZE SUM(return) SUBJECT TO
 * SUM(1) = 1.0 -> weight (Portfolio)}.
 *
 * <p>Solved in-engine above the federation boundary; never pushes down.
 * Materialises its input ({@code [bag]}).  At least one constraint is required.
 *
 * @param sense        whether to maximise or minimise the objective; never null
 * @param objective    the per-row coefficient expression summed in the objective; never null
 * @param constraints  the linear constraints (at least one); never null
 * @param groupingKeys the grouping keys (empty = one whole-relation group); never null
 * @param allocation   present iff this is LP (continuous allocation) mode; never null
 * @param input        the candidate relation; never null
 * @param location     the source location of this node; never null
 */
public record OptimizeNode(
        ObjectiveSense sense,
        Operand objective,
        List<OptimizeConstraint> constraints,
        List<String> groupingKeys,
        Optional<AllocationSpec> allocation,
        RelNode input,
        SourceLocation location
) implements RelNode {
    public OptimizeNode {
        Objects.requireNonNull(sense, "sense");
        Objects.requireNonNull(objective, "objective");
        constraints = List.copyOf(constraints);
        groupingKeys = List.copyOf(groupingKeys);
        Objects.requireNonNull(allocation, "allocation");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests (MIP mode); uses {@link SourceLocation#UNKNOWN}. */
    public OptimizeNode(ObjectiveSense sense, Operand objective,
                        List<OptimizeConstraint> constraints, List<String> groupingKeys,
                        RelNode input) {
        this(sense, objective, constraints, groupingKeys,
             Optional.empty(), input, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

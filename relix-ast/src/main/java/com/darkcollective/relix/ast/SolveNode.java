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
 * Goal-seek (SOLVE) — a per-row operator that fills the unknown columns of a row by
 * solving one or more declared arithmetic equations {@code left = right}.
 *
 * <p>For each input row, the <em>participating columns</em> are the distinct
 * attribute references appearing anywhere in the equations. Those that are
 * {@code NULL} in that row are its unknowns, so the direction is per row — the same
 * equation fills {@code principal} in one row and {@code rate} in another, depending
 * on which is blank.
 *
 * <p>A single equation is solved when exactly one participating column is
 * {@code NULL}, by rearranging the formula. A list of equations is a system: a row is
 * solved when it has as many unknowns as there are equations, every equation is
 * linear in those unknowns, and the system is non-singular. Any other row passes
 * through unchanged — a fully-populated row is already complete, and one that cannot
 * be solved keeps its {@code NULL}s.
 *
 * <p>The output schema equals the input schema — {@code solve} fills holes, it never
 * adds or removes columns. The sides are restricted to arithmetic (column references,
 * numeric literals, {@code + − × ÷}, unary minus, and calls to user-defined scalar
 * functions whose bodies are such arithmetic). With a single equation each
 * participating column may appear at most once, so the inversion is deterministic
 * regardless of which column is the runtime unknown. This operator runs in-engine
 * and never pushes down.
 *
 * <p>Example: {@code SOLVE total = principal * rate (Loans)} fills whichever of
 * {@code total}, {@code principal}, or {@code rate} is blank in each loan row.
 *
 * @param equations the equations, in source order; never empty
 * @param input     the relation whose rows are completed; must not be null
 * @param location  the source location of this node; never null
 */
public record SolveNode(List<SolveEquation> equations, RelNode input, SourceLocation location)
        implements RelNode {
    public SolveNode {
        Objects.requireNonNull(equations, "equations");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(location, "location");
        equations = List.copyOf(equations);
        if (equations.isEmpty()) {
            throw new IllegalArgumentException("SOLVE needs at least one equation");
        }
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public SolveNode(List<SolveEquation> equations, RelNode input) {
        this(equations, input, SourceLocation.UNKNOWN);
    }

    /** Convenience constructor for a single equation; uses {@link SourceLocation#UNKNOWN}. */
    public SolveNode(Operand left, Operand right, RelNode input) {
        this(List.of(new SolveEquation(left, right)), input, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

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

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Goal-seek (SOLVE) — fills the unknown columns of a row by solving one or more
 * declared arithmetic equations {@code left = right}.
 *
 * <p>For each input row, the <em>participating columns</em> are the distinct
 * attribute references appearing anywhere in the equations. Those that are
 * {@code NULL} in that row are its unknowns, so the direction is per row — the same
 * equation fills {@code principal} in one row and {@code rate} in another, depending
 * on which is blank. A row is solved when it has exactly as many unknowns as there are
 * equations, by the cheapest strategy that applies: a single equation whose unknown
 * appears once is inverted by rearranging it; equations linear in the unknowns are
 * solved by elimination; anything else is solved by Newton's method, every unknown
 * starting at 1. A row that cannot be posed — the wrong number of unknowns, or a
 * singular linear system — passes through unchanged; a row that is posed but on which
 * the iteration does not converge is an error.
 *
 * <p>With {@code groupingKeys} ({@code PER k, …}) the equations are fitted across each
 * group of rows rather than solved row by row: the unknowns are the participating
 * columns that are {@code NULL} in every row of the group, every row in which the other
 * participating columns are all present is an observation, and the unknowns are chosen
 * to minimise the sum of squared residuals over the observations — directly when the
 * equations are linear in them, by Gauss–Newton otherwise. The fitted values are
 * written into every row of the group. Grouping makes the operator blocking.
 *
 * <p>{@code tolerance} ({@code WITHIN ε}) and {@code maxRounds} ({@code MAX n ROUNDS})
 * govern the iteration, and are left empty for the engine's defaults; an iteration
 * stops when no unknown moves by more than the tolerance in a round.
 *
 * <p>The output schema equals the input schema — {@code solve} fills holes, it never
 * adds or removes columns. The sides are restricted to arithmetic (column references,
 * numeric literals, {@code + − × ÷}, unary minus, and calls to user-defined scalar
 * functions whose bodies are such arithmetic). This operator runs in-engine and never
 * pushes down.
 *
 * <p>Example: {@code SOLVE total = principal * rate (Loans)} fills whichever of
 * {@code total}, {@code principal}, or {@code rate} is blank in each loan row.
 *
 * @param equations    the equations, in source order; never empty
 * @param groupingKeys the {@code PER} columns a fit is made per; empty to solve row by row
 * @param tolerance    the {@code WITHIN} convergence tolerance, positive; empty for the default
 * @param maxRounds    the {@code MAX … ROUNDS} iteration cap, at least 1; empty for the default
 * @param input        the relation whose rows are completed; must not be null
 * @param location     the source location of this node; never null
 */
public record SolveNode(List<SolveEquation> equations, List<String> groupingKeys,
                        Optional<BigDecimal> tolerance, Optional<Integer> maxRounds,
                        RelNode input, SourceLocation location)
        implements RelNode {
    public SolveNode {
        Objects.requireNonNull(equations, "equations");
        Objects.requireNonNull(groupingKeys, "groupingKeys");
        Objects.requireNonNull(tolerance, "tolerance");
        Objects.requireNonNull(maxRounds, "maxRounds");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(location, "location");
        equations = List.copyOf(equations);
        groupingKeys = List.copyOf(groupingKeys);
        if (equations.isEmpty()) {
            throw new IllegalArgumentException("SOLVE needs at least one equation");
        }
        if (tolerance.isPresent() && tolerance.get().signum() <= 0) {
            throw new IllegalArgumentException("SOLVE tolerance must be positive");
        }
        if (maxRounds.isPresent() && maxRounds.get() < 1) {
            throw new IllegalArgumentException("SOLVE round cap must be at least 1");
        }
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public SolveNode(List<SolveEquation> equations, List<String> groupingKeys, RelNode input) {
        this(equations, groupingKeys, Optional.empty(), Optional.empty(), input,
                SourceLocation.UNKNOWN);
    }

    /** Convenience constructor for a single equation; uses {@link SourceLocation#UNKNOWN}. */
    public SolveNode(Operand left, Operand right, RelNode input) {
        this(List.of(new SolveEquation(left, right)), List.of(), input);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

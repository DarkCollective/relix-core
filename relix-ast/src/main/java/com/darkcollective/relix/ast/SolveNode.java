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
 * Goal-seek (SOLVE) — a per-row operator that fills a single unknown column by
 * inverting a declared arithmetic equation {@code left = right}.
 *
 * <p>For each input row, the <em>participating columns</em> are the distinct
 * attribute references appearing anywhere in {@code left} or {@code right}.
 * Whichever <em>single</em> participating column is {@code NULL} in that row is
 * the unknown: {@code solve} rearranges the equation to compute it and emits the
 * completed row.  The direction is therefore per-row — the same equation fills
 * {@code principal} in one row and {@code rate} in another, depending on which is
 * blank.
 *
 * <p>Rows that do not have exactly one {@code NULL} participating column pass
 * through unchanged: a fully-populated row is already complete (no recompute or
 * validation), and a row with two or more blanks is underdetermined (its
 * {@code NULL}s are left intact).
 *
 * <p>The output schema equals the input schema — {@code solve} fills holes, it
 * never adds or removes columns.  Both sides are restricted to invertible
 * arithmetic (column references, numeric literals, {@code + − × ÷} and unary
 * minus), and each participating column may appear at most once across the whole
 * equation, so the inversion is deterministic regardless of which column is the
 * runtime unknown.  This operator runs in-engine and never pushes down.
 *
 * <p>Example: {@code SOLVE total = principal * rate (Loans)} fills whichever of
 * {@code total}, {@code principal}, or {@code rate} is blank in each loan row.
 *
 * @param left     the left-hand side of the equation; must not be null
 * @param right    the right-hand side of the equation; must not be null
 * @param input    the relation whose rows are completed; must not be null
 * @param location the source location of this node; never null
 */
public record SolveNode(Operand left, Operand right, RelNode input, SourceLocation location)
        implements RelNode {
    public SolveNode {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public SolveNode(Operand left, Operand right, RelNode input) {
        this(left, right, input, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

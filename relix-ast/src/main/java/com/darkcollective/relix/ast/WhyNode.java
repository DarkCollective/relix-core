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
 * Why (ω) — reifies the lineage provenance of {@code input} as queryable data.
 *
 * <p>{@code WHY(R)} is a <em>self-terminating reification boundary</em>. Below it
 * the lineage semiring threads exactly as on today's provenance side-channel; at
 * it each result tuple's lineage polynomial is frozen into one added nested
 * column; above it the value is inert data that ordinary {@code σ}/{@code π}/{@code μ}
 * views slice. It is therefore <em>not</em> the rejected always-on annotation
 * column — nothing above {@code WHY} is re-threaded.
 *
 * <p>Output schema: {@code input columns ⊕ provenance:ANY}, where the appended
 * {@code provenance} column holds the tuple's polynomial as a faithful two-level
 * document: {@code [{coefficient, variables:[{relation, ordinal, columns}]}]} —
 * one array element per monomial (distinct derivation), one {@code variables}
 * element per contributing base-tuple occurrence.
 *
 * <p>Evaluation reuses the existing lineage K-relation path
 * ({@code ProvenanceEvaluator} over the polynomial semiring) — no new evaluation
 * concepts. The canonical K-relation must be built before output, so the operator
 * is blocking ({@link MaterializationMode#BAG}), and it is a hard optimizer
 * barrier that never pushes down.
 *
 * <p>Surface syntax: {@code WHY (R)} (ASCII) or {@code ω (R)} (glyph).
 *
 * @param input    the source relation whose lineage is reified; must not be null
 * @param location the source location of this node; never null
 */
public record WhyNode(RelNode input, SourceLocation location) implements RelNode {

    /**
     * The reserved name of the appended provenance column ({@code provenance}).
     * The reification adds one column of this name at type {@code ANY}; a clash
     * with an existing input column is a validation error — there is no
     * {@code ρ}-style rename escape hatch.
     */
    public static final String PROVENANCE_COLUMN = "provenance";

    public WhyNode {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public WhyNode(RelNode input) {
        this(input, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

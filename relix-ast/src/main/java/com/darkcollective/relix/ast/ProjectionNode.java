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
 * Projection (π) — selects a subset of attributes from {@code input},
 * optionally computing new expressions or renaming columns.
 *
 * <p>Example: {@code π id, price * 1.1 → adjusted (Products)}
 *
 * @param attributes the projected attributes; must not be empty
 * @param input      the source relation; must not be null
 * @param location   the source location of this node; never null
 */
public record ProjectionNode(List<ProjectedAttribute> attributes, RelNode input, SourceLocation location) implements RelNode {
    public ProjectionNode {
        attributes = List.copyOf(attributes);
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(location, "location");
        if (attributes.isEmpty()) {
            throw new IllegalArgumentException("Projection requires at least one attribute");
        }
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public ProjectionNode(List<ProjectedAttribute> attributes, RelNode input) {
        this(attributes, input, SourceLocation.UNKNOWN);
    }

    /**
     * Whether this projection only <em>drops</em> columns — every attribute is a bare
     * column reference with no alias and no computation, so the output columns are a
     * sub-list of the input's under their own names.
     *
     * <p>This is the shape the optimizer's column pruning ({@code PROJ-004}) inserts,
     * and it is the one π a pushdown renderer can fold <em>without</em> fixing the
     * select list: because it introduces no new name, an operator above it still
     * refers to real source columns, so a {@code WHERE}/{@code GROUP BY}/{@code ORDER
     * BY} can still be folded on top.  A computed or aliased projection cannot make
     * that claim.
     *
     * @return {@code true} when every projected attribute is an unaliased
     *         {@link AttributeOperand}
     */
    public boolean isColumnPruning() {
        return attributes.stream().allMatch(
                a -> a.alias().isEmpty() && a.expression() instanceof AttributeOperand);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

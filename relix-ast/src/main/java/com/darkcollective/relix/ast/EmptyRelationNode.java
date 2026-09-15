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
 * The relation with <em>no rows</em> and the heading of another expression — the
 * {@code ∅} that a provably-unsatisfiable query collapses to.
 *
 * <p>Introduced only by the optimizer's empty-relation propagation
 * ({@code EMPTY-001}/{@code EMPTY-002}), never by the parser: a contradictory
 * filter such as {@code σ x > 5 ∧ x < 3 (Orders)} admits no row whatever
 * {@code Orders} contains, so the whole sub-tree is replaced by this node and
 * neither scanned nor joined.
 *
 * <h2>Why not {@code EMPTY} / {@code DUM}?</h2>
 * <p>{@link TruthRelationNode#empty(SourceLocation)} is also row-less, but it is the
 * <strong>zero-column</strong> relation — the truth value <em>false</em>. Its own
 * Javadoc makes the distinction from the other side: {@code R × EMPTY} is empty
 * <em>but keeps {@code R}'s heading, so it is not {@code EMPTY} itself</em>. Every
 * law this node exists to serve is heading-preserving — {@code σ false (R)} still
 * has {@code R}'s columns, and a query that selects from it must still type-check —
 * so a row-less relation that carries a heading is a genuinely different thing from
 * the nullary literal, and gets its own node rather than a flag on that one.
 *
 * <h2>The heading is carried, not computed</h2>
 * <p>{@code relix-ast} has no dependency on the symbol layer, so this node cannot
 * hold a {@code Schema} directly. It holds the sub-expression it replaced, and
 * schema inference reports <em>that</em> expression's schema — which is exactly the
 * heading required, at every step of the propagation: replacing {@code ∅ ⋈ X} means
 * carrying the heading of the whole join, not of the empty side.
 *
 * <h2>The heading is not a child</h2>
 * <p>{@link #children()} is empty and {@link #mapChildren} returns {@code this}, so
 * this is a <strong>leaf</strong> and the carried expression is inert: no pass
 * recurses into it, nothing rewrites it, and the executor never touches it. That is
 * deliberate in both directions. It is dead code — running any of it would be the
 * work this rule exists to avoid — and, more sharply, a rewrite that changed the
 * carried expression's <em>schema</em> would silently change this node's heading,
 * which would be a correctness bug rather than a missed optimisation.
 *
 * @param heading  the expression whose schema this empty relation has; must not be null
 * @param location the source location of this node; never null
 */
public record EmptyRelationNode(RelNode heading, SourceLocation location) implements RelNode {

    /** Validates that both components are present. */
    public EmptyRelationNode {
        Objects.requireNonNull(heading, "heading");
        Objects.requireNonNull(location, "location");
    }

    /**
     * Creates an empty relation with {@code heading}'s schema, taking its source
     * location from that expression so a diagnostic still points at the query text
     * the rule collapsed.
     *
     * @param heading the expression whose schema and location to adopt; must not be null
     * @return the empty relation; never null
     */
    public static EmptyRelationNode of(RelNode heading) {
        Objects.requireNonNull(heading, "heading");
        return new EmptyRelationNode(heading, heading.location());
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

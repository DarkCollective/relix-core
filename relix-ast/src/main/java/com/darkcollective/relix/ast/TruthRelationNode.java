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
 * A nullary <em>truth relation</em> literal — a leaf node denoting one of the two
 * relations whose heading is the empty (closed, zero-column) schema.
 *
 * <p>A zero-column relation can hold at most one tuple — the empty tuple — so
 * there are exactly two of them, and they behave as truth values:
 *
 * <ul>
 *   <li>{@code UNIT} (alias {@code DEE}) — holds the empty tuple: <em>true</em>,
 *       and the identity of Cartesian product ({@code R × UNIT = R}).</li>
 *   <li>{@code EMPTY} (alias {@code DUM}) — holds no tuple: <em>false</em>.
 *       {@code R × EMPTY} is empty, but it keeps {@code R}'s heading, so it is
 *       not {@code EMPTY} itself.</li>
 * </ul>
 *
 * <p>These are Tutorial D's {@code TABLE_DEE} / {@code TABLE_DUM} (Date &amp;
 * Darwen, <i>The Third Manifesto</i>).  Relix leads with the descriptive
 * {@code UNIT}/{@code EMPTY} spellings — the lineage names are recognised
 * aliases, in the same spirit as the glyph↔keyword duality of the operators.
 *
 * <p>The empty schema this node infers to ({@code Schema.empty()}) is the same
 * one the no-key whole-relation universal quantifier {@code ∀ : P (R)} already
 * produces, so every phase below is width-0 tolerant by construction.
 *
 * @param holdsTuple {@code true} for {@code UNIT}/{@code DEE} (one empty tuple),
 *                   {@code false} for {@code EMPTY}/{@code DUM} (no tuples)
 * @param location   the source location of this node; never null
 */
public record TruthRelationNode(boolean holdsTuple, SourceLocation location) implements RelNode {

    /** The keyword spelling of the one-tuple truth relation. */
    public static final String UNIT_KEYWORD = "UNIT";

    /** The keyword spelling of the zero-tuple truth relation. */
    public static final String EMPTY_KEYWORD = "EMPTY";

    public TruthRelationNode {
        Objects.requireNonNull(location, "location");
    }

    /**
     * Creates the one-tuple truth relation ({@code UNIT} / {@code DEE}).
     *
     * @param location the source location; must not be null
     * @return a {@code UNIT} node
     */
    public static TruthRelationNode unit(SourceLocation location) {
        return new TruthRelationNode(true, location);
    }

    /**
     * Creates the zero-tuple truth relation ({@code EMPTY} / {@code DUM}).
     *
     * @param location the source location; must not be null
     * @return an {@code EMPTY} node
     */
    public static TruthRelationNode empty(SourceLocation location) {
        return new TruthRelationNode(false, location);
    }

    /**
     * Returns the canonical keyword for this literal — {@code "UNIT"} or
     * {@code "EMPTY"} — as printed by every tree renderer.
     *
     * @return the canonical keyword; never null
     */
    public String keyword() {
        return holdsTuple ? UNIT_KEYWORD : EMPTY_KEYWORD;
    }

    /**
     * The number of tuples this literal denotes: {@code 1} for {@code UNIT},
     * {@code 0} for {@code EMPTY}.  Both cardinalities are exact, not estimates.
     *
     * @return {@code 1} or {@code 0}
     */
    public long cardinality() {
        return holdsTuple ? 1L : 0L;
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

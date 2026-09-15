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
package com.darkcollective.relix.optimizer;

import com.darkcollective.relix.ast.AttributeNames;
import com.darkcollective.relix.symbol.NestedPaths;
import com.darkcollective.relix.symbol.Schema;

import java.util.Locale;

/**
 * Which input of a binary join owns an attribute reference.
 *
 * <p>Deciding this by bare column name alone is not enough for the rules that need it:
 * an equi-join's two sides usually share the joined column's <em>name</em>, so the
 * qualifier is the only thing distinguishing {@code A.x} from {@code B.x}.  A qualified
 * reference is therefore resolved by column <em>provenance</em> — the same rule
 * {@code ArrayRow#get(String)} applies at execution time — and only an unqualified one
 * falls back to name exclusivity.
 *
 * <p>A dotted name that is a <em>path</em> into a nested column ({@code location.city})
 * is resolved by its head, which is the column it actually names — the reading
 * {@code Schema#resolvePath} supplies, tried after the relation-qualified one and before
 * the bare-name fallback.
 *
 * <p>Anything either input could answer is {@link Side#UNKNOWN}, never a guess: both
 * callers ({@link TransitiveEqualityPass} deriving a filter for one side,
 * {@link OuterJoinDemotionPass} asking whether a predicate constrains the
 * null-supplying side) turn an unknown into a skipped rewrite, which costs an
 * optimization and never an answer.
 */
final class JoinSides {

    private JoinSides() {}

    /** The input a reference belongs to. */
    enum Side {
        LEFT, RIGHT, UNKNOWN;

        /** Lower-case name, for transformation-record text. */
        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Resolves {@code reference} against a join's two input schemas.
     *
     * @param reference the attribute name as written, qualified or not; must not be null
     * @param left      the left input's schema; must not be null
     * @param right     the right input's schema; must not be null
     * @return the owning side, or {@link Side#UNKNOWN} when either or neither could own it
     */
    static Side sideOf(String reference, Schema left, Schema right) {
        String qualifier = AttributeNames.qualifierOf(reference);
        String bare      = AttributeNames.stripQualifier(reference);

        if (qualifier != null) {
            boolean inLeft  = left.hasProvenance()  && left.qualifiedIndices(qualifier, bare).size() == 1;
            boolean inRight = right.hasProvenance() && right.qualifiedIndices(qualifier, bare).size() == 1;
            if (inLeft && !inRight) return Side.LEFT;
            if (inRight && !inLeft) return Side.RIGHT;
            if (inLeft) return Side.UNKNOWN;      // both sides claim it — do not guess
        }
        // A dotted name may be a path into a nested column rather than a relation
        // qualifier, and then the column it names is the head, not the tail. Resolving
        // `location.city` by its tail finds whichever side happens to carry a column
        // called `city` — so a σ on a *left* nested column was pushed into the right
        // input, filtering a relation the predicate says nothing about.
        // NestedPaths owns the rule, including why an open schema is asked nothing:
        // it resolves every name, so its answer is no evidence about which side was
        // meant. The executor asks the same question of the same headings.
        switch (NestedPaths.ownerOf(reference, left, right)) {
            case LEFT -> { return Side.LEFT; }
            case RIGHT -> { return Side.RIGHT; }
            case BOTH -> { return Side.UNKNOWN; }   // both could answer — do not guess
            case NEITHER -> { /* not a path; the bare name decides below */ }
        }

        boolean leftHas  = left.column(bare).isPresent();
        boolean rightHas = right.column(bare).isPresent();
        if (leftHas && !rightHas)  return Side.LEFT;
        if (rightHas && !leftHas)  return Side.RIGHT;
        return Side.UNKNOWN;
    }
}

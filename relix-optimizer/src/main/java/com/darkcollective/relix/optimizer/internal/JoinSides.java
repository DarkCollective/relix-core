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
package com.darkcollective.relix.optimizer.internal;

import com.darkcollective.relix.ast.internal.AttributeNames;
import com.darkcollective.relix.symbol.internal.NestedPaths;
import com.darkcollective.relix.symbol.Schema;

import java.util.Locale;
import java.util.Set;

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
public final class JoinSides {

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
        return sideOf(reference, left, right, Set.of(), Set.of());
    }

    /**
     * {@link #sideOf(String, Schema, Schema)}, given also the relation names each input
     * answers to as qualifiers ({@link com.darkcollective.relix.ast.internal.Qualifiers#inScope}).
     *
     * <p>Those names settle a qualified reference into a schema-on-read input, which has
     * no provenance to settle it by: {@code products.name} belongs to the open input
     * named {@code products} when the other input does not answer to that name. A row
     * that input produces answers to its relation's name (#972), so the reference still
     * resolves once a predicate is pushed there. Only a single qualifier segment is
     * read that way; a longer dotted name is left to the rules below.
     *
     * @param leftQualifiers  lowercased names the left input answers to
     * @param rightQualifiers lowercased names the right input answers to
     */
    static Side sideOf(String reference, Schema left, Schema right,
                       Set<String> leftQualifiers, Set<String> rightQualifiers) {
        String qualifier = AttributeNames.qualifierOf(reference);
        String bare      = AttributeNames.stripQualifier(reference);

        if (qualifier != null) {
            boolean inLeft  = left.hasProvenance()  && left.qualifiedIndices(qualifier, bare).size() == 1;
            boolean inRight = right.hasProvenance() && right.qualifiedIndices(qualifier, bare).size() == 1;
            if (inLeft && !inRight) return Side.LEFT;
            if (inRight && !inLeft) return Side.RIGHT;
            if (inLeft) return Side.UNKNOWN;      // both sides claim it — do not guess

            String q = qualifier.toLowerCase(Locale.ROOT);
            boolean namesLeft  = leftQualifiers.contains(q);
            boolean namesRight = rightQualifiers.contains(q);
            if (namesLeft && !namesRight && left.isOpen())  return Side.LEFT;
            if (namesRight && !namesLeft && right.isOpen()) return Side.RIGHT;
        }
        // A dotted name may be a path into a nested column rather than a relation
        // qualifier, and then the column it names is the head, not the tail. Resolving
        // `location.city` by its tail finds whichever side happens to carry a column
        // called `city` — so a σ on a *left* nested column was pushed into the right
        // input, filtering a relation the predicate says nothing about.
        // NestedPaths owns the rule, including why an open schema is asked nothing:
        // it resolves every name, so its answer is no evidence about which side was
        // meant. The executor asks the same question of the same headings.
        // A bare name is not a path, and asking would let a declared side claim it
        // before the rule below could weigh an open side against it.
        boolean qualified = qualifier != null;
        if (qualified) {
            switch (NestedPaths.ownerOf(reference, left, right)) {
                case LEFT -> { return Side.LEFT; }
                case RIGHT -> { return Side.RIGHT; }
                case BOTH -> { return Side.UNKNOWN; }   // both could answer — do not guess
                case NEITHER -> { /* not a path; the bare name decides below */ }
            }
        }

        Boolean leftHas  = carries(left, right, bare, qualified, true);
        Boolean rightHas = carries(right, left, bare, qualified, false);
        if (leftHas == null || rightHas == null) return Side.UNKNOWN;
        if (leftHas && !rightHas)  return Side.LEFT;
        if (rightHas && !leftHas)  return Side.RIGHT;
        return Side.UNKNOWN;
    }

    /**
     * Whether {@code side} carries the column {@code bare} under that name in the joined
     * row: yes, no, or {@code null} when the heading cannot say.
     *
     * <p>A closed heading answers exactly, and so does an open heading for the columns
     * it knows. Beyond those an open heading resolves every name, so its yes means only
     * that it <em>may</em> hold the name (#971):
     * <ul>
     *   <li>where the other side declares the name, the join's collision rule decides —
     *       the left keeps a shared name and the right's copy becomes {@code name_r}. An
     *       open right therefore cannot own it; an open left may, row by row, so the
     *       answer is yes and the pair is ambiguous;</li>
     *   <li>a <em>qualified</em> name that neither provenance nor the inputs'
     *       qualifiers settled cannot be answered at all. Its qualifier is in scope only
     *       above this join (a view's name, say), and below it would be read as a path;</li>
     *   <li>nor can a name the join itself invents for a collision — {@code c_r} where
     *       the other side has {@code c}. The open input holds that value as {@code c}.</li>
     * </ul>
     */
    private static Boolean carries(Schema side, Schema other, String bare,
                                   boolean qualified, boolean isLeft) {
        if (!side.isOpen() || side.indexOf(bare) >= 0) {
            return side.column(bare).isPresent();
        }
        if (qualified || isCollisionName(bare, other)) {
            return null;
        }
        return isLeft || other.isOpen() || other.column(bare).isEmpty();
    }

    /** Whether {@code name} is {@code c_r}, {@code c_r1}, … for a column {@code c} that {@code other} knows. */
    private static boolean isCollisionName(String name, Schema other) {
        int marker = name.toLowerCase(Locale.ROOT).lastIndexOf("_r");
        if (marker <= 0) {
            return false;
        }
        String suffix = name.substring(marker + 2);
        return suffix.chars().allMatch(Character::isDigit)
                && other.indexOf(name.substring(0, marker)) >= 0;
    }
}

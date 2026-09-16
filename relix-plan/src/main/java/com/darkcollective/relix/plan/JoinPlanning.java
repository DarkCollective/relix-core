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
package com.darkcollective.relix.plan;

import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.AttributeNames;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.Predicate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Join-planning helpers: extracting equi-join key columns from a join condition. The
 * relation names each side exposes as qualifiers, which disambiguate a name both sides
 * carry, are {@link com.darkcollective.relix.ast.Qualifiers#inScope}.
 *
 * <p>Key extraction is sound: only {@code =} comparisons between two attribute
 * references reached through top-level {@code ∧} conjunctions are taken as keys,
 * so the executor can use them as a hash pre-filter while still applying the full
 * condition as a residual.
 */
final class JoinPlanning {

    private JoinPlanning() {
    }

    /**
     * Extracts the equi-join keys from {@code condition}, resolving attribute names
     * against the {@code left}/{@code right} input schemas (using the relation-name
     * sets to disambiguate a name present on both sides).
     */
    static PhysicalNode.JoinKeys extractKeys(Predicate condition,
                                             com.darkcollective.relix.symbol.Schema left,
                                             com.darkcollective.relix.symbol.Schema right,
                                             Set<String> leftRelations,
                                             Set<String> rightRelations) {
        List<Integer> leftIdx = new ArrayList<>();
        List<Integer> rightIdx = new ArrayList<>();
        collectKeys(condition, left, right, leftRelations, rightRelations, leftIdx, rightIdx);
        return new PhysicalNode.JoinKeys(leftIdx, rightIdx);
    }

    private static void collectKeys(Predicate p,
                                    com.darkcollective.relix.symbol.Schema left,
                                    com.darkcollective.relix.symbol.Schema right,
                                    Set<String> leftRel, Set<String> rightRel,
                                    List<Integer> leftIdx, List<Integer> rightIdx) {
        switch (p) {
            case AndPredicate a -> {
                collectKeys(a.left(),  left, right, leftRel, rightRel, leftIdx, rightIdx);
                collectKeys(a.right(), left, right, leftRel, rightRel, leftIdx, rightIdx);
            }
            case ComparisonPredicate c when c.operator() == ComparisonOperator.EQUAL ->
                    addPair(c, left, right, leftRel, rightRel, leftIdx, rightIdx);
            default -> {
                // OR/NOT/other operators are not guaranteed-true conjuncts — leave them
                // to the residual predicate.
            }
        }
    }

    private static void addPair(ComparisonPredicate c,
                                com.darkcollective.relix.symbol.Schema left,
                                com.darkcollective.relix.symbol.Schema right,
                                Set<String> leftRel, Set<String> rightRel,
                                List<Integer> leftIdx, List<Integer> rightIdx) {
        if (!(c.left() instanceof AttributeOperand la) || !(c.right() instanceof AttributeOperand ra)) {
            return;
        }
        Side a = sideOf(la.name(), left, right, leftRel, rightRel);
        Side b = sideOf(ra.name(), left, right, leftRel, rightRel);
        if (a != null && b != null && a.left != b.left) {
            if (a.left) {
                leftIdx.add(a.index);
                rightIdx.add(b.index);
            } else {
                leftIdx.add(b.index);
                rightIdx.add(a.index);
            }
        }
    }

    private record Side(boolean left, int index) {
    }

    /**
     * The decomposed AS-OF ordering inequality: the match-column indices on each
     * side, the direction ({@code backward} = greatest right-value at-or-before the
     * probe), and whether the comparison is strict (excludes an exact match).
     */
    record AsOfMatch(int leftMatchIndex, int rightMatchIndex, boolean backward, boolean strict) {
    }

    /**
     * Extracts the single ordering inequality from an AS-OF match condition,
     * normalised so the probe (left) column is the left operand. The semantic
     * validator has already guaranteed exactly one such conjunct between two
     * attribute references on opposite sides.
     *
     * @return the decomposed match, or {@code null} if no resolvable inequality is
     *         found (defensive — the validator should preclude this)
     */
    static AsOfMatch extractAsOfMatch(Predicate condition,
                                      com.darkcollective.relix.symbol.Schema left,
                                      com.darkcollective.relix.symbol.Schema right,
                                      Set<String> leftRel, Set<String> rightRel) {
        List<ComparisonPredicate> inequalities = new ArrayList<>();
        collectInequalities(condition, inequalities);
        for (ComparisonPredicate c : inequalities) {
            if (!(c.left() instanceof AttributeOperand la) || !(c.right() instanceof AttributeOperand ra)) {
                continue;
            }
            Side a = sideOf(la.name(), left, right, leftRel, rightRel);
            Side b = sideOf(ra.name(), left, right, leftRel, rightRel);
            if (a == null || b == null || a.left == b.left) continue;

            // Orient so the left operand is the probe (left-side) column.
            ComparisonOperator op;
            int leftIdx, rightIdx;
            if (a.left) {
                op = c.operator();  leftIdx = a.index; rightIdx = b.index;
            } else {
                op = flip(c.operator()); leftIdx = b.index; rightIdx = a.index;
            }
            // leftCol op rightCol: >=/> ⇒ backward (greatest right ≤ left); </<= ⇒ forward.
            boolean backward = op == ComparisonOperator.GREATER || op == ComparisonOperator.GREATER_EQUAL;
            boolean strict   = op == ComparisonOperator.GREATER || op == ComparisonOperator.LESS;
            return new AsOfMatch(leftIdx, rightIdx, backward, strict);
        }
        return null;
    }

    private static void collectInequalities(Predicate p, List<ComparisonPredicate> out) {
        switch (p) {
            case AndPredicate a -> {
                collectInequalities(a.left(), out);
                collectInequalities(a.right(), out);
            }
            case ComparisonPredicate c -> {
                switch (c.operator()) {
                    case LESS, LESS_EQUAL, GREATER, GREATER_EQUAL -> out.add(c);
                    default -> { /* equality / ≠ — not an ordering inequality */ }
                }
            }
            default -> { /* OR/NOT/… — precluded by the validator */ }
        }
    }

    private static ComparisonOperator flip(ComparisonOperator op) {
        return switch (op) {
            case LESS          -> ComparisonOperator.GREATER;
            case LESS_EQUAL    -> ComparisonOperator.GREATER_EQUAL;
            case GREATER       -> ComparisonOperator.LESS;
            case GREATER_EQUAL -> ComparisonOperator.LESS_EQUAL;
            case EQUAL         -> ComparisonOperator.EQUAL;
            case NOT_EQUAL     -> ComparisonOperator.NOT_EQUAL;
        };
    }

    private static Side sideOf(String name,
                               com.darkcollective.relix.symbol.Schema left,
                               com.darkcollective.relix.symbol.Schema right,
                               Set<String> leftRel, Set<String> rightRel) {
        int dot = name.lastIndexOf('.');
        String qualifier = dot >= 0 ? name.substring(0, dot) : null;
        String column = AttributeNames.stripQualifier(name);
        int li = left.indexOf(column);
        int ri = right.indexOf(column);

        if (li >= 0 && ri < 0) return new Side(true,  li);
        if (ri >= 0 && li < 0) return new Side(false, ri);
        if (li >= 0 && ri >= 0 && qualifier != null) {
            String q = qualifier.toLowerCase(Locale.ROOT);
            boolean onLeft  = leftRel.contains(q);
            boolean onRight = rightRel.contains(q);
            if (onLeft  && !onRight) return new Side(true,  li);
            if (onRight && !onLeft)  return new Side(false, ri);
        }
        return null;   // unqualified collision or unknown column → residual
    }
}

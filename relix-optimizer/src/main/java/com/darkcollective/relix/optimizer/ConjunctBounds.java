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

import com.darkcollective.relix.ast.AstEquivalence;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BooleanOperand;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.DateOperand;
import com.darkcollective.relix.ast.DurationOperand;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.Predicates;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.ast.TimeOperand;
import com.darkcollective.relix.ast.TimestampOperand;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Range analysis over the top-level conjuncts of a predicate — the machinery behind
 * {@code PRED-004} (contradictory bounds collapse to false) and {@code PRED-005}
 * (a bound another conjunct already implies is dropped).
 *
 * <p>Each conjunct of the form {@code column ⊙ literal} is read as a bound on that
 * column; the bounds on one column fold into an interval, and the interval answers
 * both questions at once — an empty interval is a contradiction, and a bound that is
 * not the tightest in its direction is redundant.
 *
 * <h2>What is a bound</h2>
 * <ul>
 *   <li>{@code x > k} / {@code x >= k} — a lower bound, exclusive / inclusive.</li>
 *   <li>{@code x < k} / {@code x <= k} — an upper bound.</li>
 *   <li>{@code x = k} — both at once, inclusive, which is how {@code x = 5 ∧ x = 6}
 *       falls out as a contradiction without a rule of its own.</li>
 *   <li>{@code x ≠ k} — <strong>not</strong> a bound. It excises a point from the
 *       interior rather than moving an endpoint, so it is treated as unconstraining
 *       and left alone. Modelling it would need a hole set for a gain nothing here
 *       consumes.</li>
 * </ul>
 *
 * <h2>Types</h2>
 * <p>Bounds compare only within one literal kind. Numbers compare as
 * {@link BigDecimal} (so {@code 5} and {@code 5.0} are one value, matching
 * {@link AstEquivalence}); strings, and each of the ADR-0013 temporal kinds, compare
 * by their own natural order. A column carrying bounds of <em>mixed</em> kinds is
 * abandoned rather than compared or rejected — that is a type error for the
 * validator to have caught, not something for a rewrite to have an opinion about.
 *
 * <h2>⚠ NULL, and why the caller controls polarity</h2>
 * <p>{@code x > 5 ∧ x < 3} is FALSE for every non-NULL {@code x} and UNKNOWN when
 * {@code x} is NULL. A filter drops the row either way, so collapsing to false is
 * sound <em>there</em> — but under a {@code ¬} the two part company: {@code ¬FALSE}
 * keeps the row and {@code ¬UNKNOWN} drops it. This class therefore reports a
 * contradiction and lets {@link PredicateSimplifier} decide whether the position it
 * was found in may act on it. <strong>Subsumption has no such restriction</strong> —
 * dropping an implied bound preserves the three-valued result exactly, in any
 * position — which is why the two results are returned separately.
 *
 * <p>Package-private and stateless.
 */
final class ConjunctBounds {

    private ConjunctBounds() {
    }

    /**
     * The outcome of analysing one conjunct list.
     *
     * @param contradiction whether some column's bounds cannot all hold; only
     *                      actionable in a position where FALSE and UNKNOWN behave
     *                      alike (see the class Javadoc)
     * @param retained      the conjuncts that survive subsumption, in their original
     *                      order; meaningless when {@code contradiction} is true
     */
    record Result(boolean contradiction, List<Predicate> retained) {
    }

    /**
     * Analyses {@code conjuncts} for contradictory and redundant bounds.
     *
     * @param conjuncts the top-level conjuncts, in order; must not be null
     * @return the analysis; never null
     */
    static Result analyse(List<Predicate> conjuncts) {
        // Read each conjunct once, keeping the Bound instances: the retention test is
        // identity-based ("is this the tightest bound on its column"), so re-deriving
        // a bound for the second pass would compare a fresh record against the stored
        // one and drop every conjunct.
        List<Bound> bounds = new ArrayList<>(conjuncts.size());
        // Column name (lowercased, qualifier kept — AstEquivalence's policy) → its bounds.
        Map<String, Bounds> byColumn = new LinkedHashMap<>();
        for (Predicate conjunct : conjuncts) {
            Bound bound = boundOf(conjunct);
            bounds.add(bound);
            if (bound != null) {
                byColumn.computeIfAbsent(bound.column, unused -> new Bounds()).add(bound);
            }
        }

        for (Bounds columnBounds : byColumn.values()) {
            if (columnBounds.contradictory()) {
                return new Result(true, conjuncts);
            }
        }

        List<Predicate> retained = new ArrayList<>(conjuncts.size());
        for (int i = 0; i < conjuncts.size(); i++) {
            Bound bound = bounds.get(i);
            if (bound == null || byColumn.get(bound.column).retains(bound)) {
                retained.add(conjuncts.get(i));
            }
        }
        return new Result(false, retained);
    }

    // =========================================================================
    // Bound extraction
    // =========================================================================

    /** One conjunct read as a bound, or null when it is not one. */
    private record Bound(String column, ComparisonOperator operator, Operand literal,
                         Predicate source) {

        boolean lower() {
            return operator == ComparisonOperator.GREATER
                    || operator == ComparisonOperator.GREATER_EQUAL
                    || operator == ComparisonOperator.EQUAL;
        }

        boolean upper() {
            return operator == ComparisonOperator.LESS
                    || operator == ComparisonOperator.LESS_EQUAL
                    || operator == ComparisonOperator.EQUAL;
        }

        boolean inclusive() {
            return operator != ComparisonOperator.GREATER && operator != ComparisonOperator.LESS;
        }
    }

    /**
     * Reads {@code conjunct} as a bound. Accepts either operand order — {@code 5 < x}
     * is the same bound as {@code x > 5} — so the analysis does not depend on
     * {@code PRED-003} having normalised the comparison first.
     */
    private static Bound boundOf(Predicate conjunct) {
        if (!(conjunct instanceof ComparisonPredicate c)) {
            return null;
        }
        if (c.left() instanceof AttributeOperand a && orderable(c.right())) {
            return bound(a.name(), c.operator(), c.right(), c);
        }
        if (c.right() instanceof AttributeOperand a && orderable(c.left())) {
            return bound(a.name(), mirror(c.operator()), c.left(), c);
        }
        return null;
    }

    private static Bound bound(String column, ComparisonOperator op, Operand literal,
                               Predicate source) {
        // ≠ excises a point rather than moving an endpoint — not a bound.
        return op == ComparisonOperator.NOT_EQUAL ? null
                : new Bound(column.toLowerCase(Locale.ROOT), op, literal, source);
    }

    private static ComparisonOperator mirror(ComparisonOperator op) {
        return PredicateSimplifier.mirror(op);
    }

    /** {@return whether {@code op} is a literal this analysis can order} */
    private static boolean orderable(Operand op) {
        // BOOLEAN is a literal but not usefully orderable; every other literal kind is.
        return Predicates.isLiteral(op) && !(op instanceof BooleanOperand);
    }

    // =========================================================================
    // Per-column interval
    // =========================================================================

    /** The accumulated interval for one column, plus the bounds that produced it. */
    private static final class Bounds {

        private final List<Bound> all = new ArrayList<>();
        private Bound tightestLower;
        private Bound tightestUpper;
        private boolean incomparable;   // mixed literal kinds — abandon this column

        void add(Bound bound) {
            all.add(bound);
            if (incomparable) {
                return;
            }
            if (bound.lower()) {
                tightestLower = tighter(tightestLower, bound, true);
            }
            if (bound.upper()) {
                tightestUpper = tighter(tightestUpper, bound, false);
            }
        }

        /** Returns whichever bound admits fewer values, or {@code candidate} if there is no incumbent. */
        private Bound tighter(Bound incumbent, Bound candidate, boolean isLower) {
            if (incumbent == null) {
                return candidate;
            }
            Integer cmp = compare(incumbent.literal, candidate.literal);
            if (cmp == null) {
                incomparable = true;
                return incumbent;
            }
            if (cmp == 0) {
                // Same endpoint: exclusive is tighter than inclusive. The incumbent wins
                // a true tie, so the earliest-written of two identical bounds is kept.
                return incumbent.inclusive() && !candidate.inclusive() ? candidate : incumbent;
            }
            boolean candidateTighter = isLower ? cmp < 0 : cmp > 0;
            return candidateTighter ? candidate : incumbent;
        }

        /** {@return whether the accumulated interval admits no value at all} */
        boolean contradictory() {
            if (incomparable || tightestLower == null || tightestUpper == null) {
                return false;
            }
            Integer cmp = compare(tightestLower.literal, tightestUpper.literal);
            if (cmp == null) {
                return false;
            }
            if (cmp > 0) {
                return true;    // lower above upper — empty however the endpoints are read
            }
            // Equal endpoints: empty unless both include them (x >= 5 ∧ x <= 5 holds at 5).
            return cmp == 0 && !(tightestLower.inclusive() && tightestUpper.inclusive());
        }

        /**
         * {@return whether {@code bound}'s conjunct must be kept}
         *
         * <p>A bound is dropped only when a <em>different</em> conjunct on the same
         * column is at least as tight in the same direction. A bound that is both a
         * lower and an upper bound (an equality) is kept unless it is redundant in
         * both directions, so {@code x = 5 ∧ x > 3} keeps the equality and drops the
         * range rather than the other way round.
         */
        boolean retains(Bound bound) {
            if (incomparable) {
                return true;
            }
            boolean lowerNeeded = bound.lower() && bound == tightestLower;
            boolean upperNeeded = bound.upper() && bound == tightestUpper;
            return lowerNeeded || upperNeeded;
        }
    }

    // =========================================================================
    // Literal comparison
    // =========================================================================

    /**
     * Compares two literals of the same kind, or returns {@code null} when they are
     * of different kinds (or a number does not parse).  Never throws: a type error is
     * the validator's to report, and a rewrite that met one must decline, not fail.
     */
    private static Integer compare(Operand a, Operand b) {
        if (a instanceof NumberOperand x && b instanceof NumberOperand y) {
            try {
                return new BigDecimal(x.value()).compareTo(new BigDecimal(y.value()));
            } catch (NumberFormatException notADecimal) {
                return null;
            }
        }
        if (a instanceof StringOperand x && b instanceof StringOperand y) {
            return x.value().compareTo(y.value());
        }
        if (a instanceof DateOperand x && b instanceof DateOperand y) {
            return x.value().compareTo(y.value());
        }
        if (a instanceof TimeOperand x && b instanceof TimeOperand y) {
            return x.value().compareTo(y.value());
        }
        if (a instanceof TimestampOperand x && b instanceof TimestampOperand y) {
            return x.value().compareTo(y.value());
        }
        if (a instanceof DurationOperand x && b instanceof DurationOperand y) {
            return x.value().compareTo(y.value());
        }
        return null;
    }
}

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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Utilities for taking a {@link Predicate} tree apart and putting it back
 * together.
 *
 * <p>Splitting a filter into its top-level conjuncts, deciding which of them a
 * rewrite may move, and re-conjoining the remainder is the shape of nearly every
 * selection-pushdown rule. These helpers live here — beside the AST they operate
 * on — so that the passes performing those rewrites share one definition rather
 * than each carrying a private copy.
 *
 * <p>That sharing is not cosmetic. The copies had already drifted: one
 * {@code isLiteral} predated the temporal types and still recognised only
 * NUMBER/STRING/BOOLEAN, so a rewrite keyed on it silently skipped temporal
 * literals.
 *
 * <p>Note that a conjunct-splitter which also <em>validates</em> — rejecting
 * OR/NOT rather than treating them as opaque leaves — is a different operation
 * and deliberately not folded in here.
 */
public final class Predicates {

    private Predicates() {
    }

    /**
     * Splits {@code p} into its top-level conjuncts.
     *
     * <p>Descends only through {@link AndPredicate}; every other node — including
     * an {@code OR} or {@code NOT} that may itself contain conjunctions — is an
     * opaque leaf. {@code a ∧ (b ∧ c)} yields {@code [a, b, c]}; a predicate that
     * is not a conjunction at all yields itself.
     *
     * @param p the predicate to split; must not be null
     * @return its top-level conjuncts, in left-to-right order; never empty
     */
    public static List<Predicate> conjuncts(Predicate p) {
        Objects.requireNonNull(p, "p");
        List<Predicate> out = new ArrayList<>();
        flatten(p, out);
        return out;
    }

    private static void flatten(Predicate p, List<Predicate> out) {
        if (p instanceof AndPredicate a) {
            flatten(a.left(), out);
            flatten(a.right(), out);
        } else {
            out.add(p);
        }
    }

    /**
     * Folds conjuncts back into a single predicate as a left-deep
     * {@link AndPredicate} chain — the inverse of {@link #conjuncts}.
     *
     * <p>Each combining node takes its location from the right-hand conjunct, so
     * a diagnostic points at the conjunct being added rather than the whole chain.
     *
     * @param conjuncts the conjuncts to combine; must not be null or empty
     * @return the conjunction, or the sole element when there is only one
     * @throws IllegalArgumentException if {@code conjuncts} is empty
     */
    public static Predicate conjoin(List<Predicate> conjuncts) {
        Objects.requireNonNull(conjuncts, "conjuncts");
        if (conjuncts.isEmpty()) {
            throw new IllegalArgumentException("conjoin requires at least one conjunct");
        }
        Predicate combined = conjuncts.get(0);
        for (int i = 1; i < conjuncts.size(); i++) {
            combined = new AndPredicate(combined, conjuncts.get(i), conjuncts.get(i).location());
        }
        return combined;
    }

    /**
     * Returns whether {@code op} is a compile-time constant literal.
     *
     * <p>True for every literal operand form the language has, including the
     * temporal literals. False for anything computed — an attribute
     * reference, an arithmetic expression, a function call, or a set/struct/array
     * construction.
     *
     * @param op the operand to classify; must not be null
     * @return {@code true} if the operand is a literal constant
     */
    public static boolean isLiteral(Operand op) {
        Objects.requireNonNull(op, "op");
        return op instanceof StringOperand
                || op instanceof NumberOperand
                || op instanceof BooleanOperand
                || op instanceof DateOperand
                || op instanceof TimeOperand
                || op instanceof TimestampOperand
                || op instanceof DurationOperand;
    }

    /**
     * Returns whether {@code op} is a reference to {@code column}, comparing
     * case-insensitively and ignoring any relation qualifier — so {@code Users.id},
     * {@code id}, and {@code ID} all match {@code "id"}.
     *
     * @param op     the operand to test; must not be null
     * @param column the bare column name to match; must not be null
     * @return {@code true} if the operand references that column
     */
    public static boolean isColumn(Operand op, String column) {
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(column, "column");
        return op instanceof AttributeOperand a
                && a.unqualifiedName().equalsIgnoreCase(column);
    }

    /**
     * Returns the literal that {@code column} is pinned to, if {@code p} is an
     * equality between that column and a constant.
     *
     * <p>Accepts either argument order, so both {@code id = 5} and {@code 5 = id}
     * yield the literal {@code 5}. Any other predicate — a different operator, a
     * different column, or a comparison against something computed — yields
     * {@link Optional#empty()}.
     *
     * @param p      the predicate to inspect; must not be null
     * @param column the bare column name of interest; must not be null
     * @return the literal the column equals, or empty
     */
    public static Optional<Operand> equalityLiteralFor(Predicate p, String column) {
        Objects.requireNonNull(p, "p");
        Objects.requireNonNull(column, "column");
        if (!(p instanceof ComparisonPredicate c) || c.operator() != ComparisonOperator.EQUAL) {
            return Optional.empty();
        }
        if (isColumn(c.left(), column) && isLiteral(c.right())) {
            return Optional.of(c.right());
        }
        if (isColumn(c.right(), column) && isLiteral(c.left())) {
            return Optional.of(c.left());
        }
        return Optional.empty();
    }
}

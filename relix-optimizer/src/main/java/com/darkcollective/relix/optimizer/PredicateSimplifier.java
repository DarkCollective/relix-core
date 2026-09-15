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

import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.AstEquivalence;
import com.darkcollective.relix.ast.BooleanOperand;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.ElementOfPredicate;
import com.darkcollective.relix.ast.NotPredicate;
import com.darkcollective.relix.ast.NullPredicate;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.OrPredicate;
import com.darkcollective.relix.ast.PatternPredicate;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.Predicates;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.ast.visitor.PredicatePrettyPrinter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Bottom-up rewriter that applies predicate-simplification rules
 * {@code PRED-001} through {@code PRED-006} to {@link Predicate} trees.
 *
 * <p>Rules applied (in order, per node, after children are simplified):
 * <ul>
 *   <li><b>PRED-001</b> — constant predicate folding, in both directions:
 *       {@code TRUE ∧ p → p} and {@code p ∨ FALSE → p} eliminate a neutral branch,
 *       {@code FALSE ∧ p → FALSE} and {@code TRUE ∨ p → TRUE} short-circuit an
 *       absorbing one, and {@code ¬FALSE → TRUE}.  "Always true/false" is determined
 *       by evaluating {@link ComparisonPredicate} nodes whose both operands are
 *       compile-time literals, and a folded result is written back as the canonical
 *       {@link #constant} predicate.</li>
 *   <li><b>PRED-002</b> — double logical-NOT elimination:
 *       {@code ¬(¬p) → p}.</li>
 *   <li><b>PRED-003</b> — comparison normalisation: when the left operand of
 *       a comparison is a literal and the right is not, the operands are
 *       swapped and the operator mirrored so the attribute (or expression)
 *       is always on the left ({@code 5 > age → age < 5}).  This canonical
 *       form makes subsequent rule-matching simpler.</li>
 *   <li><b>PRED-004</b> — contradiction detection: the bounds a conjunction places on
 *       one column fold into an interval, and an empty interval collapses the whole
 *       conjunction to false ({@code x > 5 ∧ x < 3}, {@code x = 5 ∧ x = 6}).
 *       {@code EMPTY-001} then deletes the selection entirely.</li>
 *   <li><b>PRED-005</b> — subsumption: a bound another conjunct on the same column
 *       already implies is dropped ({@code x > 5 ∧ x > 3 → x > 5}).</li>
 *   <li><b>PRED-006</b> — duplicate-conjunct removal ({@code p ∧ p → p}), compared
 *       with {@link AstEquivalence} so that two occurrences written at different
 *       source positions — which is every real case — are recognised as one.</li>
 * </ul>
 *
 * <p>The interval machinery lives in {@link ConjunctBounds}; that class's Javadoc
 * records why {@code PRED-004} is suppressed under a {@code ¬} while
 * {@code PRED-005}/{@code PRED-006} are not.
 *
 * <p>This class is package-private and works in concert with
 * {@link PredicateSimplificationPass}, which drives the {@link
 * com.darkcollective.relix.ast.RelNode}
 * tree walk.  It has a second driver: {@link OperandSimplifier} applies it to the
 * predicate a {@link com.darkcollective.relix.ast.ConditionOperand} wraps, which is the
 * only way these rules reach a condition written in operand position (an {@code IIf}
 * test).  This class never calls back into the operand simplifier, so that pairing
 * cannot cycle.
 */
final class PredicateSimplifier {

    /** Renders a predicate for the human-readable half of a transformation record. */
    private static final PredicatePrettyPrinter PRED = new PredicatePrettyPrinter();

    private final String queryName;
    private final OptimizationContext ctx;

    /**
     * Creates a new simplifier that records transformations under
     * {@code queryName} into {@code ctx}.
     *
     * @param queryName display name used in transformation records
     * @param ctx       accumulator for transformation records
     */
    PredicateSimplifier(String queryName, OptimizationContext ctx) {
        this.queryName = queryName;
        this.ctx = ctx;
    }

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Returns a semantically equivalent, simplified form of {@code predicate}.
     * Returns the same instance when no rule fires.
     *
     * @param predicate the predicate to simplify; must not be null
     * @return the simplified predicate, or {@code predicate} unchanged
     */
    Predicate simplify(Predicate predicate) {
        return simplify(predicate, false);
    }

    /**
     * Simplifies {@code predicate}, tracking whether it sits under a logical
     * negation.
     *
     * @param negated whether some enclosing {@code ¬} makes this a negative position,
     *                in which case {@code PRED-004} must not fire — see
     *                {@link #simplifyAnd}
     */
    private Predicate simplify(Predicate predicate, boolean negated) {
        return switch (predicate) {
            case ComparisonPredicate c -> simplifyComparison(c);
            case AndPredicate        a -> simplifyAnd(a, negated);
            case OrPredicate         o -> simplifyOr(o, negated);
            case NotPredicate        n -> simplifyNot(n);
            // NullPredicate, ElementOfPredicate, and PatternPredicate have no operand-structure
            // that these rules apply to; return as-is.
            case NullPredicate      n  -> n;
            case ElementOfPredicate e  -> e;
            case PatternPredicate   p  -> p;
        };
    }

    // =========================================================================
    // Constant predicates
    // =========================================================================

    /**
     * The canonical constant-false predicate — a comparison of two boolean literals,
     * which {@link #evalConstant} folds and every downstream consumer (evaluator, SQL
     * renderer, pretty printer) already understands.
     *
     * <p>There is no {@code Predicate} arm for a bare constant, and adding one would
     * ripple through every predicate switch in the engine for no gain: a comparison of
     * two literals <em>is</em> a constant, it evaluates in O(1) per row, and
     * {@code EMPTY-001} removes even that cost by deleting the selection outright.
     */
    static Predicate constant(boolean value) {
        return new ComparisonPredicate(new BooleanOperand(value), ComparisonOperator.EQUAL,
                new BooleanOperand(true));
    }

    /** {@return whether {@code p} is statically {@code value}} */
    private static boolean isConstant(Predicate p, boolean value) {
        return evalConstant(p).filter(v -> v == value).isPresent();
    }

    // =========================================================================
    // Rule implementations
    // =========================================================================

    /**
     * PRED-003: if the literal is on the left and the non-literal is on the
     * right, swap them and mirror the operator.
     */
    private Predicate simplifyComparison(ComparisonPredicate c) {
        if (Predicates.isLiteral(c.left()) && !Predicates.isLiteral(c.right())) {
            ComparisonOperator mirrored = mirror(c.operator());
            ctx.record(OptimizationCode.PRED_003, queryName,
                    "literal moved to right: " + operandLabel(c.left())
                            + " " + c.operator() + " x  →  x "
                            + mirrored + " " + operandLabel(c.left()),
                    c.location());
            return new ComparisonPredicate(c.right(), mirrored, c.left(), c.location());
        }
        return c;
    }

    /**
     * PRED-001 (both directions), PRED-004, PRED-005 and PRED-006 over a conjunction.
     * PRED-002 (double NOT) is applied when recursing so by the time we
     * evaluate children they are already simplified.
     *
     * <h2>Why {@code negated} exists</h2>
     * <p>{@code x > 5 ∧ x < 3} is FALSE for every non-NULL {@code x} and UNKNOWN when
     * {@code x} is NULL. A filter drops the row either way, so collapsing it to false
     * is sound in a positive position — but {@code ¬FALSE} <em>keeps</em> a row where
     * {@code ¬UNKNOWN} drops it, so the same collapse under a {@code ¬} changes the
     * answer for exactly the NULL rows. {@code PRED-004} is therefore suppressed in a
     * negative position. {@code PRED-005} and {@code PRED-006} carry no such
     * restriction: dropping an implied or repeated conjunct preserves the
     * three-valued result exactly, so they fire anywhere.
     */
    private Predicate simplifyAnd(AndPredicate a, boolean negated) {
        Predicate newLeft  = simplify(a.left(), negated);
        Predicate newRight = simplify(a.right(), negated);

        // FALSE AND p → FALSE (and the mirror). Sound in any position: FALSE is
        // absorbing for ∧ in three-valued logic too.
        if (isConstant(newLeft, false) || isConstant(newRight, false)) {
            ctx.record(OptimizationCode.PRED_001, queryName,
                    "constant-false branch short-circuits AND", a.location());
            return constant(false);
        }
        // TRUE AND p → p
        if (isConstant(newLeft, true)) {
            ctx.record(OptimizationCode.PRED_001, queryName,
                    "constant-true left branch eliminated from AND", a.location());
            return newRight;
        }
        // p AND TRUE → p
        if (isConstant(newRight, true)) {
            ctx.record(OptimizationCode.PRED_001, queryName,
                    "constant-true right branch eliminated from AND", a.location());
            return newLeft;
        }

        Predicate rebuilt = (newLeft != a.left() || newRight != a.right())
                ? new AndPredicate(newLeft, newRight, a.location())
                : a;
        return simplifyConjuncts(rebuilt, negated);
    }

    /**
     * PRED-006 then PRED-004/PRED-005 over the whole conjunct list of {@code p}.
     *
     * <p>Deduplication runs first so an exact repeat is attributed to {@code PRED-006}
     * rather than falling out of the range analysis as a bound subsumed by its own
     * twin.
     */
    private Predicate simplifyConjuncts(Predicate p, boolean negated) {
        List<Predicate> conjuncts = Predicates.conjuncts(p);
        if (conjuncts.size() < 2) {
            return p;
        }

        List<Predicate> deduped = new ArrayList<>(conjuncts.size());
        for (Predicate conjunct : conjuncts) {
            if (deduped.stream().anyMatch(kept -> AstEquivalence.equivalent(kept, conjunct))) {
                ctx.record(OptimizationCode.PRED_006, queryName,
                        "duplicate conjunct removed: " + conjunct.accept(PRED),
                        conjunct.location());
            } else {
                deduped.add(conjunct);
            }
        }

        ConjunctBounds.Result bounds = ConjunctBounds.analyse(deduped);
        if (bounds.contradiction()) {
            if (negated) {
                // Sound to detect, unsound to act on here — see simplifyAnd.
                return rebuild(p, deduped);
            }
            ctx.record(OptimizationCode.PRED_004, queryName,
                    "contradictory bounds collapse to false: " + p.accept(PRED),
                    p.location());
            return constant(false);
        }
        for (Predicate dropped : deduped) {
            if (bounds.retained().stream().noneMatch(kept -> kept == dropped)) {
                ctx.record(OptimizationCode.PRED_005, queryName,
                        "bound implied by a tighter conjunct removed: " + dropped.accept(PRED),
                        dropped.location());
            }
        }
        return rebuild(p, bounds.retained());
    }

    /** Re-conjoins {@code conjuncts}, returning {@code original} when nothing was removed. */
    private static Predicate rebuild(Predicate original, List<Predicate> conjuncts) {
        return conjuncts.size() == Predicates.conjuncts(original).size()
                ? original
                : Predicates.conjoin(conjuncts);
    }

    /**
     * PRED-001: eliminate a constant-false branch from OR, and short-circuit a
     * constant-true one.
     */
    private Predicate simplifyOr(OrPredicate o, boolean negated) {
        Predicate newLeft  = simplify(o.left(), negated);
        Predicate newRight = simplify(o.right(), negated);

        // TRUE OR p → TRUE (and the mirror): TRUE is absorbing for ∨ under 3VL too.
        if (isConstant(newLeft, true) || isConstant(newRight, true)) {
            ctx.record(OptimizationCode.PRED_001, queryName,
                    "constant-true branch short-circuits OR", o.location());
            return constant(true);
        }
        // FALSE OR p → p
        if (isConstant(newLeft, false)) {
            ctx.record(OptimizationCode.PRED_001, queryName,
                    "constant-false left branch eliminated from OR", o.location());
            return newRight;
        }
        // p OR FALSE → p
        if (isConstant(newRight, false)) {
            ctx.record(OptimizationCode.PRED_001, queryName,
                    "constant-false right branch eliminated from OR", o.location());
            return newLeft;
        }

        return (newLeft != o.left() || newRight != o.right())
                ? new OrPredicate(newLeft, newRight, o.location())
                : o;
    }

    /**
     * PRED-002: ¬(¬p) → p.
     *
     * <p>Everything below a {@code ¬} is simplified in a negative position, which is
     * what suppresses {@code PRED-004} there. The flag is not toggled back by a second
     * {@code ¬} — {@code PRED-002} has already collapsed those — so nesting only ever
     * makes the analysis more conservative, never less.
     */
    private Predicate simplifyNot(NotPredicate n) {
        Predicate inner = simplify(n.predicate(), true);
        if (inner instanceof NotPredicate inner2) {
            ctx.record(OptimizationCode.PRED_002, queryName,
                    "double NOT eliminated", n.location());
            return inner2.predicate();
        }
        // ¬FALSE → TRUE, ¬TRUE → FALSE. A constant here is a genuine constant: it is
        // the negation of a literal comparison, which has no NULL reading.
        Optional<Boolean> constantInner = evalConstant(inner);
        if (constantInner.isPresent()) {
            ctx.record(OptimizationCode.PRED_001, queryName,
                    "negation of a constant folded", n.location());
            return constant(!constantInner.get());
        }
        return inner != n.predicate() ? new NotPredicate(inner, n.location()) : n;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Evaluates a predicate to a constant boolean if it is a comparison
     * with two literal operands of the same type.  Returns empty when the
     * predicate cannot be statically evaluated.
     */
    static Optional<Boolean> evalConstant(Predicate p) {
        if (!(p instanceof ComparisonPredicate c)) return Optional.empty();

        // Number vs Number
        if (c.left() instanceof NumberOperand ln && c.right() instanceof NumberOperand rn) {
            try {
                int cmp = new BigDecimal(ln.value()).compareTo(new BigDecimal(rn.value()));
                return Optional.of(applyOp(c.operator(), cmp));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        // String vs String
        if (c.left() instanceof StringOperand ls && c.right() instanceof StringOperand rs) {
            int cmp = ls.value().compareTo(rs.value());
            return Optional.of(applyOp(c.operator(), cmp));
        }
        // Boolean vs Boolean (only EQUAL / NOT_EQUAL make sense)
        if (c.left() instanceof BooleanOperand lb && c.right() instanceof BooleanOperand rb) {
            return switch (c.operator()) {
                case EQUAL     -> Optional.of(lb.value() == rb.value());
                case NOT_EQUAL -> Optional.of(lb.value() != rb.value());
                default        -> Optional.empty();
            };
        }
        return Optional.empty();
    }

    private static boolean applyOp(ComparisonOperator op, int cmp) {
        return switch (op) {
            case EQUAL         -> cmp == 0;
            case NOT_EQUAL     -> cmp != 0;
            case LESS          -> cmp < 0;
            case LESS_EQUAL    -> cmp <= 0;
            case GREATER       -> cmp > 0;
            case GREATER_EQUAL -> cmp >= 0;
        };
    }


    /** Mirrors a comparison operator for operand-swap. */
    static ComparisonOperator mirror(ComparisonOperator op) {
        return switch (op) {
            case EQUAL         -> ComparisonOperator.EQUAL;
            case NOT_EQUAL     -> ComparisonOperator.NOT_EQUAL;
            case LESS          -> ComparisonOperator.GREATER;
            case LESS_EQUAL    -> ComparisonOperator.GREATER_EQUAL;
            case GREATER       -> ComparisonOperator.LESS;
            case GREATER_EQUAL -> ComparisonOperator.LESS_EQUAL;
        };
    }

    private static String operandLabel(Operand op) {
        return switch (op) {
            case NumberOperand  n -> n.value();
            case StringOperand  s -> "\"" + s.value() + "\"";
            case BooleanOperand b -> String.valueOf(b.value());
            default               -> op.getClass().getSimpleName();
        };
    }
}

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

import com.darkcollective.relix.optimizer.OptimizationCode;
import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.BooleanOperand;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.ConditionOperand;
import com.darkcollective.relix.ast.ElementOfPredicate;
import com.darkcollective.relix.ast.NotPredicate;
import com.darkcollective.relix.ast.NullPredicate;
import com.darkcollective.relix.ast.OrPredicate;
import com.darkcollective.relix.ast.PatternPredicate;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.DateOperand;
import com.darkcollective.relix.ast.TimeOperand;
import com.darkcollective.relix.ast.TimestampOperand;
import com.darkcollective.relix.ast.DurationOperand;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.ArrayConstruction;
import com.darkcollective.relix.ast.SetLiteralOperand;
import com.darkcollective.relix.ast.StructConstruction;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.ast.UnaryOperand;
import com.darkcollective.relix.symbol.FunctionProperty;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * Bottom-up rewriter that applies expression-simplification rules
 * {@code EXPR-001} through {@code EXPR-006} to {@link Operand} trees.
 *
 * <p>Each invocation of {@link #simplify(Operand)} rewrites the entire
 * subtree rooted at the given operand using a post-order (bottom-up) walk:
 * children are simplified before the current node's own rules are tried.
 * This ensures that constant folding at a child can expose identity-law
 * matches at the parent (e.g. {@code (2 * 3) + 0} → {@code 6 + 0} →
 * {@code 6}) in a single pass.
 *
 * <p>Every simplification is recorded in the {@link OptimizationContext}
 * supplied at construction time.
 *
 * <h2>Rules applied</h2>
 * <ul>
 *   <li><b>EXPR-001</b> — numeric constant folding:
 *       {@code NumberLiteral op NumberLiteral → NumberLiteral}</li>
 *   <li><b>EXPR-002</b> — string constant concatenation:
 *       {@code StringLiteral + StringLiteral → StringLiteral}</li>
 *   <li><b>EXPR-003</b> — additive identity elimination:
 *       {@code x + 0 → x}, {@code x − 0 → x}, {@code 0 + x → x}</li>
 *   <li><b>EXPR-004</b> — multiplicative identity elimination:
 *       {@code x * 1 → x}, {@code 1 * x → x}, {@code x / 1 → x}</li>
 *   <li><b>EXPR-005</b> — multiplication by zero:
 *       {@code x * 0 → 0}, {@code 0 * x → 0}</li>
 *   <li><b>EXPR-006</b> — double negation elimination:
 *       {@code -(-x) → x}</li>
 *   <li><b>EXPR-007</b> — constant term accumulation via associativity+commutativity:
 *       {@code (x + k1) + k2 → x + (k1+k2)},
 *       {@code (x * k1) * k2 → x * (k1*k2)}, and symmetric forms.</li>
 *   <li><b>EXPR-008</b> — idempotent built-in function call elimination:
 *       {@code f(f(x)) → f(x)} when {@code f} is tagged
 *       {@link FunctionProperty#IDEMPOTENT} in the built-in registry.</li>
 * </ul>
 *
 * <p>Division by zero is never folded (EXPR-001 is skipped for
 * {@code n / 0} to avoid runtime errors at optimization time).
 *
 * <h2>Conditions in operand position</h2>
 * <p>A {@link ConditionOperand} — the boolean an {@code IIf}/{@code Nz}/{@code Coalesce}
 * tests — wraps a whole {@link Predicate}.  No <em>arithmetic</em> law applies to it, but
 * that is a statement about the wrapper, not about what it contains: the operands nested
 * inside the condition are ordinary expressions, and the condition itself is exactly what
 * {@link PredicateSimplifier} simplifies.  This class therefore recurses into it
 * ({@link #simplifyCondition}), applying the operand rules and then the predicate rules,
 * so {@code IIf(qty * 1 > 0, …)} folds like any other expression.  The recursion is
 * one-way — {@code PredicateSimplifier} never calls back — so a nested {@code IIf}
 * terminates on the operand tree's own depth.
 *
 * <p>This class is package-private; external callers should use
 * {@link ExpressionSimplificationPass#apply} which wires this simplifier
 * into a full {@link com.darkcollective.relix.ast.RelNode} tree walk.
 */
final class OperandSimplifier {

    private final String queryName;
    private final OptimizationContext ctx;

    /**
     * Applies {@code PRED-001..003} to the predicate a {@link ConditionOperand} wraps.
     * Never calls back into this class, so the mutual recursion bottoms out on the
     * operand tree's own depth.
     */
    private final PredicateSimplifier predicates;

    /**
     * Creates a new simplifier that records transformations under
     * {@code queryName} into {@code ctx}.
     *
     * @param queryName display name used in transformation records
     * @param ctx       accumulator for transformation records
     */
    OperandSimplifier(String queryName, OptimizationContext ctx) {
        this.queryName = queryName;
        this.ctx = ctx;
        this.predicates = new PredicateSimplifier(queryName, ctx);
    }

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Returns a semantically equivalent, simplified form of {@code operand}.
     * Returns the same instance when no rule fires.
     *
     * @param operand the operand to simplify; must not be null
     * @return the simplified operand, or {@code operand} unchanged
     */
    Operand simplify(Operand operand) {
        return switch (operand) {
            case AttributeOperand  a -> a;
            case NumberOperand     n -> n;
            case StringOperand     s -> s;
            case BooleanOperand    b -> b;
            case DateOperand       d -> d;
            case TimeOperand       t -> t;
            case TimestampOperand  ts -> ts;
            case DurationOperand   du -> du;
            case UnaryOperand      u -> simplifyUnary(u);
            case BinaryArithmeticExpression b -> simplifyBinary(b);
            case FunctionCall      f -> simplifyFunctionCall(f);
            case SetLiteralOperand s -> simplifySet(s);
            case StructConstruction struct -> simplifyStruct(struct);
            case ArrayConstruction  array  -> simplifyArray(array);
            // No arithmetic law applies to the boolean operand *itself* — but it wraps
            // a predicate, which is exactly what the predicate rules simplify.
            case ConditionOperand   c -> simplifyCondition(c);
        };
    }

    /**
     * Rewrites every {@link Operand} reachable through {@code predicate}, returning the
     * same instance when nothing changed.
     *
     * <p>This is the seam that reaches operands nested inside a predicate — the ones in
     * a comparison's two sides, a {@code NULL} test, an {@code ∈} membership, a
     * {@code LIKE} pattern — recursing the {@code AND}/{@code OR}/{@code NOT} structure
     * on the way.  {@link ExpressionSimplificationPass} drives it for the predicates a
     * {@code σ} or a conditional join carries; {@link #simplifyCondition} drives it for
     * the predicate a {@link ConditionOperand} wraps.
     *
     * @param predicate the predicate whose operands to simplify; must not be null
     * @return the rewritten predicate, or {@code predicate} unchanged
     */
    Predicate simplifyWithin(Predicate predicate) {
        return switch (predicate) {
            case ComparisonPredicate c -> {
                Operand newLeft  = simplify(c.left());
                Operand newRight = simplify(c.right());
                yield (newLeft != c.left() || newRight != c.right())
                        ? new ComparisonPredicate(newLeft, c.operator(), newRight, c.location())
                        : c;
            }
            case NullPredicate n -> {
                Operand newOperand = simplify(n.operand());
                yield newOperand != n.operand()
                        ? new NullPredicate(newOperand, n.isNull(), n.location())
                        : n;
            }
            case ElementOfPredicate e -> {
                Operand newElem = simplify(e.element());
                Operand newSet  = simplify(e.setExpression());
                yield (newElem != e.element() || newSet != e.setExpression())
                        ? new ElementOfPredicate(newElem, newSet, e.isNegated(), e.location())
                        : e;
            }
            case PatternPredicate p -> {
                Operand newOperand = simplify(p.operand());
                Operand newPattern = simplify(p.pattern());
                yield (newOperand != p.operand() || newPattern != p.pattern())
                        ? new PatternPredicate(newOperand, newPattern, p.negated(), p.location())
                        : p;
            }
            case AndPredicate a -> {
                Predicate newLeft  = simplifyWithin(a.left());
                Predicate newRight = simplifyWithin(a.right());
                yield (newLeft != a.left() || newRight != a.right())
                        ? new AndPredicate(newLeft, newRight, a.location())
                        : a;
            }
            case OrPredicate o -> {
                Predicate newLeft  = simplifyWithin(o.left());
                Predicate newRight = simplifyWithin(o.right());
                yield (newLeft != o.left() || newRight != o.right())
                        ? new OrPredicate(newLeft, newRight, o.location())
                        : o;
            }
            case NotPredicate n -> {
                Predicate newInner = simplifyWithin(n.predicate());
                yield newInner != n.predicate()
                        ? new NotPredicate(newInner, n.location())
                        : n;
            }
        };
    }

    /**
     * Simplifies the predicate a {@link ConditionOperand} wraps — the condition of an
     * {@code IIf}/{@code Nz}/{@code Coalesce}, or any other predicate written in operand
     * position.
     *
     * <p>Both halves apply, in the pipeline's own order: the operands nested inside the
     * condition are folded first ({@code EXPR-*}), then the predicate rules run over the
     * result ({@code PRED-*}), so {@code IIf(0 + qty * 1 > 0, …)} normalises exactly as
     * the same test written as a {@code σ} would.
     *
     * <p>The rewrite is <em>expression-preserving</em>, never branch-selecting: it changes
     * how the condition is spelled, not which branch the short-circuiting evaluator picks.
     */
    private Operand simplifyCondition(ConditionOperand c) {
        Predicate simplified = predicates.simplify(simplifyWithin(c.predicate()));
        return simplified != c.predicate()
                ? new ConditionOperand(simplified, c.location())
                : c;
    }

    // =========================================================================
    // Private rule implementations
    // =========================================================================

    /** EXPR-006: {@code -(-x) → x}. */
    private Operand simplifyUnary(UnaryOperand u) {
        Operand inner = simplify(u.operand());
        if (inner instanceof UnaryOperand inner2) {
            ctx.record(OptimizationCode.EXPR_006, queryName,
                    "double negation eliminated", u.location());
            return inner2.operand();
        }
        return inner != u.operand() ? new UnaryOperand(inner, u.location()) : u;
    }

    /**
     * Applies EXPR-001 through EXPR-005 to a binary arithmetic expression.
     * Children are simplified first (bottom-up).
     */
    private Operand simplifyBinary(BinaryArithmeticExpression b) {
        Operand left  = simplify(b.left());
        Operand right = simplify(b.right());

        // ── EXPR-001: both operands are numeric literals → fold ──────────────
        if (left instanceof NumberOperand ln && right instanceof NumberOperand rn) {
            // Skip divide-by-zero to avoid promoting a runtime error to compile-time.
            if (b.operator() != ArithmeticOperator.DIVIDE || !isZero(rn)) {
                NumberOperand folded = foldNumbers(ln, b.operator(), rn, b.location());
                ctx.record(OptimizationCode.EXPR_001, queryName,
                        ln.value() + " " + opSymbol(b.operator()) + " "
                                + rn.value() + " → " + folded.value(),
                        b.location());
                return folded;
            }
        }

        // ── EXPR-002: string + string → concatenated string ──────────────────
        if (b.operator() == ArithmeticOperator.PLUS
                && left  instanceof StringOperand ls
                && right instanceof StringOperand rs) {
            StringOperand concat = new StringOperand(ls.value() + rs.value(), b.location());
            ctx.record(OptimizationCode.EXPR_002, queryName,
                    "\"" + ls.value() + "\" + \"" + rs.value()
                            + "\" → \"" + concat.value() + "\"",
                    b.location());
            return concat;
        }

        // ── EXPR-003: additive identity ──────────────────────────────────────
        if (b.operator() == ArithmeticOperator.PLUS) {
            if (isZeroLiteral(left)) {
                ctx.record(OptimizationCode.EXPR_003, queryName, "0 + x → x", b.location());
                return right;
            }
            if (isZeroLiteral(right)) {
                ctx.record(OptimizationCode.EXPR_003, queryName, "x + 0 → x", b.location());
                return left;
            }
        }
        if (b.operator() == ArithmeticOperator.MINUS && isZeroLiteral(right)) {
            ctx.record(OptimizationCode.EXPR_003, queryName, "x - 0 → x", b.location());
            return left;
        }

        // ── EXPR-004: multiplicative identity ───────────────────────────────
        if (b.operator() == ArithmeticOperator.MULTIPLY) {
            if (isOneLiteral(left)) {
                ctx.record(OptimizationCode.EXPR_004, queryName, "1 * x → x", b.location());
                return right;
            }
            if (isOneLiteral(right)) {
                ctx.record(OptimizationCode.EXPR_004, queryName, "x * 1 → x", b.location());
                return left;
            }
        }
        if (b.operator() == ArithmeticOperator.DIVIDE && isOneLiteral(right)) {
            ctx.record(OptimizationCode.EXPR_004, queryName, "x / 1 → x", b.location());
            return left;
        }

        // ── EXPR-005: multiplication by zero ────────────────────────────────
        if (b.operator() == ArithmeticOperator.MULTIPLY
                && (isZeroLiteral(left) || isZeroLiteral(right))) {
            NumberOperand zero = new NumberOperand("0", b.location());
            ctx.record(OptimizationCode.EXPR_005, queryName, "x * 0 → 0", b.location());
            return zero;
        }

        // ── EXPR-007: constant term accumulation ─────────────────────────────
        Operand accumulated = accumulateConstants(left, b.operator(), right, b.location());
        if (accumulated != null) {
            return accumulated;
        }

        // Rebuild node if children changed but no rule fired at this level.
        if (left != b.left() || right != b.right()) {
            return new BinaryArithmeticExpression(left, b.operator(), right, b.location());
        }
        return b;
    }

    /** Simplifies arguments and applies EXPR-008 (idempotent call elimination). */
    private Operand simplifyFunctionCall(FunctionCall f) {
        var newArgs = f.arguments().stream().map(this::simplify).toList();
        boolean argsChanged = !listsIdentical(newArgs, f.arguments());
        FunctionCall candidate = argsChanged
                ? new FunctionCall(f.functionName(), newArgs, f.location())
                : f;

        // ── EXPR-008: f(f(x)) → f(x) when f is IDEMPOTENT and single-arg ─────
        if (candidate.arguments().size() == 1) {
            Operand singleArg = candidate.arguments().get(0);
            if (singleArg instanceof FunctionCall inner
                    && inner.functionName().equalsIgnoreCase(candidate.functionName())
                    && inner.arguments().size() == 1
                    && isIdempotent(candidate.functionName())) {
                ctx.record(OptimizationCode.EXPR_008, queryName,
                        candidate.functionName() + "(" + candidate.functionName()
                                + "(x)) → " + candidate.functionName() + "(x)",
                        candidate.location());
                return inner;
            }
        }

        return candidate;
    }

    /** Simplifies elements inside a set literal. */
    private Operand simplifySet(SetLiteralOperand s) {
        var newElems = s.elements().stream().map(this::simplify).toList();
        if (!listsIdentical(newElems, s.elements())) {
            return new SetLiteralOperand(newElems, s.location());
        }
        return s;
    }

    private Operand simplifyStruct(StructConstruction struct) {
        var newFields = struct.fields().stream()
                .map(f -> new StructConstruction.Field(f.name(), simplify(f.value())))
                .toList();
        boolean changed = false;
        for (int i = 0; i < newFields.size(); i++) {
            if (newFields.get(i).value() != struct.fields().get(i).value()) {
                changed = true;
                break;
            }
        }
        return changed ? new StructConstruction(newFields, struct.location()) : struct;
    }

    private Operand simplifyArray(ArrayConstruction array) {
        var newElems = array.elements().stream().map(this::simplify).toList();
        if (!listsIdentical(newElems, array.elements())) {
            return new ArrayConstruction(newElems, array.location());
        }
        return array;
    }

    // =========================================================================
    // Static numeric / identity helpers
    // =========================================================================

    /**
     * Evaluates a binary arithmetic expression over two numeric literal
     * operands and returns the folded result as a {@link NumberOperand}.
     */
    private static NumberOperand foldNumbers(NumberOperand l, ArithmeticOperator op,
                                              NumberOperand r, SourceLocation loc) {
        BigDecimal lv = new BigDecimal(l.value());
        BigDecimal rv = new BigDecimal(r.value());
        BigDecimal result = switch (op) {
            case PLUS     -> lv.add(rv);
            case MINUS    -> lv.subtract(rv);
            case MULTIPLY -> lv.multiply(rv);
            case DIVIDE   -> lv.divide(rv, MathContext.DECIMAL64);
        };
        // Normalise: strip trailing zeros and use plain decimal string.
        String str = result.stripTrailingZeros().toPlainString();
        return new NumberOperand(str, loc);
    }

    private static boolean isZero(NumberOperand n) {
        try {
            return new BigDecimal(n.value()).compareTo(BigDecimal.ZERO) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isZeroLiteral(Operand op) {
        return op instanceof NumberOperand n && isZero(n);
    }

    private static boolean isOneLiteral(Operand op) {
        if (!(op instanceof NumberOperand n)) return false;
        try {
            return new BigDecimal(n.value()).compareTo(BigDecimal.ONE) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String opSymbol(ArithmeticOperator op) {
        return switch (op) {
            case PLUS     -> "+";
            case MINUS    -> "-";
            case MULTIPLY -> "*";
            case DIVIDE   -> "/";
        };
    }

    /**
     * EXPR-007: tries to reassociate a pair of like commutative+associative
     * operations so that two numeric literals end up adjacent and can be
     * folded together.  Returns the simplified operand, or {@code null} when
     * no pattern matches.
     *
     * <p>Patterns handled (shown for PLUS; identical for MULTIPLY):
     * <pre>
     *   (x + k1) + k2  →  x + (k1+k2)
     *   (k1 + x) + k2  →  x + (k1+k2)
     *   k1 + (x + k2)  →  x + (k1+k2)
     *   k1 + (k2 + x)  →  x + (k1+k2)
     * </pre>
     */
    private Operand accumulateConstants(Operand left, ArithmeticOperator outerOp,
                                         Operand right, SourceLocation loc) {
        if (outerOp != ArithmeticOperator.PLUS && outerOp != ArithmeticOperator.MULTIPLY) {
            return null;
        }
        // (inner op k1) op k2  — left child is the nested expression
        if (right instanceof NumberOperand k2
                && left instanceof BinaryArithmeticExpression inner
                && inner.operator() == outerOp) {
            if (inner.right() instanceof NumberOperand k1
                    && !(inner.left() instanceof NumberOperand)) {
                return accumRecord(inner.left(), outerOp, k1, k2, loc);
            }
            if (inner.left() instanceof NumberOperand k1
                    && !(inner.right() instanceof NumberOperand)) {
                return accumRecord(inner.right(), outerOp, k1, k2, loc);
            }
        }
        // k1 op (inner op k2)  — right child is the nested expression
        if (left instanceof NumberOperand k1
                && right instanceof BinaryArithmeticExpression inner
                && inner.operator() == outerOp) {
            if (inner.right() instanceof NumberOperand k2
                    && !(inner.left() instanceof NumberOperand)) {
                return accumRecord(inner.left(), outerOp, k1, k2, loc);
            }
            if (inner.left() instanceof NumberOperand k2
                    && !(inner.right() instanceof NumberOperand)) {
                return accumRecord(inner.right(), outerOp, k1, k2, loc);
            }
        }
        return null;
    }

    private Operand accumRecord(Operand x, ArithmeticOperator op,
                                 NumberOperand k1, NumberOperand k2, SourceLocation loc) {
        NumberOperand K = foldNumbers(k1, op, k2, loc);
        ctx.record(OptimizationCode.EXPR_007, queryName,
                "constants accumulated: " + k1.value() + " " + opSymbol(op)
                        + " " + k2.value() + " → " + K.value(),
                loc);
        return new BinaryArithmeticExpression(x, op, K, loc);
    }

    /**
     * Whether {@code functionName} names an installed function tagged IDEMPOTENT.
     *
     * <p>Asked of the catalogue this query was analysed against, so a library's own
     * idempotent function collapses exactly as {@code UCase} does.  A name no installed
     * library offers declares nothing, and the rewrite declines — the safe direction.
     */
    private boolean isIdempotent(String functionName) {
        return ctx.functions().scalar(functionName)
                .map(fn -> fn.signature().has(FunctionProperty.IDEMPOTENT))
                .orElse(false);
    }

    /** Reference-equality check across a pair of same-size lists. */
    private static <T> boolean listsIdentical(java.util.List<T> a, java.util.List<T> b) {
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) return false;
        }
        return true;
    }
}

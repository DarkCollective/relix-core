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
package com.darkcollective.relix.processor.eval;

import com.darkcollective.relix.processor.EvaluationException;

import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.UnaryOperand;
import com.darkcollective.relix.processor.internal.ArrayRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.Value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Fills the single unknown column of a row by inverting a goal-seek equation
 * {@code left = right} (the engine side of the {@code SOLVE} operator).
 *
 * <p>For a given row, the <em>participating columns</em> are the distinct
 * attribute references in {@code left} and {@code right}.  When exactly one of
 * them is {@code NULL}, it is the unknown {@code u}: because each column appears
 * at most once across the equation (enforced by semantic validation), {@code u}
 * lives in exactly one side and the other side is fully evaluable.  The known
 * side is evaluated to a target value, then the side containing {@code u} is
 * walked top-down, applying the inverse of each arithmetic operation, until
 * {@code u} itself is reached and assigned.
 *
 * <p>Rows without exactly one {@code NULL} participating column are returned
 * unchanged: a fully-populated row is already complete, and a row with two or
 * more blanks is underdetermined.
 *
 * <p>Division precision matches {@link OperandEvaluator}: ten fractional digits,
 * {@link RoundingMode#HALF_UP}, trailing zeros stripped; division by zero throws
 * an {@link EvaluationException}.
 *
 * <p>Instances are stateless apart from the immutable {@link OperandEvaluator}
 * reference and are therefore thread-safe.
 */
public final class EquationSolver {

    private final OperandEvaluator evaluator;

    /**
     * Constructs a solver that evaluates the known parts of an equation with the
     * given operand evaluator.
     *
     * @param evaluator the evaluator for fully-known sub-expressions; must not be null
     */
    public EquationSolver(OperandEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    /**
     * Returns {@code row} with its single {@code NULL} participating column filled
     * by inverting {@code left = right}, or {@code row} unchanged when it does not
     * have exactly one {@code NULL} participating column (or the column cannot be
     * located in the row's schema).
     *
     * @param left  the left-hand side of the equation; must not be null
     * @param right the right-hand side of the equation; must not be null
     * @param row   the row to complete; must not be null
     * @return the completed row, or {@code row} unchanged
     * @throws EvaluationException on division by zero or a non-numeric operand
     */
    public Row solve(Operand left, Operand right, Row row) {
        Set<String> participating = new LinkedHashSet<>();
        collectColumns(left, participating);
        collectColumns(right, participating);

        String unknown = null;
        for (String col : participating) {
            if (row.get(col).isNull()) {
                if (unknown != null) {
                    return row; // ≥ 2 unknowns — underdetermined
                }
                unknown = col;
            }
        }
        if (unknown == null) {
            return row; // already complete
        }

        int index = row.schema().indexOf(unknown);
        if (index < 0) {
            return row; // column not positionally addressable (e.g. open schema)
        }

        boolean inLeft = containsColumn(left, unknown);
        Operand unknownSide = inLeft ? left : right;
        Operand knownSide   = inLeft ? right : left;

        BigDecimal target = asNumber(evaluator.evaluate(knownSide, row));
        BigDecimal result = solveFor(unknownSide, unknown, target, row);

        return withValue(row, index, new NumberValue(result));
    }

    // ── inversion ───────────────────────────────────────────────────────────

    /**
     * Solves the sub-expression {@code side} (which contains the unknown column)
     * for the unknown, given that {@code side} must equal {@code target}.
     */
    private BigDecimal solveFor(Operand side, String unknown, BigDecimal target, Row row) {
        return switch (side) {
            case AttributeOperand ignored -> target; // base case: side is the unknown
            case UnaryOperand u -> solveFor(u.operand(), unknown, target.negate(), row);
            case BinaryArithmeticExpression b -> {
                boolean inLeft = containsColumn(b.left(), unknown);
                Operand unknownBranch = inLeft ? b.left() : b.right();
                Operand knownBranch   = inLeft ? b.right() : b.left();
                BigDecimal other = asNumber(evaluator.evaluate(knownBranch, row));
                BigDecimal next = switch (b.operator()) {
                    case PLUS     -> target.subtract(other);
                    case MINUS    -> inLeft ? target.add(other) : other.subtract(target);
                    case MULTIPLY -> divide(target, other);
                    case DIVIDE   -> inLeft ? target.multiply(other) : divide(other, target);
                };
                yield solveFor(unknownBranch, unknown, next, row);
            }
            default -> throw new EvaluationException(
                    "SOLVE: equation contains a construct that cannot be inverted");
        };
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Collects the bare (qualifier-stripped) attribute names of {@code expr}. */
    private static void collectColumns(Operand expr, Set<String> out) {
        switch (expr) {
            case AttributeOperand a -> out.add(a.unqualifiedName());
            case UnaryOperand u -> collectColumns(u.operand(), out);
            case BinaryArithmeticExpression b -> {
                collectColumns(b.left(), out);
                collectColumns(b.right(), out);
            }
            default -> { /* literals and other operands reference no columns */ }
        }
    }

    /** Whether {@code expr} references the bare column name {@code column}. */
    private static boolean containsColumn(Operand expr, String column) {
        return switch (expr) {
            case AttributeOperand a -> a.unqualifiedName().equalsIgnoreCase(column);
            case UnaryOperand u -> containsColumn(u.operand(), column);
            case BinaryArithmeticExpression b ->
                    containsColumn(b.left(), column) || containsColumn(b.right(), column);
            default -> false;
        };
    }

    private static BigDecimal asNumber(Value value) {
        if (!(value instanceof NumberValue nv)) {
            throw new EvaluationException(
                    "SOLVE: equation requires NUMBER operands, got " + value.type());
        }
        return nv.value();
    }

    private static BigDecimal divide(BigDecimal dividend, BigDecimal divisor) {
        if (divisor.compareTo(BigDecimal.ZERO) == 0) {
            throw new EvaluationException("SOLVE: division by zero while inverting the equation");
        }
        return dividend.divide(divisor, 10, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static Row withValue(Row row, int index, Value value) {
        List<Value> values = new ArrayList<>(row.width());
        for (int i = 0; i < row.width(); i++) {
            values.add(i == index ? value : row.get(i));
        }
        return ArrayRow.of(row.schema(), values);
    }
}

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

import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.SolveEquation;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.UnaryOperand;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.internal.ArrayRow;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.Value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Fills a row's unknown columns by solving a system of {@code SOLVE} equations (the
 * engine side of {@code SOLVE &#123; … &#125;}).
 *
 * <p>The unknowns are the participating columns that are {@code NULL} in the row. The
 * row is solved when there are exactly as many unknowns as equations, every equation
 * is linear in them — no product of two terms that both hold an unknown, no unknown in
 * a divisor — and the system is non-singular; it is then set up by evaluating each
 * equation's {@code left − right} with its gradient ({@link Dual}) and solved by
 * Gaussian elimination ({@link LinearSystems}). Any other row is returned unchanged.
 *
 * <p>Results are rounded to ten fractional digits, {@link RoundingMode#HALF_UP},
 * trailing zeros stripped — the precision of {@code ÷}.
 *
 * <p>The equations are expected in their planned form, with every user-defined
 * function expanded. Instances are stateless and thread-safe.
 */
public final class EquationSystemSolver {

    /** Fractional digits a solved value is reported to. */
    static final int SCALE = 10;

    /**
     * Returns {@code row} with its unknowns filled, or unchanged when it cannot be
     * solved.
     *
     * @param equations the equations, every function expanded; must not be null
     * @param row       the row to complete; must not be null
     * @return the completed row, or {@code row} itself
     * @throws com.darkcollective.relix.processor.EvaluationException on a non-numeric
     *         known value or a division by zero among the known terms
     */
    public Row solve(List<SolveEquation> equations, Row row) {
        Map<String, String> participating = participating(equations);
        List<String> unknowns = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        for (Map.Entry<String, String> column : participating.entrySet()) {
            int index = row.schema().indexOf(column.getValue());
            if (index < 0) {
                return row; // not positionally addressable (an open schema)
            }
            if (row.get(index).isNull()) {
                unknowns.add(column.getKey());
                positions.add(index);
            }
        }
        if (unknowns.isEmpty() || unknowns.size() != equations.size()
                || !linear(equations, Set.copyOf(unknowns))) {
            return row;
        }

        int n = unknowns.size();
        BigDecimal[] origin = new BigDecimal[n];
        Arrays.fill(origin, BigDecimal.ZERO);
        BigDecimal[][] a = new BigDecimal[n][];
        BigDecimal[] b = new BigDecimal[n];
        for (int i = 0; i < n; i++) {
            Dual residual = residual(equations.get(i), unknowns, origin, row);
            a[i] = residual.gradient();
            b[i] = residual.value().negate();
        }
        Optional<BigDecimal[]> solution = LinearSystems.solve(a, b);
        return solution.map(x -> withValues(row, positions, x)).orElse(row);
    }

    /**
     * Fits the equations' unknowns across a group of rows by least squares (the engine
     * side of {@code SOLVE … PER}), returning every row of the group with the fitted
     * values written in, or the group unchanged when it cannot be fitted.
     *
     * <p>The unknowns are the participating columns that are {@code NULL} in every row
     * of the group; the observations are the rows in which every other participating
     * column is present, each contributing one residual {@code left − right} per
     * equation. The fit minimises the sum of the squared residuals, by the normal
     * equations: it needs at least as many residuals as unknowns, every equation linear
     * in the unknowns, and a non-singular system. With as many residuals as unknowns it
     * is the exact solve.
     *
     * @param equations the equations, every function expanded; must not be null
     * @param group     the rows of one group, all of one schema; must not be null
     * @return the group's rows, in order, completed or unchanged
     * @throws com.darkcollective.relix.processor.EvaluationException on a non-numeric
     *         known value or a division by zero among the known terms
     */
    public List<Row> fit(List<SolveEquation> equations, List<Row> group) {
        if (group.isEmpty()) {
            return group;
        }
        Row first = group.getFirst();
        Map<String, String> participating = participating(equations);
        List<String> unknowns = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        List<Integer> knownPositions = new ArrayList<>();
        for (Map.Entry<String, String> column : participating.entrySet()) {
            int index = first.schema().indexOf(column.getValue());
            if (index < 0) {
                return group; // not positionally addressable (an open schema)
            }
            if (group.stream().allMatch(row -> row.get(index).isNull())) {
                unknowns.add(column.getKey());
                positions.add(index);
            } else {
                knownPositions.add(index);
            }
        }
        if (unknowns.isEmpty() || !linear(equations, Set.copyOf(unknowns))) {
            return group;
        }
        List<Row> observations = group.stream()
                .filter(row -> knownPositions.stream().noneMatch(i -> row.get(i).isNull()))
                .toList();
        int n = unknowns.size();
        if (observations.size() * equations.size() < n) {
            return group;
        }

        BigDecimal[] origin = new BigDecimal[n];
        Arrays.fill(origin, BigDecimal.ZERO);
        BigDecimal[][] normal = new BigDecimal[n][n];
        BigDecimal[] moment = new BigDecimal[n];
        for (int i = 0; i < n; i++) {
            Arrays.fill(normal[i], BigDecimal.ZERO);
            moment[i] = BigDecimal.ZERO;
        }
        for (Row row : observations) {
            for (SolveEquation equation : equations) {
                Dual residual = residual(equation, unknowns, origin, row);
                BigDecimal[] g = residual.gradient();
                BigDecimal target = residual.value().negate();
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        normal[i][j] = normal[i][j].add(g[i].multiply(g[j], Dual.CONTEXT), Dual.CONTEXT);
                    }
                    moment[i] = moment[i].add(g[i].multiply(target, Dual.CONTEXT), Dual.CONTEXT);
                }
            }
        }
        Optional<BigDecimal[]> solution = LinearSystems.solve(normal, moment);
        if (solution.isEmpty()) {
            return group;
        }
        BigDecimal[] x = solution.get();
        return group.stream().map(row -> withValues(row, positions, x)).toList();
    }

    /** {@code left − right} of one equation, with its gradient, at {@code point}. */
    static Dual residual(SolveEquation equation, List<String> unknowns, BigDecimal[] point,
                         Row row) {
        Operand difference = new BinaryArithmeticExpression(equation.left(),
                ArithmeticOperator.MINUS, equation.right(), SourceLocation.UNKNOWN);
        return Dual.evaluate(difference, unknowns, point, row);
    }

    /**
     * The distinct columns the equations name, keyed by lower-cased name and in order
     * of first appearance, each mapped to its name as written (qualifier stripped).
     */
    static Map<String, String> participating(List<SolveEquation> equations) {
        Map<String, String> out = new LinkedHashMap<>();
        for (SolveEquation e : equations) {
            collect(e.left(), out);
            collect(e.right(), out);
        }
        return out;
    }

    private static void collect(Operand expr, Map<String, String> out) {
        switch (expr) {
            case AttributeOperand a ->
                    out.putIfAbsent(a.unqualifiedName().toLowerCase(Locale.ROOT), a.unqualifiedName());
            case UnaryOperand u -> collect(u.operand(), out);
            case BinaryArithmeticExpression b -> {
                collect(b.left(), out);
                collect(b.right(), out);
            }
            default -> { /* literals reference no columns */ }
        }
    }

    /** Whether every equation is affine in {@code unknowns} (lower-cased names). */
    static boolean linear(List<SolveEquation> equations, Set<String> unknowns) {
        return equations.stream().allMatch(e ->
                degree(e.left(), unknowns) <= 1 && degree(e.right(), unknowns) <= 1);
    }

    /** The degree of {@code expr} in the unknowns, where 2 stands for "not linear". */
    private static int degree(Operand expr, Set<String> unknowns) {
        return switch (expr) {
            case AttributeOperand a ->
                    unknowns.contains(a.unqualifiedName().toLowerCase(Locale.ROOT)) ? 1 : 0;
            case UnaryOperand u -> degree(u.operand(), unknowns);
            case BinaryArithmeticExpression b -> {
                int l = degree(b.left(), unknowns);
                int r = degree(b.right(), unknowns);
                yield switch (b.operator()) {
                    case PLUS, MINUS -> Math.max(l, r);
                    case MULTIPLY -> Math.min(2, l + r);
                    case DIVIDE -> r == 0 ? l : 2;
                };
            }
            default -> 0;
        };
    }

    /** Rounds a solved value to the precision {@code SOLVE} reports. */
    static BigDecimal rounded(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static Row withValues(Row row, List<Integer> positions, BigDecimal[] values) {
        List<Value> out = new ArrayList<>(row.width());
        for (int i = 0; i < row.width(); i++) {
            out.add(row.get(i));
        }
        for (int k = 0; k < positions.size(); k++) {
            out.set(positions.get(k), new NumberValue(rounded(values[k])));
        }
        return ArrayRow.of(row.schema(), out);
    }
}

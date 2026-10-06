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
import com.darkcollective.relix.ast.SolveStart;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.UnaryOperand;
import com.darkcollective.relix.processor.EvaluationException;
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
 * Fills a row's unknown columns by solving {@code SOLVE} equations, and fits a group's
 * (the engine side of {@code SOLVE} and {@code SOLVE … PER}).
 *
 * <p>The unknowns of a row are the participating columns that are {@code NULL} in it.
 * A row is solved when there are exactly as many unknowns as equations, by the
 * cheapest strategy that applies:
 * <ol>
 *   <li>a single equation whose unknown appears once is inverted by rearranging it
 *       ({@link EquationSolver});</li>
 *   <li>equations linear in the unknowns — no product of two terms that both hold an
 *       unknown, no unknown in a divisor — are set up by evaluating each
 *       {@code left − right} with its gradient ({@link Dual}) and solved by elimination
 *       ({@link LinearSystems}); a singular system leaves the row unchanged;</li>
 *   <li>anything else is solved iteratively ({@link Newton}), which leaves the row
 *       unchanged when the equations do not determine the unknowns and raises when they
 *       have no solution or the search does not converge. The search starts every
 *       unknown at 1, or where a {@code START} says; the first two strategies find the
 *       one answer there is and ignore it.</li>
 * </ol>
 * Any other row is returned unchanged.
 *
 * <p>Results are rounded to ten fractional digits, {@link RoundingMode#HALF_UP},
 * trailing zeros stripped — the precision of {@code ÷}.
 *
 * <p>The equations are expected in their planned form, with every user-defined
 * function expanded. Instances are immutable and thread-safe.
 */
public final class EquationSystemSolver {

    /** Fractional digits a solved value is reported to. */
    static final int SCALE = 10;

    /** The convergence tolerance when {@code WITHIN} is not given: 10⁻¹⁰. */
    public static final BigDecimal DEFAULT_TOLERANCE = new BigDecimal("1E-10");

    /** The round cap when {@code MAX … ROUNDS} is not given. */
    public static final int DEFAULT_MAX_ROUNDS = 100;

    private final OperandEvaluator evaluator;
    private final EquationSolver inverter;
    private final BigDecimal tolerance;
    private final int maxRounds;

    /**
     * A solver with the default tolerance and round cap, inverting single equations
     * with a default {@link OperandEvaluator}.
     */
    public EquationSystemSolver() {
        this(new OperandEvaluator(), DEFAULT_TOLERANCE, DEFAULT_MAX_ROUNDS);
    }

    /**
     * A solver with the given iteration limits.
     *
     * @param evaluator the evaluator a single inverted equation's known side is read
     *                  with; must not be null
     * @param tolerance the largest step an iteration may still take and be converged;
     *                  positive
     * @param maxRounds the iteration's round cap; at least 1
     */
    public EquationSystemSolver(OperandEvaluator evaluator, BigDecimal tolerance, int maxRounds) {
        this.evaluator = evaluator;
        this.inverter = new EquationSolver(evaluator);
        this.tolerance = tolerance;
        this.maxRounds = maxRounds;
    }

    /**
     * Returns {@code row} with its unknowns filled, or unchanged when it cannot be
     * posed.
     *
     * @param equations the equations, every function expanded; must not be null
     * @param row       the row to complete; must not be null
     * @return the completed row, or {@code row} itself
     * @throws com.darkcollective.relix.processor.EvaluationException on a non-numeric
     *         known value, a division by zero among the known terms, or an iteration
     *         that does not converge
     */
    public Row solve(List<SolveEquation> equations, Row row) {
        return solve(equations, List.of(), row);
    }

    /**
     * Returns {@code row} with its unknowns filled, or unchanged when it cannot be
     * posed, an iterative search starting each unknown a {@code START} names at that
     * start's value over {@code row}. A start for a column the row knows is ignored, and
     * one whose value is {@code NULL} leaves its unknown at 1.
     *
     * @param equations the equations, every function expanded; must not be null
     * @param starts    the {@code START} values, possibly empty; must not be null
     * @param row       the row to complete; must not be null
     * @return the completed row, or {@code row} itself
     * @throws com.darkcollective.relix.processor.EvaluationException on a non-numeric
     *         known value or start, a division by zero among the known terms, or an
     *         iteration that does not converge
     */
    public Row solve(List<SolveEquation> equations, List<SolveStart> starts, Row row) {
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
        if (unknowns.isEmpty() || unknowns.size() != equations.size()) {
            return row;
        }
        if (equations.size() == 1 && occurrences(equations.getFirst(), unknowns.getFirst()) == 1) {
            SolveEquation only = equations.getFirst();
            return inverter.solve(only.left(), only.right(), row);
        }

        int n = unknowns.size();
        if (linear(equations, Set.copyOf(unknowns))) {
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
        return Newton.solve(equations, unknowns, List.of(row), startPoint(unknowns, starts, row),
                        tolerance, maxRounds, () -> "the row " + describe(row, participating))
                .map(x -> withValues(row, positions, x))
                .orElse(row);
    }

    /**
     * Fits the equations' unknowns across a group of rows (the engine side of
     * {@code SOLVE … PER}), returning every row of the group with the fitted values
     * written in, or the group unchanged when it cannot be posed.
     *
     * <p>The unknowns are the participating columns that are {@code NULL} in every row
     * of the group; the observations are the rows in which every other participating
     * column is present, each contributing one residual {@code left − right} per
     * equation. The fit minimises the sum of the squared residuals and needs at least as
     * many residuals as unknowns. Equations linear in the unknowns are fitted directly,
     * by the normal equations; anything else iteratively ({@link Newton}), which raises
     * when it does not converge. A fit that does not determine the unknowns — a singular
     * system — leaves the group unchanged. With as many residuals as unknowns the fit is
     * the exact solve.
     *
     * @param equations the equations, every function expanded; must not be null
     * @param group     the rows of one group, all of one schema; must not be null
     * @param subject   names the group in a diagnostic, e.g. {@code material=steel}
     * @return the group's rows, in order, completed or unchanged
     * @throws com.darkcollective.relix.processor.EvaluationException on a non-numeric
     *         known value, a division by zero among the known terms, or an iteration
     *         that does not converge
     */
    public List<Row> fit(List<SolveEquation> equations, List<Row> group, String subject) {
        return fit(equations, List.of(), group, subject);
    }

    /**
     * Fits a group as {@link #fit(List, List, String)} does, an iterative fit starting
     * each unknown a {@code START} names at that start's value. Under {@code PER} a
     * start is a constant, so it is read once, over the group's first row.
     *
     * @param equations the equations, every function expanded; must not be null
     * @param starts    the {@code START} values, possibly empty; must not be null
     * @param group     the rows of one group, all of one schema; must not be null
     * @param subject   names the group in a diagnostic, e.g. {@code material=steel}
     * @return the group's rows, in order, completed or unchanged
     * @throws com.darkcollective.relix.processor.EvaluationException on a non-numeric
     *         known value or start, a division by zero among the known terms, or an
     *         iteration that does not converge
     */
    public List<Row> fit(List<SolveEquation> equations, List<SolveStart> starts,
                         List<Row> group, String subject) {
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
        if (unknowns.isEmpty()) {
            return group;
        }
        List<Row> observations = group.stream()
                .filter(row -> knownPositions.stream().noneMatch(i -> row.get(i).isNull()))
                .toList();
        int n = unknowns.size();
        if (observations.size() * equations.size() < n) {
            return group;
        }

        Optional<BigDecimal[]> solution = linear(equations, Set.copyOf(unknowns))
                ? leastSquares(equations, unknowns, observations)
                : Newton.solve(equations, unknowns, observations,
                        startPoint(unknowns, starts, first), tolerance, maxRounds,
                        () -> "the group " + subject);
        if (solution.isEmpty()) {
            return group;
        }
        BigDecimal[] fitted = solution.get();
        return group.stream().map(row -> withValues(row, positions, fitted)).toList();
    }

    /**
     * Where a search starts: each unknown at 1, except one a {@code START} names, which
     * starts at that value over {@code row} — or at 1 still, when the value is
     * {@code NULL}. A start for a column that is not an unknown here is not consulted.
     */
    private BigDecimal[] startPoint(List<String> unknowns, List<SolveStart> starts, Row row) {
        BigDecimal[] x = new BigDecimal[unknowns.size()];
        Arrays.fill(x, BigDecimal.ONE);
        for (SolveStart start : starts) {
            int k = unknowns.indexOf(start.column().toLowerCase(Locale.ROOT));
            if (k < 0) {
                continue;
            }
            Value value = evaluator.evaluate(start.value(), row);
            if (value instanceof NumberValue number) {
                x[k] = number.value();
            } else if (!value.isNull()) {
                throw new EvaluationException("SOLVE: the START for '" + start.column()
                        + "' must be a NUMBER, got " + value.type());
            }
        }
        return x;
    }

    /** The least-squares solution of a linear fit, by its normal equations. */
    private static Optional<BigDecimal[]> leastSquares(List<SolveEquation> equations,
                                                       List<String> unknowns,
                                                       List<Row> observations) {
        int n = unknowns.size();
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
        return LinearSystems.solve(normal, moment);
    }

    /** How many times {@code column} (lower-cased) is named in the equation. */
    private static int occurrences(SolveEquation equation, String column) {
        return occurrences(equation.left(), column) + occurrences(equation.right(), column);
    }

    private static int occurrences(Operand expr, String column) {
        return switch (expr) {
            case AttributeOperand a ->
                    a.unqualifiedName().toLowerCase(Locale.ROOT).equals(column) ? 1 : 0;
            case UnaryOperand u -> occurrences(u.operand(), column);
            case BinaryArithmeticExpression b ->
                    occurrences(b.left(), column) + occurrences(b.right(), column);
            default -> 0;
        };
    }

    /** The participating columns of {@code row} as {@code (name=value, …)}. */
    private static String describe(Row row, Map<String, String> participating) {
        StringBuilder sb = new StringBuilder("(");
        for (String name : participating.values()) {
            if (sb.length() > 1) sb.append(", ");
            sb.append(name).append('=').append(row.get(name).asDisplayString());
        }
        return sb.append(')').toString();
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

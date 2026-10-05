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

import com.darkcollective.relix.ast.SolveEquation;
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.processor.Row;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The iterative strategy for {@code SOLVE} equations that are neither invertible by
 * rearrangement nor linear: Levenberg–Marquardt, a damped Gauss–Newton method, which is
 * Newton's method when there are as many residuals as unknowns and the least-squares
 * fit when there are more.
 *
 * <p>Every unknown starts at 1. Each round evaluates the residuals {@code left − right}
 * of every equation over every row, with their exact derivatives ({@link Dual}), and
 * solves {@code (JᵀJ + μI) Δ = −Jᵀr} for a step. A step that reduces the sum of squared
 * residuals is taken and {@code μ} falls tenfold, toward the Gauss–Newton step; one that
 * does not is refused and {@code μ} rises tenfold, toward a short step down the
 * gradient. Every attempt, taken or refused, is a round. {@code μ} never falls below
 * {@link #MIN_DAMPING} of the largest entry of {@code JᵀJ}, which keeps the system
 * solvable at a point where the equations are degenerate — the all-ones start of
 * {@code a / b = 3, a − b = 4} is one.
 *
 * <p>The iteration has converged when no unknown's step exceeds the tolerance. The
 * point it converged to is then checked twice. If the unknowns are not determined there
 * — {@code JᵀJ} singular, as when only their product appears — there is no single
 * answer and the result is empty, which the caller reports as it does a singular linear
 * system. And when there are as many residuals as unknowns, every equation must hold:
 * to within the tolerance times the larger of 1 and its two sides. A minimum of the
 * residuals that is not a root — {@code side * side = -1} — is a question with no
 * answer, and is an error, as is reaching the round cap or dividing by zero at an
 * estimate.
 */
final class Newton {

    /** The floor on the damping, relative to the largest entry of {@code JᵀJ}. */
    static final BigDecimal MIN_DAMPING = new BigDecimal("1E-12");

    /** The damping a search starts with, relative to the largest entry of {@code JᵀJ}. */
    static final BigDecimal START_DAMPING = new BigDecimal("1E-3");

    private Newton() {
    }

    /**
     * Finds the unknowns that make the residuals zero, or as small as they can be made.
     *
     * @param equations the equations, every function expanded
     * @param unknowns  the unknown columns, lower-cased, in coordinate order
     * @param rows      the rows supplying the known columns — one, or a group's
     *                  observations; there are at least as many residuals as unknowns
     * @param tolerance the largest step that counts as converged; positive
     * @param maxRounds the round cap; at least 1
     * @param subject   names the row or group in a diagnostic
     * @return the unknowns' values, unrounded, or empty when the equations do not
     *         determine them
     * @throws EvaluationException when the iteration does not converge, or converges
     *         to a point at which a square system's equations do not hold
     */
    static Optional<BigDecimal[]> solve(List<SolveEquation> equations, List<String> unknowns,
                                        List<Row> rows, BigDecimal tolerance, int maxRounds,
                                        Supplier<String> subject) {
        int n = unknowns.size();
        BigDecimal[] x = new BigDecimal[n];
        Arrays.fill(x, BigDecimal.ONE);
        Dual[] residuals = at(equations, unknowns, rows, x, subject);
        BigDecimal cost = sumOfSquares(residuals);
        BigDecimal damping = null;
        for (int round = 1; round <= maxRounds; round++) {
            Normal normal = Normal.of(residuals, n);
            BigDecimal scale = normal.largestEntry().add(BigDecimal.ONE);
            if (damping == null) {
                damping = START_DAMPING.multiply(scale, Dual.CONTEXT);
            }
            damping = damping.max(MIN_DAMPING.multiply(scale, Dual.CONTEXT));
            // JᵀJ + μI is positive definite for μ > 0, and μ is held far above the
            // elimination's singularity threshold, so this system always has a solution.
            BigDecimal[] delta = LinearSystems.solve(normal.damped(damping), normal.gradient())
                    .orElseThrow();
            BigDecimal[] trial = moved(x, delta);
            if (largest(delta).compareTo(tolerance) <= 0) {
                return converged(equations, unknowns, rows, trial, tolerance, subject);
            }
            Dual[] next = attempt(equations, unknowns, rows, trial);
            if (next != null && sumOfSquares(next).compareTo(cost) < 0) {
                x = trial;
                residuals = next;
                cost = sumOfSquares(next);
                damping = damping.divide(BigDecimal.TEN, Dual.CONTEXT);
            } else {
                damping = damping.multiply(BigDecimal.TEN, Dual.CONTEXT);
            }
        }
        throw new EvaluationException("SOLVE: no solution within " + maxRounds
                + " rounds, for " + subject.get());
    }

    /** The checks a converged point must pass, described on the class. */
    private static Optional<BigDecimal[]> converged(List<SolveEquation> equations,
                                                    List<String> unknowns, List<Row> rows,
                                                    BigDecimal[] x, BigDecimal tolerance,
                                                    Supplier<String> subject) {
        int n = unknowns.size();
        Dual[] residuals = at(equations, unknowns, rows, x, subject);
        Normal normal = Normal.of(residuals, n);
        if (LinearSystems.solve(normal.matrix(), normal.gradient()).isEmpty()) {
            return Optional.empty();
        }
        if (residuals.length == n) {
            for (int i = 0; i < n; i++) {
                SolveEquation equation = equations.get(i);
                BigDecimal size = BigDecimal.ONE
                        .max(Dual.evaluate(equation.left(), unknowns, x, rows.getFirst()).value().abs())
                        .max(Dual.evaluate(equation.right(), unknowns, x, rows.getFirst()).value().abs());
                if (residuals[i].value().abs().compareTo(tolerance.multiply(size, Dual.CONTEXT)) > 0) {
                    throw new EvaluationException("SOLVE: the equations have no solution — the"
                            + " closest the search came leaves "
                            + EquationSystemSolver.rounded(residuals[i].value()).toPlainString()
                            + " between the sides of an equation, for " + subject.get());
                }
            }
        }
        return Optional.of(x);
    }

    /** The residuals at {@code x}, naming the row or group if one divides by zero. */
    private static Dual[] at(List<SolveEquation> equations, List<String> unknowns,
                             List<Row> rows, BigDecimal[] x, Supplier<String> subject) {
        try {
            return residuals(equations, unknowns, rows, x);
        } catch (EvaluationException e) {
            throw new EvaluationException(e.getMessage() + ", for " + subject.get(), e);
        }
    }

    /** The residuals at a trial point, or null where the equations are not defined. */
    private static Dual[] attempt(List<SolveEquation> equations, List<String> unknowns,
                                  List<Row> rows, BigDecimal[] x) {
        try {
            return residuals(equations, unknowns, rows, x);
        } catch (EvaluationException e) {
            return null; // outside the equations' domain: simply not a decrease
        }
    }

    /** Every residual, one per equation per row, at {@code x}. */
    private static Dual[] residuals(List<SolveEquation> equations, List<String> unknowns,
                                    List<Row> rows, BigDecimal[] x) {
        Dual[] out = new Dual[rows.size() * equations.size()];
        int k = 0;
        for (Row row : rows) {
            for (SolveEquation equation : equations) {
                out[k++] = EquationSystemSolver.residual(equation, unknowns, x, row);
            }
        }
        return out;
    }

    /** {@code JᵀJ} and {@code −Jᵀr} for a set of residuals. */
    private record Normal(BigDecimal[][] matrix, BigDecimal[] gradient) {

        static Normal of(Dual[] residuals, int n) {
            BigDecimal[][] matrix = new BigDecimal[n][n];
            BigDecimal[] gradient = new BigDecimal[n];
            for (int i = 0; i < n; i++) {
                Arrays.fill(matrix[i], BigDecimal.ZERO);
                gradient[i] = BigDecimal.ZERO;
            }
            for (Dual r : residuals) {
                BigDecimal[] g = r.gradient();
                BigDecimal target = r.value().negate();
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        matrix[i][j] = matrix[i][j].add(g[i].multiply(g[j], Dual.CONTEXT), Dual.CONTEXT);
                    }
                    gradient[i] = gradient[i].add(g[i].multiply(target, Dual.CONTEXT), Dual.CONTEXT);
                }
            }
            return new Normal(matrix, gradient);
        }

        BigDecimal largestEntry() {
            BigDecimal max = BigDecimal.ZERO;
            for (BigDecimal[] row : matrix) {
                max = max.max(largest(row));
            }
            return max;
        }

        BigDecimal[][] damped(BigDecimal damping) {
            BigDecimal[][] out = new BigDecimal[matrix.length][];
            for (int i = 0; i < matrix.length; i++) {
                out[i] = matrix[i].clone();
                out[i][i] = out[i][i].add(damping, Dual.CONTEXT);
            }
            return out;
        }
    }

    private static BigDecimal[] moved(BigDecimal[] x, BigDecimal[] delta) {
        BigDecimal[] out = new BigDecimal[x.length];
        for (int i = 0; i < x.length; i++) {
            out[i] = x[i].add(delta[i], Dual.CONTEXT);
        }
        return out;
    }

    private static BigDecimal sumOfSquares(Dual[] residuals) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Dual r : residuals) {
            sum = sum.add(r.value().multiply(r.value(), Dual.CONTEXT), Dual.CONTEXT);
        }
        return sum;
    }

    private static BigDecimal largest(BigDecimal[] values) {
        BigDecimal max = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            max = max.max(v.abs());
        }
        return max;
    }
}

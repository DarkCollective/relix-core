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

import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ObjectiveSense;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.OptimizeConstraint;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.solver.LinearProgram;
import com.darkcollective.relix.solver.MathProgrammingSolver;
import com.darkcollective.relix.solver.SolverResult;
import com.darkcollective.relix.solver.SolverCatalog;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Chooses the optimal <em>subset</em> of a group's rows under a linear objective
 * and linear constraints — the 0/1 knapsack / portfolio-selection problem behind
 * the {@code OPTIMIZE} operator.
 *
 * <p>Each candidate row gets a binary decision variable (in or out). The objective
 * is {@code SUM(objective)} over the chosen rows; each constraint is
 * {@code SUM(expr) ≤|≥|= bound}. Coefficients are obtained by evaluating the
 * objective / constraint expressions against each row with the supplied
 * {@link OperandEvaluator}. A row whose objective or any constraint coefficient is
 * {@code NULL} is dropped from candidacy (it can never be chosen).
 *
 * <p>This class turns rows into a {@code LinearProgram} and reads the answer back;
 * an installed solver performs the search. Everything relational stays on this side
 * of that line — which rows are candidates, what a NULL coefficient means, how a
 * constraint operator maps onto a bound — so two solvers cannot disagree about what
 * {@code OPTIMIZE} means, only about how fast they answer.
 *
 * <p>{@link #choose} returns the chosen rows for a feasible group (possibly empty —
 * e.g. minimising a positive objective), or {@link Optional#empty()} when the group
 * is <em>proved</em> infeasible (no assignment satisfies the constraints), so the
 * caller can skip it. A solver that stops without deciding is the third case and
 * raises an {@link EvaluationException}: nothing has been established about the
 * group, so skipping it would report an answer no one worked out.
 *
 * <p>Instances are stateless apart from the immutable evaluator and solver references
 * and are therefore thread-safe.
 */
public final class SubsetOptimizer {

    /** Treat a binary variable as "chosen" when the solver value is at least this. */
    private static final double CHOSEN_THRESHOLD = 0.5;

    private final OperandEvaluator evaluator;
    private final MathProgrammingSolver solver;

    /**
     * Constructs an optimizer that evaluates coefficients with the given evaluator and
     * solves with the installed solver.
     *
     * @param evaluator the evaluator for per-row coefficient expressions; must not be null
     * @throws EvaluationException if no solver is installed
     */
    public SubsetOptimizer(OperandEvaluator evaluator) {
        this(evaluator, SolverCatalog.installed().solver().orElseThrow(
                () -> new EvaluationException(NO_SOLVER)));
    }

    /**
     * Constructs an optimizer over an explicit solver.
     *
     * @param evaluator the evaluator for per-row coefficient expressions; must not be null
     * @param solver    the solver to search with; must not be null
     */
    public SubsetOptimizer(OperandEvaluator evaluator, MathProgrammingSolver solver) {
        this.evaluator = evaluator;
        this.solver = solver;
    }

    /**
     * The message for a runtime with no solver. The planner reports the same thing
     * before a query runs; reaching it here means the plan was built elsewhere.
     */
    static final String NO_SOLVER =
            "OPTIMIZE needs a mathematical-programming solver and none is installed; "
            + "add one to the module path (the shipped provider is relix-solver-ojalgo)";

    /**
     * Solves the subset-selection problem for one group.
     *
     * @param sense       maximise or minimise the objective; must not be null
     * @param objective   the per-row objective coefficient expression; must not be null
     * @param constraints the linear constraints; must not be null
     * @param groupRows   the candidate rows of the group; must not be null
     * @param groupLabel  how to name this group in a diagnostic; must not be null
     * @return the chosen rows for a feasible group (possibly empty), or
     *         {@link Optional#empty()} if the group is infeasible
     * @throws EvaluationException if a coefficient evaluates to a non-numeric value,
     *                             or the solver did not decide the group
     */
    public Optional<List<Row>> choose(ObjectiveSense sense, Operand objective,
                                      List<OptimizeConstraint> constraints,
                                      List<Row> groupRows, String groupLabel) {
        // 1. Keep only rows whose objective + every constraint coefficient is known.
        Coefficients coefficients = extract(objective, constraints, groupRows, null);
        List<Row> candidates = coefficients.candidates();
        if (candidates.isEmpty()) {
            return Optional.of(List.of()); // nothing to choose — trivially feasible
        }

        // 2. Build the MIP: one binary variable per candidate, carrying its objective
        //    weight; one expression per constraint.
        List<LinearProgram.Variable> variables = new ArrayList<>(candidates.size());
        for (double weight : coefficients.objective()) {
            variables.add(LinearProgram.Variable.binary(weight));
        }

        // 3. Solve and read back the chosen rows.
        Optional<double[]> solution = read(solver.solve(new LinearProgram(
                sense(sense), variables, constraintsOf(constraints, coefficients))),
                "OPTIMIZE", "group " + groupLabel);
        if (solution.isEmpty()) {
            return Optional.empty();
        }
        List<Row> chosen = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (solution.get()[i] >= CHOSEN_THRESHOLD) {
                chosen.add(candidates.get(i));
            }
        }
        return Optional.of(chosen);
    }

    /**
     * Solves the continuous-allocation (LP) problem for one group.
     *
     * <p>Each candidate row receives a continuous decision variable in
     * {@code [lo, hi]}.  All rows are returned (no row is discarded), each paired
     * with its solver-assigned allocation value.  An infeasible group returns
     * {@link Optional#empty()}.
     *
     * @param sense       maximise or minimise the objective; must not be null
     * @param objective   the per-row coefficient expression; must not be null
     * @param constraints the linear constraints; must not be null
     * @param lo          the lower bound for each row's allocation variable
     * @param hi          the upper bound for each row's allocation variable
     * @param groupRows   the candidate rows of the group; must not be null
     * @param groupLabel  how to name this group in a diagnostic; must not be null
     * @return every row paired with its allocation for a feasible group, or
     *         {@link Optional#empty()} if the group is infeasible
     * @throws EvaluationException if a coefficient evaluates to a non-numeric value,
     *                             or the solver did not decide the group
     */
    public Optional<List<AllocationRow>> allocate(ObjectiveSense sense, Operand objective,
                                                  List<OptimizeConstraint> constraints,
                                                  double lo, double hi,
                                                  List<Row> groupRows, String groupLabel) {
        // 1. Extract coefficients; rows with any NULL coefficient are excluded from
        //    the model (they receive a zero allocation and are emitted separately).
        List<Row> nullRows = new ArrayList<>();
        Coefficients coefficients = extract(objective, constraints, groupRows, nullRows);
        List<Row> candidates = coefficients.candidates();

        if (candidates.isEmpty()) {
            // Trivially feasible: nothing to allocate.  Null-coefficient rows get zero.
            List<AllocationRow> out = new ArrayList<>(nullRows.size());
            for (Row row : nullRows) out.add(new AllocationRow(row, 0.0));
            return Optional.of(out);
        }

        // 2. Build the LP: one continuous variable per candidate in [lo, hi].
        List<LinearProgram.Variable> variables = new ArrayList<>(candidates.size());
        for (double weight : coefficients.objective()) {
            variables.add(LinearProgram.Variable.continuous(lo, hi, weight));
        }

        // 3. Solve and read back allocation values.
        Optional<double[]> solution = read(solver.solve(new LinearProgram(
                sense(sense), variables, constraintsOf(constraints, coefficients))),
                "OPTIMIZE ALLOCATE", "group " + groupLabel);
        if (solution.isEmpty()) {
            return Optional.empty();
        }
        List<AllocationRow> output = new ArrayList<>(groupRows.size());
        for (int i = 0; i < candidates.size(); i++) {
            output.add(new AllocationRow(candidates.get(i), solution.get()[i]));
        }
        // NULL-coefficient rows are appended with a zero allocation (excluded from the model).
        for (Row row : nullRows) {
            output.add(new AllocationRow(row, 0.0));
        }
        return Optional.of(output);
    }

    /**
     * Solves the exact minimum covering problem for one candidate set.
     *
     * <p>This is the MIP set-cover formulation: given a list of candidate rows and a
     * list of demanded t-tuple covering sets (each a list of candidate indices that
     * cover that t-tuple), finds the minimum-cardinality subset of candidates that
     * covers every demanded t-tuple.
     *
     * <p>One binary variable per candidate (weight 1 — we minimise count); one
     * {@code ≥ 1} constraint per demanded t-tuple (at least one covering candidate
     * must be chosen). A demanded t-tuple with no covering candidates makes the
     * problem infeasible.
     *
     * @param candidates   the candidate rows (may be empty — returns empty list)
     * @param coveringSets one entry per demanded t-tuple: the list of candidate
     *                     indices (into {@code candidates}) that cover it;
     *                     an empty inner list means no candidate covers that tuple
     *                     (→ returns {@link Optional#empty()})
     * @return the chosen minimal covering rows, or {@link Optional#empty()} if infeasible
     */
    public Optional<List<Row>> setcover(List<Row> candidates, List<List<Integer>> coveringSets) {
        if (candidates.isEmpty() || coveringSets.isEmpty()) {
            return Optional.of(List.of());
        }
        // 1. Build the MIP: one binary variable per candidate (minimise total count);
        //    one >= 1 expression per demanded t-tuple.
        List<LinearProgram.Variable> variables = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            variables.add(LinearProgram.Variable.binary(1.0));
        }
        List<LinearProgram.Constraint> demanded = new ArrayList<>(coveringSets.size());
        for (List<Integer> covering : coveringSets) {
            if (covering.isEmpty()) {
                return Optional.empty(); // uncoverable tuple → infeasible
            }
            double[] weights = new double[candidates.size()];
            for (int idx : covering) {
                weights[idx] = 1.0;
            }
            // at least one covering candidate must be chosen
            demanded.add(new LinearProgram.Constraint(
                    weights, LinearProgram.Relation.AT_LEAST, 1.0));
        }
        // 2. Solve (minimise row count) and read back the chosen rows.
        Optional<double[]> solution = read(solver.solve(
                new LinearProgram(LinearProgram.Sense.MINIMISE, variables, demanded)),
                "COVER EXACT", "the covering model");
        if (solution.isEmpty()) {
            return Optional.empty();
        }
        List<Row> chosen = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (solution.get()[i] >= CHOSEN_THRESHOLD) {
                chosen.add(candidates.get(i));
            }
        }
        return Optional.of(chosen);
    }

    /**
     * The per-candidate coefficients of one group: the rows that survived, their
     * objective weights, and their constraint weights (one {@code double[]} per row,
     * indexed by constraint).
     */
    private record Coefficients(List<Row> candidates, List<Double> objectiveWeights,
                                List<double[]> constraintWeights) {

        /** The objective weights in candidate order. */
        double[] objective() {
            double[] weights = new double[objectiveWeights.size()];
            for (int i = 0; i < weights.length; i++) {
                weights[i] = objectiveWeights.get(i);
            }
            return weights;
        }
    }

    /**
     * Evaluates the objective and every constraint expression against each row,
     * keeping only rows whose coefficients are all known. A row with any NULL
     * coefficient can never be chosen; when {@code nullRows} is non-null it is
     * collected there instead (the LP mode still reports it, with a zero allocation).
     */
    private Coefficients extract(Operand objective, List<OptimizeConstraint> constraints,
                                 List<Row> groupRows, List<Row> nullRows) {
        List<Row> candidates = new ArrayList<>();
        List<Double> objCoeffs = new ArrayList<>();
        List<double[]> constraintCoeffs = new ArrayList<>();
        for (Row row : groupRows) {
            Double obj = coefficient(objective, row);
            if (obj == null) {
                if (nullRows != null) nullRows.add(row);
                continue;
            }
            double[] coeffs = new double[constraints.size()];
            boolean complete = true;
            for (int k = 0; k < constraints.size(); k++) {
                Double c = coefficient(constraints.get(k).expr(), row);
                if (c == null) { complete = false; break; }
                coeffs[k] = c;
            }
            if (!complete) {
                if (nullRows != null) nullRows.add(row);
                continue;
            }
            candidates.add(row);
            objCoeffs.add(obj);
            constraintCoeffs.add(coeffs);
        }
        return new Coefficients(candidates, objCoeffs, constraintCoeffs);
    }

    /**
     * Transposes the per-row constraint coefficients into one program constraint per
     * declared constraint, applying its comparison operator as the bound.
     */
    /**
     * Reads what the solver concluded: the variable values when it solved, empty
     * when the program is <em>proved</em> infeasible — a legitimate answer, and the
     * caller skips the group — and an error when the search did not decide.
     *
     * <p>That last case is the one worth being loud about. A failed or cut-off
     * search has established nothing about the program, so reporting it as an empty
     * group would answer a question the engine never actually answered, and the
     * query would return rows that are quietly wrong. It is caught here rather than
     * at each executor for the reason the rest of the relational reading lives here:
     * {@code OPTIMIZE} and {@code COVER EXACT} must not disagree about what a
     * solver's silence means.
     *
     * @param result   what the solver returned
     * @param operator the operator to name in the diagnostic
     * @param subject  what could not be decided, e.g. {@code "group (desk=FX)"}
     * @return the values, or empty when the program is infeasible
     * @throws EvaluationException if the solver did not decide
     */
    private Optional<double[]> read(SolverResult result, String operator, String subject) {
        return switch (result) {
            case SolverResult.Solved solved -> Optional.of(solved.values());
            case SolverResult.Infeasible ignored -> Optional.empty();
            case SolverResult.Undetermined undecided -> throw new EvaluationException(
                    operator + " could not decide " + subject + ": " + solver.name()
                    + " stopped at " + undecided.reason() + " without proving the program "
                    + "infeasible, so there is no result to report");
        };
    }

    private static List<LinearProgram.Constraint> constraintsOf(
            List<OptimizeConstraint> constraints, Coefficients coefficients) {
        int candidates = coefficients.candidates().size();
        List<LinearProgram.Constraint> out = new ArrayList<>(constraints.size());
        for (int k = 0; k < constraints.size(); k++) {
            double[] weights = new double[candidates];
            for (int i = 0; i < candidates; i++) {
                weights[i] = coefficients.constraintWeights().get(i)[k];
            }
            OptimizeConstraint constraint = constraints.get(k);
            out.add(new LinearProgram.Constraint(
                    weights, relation(constraint.op()), constraint.bound()));
        }
        return out;
    }

    private static LinearProgram.Sense sense(ObjectiveSense sense) {
        return sense == ObjectiveSense.MAXIMIZE
                ? LinearProgram.Sense.MAXIMISE
                : LinearProgram.Sense.MINIMISE;
    }

    private static LinearProgram.Relation relation(ComparisonOperator op) {
        return switch (op) {
            case LESS_EQUAL    -> LinearProgram.Relation.AT_MOST;
            case GREATER_EQUAL -> LinearProgram.Relation.AT_LEAST;
            case EQUAL         -> LinearProgram.Relation.EXACTLY;
            default -> throw new EvaluationException(
                    "OPTIMIZE: unsupported constraint operator " + op);
        };
    }

    /** Evaluates {@code expr} against {@code row}; {@code null} for a NULL value. */
    private Double coefficient(Operand expr, Row row) {
        Value value = evaluator.evaluate(expr, row);
        if (value.isNull()) return null;
        if (!(value instanceof NumberValue nv)) {
            throw new EvaluationException(
                    "OPTIMIZE: coefficient must be NUMBER, got " + value.type());
        }
        return nv.value().doubleValue();
    }
}

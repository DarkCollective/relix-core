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
package com.darkcollective.relix.solver;

import java.util.List;
import java.util.Objects;

/**
 * One linear program: an objective to maximise or minimise over a list of decision
 * variables, subject to a list of linear constraints over the same variables.
 *
 * <p>This is the whole of what the engine says to a solver. Every relational notion —
 * which rows are candidates, what expression produced a coefficient, what a NULL
 * coefficient means — has already been resolved by the time a program is built; what
 * remains is arithmetic. That split is deliberate: the rules for dropping a row whose
 * objective is NULL are the {@code OPTIMIZE} operator's semantics, and two solvers must
 * not be able to disagree about them.
 *
 * <p>A constraint's coefficient array is positional: {@code coefficients[i]} multiplies
 * {@code variables.get(i)}, and the array is exactly as long as the variable list.
 *
 * @param sense       whether the objective is maximised or minimised
 * @param variables   the decision variables, in the order a solution reports them
 * @param constraints the linear constraints; may be empty
 */
public record LinearProgram(Sense sense, List<Variable> variables, List<Constraint> constraints) {

    /** Whether the objective is to be maximised or minimised. */
    public enum Sense {
        /** Find the assignment with the largest objective value. */
        MAXIMISE,
        /** Find the assignment with the smallest objective value. */
        MINIMISE
    }

    /** How a constraint's weighted sum relates to its bound. */
    public enum Relation {
        /** The weighted sum must not exceed the bound. */
        AT_MOST,
        /** The weighted sum must be at least the bound. */
        AT_LEAST,
        /** The weighted sum must equal the bound. */
        EXACTLY
    }

    /**
     * One decision variable: a value in {@code [lower, upper]} contributing
     * {@code weight} per unit to the objective.
     *
     * @param integral whether the variable is restricted to whole numbers
     * @param lower    the inclusive lower bound
     * @param upper    the inclusive upper bound
     * @param weight   the variable's objective coefficient
     */
    public record Variable(boolean integral, double lower, double upper, double weight) {

        /**
         * A 0/1 decision — "is this row chosen?".
         *
         * @param weight the objective coefficient earned when the variable is 1
         * @return a binary variable
         */
        public static Variable binary(double weight) {
            return new Variable(true, 0.0, 1.0, weight);
        }

        /**
         * A continuous decision within a range — "how much of this row?".
         *
         * @param lower  the inclusive lower bound
         * @param upper  the inclusive upper bound
         * @param weight the objective coefficient per unit
         * @return a continuous variable
         */
        public static Variable continuous(double lower, double upper, double weight) {
            return new Variable(false, lower, upper, weight);
        }
    }

    /**
     * One linear constraint: {@code Σ coefficients[i]·x[i]} related to {@code bound}.
     *
     * @param coefficients one coefficient per variable, positionally
     * @param relation     how the weighted sum relates to the bound
     * @param bound        the right-hand side
     */
    public record Constraint(double[] coefficients, Relation relation, double bound) {

        /** @throws NullPointerException if the coefficients or the relation are null */
        public Constraint {
            Objects.requireNonNull(coefficients, "coefficients");
            Objects.requireNonNull(relation, "relation");
            coefficients = coefficients.clone();
        }

        /**
         * @return a copy of the coefficients, so a solver cannot mutate the program
         */
        @Override
        public double[] coefficients() {
            return coefficients.clone();
        }
    }

    /**
     * @throws NullPointerException     if any component is null
     * @throws IllegalArgumentException if a constraint's coefficient count does not
     *                                  match the variable count
     */
    public LinearProgram {
        Objects.requireNonNull(sense, "sense");
        variables = List.copyOf(variables);
        constraints = List.copyOf(constraints);
        for (Constraint constraint : constraints) {
            if (constraint.coefficients().length != variables.size()) {
                throw new IllegalArgumentException(
                        "constraint has " + constraint.coefficients().length
                        + " coefficients for " + variables.size() + " variables");
            }
        }
    }
}

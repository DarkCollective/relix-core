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
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.SolveEquation;
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.arith;
import static com.darkcollective.relix.ast.AstBuilders.attr;
import static com.darkcollective.relix.ast.AstBuilders.equation;
import static com.darkcollective.relix.ast.AstBuilders.unary;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EquationSystemSolver — per-row linear systems")
final class EquationSystemSolverTest extends ProcessorTestSupport {

    private static final EquationSystemSolver SOLVER = new EquationSystemSolver();

    private static final Schema S = schema(
            col("total", ScalarType.NUMBER),
            col("diff", ScalarType.NUMBER),
            col("a", ScalarType.NUMBER),
            col("b", ScalarType.NUMBER));

    private static Operand plus(Operand l, Operand r)  { return arith(l, ArithmeticOperator.PLUS, r); }
    private static Operand minus(Operand l, Operand r) { return arith(l, ArithmeticOperator.MINUS, r); }
    private static Operand times(Operand l, Operand r) { return arith(l, ArithmeticOperator.MULTIPLY, r); }
    private static Operand over(Operand l, Operand r)  { return arith(l, ArithmeticOperator.DIVIDE, r); }

    /** total = a + b, diff = a − b. */
    private static final List<SolveEquation> SUM_AND_DIFFERENCE = List.of(
            equation(attr("total"), plus(attr("a"), attr("b"))),
            equation(attr("diff"), minus(attr("a"), attr("b"))));

    private static Value cell(String v) {
        return v == null ? nullVal() : num(v);
    }

    private static Row row4(String total, String diff, String a, String b) {
        return row(S, cell(total), cell(diff), cell(a), cell(b));
    }

    @Nested
    @DisplayName("a solvable row")
    final class Solvable {

        @Test
        @DisplayName("fills both unknowns of a 2 × 2 system")
        void solvesTwoUnknowns() {
            Row out = SOLVER.solve(SUM_AND_DIFFERENCE, row4("10", "2", null, null));
            assertThat(out).hasValue("a", "6").hasValue("b", "4");
        }

        @Test
        @DisplayName("solves for whichever columns are blank, on either side")
        void directionIsPerRow() {
            Row out = SOLVER.solve(SUM_AND_DIFFERENCE, row4(null, null, "7", "3"));
            assertThat(out).hasValue("total", "10").hasValue("diff", "4");
        }

        @Test
        @DisplayName("needs a pivot exchange when the first coefficient is zero")
        void pivots() {
            // total = b, diff = a + b : the first equation has no a.
            List<SolveEquation> eqs = List.of(
                    equation(attr("total"), attr("b")),
                    equation(attr("diff"), plus(attr("a"), attr("b"))));
            assertThat(SOLVER.solve(eqs, row4("3", "10", null, null)))
                    .hasValue("a", "7").hasValue("b", "3");
        }

        @Test
        @DisplayName("divides by known terms and negates")
        void dividesAndNegates() {
            // total = a / diff − b, diff = −a + b * 2, with total 1 and diff 4:
            // a = 4 + 4b, so −4 − 4b + 2b = 4, b = −4 and a = −12.
            List<SolveEquation> eqs = List.of(
                    equation(attr("total"), minus(over(attr("a"), attr("diff")), attr("b"))),
                    equation(attr("diff"), plus(unary(attr("a")), times(attr("b"), lit("2")))));
            assertThat(SOLVER.solve(eqs, row4("1", "4", null, null)))
                    .hasValue("a", "-12").hasValue("b", "-4");
        }

        @Test
        @DisplayName("rounds an inexact answer to ten fractional digits")
        void roundsToTenDigits() {
            // 3a = total, b = diff  ⇒  a = 1/3
            List<SolveEquation> eqs = List.of(
                    equation(times(lit("3"), attr("a")), attr("total")),
                    equation(attr("b"), attr("diff")));
            assertThat(SOLVER.solve(eqs, row4("1", "2", null, null)))
                    .hasValue("a", "0.3333333333").hasValue("b", "2");
        }
    }

    @Nested
    @DisplayName("a row that cannot be posed passes through unchanged")
    final class PassesThrough {

        @Test
        @DisplayName("when nothing is blank")
        void complete() {
            Row in = row4("10", "2", "6", "4");
            assertThat(SOLVER.solve(SUM_AND_DIFFERENCE, in)).isSameAs(in);
        }

        @Test
        @DisplayName("when it has fewer unknowns than equations")
        void overdetermined() {
            Row in = row4("10", "2", "6", null);
            assertThat(SOLVER.solve(SUM_AND_DIFFERENCE, in)).isSameAs(in);
        }

        @Test
        @DisplayName("when it has more unknowns than equations")
        void underdetermined() {
            Row in = row4(null, "2", null, null);
            assertThat(SOLVER.solve(SUM_AND_DIFFERENCE, in)).isSameAs(in);
        }

        @Test
        @DisplayName("when the system is singular")
        void singular() {
            // total = a + b, diff = 2a + 2b : the second is the first, doubled.
            List<SolveEquation> eqs = List.of(
                    equation(attr("total"), plus(attr("a"), attr("b"))),
                    equation(attr("diff"), times(lit("2"), plus(attr("a"), attr("b")))));
            Row in = row4("10", "20", null, null);
            assertThat(SOLVER.solve(eqs, in)).isSameAs(in);
        }

        @Test
        @DisplayName("when an unknown is not positionally addressable")
        void openSchema() {
            Row in = row(schema(col("total", ScalarType.NUMBER), col("diff", ScalarType.NUMBER)),
                    num("10"), num("2"));
            assertThat(SOLVER.solve(SUM_AND_DIFFERENCE, in)).isSameAs(in);
        }
    }

    @Nested
    @DisplayName("a row that is neither invertible nor linear is solved iteratively")
    final class Iterative {

        @Test
        @DisplayName("when two unknowns multiply")
        void productOfUnknowns() {
            List<SolveEquation> eqs = List.of(
                    equation(attr("total"), times(attr("a"), attr("b"))),
                    equation(attr("diff"), minus(attr("a"), attr("b"))));
            assertThat(SOLVER.solve(eqs, row4("12", "1", null, null)))
                    .hasValue("a", "4").hasValue("b", "3");
        }

        @Test
        @DisplayName("when the product is on the left")
        void productOnTheLeft() {
            List<SolveEquation> eqs = List.of(
                    equation(times(attr("a"), attr("b")), attr("total")),
                    equation(attr("diff"), minus(attr("a"), attr("b"))));
            assertThat(SOLVER.solve(eqs, row4("12", "1", null, null)))
                    .hasValue("a", "4").hasValue("b", "3");
        }

        @Test
        @DisplayName("from a start at which the equations are degenerate")
        void degenerateStart() {
            // At a = b = 1 both gradients are (1, −1): the undamped step does not exist.
            List<SolveEquation> eqs = List.of(
                    equation(attr("total"), over(attr("a"), attr("b"))),
                    equation(attr("diff"), minus(attr("a"), attr("b"))));
            assertThat(SOLVER.solve(eqs, row4("3", "4", null, null)))
                    .hasValue("a", "6").hasValue("b", "2");
        }

        @Test
        @DisplayName("when one equation names its unknown twice")
        void repeatedUnknown() {
            Schema s = schema(col("area", ScalarType.NUMBER), col("side", ScalarType.NUMBER));
            List<SolveEquation> eq = List.of(equation(attr("area"), times(attr("side"), attr("side"))));
            assertThat(SOLVER.solve(eq, row(s, num("2"), nullVal()))).hasValue("side", "1.4142135624");
        }

        @Test
        @DisplayName("refusing a step that lands on a pole, and going on")
        void stepOntoAPole() {
            // y = x / (x − 2) from x = 1: with y = −3.0025 the first damped step is
            // exactly 1, onto x = 2. It is refused, and the search still converges.
            Schema s = schema(col("y", ScalarType.NUMBER), col("x", ScalarType.NUMBER));
            List<SolveEquation> eq = List.of(equation(attr("y"),
                    over(attr("x"), minus(attr("x"), lit("2")))));
            Row out = SOLVER.solve(eq, row(s, num("-3.0025"), nullVal()));
            assertThat(out).hasValue("x", "1.5003123048");
        }
    }

    @Nested
    @DisplayName("an iteration that finds no answer")
    final class IterativeFailures {

        private final Schema s = schema(col("area", ScalarType.NUMBER), col("side", ScalarType.NUMBER));
        private final List<SolveEquation> square =
                List.of(equation(attr("area"), times(attr("side"), attr("side"))));

        @Test
        @DisplayName("is an error when the equation has no solution")
        void noSolution() {
            assertThatThrownBy(() -> SOLVER.solve(square, row(s, num("-1"), nullVal())))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("no solution")
                    .hasMessageContaining("area=-1");
        }

        @Test
        @DisplayName("is an error when the round cap is reached")
        void roundCap() {
            EquationSystemSolver capped = new EquationSystemSolver(new OperandEvaluator(),
                    EquationSystemSolver.DEFAULT_TOLERANCE, 1);
            assertThatThrownBy(() -> capped.solve(square, row(s, num("1000000"), nullVal())))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("within 1 rounds");
        }

        @Test
        @DisplayName("is an error when an equation divides by zero at the start")
        void poleAtTheStart() {
            Schema yx = schema(col("y", ScalarType.NUMBER), col("x", ScalarType.NUMBER));
            List<SolveEquation> eq = List.of(equation(attr("y"),
                    over(attr("x"), minus(attr("x"), lit("1")))));
            assertThatThrownBy(() -> SOLVER.solve(eq, row(yx, num("2"), nullVal())))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("division by zero")
                    .hasMessageContaining("the row");
        }

        @Test
        @DisplayName("leaves the row unchanged when the equations do not determine the unknowns")
        void undetermined() {
            // The second equation is the first, doubled: only the product is fixed.
            List<SolveEquation> eqs = List.of(
                    equation(attr("total"), times(attr("a"), attr("b"))),
                    equation(attr("diff"), times(lit("2"), times(attr("a"), attr("b")))));
            Row in = row4("6", "12", null, null);
            assertThat(SOLVER.solve(eqs, in)).isSameAs(in);
        }
    }

    @Nested
    @DisplayName("a known value the arithmetic cannot use")
    final class Errors {

        @Test
        @DisplayName("is reported when it is not a number")
        void nonNumeric() {
            Schema s = schema(col("total", ScalarType.NUMBER), col("label", ScalarType.STRING),
                    col("a", ScalarType.NUMBER), col("b", ScalarType.NUMBER));
            List<SolveEquation> eqs = List.of(
                    equation(attr("total"), plus(attr("a"), attr("b"))),
                    equation(attr("label"), minus(attr("a"), attr("b"))));
            Row in = row(s, num("10"), str("x"), nullVal(), nullVal());
            assertThatThrownBy(() -> SOLVER.solve(eqs, in))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("NUMBER");
        }

        @Test
        @DisplayName("is reported when a known divisor is zero")
        void divisionByZero() {
            List<SolveEquation> eqs = List.of(
                    equation(attr("total"), plus(attr("a"), over(attr("b"), attr("diff")))),
                    equation(attr("total"), minus(attr("a"), attr("b"))));
            Row in = row4("10", "0", null, null);
            assertThatThrownBy(() -> SOLVER.solve(eqs, in))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("division by zero");
        }
    }

    private static Operand lit(String v) {
        return com.darkcollective.relix.ast.AstBuilders.num(v);
    }
}

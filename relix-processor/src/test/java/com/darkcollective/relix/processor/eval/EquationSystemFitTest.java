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
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;

@DisplayName("EquationSystemSolver.fit — least squares across a group")
final class EquationSystemFitTest extends ProcessorTestSupport {

    private static final EquationSystemSolver SOLVER = new EquationSystemSolver();

    private static final Schema S = schema(
            col("x", ScalarType.NUMBER),
            col("y", ScalarType.NUMBER),
            col("a", ScalarType.NUMBER),
            col("b", ScalarType.NUMBER));

    /** y = a * x + b. */
    private static final List<SolveEquation> LINE = List.of(equation(attr("y"),
            arith(arith(attr("a"), ArithmeticOperator.MULTIPLY, attr("x")),
                    ArithmeticOperator.PLUS, attr("b"))));

    private static List<Row> fit(List<SolveEquation> equations, List<Row> group) {
        return SOLVER.fit(equations, group, "series=s1");
    }

    private static Value cell(String v) {
        return v == null ? nullVal() : num(v);
    }

    private static Row point(String x, String y) {
        return row(S, cell(x), cell(y), nullVal(), nullVal());
    }

    @Nested
    @DisplayName("a group that can be fitted")
    final class Fitted {

        @Test
        @DisplayName("recovers an exact line and writes it into every row")
        void exactLine() {
            List<Row> out = fit(LINE, List.of(point("1", "3"), point("2", "5"), point("3", "7")));
            assertThat(out).allSatisfy(r -> assertThat(r).hasValue("a", "2").hasValue("b", "1"));
        }

        @Test
        @DisplayName("minimises the squared residuals when the points do not fit exactly")
        void leastSquares() {
            List<Row> out = fit(LINE, List.of(point("0", "1"), point("1", "3"), point("2", "4")));
            assertThat(out.getFirst()).hasValue("a", "1.5").hasValue("b", "1.1666666667");
        }

        @Test
        @DisplayName("fills a row that is not an observation too, leaving its own blank")
        void fillsNonObservations() {
            List<Row> out = fit(LINE,
                    List.of(point("1", "3"), point("2", "5"), point("4", null)));
            assertThat(out.get(2)).hasValue("a", "2").hasValue("b", "1");
            assertThat(out.get(2).get("y").isNull()).isTrue();
        }

        @Test
        @DisplayName("is the exact solve with one row and as many residuals as unknowns")
        void oneRowExactSolve() {
            Schema s = schema(col("total", ScalarType.NUMBER), col("rate", ScalarType.NUMBER),
                    col("principal", ScalarType.NUMBER));
            List<SolveEquation> eq = List.of(equation(attr("total"),
                    arith(attr("principal"), ArithmeticOperator.MULTIPLY, attr("rate"))));
            List<Row> out = fit(eq, List.of(row(s, num("50"), num("4"), nullVal())));
            assertThat(out.getFirst()).hasValue("principal", "12.5");
        }
    }

    @Nested
    @DisplayName("a group whose equations are not linear in the unknowns")
    final class Iterative {

        private final Schema mm = schema(
                col("s", ScalarType.NUMBER), col("rate", ScalarType.NUMBER),
                col("vmax", ScalarType.NUMBER), col("km", ScalarType.NUMBER));

        /** rate = vmax * s / (km + s). */
        private final List<SolveEquation> michaelisMenten = List.of(equation(attr("rate"),
                arith(arith(attr("vmax"), ArithmeticOperator.MULTIPLY, attr("s")),
                        ArithmeticOperator.DIVIDE,
                        arith(attr("km"), ArithmeticOperator.PLUS, attr("s")))));

        private Row assay(String s, String rate) {
            return row(mm, num(s), num(rate), nullVal(), nullVal());
        }

        @Test
        @DisplayName("is fitted by Gauss–Newton")
        void fitsACurve() {
            List<Row> out = fit(michaelisMenten,
                    List.of(assay("2", "5"), assay("6", "7.5"), assay("8", "8"), assay("18", "9")));
            assertThat(out).allSatisfy(r -> assertThat(r).hasValue("vmax", "10").hasValue("km", "2"));
        }

        @Test
        @DisplayName("names the group when the search does not converge")
        void namesTheGroup() {
            EquationSystemSolver capped = new EquationSystemSolver(new OperandEvaluator(),
                    EquationSystemSolver.DEFAULT_TOLERANCE, 1);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> capped.fit(michaelisMenten,
                            List.of(assay("2", "5"), assay("6", "7.5"), assay("8", "8")), "enzyme=e1"))
                    .hasMessageContaining("the group enzyme=e1");
        }
    }

    @Nested
    @DisplayName("a group that cannot be fitted comes back unchanged")
    final class Unchanged {

        @Test
        @DisplayName("when it is empty")
        void empty() {
            assertThat(fit(LINE, List.of())).isEmpty();
        }

        @Test
        @DisplayName("when no column is blank in every row")
        void noUnknown() {
            Row r = row(S, num("1"), num("3"), num("2"), nullVal());
            List<Row> group = List.of(r, row(S, num("2"), num("5"), nullVal(), num("1")));
            assertThat(fit(LINE, group)).isSameAs(group);
        }

        @Test
        @DisplayName("when there are fewer residuals than unknowns")
        void tooFewObservations() {
            List<Row> group = List.of(point("1", "3"));
            assertThat(fit(LINE, group)).isSameAs(group);
        }

        @Test
        @DisplayName("when the normal equations are singular")
        void singular() {
            // Every point at the same x: the slope is not determined.
            List<Row> group = List.of(point("2", "3"), point("2", "5"));
            assertThat(fit(LINE, group)).isSameAs(group);
        }

        @Test
        @DisplayName("when an equation is not linear in the unknowns")
        void nonlinear() {
            Operand ax = arith(attr("a"), ArithmeticOperator.MULTIPLY, attr("x"));
            List<SolveEquation> curve = List.of(equation(attr("y"),
                    arith(ax, ArithmeticOperator.MULTIPLY, attr("b"))));
            List<Row> group = List.of(point("1", "3"), point("2", "5"));
            assertThat(fit(curve, group)).isSameAs(group);
        }

        @Test
        @DisplayName("when a column is not positionally addressable")
        void openSchema() {
            List<Row> group = List.of(row(schema(col("x", ScalarType.NUMBER)), num("1")));
            assertThat(fit(LINE, group)).isSameAs(group);
        }
    }
}

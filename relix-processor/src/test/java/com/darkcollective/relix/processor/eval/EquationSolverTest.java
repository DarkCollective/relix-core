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

import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.UnaryOperand;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EquationSolver — per-row goal-seek inversion")
final class EquationSolverTest extends ProcessorTestSupport {

    private static final EquationSolver SOLVER = new EquationSolver(new OperandEvaluator());

    // Three numeric columns: total, principal, rate.
    private static final Schema S = schema(
            col("total", ScalarType.NUMBER),
            col("principal", ScalarType.NUMBER),
            col("rate", ScalarType.NUMBER));

    private static Operand a(String name)       { return AstBuilders.attr(name); }
    private static Operand n(String value)      { return AstBuilders.num(value); }
    private static Operand neg(Operand o)       { return AstBuilders.unary(o); }
    private static Operand bin(Operand l, ArithmeticOperator op, Operand r) {
        return AstBuilders.arith(l, op, r);
    }

    /** A {total, principal, rate} row from display strings; {@code null} → NULL cell. */
    private static Row row3(String total, String principal, String rate) {
        return row(S,
                total == null ? nullVal() : num(total),
                principal == null ? nullVal() : num(principal),
                rate == null ? nullVal() : num(rate));
    }

    private static String solved(Operand left, Operand right, Row row, String column) {
        return SOLVER.solve(left, right, row).get(column).asDisplayString();
    }

    @Nested
    @DisplayName("Per-row direction — total = principal * rate")
    class MultiplyEquation {
        private final Operand left = a("total");
        private final Operand right = bin(a("principal"), ArithmeticOperator.MULTIPLY, a("rate"));

        @Test
        @DisplayName("solves the bare left-hand side (total)")
        void solvesTotal() {
            assertThat(solved(left, right, row3(null, "4", "5"), "total")).isEqualTo("20");
        }

        @Test
        @DisplayName("solves a factor on the right (principal = total / rate)")
        void solvesPrincipal() {
            assertThat(solved(left, right, row3("20", null, "5"), "principal")).isEqualTo("4");
        }

        @Test
        @DisplayName("solves the other factor (rate = total / principal)")
        void solvesRate() {
            assertThat(solved(left, right, row3("20", "4", null), "rate")).isEqualTo("5");
        }

        @Test
        @DisplayName("resolves relation-qualified column names against the row")
        void qualifiedColumnNames() {
            Operand qualified = bin(a("Loans.principal"), ArithmeticOperator.MULTIPLY, a("Loans.rate"));
            assertThat(solved(a("Loans.total"), qualified, row3("20", "4", null), "rate"))
                    .isEqualTo("5");
        }
    }

    @Nested
    @DisplayName("Each operator, unknown on each side")
    class OperatorInversions {

        @Test
        @DisplayName("addition: total = principal + rate")
        void addition() {
            Operand r = bin(a("principal"), ArithmeticOperator.PLUS, a("rate"));
            assertThat(solved(a("total"), r, row3(null, "3", "4"), "total")).isEqualTo("7");
            assertThat(solved(a("total"), r, row3("7", null, "4"), "principal")).isEqualTo("3");
            assertThat(solved(a("total"), r, row3("7", "3", null), "rate")).isEqualTo("4");
        }

        @Test
        @DisplayName("subtraction: total = principal - rate (both positions)")
        void subtraction() {
            Operand r = bin(a("principal"), ArithmeticOperator.MINUS, a("rate"));
            assertThat(solved(a("total"), r, row3("2", null, "5"), "principal")).isEqualTo("7"); // p = total + rate
            assertThat(solved(a("total"), r, row3("2", "7", null), "rate")).isEqualTo("5");       // rate = principal - total
        }

        @Test
        @DisplayName("division: total = principal / rate (both positions)")
        void division() {
            Operand r = bin(a("principal"), ArithmeticOperator.DIVIDE, a("rate"));
            assertThat(solved(a("total"), r, row3("4", null, "5"), "principal")).isEqualTo("20"); // p = total * rate
            assertThat(solved(a("total"), r, row3("4", "20", null), "rate")).isEqualTo("5");       // rate = principal / total
        }

        @Test
        @DisplayName("unary minus: total = -rate")
        void unaryMinus() {
            Operand r = neg(a("rate"));
            assertThat(solved(a("total"), r, row3("5", "0", null), "rate")).isEqualTo("-5");
            assertThat(solved(a("total"), r, row3(null, "0", "5"), "total")).isEqualTo("-5");
        }

        @Test
        @DisplayName("literal on the unknown's sibling branch: total = 2 * rate")
        void literalSiblingBranch() {
            // The unknown (rate) is the right factor; the left factor is a literal,
            // so the branch test resolves the unknown to the right.
            Operand r = bin(n("2"), ArithmeticOperator.MULTIPLY, a("rate"));
            assertThat(solved(a("total"), r, row3("10", "0", null), "rate")).isEqualTo("5");
        }

        @Test
        @DisplayName("unknown under a negation on a sibling branch: total = -rate + principal")
        void negatedUnknownInBranch() {
            Operand r = bin(neg(a("rate")), ArithmeticOperator.PLUS, a("principal"));
            // total=20, principal=4 ⇒ -rate = 16 ⇒ rate = -16
            assertThat(solved(a("total"), r, row3("20", "4", null), "rate")).isEqualTo("-16");
        }

        @Test
        @DisplayName("unknown alongside a compound sibling: total = (principal * 2) + rate")
        void compoundSiblingBranch() {
            Operand r = bin(bin(a("principal"), ArithmeticOperator.MULTIPLY, n("2")),
                    ArithmeticOperator.PLUS, a("rate"));
            // total=20, principal=4 ⇒ rate = 20 - (4 * 2) = 12
            assertThat(solved(a("total"), r, row3("20", "4", null), "rate")).isEqualTo("12");
        }

        @Test
        @DisplayName("unknown nested in a left branch: total = (rate + principal) * 2")
        void nestedUnknownLeftBranch() {
            Operand r = bin(bin(a("rate"), ArithmeticOperator.PLUS, a("principal")),
                    ArithmeticOperator.MULTIPLY, n("2"));
            // total=20 ⇒ rate + principal = 10; principal=4 ⇒ rate = 6
            assertThat(solved(a("total"), r, row3("20", "4", null), "rate")).isEqualTo("6");
        }

        @Test
        @DisplayName("multi-term: total = principal * rate + 0 (literal on known side)")
        void multiTermWithLiteral() {
            // total = principal * rate, with the unknown nested two levels deep.
            Operand r = bin(bin(a("principal"), ArithmeticOperator.MULTIPLY, a("rate")),
                    ArithmeticOperator.PLUS, n("2"));
            // total=22, principal=4 ⇒ rate = (22 - 2)/4 = 5
            assertThat(solved(a("total"), r, row3("22", "4", null), "rate")).isEqualTo("5");
        }
    }

    @Nested
    @DisplayName("Pass-through cases")
    class PassThrough {
        private final Operand left = a("total");
        private final Operand right = bin(a("principal"), ArithmeticOperator.MULTIPLY, a("rate"));

        @Test
        @DisplayName("zero unknowns: a fully-populated row is unchanged")
        void zeroUnknowns() {
            Row in = row3("20", "4", "5");
            assertThat(SOLVER.solve(left, right, in)).isSameAs(in);
        }

        @Test
        @DisplayName("two unknowns: an underdetermined row is unchanged")
        void twoUnknowns() {
            Row in = row3("20", null, null);
            assertThat(SOLVER.solve(left, right, in)).isSameAs(in);
        }
    }

    @Nested
    @DisplayName("Errors")
    class Errors {
        @Test
        @DisplayName("division by zero while inverting throws")
        void divisionByZero() {
            // total = principal * rate, solving principal with rate = 0 ⇒ total / 0
            Operand right = bin(a("principal"), ArithmeticOperator.MULTIPLY, a("rate"));
            assertThatThrownBy(() -> SOLVER.solve(a("total"), right, row3("20", null, "0")))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("division by zero");
        }

        @Test
        @DisplayName("a non-numeric value on the known side throws")
        void nonNumericKnownSide() {
            // total (unknown, NULL) = label (a STRING on the known side) ⇒ not invertible.
            Schema s = schema(col("total", ScalarType.NUMBER), col("label", ScalarType.ANY));
            Row in = row(s, nullVal(), str("hello"));
            assertThatThrownBy(() -> SOLVER.solve(a("total"), a("label"), in))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("requires NUMBER");
        }
    }
}

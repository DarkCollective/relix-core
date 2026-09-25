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
package com.darkcollective.relix.parser;

import com.darkcollective.relix.ast.AstBuilders;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import com.darkcollective.relix.ast.*;
import com.darkcollective.relix.ast.internal.*;

import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;

/**
 * Tests for the declarative-optimisation operator (OPTIMIZE):
 * {@code OPTIMIZE [ALLOCATE (lo, hi)] MAXIMIZE|MINIMIZE SUM(expr) SUBJECT TO … [-> col] [PER …] (R)}.
 */
final class OptimizeParserTest extends ParserTestSupport {

    private static OptimizeConstraint c(Operand expr, ComparisonOperator op, double bound) {
        return AstBuilders.constraint(expr, op, bound);
    }

    @Nested
    class MipMode {

        @Test
        void parsesBasicKnapsack() {
            assertParsesTo("OPTIMIZE MAXIMIZE SUM(value) SUBJECT TO SUM(weight) <= 100 PER region (Cands)",
                    optimize(
                            ObjectiveSense.MAXIMIZE,
                            attr("value"),
                            List.of(c(attr("weight"), ComparisonOperator.LESS_EQUAL, 100)),
                            List.of("region"),
                            rel("Cands")));
        }

        @Test
        void parsesMinimiseWithoutPer() {
            assertParsesTo("OPTIMIZE MINIMIZE SUM(cost) SUBJECT TO SUM(coverage) >= 5 (Plans)",
                    optimize(
                            ObjectiveSense.MINIMIZE,
                            attr("cost"),
                            List.of(c(attr("coverage"), ComparisonOperator.GREATER_EQUAL, 5)),
                            List.of(),
                            rel("Plans")));
        }

        @Test
        void parsesMultipleConstraintsWithAnd() {
            assertParsesTo("OPTIMIZE MAXIMIZE SUM(value) SUBJECT TO SUM(weight) <= 100 AND SUM(volume) = 3 (R)",
                    optimize(
                            ObjectiveSense.MAXIMIZE,
                            attr("value"),
                            List.of(
                                    c(attr("weight"), ComparisonOperator.LESS_EQUAL, 100),
                                    c(attr("volume"), ComparisonOperator.EQUAL, 3)),
                            List.of(),
                            rel("R")));
        }

        @Test
        void parsesArithmeticCoefficientAndNegativeBound() {
            assertParsesTo("OPTIMIZE MAXIMIZE SUM(price * qty) SUBJECT TO SUM(delta) >= -5 (R)",
                    optimize(
                            ObjectiveSense.MAXIMIZE,
                            arith(attr("price"), ArithmeticOperator.MULTIPLY, attr("qty")),
                            List.of(c(attr("delta"), ComparisonOperator.GREATER_EQUAL, -5)),
                            List.of(),
                            rel("R")));
        }

        @Test
        void prettyPrintRoundTrips() {
            RelNode original = optimize(
                    ObjectiveSense.MAXIMIZE,
                    attr("value"),
                    List.of(c(attr("weight"), ComparisonOperator.LESS_EQUAL, 100)),
                    List.of("region"),
                    rel("Cands"));
            assertParsesTo(original.prettyPrint(), original);
        }

        @Test
        void failsWithoutSense() {
            assertParseError("OPTIMIZE SUM(value) SUBJECT TO SUM(weight) <= 100 (R)")
                    .hasMessageContaining("Expected MAXIMIZE or MINIMIZE");
        }

        @Test
        void failsWithoutSubjectTo() {
            assertParseError("OPTIMIZE MAXIMIZE SUM(value) PER region (R)")
                    .hasMessageContaining("SUBJECT TO");
        }

        @Test
        void failsOnNonRelationalConstraintOperator() {
            assertParseError("OPTIMIZE MAXIMIZE SUM(value) SUBJECT TO SUM(weight) != 100 (R)")
                    .hasMessageContaining("constraint operator");
        }

        @Test
        void failsWithoutInput() {
            assertParseError("OPTIMIZE MAXIMIZE SUM(value) SUBJECT TO SUM(weight) <= 100")
                    .hasMessageContaining("Expected '('");
        }
    }

    @Nested
    class LpMode {

        private static OptimizeNode lpNode(double lo, double hi, String col,
                                           ObjectiveSense sense, Operand obj,
                                           List<OptimizeConstraint> constraints,
                                           List<String> keys, String relation) {
            return optimize(sense, obj, constraints, keys,
                    Optional.of(allocation(lo, hi, col)),
                    rel(relation));
        }

        @Test
        void parsesBasicAllocation() {
            assertParsesTo(
                    "OPTIMIZE ALLOCATE (0.0, 1.0) MAXIMIZE SUM(ret) SUBJECT TO SUM(1) = 1.0 -> weight (Portfolio)",
                    lpNode(0.0, 1.0, "weight",
                            ObjectiveSense.MAXIMIZE, attr("ret"),
                            List.of(c(num("1"), ComparisonOperator.EQUAL, 1.0)),
                            List.of(), "Portfolio"));
        }

        @Test
        void parsesAllocationWithNegativeLowerBound() {
            assertParsesTo(
                    "OPTIMIZE ALLOCATE (-1.0, 1.0) MINIMIZE SUM(risk) SUBJECT TO SUM(ret) >= 0.05 -> alloc (Assets)",
                    lpNode(-1.0, 1.0, "alloc",
                            ObjectiveSense.MINIMIZE, attr("risk"),
                            List.of(c(attr("ret"), ComparisonOperator.GREATER_EQUAL, 0.05)),
                            List.of(), "Assets"));
        }

        @Test
        void parsesAllocationWithPerKeys() {
            assertParsesTo(
                    "OPTIMIZE ALLOCATE (0.0, 1.0) MAXIMIZE SUM(ret) SUBJECT TO SUM(1) = 1.0 -> weight PER sector (Portfolio)",
                    lpNode(0.0, 1.0, "weight",
                            ObjectiveSense.MAXIMIZE, attr("ret"),
                            List.of(c(num("1"), ComparisonOperator.EQUAL, 1.0)),
                            List.of("sector"), "Portfolio"));
        }

        @Test
        void parsesAllocationWithMultipleConstraints() {
            assertParsesTo(
                    "OPTIMIZE ALLOCATE (0.0, 1.0) MAXIMIZE SUM(ret) SUBJECT TO SUM(1) = 1.0 AND SUM(risk) <= 0.2 -> w (P)",
                    lpNode(0.0, 1.0, "w",
                            ObjectiveSense.MAXIMIZE, attr("ret"),
                            List.of(c(num("1"), ComparisonOperator.EQUAL, 1.0),
                                    c(attr("risk"), ComparisonOperator.LESS_EQUAL, 0.2)),
                            List.of(), "P"));
        }

        @Test
        void prettyPrintLpRoundTrips() {
            RelNode original = lpNode(0.0, 1.0, "weight",
                    ObjectiveSense.MAXIMIZE, attr("ret"),
                    List.of(c(num("1"), ComparisonOperator.EQUAL, 1.0)),
                    List.of(), "Portfolio");
            assertParsesTo(original.prettyPrint(), original);
        }

        @Test
        void prettyPrintLpWithPerRoundTrips() {
            RelNode original = lpNode(0.0, 1.0, "weight",
                    ObjectiveSense.MAXIMIZE, attr("ret"),
                    List.of(c(num("1"), ComparisonOperator.EQUAL, 1.0)),
                    List.of("sector"), "Portfolio");
            assertParsesTo(original.prettyPrint(), original);
        }

        @Test
        void failsWithoutOpenParen() {
            assertParseError("OPTIMIZE ALLOCATE 0.0, 1.0) MAXIMIZE SUM(ret) SUBJECT TO SUM(1) = 1.0 -> w (R)")
                    .hasMessageContaining("Expected '(' after ALLOCATE");
        }

        @Test
        void failsWithoutComma() {
            assertParseError("OPTIMIZE ALLOCATE (0.0 1.0) MAXIMIZE SUM(ret) SUBJECT TO SUM(1) = 1.0 -> w (R)")
                    .hasMessageContaining("','");
        }

        @Test
        void failsWithoutCloseParen() {
            assertParseError("OPTIMIZE ALLOCATE (0.0, 1.0 MAXIMIZE SUM(ret) SUBJECT TO SUM(1) = 1.0 -> w (R)")
                    .hasMessageContaining("Expected ')' after allocation bounds");
        }

        @Test
        void failsWithoutArrow() {
            assertParseError("OPTIMIZE ALLOCATE (0.0, 1.0) MAXIMIZE SUM(ret) SUBJECT TO SUM(1) = 1.0 (R)")
                    .hasMessageContaining("Expected '->'");
        }

        @Test
        void failsWithoutColumnName() {
            assertParseError("OPTIMIZE ALLOCATE (0.0, 1.0) MAXIMIZE SUM(ret) SUBJECT TO SUM(1) = 1.0 -> (R)")
                    .hasMessageContaining("Expected the allocation column name");
        }

        @Test
        void failsWhenLoExceedsHi() {
            assertParseError("OPTIMIZE ALLOCATE (1.0, 0.0) MAXIMIZE SUM(ret) SUBJECT TO SUM(1) = 1.0 -> w (R)")
                    .hasMessageContaining("lo must be ≤ hi");
        }

        @Test
        void failsWithoutSenseAfterAllocate() {
            assertParseError("OPTIMIZE ALLOCATE (0.0, 1.0) SUM(ret) SUBJECT TO SUM(1) = 1.0 -> w (R)")
                    .hasMessageContaining("Expected MAXIMIZE or MINIMIZE");
        }
    }
}

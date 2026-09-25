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
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.ObjectiveSense;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.OptimizeConstraint;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.solver.LinearProgram;
import com.darkcollective.relix.solver.MathProgrammingSolver;
import com.darkcollective.relix.solver.SolverResult;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SubsetOptimizer — per-group subset selection + LP allocation (ojAlgo)")
final class SubsetOptimizerTest extends ProcessorTestSupport {

    private static final SubsetOptimizer OPT = new SubsetOptimizer(new OperandEvaluator());

    private static final Schema ITEMS = schema(
            col("value", ScalarType.NUMBER),
            col("weight", ScalarType.NUMBER));

    private static final Schema ASSETS = schema(
            col("ret", ScalarType.NUMBER),
            col("risk", ScalarType.NUMBER));

    private static Operand a(String name)  { return AstBuilders.attr(name); }
    private static Operand n(String value) { return AstBuilders.num(value); }

    private static OptimizeConstraint le(Operand e, double b) {
        return constraint(e, ComparisonOperator.LESS_EQUAL, b);
    }
    private static OptimizeConstraint ge(Operand e, double b) {
        return constraint(e, ComparisonOperator.GREATER_EQUAL, b);
    }
    private static OptimizeConstraint eq(Operand e, double b) {
        return constraint(e, ComparisonOperator.EQUAL, b);
    }

    /** A {value, weight} row; null → NULL cell. */
    private static Row item(String value, String weight) {
        return row(ITEMS,
                value == null ? nullVal() : num(value),
                weight == null ? nullVal() : num(weight));
    }

    /** A {ret, risk} row; null → NULL cell. */
    private static Row asset(String ret, String risk) {
        return row(ASSETS,
                ret == null ? nullVal() : num(ret),
                risk == null ? nullVal() : num(risk));
    }

    /** The "value" cell of each chosen row, for order-insensitive assertions. */
    private static List<String> values(List<Row> rows) {
        return rows.stream().map(r -> r.get("value").asDisplayString()).sorted().toList();
    }

    /** Sums one numeric column across rows — for asserting an optimum rather than a subset. */
    private static int total(List<Row> rows, String column) {
        return rows.stream()
                .mapToInt(r -> Integer.parseInt(r.get(column).asDisplayString()))
                .sum();
    }

    @Nested
    @DisplayName("MIP mode (choose — 0/1 binary knapsack)")
    class MipMode {

    @Test
    @DisplayName("0/1 knapsack: maximise value within a weight cap")
    void knapsackMaximise() {
        var rows = List.of(item("60", "10"), item("100", "20"), item("120", "30"));
        // cap 50: {20,30} weight=50 value=220 beats {10,30}=180 and {10,20}=160
        Optional<List<Row>> chosen = OPT.choose(
                ObjectiveSense.MAXIMIZE, a("value"), List.of(le(a("weight"), 50)), rows, "(test group)");
        assertThat(chosen).isPresent();
        assertThat(values(chosen.get())).containsExactly("100", "120");
    }

    /**
     * A group whose empty selection already satisfies every {@code ≤} constraint can
     * never be infeasible, yet the solver's default feasibility tolerance reported
     * exactly that — silently turning an OPTIMIZE into a query returning no rows.
     * Four items rather than three is what trips it; the existing three-item cases
     * above all happened to fall on the safe side (issue #579).
     */
    @Test
    @DisplayName("a knapsack whose empty selection is feasible is never reported infeasible")
    void feasibleKnapsackIsNeverInfeasible() {
        var rows = List.of(item("60", "10"), item("100", "20"),
                           item("120", "30"), item("40", "5"));
        for (int cap = 1; cap <= 70; cap++) {
            Optional<List<Row>> chosen = OPT.choose(
                    ObjectiveSense.MAXIMIZE, a("value"), List.of(le(a("weight"), cap)), rows, "(test group)");
            assertThat(chosen).as("cap %d — the empty selection alone satisfies it", cap)
                    .isPresent();
            assertThat(total(chosen.get(), "weight"))
                    .as("cap %d — chosen rows must respect the bound", cap)
                    .isLessThanOrEqualTo(cap);
        }
    }

    @Test
    @DisplayName("the reference knapsack reaches its true optimum, not merely a feasible subset")
    void knapsackReachesOptimum() {
        // The docs/reference/advanced/optimize.md instance. Two subsets tie at 220:
        // {100,120} at cost 50 and {60,120,40} at cost 45 — so assert the value.
        var rows = List.of(item("60", "10"), item("100", "20"),
                           item("120", "30"), item("40", "5"));
        Optional<List<Row>> chosen = OPT.choose(
                ObjectiveSense.MAXIMIZE, a("value"), List.of(le(a("weight"), 50)), rows, "(test group)");
        assertThat(chosen).isPresent();
        assertThat(total(chosen.get(), "value")).isEqualTo(220);
        assertThat(total(chosen.get(), "weight")).isLessThanOrEqualTo(50);
    }

    @Test
    @DisplayName("minimise: cheapest subset meeting a lower bound")
    void minimiseWithLowerBound() {
        var rows = List.of(item("60", "10"), item("100", "20"), item("120", "30"));
        // weight >= 25, minimise value: only {30} (value 120) qualifies cheaper than any pair (>=160)
        Optional<List<Row>> chosen = OPT.choose(
                ObjectiveSense.MINIMIZE, a("value"), List.of(ge(a("weight"), 25)), rows, "(test group)");
        assertThat(chosen).isPresent();
        assertThat(values(chosen.get())).containsExactly("120");
    }

    @Test
    @DisplayName("cardinality limit via SUM(1) <= k")
    void cardinalityLimit() {
        var rows = List.of(item("60", "10"), item("100", "20"), item("120", "30"));
        // at most one item, maximise value -> the 120 item
        Optional<List<Row>> chosen = OPT.choose(
                ObjectiveSense.MAXIMIZE, a("value"), List.of(le(n("1"), 1)), rows, "(test group)");
        assertThat(chosen).isPresent();
        assertThat(values(chosen.get())).containsExactly("120");
    }

    @Test
    @DisplayName("an equality constraint pins the chosen count (SUM(1) = 2)")
    void equalityConstraint() {
        var rows = List.of(item("60", "10"), item("100", "20"), item("120", "30"));
        // exactly two items, maximise value -> the two highest (100 and 120)
        Optional<List<Row>> chosen = OPT.choose(ObjectiveSense.MAXIMIZE, a("value"),
                List.of(constraint(n("1"), ComparisonOperator.EQUAL, 2)), rows, "(test group)");
        assertThat(chosen).isPresent();
        assertThat(values(chosen.get())).containsExactly("100", "120");
    }

    @Test
    @DisplayName("multiple constraints are all enforced")
    void multipleConstraints() {
        var rows = List.of(item("60", "10"), item("100", "20"), item("120", "30"));
        // weight <= 50 AND at most one item -> the single highest-value item (120)
        Optional<List<Row>> chosen = OPT.choose(ObjectiveSense.MAXIMIZE, a("value"),
                List.of(le(a("weight"), 50), le(n("1"), 1)), rows, "(test group)");
        assertThat(chosen).isPresent();
        assertThat(values(chosen.get())).containsExactly("120");
    }

    @Test
    @DisplayName("an infeasible group returns empty (caller skips it)")
    void infeasibleGroup() {
        var rows = List.of(item("60", "10"), item("100", "20"));
        // total weight is 30, but the constraint demands >= 1000 — impossible
        Optional<List<Row>> chosen = OPT.choose(
                ObjectiveSense.MAXIMIZE, a("value"), List.of(ge(a("weight"), 1000)), rows, "(test group)");
        assertThat(chosen).isEmpty();
    }

    @Test
    @DisplayName("a feasible group may still choose nothing")
    void feasibleButEmptyChoice() {
        var rows = List.of(item("60", "10"), item("100", "20"));
        // minimise positive value with only an upper bound -> choose nothing
        Optional<List<Row>> chosen = OPT.choose(
                ObjectiveSense.MINIMIZE, a("value"), List.of(le(a("weight"), 100)), rows, "(test group)");
        assertThat(chosen).isPresent();
        assertThat(chosen.get()).isEmpty();
    }

    @Test
    @DisplayName("a row with a NULL coefficient is excluded from candidacy")
    void nullCoefficientExcluded() {
        var rows = List.of(item("60", "10"), item(null, "20"), item("120", "30"));
        // the NULL-value row can never be chosen; the rest fit under cap 100
        Optional<List<Row>> chosen = OPT.choose(
                ObjectiveSense.MAXIMIZE, a("value"), List.of(le(a("weight"), 100)), rows, "(test group)");
        assertThat(chosen).isPresent();
        assertThat(values(chosen.get())).containsExactly("120", "60");
    }

    @Test
    @DisplayName("a row with a NULL constraint coefficient is excluded from candidacy")
    void nullConstraintCoefficientExcluded() {
        var rows = List.of(item("60", "10"), item("100", null), item("120", "30"));
        // the row with a NULL weight cannot be weighed, so it is dropped
        Optional<List<Row>> chosen = OPT.choose(
                ObjectiveSense.MAXIMIZE, a("value"), List.of(le(a("weight"), 100)), rows, "(test group)");
        assertThat(chosen).isPresent();
        assertThat(values(chosen.get())).containsExactly("120", "60");
    }

    @Test
    @DisplayName("an unsupported constraint operator throws")
    void unsupportedConstraintOperator() {
        var rows = List.of(item("60", "10"));
        var bad = constraint(a("weight"), ComparisonOperator.LESS, 50);
        assertThatThrownBy(() -> OPT.choose(
                ObjectiveSense.MAXIMIZE, a("value"), List.of(bad), rows, "(test group)"))
                .isInstanceOf(EvaluationException.class)
                .hasMessageContaining("unsupported constraint operator");
    }

    @Test
    @DisplayName("an empty group is trivially feasible with no rows chosen")
    void emptyGroup() {
        Optional<List<Row>> chosen = OPT.choose(
                ObjectiveSense.MAXIMIZE, a("value"), List.of(le(a("weight"), 50)), List.of(), "(test group)");
        assertThat(chosen).isPresent();
        assertThat(chosen.get()).isEmpty();
    }

    @Test
    @DisplayName("a non-numeric coefficient throws")
    void nonNumericCoefficient() {
        Schema s = schema(col("value", ScalarType.NUMBER), col("label", ScalarType.ANY));
        Row r = row(s, num("10"), str("hi"));
        assertThatThrownBy(() -> OPT.choose(ObjectiveSense.MAXIMIZE, a("label"),
                List.of(le(a("value"), 5)), List.of(r), "(test group)"))
                .isInstanceOf(EvaluationException.class)
                .hasMessageContaining("must be NUMBER");
    }

    } // end MipMode

    @Nested
    @DisplayName("LP mode (allocate — continuous per-row variable)")
    class LpMode {

        @Test
        @DisplayName("sum-to-1 portfolio: weights are non-negative and sum to 1")
        void portfolioWeightsSumToOne() {
            var rows = List.of(asset("0.1", "0.05"), asset("0.2", "0.08"), asset("0.15", "0.03"));
            // maximise ret, SUM(weight) = 1, each weight in [0, 1]
            Optional<List<AllocationRow>> result = OPT.allocate(
                    ObjectiveSense.MAXIMIZE, a("ret"), List.of(eq(n("1"), 1.0)),
                    0.0, 1.0, rows, "(test group)");
            assertThat(result).isPresent();
            List<AllocationRow> allocs = result.get();
            assertThat(allocs).hasSize(3);
            // all allocations in [0, 1]
            allocs.forEach(ar -> assertThat(ar.allocation()).isBetween(0.0, 1.0));
            // allocations sum to 1 (within floating-point tolerance)
            double total = allocs.stream().mapToDouble(AllocationRow::allocation).sum();
            assertThat(total).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        }

        @Test
        @DisplayName("maximise objective: the highest-return asset gets weight 1 under sum-to-1 constraint")
        void maximiseAllocatesAllToHighestReturn() {
            // three assets, sum-to-1 constraint — LP concentrates weight on the best one
            var rows = List.of(asset("0.1", "0.05"), asset("0.3", "0.08"), asset("0.2", "0.03"));
            Optional<List<AllocationRow>> result = OPT.allocate(
                    ObjectiveSense.MAXIMIZE, a("ret"), List.of(eq(n("1"), 1.0)),
                    0.0, 1.0, rows, "(test group)");
            assertThat(result).isPresent();
            // asset with ret=0.3 should receive weight 1.0; others near 0
            List<AllocationRow> sorted = result.get().stream()
                    .sorted((a, b) -> Double.compare(b.allocation(), a.allocation()))
                    .toList();
            assertThat(sorted.get(0).row()).hasValue("ret", "0.3");
            assertThat(sorted.get(0).allocation()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        }

        @Test
        @DisplayName("minimise mode: minimum-risk allocation satisfying a return floor")
        void minimiseRisk() {
            var rows = List.of(asset("0.1", "0.05"), asset("0.2", "0.12"), asset("0.15", "0.03"));
            // minimise risk, SUM(weight) = 1, SUM(ret * weight) >= 0.1
            Optional<List<AllocationRow>> result = OPT.allocate(
                    ObjectiveSense.MINIMIZE, a("risk"),
                    List.of(eq(n("1"), 1.0), ge(a("ret"), 0.1)),
                    0.0, 1.0, rows, "(test group)");
            assertThat(result).isPresent();
            assertThat(result.get()).hasSize(3);
        }

        @Test
        @DisplayName("all rows are returned including those with zero allocation")
        void allRowsReturnedEvenZeroAllocation() {
            // cap = 1.0, only one row can be fully allocated under SUM(1) <= 1
            var rows = List.of(asset("0.1", "0.05"), asset("0.3", "0.08"), asset("0.2", "0.03"));
            Optional<List<AllocationRow>> result = OPT.allocate(
                    ObjectiveSense.MAXIMIZE, a("ret"), List.of(le(n("1"), 1.0)),
                    0.0, 1.0, rows, "(test group)");
            assertThat(result).isPresent();
            // all 3 rows returned (some may have allocation 0)
            assertThat(result.get()).hasSize(3);
        }

        @Test
        @DisplayName("NULL-coefficient rows are excluded from the model and get zero allocation")
        void nullCoefficientRowsGetZeroAllocation() {
            var rows = List.of(asset("0.1", "0.05"), asset(null, "0.08"), asset("0.2", "0.03"));
            Optional<List<AllocationRow>> result = OPT.allocate(
                    ObjectiveSense.MAXIMIZE, a("ret"), List.of(eq(n("1"), 1.0)),
                    0.0, 1.0, rows, "(test group)");
            assertThat(result).isPresent();
            assertThat(result.get()).hasSize(3);
            // the NULL-ret row (index 1 in original) should have zero allocation
            AllocationRow nullRow = result.get().stream()
                    .filter(ar -> ar.row().get("ret").isNull())
                    .findFirst().orElseThrow();
            assertThat(nullRow.allocation()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("an infeasible LP group returns empty")
        void infeasibleReturnsEmpty() {
            var rows = List.of(asset("0.1", "0.05"), asset("0.2", "0.08"));
            // impossible: each asset in [0,1] but sum must equal 100
            Optional<List<AllocationRow>> result = OPT.allocate(
                    ObjectiveSense.MAXIMIZE, a("ret"), List.of(eq(n("1"), 100.0)),
                    0.0, 1.0, rows, "(test group)");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("empty group returns a present empty list (trivially feasible)")
        void emptyGroupReturnsEmptyList() {
            Optional<List<AllocationRow>> result = OPT.allocate(
                    ObjectiveSense.MAXIMIZE, a("ret"), List.of(eq(n("1"), 1.0)),
                    0.0, 1.0, List.of(), "(test group)");
            assertThat(result).isPresent();
            assertThat(result.get()).isEmpty();
        }

        @Test
        @DisplayName("allocation bounds [lo, hi] are respected")
        void allocationRespectsBounds() {
            var rows = List.of(asset("0.1", "0.05"), asset("0.2", "0.08"), asset("0.3", "0.03"));
            // each asset in [0.1, 0.5], sum = 1
            Optional<List<AllocationRow>> result = OPT.allocate(
                    ObjectiveSense.MAXIMIZE, a("ret"), List.of(eq(n("1"), 1.0)),
                    0.1, 0.5, rows, "(test group)");
            assertThat(result).isPresent();
            result.get().forEach(ar ->
                    assertThat(ar.allocation()).isBetween(0.1 - 1e-9, 0.5 + 1e-9));
        }
    } // end LpMode

    @Nested
    @DisplayName("SetCover — exact-minimum MIP set-cover")
    class SetCover {

        /** Helper: row with a single string "id" column. */
        private Row id(String value) {
            Schema s = schema(col("id", ScalarType.ANY));
            return row(s, str(value));
        }

        @Test
        @DisplayName("empty candidates returns present empty list")
        void emptyCandidatesReturnsEmpty() {
            Optional<List<Row>> result = OPT.setcover(List.of(), List.of(List.of(0)));
            assertThat(result).isPresent();
            assertThat(result.get()).isEmpty();
        }

        @Test
        @DisplayName("empty coveringSets returns present empty list")
        void emptyCoveringSetsReturnsEmpty() {
            var candidates = List.of(id("A"), id("B"));
            Optional<List<Row>> result = OPT.setcover(candidates, List.of());
            assertThat(result).isPresent();
            assertThat(result.get()).isEmpty();
        }

        @Test
        @DisplayName("single candidate covers a single demanded tuple")
        void singleCandidateSingleTuple() {
            var candidates = List.of(id("A"));
            // demanded tuple 0 is covered only by candidate 0
            Optional<List<Row>> result = OPT.setcover(candidates, List.of(List.of(0)));
            assertThat(result).isPresent();
            assertThat(result.get()).hasSize(1);
        }

        @Test
        @DisplayName("selects the minimum-cardinality cover (one row covers two tuples)")
        void minimumCardinalitySelection() {
            // Two candidates: A covers tuple 0, B covers tuples 0 and 1.
            // Optimal: choose only B (one row suffices).
            var a = id("A");
            var b = id("B");
            var candidates = List.of(a, b);
            var coveringSets = List.of(
                    List.of(0, 1), // tuple 0 covered by A(0) and B(1)
                    List.of(1)     // tuple 1 covered only by B(1)
            );
            Optional<List<Row>> result = OPT.setcover(candidates, coveringSets);
            assertThat(result).isPresent();
            assertThat(result.get()).containsExactly(b);
        }

        @Test
        @DisplayName("infeasible when a demanded tuple has no covering candidate")
        void infeasibleWhenNoCovering() {
            var candidates = List.of(id("A"));
            // tuple 0 is covered by candidate 0, but tuple 1 has no covering candidate
            List<List<Integer>> coveringSets = List.of(List.of(0), List.of());
            Optional<List<Row>> result = OPT.setcover(candidates, coveringSets);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("all candidates chosen when each covers a unique demanded tuple")
        void allCandidatesChosenWhenNoneRedundant() {
            var a = id("A");
            var b = id("B");
            var c = id("C");
            var candidates = List.of(a, b, c);
            // Each demanded tuple is covered only by one candidate
            var coveringSets = List.of(List.of(0), List.of(1), List.of(2));
            Optional<List<Row>> result = OPT.setcover(candidates, coveringSets);
            assertThat(result).isPresent();
            assertThat(result.get()).containsExactlyInAnyOrder(a, b, c);
        }

        @Test
        @DisplayName("prefers two rows over three when two suffice")
        void prefersTwoOverThree() {
            var a = id("A");
            var b = id("B");
            var c = id("C");
            var candidates = List.of(a, b, c);
            // Tuples 0,1 covered by A; tuples 2,3 covered by B; C redundant (covers tuple 0 only)
            var coveringSets = List.of(
                    List.of(0, 2), // tuple 0: covered by A(0) and C(2)
                    List.of(0),    // tuple 1: covered only by A(0)
                    List.of(1),    // tuple 2: covered only by B(1)
                    List.of(1)     // tuple 3: covered only by B(1)
            );
            Optional<List<Row>> result = OPT.setcover(candidates, coveringSets);
            assertThat(result).isPresent();
            // Optimal is {A, B} — 2 rows; C is redundant
            assertThat(result.get()).containsExactlyInAnyOrder(a, b);
        }
    } // end SetCover

    /**
     * What the engine does with each answer a solver can give — pinned against
     * stub solvers, since a real one cannot be asked to fail on demand.
     */
    @Nested
    @DisplayName("reading the solver's answer")
    class SolverAnswers {

        /** A solver that always returns the given answer, whatever it is asked. */
        private SubsetOptimizer optimizerReturning(SolverResult answer) {
            return new SubsetOptimizer(new OperandEvaluator(), new MathProgrammingSolver() {
                @Override public String name() { return "stub"; }
                @Override public SolverResult solve(LinearProgram program) { return answer; }
            });
        }

        private final List<Row> rows = List.of(item("60", "10"), item("100", "20"));
        private final List<OptimizeConstraint> cap = List.of(le(a("weight"), 50));

        @Test
        @DisplayName("a proved infeasibility is an answer: the group is skipped, not an error")
        void infeasibleGroupIsSkipped() {
            var optimizer = optimizerReturning(SolverResult.infeasible());
            assertThat(optimizer.choose(ObjectiveSense.MAXIMIZE, a("value"), cap, rows, "(all rows)"))
                    .isEmpty();
        }

        @Test
        @DisplayName("an undecided search is an error naming the operator, group, solver and reason")
        void undecidedSearchIsAnError() {
            var optimizer = optimizerReturning(SolverResult.undetermined("FAILED"));
            assertThatThrownBy(() -> optimizer.choose(
                    ObjectiveSense.MAXIMIZE, a("value"), cap, rows, "(desk=FX)"))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("OPTIMIZE")
                    .hasMessageContaining("(desk=FX)")
                    .hasMessageContaining("stub")
                    .hasMessageContaining("FAILED")
                    .hasMessageContaining("without proving the program infeasible");
        }

        @Test
        @DisplayName("ALLOCATE reports its own name when the search does not decide")
        void undecidedAllocationIsAnError() {
            var optimizer = optimizerReturning(SolverResult.undetermined("UNBOUNDED"));
            assertThatThrownBy(() -> optimizer.allocate(
                    ObjectiveSense.MAXIMIZE, a("ret"), List.of(le(a("risk"), 1)), 0.0, 1.0,
                    List.of(asset("5", "1")), "(all rows)"))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("OPTIMIZE ALLOCATE")
                    .hasMessageContaining("UNBOUNDED");
        }

        @Test
        @DisplayName("COVER EXACT reports the covering model rather than a group")
        void undecidedCoverIsAnError() {
            var optimizer = optimizerReturning(SolverResult.undetermined("INVALID"));
            assertThatThrownBy(() -> optimizer.setcover(
                    List.of(item("1", "1"), item("2", "2")), List.of(List.of(0), List.of(1))))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("COVER EXACT")
                    .hasMessageContaining("the covering model")
                    .hasMessageContaining("INVALID");
        }

        @Test
        @DisplayName("an uncoverable tuple is still infeasible — it is proved without a solver")
        void uncoverableTupleStaysInfeasible() {
            var optimizer = optimizerReturning(SolverResult.undetermined("never asked"));
            assertThat(optimizer.setcover(List.of(item("1", "1")), List.of(List.of())))
                    .isEmpty();
        }
    }
}

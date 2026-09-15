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
package com.darkcollective.relix.embed.equivalence;

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.processor.DataSourceConnector;
import com.darkcollective.relix.processor.ExecutionContext;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.exec.PhysicalExecutor;
import com.darkcollective.relix.processor.exec.RelNodeExecutor;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.semantic.SchemaInference;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.rel;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The same query, run by a different join algorithm, must return the same rows.</b>
 *
 * <p>The planner picks HASH, MERGE or NESTED_LOOP from the cost model, and that choice is
 * asserted at length — {@code PlannerTest} is some 2,800 lines about which one is chosen.
 * Nothing tied the choice to the answer. The two suites that execute joins,
 * {@code MergeJoinExecutionTest} and {@code JoinOperatorsTest}, were written independently
 * and each carries its own expected rows, so an algorithm that disagreed with its
 * neighbours would show up as two hand-written expectations that happen to differ — which
 * is not something review catches.
 *
 * <p>Here one plan is executed, then rewritten by {@link PlanVariation} and executed
 * again, and the rows are compared. Nothing is written down to go stale: the expectation
 * is the engine's own other answer.
 *
 * <h2>Rows are compared as a multiset, and positionally</h2>
 *
 * A join algorithm is free to emit rows in any order — that is the point of choosing one —
 * so the sequence is not asserted. The cells are compared in <em>column order</em>, which
 * makes this sensitive to a variant that permutes the output heading; that is the shape
 * the removed {@code JOIN-003} rule had, and it returned wrong rows for a year.
 */
@DisplayName("Plan variation — the join algorithm does not change the answer")
final class PlanVariationEquivalenceTest {

    private static final RelNodeExecutor PLANNER = new RelNodeExecutor();
    private static final PhysicalExecutor EXECUTOR = new PhysicalExecutor();

    /**
     * Two relations sharing {@code id} and {@code cat}, with the overlaps a join needs to
     * do more than pass rows through: a key present on both sides more than once, so a
     * join multiplies rather than matches one-to-one; a key on one side only, so an outer
     * join has a row to pad and an anti-join one to keep; and a NULL key, which every
     * algorithm has to drop from its matching and none may drop from an outer result.
     */
    private static final String FIXTURES = """
            Left := [
            | id | cat | qty |
            |----|-----|-----|
            | 1  | a   | 10  |
            | 2  | a   | 20  |
            | 2  | b   | 25  |
            | 3  | b   | 30  |
            | 4  | c   | 40  |
            |    | a   | 50  |
            ];
            Right := [
            | id | cat | score |
            |----|-----|-------|
            | 1  | a   | 7     |
            | 2  | a   | 2     |
            | 2  | a   | 9     |
            | 5  | d   | 4     |
            |    | b   | 1     |
            ];
            """;

    // =========================================================================

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget named -> rel(named.name());
            case ExpressionQueryTarget e -> e.expression();
        };
    }

    /** The rows of {@code plan}, each row as its cells in column order. */
    private static List<List<String>> rowsOf(PhysicalNode plan, ExecutionContext ctx) {
        try (Stream<Row> stream = EXECUTOR.execute(plan, ctx)) {
            List<List<String>> rows = new ArrayList<>();
            stream.forEach(row -> {
                List<String> cells = new ArrayList<>(row.width());
                for (int i = 0; i < row.width(); i++) {
                    cells.add(row.get(i).asDisplayString());
                }
                rows.add(cells);
            });
            return List.copyOf(rows);
        }
    }

    private static Map<List<String>, Integer> frequencies(List<List<String>> rows) {
        Map<List<String>, Integer> counts = new HashMap<>();
        rows.forEach(row -> counts.merge(row, 1, Integer::sum));
        return counts;
    }

    /** A planned query, with the context it must be executed in. */
    private record Planned(PhysicalNode plan, ExecutionContext ctx, String script) {}

    private static Planned plan(String script) {
        SemanticModel m = model(FIXTURES + script);
        RelNode tree = queryNode(m.rootQueries().getFirst());
        SchemaAnnotations merged =
                SchemaInference.annotate(m.symbolTable(), tree, m.nodeSchemas(), m.functions());
        DataSourceConnector connector = ExecutionContext.inlineOnly(m).connector();
        ExecutionContext ctx = ExecutionContext.of(m, merged, connector);
        return new Planned(PLANNER.plan(tree, ctx), ctx, script);
    }

    /**
     * Runs the plan as the planner built it, then once per rewrite, and asserts every run
     * agrees. Each caller states its own precondition first — that there is a join to
     * re-run, or a spool to remove — because a variant applied to a plan it cannot change
     * compares that plan with itself and passes for no reason.
     */
    private static void assertEveryVariantAgrees(Planned planned) {
        Map<List<String>, Integer> asPlanned = frequencies(rowsOf(planned.plan(), planned.ctx()));

        PlanVariation.variants().forEach((label, rewrite) ->
                assertThat(frequencies(rowsOf(rewrite.apply(planned.plan()), planned.ctx())))
                        .as("%s disagrees with the plan as chosen, for: %s",
                                label, planned.script())
                        .isEqualTo(asPlanned));
    }

    /** As above, for a query whose point is its join. */
    private static List<PhysicalNode.JoinAlgorithm> assertAgreesUnderEveryAlgorithm(String script) {
        Planned planned = plan(script);
        List<PhysicalNode.JoinAlgorithm> chosen = PlanVariation.joinAlgorithms(planned.plan());
        assertThat(chosen)
                .as("the query must actually contain a join, or this proves nothing: %s", script)
                .isNotEmpty();
        assertEveryVariantAgrees(planned);
        return chosen;
    }

    // =========================================================================

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '~', textBlock = """
            natural join        ~ query { Left ⋈ Right };
            theta join, equi    ~ query { Left ⨝ Left.id = Right.id Right };
            left outer          ~ query { Left ⟕ Left.id = Right.id Right };
            right outer         ~ query { Left ⟖ Left.id = Right.id Right };
            full outer          ~ query { Left ⟗ Left.id = Right.id Right };
            semi join           ~ query { Left ⋉ Left.id = Right.id Right };
            anti join           ~ query { Left ▷ Left.id = Right.id Right };
            two keys            ~ query { Left ⨝ Left.id = Right.id ∧ Left.cat = Right.cat Right };
            a filter above      ~ query { σ qty > 15 (Left ⋈ Right) };
            an aggregate above  ~ query { γ cat, COUNT(*) → n (Left ⋈ Right) };
            a sort above        ~ query { τ qty ASC (Left ⟕ Left.id = Right.id Right) };
            joined twice        ~ query { (Left ⋈ Right) ⋈ Left };
            """)
    void everyAlgorithmAgrees(String label, String script) {
        assertAgreesUnderEveryAlgorithm(script);
    }

    /**
     * The MERGE arm, reached the only way it legally can be: the planner chooses it when
     * both inputs already deliver the join's order, and the forced variants are then
     * compared against <em>it</em>. Forcing MERGE in the other direction would skip the
     * sorts the planner inserts while building and compare against a plan it would never
     * have emitted — see {@link PlanVariation}.
     */
    @Test
    @DisplayName("a merge join agrees with the algorithms it was chosen over")
    void aMergeJoinAgreesWithTheRest() {
        List<PhysicalNode.JoinAlgorithm> chosen = assertAgreesUnderEveryAlgorithm(
                "Ordered := { τ id ASC (Left) };\n"
              + "Sorted := { τ id ASC (Right) };\n"
              + "query { Ordered ⨝ Ordered.id = Sorted.id Sorted };\n");

        assertThat(chosen)
                .as("if the planner stops choosing MERGE here, this case has quietly "
                    + "become a second test of HASH and the arm is uncovered again")
                .contains(PhysicalNode.JoinAlgorithm.MERGE);
    }

    /**
     * The sharing axis, which needs a query that shares something.
     *
     * <p>Reading one <em>inline</em> relation twice is not enough: the planner declines to
     * spool rows already in memory, since a second read of them repeats no work. What it
     * shares is a sub-expression that costs something to produce, so the query reads the
     * same selection in two places.
     *
     * <p>Removing the spool is always legal — the planner only shares a sub-plan it has
     * proved reproducible, which is the same proof that lets a reader re-run it — so the
     * unshared plan is an independent answer to the same question.
     */
    @Test
    @DisplayName("a shared sub-plan agrees with the same query not sharing it")
    void sharingDoesNotChangeTheAnswer() {
        Planned planned = plan("query { (σ qty > 15 (Left)) ⊎ (σ qty > 15 (Left)) };\n");

        assertThat(PlanVariation.spoolCount(planned.plan()))
                .as("if the planner stops sharing here, the no-sharing variant is comparing "
                    + "the plan with itself and this axis is uncovered")
                .isPositive();
        assertThat(PlanVariation.spoolCount(PlanVariation.withoutSpools(planned.plan())))
                .as("the rewrite must actually remove them")
                .isZero();

        assertEveryVariantAgrees(planned);
    }

    @Test
    @DisplayName("forcing actually changes the plan, or every case above is vacuous")
    void forcingIsNotANoOp() {
        SemanticModel m = model(FIXTURES + "query { Left ⨝ Left.id = Right.id Right };\n");
        RelNode tree = queryNode(m.rootQueries().getFirst());
        SchemaAnnotations merged =
                SchemaInference.annotate(m.symbolTable(), tree, m.nodeSchemas(), m.functions());
        ExecutionContext ctx = ExecutionContext.of(
                m, merged, ExecutionContext.inlineOnly(m).connector());
        PhysicalNode planned = PLANNER.plan(tree, ctx);

        assertThat(PlanVariation.joinAlgorithms(
                PlanVariation.forcing(planned, PlanVariation.Forced.NESTED_LOOP)))
                .containsOnly(PhysicalNode.JoinAlgorithm.NESTED_LOOP);
        assertThat(PlanVariation.joinAlgorithms(
                PlanVariation.forcing(planned, PlanVariation.Forced.HASH)))
                .containsOnly(PhysicalNode.JoinAlgorithm.HASH);
    }
}

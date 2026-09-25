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
package com.darkcollective.relix.plan.internal;

import com.darkcollective.relix.plan.internal.PhysicalPlanPrinter;
import com.darkcollective.relix.plan.internal.Planner;
import com.darkcollective.relix.plan.internal.PushdownFunctions;
import com.darkcollective.relix.plan.internal.SqlExpressions;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.semantic.internal.IrReport;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A comparison operator must read the same wherever it is shown to a human.
 *
 * <p>{@code :tree} (the {@link IrReport}) and {@code :explain} (the
 * {@link PhysicalPlanPrinter}) once carried separate operator→symbol tables that
 * had drifted, so one solver constraint printed as {@code SUM(weight)≤100} in the
 * IR report and {@code SUM(weight) <= 100} in the physical plan. Both now go
 * through {@link ComparisonOperator#symbol()}; this test is the guard against
 * them drifting apart again.
 *
 * <p>This lives in {@code relix-plan} because it is the only module that can see
 * both renderers.
 */
@DisplayName("operator rendering parity — :tree and :explain agree")
final class OperatorSymbolParityTest {

    /**
     * An OPTIMIZE constraint is the construct both views spell out — {@code explain}
     * prints a σ as a bare {@code Select} without its predicate, so the solver
     * constraint is where the two renderers actually met (and disagreed).
     */
    private static final String SCRIPT = """
            Items := [| id | region | value | weight |
                       | 1  | north  | 10    | 5      |
                       | 2  | south  | 20    | 8      |];
            query { OPTIMIZE MAXIMIZE SUM(value)
                    SUBJECT TO SUM(weight) <= 100 PER region (Items) };
            """;

    private static String explainOf(SemanticModel m) {
        var target = (ExpressionQueryTarget) m.rootQueries().getFirst().target();
        return PhysicalPlanPrinter.explain(
                new Planner(m.symbolTable(), m.nodeSchemas()).plan(target.expression()));
    }

    @Test
    @DisplayName("≤ renders identically in the IR report and the physical plan")
    void lessEqualRendersIdentically() {
        SemanticModel m = model(SCRIPT);

        String ir      = IrReport.generate(m);
        String explain = explainOf(m);

        assertThat(ir).contains("≤");
        assertThat(explain).contains("≤");

        // The ASCII spelling belongs to the SQL wire format, never to a rendered
        // view — its presence here would mean a renderer has its own table again.
        assertThat(ir).doesNotContain("<=");
        assertThat(explain).doesNotContain("<=");
    }

    @Test
    @DisplayName("a solver constraint reads identically in both views")
    void constraintTextIsIdentical() {
        SemanticModel m = model(SCRIPT);

        // The exact shared spelling — the sub-expression both views print. It used
        // to differ in spacing ("SUM(weight) ≤ 100"), separator, and key style
        // (" per region"), because each printer rendered it by hand.
        String shared = "s.t. SUM(weight)≤100 [region]";
        assertThat(IrReport.generate(m)).contains(shared);
        assertThat(explainOf(m)).contains(shared);
    }

    @Test
    @DisplayName("every operator's display symbol is the documented Unicode form")
    void symbolsAreCanonical() {
        assertThat(ComparisonOperator.EQUAL.symbol()).isEqualTo("=");
        assertThat(ComparisonOperator.NOT_EQUAL.symbol()).isEqualTo("≠");
        assertThat(ComparisonOperator.LESS.symbol()).isEqualTo("<");
        assertThat(ComparisonOperator.LESS_EQUAL.symbol()).isEqualTo("≤");
        assertThat(ComparisonOperator.GREATER.symbol()).isEqualTo(">");
        assertThat(ComparisonOperator.GREATER_EQUAL.symbol()).isEqualTo("≥");
    }

    @Test
    @DisplayName("the SQL wire format deliberately keeps ASCII, independent of display")
    void sqlKeepsAsciiSpelling() {
        // Guards the other direction: unifying the display symbol must not leak a
        // Unicode glyph into generated SQL, which no backend would parse.
        var rendered = SqlExpressions.predicate(
                cmp(attr("weight"),
                        ComparisonOperator.LESS_EQUAL, num("15")),
                SqlExpressions.ColumnRenderer.STRIP_QUALIFIER,
                PushdownFunctions.of(FunctionCatalog.empty()));

        assertThat(rendered).hasValue("(weight <= 15)");
        assertThat(rendered.orElseThrow()).doesNotContain("≤");
    }
}

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
package com.darkcollective.relix.plan;

import com.darkcollective.relix.plan.internal.PhysicalPlanPrinter;
import com.darkcollective.relix.plan.internal.Planner;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.cost.BoundednessSource;
import com.darkcollective.relix.events.QueryEventListener;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.plan.PlanAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Tests that the planner inlines table-valued function calls by substituting the
 * call arguments into the function body — so no {@code RelationFunctionCall}
 * survives into the physical plan.
 */
@DisplayName("Planner — table-valued function inlining")
final class RelationFunctionPlanTest {

    private static final String ORDERS =
            "Orders := [| order_id | customer_id | amount |\n" +
            "           | 1        | 2           | 100    |];\n";

    private static PhysicalNode planFirstQuery(String src) {
        SemanticModel model = model(src);
        RelNode logical = ((ExpressionQueryTarget) model.rootQueries().get(0).target()).expression();
        return new Planner(model.symbolTable(), model.nodeSchemas()).plan(logical);
    }

    @Test
    @DisplayName("a call inlines to its (substituted) body — a Select over the base relation")
    void inlinesToBody() {
        PhysicalNode plan = planFirstQuery(
                "def ordersFor(cid: NUMBER): RELATION := { σ customer_id = cid (Orders) };\n" + ORDERS
                + "query { ordersFor(2) };");
        // The body is σ customer_id = 2 (Orders): a Select over a Scan, with no
        // RelationFunctionCall surviving into the physical plan.
        PhysicalNode.Select select = assertThat(plan).asNode(PhysicalNode.Select.class);
        assertThat(select.input()).isNode(PhysicalNode.Scan.class);
        assertThat(PhysicalPlanPrinter.explain(plan)).doesNotContain("ordersFor");
    }

    @Test
    @DisplayName("a no-argument call inlines to its body")
    void inlinesZeroArgCall() {
        PhysicalNode plan = planFirstQuery(
                "def allOrders(): RELATION := { Orders };\n" + ORDERS
                + "query { allOrders() };");
        assertThat(plan).isNode(PhysicalNode.Scan.class);
    }

    @Test
    @DisplayName("the analysis catalogue types a function call inside an inlined body")
    void inlinedBodyTypesItsFunctionCalls() {
        // The substituted body is a fresh tree, so its schemas are re-inferred at plan
        // time. A conditional's result follows its branches, and only the catalogue
        // knows that rule — a planner given none types the column dynamically.
        String src = "def flagged(min: NUMBER): RELATION := "
                + "{ π order_id, IIf(amount > min, amount, 0) → v (Orders) };\n" + ORDERS
                + "query { flagged(50) };";
        SemanticModel model = model(src);
        RelNode logical = ((ExpressionQueryTarget) model.rootQueries().get(0).target()).expression();

        PhysicalNode typed = new Planner(model.symbolTable(), model.nodeSchemas(), Map.of(),
                Map.of(), Map.of(), QueryEventListener.NONE, BoundednessSource.ALL_BOUNDED,
                model.functions()).plan(logical);
        PhysicalNode untyped = new Planner(model.symbolTable(), model.nodeSchemas()).plan(logical);

        assertThat(typed.schema().column("v").orElseThrow().type())
                .isEqualTo(ScalarType.NUMBER);
        assertThat(untyped.schema().column("v").orElseThrow().type())
                .isEqualTo(ScalarType.ANY);
    }

    @Test
    @DisplayName("a recursive table-valued function is rejected at plan time")
    void recursiveFunctionRejected() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> planFirstQuery(
                        "def loop(): RELATION := { loop() };\n"
                        + "query { loop() };"))
                .withMessageContaining("Recursive");
    }
}

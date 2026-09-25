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

import com.darkcollective.relix.plan.internal.PhysicalPlanJson;
import com.darkcollective.relix.plan.internal.PhysicalPlanPrinter;
import com.darkcollective.relix.plan.internal.Planner;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.Ordering;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.plan.PlanAssertions.assertThat;

/** Tests for WHY (ω) physical planning and rendering (ADR-0018, issue #320). */
@DisplayName("WHY — physical plan, printer, JSON")
final class WhyPlanTest {

    private static final Schema PROV_SCHEMA = new Schema(List.of(
            new ColumnDefinition("id", ScalarType.NUMBER),
            new ColumnDefinition("provenance", ScalarType.ANY)));

    // ── direct PhysicalNode construction (printer / JSON) ────────────────────

    @Test
    @DisplayName("the printer renders a Why node as a leaf labelled 'Why'")
    void printerLabel() {
        var why = new PhysicalNode.Why(PROV_SCHEMA, rel("Orders"));
        assertThat(PhysicalPlanPrinter.explain(why)).isEqualTo("Why\n");
    }

    @Test
    @DisplayName("JSON carries op 'Why', the appended provenance column, and no physical children")
    void jsonShape() {
        var why = new PhysicalNode.Why(PROV_SCHEMA, rel("Orders"));
        String json = PhysicalPlanJson.toJson(why);
        assertThat(json).contains("\"op\":\"Why\"");
        assertThat(json).contains("\"name\":\"provenance\"");
        assertThat(json).contains("\"children\":[]");
    }

    @Test
    @DisplayName("a Why node exposes no physical children — its subtree is the logical input")
    void noPhysicalChildren() {
        RelNode logical = rel("Orders");
        var why = new PhysicalNode.Why(PROV_SCHEMA, logical);
        assertThat(why.children()).isEmpty();
        assertThat(why.logicalInput()).isSameAs(logical);
        assertThat(why.deliveredOrdering()).isEqualTo(Ordering.none());
    }

    // ── end-to-end planning ──────────────────────────────────────────────────

    @Test
    @DisplayName("WHY over an inline source plans to PhysicalNode.Why with the provenance column")
    void plansToWhy() {
        PhysicalNode plan = planFirstQuery("""
                Orders := [| order_id | region |
                           | 1        | west   |];
                query { ω (Orders) };
                """);
        PhysicalNode.Why why = assertThat(plan).asNode(PhysicalNode.Why.class);
        assertThat(why.schema().column("provenance")).isPresent();
        assertThat(why.logicalInput()).isNode(RelationNode.class);
    }

    @Test
    @DisplayName("WHY over a database source is NOT pushed — its input stays logical (lineage preserved)")
    void neverPushed() {
        PhysicalNode plan = planFirstQuery("""
                source Orders from database {
                    url: "jdbc:h2:mem", table: "orders",
                    schema: { order_id: NUMBER, region: STRING } };
                query { ω (σ region = "west" (Orders)) };
                """);
        // The whole expression is a self-terminating Why; nothing inside is a PushedScan.
        PhysicalNode.Why why = assertThat(plan).asNode(PhysicalNode.Why.class);
        assertThat(why.children()).isEmpty();
        // The logical subtree is preserved verbatim (a σ over the relation), not folded
        // into a backend query — so the provenance evaluator can thread it.
        assertThat(why.logicalInput()).isNode(SelectionNode.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static PhysicalNode planFirstQuery(String src) {
        SemanticModel m = model(src);
        RelNode logical = ((ExpressionQueryTarget) m.rootQueries().get(0).target()).expression();
        return new Planner(m.symbolTable(), m.nodeSchemas(), m.statistics(),
                m.sources(), m.connections()).plan(logical);
    }
}

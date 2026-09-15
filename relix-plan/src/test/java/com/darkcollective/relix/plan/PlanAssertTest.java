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

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.events.QueryEventListener;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.darkcollective.relix.plan.PlanAssertions.assertThat;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PlanAssert} is the assert issue #727 was written about, and its deliverable
 * is the failure message, so the negative cases here assert that the message carries
 * the plan — rendered by the same {@link PhysicalPlanPrinter} a user sees under
 * {@code --explain}, so a failure and a bug report describe the plan identically.
 */
@DisplayName("PlanAssert — plan assertions that print the plan")
final class PlanAssertTest {

    private static final String USERS = """
            Users := [| id | name  |
                       | 1  | Alice |];
            """;

    private static PhysicalNode plan(String src) {
        SemanticModel model = model(src);
        RelNode logical = ((ExpressionQueryTarget) model.rootQueries().get(0).target()).expression();
        return new Planner(model.symbolTable(), model.nodeSchemas(), Map.of(), Map.of(), Map.of(),
                QueryEventListener.NONE).plan(logical);
    }

    @Nested
    @DisplayName("shape and navigation")
    class Shape {

        @Test
        @DisplayName("navigates by position rather than by cast — the pattern this replaces")
        void navigates() {
            assertThat(plan(USERS + "query { σ id = 1 (Users) };"))
                    .isNode(PhysicalNode.Select.class)
                    .input().isScanOf("Users");
        }

        @Test
        @DisplayName("a wrong node kind prints the whole plan")
        void wrongKindPrintsThePlan() {
            PhysicalNode p = plan(USERS + "query { π name (Users) };");
            assertThatThrownBy(() -> assertThat(p).isNode(PhysicalNode.Select.class))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("expected a Select but was a Project")
                    // the whole plan, indented, not just the node that mismatched
                    .hasMessageContaining("Project")
                    .hasMessageContaining("└─ Scan Users");
        }

        @Test
        @DisplayName("a wrong scan target names the relation actually read")
        void wrongScanTarget() {
            PhysicalNode p = plan(USERS + "query { Users };");
            assertThatThrownBy(() -> assertThat(p).isScanOf("Orders"))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("expected a Scan of Orders but it reads Users");
        }

        @Test
        @DisplayName("left() and right() walk a binary plan node")
        void binary() {
            PhysicalNode p = plan(USERS
                    + "Others := [| id | name |\n"
                    + "            | 2  | Bob  |];\n"
                    + "query { Users ∪ Others };");
            assertThat(p).isNode(PhysicalNode.SetOp.class).left().isScanOf("Users");
            assertThat(p).right().isScanOf("Others");
        }

        @Test
        @DisplayName("input() on a binary node fails on the arity")
        void inputRejectsBinary() {
            PhysicalNode p = plan(USERS
                    + "Others := [| id | name |\n"
                    + "            | 2  | Bob  |];\n"
                    + "query { Users ∪ Others };");
            assertThatThrownBy(() -> assertThat(p).input())
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("to have 1 child plan(s) but it has 2");
        }

        @Test
        @DisplayName("isNodeSatisfying reaches the record's own accessors, keeping the plan as context")
        void narrows() {
            assertThat(plan(USERS + "query { λ 1 (Users) };"))
                    .isNodeSatisfying(PhysicalNode.Limit.class,
                            l -> org.assertj.core.api.Assertions.assertThat(l.count()).isEqualTo(1L));
        }
    }

    @Nested
    @DisplayName("whole-plan claims")
    class WholePlan {

        @Test
        @DisplayName("schema() asserts the heading the planner baked in")
        void schema() {
            assertThat(plan(USERS + "query { π name (Users) };"))
                    .schema().hasColumnNames("name");
        }

        @Test
        @DisplayName("containsNoNode is a claim about the plan, not its root")
        void containsNoNode() {
            PhysicalNode p = plan(USERS + "query { σ id = 1 (Users) };");
            assertThat(p).containsNoNode(PhysicalNode.Distinct.class)
                    .containsNodes(PhysicalNode.Scan.class, 1);
            assertThatThrownBy(() -> assertThat(p).containsNoNode(PhysicalNode.Scan.class))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("expected no Scan anywhere in the plan, but found 1");
        }

        @Test
        @DisplayName("explainsAs states the whole shape in one assertion")
        void explainsAs() {
            PhysicalNode p = plan(USERS + "query { Users };");
            assertThat(p).explainsAs(PhysicalPlanPrinter.explain(p));
        }
    }
}

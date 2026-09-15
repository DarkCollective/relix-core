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

import com.darkcollective.relix.semantic.SemanticAssertions;

/**
 * The relix assertion entry point at the planning layer — {@link SemanticAssertions}
 * plus {@link PhysicalNode}.
 *
 * <p>A planner test asserts on all four subjects below this point in one method —
 * the analysis that produced the logical tree, the tree, the plan, and the heading
 * the plan bakes in — so they are reachable from a single import:
 *
 * {@snippet lang = "java":
 * import static com.darkcollective.relix.plan.PlanAssertions.assertThat;
 *
 * assertThat(plan).isNode(PhysicalNode.Select.class).input().isScanOf("Users");
 * assertThat(plan).schema().hasColumnNames("id", "name");
 * }
 *
 * @see PlanAssert
 */
public class PlanAssertions extends SemanticAssertions {

    /** Not instantiable directly; extend it, or import its members statically. */
    protected PlanAssertions() {
    }

    /**
     * Begins an assertion on a physical plan.
     *
     * @param actual the plan under test; may be null (the assert reports it)
     * @return the assert
     */
    public static PlanAssert assertThat(PhysicalNode actual) {
        return new PlanAssert(actual);
    }
}

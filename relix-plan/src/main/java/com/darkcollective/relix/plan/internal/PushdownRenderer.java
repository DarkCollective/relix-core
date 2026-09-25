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

import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.function.FunctionContext;
import com.darkcollective.relix.ast.SortSpecification;

import java.util.List;
import java.util.Optional;

/**
 * Capability seam for connectors that can accept a native-query pushdown.
 *
 * <p>An implementation translates a logical sub-tree into a backend-native query
 * (SQL for JDBC, a pipeline document for a document store, …) and returns a
 * {@link PhysicalNode.PushedScan} when the sub-tree is fully expressible in that
 * backend's query language.  The {@link Planner} holds at most one renderer per
 * planning session; future work wires additional renderers as more connector types
 * gain pushdown support (ADR-0010).
 */
interface PushdownRenderer {

    /**
     * Attempts to fold {@code node} into a single native-query scan.
     *
     * @param node the logical sub-tree to push; must not be null
     * @return the pushed scan, or empty when the sub-tree is not fully pushable
     */
    Optional<PhysicalNode.PushedScan> tryPush(RelNode node);

    /**
     * Tells this renderer the run's ambient state, so a call that is constant for the
     * run can be evaluated here and sent as a value.
     *
     * <p>Default no-op, and absent by default in the renderers that do use it: a planner
     * that was not told the clock substitutes nothing and renders exactly what it
     * rendered before. That is the safe direction — substituting an instant the executor
     * will not agree with is the one failure this must not have — and it means only the
     * one path that knows the run's clock has to pass it.
     *
     * @param context the run's function context, holding its pinned clock
     */
    default void useFunctionContext(FunctionContext context) {
        // A renderer with no stable-call substitution has nothing to remember.
    }

    /**
     * Attempts to fold {@code node} into a native-query scan whose output arrives
     * ordered by {@code keys} — satisfying a merge join's required order from the
     * source rather than sorting in the engine (ADR-0009, Phase C4).
     *
     * @param node the logical sub-tree to push; must not be null
     * @param keys the required ordering keys; must not be null or empty
     * @return the ordered pushed scan, or empty when it cannot be pushed with this order
     */
    Optional<PhysicalNode.PushedScan> tryPushOrdered(RelNode node, List<SortSpecification> keys);
}

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
package com.darkcollective.relix.processor.internal;

import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.plan.PlanEstimates;

/**
 * Callback that receives one query's planned {@link PhysicalNode}, without
 * executing it.
 *
 * <p>Used by {@link QueryExecutor#plan} to hand each {@code query} statement's
 * physical plan <em>object</em> to the caller — unlike {@link PlanConsumer},
 * which renders the plan to text. A caller that needs the structured plan (e.g.
 * to serialize it to JSON for the query bundle) uses this; a caller that only
 * wants a human-readable rendering uses {@link PlanConsumer}.
 */
@FunctionalInterface
public interface PhysicalPlanConsumer {

    /**
     * Consumes one query's physical plan and the cardinality estimates that go with
     * it.
     *
     * <p>The estimates arrive separately because they are a side table, not a
     * component of the nodes — see {@link PlanEstimates}.
     *
     * @param label     the query's display label (relation name or {@code "<expression N>"})
     * @param plan      the planned physical tree
     * @param estimates the per-node estimated row counts; never null, possibly empty
     */
    void accept(String label, PhysicalNode plan, PlanEstimates estimates);
}

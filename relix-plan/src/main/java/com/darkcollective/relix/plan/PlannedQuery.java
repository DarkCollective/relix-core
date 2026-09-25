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

import java.util.Objects;

/**
 * A physical plan together with the row estimates the planner computed for it.
 *
 * <p>The estimates are a side table rather than a component of the nodes (see
 * {@link PlanEstimates}), so they travel beside the plan rather than inside it. This
 * record is that pairing.
 *
 * @param plan      the planned physical tree
 * @param estimates the estimated row count of each node
 * @since 1.0
 */
public record PlannedQuery(PhysicalNode plan, PlanEstimates estimates) {

    /** Checks that neither component is null. */
    public PlannedQuery {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(estimates, "estimates");
    }
}

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
/**
 * The physical plan: what the engine will run.
 *
 * <p>{@link com.darkcollective.relix.plan.PhysicalNode} is the plan tree, one record per
 * physical operator. {@link com.darkcollective.relix.plan.PlanEstimates} carries each
 * node's estimated row count beside the tree rather than inside it, and
 * {@link com.darkcollective.relix.plan.PlannedQuery} pairs the two, as
 * {@code Relation.plan()} returns them.
 */
package com.darkcollective.relix.plan;

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

import com.darkcollective.relix.plan.internal.Planner;

/**
 * Physical algorithm choice for a {@link PhysicalNode.Trace} optimal-path operator.
 *
 * <p>The {@link Planner} picks the algorithm as a physical-strategy decision — the
 * same class of choice as join algorithm / build side — and surfaces it as a
 * {@link com.darkcollective.relix.events.QueryEvent.Stage#PLAN} event:
 *
 * <ul>
 *   <li>{@link #RELAXATION} — the default Bellman-Ford–style fixpoint that handles
 *       any weights (including negative edges, no negative cycle) and any
 *       {@code MINIMIZE}/{@code MAXIMIZE} sense.</li>
 *   <li>{@link #DIJKSTRA} — single-pair shortest path with goal-directed early
 *       termination, chosen when <em>both</em> endpoints are bound and the sense is
 *       {@code MINIMIZE}. It is asymptotically cheaper but only sound for
 *       non-negative weights; the executor verifies that precondition at runtime
 *       and falls back to {@link #RELAXATION} if a negative weight is present, so
 *       correctness never depends on the planner's optimism.</li>
 * </ul>
 */
public enum TraceAlgorithm {
    /** Bellman-Ford–style relaxation fixpoint (default; any weights, any sense). */
    RELAXATION,
    /** Single-pair Dijkstra with early exit (bounded source+target, MINIMIZE, non-negative weights). */
    DIJKSTRA
}

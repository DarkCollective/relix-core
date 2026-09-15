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
 * Physical query planning for the relix relational algebra system.
 *
 * <p>This module turns an optimised logical
 * {@link com.darkcollective.relix.ast.RelNode} tree into an executable
 * {@link com.darkcollective.relix.plan.PhysicalNode} plan via the
 * {@link com.darkcollective.relix.plan.Planner}.  Planning fixes the physical
 * strategy — join algorithm (hash vs nested-loop) and build side — using the
 * {@code relix-cost} model, and resolves each node's output schema up front, so
 * the execution engine can run the plan mechanically.
 */
module com.darkcollective.relix.plan {
    requires transitive com.darkcollective.relix.ast;
    requires transitive com.darkcollective.relix.symbol;
    requires transitive com.darkcollective.relix.semantic;
    // transitive: BoundednessSource (relix-cost) appears in the Planner's public ctor.
    requires transitive com.darkcollective.relix.cost;

    // QueryEventListener — in the Planner's public constructor.
    requires transitive com.darkcollective.relix.events;

    // JsonWriter — in PhysicalPlanJson.write(JsonWriter, …); transitive so a
    // bundle assembler can drive one writer across modules.
    requires transitive com.darkcollective.relix.json;

    // FunctionCatalog — in the Planner's public constructor, and the source of
    // every function's backend spelling (ADR-0026 S6). The default library is
    // absent on purpose: a provider is discovered, never required.
    requires transitive com.darkcollective.relix.function;

    // SolverCatalog — in Planner.withSolvers, and what the planner asks before it
    // plans OPTIMIZE or COVER EXACT. The SPI only; a solver provider is discovered,
    // never required (ADR-0026 D8).
    requires transitive com.darkcollective.relix.solver;

    exports com.darkcollective.relix.plan;
}

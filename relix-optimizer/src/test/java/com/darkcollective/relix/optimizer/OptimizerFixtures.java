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
package com.darkcollective.relix.optimizer;

import com.darkcollective.relix.optimizer.internal.DeterminismSource;
import com.darkcollective.relix.optimizer.internal.OptimizationContext;
import com.darkcollective.relix.optimizer.internal.QueryOptimizer;
import com.darkcollective.relix.cost.DistinctnessSource;
import com.darkcollective.relix.cost.MonotoneGeneratorSource;
import com.darkcollective.relix.events.QueryEventListener;
import com.darkcollective.relix.function.FunctionCatalog;

/**
 * Contexts for the rules that ask what a function may do.
 *
 * <p>{@link QueryOptimizer#optimize(com.darkcollective.relix.semantic.SemanticModel)} puts
 * the model's own {@link FunctionCatalog} on every {@link OptimizationContext} it builds,
 * so in production {@code EXPR-008}, {@code DIST-002} and {@code LATERAL-001} always have
 * one. A test driving a pass directly has to supply it, and a bare
 * {@code new OptimizationContext()} carries the empty catalogue — under which those rules
 * correctly decline to fire. This is where a test that is <em>about</em> one of them gets
 * a context that can.
 */
public final class OptimizerFixtures {

    /** The installed libraries — the same set the engine would discover at run time. */
    public static final FunctionCatalog FUNCTIONS = FunctionCatalog.discover();

    private OptimizerFixtures() {
    }

    /** A context over the installed function libraries. */
    public static OptimizationContext context() {
        return context(DistinctnessSource.NONE);
    }

    /** A context over the installed libraries, with a per-leaf distinctness source. */
    public static OptimizationContext context(DistinctnessSource distinctness) {
        return new OptimizationContext(QueryEventListener.NONE, distinctness,
                MonotoneGeneratorSource.NONE, FUNCTIONS);
    }

    /**
     * A source that vouches for every expression.
     *
     * <p>The production answer is {@code RelationDeterminism} bound to the query's symbol
     * table, which a pass test has no model to build. For a fixture tree of base
     * relations and literal predicates that walk answers {@code true} throughout, so
     * stating it here is not a weaker claim — it is the same one, made without a symbol
     * table. A test that is <em>about</em> the gate supplies
     * {@link DeterminismSource#NONE} or a source of its own instead.
     */
    public static final DeterminismSource REPRODUCIBLE = expression -> true;

    /** A context whose determinism source vouches for everything. */
    public static OptimizationContext reproducible() {
        return reproducible(DistinctnessSource.NONE);
    }

    /** A context whose determinism source vouches for everything, plus a distinctness source. */
    public static OptimizationContext reproducible(DistinctnessSource distinctness) {
        return context(distinctness, REPRODUCIBLE);
    }

    /** A context over the installed libraries, with both per-run lookups stated. */
    public static OptimizationContext context(DistinctnessSource distinctness,
                                       DeterminismSource determinism) {
        return new OptimizationContext(QueryEventListener.NONE, distinctness,
                MonotoneGeneratorSource.NONE, FUNCTIONS, determinism);
    }
}

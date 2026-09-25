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
package com.darkcollective.relix.optimizer.internal;

import com.darkcollective.relix.ast.RelNode;

/**
 * Answers, for a pipeline rule, whether an expression evaluates to the same relation
 * every time it is run.
 *
 * <p>A rewrite that changes <em>how many times</em> a sub-expression is evaluated needs
 * this. {@code SET-001} collapses {@code R ∪ R} to one evaluation of {@code R};
 * {@code SEL-010} turns two reads of {@code A} into one. Both are answer-preserving
 * exactly when the expression is reproducible, and {@code X ∆ X} over an unseeded
 * {@code SAMPLE} is a query <em>about</em> two draws.
 *
 * <p>It exists as a seam rather than as a direct call because the authoritative answer —
 * {@code RelationDeterminism} — resolves views and table-valued functions through a
 * {@link com.darkcollective.relix.symbol.table.SymbolTable}, and no rule in
 * {@link OptimizationPipeline} takes one. It is supplied on
 * {@link OptimizationContext} alongside the two other per-run lookups and the function
 * catalogue, which is the bundle a rule is already handed;
 * {@link QueryOptimizer#optimize(com.darkcollective.relix.semantic.SemanticModel)} binds
 * the model's symbol table into it.
 *
 * <p>The default is {@link #NONE}, which answers {@code false} for everything. That is
 * the conservative direction — a rule declines to fire — so a caller that supplies none
 * gets a slower plan, never a wrong one, exactly as
 * {@link com.darkcollective.relix.function.FunctionCatalog#empty()} does for the rules
 * that ask what a function may do.
 */
@FunctionalInterface
public interface DeterminismSource {

    /**
     * A source that vouches for nothing — every expression reads as irreproducible.
     *
     * <p>Not "no expression is deterministic", but "this caller cannot say": the rules
     * that consult it decline, which is the same behaviour they have for an expression
     * genuinely known to be volatile.
     */
    DeterminismSource NONE = expression -> false;

    /**
     * Returns whether evaluating {@code expression} twice necessarily gives the same
     * relation.
     *
     * @param expression the sub-expression to classify; never null
     * @return {@code true} only when the expression is known to be reproducible
     */
    boolean isDeterministic(RelNode expression);
}

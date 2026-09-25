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
 * Query optimization passes for Relix relational algebra expressions.
 *
 * <p>This module accepts a {@link com.darkcollective.relix.semantic.SemanticModel}
 * produced by semantic analysis and rewrites each query's
 * {@link com.darkcollective.relix.ast.RelNode} tree to a semantically equivalent
 * but more efficient form.  Every transformation is logged in an
 * {@link com.darkcollective.relix.optimizer.internal.OptimizationContext} so the full
 * audit trail can be rendered by
 * the console's optimization report.
 *
 * <h2>Optimization rules</h2>
 * <p>Each rule is identified by a unique
 * {@link com.darkcollective.relix.optimizer.OptimizationCode} and implements
 * the {@link com.darkcollective.relix.optimizer.internal.OptimizationRule} interface.
 * Codes are grouped by a category prefix ({@code SEL}, {@code JOIN}, …);
 * {@link com.darkcollective.relix.optimizer.OptimizationCode} is the definitive
 * list of both the categories and the rules in them, and
 * {@link com.darkcollective.relix.optimizer.internal.OptimizationPipeline} declares the
 * order they run in.  Neither is restated here — a second copy of an enumerable
 * list is a copy that drifts.
 *
 * <h2>Entry point</h2>
 * <pre>
 *   QueryOptimizer optimizer = new QueryOptimizer();
 *   List&lt;OptimizationResult&gt; results = optimizer.optimize(semanticModel);
 * </pre>
 */
module com.darkcollective.relix.optimizer {
    // RelNode, Predicate, Operand, SourceLocation — all in the public API.
    requires transitive com.darkcollective.relix.ast;

    // SemanticModel, SchemaAnnotations — in the public API.
    // Transitively provides relix-symbol and relix-ast.
    requires transitive com.darkcollective.relix.semantic;

    // The relation-property framework (ADR-0009): PropertyDeriver/OrderDeriver/Ordering for
    // DIST-001 and SORT-001, plus DistinctnessSource + MonotoneGeneratorSource in optimize()'s
    // public API.  Not CostEstimator/CostTier — the optimizer costs nothing;
    // cost-driven choices belong to the planner.
    requires transitive com.darkcollective.relix.cost;

    // QueryEventListener — in the optimize(SemanticModel, QueryEventListener) public API.
    requires transitive com.darkcollective.relix.events;

    // AggregateProperty / FunctionCatalog: DIST-002 asks an aggregate whether
    // multiplicity reaches its result rather than testing it against a name list
    // (ADR-0026 S7). Not transitive — no optimizer API mentions a function type.
    requires com.darkcollective.relix.function;

    exports com.darkcollective.relix.optimizer;
    exports com.darkcollective.relix.optimizer.internal to com.darkcollective.relix.embed;
}

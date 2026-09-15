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

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;

import java.util.List;

/**
 * A single query-optimization transformation rule.
 *
 * <p>Each rule encapsulates one rewrite family (identified by its {@link #code()},
 * or by {@link #codes()} for a rule that emits several related codes) and is applied
 * to a {@link RelNode} tree by {@link QueryOptimizer}.  The rules that make up the
 * default pipeline, the order they run in, and which of them are iterated to a
 * fixpoint are declared by {@link OptimizationPipeline}.  Rules must be:
 * <ul>
 *   <li><strong>Idempotent</strong> — applying the same rule twice to an
 *       already-optimized tree returns the same result as applying it once.</li>
 *   <li><strong>Semantics-preserving</strong> — the rewritten tree must
 *       produce identical results to the original for any legal input.</li>
 *   <li><strong>Stateless</strong> (recommended) — implementations should
 *       carry no mutable state so they are safe to share across multiple
 *       optimization runs.</li>
 * </ul>
 *
 * <h2>Contract for {@link #apply}</h2>
 * <ol>
 *   <li>Return the original {@code node} <em>unchanged</em> when the rule
 *       does not apply to this node.  Do <em>not</em> call
 *       {@link OptimizationContext#record} in this case.</li>
 *   <li>Return a rewritten {@link RelNode} when the rule fires, and call
 *       {@link OptimizationContext#record(OptimizationCode, String, String,
 *       com.darkcollective.relix.ast.SourceLocation) ctx.record()} exactly
 *       once per top-level rewrite performed at this node.</li>
 *   <li>Most rules only inspect the current node and rely on the
 *       {@link QueryOptimizer} engine to handle bottom-up or top-down
 *       traversal.  Rules that need to recurse into children explicitly
 *       should document this clearly.</li>
 * </ol>
 *
 * <p><strong>The two obligations above are what {@link OptimizationPipeline}'s
 * fixpoint driver reads as "progress".</strong>  It re-runs an iterated phase only
 * while its rules both recorded something <em>and</em> returned a different tree
 * (reference inequality, per {@link RelNode#mapChildren}'s identity contract).  A
 * rule that rewrites without recording, or records without rewriting, is not a
 * correctness bug on its own but does stop the phase early.
 */
public interface OptimizationRule {

    /**
     * Returns the {@link OptimizationCode} that identifies this rule.
     *
     * <p>For a rule that emits several codes this is the primary one — the first
     * entry of {@link #codes()}.
     *
     * @return the code; never null
     */
    OptimizationCode code();

    /**
     * Returns every {@link OptimizationCode} this rule can emit, primary first.
     *
     * <p>Rules are registered one per <em>pass</em>, and several passes cover a
     * family of related rewrites — {@code SelectionPushdownPass} alone emits
     * {@code SEL-003..009}.  The default implementation returns just
     * {@link #code()}, which is right for a single-code rule.
     *
     * @return unmodifiable list, never null or empty
     */
    default List<OptimizationCode> codes() {
        return List.of(code());
    }

    /**
     * Returns a short stable identifier for this rule, used when a pipeline is
     * rendered or a rule is named in a diagnostic.
     *
     * <p>Defaults to the primary {@link #code()}'s code string.
     *
     * @return the name; never null or blank
     */
    default String name() {
        return code().code();
    }

    /**
     * Applies this rule to the given node, returning either the original node
     * (if the rule did not fire) or a rewritten node (if it did).
     *
     * @param node        the node to inspect and possibly rewrite; never null
     * @param queryName   display name of the relation/query being optimized,
     *                    used in {@link TransformationRecord}s; never blank
     * @param schemas     schema annotations from semantic analysis; rules that
     *                    need to know a node's output schema use this to look
     *                    it up; never null
     * @param ctx         mutable context that accumulates transformation records;
     *                    never null
     * @return the original {@code node} when the rule did not fire, or a new
     *         {@link RelNode} representing the rewritten tree; never null
     */
    RelNode apply(RelNode             node,
                  String              queryName,
                  SchemaAnnotations   schemas,
                  OptimizationContext ctx);
}

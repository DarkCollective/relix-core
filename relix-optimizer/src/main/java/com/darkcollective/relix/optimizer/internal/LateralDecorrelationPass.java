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

import com.darkcollective.relix.optimizer.OptimizationCode;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.LateralJoinNode;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.internal.OperandWalker;
import com.darkcollective.relix.ast.ProductNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationFunctionCall;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.semantic.internal.RelationDeterminism;
import com.darkcollective.relix.symbol.FunctionProperty;
import com.darkcollective.relix.symbol.function.RelationFunctionSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.Optional;

/**
 * Optimization pass that decorrelates an <em>uncorrelated</em> {@code LATERAL} join —
 * one whose table-valued-function arguments never reference the outer row
 * ({@code LATERAL-001}).
 *
 * <h2>Rule</h2>
 * <pre>{@code
 *   L LATERAL f(c…)  →  L × f(c…)      when no argument reads a left column
 * }</pre>
 *
 * <h2>Why it is sound</h2>
 * <p>{@link LateralJoinNode} evaluates its arguments against each left row, instantiates
 * the function body with those values, and concatenates the left row with every row the
 * body yields.  When the arguments contain no attribute reference they evaluate to the
 * same values for every left row, so every instantiation is the <em>same</em> relation —
 * exactly the relation the leaf {@link RelationFunctionCall} denotes.  Pairing that one
 * relation with each left row is a Cartesian product, and the two agree on both parts
 * that matter:
 * <ul>
 *   <li><b>Schema.</b> Both are {@code left.concat(body)} — the lateral's inference arm
 *       and {@code binaryConcat} produce the identical ordered heading, so positional
 *       consumers downstream see no change (the trap {@code JOIN-003} fell into).</li>
 *   <li><b>Row order.</b> {@code ×} iterates the left input in the outer loop and the
 *       materialised right side in the inner one, which is the order the lateral emits
 *       its per-row groups in.</li>
 * </ul>
 *
 * <h2>Why it is worth doing</h2>
 * <p>The executor re-invokes <em>the planner</em> once per left row — N argument
 * lift-and-bind round trips and N freshly planned bodies for N rows.  Decorrelation
 * replaces all of that with one plan and one execution.
 *
 * <p>The larger win is visibility.  {@link LateralJoinNode#children()} reports only the
 * left input, so the function body is invisible to structural traversal and no other rule
 * can look into or through the operator.  Once it is a {@code ×} over a
 * {@link RelationFunctionCall}, the ordinary machinery applies: {@code JOIN-001} can turn
 * a selection above it into a theta join, selections push into the left input, and the
 * planner costs it like any other product.
 *
 * <h2>When it does not fire</h2>
 * <p>Every condition is a way of asking the same question — <em>would running the body
 * once give what running it once per row gives?</em>
 * <ul>
 *   <li><b>Any attribute reference in an argument.</b> That is the definition of a
 *       correlated lateral, and the test is deliberately blunt: <em>any</em>
 *       {@link com.darkcollective.relix.ast.AttributeOperand} anywhere in any argument
 *       blocks the rewrite.  A lateral's arguments are resolved against the left schema
 *       and nothing else, so an attribute that is not a left column is a validation error
 *       rather than a decorrelation opportunity — and a false "uncorrelated" would
 *       produce wrong answers.</li>
 *   <li><b>A non-deterministic argument.</b> {@code LATERAL f(Rand())} evaluates its
 *       argument once per left row; folding it to a single call would evaluate it once in
 *       total, which is observable.</li>
 *   <li><b>A non-deterministic function body.</b> The same objection one level down:
 *       {@code LATERAL sampleOf(0.5)} over a body reading {@code Rand()}, drawing an
 *       unseeded {@code SAMPLE}, or calling a view or nested TVF that does, produces a
 *       different relation per left row even with constant arguments.  Answered by
 *       {@link RelationDeterminism}, which is why this rule needs a {@link SymbolTable}
 *       and therefore runs in {@link QueryOptimizer}'s per-query preamble beside
 *       {@link ViewInliner} rather than as an {@link OptimizationPipeline} rule — every
 *       rule <em>in</em> the pipeline is a pure syntactic rewrite, and this one is not.
 *       A function that cannot be resolved is treated as non-deterministic.</li>
 * </ul>
 *
 * <p>The pass is <em>bottom-up</em>: each child is rewritten before the rule is attempted
 * at the current node, so a stack of uncorrelated laterals decorrelates fully in one
 * traversal.
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, SymbolTable, OptimizationContext, String)} as a static method.
 */
public final class LateralDecorrelationPass {

    private LateralDecorrelationPass() {}

    /**
     * Applies lateral decorrelation (LATERAL-001) to the entire tree rooted at
     * {@code node}.
     *
     * @param node      root of the tree to transform; must not be null
     * @param symbols   used to resolve the TVF and classify its body; must not be null
     * @param ctx       transformation record accumulator
     * @param queryName display name used in transformation records
     * @return the transformed tree, or {@code node} unchanged when no rule fires
     */
    static RelNode apply(RelNode node, SymbolTable symbols,
                         OptimizationContext ctx, String queryName) {
        RelNode rewritten = node.mapChildren(child -> apply(child, symbols, ctx, queryName));
        if (!(rewritten instanceof LateralJoinNode lateral)
                || correlatedArguments(lateral, ctx.functions())
                || !bodyIsDeterministic(lateral, symbols, ctx.functions())) {
            return rewritten;
        }
        ctx.record(OptimizationCode.LATERAL_001, queryName,
                "LATERAL " + lateral.functionName()
                + " decorrelated to × over a single call (no argument reads the outer row)",
                lateral.location());
        RelNode call = new RelationFunctionCall(lateral.functionName(), lateral.arguments(),
                lateral.location());
        return new ProductNode(lateral.left(), call, lateral.location());
    }

    /**
     * Whether {@code lateral}'s arguments make it genuinely per-row: any attribute
     * reference (a left-column read) or any call the query's own catalogue does not tag
     * {@link FunctionProperty#DETERMINISTIC} — which includes every user-defined scalar
     * function, the same conservative reading {@code DIST-002} uses.
     */
    private static boolean correlatedArguments(LateralJoinNode lateral,
                                               FunctionCatalog functions) {
        boolean[] perRow = {false};
        for (Operand argument : lateral.arguments()) {
            OperandWalker.walk(argument,
                    unused -> perRow[0] = true,
                    (FunctionCall call) -> {
                        boolean declared = functions.scalar(call.functionName())
                                .map(fn -> fn.signature().has(FunctionProperty.DETERMINISTIC))
                                .orElse(false);
                        if (!declared) {
                            perRow[0] = true;
                        }
                    });
        }
        return perRow[0];
    }

    /**
     * Whether the table-valued function {@code lateral} calls has a body that evaluates to
     * the same relation every time.  An unresolvable function — or one whose arity does
     * not match, which the planner would reject anyway — answers {@code false}.
     */
    private static boolean bodyIsDeterministic(LateralJoinNode lateral, SymbolTable symbols,
                                               FunctionCatalog functions) {
        Optional<RelationFunctionSymbol> fn = symbols.resolveFunction(lateral.functionName())
                .stream()
                .filter(RelationFunctionSymbol.class::isInstance)
                .map(RelationFunctionSymbol.class::cast)
                .filter(f -> f.parameters().size() == lateral.arguments().size())
                .findFirst();
        return fn.isPresent()
                && RelationDeterminism.isDeterministic(fn.get().body(), symbols, functions);
    }
}

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
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.List;

/**
 * Expands references to named views into the views' bodies, so that the rule
 * passes can optimise <em>across</em> former view boundaries (e.g. push a
 * selection from the outer query down into what used to be a view).
 *
 * <p>A reference {@code RelationNode("V")} that resolves to a
 * {@link QueryRelationSymbol} is replaced by
 * {@code ρ V (inline(V.body))} — the view's (recursively inlined) body wrapped in
 * a relation-only {@link RenameNode}.  The wrapper re-establishes {@code V} as a
 * relation alias so that qualified attribute references like {@code V.col} still
 * resolve to the correct side of a join after inlining (without it, the view name
 * would no longer name any subtree).  A relation-only rename leaves column names
 * unchanged, so the expansion is otherwise transparent.
 *
 * <p>Base relations (inline literals, sources, databases) are left as
 * {@link RelationNode} leaves.  Cycles are impossible because semantic analysis
 * rejects cyclic view definitions.
 */
final class ViewInliner {

    private ViewInliner() {
    }

    /**
     * Returns {@code node} with every view reference expanded.  Each expansion is
     * recorded as an {@link OptimizationCode#INLINE_001} transformation in
     * {@code ctx}.  Returns the same instance when {@code node} contains no view
     * references.
     *
     * @param node      the tree to inline; must not be null
     * @param symbols   the symbol table used to resolve view references; must not be null
     * @param ctx       collects an {@code INLINE-001} record per inlined view
     * @param queryName the owning query's name, for the transformation record
     * @return the inlined tree
     */
    static RelNode inline(RelNode node, SymbolTable symbols,
                          OptimizationContext ctx, String queryName) {
        if (node instanceof RelationNode relation) {
            return symbols.lookupRelation(relation.name())
                    .filter(QueryRelationSymbol.class::isInstance)
                    .map(QueryRelationSymbol.class::cast)
                    .map(view -> inlineView(relation, view, symbols, ctx, queryName))
                    .orElse(relation);   // base relation — leaf, unchanged
        }
        return node.mapChildren(child -> inline(child, symbols, ctx, queryName));
    }

    private static RelNode inlineView(RelationNode reference, QueryRelationSymbol view,
                                      SymbolTable symbols, OptimizationContext ctx, String queryName) {
        ctx.record(OptimizationCode.INLINE_001, queryName,
                "view '" + view.declaredName() + "' inlined", reference.location());
        RelNode inlinedBody = inline(view.body(), symbols, ctx, queryName);
        // Re-alias the body to the view name so qualified references still resolve.
        return new RenameNode(reference.name(), List.of(), inlinedBody, reference.location());
    }
}

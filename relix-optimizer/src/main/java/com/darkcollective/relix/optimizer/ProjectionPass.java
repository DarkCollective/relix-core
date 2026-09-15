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

import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.symbol.Schema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Cleanup pass that simplifies projection ({@code π}) nodes using three rules:
 *
 * <ul>
 *   <li><b>PROJ-001</b> — Removes a redundant projection that outputs exactly
 *       the same column set as its input.  Requires a schema annotation for
 *       the projection's input; skipped when the annotation is absent.
 *       A projection is redundant when every projected attribute is a
 *       plain column reference (no alias, no arithmetic), and the set of
 *       referenced column names exactly equals the input schema's column
 *       names.</li>
 *   <li><b>PROJ-002</b> — Merges two stacked projections into one, eliminating
 *       the intermediate operator.  The outer projection's attributes must be
 *       plain column references (possibly with aliases); each is resolved to
 *       the inner projection's corresponding expression.
 *       After merging, PROJ-001 is tested on the merged result.</li>
 *   <li><b>PROJ-003</b> — Pushes a projection below an intervening selection
 *       when every attribute referenced by the selection's predicate is present
 *       in the projection's output, so the selection can operate on the
 *       narrower (projected) rows.  No schema annotations are required.
 *       {@code π(A)(σ(p)(R)) → σ(p)(π(A)(R))} when {@code attrs(p) ⊆ A}.
 *       </li>
 * </ul>
 *
 * <p>The rules are applied in order within a single bottom-up traversal:
 * PROJ-003 is attempted first (structural re-ordering), then PROJ-002
 * (merging), then PROJ-001 (removal).  Only the first applicable rule
 * fires per projection node per pass.
 *
 * <p>This pass runs in the cleanup phase, after selection pushdown is
 * complete.  Running it separately from the selection-pushdown phase avoids
 * an infinite loop that would otherwise occur if PROJ-003 and SEL-003 were
 * iterated together (they are mutual inverses when all predicate columns are
 * within the projection output).
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, SchemaAnnotations, OptimizationContext)}
 * as a static method.
 */
final class ProjectionPass {

    private ProjectionPass() {}

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Applies projection rules (PROJ-001..003) to the entire tree rooted at
     * {@code node}.
     *
     * @param node      root of the tree to transform; must not be null
     * @param queryName display name used in transformation records
     * @param schemas   schema annotations used by PROJ-001 to detect redundant
     *                  projections; may be empty (PROJ-001 is skipped when
     *                  the relevant annotation is absent)
     * @param ctx       transformation record accumulator
     * @return the transformed tree, or {@code node} unchanged when no rule fires
     */
    static RelNode apply(RelNode node, String queryName,
                         SchemaAnnotations schemas, OptimizationContext ctx) {
        return rewriteNode(node, queryName, schemas, ctx);
    }

    // =========================================================================
    // RelNode traversal  (bottom-up)
    // =========================================================================

    private static RelNode rewriteNode(RelNode node, String queryName,
                                        SchemaAnnotations schemas,
                                        OptimizationContext ctx) {
        return switch (node) {

            // ── π: apply projection rules after recursing into input ──────────
            case ProjectionNode p -> {
                RelNode newInput = rewriteNode(p.input(), queryName, schemas, ctx);
                ProjectionNode current = (newInput != p.input())
                        ? new ProjectionNode(p.attributes(), newInput, p.location()) : p;
                yield applyProjectionRules(current, queryName, schemas, ctx);
            }

            // ── All other nodes: recurse into children, no rule fires ────────
            default -> node.mapChildren(child -> rewriteNode(child, queryName, schemas, ctx));
        };
    }

    // =========================================================================
    // Rule dispatcher for ProjectionNode
    // =========================================================================

    /**
     * Attempts PROJ-003, then PROJ-002, then PROJ-001, returning the first
     * transformation that fires (or {@code p} unchanged if none apply).
     */
    private static RelNode applyProjectionRules(ProjectionNode p,
                                                 String queryName,
                                                 SchemaAnnotations schemas,
                                                 OptimizationContext ctx) {
        // PROJ-003: push π below σ so selection operates on narrower rows
        if (p.input() instanceof SelectionNode s
                && canPushBelowSelection(p.attributes(), s)) {
            ctx.record(OptimizationCode.PROJ_003, queryName,
                    "projection pushed below selection to reduce column width",
                    p.location());
            RelNode newProj = new ProjectionNode(p.attributes(), s.input(), p.location());
            return new SelectionNode(s.predicate(), newProj, s.location());
        }

        // PROJ-002: merge π(outer)(π(inner)(R)) → π(merged)(R)
        if (p.input() instanceof ProjectionNode inner) {
            Optional<ProjectionNode> merged = tryMerge(p, inner);
            if (merged.isPresent()) {
                ctx.record(OptimizationCode.PROJ_002, queryName,
                        "consecutive projections merged",
                        p.location());
                // After merge, test PROJ-001 on the result
                ProjectionNode mergedNode = merged.get();
                if (isRedundant(mergedNode, schemas)) {
                    ctx.record(OptimizationCode.PROJ_001, queryName,
                            "redundant projection eliminated after merge",
                            mergedNode.location());
                    return mergedNode.input();
                }
                return mergedNode;
            }
        }

        // PROJ-001: remove no-op projection
        if (isRedundant(p, schemas)) {
            ctx.record(OptimizationCode.PROJ_001, queryName,
                    "redundant projection eliminated (projects identical schema)",
                    p.location());
            return p.input();
        }

        return p;
    }

    // =========================================================================
    // PROJ-003 helpers
    // =========================================================================

    /**
     * Returns {@code true} when all attribute names referenced in the selection's
     * predicate are present in the projection output described by {@code attrs}.
     *
     * <p>The projection output column name for each projected attribute is:
     * the alias (if present), or the unqualified name of a plain
     * {@link AttributeOperand} expression (if no alias).  Computed
     * expressions without aliases have no simple output name and cannot be
     * tested against predicate references.
     */
    private static boolean canPushBelowSelection(List<ProjectedAttribute> attrs,
                                                  SelectionNode s) {
        Set<String> predAttrs = PredicateAttributeCollector.collectNames(s.predicate());
        if (predAttrs.isEmpty()) return true; // literal predicate — always safe

        Set<String> projOutput = projectionOutputNames(attrs);
        return predAttrs.stream().allMatch(a -> {
            String col = PredicateAttributeCollector.columnPart(a).toLowerCase(Locale.ROOT);
            return projOutput.contains(col);
        });
    }

    /** Returns the set of output column names (lowercased) produced by {@code attrs}. */
    private static Set<String> projectionOutputNames(List<ProjectedAttribute> attrs) {
        var names = new HashSet<String>();
        for (ProjectedAttribute pa : attrs) {
            if (pa.alias().isPresent()) {
                names.add(pa.alias().get().toLowerCase(Locale.ROOT));
            } else if (pa.expression() instanceof AttributeOperand ao) {
                names.add(PredicateAttributeCollector.columnPart(ao.name())
                        .toLowerCase(Locale.ROOT));
            }
            // computed expression with no alias → no output name to register
        }
        return names;
    }

    // =========================================================================
    // PROJ-002 helpers
    // =========================================================================

    /**
     * Attempts to merge {@code outer} and {@code inner} into a single
     * projection directly on {@code inner.input()}.
     *
     * <p>Merge is possible when every attribute in {@code outer} is a plain
     * column reference ({@link AttributeOperand}), and the referenced column
     * can be found in the output of {@code inner}.  The merged attribute uses
     * the inner's expression together with the outer's alias (if any).
     *
     * @return the merged projection, or {@link Optional#empty()} if any outer
     *         attribute cannot be resolved through the inner projection
     */
    private static Optional<ProjectionNode> tryMerge(ProjectionNode outer,
                                                       ProjectionNode inner) {
        var mergedAttrs = new ArrayList<ProjectedAttribute>(outer.attributes().size());
        for (ProjectedAttribute outerPa : outer.attributes()) {
            // Outer expression must be a simple column reference
            if (!(outerPa.expression() instanceof AttributeOperand outerAo)) {
                return Optional.empty();
            }
            String lookupName = PredicateAttributeCollector
                    .columnPart(outerAo.name());

            // Find the inner attr that produces `lookupName` in its output
            Optional<ProjectedAttribute> innerMatch = findOutputAttr(
                    inner.attributes(), lookupName);
            if (innerMatch.isEmpty()) return Optional.empty();

            ProjectedAttribute innerPa = innerMatch.get();
            // Compose: inner expression + outer alias (if present) or inner alias
            ProjectedAttribute mergedPa = outerPa.alias().isPresent()
                    ? ProjectedAttribute.aliased(innerPa.expression(),
                            outerPa.alias().get())
                    : innerPa; // keep inner as-is (expression + its alias)
            mergedAttrs.add(mergedPa);
        }
        return Optional.of(new ProjectionNode(mergedAttrs, inner.input(), outer.location()));
    }

    /**
     * Finds the projected attribute in {@code attrs} whose output column name
     * equals {@code colName} (case-insensitive).  The output name is the alias
     * (if present) or the unqualified name of a plain {@link AttributeOperand}
     * expression.
     */
    private static Optional<ProjectedAttribute> findOutputAttr(
            List<ProjectedAttribute> attrs, String colName) {
        String key = colName.toLowerCase(Locale.ROOT);
        for (ProjectedAttribute pa : attrs) {
            String outputName = pa.alias()
                    .map(a -> a.toLowerCase(Locale.ROOT))
                    .orElseGet(() -> {
                        if (pa.expression() instanceof AttributeOperand ao) {
                            return PredicateAttributeCollector
                                    .columnPart(ao.name())
                                    .toLowerCase(Locale.ROOT);
                        }
                        return null; // computed expr with no alias → no output name
                    });
            if (key.equals(outputName)) return Optional.of(pa);
        }
        return Optional.empty();
    }

    // =========================================================================
    // PROJ-001 helpers
    // =========================================================================

    /**
     * Returns {@code true} when {@code p} is a no-op: every projected
     * attribute is a plain, unaliased column reference, and together the
     * references cover exactly the same columns as the input schema (by name,
     * case-insensitively, regardless of order).
     *
     * <p>Returns {@code false} when the input schema is not annotated.
     */
    private static boolean isRedundant(ProjectionNode p, SchemaAnnotations schemas) {
        Optional<Schema> inputSchemaOpt = schemas.get(p.input());
        if (inputSchemaOpt.isEmpty()) return false;
        Schema inputSchema = inputSchemaOpt.get();

        List<ProjectedAttribute> attrs = p.attributes();
        if (attrs.size() != inputSchema.width()) return false;

        // Build the set of input column names
        var remaining = new HashSet<String>();
        inputSchema.columns().forEach(c -> remaining.add(c.name().toLowerCase(Locale.ROOT)));

        for (ProjectedAttribute pa : attrs) {
            // Must be a plain column reference with no alias (or alias == column name)
            if (!(pa.expression() instanceof AttributeOperand ao)) return false;
            String colName = PredicateAttributeCollector
                    .columnPart(ao.name()).toLowerCase(Locale.ROOT);
            if (pa.alias().isPresent()
                    && !pa.alias().get().equalsIgnoreCase(colName)) {
                return false; // alias renames the column
            }
            if (!remaining.remove(colName)) return false; // not in input or duplicate
        }
        return remaining.isEmpty(); // all input columns covered
    }

}

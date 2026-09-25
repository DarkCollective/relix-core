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
import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.AggregationNode;
import com.darkcollective.relix.ast.internal.AttributeNames;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.UnnestNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Optimization pass for the NF² nest/unnest round-trip laws ({@code NEST-001..003}),
 * after Hölsch, Grossniklaus &amp; Scholl, <em>Optimization of Nested Queries using the
 * NF² Algebra</em> (SIGMOD 2016); see {@code ADR-0006}.
 *
 * <p>Relix expresses NEST as {@code γ keys, COLLECT(x)→g (R)} (the
 * {@link AggregateOperator#COLLECT} aggregate builds an array-valued column) and
 * UNNEST as {@code μ g (…)} ({@link UnnestNode}); the two are genuine inverses.
 * This pass recognises the patterns where one immediately undoes the other, or
 * where a filter/column-pruning projection can move below the row-multiplying
 * {@code μ}.
 *
 * <h2>Rules</h2>
 * <pre>{@code
 *   NEST-001:  μ g (γ keys, COLLECT(x)→g (R))  →  π keys, (x → g) (R)
 *   NEST-002:  σ p (μ c (R))                   →  μ c (σ p (R))         [p avoids c and any ordinality column]
 *   NEST-003:  π cols (μ c (R))                →  μ c (π cols (R))      [bare column list including c, none reading into c]
 * }</pre>
 *
 * <h3>NEST-001 — round-trip collapse ({@code μ ∘ COLLECT = id})</h3>
 * <p>Fires when a {@code μ} unnests exactly the array column produced by a single
 * {@code COLLECT} aggregate of the {@link AggregationNode} directly beneath it.
 * Grouping then exploding reproduces the pre-nest rows (the multiset of
 * {@code (keys, x)} values is unchanged under bag semantics), so the pair is
 * rewritten to a projection that selects the keys and the collected expression
 * (renamed to the unnested column). This removes a blocking {@code γ} and the
 * {@code μ}; the produced projection is then available to the projection/pushdown
 * cleanup passes.
 *
 * <p>The rule is guarded:
 * <ul>
 *   <li>the {@code μ} must carry <strong>no</strong> {@code WITH ORDINALITY}
 *       column (a per-group 1-based position is not reproduced by the projection);</li>
 *   <li>an <strong>outer</strong> {@code μ} fires only when the aggregation has at
 *       least one grouping key — with keys every group is non-empty so {@code COLLECT}
 *       never yields an empty array and {@code outer} is moot, but a no-key
 *       (scalar) {@code COLLECT} over an empty input still emits one empty array,
 *       whose outer-unnest NULL row the projection would not reproduce.</li>
 * </ul>
 *
 * <h3>NEST-002 — selection pushed below unnest</h3>
 * <p>A predicate that references neither the unnested column nor the ordinality
 * column commutes with the explode, so it is moved below {@code μ} to filter
 * before the row multiplication. Valid for both inner and outer {@code μ}.
 *
 * <h3>NEST-003 — projection pushed below unnest</h3>
 * <p>A column-pruning projection — every attribute a bare, unaliased column and
 * the unnested column among them — moves below {@code μ} so fewer columns flow
 * through the explode. Conservatively skipped when any attribute carries an
 * expression or alias, when the unnested column is not projected, or when the
 * {@code μ} has an ordinality column.
 *
 * <h3>What "references the unnested column" means</h3>
 * <p>Both push-down rules turn on that question, and a dotted attribute name has two
 * readings of it. {@code skills.years} is a path into the column {@code skills} — a
 * field of the element the {@code μ} produced — and reading it as a relation qualifier
 * yields {@code years}, which does not look like a reference to {@code skills} at all.
 * A rule that only looked at the qualifier reading therefore pushed such a predicate
 * below the {@code μ}, where {@code skills} is still the array, the path resolves to
 * nothing, and the query returns no rows. Both guards test both readings.
 *
 * <p>The traversal is <em>bottom-up</em>: each input is rewritten before the rule
 * is attempted at the current node. A {@code σ} pushed below a {@code μ} becomes a
 * {@code μ} at the top, so a stack of independent selections above one {@code μ}
 * all push through in a single invocation without an explicit retry.
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, SchemaAnnotations, OptimizationContext)} as a
 * static method.
 */
final class NestUnnestPass {

    private NestUnnestPass() {}

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Applies the nest/unnest round-trip laws (NEST-001..003) to the entire tree
     * rooted at {@code node}.
     *
     * @param node      root of the tree to transform; must not be null
     * @param queryName display name used in transformation records
     * @param schemas   schema annotations; unused by this pass (the rules are
     *                  purely structural) but accepted for API consistency
     * @param ctx       transformation record accumulator
     * @return the transformed tree, or {@code node} unchanged when no rule fires
     */
    static RelNode apply(RelNode node, String queryName,
                         SchemaAnnotations schemas, OptimizationContext ctx) {
        return rewriteNode(node, queryName, ctx);
    }

    // =========================================================================
    // RelNode traversal (bottom-up)
    // =========================================================================

    private static RelNode rewriteNode(RelNode node, String queryName,
                                       OptimizationContext ctx) {
        return switch (node) {

            // ── μ: recurse into input, then try NEST-001 ──────────────────────
            case UnnestNode u -> {
                RelNode ni = rewriteNode(u.input(), queryName, ctx);
                UnnestNode current = (ni != u.input())
                        ? new UnnestNode(u.column(), u.outer(), u.ordinalityColumn(), ni, u.location())
                        : u;
                yield tryRoundTrip(current, queryName, ctx);
            }

            // ── σ: recurse into input, then try NEST-002 ──────────────────────
            case SelectionNode s -> {
                RelNode ni = rewriteNode(s.input(), queryName, ctx);
                SelectionNode current = (ni != s.input())
                        ? new SelectionNode(s.predicate(), ni, s.location()) : s;
                if (current.input() instanceof UnnestNode u
                        && predicateAvoidsUnnest(current, u)) {
                    ctx.record(OptimizationCode.NEST_002, queryName,
                            "selection pushed below unnest; predicate avoids the unnested column",
                            current.location());
                    yield new UnnestNode(u.column(), u.outer(), u.ordinalityColumn(),
                            new SelectionNode(current.predicate(), u.input(), current.location()),
                            u.location());
                }
                yield current;
            }

            // ── π: recurse into input, then try NEST-003 ──────────────────────
            case ProjectionNode p -> {
                RelNode ni = rewriteNode(p.input(), queryName, ctx);
                ProjectionNode current = (ni != p.input())
                        ? new ProjectionNode(p.attributes(), ni, p.location()) : p;
                if (current.input() instanceof UnnestNode u
                        && projectionPrunesAround(current, u)) {
                    ctx.record(OptimizationCode.NEST_003, queryName,
                            "column-pruning projection pushed below unnest",
                            current.location());
                    yield new UnnestNode(u.column(), u.outer(), u.ordinalityColumn(),
                            new ProjectionNode(current.attributes(), u.input(), current.location()),
                            u.location());
                }
                yield current;
            }

            // ── All other nodes: recurse into children, no rule fires ────────
            default -> node.mapChildren(child -> rewriteNode(child, queryName, ctx));
        };
    }

    // =========================================================================
    // Rule helpers
    // =========================================================================

    /**
     * NEST-001: rewrites {@code μ g (γ keys, COLLECT(x)→g (R))} to
     * {@code π keys, (x → g) (R)} when the guards hold, else returns {@code u}.
     */
    private static RelNode tryRoundTrip(UnnestNode u, String queryName, OptimizationContext ctx) {
        if (u.ordinalityColumn().isPresent()
                || !(u.input() instanceof AggregationNode a)
                || a.aggregates().size() != 1) {
            return u;
        }
        AggregateFunction collect = a.aggregates().get(0);
        if (collect.operator() != AggregateOperator.COLLECT
                || !collect.outputName().equalsIgnoreCase(u.column())) {
            return u;
        }
        // An outer μ over a no-key (scalar) COLLECT would emit a NULL row on empty
        // input that the projection cannot reproduce; require keys in that case.
        if (u.outer() && a.groupingKeys().isEmpty()) {
            return u;
        }
        List<ProjectedAttribute> attrs = new ArrayList<>(a.groupingKeys().size() + 1);
        for (GroupingKey key : a.groupingKeys()) {
            // A grouping key survives into the projection as its expression aliased
            // to the same output column it produced in the γ.
            attrs.add(new ProjectedAttribute(key.expression(), key.alias()));
        }
        attrs.add(new ProjectedAttribute(collect.argument(), Optional.of(collect.outputName())));
        ctx.record(OptimizationCode.NEST_001, queryName,
                "μ over COLLECT collapsed to projection (nest/unnest round-trip)",
                u.location());
        return new ProjectionNode(attrs, a.input(), u.location());
    }

    /**
     * NEST-002 guard: true when {@code s}'s predicate references neither the
     * unnested column nor the ordinality column of {@code u}.
     */
    private static boolean predicateAvoidsUnnest(SelectionNode s, UnnestNode u) {
        for (String name : PredicateAttributeCollector.collectNames(s.predicate())) {
            if (touches(name, u.column())) {
                return false;
            }
            if (u.ordinalityColumn().map(o -> touches(name, o)).orElse(false)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code name} reads {@code column} under <em>either</em> reading of a
     * dotted attribute name — as the column part of a relation-qualified reference,
     * or as the head of a path into it.
     *
     * <p>Both readings have to be refused, and the second is the one a push below
     * {@code μ} turns into wrong rows rather than a slower plan. Above the unnest
     * {@code skills} holds one element, so {@code skills.years} names a field of it;
     * below, {@code skills} is still the array, and the same path resolves to nothing
     * — so the predicate is UNKNOWN for every row and the query returns none. Reading
     * only the qualifier part of {@code skills.years} yields {@code years}, which does
     * not look like a reference to {@code skills} at all, which is how that push used
     * to be permitted.
     *
     * <p>The test is deliberately blunt in the other direction: a name whose tail
     * matches under the path reading (or vice versa) blocks the rewrite too. Declining
     * to push is always sound, so an over-match costs a slower plan and never an
     * answer.
     */
    private static boolean touches(String name, String column) {
        return PredicateAttributeCollector.columnPart(name).equalsIgnoreCase(column)
                || AttributeNames.pathHead(name).equalsIgnoreCase(column);
    }

    /**
     * NEST-003 guard: true when {@code u} has no ordinality column and every
     * projected attribute is a bare, unaliased column reference, with the
     * unnested column among them (a pure column-pruning projection).
     *
     * <p>A projection that reads a <em>field</em> of the unnested column is not a
     * column-pruning projection and is refused. It is the same hazard NEST-002 has,
     * and it needs its own test rather than {@link #touches}: here a reference to the
     * unnested column is what <em>permits</em> the rewrite, so a path into that column
     * would otherwise argue for the push instead of against it.
     */
    private static boolean projectionPrunesAround(ProjectionNode p, UnnestNode u) {
        if (u.ordinalityColumn().isPresent()) {
            return false;
        }
        boolean projectsUnnestColumn = false;
        for (ProjectedAttribute attr : p.attributes()) {
            if (attr.alias().isPresent()
                    || !(attr.expression() instanceof AttributeOperand a)) {
                return false;
            }
            if (AttributeNames.isPathInto(a.name(), u.column())) {
                return false;   // reads a field of the element, which exists only above μ
            }
            if (PredicateAttributeCollector.columnPart(a.name()).equalsIgnoreCase(u.column())) {
                projectsUnnestColumn = true;
            }
        }
        return projectsUnnestColumn;
    }
}

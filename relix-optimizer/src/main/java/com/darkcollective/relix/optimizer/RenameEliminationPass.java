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

import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregationNode;
import com.darkcollective.relix.ast.AttributeNames;
import com.darkcollective.relix.ast.ConditionalJoinNode;
import com.darkcollective.relix.ast.DifferenceNode;
import com.darkcollective.relix.ast.DistinctNode;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.IntersectionNode;
import com.darkcollective.relix.ast.LimitNode;
import com.darkcollective.relix.ast.NaturalJoinNode;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.OperandWalker;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProductNode;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SortNode;
import com.darkcollective.relix.ast.SortSpecification;
import com.darkcollective.relix.ast.SymmetricDifferenceNode;
import com.darkcollective.relix.ast.UnionAllNode;
import com.darkcollective.relix.ast.UnionNode;
import com.darkcollective.relix.ast.UnnestNode;

import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Rename elimination ({@code RENAME-001} / {@code RENAME-002}) — the cleanup pass that
 * runs immediately after {@link ViewInliner}.
 *
 * <h2>Why it exists</h2>
 * <p>{@code ViewInliner} replaces every view reference with
 * {@code ρ V (inline(V.body))}.  The wrapper is load-bearing at the moment of inlining —
 * it re-establishes {@code V} as an alias so a qualified {@code V.col} still resolves —
 * but once nothing names {@code V}, the ρ is dead weight that <em>survives into the
 * final plan</em>, sitting between operators that other passes need to see adjacent.
 * That is not hypothetical: {@link SelectionIntoFixpointPass} grew explicit logic to
 * peel interleaved relation-only renames out of its transparent prefix, and every
 * future shape-matching pass would pay the same tax.
 *
 * <h2>The two rules</h2>
 * <dl>
 *   <dt>{@code RENAME-001} — collapse a ρ chain</dt>
 *   <dd>A relation-only ρ directly above another ρ absorbs it:
 *       {@code ρ V (ρ W [spec] (R))} → {@code ρ V [spec] (R)}.  <strong>Unconditional.</strong>
 *       The outer ρ re-anchors every column's provenance to {@code V}, so {@code W} is
 *       not a resolvable qualifier at the outer node's output either way — collapsing
 *       loses a name that was already invisible one level up.</dd>
 *
 *   <dt>{@code RENAME-002} — drop an unreferenced relation-only ρ</dt>
 *   <dd>{@code ρ V (X)} → {@code X}, when nothing in the query names {@code V}.  A
 *       relation-only ρ leaves every <em>column name</em> untouched (see
 *       {@code SchemaInferenceVisitor#visit(RenameNode)}), so the only thing removal can
 *       change is how a <em>relation-qualified</em> reference resolves.</dd>
 * </dl>
 *
 * <h2>The reference sweep, and why it checks two things</h2>
 * <p>A qualified reference resolves by column <em>provenance</em> and silently falls
 * back to a bare-name match when the qualifier names nothing
 * ({@code ArrayRow#get(String)}).  Removing {@code ρ V} reverts the provenance of every
 * column beneath it from {@code V} to whatever the input carried, so two things have to
 * hold — and a reference can hide in a predicate, a projection, a join condition, a
 * grouping or sort key, so the sweep covers all of them via {@link OperandWalker}:
 * <ol>
 *   <li><b>{@code V} is not referenced.</b>  Otherwise {@code V.col} stops resolving and
 *       falls back to a bare-name first match — a silent wrong answer.</li>
 *   <li><b>No relation name the input <em>re-exposes</em> is referenced either.</b>  This
 *       is the subtle half.  In {@code (ρ V (Orders)) ⋈ Orders} a reference to
 *       {@code Orders.id} resolves to exactly one column today, because the left side is
 *       stamped {@code V}.  Drop the ρ and both sides are stamped {@code Orders}: the
 *       reference becomes ambiguous and falls back to a first match.  Nothing about
 *       {@code V} itself would have caught that.</li>
 * </ol>
 *
 * <p>The sweep is deliberately whole-tree rather than "above this ρ".  A qualifier used
 * only <em>below</em> the ρ cannot actually be affected by removing it, so treating it as
 * a blocker is over-conservative — but it costs one traversal instead of a
 * position-aware analysis, and an over-conservative descriptor is a no-op, never a wrong
 * answer (ADR-0020, Decision 5).
 *
 * <p>The same principle governs the node types the sweep understands.  It enumerates the
 * operand-carrying core (σ π ρ γ τ λ δ μ, the joins, the set operations); reaching
 * <em>any</em> other node — an analytic, recursive, solver or generator operator whose
 * column references this pass does not know how to enumerate — abandons the whole pass
 * rather than guess.  Deriving "unreferenced" from an incomplete sweep is precisely how
 * a rewrite of this shape returns wrong rows.
 *
 * <h2>Where it runs</h2>
 * <p>Not in {@link OptimizationPipeline}, but in
 * {@code QueryOptimizer.inlineThenOptimize} between {@code ViewInliner} and the schema
 * re-inference that follows it.  Removing a node drops the identity-keyed
 * {@link com.darkcollective.relix.semantic.SchemaAnnotations} entry of every node above
 * it, so running before the re-inference is what keeps the rule passes seeing a fully
 * annotated tree.
 *
 * <p>Composing two <em>column-renaming</em> ρ into one is deliberately out of scope: it
 * needs the input schema to resolve which pass-through column an outer pair names, and
 * {@code ViewInliner} — the reason this pass exists — only ever produces the
 * relation-only form.
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, OptimizationContext)} as a static method.
 */
final class RenameEliminationPass {

    private RenameEliminationPass() {}

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Collapses and removes renames across the whole tree rooted at {@code node}.
     *
     * @param node      root of the tree to transform; must not be null
     * @param queryName display name used in transformation records
     * @param ctx       transformation record accumulator
     * @return the transformed tree, or {@code node} unchanged when no rule fires
     */
    static RelNode apply(RelNode node, String queryName, OptimizationContext ctx) {
        Optional<Set<String>> referenced = referencedQualifiers(node);
        if (referenced.isEmpty()) {
            return node;   // a node whose column references we cannot enumerate
        }
        return rewrite(node, referenced.get(), queryName, ctx);
    }

    // =========================================================================
    // Bottom-up rewrite
    // =========================================================================

    /**
     * Rewrites {@code node}'s inputs first, so a chain of three renames collapses
     * inside this single application: the node above always sees the already-collapsed
     * input.
     */
    private static RelNode rewrite(RelNode node, Set<String> referenced,
                                   String queryName, OptimizationContext ctx) {
        RelNode mapped = node.mapChildren(child -> rewrite(child, referenced, queryName, ctx));
        if (!(mapped instanceof RenameNode r) || r.renamesColumns()) {
            return mapped;
        }
        // A relation-only ρ: it always has a relation name (the record forbids a ρ with
        // neither a name nor a column spec).
        String alias = r.relationName().orElseThrow();

        // ── RENAME-001: absorb the ρ directly beneath ─────────────────────────
        if (r.input() instanceof RenameNode inner) {
            ctx.record(OptimizationCode.RENAME_001, queryName,
                    "rename chain collapsed — ρ " + alias + " absorbed the ρ"
                            + inner.relationName().map(n -> " " + n).orElse("") + " beneath it",
                    r.location());
            r = new RenameNode(r.relationName(), inner.attributes(), inner.pairs(),
                    inner.input(), r.location());
            if (r.renamesColumns()) {
                return r;   // the collapsed node carries the inner's column renames
            }
        }

        // ── RENAME-002: drop it when its alias buys nothing ───────────────────
        if (removable(alias, r.input(), referenced)) {
            ctx.record(OptimizationCode.RENAME_002, queryName,
                    "rename removed — nothing references the alias " + alias,
                    r.location());
            return r.input();
        }
        return r;
    }

    /**
     * Whether {@code ρ alias (input)} can be dropped: neither the alias itself nor any
     * relation name the input would re-expose as column provenance is used as a
     * qualifier anywhere in the query.
     */
    private static boolean removable(String alias, RelNode input, Set<String> referenced) {
        if (referenced.contains(lower(alias))) {
            return false;
        }
        var exposed = new HashSet<String>();
        collectExposedNames(input, exposed);
        return exposed.stream().noneMatch(referenced::contains);
    }

    /**
     * The relation names {@code node}'s subtree could stamp onto its output columns as
     * provenance — every base-relation name and every ρ alias in it.  A superset is
     * fine: it only blocks more removals.
     */
    private static void collectExposedNames(RelNode node, Set<String> into) {
        if (node instanceof RelationNode leaf) {
            into.add(lower(leaf.name()));
        } else if (node instanceof RenameNode r) {
            r.relationName().ifPresent(name -> into.add(lower(name)));
        }
        node.children().forEach(child -> collectExposedNames(child, into));
    }

    // =========================================================================
    // Reference sweep
    // =========================================================================

    /**
     * Every relation qualifier used by an attribute reference anywhere in {@code node},
     * lowercased — or {@link Optional#empty()} when the tree contains a node whose
     * column references this pass cannot enumerate, in which case no rename may be
     * removed.
     */
    private static Optional<Set<String>> referencedQualifiers(RelNode node) {
        var qualifiers = new HashSet<String>();
        return collectQualifiers(node, qualifiers) ? Optional.of(qualifiers) : Optional.empty();
    }

    /** Returns false as soon as an operand-carrying node type is not understood. */
    private static boolean collectQualifiers(RelNode node, Set<String> into) {
        switch (node) {
            case SelectionNode s -> collect(s.predicate(), into);
            case ProjectionNode p -> {
                for (ProjectedAttribute pa : p.attributes()) {
                    collect(pa.expression(), into);
                }
            }
            case ConditionalJoinNode j -> collect(j.condition(), into);
            case AggregationNode a -> {
                if (a.groupingKeys() != null) {
                    for (GroupingKey key : a.groupingKeys()) {
                        collect(key.expression(), into);
                    }
                }
                for (AggregateFunction fn : a.aggregates()) {
                    collect(fn.argument(), into);
                    fn.yieldExpr().ifPresent(y -> collect(y, into));
                }
            }
            case SortNode t -> {
                for (SortSpecification spec : t.sortSpecs()) {
                    collect(spec.expression(), into);
                }
            }
            case UnnestNode u -> addQualifier(u.column(), into);

            // Nodes with no column references of their own.
            case RelationNode _, RenameNode _, LimitNode _, DistinctNode _,
                 NaturalJoinNode _, ProductNode _, UnionNode _, UnionAllNode _,
                 IntersectionNode _, DifferenceNode _, SymmetricDifferenceNode _ -> { }

            // Anything else — analytic, recursive, solver, generator, provenance —
            // carries column references this pass does not enumerate. Refuse rather
            // than derive "unreferenced" from an incomplete sweep.
            default -> {
                return false;
            }
        }
        return node.children().stream().allMatch(child -> collectQualifiers(child, into));
    }

    private static void collect(Predicate predicate, Set<String> into) {
        OperandWalker.walk(predicate,
                attr -> addQualifier(attr.name(), into),
                _ -> { /* a function's own name is not a column reference */ });
    }

    private static void collect(Operand expression, Set<String> into) {
        OperandWalker.walk(expression,
                attr -> addQualifier(attr.name(), into),
                _ -> { /* a function's own name is not a column reference */ });
    }

    private static void addQualifier(String attributeName, Set<String> into) {
        String qualifier = AttributeNames.qualifierOf(attributeName);
        if (qualifier != null) {
            into.add(lower(qualifier));
        }
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}

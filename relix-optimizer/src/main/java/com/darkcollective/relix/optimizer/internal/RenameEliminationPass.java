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
import com.darkcollective.relix.ast.AggregationNode;
import com.darkcollective.relix.ast.internal.AttributeNames;
import com.darkcollective.relix.ast.ConditionalJoinNode;
import com.darkcollective.relix.ast.DifferenceNode;
import com.darkcollective.relix.ast.DistinctNode;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.IntersectionNode;
import com.darkcollective.relix.ast.LimitNode;
import com.darkcollective.relix.ast.NaturalJoinNode;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.internal.OperandWalker;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProductNode;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.RenameNode.RenamePair;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SortNode;
import com.darkcollective.relix.ast.SortSpecification;
import com.darkcollective.relix.ast.SymmetricDifferenceNode;
import com.darkcollective.relix.ast.UnionAllNode;
import com.darkcollective.relix.ast.UnionNode;
import com.darkcollective.relix.ast.UnnestNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Rename elimination ({@code RENAME-001} … {@code RENAME-004}) — the cleanup pass that
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
 *
 *   <dt>{@code RENAME-003} — drop identity pairs</dt>
 *   <dd>{@code ρ id→id, a→q (A)} → {@code ρ a→q (A)}.  Dropping the pairs is
 *       unconditional; dropping the node is {@code RENAME-002}'s decision, since a ρ
 *       whose pairs all cancel may still carry a relation name something resolves
 *       against.  So this rule reduces the node to its relation-only form and hands it
 *       straight to the sweep below.</dd>
 *
 *   <dt>{@code RENAME-004} — compose stacked pair-form renames</dt>
 *   <dd>{@code ρ b→c (ρ a→b (R))} → {@code ρ a→c (R)}.  Where {@code RENAME-001} needs
 *       its outer ρ to be relation-only, this one composes two column renames — and is
 *       unconditional for the same reason, once the merged node keeps whichever relation
 *       name exists.  See {@link #compose} for the one case it declines and for why a
 *       rename <em>cycle</em> needs no arm of its own.</dd>
 * </dl>
 *
 * <p>The column rules run first at each node: composing is what turns a cycle into the
 * identity pairs {@code RENAME-003} drops, and reducing a cancelled ρ to relation-only
 * form is what puts it in front of {@code RENAME-002}.
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
 * <p>The <em>positional</em> form ({@code ρ E (a, b, c)}) is deliberately out of scope
 * for both column rules: it renames by position and is arity-bound, so composing it with
 * a pair form — or with another positional form — is a different question, and one
 * {@code ViewInliner}, the reason this pass exists, never poses.
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, OptimizationContext)} as a static method.
 */
public final class RenameEliminationPass {

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
        if (!(mapped instanceof RenameNode renamed)) {
            return mapped;
        }
        RelNode r = columnRules(renamed, queryName, ctx);
        if (!(r instanceof RenameNode node2) || node2.renamesColumns()) {
            return r;   // dropped outright, or still reassigning columns
        }
        return relationOnlyRules(node2, referenced, queryName, ctx);
    }

    /**
     * {@code RENAME-004} then {@code RENAME-003}: compose with the pair-form ρ beneath,
     * then drop the pairs that rename a column to the name it already has. Composition
     * first, because that is what turns a rename cycle into the identity pairs the
     * second rule knows how to drop.
     *
     * @return the rewritten node — a {@link RenameNode} unless every pair cancelled and
     *         there was no relation name left to keep it alive
     */
    private static RelNode columnRules(RenameNode r, String queryName,
                                       OptimizationContext ctx) {
        RenameNode current = compose(r, queryName, ctx);
        return dropIdentityPairs(current, queryName, ctx);
    }

    /** The relation-only rules, unchanged: {@code RENAME-001} then {@code RENAME-002}. */
    private static RelNode relationOnlyRules(RenameNode node, Set<String> referenced,
                                             String queryName, OptimizationContext ctx) {
        RenameNode r = node;
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

    // =========================================================================
    // RENAME-004 — compose stacked pair-form renames
    // =========================================================================

    /**
     * Collapses {@code ρ [outer] (ρ [inner] (R))} into one pair-form ρ, when both are
     * pair form and the outer renames no column the inner has already consumed.
     *
     * <p>Each inner pair {@code a → b} is chased through the outer's sources: an outer
     * {@code b → c} makes it {@code a → c} and is consumed, otherwise it stands. Outer
     * pairs that matched no inner target rename a pass-through column and are carried
     * over unchanged.
     *
     * <p>The relation name is the outer's where it has one, the inner's otherwise, so
     * nothing a qualified reference could resolve against is lost. Where both carry one,
     * the inner's goes — which is {@code RENAME-001}'s argument exactly: an outer ρ with
     * a relation name re-anchors every column's provenance, so the inner alias was
     * already unresolvable one level up.
     *
     * <p><strong>The declined case is not a formality.</strong> With the inner renaming
     * {@code a → b}, an outer {@code a → c} names a column the inner consumed: today it
     * renames nothing, and a naive composition would produce a ρ carrying both
     * {@code a → b} and {@code a → c}. So the two sources must be disjoint.
     *
     * <p>There is deliberately <em>no</em> rule that cancels {@code a → b, b → a} within
     * one ρ — that is a <em>swap</em> of two column names, not a no-op. A rename cycle
     * written as two stacked ρ composes to an identity pair here, and
     * {@link #dropIdentityPairs} drops it; cancelling inside a single node would delete a
     * real rename.
     */
    private static RenameNode compose(RenameNode outer, String queryName,
                                      OptimizationContext ctx) {
        if (outer.pairs().isEmpty() || !(outer.input() instanceof RenameNode inner)
                || inner.pairs().isEmpty()) {
            return outer;
        }
        Set<String> innerSources = inner.pairs().stream()
                .map(pair -> lower(pair.from())).collect(Collectors.toCollection(HashSet::new));
        if (outer.pairs().stream().anyMatch(pair -> innerSources.contains(lower(pair.from())))) {
            return outer;   // the outer names a column the inner has already renamed away
        }

        var byOuterSource = new LinkedHashMap<String, RenamePair>();
        outer.pairs().forEach(pair -> byOuterSource.put(lower(pair.from()), pair));

        var composed = new ArrayList<RenamePair>(inner.pairs().size() + outer.pairs().size());
        for (RenamePair pair : inner.pairs()) {
            RenamePair chained = byOuterSource.remove(lower(pair.to()));
            composed.add(chained == null ? pair : new RenamePair(pair.from(), chained.to()));
        }
        composed.addAll(byOuterSource.values());

        ctx.record(OptimizationCode.RENAME_004, queryName,
                "stacked column renames composed into one ρ", outer.location());
        return new RenameNode(
                outer.relationName().or(inner::relationName),
                List.of(), composed, inner.input(), outer.location());
    }

    // =========================================================================
    // RENAME-003 — drop identity pairs
    // =========================================================================

    /**
     * Drops every pair that renames a column to the name it already has.
     *
     * <p>Comparison is exact rather than case-insensitive: {@code a → A} changes the
     * spelling a result's heading is printed with, which is an answer, not a detail.
     *
     * <p>Dropping the <em>pairs</em> is unconditional; dropping the <em>node</em> is not,
     * because it may still carry a relation name something resolves a qualified reference
     * against. So a ρ whose pairs all cancel is reduced to its relation-only form and
     * {@code RENAME-002}'s reference sweep — which the caller runs next — decides whether
     * it lives. Only a ρ with no relation name at all has nothing left to be, and the
     * record forbids one, so that node goes.
     */
    private static RelNode dropIdentityPairs(RenameNode r, String queryName,
                                             OptimizationContext ctx) {
        List<RenamePair> kept = r.pairs().stream()
                .filter(pair -> !pair.from().equals(pair.to()))
                .toList();
        if (kept.size() == r.pairs().size()) {
            return r;
        }
        ctx.record(OptimizationCode.RENAME_003, queryName,
                "identity rename pair" + (r.pairs().size() - kept.size() == 1 ? "" : "s")
                        + " dropped — the column already has that name",
                r.location());
        if (!kept.isEmpty() || r.relationName().isPresent()) {
            return new RenameNode(r.relationName(), List.of(), kept, r.input(), r.location());
        }
        return r.input();
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
            case RelationNode ignored -> { }
            case RenameNode ignored -> { }
            case LimitNode ignored -> { }
            case DistinctNode ignored -> { }
            case NaturalJoinNode ignored -> { }
            case ProductNode ignored -> { }
            case UnionNode ignored -> { }
            case UnionAllNode ignored -> { }
            case IntersectionNode ignored -> { }
            case DifferenceNode ignored -> { }
            case SymmetricDifferenceNode ignored -> { }

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
                unused -> { /* a function's own name is not a column reference */ });
    }

    private static void collect(Operand expression, Set<String> into) {
        OperandWalker.walk(expression,
                attr -> addQualifier(attr.name(), into),
                unused -> { /* a function's own name is not a column reference */ });
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

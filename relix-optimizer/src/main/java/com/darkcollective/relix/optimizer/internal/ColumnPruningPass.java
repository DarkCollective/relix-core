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
import com.darkcollective.relix.ast.AntiJoinNode;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ConditionalJoinNode;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.LimitNode;
import com.darkcollective.relix.ast.NaturalJoinNode;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.internal.OperandWalker;
import com.darkcollective.relix.ast.PairwiseUniversalNode;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProductNode;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SemiJoinNode;
import com.darkcollective.relix.ast.SortNode;
import com.darkcollective.relix.ast.SortSpecification;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Schema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Column pruning ({@code PROJ-004}) — narrows the rows flowing out of every base
 * relation to the columns the query actually reads.
 *
 * <p>Where {@code PROJ-001..003} are local π rewrites, this pass is a
 * <strong>top-down "required columns" walk</strong>: each node is told which of its
 * output columns its parent needs, derives what it therefore needs from each child,
 * and hands that down.  At a base-relation leaf the accumulated requirement is
 * compared against the leaf's schema, and a narrowing projection is inserted when
 * the query reads strictly fewer columns than the table has:
 *
 * <pre>{@code
 *   γ region, SUM(amount) → total (Sales)     -- Sales(rep, region, amount, note)
 *     →  γ region, SUM(amount) → total (π region, amount (Sales))
 * }</pre>
 *
 * <p>A projection is <em>also</em> narrowed in place when its parent reads only
 * some of the columns it produces, so the requirement that reaches the leaf is as
 * small as the query allows.
 *
 * <h2>Why it is worth a pass of its own</h2>
 * <ul>
 *   <li>{@code SqlPushdownPlanner} builds its {@code SELECT} list from the schema of
 *       a bare scan, so a narrower leaf is fewer columns over the wire, not merely
 *       fewer cycles in the engine.</li>
 *   <li>A hash join materializes its build side; narrower rows mean a smaller
 *       build.</li>
 *   <li>{@code Sort}, {@code Aggregate}, {@code Window}, {@code FULL OUTER},
 *       {@code UNION} and {@code Division} all buffer rows, and width multiplies
 *       straight through every one of them.</li>
 * </ul>
 *
 * <h2>Required-column derivation</h2>
 * <p>The requirement is either <em>unconstrained</em> ({@link Optional#empty()} —
 * "every column this node produces may be read") or a set of unqualified,
 * lowercased column names.  The root starts unconstrained; a node that cannot
 * <em>prove</em> a narrower requirement for a child hands that child an
 * unconstrained one, which stops pruning at that edge but never below it — a π
 * further down still starts a fresh requirement of its own.
 *
 * <table border="1">
 *   <caption>Per-node derivation</caption>
 *   <tr><th>Node</th><th>What each child is told it must produce</th></tr>
 *   <tr><td>π</td><td>the attribute expressions' own column references</td></tr>
 *   <tr><td>σ</td><td>parent's requirement ∪ the predicate's columns</td></tr>
 *   <tr><td>τ</td><td>parent's requirement ∪ the sort keys' columns</td></tr>
 *   <tr><td>λ</td><td>parent's requirement, unchanged</td></tr>
 *   <tr><td>ρ (pair form)</td><td>parent's requirement mapped back through the
 *       renames, ∪ every renamed source column</td></tr>
 *   <tr><td>γ</td><td>the grouping keys' ∪ the aggregate arguments' columns —
 *       independent of what the parent reads</td></tr>
 *   <tr><td>× ⋈ and the conditional joins</td><td>(parent's requirement ∪ the join
 *       condition's columns) restricted to that side's schema; ⋈ additionally keeps
 *       every common column, because those <em>are</em> its join keys</td></tr>
 *   <tr><td>⋉ ▷</td><td>as above on the left; the right side needs only the join
 *       condition's columns, since a semi/anti join emits no right column</td></tr>
 * </table>
 *
 * <h2>What deliberately does not prune</h2>
 * <ul>
 *   <li><b>δ and the deduplicating set operations</b> ({@code ∪ ∩ − ∆}) — they
 *       compare <em>whole rows</em>, so {@code π a (δ R) ≠ δ (π a R)}: dropping a
 *       column before the dedup merges rows that were distinct.  Same argument for
 *       {@code ÷}, {@code ∘} and {@code ⊔}, whose semantics are defined in terms of
 *       the operand headings.</li>
 *   <li><b>{@code ⊎}</b> — safe in principle, but its branches are matched
 *       positionally, and pruning each branch independently could align them
 *       differently.  Deferred rather than risked.</li>
 *   <li><b>The positional form of ρ</b> ({@code ρ E (a, b, c)}) — it is arity-bound,
 *       so narrowing its input would break the rename.</li>
 *   <li><b>μ, and every analytic / recursive / solver operator</b> — they are
 *       reached through the default arm, which hands children an unconstrained
 *       requirement.</li>
 *   <li><b>An open or unresolved leaf schema</b> (schema-on-read sources, ADR-0001;
 *       the {@code *:ANY} placeholder) — you cannot prune what you cannot
 *       enumerate.</li>
 * </ul>
 *
 * <p>The pass runs <strong>last</strong>, after every pattern-matching phase has
 * settled.  It has to: an inserted π between a σ and its leaf would hide the shape
 * {@code GEN-001}, {@code CLOSURE-001} and their family match on, and running it
 * before {@link ProjectionPass} would let {@code PROJ-002} merge a freshly inserted
 * leaf projection straight back into the one above it.
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, SchemaAnnotations, OptimizationContext)}
 * as a static method.
 */
public final class ColumnPruningPass {

    private ColumnPruningPass() {}

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Prunes columns across the whole tree rooted at {@code node}.
     *
     * @param node      root of the tree to transform; must not be null
     * @param queryName display name used in transformation records
     * @param schemas   schema annotations from semantic analysis; leaves without an
     *                  annotation are left alone
     * @param ctx       transformation record accumulator
     * @return the transformed tree, or {@code node} unchanged when nothing pruned
     */
    static RelNode apply(RelNode node, String queryName,
                         SchemaAnnotations schemas, OptimizationContext ctx) {
        return prune(node, unconstrained(), queryName, schemas, ctx);
    }

    // =========================================================================
    // Top-down traversal
    // =========================================================================

    /**
     * Rewrites {@code node} knowing that its parent reads only {@code need} of its
     * output columns ({@link Optional#empty()} = all of them).
     */
    private static RelNode prune(RelNode node, Optional<Set<String>> need,
                                 String queryName, SchemaAnnotations schemas,
                                 OptimizationContext ctx) {
        return switch (node) {

            // ── π: narrow in place, then require only what it reads ───────────
            case ProjectionNode p -> {
                ProjectionNode narrowed = narrow(p, need, queryName, ctx);
                Set<String> below = columnsOf(narrowed.attributes());
                // A π already sitting directly on a leaf is the narrowest form there
                // is; inserting another below it would only stack two projections.
                RelNode newInput = (narrowed.input() instanceof RelationNode)
                        ? narrowed.input()
                        : prune(narrowed.input(), Optional.of(below), queryName, schemas, ctx);
                yield rebuild(narrowed, newInput);
            }

            // ── σ: parent's need plus whatever the predicate reads ────────────
            case SelectionNode s -> {
                RelNode newInput = prune(s.input(), plus(need, columnsOf(s.predicate())),
                        queryName, schemas, ctx);
                yield (newInput == s.input()) ? s
                        : new SelectionNode(s.predicate(), newInput, s.location());
            }

            // ── τ: parent's need plus the sort keys ───────────────────────────
            case SortNode t -> {
                var keyColumns = new HashSet<String>();
                for (SortSpecification spec : t.sortSpecs()) {
                    collectInto(spec.expression(), keyColumns);
                }
                RelNode newInput = prune(t.input(), plus(need, keyColumns),
                        queryName, schemas, ctx);
                yield (newInput == t.input()) ? t
                        : new SortNode(t.sortSpecs(), newInput, t.location());
            }

            // ── λ: row-count only, columns pass straight through ──────────────
            case LimitNode l -> {
                RelNode newInput = prune(l.input(), need, queryName, schemas, ctx);
                yield (newInput == l.input()) ? l
                        : new LimitNode(l.offset(), l.count(), newInput, l.location());
            }

            // ── ρ: map the need back through the rename ───────────────────────
            case RenameNode r -> {
                RelNode newInput = prune(r.input(), needBelowRename(r, need, schemas),
                        queryName, schemas, ctx);
                yield (newInput == r.input()) ? r : r.withInput(newInput);
            }

            // ── γ: keys + aggregate arguments, regardless of what is read above ─
            case AggregationNode a -> {
                RelNode newInput = prune(a.input(), Optional.of(aggregationNeed(a)),
                        queryName, schemas, ctx);
                yield (newInput == a.input()) ? a
                        : new AggregationNode(a.groupingKeys(), a.aggregates(), newInput,
                                a.location());
            }

            // ── ×: no condition, so simply split the need by side ─────────────
            case ProductNode x -> {
                Optional<Schema> ls = schemas.get(x.left());
                Optional<Schema> rs = schemas.get(x.right());
                if (ls.isEmpty() || rs.isEmpty()) {
                    yield recurseUnconstrained(x, queryName, schemas, ctx);
                }
                RelNode newLeft  = prune(x.left(),  restrict(need, ls.get()), queryName, schemas, ctx);
                RelNode newRight = prune(x.right(), restrict(need, rs.get()), queryName, schemas, ctx);
                yield (newLeft == x.left() && newRight == x.right()) ? x
                        : new ProductNode(newLeft, newRight, x.location());
            }

            // ── ⋈: split by side, but never drop a common (join key) column ───
            case NaturalJoinNode j -> {
                Optional<Schema> ls = schemas.get(j.left());
                Optional<Schema> rs = schemas.get(j.right());
                if (ls.isEmpty() || rs.isEmpty()) {
                    yield recurseUnconstrained(j, queryName, schemas, ctx);
                }
                // The join keys are the schemas' common columns — prune one away and
                // the operator would join on a different (or empty) key set.
                Set<String> common = intersection(names(ls.get()), names(rs.get()));
                RelNode newLeft  = prune(j.left(),
                        restrict(plus(need, common), ls.get()), queryName, schemas, ctx);
                RelNode newRight = prune(j.right(),
                        restrict(plus(need, common), rs.get()), queryName, schemas, ctx);
                yield (newLeft == j.left() && newRight == j.right()) ? j
                        : new NaturalJoinNode(newLeft, newRight, j.location());
            }

            // ── θ / outer / semi / anti: need ∪ condition, split by side ──────
            case ConditionalJoinNode j -> pruneConditionalJoin(j, need, queryName, schemas, ctx);

            // ── A base relation: the one place a projection is inserted ───────
            case RelationNode leaf -> pruneLeaf(leaf, need, queryName, schemas, ctx);

            // ── Everything else: recurse, but promise the children nothing ────
            default -> recurseUnconstrained(node, queryName, schemas, ctx);
        };
    }

    /** Recurses into every child with an unconstrained requirement. */
    private static RelNode recurseUnconstrained(RelNode node, String queryName,
                                                SchemaAnnotations schemas,
                                                OptimizationContext ctx) {
        return node.mapChildren(child -> prune(child, unconstrained(), queryName, schemas, ctx));
    }

    // =========================================================================
    // Joins
    // =========================================================================

    /**
     * Prunes the two inputs of a {@link ConditionalJoinNode}.  A semi- or anti-join
     * emits no right-hand column, so its right input needs only the columns the join
     * condition reads; every other member emits both sides, so both get the parent's
     * requirement plus the condition.
     *
     * <p>{@link PairwiseUniversalNode} is excluded — its result is defined by a
     * per-group "for all" over the right input rather than by row matching, so it is
     * left to the conservative unconstrained recursion.
     */
    private static RelNode pruneConditionalJoin(ConditionalJoinNode j,
                                                Optional<Set<String>> need,
                                                String queryName,
                                                SchemaAnnotations schemas,
                                                OptimizationContext ctx) {
        if (j instanceof PairwiseUniversalNode) {
            return recurseUnconstrained(j, queryName, schemas, ctx);
        }
        Optional<Schema> ls = schemas.get(j.left());
        Optional<Schema> rs = schemas.get(j.right());
        if (ls.isEmpty() || rs.isEmpty()) {
            return recurseUnconstrained(j, queryName, schemas, ctx);
        }
        Set<String> condition = columnsOf(j.condition());
        boolean leftOnlyOutput = j instanceof SemiJoinNode || j instanceof AntiJoinNode;

        Optional<Set<String>> leftNeed  = restrict(plus(need, condition), ls.get());
        Optional<Set<String>> rightNeed = leftOnlyOutput
                ? restrict(Optional.of(condition), rs.get())
                : restrict(plus(need, condition), rs.get());

        RelNode newLeft  = prune(j.left(),  leftNeed,  queryName, schemas, ctx);
        RelNode newRight = prune(j.right(), rightNeed, queryName, schemas, ctx);
        return (newLeft == j.left() && newRight == j.right()) ? j
                : j.rebuild(newLeft, newRight, j.condition());
    }

    // =========================================================================
    // Leaf pruning — the rewrite itself
    // =========================================================================

    /**
     * Wraps {@code leaf} in a narrowing π when the query provably reads fewer than
     * all of its columns.  Returns {@code leaf} unchanged when:
     * <ul>
     *   <li>the requirement is unconstrained or empty (nothing to project, and a
     *       zero-column projection is not a thing);</li>
     *   <li>the leaf has no schema annotation, or its schema is open / empty / the
     *       {@code *:ANY} unresolved placeholder;</li>
     *   <li>a required name is absent from the schema — the derivation and the
     *       schema disagree, so pruning is not provably safe;</li>
     *   <li>the requirement already covers every column, so the π would be a no-op
     *       that {@code PROJ-001} would only have to remove again.</li>
     * </ul>
     * The projected columns are emitted in <em>schema order</em>, so the rewrite is
     * deterministic and the surviving prefix of the table keeps its natural shape.
     */
    private static RelNode pruneLeaf(RelationNode leaf, Optional<Set<String>> need,
                                     String queryName, SchemaAnnotations schemas,
                                     OptimizationContext ctx) {
        if (need.isEmpty() || need.get().isEmpty()) return leaf;
        Optional<Schema> schemaOpt = schemas.get(leaf);
        if (schemaOpt.isEmpty()) return leaf;
        Schema schema = schemaOpt.get();
        if (!enumerable(schema)) return leaf;

        Set<String> required = need.get();
        if (!names(schema).containsAll(required)) return leaf;

        List<ColumnDefinition> kept = schema.columns().stream()
                .filter(c -> required.contains(lower(c.name())))
                .toList();
        if (kept.size() >= schema.width()) return leaf;

        ctx.record(OptimizationCode.PROJ_004, queryName,
                "columns pruned at " + leaf.name() + ": " + kept.size() + " of "
                        + schema.width() + " read",
                leaf.location());
        List<ProjectedAttribute> attrs = kept.stream()
                .map(c -> ProjectedAttribute.simple(new AttributeOperand(c.name())))
                .toList();
        return new ProjectionNode(attrs, leaf, leaf.location());
    }

    /**
     * Whether {@code schema}'s columns can be enumerated — false for an open
     * (schema-on-read) schema, the empty schema, and the {@code *:ANY} placeholder
     * semantic analysis leaves behind when it could not resolve a relation.
     *
     * <p>There is no separate width test: {@code Schema.isEmpty} is already
     * "closed and no columns", and an open schema has left by the first test, so a
     * {@code width() == 0} disjunct after those two can never be the one that decides.
     */
    private static boolean enumerable(Schema schema) {
        if (schema.isOpen() || schema.isEmpty()) return false;
        return schema.columns().stream().noneMatch(c -> "*".equals(c.name()));
    }

    // =========================================================================
    // Projection narrowing
    // =========================================================================

    /**
     * Drops the attributes of {@code p} whose output column the parent does not
     * read.  Only an attribute with a determinable output name — an alias, or a bare
     * column reference — can be tested, so a computed attribute without an alias is
     * always kept.  Returns {@code p} unchanged when nothing can be dropped, or when
     * dropping would leave the projection empty.
     */
    private static ProjectionNode narrow(ProjectionNode p, Optional<Set<String>> need,
                                         String queryName, OptimizationContext ctx) {
        if (need.isEmpty()) return p;
        Set<String> required = need.get();

        var kept = new ArrayList<ProjectedAttribute>(p.attributes().size());
        for (ProjectedAttribute pa : p.attributes()) {
            Optional<String> outputName = outputName(pa);
            if (outputName.isEmpty() || required.contains(outputName.get())) {
                kept.add(pa);
            }
        }
        if (kept.isEmpty() || kept.size() == p.attributes().size()) return p;

        ctx.record(OptimizationCode.PROJ_004, queryName,
                "projection narrowed to the " + kept.size() + " of " + p.attributes().size()
                        + " column(s) read above it",
                p.location());
        return new ProjectionNode(kept, p.input(), p.location());
    }

    /** The lowercased output column name of {@code pa}, when it has a determinable one. */
    private static Optional<String> outputName(ProjectedAttribute pa) {
        if (pa.alias().isPresent()) return Optional.of(lower(pa.alias().get()));
        if (pa.expression() instanceof AttributeOperand ao) {
            return Optional.of(lower(PredicateAttributeCollector.columnPart(ao.name())));
        }
        return Optional.empty();
    }

    private static ProjectionNode rebuild(ProjectionNode p, RelNode newInput) {
        return (newInput == p.input()) ? p
                : new ProjectionNode(p.attributes(), newInput, p.location());
    }

    // =========================================================================
    // Per-node requirement derivation
    // =========================================================================

    /**
     * The columns a γ reads: every grouping-key expression's and every aggregate
     * argument's (and yield expression's) references.  Independent of what is read
     * above it — a γ's output columns are computed, not passed through.
     */
    private static Set<String> aggregationNeed(AggregationNode a) {
        var columns = new HashSet<String>();
        // AggregationNode is the one node whose grouping-key list may be null (ungrouped).
        if (a.groupingKeys() != null) {
            for (GroupingKey key : a.groupingKeys()) {
                collectInto(key.expression(), columns);
            }
        }
        for (AggregateFunction fn : a.aggregates()) {
            collectInto(fn.argument(), columns);
            fn.yieldExpr().ifPresent(y -> collectInto(y, columns));
        }
        return columns;
    }

    /**
     * The requirement to hand a ρ's input.  The <em>pair</em> form
     * ({@code old → new}) maps each required output name back to its source column
     * and additionally keeps every renamed source column, since a rename of a column
     * that is no longer there would fail.  The <em>positional</em> form is
     * arity-bound, so its input is left unconstrained; a relation-only ρ passes the
     * requirement through untouched.
     */
    private static Optional<Set<String>> needBelowRename(RenameNode r,
                                                         Optional<Set<String>> need,
                                                         SchemaAnnotations schemas) {
        if (!r.renamesColumns()) return need;              // relation-only ρ
        if (!r.attributes().isEmpty()) return unconstrained();  // positional: arity-bound
        if (need.isEmpty()) return unconstrained();

        Optional<Schema> inputSchema = schemas.get(r.input());
        if (inputSchema.isEmpty()) return unconstrained();

        var below = new HashSet<String>();
        for (RenameNode.RenamePair pair : r.pairs()) {
            below.add(lower(pair.from()));                 // the rename needs its source
        }
        var renamedTo = new HashSet<String>();
        r.pairs().forEach(pair -> renamedTo.add(lower(pair.to())));
        for (String required : need.get()) {
            // A required output name that no pair produced is an untouched column,
            // and passes through under its own name.
            if (!renamedTo.contains(required)) below.add(required);
        }
        return Optional.of(below);
    }

    // =========================================================================
    // Requirement-set plumbing
    // =========================================================================

    /** The unconstrained requirement — "every column this node produces may be read". */
    private static Optional<Set<String>> unconstrained() {
        return Optional.empty();
    }

    /** {@code need ∪ extra}, staying unconstrained when {@code need} is. */
    private static Optional<Set<String>> plus(Optional<Set<String>> need, Set<String> extra) {
        if (need.isEmpty()) return unconstrained();
        var union = new HashSet<>(need.get());
        union.addAll(extra);
        return Optional.of(union);
    }

    /**
     * {@code need} restricted to the columns {@code schema} actually has — the split
     * of a join's requirement across its two sides.  An unconstrained requirement
     * stays unconstrained.
     */
    private static Optional<Set<String>> restrict(Optional<Set<String>> need, Schema schema) {
        if (need.isEmpty()) return unconstrained();
        if (!enumerable(schema)) return unconstrained();
        return Optional.of(intersection(need.get(), names(schema)));
    }

    private static Set<String> intersection(Set<String> a, Set<String> b) {
        var result = new HashSet<>(a);
        result.retainAll(b);
        return result;
    }

    /** The lowercased column names of {@code schema}. */
    private static Set<String> names(Schema schema) {
        var result = new HashSet<String>(schema.width());
        schema.columns().forEach(c -> result.add(lower(c.name())));
        return result;
    }

    // =========================================================================
    // Column reference collection
    // =========================================================================

    /** The unqualified, lowercased column names {@code predicate} reads. */
    private static Set<String> columnsOf(Predicate predicate) {
        var columns = new HashSet<String>();
        OperandWalker.walk(predicate,
                attr -> columns.add(lower(PredicateAttributeCollector.columnPart(attr.name()))),
                fn -> { /* a function call's own name is not a column */ });
        return columns;
    }

    /** The unqualified, lowercased column names a projection's expressions read. */
    private static Set<String> columnsOf(List<ProjectedAttribute> attrs) {
        var columns = new HashSet<String>();
        for (ProjectedAttribute pa : attrs) {
            collectInto(pa.expression(), columns);
        }
        return columns;
    }

    private static void collectInto(Operand expression, Set<String> columns) {
        OperandWalker.walk(expression,
                attr -> columns.add(lower(PredicateAttributeCollector.columnPart(attr.name()))),
                fn -> { /* a function call's own name is not a column */ });
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}

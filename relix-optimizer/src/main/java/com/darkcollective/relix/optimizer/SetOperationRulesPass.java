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

import com.darkcollective.relix.ast.AstEquivalence;
import com.darkcollective.relix.ast.DifferenceNode;
import com.darkcollective.relix.ast.DistinctNode;
import com.darkcollective.relix.ast.EmptyRelationNode;
import com.darkcollective.relix.ast.IntersectionNode;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProductNode;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.SymmetricDifferenceNode;
import com.darkcollective.relix.ast.UnionAllNode;
import com.darkcollective.relix.ast.UnionNode;
import com.darkcollective.relix.cost.PropertyDeriver;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Schema;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * The rules that read the <em>two sides of a set operation together</em> — the family
 * nothing in the optimizer looked at before, although {@code AstEquivalence} had been
 * comparing sub-expressions for conjunct de-duplication and for plan-time CSE all along
 * ({@code SET-001} … {@code SET-005}).
 *
 * <h2>Rules</h2>
 * <pre>{@code
 *   SET-001   R ∪ R,  R ∩ R                 →  δ R
 *             R − R,  R ∆ R                 →  ∅
 *   SET-002   σk(R) ∪ R                     →  δ R
 *             σk(R) ∩ R                     →  δ σk(R)
 *             σk(R) − R                     →  ∅
 *             R − σk(R),  σk(R) ∆ R         →  δ σ (¬k ∨ k IS UNKNOWN) (R)
 *   SET-003   ρ s (R) ⊕ ρ s (Q)             →  ρ s (R ⊕ Q)        ⊕ ∈ {∪ ∩ − ∆ ⊎}
 *   SET-004   π c (A) ∪ π c (B)             →  δ π c (A ∪ B)
 *   SET-005   A × B ∪ A × C                 →  δ (A × (B ∪ C))
 *             A × C ∪ B × C                 →  δ ((A ∪ B) × C)
 * }</pre>
 *
 * <h2>What CSE already buys, and what it does not</h2>
 * <p>{@code SharedSubexpressions} keys on {@code AstEquivalence.digest}, so {@code R ∪ R}
 * already <em>plans</em> {@code R} once under a {@code Spool} and reads it twice. That is
 * the expensive half. What survives is the set operation itself: {@code ∪} and {@code ∩}
 * each buffer a hash set of one side and probe it with the other, so {@code R ∪ R} builds
 * a whole-relation hash table to discover it already had every row. These rules make that
 * vanish rather than make it cheaper — and once one fires the spool goes too, there being
 * one reader left.
 *
 * <h2>The δ is the soundness argument, not decoration</h2>
 * <p>{@code ∪}, {@code ∩} and {@code −} all declare
 * {@link com.darkcollective.relix.ast.MaterializationMode#SET}, and {@code PropertyDeriver}
 * reports their output duplicate-free on the strength of that declaration — which
 * {@code DIST-001} reads, and a δ it removes does not come back. So a rewrite that hands
 * back an expression the input need not have de-duplicated must say so with a δ. That is
 * the same argument {@code EMPTY} records for excluding {@code X − ∅ → X}, and it is the
 * one thing a set-only implementation of this family does not have to make. The δ is
 * dropped where {@code PropertyDeriver} proves the replacement duplicate-free, which is
 * the better outcome and not always available; either way the set operation is gone.
 *
 * <p>{@code SET-003} is the exception, and for a reason worth stating: a rename changes no
 * value and drops no column, and the whole-row set operations match <em>positionally and
 * name-blind</em>, so the rows being de-duplicated are the same set before and after.
 * Hoisting a ρ moves no de-duplication anywhere. Hoisting a π or a × does.
 *
 * <h2>Gates</h2>
 * <ul>
 *   <li><b>Reproducibility</b>, for {@code SET-001}, {@code SET-002} and {@code SET-005}:
 *       each collapses two evaluations of one expression into one, and {@code X ∆ X} over
 *       an unseeded {@code SAMPLE} is a query <em>about</em> two draws. Asked of
 *       {@link DeterminismSource} on the context, which is
 *       {@code RelationDeterminism} bound to the query's symbol table — the same
 *       predicate {@code SharedSubexpressions} gates spooling on. {@code SET-003} and
 *       {@code SET-004} need no gate: both branches are still evaluated exactly once.</li>
 *   <li><b>{@code ⊔} is excluded</b> throughout — its branches have different headings by
 *       construction — and {@code ⊎} appears only under {@code SET-003}, bag union being
 *       an operator whose multiplicities are the answer.</li>
 *   <li><b>{@code R − σk(R)} is the selection's complement, not {@code σ¬k(R)}.</b> A
 *       row whose {@code k} is UNKNOWN is absent from {@code σk(R)}, so the difference
 *       keeps it, while {@code ¬UNKNOWN} is UNKNOWN and {@code σ¬k} drops it —
 *       see {@link SelectionComplement}. {@code σk(R) ∆ R} is that difference written
 *       another way and takes the same predicate, in either operand order.</li>
 *   <li><b>The self-join arm is excluded</b>, though the family it was proposed with
 *       reaches it. {@code R ⋈ R → R} fails twice over here: a natural join's keys are
 *       every column, and the engine's joins skip NULL keys
 *       ({@code ExecSupport.filterNullKeys}), so a row with a NULL anywhere does not
 *       match itself and is dropped; and a row present twice matches itself four ways.
 *       Nullability is not a property a {@link Schema} carries, so there is nothing to
 *       gate on. The outer arms inherit the first failure — {@code R ⟕ R} NULL-pads the
 *       row it could not match — and the theta arm inherits it through {@code R.x = R.x}.
 *       Left out rather than got wrong.</li>
 * </ul>
 *
 * <h2>Why {@code SET-004} asks for a heading and the others do not</h2>
 * <p>A set operation pairs its branches' columns <em>by position</em> while a π names
 * them. {@code π x (A) ∪ π x (B)} is well defined for {@code A = (x, y)} and
 * {@code B = (y, x)}; {@code π x (A ∪ B)} reads the union's heading, which is
 * <em>{@code A}'s</em>, so it would project {@code B}'s {@code y}. The rule therefore
 * fires only when both inputs carry an inferred, closed heading and the two agree name
 * for name in order. {@code SET-003} and {@code SET-005} need no such question:
 * the one preserves arity and column order by construction, and the other's two branches
 * are {@code A ++ B} and {@code A ++ C}, whose {@code B}/{@code C} portions the outer ∪
 * has already paired position for position.
 *
 * <h2>{@code SET-005} factors on the same side only</h2>
 * <p>{@code A × B ∪ C × A} has the common operand at opposite ends: the headings are
 * {@code A ++ B} and {@code C ++ A}, so factoring {@code A} out would permute the ordered
 * heading that positional ρ and the whole-row set operations read. That is exactly what
 * {@code JOIN-003} was removed for, and it is why this matches on
 * {@code left ≡ left} or {@code right ≡ right} and on nothing else.
 *
 * <p>The pass is <em>bottom-up</em>: each input is rewritten before the rule is attempted
 * at the node above, so a chain such as {@code (R ∪ R) ∪ (R ∪ R)} collapses fully in one
 * traversal.
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, SchemaAnnotations, OptimizationContext)} as a static
 * method.
 */
final class SetOperationRulesPass {

    private SetOperationRulesPass() {}

    /**
     * Applies the set-operation identities (SET-001..005) to the whole tree rooted at
     * {@code node}.
     *
     * @param node      root of the tree to transform; must not be null
     * @param queryName display name used in transformation records
     * @param schemas   schema annotations, read by {@code SET-004} alone
     * @param ctx       transformation record accumulator, and the determinism and
     *                  distinctness lookups the gates consult
     * @return the transformed tree, or {@code node} unchanged when no rule fires
     */
    static RelNode apply(RelNode node, String queryName,
                         SchemaAnnotations schemas, OptimizationContext ctx) {
        return rewriteNode(node, queryName, schemas, ctx);
    }

    // =========================================================================
    // Traversal (bottom-up)
    // =========================================================================

    private static RelNode rewriteNode(RelNode node, String queryName,
                                       SchemaAnnotations schemas, OptimizationContext ctx) {
        RelNode mapped = node.mapChildren(c -> rewriteNode(c, queryName, schemas, ctx));

        RelNode rewritten = identity(mapped, queryName, ctx);
        if (rewritten != null) {
            return rewritten;
        }
        rewritten = absorption(mapped, queryName, ctx);
        if (rewritten != null) {
            return rewritten;
        }
        rewritten = hoistRename(mapped, queryName, ctx);
        if (rewritten != null) {
            return rewritten;
        }
        rewritten = hoistProjection(mapped, queryName, schemas, ctx);
        if (rewritten != null) {
            return rewritten;
        }
        rewritten = hoistProduct(mapped, queryName, ctx);
        return rewritten != null ? rewritten : mapped;
    }

    // =========================================================================
    // SET-001 — the two sides are the same expression
    // =========================================================================

    /** {@return the replacement for an idempotent set operation, or {@code null}} */
    private static RelNode identity(RelNode node, String queryName, OptimizationContext ctx) {
        Sides sides = Sides.of(node);
        if (sides == null || !AstEquivalence.equivalent(sides.left(), sides.right())) {
            return null;
        }
        if (!ctx.determinism().isDeterministic(sides.left())) {
            return null;
        }
        return switch (node) {
            case UnionNode ignored -> record(OptimizationCode.SET_001, queryName, ctx, node,
                    "union of an expression with itself replaced by the expression",
                    deduplicated(sides.left(), node.location(), ctx));
            case IntersectionNode ignored -> record(OptimizationCode.SET_001, queryName, ctx, node,
                    "intersection of an expression with itself replaced by the expression",
                    deduplicated(sides.left(), node.location(), ctx));
            case DifferenceNode ignored -> record(OptimizationCode.SET_001, queryName, ctx, node,
                    "difference of an expression with itself is empty",
                    EmptyRelationNode.of(node));
            case SymmetricDifferenceNode ignored ->
                    record(OptimizationCode.SET_001, queryName, ctx, node,
                            "symmetric difference of an expression with itself is empty",
                            EmptyRelationNode.of(node));
            default -> null;    // ⊎ — a bag union's multiplicities are the answer
        };
    }

    // =========================================================================
    // SET-002 — a selection against its own input
    // =========================================================================

    /** {@return the replacement for a set operation absorbing a σ, or {@code null}} */
    private static RelNode absorption(RelNode node, String queryName, OptimizationContext ctx) {
        Sides sides = Sides.of(node);
        if (sides == null) {
            return null;
        }
        RelNode left  = sides.left();
        RelNode right = sides.right();
        boolean leftFilters  = filters(left, right);
        boolean rightFilters = filters(right, left);
        if (!leftFilters && !rightFilters) {
            return null;
        }
        // The bare side is the one evaluated twice today; either way the whole operation
        // collapses to one evaluation, so the gate is on both.
        if (!ctx.determinism().isDeterministic(left)
                || !ctx.determinism().isDeterministic(right)) {
            return null;
        }
        SourceLocation at = node.location();
        return switch (node) {
            // σk(R) ∪ R  ≡  R ∪ σk(R)  ≡  R — the filter admits nothing new.
            case UnionNode ignored -> record(OptimizationCode.SET_002, queryName, ctx, node,
                    "union with a selection of its own input keeps only the input",
                    deduplicated(leftFilters ? right : left, at, ctx));
            // σk(R) ∩ R  ≡  R ∩ σk(R)  ≡  σk(R) — the filter is the narrower side.
            case IntersectionNode ignored -> record(OptimizationCode.SET_002, queryName, ctx, node,
                    "intersection with a selection of its own input keeps only the selection",
                    deduplicated(leftFilters ? left : right, at, ctx));
            // σk(R) − R is empty; R − σk(R) is the rows the filter did NOT keep.
            case DifferenceNode ignored -> leftFilters
                    ? record(OptimizationCode.SET_002, queryName, ctx, node,
                            "a selection minus its own input is empty",
                            EmptyRelationNode.of(node))
                    : record(OptimizationCode.SET_002, queryName, ctx, node,
                            "an input minus a selection of itself is the complement "
                                    + "of that selection",
                            deduplicated(complementOf(right, left, at), at, ctx));
            // σk(R) ∆ R is (σk(R) − R) ∪ (R − σk(R)) = R − σk(R), the σ being a subset
            // of its own input — so it is the arm above, in either operand order.
            case SymmetricDifferenceNode ignored -> {
                RelNode filter = leftFilters ? left : right;
                RelNode bare   = leftFilters ? right : left;
                yield record(OptimizationCode.SET_002, queryName, ctx, node,
                        "symmetric difference with a selection of its own input is the "
                                + "complement of that selection",
                        deduplicated(complementOf(filter, bare, at), at, ctx));
            }
            default -> null;    // ⊎ is a bag — its multiplicities are the answer
        };
    }

    /**
     * {@code σ (rows filter does not keep) (input)} — the relational complement of a
     * selection within its own input.
     *
     * @param filter a {@link SelectionNode} over an expression equivalent to {@code input}
     * @param input  the bare occurrence, used as the rewrite's input so the tree keeps
     *               the node the query actually wrote
     */
    private static RelNode complementOf(RelNode filter, RelNode input, SourceLocation at) {
        Predicate kept = ((SelectionNode) filter).predicate();
        return new SelectionNode(SelectionComplement.of(kept, at), input, at);
    }

    /** {@return whether {@code maybeFilter} is a σ over an expression equivalent to
     *  {@code other}} */
    private static boolean filters(RelNode maybeFilter, RelNode other) {
        return maybeFilter instanceof SelectionNode s
                && AstEquivalence.equivalent(s.input(), other);
    }

    // =========================================================================
    // SET-003 — a common rename hoisted above the operation
    // =========================================================================

    /** {@return the set operation with a shared ρ lifted above it, or {@code null}} */
    private static RelNode hoistRename(RelNode node, String queryName, OptimizationContext ctx) {
        Sides sides = Sides.of(node);
        if (sides == null && !(node instanceof UnionAllNode)) {
            return null;
        }
        RelNode left  = sides != null ? sides.left()  : ((UnionAllNode) node).left();
        RelNode right = sides != null ? sides.right() : ((UnionAllNode) node).right();
        if (!(left instanceof RenameNode lr) || !(right instanceof RenameNode rr)
                || !sameRenameSpec(lr, rr)) {
            return null;
        }
        RelNode inner = rebuild(node, lr.input(), rr.input());
        return record(OptimizationCode.SET_003, queryName, ctx, node,
                "a rename both branches apply hoisted above the set operation",
                new RenameNode(lr.relationName(), lr.attributes(), lr.pairs(), inner,
                        lr.location()));
    }

    /**
     * Whether two renames say the same thing — the relation name they anchor to and the
     * columns they reassign. Anything less is not a common operator: two different
     * relation names stamp two different provenances, and one hoisted rename can carry
     * only one of them.
     */
    private static boolean sameRenameSpec(RenameNode a, RenameNode b) {
        return a.relationName().equals(b.relationName())
                && a.attributes().equals(b.attributes())
                && a.pairs().equals(b.pairs());
    }

    // =========================================================================
    // SET-004 — a common projection hoisted above a union
    // =========================================================================

    /** {@return the union with a shared π lifted above it, or {@code null}} */
    private static RelNode hoistProjection(RelNode node, String queryName,
                                           SchemaAnnotations schemas, OptimizationContext ctx) {
        if (!(node instanceof UnionNode u)
                || !(u.left() instanceof ProjectionNode lp)
                || !(u.right() instanceof ProjectionNode rp)
                || !sameProjection(lp.attributes(), rp.attributes())
                || !sameHeading(schemas, lp.input(), rp.input())) {
            return null;
        }
        RelNode inner = new UnionNode(lp.input(), rp.input(), u.location());
        RelNode projected = new ProjectionNode(lp.attributes(), inner, lp.location());
        return record(OptimizationCode.SET_004, queryName, ctx, node,
                "a projection both branches apply hoisted above the union",
                deduplicated(projected, u.location(), ctx));
    }

    /** Whether two projection lists select the same expressions under the same names. */
    private static boolean sameProjection(List<ProjectedAttribute> a,
                                          List<ProjectedAttribute> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).alias().equals(b.get(i).alias())
                    || !AstEquivalence.equivalent(a.get(i).expression(), b.get(i).expression())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether both expressions carry an inferred, closed heading naming the same columns
     * in the same order — the condition under which the union's positional pairing and
     * the projection's naming agree. An un-annotated node (one an earlier rewrite
     * produced) or an open, schema-on-read heading declines.
     */
    private static boolean sameHeading(SchemaAnnotations schemas, RelNode a, RelNode b) {
        Optional<Schema> left  = schemas.get(a);
        Optional<Schema> right = schemas.get(b);
        if (left.isEmpty() || right.isEmpty() || left.get().isOpen() || right.get().isOpen()) {
            return false;
        }
        return columnNames(left.get()).equals(columnNames(right.get()));
    }

    private static List<String> columnNames(Schema schema) {
        return schema.columns().stream().map(ColumnDefinition::name).toList();
    }

    // =========================================================================
    // SET-005 — a common product operand hoisted above a union
    // =========================================================================

    /** {@return the union with a shared × operand lifted above it, or {@code null}} */
    private static RelNode hoistProduct(RelNode node, String queryName, OptimizationContext ctx) {
        if (!(node instanceof UnionNode u)
                || !(u.left() instanceof ProductNode lp)
                || !(u.right() instanceof ProductNode rp)) {
            return null;
        }
        SourceLocation at = u.location();
        if (AstEquivalence.equivalent(lp.left(), rp.left())
                && ctx.determinism().isDeterministic(lp.left())) {
            RelNode product = new ProductNode(lp.left(),
                    new UnionNode(lp.right(), rp.right(), at), lp.location());
            return record(OptimizationCode.SET_005, queryName, ctx, node,
                    "a product's common left operand hoisted above the union",
                    deduplicated(product, at, ctx));
        }
        if (AstEquivalence.equivalent(lp.right(), rp.right())
                && ctx.determinism().isDeterministic(lp.right())) {
            RelNode product = new ProductNode(
                    new UnionNode(lp.left(), rp.left(), at), lp.right(), lp.location());
            return record(OptimizationCode.SET_005, queryName, ctx, node,
                    "a product's common right operand hoisted above the union",
                    deduplicated(product, at, ctx));
        }
        // A common operand on OPPOSITE sides is deliberately not matched: factoring it
        // would permute the ordered heading the positional consumers read.
        return null;
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

    /**
     * The two inputs of a de-duplicating binary set operation ({@code ∪}, {@code ∩},
     * {@code −}, {@code ∆}), or {@code null} for anything else — {@code ⊎} and {@code ⊔}
     * included, each excluded for its own reason.
     */
    private record Sides(RelNode left, RelNode right) {
        static Sides of(RelNode node) {
            return switch (node) {
                case UnionNode n -> new Sides(n.left(), n.right());
                case IntersectionNode n -> new Sides(n.left(), n.right());
                case DifferenceNode n -> new Sides(n.left(), n.right());
                case SymmetricDifferenceNode n -> new Sides(n.left(), n.right());
                default -> null;
            };
        }
    }

    /** Rebuilds {@code node}'s own kind of set operation over new inputs. */
    private static RelNode rebuild(RelNode node, RelNode left, RelNode right) {
        BiFunction<RelNode, RelNode, RelNode> of = switch (node) {
            case UnionNode n -> (l, r) -> new UnionNode(l, r, n.location());
            case UnionAllNode n -> (l, r) -> new UnionAllNode(l, r, n.location());
            case IntersectionNode n -> (l, r) -> new IntersectionNode(l, r, n.location());
            case DifferenceNode n -> (l, r) -> new DifferenceNode(l, r, n.location());
            case SymmetricDifferenceNode n ->
                    (l, r) -> new SymmetricDifferenceNode(l, r, n.location());
            default -> throw new IllegalArgumentException(
                    "not a binary set operation: " + node.getClass().getSimpleName());
        };
        return of.apply(left, right);
    }

    /**
     * Wraps {@code node} in a {@code δ} unless it is provably duplicate-free — the claim
     * the set operation being removed used to make on the tree's behalf.
     */
    private static RelNode deduplicated(RelNode node, SourceLocation at,
                                        OptimizationContext ctx) {
        return PropertyDeriver.derive(node, ctx.distinctness()).isDuplicateFree()
                ? node
                : new DistinctNode(node, at);
    }

    /** Records the firing and returns the replacement, so a rule arm reads as one line. */
    private static RelNode record(OptimizationCode code, String queryName,
                                  OptimizationContext ctx, RelNode replaced,
                                  String description, RelNode replacement) {
        ctx.record(code, queryName, description, replaced.location());
        return replacement;
    }
}

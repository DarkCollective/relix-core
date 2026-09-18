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

import com.darkcollective.relix.ast.AttributeNames;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.ConditionalJoinNode;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.Predicates;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.ThetaJoinNode;
import com.darkcollective.relix.ast.visitor.PredicatePrettyPrinter;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.symbol.Schema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Transitive equality propagation ({@code EQ-001}) — the law that turns a one-sided
 * filter into a filter on <em>both</em> sources.
 *
 * <pre>{@code
 *   A.x = B.x  ∧  A.x = 5    ⊨    B.x = 5
 * }</pre>
 *
 * <p>Today {@code σ A.x = 5 (A ⨝ᴀ.ˣ⁼ᴮ.ˣ B)} filters {@code A} and scans {@code B} whole,
 * shipping every {@code B} row to the engine to be discarded by the join.  Deriving
 * {@code B.x = 5} and placing it on {@code B}'s side means — with capability-based
 * pushdown (ADR-0011) — that <strong>both backends filter independently</strong>.  That
 * is the entire point of the rule: it is not fewer cycles, it is less data over the
 * wire.
 *
 * <h2>How the classes are built</h2>
 * <p>At each {@link ThetaJoinNode} the pass gathers every top-level conjoined equality
 * it can see from that vantage point — the join's own condition, the σ-chain
 * <em>above</em> the join, and the σ-chain directly above each <em>input</em> (which is
 * where {@code JOIN-002} will have left a binding by the next sweep).  Column-to-column
 * equalities union their two operands; column-to-literal equalities bind a class.  For
 * every class with a binding, each other member gets that literal, as a {@code σ} placed
 * directly on the side whose schema owns that column.
 *
 * <h2>Column identity is textual, deliberately</h2>
 * <p>A class member is the attribute reference <em>as written</em>, lowercased — so
 * {@code A.x} and a bare {@code x} are different members.  Stripping the qualifier
 * first, as an equivalence over bare column names, would merge {@code A.x} and
 * {@code B.x} into one member and make the motivating law vacuous: the two sides of an
 * equi-join usually share a column <em>name</em>, which is exactly why the qualifier is
 * the only thing distinguishing them.  The cost is a missed derivation when the same
 * column is written qualified in one conjunct and bare in another; the alternative is
 * wrong answers.
 *
 * <p>Which side a derived predicate goes to is resolved by <em>provenance</em>, not by
 * name ({@link JoinSides}): a qualified reference finds its input through the column
 * provenance the executor itself resolves against.  A member either side could own is
 * skipped.
 *
 * <h2>Soundness</h2>
 * <ul>
 *   <li><b>Inner joins only.</b>  The pass matches {@link ThetaJoinNode} and nothing
 *       else.  An outer join's condition does not hold of its padded rows, so an
 *       equality read out of {@code ⟕}/{@code ⟖}/{@code ⟗} and pushed into the
 *       null-supplying side would delete rows the join is required to keep.  (Once
 *       {@code JOIN-004} demotes such a join to inner, it becomes eligible here — that
 *       is the intended interaction, and why the demotion runs first.)</li>
 *   <li><b>Conjuncts only.</b>  {@link Predicates#conjuncts} descends {@code ∧} and
 *       treats {@code ∨}/{@code ¬} as opaque leaves, so an equality that holds in only
 *       one branch of a disjunction is never read as a fact.</li>
 *   <li><b>NULLs need no special case here.</b>  Equality is NULL-rejecting, so a row
 *       satisfying both {@code A.x = B.x} and {@code A.x = 5} has neither column NULL;
 *       the derived {@code B.x = 5} discards only rows the join would have discarded.</li>
 * </ul>
 *
 * <h2>Termination</h2>
 * <p>A derived predicate is redundant by construction, so the pass must not keep
 * re-deriving it.  Two things prevent that: the sweep collects existing bindings from
 * the input σ-chains as well (so a derivation made last sweep is seen as a fact this
 * sweep), and before emitting, the target input's whole subtree is checked for a
 * predicate already constraining that column to that literal — which is what stops a
 * re-emission after {@code SEL-004} has carried the derived σ further down, out of the
 * chain directly above the input.
 *
 * <h2>Deliberately not implemented: the column-to-column half</h2>
 * <p>The second law, {@code A.x = B.x ∧ B.x = C.x ⊨ A.x = C.x}, is sound and cheap to
 * derive from the same classes.  It is not emitted because nothing consumes it: the
 * optimizer performs <em>no</em> join reordering at all — join shape is fixed by the
 * query text — so an implied cross-join equality widens no search anything actually
 * performs, and would only add a redundant predicate for the executor to evaluate.
 * (The planner makes its cost decisions — join algorithm, build side — without
 * touching the logical tree, so no rewrite in this space fires.)  Worth revisiting
 * if a cost-based join-order enumerator ever lands.
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, SchemaAnnotations, OptimizationContext)} as a static
 * method.
 */
final class TransitiveEqualityPass {

    private TransitiveEqualityPass() {}

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Propagates literal bindings across equi-join columns throughout the tree rooted
     * at {@code node}.
     *
     * @param node      root of the tree to transform; must not be null
     * @param queryName display name used in transformation records
     * @param schemas   schema annotations; a join whose inputs are unannotated is skipped
     * @param ctx       transformation record accumulator
     * @return the transformed tree, or {@code node} unchanged when nothing was derived
     */
    static RelNode apply(RelNode node, String queryName,
                         SchemaAnnotations schemas, OptimizationContext ctx) {
        return rewrite(node, List.of(), queryName, schemas, ctx);
    }

    // =========================================================================
    // Traversal
    // =========================================================================

    /**
     * Rewrites {@code node}, where {@code fromAbove} are the conjuncts of the σ-chain
     * this node sits directly beneath — the bindings a join can legitimately use before
     * {@code JOIN-002} has pushed them into its inputs.
     */
    private static RelNode rewrite(RelNode node, List<Predicate> fromAbove, String queryName,
                                   SchemaAnnotations schemas, OptimizationContext ctx) {
        if (node instanceof SelectionNode s) {
            var extended = new ArrayList<>(fromAbove);
            extended.addAll(Predicates.conjuncts(s.predicate()));
            RelNode newInput = rewrite(s.input(), extended, queryName, schemas, ctx);
            return (newInput == s.input()) ? s
                    : new SelectionNode(s.predicate(), newInput, s.location());
        }
        if (node instanceof ThetaJoinNode j) {
            RelNode derived = derive(j, fromAbove, queryName, schemas, ctx);
            // Recurse into whatever the derivation produced, with a fresh (empty)
            // set of bindings from above — the conjuncts collected for this join do
            // not hold below its inputs' own filters.
            return derived.mapChildren(child -> rewrite(child, List.of(), queryName, schemas, ctx));
        }
        return node.mapChildren(child -> rewrite(child, List.of(), queryName, schemas, ctx));
    }

    // =========================================================================
    // The derivation
    // =========================================================================

    /**
     * Returns {@code join} with a derived {@code σ} on each side that a class binding
     * reaches, or {@code join} unchanged when nothing new can be derived.
     */
    private static RelNode derive(ThetaJoinNode join, List<Predicate> fromAbove,
                                  String queryName, SchemaAnnotations schemas,
                                  OptimizationContext ctx) {
        Optional<Schema> leftSchema  = schemas.get(join.left());
        Optional<Schema> rightSchema = schemas.get(join.right());
        if (leftSchema.isEmpty() || rightSchema.isEmpty()) {
            return join;
        }

        var atoms = new ArrayList<Predicate>();
        atoms.addAll(Predicates.conjuncts(join.condition()));
        atoms.addAll(fromAbove);
        atoms.addAll(chainConjuncts(join.left()));
        atoms.addAll(chainConjuncts(join.right()));

        var classes = new EqualityClasses();
        for (Predicate atom : atoms) {
            classes.add(atom);
        }

        RelNode newLeft  = join.left();
        RelNode newRight = join.right();
        for (Map.Entry<String, Operand> derived : classes.impliedBindings().entrySet()) {
            String  member  = derived.getKey();
            Operand literal = derived.getValue();

            JoinSides.Side side = JoinSides.sideOf(member, leftSchema.get(), rightSchema.get());
            if (side == JoinSides.Side.UNKNOWN) {
                continue;
            }
            RelNode target = (side == JoinSides.Side.LEFT) ? newLeft : newRight;
            if (alreadyConstrains(target, member, literal)) {
                continue;
            }

            Predicate implied = new ComparisonPredicate(
                    new AttributeOperand(classes.spelling(member)),
                    ComparisonOperator.EQUAL, literal, join.location());
            if (side == JoinSides.Side.LEFT) {
                newLeft = new SelectionNode(implied, newLeft, join.location());
            } else {
                newRight = new SelectionNode(implied, newRight, join.location());
            }
            ctx.record(OptimizationCode.EQ_001, queryName,
                    "equality propagated across the join — σ " + render(implied)
                            + " derived for the " + side.label() + " input",
                    join.location());
        }

        return (newLeft == join.left() && newRight == join.right()) ? join
                : join.rebuild(newLeft, newRight, join.condition());
    }

    // =========================================================================
    // Equivalence classes
    // =========================================================================

    /**
     * Union-find over attribute references (keyed by their lowercased written name),
     * plus the literal each class is pinned to.
     */
    private static final class EqualityClasses {

        private final Map<String, String>  parent   = new LinkedHashMap<>();
        private final Map<String, Operand> literals = new HashMap<>();
        /** key → the reference as the user first spelled it, for the emitted predicate. */
        private final Map<String, String>  spelling = new HashMap<>();

        /** Feeds one conjunct in; anything that is not an equality is ignored. */
        void add(Predicate atom) {
            if (!(atom instanceof ComparisonPredicate c)
                    || c.operator() != ComparisonOperator.EQUAL) {
                return;
            }
            if (c.left() instanceof AttributeOperand a && c.right() instanceof AttributeOperand b) {
                union(remember(a), remember(b));
            } else if (c.left() instanceof AttributeOperand a && Predicates.isLiteral(c.right())) {
                bind(remember(a), c.right());
            } else if (c.right() instanceof AttributeOperand a && Predicates.isLiteral(c.left())) {
                bind(remember(a), c.left());
            }
        }

        /** The reference as first written for {@code key}, so an emitted σ reads naturally. */
        String spelling(String key) {
            return spelling.getOrDefault(key, key);
        }

        private String remember(AttributeOperand attr) {
            String key = key(attr);
            spelling.putIfAbsent(key, attr.name());
            return key;
        }

        /**
         * The bindings implied but not stated: for every class holding a literal, each
         * member that is not itself already bound to one, mapped to that literal.
         *
         * <p>A class pinned to two different literals is a contradiction; the first
         * binding wins.  Propagating it is still sound (the query returns nothing either
         * way), and the contradiction itself is T2.4's business, not this pass's.
         */
        Map<String, Operand> impliedBindings() {
            var byRoot = new LinkedHashMap<String, Set<String>>();
            for (String member : parent.keySet()) {
                byRoot.computeIfAbsent(find(member), unused -> new LinkedHashSet<>()).add(member);
            }
            var implied = new LinkedHashMap<String, Operand>();
            for (Set<String> members : byRoot.values()) {
                Operand literal = members.stream()
                        .map(literals::get)
                        .filter(java.util.Objects::nonNull)
                        .findFirst()
                        .orElse(null);
                if (literal == null) {
                    continue;
                }
                for (String member : members) {
                    if (!literals.containsKey(member)) {
                        implied.put(member, literal);
                    }
                }
            }
            return implied;
        }

        private void bind(String member, Operand literal) {
            find(member);                       // register the member
            literals.putIfAbsent(member, literal);
        }

        private void union(String a, String b) {
            String rootA = find(a);
            String rootB = find(b);
            if (!rootA.equals(rootB)) {
                parent.put(rootA, rootB);
            }
        }

        private String find(String member) {
            parent.putIfAbsent(member, member);
            String current = member;
            while (!parent.get(current).equals(current)) {
                current = parent.get(current);
            }
            // Path compression keeps repeated lookups cheap on a long equality chain.
            String walk = member;
            while (!parent.get(walk).equals(current)) {
                String next = parent.get(walk);
                parent.put(walk, current);
                walk = next;
            }
            return current;
        }

        private static String key(AttributeOperand attr) {
            return attr.name().toLowerCase(Locale.ROOT);
        }
    }

    // =========================================================================
    // Side resolution
    // =========================================================================

    // Which input owns a reference is JoinSides' job — shared with
    // OuterJoinDemotionPass, which asks the same question of a null-supplying side.

    // =========================================================================
    // Existing-constraint detection
    // =========================================================================

    /** The conjuncts of the σ-chain sitting directly above {@code node}'s subtree root. */
    private static List<Predicate> chainConjuncts(RelNode node) {
        var conjuncts = new ArrayList<Predicate>();
        RelNode current = node;
        while (current instanceof SelectionNode s) {
            conjuncts.addAll(Predicates.conjuncts(s.predicate()));
            current = s.input();
        }
        return conjuncts;
    }

    /**
     * Whether {@code subtree} anywhere already pins {@code member}'s column to
     * {@code literal}.  Matching is on the <em>bare</em> column name, so a derived σ
     * that a later sweep pushed below a ρ (losing its qualifier) is still recognised —
     * which is what keeps the pass from emitting the same predicate on every sweep.
     * Over-matching only means a redundant filter is not added.
     */
    private static boolean alreadyConstrains(RelNode subtree, String member, Operand literal) {
        String bare = AttributeNames.stripQualifier(member).toLowerCase(Locale.ROOT);
        return predicatesIn(subtree).stream()
                .flatMap(p -> Predicates.conjuncts(p).stream())
                .anyMatch(c -> pins(c, bare, literal));
    }

    private static boolean pins(Predicate p, String bareColumn, Operand literal) {
        if (!(p instanceof ComparisonPredicate c) || c.operator() != ComparisonOperator.EQUAL) {
            return false;
        }
        return matches(c.left(), c.right(), bareColumn, literal)
                || matches(c.right(), c.left(), bareColumn, literal);
    }

    private static boolean matches(Operand maybeColumn, Operand maybeLiteral,
                                   String bareColumn, Operand literal) {
        return maybeColumn instanceof AttributeOperand a
                && AttributeNames.stripQualifier(a.name()).equalsIgnoreCase(bareColumn)
                && maybeLiteral.equals(literal);
    }

    /** Every predicate carried by a σ or a conditional join anywhere in {@code node}. */
    private static List<Predicate> predicatesIn(RelNode node) {
        var found = new ArrayList<Predicate>();
        collectPredicates(node, found);
        return found;
    }

    private static void collectPredicates(RelNode node, List<Predicate> into) {
        if (node instanceof SelectionNode s) {
            into.add(s.predicate());
        } else if (node instanceof ConditionalJoinNode j) {
            into.add(j.condition());
        }
        node.children().forEach(child -> collectPredicates(child, into));
    }

    private static String render(Predicate p) {
        return p.accept(new PredicatePrettyPrinter());
    }
}

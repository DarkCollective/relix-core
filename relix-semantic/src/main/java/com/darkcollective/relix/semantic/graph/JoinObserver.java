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
package com.darkcollective.relix.semantic.graph;

import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.AsOfJoinNode;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.CompositionNode;
import com.darkcollective.relix.ast.ConditionalJoinNode;
import com.darkcollective.relix.ast.FullOuterJoinNode;
import com.darkcollective.relix.ast.LeftOuterJoinNode;
import com.darkcollective.relix.ast.NaturalJoinNode;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProductNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.RightOuterJoinNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.symbol.Symbol;
import com.darkcollective.relix.symbol.graph.EdgeOrigin;
import com.darkcollective.relix.symbol.graph.Endpoint;
import com.darkcollective.relix.symbol.graph.Relationship;
import com.darkcollective.relix.symbol.graph.SchemaGraph;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.relation.RelationSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Learns provisional {@link EdgeOrigin#LEARNED} schema-graph edges from the
 * joins a user actually writes.
 *
 * <p>Tables often start with no declared foreign keys, but the joins users
 * write surface the relationships anyway. This observer walks query and view
 * trees and captures each <em>cross-side column equality</em> as a candidate
 * relationship the graph does not yet know. The evidence is a property of the
 * join <em>condition</em>, not of the join <em>operator</em> — so a single
 * {@code case ConditionalJoinNode} arm covers all seven uniform joins, and a
 * {@code σ o.cid = c.cid (Orders × Customers)} yields the very same edge as the
 * equivalent θ-join. The observation points are:
 *
 * <ul>
 *   <li>every {@link ConditionalJoinNode} (θ, the three outer joins, semi,
 *       anti, pairwise-∀) — equality conjuncts of its condition split across
 *       the two inputs;</li>
 *   <li>{@link NaturalJoinNode} and {@link CompositionNode} — their matched
 *       columns (the name-intersection of the two input schemas);</li>
 *   <li>{@link AsOfJoinNode} — the <em>equality</em> conjuncts of its condition
 *       (its partition keys); the single ordering inequality is never an
 *       equijoin and is skipped;</li>
 *   <li>a {@link SelectionNode} over a {@link ProductNode} — the equality
 *       conjuncts of the σ predicate split across the ×'s two inputs, the same
 *       assertion a θ-join makes written differently.</li>
 * </ul>
 *
 * <p>A bare {@code ×} carries no evidence, and non-equi conditions (band joins,
 * interval overlap) are not representable as positional equijoin endpoints
 * — both are ignored.
 *
 * <p><b>Endpoint resolution.</b> Both sides of a candidate must bottom out in
 * something carrying a {@link RelationSymbol}: a {@link RelationNode} or a
 * relation-only {@link RenameNode} over one (whose alias is how the join
 * condition qualifies its columns — this is what makes a self-join observable).
 * A join over a filtered, projected, or aggregated intermediate is unobservable
 * until derived endpoints participate in learning, so
 * such a side yields nothing here.
 *
 * <p><b>Bounds.</b> Join type sets the bounds a learned edge carries, never
 * whether it is captured. An outer join is the direct {@code min = 0} signal on
 * its null-supplying side, which coincides with the unconstrained
 * {@code [0..*]} default every other join leaves in place — a join alone never
 * bounds fan-out ({@code max}) or guarantees a match ({@code min ≥ 1}). So every
 * learned edge is {@code [0..*]} on both endpoints; the outer-join distinction
 * is real but structurally the default, and is not fabricated into a bound the
 * evidence does not support (examination against candidate keys, item 5, is what
 * sharpens it).
 *
 * <p><b>De-noising.</b> A candidate that the graph already carries between the
 * same relations on the same columns is dropped regardless of name — a learned
 * edge must not shadow or duplicate a declared one. Candidates observed
 * repeatedly (the same join written across many queries) collapse to one. The
 * caller is responsible for supplying only the trees of <em>successful</em>
 * analyses; nothing is learned from a query that failed to resolve.
 *
 * <p>This class is a pure producer: it reads trees and a graph and returns
 * candidate edges. It never mutates the graph, prompts, or persists — recording
 * policy (confirm-or-record, session persistence) is the REPL's, shared with the
 * conversational acquisition path (item 6).
 */
public final class JoinObserver {

    private final SymbolTable symbols;
    private final SchemaGraph existing;

    /**
     * @param symbols  the symbol table trees resolve against; never null
     * @param existing the graph to de-noise against — a candidate it already
     *                 carries is not re-proposed; never null (use
     *                 {@link SchemaGraph#EMPTY})
     */
    public JoinObserver(SymbolTable symbols, SchemaGraph existing) {
        this.symbols = Objects.requireNonNull(symbols, "symbols");
        this.existing = Objects.requireNonNull(existing, "existing");
    }

    /**
     * Observes learned edges across every user-written tree of a semantic model
     * — the inline expression of each root query plus every view body — de-noised
     * against the model's own graph.
     *
     * @param model an analysed model; only meaningful for a valid analysis, since
     *              nothing worth learning comes from a query that failed to resolve
     * @return the distinct candidate edges, in first-seen order; never null
     */
    public static List<Relationship> observe(SemanticModel model) {
        Objects.requireNonNull(model, "model");
        return new JoinObserver(model.symbolTable(), model.schemaGraph())
                .observeTrees(treesOf(model));
    }

    /**
     * Observes learned edges across the given trees, de-noised against this
     * observer's existing graph and against one another.
     *
     * @param trees the query/view trees to scan; never null
     * @return the distinct candidate edges, in first-seen order; never null
     */
    public List<Relationship> observeTrees(Collection<RelNode> trees) {
        Objects.requireNonNull(trees, "trees");
        Map<String, Relationship> byIdentity = new LinkedHashMap<>();
        for (RelNode tree : trees) {
            if (tree != null) {
                walk(tree, byIdentity);
            }
        }
        return new ArrayList<>(byIdentity.values());
    }

    // =========================================================================
    // Tree walk
    // =========================================================================

    private void walk(RelNode node, Map<String, Relationship> out) {
        observeAt(node).ifPresent(edge -> {
            String id = structuralIdentity(edge);
            if (!knownToGraph(edge) && !out.containsKey(id)) {
                out.put(id, edge);
            }
        });
        for (RelNode child : node.children()) {
            walk(child, out);
        }
    }

    /** Returns the candidate edge this node asserts, if it is an observation point. */
    private Optional<Relationship> observeAt(RelNode node) {
        return switch (node) {
            case ConditionalJoinNode j -> fromCondition(j.left(), j.right(), j.condition());
            case AsOfJoinNode j        -> fromCondition(j.left(), j.right(), j.condition());
            case NaturalJoinNode j     -> fromMatchedColumns(j.left(), j.right());
            case CompositionNode j     -> fromMatchedColumns(j.left(), j.right());
            case SelectionNode s when s.input() instanceof ProductNode p ->
                    fromCondition(p.left(), p.right(), s.predicate());
            default -> Optional.empty();
        };
    }

    // =========================================================================
    // Condition-based observation (θ, outer, semi, anti, ∀, as-of, σ-over-×)
    // =========================================================================

    /**
     * Builds the candidate edge asserted by the cross-side equality conjuncts of
     * {@code condition} over inputs {@code left} and {@code right}.
     */
    private Optional<Relationship> fromCondition(RelNode left, RelNode right, Predicate condition) {
        Optional<Leaf> l = leaf(left);
        Optional<Leaf> r = leaf(right);
        if (l.isEmpty() || r.isEmpty()) {
            return Optional.empty();
        }
        Leaf leftLeaf = l.get();
        Leaf rightLeaf = r.get();

        List<String> leftCols = new ArrayList<>();
        List<String> rightCols = new ArrayList<>();
        for (Predicate conjunct : conjuncts(condition)) {
            crossSidePair(conjunct, leftLeaf, rightLeaf)
                    .ifPresent(pair -> {
                        leftCols.add(pair.leftColumn());
                        rightCols.add(pair.rightColumn());
                    });
        }
        if (leftCols.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(edge(leftLeaf, leftCols, rightLeaf, rightCols));
    }

    /**
     * Resolves one equality conjunct to a cross-side column pair, if it is an
     * {@code =} between an attribute of one input and an attribute of the other.
     * Anything else — an inequality (an as-of ordering key), a literal filter
     * ({@code status = 'active'}), a same-side equality — yields nothing.
     */
    private Optional<CrossPair> crossSidePair(Predicate conjunct, Leaf left, Leaf right) {
        if (!(conjunct instanceof ComparisonPredicate cmp)
                || cmp.operator() != ComparisonOperator.EQUAL
                || !(cmp.left() instanceof AttributeOperand a)
                || !(cmp.right() instanceof AttributeOperand b)) {
            return Optional.empty();
        }
        Side sideA = sideOf(a, left, right);
        Side sideB = sideOf(b, left, right);

        // Cross-side iff one attribute resolves to each input. An attribute whose
        // bare name lives in both schemas is AMBIGUOUS; it is admitted only when
        // the other attribute has already pinned the opposite side.
        if (sideA == Side.LEFT && sideB == Side.RIGHT) {
            return Optional.of(new CrossPair(column(a), column(b)));
        }
        if (sideA == Side.RIGHT && sideB == Side.LEFT) {
            return Optional.of(new CrossPair(column(b), column(a)));
        }
        if (sideA == Side.AMBIGUOUS && sideB == Side.LEFT) {
            return Optional.of(new CrossPair(column(b), column(a)));
        }
        if (sideA == Side.AMBIGUOUS && sideB == Side.RIGHT) {
            return Optional.of(new CrossPair(column(a), column(b)));
        }
        if (sideA == Side.LEFT && sideB == Side.AMBIGUOUS) {
            return Optional.of(new CrossPair(column(a), column(b)));
        }
        if (sideA == Side.RIGHT && sideB == Side.AMBIGUOUS) {
            return Optional.of(new CrossPair(column(b), column(a)));
        }
        return Optional.empty();
    }

    /** Which input an attribute belongs to, by qualifier if present else by schema membership. */
    private Side sideOf(AttributeOperand op, Leaf left, Leaf right) {
        int dot = op.name().lastIndexOf('.');
        if (dot >= 0) {
            String qualifier = op.name().substring(0, dot);
            if (qualifier.equalsIgnoreCase(left.qualifier())) {
                return Side.LEFT;
            }
            if (qualifier.equalsIgnoreCase(right.qualifier())) {
                return Side.RIGHT;
            }
            return Side.NONE;
        }
        String col = op.name();
        boolean inLeft = hasColumn(left.symbol(), col);
        boolean inRight = hasColumn(right.symbol(), col);
        if (inLeft && inRight) {
            return Side.AMBIGUOUS;
        }
        if (inLeft) {
            return Side.LEFT;
        }
        if (inRight) {
            return Side.RIGHT;
        }
        return Side.NONE;
    }

    // =========================================================================
    // Matched-column observation (natural join, composition)
    // =========================================================================

    /** The candidate edge over the name-intersection of two inputs' schemas. */
    private Optional<Relationship> fromMatchedColumns(RelNode left, RelNode right) {
        Optional<Leaf> l = leaf(left);
        Optional<Leaf> r = leaf(right);
        if (l.isEmpty() || r.isEmpty()) {
            return Optional.empty();
        }
        List<String> shared = new ArrayList<>();
        for (var column : l.get().symbol().schema().columns()) {
            if (hasColumn(r.get().symbol(), column.name())) {
                shared.add(column.name());
            }
        }
        if (shared.isEmpty()) {
            return Optional.empty();
        }
        // Matched columns join like-named to like-named on both sides.
        return Optional.of(edge(l.get(), shared, r.get(), shared));
    }

    // =========================================================================
    // Edge assembly + de-noising
    // =========================================================================

    /** Assembles a LEARNED edge with unconstrained bounds on both endpoints. */
    private static Relationship edge(Leaf left, List<String> leftCols,
                                     Leaf right, List<String> rightCols) {
        Endpoint source = Endpoint.unbounded(left.symbol(), List.copyOf(leftCols));
        Endpoint target = Endpoint.unbounded(right.symbol(), List.copyOf(rightCols));
        return new Relationship(defaultName(left.symbol(), leftCols),
                Optional.empty(), false, source, target, EdgeOrigin.LEARNED);
    }

    /** A provisional name a user can later rename: source relation plus its columns. */
    private static String defaultName(RelationSymbol source, List<String> columns) {
        return source.declaredName() + "_" + String.join("_", columns);
    }

    /** {@code true} if the existing graph already carries this edge (by structure, any name). */
    private boolean knownToGraph(Relationship candidate) {
        String id = edgeIdentity(candidate);
        for (Relationship e : existing.edgesOf(candidate.source().relation())) {
            if (edgeIdentity(e).equals(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The name-independent identity of an edge: its two endpoints (relation key
     * plus lower-cased columns), unordered so an edge and its reversal collapse
     * onto one key.
     *
     * <p>This is the key de-noising uses, and the name is deliberately excluded
     * from it: a learned edge's generated name would never match the name a user
     * chose for the same columns, so including it would let a learned edge
     * duplicate a declared one. It is public because a caller holding learned
     * edges needs the same notion of sameness — to rename one, drop one, or
     * remember that a candidate was rejected and must not be re-proposed.
     *
     * @param relationship the edge to key; never null
     * @return its structural identity; never null
     */
    public static String edgeIdentity(Relationship relationship) {
        Objects.requireNonNull(relationship, "relationship");
        return structuralIdentity(relationship);
    }

    /**
     * Structural identity for de-noising: the two endpoints (relation key +
     * lower-cased columns), unordered so a reversed edge collapses — the name is
     * deliberately excluded, since a learned edge's generated name would never
     * match a declared one on the same columns.
     */
    private static String structuralIdentity(Relationship r) {
        String a = endpointIdentity(r.source());
        String b = endpointIdentity(r.target());
        return a.compareTo(b) <= 0 ? a + "<->" + b : b + "<->" + a;
    }

    private static String endpointIdentity(Endpoint e) {
        StringBuilder sb = new StringBuilder(SchemaGraph.key(e.relation()));
        for (String c : e.columns()) {
            sb.append('#').append(c.toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    // =========================================================================
    // Endpoint (leaf) resolution
    // =========================================================================

    /**
     * Resolves a join input to a relation endpoint: a bare {@link RelationNode},
     * or a relation-only {@link RenameNode} over one (whose alias is how the
     * condition qualifies the side — the key to observing a self-join). Any other
     * shape (a filter, projection, aggregation, or nested join) is unobservable.
     */
    private Optional<Leaf> leaf(RelNode node) {
        return switch (node) {
            case RelationNode rn -> symbols.resolveRelation(rn.name())
                    .map(sym -> new Leaf(sym, rn.name()));
            // A relation-only rename (no column renaming) relabels the side without
            // changing its columns, so the base relation's column names still hold.
            case RenameNode rename when !rename.renamesColumns()
                    && rename.relationName().isPresent()
                    && rename.input() instanceof RelationNode rn ->
                    symbols.resolveRelation(rn.name())
                            .map(sym -> new Leaf(sym, rename.relationName().get()));
            default -> Optional.empty();
        };
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Flattens an {@code ∧}-tree into its conjuncts, leaving non-∧ predicates whole. */
    private static List<Predicate> conjuncts(Predicate predicate) {
        List<Predicate> out = new ArrayList<>();
        collectConjuncts(predicate, out);
        return out;
    }

    private static void collectConjuncts(Predicate predicate, List<Predicate> out) {
        if (predicate instanceof AndPredicate and) {
            collectConjuncts(and.left(), out);
            collectConjuncts(and.right(), out);
        } else {
            out.add(predicate);
        }
    }

    private static boolean hasColumn(RelationSymbol relation, String column) {
        return relation.schema().column(column).isPresent();
    }

    private static String column(AttributeOperand op) {
        return op.unqualifiedName();
    }

    /**
     * Every user-written tree of a model: the inline expression of each root
     * query, plus every view body (which is what a root query that merely names a
     * view resolves to anyway). Deduplication downstream makes any overlap
     * harmless.
     */
    private static List<RelNode> treesOf(SemanticModel model) {
        List<RelNode> trees = new ArrayList<>();
        for (QueryStatement query : model.rootQueries()) {
            if (query.target() instanceof ExpressionQueryTarget expr) {
                trees.add(expr.expression());
            }
        }
        for (Symbol symbol : model.symbolTable().allSymbols()) {
            if (symbol instanceof QueryRelationSymbol view) {
                trees.add(view.body());
            }
        }
        return trees;
    }

    /** Which input of a join an attribute resolves to. */
    private enum Side { LEFT, RIGHT, AMBIGUOUS, NONE }

    /** A resolved join input: the relation and the name its columns are qualified by. */
    private record Leaf(RelationSymbol symbol, String qualifier) {}

    /** One cross-side equality: the left input's column paired with the right input's. */
    private record CrossPair(String leftColumn, String rightColumn) {}
}

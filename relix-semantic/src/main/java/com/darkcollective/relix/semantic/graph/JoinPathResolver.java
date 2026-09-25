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
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.ConditionalJoinNode;
import com.darkcollective.relix.ast.NaturalJoinNode;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.ThetaJoinNode;
import com.darkcollective.relix.symbol.graph.JoinResolution.Alternative;
import com.darkcollective.relix.symbol.graph.JoinResolution.Ambiguous;
import com.darkcollective.relix.symbol.graph.JoinResolution.BoundsFacts;
import com.darkcollective.relix.symbol.graph.JoinResolution.Passthrough;
import com.darkcollective.relix.symbol.graph.JoinResolution.Resolved;
import com.darkcollective.relix.symbol.graph.Endpoint;
import com.darkcollective.relix.symbol.graph.internal.JoinPath;
import com.darkcollective.relix.symbol.graph.JoinResolution;
import com.darkcollective.relix.symbol.graph.internal.PathSearchResult;
import com.darkcollective.relix.symbol.graph.Relationship;
import com.darkcollective.relix.symbol.graph.SchemaGraph;
import com.darkcollective.relix.symbol.graph.internal.SchemaGraphSearch;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.relation.RelationSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Assembles the join a request needs from the schema graph instead of trusting
 * the model's guess — the first producer of a {@link JoinResolution}.
 *
 * <p>The model does <em>extraction</em> (which relations/columns/filters the user
 * wants); this does <em>structure</em> (the join over the graph). Given a
 * generated program, it finds the base-relation join at its core (a stack of
 * unary operators over a region of joins over relation leaves), maps the leaves
 * to graph terminals, and searches the graph:
 *
 * <ul>
 *   <li><b>unique</b> minimal path → rewrite the join region with graph-derived
 *       equijoin conditions, correcting a wrong FK guess ({@link Resolved});</li>
 *   <li><b>ambiguous</b> → enumerate the paths by relationship name for a
 *       "did you mean?" ({@link Ambiguous} — the two-FK {@code issues → users}
 *       case);</li>
 *   <li>anything the graph cannot improve → {@link Passthrough}.</li>
 * </ul>
 *
 * <p><b>Safety scope.</b> The rewrite fires only when it is provably semantics-
 * preserving: the join region must be a <em>pure</em> tree of joins over distinct
 * base-relation leaves (no filter/projection buried inside it, no self-join), and
 * the graph path must span <em>exactly</em> that leaf set — so only the join
 * conditions change, never the set of tables or their left-to-right order (which
 * keeps collided-column {@code _r} disambiguation stable). Anything outside that
 * envelope — a missing intermediate table, an aggregate mid-join, a multi-
 * statement program — is passed through untouched. Inserting a missing table and
 * resolving a qualified-column collision are outside its scope.
 *
 * <p><b>Bounds facts</b> ({@link BoundsFacts}) are computed and carried for
 * tracing only; nothing surfaces the fan-out / row-drop they describe to the user.
 */
public final class JoinPathResolver {

    private JoinPathResolver() {
    }

    /**
     * Resolves the join in {@code program} against {@code graph}.
     *
     * @param graph   the schema relationship graph (empty → always passthrough)
     * @param symbols the symbol table the program's relation names resolve against
     * @param program the generated query's root relational-algebra node
     * @return the resolution outcome; never null
     */
    public static JoinResolution resolve(SchemaGraph graph, SymbolTable symbols, RelNode program) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(symbols, "symbols");
        Objects.requireNonNull(program, "program");

        if (graph.isEmpty()) {
            return new Passthrough("no schema graph");
        }

        // Descend the unary operator spine to the top of the join region.
        RelNode region = program;
        while (region.children().size() == 1) {
            region = region.children().get(0);
        }
        if (!isJoinNode(region)) {
            return new Passthrough("no base-relation join to resolve");
        }

        // The region must be a pure tree of joins over relation leaves.
        List<RelationNode> leaves = new ArrayList<>();
        if (!collectPureJoinLeaves(region, leaves)) {
            return new Passthrough("join region is not a pure base-relation join");
        }

        // Map leaves to graph terminals, keyed by graph key; reject a self-join.
        Map<String, RelationNode> nodeByKey = new LinkedHashMap<>();
        List<RelationSymbol> terminals = new ArrayList<>();
        List<RelationSymbol> nominated = new ArrayList<>();
        for (RelationNode leaf : leaves) {
            Optional<RelationSymbol> sym = symbols.lookupRelation(leaf.name());
            if (sym.isEmpty()) {
                return new Passthrough("unresolved relation " + leaf.name());
            }
            String key = SchemaGraph.key(sym.get());
            if (nodeByKey.putIfAbsent(key, leaf) != null) {
                return new Passthrough("self-join is out of scope");
            }
            terminals.add(sym.get());
            if (sym.get() instanceof QueryRelationSymbol) {
                nominated.add(sym.get());
            }
        }
        // A join region always has at least two distinct leaves (a single leaf
        // fails the isJoinNode gate, two identical leaves the self-join gate), so
        // terminals.size() >= 2 holds here.

        PathSearchResult search = SchemaGraphSearch.search(graph, terminals, nominated);
        if (search.paths().isEmpty()) {
            return new Passthrough("no connecting path in the graph");
        }

        if (search.unique()) {
            JoinPath path = search.paths().get(0);
            if (!spansExactly(path, nodeByKey.keySet())) {
                return new Passthrough("the minimal path introduces or omits tables");
            }
            return new Resolved(assemble(program, region, path, nodeByKey), boundsOf(path));
        }

        // Ambiguous: every alternative must be a pure condition swap over the same tables.
        List<Alternative> alternatives = new ArrayList<>();
        for (JoinPath path : search.paths()) {
            if (!spansExactly(path, nodeByKey.keySet())) {
                return new Passthrough("ambiguous paths differ in their tables");
            }
            alternatives.add(new Alternative(label(path), describe(path, nodeByKey),
                    assemble(program, region, path, nodeByKey), boundsOf(path)));
        }
        return new Ambiguous(alternatives);
    }

    // ── join-region analysis ────────────────────────────────────────────────

    private static boolean isJoinNode(RelNode n) {
        return n instanceof ConditionalJoinNode || n instanceof NaturalJoinNode;
    }

    /**
     * Collects the relation leaves of a join region, returning {@code false} if
     * the region contains anything other than join nodes and relation leaves (a
     * buried σ/π/γ makes a structural rewrite unsafe).
     */
    private static boolean collectPureJoinLeaves(RelNode node, List<RelationNode> out) {
        if (node instanceof RelationNode rn) {
            out.add(rn);
            return true;
        }
        if (isJoinNode(node)) {
            for (RelNode child : node.children()) {
                if (!collectPureJoinLeaves(child, out)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean spansExactly(JoinPath path, Set<String> leafKeys) {
        return path.relationKeys().equals(leafKeys);
    }

    // ── assembly ─────────────────────────────────────────────────────────────

    /**
     * Rebuilds the join region as a left-deep chain over the path's edges,
     * preserving the model's leaf order, then splices it back under the unary
     * spine and pretty-prints the result. Total: the caller has verified the path
     * spans exactly these leaves, so a walkable order always exists.
     */
    private static String assemble(RelNode program, RelNode region, JoinPath path,
                                   Map<String, RelationNode> nodeByKey) {
        // nodeByKey is a LinkedHashMap built in leaf order, so its keys are the
        // model's left-to-right leaf order.
        List<String> order = new ArrayList<>(nodeByKey.keySet());
        RelNode joinTree = buildJoinTree(order, path, nodeByKey);
        return replace(program, region, joinTree).prettyPrint();
    }

    /**
     * A left-deep join chain in {@code order}, each new relation attached by the
     * path edge connecting it to the already-included set. A leaf that is not yet
     * adjacent is deferred to a later round; the path's tree connectivity over
     * exactly these leaves guarantees one is always reachable.
     */
    private static RelNode buildJoinTree(List<String> order, JoinPath path,
                                         Map<String, RelationNode> nodeByKey) {
        List<String> remaining = new ArrayList<>(order);
        Set<String> included = new HashSet<>();
        RelNode current = nodeByKey.get(remaining.remove(0));
        included.add(order.get(0));

        while (!remaining.isEmpty()) {
            String next = null;
            Relationship via = null;
            for (String candidate : remaining) {
                Relationship edge = connectingEdge(path, candidate, included);
                if (edge != null) {
                    next = candidate;
                    via = edge;
                    break;
                }
            }
            Predicate condition = buildCondition(via, nodeByKey);
            current = new ThetaJoinNode(current, nodeByKey.get(next), condition);
            included.add(next);
            remaining.remove(next);
        }
        return current;
    }

    /** The path edge joining {@code candidate} to the already-included set, if any. */
    private static Relationship connectingEdge(JoinPath path, String candidate, Set<String> included) {
        for (Relationship edge : path.edges()) {
            String a = SchemaGraph.key(edge.source().relation());
            String b = SchemaGraph.key(edge.target().relation());
            if (a.equals(candidate) && included.contains(b)) {
                return edge;
            }
            if (b.equals(candidate) && included.contains(a)) {
                return edge;
            }
        }
        return null;
    }

    /** The equijoin predicate of an edge: a conjunction of positional column equalities. */
    private static Predicate buildCondition(Relationship edge, Map<String, RelationNode> nodeByKey) {
        Endpoint source = edge.source();
        Endpoint target = edge.target();
        String left = nodeByKey.get(SchemaGraph.key(source.relation())).name();
        String right = nodeByKey.get(SchemaGraph.key(target.relation())).name();
        Predicate condition = null;
        for (int i = 0; i < source.columns().size(); i++) {
            ComparisonPredicate eq = new ComparisonPredicate(
                    new AttributeOperand(left + "." + source.columns().get(i)),
                    ComparisonOperator.EQUAL,
                    new AttributeOperand(right + "." + target.columns().get(i)));
            condition = condition == null ? eq : new AndPredicate(condition, eq);
        }
        return condition;
    }

    /** Replaces {@code target} (by identity) with {@code replacement} everywhere in {@code root}. */
    private static RelNode replace(RelNode root, RelNode target, RelNode replacement) {
        if (root == target) {
            return replacement;
        }
        return root.mapChildren(c -> replace(c, target, replacement));
    }

    // ── labels and bounds ────────────────────────────────────────────────────

    private static String label(JoinPath path) {
        return String.join(" → ", path.relationshipNames());
    }

    private static String describe(JoinPath path, Map<String, RelationNode> nodeByKey) {
        StringJoiner joiner = new StringJoiner("; ");
        for (Relationship edge : path.edges()) {
            String left = nodeByKey.get(SchemaGraph.key(edge.source().relation())).name();
            String right = nodeByKey.get(SchemaGraph.key(edge.target().relation())).name();
            joiner.add(left + "." + String.join(",", edge.source().columns())
                    + " = " + right + "." + String.join(",", edge.target().columns()));
        }
        return joiner.toString();
    }

    /**
     * The multiplicity facts of a path: fan-out if any endpoint fails to guarantee
     * a single match ({@code max} unbounded or {@code > 1}); row-drop if any
     * endpoint admits zero matches ({@code min == 0}). With unconstrained default
     * bounds both are true — the honest "cannot rule out either effect" reading.
     */
    private static BoundsFacts boundsOf(JoinPath path) {
        boolean fanOut = false;
        boolean rowDrop = false;
        for (Relationship edge : path.edges()) {
            for (Endpoint end : List.of(edge.source(), edge.target())) {
                OptionalLong max = end.max();
                if (max.isEmpty() || max.getAsLong() > 1) {
                    fanOut = true;
                }
                if (end.min() == 0) {
                    rowDrop = true;
                }
            }
        }
        return new BoundsFacts(fanOut, rowDrop);
    }
}

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
package com.darkcollective.relix.processor.exec;

import com.darkcollective.relix.ast.ObjectiveSense;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.plan.TraceAlgorithm;
import com.darkcollective.relix.processor.ArrayRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.processor.eval.ValueComparator;
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.Schema;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Executes the recursion operators — transitive CLOSURE and the general FIX least-fixpoint binder
 * with its RecursiveRef.
 *
 * <p>Holds a {@link ChildDispatch} to run input sub-plans.
 */
final class RecursionExecutor {

    private static final System.Logger LOG = System.getLogger(RecursionExecutor.class.getName());

    private final ChildDispatch dispatch;

    RecursionExecutor(ChildDispatch dispatch) {
        this.dispatch = dispatch;
    }

    /**
     * Transitive closure by semi-naïve least-fixpoint iteration over set
     * semantics.  Materialises the input edges once (skipping null endpoints,
     * deduplicating), then repeatedly joins the newly-derived delta against the
     * edge adjacency until no new pair appears — which terminates even on cyclic
     * graphs because a pair already derived is never re-added.  Reflexive closure
     * additionally emits the identity pair for every node.
     *
     * <p>This is the adjacency-specialised instance of the general semi-naïve
     * fixpoint (see {@link com.darkcollective.relix.ast.FixpointNode}).  It avoids
     * a per-round hash join by building an adjacency map once; both forms compute
     * the same set of reachable pairs.
     *
     * <p><strong>Endpoint pushdown (ADR-0020).</strong> When the optimizer
     * has folded a constant endpoint equality into
     * {@link PhysicalNode.Closure#boundSource()} / {@link PhysicalNode.Closure#boundTarget()},
     * the all-pairs fixpoint is replaced by a directed BFS that only computes the
     * asked-for slice:
     * <ul>
     *   <li><em>source bound {@code s}</em> — single-source reachability seeded from
     *       {@code s} over the forward adjacency, emitting {@code (s, r)};</li>
     *   <li><em>target bound {@code t}</em> — single-target reachability seeded from
     *       {@code t} over the <em>reversed</em> adjacency, emitting {@code (r, t)};</li>
     *   <li><em>both</em> — single-source from {@code s} then kept only where the
     *       target equals {@code t} (a single-pair check).</li>
     * </ul>
     * The reflexive variant contributes the matching identity pair only when the
     * bound endpoint is an actual node of the graph, exactly mirroring the
     * all-pairs reflexive rule restricted to the requested slice.
     */
    Stream<Row> executeClosure(PhysicalNode.Closure node, EvalCtx ctx) {
        Schema outputSchema = node.schema();
        String fromCol = node.fromColumn();
        String toCol   = node.toColumn();

        boolean undirected = node.undirected();

        Optional<Value> boundSource = node.boundSource().map(op -> literal(op, ctx));
        Optional<Value> boundTarget = node.boundTarget().map(op -> literal(op, ctx));
        boolean targetOnly = boundSource.isEmpty() && boundTarget.isPresent();

        Set<Pair> edges = new LinkedHashSet<>();
        Map<Value, List<Value>> adjacency = new HashMap<>();
        Map<Value, List<Value>> reverse = targetOnly ? new HashMap<>() : null;
        Set<Value> nodes = new LinkedHashSet<>();
        try (Stream<Row> input = dispatch.buffering(node.input(), ctx, node)) {
            input.forEach(row -> {
                Value a = row.get(fromCol);
                Value b = row.get(toCol);
                if (a.isNull() || b.isNull()) {
                    return;   // a null endpoint is not a graph node
                }
                nodes.add(a);
                nodes.add(b);
                addEdge(edges, adjacency, reverse, a, b);
                if (undirected) {
                    // The reverse of the same row, not a second row: the adjacency is read
                    // both ways from one edge set rather than from a doubled relation.
                    addEdge(edges, adjacency, reverse, b, a);
                }
            });
        }

        Set<Pair> result = (boundSource.isPresent() || boundTarget.isPresent())
                ? boundedClosure(node, boundSource, boundTarget, adjacency, reverse, nodes)
                : allPairsClosure(node, edges, adjacency, nodes);

        List<Row> outputRows = new ArrayList<>(result.size());
        for (Pair p : result) {
            outputRows.add(ArrayRow.of(outputSchema, List.of(p.from(), p.to())));
        }
        return BagRelation.of(outputSchema, outputRows).stream();
    }

    /**
     * Records one directed edge {@code a → b} into the edge set and both adjacency maps,
     * doing nothing if the pair is already known.
     */
    private static void addEdge(Set<Pair> edges, Map<Value, List<Value>> adjacency,
                                Map<Value, List<Value>> reverse, Value a, Value b) {
        if (edges.add(new Pair(a, b))) {
            adjacency.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
            if (reverse != null) {
                reverse.computeIfAbsent(b, k -> new ArrayList<>()).add(a);
            }
        }
    }

    /** The original all-pairs transitive (R⁺) / reflexive-transitive (R*) closure. */
    private static Set<Pair> allPairsClosure(PhysicalNode.Closure node, Set<Pair> edges,
                                             Map<Value, List<Value>> adjacency, Set<Value> nodes) {
        Set<Pair> result = new LinkedHashSet<>(edges);   // base: the 1-step pairs (R⁺)
        Set<Pair> delta  = new LinkedHashSet<>(edges);
        while (!delta.isEmpty()) {
            Set<Pair> next = new LinkedHashSet<>();
            for (Pair p : delta) {
                List<Value> successors = adjacency.get(p.to());
                if (successors == null) {
                    continue;
                }
                for (Value c : successors) {
                    Pair derived = new Pair(p.from(), c);
                    if (!result.contains(derived)) {
                        next.add(derived);
                    }
                }
            }
            result.addAll(next);
            delta = next;
        }

        if (node.reflexive()) {
            for (Value n : nodes) {
                result.add(new Pair(n, n));
            }
        }
        return result;
    }

    /**
     * Single-source / single-target / single-pair reachability for a closure whose
     * endpoint(s) the optimizer fixed to a constant (ADR-0020 endpoint pushdown).
     */
    private static Set<Pair> boundedClosure(PhysicalNode.Closure node,
                                            Optional<Value> boundSource, Optional<Value> boundTarget,
                                            Map<Value, List<Value>> adjacency,
                                            Map<Value, List<Value>> reverse, Set<Value> nodes) {
        Set<Pair> result = new LinkedHashSet<>();
        if (boundSource.isPresent()) {
            for (Value s : seeds(boundSource.get(), nodes)) {
                for (Value r : reachable(s, adjacency)) {
                    result.add(new Pair(s, r));
                }
                if (node.reflexive()) {
                    result.add(new Pair(s, s));
                }
            }
            if (boundTarget.isPresent()) {
                Value t = boundTarget.get();
                result.removeIf(p -> !equalEndpoints(p.to(), t));   // single-pair: keep s ⇝ t
            }
        } else {   // target-only: everything that reaches t, over the reversed adjacency
            for (Value t : seeds(boundTarget.get(), nodes)) {
                for (Value r : reachable(t, reverse)) {
                    result.add(new Pair(r, t));
                }
                if (node.reflexive()) {
                    result.add(new Pair(t, t));
                }
            }
        }
        return result;
    }

    /**
     * The graph's own nodes that the bound literal selects — its <em>values</em>, not the
     * literal.
     *
     * <p>What makes this more than a lookup is the value it emits. The unbounded closure
     * builds its pairs out of row values, so a bounded run that emitted the <em>literal</em>
     * would answer {@code (20|5)} where the σ above it answers {@code (20.0|5)} — the same
     * node, under the spelling the data uses rather than the one the query used.
     *
     * <p>It was also once more than that. A node's identity is {@code Value} equality, and
     * a number's used to include its scale, so {@code 20} and {@code 20.0} were two nodes
     * and a literal found whichever happened to match its spelling. That is no longer so —
     * {@code NumberValue} compares numerically, so the two are one node and a set holds one
     * of them — and the walk is kept because the value it picks is still the data's own.
     */
    private static List<Value> seeds(Value bound, Set<Value> nodes) {
        List<Value> matching = new ArrayList<>(1);
        for (Value n : nodes) {
            if (equalEndpoints(n, bound)) {
                matching.add(n);
            }
        }
        return matching;
    }

    /**
     * Endpoint equality as the σ that produced the bound would have decided it — which is
     * {@link ValueComparator#equal}, and not a comparison against zero.
     *
     * <p>The two differ on a pair the comparator cannot <em>order</em>. Comparing raises
     * there, so a bound of another type than the graph's nodes — {@code σ src = "zzz"}
     * over numeric nodes — turned an answer of no rows into a failed query, the σ alone
     * having simply matched nothing. {@code equal} never throws, for the reason its own
     * javadoc gives: a match test should skip a mismatched pair rather than abort, and
     * comparing against zero conflates <em>different</em> with <em>unorderable</em>.
     *
     * <p>They also differ on NULL, which {@code compare} places consistently and therefore
     * calls equal to itself while {@code equal} says NULL matches nothing. That makes no
     * difference here and cannot: a bound is a literal, and there is no NULL literal for
     * one to be. A NULL on the <em>node</em> side already answered "not equal" both ways.
     */
    private static boolean equalEndpoints(Value a, Value b) {
        return ValueComparator.equal(a, b);
    }

    /**
     * Set of nodes reachable from {@code seed} in one or more hops over the given
     * adjacency, by breadth-first traversal (terminates on cycles — a node already
     * reached is never re-enqueued). Does not include {@code seed} itself unless a
     * cycle leads back to it.
     */
    private static Set<Value> reachable(Value seed, Map<Value, List<Value>> adjacency) {
        Set<Value> reached = new LinkedHashSet<>();
        Deque<Value> queue = new ArrayDeque<>();
        List<Value> seeds = adjacency.get(seed);
        if (seeds != null) {
            for (Value v : seeds) {
                if (reached.add(v)) {
                    queue.add(v);
                }
            }
        }
        while (!queue.isEmpty()) {
            Value cur = queue.poll();
            List<Value> successors = adjacency.get(cur);
            if (successors == null) {
                continue;
            }
            for (Value v : successors) {
                if (reached.add(v)) {
                    queue.add(v);
                }
            }
        }
        return reached;
    }

    /** Evaluates a literal endpoint-bound operand to its {@link Value}. */
    private Value literal(Operand op, EvalCtx ctx) {
        return ctx.operandEval().evaluate(op, EMPTY_ROW);
    }

    /** A zero-column row used only to evaluate constant (literal) operands. */
    private static final Row EMPTY_ROW = ArrayRow.of(Schema.empty(), List.of());

    /** A directed edge / closure pair, keyed on {@link Value} equality. */
    private record Pair(Value from, Value to) {}

    /**
     * Connected-components labelling (CLUSTER) by an in-engine undirected union-find
     * over the whole edge set.
     *
     * <p>Reads the input as undirected edges over the {@code from}/{@code to} columns
     * (null endpoints skipped — a null is not a graph node), unioning each edge's two
     * endpoints, then partitions the distinct nodes into maximal connected components.
     * Component labels are <em>canonical</em>: each component is keyed on its minimum
     * node id (by {@link ValueComparator}); components are then sorted by that minimum
     * and assigned dense 1-based integer ids, so the labelling is reproducible
     * regardless of input row order.  Output is one row per distinct node —
     * {@code (node, label)} — emitted in ascending {@code (label, node)} order.
     */
    Stream<Row> executeCluster(PhysicalNode.Cluster node, EvalCtx ctx) {
        Schema outputSchema = node.schema();
        String fromCol = node.fromColumn();
        String toCol   = node.toColumn();

        // Union-find over node Values: parent map + insertion-ordered node set.
        Map<Value, Value> parent = new HashMap<>();
        Set<Value> nodes = new LinkedHashSet<>();
        try (Stream<Row> input = dispatch.buffering(node.input(), ctx, node)) {
            input.forEach(row -> {
                Value a = row.get(fromCol);
                Value b = row.get(toCol);
                if (a.isNull() || b.isNull()) {
                    // A null endpoint is not a graph node — and neither is what it was
                    // paired with, since a row missing an end is not an edge. Registering
                    // the surviving value made it an island the data never claimed, and
                    // renumbered every component after it: labels are dense and ordered by
                    // the component minimum, so a spurious singleton shifts the rest.
                    return;
                }
                nodes.add(a);
                parent.putIfAbsent(a, a);
                nodes.add(b);
                parent.putIfAbsent(b, b);
                union(parent, a, b);   // undirected: from ↔ to
            });
        }

        // Each component's representative = the minimum node id within it.
        Comparator<Value> order = ValueComparator.NULLS_LAST;
        Map<Value, Value> componentMin = new HashMap<>();   // root → min node so far
        for (Value n : nodes) {
            Value root = find(parent, n);
            componentMin.merge(root, n, (cur, cand) -> order.compare(cand, cur) < 0 ? cand : cur);
        }

        // Order components by their minimum node, assign dense 1-based labels.
        List<Value> representatives = new ArrayList<>(componentMin.values());
        representatives.sort(order);
        Map<Value, Integer> labelOf = new HashMap<>();   // component min → label
        for (int i = 0; i < representatives.size(); i++) {
            labelOf.put(representatives.get(i), i + 1);
        }

        // Emit one row per distinct node, sorted by (label, node) for determinism.
        List<Value> sortedNodes = new ArrayList<>(nodes);
        sortedNodes.sort(Comparator
                .<Value>comparingInt(n -> labelOf.get(componentMin.get(find(parent, n))))
                .thenComparing(order));

        List<Row> outputRows = new ArrayList<>(sortedNodes.size());
        for (Value n : sortedNodes) {
            int label = labelOf.get(componentMin.get(find(parent, n)));
            outputRows.add(ArrayRow.of(outputSchema,
                    List.of(n, new NumberValue(java.math.BigDecimal.valueOf(label)))));
        }
        return BagRelation.of(outputSchema, outputRows).stream();
    }

    /**
     * Bounded variable-length path reachability (PATH) by an in-engine bounded
     * breadth-first traversal over the whole edge set.
     *
     * <p>Reads the input as directed edges over the {@code from}/{@code to} columns
     * (null endpoints skipped — a null is not a graph node) and builds an adjacency
     * map once.  A multi-source BFS then discovers, for every {@code (source, target)}
     * pair, the <em>shortest</em> path length; pairs whose length lies within the
     * inclusive {@code [minHops, maxHops]} window are emitted as
     * {@code (from, to, depth)}.  Because BFS visits nodes in non-decreasing distance,
     * the first time a pair is reached is its shortest length.  Cyclic graphs terminate
     * because the traversal is capped at {@code maxHops}.  Output is sorted by
     * {@code (from, to)} for reproducibility regardless of input row order.
     */
    Stream<Row> executePath(PhysicalNode.Path node, EvalCtx ctx) {
        Schema outputSchema = node.schema();
        String fromCol = node.fromColumn();
        String toCol   = node.toColumn();
        int minHops = node.minHops();
        int maxHops = node.maxHops();
        boolean undirected = node.undirected();

        Map<Value, List<Value>> adjacency = new HashMap<>();
        Set<Pair> edges = new LinkedHashSet<>();
        try (Stream<Row> input = dispatch.buffering(node.input(), ctx, node)) {
            input.forEach(row -> {
                Value a = row.get(fromCol);
                Value b = row.get(toCol);
                if (a.isNull() || b.isNull()) {
                    return;   // a null endpoint is not a graph node
                }
                addEdge(edges, adjacency, null, a, b);
                if (undirected) {
                    addEdge(edges, adjacency, null, b, a);
                }
            });
        }

        // Shortest hop distance for every (source, target) pair, discovered by BFS.
        // The 1-step frontier is the edge set; each round extends every frontier pair
        // by one hop, recording a derived pair only on first (shortest) discovery.
        Map<Pair, Integer> shortest = new LinkedHashMap<>();
        Set<Pair> frontier = new LinkedHashSet<>();   // (source, node) reached at the current depth
        for (Pair e : edges) {
            if (shortest.putIfAbsent(e, 1) == null) {
                frontier.add(e);
            }
        }
        for (int depth = 1; depth < maxHops && !frontier.isEmpty(); depth++) {
            Set<Pair> next = new LinkedHashSet<>();
            for (Pair sp : frontier) {
                List<Value> successors = adjacency.get(sp.to());
                if (successors == null) {
                    continue;
                }
                for (Value c : successors) {
                    Pair derived = new Pair(sp.from(), c);
                    if (!shortest.containsKey(derived)) {
                        shortest.put(derived, depth + 1);
                        next.add(derived);
                    }
                }
            }
            frontier = next;
        }

        // Emit pairs whose shortest length is within the window, ordered by (from, to).
        Comparator<Value> order = ValueComparator.NULLS_LAST;
        List<Map.Entry<Pair, Integer>> emitted = new ArrayList<>();
        for (Map.Entry<Pair, Integer> entry : shortest.entrySet()) {
            int d = entry.getValue();
            if (d >= minHops && d <= maxHops) {
                emitted.add(entry);
            }
        }
        emitted.sort(Comparator
                .<Map.Entry<Pair, Integer>, Value>comparing(e -> e.getKey().from(), order)
                .thenComparing(e -> e.getKey().to(), order));

        List<Row> outputRows = new ArrayList<>(emitted.size());
        for (Map.Entry<Pair, Integer> entry : emitted) {
            Pair p = entry.getKey();
            outputRows.add(ArrayRow.of(outputSchema, List.of(
                    p.from(), p.to(),
                    new NumberValue(java.math.BigDecimal.valueOf(entry.getValue())))));
        }
        return BagRelation.of(outputSchema, outputRows).stream();
    }

    /** Union-find {@code find} with path compression over the {@code parent} map. */
    private static Value find(Map<Value, Value> parent, Value x) {
        Value root = x;
        while (!root.equals(parent.get(root))) {
            root = parent.get(root);
        }
        // Path compression: point every node on the path directly at the root.
        Value cur = x;
        while (!cur.equals(root)) {
            Value next = parent.get(cur);
            parent.put(cur, root);
            cur = next;
        }
        return root;
    }

    /** Union-find {@code union}: merges the components of {@code a} and {@code b}. */
    private static void union(Map<Value, Value> parent, Value a, Value b) {
        Value ra = find(parent, a);
        Value rb = find(parent, b);
        if (!ra.equals(rb)) {
            parent.put(ra, rb);
        }
    }

    /**
     * Semi-naïve least-fixpoint evaluation for the general {@code FIX} operator
     * (ADR-0003).
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Evaluate {@link PhysicalNode.Fixpoint#base()} → initial result set
     *       ({@code result}) and first delta ({@code delta = result}).</li>
     *   <li>While {@code delta} is non-empty: bind {@code name → delta}, evaluate
     *       {@link PhysicalNode.Fixpoint#step()}, keep only rows not already in
     *       {@code result} ({@code next}), then {@code result += next; delta = next}.</li>
     *   <li>Emit {@code result} as a set (no duplicates).</li>
     * </ol>
     *
     * <p>Binding only the delta (newly-derived rows) rather than the full accumulator
     * is sound for linear recursion (validator-enforced: exactly one
     * {@link PhysicalNode.RecursiveRef} in the step) with monotone operators —
     * exactly the same invariant that makes the binary {@link PhysicalNode.Closure}
     * semi-naïve loop correct.
     *
     * <p>Row identity is determined by extracting each row's values in the canonical
     * output-schema column order; duplicate rows in the base or step are collapsed.
     *
     * <p><strong>This loop does not always terminate, and the guarantee this javadoc used
     * to state was wrong</strong> (#874). The old claim — each round adds ≥1 row or stops,
     * and the universe of possible rows is finite — is the standard Datalog argument, and
     * its second half needs a premise nothing enforces: that the step draws every value it
     * emits from the <em>active domain</em> of its inputs. A projection is on
     * {@code RecursiveRefChecker}'s monotone list with no constraint on what it computes,
     * so a step projecting {@code n + 1} mints a value no input holds, every round,
     * forever — and {@code NUMBER} is a {@code BigDecimal}, so there is no width at which
     * it wraps and starts repeating. The universe is infinite and the ascending chain never
     * closes. {@code docs/design/general-recursion-plan.md} carries the corrected argument
     * and the reason the hole is left open rather than closed.
     *
     * <p>So the two bounds here are load-bearing rather than belt-and-braces:
     * {@code maxFixpointRounds} bounds the iteration, and
     * {@link MaterializationBudget#charge} bounds the accumulator — which is the operator's
     * real buffer, and was charged to nothing before #874. Both are unlimited by default,
     * so a runaway fixpoint is bounded by the heap unless a caller asked for better.
     *
     * <p>Nested {@code FIX} nodes with different names work correctly because
     * {@link EvalCtx#withBinding} creates a fresh context that shadows only the named
     * binding, leaving any outer binding intact.
     */
    Stream<Row> executeFixpoint(PhysicalNode.Fixpoint node, EvalCtx ctx) {
        Schema schema = node.schema();
        int maxRounds = ctx.maxFixpointRounds();
        String name = node.name();

        // Phase 1 — evaluate the base (non-recursive seed).
        Set<List<Value>> result = new LinkedHashSet<>();
        try (Stream<Row> base = dispatch.buffering(node.base(), ctx, node)) {
            base.forEach(row -> result.add(rowKey(row, schema)));
        }
        // The accumulator, not any one round's read, is what this operator holds.
        MaterializationBudget.charge(node, result.size(), ctx);

        Set<List<Value>> delta = new LinkedHashSet<>(result);
        int round = 0;

        // Phase 2 — iterate until no new rows are derived.
        while (!delta.isEmpty()) {
            if (round >= maxRounds) {
                throw new EvaluationException(
                        "FIX '" + name + "' exceeded " + maxRounds
                        + " iteration round(s); add a bound (e.g. λ n) below it, "
                        + "or increase --max-fixpoint-rounds");
            }
            round++;
            final int r = round;
            final int ds = delta.size();
            final int rs = result.size();
            LOG.log(System.Logger.Level.DEBUG,
                    () -> "FIX '" + name + "' round " + r
                            + ": delta=" + ds + " result=" + rs);

            List<Row> deltaRows = deltaAsRows(delta, schema);
            EvalCtx stepCtx = ctx.withBinding(name, deltaRows);

            Set<List<Value>> next = new LinkedHashSet<>();
            try (Stream<Row> step = dispatch.buffering(node.step(), stepCtx, node)) {
                step.forEach(row -> {
                    List<Value> key = rowKey(row, schema);
                    if (!result.contains(key)) {
                        next.add(key);
                    }
                });
            }
            result.addAll(next);
            MaterializationBudget.charge(node, result.size(), ctx);
            delta = next;
        }

        // Phase 3 — emit result as a set-semantics bag.
        List<Row> outputRows = new ArrayList<>(result.size());
        for (List<Value> values : result) {
            outputRows.add(ArrayRow.of(schema, values));
        }
        return BagRelation.of(schema, outputRows).stream();
    }

    /**
     * Converts a set of value-lists (the current delta) into {@link Row}s with the
     * given schema so the step sub-tree can evaluate against them.
     */
    private static List<Row> deltaAsRows(Set<List<Value>> delta, Schema schema) {
        List<Row> rows = new ArrayList<>(delta.size());
        for (List<Value> values : delta) {
            rows.add(ArrayRow.of(schema, values));
        }
        return rows;
    }

    /**
     * Extracts the values from {@code row} positionally, producing the key used for
     * set-deduplication in the fixpoint accumulator.
     *
     * <p>Identity is <em>positional</em>, not by-name: a fixpoint takes its output
     * headings from the base schema (the {@code ∪} convention — the validator enforces
     * only positional union-compatibility, never matching names), so a step sub-plan
     * may legitimately rename its columns.  Looking the values up by the base schema's
     * names would then throw {@link com.darkcollective.relix.processor.ArrayRow#get(String)}
     * on a renamed step row.  Open (schema-on-read) schemas have no fixed columns, so
     * the row itself carries the authoritative width.
     */
    private static List<Value> rowKey(Row row, Schema schema) {
        int width = schema.isOpen() ? row.width() : schema.columns().size();
        List<Value> key = new ArrayList<>(width);
        for (int i = 0; i < width; i++) {
            key.add(row.get(i));
        }
        return key;
    }

    /**
     * Streams the current delta rows bound to {@link PhysicalNode.RecursiveRef#name()}
     * in the execution context's recursion binding map.  An unbound reference (which
     * should never occur in a well-formed plan produced by the planner) raises an
     * {@link EvaluationException} as a defensive guard.
     */
    Stream<Row> executeRecursiveRef(PhysicalNode.RecursiveRef node, EvalCtx ctx) {
        List<Row> bound = ctx.recursionBindings().get(node.name());
        if (bound == null) {
            throw new EvaluationException(
                    "Unbound recursive reference '" + node.name()
                    + "' — this is a planner bug; RecursiveRef must appear inside a Fixpoint step");
        }
        return bound.stream();
    }

    /**
     * Optimal-path extraction (TRACE) by a Bellman-Ford–style all-pairs fixpoint.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Materialise the edge set as a list of {@code (from, to, weight)} triples
     *       (null endpoints / non-numeric weights skipped).</li>
     *   <li>Seed the best-path table with every direct edge:
     *       {@code best[(from,to)] = (weight, [from, to])}.</li>
     *   <li>Repeatedly relax: for every known path ending at some node {@code v} and
     *       every edge {@code (v, w, ew)}, if extending the path to {@code w} would be
     *       better (lower for MINIMIZE, higher for MAXIMIZE), update the entry.</li>
     *   <li>Iterate until no entry changes (guaranteed to terminate on graphs without
     *       improvement-cycles; the {@code --max-fixpoint-rounds} guard fires on
     *       positive-weight cycles under MAXIMIZE).</li>
     *   <li>Emit one row per distinct {@code (from, to)} pair: the optimal cost and
     *       the traversed node sequence as an ordered {@link ArrayValue}.</li>
     * </ol>
     *
     * <p>Row identity is by the {@code (from, to)} pair; the path array is ordered
     * (not sorted) — the sequence in which nodes were traversed from origin to
     * destination inclusive.
     *
     * <p><strong>Endpoint pushdown (ADR-0020 slice 1).</strong> When the
     * optimizer has folded a constant endpoint equality into
     * {@link PhysicalNode.Trace#boundSource()} / {@link PhysicalNode.Trace#boundTarget()},
     * the all-pairs relaxation is replaced by a bounded-seed search:
     * <ul>
     *   <li><em>source bound {@code s}</em> — seed only the edges leaving {@code s},
     *       so the relaxation produces the optimal {@code (s, ·)} paths alone;</li>
     *   <li><em>target bound {@code t}</em> — run the same single-source search from
     *       {@code t} over the <em>reversed</em> graph (each edge flipped, same
     *       weight), then re-orient each result to {@code (r, t)} with its path
     *       reversed — the optimal {@code (·, t)} paths;</li>
     *   <li><em>both</em> — single-source from {@code s} then kept only where the
     *       destination equals {@code t} (single-pair search).</li>
     * </ul>
     * (Slice 2 — single-pair Dijkstra with goal-directed early exit for the
     * non-negative MINIMIZE case — is a later planner-stage refinement; this
     * bounded-seed relaxation is the slice-1 win.)
     */
    Stream<Row> executeTrace(PhysicalNode.Trace node, EvalCtx ctx) {
        Schema outputSchema = node.schema();
        String fromCol   = node.fromColumn();
        String toCol     = node.toColumn();
        String weightCol = node.weightColumn();
        boolean minimize = node.sense() == ObjectiveSense.MINIMIZE;
        boolean undirected = node.undirected();
        int maxRounds    = ctx.maxFixpointRounds();

        Optional<Value> boundSource = node.boundSource().map(op -> literal(op, ctx));
        Optional<Value> boundTarget = node.boundTarget().map(op -> literal(op, ctx));
        boolean targetOnly = boundSource.isEmpty() && boundTarget.isPresent();

        // ── Materialise edges (null endpoints / non-numeric weights skipped) ──
        List<TraceEdge> edgeList = new ArrayList<>();
        try (Stream<Row> input = dispatch.buffering(node.input(), ctx, node)) {
            input.forEach(row -> {
                Value f = row.get(fromCol);
                Value w = row.get(toCol);
                Value wt = row.get(weightCol);
                if (f.isNull() || w.isNull() || wt.isNull()) return;
                if (wt instanceof NumberValue nv) {
                    edgeList.add(new TraceEdge(f, w, nv.value().doubleValue()));
                    if (undirected) {
                        // One edge, traversable either way at the same cost.
                        edgeList.add(new TraceEdge(w, f, nv.value().doubleValue()));
                    }
                }
            });
        }

        // Slice 2: single-pair Dijkstra with goal-directed early exit, chosen by the
        // planner for a bounded source+target MINIMIZE. Sound only for non-negative
        // weights; bail to the relaxation below (returns null) if a negative edge exists.
        if (node.algorithm() == TraceAlgorithm.DIJKSTRA
                && boundSource.isPresent() && boundTarget.isPresent()) {
            List<Row> dijkstra = singlePairDijkstra(
                    edgeList, boundSource.get(), boundTarget.get(), outputSchema);
            if (dijkstra != null) {
                return BagRelation.of(outputSchema, dijkstra).stream();
            }
        }

        String label = fromCol + "→" + toCol;
        List<Row> outputRows;
        if (boundSource.isEmpty() && boundTarget.isEmpty()) {
            Map<Pair, TracePath> best = new HashMap<>();
            for (TraceEdge e : edgeList) {
                seedEdge(best, e, minimize);
            }
            relax(best, edgeList, minimize, maxRounds, label, node.sense());
            outputRows = emitTrace(outputSchema, best, false);
        } else if (!targetOnly) {
            // Single-source from s, optionally narrowed to a fixed destination.
            Map<Pair, TracePath> best = singleSourceTrace(
                    boundSource.get(), edgeList, minimize, maxRounds, label, node.sense());
            if (boundTarget.isPresent()) {
                Value t = boundTarget.get();
                best.keySet().removeIf(k -> !k.to().equals(t));
            }
            outputRows = emitTrace(outputSchema, best, false);
        } else {
            // Target-only: single-source from t over the reversed graph, then flip.
            List<TraceEdge> reversed = new ArrayList<>(edgeList.size());
            for (TraceEdge e : edgeList) {
                reversed.add(new TraceEdge(e.to(), e.from(), e.weight()));
            }
            Map<Pair, TracePath> best = singleSourceTrace(
                    boundTarget.get(), reversed, minimize, maxRounds, label, node.sense());
            outputRows = emitTrace(outputSchema, best, true);
        }
        return BagRelation.of(outputSchema, outputRows).stream();
    }

    /** A directed, weighted edge for TRACE. */
    private record TraceEdge(Value from, Value to, double weight) {}

    /** A best-path entry: total cost + the ordered node sequence. */
    private record TracePath(double cost, List<Value> path) {}

    /**
     * Single-source optimal paths: seeds only the edges leaving {@code seed} then
     * relaxes over the full edge set, so every entry is keyed {@code (seed, ·)}.
     */
    private static Map<Pair, TracePath> singleSourceTrace(Value seed, List<TraceEdge> edges,
            boolean minimize, int maxRounds, String label, ObjectiveSense sense) {
        Map<Pair, TracePath> best = new HashMap<>();
        for (TraceEdge e : edges) {
            if (e.from().equals(seed)) {
                seedEdge(best, e, minimize);
            }
        }
        relax(best, edges, minimize, maxRounds, label, sense);
        return best;
    }

    /** Seeds the best table with a single direct edge (keeping the optimal per pair). */
    private static void seedEdge(Map<Pair, TracePath> best, TraceEdge e, boolean minimize) {
        Pair key = new Pair(e.from(), e.to());
        TracePath cur = best.get(key);
        if (cur == null || isBetter(e.weight(), cur.cost(), minimize)) {
            best.put(key, new TracePath(e.weight(), List.of(e.from(), e.to())));
        }
    }

    /**
     * Bellman-Ford–style relaxation in place: repeatedly extends each known path by
     * one hop, keeping the optimal, until no entry changes. The
     * {@code --max-fixpoint-rounds} guard fires on improvement cycles.
     */
    private static void relax(Map<Pair, TracePath> best, List<TraceEdge> edges,
            boolean minimize, int maxRounds, String label, ObjectiveSense sense) {
        int round = 0;
        boolean changed = true;
        while (changed) {
            if (round >= maxRounds) {
                throw new EvaluationException(
                        "TRACE '" + label + "' exceeded " + maxRounds
                        + " iteration round(s); the graph may contain improvement cycles "
                        + "under " + sense + ". Add a bound or increase "
                        + "--max-fixpoint-rounds");
            }
            round++;
            changed = false;
            // Snapshot entries to avoid ConcurrentModificationException
            List<Map.Entry<Pair, TracePath>> snapshot = new ArrayList<>(best.entrySet());
            for (Map.Entry<Pair, TracePath> entry : snapshot) {
                Pair reach = entry.getKey();         // (origin → v)
                TracePath pe = entry.getValue();
                // Extend via each edge (v → w, ew)
                for (TraceEdge e : edges) {
                    if (!e.from().equals(reach.to())) continue;
                    Value w = e.to();
                    // Avoid creating loops: the path already visits reach.from()
                    // at position 0; if w is already in the path, skip.
                    if (pe.path().contains(w)) continue;
                    double newCost = pe.cost() + e.weight();
                    Pair newKey = new Pair(reach.from(), w);
                    TracePath cur = best.get(newKey);
                    if (cur == null || isBetter(newCost, cur.cost(), minimize)) {
                        List<Value> newPath = new ArrayList<>(pe.path());
                        newPath.add(w);
                        best.put(newKey, new TracePath(newCost, List.copyOf(newPath)));
                        changed = true;
                    }
                }
            }
        }
    }

    /**
     * Emits one output row per best-path entry. When {@code reversed} (target-only
     * search over the reversed graph), each {@code (t, r)} entry is re-oriented to
     * {@code (r, t)} with its node sequence reversed so the path reads origin→target.
     */
    private static List<Row> emitTrace(Schema outputSchema, Map<Pair, TracePath> best,
                                       boolean reversed) {
        List<Row> outputRows = new ArrayList<>(best.size());
        for (Map.Entry<Pair, TracePath> entry : best.entrySet()) {
            Pair pair = entry.getKey();
            TracePath pe = entry.getValue();
            Value fromVal;
            Value toVal;
            List<Value> pathSeq;
            if (reversed) {
                fromVal = pair.to();      // r — the node that reaches the bound target
                toVal   = pair.from();    // t — the seed (bound target)
                List<Value> rev = new ArrayList<>(pe.path());
                Collections.reverse(rev);
                pathSeq = rev;
            } else {
                fromVal = pair.from();
                toVal   = pair.to();
                pathSeq = pe.path();
            }
            Value pathVal = new ArrayValue(pathSeq);
            outputRows.add(ArrayRow.of(outputSchema,
                    List.of(fromVal, toVal,
                            new NumberValue(java.math.BigDecimal.valueOf(pe.cost())),
                            pathVal)));
        }
        return outputRows;
    }

    /**
     * Single-pair shortest path by Dijkstra with goal-directed early termination
     * (ADR-0020 slice 2) — the optimal {@code s ⇝ t} path under {@code MINIMIZE}.
     *
     * <p>Returns the single output row (or an empty list when {@code t} is unreachable
     * or equals {@code s}, matching the relaxation, which never emits a trivial
     * self-pair). Returns {@code null} to signal that a <em>negative</em> edge weight
     * was found — Dijkstra is unsound there, so the caller falls back to the
     * Bellman-Ford relaxation. Lazy-deletion priority queue; halts as soon as {@code t}
     * is finalized.
     */
    private static List<Row> singlePairDijkstra(List<TraceEdge> edges, Value s, Value t,
                                                Schema outputSchema) {
        if (s.equals(t)) {
            return List.of();   // no trivial zero-length self-pair (matches relaxation)
        }
        Map<Value, List<TraceEdge>> adjacency = new HashMap<>();
        for (TraceEdge e : edges) {
            if (e.weight() < 0) {
                return null;     // negative weight → Dijkstra unsound; signal fallback
            }
            adjacency.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
        }

        record QEntry(Value node, double dist) {}
        Map<Value, Double> dist = new HashMap<>();
        Map<Value, Value> prev = new HashMap<>();
        Set<Value> finalized = new HashSet<>();
        PriorityQueue<QEntry> pq = new PriorityQueue<>(Comparator.comparingDouble(QEntry::dist));
        dist.put(s, 0.0);
        pq.add(new QEntry(s, 0.0));

        while (!pq.isEmpty()) {
            QEntry top = pq.poll();
            Value u = top.node();
            if (!finalized.add(u)) {
                continue;        // stale duplicate entry
            }
            if (u.equals(t)) {
                break;           // goal finalized — early termination
            }
            List<TraceEdge> out = adjacency.get(u);
            if (out == null) {
                continue;
            }
            for (TraceEdge e : out) {
                Value v = e.to();
                if (finalized.contains(v)) {
                    continue;
                }
                double nd = top.dist() + e.weight();
                if (nd < dist.getOrDefault(v, Double.POSITIVE_INFINITY)) {
                    dist.put(v, nd);
                    prev.put(v, u);
                    pq.add(new QEntry(v, nd));
                }
            }
        }

        if (!finalized.contains(t)) {
            return List.of();    // t unreachable from s
        }
        // Reconstruct the path s … t by walking predecessors backwards.
        List<Value> path = new ArrayList<>();
        for (Value cur = t; cur != null; cur = prev.get(cur)) {
            path.add(cur);
        }
        Collections.reverse(path);
        Row row = ArrayRow.of(outputSchema, List.of(
                s, t,
                new NumberValue(java.math.BigDecimal.valueOf(dist.get(t))),
                new ArrayValue(path)));
        return List.of(row);
    }

    /** Returns true if {@code candidate} is strictly better than {@code current}. */
    private static boolean isBetter(double candidate, double current, boolean minimize) {
        return minimize ? candidate < current : candidate > current;
    }
}

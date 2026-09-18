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
package com.darkcollective.relix.processor.provenance;

import com.darkcollective.relix.ast.ClosureNode;
import com.darkcollective.relix.ast.NaturalJoinNode;
import com.darkcollective.relix.ast.ProductNode;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.ThetaJoinNode;
import com.darkcollective.relix.ast.TruthRelationNode;
import com.darkcollective.relix.ast.UnionAllNode;
import com.darkcollective.relix.ast.UnionNode;
import com.darkcollective.relix.processor.ArrayRow;
import com.darkcollective.relix.processor.ExecutionContext;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.processor.eval.OperandEvaluator;
import com.darkcollective.relix.processor.eval.PredicateEvaluator;
import com.darkcollective.relix.processor.eval.ValueComparator;
import com.darkcollective.relix.processor.exec.QualifiedRow;
import com.darkcollective.relix.processor.exec.RelNodeExecutor;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.provenance.Semiring;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.relation.RelationSymbol;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Evaluates a logical {@link RelNode} tree as a {@link AnnotatedRelation K-relation},
 * threading a chosen {@link Semiring} through the positive-algebra operators
 * This is the bridge from the engine's ordinary {@code Stream<Row>} evaluation
 * to the annotated model.
 *
 * <h2>What is threaded, and what is opaque</h2>
 * <p>Provenance v1 covers <strong>positive relational algebra</strong> only: this
 * evaluator recurses through, and threads annotations across, selection (σ),
 * projection (π), rename (ρ), product (×), inner/natural join (⋈/⨝), and union (∪) —
 * combining derivations with the semiring's {@code ⊕}/{@code ⊗} exactly as
 * {@link AnnotatedRelation}'s operators define. <strong>Transitive closure</strong>
 * ({@code CLOSURE}/{@code RCLOSURE}) is also threaded — as a
 * <strong>semiring-weighted closure</strong>:
 * iterating the closure with the chosen semiring unifies reachability
 * (boolean), path-counting (ℕ), and shortest path (tropical) into one mechanism —
 * see {@link #weightedClosure}. Every <em>other</em> node — a base relation, or a
 * non-positive operator such as γ, τ, δ, a difference, an outer join, or a general
 * fixpoint — is treated as an <strong>opaque base
 * relation</strong>: its subtree is evaluated by the ordinary engine and
 * {@link AnnotatedRelation#lift lifted}, so every one of its output tuples is
 * annotated with the semiring's {@code one()}. Provenance therefore flows through
 * the positive operators <em>above</em> the nearest non-positive boundary, and that
 * boundary's output is read as a fresh source — the honest reading of the
 * positive-RA theory, and the behaviour that lets provenance run over any query.
 *
 * <h2>Above the federation boundary</h2>
 * <p>Annotation tracking is in-engine and never pushed to a source.
 * The threaded operators are handled here directly; only the opaque subtrees reach
 * the planner (where pushdown is harmless, since the whole subtree is read as a
 * base relation). The optimizer is not involved.
 *
 * <h2>Cost</h2>
 * <p>A K-relation is canonical (materialised) by construction, so unlike the
 * default streaming path this evaluator buffers each relation. Provenance is an
 * explicitly requested, heavier mode; the default path pays nothing.
 *
 * <h2>Thread safety</h2>
 * <p>Stateless and therefore thread-safe; the relations it returns are immutable.
 */
public final class ProvenanceEvaluator {

    private final RelNodeExecutor nodeExecutor = new RelNodeExecutor();

    /**
     * Evaluates {@code node} as a K-relation over {@code semiring}.
     *
     * @param node     the logical relational algebra tree to evaluate; must not be null
     * @param semiring the annotation semiring; must not be null
     * @param ctx      the shared execution context (connector, schemas, symbols); must not be null
     * @param <K>      the semiring annotation type
     * @return the canonical annotated relation
     * @throws EvaluationException if a node's schema is unavailable or a data-level error occurs
     */
    public <K> AnnotatedRelation<K> evaluate(RelNode node, Semiring<K> semiring, ExecutionContext ctx) {
        return evaluate(node, semiring, ctx, BaseAnnotator.forSemiring(
                Objects.requireNonNull(semiring, "semiring"), null));
    }

    /**
     * Evaluates {@code node} as a K-relation over {@code semiring}, using
     * {@code baseAnnotator} to annotate each base-tuple occurrence — for a caller that
     * has one already. The three-argument overload builds the one the semiring asks for,
     * which is what an ordinary caller wants.
     *
     * @param node          the logical relational algebra tree to evaluate; must not be null
     * @param semiring      the annotation semiring; must not be null
     * @param ctx           the shared execution context; must not be null
     * @param baseAnnotator annotates each base-tuple occurrence; must not be null
     * @param <K>           the semiring annotation type
     * @return the canonical annotated relation
     * @throws EvaluationException if a node's schema is unavailable or a data-level error occurs
     */
    public <K> AnnotatedRelation<K> evaluate(RelNode node, Semiring<K> semiring, ExecutionContext ctx,
                                             BaseAnnotator<K> baseAnnotator) {
        Objects.requireNonNull(node,          "node");
        Objects.requireNonNull(semiring,      "semiring");
        Objects.requireNonNull(ctx,           "ctx");
        Objects.requireNonNull(baseAnnotator, "baseAnnotator");
        OperandEvaluator operandEval = new OperandEvaluator(ctx.symbolTable(), ctx.functions(), ctx.functionContext());
        PredicateEvaluator predicateEval = new PredicateEvaluator(operandEval);
        return eval(node, semiring, ctx, operandEval, predicateEval, baseAnnotator);
    }

    private <K> AnnotatedRelation<K> eval(RelNode node, Semiring<K> semiring, ExecutionContext ctx,
                                          OperandEvaluator operandEval, PredicateEvaluator predicateEval,
                                          BaseAnnotator<K> ba) {
        return switch (node) {
            case SelectionNode s -> eval(s.input(), semiring, ctx, operandEval, predicateEval, ba)
                    .select(row -> predicateEval.evaluate(s.predicate(), row));

            case ProjectionNode p -> {
                Schema out = schemaOf(p, ctx);
                yield eval(p.input(), semiring, ctx, operandEval, predicateEval, ba)
                        .project(out, row -> projectRow(p.attributes(), out, row, operandEval));
            }

            // Rename (ρ) — positive and value-preserving: it re-labels a row's columns
            // (and re-anchors the relation label) without adding, dropping or reordering
            // one, so each derivation carries through unchanged and only the heading moves.
            case RenameNode rn -> {
                Schema out = schemaOf(rn, ctx);
                yield eval(rn.input(), semiring, ctx, operandEval, predicateEval, ba)
                        .project(out, row -> reschema(row, out));
            }

            case ProductNode pr -> {
                Schema out = schemaOf(pr, ctx);
                AnnotatedRelation<K> left  = eval(pr.left(),  semiring, ctx, operandEval, predicateEval, ba);
                AnnotatedRelation<K> right = eval(pr.right(), semiring, ctx, operandEval, predicateEval, ba);
                yield left.product(right, out, (l, r) -> concat(l, r, out));
            }

            case ThetaJoinNode j -> {
                Schema out = schemaOf(j, ctx);   // theta-join output = left ++ right
                // Evaluate the condition against a QualifiedRow so a shared column name
                // (e.g. A.k = B.k) routes each qualifier to the correct side rather than
                // collapsing to the first positional match.
                Schema leftSchema  = schemaOf(j.left(),  ctx);
                Schema rightSchema = schemaOf(j.right(), ctx);
                Set<String> leftRel  = relationNames(j.left());
                Set<String> rightRel = relationNames(j.right());
                AnnotatedRelation<K> left  = eval(j.left(),  semiring, ctx, operandEval, predicateEval, ba);
                AnnotatedRelation<K> right = eval(j.right(), semiring, ctx, operandEval, predicateEval, ba);
                yield left.join(right,
                        (l, r) -> predicateEval.evaluate(j.condition(), new QualifiedRow(
                                l, r, leftSchema, rightSchema, leftRel, rightRel, out)),
                        out,
                        (l, r) -> concat(l, r, out));
            }

            case NaturalJoinNode j -> naturalJoin(j, semiring, ctx, operandEval, predicateEval, ba);

            // Transitive closure (R⁺/R*) — threaded as a semiring-weighted closure:
            // the chosen semiring decides the meaning (reachability / path count /
            // shortest path). The edge weights are the input edges' base
            // annotations (one() by default, or read from a weight column via the
            // BaseAnnotator), so this shares the provenance K-relation algebra.
            case ClosureNode cl -> weightedClosure(cl, semiring, ctx, operandEval, predicateEval, ba);

            // Union (∪) and union-all (⊎) both combine alternative derivations with ⊕;
            // the semiring decides whether multiplicities collapse (boolean) or add (ℕ).
            case UnionNode u -> eval(u.left(), semiring, ctx, operandEval, predicateEval, ba)
                    .union(eval(u.right(), semiring, ctx, operandEval, predicateEval, ba));
            case UnionAllNode u -> eval(u.left(), semiring, ctx, operandEval, predicateEval, ba)
                    .union(eval(u.right(), semiring, ctx, operandEval, predicateEval, ba));

            // The truth-relation literals ARE the semiring identities, so they are
            // annotated directly rather than lifted through the base annotator: UNIT
            // is the empty tuple annotated one() (the ⊗ identity — it contributes no
            // lineage of its own), and EMPTY is the empty K-relation (zero()).
            case TruthRelationNode t -> AnnotatedRelation.lift(Schema.empty(), semiring,
                    t.holdsTuple() ? Stream.of(ArrayRow.of(Schema.empty(), List.of()))
                                   : Stream.empty());

            // A reference to a named view is inlined — thread provenance through the
            // view body so lineage reaches the base tables, not the view's output rows.
            // A reference to a true base relation falls through to the opaque lift.
            case RelationNode r when isView(r, ctx) ->
                    eval(viewBody(r, ctx), semiring, ctx, operandEval, predicateEval, ba);

            // Base relations and every non-positive operator: read as an opaque base
            // relation — evaluate with the ordinary engine and lift each occurrence via
            // the base annotator (one() for cheap semirings, a fresh variable for lineage).
            default -> liftOpaque(node, semiring, ctx, ba);
        };
    }

    /** {@return the lowercased relation names appearing under {@code node} — for qualifier routing} */
    private static Set<String> relationNames(RelNode node) {
        Set<String> names = new HashSet<>();
        collectRelationNames(node, names);
        return names;
    }

    private static void collectRelationNames(RelNode node, Set<String> out) {
        if (node instanceof RelationNode r) {
            out.add(r.name().toLowerCase(Locale.ROOT));
        }
        for (RelNode child : node.children()) {
            collectRelationNames(child, out);
        }
    }

    /** {@return whether {@code r} names a query view (whose body should be threaded)} */
    private static boolean isView(RelationNode r, ExecutionContext ctx) {
        return ctx.symbolTable().lookupRelation(r.name())
                .filter(s -> s instanceof QueryRelationSymbol)
                .isPresent();
    }

    /** {@return the body of the view named by {@code r}} */
    private static RelNode viewBody(RelationNode r, ExecutionContext ctx) {
        return ((QueryRelationSymbol) ctx.symbolTable().lookupRelation(r.name()).orElseThrow()).body();
    }

    /**
     * Natural join (⋈): match on the shared columns (by name), output the left row
     * concatenated with the right row's non-shared columns. Mirrors the engine's
     * value semantics — a shared column with a NULL on either side never matches,
     * and a join with no shared columns yields nothing.
     */
    private <K> AnnotatedRelation<K> naturalJoin(NaturalJoinNode j, Semiring<K> semiring, ExecutionContext ctx,
                                                 OperandEvaluator operandEval, PredicateEvaluator predicateEval,
                                                 BaseAnnotator<K> ba) {
        Schema leftSchema  = schemaOf(j.left(),  ctx);
        Schema rightSchema = schemaOf(j.right(), ctx);
        Schema out = schemaOf(j, ctx);

        List<int[]> sharedPairs = new ArrayList<>();        // [leftIndex, rightIndex] per shared column
        List<Integer> rightOnly = new ArrayList<>();         // right indices not shared with the left
        for (int r = 0; r < rightSchema.columns().size(); r++) {
            String name = rightSchema.columns().get(r).name();
            int l = leftSchema.indexOf(name);
            if (l >= 0) {
                sharedPairs.add(new int[] {l, r});
            } else {
                rightOnly.add(r);
            }
        }

        AnnotatedRelation<K> left  = eval(j.left(),  semiring, ctx, operandEval, predicateEval, ba);
        AnnotatedRelation<K> right = eval(j.right(), semiring, ctx, operandEval, predicateEval, ba);
        return left.join(right,
                (l, r) -> naturalMatch(l, r, sharedPairs),
                out,
                (l, r) -> naturalConcat(l, r, rightOnly, out));
    }

    /**
     * Semiring-weighted transitive closure (ADR-0003 §D2-B / ADR-0004 item 8)
     * — the convergence point of the closure (ADR-0003) and provenance
     * (ADR-0004) work: one mechanism whose meaning is chosen by the semiring.
     *
     * <p>The {@link ClosureNode#input() input} is read as a weighted edge relation
     * over {@link ClosureNode#fromColumn() from}/{@link ClosureNode#toColumn() to}:
     * each edge carries the base annotation the {@link BaseAnnotator} assigns it
     * ({@code one()} by default, or a per-edge weight read from a weight column).
     * The closure is the least fixpoint {@code T = E ⊕ (T ∘ E)}, where {@code ∘} is
     * relational composition on the join endpoint and the semiring supplies the
     * combine: a derivation's annotation is the {@code ⊗}-product of its edge
     * annotations, and alternative derivations of a pair are combined with
     * {@code ⊕}. Choosing the semiring chooses the answer:
     * <ul>
     *   <li><strong>boolean</strong> — reachability (a pair is present iff some path
     *       connects it);</li>
     *   <li><strong>ℕ (counting)</strong> — the number of distinct paths between the
     *       pair;</li>
     *   <li><strong>tropical (min,+)</strong> — the shortest-path weight (with a
     *       per-edge weight column; without one every edge weighs {@code one()} = 0,
     *       so the result is degenerate but well-defined).</li>
     * </ul>
     *
     * <p>Evaluation is naïve (each round recomputes {@code E ⊕ (T ∘ E)} from the
     * previous {@code T}) and stops when {@code T} is stable. An <em>idempotent</em>
     * {@code ⊕} (boolean reachability, tropical shortest path over non-negative
     * weights) always converges over the finite pair universe; a <em>non-idempotent</em>
     * {@code ⊕} (ℕ path-counting) converges only on an acyclic graph — a cycle has
     * unboundedly many paths, and the iteration is cut off by the context's
     * {@link ExecutionContext#maxFixpointRounds() fixpoint round cap} with a clear
     * error rather than looping forever. The reflexive variant ({@code RCLOSURE})
     * adds an identity pair {@code (n, n)} at {@code one()} for every node.
     *
     * <p>Like every closure in the engine, this runs in-engine above the federation
     * boundary; only the edge subtree (whose σ/π/⋈ thread normally) may reach a
     * source.
     */
    private <K> AnnotatedRelation<K> weightedClosure(
            ClosureNode cl, Semiring<K> semiring, ExecutionContext ctx,
            OperandEvaluator operandEval, PredicateEvaluator predicateEval, BaseAnnotator<K> ba) {
        Schema out = schemaOf(cl, ctx);          // (from, to)
        String fromCol = cl.fromColumn();
        String toCol   = cl.toColumn();

        // The input edges as a (from, to)-annotated relation: drop null-endpoint
        // edges (a null endpoint is not a graph node — mirrors the plain executor)
        // and project away every non-endpoint column, so parallel edges combine with
        // ⊕ (the cheaper weight under tropical, the summed multiplicity under ℕ).
        AnnotatedRelation<K> directed =
                eval(cl.input(), semiring, ctx, operandEval, predicateEval, ba)
                        .select(row -> !row.get(fromCol).isNull() && !row.get(toCol).isNull())
                        .project(out, row -> ArrayRow.of(out, List.of(row.get(fromCol), row.get(toCol))));

        // An undirected reading is the edge set together with its transpose, merged by ⊕ —
        // the same rule that already combines parallel edges, so an edge written both ways
        // combines rather than counting twice.
        //
        // A self-loop is its own transpose, so it is ⊕-ed with itself. That is only a
        // different answer for a non-idempotent semiring (ℕ would say 2 where the plain
        // operator's deduplicating edge set says 1), and such a semiring cannot terminate
        // on a graph containing a self-loop at all: the loop is a cycle, and the fixpoint
        // adds to the annotation on every round. So there is no reading in which excluding
        // it would change an answer anyone can observe, and a branch nothing can reach is
        // worse than the arithmetic it avoids.
        AnnotatedRelation<K> edges = cl.undirected()
                ? directed.union(directed.project(
                        out, row -> ArrayRow.of(out, List.of(row.get(1), row.get(0)))))
                : directed;

        // Index the edges by their source endpoint for the per-round composition,
        // and collect the node set for the reflexive variant.
        Map<Value, List<Annotated<K>>> byFrom = new LinkedHashMap<>();
        Set<Value> nodes = new LinkedHashSet<>();
        edges.stream().forEach(a -> {
            Value f = a.row().get(0);
            Value t = a.row().get(1);
            nodes.add(f);
            nodes.add(t);
            byFrom.computeIfAbsent(f, k -> new ArrayList<>()).add(a);
        });

        int maxRounds = ctx.maxFixpointRounds();
        Map<Row, K> result = toMap(edges);

        int round = 0;
        while (true) {
            if (round >= maxRounds) {
                throw new EvaluationException(
                        "weighted CLOSURE exceeded " + maxRounds + " iteration round(s); a "
                        + "non-idempotent semiring (e.g. counting) over a cyclic graph does not "
                        + "converge — make the graph acyclic or raise --max-fixpoint-rounds");
            }
            round++;
            Map<Row, K> next = new LinkedHashMap<>();
            // Base term E.
            edges.stream().forEach(a -> next.merge(a.row(), a.annotation(), semiring::plus));
            // Composition term (T ∘ E): extend each derived pair (a, b) by an edge (b, c).
            for (Map.Entry<Row, K> e : result.entrySet()) {
                Value a = e.getKey().get(0);
                Value b = e.getKey().get(1);
                List<Annotated<K>> outgoing = byFrom.get(b);
                if (outgoing == null) {
                    continue;
                }
                for (Annotated<K> edge : outgoing) {
                    Row derived = ArrayRow.of(out, List.of(a, edge.row().get(1)));
                    next.merge(derived, semiring.times(e.getValue(), edge.annotation()), semiring::plus);
                }
            }
            next.values().removeIf(k -> k.equals(semiring.zero()));
            if (next.equals(result)) {
                break;
            }
            result = next;
        }

        // Reflexive-transitive closure (R*): add the identity pair for every node.
        if (cl.reflexive()) {
            K one = semiring.one();
            for (Value n : nodes) {
                result.merge(ArrayRow.of(out, List.of(n, n)), one, semiring::plus);
            }
            result.values().removeIf(k -> k.equals(semiring.zero()));
        }

        return AnnotatedRelation.normalise(out, semiring,
                result.entrySet().stream().map(e -> new Annotated<>(e.getKey(), e.getValue())));
    }

    /** {@return a mutable {@code Row → annotation} map of a canonical annotated relation} */
    private static <K> Map<Row, K> toMap(AnnotatedRelation<K> relation) {
        Map<Row, K> map = new LinkedHashMap<>();
        relation.stream().forEach(a -> map.put(a.row(), a.annotation()));
        return map;
    }

    /** True iff every shared column is non-null on both sides and equal; false when none are shared. */
    private static boolean naturalMatch(Row left, Row right, List<int[]> sharedPairs) {
        if (sharedPairs.isEmpty()) {
            return false;   // no common columns → no matches (mirrors the engine)
        }
        for (int[] pair : sharedPairs) {
            Value lv = left.get(pair[0]);
            Value rv = right.get(pair[1]);
            if (!ValueComparator.equal(lv, rv)) {
                return false;
            }
        }
        return true;
    }

    /** Left row's values ++ the right row's non-shared values, relabelled with {@code out}. */
    private static Row naturalConcat(Row left, Row right, List<Integer> rightOnly, Schema out) {
        List<Value> values = new ArrayList<>(out.width());
        for (int i = 0; i < left.width(); i++) {
            values.add(left.get(i));
        }
        for (int r : rightOnly) {
            values.add(right.get(r));
        }
        return ArrayRow.of(out, values);
    }

    /** Concatenates a left and right row into {@code out} (left values then right values). */
    private static Row concat(Row left, Row right, Schema out) {
        List<Value> values = new ArrayList<>(out.width());
        for (int i = 0; i < left.width(); i++) {
            values.add(left.get(i));
        }
        for (int i = 0; i < right.width(); i++) {
            values.add(right.get(i));
        }
        return ArrayRow.of(out, values);
    }

    /** Builds a projected row by evaluating each projected expression against {@code row}. */
    /** Re-labels {@code row} with {@code out} (same width, same values, in order). */
    private static Row reschema(Row row, Schema out) {
        if (row.schema().equals(out)) {
            return row;
        }
        List<Value> values = new ArrayList<>(row.width());
        for (int i = 0; i < row.width(); i++) values.add(row.get(i));
        return ArrayRow.of(out, values);
    }

    private static Row projectRow(List<ProjectedAttribute> attrs, Schema out, Row row, OperandEvaluator eval) {
        List<Value> values = new ArrayList<>(attrs.size());
        for (ProjectedAttribute attr : attrs) {
            values.add(eval.evaluate(attr.expression(), row));
        }
        return ArrayRow.of(out, values);
    }

    /**
     * Evaluates an opaque subtree with the ordinary engine and lifts each output row
     * via {@code ba} (the base annotator), assigning a 1-based occurrence ordinal so
     * the lineage annotator can mint a distinct variable per row. The resulting
     * annotated rows are {@link AnnotatedRelation#normalise normalised}, so equal
     * occurrences combine with {@code ⊕} — for {@code one()} this reproduces the cheap
     * {@code lift}, for lineage it yields {@code x₁ ⊕ x₂} on a merged duplicate.
     */
    private <K> AnnotatedRelation<K> liftOpaque(RelNode node, Semiring<K> semiring, ExecutionContext ctx,
                                                BaseAnnotator<K> ba) {
        Schema schema = schemaOf(node, ctx);
        String source = sourceLabel(node);
        long[] ordinal = {0};
        try (Stream<Row> rows = nodeExecutor.execute(node, ctx)) {
            return AnnotatedRelation.normalise(schema, semiring,
                    rows.map(row -> new Annotated<>(row, ba.annotate(source, ++ordinal[0], row))));
        }
    }

    /** A readable label for a leaf's provenance variables: a relation's name, else its operator. */
    private static String sourceLabel(RelNode node) {
        return node instanceof RelationNode rel
                ? rel.name()
                : node.getClass().getSimpleName().replaceFirst("Node$", "");
    }

    /**
     * Resolves a node's output schema: from the per-node inference annotations, or —
     * for a bare relation reference (e.g. a named-query target, absent from the
     * annotation map) — from the symbol table.
     */
    private static Schema schemaOf(RelNode node, ExecutionContext ctx) {
        return ctx.nodeSchemas().get(node).orElseGet(() -> {
            if (node instanceof RelationNode rel) {
                return ctx.symbolTable().lookupRelation(rel.name())
                        .map(RelationSymbol::schema)
                        .orElseThrow(() -> new EvaluationException(
                                "Unknown relation '" + rel.name() + "' in provenance evaluation"));
            }
            throw new EvaluationException(
                    "No schema annotation for provenance node " + node.getClass().getSimpleName()
                    + " — was semantic analysis run?");
        });
    }
}

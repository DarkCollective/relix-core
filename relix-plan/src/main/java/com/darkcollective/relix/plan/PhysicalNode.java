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
package com.darkcollective.relix.plan;

import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.ConsolidationFunction;
import com.darkcollective.relix.ast.AllocationSpec;
import com.darkcollective.relix.ast.ObjectiveSense;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.OptimizeConstraint;
import com.darkcollective.relix.ast.ProduceBound;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.SortSpecification;
import com.darkcollective.relix.ast.WindowFrame;
import com.darkcollective.relix.ast.WindowFunction;
import com.darkcollective.relix.cost.Ordering;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.relation.RelationSymbol;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;

/**
 * A node in a <em>physical</em> query plan — the executable form of a logical
 * {@link com.darkcollective.relix.ast.RelNode} tree, produced by the
 * {@link Planner}.
 *
 * <p>Where the logical tree describes <em>what</em> to compute, the physical plan
 * fixes <em>how</em>: the {@link Join} node records the chosen
 * {@link JoinAlgorithm} and {@link BuildSide}, and every node carries its already
 * resolved output {@link #schema()} so the executor never has to re-infer schemas
 * or look them up by node identity.
 *
 * <p>The hierarchy is sealed; the permitted operators are declared as nested
 * records.  Operators with no physical choice ({@link Select}, {@link Project},
 * {@link Rename}, {@link Aggregate}, {@link Sort}, {@link Limit}, {@link Distinct},
 * {@link SetOp}, {@link Division}) mirror their logical counterparts one-to-one;
 * {@link Join} consolidates every join flavour (including Cartesian product) plus
 * the physical strategy; {@link Scan} is the leaf that reads a base relation.
 * Views are <em>inlined</em> by the planner, so they never appear as a node.
 */
public sealed interface PhysicalNode {

    /** The output schema of this node; resolved at planning time. */
    Schema schema();

    /** Direct child plans, left-to-right; empty for a {@link Scan}. */
    List<PhysicalNode> children();

    /**
     * Returns this node with each direct child replaced by the result of applying
     * {@code f} to it, preserving every other component — schema, join algorithm,
     * predicates, keys, bounds.
     *
     * <p>The structural-rewrite helper {@code RelNode} has had all along, and the same
     * contract: this node is returned <em>reference-identical</em> when {@code f} returns
     * the same reference for every child, so a rewrite can detect "nothing changed" with
     * a {@code ==} check, and a sub-tree nothing touched is shared rather than copied.
     *
     * <p>It exists because a plan is otherwise only readable, not rewritable: asking what
     * the same query answers under a different join algorithm means rebuilding the spine
     * above the join that changed, and the alternative is a reconstruction {@code switch}
     * per caller — which is the shape that has twice been found silently dropping a
     * component.
     *
     * <p>"Child" means what {@link #children()} means, so the two cannot disagree: a
     * {@code Why}'s logical sub-tree and a {@code LateralJoin}'s per-row body are not
     * children of the physical plan and are carried through untouched.
     *
     * @param f the transformation to apply to each direct child; must not be null
     * @return the rewritten node, or {@code this} if no child changed
     */
    default PhysicalNode mapChildren(java.util.function.UnaryOperator<PhysicalNode> f) {
        return switch (this) {
            // Leaves in the sense children() means. `Why` carries a *logical* sub-tree and
            // `Scan` a relation symbol, so neither has a physical child to hand to f.
            case Scan ignored -> this;
            case Empty ignored -> this;
            case PushedScan ignored -> this;
            case RecursiveRef ignored -> this;
            case Why ignored -> this;
            case Spool n -> {
                PhysicalNode in = f.apply(n.input());
                yield in == n.input() ? n : new Spool(n.schema(), n.id(), in);
            }
            case Select n -> {
                PhysicalNode in = f.apply(n.input());
                yield in == n.input() ? n : new Select(n.schema(), n.predicate(), in);
            }
            case Project n -> {
                PhysicalNode in = f.apply(n.input());
                yield in == n.input() ? n : new Project(n.schema(), n.attributes(), in);
            }
            case Rename n -> {
                PhysicalNode in = f.apply(n.input());
                yield in == n.input() ? n : new Rename(n.schema(), n.relation(), n.pairs(), in);
            }
            case Distinct n -> {
                PhysicalNode in = f.apply(n.input());
                yield in == n.input() ? n : new Distinct(n.schema(), n.streaming(), in);
            }
            case Unnest n -> {
                PhysicalNode in = f.apply(n.input());
                yield in == n.input() ? n : new Unnest(n.schema(), n.column(), n.outer(), n.ordinalityColumn(), in);
            }
            case Closure n -> {
                PhysicalNode in = f.apply(n.input());
                yield in == n.input() ? n : new Closure(n.schema(), n.fromColumn(), n.toColumn(), n.undirected(), n.reflexive(), n.boundSource(), n.boundTarget(), in);
            }
            case Cluster n -> {
                PhysicalNode in = f.apply(n.input());
                yield in == n.input() ? n : new Cluster(n.schema(), n.fromColumn(), n.toColumn(), n.labelColumn(), in);
            }
            case Path n -> {
                PhysicalNode in = f.apply(n.input());
                yield in == n.input() ? n : new Path(n.schema(), n.fromColumn(), n.toColumn(), n.undirected(), n.minHops(), n.maxHops(), n.depthColumn(), n.boundSource(), n.boundTarget(), in);
            }
            case Trace n -> {
                PhysicalNode in = f.apply(n.input());
                yield in == n.input() ? n : new Trace(n.schema(), n.fromColumn(), n.toColumn(), n.undirected(), n.weightColumn(), n.sense(), n.pathColumn(), n.algorithm(), n.boundSource(), n.boundTarget(), in);
            }
            case Limit n -> {
                PhysicalNode in = f.apply(n.input());
                yield in == n.input() ? n : new Limit(n.schema(), n.offset(), n.count(), in);
            }
            case Sort n -> {
                PhysicalNode in = f.apply(n.input());
                yield in == n.input() ? n : new Sort(n.schema(), n.sortSpecs(), in);
            }
            case Aggregate n -> {
                PhysicalNode in = f.apply(n.input());
                yield in == n.input() ? n : new Aggregate(n.schema(), n.groupingKeys(), n.aggregates(), n.streaming(), in);
            }
            case Universal n -> {
                PhysicalNode in = f.apply(n.input());
                yield in == n.input() ? n : new Universal(n.schema(), n.groupingAttributes(), n.predicate(), in);
            }
            case TopK n -> {
                PhysicalNode in = f.apply(n.input());
                yield in == n.input() ? n : new TopK(n.schema(), n.groupingAttributes(), n.sortSpecs(), n.offset(), n.count(), in);
            }
            case Window n -> {
                PhysicalNode in = f.apply(n.input());
                yield in == n.input() ? n : new Window(n.schema(), n.function(), n.partitionKeys(), n.sortSpecs(), n.frame(), n.outputColumn(), in);
            }
            case Sessionize n -> {
                PhysicalNode in = f.apply(n.input());
                yield in == n.input() ? n : new Sessionize(n.schema(), n.orderColumn(), n.threshold(), n.partitionKeys(), n.sessionColumn(), in);
            }
            case Tree n -> {
                PhysicalNode in = f.apply(n.input());
                yield in == n.input() ? n : new Tree(n.schema(), n.keyColumn(), n.parentColumn(), n.orderSpecs(), n.childrenColumn(), in);
            }
            case BernoulliSample n -> {
                PhysicalNode in = f.apply(n.input());
                yield in == n.input() ? n : new BernoulliSample(n.schema(), n.probability(), n.seed(), in);
            }
            case ReservoirSample n -> {
                PhysicalNode in = f.apply(n.input());
                yield in == n.input() ? n : new ReservoirSample(n.schema(), n.count(), n.seed(), in);
            }
            case Solve n -> {
                PhysicalNode in = f.apply(n.input());
                yield in == n.input() ? n : new Solve(n.schema(), n.left(), n.right(), in);
            }
            case Optimize n -> {
                PhysicalNode in = f.apply(n.input());
                yield in == n.input() ? n : new Optimize(n.schema(), n.sense(), n.objective(), n.constraints(), n.groupingKeys(), n.allocation(), in);
            }
            case Cover n -> {
                PhysicalNode in = f.apply(n.input());
                yield in == n.input() ? n : new Cover(n.schema(), n.strength(), n.exact(), in);
            }
            case Downsample n -> {
                PhysicalNode in = f.apply(n.input());
                yield in == n.input() ? n : new Downsample(n.schema(), n.timestampColumn(), n.intervalSeconds(), n.function(), n.groupingKeys(), n.maxRows(), in);
            }
            case Unpivot n -> {
                PhysicalNode in = f.apply(n.input());
                yield in == n.input() ? n : new Unpivot(n.schema(), n.columns(), n.nameColumn(), n.valueColumn(), in);
            }
            case Pivot n -> {
                PhysicalNode in = f.apply(n.input());
                yield in == n.input() ? n : new Pivot(n.schema(), n.valueColumn(), n.keyColumn(), n.groupKeys(), in);
            }
            case Fixpoint n -> {
                PhysicalNode lv = f.apply(n.base()), rv = f.apply(n.step());
                yield (lv == n.base() && rv == n.step()) ? n
                        : new Fixpoint(n.schema(), n.name(), lv, rv);
            }
            case Join n -> {
                PhysicalNode lv = f.apply(n.left()), rv = f.apply(n.right());
                yield (lv == n.left() && rv == n.right()) ? n
                        : new Join(n.schema(), n.kind(), n.algorithm(), n.buildSide(), n.condition(), n.keys(), n.leftRelations(), n.rightRelations(), lv, rv);
            }
            case AsOfJoin n -> {
                PhysicalNode lv = f.apply(n.left()), rv = f.apply(n.right());
                yield (lv == n.left() && rv == n.right()) ? n
                        : new AsOfJoin(n.schema(), n.partitionKeys(), n.leftMatchIndex(), n.rightMatchIndex(), n.backward(), n.strict(), n.tolerance(), n.inner(), n.tieBreak(), lv, rv);
            }
            case IntervalJoin n -> {
                PhysicalNode lv = f.apply(n.left()), rv = f.apply(n.right());
                yield (lv == n.left() && rv == n.right()) ? n
                        : new IntervalJoin(n.schema(), n.relation(), n.leftStartIdx(), n.leftEndIdx(), n.rightStartIdx(), n.rightEndIdx(), n.merge(), lv, rv);
            }
            case SetOp n -> {
                PhysicalNode lv = f.apply(n.left()), rv = f.apply(n.right());
                yield (lv == n.left() && rv == n.right()) ? n
                        : new SetOp(n.schema(), n.kind(), lv, rv);
            }
            case Division n -> {
                PhysicalNode lv = f.apply(n.left()), rv = f.apply(n.right());
                yield (lv == n.left() && rv == n.right()) ? n
                        : new Division(n.schema(), lv, rv);
            }
            // Its right input is a TVF body planned per outer row, not a child here.
            case LateralJoin n -> {
                PhysicalNode l = f.apply(n.left());
                yield l == n.left() ? n : new LateralJoin(n.schema(), n.functionName(), n.arguments(), n.bodyBuilder(), n.deterministicBody(), l);
            }
            case ConstructiveCover n -> {
                List<PhysicalNode> mapped = n.factors().stream().map(f).toList();
                boolean same = mapped.size() == n.factors().size();
                for (int i = 0; same && i < mapped.size(); i++) {
                    same = mapped.get(i) == n.factors().get(i);
                }
                yield same ? n
                        : new ConstructiveCover(n.schema(), n.strength(), mapped, n.conjuncts());
            }
        };
    }

    /**
     * The {@link Ordering} that this node delivers on its output stream.
     * The default implementation returns {@link Ordering#none()} —
     * meaning no guaranteed row order — which is the conservative safe answer for
     * any operator that does not propagate or establish an ordering.  Individual
     * nodes that do preserve order ({@link Select}, {@link Limit}) or establish it
     * ({@link Sort}) override this method.
     *
     * @return the delivered ordering; never null
     */
    default Ordering deliveredOrdering() { return Ordering.none(); }

    // ── physical strategy enums ─────────────────────────────────────────────

    /**
     * How a join is executed.
     *
     * <ul>
     *   <li>{@link #HASH} — builds a hash index on the build side, probes with the
     *       stream side.  O(build-side) memory, O(1) probe per row.</li>
     *   <li>{@link #NESTED_LOOP} — nested iteration; used when no equi-join key is
     *       available.  O(1) memory but O(left × right) time.</li>
     *   <li>{@link #MERGE} — sort-merge join; requires both inputs sorted on the
     *       join keys (enforced by the planner via inserted {@link Sort} nodes).
     *       O(1) memory once inputs are in order; supports early termination.</li>
     * </ul>
     */
    enum JoinAlgorithm { HASH, NESTED_LOOP, MERGE }

    /** Which input a join materialises (hashes, or buffers for nested-loop). */
    enum BuildSide { LEFT, RIGHT }

    /** The join flavour. {@code PRODUCT} is an unconditioned Cartesian product. */
    enum JoinKind { INNER, NATURAL, SEMI, ANTI, UNIVERSAL_SEMI, LEFT_OUTER, RIGHT_OUTER, FULL_OUTER, PRODUCT }

    /** The set-operation flavour. */
    enum SetKind { UNION, UNION_ALL, OUTER_UNION, DIFFERENCE, INTERSECT }

    /**
     * Equi-join key columns: {@code left.get(i)} and {@code right.get(i)} are the
     * positions, in the left and right inputs, of the {@code i}-th equated column
     * pair.  Empty when the join has no hashable equality.
     */
    record JoinKeys(List<Integer> left, List<Integer> right) {
        public JoinKeys {
            left = List.copyOf(left);
            right = List.copyOf(right);
        }
        public boolean isEmpty() {
            return left.isEmpty();
        }
        public static JoinKeys none() {
            return new JoinKeys(List.of(), List.of());
        }
    }

    // ── leaves ──────────────────────────────────────────────────────────────

    /**
     * Reads a base relation: inline rows, or an external source/database via the connector.
     *
     * <p>{@code qualifier} is the name the query referenced the relation by, which is the
     * qualifier its columns answer to. A declared heading carries it as column provenance;
     * a schema-on-read row has no heading to carry it, so the executor anchors each
     * document to it.
     *
     * @param schema       the relation's heading
     * @param source       the relation read
     * @param produceBound a generator production bound, if one was pushed
     * @param qualifier    the name the query referenced the relation by, when known
     */
    record Scan(Schema schema, RelationSymbol source,
                Optional<ProduceBound> produceBound, Optional<String> qualifier) implements PhysicalNode {

        /** Scan without a generator production bound. */
        Scan(Schema schema, RelationSymbol source) {
            this(schema, source, Optional.empty());
        }

        /** Scan under no particular qualifier. */
        Scan(Schema schema, RelationSymbol source, Optional<ProduceBound> produceBound) {
            this(schema, source, produceBound, Optional.empty());
        }

        @Override public List<PhysicalNode> children() { return List.of(); }
    }

    /**
     * Produces no rows at all, under {@code schema}'s heading — the physical form of
     * {@link com.darkcollective.relix.ast.EmptyRelationNode}, planted by the
     * optimizer where a sub-tree was proved unsatisfiable.
     *
     * <p>The point of the node is what it does <em>not</em> contain: the sub-plan it
     * replaced is gone, so nothing under it is scanned, joined or aggregated. That is
     * the whole win — a contradictory filter costs one empty stream rather than a full
     * scan and everything above it.
     */
    record Empty(Schema schema) implements PhysicalNode {

        @Override public List<PhysicalNode> children() { return List.of(); }
    }

    /**
     * A leaf that pushes a relational sub-expression down to a connector as a
     * single native query.  The planner emits this in place of a {@link Scan}
     * (and the operators folded into it) when a sub-tree over one connection's
     * tables can be translated to the connector's native query language; the
     * executor runs {@link #nativeQuery()} on the named {@link #connection()} via
     * {@code DataSourceConnector.openQuery} (relix-processor, which depends on
     * this module rather than the other way round, hence no link) and reads the
     * result positionally into {@link #schema()}.
     *
     * <p>When the pushed query ends in an {@code ORDER BY} (or equivalent sort
     * directive) that the planner folded from a sort, the scan advertises the
     * resulting row order via {@link #ordering} (and {@link #deliveredOrdering()}),
     * so a downstream merge join or streaming δ/γ can treat the source as already
     * sorted.  A scan with no pushed ordering carries
     * {@link Ordering#none()}.
     *
     * @param schema        the output schema; its column order matches the query result
     * @param connectorType the connector type token (e.g. {@code "jdbc"}) that
     *                      identifies which renderer produced this scan
     * @param connection    the canonical name of the connection to run the query on
     * @param nativeQuery   the backend-native query text (SQL for JDBC, etc.)
     * @param ordering      the row order the query guarantees on its output;
     *                      {@link Ordering#none()} when unordered
     */
    record PushedScan(Schema schema, String connectorType, String connection,
                      String nativeQuery, Ordering ordering) implements PhysicalNode {
        /** A pushed scan with no guaranteed row order. */
        public PushedScan(Schema schema, String connectorType, String connection, String nativeQuery) {
            this(schema, connectorType, connection, nativeQuery, Ordering.none());
        }
        @Override public List<PhysicalNode> children() { return List.of(); }
        @Override public Ordering deliveredOrdering() { return ordering; }
    }

    // ── sharing ─────────────────────────────────────────────────────────────

    /**
     * Marks a sub-plan whose rows are computed once and read by more than one
     * consumer.  The <em>same</em> {@code Spool} appears at every site that reads it,
     * so the plan is a directed acyclic graph at this node rather than a tree.
     *
     * <p>Without it, a plan is a tree and every occurrence of a sub-expression is
     * executed independently — which is why the symmetric difference
     * {@code A ∆ B}, whose two branches {@code (A − B)} and {@code (B − A)} each
     * read both inputs, would otherwise evaluate {@code A} and {@code B} twice
     * apiece.
     *
     * <p>The executor keys its buffer on {@link #id}, not on object identity, so a
     * plan that is copied or rebuilt keeps sharing what it shared before.  Ids are
     * unique within one planned query and carry no meaning across queries.
     *
     * <p><b>Sharing is not free and not always allowed.</b>  Replaying rows means
     * holding them, so the executor buffers under a row budget and falls back to
     * re-executing the sub-plan when the budget is exceeded — never a wrong answer,
     * only the cost that was being avoided.  The planner also declines to spool a
     * sub-expression whose two evaluations are entitled to differ: one that reads
     * system state (a random draw, an unseeded sample), or one that names a
     * recursive relation whose value changes with every iteration.
     *
     * <p>A spool is transparent to everything above it: it emits exactly its input's
     * rows, in its input's order, under its input's schema, so no consumer — including
     * a positional one — can tell a spooled sub-plan from an unspooled one.
     *
     * @param schema the output schema; always the input's schema
     * @param id     the identifier the executor buffers this sub-plan under; unique
     *               within one planned query
     * @param input  the sub-plan whose rows are shared
     */
    record Spool(Schema schema, int id, PhysicalNode input) implements PhysicalNode {
        @Override public List<PhysicalNode> children() { return List.of(input); }
        @Override public Ordering deliveredOrdering() { return input.deliveredOrdering(); }
    }

    // ── streaming unary operators ───────────────────────────────────────────

    /** Selection (σ) — streaming row filter; preserves the input's delivered ordering. */
    record Select(Schema schema, Predicate predicate, PhysicalNode input) implements PhysicalNode {
        @Override public List<PhysicalNode> children() { return List.of(input); }
        @Override public Ordering deliveredOrdering() { return input.deliveredOrdering(); }
    }

    record Project(Schema schema, List<ProjectedAttribute> attributes, PhysicalNode input) implements PhysicalNode {
        @Override public List<PhysicalNode> children() { return List.of(input); }
    }

    /**
     * Relation/column rename — a metadata-only relabel to {@link #schema()}.
     *
     * <p>A declared row is relabelled by the heading alone. A schema-on-read row has no
     * heading to relabel, so the executor applies the rename to the document itself:
     * {@code pairs} renames the fields it names, and {@code relation} re-anchors the
     * document to the new relation name.
     *
     * @param schema   the renamed heading
     * @param relation the new relation name, if the rename supplies one
     * @param pairs    the {@code old → new} pairs of the pair form; empty otherwise
     * @param input    the renamed sub-plan
     */
    record Rename(Schema schema, Optional<String> relation,
                  List<RenameNode.RenamePair> pairs,
                  PhysicalNode input) implements PhysicalNode {

        public Rename {
            pairs = List.copyOf(pairs);
        }

        /** A rename that gives no new relation name. */
        public Rename(Schema schema, PhysicalNode input) {
            this(schema, Optional.empty(), List.of(), input);
        }

        @Override public List<PhysicalNode> children() { return List.of(input); }
    }

    /**
     * Distinct (δ) — eliminates duplicate rows.  When {@code streaming} is set the
     * planner has proven the input delivers an ordering covering the whole row, so the executor
     * deduplicates in a single linear pass
     * (comparing adjacent rows) instead of buffering a hash set; the streaming
     * variant therefore preserves the input's delivered ordering.  Otherwise the
     * executor falls back to the hash-based distinct.
     */
    record Distinct(Schema schema, boolean streaming, PhysicalNode input) implements PhysicalNode {
        @Override public List<PhysicalNode> children() { return List.of(input); }
        @Override public Ordering deliveredOrdering() {
            return streaming ? input.deliveredOrdering() : Ordering.none();
        }
    }

    /**
     * Unnest (μ) — explodes the array-valued {@code column} into one row per
     * element; {@code outer} keeps a NULL-bound row when the array is empty/missing.
     * When {@code ordinalityColumn} is present (SQL {@code WITH ORDINALITY}), an extra
     * {@code NUMBER} column of that name carries each element's 1-based position.
     */
    record Unnest(Schema schema, String column, boolean outer,
                  java.util.Optional<String> ordinalityColumn, PhysicalNode input)
            implements PhysicalNode {
        @Override public List<PhysicalNode> children() { return List.of(input); }
    }

    /**
     * Transitive closure (least fixpoint) of the {@code input} edge relation over
     * {@link #fromColumn()}/{@link #toColumn()}.  {@link #reflexive()} selects
     * {@code R*} (adds identity pairs) over {@code R⁺}.  Evaluated in-engine by an
     * iterative fixpoint under set semantics; never pushed to a source.
     *
     * <p>{@link #boundSource()} / {@link #boundTarget()} are the optional constant
     * endpoint bounds folded in by the optimizer ({@code CLOSURE-001}):
     * a present {@code boundSource} runs single-source reachability seeded from
     * that literal, a present {@code boundTarget} runs single-target reachability
     * over the reversed adjacency, and both present is a single-pair check. Each is
     * a <em>literal</em> {@link Operand}; the output schema is unchanged.
     */
    record Closure(Schema schema, String fromColumn, String toColumn,
                   boolean undirected, boolean reflexive,
                   Optional<Operand> boundSource, Optional<Operand> boundTarget,
                   PhysicalNode input) implements PhysicalNode {

        /** Unbounded closure (no endpoint pushdown) — both bounds empty. */
        Closure(Schema schema, String fromColumn, String toColumn,
                boolean undirected, boolean reflexive, PhysicalNode input) {
            this(schema, fromColumn, toColumn, undirected, reflexive,
                    Optional.empty(), Optional.empty(), input);
        }

        @Override public List<PhysicalNode> children() { return List.of(input); }
    }

    /**
     * Connected-components labelling (CLUSTER) of the {@code input} edge relation,
     * read as undirected edges over {@link #fromColumn()}/{@link #toColumn()}.
     * Emits one row per distinct node: the node identifier plus its
     * {@link #labelColumn()} component id (a dense 1-based NUMBER, canonical by the
     * component's minimum node id).  Evaluated in-engine by an undirected union-find
     * over the whole edge set; never pushed to a source.
     */
    record Cluster(Schema schema, String fromColumn, String toColumn,
                   String labelColumn, PhysicalNode input) implements PhysicalNode {
        @Override public List<PhysicalNode> children() { return List.of(input); }
    }

    /**
     * Bounded variable-length path reachability (PATH) over the {@code input} edge
     * relation, read over {@link #fromColumn()}/{@link #toColumn()} as directed edges, or
     * as undirected ones when {@link #undirected()}.
     * Emits one row {@code (from, to, depth)} for every pair connected by a directed
     * path whose length lies within {@code [minHops, maxHops]}, where {@code depth}
     * is the shortest such length ({@link #depthColumn()}, a NUMBER).  Evaluated
     * in-engine by a bounded breadth-first traversal; never pushed to a source.
     *
     * <p>{@link #boundSource()} / {@link #boundTarget()} carry the endpoint bounds the
     * optimizer folds in ({@code PATH-001}): a present {@code boundSource} seeds the
     * traversal at that literal instead of at every node, a present {@code boundTarget}
     * searches the reversed adjacency from it, and both present is a single-pair search.
     * The distance is unaffected — the shortest path from a seed does not depend on which
     * other seeds were present — so the bounded result is a slice of the unbounded one.
     */
    record Path(Schema schema, String fromColumn, String toColumn, boolean undirected,
                int minHops, int maxHops, String depthColumn,
                Optional<Operand> boundSource, Optional<Operand> boundTarget,
                PhysicalNode input) implements PhysicalNode {

        /** Unbounded path (no endpoint pushdown) — both bounds empty. */
        Path(Schema schema, String fromColumn, String toColumn, boolean undirected,
             int minHops, int maxHops, String depthColumn, PhysicalNode input) {
            this(schema, fromColumn, toColumn, undirected, minHops, maxHops, depthColumn,
                    Optional.empty(), Optional.empty(), input);
        }

        @Override public List<PhysicalNode> children() { return List.of(input); }
    }

    /**
     * Optimal-path extraction (TRACE) over the {@code input} weighted edge relation, read
     * as directed edges or, when {@link #undirected()}, as undirected ones. Computes, for every reachable {@code (from, to)} pair, the path that
     * minimises or maximises the total edge weight, returning the traversed node
     * sequence as an ordered array in {@link #pathColumn()}. Evaluated in-engine via a
     * Bellman-Ford–style all-pairs fixpoint; never pushed to a source.
     */
    record Trace(Schema schema, String fromColumn, String toColumn, boolean undirected,
                 String weightColumn, ObjectiveSense sense, String pathColumn,
                 TraceAlgorithm algorithm,
                 Optional<Operand> boundSource, Optional<Operand> boundTarget,
                 PhysicalNode input) implements PhysicalNode {

        /** Bounded trace evaluated by the default relaxation fixpoint. */
        Trace(Schema schema, String fromColumn, String toColumn, boolean undirected,
              String weightColumn, ObjectiveSense sense, String pathColumn,
              Optional<Operand> boundSource, Optional<Operand> boundTarget,
              PhysicalNode input) {
            this(schema, fromColumn, toColumn, undirected, weightColumn, sense, pathColumn,
                    TraceAlgorithm.RELAXATION, boundSource, boundTarget, input);
        }

        @Override public List<PhysicalNode> children() { return List.of(input); }
    }

    /**
     * General monotone recursion (FIX): computes the semi-naïve least-fixpoint of
     * {@link #step()} seeded by {@link #base()}, with the relation named
     * {@link #name()} bound to the current delta during each step evaluation.  Output
     * is the union of all step iterates under set semantics (no duplicate row ever
     * re-added); materialises a set.  Never pushed to a source.
     *
     * @param name  the bound recursive relation name (must match the name used by
     *              {@link RecursiveRef} nodes inside {@code step})
     * @param base  the non-recursive seed; planned independently
     * @param step  the recursive body; evaluated repeatedly with {@code name} bound
     *              to the current delta
     */
    record Fixpoint(Schema schema, String name, PhysicalNode base, PhysicalNode step)
            implements PhysicalNode {
        @Override public List<PhysicalNode> children() { return List.of(base, step); }
    }

    /**
     * A reference to the recursive relation bound by an enclosing {@link Fixpoint}.
     * At execution time, streams the current delta from the executor's recursion
     * binding map.  Always a leaf (no children); schema matches the enclosing
     * {@code Fixpoint}'s output schema.
     *
     * @param name the bound recursive relation name (identifies the binding in the
     *             executor's recursion map)
     */
    record RecursiveRef(Schema schema, String name) implements PhysicalNode {
        @Override public List<PhysicalNode> children() { return List.of(); }
    }

    /** Limit (λ) — takes a prefix of the input; preserves the input's delivered ordering. */
    record Limit(Schema schema, Optional<Long> offset, long count, PhysicalNode input) implements PhysicalNode {
        @Override public List<PhysicalNode> children() { return List.of(input); }
        @Override public Ordering deliveredOrdering() { return input.deliveredOrdering(); }
    }

    // ── materialising unary operators ───────────────────────────────────────

    /** Sort (τ) — establishes ordering on its sort keys. */
    record Sort(Schema schema, List<SortSpecification> sortSpecs, PhysicalNode input) implements PhysicalNode {
        @Override public List<PhysicalNode> children() { return List.of(input); }
        @Override public Ordering deliveredOrdering() { return Ordering.of(sortSpecs); }
    }

    /**
     * Aggregation (γ) — groups by {@code groupingKeys} and reduces each group
     * with {@code aggregates}.  When {@code streaming} is set the planner has proven
     * the input delivers an ordering grouping the rows by the grouping keys, so the executor
     * aggregates in a single linear pass
     * holding only one group at a time instead of building a full hash index;
     * otherwise it falls back to the hash-grouped aggregate.
     */
    record Aggregate(Schema schema, List<GroupingKey> groupingKeys,
                     List<AggregateFunction> aggregates, boolean streaming,
                     PhysicalNode input) implements PhysicalNode {
        @Override public List<PhysicalNode> children() { return List.of(input); }

        /**
         * A streaming γ emits one row per group in the order the groups arrive — i.e.
         * by the input's leading keys, which (by the streaming gate) are exactly the
         * grouping keys.  Those grouping-key columns survive into the output, so the
         * delivered ordering is that grouping-key prefix of the input's ordering; a
         * downstream merge join or streaming δ/γ can reuse it.  A hash-grouped γ
         * buffers and emits in unspecified order, so it delivers {@link Ordering#none()}.
         */
        @Override public Ordering deliveredOrdering() {
            if (!streaming) return Ordering.none();
            return Ordering.of(input.deliveredOrdering().keys()
                    .subList(0, groupingKeys.size()));
        }
    }

    /**
     * Group-wise universal quantification (∀): keeps the grouping-key tuple of
     * each group in which every row of {@code input} satisfies {@code predicate}
     * (strict NULL semantics — an UNKNOWN row disqualifies its group).
     */
    record Universal(Schema schema, List<String> groupingAttributes,
                     Predicate predicate, PhysicalNode input) implements PhysicalNode {
        @Override public List<PhysicalNode> children() { return List.of(input); }
    }

    /**
     * Top-k per group: within each partition (by {@code groupingAttributes}) keeps
     * the {@code count} rows highest by {@code sortSpecs}, after skipping
     * {@code offset}.  Output rows are the full input rows.
     */
    record TopK(Schema schema, List<String> groupingAttributes,
                List<SortSpecification> sortSpecs, Optional<Long> offset, long count,
                PhysicalNode input) implements PhysicalNode {
        @Override public List<PhysicalNode> children() { return List.of(input); }
    }

    /**
     * Window (ROLLING / WINDOW): adds one computed column ({@code outputColumn}) to
     * every input row, partitioned by {@code partitionKeys} and ordered within each
     * partition by {@code sortSpecs}.  The {@code frame} sets the scope of rows fed
     * to {@code function}.  Output schema = input schema + the appended column.
     */
    record Window(Schema schema, WindowFunction function, List<String> partitionKeys,
                  List<SortSpecification> sortSpecs, WindowFrame frame, String outputColumn,
                  PhysicalNode input) implements PhysicalNode {
        @Override public List<PhysicalNode> children() { return List.of(input); }
    }

    /**
     * Gap-and-island / sessionization (SESSIONIZE): within each partition (by
     * {@code partitionKeys}) orders rows ascending by {@code orderColumn} and
     * appends a 1-based session-id column ({@code sessionColumn}), incremented
     * whenever the gap to the prior row exceeds {@code threshold}.  Output schema =
     * input schema + the appended {@code NUMBER} session column.
     */
    record Sessionize(Schema schema, String orderColumn, Operand threshold,
                      List<String> partitionKeys, String sessionColumn,
                      PhysicalNode input) implements PhysicalNode {
        @Override public List<PhysicalNode> children() { return List.of(input); }
    }

    /**
     * Adjacency-to-forest nesting (TREE): folds the adjacency relation
     * {@code input} (with node key {@code keyColumn} and parent key
     * {@code parentColumn}) into a forest of nested documents — one output row per
     * root, each carrying its subtree in the appended {@code childrenColumn} array
     * (siblings ordered by {@code orderSpecs}, empty = input order).  Output schema =
     * input schema + the {@code childrenColumn} nested {@code ANY} column.  Blocking;
     * never pushed down.
     */
    record Tree(Schema schema, String keyColumn, String parentColumn,
                List<SortSpecification> orderSpecs, String childrenColumn,
                PhysicalNode input) implements PhysicalNode {
        @Override public List<PhysicalNode> children() { return List.of(input); }
    }

    /**
     * Lineage reification (WHY): emits every result tuple of its input
     * unchanged plus the reserved {@code provenance:ANY} column holding that tuple's
     * lineage polynomial as a nested document.  Output schema = input schema + the
     * {@code provenance} column.
     *
     * <p>WHY is a <em>self-terminating reification boundary</em>: its input is carried
     * as the <em>logical</em> {@link RelNode} subtree ({@link #logicalInput()}), because
     * the executor evaluates it as a lineage K-relation through the
     * separate {@code ProvenanceEvaluator} path (threading the polynomial semiring,
     * inlining views, reading non-positive operators as opaque bases) rather than through
     * the streaming physical executor. The subtree therefore has no <em>physical</em>
     * children and is never pushed to a source (pushing would compute the rows in the
     * backend and lose the row-level lineage). Blocking; a hard optimizer barrier (no
     * pass rewrites inside or across it).
     */
    record Why(Schema schema, RelNode logicalInput) implements PhysicalNode {
        @Override public List<PhysicalNode> children() { return List.of(); }
    }

    /**
     * Bernoulli sampling (SAMPLE p [SEED n]): keeps each row of {@code input}
     * independently with probability {@code probability}.  When {@code seed} is
     * present the random draw is deterministic (same seed ⇒ same selection);
     * otherwise fresh randomness is used each run.  Output schema = input schema.
     */
    record BernoulliSample(Schema schema, double probability, java.util.Optional<Long> seed,
                           PhysicalNode input) implements PhysicalNode {
        @Override public List<PhysicalNode> children() { return List.of(input); }
    }

    /**
     * Reservoir (fixed-count) sampling (SAMPLE … ROWS [SEED n]): keeps exactly
     * {@code count} rows of {@code input}, chosen uniformly at random without
     * replacement (or the whole input when it has fewer rows).  When {@code seed}
     * is present the sampling is deterministic.  Buffers the input via a reservoir;
     * output rows are the full input rows and the schema equals the input schema.
     */
    record ReservoirSample(Schema schema, long count, java.util.Optional<Long> seed,
                           PhysicalNode input) implements PhysicalNode {
        @Override public List<PhysicalNode> children() { return List.of(input); }
    }

    /**
     * Goal-seek (SOLVE): for each row of {@code input}, fills the single NULL
     * column participating in the equation {@code left = right} by inverting the
     * arithmetic.  Rows without exactly one NULL participating column pass through
     * unchanged.  Output schema equals the input schema.
     */
    record Solve(Schema schema, Operand left, Operand right,
                 PhysicalNode input) implements PhysicalNode {
        @Override public List<PhysicalNode> children() { return List.of(input); }
    }

    /**
     * Declarative optimisation (OPTIMIZE): within each group (by
     * {@code groupingKeys}) either selects the optimal subset (MIP, when
     * {@code allocation} is empty) or assigns continuous allocations (LP, when
     * {@code allocation} is present).
     *
     * <p>MIP: emits chosen input rows unchanged; output schema equals input schema.
     * LP: emits all input rows with the allocation value appended; output schema =
     * input + 1 column.
     */
    record Optimize(Schema schema, ObjectiveSense sense, Operand objective,
                    List<OptimizeConstraint> constraints, List<String> groupingKeys,
                    Optional<AllocationSpec> allocation,
                    PhysicalNode input) implements PhysicalNode {
        @Override public List<PhysicalNode> children() { return List.of(input); }
    }

    // ── binary operators ──────────────────────────────────────────────────────

    /**
     * A join of any kind, carrying the chosen physical strategy.
     *
     * <p>A {@link JoinAlgorithm#MERGE} join {@linkplain #deliveredOrdering() advertises
     * the order} its sorted-merge scan produces, so a downstream merge join, streaming
     * {@code δ}, or streaming {@code γ} can reuse it instead of re-sorting rows that
     * are already in order.
     *
     * @param condition       the join predicate; empty for {@link JoinKind#NATURAL}
     *                        and {@link JoinKind#PRODUCT}
     * @param keys            the equi-join key columns used for a
     *                        {@link JoinAlgorithm#HASH} join (for {@code NATURAL}, the
     *                        common columns); empty for a nested-loop join and products
     * @param leftRelations   relation names reachable on the left side, used to
     *                        disambiguate qualified attribute references when
     *                        re-evaluating {@code condition}; empty when there is no
     *                        condition
     * @param rightRelations  relation names reachable on the right side
     */
    record Join(Schema schema, JoinKind kind, JoinAlgorithm algorithm, BuildSide buildSide,
                Optional<Predicate> condition, JoinKeys keys,
                Set<String> leftRelations, Set<String> rightRelations,
                PhysicalNode left, PhysicalNode right) implements PhysicalNode {
        public Join {
            leftRelations = Set.copyOf(leftRelations);
            rightRelations = Set.copyOf(rightRelations);
        }
        @Override public List<PhysicalNode> children() { return List.of(left, right); }

        /**
         * The order a {@link JoinAlgorithm#MERGE} join delivers; {@link Ordering#none()}
         * for every other algorithm (a hash or nested-loop scan makes no order promise).
         * The left key column names are the ones that survive into {@link #schema()} —
         * a concatenated schema keeps the left names verbatim and only disambiguates
         * duplicated right ones, a natural join keeps the common column from the left,
         * and a semi/anti join's schema <em>is</em> the left schema — so the keys named
         * here are always addressable in the output.
         *
         * <p>What is claimed depends on the join kind:
         *
         * <ul>
         *   <li><b>{@code INNER} / {@code NATURAL}</b> — the merge keys, ascending: the
         *       scan visits equal-key batches in ascending key order, so the key columns
         *       are ordered on the output regardless of how a batch's rows are paired.
         *       Built by {@link Planner#mergeOrdering} — the same call the planner uses
         *       to decide the merge is feasible, so the required and delivered orderings
         *       cannot drift apart.</li>
         *   <li><b>{@code SEMI}</b> — the left input's delivered ordering, which is at
         *       least as strong as the merge keys: the output rows <em>are</em> left
         *       rows, emitted as a subsequence of the left stream.</li>
         *   <li><b>{@code ANTI}</b> — nothing. The anti-join executor emits left rows
         *       with a NULL join key <em>ahead of</em> the sorted run (they can never
         *       match), while an ASC sort places NULLs last, so the output is not in
         *       key order. {@link Ordering} has no null-placement dimension to express
         *       the difference, so the honest answer is {@code none()}.</li>
         * </ul>
         *
         * <p>The other kinds never reach {@code MERGE} (see {@code Planner.mergeEligible}),
         * and fall through to {@code none()} if one ever does.
         *
         * <p>NULL placement does not otherwise arise here: the merge executor filters
         * NULL-key rows out of both inputs before scanning, so — the anti-join case
         * above aside — no NULL key reaches the output to be placed.
         */
        @Override public Ordering deliveredOrdering() {
            if (algorithm != JoinAlgorithm.MERGE) {
                return Ordering.none();
            }
            return switch (kind) {
                case INNER, NATURAL -> Planner.mergeOrdering(keys.left(), left.schema());
                case SEMI           -> left.deliveredOrdering();
                default             -> Ordering.none();
            };
        }
    }

    /**
     * AS-OF join — a temporal "pick the nearest right row by time" join.
     * For each left (probe) row, among the right rows agreeing on the
     * {@link #partitionKeys} and satisfying the ordering relation between
     * {@link #leftMatchIndex} and {@link #rightMatchIndex}, emits the probe
     * concatenated with the single nearest right row.
     *
     * <p>{@link #backward} selects the direction: {@code true} keeps the greatest
     * {@code right[match]} at-or-before the probe ("most recent as-of"), {@code false}
     * keeps the least {@code right[match]} at-or-after.  {@link #strict} excludes an
     * exact-value match (a {@code >}/{@code <} condition rather than {@code >=}/{@code <=}).
     *
     * <p>When {@link #inner} is {@code false} (default, left-outer), every probe
     * survives with NULL right columns on no match.  When {@code true} (inner),
     * unmatched probes are dropped.
     *
     * <p>An optional {@link #tolerance} imposes a maximum temporal distance between the
     * probe and the matched right row; a candidate exceeding the tolerance is treated as
     * no-match.
     *
     * <p>{@link #tieBreak} resolves ties among right rows sharing the nearest match
     * value: {@code LAST} keeps the last in input order (default); {@code FIRST} keeps
     * the first.
     *
     * @param partitionKeys   the equality (partition) key columns, left/right indices
     * @param leftMatchIndex  the probe's match (ordering) column index
     * @param rightMatchIndex the right input's match (ordering) column index
     * @param tolerance       optional maximum temporal distance for the match
     * @param inner           when {@code true}, drop unmatched probes
     * @param tieBreak        tie-break rule for tied nearest-match values
     */
    record AsOfJoin(Schema schema, JoinKeys partitionKeys,
                    int leftMatchIndex, int rightMatchIndex, boolean backward, boolean strict,
                    java.util.Optional<java.time.Duration> tolerance,
                    boolean inner,
                    com.darkcollective.relix.ast.TieBreak tieBreak,
                    PhysicalNode left, PhysicalNode right) implements PhysicalNode {
        /** Convenience constructor preserving backward compatibility (no tolerance, left-outer, LAST). */
        public AsOfJoin(Schema schema, JoinKeys partitionKeys,
                        int leftMatchIndex, int rightMatchIndex, boolean backward, boolean strict,
                        PhysicalNode left, PhysicalNode right) {
            this(schema, partitionKeys, leftMatchIndex, rightMatchIndex, backward, strict,
                 java.util.Optional.empty(), false, com.darkcollective.relix.ast.TieBreak.LAST, left, right);
        }
        @Override public List<PhysicalNode> children() { return List.of(left, right); }
        @Override public Ordering deliveredOrdering() { return left.deliveredOrdering(); }
    }

    /**
     * Interval join — tests each pair of rows against an Allen interval
     * algebra relation.  Always an inner join (unmatched rows are dropped).
     *
     * <p>{@link #merge} selects the executor variant:
     * {@code false} (default) runs the general plane-sweep / sorted-band executor,
     * which sorts the inputs itself; {@code true} runs a streaming sort-merge over
     * two inputs that <em>already</em> deliver an ascending order on their interval
     * start column, skipping the global endpoint sort and maintaining a bounded
     * sliding active set.  The planner only sets {@code merge} for an
     * overlap-or-touch relation when both children already satisfy the required
     * ordering (no inserted {@link Sort}); {@code PRECEDES}/{@code PRECEDED_BY} always
     * use the sorted-band executor.
     *
     * @param relation       the Allen relation to test
     * @param leftStartIdx   column index of the left interval's start in the left input
     * @param leftEndIdx     column index of the left interval's end in the left input
     * @param rightStartIdx  column index of the right interval's start in the right input
     * @param rightEndIdx    column index of the right interval's end in the right input
     * @param merge          when {@code true}, use the streaming sort-merge variant
     */
    record IntervalJoin(Schema schema,
                        com.darkcollective.relix.ast.AllenRelation relation,
                        int leftStartIdx, int leftEndIdx,
                        int rightStartIdx, int rightEndIdx,
                        boolean merge,
                        PhysicalNode left, PhysicalNode right) implements PhysicalNode {
        /** Convenience constructor for the default (non-merge) plane-sweep variant. */
        public IntervalJoin(Schema schema,
                            com.darkcollective.relix.ast.AllenRelation relation,
                            int leftStartIdx, int leftEndIdx,
                            int rightStartIdx, int rightEndIdx,
                            PhysicalNode left, PhysicalNode right) {
            this(schema, relation, leftStartIdx, leftEndIdx, rightStartIdx, rightEndIdx, false, left, right);
        }
        @Override public List<PhysicalNode> children() { return List.of(left, right); }
    }

    record SetOp(Schema schema, SetKind kind, PhysicalNode left, PhysicalNode right) implements PhysicalNode {
        @Override public List<PhysicalNode> children() { return List.of(left, right); }
    }

    record Division(Schema schema, PhysicalNode left, PhysicalNode right) implements PhysicalNode {
        @Override public List<PhysicalNode> children() { return List.of(left, right); }
    }

    /**
     * Covering reduction (COVER): keeps a near-minimal subset of {@code input} rows
     * such that every distinct t-column value combination ({@link #strength}-way tuple)
     * occurring in the input occurs in the output.  Output schema equals the input
     * schema (a windowed filter like {@link TopK} and {@link Optimize}).
     *
     * <p>When {@link #exact} is {@code false} (default), executed in-engine by a greedy
     * algorithm: buffer candidates, build the coverage universe (one set of
     * demanded tuples per C(w,t) column subset), then repeatedly select the
     * highest-scoring candidate (earliest-arrival tie-break) until the universe is empty.
     *
     * <p>When {@link #exact} is {@code true} ({@code COVER EXACT t (R)}), uses a MIP
     * set-cover formulation via ojAlgo to find a provably minimal suite.
     *
     * <p>Never pushed to a source.
     *
     * @param strength the covering strength {@code t}: every t-column value
     *                 combination occurring in the input must occur in the output
     * @param exact    when {@code true}, use MIP set-cover for an exact minimum suite
     */
    record Cover(Schema schema, int strength, boolean exact, PhysicalNode input) implements PhysicalNode {
        @Override public List<PhysicalNode> children() { return List.of(input); }
    }

    /**
     * Constructive covering reduction (COVER, constructive mode): builds
     * candidate rows value-by-value over the
     * {@link #factors} domains, using {@link #conjuncts} as a validity oracle,
     * <em>never materialising the full Cartesian product</em>.
     *
     * <p>The planner emits this node when it recognises the pattern
     * {@code Cover(t, σ?(Product(leaves…)))}; the materialized {@link Cover} node
     * is emitted for all other inputs.  The constructive executor builds each test
     * row factor-by-factor: at each step it enumerates candidate values from that
     * factor's domain, checks any conjuncts whose referenced columns are now fully
     * bound (partial-row validity), scores by uncovered-tuple gain, and commits the
     * best choice.  The universe is built directly from the factor domains rather
     * than the materialized product.
     *
     * @param strength  the covering strength {@code t}
     * @param factors   physical nodes for the individual factor (leaf) relations;
     *                  each is executed independently to obtain its domain rows
     * @param conjuncts the selection predicate split into atomic conjuncts; evaluated
     *                  only when all referenced columns are bound (partial-row oracle)
     */
    record ConstructiveCover(Schema schema, int strength,
                             List<PhysicalNode> factors,
                             List<Predicate> conjuncts) implements PhysicalNode {
        public ConstructiveCover {
            if (strength < 1) throw new IllegalArgumentException("strength must be >= 1");
            factors   = List.copyOf(factors);
            conjuncts = List.copyOf(conjuncts);
        }
        @Override public List<PhysicalNode> children() { return factors; }
    }

    /**
     * Time-series downsampling (DOWNSAMPLE): groups input rows into fixed-width
     * time buckets and consolidates numeric columns using the chosen
     * {@link ConsolidationFunction}.  Output schema is the grouping keys +
     * {@code bucket:TIMESTAMP} + one column per numeric non-key column named
     * {@code <fn>_<col>} (or just {@code count} for COUNT).
     *
     * @param timestampColumn the column holding the row's timestamp
     * @param intervalSeconds bucket width in seconds
     * @param function        how to consolidate each numeric column
     * @param groupingKeys    additional grouping keys (may be empty)
     * @param maxRows         optional limit on the most-recent buckets to emit
     */
    record Downsample(Schema schema, String timestampColumn, long intervalSeconds,
                      ConsolidationFunction function, List<String> groupingKeys,
                      OptionalLong maxRows, PhysicalNode input) implements PhysicalNode {
        public Downsample {
            groupingKeys = List.copyOf(groupingKeys);
        }
        @Override public List<PhysicalNode> children() { return List.of(input); }
    }

    /**
     * Lateral / correlated table-valued function join: for each row of {@link #left()},
     * evaluates the {@link #arguments()} in that row's context, binds them into the
     * function body via {@link #bodyBuilder()}, plans the instantiated body, and
     * concatenates the left row with each row the body produces.
     *
     * <p>The {@link #bodyBuilder()} captures the function body, the planner, and the
     * schema-annotation map: given the argument expressions with parameter references
     * already replaced by their current literal values, it returns a ready-to-execute
     * {@link PhysicalNode}.  This keeps all inlining and re-planning logic in the
     * planner rather than the executor.
     *
     * <p>Output schema = left schema ++ TVF return schema.  Never pushed to a source.
     *
     * @param functionName  the TVF name, for display and debugging
     * @param arguments     the argument {@link Operand} expressions (may reference left
     *                      columns); evaluated per left row at execution time
     * @param bodyBuilder   given the per-row argument literals, returns the planned
     *                      physical body for that invocation
     * @param deterministicBody whether the function body evaluates to the same relation
     *                      every time it is run — decided by the planner, which can see
     *                      the body the executor only receives a {@link #bodyBuilder()}
     *                      for. The executor may reuse one execution's <em>rows</em>
     *                      across outer rows carrying the same arguments only when this
     *                      is {@code true}; the planned body itself is reusable either
     *                      way, since planning does not evaluate anything
     */
    record LateralJoin(Schema schema,
                       String functionName,
                       List<Operand> arguments,
                       Function<List<Operand>, PhysicalNode> bodyBuilder,
                       boolean deterministicBody,
                       PhysicalNode left) implements PhysicalNode {
        public LateralJoin {
            Objects.requireNonNull(functionName, "functionName");
            arguments = List.copyOf(arguments);
            Objects.requireNonNull(bodyBuilder, "bodyBuilder");
        }
        @Override public List<PhysicalNode> children() { return List.of(left); }
    }

    /**
     * Column-to-rows rotation (UNPIVOT): folds the listed columns into rows — each
     * input row fans out to one output row per listed column, with the column name
     * placed in {@code nameColumn} (STRING) and the cell value in {@code valueColumn}
     * (ANY).  Output schema = input schema minus the listed columns plus the two new
     * columns.  Streaming (never buffers the input).
     *
     * @param columns     the column names to fold into rows (≥1)
     * @param nameColumn  output column holding the source column name
     * @param valueColumn output column holding the cell value
     */
    record Unpivot(Schema schema, List<String> columns, String nameColumn,
                   String valueColumn, PhysicalNode input) implements PhysicalNode {
        public Unpivot {
            columns = List.copyOf(columns);
        }
        @Override public List<PhysicalNode> children() { return List.of(input); }
    }

    /**
     * Rows-to-columns rotation (PIVOT): groups the input by the optional {@code groupKeys},
     * then turns each distinct value of {@code keyColumn} into a new output column whose
     * cell is the corresponding {@code valueColumn} cell (NULL when the group has no row
     * for that key).  Output schema is open (dynamic — depends on runtime key values).
     * Blocking (must see all rows to determine the full set of column headers).
     *
     * @param valueColumn the column whose cell values fill the new columns
     * @param keyColumn   the column whose distinct values become column headers
     * @param groupKeys   optional grouping keys (may be empty = whole relation)
     */
    record Pivot(Schema schema, String valueColumn, String keyColumn,
                 List<String> groupKeys, PhysicalNode input) implements PhysicalNode {
        public Pivot {
            groupKeys = List.copyOf(groupKeys);
        }
        @Override public List<PhysicalNode> children() { return List.of(input); }
    }
}

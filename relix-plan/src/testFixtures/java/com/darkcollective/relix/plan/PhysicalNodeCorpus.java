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
import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.AllenRelation;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ConsolidationFunction;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.ObjectiveSense;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SortDirection;
import com.darkcollective.relix.ast.TieBreak;
import com.darkcollective.relix.ast.WindowFrame;
import com.darkcollective.relix.ast.WindowFunction;
import com.darkcollective.relix.cost.Ordering;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.ShadowPolicy;
import com.darkcollective.relix.symbol.relation.InlineRelationSymbol;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static com.darkcollective.relix.ast.AstBuilders.*;

/**
 * One instance of every concrete {@link PhysicalNode} kind, for tests that must reason
 * about all of them rather than the handful someone thought to write down.
 *
 * <p>The counterpart of {@code RelNodeCorpus}, and here for the same reason. The
 * hierarchy is sealed, so the compiler forces a {@code switch} arm for a new operator —
 * but nothing forced the operator to have a printer test or a JSON test.
 * {@code PhysicalPlanJsonTest} and {@code PhysicalPlanPrinterTest} are 128 per-kind
 * methods between them, each asserting something genuinely different, and neither is
 * related to the {@code permits} clause: a new physical operator could ship with no
 * entry in either and nothing would say so.
 *
 * <p>The guard goes beside those suites, not instead of them.
 * {@code PhysicalNodeCorpusTest} holds this list to the hierarchy, and
 * {@code PhysicalNodeContractTest} asserts over it only what is uniform for every kind
 * — that it renders, that it carries its own discriminator into JSON, that the JSON is
 * well-formed, that {@code schema()} and {@code children()} answer. What each operator
 * specifically prints stays where it is.
 *
 * <h2>The rules</h2>
 *
 * <ul>
 *   <li><strong>Exactly one entry per concrete kind</strong>, enumerated reflectively
 *       by the guard rather than counted here.</li>
 *   <li><strong>Optional and list components are populated wherever the node allows
 *       it.</strong> An entry left at its defaults still renders, but it renders less:
 *       an empty {@code Optional} exercises no branch of the label that reads it, and
 *       both this corpus's tests and the printer's own are the poorer for it.</li>
 *   <li><strong>Children are the shared {@link #LEFT} and {@link #RIGHT} leaves</strong>,
 *       named so a test can tell the two sides apart in rendered output.</li>
 * </ul>
 *
 * <p>One node per kind, not one per shape — a {@code Join} has four algorithms and a
 * {@code SetOp} five kinds, and a test that cares which states it itself, beside the
 * assertion explaining why it matters.
 */
public final class PhysicalNodeCorpus {

    private PhysicalNodeCorpus() {
    }

    /** The schema every entry carries: one NUMBER column. */
    public static final Schema SCHEMA =
            new Schema(List.of(new ColumnDefinition("x", ScalarType.NUMBER)));

    /** The left/only child of every entry that has one. */
    public static final PhysicalNode LEFT =
            new PhysicalNode.PushedScan(SCHEMA, "jdbc", "db", "SELECT x FROM l");

    /** The right child of every binary entry. */
    public static final PhysicalNode RIGHT =
            new PhysicalNode.PushedScan(SCHEMA, "jdbc", "db", "SELECT x FROM r");

    /**
     * A left child that delivers rows ordered by {@code x}, for the entries whose
     * streaming form has an ordered input as its precondition. A streaming
     * {@code Aggregate} reads its input's leading sort keys as its grouping keys, so
     * one built over an unordered input is not a plan the planner can produce — and it
     * does not merely deliver no ordering, it throws.
     */
    public static final PhysicalNode SORTED_LEFT =
            new PhysicalNode.PushedScan(SCHEMA, "jdbc", "db", "SELECT x FROM l ORDER BY x",
                    Ordering.of(List.of(sortKey(
                            attr("x"), SortDirection.ASC))));

    /** The condition every entry needing a {@link Predicate} carries. */
    public static final Predicate CONDITION = cmp(
            attr("x"), ComparisonOperator.EQUAL, num("1"));

    private static final Operand X = attr("x");
    private static final Operand ONE = num("1");

    /** The base relation a {@code Scan} reads. */
    private static final InlineRelationSymbol SOURCE = new InlineRelationSymbol(
            "default", "Base", Provenance.USER, ShadowPolicy.FORBIDDEN, SCHEMA, List.of());

    /** The logical tree {@code Why} reifies lineage over; it is not a physical child. */
    private static final RelNode LOGICAL = rel("Base");

    /**
     * One instance of every concrete {@link PhysicalNode} kind, in {@code PhysicalNode}'s
     * own declaration order — leaves, sharing, unary, recursion, analytics, joins, set
     * operations, then the rest.
     *
     * @return 40 nodes, one per kind; never empty
     */
    public static List<PhysicalNode> everyKind() {
        return List.of(
                // ── leaves ──────────────────────────────────────────────────────
                new PhysicalNode.Scan(SCHEMA, SOURCE,
                        Optional.of(produceBound("x", ComparisonOperator.LESS,
                                num("100"))),
                        Optional.of("T")),
                new PhysicalNode.Empty(SCHEMA),
                new PhysicalNode.PushedScan(SCHEMA, "jdbc", "db", "SELECT x FROM t",
                        Ordering.of(List.of(sortKey(X, SortDirection.ASC)))),

                // ── sharing ─────────────────────────────────────────────────────
                new PhysicalNode.Spool(SCHEMA, 1, LEFT),

                // ── streaming unary ─────────────────────────────────────────────
                new PhysicalNode.Select(SCHEMA, CONDITION, LEFT),
                new PhysicalNode.Project(SCHEMA,
                        List.of(ProjectedAttribute.aliased(X, "y")), LEFT),
                new PhysicalNode.Rename(SCHEMA, Optional.of("R"),
                        List.of(renamePair("x", "y")), LEFT),
                new PhysicalNode.Distinct(SCHEMA, true, LEFT),
                new PhysicalNode.Unnest(SCHEMA, "items", true, Optional.of("ord"), LEFT),

                // ── recursion and graphs ────────────────────────────────────────
                new PhysicalNode.Closure(SCHEMA, "src", "dst", true,
                        Optional.of(ONE), Optional.of(ONE), LEFT),
                new PhysicalNode.Cluster(SCHEMA, "src", "dst", "component", LEFT),
                new PhysicalNode.Path(SCHEMA, "src", "dst", 1, 3, "depth", LEFT),
                new PhysicalNode.Trace(SCHEMA, "src", "dst", "cost", ObjectiveSense.MINIMIZE,
                        "route", TraceAlgorithm.RELAXATION,
                        Optional.of(ONE), Optional.of(ONE), LEFT),
                new PhysicalNode.Fixpoint(SCHEMA, "T", LEFT, RIGHT),
                new PhysicalNode.RecursiveRef(SCHEMA, "T"),

                // ── blocking unary ──────────────────────────────────────────────
                new PhysicalNode.Limit(SCHEMA, Optional.of(5L), 10L, LEFT),
                new PhysicalNode.Sort(SCHEMA,
                        List.of(sortKey(X, SortDirection.DESC)), LEFT),
                new PhysicalNode.Aggregate(SCHEMA, List.of(GroupingKey.column("x")),
                        List.of(AggregateFunction.simple(AggregateOperator.SUM, "x")),
                        true, SORTED_LEFT),
                new PhysicalNode.Universal(SCHEMA, List.of("x"), CONDITION, LEFT),

                // ── analytics ───────────────────────────────────────────────────
                new PhysicalNode.TopK(SCHEMA, List.of("x"),
                        List.of(sortKey(X, SortDirection.DESC)),
                        Optional.of(2L), 5L, LEFT),
                new PhysicalNode.Window(SCHEMA,
                        new WindowFunction.AggregateWindow(AggregateOperator.SUM, X),
                        List.of("x"), List.of(sortKey(X, SortDirection.ASC)),
                        new WindowFrame.BoundedFrame(3), "running", LEFT),
                new PhysicalNode.Sessionize(SCHEMA, "at", ONE, List.of("x"), "session", LEFT),
                new PhysicalNode.Tree(SCHEMA, "id", "parent",
                        List.of(sortKey(X, SortDirection.ASC)), "children", LEFT),
                new PhysicalNode.Why(SCHEMA, LOGICAL),

                // ── sampling ────────────────────────────────────────────────────
                new PhysicalNode.BernoulliSample(SCHEMA, 0.25d, Optional.of(7L), LEFT),
                new PhysicalNode.ReservoirSample(SCHEMA, 100L, Optional.of(7L), LEFT),

                // ── solver ──────────────────────────────────────────────────────
                new PhysicalNode.Solve(SCHEMA, X, ONE, LEFT),
                new PhysicalNode.Optimize(SCHEMA, ObjectiveSense.MAXIMIZE, X,
                        List.of(constraint(X, ComparisonOperator.LESS_EQUAL, 10d)),
                        List.of("x"), Optional.of(allocation(0d, 1d, "share")), LEFT),

                // ── joins ───────────────────────────────────────────────────────
                new PhysicalNode.Join(SCHEMA, PhysicalNode.JoinKind.INNER,
                        PhysicalNode.JoinAlgorithm.HASH, PhysicalNode.BuildSide.RIGHT,
                        Optional.of(CONDITION),
                        new PhysicalNode.JoinKeys(List.of(0), List.of(0)),
                        Set.of("L"), Set.of("R"), LEFT, RIGHT),
                new PhysicalNode.AsOfJoin(SCHEMA, PhysicalNode.JoinKeys.none(), 0, 0,
                        true, true, Optional.of(Duration.ofMinutes(5)), true, TieBreak.LAST,
                        LEFT, RIGHT),
                new PhysicalNode.IntervalJoin(SCHEMA, AllenRelation.OVERLAPS,
                        0, 0, 0, 0, true, LEFT, RIGHT),
                new PhysicalNode.LateralJoin(SCHEMA, "generate_series", List.of(ONE),
                        args -> RIGHT, true, LEFT),

                // ── set operations ──────────────────────────────────────────────
                new PhysicalNode.SetOp(SCHEMA, PhysicalNode.SetKind.UNION, LEFT, RIGHT),
                new PhysicalNode.Division(SCHEMA, LEFT, RIGHT),

                // ── generation ──────────────────────────────────────────────────
                new PhysicalNode.Cover(SCHEMA, 2, true, LEFT),
                new PhysicalNode.ConstructiveCover(SCHEMA, 2,
                        List.of(LEFT, RIGHT), List.of(CONDITION)),

                // ── reshaping ───────────────────────────────────────────────────
                new PhysicalNode.Downsample(SCHEMA, "at", 60L, ConsolidationFunction.AVG,
                        List.of("x"), OptionalLong.of(1000L), LEFT),
                new PhysicalNode.Unpivot(SCHEMA, List.of("a", "b"), "name", "value", LEFT),
                new PhysicalNode.Pivot(SCHEMA, "value", "key", List.of("x"), LEFT));
    }
}

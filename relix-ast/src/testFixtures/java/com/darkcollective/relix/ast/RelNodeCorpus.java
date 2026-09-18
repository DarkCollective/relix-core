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
package com.darkcollective.relix.ast;

import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.agg;
import static com.darkcollective.relix.ast.AstBuilders.allocation;
import static com.darkcollective.relix.ast.AstBuilders.antiJoin;
import static com.darkcollective.relix.ast.AstBuilders.asOfJoin;
import static com.darkcollective.relix.ast.AstBuilders.asc;
import static com.darkcollective.relix.ast.AstBuilders.attr;
import static com.darkcollective.relix.ast.AstBuilders.attrs;
import static com.darkcollective.relix.ast.AstBuilders.closure;
import static com.darkcollective.relix.ast.AstBuilders.cluster;
import static com.darkcollective.relix.ast.AstBuilders.cmp;
import static com.darkcollective.relix.ast.AstBuilders.cols;
import static com.darkcollective.relix.ast.AstBuilders.composition;
import static com.darkcollective.relix.ast.AstBuilders.constraint;
import static com.darkcollective.relix.ast.AstBuilders.cover;
import static com.darkcollective.relix.ast.AstBuilders.desc;
import static com.darkcollective.relix.ast.AstBuilders.difference;
import static com.darkcollective.relix.ast.AstBuilders.distinct;
import static com.darkcollective.relix.ast.AstBuilders.division;
import static com.darkcollective.relix.ast.AstBuilders.downsample;
import static com.darkcollective.relix.ast.AstBuilders.emptyOf;
import static com.darkcollective.relix.ast.AstBuilders.fixpoint;
import static com.darkcollective.relix.ast.AstBuilders.fullJoin;
import static com.darkcollective.relix.ast.AstBuilders.groupBy;
import static com.darkcollective.relix.ast.AstBuilders.intersection;
import static com.darkcollective.relix.ast.AstBuilders.intervalJoin;
import static com.darkcollective.relix.ast.AstBuilders.join;
import static com.darkcollective.relix.ast.AstBuilders.lateral;
import static com.darkcollective.relix.ast.AstBuilders.leftJoin;
import static com.darkcollective.relix.ast.AstBuilders.limit;
import static com.darkcollective.relix.ast.AstBuilders.naturalJoin;
import static com.darkcollective.relix.ast.AstBuilders.num;
import static com.darkcollective.relix.ast.AstBuilders.optimize;
import static com.darkcollective.relix.ast.AstBuilders.outerUnion;
import static com.darkcollective.relix.ast.AstBuilders.pairwiseUniversal;
import static com.darkcollective.relix.ast.AstBuilders.path;
import static com.darkcollective.relix.ast.AstBuilders.pivot;
import static com.darkcollective.relix.ast.AstBuilders.produceBound;
import static com.darkcollective.relix.ast.AstBuilders.product;
import static com.darkcollective.relix.ast.AstBuilders.project;
import static com.darkcollective.relix.ast.AstBuilders.recRef;
import static com.darkcollective.relix.ast.AstBuilders.rel;
import static com.darkcollective.relix.ast.AstBuilders.rename;
import static com.darkcollective.relix.ast.AstBuilders.reservoirSample;
import static com.darkcollective.relix.ast.AstBuilders.rightJoin;
import static com.darkcollective.relix.ast.AstBuilders.sample;
import static com.darkcollective.relix.ast.AstBuilders.select;
import static com.darkcollective.relix.ast.AstBuilders.semiJoin;
import static com.darkcollective.relix.ast.AstBuilders.sessionize;
import static com.darkcollective.relix.ast.AstBuilders.solve;
import static com.darkcollective.relix.ast.AstBuilders.sort;
import static com.darkcollective.relix.ast.AstBuilders.symmetricDifference;
import static com.darkcollective.relix.ast.AstBuilders.topK;
import static com.darkcollective.relix.ast.AstBuilders.trace;
import static com.darkcollective.relix.ast.AstBuilders.tree;
import static com.darkcollective.relix.ast.AstBuilders.tvf;
import static com.darkcollective.relix.ast.AstBuilders.union;
import static com.darkcollective.relix.ast.AstBuilders.unionAll;
import static com.darkcollective.relix.ast.AstBuilders.universal;
import static com.darkcollective.relix.ast.AstBuilders.unnest;
import static com.darkcollective.relix.ast.AstBuilders.unpivot;
import static com.darkcollective.relix.ast.AstBuilders.unitRel;
import static com.darkcollective.relix.ast.AstBuilders.why;

/**
 * One instance of every concrete {@link RelNode} kind, for tests that must reason about
 * <em>all</em> of them rather than the handful someone thought to write down.
 *
 * <p>The AST's sealed hierarchies make a {@code switch} exhaustive, so the compiler
 * catches a pass that forgets a kind.  It cannot catch a pass that <em>handles</em> a kind
 * incorrectly, and the coverage report shows that gap plainly: the arms of
 * {@code RelNode.mapChildren} and of the {@code relix-cost} derivations are, for roughly
 * twenty kinds each, never executed by any test.  A corpus is what turns "the compiler
 * made me write an arm" into "a test ran it".
 *
 * <h2>The rules</h2>
 *
 * <ul>
 *   <li><strong>Exactly one entry per concrete kind.</strong> {@code RelNodeCorpusTest}
 *       enumerates the hierarchy reflectively and fails on a kind that is missing or
 *       listed twice — the same guard, for the same reason, as
 *       {@code AstBuilderCoverageTest}.</li>
 *   <li><strong>Every entry is built through {@link AstBuilders}</strong>, so the corpus
 *       cannot drift from the authoring surface and a factory with the wrong argument
 *       order shows up here.</li>
 *   <li><strong>Optional and list components are populated wherever the node's own
 *       invariants allow it.</strong>  An entry left at its defaults still satisfies a
 *       structural test, but it tests less: a node whose {@code Optional} is empty
 *       compares equal to one that dropped it.  {@code RenameNode} is the documented
 *       exception — it rejects positional attributes and {@code from → to} pairs at the
 *       same time, so only one of the two can be non-empty.</li>
 *   <li><strong>Children are the shared {@link #LEFT} and {@link #RIGHT} leaves</strong>,
 *       named rather than anonymous so a test can identify them by name — which is what
 *       a {@code BoundednessSource} or {@code DistinctnessSource} keyed by relation name
 *       needs in order to answer differently for the two sides.</li>
 * </ul>
 *
 * <h2>What this is not</h2>
 *
 * <p>One node per kind, not one per <em>shape</em>.  Several kinds behave differently
 * depending on a component — a {@link WindowFrame.BoundedFrame} window streams where a
 * {@link WindowFrame.CumulativeFrame} one blocks, an {@link AggregationNode} with no
 * grouping keys yields a single row where a grouped one does not.  The corpus carries the
 * general case (here, the blocking and the grouped one); a test that cares about the other
 * shape states it itself, next to the assertion that explains why it matters.
 */
public final class RelNodeCorpus {

    private RelNodeCorpus() {
    }

    /** The left/only child of every entry that has one. */
    public static final RelationNode LEFT = rel("L");

    /** The right child of every binary entry. */
    public static final RelationNode RIGHT = rel("R");

    /** The condition every entry needing a {@link Predicate} carries. */
    public static final Predicate CONDITION =
            cmp(attr("x"), ComparisonOperator.EQUAL, attr("y"));

    /**
     * One instance of every concrete {@link RelNode} kind, in {@code RelNode}'s own
     * {@code permits} order — leaves, unary, joins, set operations, then the rest.
     *
     * @return 52 nodes, one per kind; never empty
     */
    public static List<RelNode> everyKind() {
        return List.of(
                // ── leaves ──────────────────────────────────────────────────────
                // Carries a produce bound so the GEN-001 path is a populated Optional.
                rel("Gen", produceBound("n", ComparisonOperator.LESS, num("100"))),
                tvf("generate_series", num("1"), num("10")),
                unitRel(),
                // ∅ — its heading is an inert component, deliberately not a child.
                emptyOf(LEFT),
                recRef("T"),

                // ── unary ───────────────────────────────────────────────────────
                project(attrs("a", "b"), LEFT),
                select(CONDITION, LEFT),
                rename("Renamed", cols("a", "b"), LEFT),
                groupBy(cols("dept"), List.of(agg(AggregateOperator.SUM, "amount")), LEFT),
                sort(List.of(asc("a"), desc("b")), LEFT),
                limit(Optional.of(5L), 10L, LEFT),
                distinct(LEFT),
                unnest("tags", true, Optional.of("ordinality"), LEFT),

                // ── joins ───────────────────────────────────────────────────────
                naturalJoin(LEFT, RIGHT),
                join(LEFT, RIGHT, CONDITION),
                leftJoin(LEFT, RIGHT, CONDITION),
                rightJoin(LEFT, RIGHT, CONDITION),
                fullJoin(LEFT, RIGHT, CONDITION),
                semiJoin(LEFT, RIGHT, CONDITION),
                antiJoin(LEFT, RIGHT, CONDITION),
                pairwiseUniversal(LEFT, RIGHT, CONDITION),
                asOfJoin(LEFT, RIGHT, CONDITION,
                        Optional.of(attr("tolerance")), true, TieBreak.FIRST),
                intervalJoin(LEFT, RIGHT, AllenRelation.OVERLAPS,
                        "l_start", "l_end", "r_start", "r_end"),
                lateral(LEFT, "expand", attr("id")),

                // ── set operations ──────────────────────────────────────────────
                product(LEFT, RIGHT),
                union(LEFT, RIGHT),
                unionAll(LEFT, RIGHT),
                outerUnion(LEFT, RIGHT),
                difference(LEFT, RIGHT),
                intersection(LEFT, RIGHT),
                division(LEFT, RIGHT),
                symmetricDifference(LEFT, RIGHT),
                composition(LEFT, RIGHT),

                // ── recursion and graph ─────────────────────────────────────────
                // Undirected, so that a walker dropping the component is caught: the
                // directed reading is the default, and a dropped `false` looks identical.
                closure("src", "dst", true, true,
                        Optional.of(attr("from_bound")), Optional.of(attr("to_bound")), LEFT),
                cluster("src", "dst", "component", LEFT),
                path("src", "dst", true, 1, 4, "hops", LEFT),
                trace("src", "dst", true, "weight", ObjectiveSense.MINIMIZE, "route",
                        Optional.of(attr("from_bound")), Optional.of(attr("to_bound")), LEFT),
                fixpoint("T", LEFT, RIGHT),

                // ── analytics ───────────────────────────────────────────────────
                // A cumulative frame: the blocking general case (a BoundedFrame streams).
                AstBuilders.window(
                        new WindowFunction.AggregateWindow(AggregateOperator.SUM, attr("amount")),
                        cols("dept"), List.of(asc("day")),
                        new WindowFrame.CumulativeFrame(), "running_total", LEFT),
                topK(cols("dept"), List.of(desc("amount")), Optional.of(1L), 5, LEFT),
                sessionize("ts", num("1800"), cols("user_id"), "session", LEFT),
                downsample("ts", "1h", ConsolidationFunction.AVG, cols("sensor"), 500, LEFT),
                pivot("amount", "quarter", cols("dept"), LEFT),
                unpivot(cols("q1", "q2"), "quarter", "amount", LEFT),
                tree("id", "parent_id", List.of(asc("ordinal")), "children", LEFT),

                // ── solver ──────────────────────────────────────────────────────
                solve(attr("x"), num("42"), LEFT),
                optimize(ObjectiveSense.MAXIMIZE, attr("value"),
                        List.of(constraint(attr("weight"), ComparisonOperator.LESS_EQUAL, 10.0)),
                        cols("dept"), Optional.of(allocation(0.0, 1.0, "share")), LEFT),

                // ── sampling ────────────────────────────────────────────────────
                sample(0.25, Optional.of(42L), LEFT),
                reservoirSample(100, Optional.of(42L), LEFT),

                // ── quantification, generation, diagnostics ─────────────────────
                universal(cols("student"), CONDITION, LEFT),
                cover(2, true, LEFT),
                why(LEFT));
    }
}

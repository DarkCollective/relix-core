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
package com.darkcollective.relix.embed;

import com.darkcollective.relix.ast.AllenRelation;
import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.ConsolidationFunction;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.ObjectiveSense;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.OptimizeConstraint;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SortSpecification;
import com.darkcollective.relix.ast.TieBreak;
import com.darkcollective.relix.ast.WindowFrame;
import com.darkcollective.relix.ast.WindowFunction;
import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.Expr;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.cost.Boundedness;
import com.darkcollective.relix.cost.BoundednessSource;
import com.darkcollective.relix.cost.DistinctnessSource;
import com.darkcollective.relix.cost.MonotoneGeneratorSource;
import com.darkcollective.relix.cost.PropertyDeriver;
import com.darkcollective.relix.cost.StatisticsDistinctnessSource;
import com.darkcollective.relix.cost.StatisticsSource;
import com.darkcollective.relix.events.EventMetrics;
import com.darkcollective.relix.events.QueryEvent;
import com.darkcollective.relix.events.QueryEventListener;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.optimizer.OptimizationResult;
import com.darkcollective.relix.optimizer.TransformationRecord;
import com.darkcollective.relix.optimizer.QueryOptimizer;
import com.darkcollective.relix.plan.PhysicalPlanJson;
import com.darkcollective.relix.plan.PhysicalPlanPrinter;
import com.darkcollective.relix.processor.DataSourceConnector;
import com.darkcollective.relix.processor.ExecutionContext;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.exec.RelNodeExecutor;
import com.darkcollective.relix.processor.generator.GeneratorBoundednessSource;
import com.darkcollective.relix.processor.generator.GeneratorDistinctnessSource;
import com.darkcollective.relix.processor.generator.GeneratorMonotonicitySource;
import com.darkcollective.relix.processor.provenance.AnnotatedRelation;
import com.darkcollective.relix.processor.provenance.BaseAnnotator;
import com.darkcollective.relix.processor.provenance.ProvenanceEvaluator;
import com.darkcollective.relix.provenance.Semiring;
import com.darkcollective.relix.semantic.SchemaInference;
import com.darkcollective.relix.semantic.RelationDeterminism;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.RelationStatistics;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.Value;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A relation — an expression together with the analysis it resolves against.
 *
 * <p><strong>A relation is a value, not a pending execution.</strong> Operators are
 * functions from relations to relations, closed under composition; that is the algebra,
 * and it is what this type models. Composing, optimising, rendering and inspecting a
 * relation are whole uses of the engine in their own right, and none of them runs
 * anything.
 *
 * <h2>Pinned at creation</h2>
 *
 * <p>A relation captures the {@link SemanticModel} its session held when it was created.
 * Redefining a source or view afterwards does not change it. Without that rule this type
 * would be a view onto mutable state rather than a value — its meaning would shift under
 * its holder, and it could be neither cached nor shared across threads.
 *
 * <h2>Inspection is staged; execution is not</h2>
 *
 * <p>{@link #render()} and {@link #optimized()}{@code .render()} differ; {@link #stream()}
 * and {@link #optimized()}{@code .stream()} do not. Every execution terminal optimises
 * first, because a library call that quietly skipped a rewrite phase would hand back a
 * correct answer by a worse plan with no signal that it had. {@link #optimized()} exists
 * so a caller can <em>see</em> and serialise the rewritten form, not to enable it.
 *
 * @since 1.0
 */
public final class Relation {

    /** The column {@link #count()} reads its answer out of. */
    private static final String COUNT_COLUMN = "count";

    /**
     * The spliterator characteristics {@link #guarded} carries across.
     *
     * <p>Everything outside this mask is dropped rather than repeated: {@code SIZED} would
     * be a promise about a count the wrapper's own splitting does not keep, and
     * {@code SORTED} obliges a comparator the wrapper has no way to supply. Both are
     * optimisations, and losing one costs a little work; reporting one falsely is a wrong
     * answer.
     */
    private static final int GUARDED_CHARACTERISTICS =
            Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.DISTINCT | Spliterator.IMMUTABLE;

    private final Relix session;
    private final SemanticModel model;
    private final RelNode node;

    /**
     * The {@code OPTIMIZE}-stage events of the rewrite that produced this relation, empty
     * for one that was not produced by {@link #optimized()}.
     */
    private final List<QueryEvent> events;

    /** The name the script gave this query, if it came from one; null otherwise. */
    private final String label;

    /** What the rewriter did, as records; empty unless this came from {@link #optimized()}. */
    private final List<TransformationRecord> rewrites;

    Relation(Relix session, SemanticModel model, RelNode node) {
        this(session, model, node, List.of(), List.of(), null);
    }

    Relation(Relix session, SemanticModel model, RelNode node, String label) {
        this(session, model, node, List.of(), List.of(), label);
    }

    private Relation(Relix session, SemanticModel model, RelNode node, List<QueryEvent> events,
                     List<TransformationRecord> rewrites, String label) {
        this.session = Objects.requireNonNull(session, "session");
        this.model = Objects.requireNonNull(model, "model");
        this.node = Objects.requireNonNull(node, "node");
        this.events = List.copyOf(events);
        this.rewrites = List.copyOf(rewrites);
        this.label = label;
    }

    /**
     * What the script called this query, when it came from one.
     *
     * <p>{@code query Open;} gives {@code Open}; an unnamed {@code query { … }} gives
     * {@code <expression 2>}, numbered among the script's query statements. Present only
     * on a relation {@link Relix#script} returned: a relation built by composing carries
     * no label, because the composed expression is not the one the script named.
     *
     * <p>It exists because a result needs a heading in every rendering a caller might
     * choose, and inventing one at the point of printing would give a different answer
     * from the one the engine's own feed uses.
     *
     * @return the label, or empty for a relation nobody named
     * @since 1.0
     */
    public Optional<String> label() {
        return Optional.ofNullable(label);
    }

    /**
     * The expression tree this relation denotes.
     *
     * @return the logical expression; never null
     * @since 1.0
     */
    public RelNode node() {
        return node;
    }

    /**
     * The analysis this relation is pinned to — the session's state when it was created.
     *
     * @return the semantic model; never null
     * @since 1.0
     */
    public SemanticModel model() {
        return model;
    }

    // -------------------------------------------------------------------------
    // Composition
    // -------------------------------------------------------------------------

    /**
     * This relation with {@code composed} as its expression, re-annotated against the
     * same environment.
     *
     * <p>Every unary combinator goes through here. The symbol table is unchanged —
     * composing declares nothing — but the new tree needs its own schema annotations, and
     * {@code SchemaInference.annotate} is the mechanism that supplies them without a
     * second full analysis.
     */
    private Relation derive(RelNode composed) {
        return derive(composed, model);
    }

    /**
     * The composition of this relation with {@code right}, resolved against what
     * <em>both</em> operands know.
     *
     * <p>Every binary combinator goes through here, and the merge is what makes it
     * correct. A relation pins the analysis it was built against, which is what makes it
     * a value — but that analysis governs its own subtree, not the operand it is being
     * combined with. Resolving the composed tree against this side's model alone loses
     * every name only the other side's model carries: a table a dotted reference
     * introspected when {@code right} was built, or anything the session declared after
     * {@code this} was pinned.
     */
    private Relation derive(RelNode composed, Relation right) {
        return derive(composed, ComposedModel.of(model, right.model));
    }

    /** As {@link #derive(RelNode)}, over an environment that is not necessarily this one's. */
    private Relation derive(RelNode composed, SemanticModel environment) {
        return new Relation(
                session,
                new SemanticModel(
                        environment.namespace(), environment.symbolTable(), environment.sources(),
                        environment.connections(), environment.statistics(),
                        SchemaInference.annotate(environment.symbolTable(), composed,
                                environment.nodeSchemas(), environment.functions()),
                        environment.schemaGraph(), environment.rootQueries(),
                        environment.functions()),
                composed);
    }

    /**
     * The other operand's expression, once it is established that the two can be composed
     * at all.
     *
     * <p>Two relations from different sessions cannot: each resolves against its own
     * session's bindings — its connections, its handles, its clock — and there is no
     * session the composed relation could belong to. That is a different mistake from a
     * name clash, and is refused as one.
     */
    private RelNode other(Relation relation) {
        Objects.requireNonNull(relation, "relation");
        if (relation.session != session) {
            throw new RelixException(
                    "cannot compose relations from two different sessions; a relation "
                            + "resolves against its own session's bindings");
        }
        return relation.node();
    }

    // -------------------------------------------------------------------------
    // Core operators — reference §3
    // -------------------------------------------------------------------------

    /**
     * σ — the rows satisfying {@code predicate}.
     *
     * @param predicate the selection predicate; must not be null
     * @return the selected relation
     * @since 1.0
     */
    public Relation select(Predicate predicate) {
        return derive(AstBuilders.select(predicate, node));
    }

    /**
     * π — the named columns, in the order given.
     *
     * @param attributes the projected attributes; must not be null
     * @return the projected relation
     * @since 1.0
     */
    public Relation project(List<ProjectedAttribute> attributes) {
        return derive(AstBuilders.project(attributes, node));
    }

    /**
     * π over bare column names.
     *
     * @param columns the column names; must not be null
     * @return the projected relation
     * @since 1.0
     */
    public Relation project(String... columns) {
        return derive(AstBuilders.project(AstBuilders.attrs(columns), node));
    }

    /**
     * ρ — renames the relation.
     *
     * @param relationName the new relation name; must not be null
     * @return the renamed relation
     * @since 1.0
     */
    public Relation rename(String relationName) {
        return derive(AstBuilders.rename(relationName, List.of(), node));
    }

    /**
     * ρ — renames the relation and its columns positionally.
     *
     * @param relationName the new relation name; must not be null
     * @param attributes   the new column names, one per column
     * @return the renamed relation
     * @since 1.0
     */
    public Relation rename(String relationName, List<String> attributes) {
        return derive(AstBuilders.rename(relationName, attributes, node));
    }

    /**
     * γ — grouping keys and aggregates together, because γ is one node.
     *
     * @param groupingColumns the bare grouping columns
     * @param aggregates      the aggregate functions
     * @return the aggregated relation
     * @since 1.0
     */
    public Relation aggregate(List<String> groupingColumns, List<AggregateFunction> aggregates) {
        return derive(AstBuilders.groupBy(groupingColumns, aggregates, node));
    }

    /**
     * γ over grouping keys that are full expressions rather than bare columns.
     *
     * @param groupingKeys the grouping keys
     * @param aggregates   the aggregate functions
     * @return the aggregated relation
     * @since 1.0
     */
    public Relation aggregateBy(List<GroupingKey> groupingKeys, List<AggregateFunction> aggregates) {
        return derive(AstBuilders.groupByKeys(groupingKeys, aggregates, node));
    }

    /**
     * τ — sorted by the given keys.
     *
     * @param sortSpecs the sort keys, most significant first
     * @return the sorted relation
     * @since 1.0
     */
    public Relation sort(List<SortSpecification> sortSpecs) {
        return derive(AstBuilders.sort(sortSpecs, node));
    }

    /**
     * τ — sorted by the given keys.
     *
     * @param sortSpecs the sort keys, most significant first
     * @return the sorted relation
     * @since 1.0
     */
    public Relation sort(SortSpecification... sortSpecs) {
        return sort(Arrays.asList(sortSpecs));
    }

    /**
     * λ — at most {@code count} rows.
     *
     * <p>Wraps rather than replaces: λ over λ is well-defined and the tighter bound wins,
     * and rewriting a λ that may not be at the root would be smoothing over the algebra.
     * This is also how an unbounded relation is made finite.
     *
     * @param count the maximum number of rows
     * @return the bounded relation
     * @since 1.0
     */
    public Relation limit(long count) {
        return derive(AstBuilders.limit(count, node));
    }

    /**
     * λ — at most {@code count} rows, after skipping {@code offset}.
     *
     * @param offset how many rows to skip
     * @param count  the maximum number of rows
     * @return the bounded relation
     * @since 1.0
     */
    public Relation limit(long offset, long count) {
        return derive(AstBuilders.limit(Optional.of(offset), count, node));
    }

    /**
     * δ — duplicate rows removed.
     *
     * @return the deduplicated relation
     * @since 1.0
     */
    public Relation distinct() {
        return derive(AstBuilders.distinct(node));
    }

    /**
     * μ — one row per element of the named nested column.
     *
     * @param column the array-valued column to unnest
     * @return the unnested relation
     * @since 1.0
     */
    public Relation unnest(String column) {
        return derive(AstBuilders.unnest(column, node));
    }

    /**
     * μ — unnest, keeping rows whose collection is empty when {@code outer}.
     *
     * @param column           the array-valued column
     * @param outer            whether to keep rows with nothing to unnest
     * @param ordinalityColumn a column to receive each element's position, if wanted
     * @return the unnested relation
     * @since 1.0
     */
    public Relation unnest(String column, boolean outer, Optional<String> ordinalityColumn) {
        return derive(AstBuilders.unnest(column, outer, ordinalityColumn, node));
    }

    /**
     * ω — each row's lineage reified as a nested {@code provenance} column.
     *
     * @return the relation with provenance
     * @since 1.0
     */
    public Relation why() {
        return derive(AstBuilders.why(node));
    }

    /**
     * ∀ — the groups in which every row satisfies {@code predicate}.
     *
     * @param groupingAttributes the grouping columns; empty for the no-key form
     * @param predicate          the condition every row must satisfy
     * @return the quantified relation
     * @since 1.0
     */
    public Relation forall(List<String> groupingAttributes, Predicate predicate) {
        return derive(AstBuilders.universal(groupingAttributes, predicate, node));
    }

    // -------------------------------------------------------------------------
    // Joins — reference §5
    // -------------------------------------------------------------------------

    /**
     * ⋈ — natural join on the common columns.
     *
     * @param right the right input; must not be null
     * @return the joined relation
     * @since 1.0
     */
    public Relation join(Relation right) {
        return derive(AstBuilders.naturalJoin(node, other(right)), right);
    }

    /**
     * ⨝ — theta join on an explicit condition.
     *
     * @param right     the right input; must not be null
     * @param condition the join condition
     * @return the joined relation
     * @since 1.0
     */
    public Relation join(Relation right, Predicate condition) {
        return derive(AstBuilders.join(node, other(right), condition), right);
    }

    /**
     * ⟕ — left outer join.
     *
     * @param right     the right input; must not be null
     * @param condition the join condition
     * @return the joined relation
     * @since 1.0
     */
    public Relation leftJoin(Relation right, Predicate condition) {
        return derive(AstBuilders.leftJoin(node, other(right), condition), right);
    }

    /**
     * ⟖ — right outer join.
     *
     * @param right     the right input; must not be null
     * @param condition the join condition
     * @return the joined relation
     * @since 1.0
     */
    public Relation rightJoin(Relation right, Predicate condition) {
        return derive(AstBuilders.rightJoin(node, other(right), condition), right);
    }

    /**
     * ⟗ — full outer join.
     *
     * @param right     the right input; must not be null
     * @param condition the join condition
     * @return the joined relation
     * @since 1.0
     */
    public Relation fullJoin(Relation right, Predicate condition) {
        return derive(AstBuilders.fullJoin(node, other(right), condition), right);
    }

    /**
     * ⋉ — the left rows having a match.
     *
     * @param right     the right input; must not be null
     * @param condition the join condition
     * @return the semi-joined relation
     * @since 1.0
     */
    public Relation semiJoin(Relation right, Predicate condition) {
        return derive(AstBuilders.semiJoin(node, other(right), condition), right);
    }

    /**
     * ▷ — the left rows having no match.
     *
     * @param right     the right input; must not be null
     * @param condition the join condition
     * @return the anti-joined relation
     * @since 1.0
     */
    public Relation antiJoin(Relation right, Predicate condition) {
        return derive(AstBuilders.antiJoin(node, other(right), condition), right);
    }

    /**
     * ∀ — the pairwise universal join: left rows matching <em>every</em> right row.
     *
     * @param right     the right input; must not be null
     * @param condition the condition every right row must satisfy
     * @return the quantified relation
     * @since 1.0
     */
    public Relation forall(Relation right, Predicate condition) {
        return derive(AstBuilders.pairwiseUniversal(node, other(right), condition), right);
    }

    /**
     * AS-OF join — each left row matched to the most recent right row.
     *
     * @param right     the right input; must not be null
     * @param condition the match condition
     * @return the joined relation
     * @since 1.0
     */
    public Relation asOfJoin(Relation right, Predicate condition) {
        return derive(AstBuilders.asOfJoin(node, other(right), condition), right);
    }

    /**
     * AS-OF join with a tolerance and an explicit tie-break.
     *
     * @param right     the right input; must not be null
     * @param condition the match condition
     * @param tolerance how far back a match may be, if bounded
     * @param inner     whether unmatched left rows are dropped
     * @param tieBreak  which row wins when two are equally recent
     * @return the joined relation
     * @since 1.0
     */
    public Relation asOfJoin(Relation right, Predicate condition, Optional<Operand> tolerance,
                             boolean inner, TieBreak tieBreak) {
        return derive(
                AstBuilders.asOfJoin(node, other(right), condition, tolerance, inner, tieBreak),
                right);
    }

    /**
     * Interval join — rows whose intervals stand in the given Allen relation.
     *
     * @param right      the right input; must not be null
     * @param relation   the Allen interval relation
     * @param leftStart  the left interval's start column
     * @param leftEnd    the left interval's end column
     * @param rightStart the right interval's start column
     * @param rightEnd   the right interval's end column
     * @return the joined relation
     * @since 1.0
     */
    public Relation intervalJoin(Relation right, AllenRelation relation, String leftStart,
                                 String leftEnd, String rightStart, String rightEnd) {
        return derive(AstBuilders.intervalJoin(node, other(right), relation,
                leftStart, leftEnd, rightStart, rightEnd), right);
    }

    /**
     * LATERAL — a table function evaluated per left row.
     *
     * @param functionName the table function's name
     * @param arguments    its arguments, which may reference this relation's columns
     * @return the joined relation
     * @since 1.0
     */
    public Relation lateral(String functionName, Operand... arguments) {
        return derive(AstBuilders.lateral(node, functionName, arguments));
    }

    // -------------------------------------------------------------------------
    // Set operations — reference §6
    // -------------------------------------------------------------------------

    /**
     * × — the Cartesian product.
     *
     * @param right the right input; must not be null
     * @return the product
     * @since 1.0
     */
    public Relation cross(Relation right) {
        return derive(AstBuilders.product(node, other(right)), right);
    }

    /**
     * ∪ — set union, duplicates removed.
     *
     * @param right the right input; must not be null
     * @return the union
     * @since 1.0
     */
    public Relation union(Relation right) {
        return derive(AstBuilders.union(node, other(right)), right);
    }

    /**
     * ⊎ — bag union, duplicates kept.
     *
     * @param right the right input; must not be null
     * @return the union
     * @since 1.0
     */
    public Relation unionAll(Relation right) {
        return derive(AstBuilders.unionAll(node, other(right)), right);
    }

    /**
     * ⊔ — outer union over differing headings.
     *
     * @param right the right input; must not be null
     * @return the union
     * @since 1.0
     */
    public Relation outerUnion(Relation right) {
        return derive(AstBuilders.outerUnion(node, other(right)), right);
    }

    /**
     * − — the rows of this relation not in {@code right}.
     *
     * @param right the right input; must not be null
     * @return the difference
     * @since 1.0
     */
    public Relation difference(Relation right) {
        return derive(AstBuilders.difference(node, other(right)), right);
    }

    /**
     * ∩ — the rows in both.
     *
     * @param right the right input; must not be null
     * @return the intersection
     * @since 1.0
     */
    public Relation intersect(Relation right) {
        return derive(AstBuilders.intersection(node, other(right)), right);
    }

    /**
     * ÷ — relational division.
     *
     * @param right the divisor; must not be null
     * @return the quotient
     * @since 1.0
     */
    public Relation divide(Relation right) {
        return derive(AstBuilders.division(node, other(right)), right);
    }

    /**
     * ∆ — the rows in exactly one of the two.
     *
     * @param right the right input; must not be null
     * @return the symmetric difference
     * @since 1.0
     */
    public Relation symmetricDifference(Relation right) {
        return derive(AstBuilders.symmetricDifference(node, other(right)), right);
    }

    /**
     * ∘ — relational composition.
     *
     * @param right the right input; must not be null
     * @return the composition
     * @since 1.0
     */
    public Relation compose(Relation right) {
        return derive(AstBuilders.composition(node, other(right)), right);
    }

    // -------------------------------------------------------------------------
    // Advanced operators — reference §11
    // -------------------------------------------------------------------------

    /**
     * CLOSURE — the transitive closure over an edge relation.
     *
     * @param fromColumn the edge's source column
     * @param toColumn   the edge's target column
     * @return the closure
     * @since 1.0
     */
    public Relation closure(String fromColumn, String toColumn) {
        return derive(AstBuilders.closure(fromColumn, toColumn, node));
    }

    /**
     * CLOSURE, optionally reflexive.
     *
     * @param fromColumn the edge's source column
     * @param toColumn   the edge's target column
     * @param reflexive  whether every node reaches itself
     * @return the closure
     * @since 1.0
     */
    public Relation closure(String fromColumn, String toColumn, boolean reflexive) {
        return derive(AstBuilders.closure(fromColumn, toColumn, reflexive, node));
    }

    /**
     * CLOSURE reading its two columns as an undirected edge, so the relation is followed
     * both ways from one edge set.
     *
     * @param fromColumn the first endpoint column
     * @param toColumn   the second endpoint column
     * @param undirected {@code true} to read the edges both ways
     * @param reflexive  {@code true} for {@code R*}, {@code false} for {@code R⁺}
     * @return the closure
     * @since 1.0
     */
    public Relation closure(String fromColumn, String toColumn, boolean undirected,
                            boolean reflexive) {
        return derive(AstBuilders.closure(fromColumn, toColumn, undirected, reflexive, node));
    }

    /**
     * CLUSTER — connected components, labelled.
     *
     * @param fromColumn  the edge's source column
     * @param toColumn    the edge's target column
     * @param labelColumn the column to receive each component's label
     * @return the clustered relation
     * @since 1.0
     */
    public Relation cluster(String fromColumn, String toColumn, String labelColumn) {
        return derive(AstBuilders.cluster(fromColumn, toColumn, labelColumn, node));
    }

    /**
     * PATH — paths of bounded length between nodes.
     *
     * @param fromColumn  the edge's source column
     * @param toColumn    the edge's target column
     * @param minHops     the minimum path length
     * @param maxHops     the maximum path length
     * @param depthColumn the column to receive each path's length
     * @return the paths
     * @since 1.0
     */
    public Relation path(String fromColumn, String toColumn, int minHops, int maxHops,
                         String depthColumn) {
        return derive(AstBuilders.path(fromColumn, toColumn, minHops, maxHops, depthColumn, node));
    }

    /**
     * PATH over an edge relation read both ways.
     *
     * @param fromColumn  the first endpoint column
     * @param toColumn    the second endpoint column
     * @param undirected  {@code true} to read the edges both ways
     * @param minHops     the inclusive lower bound on path length
     * @param maxHops     the inclusive upper bound on path length
     * @param depthColumn the name of the appended shortest-distance column
     * @return the bounded paths
     * @since 1.0
     */
    public Relation path(String fromColumn, String toColumn, boolean undirected,
                         int minHops, int maxHops, String depthColumn) {
        return derive(AstBuilders.path(fromColumn, toColumn, undirected, minHops, maxHops,
                depthColumn, node));
    }

    /**
     * TRACE — the optimal path between each reachable pair, as an ordered array.
     *
     * @param from   the edge's source column
     * @param to     the edge's target column
     * @param weight the edge-weight column
     * @param sense  whether to minimise or maximise
     * @param path   the column to receive the path
     * @return the traced relation
     * @since 1.0
     */
    public Relation trace(String from, String to, String weight, ObjectiveSense sense, String path) {
        return derive(AstBuilders.trace(from, to, weight, sense, path, node));
    }

    /**
     * TRACE over a weighted edge relation read both ways, each edge traversable in either
     * direction at the same cost.
     *
     * @param from       the first endpoint column
     * @param to         the second endpoint column
     * @param undirected {@code true} to read the edges both ways
     * @param weight     the edge-weight column
     * @param sense      whether to minimise or maximise the total weight
     * @param path       the name of the appended path-array column
     * @return the optimal paths
     * @since 1.0
     */
    public Relation trace(String from, String to, boolean undirected, String weight,
                          ObjectiveSense sense, String path) {
        return derive(AstBuilders.trace(from, to, undirected, weight, sense, path, node));
    }

    /**
     * FIX — the least fixpoint of {@code step}, with this relation as the base.
     *
     * @param name the name the step refers to itself by
     * @param step the recursive step
     * @return the fixpoint
     * @since 1.0
     */
    public Relation fix(String name, Relation step) {
        return derive(AstBuilders.fixpoint(name, node, other(step)), step);
    }

    /**
     * WINDOW — a window function over ordered partitions.
     *
     * @param function     the window function
     * @param partitionKeys the partition columns
     * @param sortSpecs    the ordering within a partition
     * @param frame        the frame
     * @param outputColumn the column to receive the result
     * @return the windowed relation
     * @since 1.0
     */
    public Relation window(WindowFunction function, List<String> partitionKeys,
                           List<SortSpecification> sortSpecs, WindowFrame frame,
                           String outputColumn) {
        return derive(AstBuilders.window(function, partitionKeys, sortSpecs, frame,
                outputColumn, node));
    }

    /**
     * TOP — the first {@code count} rows of each group, by the given order.
     *
     * @param groupingAttributes the grouping columns; empty for the whole relation
     * @param sortSpecs          the ordering
     * @param count              how many rows per group
     * @return the top rows
     * @since 1.0
     */
    public Relation top(List<String> groupingAttributes, List<SortSpecification> sortSpecs,
                        long count) {
        return derive(AstBuilders.topK(groupingAttributes, sortSpecs, count, node));
    }

    /**
     * SESSIONIZE — groups consecutive rows into sessions by an inactivity threshold.
     *
     * @param orderColumn   the column defining order
     * @param threshold     the gap that starts a new session
     * @param partitionKeys the columns sessions are computed within
     * @param sessionColumn the column to receive the session identifier
     * @return the sessionized relation
     * @since 1.0
     */
    public Relation sessionize(String orderColumn, Operand threshold, List<String> partitionKeys,
                               String sessionColumn) {
        return derive(AstBuilders.sessionize(orderColumn, threshold, partitionKeys,
                sessionColumn, node));
    }

    /**
     * DOWNSAMPLE — consolidates rows into fixed time buckets.
     *
     * @param tsColumn the timestamp column
     * @param interval the bucket width
     * @param function how values within a bucket are consolidated
     * @param keys     the columns bucketed within
     * @return the downsampled relation
     * @since 1.0
     */
    public Relation downsample(String tsColumn, String interval, ConsolidationFunction function,
                               List<String> keys) {
        return derive(AstBuilders.downsample(tsColumn, interval, function, keys, node));
    }

    /**
     * PIVOT — turns row values into columns.
     *
     * @param valueColumn the column supplying the values
     * @param keyColumn   the column supplying the new column names
     * @param groupKeys   the columns pivoted within
     * @return the pivoted relation
     * @since 1.0
     */
    public Relation pivot(String valueColumn, String keyColumn, List<String> groupKeys) {
        return derive(AstBuilders.pivot(valueColumn, keyColumn, groupKeys, node));
    }

    /**
     * UNPIVOT — turns columns into rows.
     *
     * @param columns     the columns to unpivot
     * @param nameColumn  the column to receive each column's name
     * @param valueColumn the column to receive its value
     * @return the unpivoted relation
     * @since 1.0
     */
    public Relation unpivot(List<String> columns, String nameColumn, String valueColumn) {
        return derive(AstBuilders.unpivot(columns, nameColumn, valueColumn, node));
    }

    /**
     * TREE — folds an adjacency list into a forest of nested documents.
     *
     * @param keyColumn      each row's identifier
     * @param parentColumn   its parent's identifier
     * @param childrenColumn the column to receive each node's children
     * @return the forest
     * @since 1.0
     */
    public Relation tree(String keyColumn, String parentColumn, String childrenColumn) {
        return derive(AstBuilders.tree(keyColumn, parentColumn, childrenColumn, node));
    }

    /**
     * SAMPLE — a Bernoulli sample, each row kept with the given probability.
     *
     * @param probability the per-row probability
     * @return the sample
     * @since 1.0
     */
    public Relation sample(double probability) {
        return derive(AstBuilders.sample(probability, node));
    }

    /**
     * SAMPLE with a seed, so the draw is reproducible.
     *
     * @param probability the per-row probability
     * @param seed        the seed
     * @return the sample
     * @since 1.0
     */
    public Relation sample(double probability, long seed) {
        return derive(AstBuilders.sample(probability, Optional.of(seed), node));
    }

    /**
     * RESERVOIR SAMPLE — exactly {@code count} rows, uniformly drawn in one pass.
     *
     * @param count how many rows to keep
     * @return the sample
     * @since 1.0
     */
    public Relation sampleReservoir(long count) {
        return derive(AstBuilders.reservoirSample(count, node));
    }

    /**
     * RESERVOIR SAMPLE with a seed.
     *
     * @param count how many rows to keep
     * @param seed  the seed
     * @return the sample
     * @since 1.0
     */
    public Relation sampleReservoir(long count, long seed) {
        return derive(AstBuilders.reservoirSample(count, Optional.of(seed), node));
    }

    /**
     * SOLVE — solves {@code left = right} for the unknown, per row.
     *
     * @param left  the equation's left side
     * @param right the equation's right side
     * @return the solved relation
     * @since 1.0
     */
    public Relation solve(Operand left, Operand right) {
        return derive(AstBuilders.solve(left, right, node));
    }

    /**
     * OPTIMIZE — the subset of rows optimising an objective under constraints.
     *
     * <p>Note that this is the <em>operator</em>. Staging the logical rewriter is
     * {@code optimized()}, which takes no arguments: the two are unrelated, and the
     * operator keeps the name the reference manual gives it.
     *
     * @param sense        whether to maximise or minimise
     * @param objective    the quantity being optimised
     * @param constraints  the constraints
     * @param groupingKeys the columns a separate problem is solved per
     * @return the chosen rows
     * @since 1.0
     */
    public Relation optimize(ObjectiveSense sense, Operand objective,
                             List<OptimizeConstraint> constraints, List<String> groupingKeys) {
        return derive(AstBuilders.optimize(sense, objective, constraints, groupingKeys, node));
    }

    /**
     * COVER — a covering test suite of the given strength.
     *
     * @param strength the interaction strength
     * @return the covering set
     * @since 1.0
     */
    public Relation cover(int strength) {
        return derive(AstBuilders.cover(strength, node));
    }

    /**
     * COVER, optionally exact.
     *
     * @param strength the interaction strength
     * @param exact    whether the cover must be minimal
     * @return the covering set
     * @since 1.0
     */
    public Relation cover(int strength, boolean exact) {
        return derive(AstBuilders.cover(strength, exact, node));
    }

    // -------------------------------------------------------------------------
    // Inspection terminals — none of these executes anything
    // -------------------------------------------------------------------------

    /**
     * This relation as Relix text.
     *
     * <p>Re-parseable: the text is what {@code Relix.relation(String)} reads back, so a
     * program can hand its own optimised query to something that only speaks the language.
     * Formatting is normalised rather than preserved, and a literal's spelling may be too
     * — {@code 5.0} renders as it was parsed, not as it was typed.
     *
     * @return the expression as {@code .relix} text
     * @since 1.0
     */
    public String render() {
        return node.prettyPrint();
    }

    /**
     * The heading this relation produces.
     *
     * @return the output schema
     * @throws RelixException if the expression carries no inferred schema, which happens
     *                        only when it references a name the analyser could not resolve
     *
     * @since 1.0
     */
    public Schema schema() {
        return model.nodeSchemas().get(node).orElseThrow(() -> new RelixException(
                "no schema was inferred for this relation; " + becauseOf(unresolved())));
    }

    /**
     * The logical rewriter's output — the same relation, rewritten.
     *
     * <p>Views are inlined first, so rules optimise across a boundary the author wrote for
     * clarity. The result carries the events of its own rewrite, which is what makes this
     * more than "the tree, but different": {@link #events()} names each rule that fired.
     *
     * <p>Calling it is not what makes execution optimised — every execution terminal runs
     * the rewriter regardless. This is how a caller sees the result.
     *
     * @return the rewritten relation
     * @since 1.0
     */
    public Relation optimized() {
        List<QueryEvent> collected = new ArrayList<>();
        List<OptimizationResult> results = new QueryOptimizer().optimize(
                staged(), collected::add, distinctness(), monotoneGenerators());
        RelNode rewritten = results.isEmpty() ? node : results.getFirst().optimized();
        List<TransformationRecord> applied =
                results.isEmpty() ? List.of() : results.getFirst().applied();
        Relation derived = derive(rewritten);
        return new Relation(session, derived.model, rewritten, collected, applied, label);
    }

    /**
     * What the rewriter did to produce this relation.
     *
     * <p>Empty unless this relation came from {@link #optimized()} — a relation nobody has
     * asked to rewrite has had nothing done to it. Each event names a rule by its code
     * ({@code SEL-001}, {@code PROJ-004}) and describes what it rewrote.
     *
     * @return the {@code OPTIMIZE}-stage events, in the order the rules fired
     * @since 1.0
     */
    public List<QueryEvent> events() {
        return events;
    }

    /**
     * What the rewriter did, as records rather than as a feed.
     *
     * <p>The same rewrite {@link #events()} reports, in the form a consumer that wants to
     * <em>render</em> it needs: each record names the rule, the relation it fired on, and
     * what it did. An event says a rule fired; a record says what it fired on, which is
     * the difference between a trace and a report.
     *
     * <p>Empty unless this relation came from {@link #optimized()}, for the reason
     * {@link #events()} is.
     *
     * @return the transformations, in the order they were applied
     * @since 1.0
     */
    public List<TransformationRecord> rewrites() {
        return rewrites;
    }

    /**
     * The physical plan, as {@code --explain} prints it: join algorithms and build sides,
     * what was folded into a native query and pushed to its backend, and each node's
     * estimated row count.
     *
     * <p>Staged like {@link #render()}: this plans the relation as written, and
     * {@link #optimized()}{@code .explain()} plans the rewritten one. Nothing is executed
     * and no source is read — planning asks the catalog and the cost model, not the data.
     *
     * @return the printed plan
     * @since 1.0
     */
    public String explain() {
        return explain(QueryEventListener.NONE);
    }

    /**
     * The physical plan, with the planner's decisions reported to {@code listener}.
     *
     * <p>{@link #explain()} for a caller that wants the choices as well as the plan —
     * which join algorithm, which build side, what was pushed. The printed plan shows the
     * outcome; the events say what was decided along the way, and a host keeping a feed
     * wants both from one planning pass rather than two.
     *
     * @param listener notified of each planning decision; must not be null
     * @return the printed plan
     * @since 1.0
     */
    public String explain(QueryEventListener listener) {
        Objects.requireNonNull(listener, "listener");
        requireResolvable();
        RelNodeExecutor.PlannedQuery planned = new RelNodeExecutor()
                .withObservedCardinalities(session.observedExpressions())
                .planWithEstimates(node, planningContext(), listener);
        return PhysicalPlanPrinter.explain(planned.plan(), planned.estimates());
    }

    /**
     * The physical plan as JSON, for a program rather than a reader.
     *
     * @return the plan as a JSON document
     * @since 1.0
     */
    public String explainJson() {
        RelNodeExecutor.PlannedQuery planned = plan();
        return PhysicalPlanJson.toJson(planned.plan(), planned.estimates());
    }

    /**
     * The physical plan itself, together with the row estimates the planner computed.
     *
     * <p>The estimates travel beside the plan rather than inside it: two structurally
     * identical plans costed under different statistics would otherwise be unequal, so
     * {@link com.darkcollective.relix.plan.PlanEstimates} is an identity-keyed side table.
     *
     * @return the plan and its estimates
     * @since 1.0
     */
    public RelNodeExecutor.PlannedQuery plan() {
        requireResolvable();
        return new RelNodeExecutor()
                .withObservedCardinalities(session.observedExpressions())
                .planWithEstimates(node, planningContext(), QueryEventListener.NONE);
    }

    // -------------------------------------------------------------------------
    // Execution terminals — every one of these optimises first
    // -------------------------------------------------------------------------

    /**
     * How many rows this relation has.
     *
     * <p>This is the algebra rather than a third execution mode: it is {@code γ COUNT(*)}
     * over the relation, an operator that already pushes down, so against a database the
     * backend does the counting and exactly one row crosses the wire.
     *
     * @return the row count
     * @throws RelixException if the relation cannot be executed
     * @since 1.0
     */
    public long count() {
        Relation counted = derive(AstBuilders.groupBy(List.of(),
                List.of(AggregateFunction.aliased(AggregateOperator.COUNT, Expr.num(1), COUNT_COLUMN)),
                node));
        // A scalar aggregate answers exactly one row — a count of nothing is a row saying
        // zero, not no row — so anything else here is an engine defect rather than a query
        // that had no answer, and is said as one.
        List<Tuple> rows = counted.toList();
        Value value = rows.size() == 1 ? rows.getFirst().get(COUNT_COLUMN) : null;
        if (value instanceof NumberValue n) {
            return n.value().longValue();
        }
        throw new RelixException("counting this relation did not produce a number: " + rows);
    }

    /**
     * Every row, in memory.
     *
     * <p><strong>The one to reach for first.</strong> It drains the row stream and closes
     * it, so the common case — a result that fits in memory — cannot leak the JDBC
     * connection a lazy stream holds open.
     *
     * @return the rows, in the order the query produced them
     * @throws UnboundedRelationException if the relation is provably unbounded, since
     *         collecting one would never return
     * @throws QueryExecutionException if the query runs and a source gives way
     * @throws RelixException if the session is closed
     * @since 1.0
     */
    public List<Tuple> toList() {
        Relation optimised = optimized();
        optimised.requireBounded();
        List<Tuple> rows;
        try (Stream<Tuple> stream = optimised.open(QueryEventListener.NONE)) {
            rows = stream.toList();
        }
        // Drained, so the count is this relation's cardinality rather than a number about
        // how much the caller wanted — which is exactly the condition stream() cannot meet.
        observe(rows.size());
        return rows;
    }

    /**
     * Every row, as an instance of {@code type}.
     *
     * <p>{@link #toList()} with the mapping written for you. A record's components carry
     * a name and a type, which is everything the mapping needs, so each component is read
     * from the column of the same name through the accessor its type names — with the
     * same refusal semantics {@link Tuple} has, since it <em>is</em> {@code Tuple} doing
     * the reading.
     *
     * {@snippet lang = "java":
     * record Order(long id, String customer, BigDecimal amount) { }
     *
     * List<Order> orders = relix.relation("Orders").toList(Order.class);
     * }
     *
     * <p><strong>The binding is checked before any row moves.</strong> The heading is
     * known without running anything, so a component naming no column, or one whose column
     * holds another type, is a failure at this call rather than at row 400,000. The
     * exceptions are what the heading cannot answer: a column typed {@code ANY} carries no
     * declared type to check against, and whether a value is NULL is not a property of a
     * heading at all — so a {@code NULL} read into a primitive component is refused at that
     * row, naming the component and asking for the boxed type.
     *
     * <p><strong>Names must match.</strong> There is no annotation to say otherwise: the
     * query already renames a column, and {@code π amount → total (…)} says it where a
     * reader of the query will look. Matching is case-insensitive, as every column lookup
     * is.
     *
     * <p>A component may be a {@code String}, a {@code BigDecimal}, {@code Long}/{@code long},
     * {@code Integer}/{@code int}, {@code Double}/{@code double},
     * {@code Boolean}/{@code boolean}, an {@code Instant}, {@code LocalDate},
     * {@code LocalTime} or {@code Duration}, a {@code List} for an array column, a
     * {@code Map} for a struct column, or a {@code Value} — which is the one to reach for
     * where a column's type genuinely varies, and the only one that receives a NULL as a
     * {@code NullValue} rather than as Java {@code null}.
     *
     * <p>The record is built through its canonical constructor, reflectively. A record in
     * a named module therefore has to be reachable from here — public in an exported
     * package, or its package opened — and is refused by name when it is not.
     *
     * @param type the record to read each row into; must not be null
     * @param <T>  the record type
     * @return the rows, in the order the query produced them
     * @throws RelixException if {@code type} is not a record, if a component names no
     *                        column, if a column holds a type the component cannot hold, or
     *                        if the record cannot be constructed from here
     * @throws UnboundedRelationException if the relation is provably unbounded, since
     *         collecting one would never return
     * @throws QueryExecutionException if the query runs and a source gives way
     *
     * @since 1.0
     */
    public <T> List<T> toList(Class<T> type) {
        RecordBinding<T> binding = RecordBinding.of(type, schema());
        return toList().stream().map(binding::bind).toList();
    }

    /**
     * Every row, lazily.
     *
     * <p>The deliberate opt-in for a caller who wants the engine's laziness and accepts
     * what comes with it: <strong>the stream is a resource and the caller closes it</strong>,
     * because it may hold a live database cursor. Use it in a try-with-resources.
     *
     * <p>Closing early is how a partial read is cancelled — the engine is pull-based end to
     * end, so nothing further is computed once nobody pulls. That is also why this needs no
     * boundedness guard: lazily consuming a relation that never ends is what a generator is
     * for.
     *
     * @return a lazy stream of rows; the caller must close it
     * @throws RelixException if the session is closed
     * @since 1.0
     */
    public Stream<Tuple> stream() {
        return optimized().open(QueryEventListener.NONE);
    }

    /**
     * Every row, lazily, with the run reported to {@code listener} as it happens.
     *
     * <p>{@link #run()} for a caller that must not collect the rows — a trace over a
     * result too large to hold, most obviously, where what is wanted is the feed and the
     * rows are drained and dropped.
     *
     * <p>The rewrite's events arrive first and in full, because the rewriter has already
     * finished by the time there is a stream to hand back; the planner's and the
     * executor's arrive as the stream is pulled. As with {@link #stream()}, the caller
     * owns the stream and closes it.
     *
     * @param listener notified of each event of this run; must not be null
     * @return a lazy stream of rows; the caller must close it
     * @throws RelixException if the session is closed
     * @since 1.0
     */
    public Stream<Tuple> stream(QueryEventListener listener) {
        Objects.requireNonNull(listener, "listener");
        Relation optimised = optimized();
        optimised.events().forEach(listener::onEvent);
        return optimised.open(listener);
    }

    /**
     * Every row, lazily, as an instance of {@code type}.
     *
     * <p>{@link #toList(Class)}'s mapping over {@link #stream()}'s laziness — the pairing
     * a result too large to hold wants. The binding is still checked before the query
     * runs, and the stream is still the caller's to close.
     *
     * @param type the record to read each row into; must not be null
     * @param <T>  the record type
     * @return a lazy stream of records; the caller must close it
     * @throws RelixException if the binding cannot be made — see {@link #toList(Class)}
     * @since 1.0
     */
    public <T> Stream<T> stream(Class<T> type) {
        RecordBinding<T> binding = RecordBinding.of(type, schema());
        return stream().map(binding::bind);
    }

    /**
     * Every row, together with what the engine did to produce it.
     *
     * <p>{@link #toList()} with the run's own event feed attached — the rules that fired,
     * the planner's choices, what was pushed to a backend. See {@link Rows}.
     *
     * @return the rows and this run's events
     * @throws UnboundedRelationException if the relation is provably unbounded
     * @throws QueryExecutionException if the query runs and a source gives way
     * @throws RelixException if the session is closed
     * @since 1.0
     */
    public Rows run() {
        Relation optimised = optimized();
        optimised.requireBounded();
        List<QueryEvent> collected = new ArrayList<>(optimised.events());
        List<Tuple> rows;
        // Wall clock over the drain — the run's own elapsed time, which is what a caller
        // asking "how long did this take?" means. A scan's duration is self time and
        // answers a different question; see EventMetrics.duration.
        long start = System.nanoTime();
        try (Stream<Tuple> stream = optimised.open(collected::add)) {
            rows = stream.toList();
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        // The run's own cardinality, stated as an event so it arrives on the one feed
        // rather than as a second channel a consumer has to know about. It is emitted
        // here rather than by the executor because the executor cannot say it: a row
        // stream's length is not known until something drains it, and this terminal is
        // the thing that does. Deliberately after the drain, and so not reached when the
        // query throws part-way — a failed run delivered no result, and a row count for
        // it would be read as a relation's size by everything downstream.
        collected.add(QueryEvent.of(QueryEvent.Stage.EXECUTE, "ROWS",
                        "query delivered " + rows.size() + " row" + (rows.size() == 1 ? "" : "s"))
                .withMetrics(EventMetrics.of(rows.size(), elapsed)));
        observe(rows.size());
        return new Rows(optimised.schema(), rows, collected);
    }

    /**
     * The relation annotated over a semiring — where each output row came from, in whatever
     * algebra the semiring names.
     *
     * <p>Booleans give presence, ℕ gives multiplicity or a path count, the tropical
     * semiring gives a shortest distance, and the polynomial semiring gives full lineage:
     * the exact input tuples that produced each output tuple, and how they combined.
     *
     * <p>This is not {@link #why()}. That operator reifies lineage into a <em>column</em> of
     * an ordinary relation; this annotates the <em>whole relation</em> over a semiring of
     * the caller's choosing, which is why it cannot ride the row terminals.
     *
     * <p>Evaluated from the raw logical tree — no rewriting and no pushdown, since
     * annotation tracking is in-engine, above the federation boundary — and materialised
     * rather than streamed, because a K-relation is canonical.
     *
     * @param semiring the annotation semiring; must not be null
     * @param <K>      the annotation type
     * @return the annotated relation
     * @throws RelixException if the session is closed
     * @since 1.0
     */
    public <K> AnnotatedRelation<K> provenance(Semiring<K> semiring) {
        return annotate(semiring, null);
    }

    /**
     * As {@link #provenance(Semiring)}, reading each edge's weight from a column.
     *
     * <p>What makes a weighted transitive closure mean something: with a tropical semiring
     * and a {@code distance} column the annotation of a reachable pair is the shortest
     * route to it. A row lacking the column, or carrying a null or non-numeric value,
     * weighs the semiring's one.
     *
     * @param semiring     the annotation semiring; must not be null
     * @param weightColumn the per-edge weight column; must not be null
     * @param <K>          the annotation type
     * @return the annotated relation
     * @throws RelixException if the session is closed
     * @since 1.0
     */
    public <K> AnnotatedRelation<K> provenance(Semiring<K> semiring, String weightColumn) {
        Objects.requireNonNull(weightColumn, "weightColumn");
        return annotate(semiring, weightColumn);
    }

    // -------------------------------------------------------------------------
    // Execution internals
    // -------------------------------------------------------------------------

    private <K> AnnotatedRelation<K> annotate(Semiring<K> semiring, String weightColumn) {
        Objects.requireNonNull(semiring, "semiring");
        session.requireOpen();
        requireResolvable();
        // Which shape of base annotation applies is the semiring's own answer, so there is
        // nothing to dispatch on here: lineage mints a variable per occurrence and the
        // weighted semirings read the column, both through the same call.
        BaseAnnotator<K> annotator = BaseAnnotator.forSemiring(semiring, weightColumn);
        try (DataSourceConnector connector = session.openConnector(model)) {
            return new ProvenanceEvaluator()
                    .evaluate(node, semiring, context(connector, QueryEventListener.NONE), annotator);
        } catch (RuntimeException e) {
            // Reads every row before it can annotate one, so there is no lazy window here:
            // the single catch covers the whole of it.
            throw asFailure(e);
        }
    }

    /**
     * Plans and starts this relation, handing the caller a stream that owns the connector.
     *
     * <p>Ownership is the whole of it: the connector holds the borrowed JDBC connections a
     * lazy stream reads through, so it is closed when the stream is — and closed here if
     * planning throws, because in that case no stream ever reaches the caller to close.
     */
    private Stream<Tuple> open(QueryEventListener listener) {
        session.requireOpen();
        requireResolvable();
        DataSourceConnector connector = session.openConnector(model);
        try {
            // Registered with the session, so a stream the caller walks away from is still
            // closed when the session is — the connection it borrowed is otherwise beyond
            // the pool's reach, which closes what is idle and not what is out on loan.
            return session.track(guarded(new RelNodeExecutor()
                    .withObservedCardinalities(session.observedExpressions())
                    .execute(node, context(connector, observing(listener)))
                    .map(Tuple::of)
                    .onClose(connector::close)));
        } catch (RuntimeException e) {
            connector.close();
            throw asFailure(e);
        }
    }

    /**
     * The engine's row stream, with every failure it can raise restated as this API's own.
     *
     * <p>A lazy stream does its work when pulled, so the window this closes is not the one
     * the {@code catch} above closes: starting the query is where a CSV file that is
     * missing or a pushed statement a database refuses shows up, and <em>iterating</em> is
     * where a connection dropped halfway through a result set does. The second window is
     * the one a caller cannot guard by wrapping the call that opened the stream, because by
     * then the exception is arriving inside their own loop.
     *
     * <p>Wrapping the spliterator rather than mapping the elements is what reaches it: the
     * throw comes from advancing the stream, not from anything a {@code map} would see.
     */
    private static Stream<Tuple> guarded(Stream<Tuple> rows) {
        Spliterator<Tuple> source = rows.spliterator();
        Spliterator<Tuple> guarded = new Spliterators.AbstractSpliterator<>(
                source.estimateSize(), source.characteristics() & GUARDED_CHARACTERISTICS) {
            @Override
            public boolean tryAdvance(Consumer<? super Tuple> action) {
                try {
                    return source.tryAdvance(action);
                } catch (RuntimeException e) {
                    throw asFailure(e);
                }
            }
        };
        return StreamSupport.stream(guarded, false).onClose(() -> {
            try {
                rows.close();
            } catch (RuntimeException e) {
                throw asFailure(e);
            }
        });
    }

    /**
     * Restates an engine failure as a {@link QueryExecutionException}, keeping the message.
     *
     * <p>The engine's message is better than anything that could be written here — it names
     * the connection and table, or the relation, that gave way — so the wrapper carries it
     * unchanged and adds only the type. What is already this API's own is passed through:
     * a closed session and an unresolvable name are refusals rather than failures, and
     * re-wrapping one would say the query ran when it never started.
     */
    private static RuntimeException asFailure(RuntimeException e) {
        if (e instanceof RelixException) {
            return e;
        }
        // The planner's refusal of a blocking operator over an endless input is the same
        // fact as a collecting terminal's, reached by a different route, so it is not a
        // failure and does not arrive as one.
        if (e instanceof com.darkcollective.relix.plan.BoundednessException) {
            return new UnboundedRelationException(e.getMessage(), e);
        }
        return new QueryExecutionException(
                e.getMessage() == null ? e.toString() : e.getMessage(), e);
    }

    /**
     * The caller's listener with this session's own measuring one behind it.
     *
     * <p>Every execution observes, not only a traced one: a scan the engine read to the
     * end is a measured cardinality whether or not anybody asked to watch, and throwing
     * it away because the caller passed no listener would make the cost model's memory
     * depend on whether someone was looking.
     */
    private QueryEventListener observing(QueryEventListener listener) {
        return event -> {
            if (event.stage() == QueryEvent.Stage.EXECUTE && event.code().equals("SCAN")) {
                event.target().ifPresent(relation ->
                        event.metrics().rows().ifPresent(rows ->
                                session.observeRows(relation, rows)));
            }
            listener.onEvent(event);
        };
    }

    /**
     * Records that this relation produced {@code rows} rows — but only when that number
     * is a property of the expression rather than of the moment it ran.
     *
     * <p>The gate is the one shared sub-expression elimination already needs, and for the
     * same reason: {@code σ ts > NOW() − DURATION 'P1D' (Events)} has a count that drifts
     * by construction, and an unseeded {@code SAMPLE} draws differently every time. A
     * memo of either is a number that was true once.
     */
    private void observe(long rows) {
        if (RelationDeterminism.isDeterministic(node, model.symbolTable(), model.functions())) {
            session.observedExpressions().record(node, rows);
        }
    }

    /**
     * Refuses a relation that provably never ends.
     *
     * <p>The plan-time check catches a blocking <em>operator</em> over an unbounded input;
     * it cannot see a blocking <em>consumer</em>, which is not in the tree at all. A
     * terminal that collects is exactly that consumer, so it asks the question the checker
     * structurally cannot.
     */
    private void requireBounded() {
        BoundednessSource boundedness =
                new GeneratorBoundednessSource(model.sources(), session.generators());
        if (PropertyDeriver.boundedness(node, boundedness) == Boundedness.UNBOUNDED) {
            throw new UnboundedRelationException(
                    "cannot collect an unbounded relation into a list; add a bound "
                            + "(e.g. limit(n)), or stream it instead");
        }
    }

    /**
     * Refuses a relation naming something its own analysis cannot resolve.
     *
     * <p>Planning is where such a name is next asked about, and what it says there is
     * either an internal invariant ("no schema annotation for ProjectionNode") or an
     * {@code IllegalStateException} — both of which read as engine defects rather than as
     * a query naming a table nothing declares. The names are known here, so they are said
     * here, in the exception type the rest of this API refuses with.
     *
     * <p>Only a session in {@code allowUnresolved} mode gets this far: strict analysis
     * refuses an unresolvable name when the relation is built.
     */
    private void requireResolvable() {
        List<String> unresolved = unresolved();
        if (!unresolved.isEmpty()) {
            throw new RelixException("cannot plan this relation; " + becauseOf(unresolved));
        }
    }

    /** The relation names in this expression that this relation's symbol table does not resolve. */
    private List<String> unresolved() {
        List<String> names = new ArrayList<>();
        collectUnresolved(node, names);
        return names;
    }

    private void collectUnresolved(RelNode current, List<String> names) {
        if (current instanceof RelationNode reference
                && model.symbolTable().resolveRelation(reference.name()).isEmpty()
                && !names.contains(reference.name())) {
            names.add(reference.name());
        }
        current.children().forEach(child -> collectUnresolved(child, names));
    }

    /** Why a relation could not be resolved, naming what could not be, when that is known. */
    private static String becauseOf(List<String> unresolved) {
        return unresolved.isEmpty()
                ? "it references a name that could not be resolved"
                : "it references " + (unresolved.size() == 1 ? "a relation" : "relations")
                        + " nothing declares: " + String.join(", ", unresolved);
    }

    /** The context an execution runs in: this relation's own model, the session's bindings. */
    private ExecutionContext context(DataSourceConnector connector, QueryEventListener listener) {
        return ExecutionContext.of(measured(model), connector)
                .withClock(session.clock())
                // The session's cap, not the context's default of unlimited. Every guard
                // built on it reads it from here — FIX's round limit, TRACE's
                // improvement-cycle limit, and the weighted-closure bound — so a session
                // that asked for one and did not get it is a runaway query with the brake
                // silently disconnected.
                .withMaxFixpointRounds(session.maxFixpointRounds())
                // Likewise the session's, not the context's default of unlimited: the
                // blocking-operator row cap is the one brake on a bounded relation too
                // large for the heap, and a session that asked for one and did not get it
                // is exactly the unattributable OutOfMemoryError it exists to replace.
                .withMaxMaterializedRows(session.maxMaterializedRows())
                .withListener(listener);
    }

    /**
     * This model with the session's measured row counts folded into its statistics.
     *
     * <p>A leaf's real size has a home already — {@code RelationStatistics}, keyed by
     * relation — so an observed count needs no new channel to reach the cost model: it
     * goes where an introspected one goes, and every consumer of analysis-time statistics
     * reads it without knowing the difference.
     */
    private SemanticModel measured(SemanticModel base) {
        Map<String, RelationStatistics> statistics =
                session.statisticsWithObservations(base.statistics());
        return statistics == base.statistics() ? base : new SemanticModel(
                base.namespace(), base.symbolTable(), base.sources(), base.connections(),
                statistics, base.nodeSchemas(), base.schemaGraph(), base.rootQueries(),
                base.functions());
    }

    /**
     * The context planning runs in.
     *
     * <p>Its connector rejects every open, which is not a limitation: planning asks the
     * catalog and the cost model and reads no rows, so building the real connector stack
     * would cost a service-loader scan to open nothing.
     */
    private ExecutionContext planningContext() {
        return ExecutionContext.inlineOnly(measured(model)).withClock(session.clock());
    }

    /**
     * This relation as a one-query model — the shape the optimizer takes.
     *
     * <p>The optimizer's entry point is a whole model because a script has many roots; a
     * relation is one expression, so it is presented as a model with exactly one.
     */
    private SemanticModel staged() {
        SemanticModel base = measured(model);
        return new SemanticModel(
                base.namespace(), base.symbolTable(), base.sources(), base.connections(),
                base.statistics(), base.nodeSchemas(), base.schemaGraph(),
                List.of(new QueryStatement(new ExpressionQueryTarget(node), SourceLocation.UNKNOWN)),
                base.functions());
    }

    /**
     * The two disjoint kinds of inherently duplicate-free leaf, so {@code DIST-001} can
     * drop a δ over one: a generator that declares itself distinct, and a base relation
     * whose statistics carry a declared key.
     */
    private DistinctnessSource distinctness() {
        return DistinctnessSource.anyOf(
                new GeneratorDistinctnessSource(model.sources(), session.generators()),
                new StatisticsDistinctnessSource(
                        StatisticsSource.of(model.symbolTable(), model.statistics())));
    }

    /** The ascending-generator lookup {@code GEN-001} folds an upper-bound σ against. */
    private MonotoneGeneratorSource monotoneGenerators() {
        return new GeneratorMonotonicitySource(model.sources(), session.generators());
    }

    @Override
    public String toString() {
        return node.prettyPrint();
    }
}

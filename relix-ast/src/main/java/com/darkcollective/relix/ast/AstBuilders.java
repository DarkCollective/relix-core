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
import java.util.OptionalLong;


/**
 * The AST authoring surface — one factory per node kind, for tests and embedders that
 * build a tree directly rather than parsing one.
 *
 * <p>The AST is the engine's public contract, so constructing it must not require the
 * grammar. These factories are that alternative: every concrete {@link RelNode},
 * {@link Predicate} and {@link Operand} kind has one, and {@code AstBuilderCoverageTest}
 * fails the build if a newly permitted kind arrives without it.
 *
 * <h2>The one rule</h2>
 *
 * <p><strong>A factory takes the record's own components minus {@link SourceLocation}</strong>
 * — which defaults to {@link SourceLocation#UNKNOWN} — <strong>arranged so that the
 * relation inputs sit where the operator's own notation puts them.</strong> Where a node
 * offers a narrower convenience constructor, the factory is overloaded to match it.
 *
 * <p>That arrangement is decided by the operator, not by the record, because the records
 * themselves are not consistent about it — most declare the input last, seven declare it
 * first, and {@code UnnestNode} declares it fourth. Reproducing each record's own order
 * would publish that inconsistency as the thing a caller has to learn. So:
 *
 * <ol>
 *   <li><b>A leaf takes no relation.</b> {@code rel("Orders")}, {@code unitRel()}.</li>
 *   <li><b>A unary operator's input goes last</b>, after the operator's own arguments, so
 *       the call reads as the operator does: {@code select(pred, rel("R"))} is
 *       σ<sub>pred</sub>(R), and {@code closure("src", "dst", rel("Edges"))} is exactly
 *       how {@code CLOSURE src, dst (Edges)} is written.</li>
 *   <li><b>A join or set operation's inputs lead</b>, before any modifier, so the call
 *       reads as the infix operator does: {@code join(left, right, cond)} is
 *       L&nbsp;⋈<sub>cond</sub>&nbsp;R, {@code union(a, b)} is A&nbsp;∪&nbsp;B. This is
 *       what puts {@code lateral(left, "f", args)} here rather than under (2) — its right
 *       input is a table-function call rather than a relation, but it is a join.</li>
 *   <li><b>A binder precedes what it scopes.</b> {@code fixpoint(name, base, step)} is the
 *       only node where a name scopes an input: {@code step} cannot be read without
 *       knowing what {@code name} binds, so it leads its two relations.</li>
 * </ol>
 *
 * <p>{@code AstBuilderOrderTest} holds every factory to this, so the rule is checked
 * rather than remembered — the four graph operators ({@code closure}, {@code cluster},
 * {@code path}, {@code trace}) each took their input first until it was, which put them
 * in silent disagreement with their own surface syntax.
 *
 * <p>List-valued components are taken as {@link List}; the element helpers
 * ({@link #attrs}, {@link #cols}, {@link #asc}, {@link #desc}, {@link #projected},
 * {@link #agg}) are what keep the call sites short:
 *
 * {@snippet lang = "java":
 * RelNode plan = project(attrs("dept", "total"),
 *                        groupBy(cols("dept"), List.of(agg(AggregateOperator.SUM, "amount")),
 *                                select(cmp(attr("status"), ComparisonOperator.EQUAL, str("OPEN")),
 *                                       rel("Orders"))));
 * }
 *
 * <p>Extend this class to reach the factories unqualified, or import them statically.
 *
 * <p>This is {@code main} source, not a test fixture: building a tree without the
 * grammar is what the AST being the engine's public contract <em>means</em>, and a
 * surface published from {@code testFixtures} is one embedders were told to use but
 * could not depend on. The consequence is that these factories carry the same
 * compatibility promise as the records they build — a renamed record component is a
 * breaking change here, not a test edit.
 *
 * <p>For a shorter spelling of the same nodes — {@code eq(attr("id"), num(42))} rather
 * than {@code cmp(attr("id"), ComparisonOperator.EQUAL, num("42"))} — and for building
 * literals from Java values instead of their source spelling, see {@link Expr}, which
 * extends this class.
 *
 * @see Expr
 */
public abstract class AstBuilders {

    // -------------------------------------------------------------------------
    // Operand factories — one per Operand kind
    // -------------------------------------------------------------------------

    /** A column reference, e.g. {@code amount}. */
    public static AttributeOperand attr(String name) {
        return new AttributeOperand(name);
    }

    /** A numeric literal, spelled as written — {@code "5"} and {@code "5.0"} differ. */
    public static NumberOperand num(String value) {
        return new NumberOperand(value);
    }

    /** A string literal. */
    public static StringOperand str(String value) {
        return new StringOperand(value);
    }

    /** A boolean literal. */
    public static BooleanOperand bool(boolean value) {
        return new BooleanOperand(value);
    }

    /** Builds a {@link DateOperand} from an ISO-8601 date string, e.g. {@code "2026-06-15"}. */
    public static DateOperand date(String iso) {
        return new DateOperand(TemporalLiterals.parseDate(iso));
    }

    /** Builds a {@link TimeOperand} from an ISO-8601 time string, e.g. {@code "13:40:00"}. */
    public static TimeOperand time(String iso) {
        return new TimeOperand(TemporalLiterals.parseTime(iso));
    }

    /**
     * Builds a {@link TimestampOperand} from an ISO-8601 timestamp string,
     * UTC-normalised, e.g. {@code "2026-06-15T13:40:00Z"}.
     */
    public static TimestampOperand timestamp(String iso) {
        return new TimestampOperand(TemporalLiterals.parseTimestamp(iso));
    }

    /** Builds a {@link DurationOperand} from an ISO-8601 duration string, e.g. {@code "PT30M"}. */
    public static DurationOperand duration(String iso) {
        return new DurationOperand(TemporalLiterals.parseDuration(iso));
    }

    /** A binary arithmetic expression, e.g. {@code price * qty}. */
    public static BinaryArithmeticExpression arith(Operand left, ArithmeticOperator operator, Operand right) {
        return new BinaryArithmeticExpression(left, operator, right);
    }

    /** A scalar function call, e.g. {@code Abs(delta)}. */
    public static FunctionCall func(String name, Operand... args) {
        return new FunctionCall(name, List.of(args));
    }

    /** A set literal, the right-hand side of {@code IN}. */
    public static SetLiteralOperand set(Operand... elements) {
        return new SetLiteralOperand(List.of(elements));
    }

    /** An array construction {@code [a, b, …]}. */
    public static ArrayConstruction arrayOf(Operand... elements) {
        return new ArrayConstruction(List.of(elements));
    }

    /** One field of a {@link #structOf} construction. */
    public static StructConstruction.Field field(String name, Operand value) {
        return new StructConstruction.Field(name, value);
    }

    /** A struct construction <code>&#123;name: expr, …&#125;</code>. */
    public static StructConstruction structOf(StructConstruction.Field... fields) {
        return new StructConstruction(List.of(fields));
    }

    /** Arithmetic negation of an operand. */
    public static UnaryOperand unary(Operand operand) {
        return new UnaryOperand(operand);
    }

    /** A predicate used where an operand is expected, so a boolean can be projected. */
    public static ConditionOperand condition(Predicate predicate) {
        return new ConditionOperand(predicate);
    }

    // -------------------------------------------------------------------------
    // Predicate factories — one per Predicate kind
    // -------------------------------------------------------------------------

    /** A comparison, e.g. {@code amount > 100}. */
    public static ComparisonPredicate cmp(Operand left, ComparisonOperator operator, Operand right) {
        return new ComparisonPredicate(left, operator, right);
    }

    /** Conjunction {@code left ∧ right}. */
    public static AndPredicate and(Predicate left, Predicate right) {
        return new AndPredicate(left, right);
    }

    /** Disjunction {@code left ∨ right}. */
    public static OrPredicate or(Predicate left, Predicate right) {
        return new OrPredicate(left, right);
    }

    /** Negation {@code ¬predicate}. */
    public static NotPredicate not(Predicate predicate) {
        return new NotPredicate(predicate);
    }

    /** {@code IS NULL} when {@code isNull}, {@code IS NOT NULL} otherwise. */
    public static NullPredicate nullPred(Operand operand, boolean isNull) {
        return new NullPredicate(operand, isNull);
    }

    /** Membership {@code element ∈ setExpression}. */
    public static ElementOfPredicate elementOf(Operand element, Operand setExpression) {
        return new ElementOfPredicate(element, setExpression, false);
    }

    /** Non-membership {@code element ∉ setExpression}. */
    public static ElementOfPredicate notElementOf(Operand element, Operand setExpression) {
        return new ElementOfPredicate(element, setExpression, true);
    }

    /** SQL {@code LIKE}. */
    public static PatternPredicate like(Operand operand, Operand pattern) {
        return new PatternPredicate(operand, pattern, false);
    }

    /** SQL {@code NOT LIKE}. */
    public static PatternPredicate notLike(Operand operand, Operand pattern) {
        return new PatternPredicate(operand, pattern, true);
    }

    // -------------------------------------------------------------------------
    // Element helpers — the list members the node factories take
    // -------------------------------------------------------------------------

    /** An unaliased projected expression. */
    public static ProjectedAttribute projected(Operand expression) {
        return ProjectedAttribute.simple(expression);
    }

    /** An aliased projected expression, {@code expression → alias}. */
    public static ProjectedAttribute projected(Operand expression, String alias) {
        return ProjectedAttribute.aliased(expression, alias);
    }

    /** The projection list for bare column names — the π argument tests write most. */
    public static List<ProjectedAttribute> attrs(String... columns) {
        return java.util.Arrays.stream(columns)
                .map(c -> ProjectedAttribute.simple(new AttributeOperand(c)))
                .toList();
    }

    /** A plain list of column names, for the components that take one. */
    public static List<String> cols(String... columns) {
        return List.of(columns);
    }

    /** An ascending sort key on a bare column. */
    public static SortSpecification asc(String column) {
        return new SortSpecification(column, SortDirection.ASC);
    }

    /** A descending sort key on a bare column. */
    public static SortSpecification desc(String column) {
        return new SortSpecification(column, SortDirection.DESC);
    }

    /** A sort key on an arbitrary expression. */
    public static SortSpecification sortKey(Operand expression, SortDirection direction) {
        return new SortSpecification(expression, direction);
    }

    /** An unaliased aggregate over a bare column, e.g. {@code SUM(amount)}. */
    public static AggregateFunction agg(AggregateOperator operator, String attribute) {
        return AggregateFunction.simple(operator, attribute);
    }

    /** An aliased aggregate over a bare column, e.g. {@code SUM(amount) → total}. */
    public static AggregateFunction agg(AggregateOperator operator, String attribute, String alias) {
        return AggregateFunction.aliased(operator, attribute, alias);
    }

    /** An aggregate over an arbitrary expression, e.g. {@code SUM(price * qty)}. */
    public static AggregateFunction aggOf(AggregateOperator operator, Operand argument) {
        return AggregateFunction.of(operator, argument);
    }

    /**
     * A two-argument aggregate, e.g. {@code ARGMAX(rank, name)} — the value of
     * {@code yieldExpr} from the row where {@code argument} is the group extremum.
     */
    public static AggregateFunction argAgg(AggregateOperator operator, Operand argument,
                                           Operand yieldExpr) {
        return new AggregateFunction(operator, argument, Optional.of(yieldExpr),
                Optional.empty());
    }

    /** An aliased two-argument aggregate, e.g. {@code ARGMAX(rank, name) → top}. */
    public static AggregateFunction argAgg(AggregateOperator operator, Operand argument,
                                           Operand yieldExpr, String alias) {
        return new AggregateFunction(operator, argument, Optional.of(yieldExpr),
                Optional.of(alias));
    }

    /** A grouping key on a bare column. */
    public static GroupingKey key(String column) {
        return GroupingKey.column(column);
    }

    /** A grouping key on an arbitrary expression, e.g. {@code YEAR(order_date) → yr}. */
    public static GroupingKey key(Operand expression, String alias) {
        return GroupingKey.aliased(expression, alias);
    }

    /** One column rename {@code from → to}, for the pair form of ρ. */
    public static RenameNode.RenamePair renamePair(String from, String to) {
        return new RenameNode.RenamePair(from, to);
    }

    /** A generator's produce bound, e.g. {@code n < 100}. */
    public static ProduceBound produceBound(String column, ComparisonOperator operator, Operand limit) {
        return new ProduceBound(column, operator, limit);
    }

    /** One {@code OPTIMIZE} constraint, e.g. {@code SUM(cost) ≤ 50}. */
    public static OptimizeConstraint constraint(Operand expr, ComparisonOperator op, double bound) {
        return new OptimizeConstraint(expr, op, bound);
    }

    /** An {@code OPTIMIZE} continuous-allocation spec. */
    public static AllocationSpec allocation(double lo, double hi, String columnName) {
        return new AllocationSpec(lo, hi, columnName);
    }

    // -------------------------------------------------------------------------
    // Leaf nodes
    // -------------------------------------------------------------------------

    /** A base-relation reference. */
    public static RelationNode rel(String name) {
        return new RelationNode(name);
    }

    /** A generator reference carrying a produce bound. */
    public static RelationNode rel(String name, ProduceBound bound) {
        return new RelationNode(name, Optional.of(bound), SourceLocation.UNKNOWN);
    }

    /** A table-valued function call, {@code f(args…)}. */
    public static RelationFunctionCall tvf(String functionName, Operand... arguments) {
        return new RelationFunctionCall(functionName, List.of(arguments));
    }

    /** The one-tuple truth relation {@code UNIT} ({@code DEE}). */
    public static TruthRelationNode unitRel() {
        return TruthRelationNode.unit(SourceLocation.UNKNOWN);
    }

    /** The zero-tuple truth relation {@code EMPTY} ({@code DUM}). */
    public static TruthRelationNode emptyRel() {
        return TruthRelationNode.empty(SourceLocation.UNKNOWN);
    }

    /**
     * The optimizer's ∅ — no rows, carrying {@code heading}'s schema. The heading is an
     * inert component rather than a child, so it is invisible to structural traversal.
     */
    public static EmptyRelationNode emptyOf(RelNode heading) {
        return EmptyRelationNode.of(heading);
    }

    /** A reference to the enclosing {@code FIX} accumulator. */
    public static RecursiveRefNode recRef(String name) {
        return new RecursiveRefNode(name);
    }

    // -------------------------------------------------------------------------
    // Unary operators
    // -------------------------------------------------------------------------

    /** σ — selection. */
    public static SelectionNode select(Predicate predicate, RelNode input) {
        return new SelectionNode(predicate, input);
    }

    /** π — projection. */
    public static ProjectionNode project(List<ProjectedAttribute> attributes, RelNode input) {
        return new ProjectionNode(attributes, input);
    }

    /** ρ — rename the relation and, optionally, all its columns positionally. */
    public static RenameNode rename(String relationName, List<String> attributes, RelNode input) {
        return new RenameNode(relationName, attributes, input);
    }

    /** ρ — the general form: optional relation name, positional attributes, and {@code from → to} pairs. */
    public static RenameNode rename(Optional<String> relationName, List<String> attributes,
                                    List<RenameNode.RenamePair> pairs, RelNode input) {
        return new RenameNode(relationName, attributes, pairs, input, SourceLocation.UNKNOWN);
    }

    /** γ — aggregation grouped by bare columns. */
    public static AggregationNode groupBy(List<String> groupingColumns,
                                          List<AggregateFunction> aggregates, RelNode input) {
        return new AggregationNode(groupingColumns, aggregates, input);
    }

    /** γ — aggregation grouped by arbitrary key expressions. */
    public static AggregationNode groupByKeys(List<GroupingKey> groupingKeys,
                                              List<AggregateFunction> aggregates, RelNode input) {
        return new AggregationNode(groupingKeys, aggregates, input, SourceLocation.UNKNOWN);
    }

    /** τ — sort. */
    public static SortNode sort(List<SortSpecification> sortSpecs, RelNode input) {
        return new SortNode(sortSpecs, input);
    }

    /** λ — limit, no offset. */
    public static LimitNode limit(long count, RelNode input) {
        return new LimitNode(Optional.empty(), count, input);
    }

    /** λ — limit with an offset. */
    public static LimitNode limit(Optional<Long> offset, Long count, RelNode input) {
        return new LimitNode(offset, count, input);
    }

    /** δ — duplicate elimination. */
    public static DistinctNode distinct(RelNode input) {
        return new DistinctNode(input);
    }

    /** μ — unnest an array column. */
    public static UnnestNode unnest(String column, RelNode input) {
        return new UnnestNode(column, input);
    }

    /** μ — unnest, keeping rows whose array is empty when {@code outer}. */
    public static UnnestNode unnest(String column, boolean outer, RelNode input) {
        return new UnnestNode(column, outer, input);
    }

    /** μ — unnest with an ordinality column. */
    public static UnnestNode unnest(String column, boolean outer, Optional<String> ordinalityColumn,
                                    RelNode input) {
        return new UnnestNode(column, outer, ordinalityColumn, input, SourceLocation.UNKNOWN);
    }

    /** ∀ — universal quantification over grouping columns. */
    public static UniversalNode universal(List<String> groupingAttributes, Predicate predicate,
                                          RelNode input) {
        return new UniversalNode(groupingAttributes, predicate, input);
    }

    /** ω — reify each tuple's lineage as a nested {@code provenance} column. */
    public static WhyNode why(RelNode input) {
        return new WhyNode(input);
    }

    // -------------------------------------------------------------------------
    // Joins
    // -------------------------------------------------------------------------

    /** ⋈ — natural join. */
    public static NaturalJoinNode naturalJoin(RelNode left, RelNode right) {
        return new NaturalJoinNode(left, right);
    }

    /** ⨝ — theta join. */
    public static ThetaJoinNode join(RelNode left, RelNode right, Predicate condition) {
        return new ThetaJoinNode(left, right, condition);
    }

    /** ⟕ — left outer join. */
    public static LeftOuterJoinNode leftJoin(RelNode left, RelNode right, Predicate condition) {
        return new LeftOuterJoinNode(left, right, condition);
    }

    /** ⟖ — right outer join. */
    public static RightOuterJoinNode rightJoin(RelNode left, RelNode right, Predicate condition) {
        return new RightOuterJoinNode(left, right, condition);
    }

    /** ⟗ — full outer join. */
    public static FullOuterJoinNode fullJoin(RelNode left, RelNode right, Predicate condition) {
        return new FullOuterJoinNode(left, right, condition);
    }

    /** ⋉ — semi join. */
    public static SemiJoinNode semiJoin(RelNode left, RelNode right, Predicate condition) {
        return new SemiJoinNode(left, right, condition);
    }

    /** ▷ — anti join. */
    public static AntiJoinNode antiJoin(RelNode left, RelNode right, Predicate condition) {
        return new AntiJoinNode(left, right, condition);
    }

    /** The pairwise ∀ semi-join. */
    public static PairwiseUniversalNode pairwiseUniversal(RelNode left, RelNode right,
                                                          Predicate condition) {
        return new PairwiseUniversalNode(left, right, condition);
    }

    /** AS-OF join, outer, no tolerance, default tie-break. */
    public static AsOfJoinNode asOfJoin(RelNode left, RelNode right, Predicate condition) {
        return new AsOfJoinNode(left, right, condition);
    }

    /** AS-OF join, fully specified. */
    public static AsOfJoinNode asOfJoin(RelNode left, RelNode right, Predicate condition,
                                        Optional<Operand> tolerance, boolean inner, TieBreak tieBreak) {
        return new AsOfJoinNode(left, right, condition, tolerance, inner, tieBreak,
                SourceLocation.UNKNOWN);
    }

    /** Interval join over an Allen relation between two {@code [start, end]} column pairs. */
    public static IntervalJoinNode intervalJoin(RelNode left, RelNode right, AllenRelation relation,
                                                String leftStart, String leftEnd,
                                                String rightStart, String rightEnd) {
        return new IntervalJoinNode(left, right, relation, leftStart, leftEnd, rightStart, rightEnd);
    }

    /** {@code LATERAL} — a TVF call evaluated per left row. */
    public static LateralJoinNode lateral(RelNode left, String functionName, Operand... arguments) {
        return new LateralJoinNode(left, functionName, List.of(arguments));
    }

    // -------------------------------------------------------------------------
    // Set operators
    // -------------------------------------------------------------------------

    /** × — Cartesian product. */
    public static ProductNode product(RelNode left, RelNode right) {
        return new ProductNode(left, right);
    }

    /** ∪ — set union. */
    public static UnionNode union(RelNode left, RelNode right) {
        return new UnionNode(left, right);
    }

    /** ⊎ — bag union, keeping duplicates. */
    public static UnionAllNode unionAll(RelNode left, RelNode right) {
        return new UnionAllNode(left, right);
    }

    /** ⊔ — outer union over differing headings. */
    public static OuterUnionNode outerUnion(RelNode left, RelNode right) {
        return new OuterUnionNode(left, right);
    }

    /** − — difference. */
    public static DifferenceNode difference(RelNode left, RelNode right) {
        return new DifferenceNode(left, right);
    }

    /** ∩ — intersection. */
    public static IntersectionNode intersection(RelNode left, RelNode right) {
        return new IntersectionNode(left, right);
    }

    /** ÷ — division. */
    public static DivisionNode division(RelNode left, RelNode right) {
        return new DivisionNode(left, right);
    }

    /** ∆ — symmetric difference. */
    public static SymmetricDifferenceNode symmetricDifference(RelNode left, RelNode right) {
        return new SymmetricDifferenceNode(left, right);
    }

    /** ∘ — relational composition. */
    public static CompositionNode composition(RelNode left, RelNode right) {
        return new CompositionNode(left, right);
    }

    // -------------------------------------------------------------------------
    // Recursion and graph operators
    // -------------------------------------------------------------------------

    /** Transitive closure {@code R⁺}. */
    public static ClosureNode closure(String fromColumn, String toColumn, RelNode input) {
        return new ClosureNode(input, fromColumn, toColumn);
    }

    /** Closure — reflexive-transitive {@code R*} when {@code reflexive}. */
    public static ClosureNode closure(String fromColumn, String toColumn, boolean reflexive,
                                      RelNode input) {
        return new ClosureNode(input, fromColumn, toColumn, reflexive);
    }

    /** Closure reading its two columns as an undirected edge ({@code a ↔ b}). */
    public static ClosureNode closure(String fromColumn, String toColumn, boolean undirected,
                                      boolean reflexive, RelNode input) {
        return new ClosureNode(input, fromColumn, toColumn, undirected, reflexive,
                Optional.empty(), Optional.empty(), SourceLocation.UNKNOWN);
    }

    /** Closure with the endpoint bounds {@code CLOSURE-001} pushes down. */
    public static ClosureNode closure(String fromColumn, String toColumn, boolean undirected,
                                      boolean reflexive, Optional<Operand> boundSource,
                                      Optional<Operand> boundTarget, RelNode input) {
        return new ClosureNode(input, fromColumn, toColumn, undirected, reflexive,
                boundSource, boundTarget, SourceLocation.UNKNOWN);
    }

    /** Connected components, labelled into {@code labelColumn}. */
    public static ClusterNode cluster(String fromColumn, String toColumn, String labelColumn,
                                      RelNode input) {
        return new ClusterNode(input, fromColumn, toColumn, labelColumn);
    }

    /** Bounded-hop paths, with the hop count in {@code depthColumn}. */
    public static PathNode path(String fromColumn, String toColumn, int minHops, int maxHops,
                                String depthColumn, RelNode input) {
        return new PathNode(input, fromColumn, toColumn, minHops, maxHops, depthColumn);
    }

    /** Bounded-hop paths over an undirected edge relation ({@code a ↔ b}). */
    public static PathNode path(String fromColumn, String toColumn, boolean undirected,
                                int minHops, int maxHops, String depthColumn, RelNode input) {
        return new PathNode(input, fromColumn, toColumn, undirected, minHops, maxHops,
                depthColumn, SourceLocation.UNKNOWN);
    }

    /** Path with the endpoint bounds {@code PATH-001} pushes down. */
    public static PathNode path(String fromColumn, String toColumn, boolean undirected,
                                int minHops, int maxHops, String depthColumn,
                                Optional<Operand> boundSource, Optional<Operand> boundTarget,
                                RelNode input) {
        return new PathNode(input, fromColumn, toColumn, undirected, minHops, maxHops,
                depthColumn, boundSource, boundTarget, SourceLocation.UNKNOWN);
    }

    /** Weighted shortest/longest path, appending {@code pathColumn}. */
    public static TraceNode trace(String from, String to, String weight, ObjectiveSense sense,
                                  String path, RelNode input) {
        return new TraceNode(input, from, to, weight, sense, path);
    }

    /** Trace over an undirected weighted edge relation ({@code a ↔ b}). */
    public static TraceNode trace(String from, String to, boolean undirected, String weight,
                                  ObjectiveSense sense, String path, RelNode input) {
        return new TraceNode(input, from, to, undirected, weight, sense, path,
                Optional.empty(), Optional.empty(), SourceLocation.UNKNOWN);
    }

    /** Trace with the endpoint bounds {@code TRACE-001} pushes down. */
    public static TraceNode trace(String from, String to, boolean undirected, String weight,
                                  ObjectiveSense sense, String path,
                                  Optional<Operand> boundSource,
                                  Optional<Operand> boundTarget, RelNode input) {
        return new TraceNode(input, from, to, undirected, weight, sense, path,
                boundSource, boundTarget, SourceLocation.UNKNOWN);
    }

    /** {@code FIX} — a monotone least fixpoint over {@code base} and {@code step}. */
    public static FixpointNode fixpoint(String name, RelNode base, RelNode step) {
        return new FixpointNode(name, base, step);
    }

    // -------------------------------------------------------------------------
    // Analytics, sampling, solving and generation
    // -------------------------------------------------------------------------

    /** {@code WINDOW}/{@code ROLLING} — one window function into {@code outputColumn}. */
    public static WindowNode window(WindowFunction function, List<String> partitionKeys,
                                    List<SortSpecification> sortSpecs, WindowFrame frame,
                                    String outputColumn, RelNode input) {
        return new WindowNode(function, partitionKeys, sortSpecs, frame, outputColumn, input);
    }

    /** {@code TOP} — the first {@code count} rows of each partition. */
    public static TopKNode topK(List<String> groupingAttributes, List<SortSpecification> sortSpecs,
                                long count, RelNode input) {
        return new TopKNode(groupingAttributes, sortSpecs, count, input);
    }

    /** {@code TOP} with an offset. */
    public static TopKNode topK(List<String> groupingAttributes, List<SortSpecification> sortSpecs,
                                Optional<Long> offset, long count, RelNode input) {
        return new TopKNode(groupingAttributes, sortSpecs, offset, count, input,
                SourceLocation.UNKNOWN);
    }

    /** {@code SESSIONIZE} — gap-based sessions, unpartitioned. */
    public static SessionizeNode sessionize(String orderColumn, Operand threshold,
                                            String sessionColumn, RelNode input) {
        return new SessionizeNode(input, orderColumn, threshold, List.of(), sessionColumn);
    }

    /** {@code SESSIONIZE} — gap-based sessions within each partition. */
    public static SessionizeNode sessionize(String orderColumn, Operand threshold,
                                            List<String> partitionKeys, String sessionColumn,
                                            RelNode input) {
        return new SessionizeNode(input, orderColumn, threshold, partitionKeys, sessionColumn);
    }

    /** {@code DOWNSAMPLE} — consolidate into time buckets. */
    public static DownsampleNode downsample(String tsCol, String interval,
                                            ConsolidationFunction fn, RelNode input) {
        return new DownsampleNode(tsCol, interval, fn, List.of(), OptionalLong.empty(), input);
    }

    /** {@code DOWNSAMPLE} within each group. */
    public static DownsampleNode downsample(String tsCol, String interval,
                                            ConsolidationFunction fn, List<String> keys, RelNode input) {
        return new DownsampleNode(tsCol, interval, fn, keys, OptionalLong.empty(), input);
    }

    /** {@code DOWNSAMPLE} with a target row budget. */
    public static DownsampleNode downsample(String tsCol, String interval,
                                            ConsolidationFunction fn, List<String> keys,
                                            long maxRows, RelNode input) {
        return new DownsampleNode(tsCol, interval, fn, keys, OptionalLong.of(maxRows), input);
    }

    /** {@code DOWNSAMPLE} with the row budget already in the form the node holds. */
    public static DownsampleNode downsample(String tsCol, String interval,
                                            ConsolidationFunction fn, List<String> keys,
                                            OptionalLong maxRows, RelNode input) {
        return new DownsampleNode(tsCol, interval, fn, keys, maxRows, input);
    }

    /** {@code PIVOT} — spread {@code valueColumn} across the values of {@code keyColumn}. */
    public static PivotNode pivot(String valueColumn, String keyColumn, List<String> groupKeys,
                                  RelNode input) {
        return new PivotNode(valueColumn, keyColumn, groupKeys, input);
    }

    /** {@code UNPIVOT} — fold {@code columns} into a name/value pair. */
    public static UnpivotNode unpivot(List<String> columns, String nameColumn, String valueColumn,
                                      RelNode input) {
        return new UnpivotNode(columns, nameColumn, valueColumn, input);
    }

    /** {@code TREE} — fold an adjacency list into a forest of nested documents. */
    public static TreeNode tree(String keyColumn, String parentColumn,
                                String childrenColumn, RelNode input) {
        return new TreeNode(input, keyColumn, parentColumn, List.of(), childrenColumn);
    }

    /** {@code TREE} with sibling ordering. */
    public static TreeNode tree(String keyColumn, String parentColumn,
                                List<SortSpecification> orderSpecs, String childrenColumn,
                                RelNode input) {
        return new TreeNode(input, keyColumn, parentColumn, orderSpecs, childrenColumn);
    }

    /** {@code SAMPLE} — Bernoulli sampling, unseeded and therefore volatile. */
    public static SampleNode sample(double probability, RelNode input) {
        return new SampleNode(probability, input);
    }

    /** {@code SAMPLE} with a seed, which makes it reproducible. */
    public static SampleNode sample(double probability, Optional<Long> seed, RelNode input) {
        return new SampleNode(probability, seed, input, SourceLocation.UNKNOWN);
    }

    /** {@code SAMPLE n} — reservoir sampling of a fixed row count. */
    public static ReservoirSampleNode reservoirSample(long count, RelNode input) {
        return new ReservoirSampleNode(count, input);
    }

    /** {@code SAMPLE n} with a seed. */
    public static ReservoirSampleNode reservoirSample(long count, Optional<Long> seed, RelNode input) {
        return new ReservoirSampleNode(count, seed, input, SourceLocation.UNKNOWN);
    }

    /** {@code SOLVE} — invert an equation for its single unknown. */
    public static SolveNode solve(Operand left, Operand right, RelNode input) {
        return new SolveNode(left, right, input);
    }

    /** {@code OPTIMIZE} — subset selection (MIP mode). */
    public static OptimizeNode optimize(ObjectiveSense sense, Operand objective,
                                        List<OptimizeConstraint> constraints,
                                        List<String> groupingKeys, RelNode input) {
        return new OptimizeNode(sense, objective, constraints, groupingKeys, input);
    }

    /** {@code OPTIMIZE} with a continuous-allocation spec (LP mode). */
    public static OptimizeNode optimize(ObjectiveSense sense, Operand objective,
                                        List<OptimizeConstraint> constraints,
                                        List<String> groupingKeys,
                                        Optional<AllocationSpec> allocation, RelNode input) {
        return new OptimizeNode(sense, objective, constraints, groupingKeys, allocation, input,
                SourceLocation.UNKNOWN);
    }

    /** {@code COVER t} — a t-way covering suite. */
    public static CoverNode cover(int strength, RelNode input) {
        return new CoverNode(strength, input);
    }

    /** {@code COVER t EXACT} when {@code exact}, which needs an installed solver. */
    public static CoverNode cover(int strength, boolean exact, RelNode input) {
        return new CoverNode(strength, exact, input);
    }
}

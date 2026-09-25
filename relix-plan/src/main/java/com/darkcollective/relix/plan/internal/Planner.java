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
package com.darkcollective.relix.plan.internal;

import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.plan.PlanEstimates;
import com.darkcollective.relix.plan.TraceAlgorithm;
import com.darkcollective.relix.ast.AggregationNode;
import com.darkcollective.relix.ast.internal.AstEquivalence;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.AsOfJoinNode;
import com.darkcollective.relix.ast.AntiJoinNode;
import com.darkcollective.relix.ast.IntervalJoinNode;
import com.darkcollective.relix.ast.PairwiseUniversalNode;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.CompositionNode;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.LateralJoinNode;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.SortDirection;
import com.darkcollective.relix.ast.SortSpecification;
import com.darkcollective.relix.ast.DifferenceNode;
import com.darkcollective.relix.ast.DistinctNode;
import com.darkcollective.relix.ast.DivisionNode;
import com.darkcollective.relix.ast.FullOuterJoinNode;
import com.darkcollective.relix.ast.IntersectionNode;
import com.darkcollective.relix.ast.LeftOuterJoinNode;
import com.darkcollective.relix.ast.LimitNode;
import com.darkcollective.relix.ast.NaturalJoinNode;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProductNode;
import com.darkcollective.relix.ast.internal.Qualifiers;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationFunctionCall;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.RightOuterJoinNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SemiJoinNode;
import com.darkcollective.relix.ast.OptimizeNode;
import com.darkcollective.relix.ast.OuterUnionNode;
import com.darkcollective.relix.ast.ReservoirSampleNode;
import com.darkcollective.relix.ast.SampleNode;
import com.darkcollective.relix.ast.SolveNode;
import com.darkcollective.relix.ast.SortNode;
import com.darkcollective.relix.ast.SymmetricDifferenceNode;
import com.darkcollective.relix.ast.TopKNode;
import com.darkcollective.relix.ast.ThetaJoinNode;
import com.darkcollective.relix.ast.UnionAllNode;
import com.darkcollective.relix.ast.UnionNode;
import com.darkcollective.relix.ast.UniversalNode;
import com.darkcollective.relix.ast.UnnestNode;
import com.darkcollective.relix.ast.ClosureNode;
import com.darkcollective.relix.ast.ClusterNode;
import com.darkcollective.relix.ast.PathNode;
import com.darkcollective.relix.ast.SessionizeNode;
import com.darkcollective.relix.ast.TreeNode;
import com.darkcollective.relix.ast.WhyNode;
import com.darkcollective.relix.ast.WindowNode;
import com.darkcollective.relix.ast.ObjectiveSense;
import com.darkcollective.relix.ast.TraceNode;
import com.darkcollective.relix.ast.EmptyRelationNode;
import com.darkcollective.relix.ast.TruthRelationNode;
import com.darkcollective.relix.ast.CoverNode;
import com.darkcollective.relix.ast.DownsampleNode;
import com.darkcollective.relix.ast.FixpointNode;
import com.darkcollective.relix.ast.RecursiveRefNode;
import com.darkcollective.relix.ast.UnpivotNode;
import com.darkcollective.relix.ast.PivotNode;
import com.darkcollective.relix.cost.Boundedness;
import com.darkcollective.relix.cost.BoundednessChecker;
import com.darkcollective.relix.solver.SolverCatalog;
import com.darkcollective.relix.cost.BoundednessSource;
import com.darkcollective.relix.cost.CostEstimator;
import com.darkcollective.relix.ast.Ordering;
import com.darkcollective.relix.cost.PropertyDeriver;
import com.darkcollective.relix.cost.ObservedCardinalities;
import com.darkcollective.relix.cost.StatisticsSource;
import com.darkcollective.relix.events.QueryEvent;
import com.darkcollective.relix.events.QueryEventListener;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.function.FunctionContext;
import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.source.HttpSourceConfig;

import com.darkcollective.relix.plan.PhysicalNode.BuildSide;
import com.darkcollective.relix.plan.PhysicalNode.JoinAlgorithm;
import com.darkcollective.relix.plan.PhysicalNode.JoinKeys;
import com.darkcollective.relix.plan.PhysicalNode.JoinKind;
import com.darkcollective.relix.plan.PhysicalNode.SetKind;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.semantic.internal.RelationDeterminism;
import com.darkcollective.relix.semantic.internal.SchemaInference;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.function.RelationFunctionSymbol;
import com.darkcollective.relix.symbol.RelationStatistics;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.ShadowPolicy;
import com.darkcollective.relix.symbol.relation.InlineRelationSymbol;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.relation.RelationSymbol;
import com.darkcollective.relix.symbol.relation.SystemRelationSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;

/**
 * Translates an optimised logical {@link RelNode} tree into an executable
 * {@link PhysicalNode} plan, fixing every physical decision up front:
 *
 * <ul>
 *   <li><b>Join algorithm</b> — {@link JoinAlgorithm#MERGE} when at least one input
 *       already delivers an ordering satisfying the join keys (the other gets a
 *       {@link PhysicalNode.Sort} enforcer inserted); {@link JoinAlgorithm#HASH}
 *       when an equi-join key exists but neither input is sorted; otherwise
 *       {@link JoinAlgorithm#NESTED_LOOP}.  MERGE is eligible for INNER, NATURAL,
 *       SEMI, and ANTI joins.</li>
 *   <li><b>Build side</b> — for HASH/nested-loop: the cheaper input is built for
 *       symmetric joins (inner, natural, full outer, product); the non-preserved
 *       side is built for asymmetric joins.  For MERGE joins the field is
 *       {@link PhysicalNode.BuildSide#RIGHT} by convention (not used by the
 *       merge executor).</li>
 *   <li><b>Schemas</b> — each physical node's output schema is resolved from the
 *       supplied {@link SchemaAnnotations} (or the relation's declared schema for a
 *       scan), so the executor never re-infers or looks schemas up by identity.</li>
 * </ul>
 *
 * <p>Named views ({@link QueryRelationSymbol}) are <em>inlined</em>: a reference to
 * a view is planned by planning the view's body, so views never appear as plan
 * nodes.
 *
 * <p>The supplied {@link SchemaAnnotations} must cover every node the planner
 * visits — both the input tree and the bodies of any inlined views.  A model's
 * {@link com.darkcollective.relix.semantic.SemanticModel#nodeSchemas()} satisfies
 * this for an un-rewritten tree; a rewritten tree should be annotated via
 * {@link com.darkcollective.relix.semantic.internal.SchemaInference} first.
 *
 * <h2>Thread safety</h2>
 * <p>A {@code Planner} is bound to one symbol table and annotation set; it is
 * stateless beyond those and may be reused across {@link #plan} calls.
 */
public final class Planner {

    private final SymbolTable symbols;
    // Two readers: re-annotating an inlined table-valued-function body (a fresh tree,
    // whose function calls must type as they did during analysis), and every pushdown
    // renderer, which asks a function how it is spelled for the backend rather than
    // holding a name table of its own (ADR-0026 S6).
    private final FunctionCatalog functions;
    // Grows monotonically as table-valued-function bodies are inlined and annotated
    // on demand; an instance plans a single root, so widening the annotation set is safe.
    private SchemaAnnotations schemas;
    private final Set<String> activeInlines = new HashSet<>();  // recursive-TVF guard
    private final CostEstimator costEstimator;
    private final PlanEstimates estimates = new PlanEstimates();
    private final StatisticsSource statistics;   // candidate keys for index-backed merge
    private final List<PushdownRenderer> pushdowns;   // empty when pushdown is disabled; tried in turn
    private final QueryEventListener listener;

    /** Canonical name → source declaration, for the leaves whose scan can be narrowed. */
    private final Map<String, SourceDeclaration> sources;
    private final BoundednessSource boundedness; // per-leaf boundedness for the build-side rule (ADR-0008)
    private boolean boundednessChecked;          // the root-tree materialisation-safety check runs once
    private int nextSpoolId = 1;                 // spool ids are unique within one planned root
    // digest → the sharing it is worth, for the sub-expressions worth sharing;
    // null until the first plan() call computes it from that call's root.
    private Map<String, SharedSubexpressions.Sharing> sharedSites;
    // digest → the spool standing in for it, so the second site reads the first's plan.
    private final Map<String, PhysicalNode.Spool> spoolsByDigest = new HashMap<>();
    // Depth of table-valued-function bodies currently being planned. A LATERAL body is
    // planned once per outer row (through bodyBuilder), so a spool minted inside one
    // would allocate a fresh id per row and the executor's buffer would grow with the
    // outer relation. LateralMemo already memoizes at that level.
    private int lateralBodyDepth;
    // Whether a mathematical-programming solver is installed. Only OPTIMIZE and
    // COVER EXACT need one, and both are worth failing before the inputs are read.
    private SolverCatalog solvers = SolverCatalog.installed();

    /**
     * Creates a planner with no relation statistics; the cost model falls back to
     * its tier-based heuristics for every build-side decision.
     *
     * @param symbols the symbol table used to resolve relations (and inline views); must not be null
     * @param schemas per-node schemas covering the tree(s) to be planned; must not be null
     */
    public Planner(SymbolTable symbols, SchemaAnnotations schemas) {
        this(symbols, schemas, Map.of());
    }

    /**
     * Creates a planner backed by per-relation statistics, used to choose hash-join
     * build sides by estimated row count (smaller side built) when both inputs'
     * cardinalities are known, falling back to the tier model otherwise.
     *
     * @param symbols    the symbol table used to resolve relations (and inline views); must not be null
     * @param schemas    per-node schemas covering the tree(s) to be planned; must not be null
     * @param statistics canonical relation name → statistics (as in
     *                   {@link com.darkcollective.relix.semantic.SemanticModel#statistics()}); must
     *                   not be null
     */
    public Planner(SymbolTable symbols, SchemaAnnotations schemas,
                   Map<String, RelationStatistics> statistics) {
        this(symbols, schemas, statistics, Map.of(), Map.of());
    }

    /**
     * Creates a planner with statistics and SQL pushdown enabled.  Sub-trees over a
     * single connection's tables are translated to a {@link PhysicalNode.PushedScan}
     * and run in the database; everything else is planned in-engine as usual.
     *
     * @param symbols     the symbol table used to resolve relations (and inline views); must not be null
     * @param schemas     per-node schemas covering the tree(s) to be planned; must not be null
     * @param statistics  canonical relation name → statistics; must not be null
     * @param sources     canonical name → source declaration (as in
     *                    {@link com.darkcollective.relix.semantic.SemanticModel#sources()}); must
     *                    not be null
     * @param connections canonical name → connection declaration (as in
     *                    {@link com.darkcollective.relix.semantic.SemanticModel#connections()});
     *                    must not be null
     */
    public Planner(SymbolTable symbols, SchemaAnnotations schemas,
                   Map<String, RelationStatistics> statistics,
                   Map<String, SourceDeclaration> sources,
                   Map<String, ConnectionDeclaration> connections) {
        this(symbols, schemas, statistics, sources, connections, QueryEventListener.NONE);
    }

    /**
     * As {@link #Planner(SymbolTable, SchemaAnnotations, Map, Map, Map)}, but also
     * emits a {@link QueryEvent} to {@code listener} for each physical decision —
     * a join's algorithm/build side and each sub-tree pushed down as SQL.
     *
     * @param symbols     the symbol table; must not be null
     * @param schemas     per-node schemas covering the tree(s); must not be null
     * @param statistics  canonical relation name → statistics; must not be null
     * @param sources     canonical name → source declaration; must not be null
     * @param connections canonical name → connection declaration; must not be null
     * @param listener    notified on each physical decision; must not be null
     *                    (use {@link QueryEventListener#NONE} for no observation)
     */
    public Planner(SymbolTable symbols, SchemaAnnotations schemas,
                   Map<String, RelationStatistics> statistics,
                   Map<String, SourceDeclaration> sources,
                   Map<String, ConnectionDeclaration> connections,
                   QueryEventListener listener) {
        this(symbols, schemas, statistics, sources, connections, listener, BoundednessSource.ALL_BOUNDED);
    }

    /**
     * As {@link #Planner(SymbolTable, SchemaAnnotations, Map, Map, Map, QueryEventListener)},
     * plus a per-leaf {@link BoundednessSource} that drives the join build-side rule: an
     * {@linkplain Boundedness#UNBOUNDED unbounded} input must be the
     * probe side, never the hash build side; both sides unbounded is a plan-time
     * {@link BoundednessException}.
     *
     * @param boundedness the per-leaf boundedness lookup; must not be null
     *                    (use {@link BoundednessSource#ALL_BOUNDED} when every leaf is finite)
     */
    public Planner(SymbolTable symbols, SchemaAnnotations schemas,
                   Map<String, RelationStatistics> statistics,
                   Map<String, SourceDeclaration> sources,
                   Map<String, ConnectionDeclaration> connections,
                   QueryEventListener listener,
                   BoundednessSource boundedness) {
        this(symbols, schemas, statistics, sources, connections, listener, boundedness,
                FunctionCatalog.empty());
    }

    /**
     * As {@link #Planner(SymbolTable, SchemaAnnotations, Map, Map, Map, QueryEventListener,
     * BoundednessSource)}, plus the function catalogue the tree was analysed against.
     *
     * <p>It is read in two places.  Inlining a table-valued function call substitutes the
     * call's arguments into the function's body, and that freshly-built body has to be
     * re-annotated before it can be planned; passing the analysis catalogue
     * ({@link com.darkcollective.relix.semantic.SemanticModel#functions()}) is what makes
     * a call inside such a body type exactly as it did during analysis.  Pushdown reads
     * it too: a function's backend spelling comes from the function itself, so a call
     * folds into a pushed query only when the catalogue holds it.
     *
     * <p>The other constructors supply an empty catalogue.  Under one, a call in an
     * inlined body that the symbol table does not already hold types as {@code ANY}, and
     * no function folds into a pushed query — a slower plan, never a wrong one.  Supply
     * the catalogue the tree was analysed against and both follow.
     *
     * @param functions the catalogue the tree was analysed against; must not be null
     */
    public Planner(SymbolTable symbols, SchemaAnnotations schemas,
                   Map<String, RelationStatistics> statistics,
                   Map<String, SourceDeclaration> sources,
                   Map<String, ConnectionDeclaration> connections,
                   QueryEventListener listener,
                   BoundednessSource boundedness,
                   FunctionCatalog functions) {
        this.symbols = Objects.requireNonNull(symbols, "symbols");
        this.functions = Objects.requireNonNull(functions, "functions");
        this.schemas = Objects.requireNonNull(schemas, "schemas");
        Objects.requireNonNull(statistics, "statistics");
        this.sources = Map.copyOf(Objects.requireNonNull(sources, "sources"));
        Objects.requireNonNull(connections, "connections");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.boundedness = Objects.requireNonNull(boundedness, "boundedness");
        StatisticsSource source = StatisticsSource.of(symbols, statistics);
        this.statistics = source;
        this.costEstimator = new CostEstimator(symbols, source);
        // One renderer per pushdown-capable backend, consulted in turn (ADR-0011):
        // SQL for jdbc connections, an aggregation pipeline for mongodb connections.
        List<PushdownRenderer> renderers = new ArrayList<>();
        Map<String, ConnectionDeclaration> jdbcConnections = connectionsOfType(connections, "jdbc");
        if (!jdbcConnections.isEmpty()) {
            renderers.add(new SqlPushdownPlanner(schemas, sources, jdbcConnections, functions));
        }
        Map<String, ConnectionDeclaration> mongoConnections = connectionsOfType(connections, "mongodb");
        if (!mongoConnections.isEmpty()) {
            renderers.add(new MongoPushdownPlanner(schemas, sources, mongoConnections, functions));
        }
        this.pushdowns = List.copyOf(renderers);
    }

    /**
     * Plans against an explicit set of solvers rather than the installed ones.
     *
     * <p>The seam a test or an embedder uses to plan as though a solver were present
     * or absent; production planning reads {@link SolverCatalog#installed()}.
     *
     * @param catalog the solvers to plan against; must not be null
     * @return this planner, for chaining
     */
    /**
     * Lets this planner prefer a row count a previous run actually produced over the one
     * the cost model would compute for the same expression.
     *
     * <p>A wither rather than a constructor parameter for the reason
     * {@link #withSolvers} is one: this class already has six constructors, and the
     * seventh argument nobody passes is how a constructor list becomes unreadable.
     *
     * @param observed the recorded counts; must not be null
     * @return this planner, for chaining
     */
    public Planner withObservedCardinalities(ObservedCardinalities observed) {
        costEstimator.withObserved(observed);
        return this;
    }

    /**
     * Lets this planner evaluate a call that is constant for the run — {@code NOW()} —
     * and push the resulting value, rather than declining the fold because the backend
     * would answer from its own clock.
     *
     * <p>The context must be the one the execution will use, because the substituted
     * value has to equal the one the unfolded half of the same query computes. That is
     * why it is passed rather than made here: {@code ExecutionContext} pins the run's
     * instant, and this is that instant travelling to the renderers.
     *
     * @param context the run's ambient state; must not be null
     * @return this planner, for chaining
     */
    public Planner withFunctionContext(FunctionContext context) {
        Objects.requireNonNull(context, "context");
        pushdowns.forEach(renderer -> renderer.useFunctionContext(context));
        return this;
    }

    public Planner withSolvers(SolverCatalog catalog) {
        this.solvers = Objects.requireNonNull(catalog, "catalog");
        return this;
    }

    /**
     * Refuses to plan an operator that states a mathematical program when nothing can
     * solve one. Checked here rather than in the executor so the query fails before
     * its inputs are read, and against the operator's own name so the message names
     * what the user wrote.
     */
    private void requireSolver(String operator) {
        if (solvers.isEmpty()) {
            throw new NoSolverInstalledException(operator);
        }
    }

    /** The subset of {@code connections} whose connector type is {@code type}. */
    private static Map<String, ConnectionDeclaration> connectionsOfType(
            Map<String, ConnectionDeclaration> connections, String type) {
        return connections.entrySet().stream()
                .filter(e -> e.getValue().connectorType().equals(type))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Tries each pushdown renderer in turn, returning the first that folds {@code node}. */
    private Optional<PhysicalNode.PushedScan> tryPush(RelNode node) {
        for (PushdownRenderer renderer : pushdowns) {
            Optional<PhysicalNode.PushedScan> pushed = renderer.tryPush(node);
            if (pushed.isPresent()) {
                return pushed;
            }
        }
        return Optional.empty();
    }

    /** Tries each pushdown renderer in turn for an order-delivering scan of {@code node}. */
    private Optional<PhysicalNode.PushedScan> tryPushOrdered(RelNode node, List<SortSpecification> keys) {
        for (PushdownRenderer renderer : pushdowns) {
            Optional<PhysicalNode.PushedScan> pushed = renderer.tryPushOrdered(node, keys);
            if (pushed.isPresent()) {
                return pushed;
            }
        }
        return Optional.empty();
    }

    /**
     * The estimated row counts recorded while planning, keyed by plan node.
     *
     * <p>Populated by {@link #plan(RelNode)} and empty before it runs.  A node the
     * planner built <em>inside</em> another arm rather than through {@code plan} — an
     * order-enforcing {@code Sort} wrapper, say — carries no estimate, which reads as
     * unknown; see {@link PlanEstimates} for why unknown is deliberately not zero.
     *
     * @return the estimates for the plan this instance produced; never null
     */
    public PlanEstimates estimates() {
        return estimates;
    }

    /**
     * Produces the physical plan for {@code node}.
     *
     * <p>Each node is costed as it is built, and the estimate is filed in
     * {@link #estimates()} rather than carried on the node — see {@link PlanEstimates}
     * for the reasoning. The estimate is taken from the <em>logical</em> node, which is
     * where the cost model's statistics apply; a pushed-down scan is therefore costed as
     * the sub-tree it replaced, which is the number a reader wants (how many rows come
     * back), not the number of tables it folded.
     *
     * @param node the logical root to plan; must not be null
     * @return the executable physical plan
     */
    public PhysicalNode plan(RelNode node) {
        if (sharedSites == null) {
            // The first call is the root; every later one is a child of it (or, at
            // execution time, a LATERAL body, where sharing is declined anyway).
            sharedSites = SharedSubexpressions.detect(node, this::shareable);
        }
        String digest = sharedSites.isEmpty() ? null : AstEquivalence.digest(node);
        if (digest != null) {
            PhysicalNode.Spool shared = spoolsByDigest.get(digest);
            if (shared != null) {
                return shared;   // this sub-expression is already planned; read that plan
            }
        }
        PhysicalNode physical = planNode(node);
        estimates.record(physical, costEstimator.estimateRows(node));
        SharedSubexpressions.Sharing sharing = digest == null ? null : sharedSites.get(digest);
        if (sharing == null) {
            return physical;
        }
        PhysicalNode shared = share(node, physical, sharing);
        if (shared instanceof PhysicalNode.Spool spool) {
            spoolsByDigest.put(digest, spool);
        }
        return shared;
    }

    private PhysicalNode planNode(RelNode node) {
        // Materialisation-safety check (ADR-0008/0009): a blocking operator over a
        // provably-unbounded input is a plan-time error. Run once, on the root (a
        // Planner instance plans a single root); λ already bounds, so this sees the
        // post-optimization tree's pushed bounds. A no-op until an unbounded generator
        // exists (every leaf is BOUNDED today).
        if (!boundednessChecked) {
            boundednessChecked = true;
            List<String> violations = BoundednessChecker.check(node, boundedness);
            if (!violations.isEmpty()) {
                throw new BoundednessException(String.join("; ", violations));
            }
        }
        Optional<PhysicalNode.PushedScan> pushed = tryPush(node);
        if (pushed.isPresent()) {
            listener.onEvent(QueryEvent.of(QueryEvent.Stage.PLAN, "PUSHDOWN",
                    "pushed to '" + pushed.get().connection() + "': " + pushed.get().nativeQuery()));
            return pushed.get();
        }
        PhysicalNode physical = switch (node) {
            case RelationNode r   -> planRelation(r);
            case RelationFunctionCall f -> planRelationFunctionCall(f);
            case TruthRelationNode t -> planTruthRelation(t);
            // ∅ — the sub-tree it replaced is never planned, which is the point of the
            // rule that produced it; the schema comes from the annotation, so the
            // heading it carries is not walked here either.
            case EmptyRelationNode e -> new PhysicalNode.Empty(schemaOf(e));

            case SelectionNode s  -> new PhysicalNode.Select(schemaOf(s), s.predicate(), plan(s.input()));
            case ProjectionNode p -> planProjection(p);
            case RenameNode r     -> new PhysicalNode.Rename(
                    schemaOf(r), r.relationName(), r.pairs(), plan(r.input()));
            case DistinctNode d   -> planDistinct(d);
            case UnnestNode u     -> new PhysicalNode.Unnest(
                    schemaOf(u), u.column(), u.outer(), u.ordinalityColumn(), plan(u.input()));
            case ClosureNode c    -> new PhysicalNode.Closure(
                    schemaOf(c), c.fromColumn(), c.toColumn(), c.undirected(), c.reflexive(),
                    c.boundSource(), c.boundTarget(), plan(c.input()));
            case ClusterNode c    -> new PhysicalNode.Cluster(
                    schemaOf(c), c.fromColumn(), c.toColumn(), c.labelColumn(), plan(c.input()));
            case PathNode p       -> new PhysicalNode.Path(
                    schemaOf(p), p.fromColumn(), p.toColumn(), p.undirected(),
                    p.minHops(), p.maxHops(), p.depthColumn(),
                    p.boundSource(), p.boundTarget(), plan(p.input()));
            case TraceNode t      -> new PhysicalNode.Trace(
                    schemaOf(t), t.fromColumn(), t.toColumn(), t.undirected(), t.weightColumn(),
                    t.sense(), t.pathColumn(), traceAlgorithm(t),
                    t.boundSource(), t.boundTarget(), plan(t.input()));
            case LimitNode l      -> new PhysicalNode.Limit(schemaOf(l), l.offset(), l.count(), plan(l.input()));
            case SortNode s       -> new PhysicalNode.Sort(schemaOf(s), s.sortSpecs(), plan(s.input()));
            case AggregationNode a -> planAggregate(a);
            case UniversalNode u   -> new PhysicalNode.Universal(
                    schemaOf(u), u.groupingAttributes(), u.predicate(), plan(u.input()));
            case SampleNode s      -> planSample(s);
            case ReservoirSampleNode s -> new PhysicalNode.ReservoirSample(
                    schemaOf(s), s.count(), s.seed(), plan(s.input()));
            case SolveNode s       -> new PhysicalNode.Solve(
                    schemaOf(s), s.left(), s.right(), plan(s.input()));
            case OptimizeNode o    -> {
                requireSolver("OPTIMIZE");
                yield new PhysicalNode.Optimize(
                        schemaOf(o), o.sense(), o.objective(), o.constraints(),
                        o.groupingKeys(), o.allocation(), plan(o.input()));
            }
            case TopKNode t        -> new PhysicalNode.TopK(
                    schemaOf(t), t.groupingAttributes(), t.sortSpecs(),
                    t.offset(), t.count(), plan(t.input()));
            case WindowNode w      -> new PhysicalNode.Window(
                    schemaOf(w), w.function(), w.partitionKeys(), w.sortSpecs(),
                    w.frame(), w.outputColumn(), plan(w.input()));
            case SessionizeNode s  -> new PhysicalNode.Sessionize(
                    schemaOf(s), s.orderColumn(), s.threshold(), s.partitionKeys(),
                    s.sessionColumn(), plan(s.input()));

            // Adjacency-to-forest nesting (TREE, ADR-0019): blocking, recursive ANY
            // output, never pushed (SqlPushdownPlanner / MongoPushdownPlanner hit their
            // default arm), so σ/π below it over a connection table still fold into the
            // source scan.
            case TreeNode t -> new PhysicalNode.Tree(
                    schemaOf(t), t.keyColumn(), t.parentColumn(), t.orderSpecs(),
                    t.childrenColumn(), plan(t.input()));

            // Lineage reification (WHY, ADR-0018): a self-terminating boundary that
            // reifies its subtree's lineage as a nested column. The input is carried as
            // the LOGICAL subtree (not planned/pushed) — the executor evaluates it via
            // the ProvenanceEvaluator path, and pushing it to a
            // source would lose the row-level lineage. Blocking; never pushed.
            case WhyNode w -> new PhysicalNode.Why(schemaOf(w), w.input());

            case NaturalJoinNode j    -> planNaturalJoin(j);
            case ThetaJoinNode j      -> planJoin(JoinKind.INNER,       j, j.left(), j.right(), j.condition());
            case LeftOuterJoinNode j  -> planJoin(JoinKind.LEFT_OUTER,  j, j.left(), j.right(), j.condition());
            case RightOuterJoinNode j -> planJoin(JoinKind.RIGHT_OUTER, j, j.left(), j.right(), j.condition());
            case FullOuterJoinNode j  -> planJoin(JoinKind.FULL_OUTER,  j, j.left(), j.right(), j.condition());
            case SemiJoinNode j            -> planJoin(JoinKind.SEMI,           j, j.left(), j.right(), j.condition());
            case AntiJoinNode j            -> planJoin(JoinKind.ANTI,           j, j.left(), j.right(), j.condition());
            case PairwiseUniversalNode j   -> planJoin(JoinKind.UNIVERSAL_SEMI, j, j.left(), j.right(), j.condition());
            case AsOfJoinNode j            -> planAsOfJoin(j);
            case IntervalJoinNode j        -> planIntervalJoin(j);
            case ProductNode p        -> planProduct(p);

            case UnionNode u        -> new PhysicalNode.SetOp(schemaOf(u), SetKind.UNION,      plan(u.left()), plan(u.right()));
            case UnionAllNode u     -> new PhysicalNode.SetOp(schemaOf(u), SetKind.UNION_ALL,  plan(u.left()), plan(u.right()));
            case OuterUnionNode u   -> new PhysicalNode.SetOp(schemaOf(u), SetKind.OUTER_UNION, plan(u.left()), plan(u.right()));
            case DifferenceNode d   -> new PhysicalNode.SetOp(schemaOf(d), SetKind.DIFFERENCE, plan(d.left()), plan(d.right()));
            case IntersectionNode i -> new PhysicalNode.SetOp(schemaOf(i), SetKind.INTERSECT,  plan(i.left()), plan(i.right()));
            case DivisionNode d     -> new PhysicalNode.Division(schemaOf(d), plan(d.left()), plan(d.right()));

            case SymmetricDifferenceNode s -> planSymmetricDifference(s);
            case CompositionNode c         -> planComposition(c);

            // General recursion (FIX) — semi-naïve fixpoint engine.
            // Base and step are planned independently; the RecursiveRef leaf streams
            // the current delta at execution time via the executor's recursion map.
            // No pushdown by construction: pushdown renderers hit their default arm
            // for both node types, so σ/π below the FIX over a connection table still
            // fold into the source scan, but the binder itself never pushes.
            case FixpointNode fx -> new PhysicalNode.Fixpoint(
                    schemaOf(fx), fx.name(), plan(fx.base()), plan(fx.step()));
            case RecursiveRefNode r -> new PhysicalNode.RecursiveRef(schemaOf(r), r.name());

            // Covering reduction (COVER) — greedy in-engine executor (ADR-0012, slice 3).
            // Never pushed: both SqlPushdownPlanner and MongoPushdownPlanner hit their
            // default fallback arms, so σ/π below the COVER still fold into the source.
            // Constructive mode (ADR-0012 Decision 3 Option B): the planner
            // recognises Cover(t, σ?(Product(leaves…))) and emits ConstructiveCover so the
            // executor never materialises the full Cartesian product.
            case CoverNode c -> {
                // Only the exact mode is a MIP; greedy COVER is a constructive
                // algorithm and runs in a build with no solver at all.
                if (c.exact()) {
                    requireSolver("COVER EXACT");
                }
                var constructive = tryConstructiveCover(c);
                yield constructive.isPresent() ? constructive.get()
                        : new PhysicalNode.Cover(schemaOf(c), c.strength(), c.exact(), plan(c.input()));
            }

            case DownsampleNode d -> new PhysicalNode.Downsample(
                    schemaOf(d), d.timestampColumn(),
                    DownsampleNode.parseIntervalSeconds(d.interval()),
                    d.function(), d.groupingKeys(), d.maxRows(), plan(d.input()));

            // UNPIVOT (columns-to-rows): streaming fold of listed columns into rows.
            // Never pushed (SqlPushdownPlanner / MongoPushdownPlanner hit their default arm).
            case UnpivotNode u -> new PhysicalNode.Unpivot(
                    schemaOf(u), u.columns(), u.nameColumn(), u.valueColumn(), plan(u.input()));

            // PIVOT (rows-to-columns): dynamic rotation; blocking, open output schema.
            // Never pushed (SqlPushdownPlanner / MongoPushdownPlanner hit their default arm).
            case PivotNode pv -> new PhysicalNode.Pivot(
                    schemaOf(pv), pv.valueColumn(), pv.keyColumn(), pv.groupKeys(), plan(pv.input()));

            // Lateral / correlated TVF join: per left row, inline the function body
            // with per-row argument values and plan the result.  The bodyBuilder lambda
            // captures the planner context; execution happens once per left row.
            // Never pushed (SqlPushdownPlanner / MongoPushdownPlanner hit their default arm).
            case LateralJoinNode n -> planLateralJoin(n);
        };
        if (physical instanceof PhysicalNode.Join join) {
            listener.onEvent(QueryEvent.of(QueryEvent.Stage.PLAN, "JOIN",
                    join.kind() + " join: " + join.algorithm() + ", build=" + join.buildSide()));
            emitNestedLoopWarning(join);
        }
        if (physical instanceof PhysicalNode.Trace tr
                && tr.algorithm() == TraceAlgorithm.DIJKSTRA) {
            listener.onEvent(QueryEvent.of(QueryEvent.Stage.PLAN, "TRACE",
                    "single-pair Dijkstra — bounded source+target, MINIMIZE"
                    + " (assumes non-negative weights; falls back to relaxation otherwise)"));
        }
        return physical;
    }

    /**
     * Emits a {@code JOIN-NESTED-LOOP} event when a join falls back to comparing every
     * left row against every right row.
     *
     * <p>The quadratic path used to be invisible: {@code JoinExecutor} guards its hash
     * path with a {@code hashable(join)} test and, when it fails, walks the whole right
     * input per left row.  That guard is exactly {@code algorithm() == HASH}, and MERGE is
     * dispatched before it — so the executor nested-loops precisely when <em>this</em>
     * planner chose {@link JoinAlgorithm#NESTED_LOOP}, which it does when
     * {@link JoinPlanning#extractKeys} found no equi-join key in the condition.  Plan and
     * execution cannot disagree, so surfacing the planner's own choice is the whole fix;
     * what was missing was the <em>why</em>, which only this side knows.
     *
     * <p>A {@link JoinKind#PRODUCT} is excluded: {@code ×} has no condition to extract a
     * key from, so being quadratic is what the user asked for, not a degradation.
     */
    private void emitNestedLoopWarning(PhysicalNode.Join join) {
        if (join.algorithm() != JoinAlgorithm.NESTED_LOOP || join.kind() == JoinKind.PRODUCT) {
            return;
        }
        listener.onEvent(QueryEvent.of(QueryEvent.Stage.PLAN, "JOIN-NESTED-LOOP",
                join.kind() + " join has no usable equi-join key, so every left row is"
                + " compared against every right row (O(n×m)); a hash or merge join needs"
                + " at least one `left.col = right.col` conjunct in the condition"));
    }

    /**
     * Chooses the physical algorithm for a {@link TraceNode} (ADR-0020 slice 2):
     * single-pair {@link TraceAlgorithm#DIJKSTRA} when both endpoints are bound and the
     * sense is {@code MINIMIZE}, else the default {@link TraceAlgorithm#RELAXATION}. The
     * non-negative-weight precondition that Dijkstra also requires is verified at
     * execution time (the executor falls back to relaxation otherwise), since edge
     * weights are data not known at plan time.
     */
    private static TraceAlgorithm traceAlgorithm(TraceNode t) {
        return (t.boundSource().isPresent() && t.boundTarget().isPresent()
                && t.sense() == ObjectiveSense.MINIMIZE)
                ? TraceAlgorithm.DIJKSTRA : TraceAlgorithm.RELAXATION;
    }

    // ── leaves & views ─────────────────────────────────────────────────────

    /**
     * Plans a nullary truth-relation literal ({@code UNIT} / {@code EMPTY}) as an
     * ordinary inline scan over the empty (zero-column) heading: {@code UNIT}'s
     * single row is the empty tuple — a row map with no entries — and
     * {@code EMPTY} has no rows at all.  No new physical operator is needed; the
     * executor's existing inline-scan path produces exactly the right stream.
     */
    private PhysicalNode planTruthRelation(TruthRelationNode node) {
        List<Map<String, Operand>> rows = node.holdsTuple() ? List.of(Map.of()) : List.of();
        RelationSymbol literal = new InlineRelationSymbol(
                "builtin", node.keyword(), Provenance.BUILTIN, ShadowPolicy.FORBIDDEN,
                Schema.empty(), rows);
        return new PhysicalNode.Scan(Schema.empty(), literal);
    }

    /**
     * Plans a projection, narrowing the scan beneath it when that scan can be asked for
     * fewer columns than the relation declares.
     *
     * <p>This is projection pushdown for a source whose <em>selection is its schema</em>.
     * A SQL backend needs a rendered {@code SELECT} list and an aggregation pipeline needs
     * a {@code $project}, so both go through a {@link PushdownRenderer} and a native query.
     * A GraphQL endpoint needs neither: its selection set is the field list, and a connector
     * is already handed one as the schema it must produce. So the fold is to hand it a
     * smaller schema, and the {@code π} stays above — it costs nothing once the scan is
     * narrow, and it is what still decides the output's column <em>order</em>.
     *
     * <p>Only an HTTP source is narrowed, and deliberately: narrowing is safe exactly where
     * a connector resolves each column independently by name or path. A generator's heading
     * comes from a registry that the analyser read too, and an inline relation's rows are
     * stored whole — for those, a narrower request is a different relation rather than a
     * cheaper one.
     */
    private PhysicalNode planProjection(ProjectionNode node) {
        PhysicalNode input = narrowedScan(node).orElseGet(() -> plan(node.input()));
        return new PhysicalNode.Project(schemaOf(node), node.attributes(), input);
    }

    /** {@return the narrowed scan for {@code node}'s input, when there is one to narrow} */
    private Optional<PhysicalNode> narrowedScan(ProjectionNode node) {
        if (!node.isColumnPruning() || !(node.input() instanceof RelationNode relation)) {
            return Optional.empty();
        }
        SourceDeclaration declaration = sources.get(relation.name().toLowerCase(Locale.ROOT));
        if (declaration == null || !(declaration.config() instanceof HttpSourceConfig)) {
            return Optional.empty();
        }
        // Chained rather than branched: an unresolvable relation is not a case to handle
        // here — planRelation raises on one — and schema-on-read has no declared columns to
        // drop, so both simply fall out of the Optional.
        return symbols.resolveRelation(relation.name())
                .filter(symbol -> !symbol.schema().isOpen())
                .flatMap(symbol -> narrowTo(symbol, relation, wantedColumns(node)));
    }

    /** {@return the column names a column-pruning projection reads} */
    private static List<String> wantedColumns(ProjectionNode node) {
        return node.attributes().stream()
                .map(a -> ((AttributeOperand) a.expression()).name())
                .toList();
    }

    /** {@return a scan over {@code wanted} alone, or empty when that is every column} */
    private static Optional<PhysicalNode> narrowTo(RelationSymbol symbol, RelationNode relation,
                                                   List<String> wanted) {
        Schema declared = symbol.schema();
        List<ColumnDefinition> kept = declared.columns().stream()
                .filter(c -> wanted.stream().anyMatch(w -> w.equalsIgnoreCase(c.name())))
                .toList();
        if (kept.size() == declared.columns().size()) {
            return Optional.empty();   // the query reads all of them; there is nothing to drop
        }
        return Optional.of(new PhysicalNode.Scan(new Schema(kept), symbol,
                relation.produceBound(), Optional.of(relation.name())));
    }

    private PhysicalNode planRelation(RelationNode node) {
        RelationSymbol symbol = symbols.resolveRelation(node.name())
                .orElseThrow(() -> new IllegalStateException("Unknown relation: '" + node.name() + "'"));
        if (symbol instanceof QueryRelationSymbol view) {
            return plan(view.body());   // inline the view
        }
        return new PhysicalNode.Scan(symbol.schema(), symbol, node.produceBound(), Optional.of(node.name()));
    }

    /**
     * Plans a table-valued function call by <em>inlining</em>: binds the function's
     * body to the call arguments (parameter references substituted by argument
     * expressions, {@link RelationFunctionInliner}), re-annotates the freshly-built
     * body so {@link #schemaOf} can resolve it, then plans the body.  Recursive
     * (self- or mutually-recursive) functions are rejected — inlining would not
     * terminate.
     */
    private PhysicalNode planRelationFunctionCall(RelationFunctionCall call) {
        RelationFunctionSymbol fn = symbols.resolveFunction(call.functionName()).stream()
                .filter(RelationFunctionSymbol.class::isInstance)
                .map(RelationFunctionSymbol.class::cast)
                .filter(f -> f.parameters().size() == call.arguments().size())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Unknown table-valued function: '" + call.functionName()
                        + "' with " + call.arguments().size() + " argument(s)"));

        String key = fn.canonicalName();
        if (!activeInlines.add(key)) {
            throw new IllegalStateException("Recursive table-valued function '"
                    + call.functionName() + "' cannot be inlined "
                    + "(recursive table-valued functions are not supported)");
        }
        try {
            RelNode body = RelationFunctionInliner.bind(fn, call.arguments());
            // The substituted body is freshly built — its nodes are absent from the
            // annotation set, so re-infer (the substituted body has no parameter
            // references left to resolve) and merge before planning it.
            this.schemas = SchemaInference.annotate(symbols, body, this.schemas, functions);
            return plan(body);
        } finally {
            activeInlines.remove(key);
        }
    }

    // ── distinct & aggregation (streaming over ordered input — Phase C3) ────────

    /**
     * Plans a {@code δ} (DISTINCT).  Chooses the streaming variant when the input
     * already delivers an ordering covering the whole output row — duplicate rows are
     * then contiguous, so the executor deduplicates in a single linear pass; otherwise
     * falls back to the hash-based distinct.
     *
     * <p>A column-less schema (an {@linkplain Schema#open() open}/schema-on-read or
     * {@linkplain Schema#empty() empty} relation) never qualifies: {@code clusters}
     * is vacuously satisfied by the empty column set, but the input carries no proven
     * ordering, so adjacent-only deduplication would be unsound.
     */
    private PhysicalNode planDistinct(DistinctNode node) {
        PhysicalNode input = plan(node.input());
        Schema schema = schemaOf(node);
        List<String> columns = schema.columns().stream().map(ColumnDefinition::name).toList();
        boolean streaming = !columns.isEmpty() && input.deliveredOrdering().clusters(columns);
        return new PhysicalNode.Distinct(schema, streaming, input);
    }

    /**
     * Plans a {@code γ} (GROUP).  Chooses the streaming variant when the input already
     * delivers an ordering grouping the rows by the grouping keys — each group is then
     * contiguous, so the executor aggregates in a single linear pass holding one group
     * at a time; otherwise falls back to the hash-grouped aggregate.  A scalar
     * aggregate (no grouping keys) is never streaming — it must read the whole input.
     */
    private PhysicalNode planAggregate(AggregationNode node) {
        PhysicalNode input = plan(node.input());
        // Only a grouping entirely over plain columns can be matched against the
        // input's delivered ordering to stream; a derived key (e.g. YEAR(ts)) forces
        // the hash-grouped path.
        boolean streaming = GroupingKey.plainColumns(node.groupingKeys())
                .map(cols -> input.deliveredOrdering().groupsBy(cols))
                .orElse(false);
        return new PhysicalNode.Aggregate(
                schemaOf(node), node.groupingKeys(), node.aggregates(), streaming, input);
    }

    // ── joins ─────────────────────────────────────────────────────────────────

    private PhysicalNode planJoin(JoinKind kind, RelNode logical,
                                  RelNode leftLogical, RelNode rightLogical, Predicate condition) {
        PhysicalNode left = plan(leftLogical);
        PhysicalNode right = plan(rightLogical);
        Set<String> leftRelations = Qualifiers.inScope(leftLogical);
        Set<String> rightRelations = Qualifiers.inScope(rightLogical);
        JoinKeys keys = JoinPlanning.extractKeys(
                condition, left.schema(), right.schema(), leftRelations, rightRelations);

        JoinAlgorithm algorithm;
        if (!keys.isEmpty() && mergeEligible(kind)) {
            MergePlan mp = tryMerge(left, leftLogical, right, rightLogical, keys);
            if (mp != null) {
                left      = mp.left();
                right     = mp.right();
                algorithm = JoinAlgorithm.MERGE;
            } else {
                algorithm = JoinAlgorithm.HASH;
            }
        } else {
            algorithm = keys.isEmpty() ? JoinAlgorithm.NESTED_LOOP : JoinAlgorithm.HASH;
        }
        return new PhysicalNode.Join(schemaOf(logical), kind, algorithm,
                buildSide(kind, leftLogical, rightLogical, algorithm), Optional.of(condition), keys,
                leftRelations, rightRelations, left, right);
    }

    /**
     * Plans an AS-OF join (ADR-0014): decomposes the condition into partition
     * (equality) keys and the single ordering inequality, baking both into a
     * {@link PhysicalNode.AsOfJoin}.  Never pushed to a source.
     */
    private PhysicalNode planAsOfJoin(AsOfJoinNode node) {
        PhysicalNode left = plan(node.left());
        PhysicalNode right = plan(node.right());
        Set<String> leftRelations = Qualifiers.inScope(node.left());
        Set<String> rightRelations = Qualifiers.inScope(node.right());
        JoinKeys partitionKeys = JoinPlanning.extractKeys(
                node.condition(), left.schema(), right.schema(), leftRelations, rightRelations);
        JoinPlanning.AsOfMatch match = JoinPlanning.extractAsOfMatch(
                node.condition(), left.schema(), right.schema(), leftRelations, rightRelations);
        if (match == null) {
            // Defensive: the validator guarantees one resolvable ordering inequality.
            throw new IllegalStateException("AS-OF join has no resolvable ordering inequality");
        }
        // Evaluate the optional tolerance DURATION literal at planning time.
        java.util.Optional<java.time.Duration> tolerance = node.tolerance().map(t -> {
            if (t instanceof com.darkcollective.relix.ast.DurationOperand d) {
                return d.value();
            }
            throw new IllegalStateException(
                    "AS-OF WITHIN tolerance must be a DURATION literal; got " + t.getClass().getSimpleName());
        });
        return new PhysicalNode.AsOfJoin(schemaOf(node), partitionKeys,
                match.leftMatchIndex(), match.rightMatchIndex(), match.backward(), match.strict(),
                tolerance, node.inner(), node.tieBreak(),
                left, right);
    }

    /**
     * Plans an interval join (ADR-0014): resolves the four endpoint column
     * indices from the left and right schemas and emits a
     * {@link PhysicalNode.IntervalJoin}.  Never pushed to a source.
     *
     * <p>Chooses the streaming sort-merge variant (ADR-0009 ordering reuse) only when
     * the relation is in the overlap-or-touch family ({@code PRECEDES}/{@code PRECEDED_BY}
     * have their own sorted-band executor) <em>and</em> both inputs already deliver an
     * ascending order on their interval start column — so no {@link PhysicalNode.Sort}
     * is inserted.  Otherwise the general plane-sweep variant runs.
     */
    private PhysicalNode planIntervalJoin(IntervalJoinNode node) {
        PhysicalNode left  = plan(node.left());
        PhysicalNode right = plan(node.right());
        com.darkcollective.relix.symbol.Schema ls = left.schema();
        com.darkcollective.relix.symbol.Schema rs = right.schema();
        int lStartIdx = resolveColumnIndex(ls, node.leftStart(),  "left start",  node);
        int lEndIdx   = resolveColumnIndex(ls, node.leftEnd(),    "left end",    node);
        int rStartIdx = resolveColumnIndex(rs, node.rightStart(), "right start", node);
        int rEndIdx   = resolveColumnIndex(rs, node.rightEnd(),   "right end",   node);
        boolean merge = intervalMergeEligible(node, left, right, lStartIdx, rStartIdx);
        return new PhysicalNode.IntervalJoin(schemaOf(node), node.relation(),
                lStartIdx, lEndIdx, rStartIdx, rEndIdx, merge, left, right);
    }

    /**
     * Whether an interval join may use the streaming sort-merge variant: the relation
     * must overlap or touch (the band relations keep their own executor), and both
     * inputs must already deliver an ascending order on their start column.  The
     * required ordering is built from the start column's name as it appears in the
     * input schema, matching what an upstream {@code τ} delivers.
     */
    private static boolean intervalMergeEligible(IntervalJoinNode node,
                                                 PhysicalNode left, PhysicalNode right,
                                                 int lStartIdx, int rStartIdx) {
        switch (node.relation()) {
            case PRECEDES, PRECEDED_BY -> { return false; }
            default -> { /* overlap-or-touch family — eligible */ }
        }
        Ordering leftRequired  = startOrdering(left.schema(),  lStartIdx);
        Ordering rightRequired = startOrdering(right.schema(), rStartIdx);
        return left.deliveredOrdering().satisfies(leftRequired)
            && right.deliveredOrdering().satisfies(rightRequired);
    }

    /** A single-key ascending {@link Ordering} on the start column at {@code startIdx} of {@code schema}. */
    private static Ordering startOrdering(Schema schema, int startIdx) {
        return Ordering.of(List.of(
                new SortSpecification(schema.columns().get(startIdx).name(), SortDirection.ASC)));
    }

    /** Resolves a column name to its 0-based index in {@code schema}, throwing if absent. */
    private static int resolveColumnIndex(com.darkcollective.relix.symbol.Schema schema,
                                          String name, String role, RelNode node) {
        // Strip qualifier (e.g. "Stays.checkin" → "checkin")
        String simple = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
        int idx = schema.indexOf(simple);
        if (idx < 0) {
            throw new IllegalStateException(
                    "IJOIN: " + role + " column '" + name + "' not found in schema " + schema);
        }
        return idx;
    }

    private PhysicalNode planNaturalJoin(NaturalJoinNode node) {
        PhysicalNode left = plan(node.left());
        PhysicalNode right = plan(node.right());
        JoinKeys keys = commonColumnKeys(left.schema(), right.schema());
        // A natural join is always an equi-join on its common columns; the executor
        // yields no rows when there are none.  It matches by column index (no qualified
        // condition), so the relation-name sets are unused here.
        JoinAlgorithm algorithm = JoinAlgorithm.HASH;
        if (!keys.isEmpty()) {
            MergePlan mp = tryMerge(left, node.left(), right, node.right(), keys);
            if (mp != null) {
                left      = mp.left();
                right     = mp.right();
                algorithm = JoinAlgorithm.MERGE;
            }
        }
        return new PhysicalNode.Join(schemaOf(node), JoinKind.NATURAL, algorithm,
                buildSide(JoinKind.NATURAL, node.left(), node.right(), algorithm),
                Optional.empty(), keys, Set.of(), Set.of(), left, right);
    }

    /**
     * Tries to plan a merge join between {@code left} and {@code right} on {@code keys},
     * choosing among the three ways to feed a merge (ADR-0009, Phase C4):
     *
     * <ul>
     *   <li><b>one side already ordered</b> — merge, {@linkplain #enforceOrder enforcing}
     *       the order on the other side (an {@code ORDER BY} pushed into its source where
     *       possible, else an in-engine {@link PhysicalNode.Sort});</li>
     *   <li><b>neither side ordered, but both index-backed</b> — a <em>source-sorted
     *       merge</em>: each side's required order is pushed into its source as an
     *       {@code ORDER BY} the database serves from an index, so both streams arrive
     *       sorted for (near) free and merge with no in-engine sort (the federation win);</li>
     *   <li><b>otherwise</b> — {@code null}, preferring a HASH join, since sorting a side
     *       in-engine (or a non-indexed source sort) merely to enable a merge is not a
     *       clear win.</li>
     * </ul>
     *
     * @param leftLogical  the left input's logical node, used to retry SQL pushdown with
     *                     an injected order; {@code rightLogical} likewise for the right
     */
    private MergePlan tryMerge(PhysicalNode left, RelNode leftLogical,
                               PhysicalNode right, RelNode rightLogical, JoinKeys keys) {
        Ordering leftRequired  = mergeOrdering(keys.left(),  left.schema());
        Ordering rightRequired = mergeOrdering(keys.right(), right.schema());
        boolean leftSorted  = left.deliveredOrdering().satisfies(leftRequired);
        boolean rightSorted = right.deliveredOrdering().satisfies(rightRequired);

        if (leftSorted || rightSorted) {
            return new MergePlan(
                    leftSorted  ? left  : enforceOrder(left,  leftLogical,  leftRequired),
                    rightSorted ? right : enforceOrder(right, rightLogical, rightRequired));
        }

        // Neither side is pre-sorted: a source-sorted merge pays off only when BOTH sides
        // deliver the order near-free from an index-backed source ORDER BY.
        Optional<PhysicalNode.PushedScan> leftScan  = indexBackedOrderedScan(leftLogical,  leftRequired);
        Optional<PhysicalNode.PushedScan> rightScan = indexBackedOrderedScan(rightLogical, rightRequired);
        if (leftScan.isPresent() && rightScan.isPresent()) {
            return new MergePlan(leftScan.get(), rightScan.get());
        }
        return null;  // prefer HASH
    }

    /** Carrier for the (possibly enforcer-augmented) left and right merge inputs. */
    private record MergePlan(PhysicalNode left, PhysicalNode right) {}

    /**
     * Makes {@code physical} deliver {@code required}: as an ordered
     * {@link PhysicalNode.PushedScan} when the order can be pushed into its source
     * (re-pushing {@code logical} with an injected {@code ORDER BY}, ADR-0009 Phase C4),
     * else wrapped in an in-engine {@link PhysicalNode.Sort} enforcer.
     */
    private PhysicalNode enforceOrder(PhysicalNode physical, RelNode logical, Ordering required) {
        Optional<PhysicalNode.PushedScan> pushed = tryPushOrdered(logical, required.keys());
        if (pushed.isPresent()) {
            return pushed.get();
        }
        return new PhysicalNode.Sort(physical.schema(), required.keys(), physical);
    }

    /**
     * Returns an ordered {@link PhysicalNode.PushedScan} for {@code logical} when its required
     * order can be both pushed into its source <em>and</em> served there from an index — so
     * the database returns the rows ordered for (near) free.  "Index-backed" is approximated
     * structurally: the order keys are a prefix of a known candidate key of the single base
     * relation the side scans.  Empty when the order is not pushable, the side spans more
     * than one base relation, or no candidate key covers the order keys (e.g. an arbitrary,
     * non-indexed column, or a stat-less / non-database source).
     */
    private Optional<PhysicalNode.PushedScan> indexBackedOrderedScan(RelNode logical, Ordering required) {
        if (!indexBacked(logical, required)) {
            return Optional.empty();
        }
        return tryPushOrdered(logical, required.keys());
    }

    /** Whether {@code required}'s keys are a prefix of a candidate key of {@code logical}'s base relation. */
    private boolean indexBacked(RelNode logical, Ordering required) {
        Set<String> relations = Qualifiers.inScope(logical);
        if (relations.size() != 1) {
            return false;   // need a single base relation to attribute a candidate key
        }
        Optional<RelationStatistics> stats = statistics.forRelation(relations.iterator().next());
        if (stats.isEmpty()) {
            return false;
        }
        List<String> orderColumns = new ArrayList<>(required.keys().size());
        for (SortSpecification key : required.keys()) {
            Optional<String> col = key.columnName();
            if (col.isEmpty()) {
                return false;   // a derived sort key is not backed by a stored index
            }
            orderColumns.add(col.get());
        }
        return stats.get().keys().stream().anyMatch(key -> isPrefix(orderColumns, key));
    }

    /** Whether {@code prefix} is a leading sublist of {@code full} (case-insensitive). */
    private static boolean isPrefix(List<String> prefix, List<String> full) {
        if (prefix.size() > full.size()) {
            return false;
        }
        for (int i = 0; i < prefix.size(); i++) {
            if (!prefix.get(i).equalsIgnoreCase(full.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code kind} supports a merge-join execution. */
    private static boolean mergeEligible(JoinKind kind) {
        return switch (kind) {
            case INNER, SEMI, ANTI -> true;
            default -> false;
        };
    }

    /**
     * Builds the required {@link Ordering} for a merge join on {@code keyIndices}
     * (column positions in {@code schema}).  Uses {@link SortDirection#ASC} for all
     * keys — the planner only promotes MERGE when the delivered ordering already
     * satisfies this (or inserts an ASC enforcer), so direction is consistent on
     * both sides.
     *
     * <p>Also the ordering a merge join <em>delivers</em>: shared with
     * {@link PhysicalNode.Join#deliveredOrdering()} so the required and delivered
     * forms are one definition rather than two that can drift.
     */
    public static Ordering mergeOrdering(List<Integer> keyIndices, Schema schema) {
        List<SortSpecification> specs = keyIndices.stream()
                .map(i -> new SortSpecification(schema.columns().get(i).name(), SortDirection.ASC))
                .toList();
        return Ordering.of(specs);
    }

    /**
     * Desugars symmetric difference {@code A ∆ B} into {@code (A − B) ∪ (B − A)}
     * over physical set operators.  All three intermediate set operations share
     * the node's (union-compatible) output schema.
     *
     * <p>Both inputs are read <em>twice</em> by that desugaring — once by each
     * difference — so each is planned once and wrapped in a
     * {@link PhysicalNode.Spool} that both branches read, rather than planned twice
     * into two independent sub-plans the executor would run separately.  Where an
     * input cannot be shared (see {@code share}) the plan falls back to the two
     * independent sub-plans, which is what it always did.
     */
    private PhysicalNode planSymmetricDifference(SymmetricDifferenceNode node) {
        Schema schema = schemaOf(node);
        PhysicalNode left =
                share(node.left(), plan(node.left()), SharedSubexpressions.Sharing.sites(2));
        PhysicalNode right =
                share(node.right(), plan(node.right()), SharedSubexpressions.Sharing.sites(2));
        PhysicalNode leftMinusRight =
                new PhysicalNode.SetOp(schema, SetKind.DIFFERENCE, left, right);
        PhysicalNode rightMinusLeft =
                new PhysicalNode.SetOp(schema, SetKind.DIFFERENCE, right, left);
        return new PhysicalNode.SetOp(schema, SetKind.UNION, leftMinusRight, rightMinusLeft);
    }

    // ── sharing ───────────────────────────────────────────────────────────────

    /**
     * Wraps {@code physical} in a {@link PhysicalNode.Spool} so that the several
     * places reading it evaluate it once, or returns it unchanged when sharing its
     * rows would not be sound.
     *
     * <p>Three things make a sub-expression unsharable, and each of them is a way to
     * return silently wrong rows rather than merely slow ones:
     *
     * <ul>
     *   <li><b>It reads system state.</b> Two evaluations of a random draw or an
     *       unseeded sample are <em>entitled</em> to differ, and a user who writes
     *       {@code X ∆ X} over one is asking for exactly that difference. Replaying
     *       one evaluation would silently answer a different question.</li>
     *   <li><b>It names a recursive relation bound outside itself.</b> A recursive
     *       reference denotes the current iteration's rows, so a buffer filled during
     *       the first iteration would still be answering for the first iteration on
     *       the tenth. A self-contained {@code FIX} is fine — the references its own
     *       step makes are bound by it — which is why the check tracks which names are
     *       bound rather than rejecting recursion outright.</li>
     *   <li><b>It is a table-valued-function body under a LATERAL join.</b> That body
     *       is planned once per outer row, so a spool minted inside one would allocate
     *       a fresh id per row and buffer rows for every one of them.</li>
     * </ul>
     *
     * @param logical  the logical expression {@code physical} was planned from
     * @param physical the planned sub-plan
     * @param sharing  why the sub-expression is worth evaluating once
     * @return the sub-plan, spooled when sharing is sound
     */
    private PhysicalNode share(RelNode logical, PhysicalNode physical,
                               SharedSubexpressions.Sharing sharing) {
        if (physical instanceof PhysicalNode.Spool) {
            return physical;   // already shared — one spool is what the readers want
        }
        if (lateralBodyDepth > 0 || !shareable(logical)) {
            return physical;
        }
        PhysicalNode.Spool spool =
                new PhysicalNode.Spool(physical.schema(), nextSpoolId++, physical);
        listener.onEvent(QueryEvent.of(QueryEvent.Stage.PLAN, "SPOOL",
                "shared sub-expression #" + spool.id() + " (" + reason(sharing) + "): "
                        + logical.prettyPrint()));
        estimates.record(spool, costEstimator.estimateRows(logical));
        return spool;
    }

    /**
     * What the spool event says about why this sub-expression is shared. A count is
     * the ordinary answer; a sub-expression inside a fixpoint step is written once and
     * evaluated once per iteration, and the round count is not known until it runs.
     */
    private static String reason(SharedSubexpressions.Sharing sharing) {
        return sharing.perRound()
                ? "once per fixpoint round"
                : sharing.sites() + " sites";
    }

    /**
     * Whether {@code node}'s rows may stand in for a second evaluation of it — the
     * question both the detection pass and {@link #share} ask, so that they cannot
     * disagree about what is shareable.
     *
     * <p>Independent of where the node sits, which is what lets the detection pass
     * memoize the answer per distinct sub-expression: the {@code LATERAL} rule is
     * about the planner's position rather than the node, so it lives in
     * {@link #share} instead.
     */
    private boolean shareable(RelNode node) {
        return !freeToReEvaluate(node)
                && !referencesUnboundRecursion(node)
                && RelationDeterminism.isDeterministic(node, symbols, functions);
    }

    /**
     * Whether evaluating {@code node} a second time would repeat no work — in which
     * case holding its rows costs memory and saves nothing.
     *
     * <p>True of exactly the sub-expressions that have no operators of their own and
     * name rows already in memory: the {@code UNIT}/{@code EMPTY} literals, the
     * optimizer's empty relation, and a reference to an inline or system relation.
     * A reference to a <em>view</em> is deliberately not one of them — evaluating it
     * means evaluating its body, which is exactly the work worth doing once — and
     * neither is a file or database relation, where a second evaluation is a second
     * read of the source.
     */
    private boolean freeToReEvaluate(RelNode node) {
        if (node instanceof TruthRelationNode || node instanceof EmptyRelationNode) {
            return true;
        }
        if (node instanceof RelationNode relation) {
            return symbols.resolveRelation(relation.name())
                    .map(symbol -> symbol instanceof InlineRelationSymbol
                                || symbol instanceof SystemRelationSymbol)
                    .orElse(false);
        }
        return false;
    }

    /**
     * Whether {@code node} reads a recursive relation whose binding lives outside it —
     * the case where one evaluation cannot stand in for another, because the binding
     * changes with every fixpoint iteration.
     *
     * <p>A {@link FixpointNode} binds its own name for the whole of its base and step,
     * so a self-contained recursion answers {@code false} and can be shared like any
     * other sub-expression.
     */
    private static boolean referencesUnboundRecursion(RelNode node) {
        return referencesUnboundRecursion(node, Set.of());
    }

    private static boolean referencesUnboundRecursion(RelNode node, Set<String> bound) {
        if (node instanceof RecursiveRefNode ref) {
            return !bound.contains(ref.name().toLowerCase(java.util.Locale.ROOT));
        }
        Set<String> inner = bound;
        if (node instanceof FixpointNode fix) {
            inner = new HashSet<>(bound);
            inner.add(fix.name().toLowerCase(java.util.Locale.ROOT));
        }
        for (RelNode child : node.children()) {
            if (referencesUnboundRecursion(child, inner)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Desugars composition {@code R ∘ S} into {@code π (non-shared columns) (R ⋈ S)}:
     * a natural join on the shared columns followed by a projection that drops them.
     * The projection's target columns are exactly the composition node's output
     * schema, which the schema-inference phase already computed.
     */
    private PhysicalNode planComposition(CompositionNode node) {
        PhysicalNode left = plan(node.left());
        PhysicalNode right = plan(node.right());
        JoinKeys keys = commonColumnKeys(left.schema(), right.schema());
        Schema joinSchema = naturalJoinSchema(left.schema(), right.schema());
        PhysicalNode join = new PhysicalNode.Join(joinSchema, JoinKind.NATURAL, JoinAlgorithm.HASH,
                buildSide(JoinKind.NATURAL, node.left(), node.right()), Optional.empty(), keys,
                Set.of(), Set.of(), left, right);

        Schema outSchema = schemaOf(node);
        List<ProjectedAttribute> attributes = outSchema.columns().stream()
                .map(col -> ProjectedAttribute.simple(new AttributeOperand(col.name())))
                .toList();
        return new PhysicalNode.Project(outSchema, attributes, join);
    }

    /** Natural-join output schema: left columns, then right columns with new names. */
    private static Schema naturalJoinSchema(Schema left, Schema right) {
        List<ColumnDefinition> cols = new ArrayList<>(left.columns());
        for (ColumnDefinition rc : right.columns()) {
            if (left.indexOf(rc.name()) < 0) {
                cols.add(rc);
            }
        }
        return new Schema(cols);
    }

    /**
     * Desugars Bernoulli sampling {@code SAMPLE p (R)} into the selection
     * {@code σ RAND() < p (R)} — a {@link PhysicalNode.Select} over a per-row
     * {@code RAND() < p} predicate, so each row is an independent coin flip.
     */
    private PhysicalNode planSample(SampleNode node) {
        return new PhysicalNode.BernoulliSample(
                schemaOf(node), node.probability(), node.seed(), plan(node.input()));
    }

    // ── constructive COVER (ADR-0012 Decision 3 Option B) ────────────────────

    /**
     * Attempts to recognise the pattern {@code Cover(t, σ?(Product(leaves…)))} and
     * return a {@link PhysicalNode.ConstructiveCover} that avoids materialising the
     * full Cartesian product.
     *
     * <p>Matches when the COVER's input (after stripping zero or more selection
     * layers) is a (possibly nested) {@link ProductNode} of non-product leaves.  The
     * collected conjuncts are passed to the executor, which evaluates each only once
     * all of its referenced columns are bound (partial-row validity oracle).
     *
     * <p>Returns empty when the pattern does not match; the caller then falls back to
     * the materialized {@link PhysicalNode.Cover}.
     */
    private Optional<PhysicalNode.ConstructiveCover> tryConstructiveCover(CoverNode c) {
        RelNode body = c.input();
        List<Predicate> conjuncts = new ArrayList<>();

        // Strip selection layers, splitting each predicate into atomic conjuncts.
        while (body instanceof SelectionNode s) {
            splitConjuncts(s.predicate(), conjuncts);
            body = s.input();
        }

        // Body must be a (possibly nested) product of non-product leaves.
        if (!(body instanceof ProductNode)) return Optional.empty();

        List<RelNode> leaves = flattenProduct(body);

        // Plan each factor independently; the executor will execute each separately.
        List<PhysicalNode> factors = new ArrayList<>(leaves.size());
        for (RelNode leaf : leaves) {
            factors.add(plan(leaf));
        }

        return Optional.of(new PhysicalNode.ConstructiveCover(
                schemaOf(c), c.strength(), factors, conjuncts));
    }

    /** Recursively collects the atomic conjuncts from an {@link AndPredicate} tree. */
    private static void splitConjuncts(Predicate p, List<Predicate> out) {
        if (p instanceof AndPredicate and) {
            splitConjuncts(and.left(), out);
            splitConjuncts(and.right(), out);
        } else {
            out.add(p);
        }
    }

    /**
     * Recursively flattens a (possibly nested) {@link ProductNode} tree into a list
     * of non-product leaves, preserving left-to-right order.
     */
    private static List<RelNode> flattenProduct(RelNode node) {
        List<RelNode> result = new ArrayList<>();
        flattenProductHelper(node, result);
        return result;
    }

    private static void flattenProductHelper(RelNode node, List<RelNode> out) {
        if (node instanceof ProductNode p) {
            flattenProductHelper(p.left(), out);
            flattenProductHelper(p.right(), out);
        } else {
            out.add(node);
        }
    }

    private PhysicalNode planProduct(ProductNode node) {
        PhysicalNode left = plan(node.left());
        PhysicalNode right = plan(node.right());
        return new PhysicalNode.Join(schemaOf(node), JoinKind.PRODUCT, JoinAlgorithm.NESTED_LOOP,
                buildSide(JoinKind.PRODUCT, node.left(), node.right()), Optional.empty(), JoinKeys.none(),
                // No condition needs them, but a schema-on-read output does: its rows
                // record which relation each field came from (#970).
                Qualifiers.inScope(node.left()), Qualifiers.inScope(node.right()),
                left, right);
    }

    /**
     * Picks the build side.  For a {@link JoinAlgorithm#MERGE} join the executor
     * does not use the build side, so {@link BuildSide#RIGHT} is returned by
     * convention.  For HASH/nested-loop: outer/semi/anti joins build their
     * non-preserved side; symmetric joins build whichever input the cost model
     * rates cheaper (ties favour the right).
     */
    private BuildSide buildSide(JoinKind kind, RelNode left, RelNode right,
                                JoinAlgorithm algorithm) {
        if (algorithm == JoinAlgorithm.MERGE) {
            return BuildSide.RIGHT;  // convention — ignored by merge executor
        }
        BuildSide chosen = switch (kind) {
            case LEFT_OUTER, SEMI, ANTI, UNIVERSAL_SEMI -> BuildSide.RIGHT;
            case RIGHT_OUTER            -> BuildSide.LEFT;
            case INNER, NATURAL, FULL_OUTER, PRODUCT ->
                    estimateCheaperOrEqual(right, left) ? BuildSide.RIGHT : BuildSide.LEFT;
        };
        return enforceBoundedBuildSide(kind, left, right, chosen);
    }

    /**
     * A hash/nested-loop join materialises its <em>build</em> side, so a provably
     * {@linkplain Boundedness#UNBOUNDED unbounded} input must never be the build side
     * (ADR-0008): keep it on the probe side. If both inputs are unbounded there is no
     * boundable build side — a plan-time {@link BoundednessException}. When the chosen
     * build side is bounded (the common case) this is a no-op.
     */
    private BuildSide enforceBoundedBuildSide(JoinKind kind, RelNode left, RelNode right, BuildSide chosen) {
        boolean leftUnbounded = PropertyDeriver.boundedness(left, boundedness) == Boundedness.UNBOUNDED;
        boolean rightUnbounded = PropertyDeriver.boundedness(right, boundedness) == Boundedness.UNBOUNDED;
        if (!leftUnbounded && !rightUnbounded) {
            return chosen;
        }
        if (leftUnbounded && rightUnbounded) {
            throw new BoundednessException(
                    "cannot " + kind + " join two unbounded relations (no boundable hash build side); "
                    + "add a bound (e.g. λ n) to one side");
        }
        // Exactly one side is unbounded — it must be the probe; build the bounded side.
        BuildSide required = leftUnbounded ? BuildSide.RIGHT : BuildSide.LEFT;
        if (chosen != required) {
            // An asymmetric join (e.g. LEFT_OUTER builds the right) whose required build
            // side is the unbounded one cannot be satisfied.
            boolean asymmetric = switch (kind) {
                case LEFT_OUTER, SEMI, ANTI, UNIVERSAL_SEMI, RIGHT_OUTER -> true;
                case INNER, NATURAL, FULL_OUTER, PRODUCT -> false;
            };
            if (asymmetric) {
                throw new BoundednessException(
                        "cannot " + kind + " join: its build side is the unbounded relation; "
                        + "add a bound (e.g. λ n) to that side");
            }
        }
        return required;
    }

    /** Backward-compatible 3-arg variant used by callers that have no algorithm yet. */
    private BuildSide buildSide(JoinKind kind, RelNode left, RelNode right) {
        return buildSide(kind, left, right, JoinAlgorithm.HASH);
    }

    private boolean estimateCheaperOrEqual(RelNode candidate, RelNode other) {
        OptionalLong candidateRows = costEstimator.estimateRows(candidate);
        OptionalLong otherRows = costEstimator.estimateRows(other);
        if (candidateRows.isPresent() && otherRows.isPresent()) {
            return candidateRows.getAsLong() <= otherRows.getAsLong();   // build the smaller side
        }
        return costEstimator.estimate(candidate).compareTo(costEstimator.estimate(other)) <= 0;
    }

    private static JoinKeys commonColumnKeys(Schema left, Schema right) {
        List<Integer> leftIdx = new ArrayList<>();
        List<Integer> rightIdx = new ArrayList<>();
        List<ColumnDefinition> leftCols = left.columns();
        for (int i = 0; i < leftCols.size(); i++) {
            int ri = right.indexOf(leftCols.get(i).name());
            if (ri >= 0) {
                leftIdx.add(i);
                rightIdx.add(ri);
            }
        }
        return new JoinKeys(leftIdx, rightIdx);
    }

    // ── lateral / correlated TVF join ──────────────────────────────────────────

    /**
     * Plans a {@code LATERAL} correlated TVF join.  The left input is planned once;
     * a {@link Function}{@code <List<Operand>, PhysicalNode>} {@code bodyBuilder}
     * lambda captures the planner context so the executor can build a fresh physical
     * body for each outer row, substituting per-row argument values.
     *
     * <p>The {@code bodyBuilder} follows the same bind-annotate-plan sequence as
     * {@link #planRelationFunctionCall}, but the argument literals are supplied at
     * execution time (one {@link List} per outer row), so the lambda is invoked once
     * per left row rather than once at plan time.
     */
    private PhysicalNode planLateralJoin(LateralJoinNode node) {
        PhysicalNode left = plan(node.left());
        Schema outSchema = schemaOf(node);

        RelationFunctionSymbol fn = symbols.resolveFunction(node.functionName()).stream()
                .filter(RelationFunctionSymbol.class::isInstance)
                .map(RelationFunctionSymbol.class::cast)
                .filter(f -> f.parameters().size() == node.arguments().size())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Unknown table-valued function '" + node.functionName()
                        + "' for LATERAL join with " + node.arguments().size() + " argument(s)"));

        // bodyBuilder: given per-row argument literals (one per outer row), inline the
        // function body and plan it.  Captures `this` (the Planner) for access to
        // RelationFunctionInliner (package-private) and the growing schemas annotation set.
        Function<List<com.darkcollective.relix.ast.Operand>, PhysicalNode> bodyBuilder = argOperands -> {
            String key = fn.canonicalName();
            if (!activeInlines.add(key)) {
                throw new IllegalStateException("Recursive table-valued function '"
                        + node.functionName() + "' cannot be inlined in LATERAL join");
            }
            lateralBodyDepth++;
            try {
                RelNode body = RelationFunctionInliner.bind(fn, argOperands);
                this.schemas = SchemaInference.annotate(symbols, body, this.schemas, functions);
                return plan(body);
            } finally {
                lateralBodyDepth--;
                activeInlines.remove(key);
            }
        };

        // Whether one execution of the body may stand in for all of them. The executor
        // is handed `bodyBuilder`, a lambda it cannot look inside, so the decision has to
        // be made here — where `fn.body()` is in hand — and carried on the node.
        boolean deterministicBody =
                RelationDeterminism.isDeterministic(fn.body(), symbols, functions);

        return new PhysicalNode.LateralJoin(outSchema, node.functionName(),
                node.arguments(), bodyBuilder, deterministicBody, left);
    }

    // ── schema resolution ─────────────────────────────────────────────────────

    private Schema schemaOf(RelNode node) {
        return schemas.require(node,
                "— was the tree annotated (SchemaInference) before planning?");
    }
}

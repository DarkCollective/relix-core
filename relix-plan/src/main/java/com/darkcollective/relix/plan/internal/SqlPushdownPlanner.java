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
import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.AggregationNode;
import com.darkcollective.relix.ast.AllenRelation;
import com.darkcollective.relix.ast.AsOfJoinNode;
import com.darkcollective.relix.ast.DistinctNode;
import com.darkcollective.relix.ast.IntervalJoinNode;
import com.darkcollective.relix.ast.LimitNode;
import com.darkcollective.relix.ast.OffsetFunction;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RankingFunction;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SortDirection;
import com.darkcollective.relix.ast.SortNode;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.SortSpecification;
import com.darkcollective.relix.ast.NaturalJoinNode;
import com.darkcollective.relix.ast.ThetaJoinNode;
import com.darkcollective.relix.ast.TopKNode;
import com.darkcollective.relix.ast.UniversalNode;
import com.darkcollective.relix.ast.WindowFunction;
import com.darkcollective.relix.ast.WindowNode;
import com.darkcollective.relix.ast.Ordering;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.function.FunctionContext;
import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.source.ConnectionTableSourceConfig;
import com.darkcollective.relix.plan.internal.SqlExpressions.ColumnRenderer;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Schema;

import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.symbol.ScalarType;

import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.ElementOfPredicate;
import com.darkcollective.relix.ast.PatternPredicate;
import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.OrPredicate;
import com.darkcollective.relix.ast.NotPredicate;
import com.darkcollective.relix.ast.NullPredicate;
import com.darkcollective.relix.ast.UnaryOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.SetLiteralOperand;
import com.darkcollective.relix.ast.ConditionOperand;
import com.darkcollective.relix.ast.FunctionCall;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Translates a maximal sub-tree of selection / projection / aggregation / sort /
 * limit (and same-connection inner equi-joins) over a single database connection's
 * tables into one {@link PhysicalNode.PushedScan}, so the work runs in the database
 * instead of the relix engine.
 *
 * <p>Only the compositions that map cleanly onto a flat {@code SELECT … FROM …
 * WHERE … GROUP BY … ORDER BY … LIMIT …} are pushed, and only while the result
 * stays semantically identical — each operator folds in that canonical SQL order:
 * <ul>
 *   <li>a {@link SelectionNode} folds into {@code WHERE} only while no projection,
 *       sort, or limit has been folded yet;</li>
 *   <li>a {@link ProjectionNode} folds into the select list under the same rule;</li>
 *   <li>a {@link DistinctNode} folds into {@code SELECT DISTINCT} — after the select
 *       list is fixed (it deduplicates the projected rows) and before {@code ORDER BY}
 *       / {@code LIMIT}, which apply to the deduplicated result;</li>
 *   <li>an {@link AggregationNode} folds into the select list plus {@code GROUP BY}
 *       (it fixes the select list, like a projection);</li>
 *   <li>a {@link UniversalNode} (∀, group-wise universal quantification) folds into
 *       {@code GROUP BY … HAVING boolAnd(P)} on every dialect (ADR-0005); the
 *       no-key whole-relation form falls back to in-engine;</li>
 *   <li>a {@link SortNode} folds into {@code ORDER BY} only onto a base scan plus
 *       {@code WHERE} (before any projection/aggregation/limit), so every ordering
 *       key is a real table column rather than a select-list alias;</li>
 *   <li>a {@link LimitNode} folds into {@code LIMIT}/{@code OFFSET} while no limit
 *       has been folded yet;</li>
 *   <li>a <em>global</em> {@link TopKNode} — one with no grouping attributes, the form
 *       {@code LIM-003} produces by fusing {@code λ} over {@code τ} — folds into
 *       {@code ORDER BY … LIMIT}, exactly as the {@code λ}/{@code τ} pair it replaced
 *       did.  A partitioned {@code TOP … PER k} does not: SQL needs a window function
 *       or a lateral join for it, so it falls back to in-engine execution;</li>
 *   <li>a {@link ThetaJoinNode} folds into a {@code JOIN … ON} when both inputs are
 *       bare connection-table scans of differently named relations on the
 *       <em>same</em> connection, with a translatable, unambiguous condition. Each side
 *       is named in the statement by an alias the fold chooses, so a dotted
 *       {@code shop.orders} folds as readily as a declared source.</li>
 *   <li>a {@link NaturalJoinNode} folds the same way, into a {@code JOIN … ON} whose
 *       condition equates the columns the two headings share, when those columns have
 *       the same type on both sides.</li>
 * </ul>
 *
 * <p>Anything else — a non-connection leaf, a mixed-connection sub-tree, an
 * untranslatable predicate or expression, or an ordering that would change the
 * meaning — makes {@link #tryPush} return empty, and the planner falls back to
 * in-engine execution (still attempting to push each child).
 */
final class SqlPushdownPlanner implements PushdownRenderer {

    @Override
    public void useFunctionContext(FunctionContext context) {
        this.functions = functions.withContext(context);
    }

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final SchemaAnnotations schemas;
    private final Map<String, SourceDeclaration> sources;
    private final Map<String, ConnectionDeclaration> connections;
    /**
     * The functions the tree was analysed against — the only place a function's SQL
     * spelling comes from. An empty catalogue means no function folds into a pushed
     * query, which is a slower plan and never a wrong one.
     */
    private PushdownFunctions functions;

    SqlPushdownPlanner(SchemaAnnotations schemas,
                       Map<String, SourceDeclaration> sources,
                       Map<String, ConnectionDeclaration> connections,
                       FunctionCatalog functions) {
        this.schemas = schemas;
        this.sources = sources;
        this.connections = connections;
        this.functions = PushdownFunctions.of(Objects.requireNonNull(functions, "functions"));
    }

    @Override
    public Optional<PhysicalNode.PushedScan> tryPush(RelNode node) {
        return build(node).map(SqlPushdownPlanner::toScan);
    }

    /**
     * Pushes {@code node} as a SQL scan whose rows arrive ordered by {@code keys},
     * folding them into an {@code ORDER BY} the same way a {@link SortNode} folds —
     * so the planner can satisfy a merge join's required order from the source rather
     * than sorting in the engine (ADR-0009, Phase C4).
     *
     * <p>Returns empty when {@code node} is not pushable, has already folded a
     * projection / aggregation / sort / limit (so an {@code ORDER BY} can no longer be
     * appended over real table columns), or a key is not a renderable column.
     *
     * @param node the relational sub-tree to push; must not be null
     * @param keys the required ordering keys; must not be null or empty
     * @return the ordered SQL scan, or empty if it cannot be pushed ordered
     */
    @Override
    public Optional<PhysicalNode.PushedScan> tryPushOrdered(RelNode node, List<SortSpecification> keys) {
        Optional<Pushed> built = build(node);
        if (built.isEmpty()) {
            return Optional.empty();
        }
        Pushed p = built.get();
        if (p.distinct || p.projectionApplied || p.sortApplied || p.limitApplied
                || !foldOrderBy(p, keys)) {
            return Optional.empty();
        }
        return Optional.of(toScan(p));
    }

    private static PhysicalNode.PushedScan toScan(Pushed p) {
        return new PhysicalNode.PushedScan(p.schema, p.connectorType, p.connection, p.toSql(),
                Ordering.of(p.deliveredKeys));
    }

    private Optional<Pushed> build(RelNode node) {
        return switch (node) {
            case RelationNode r    -> base(r);
            case SelectionNode s   -> selection(s);
            case ProjectionNode p  -> projection(p);
            case DistinctNode d    -> distinct(d);
            case AggregationNode a -> aggregation(a);
            case UniversalNode u   -> universal(u);
            case SortNode s        -> sort(s);
            case LimitNode l       -> limit(l);
            case TopKNode t        -> topK(t);
            case ThetaJoinNode j   -> join(j);
            case NaturalJoinNode j -> naturalJoin(j);
            case AsOfJoinNode j    -> asOfJoin(j);
            case IntervalJoinNode j -> intervalJoin(j);
            case WindowNode w      -> window(w);
            default                -> Optional.empty();
        };
    }

    private Optional<Pushed> base(RelationNode node) {
        SourceDeclaration source = sources.get(node.name().toLowerCase(Locale.ROOT));
        if (source == null || !(source.config() instanceof ConnectionTableSourceConfig table)) {
            return Optional.empty();
        }
        String connection = table.connection().toLowerCase(Locale.ROOT);
        ConnectionDeclaration declaration = connections.get(connection);
        if (declaration == null) {
            return Optional.empty();
        }
        Dialect dialect = Dialect.of(declaration);
        Schema schema = schemaOf(node);
        List<String> selectList = new ArrayList<>(schema.width());
        for (ColumnDefinition col : schema.columns()) {
            selectList.add(dialect.quote(SqlExpressions.column(col.name())));
        }
        ColumnRenderer renderer = name -> Optional.of(dialect.quote(SqlExpressions.column(name)));
        Pushed pushed = new Pushed(declaration.connectorType(), connection,
                dialect.table(table.table()), selectList, schema, renderer, dialect);
        StringColumns scanStrings = stringColumnsOf(schema);
        pushed.exactStrings = Dialect.comparesStringsExactly(declaration);
        pushed.comparing = comparingRenderer(renderer, scanStrings,
                pushed.exactStrings, dialect, dialect::exactStringComparison);
        pushed.ordersExactly = Dialect.ordersStringsExactly(declaration);
        pushed.ordering = comparingRenderer(renderer, scanStrings,
                pushed.ordersExactly, dialect, dialect::exactStringOrder);
        pushed.bareScan = true;
        pushed.relationName = node.name();
        pushed.tableName = table.table();
        pushed.baseColumns = schema.columns();
        return Optional.of(pushed);
    }

    private Optional<Pushed> selection(SelectionNode node) {
        Optional<Pushed> input = build(node.input());
        if (input.isEmpty()) {
            return Optional.empty();
        }
        Pushed p = input.get();
        // WHERE must precede the select list, ORDER BY, LIMIT, and window OVER expressions
        // (a predicate referencing a window result needs a subquery, not a WHERE clause).
        if (p.projectionApplied || p.sortApplied || p.limitApplied || p.windowApplied) {
            return Optional.empty();
        }
        StringColumns strings = stringColumnsOf(p.schema);
        // A HAVING cannot name a collated grouping key at all: MySQL resolves a
        // HAVING's names against the select list and the grouped columns, and rejects a
        // base column appearing inside an expression there — which is what a collated
        // key is. Referencing the select-list alias instead would work on MySQL and fail
        // on Postgres, which does not admit an alias in HAVING, so the narrow case
        // declines. `SEL-007` pushes this shape below the γ into a WHERE anyway, where
        // it folds like any other predicate.
        if (!p.exactStrings && p.aggregated && referencesString(node.predicate(), strings)) {
            return Optional.empty();
        }
        // Rendered through the comparison renderer: on a backend whose collation is not
        // exact, a string column in a predicate becomes CONVERT(…) COLLATE …, so `=`,
        // IN and LIKE mean what they mean in the engine.
        Optional<String> sql = SqlExpressions.predicate(node.predicate(), p.comparing, p.ordering,
                p.dialect, functions);
        if (sql.isEmpty()) {
            return Optional.empty();
        }
        // Above a GROUP BY the same σ is a HAVING: it selects groups rather than rows,
        // and its references are the aggregation's outputs, which p.renderer already
        // resolves to the expressions that produced them. Three-valued exactly as WHERE
        // is, so the predicate needs no different rendering — only a different clause.
        if (p.aggregated) {
            p.having = p.having == null ? sql.get() : p.having + " AND " + sql.get();
        } else {
            p.where.add(sql.get());
        }
        p.bareScan = false;
        p.schema = schemaOf(node);     // unchanged by selection, but keep annotations authoritative
        return Optional.of(p);
    }

    private Optional<Pushed> projection(ProjectionNode node) {
        Optional<Pushed> input = build(node.input());
        if (input.isEmpty()) {
            return Optional.empty();
        }
        Pushed p = input.get();
        // A π above a GROUP BY would have to rewrite the select list the aggregation
        // fixed, which is a different rewrite from the ones a HAVING / ORDER BY / LIMIT
        // above one are — so it still declines and the projection happens in the engine.
        //
        // A window fixes the select list for the same reason and was missing from this
        // list, though not from the one next to it: the OVER expression *is* a select
        // item, so replacing the list below drops it while the projection above still
        // names the column it was to have produced. That rendered
        // `SELECT oid, g1 FROM orders` for a ROLLING aliased g1 — SQL naming a column
        // nothing computes, which the database refuses while the engine returns rows.
        if (p.aggregated || p.projectionApplied || p.sortApplied || p.limitApplied
                || p.windowApplied) {
            return Optional.empty();
        }
        List<String> selectList = new ArrayList<>(node.attributes().size());
        for (ProjectedAttribute attr : node.attributes()) {
            Optional<String> sql = SqlExpressions.operand(attr.expression(), p.renderer, p.dialect, functions);
            if (sql.isEmpty()) {
                return Optional.empty();
            }
            selectList.add(sql.get());
        }
        p.selectList = selectList;
        p.schema = schemaOf(node);
        // A *column-pruning* π introduces no new name — it only drops columns — so an
        // operator above it still names real table columns and its WHERE / GROUP BY /
        // ORDER BY can still be folded on top of this narrowed select list, and a join
        // above it can still treat the side as a table scan (of fewer columns). Only a
        // computed or aliased projection genuinely fixes the select list and stops
        // being a scan. Without this, the optimizer's PROJ-004 would trade a whole
        // pushed query for a narrower one that no longer pushes at all.
        boolean pruning = node.isColumnPruning();
        p.projectionApplied = !pruning;
        if (pruning && p.bareScan) {
            // Still a scan of `tableName AS alias`, now of a narrower column list — so a
            // join fold above renders `alias`.`col` for exactly the surviving columns.
            p.baseColumns = p.schema.columns();
        } else {
            p.bareScan = false;
        }
        return Optional.of(p);
    }

    /**
     * Folds a {@code δ} into {@code SELECT DISTINCT} — a rendering every dialect spells
     * the same way, so there is no {@link Dialect} branch here.
     *
     * <p>{@code DISTINCT} applies to the <em>projected</em> select list, so it folds after
     * a π (unlike a {@code WHERE}, which must precede one) and before {@code ORDER BY} /
     * {@code LIMIT}, which apply to the deduplicated result.
     *
     * <p>A δ over a {@code GROUP BY} is not rendered at all: γ already emits one row per
     * key, so the δ is redundant — {@code DIST-001} removes it, and if one survives (an
     * un-optimized tree, say) this bails rather than emit both.  The planner then falls
     * back, pushes the γ on its own, and keeps an in-engine {@code Distinct} above it.
     *
     * <p>A {@code τ} above the δ still folds, and the {@code ORDER BY} it produces names
     * a select-list column as {@code SELECT DISTINCT} requires: the sort key is a column
     * of the δ's output schema, and the only π that can sit below without setting
     * {@code projectionApplied} (which stops {@link #sort} outright) is a column-pruning
     * one, whose select list <em>is</em> that schema.
     *
     * <p>What it will not do is advertise a delivered ordering to
     * {@link #tryPushOrdered}: the deduplication is unordered, and a merge join asking a
     * source for sorted rows would be told a scan is sorted when the {@code ORDER BY}
     * could not be appended over real table columns anyway.
     */
    private Optional<Pushed> distinct(DistinctNode node) {
        Optional<Pushed> input = build(node.input());
        if (input.isEmpty()) {
            return Optional.empty();
        }
        Pushed p = input.get();
        if (p.sortApplied || p.limitApplied || !p.groupBy.isEmpty()) {
            return Optional.empty();
        }
        // SELECT DISTINCT compares every output column to every other row's, so on a
        // backend whose collation is not exact the select list itself is what has to be
        // rendered in comparison position — there is nowhere else for the wrapping to
        // go, DISTINCT having no clause of its own.
        p.selectList = comparedSelectList(p);
        // Idempotent: a second δ over an already-`SELECT DISTINCT` query is a no-op,
        // whatever sits between them — deduplicating what is already deduplicated, or
        // deduplicating a projection of it, produces the same rows either way.
        p.distinct = true;
        // `SELECT DISTINCT …` is no longer a bare table scan, so a join above it cannot
        // fold this side as `table AS alias`.
        p.bareScan = false;
        p.schema = schemaOf(node);
        return Optional.of(p);
    }

    private Optional<Pushed> aggregation(AggregationNode node) {
        Optional<Pushed> input = build(node.input());
        if (input.isEmpty()) {
            return Optional.empty();
        }
        Pushed p = input.get();
        // A window belongs here for a reason the others do not share: SQL computes window
        // functions *after* GROUP BY, so a GROUP BY naming one is not merely a different
        // rewrite — it is not expressible at all.
        if (p.aggregated || p.projectionApplied || p.sortApplied || p.limitApplied
                || p.windowApplied) {
            return Optional.empty();   // GROUP BY fixes the select list, like a projection
        }
        List<String> selectList = new ArrayList<>();
        List<String> groupBy = new ArrayList<>();
        // What each of this γ's output columns is, as SQL — the map an operator above it
        // resolves its own references through.
        Map<String, String> outputs = new LinkedHashMap<>();
        for (GroupingKey key : node.groupingKeys()) {
            // Only a bare, unaliased column pushes down; a derived key (e.g.
            // YEAR(ts)) or a renamed key falls back to in-engine aggregation.
            // In comparison position: a GROUP BY compares its keys, and selecting the
            // same expression is what ONLY_FULL_GROUP_BY asks of a grouped select list.
            Optional<String> col = key.isPlainColumn()
                    ? p.comparing.render(key.columnName().get())
                    : Optional.empty();
            if (col.isEmpty()) {
                return Optional.empty();
            }
            selectList.add(col.get());
            groupBy.add(col.get());
            outputs.put(outputKey(key.outputName()), col.get());
        }
        for (AggregateFunction aggregate : node.aggregates()) {
            // MIN and MAX order their argument, so they render it in comparison
            // position exactly as an ORDER BY key does. COUNT, SUM and AVG reduce
            // without asking which of two values is larger, and are left alone — which
            // keeps the collation out of SQL that has no use for it.
            ColumnRenderer cols = ORDERING_AGGREGATES.contains(aggregate.operator())
                    ? p.ordering : p.renderer;
            Optional<String> expr = aggregateSql(aggregate, cols);
            if (expr.isEmpty()) {
                return Optional.empty();
            }
            selectList.add(expr.get());
            outputs.put(outputKey(aggregate.outputName()), expr.get());
        }
        p.selectList = selectList;
        p.groupBy = groupBy;
        p.renderer = aggregateOutputRenderer(outputs);
        // And the comparison renderer with it — above a γ the two are the same, because
        // an output already *is* the expression that produced it, and a string grouping
        // key was rendered in comparison position when the GROUP BY was built. Leaving
        // this pointing at the scan below would resolve a HAVING's `total` against the
        // table, where no such column exists.
        p.comparing = p.renderer;
        // The ordering renderer rebinds with them, and for the same reason. A string
        // grouping key was already collated when the GROUP BY was built, and an
        // aggregate's output is the expression that produced it — a MIN or MAX among
        // them having been rendered in ordering position there.
        p.ordering = p.renderer;
        p.aggregated = true;
        p.bareScan = false;
        p.schema = schemaOf(node);
        return Optional.of(p);
    }

    /**
     * Resolves a reference above a {@code GROUP BY} to the select-list expression that
     * produced it — {@code total} to {@code SUM(`amount`)}, {@code region} to
     * {@code `region`} — and declines any name the aggregation does not output.
     *
     * <h2>Why the expression rather than an alias</h2>
     * Because both clauses that use this want one. Standard SQL does not let a
     * {@code HAVING} clause name a select-list alias at all, and while {@code ORDER BY}
     * does, it accepts one only as a bare output-column reference — not inside an
     * expression, which is exactly where {@link Dialect#orderByTerms} puts it to place
     * NULLs where the engine does. Repeating the expression is legal in both clauses on
     * every dialect, so it is one answer rather than two and a dialect branch between
     * them.
     */
    private static ColumnRenderer aggregateOutputRenderer(Map<String, String> outputs) {
        return name -> Optional.ofNullable(outputs.get(outputKey(name)));
    }

    // ── Strings, and whether the backend compares them as the engine does ─────────

    /**
     * Whether a column name is one the backend would compare under a collation.
     *
     * <p>A resolver rather than a {@link Schema} because a join has two of them, and
     * because the same name may appear on both sides — which a merged schema cannot
     * hold and does not need to, since the answer is the same either way.
     */
    @FunctionalInterface
    private interface StringColumns {
        boolean includes(String attribute);

        /** Nothing is a string — for a context that has already established as much. */
        StringColumns NONE = attribute -> false;
    }

    /**
     * The string-typed columns of {@code schema}. An unknown name counts as one: it is a
     * name this schema cannot vouch for, and the safe reading of "might be" is the one
     * that declines.
     *
     * <p>{@code ANY} counts too. A schema-on-read column has no declared type and may
     * hold a string.
     */
    private static StringColumns stringColumnsOf(Schema schema) {
        return attribute -> schema.column(SqlExpressions.column(attribute))
                .map(SqlPushdownPlanner::comparesAsString)
                .orElse(true);
    }

    /**
     * The same over plain column lists — a join's two sides, taken together.
     *
     * <p>Together rather than one resolver per side combined with {@code ||}: a name
     * belongs to one side, so asking the other yields "I have never heard of it", which
     * this resolver has to read as "string" and which would then make every join
     * condition look like a string comparison. Pooling the columns asks the side that
     * actually holds the name. A name held by both is a string if either is, which is
     * the conservative reading and the only one that could matter.
     */
    @SafeVarargs
    private static StringColumns stringColumnsOf(List<ColumnDefinition>... sides) {
        List<ColumnDefinition> pooled = Stream.of(sides).flatMap(List::stream).toList();
        return attribute -> {
            String name = SqlExpressions.column(attribute);
            List<ColumnDefinition> matches = pooled.stream()
                    .filter(c -> c.name().equalsIgnoreCase(name))
                    .toList();
            return matches.isEmpty()
                    || matches.stream().anyMatch(SqlPushdownPlanner::comparesAsString);
        };
    }

    /** Whether a column's declared type is one the backend would compare under a collation. */
    private static boolean comparesAsString(ColumnDefinition column) {
        return column.type() instanceof ScalarType scalar && comparesAsString(scalar);
    }

    /**
     * Whether a type is one a collation has an opinion about. {@code ANY} counts: a
     * schema-on-read column has no declared type and may hold a string, and the safe
     * reading of "might be" is the one that declines.
     */
    private static boolean comparesAsString(ScalarType type) {
        return type == ScalarType.STRING || type == ScalarType.ANY;
    }

    /**
     * {@code p}'s select list with each string-typed entry rendered in comparison
     * position — what {@code SELECT DISTINCT} needs, since the expressions it selects
     * are the expressions it deduplicates on.
     *
     * <p>Wrapped by position rather than by name: a select list is built in its
     * schema's order by every arm that sets one, so the n-th entry is the n-th column
     * and no lookup is needed. Wrapping the expression is right whatever produced it —
     * a bare column, a projection, a call — because what is being made exact is the
     * comparison of the values, not the reading of a column.
     */
    private static List<String> comparedSelectList(Pushed p) {
        if (p.exactStrings) {
            return p.selectList;
        }
        List<ColumnDefinition> columns = p.schema.columns();
        List<String> compared = new ArrayList<>(p.selectList.size());
        for (int i = 0; i < p.selectList.size(); i++) {
            String sql = p.selectList.get(i);
            boolean string = i >= columns.size() || comparesAsString(columns.get(i));
            compared.add(string ? p.dialect.exactStringComparison(sql) : sql);
        }
        return compared;
    }

    /**
     * A renderer for <em>comparison</em> position: like {@code plain}, except that a
     * string-typed reference is wrapped so the backend compares it exactly.
     *
     * <p>Two renderers rather than one because the wrapping belongs only where a
     * comparison happens. A select list that merely returns a column has nothing to
     * compare, and wrapping it there would be noise in every pushed query — with one
     * exception the callers handle: a {@code GROUP BY} and a {@code DISTINCT} compare
     * the very expressions they select, so those render their select list through this
     * one too.
     *
     * @param plain    how a reference renders normally
     * @param strings  which names are string-typed
     * @param exact    whether the backend already answers this position's question exactly
     * @param dialect  the target dialect
     * @param wrap     the dialect's wrapping for this position — equality, or ordering
     * @return the renderer for that position, which is {@code plain} when nothing needs wrapping
     */
    private static ColumnRenderer comparingRenderer(ColumnRenderer plain, StringColumns strings,
                                                    boolean exact, Dialect dialect,
                                                    java.util.function.UnaryOperator<String> wrap) {
        if (exact) {
            return plain;
        }
        return attribute -> plain.render(attribute)
                .map(sql -> strings.includes(attribute) ? wrap.apply(sql) : sql);
    }

    /** Whether {@code predicate} names any string-typed column. */
    private static boolean referencesString(Predicate predicate, StringColumns strings) {
        Set<String> names = new LinkedHashSet<>();
        collectAttributes(predicate, names);
        return names.stream().anyMatch(strings::includes);
    }

    /** Every attribute name {@code predicate} references. */
    private static void collectAttributes(Predicate predicate, Set<String> into) {
        switch (predicate) {
            case ComparisonPredicate c -> {
                collectAttributes(c.left(), into);
                collectAttributes(c.right(), into);
            }
            case ElementOfPredicate e -> {
                collectAttributes(e.element(), into);
                collectAttributes(e.setExpression(), into);
            }
            case PatternPredicate t -> collectAttributes(t.operand(), into);
            case AndPredicate a -> {
                collectAttributes(a.left(), into);
                collectAttributes(a.right(), into);
            }
            case OrPredicate o -> {
                collectAttributes(o.left(), into);
                collectAttributes(o.right(), into);
            }
            case NotPredicate n -> collectAttributes(n.predicate(), into);
            case NullPredicate n -> collectAttributes(n.operand(), into);
        }
    }

    private static void collectAttributes(Operand operand, Set<String> into) {
        switch (operand) {
            case AttributeOperand a -> into.add(a.name());
            case UnaryOperand u -> collectAttributes(u.operand(), into);
            case BinaryArithmeticExpression e -> {
                collectAttributes(e.left(), into);
                collectAttributes(e.right(), into);
            }
            case SetLiteralOperand set -> set.elements().forEach(e -> collectAttributes(e, into));
            case FunctionCall f -> f.arguments().forEach(a -> collectAttributes(a, into));
            case ConditionOperand c -> collectAttributes(c.predicate(), into);
            default -> { }
        }
    }

    /** The aggregates whose answer depends on how two values compare. */
    private static final Set<AggregateOperator> ORDERING_AGGREGATES =
            Set.of(AggregateOperator.MIN, AggregateOperator.MAX);

    /** An output column's map key: unqualified, and case-insensitive as relix names are. */
    private static String outputKey(String name) {
        return SqlExpressions.column(name).toLowerCase(Locale.ROOT);
    }

    private Optional<Pushed> universal(UniversalNode node) {
        // No-key (whole-relation) ∀ has no natural SQL form; fall back to in-engine.
        if (node.groupingAttributes().isEmpty()) {
            return Optional.empty();
        }
        Optional<Pushed> input = build(node.input());
        if (input.isEmpty()) {
            return Optional.empty();
        }
        Pushed p = input.get();
        // GROUP BY fixes the select list, like a projection; cannot fold over an
        // already-projected, sorted, limited or grouped sub-tree. Nor over a window,
        // which SQL computes after both GROUP BY and HAVING — ∀ renders as both.
        if (p.aggregated || p.projectionApplied || p.sortApplied || p.limitApplied
                || p.windowApplied) {
            return Optional.empty();
        }
        Optional<String> predSql = SqlExpressions.predicate(node.predicate(), p.comparing, p.dialect, functions);
        if (predSql.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> havingExpr = p.dialect.boolAnd(predSql.get());
        if (havingExpr.isEmpty()) {
            return Optional.empty();   // dialect has no supported spelling (e.g. MySQL)
        }
        List<String> selectList = new ArrayList<>();
        List<String> groupBy = new ArrayList<>();
        for (String attribute : node.groupingAttributes()) {
            // A GROUP BY compares each key to every other row's, so the key is rendered
            // in comparison position — and selected as the same expression, which is
            // also what MySQL's ONLY_FULL_GROUP_BY requires of a grouped select list.
            Optional<String> col = p.comparing.render(attribute);
            if (col.isEmpty()) {
                return Optional.empty();
            }
            selectList.add(col.get());
            groupBy.add(col.get());
        }
        p.selectList = selectList;
        p.groupBy = groupBy;
        p.having = havingExpr.get();
        p.projectionApplied = true;
        p.bareScan = false;
        p.schema = schemaOf(node);
        return Optional.of(p);
    }

    /**
     * Renders {@code SUM(price * qty)} and its kin, by asking the aggregate how it is
     * spelled — the same seam a scalar call goes through.
     *
     * <p>Declining is safe and covers two cases without naming either: an argument with
     * no SQL form, and an aggregate with no SQL form at all. {@code COLLECT} produces a
     * nested value and {@code ARGMAX}/{@code ARGMIN} are what SQL needs a window
     * function for, so none of the three supplies a spelling and all three fall back to
     * in-engine aggregation.
     */
    private Optional<String> aggregateSql(AggregateFunction aggregate, ColumnRenderer cols) {
        return aggregateSql(aggregate.operator(), aggregate.argument(), cols, Dialect.GENERIC);
    }

    /** As above, for a given dialect — the window form renders the same call. */
    private Optional<String> aggregateSql(AggregateOperator operator, Operand argument,
                                          ColumnRenderer cols, Dialect dialect) {
        Optional<String> rendered = SqlExpressions.operand(argument, cols, dialect, functions);
        if (rendered.isEmpty()) {
            return Optional.empty();
        }
        return functions.aggregate(operator.name())
                .flatMap(reduction -> reduction.pushdown()
                        .render(dialect.pushdownTarget(), List.of(rendered.get())));
    }

    private Optional<Pushed> window(WindowNode node) {
        Optional<Pushed> input = build(node.input());
        if (input.isEmpty()) {
            return Optional.empty();
        }
        Pushed p = input.get();
        // Cannot fold a window over an already-grouped, already-projected or limited
        // scan: the window expression references real table columns that are no longer
        // in scope.
        //
        // Nor over a prior window, which this said was fine and is not. A second window
        // may order or partition by the first one's alias, and that alias is not a table
        // column — the SQL names something the database cannot resolve. Declining every
        // window over a window is broader than the defect: two windows that both key on
        // real columns are ordinary SQL and are now computed here instead. That is a
        // slower plan rather than a wrong answer, and narrowing it means teaching the
        // column renderer to refuse a name no table has, which is its own piece of work.
        //
        // A sort is here for the mirror-image reason, and it is the clock that decides
        // both: SQL evaluates ORDER BY *after* the window, so a folded τ beneath one
        // orders the output while the engine's τ orders the window's input. Between them
        // these two conditions say the whole rule — a window folds onto a bare scan and a
        // WHERE, and nothing folds on top of a window.
        if (p.aggregated || p.projectionApplied || p.limitApplied
                || p.sortApplied || p.distinct) {
            return Optional.empty();
        }
        // Two windows in one select list is ordinary SQL, so long as the second keys on
        // real columns. Keying on the first one's output is not: that alias is not a
        // table column, and PARTITION BY / ORDER BY inside an OVER cannot resolve one.
        if (namesAWindowAlias(p, node.partitionKeys(), node.sortSpecs())) {
            return Optional.empty();
        }
        if (!p.dialect.supportsWindowFunctions()) {
            return Optional.empty();
        }
        // Render the function call portion (e.g. "AVG(price)", "RANK()", "LAG(salary, 1)").
        Optional<String> fnSql = windowFunctionCall(node.function(), p.renderer, p.dialect);
        if (fnSql.isEmpty()) {
            return Optional.empty();
        }
        // Render partition keys.
        List<String> partitionCols = new ArrayList<>(node.partitionKeys().size());
        for (String key : node.partitionKeys()) {
            Optional<String> col = p.renderer.render(key);
            if (col.isEmpty()) {
                return Optional.empty();
            }
            partitionCols.add(col.get());
        }
        // Render sort specs.
        List<String> orderExprs = new ArrayList<>(node.sortSpecs().size());
        for (SortSpecification spec : node.sortSpecs()) {
            Optional<String> col = spec.columnName().flatMap(p.renderer::render);
            if (col.isEmpty()) {
                return Optional.empty();
            }
            orderExprs.addAll(p.dialect.orderByTerms(
                    col.get(), spec.direction() == SortDirection.DESC));
        }
        String overClause = p.dialect.windowOverClause(partitionCols, orderExprs, node.frame());
        String windowExpr = fnSql.get() + overClause + " AS " + p.dialect.quote(node.outputColumn());
        List<String> newSelectList = new ArrayList<>(p.selectList);
        newSelectList.add(windowExpr);
        p.selectList = newSelectList;
        p.windowApplied = true;
        p.windowAliases.add(node.outputColumn());
        p.bareScan = false;
        p.schema = schemaOf(node);
        return Optional.of(p);
    }

    /**
     * Whether any of these keys names a column a folded window produced.
     *
     * <p>A window's output is a select-list alias rather than a column of the table, so an
     * {@code ORDER BY}, {@code PARTITION BY} or {@code GROUP BY} naming one is asking the
     * database to resolve a name at a point in the statement where it does not yet exist.
     * The renderer emits the bare name regardless, which is how {@code SELECT …, rk} over
     * a dropped {@code OVER} item reached a database at all.
     */
    private static boolean namesAWindowAlias(Pushed p, List<String> keys,
                                             List<SortSpecification> sortSpecs) {
        if (p.windowAliases.isEmpty()) {
            return false;
        }
        if (keys.stream().anyMatch(p.windowAliases::contains)) {
            return true;
        }
        return sortSpecs.stream()
                .map(SortSpecification::columnName)
                .flatMap(Optional::stream)
                .anyMatch(p.windowAliases::contains);
    }

    private Optional<String> windowFunctionCall(WindowFunction fn, ColumnRenderer cols, Dialect dialect) {
        return switch (fn) {
            // An aggregate over a window is the same call as one over a group, so it
            // reads the same spelling; COLLECT and ARGMAX/ARGMIN supply none and fall back.
            case WindowFunction.AggregateWindow a ->
                    aggregateSql(a.operator(), a.argument(), cols, dialect);
            case WindowFunction.RankingWindow r -> {
                if (r.function() == RankingFunction.NTILE) {
                    if (r.ntileCount().isEmpty()) {
                        yield Optional.empty();
                    }
                    Optional<String> count = SqlExpressions.operand(r.ntileCount().get(), cols, dialect, functions);
                    yield count.map(c -> "NTILE(" + c + ")");
                }
                String name = switch (r.function()) {
                    case ROW_NUMBER  -> "ROW_NUMBER";
                    case RANK        -> "RANK";
                    case DENSE_RANK  -> "DENSE_RANK";
                    case PERCENT_RANK -> "PERCENT_RANK";
                    case NTILE       -> "NTILE"; // handled above
                };
                yield Optional.of(name + "()");
            }
            case WindowFunction.OffsetWindow o -> {
                Optional<String> expr = SqlExpressions.operand(o.expression(), cols, dialect, functions);
                if (expr.isEmpty()) {
                    yield Optional.empty();
                }
                String name = switch (o.function()) {
                    case LAG         -> "LAG";
                    case LEAD        -> "LEAD";
                    case FIRST_VALUE -> "FIRST_VALUE";
                    case LAST_VALUE  -> "LAST_VALUE";
                };
                StringBuilder args = new StringBuilder(expr.get());
                if (o.offset().isPresent()) {
                    Optional<String> offset = SqlExpressions.operand(o.offset().get(), cols, dialect, functions);
                    if (offset.isEmpty()) {
                        yield Optional.empty();
                    }
                    args.append(", ").append(offset.get());
                    if (o.defaultValue().isPresent()) {
                        Optional<String> def = SqlExpressions.operand(o.defaultValue().get(), cols, dialect, functions);
                        if (def.isEmpty()) {
                            yield Optional.empty();
                        }
                        args.append(", ").append(def.get());
                    }
                }
                yield Optional.of(name + "(" + args + ")");
            }
        };
    }

    private Optional<Pushed> sort(SortNode node) {
        Optional<Pushed> input = build(node.input());
        if (input.isEmpty()) {
            return Optional.empty();
        }
        Pushed p = input.get();
        // ORDER BY must precede LIMIT and be the only sort; fold only onto base+WHERE so
        // every ordering key is a real table column (not a select-list alias we don't emit).
        // A window is in this list for the reason the comment above gives and then some:
        // its alias is not a table column, and a backend need not resolve a select-list
        // alias in ORDER BY at all — H2 refuses one naming a window.
        if (p.projectionApplied || p.sortApplied || p.limitApplied
                || namesAWindowAlias(p, List.of(), node.sortSpecs())
                || !foldOrderBy(p, node.sortSpecs())) {
            return Optional.empty();
        }
        p.bareScan = false;
        p.schema = schemaOf(node);
        return Optional.of(p);
    }

    /**
     * Folds {@code keys} into {@code p}'s {@code ORDER BY} clause and records them as
     * its delivered ordering.  Returns {@code false} (leaving {@code p} unchanged)
     * when any key is not a column the renderer can resolve.
     */
    private static boolean foldOrderBy(Pushed p, List<SortSpecification> keys) {
        // One shape declines: ordering by a grouping key that had to be collated.
        //
        // MySQL matches an ORDER BY term against the GROUP BY expression syntactically,
        // and NULL placement wraps the key — `(expr IS NULL) ASC, expr ASC` — so the
        // first term is an expression *containing* the grouping expression rather than
        // being it, and ONLY_FULL_GROUP_BY rejects the query. It accepts the same shape
        // over a bare column, which is why this bites only where the key was wrapped.
        // Naming the select-list alias instead would work there and fail on Postgres,
        // which admits an alias in ORDER BY but not inside an expression — the same bind
        // the HAVING fold hit, and the same answer.
        if (p.aggregated && !p.exactStrings) {
            StringColumns strings = stringColumnsOf(p.schema);
            boolean collatedKey = keys.stream()
                    .map(SortSpecification::columnName)
                    .flatMap(Optional::stream)
                    .anyMatch(strings::includes);
            if (collatedKey) {
                return false;
            }
        }
        List<String> orderBy = new ArrayList<>(keys.size());
        for (SortSpecification spec : keys) {
            // In comparison position: an ORDER BY compares each key to every other
            // row's, so a string key is ordered under an explicit exact collation where
            // the backend's own is not one. The two then agree because the engine orders
            // strings by code point, which is the order a binary collation uses and the
            // order UTF-8 bytes already sort in.
            Optional<String> col = spec.columnName().flatMap(p.ordering::render);
            if (col.isEmpty()) {
                return false;
            }
            orderBy.addAll(p.dialect.orderByTerms(
                    col.get(), spec.direction() == SortDirection.DESC));
        }
        p.orderBy = orderBy;
        p.deliveredKeys = keys;
        p.sortApplied = true;
        return true;
    }

    private Optional<Pushed> limit(LimitNode node) {
        Optional<Pushed> input = build(node.input());
        if (input.isEmpty()) {
            return Optional.empty();
        }
        Pushed p = input.get();
        // A LIMIT over a window takes *some* rows where the engine takes the first ones
        // its own scan produced, and the two need not be the same rows — a fold that
        // changes which rows come back, not merely their order.
        if (p.limitApplied || p.windowApplied) {
            return Optional.empty();
        }
        // A limit with nothing ordering it is legal SQL almost everywhere, and not where
        // the row limiting is OFFSET … FETCH, which SQL Server accepts only after an
        // ORDER BY. Declining leaves the λ in the engine over whatever else folded.
        if (p.dialect.limitNeedsOrderBy() && p.orderBy.isEmpty()) {
            return Optional.empty();
        }
        p.limit = node.count();
        p.offset = node.offset().orElse(0L);
        p.limitApplied = true;
        p.bareScan = false;
        p.schema = schemaOf(node);
        return Optional.of(p);
    }

    /**
     * Folds a <em>global</em> top-k — {@code TopKNode} with no grouping attributes —
     * into {@code ORDER BY … LIMIT}.  That node is what {@code LIM-003} produces from
     * a {@code λ} over a {@code τ}, and it means exactly what the pair meant, so
     * without this arm the top-N rewrite would <em>cost</em> the pushdown it should
     * have kept.  A partitioned {@code TOP … PER k} is not expressible as a plain
     * {@code LIMIT} and returns empty.
     */
    private Optional<Pushed> topK(TopKNode node) {
        if (!node.groupingAttributes().isEmpty()) {
            return Optional.empty();
        }
        Optional<Pushed> input = build(node.input());
        if (input.isEmpty()) {
            return Optional.empty();
        }
        Pushed p = input.get();
        // Same constraint as sort(): ORDER BY must precede LIMIT and be folded onto
        // base+WHERE, so every ordering key is a real table column.
        if (p.projectionApplied || p.sortApplied || p.limitApplied
                || namesAWindowAlias(p, node.groupingAttributes(), node.sortSpecs())
                || !foldOrderBy(p, node.sortSpecs())) {
            return Optional.empty();
        }
        p.limit = node.count();
        p.offset = node.offset().orElse(0L);
        p.limitApplied = true;
        p.bareScan = false;
        p.schema = schemaOf(node);
        return Optional.of(p);
    }

    private Optional<Pushed> join(ThetaJoinNode node) {
        // A σ between the join and either scan is peeled off and re-rendered into the
        // joined statement's WHERE: an inner join commutes with a filter on either side.
        Peeled leftPeel = peelFilters(node.left());
        Peeled rightPeel = peelFilters(node.right());
        Optional<Pushed> leftOpt = build(leftPeel.scan());
        Optional<Pushed> rightOpt = build(rightPeel.scan());
        if (leftOpt.isEmpty() || rightOpt.isEmpty()) {
            return Optional.empty();
        }
        Pushed left = leftOpt.get();
        Pushed right = rightOpt.get();
        // Conservative: only join two bare scans of distinct relations on the same
        // connection.  Anything else falls back to an in-engine join.
        if (!bareJoinable(left, right)) {
            return Optional.empty();
        }

        Dialect dialect = left.dialect;   // both sides share the connection, hence the dialect
        JoinAliases aliases = JoinAliases.of(left, right);
        ColumnRenderer renderer = joinRenderer(left, right, aliases, dialect);
        // A join condition compares the two sides' columns to each other, so it renders
        // in comparison position. Both sides share the connection, hence one answer.
        StringColumns joinStrings = stringColumnsOf(left.baseColumns, right.baseColumns);
        ColumnRenderer comparing = comparingRenderer(renderer, joinStrings,
                left.exactStrings, dialect, dialect::exactStringComparison);
        ColumnRenderer ordering = comparingRenderer(renderer, joinStrings,
                left.ordersExactly, dialect, dialect::exactStringOrder);

        Optional<String> condition = SqlExpressions.predicate(node.condition(), comparing, ordering,
                dialect, functions);
        if (condition.isEmpty()) {
            return Optional.empty();
        }

        List<String> selectList = new ArrayList<>();
        selectList.addAll(qualifiedColumns(dialect, aliases.left(), left.baseColumns));
        selectList.addAll(qualifiedColumns(dialect, aliases.right(), right.baseColumns));

        String from = dialect.table(left.tableName) + " " + dialect.quote(aliases.left())
                + " JOIN " + dialect.table(right.tableName) + " " + dialect.quote(aliases.right())
                + " ON " + condition.get();
        List<Predicate> peeled = new ArrayList<>(leftPeel.filters());
        peeled.addAll(rightPeel.filters());
        Optional<List<String>> filters = renderPeeled(peeled, comparing, ordering, dialect);
        if (filters.isEmpty()) {
            return Optional.empty();
        }

        Pushed joined = new Pushed(left.connectorType, left.connection, from, selectList,
                schemaOf(node), renderer, dialect);
        joined.where.addAll(filters.get());
        // The collation answers belong to the connection, and both sides share one, so
        // they carry over — without which a σ or a τ folded *above* the join would
        // render its strings uncollated where the join's own condition did not.
        joined.exactStrings = left.exactStrings;
        joined.comparing = comparing;
        joined.ordersExactly = left.ordersExactly;
        joined.ordering = ordering;
        return Optional.of(joined);
    }

    /**
     * Folds a natural join over two bare connection-table scans into a
     * {@code JOIN … ON l.c = r.c AND …} over the columns the two headings share.
     *
     * <p>The condition is written out rather than left to SQL's {@code NATURAL JOIN},
     * which decides the shared columns from the database's tables, not from the headings
     * the engine joined on, and matches their names by the database's own case rules.
     * The select list follows the join's inferred heading, where each shared column
     * appears once and holds the left side's value.
     *
     * <p>Declines when the headings share no column, which analysis already refuses, and
     * when a shared column has a different type on each side, because the engine's
     * equality then follows its own coercions (an ISO string equals the date it spells)
     * and a database's follows others.
     */
    private Optional<Pushed> naturalJoin(NaturalJoinNode node) {
        Peeled leftPeel = peelFilters(node.left());
        Peeled rightPeel = peelFilters(node.right());
        Optional<Pushed> leftOpt = build(leftPeel.scan());
        Optional<Pushed> rightOpt = build(rightPeel.scan());
        if (leftOpt.isEmpty() || rightOpt.isEmpty()) {
            return Optional.empty();
        }
        Pushed left = leftOpt.get();
        Pushed right = rightOpt.get();
        if (!bareJoinable(left, right)) {
            return Optional.empty();
        }
        List<String> shared = new ArrayList<>();
        for (ColumnDefinition lc : left.baseColumns) {
            for (ColumnDefinition rc : right.baseColumns) {
                if (lc.name().equalsIgnoreCase(rc.name())) {
                    if (!lc.type().equals(rc.type())) {
                        return Optional.empty();
                    }
                    shared.add(lc.name());
                }
            }
        }
        if (shared.isEmpty()) {
            return Optional.empty();
        }

        Dialect dialect = left.dialect;   // both sides share the connection, hence the dialect
        JoinAliases aliases = JoinAliases.of(left, right);
        ColumnRenderer renderer = naturalJoinRenderer(left, right, shared, aliases, dialect);
        StringColumns joinStrings = stringColumnsOf(left.baseColumns, right.baseColumns);
        List<String> condition = new ArrayList<>(shared.size());
        for (String column : shared) {
            String l = qualified(dialect, aliases.left(), column);
            String r = qualified(dialect, aliases.right(), column);
            if (!left.exactStrings && joinStrings.includes(column)) {
                l = dialect.exactStringComparison(l);
                r = dialect.exactStringComparison(r);
            }
            condition.add("(" + l + " = " + r + ")");
        }

        // The select list is read off the join's own heading, so it cannot disagree with
        // it: a column the left side has comes from the left, any other from the right.
        List<String> leftColumns = columnNames(left.baseColumns);
        Schema schema = schemaOf(node);
        List<String> selectList = new ArrayList<>(schema.width());
        for (ColumnDefinition col : schema.columns()) {
            String alias = containsIgnoreCase(leftColumns, col.name()) ? aliases.left() : aliases.right();
            selectList.add(qualified(dialect, alias, col.name()));
        }

        String from = dialect.table(left.tableName) + " " + dialect.quote(aliases.left())
                + " JOIN " + dialect.table(right.tableName) + " " + dialect.quote(aliases.right())
                + " ON " + String.join(" AND ", condition);
        ColumnRenderer natComparing = comparingRenderer(renderer, joinStrings,
                left.exactStrings, dialect, dialect::exactStringComparison);
        ColumnRenderer natOrdering = comparingRenderer(renderer, joinStrings,
                left.ordersExactly, dialect, dialect::exactStringOrder);
        List<Predicate> peeled = new ArrayList<>(leftPeel.filters());
        peeled.addAll(rightPeel.filters());
        Optional<List<String>> filters = renderPeeled(peeled, natComparing, natOrdering, dialect);
        if (filters.isEmpty()) {
            return Optional.empty();
        }

        Pushed joined = new Pushed(left.connectorType, left.connection, from, selectList,
                schema, renderer, dialect);
        joined.where.addAll(filters.get());
        joined.exactStrings = left.exactStrings;
        joined.comparing = natComparing;
        joined.ordersExactly = left.ordersExactly;
        joined.ordering = natOrdering;
        return Optional.of(joined);
    }

    /**
     * Renders a reference to a natural join's output. A shared column is one column
     * there, holding the left row's value and answering to the left relation's name, as
     * analysis resolves it; any other column renders against the side that has it. A
     * qualifier naming the other side, or neither, declines.
     */
    private static ColumnRenderer naturalJoinRenderer(Pushed left, Pushed right, List<String> shared,
                                                      JoinAliases aliases, Dialect dialect) {
        List<String> leftColumns = columnNames(left.baseColumns);
        List<String> rightColumns = columnNames(right.baseColumns);
        return attribute -> {
            String col = SqlExpressions.column(attribute);
            Optional<String> qualifier = SqlExpressions.qualifier(attribute);
            if (qualifier.isPresent()
                    && !qualifier.get().equalsIgnoreCase(left.relationName)
                    && !qualifier.get().equalsIgnoreCase(right.relationName)) {
                return Optional.empty();
            }
            boolean fromRight = qualifier.isPresent()
                    && qualifier.get().equalsIgnoreCase(right.relationName);
            if (!fromRight && containsIgnoreCase(leftColumns, col)) {
                return Optional.of(qualified(dialect, aliases.left(), col));
            }
            boolean fromLeft = qualifier.isPresent() && !fromRight;
            if (!fromLeft && containsIgnoreCase(rightColumns, col) && !containsIgnoreCase(shared, col)) {
                return Optional.of(qualified(dialect, aliases.right(), col));
            }
            return Optional.empty();
        };
    }

    /**
     * A join input split into the scan beneath it and the filters stacked on top.
     *
     * <p>An inner join commutes with a filter on either side — {@code (σp A) ⋈ (σq B)}
     * is {@code σ(p ∧ q)(A ⋈ B)} — so a σ between the join and its scan does not have to
     * stop the fold. It did, because {@link #bareJoinable} asks whether each side is a
     * <em>bare</em> scan and a folded σ is not one.
     *
     * <p>That mattered more than it looks, because the optimizer <em>creates</em> this
     * shape: {@code SEL-005} pushes a conjunct into the join input it belongs to, which
     * is the right rewrite for an in-engine join and, before this, silently cost a
     * same-connection join its whole-statement fold. The query went from one
     * {@code SELECT … JOIN … WHERE} to two scans, one of them unfiltered, and a hash join
     * here.
     *
     * @param scan    the input with every σ stripped off it
     * @param filters those σ's predicates, outermost first
     */
    private record Peeled(RelNode scan, List<Predicate> filters) {}

    /**
     * Strips the σ chain off a join input.
     *
     * <p>Only σ: a π, τ, λ or γ between the join and the scan changes what the side
     * <em>is</em>, and the folded statement reads each side's table directly.
     */
    private static Peeled peelFilters(RelNode input) {
        List<Predicate> filters = new ArrayList<>();
        RelNode current = input;
        while (current instanceof SelectionNode s) {
            filters.add(s.predicate());
            current = s.input();
        }
        return new Peeled(current, filters);
    }

    /**
     * Renders the peeled filters into the joined statement's {@code WHERE}, or empty if
     * any of them declines.
     *
     * <p>Declining the whole fold on one unrenderable filter is not a loss: the planner
     * then plans each side on its own, and each re-folds its own σ exactly as it did
     * before this method existed. So the worst case is what used to be the only case.
     *
     * <p>The ambiguity a reader expects to have to guard against — a bare column name
     * that both sides have — needs no guard here, because {@code joinRenderer} already
     * answers empty for one, which lands in the same decline.
     */
    private Optional<List<String>> renderPeeled(List<Predicate> filters, ColumnRenderer comparing,
                                                ColumnRenderer ordering, Dialect dialect) {
        List<String> rendered = new ArrayList<>(filters.size());
        for (Predicate filter : filters) {
            Optional<String> sql =
                    SqlExpressions.predicate(filter, comparing, ordering, dialect, functions);
            if (sql.isEmpty()) {
                return Optional.empty();
            }
            rendered.add(sql.get());
        }
        return Optional.of(rendered);
    }

    /**
     * Whether two pushed sub-trees are joinable as bare connection-table scans: both
     * are bare scans on the <em>same</em> connection, of relations with different
     * names.  Shared by the theta-join, AS-OF, and interval-join folds.
     *
     * <p>The names must differ because a qualifier is all a condition has to say which
     * side it means: in a self-join {@code Orders.id} names both, and the fold would have
     * to guess. What the names need <em>not</em> be is SQL identifiers — the statement
     * names each side by an alias of its own ({@link JoinAliases}), which is what lets a
     * dotted {@code shop.orders} fold.
     */
    private static boolean bareJoinable(Pushed left, Pushed right) {
        return left.bareScan && right.bareScan
                && left.connection.equals(right.connection)
                && !left.relationName.equalsIgnoreCase(right.relationName);
    }

    /**
     * The SQL aliases a folded join names its two sides by.
     *
     * <p>An alias only has to be a unique, safe identifier inside one statement; it never
     * has to <em>be</em> the relation's name. It is the name when that is already an
     * identifier, which keeps the SQL readable ({@code customers Customers}); otherwise
     * the last segment of a dotted name ({@code shop.orders} → {@code orders}), and
     * failing that a positional {@code t0}/{@code t1}. A clash between the two — which
     * only derivation can cause, since {@link #bareJoinable} refuses equal names — is
     * settled by suffixing the right side.
     *
     * @param left  the left side's alias
     * @param right the right side's alias
     */
    private record JoinAliases(String left, String right) {

        static JoinAliases of(Pushed leftSide, Pushed rightSide) {
            String left = aliasFor(leftSide.relationName, "t0");
            String right = aliasFor(rightSide.relationName, "t1");
            if (left.equalsIgnoreCase(right)) {
                right = right + "_1";
            }
            return new JoinAliases(left, right);
        }

        private static String aliasFor(String relationName, String fallback) {
            if (IDENTIFIER.matcher(relationName).matches()) {
                return relationName;
            }
            String last = relationName.substring(relationName.lastIndexOf('.') + 1);
            return IDENTIFIER.matcher(last).matches() ? last : fallback;
        }
    }

    /**
     * Folds an AS-OF join (ADR-0014) over two bare connection-table scans
     * into a {@code LATERAL} "nearest row" lookup: for each probe row the database runs
     * a correlated sub-select that filters the right table by the match condition,
     * orders it by the match column in the direction the inequality implies, and takes
     * the single nearest row ({@code … ORDER BY r.ts DESC LIMIT 1}).  A {@code LEFT JOIN
     * LATERAL … ON TRUE} preserves unmatched probes (left-outer, the default); a plain
     * {@code JOIN LATERAL … ON TRUE} drops them (the inner variant).
     *
     * <p>Falls back to in-engine execution when the inputs are not two bare scans on one
     * connection, the dialect lacks {@code LATERAL} support ({@link Dialect#supportsLateralAsOf}),
     * the condition is untranslatable, or a {@code WITHIN} tolerance is present — the
     * tolerance bound leans on temporal arithmetic that has no portable SQL form, so it
     * is kept in-engine.
     *
     * <p><b>Tie semantics.</b> When several right rows share the nearest match value the
     * in-engine executor's {@code TIES (FIRST|LAST)} rule resolves to a specific input-order
     * row; under pushdown the database's {@code LIMIT 1} chooses one of the tied rows by
     * its own row ordering instead.  The two agree whenever the tied rows are
     * indistinguishable in the selected columns; the divergence is documented in ADR-0014.
     */
    private Optional<Pushed> asOfJoin(AsOfJoinNode node) {
        if (node.tolerance().isPresent()) {
            return Optional.empty();   // WITHIN bound has no portable SQL form — keep in-engine
        }
        Optional<Pushed> leftOpt = build(node.left());
        Optional<Pushed> rightOpt = build(node.right());
        if (leftOpt.isEmpty() || rightOpt.isEmpty()) {
            return Optional.empty();
        }
        Pushed left = leftOpt.get();
        Pushed right = rightOpt.get();
        if (!bareJoinable(left, right)) {
            return Optional.empty();
        }
        Dialect dialect = left.dialect;
        if (!dialect.supportsLateralAsOf()) {
            return Optional.empty();
        }
        JoinAliases aliases = JoinAliases.of(left, right);
        ColumnRenderer renderer = joinRenderer(left, right, aliases, dialect);
        // A join condition compares the two sides' columns to each other, so it renders
        // in comparison position. Both sides share the connection, hence one answer.
        StringColumns joinStrings = stringColumnsOf(left.baseColumns, right.baseColumns);
        ColumnRenderer comparing = comparingRenderer(renderer, joinStrings,
                left.exactStrings, dialect, dialect::exactStringComparison);
        ColumnRenderer ordering = comparingRenderer(renderer, joinStrings,
                left.ordersExactly, dialect, dialect::exactStringOrder);

        Optional<String> condition = SqlExpressions.predicate(node.condition(), comparing, ordering,
                dialect, functions);
        if (condition.isEmpty()) {
            return Optional.empty();
        }
        // Decode the ordering inequality to drive the ORDER BY direction. A condition
        // qualifies by relation name, never by the SQL alias, so the names are the sets.
        JoinPlanning.AsOfMatch match = JoinPlanning.extractAsOfMatch(
                node.condition(), schemaOf(node.left()), schemaOf(node.right()),
                Set.of(left.relationName.toLowerCase(Locale.ROOT)),
                Set.of(right.relationName.toLowerCase(Locale.ROOT)));
        if (match == null) {
            return Optional.empty();
        }
        String matchColumn = right.baseColumns.get(match.rightMatchIndex()).name();
        String orderBy = qualified(dialect, aliases.right(), matchColumn)
                + (match.backward() ? " DESC" : " ASC");

        Optional<String> from = dialect.nearestRowJoin(
                dialect.table(left.tableName) + " " + dialect.quote(aliases.left()),
                String.join(", ", qualifiedColumns(dialect, aliases.right(), right.baseColumns)),
                "FROM " + dialect.table(right.tableName) + " " + dialect.quote(aliases.right())
                        + " WHERE " + condition.get(),
                orderBy, dialect.quote(aliases.right()), node.inner());
        if (from.isEmpty()) {
            return Optional.empty();
        }

        List<String> selectList = new ArrayList<>();
        selectList.addAll(qualifiedColumns(dialect, aliases.left(), left.baseColumns));
        selectList.addAll(qualifiedColumns(dialect, aliases.right(), right.baseColumns));
        return Optional.of(new Pushed(left.connectorType, left.connection, from.get(), selectList,
                schemaOf(node), renderer, dialect));
    }

    /**
     * Folds an interval join (ADR-0014) over two bare connection-table scans
     * into a plain inner {@code JOIN … ON} whose condition is the chosen Allen relation's
     * endpoint comparison (e.g. {@code OVERLAPS} → {@code l.s < r.s AND l.e > r.s AND l.e
     * < r.e}).  The comparisons mirror the in-engine {@code allenTest} exactly, and SQL's
     * three-valued logic excludes any NULL-endpoint row, matching the engine's
     * NULL-endpoint drop.  Works on every dialect (only {@code <}/{@code =}/{@code >} are
     * used — no SQL:2011 period-predicate syntax is required).
     *
     * <p>Falls back to in-engine execution when the inputs are not two bare scans on one
     * connection or an endpoint column cannot be resolved.
     */
    private Optional<Pushed> intervalJoin(IntervalJoinNode node) {
        Optional<Pushed> leftOpt = build(node.left());
        Optional<Pushed> rightOpt = build(node.right());
        if (leftOpt.isEmpty() || rightOpt.isEmpty()) {
            return Optional.empty();
        }
        Pushed left = leftOpt.get();
        Pushed right = rightOpt.get();
        if (!bareJoinable(left, right)) {
            return Optional.empty();
        }
        Dialect dialect = left.dialect;
        JoinAliases aliases = JoinAliases.of(left, right);
        ColumnRenderer renderer = joinRenderer(left, right, aliases, dialect);

        Optional<String> ls = renderer.render(node.leftStart());
        Optional<String> le = renderer.render(node.leftEnd());
        Optional<String> rs = renderer.render(node.rightStart());
        Optional<String> re = renderer.render(node.rightEnd());
        if (ls.isEmpty() || le.isEmpty() || rs.isEmpty() || re.isEmpty()) {
            return Optional.empty();
        }
        String condition = allenCondition(node.relation(), ls.get(), le.get(), rs.get(), re.get());

        List<String> selectList = new ArrayList<>();
        selectList.addAll(qualifiedColumns(dialect, aliases.left(), left.baseColumns));
        selectList.addAll(qualifiedColumns(dialect, aliases.right(), right.baseColumns));
        String from = dialect.table(left.tableName) + " " + dialect.quote(aliases.left())
                + " JOIN " + dialect.table(right.tableName) + " " + dialect.quote(aliases.right())
                + " ON " + condition;
        return Optional.of(new Pushed(left.connectorType, left.connection, from, selectList,
                schemaOf(node), renderer, dialect));
    }

    /**
     * The SQL endpoint condition for an Allen relation, mirroring the in-engine
     * {@code allenTest}.  Half-open intervals, strict bounds; {@code ls}/{@code le} are
     * the (already-rendered) left start/end, {@code rs}/{@code re} the right.
     */
    private static String allenCondition(AllenRelation rel, String ls, String le, String rs, String re) {
        return switch (rel) {
            case INTERSECTS    -> "(" + ls + " < " + re + " AND " + rs + " < " + le + ")";
            case OVERLAPS      -> "(" + ls + " < " + rs + " AND " + le + " > " + rs + " AND " + le + " < " + re + ")";
            case OVERLAPPED_BY -> "(" + rs + " < " + ls + " AND " + re + " > " + ls + " AND " + re + " < " + le + ")";
            case DURING        -> "(" + rs + " < " + ls + " AND " + le + " < " + re + ")";
            case CONTAINS      -> "(" + ls + " < " + rs + " AND " + re + " < " + le + ")";
            case STARTS        -> "(" + ls + " = " + rs + " AND " + le + " < " + re + ")";
            case STARTED_BY    -> "(" + ls + " = " + rs + " AND " + re + " < " + le + ")";
            case FINISHES      -> "(" + le + " = " + re + " AND " + rs + " < " + ls + ")";
            case FINISHED_BY   -> "(" + le + " = " + re + " AND " + ls + " < " + rs + ")";
            case EQUALS        -> "(" + ls + " = " + rs + " AND " + le + " = " + re + ")";
            case MEETS         -> "(" + le + " = " + rs + ")";
            case MET_BY        -> "(" + ls + " = " + re + ")";
            case PRECEDES      -> "(" + le + " < " + rs + ")";
            case PRECEDED_BY   -> "(" + ls + " > " + re + ")";
        };
    }

    /** Renders {@code alias.column}, each part dialect-quoted. */
    private static String qualified(Dialect dialect, String alias, String column) {
        return dialect.quote(alias) + "." + dialect.quote(SqlExpressions.column(column));
    }

    /** Renders the {@code alias.column} list for every column of {@code columns}. */
    private static List<String> qualifiedColumns(Dialect dialect, String alias,
                                                 List<ColumnDefinition> columns) {
        List<String> out = new ArrayList<>(columns.size());
        for (ColumnDefinition col : columns) {
            out.add(qualified(dialect, alias, col.name()));
        }
        return out;
    }

    /**
     * Renders a reference in a folded join's condition as {@code alias.column}.
     *
     * <p>A qualified reference is resolved by the relation name it carries — the name
     * the analyser checked it against, which for a dotted source is the whole of
     * {@code shop.orders} — and then rendered with that side's SQL alias. Nothing else
     * about a qualifier is trusted: one that names neither relation is a path into a
     * nested column ({@code NestedPaths} is how the engine settles which side's), and a
     * path has no {@code alias.column} spelling, so it declines rather than being
     * resolved by its tail, which would bind it to whichever side has a column of that
     * name. An unqualified reference renders only when exactly one side has the column.
     */
    private static ColumnRenderer joinRenderer(Pushed left, Pushed right, JoinAliases aliases,
                                               Dialect dialect) {
        List<String> leftColumns = columnNames(left.baseColumns);
        List<String> rightColumns = columnNames(right.baseColumns);
        return attribute -> {
            String col = SqlExpressions.column(attribute);
            Optional<String> qualifier = SqlExpressions.qualifier(attribute);
            if (qualifier.isPresent()) {
                String q = qualifier.get();
                if (q.equalsIgnoreCase(left.relationName)) {
                    return containsIgnoreCase(leftColumns, col)
                            ? Optional.of(dialect.quote(aliases.left()) + "." + dialect.quote(col))
                            : Optional.empty();
                }
                if (q.equalsIgnoreCase(right.relationName)) {
                    return containsIgnoreCase(rightColumns, col)
                            ? Optional.of(dialect.quote(aliases.right()) + "." + dialect.quote(col))
                            : Optional.empty();
                }
                return Optional.empty();
            }
            // Unqualified: resolve to the single side that has the column, else ambiguous.
            boolean onLeft = containsIgnoreCase(leftColumns, col);
            boolean onRight = containsIgnoreCase(rightColumns, col);
            if (onLeft == onRight) {
                return Optional.empty();
            }
            return Optional.of(dialect.quote(onLeft ? aliases.left() : aliases.right())
                    + "." + dialect.quote(col));
        };
    }

    private static boolean containsIgnoreCase(List<String> columns, String name) {
        return columns.stream().anyMatch(c -> c.equalsIgnoreCase(name));
    }

    private static List<String> columnNames(List<ColumnDefinition> columns) {
        List<String> names = new ArrayList<>(columns.size());
        for (ColumnDefinition col : columns) {
            names.add(col.name());
        }
        return names;
    }

    private Schema schemaOf(RelNode node) {
        return schemas.require(node, "during SQL pushdown");
    }

    /** Mutable accumulator for one connection's pushable sub-tree (never shared). */
    private static final class Pushed {
        final String connectorType;
        final String connection;
        final String from;            // FROM-clause body: a table, or a JOIN expression
        // Not final: a GROUP BY changes what a name above it means. Below one a
        // reference is a table column; above one the only references that exist are the
        // aggregation's own output columns, which resolve to the select-list
        // expressions that produced them.
        ColumnRenderer renderer;
        final Dialect dialect;
        List<String> selectList;
        final List<String> where = new ArrayList<>();
        List<String> groupBy = List.of();
        String having = null;             // HAVING expr for ∀ pushdown; null = no HAVING clause
        List<String> orderBy = List.of();
        List<SortSpecification> deliveredKeys = List.of();   // structured ORDER BY → delivered Ordering
        boolean distinct = false;         // SELECT DISTINCT (a δ folded in)
        // Whether this connection's backend compares strings as the engine does. Read
        // once, at the scan, because it is a property of the connection rather than of
        // any one operator.
        boolean exactStrings = true;
        // How a reference is rendered in COMPARISON position, which on a backend whose
        // collation is not exact is not how it is rendered anywhere else: the same
        // column becomes CONVERT(…) COLLATE … so that `=` means what the engine means.
        // Equal to `renderer` wherever the backend already compares exactly.
        ColumnRenderer comparing;
        // Whether this connection's backend orders strings as the engine does. A second
        // boolean rather than a reading of the first, because Postgres answers the two
        // differently: its equality is exact and its ordering is the locale's.
        boolean ordersExactly = true;
        // How a reference is rendered in ORDERING position — an inequality, an ORDER BY
        // key, a MIN or a MAX. Equal to `renderer` wherever the backend already orders
        // by code point, and to `comparing` on a backend whose one collation settles
        // both questions.
        ColumnRenderer ordering;
        // A GROUP BY has been folded in. Kept apart from projectionApplied, which this
        // used to set: both fix the select list, but only one of them leaves the fold
        // able to continue — a HAVING, an ORDER BY over an aggregate and the LIMIT after
        // it are further clauses of the same statement, not a second statement.
        boolean aggregated = false;
        boolean projectionApplied = false;
        boolean sortApplied = false;
        boolean limitApplied = false;
        boolean windowApplied = false;
        /**
         * The output columns folded windows have added to the select list.
         *
         * <p>These are select-list aliases and not columns of any table, which is what
         * makes them the discriminator: an expression above may name one, and a clause
         * SQL evaluates before the window cannot resolve it.
         */
        final java.util.Set<String> windowAliases = new java.util.HashSet<>();
        long limit = -1;
        long offset = 0;
        Schema schema;

        // Set only while this is a bare base scan (enables join folding).
        boolean bareScan = false;
        String relationName;          // the name a join condition qualifies this side by
        String tableName;
        List<ColumnDefinition> baseColumns;

        Pushed(String connectorType, String connection, String from, List<String> selectList,
               Schema schema, ColumnRenderer renderer, Dialect dialect) {
            this.connectorType = connectorType;
            this.connection = connection;
            this.from = from;
            this.selectList = selectList;
            this.schema = schema;
            this.renderer = renderer;
            this.comparing = renderer;
            this.ordering = renderer;
            this.dialect = dialect;
        }

        String toSql() {
            StringBuilder sql = new StringBuilder("SELECT ");
            if (distinct) {
                sql.append("DISTINCT ");
            }
            sql.append(String.join(", ", selectList)).append(" FROM ").append(from);
            if (!where.isEmpty()) {
                StringJoiner conj = new StringJoiner(" AND ");
                where.forEach(conj::add);
                sql.append(" WHERE ").append(conj);
            }
            if (!groupBy.isEmpty()) {
                sql.append(" GROUP BY ").append(String.join(", ", groupBy));
            }
            if (having != null) {
                sql.append(" HAVING ").append(having);
            }
            if (!orderBy.isEmpty()) {
                sql.append(" ORDER BY ").append(String.join(", ", orderBy));
            }
            if (limitApplied) {
                sql.append(' ').append(dialect.limit(limit, offset));
            }
            return sql.toString();
        }
    }
}

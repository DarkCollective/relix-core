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
package com.darkcollective.relix.semantic;

import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ClosureNode;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.DifferenceNode;
import com.darkcollective.relix.ast.DistinctNode;
import com.darkcollective.relix.ast.DurationOperand;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.cost.Boundedness;
import com.darkcollective.relix.events.QueryEvent;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.FunctionProperty;
import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.ColumnStatistics;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.RelationStatistics;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.ShadowPolicy;
import com.darkcollective.relix.symbol.Symbol;
import com.darkcollective.relix.symbol.function.FunctionSymbol;
import com.darkcollective.relix.symbol.function.RelationFunctionSymbol;
import com.darkcollective.relix.symbol.relation.InlineRelationSymbol;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.relation.RelationSymbol;
import com.darkcollective.relix.symbol.relation.SystemRelationSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Builds the read-only <strong>system catalog relations</strong> — the {@code
 * relix.*} namespace that lets a script query the engine's own knowledge about
 * itself (the {@code information_schema} analogue; see ADR-0007).
 *
 * <h2>Strategy (ADR-0007, Decision 3 — Strategy A)</h2>
 * Catalogs are registered as {@link SystemRelationSymbol} — a dedicated, first-class
 * read-only symbol kind in the reserved {@code relix} namespace.  This promotes the
 * v1 Strategy-B approach (reusing {@link InlineRelationSymbol}) to the Strategy-A
 * escape hatch described in the issue, giving callers a structural signal that these
 * relations are engine-computed and not user-defined inline data.  The IR now labels
 * them {@code SYS} instead of {@code INL}.  {@code relix.dependencies} is the
 * exception: it is a {@link QueryRelationSymbol} <em>derived</em> from
 * {@code relix.plan}, so it has no Java-computed extent at all — it is planned
 * and executed lazily like any other view (see {@link #buildDependencies()}).
 *
 * <h2>Two-step registration (the ordering constraint)</h2>
 * {@code relix.relations} has a collection-time extent, so
 * {@link #registerInto} computes and registers it in full
 * <em>before</em> schema inference.  {@code relix.columns} and {@code relix.plan}
 * are different: their <em>rows</em> need the inferred output schemas of views
 * (which only resolve during inference), yet the symbol must already exist
 * <em>before</em> inference for a query to reference it.  Both are therefore
 * handled with the same placeholder-then-fill pattern the engine already uses for
 * views:
 * <ol>
 *   <li>{@link #registerInto} registers them with their (fixed,
 *       known) schema but an empty extent, so references resolve and infer
 *       correctly.</li>
 *   <li>{@link #fillColumns(SymbolTable)} / {@link #fillPlan(SymbolTable,
 *       SchemaAnnotations)} re-register them after inference with the real extent,
 *       read off the now-resolved symbol schemas (columns) and per-node schema
 *       annotations (plan).</li>
 * </ol>
 *
 * <h2>Reservation</h2>
 * Catalog symbols live in the {@code relix} namespace with {@link Provenance#BUILTIN};
 * a user {@code :=} (always in a user namespace) can never name one.  The
 * single-registration catalogs use {@link ShadowPolicy#FORBIDDEN}; {@code
 * relix.columns} uses {@link ShadowPolicy#PERMITTED} so its post-inference fill
 * can overwrite the placeholder.
 *
 * <h2>Self-exclusion (ADR-0007, Decision 4)</h2>
 * Every extent is computed from the relation symbols <em>outside</em> the {@code
 * relix} namespace, so the catalog never describes itself or its siblings; the
 * extents are stable and acyclic.
 *
 * <p>{@code relix.catalog} is the one deliberate exception, and it is opt-in by
 * being a relation of its own: naming it is how you ask. It changes no other
 * extent — {@code relix.relations}, {@code relix.columns} and {@code
 * relix.functions} still describe only the user's namespace — and it cannot
 * recurse, because it is computed in Java from the symbol table rather than
 * derived over another catalog view. It exists because the exclusion above left
 * the introspection surface as the one thing that could not be introspected.
 *
 * <h2>Structural vs temporal extents</h2>
 * Every catalog above answers "what <em>is</em>" — it is a projection of the
 * model being analysed, so its extent is fixed the moment analysis finishes.
 * {@code relix.events} is the one that answers "what <em>happened</em>": its rows
 * are the observability feed of the <em>previously</em> executed statement,
 * handed in by the session host.  It has to be the previous one, because the
 * feed a statement produces does not exist until the optimizer, planner, and
 * executor have run — all of which come strictly after the catalog is built.  A
 * batch script analysed once therefore sees an empty {@code relix.events}; a
 * session that re-analyses per statement sees the run before it.  This is the
 * push→pull bridge of ADR-0017 Decision 3, and it is the only catalog whose
 * extent is not a function of the script.
 *
 * <h2>Scope</h2>
 * Ships {@code relix.relations}, {@code relix.dependencies}, {@code relix.columns},
 * {@code relix.keys} (candidate keys, the one collected statistic with no flat
 * column to sit in), {@code relix.functions}, {@code relix.connections} (the last
 * redacted — no raw URLs; ADR-0007 Decision 5), {@code relix.plan} (the queryable
 * logical expression tree), {@code relix.events} (the previous run's feed), and
 * {@code relix.catalog} (the reserved namespace describing itself).
 */
final class CatalogBuilder {

    /** The reserved namespace under which all catalog relations live. */
    static final String CATALOG_NAMESPACE = "relix";

    private CatalogBuilder() {
    }

    /**
     * Registers the catalog relations whose extents are known at symbol-collection
     * time ({@code relix.relations}) in full, the {@code relix.dependencies} view
     * (derived from {@code relix.plan}), plus empty-extent placeholders for
     * {@code relix.columns} / {@code relix.plan} (filled later by
     * {@link #fillColumns(SymbolTable)} / {@link #fillPlan(SymbolTable,
     * SchemaAnnotations)} once inference resolves view schemas).
     *
     * <p>Must run after symbol collection and before schema inference.
     *
     * @param table       the populated symbol table; must not be null
     * @param connections the declared database connections, keyed by canonical
     *                    name; must not be null (may be empty)
     * @param statistics  per-relation statistics keyed by canonical name, feeding
     *                    the {@code row_count} column of {@code relix.relations}
     *                    and the whole extent of {@code relix.keys}; must not be
     *                    null (may be empty — an absent entry yields a NULL count
     *                    and no key rows)
     * @param events      the observability feed of the previously executed
     *                    statement, in arrival order, forming the extent of
     *                    {@code relix.events}; must not be null (empty when the
     *                    host observed nothing, as in a one-shot batch run)
     * @param functions   the installed function catalogue, listed in full by
     *                    {@code relix.functions} regardless of what the script
     *                    calls; must not be null
     * @param components  what the host can see that the engine cannot — connectors,
     *                    solvers, drivers; must not be null
     * @param boundedness answers whether a named relation is finite, feeding the
     *                    {@code boundedness} column of {@code relix.relations}; must
     *                    not be null
     */
    static void registerInto(SymbolTable table,
                             Map<String, ConnectionDeclaration> connections,
                             Map<String, RelationStatistics> statistics,
                             List<QueryEvent> events,
                             FunctionCatalog functions,
                             ComponentInventory components,
                             RelationBoundedness boundedness) {
        List<RelationSymbol> userRelations = userRelations(table);
        table.register(buildRelations(userRelations, statistics, boundedness));
        table.register(buildKeys(userRelations, statistics));
        table.register(buildFunctions(table, functions));
        table.register(buildConnections(connections));
        table.register(buildEvents(events));
        table.register(buildVersion(functions, components));
        // Placeholders: correct schema, empty extent — resolvable during inference,
        // filled afterwards (relix.columns / relix.plan need inferred schemas).
        table.register(columnsSymbol(List.of(), Map.of()));
        table.register(planSymbol(List.of()));
        // relix.catalog is a placeholder for a different reason: it lists the
        // reserved namespace, so its extent is only complete once every sibling
        // below has registered — and it has to be in the table by then to list
        // itself. Filled at the end of this method, not after inference.
        table.register(buildCatalog(List.of()));
        // relix.dependencies is *derived* from relix.plan (a view), so it is
        // registered after the relix.plan placeholder it reads.
        table.register(buildDependencies());
        // The introspection standard library: the engine's own
        // introspection queries shipped as readable Relix, layered over the
        // catalog relations above (relix.relations / relix.dependencies).  Like
        // relix.dependencies, these have no Java-computed extent — they are
        // planned and executed lazily by the normal pipeline when called.
        table.register(buildUnused());
        table.register(buildDeps());
        table.register(buildImpact());
        // Data-driven exploration queries over the existing catalog: find a column
        // across relations, read one relation's columns, spot dependency cycles,
        // filter the function library by category.  Same stdlib pattern — views/TVFs,
        // no bespoke Java, no new language construct.
        table.register(buildFind());
        table.register(buildSchema());
        table.register(buildCycles());
        table.register(buildFuncs());
        // Layered over relix.events the same way: the optimizer's slice of the
        // feed, which is the half users reach for by name ("did SEL-001 fire?").
        table.register(buildRules());
        // Last, so it sees every sibling above and its own placeholder: the
        // reserved namespace describing itself.
        table.register(buildCatalog(relixSymbols(table)));
    }

    /**
     * Re-registers {@code relix.columns} with its real extent, read off the
     * now-resolved symbol schemas.  Must run after schema inference (which fills in
     * every view's output schema) and overwrites the placeholder from
     * {@link #registerInto}.
     *
     * @param table      the symbol table, after inference; must not be null
     * @param statistics per-relation statistics keyed by canonical name, used to
     *                   populate the {@code distinct_count}/{@code null_count}
     *                   columns; must not be null (may be empty)
     */
    static void fillColumns(SymbolTable table, Map<String, RelationStatistics> statistics) {
        table.register(columnsSymbol(userRelations(table), statistics));
    }

    /**
     * Re-registers {@code relix.plan} with its real extent — one row per logical
     * IR node of every view's body, read off the per-node {@code schemas}
     * annotations populated by schema inference.  Must run after inference (so the
     * node schemas exist) and overwrites the placeholder from
     * {@link #registerInto}.
     *
     * @param table   the symbol table, after inference; must not be null
     * @param schemas the inferred per-node schema annotations; must not be null
     */
    static void fillPlan(SymbolTable table, SchemaAnnotations schemas) {
        table.register(planSymbol(buildPlanRows(table, schemas)));
    }

    /** Relation symbols outside the reserved {@code relix} namespace (self-exclusion). */
    private static List<RelationSymbol> userRelations(SymbolTable table) {
        List<RelationSymbol> result = new ArrayList<>();
        for (Symbol sym : table.allSymbols()) {
            if (sym instanceof RelationSymbol rel
                    && !CATALOG_NAMESPACE.equals(rel.namespace())) {
                result.add(rel);
            }
        }
        return result;
    }

    /**
     * {@code relix.relations} — one row per relation symbol:
     * {@code (name, kind, namespace, materialization, row_count, boundedness)}.
     *
     * <p>{@code row_count} is the relation's <em>known</em> cardinality from
     * {@code statistics} (exact for inline relations and finite generators, the
     * catalog count for an introspected JDBC table).  It is NULL — the key is
     * omitted — when no count was collected: a view (its cardinality is an
     * estimate, not a stored stat), an un-scanned file/HTTP source, or a
     * provably-infinite generator.
     *
     * <p>{@code boundedness} is the other question about size, and a different kind of
     * answer: a row count is measured, where this is <em>proved</em> — one of
     * {@code bounded} / {@code unbounded} / {@code unknown}, from the source of the
     * same name.  The two are
     * independent and the pairs that look odd are the informative ones: a JDBC table
     * with no catalog count is {@code bounded} with a NULL {@code row_count} (finite,
     * unmeasured), while {@code Naturals} has no count because it has no end.  It is
     * never NULL, because {@code unknown} is a value of the lattice rather than an
     * absence — "nothing proves this finite either way" is a claim, where a NULL count
     * is the absence of one.
     */
    private static RelationSymbol buildRelations(
            List<RelationSymbol> relations, Map<String, RelationStatistics> statistics,
            RelationBoundedness boundedness) {
        Schema schema = new Schema(List.of(
                new ColumnDefinition("name", ScalarType.STRING),
                new ColumnDefinition("kind", ScalarType.STRING),
                new ColumnDefinition("namespace", ScalarType.STRING),
                new ColumnDefinition("materialization", ScalarType.STRING),
                new ColumnDefinition("row_count", ScalarType.NUMBER),
                new ColumnDefinition("boundedness", ScalarType.STRING)));

        List<Map<String, Operand>> rows = new ArrayList<>(relations.size());
        for (RelationSymbol rel : relations) {
            Map<String, Operand> row = new LinkedHashMap<>();
            row.put("name", new StringOperand(rel.declaredName()));
            row.put("kind", new StringOperand(IrReport.kindCode(rel)));
            row.put("namespace", new StringOperand(rel.namespace()));
            row.put("materialization", new StringOperand(materialization(rel)));
            rowCount(statistics, rel).ifPresent(count ->
                    row.put("row_count", new NumberOperand(Long.toString(count))));
            row.put("boundedness", new StringOperand(
                    boundednessCode(boundedness.of(rel))));
            rows.add(row);
        }
        return catalogRelation("relations", schema, ShadowPolicy.FORBIDDEN, rows);
    }

    /**
     * The lower-case token {@code relix.relations.boundedness} carries, spelled like
     * {@code materialization} beside it rather than like the enum constant.
     */
    private static String boundednessCode(Boundedness boundedness) {
        return switch (boundedness) {
            case BOUNDED   -> "bounded";
            case UNBOUNDED -> "unbounded";
            case UNKNOWN   -> "unknown";
        };
    }

    /**
     * {@code relix.keys} — candidate keys as data: one row per column of every
     * candidate key of every relation, {@code (relation, key, ordinal, column)}.
     *
     * <p>A key is a <em>list</em> of columns and a relation may have several, so
     * this is the one collected statistic with no flat column to live in. The
     * scalar figures do have one and are there rather than here — {@code row_count}
     * on {@code relix.relations}, {@code distinct_count}/{@code null_count} on
     * {@code relix.columns} — because a second copy is a second thing to disagree.
     *
     * <p>{@code key} numbers the candidate keys of one relation from 0; {@code
     * ordinal} is the column's position <em>within</em> that key, which is
     * significant — a composite key's column order is the order an index on it
     * would be built in. A relation with one single-column key contributes one row
     * with both at 0.
     *
     * <p>Exact for inline relations (a column with no NULLs whose values are all
     * distinct) and read from the primary key for an introspected JDBC table. A
     * relation whose statistics were never collected — a view, an un-scanned file
     * or HTTP source — contributes no rows, which is not the same claim as having
     * no key.
     */
    private static RelationSymbol buildKeys(
            List<RelationSymbol> relations, Map<String, RelationStatistics> statistics) {
        Schema schema = new Schema(List.of(
                new ColumnDefinition("relation", ScalarType.STRING),
                new ColumnDefinition("key", ScalarType.NUMBER),
                new ColumnDefinition("ordinal", ScalarType.NUMBER),
                new ColumnDefinition("column", ScalarType.STRING)));

        List<Map<String, Operand>> rows = new ArrayList<>();
        for (RelationSymbol rel : relations) {
            RelationStatistics stats = statistics.get(rel.canonicalName());
            if (stats == null) {
                continue;
            }
            List<List<String>> keys = stats.keys();
            for (int k = 0; k < keys.size(); k++) {
                List<String> key = keys.get(k);
                for (int i = 0; i < key.size(); i++) {
                    Map<String, Operand> row = new LinkedHashMap<>();
                    row.put("relation", new StringOperand(rel.declaredName()));
                    row.put("key", new NumberOperand(Integer.toString(k)));
                    row.put("ordinal", new NumberOperand(Integer.toString(i)));
                    row.put("column", new StringOperand(key.get(i)));
                    rows.add(row);
                }
            }
        }
        return catalogRelation("keys", schema, ShadowPolicy.FORBIDDEN, rows);
    }

    /**
     * {@code relix.catalog} — the reserved namespace describing itself: one row per
     * {@code relix.*} relation and table-valued function,
     * {@code (name, kind, arity, schema_code)}.
     *
     * <p>It is the mirror of every other catalog rather than an exception to them.
     * The rest describe the <em>user's</em> relations and exclude the reserved
     * namespace — which keeps their extents about the script and the derived
     * layering acyclic, and is why the introspection surface was the one thing that
     * could not be introspected: to learn what {@code relix.*} holds you had to
     * read the documentation. This relation answers that from inside the engine,
     * and it changes no other extent: opting in is naming it.
     *
     * <p>It describes <strong>itself</strong> too, which is what makes it a
     * complete listing rather than one with a hole where the entry point is — hence
     * the placeholder-then-fill registration {@code relix.columns} already uses.
     * The cycle that would make that dangerous is not available: the extent is
     * computed in Java from the symbol table, never derived over another catalog
     * view, and {@code userRelations} still filters the reserved namespace out of
     * everything else.
     *
     * <p>Columns:
     * <ul>
     *   <li>{@code name} — qualified, as you would type it ({@code relix.plan}),
     *       since the value of a listing is that you can copy a row out of it.</li>
     *   <li>{@code kind} — the same codes the IR uses: {@code SYS} for an
     *       engine-computed relation, {@code QR} for one of the stdlib views,
     *       {@code TVF} for a table-valued function.</li>
     *   <li>{@code arity} — how many arguments a call passes, NULL for a relation
     *       (which is not called).</li>
     *   <li>{@code schema_code} — the heading it yields, in the same compact form
     *       {@code relix.plan.schema_code} carries.</li>
     * </ul>
     *
     * <p>There is deliberately no description column. The text would be prose
     * hand-written here and duplicated from the reference pages, with nothing
     * keeping the two in step — the arrangement whose 52 rows were deleted when the
     * function library started serving its own documentation.
     */
    private static SystemRelationSymbol buildCatalog(List<Symbol> entries) {
        Schema schema = new Schema(List.of(
                new ColumnDefinition("name", ScalarType.STRING),
                new ColumnDefinition("kind", ScalarType.STRING),
                new ColumnDefinition("arity", ScalarType.NUMBER),
                new ColumnDefinition("schema_code", ScalarType.STRING)));

        List<Map<String, Operand>> rows = new ArrayList<>(entries.size());
        for (Symbol sym : entries) {
            Map<String, Operand> row = new LinkedHashMap<>();
            row.put("name", new StringOperand(
                    CATALOG_NAMESPACE + "." + sym.declaredName()));
            row.put("kind", new StringOperand(IrReport.kindCode(sym)));
            Optional<Schema> heading = Optional.empty();
            if (sym instanceof RelationFunctionSymbol fn) {
                row.put("arity", new NumberOperand(
                        Integer.toString(fn.parameters().size())));
                heading = fn.returnSchema();
            } else if (sym instanceof RelationSymbol rel) {
                heading = Optional.of(rel.schema());   // arity omitted → NULL
            }
            schemaCode(heading).ifPresent(code ->
                    row.put("schema_code", new StringOperand(code)));
            rows.add(row);
        }
        // PERMITTED so the self-including fill can overwrite the placeholder.
        return catalogRelation("catalog", schema, ShadowPolicy.PERMITTED, rows);
    }

    /**
     * Every symbol <em>inside</em> the reserved namespace — the exact complement of
     * {@link #userRelations(SymbolTable)}, and functions as well as relations,
     * since half the stdlib is table-valued.
     */
    private static List<Symbol> relixSymbols(SymbolTable table) {
        List<Symbol> result = new ArrayList<>();
        for (Symbol sym : table.allSymbols()) {
            if (CATALOG_NAMESPACE.equals(sym.namespace())) {
                result.add(sym);
            }
        }
        return result;
    }

    /** The collected row count for {@code rel}, if any, keyed by canonical name. */
    private static OptionalLong rowCount(
            Map<String, RelationStatistics> statistics, RelationSymbol rel) {
        RelationStatistics stats = statistics.get(rel.canonicalName());
        return stats == null ? OptionalLong.empty() : stats.rowCount();
    }

    /**
     * {@code relix.dependencies} — name-level lineage, {@code (dependent,
     * depends_on)}: one edge for every relation a view's body references.  Only
     * views have bodies, so only views appear as dependents; base relations
     * (SRC/INL/DB) are leaves with no edges.
     *
     * <p>Edge names are kept as written, so {@code dependent} values line up with
     * the {@code depends_on} values of the views they reference — which is what
     * lets {@code CLOSURE dependent, depends_on (relix.dependencies)} compute
     * transitive lineage (ADR-0007's showcase).
     *
     * <h3>Derived, not hand-walked</h3>
     * Rather than walking each view body in Java, this is a <em>view</em> over the
     * {@code relix.plan} primitive: every {@link RelationNode} leaf there already
     * carries the owning {@code query} and the bare {@code relation} it references
     * ({@code op = "Relation"}), so a relation-reference edge is exactly
     * <pre>δ π query→dependent, relation→depends_on (σ op = "Relation" (relix.plan))</pre>
     * The {@code δ} restores the set semantics of the old extent (a body may
     * reference the same relation twice, but {@code relix.plan} has one row per
     * reference).  The view is planned and executed lazily by the normal pipeline
     * when queried — so no part of this cold introspection path is hand-coded, and
     * the layering (primitive {@code relix.plan} → derived {@code relix.dependencies})
     * is explicit (ADR-0017).
     *
     * <p><strong>Self-exclusion still holds.</strong> {@code relix.plan} only
     * contains rows for user-namespace views, and this view lives in the {@code
     * relix} namespace, so {@code relix.plan} never describes it and the derivation
     * cannot recurse.
     */
    private static QueryRelationSymbol buildDependencies() {
        Schema schema = new Schema(List.of(
                new ColumnDefinition("dependent", ScalarType.STRING),
                new ColumnDefinition("depends_on", ScalarType.STRING)));

        // δ π query→dependent, relation→depends_on (σ op = "Relation" (relix.plan))
        RelNode body = new DistinctNode(
                new ProjectionNode(
                        List.of(
                                ProjectedAttribute.aliased(
                                        new AttributeOperand("query"), "dependent"),
                                ProjectedAttribute.aliased(
                                        new AttributeOperand("relation"), "depends_on")),
                        new SelectionNode(
                                new ComparisonPredicate(
                                        new AttributeOperand("op"),
                                        ComparisonOperator.EQUAL,
                                        new StringOperand("Relation")),
                                new RelationNode(CATALOG_NAMESPACE + ".plan"))));

        return new QueryRelationSymbol(
                CATALOG_NAMESPACE, "dependencies", Provenance.BUILTIN,
                ShadowPolicy.FORBIDDEN, schema, body);
    }

    // =========================================================================
    // Introspection standard library
    //
    // The engine's own introspection logic, shipped as readable Relix in the
    // reserved relix namespace next to the catalog relations it queries.  Each
    // is a view (zero-arg) or a table-valued function (parameterised) layered
    // over relix.relations / relix.dependencies — no new language construct, no
    // Java-computed extent.  They are the canonical definitions behind the REPL
    // introspection commands (the :source command prints their text), so the
    // engine's introspection is written in — and exercised by — its own
    // language (ADR-0017, Decision 4: upper layers reach kernel state only
    // through Relix queries).  Self-exclusion holds: relix.dependencies and
    // relix.relations only describe user-namespace relations, so these
    // relix-namespace definitions never appear in their own inputs.
    // =========================================================================

    /**
     * {@code relix.unused} — relations that nothing else references: the
     * {@code name}s in {@code relix.relations} minus the {@code depends_on}
     * targets in {@code relix.dependencies}.  A single-column ({@code name})
     * view; useful for spotting dead definitions (and top-level outputs, which
     * are by definition referenced by nothing).
     *
     * <pre>(π name (relix.relations)) − (π depends_on→name (relix.dependencies))</pre>
     *
     * <p>The right side aliases {@code depends_on} to {@code name} so the two
     * sides are union-compatible for the set difference.
     */
    private static QueryRelationSymbol buildUnused() {
        Schema schema = new Schema(List.of(
                new ColumnDefinition("name", ScalarType.STRING)));

        RelNode allNames = new ProjectionNode(
                List.of(ProjectedAttribute.simple(new AttributeOperand("name"))),
                new RelationNode(CATALOG_NAMESPACE + ".relations"));
        RelNode referenced = new ProjectionNode(
                List.of(ProjectedAttribute.aliased(
                        new AttributeOperand("depends_on"), "name")),
                new RelationNode(CATALOG_NAMESPACE + ".dependencies"));
        RelNode body = new DifferenceNode(allNames, referenced);

        return new QueryRelationSymbol(
                CATALOG_NAMESPACE, "unused", Provenance.BUILTIN,
                ShadowPolicy.FORBIDDEN, schema, body);
    }

    /**
     * {@code relix.deps(r)} — the relations {@code r} transitively depends on
     * (its full upstream lineage): the {@code depends_on} side of the transitive
     * closure of {@code relix.dependencies}, restricted to edges out of {@code r}.
     *
     * <pre>π depends_on (σ dependent = r (CLOSURE dependent, depends_on
     * (relix.dependencies)))</pre>
     */
    private static RelationFunctionSymbol buildDeps() {
        return lineageFunction("deps", "dependent", "depends_on");
    }

    /**
     * {@code relix.impact(r)} — the relations that transitively depend on
     * {@code r} (its blast radius / reverse lineage): the {@code dependent} side
     * of the transitive closure of {@code relix.dependencies}, restricted to
     * edges into {@code r}.
     *
     * <pre>π dependent (σ depends_on = r (CLOSURE dependent, depends_on
     * (relix.dependencies)))</pre>
     */
    private static RelationFunctionSymbol buildImpact() {
        return lineageFunction("impact", "depends_on", "dependent");
    }

    /**
     * Shared shape of {@code relix.deps}/{@code relix.impact}: project the
     * {@code project} side of the {@code relix.dependencies} transitive closure,
     * keeping only edges whose {@code filter} side equals the parameter {@code r}.
     * The two functions differ only in which end of the edge is the seed
     * ({@code filter}) and which is the result ({@code project}).
     */
    private static RelationFunctionSymbol lineageFunction(
            String name, String filterColumn, String projectColumn) {
        Schema schema = new Schema(List.of(
                new ColumnDefinition(projectColumn, ScalarType.STRING)));

        RelNode body = new ProjectionNode(
                List.of(ProjectedAttribute.simple(new AttributeOperand(projectColumn))),
                new SelectionNode(
                        new ComparisonPredicate(
                                new AttributeOperand(filterColumn),
                                ComparisonOperator.EQUAL,
                                new AttributeOperand("r")),
                        new ClosureNode(
                                new RelationNode(CATALOG_NAMESPACE + ".dependencies"),
                                "dependent", "depends_on")));

        return RelationFunctionSymbol.builder(name)
                .namespace(CATALOG_NAMESPACE)
                .provenance(Provenance.BUILTIN)
                .shadowPolicy(ShadowPolicy.FORBIDDEN)
                .parameter("r", ScalarType.STRING)
                .body(body)
                .returnSchema(schema)
                .build();
    }

    /**
     * {@code relix.find(col)} — every relation that has a column named {@code col},
     * with the type it carries there: a filter over {@code relix.columns} keyed on
     * the {@code column} name.  Useful for tracing where a column lives across a
     * schema (e.g. which relations expose {@code customer_id}).
     *
     * <pre>π relation, type (σ column = col (relix.columns))</pre>
     *
     * <p>The {@code column} projection is dropped — it is constant (it equals the
     * argument) — leaving the relation it belongs to and that relation's declared
     * type for the column.
     */
    private static RelationFunctionSymbol buildFind() {
        Schema schema = new Schema(List.of(
                new ColumnDefinition("relation", ScalarType.STRING),
                new ColumnDefinition("type", ScalarType.STRING)));

        RelNode body = new ProjectionNode(
                List.of(
                        ProjectedAttribute.simple(new AttributeOperand("relation")),
                        ProjectedAttribute.simple(new AttributeOperand("type"))),
                new SelectionNode(
                        new ComparisonPredicate(
                                new AttributeOperand("column"),
                                ComparisonOperator.EQUAL,
                                new AttributeOperand("col")),
                        new RelationNode(CATALOG_NAMESPACE + ".columns")));

        return RelationFunctionSymbol.builder("find")
                .namespace(CATALOG_NAMESPACE)
                .provenance(Provenance.BUILTIN)
                .shadowPolicy(ShadowPolicy.FORBIDDEN)
                .parameter("col", ScalarType.STRING)
                .body(body)
                .returnSchema(schema)
                .build();
    }

    /**
     * {@code relix.schema(rel)} — the columns of one relation, with the type and
     * position each holds there: a filter over {@code relix.columns} keyed on the
     * {@code relation} name.  The by-relation mirror of the by-column
     * {@link #buildFind()}, and the data twin of the REPL's {@code :schema}.
     *
     * <pre>π column, type, ordinal (σ relation = rel (relix.columns))</pre>
     *
     * <p>The {@code relation} projection is dropped — it is constant (it equals the
     * argument) — as are {@code distinct_count} and {@code null_count}, which say
     * what the statistics know rather than what the heading is; ask
     * {@code relix.columns} directly for those.  An open (schema-on-read) relation
     * contributes a single {@code *:ANY} row, exactly as it does in
     * {@code relix.columns}: nothing here special-cases it.
     */
    private static RelationFunctionSymbol buildSchema() {
        Schema schema = new Schema(List.of(
                new ColumnDefinition("column", ScalarType.STRING),
                new ColumnDefinition("type", ScalarType.STRING),
                new ColumnDefinition("ordinal", ScalarType.NUMBER)));

        RelNode body = new ProjectionNode(
                List.of(
                        ProjectedAttribute.simple(new AttributeOperand("column")),
                        ProjectedAttribute.simple(new AttributeOperand("type")),
                        ProjectedAttribute.simple(new AttributeOperand("ordinal"))),
                new SelectionNode(
                        new ComparisonPredicate(
                                new AttributeOperand("relation"),
                                ComparisonOperator.EQUAL,
                                new AttributeOperand("rel")),
                        new RelationNode(CATALOG_NAMESPACE + ".columns")));

        return RelationFunctionSymbol.builder("schema")
                .namespace(CATALOG_NAMESPACE)
                .provenance(Provenance.BUILTIN)
                .shadowPolicy(ShadowPolicy.FORBIDDEN)
                .parameter("rel", ScalarType.STRING)
                .body(body)
                .returnSchema(schema)
                .build();
    }

    /**
     * {@code relix.funcs(cat)} — the callable functions in one category: a filter
     * over {@code relix.functions} keyed on {@code category} (e.g. {@code "Math"},
     * {@code "String"}, {@code "user"}), projecting the name, arity, and return
     * type.  Data-driven off {@code relix.functions}' properties, so it complements
     * the static {@code :doc}/{@code :ops} doc tables rather than duplicating them.
     *
     * <pre>π name, arity, return_type (σ category = cat (relix.functions))</pre>
     */
    private static RelationFunctionSymbol buildFuncs() {
        Schema schema = new Schema(List.of(
                new ColumnDefinition("name", ScalarType.STRING),
                new ColumnDefinition("arity", ScalarType.NUMBER),
                new ColumnDefinition("return_type", ScalarType.STRING)));

        RelNode body = new ProjectionNode(
                List.of(
                        ProjectedAttribute.simple(new AttributeOperand("name")),
                        ProjectedAttribute.simple(new AttributeOperand("arity")),
                        ProjectedAttribute.simple(new AttributeOperand("return_type"))),
                new SelectionNode(
                        new ComparisonPredicate(
                                new AttributeOperand("category"),
                                ComparisonOperator.EQUAL,
                                new AttributeOperand("cat")),
                        new RelationNode(CATALOG_NAMESPACE + ".functions")));

        return RelationFunctionSymbol.builder("funcs")
                .namespace(CATALOG_NAMESPACE)
                .provenance(Provenance.BUILTIN)
                .shadowPolicy(ShadowPolicy.FORBIDDEN)
                .parameter("cat", ScalarType.STRING)
                .body(body)
                .returnSchema(schema)
                .build();
    }

    /**
     * {@code relix.cycles} — the relations caught in a circular definition: every
     * {@code name} reachable from itself through the dependency edge.  Computed as
     * the diagonal of the transitive closure of {@code relix.dependencies} — the
     * edges whose two endpoints coincide ({@code dependent = depends_on}).  Empty
     * for a well-formed (acyclic) script; a non-empty result flags a definition
     * cycle the engine would otherwise reject only on execution.
     *
     * <pre>δ π dependent→name (σ dependent = depends_on
     *        (CLOSURE dependent, depends_on (relix.dependencies)))</pre>
     *
     * <p>The {@code δ} keeps the result a set of relation names: each member of a
     * cycle contributes exactly one self-reachable edge, but the dedup makes that
     * guarantee explicit (mirroring {@link #buildDependencies()}).
     */
    private static QueryRelationSymbol buildCycles() {
        Schema schema = new Schema(List.of(
                new ColumnDefinition("name", ScalarType.STRING)));

        RelNode body = new DistinctNode(
                new ProjectionNode(
                        List.of(ProjectedAttribute.aliased(
                                new AttributeOperand("dependent"), "name")),
                        new SelectionNode(
                                new ComparisonPredicate(
                                        new AttributeOperand("dependent"),
                                        ComparisonOperator.EQUAL,
                                        new AttributeOperand("depends_on")),
                                new ClosureNode(
                                        new RelationNode(CATALOG_NAMESPACE + ".dependencies"),
                                        "dependent", "depends_on"))));

        return new QueryRelationSymbol(
                CATALOG_NAMESPACE, "cycles", Provenance.BUILTIN,
                ShadowPolicy.FORBIDDEN, schema, body);
    }

    /**
     * {@code relix.columns} — inferred schema as data (the {@code
     * information_schema.columns} analogue): one row per column of every relation,
     * {@code (relation, column, type, ordinal, distinct_count, null_count)} where
     * {@code type} is the NF² {@link com.darkcollective.relix.symbol.Type#code()}.
     * An open (schema-on-read) relation contributes a single {@code "*"} row typed
     * {@code ANY}.
     *
     * <p>{@code distinct_count}/{@code null_count} are the column's collected
     * {@link ColumnStatistics} (exact for inline relations, the catalog figures for
     * an introspected JDBC table).  Each is NULL — its key omitted — when not
     * collected: an open-schema {@code "*"} row, a view's column, or any source
     * whose column statistics were not gathered.
     */
    private static SystemRelationSymbol columnsSymbol(
            List<RelationSymbol> relations, Map<String, RelationStatistics> statistics) {
        Schema schema = new Schema(List.of(
                new ColumnDefinition("relation", ScalarType.STRING),
                new ColumnDefinition("column", ScalarType.STRING),
                new ColumnDefinition("type", ScalarType.STRING),
                new ColumnDefinition("ordinal", ScalarType.NUMBER),
                new ColumnDefinition("distinct_count", ScalarType.NUMBER),
                new ColumnDefinition("null_count", ScalarType.NUMBER)));

        List<Map<String, Operand>> rows = new ArrayList<>();
        for (RelationSymbol rel : relations) {
            Schema relSchema = rel.schema();
            if (relSchema.isOpen()) {
                rows.add(columnRow(rel.declaredName(), "*", ScalarType.ANY.code(), 0, null));
                continue;
            }
            RelationStatistics stats = statistics.get(rel.canonicalName());
            List<ColumnDefinition> cols = relSchema.columns();
            for (int i = 0; i < cols.size(); i++) {
                ColumnDefinition col = cols.get(i);
                ColumnStatistics colStats =
                        stats == null ? null : stats.column(col.name()).orElse(null);
                rows.add(columnRow(rel.declaredName(), col.name(), col.type().code(), i, colStats));
            }
        }
        // PERMITTED so the post-inference fill can overwrite the placeholder.
        return catalogRelation("columns", schema, ShadowPolicy.PERMITTED, rows);
    }

    /**
     * One {@code relix.columns} row.  A null {@code colStats} (or an absent
     * distinct/null figure within it) leaves the corresponding count column NULL.
     */
    private static Map<String, Operand> columnRow(
            String relation, String column, String typeCode, int ordinal,
            ColumnStatistics colStats) {
        Map<String, Operand> row = new LinkedHashMap<>();
        row.put("relation", new StringOperand(relation));
        row.put("column", new StringOperand(column));
        row.put("type", new StringOperand(typeCode));
        row.put("ordinal", new NumberOperand(Integer.toString(ordinal)));
        if (colStats != null) {
            colStats.distinctCount().ifPresent(d ->
                    row.put("distinct_count", new NumberOperand(Long.toString(d))));
            colStats.nullCount().ifPresent(n ->
                    row.put("null_count", new NumberOperand(Long.toString(n))));
        }
        return row;
    }

    /**
     * {@code relix.plan} — the logical expression tree as data: one row per
     * IR node of every view's body, {@code (query, node_id, parent_id, ordinal, op,
     * label, relation, schema_code, materialization, depth)}.  This is the queryable sibling
     * of the ASCII {@code :tree} rendering — both walk the same structure via
     * {@link IrReport#nodeChildren} and label it via {@link IrReport#baseLabel}, so
     * they cannot drift.
     *
     * <ul>
     *   <li>{@code query} — the owning view's declared name.</li>
     *   <li>{@code node_id} — a stable, deterministic id assigned by a pre-order
     *       walk of the body (root is {@code 0}); {@code parent_id} is the parent's
     *       id (null for the root), so {@code (node_id, parent_id)} forms the tree
     *       edge that {@code CLOSURE}/{@code PATH} can traverse.</li>
     *   <li>{@code ordinal} — the node's position among its siblings (0-based).</li>
     *   <li>{@code op} — the {@link RelNode} type ({@link LogicalPlanJson#opName});
     *       {@code label} — its {@link IrReport#baseLabel} form (no mat tag).</li>
     *   <li>{@code relation} — for a {@link RelationNode} leaf, the bare name of the
     *       relation it references ({@code op = "Relation"}); null for every other
     *       node.  This is the clean join key (unlike {@code label}, which decorates
     *       the name with a kind tag) that lets {@code relix.dependencies} be derived
     *       as {@code π query→dependent, relation→depends_on (σ op = "Relation"
     *       (relix.plan))}.</li>
     *   <li>{@code schema_code} — the node's inferred output schema as a
     *       {@link com.darkcollective.relix.symbol.Type#code()} string ({@code {id:N,…}},
     *       {@code ?} for an open schema), or null when unresolved.</li>
     *   <li>{@code materialization} — stream | bag | set | sort.</li>
     *   <li>{@code depth} — the node's depth from the view root (root is {@code 0}).</li>
     * </ul>
     *
     * <p>Only views ({@link QueryRelationSymbol}) have bodies, so only views
     * contribute rows; a base relation appears as a {@code RelationNode} leaf
     * inside the views that reference it (mirroring {@code relix.dependencies}).
     */
    private static SystemRelationSymbol planSymbol(List<Map<String, Operand>> rows) {
        Schema schema = new Schema(List.of(
                new ColumnDefinition("query", ScalarType.STRING),
                new ColumnDefinition("node_id", ScalarType.NUMBER),
                new ColumnDefinition("parent_id", ScalarType.NUMBER),
                new ColumnDefinition("ordinal", ScalarType.NUMBER),
                new ColumnDefinition("op", ScalarType.STRING),
                new ColumnDefinition("label", ScalarType.STRING),
                new ColumnDefinition("relation", ScalarType.STRING),
                new ColumnDefinition("schema_code", ScalarType.STRING),
                new ColumnDefinition("materialization", ScalarType.STRING),
                new ColumnDefinition("depth", ScalarType.NUMBER)));
        // PERMITTED so the post-inference fill can overwrite the placeholder.
        return catalogRelation("plan", schema, ShadowPolicy.PERMITTED, rows);
    }

    /** One row per IR node across every view body, in view-then-pre-order order. */
    private static List<Map<String, Operand>> buildPlanRows(
            SymbolTable table, SchemaAnnotations schemas) {
        List<Map<String, Operand>> rows = new ArrayList<>();
        for (RelationSymbol rel : userRelations(table)) {
            if (rel instanceof QueryRelationSymbol view) {
                appendPlanRows(rows, view.declaredName(), view.body(),
                        null, 0, 0, new int[]{0}, table, schemas);
            }
        }
        return rows;
    }

    /**
     * Emits one row for {@code node} then recurses pre-order into its children.
     * {@code counter} is a single-element holder so node ids are assigned densely
     * in pre-order across the whole walk; {@code parentId} is null only for the
     * view root (which omits the {@code parent_id} column → NULL).
     */
    private static void appendPlanRows(
            List<Map<String, Operand>> rows, String query, RelNode node,
            Integer parentId, int ordinal, int depth, int[] counter,
            SymbolTable table, SchemaAnnotations schemas) {
        int id = counter[0]++;
        Map<String, Operand> row = new LinkedHashMap<>();
        row.put("query", new StringOperand(query));
        row.put("node_id", new NumberOperand(Integer.toString(id)));
        if (parentId != null) {
            row.put("parent_id", new NumberOperand(Integer.toString(parentId)));
        } // else omit → NULL for the root
        row.put("ordinal", new NumberOperand(Integer.toString(ordinal)));
        row.put("op", new StringOperand(LogicalPlanJson.opName(node)));
        row.put("label", new StringOperand(IrReport.baseLabel(node, table)));
        if (node instanceof RelationNode rel) {
            row.put("relation", new StringOperand(rel.name()));
        } // else omit → NULL: only leaf relation references name a relation
        schemaCode(schemas.get(node)).ifPresent(code ->
                row.put("schema_code", new StringOperand(code)));
        row.put("materialization", new StringOperand(nodeMaterialization(node)));
        row.put("depth", new NumberOperand(Integer.toString(depth)));
        rows.add(row);

        List<RelNode> children = IrReport.nodeChildren(node);
        for (int i = 0; i < children.size(); i++) {
            appendPlanRows(rows, query, children.get(i), id, i, depth + 1,
                    counter, table, schemas);
        }
    }

    /**
     * Renders a node's inferred output schema as a {@code Type.code()}-style string:
     * {@code {col:code,…}} for a closed schema, {@code ?} for an open one, or empty
     * when the schema is absent or the inference placeholder (a single {@code *:ANY}).
     */
    private static Optional<String> schemaCode(Optional<Schema> maybe) {
        if (maybe.isEmpty()) {
            return Optional.empty();
        }
        Schema schema = maybe.get();
        if (schema.isOpen()) {
            return Optional.of(ScalarType.ANY.code());
        }
        List<ColumnDefinition> cols = schema.columns();
        if (cols.size() == 1 && cols.get(0).name().equals("*")
                && cols.get(0).type() == ScalarType.ANY) {
            return Optional.empty();   // unresolved placeholder → NULL
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(cols.get(i).name()).append(':').append(cols.get(i).type().code());
        }
        return Optional.of(sb.append('}').toString());
    }

    /** The output materialisation of a single IR node. */
    private static String nodeMaterialization(RelNode node) {
        return switch (node.materializationMode()) {
            case STREAM -> "stream";
            case BAG    -> "bag";
            case SET    -> "set";
            case SORTED -> "sort";
        };
    }

    /**
     * {@code relix.functions} — the callable scalar-function library: every function
     * the installed libraries offer (regardless of what the script uses) plus every
     * user {@code def}.  Columns: {@code (name, category, arity, max_arity,
     * return_type, pure, deterministic, idempotent, is_builtin)}.
     *
     * <p>A function is one row.  {@code arity} is the smallest argument count it
     * accepts and {@code max_arity} the largest, with {@code -1} for a function that
     * takes any number: {@code Round}, which may be called with one argument or two,
     * is a single row reading {@code 1}/{@code 2} rather than a row per form.
     */
    private static RelationSymbol buildFunctions(SymbolTable table, FunctionCatalog functions) {
        Schema schema = new Schema(List.of(
                new ColumnDefinition("name", ScalarType.STRING),
                new ColumnDefinition("category", ScalarType.STRING),
                new ColumnDefinition("arity", ScalarType.NUMBER),
                new ColumnDefinition("max_arity", ScalarType.NUMBER),
                new ColumnDefinition("return_type", ScalarType.STRING),
                new ColumnDefinition("pure", ScalarType.STRING),
                new ColumnDefinition("deterministic", ScalarType.STRING),
                new ColumnDefinition("idempotent", ScalarType.STRING),
                new ColumnDefinition("is_builtin", ScalarType.STRING)));

        List<Map<String, Operand>> rows = new ArrayList<>();
        for (ScalarFunction fn : functions.scalars()) {
            var signature = fn.signature();
            rows.add(functionRow(signature.name(), signature.category(),
                    signature.arity().min(), signature.arity().max(),
                    signature.returnType().code(), signature.properties(), true));
        }
        // User-defined functions live in the symbol table (collected in Phase 3).
        for (Symbol sym : table.allSymbols()) {
            if (sym instanceof FunctionSymbol fn && fn.provenance() == Provenance.USER) {
                int arity = fn.parameters().size();
                rows.add(functionRow(fn.declaredName(), "user", arity, arity,
                        fn instanceof RelationFunctionSymbol ? "relation" : fn.returnType().code(),
                        fn.properties(), false));
            }
        }
        return catalogRelation("functions", schema, ShadowPolicy.FORBIDDEN, rows);
    }

    /**
     * {@code relix.version} — what is installed in this process, one row per component:
     * {@code (component, kind, version)}.
     *
     * <p>The question it answers is not "what release is this" but "what is actually
     * loaded". Providers are discovered by {@code ServiceLoader}, so a missing one fails
     * nothing until a query needs it, and an installed one nobody expected is invisible
     * until it wins a clash. A row per component makes both visible, and makes them
     * visible to a <em>query</em> — joinable and filterable with the machinery a caller
     * already has, rather than through a second introspection surface in Java.
     *
     * <p>The engine reports what it holds: itself, and the function libraries it resolves
     * against. Everything else comes from the host, because everything else lives above
     * the engine or behind a dependency it is not allowed to have.
     *
     * <p>A {@code version} of {@code unknown} is the honest answer for a provider on a
     * plain class path, which carries no version anywhere the runtime can read. The name
     * is the part that answers the question.
     */
    private static RelationSymbol buildVersion(FunctionCatalog functions,
                                               ComponentInventory components) {
        Schema schema = new Schema(List.of(
                new ColumnDefinition("component", ScalarType.STRING),
                new ColumnDefinition("kind", ScalarType.STRING),
                new ColumnDefinition("version", ScalarType.STRING)));

        List<Map<String, Operand>> rows = new ArrayList<>();
        rows.add(versionRow(new ComponentInventory.Component(
                "relix-engine", ComponentInventory.ENGINE, ComponentInventory.engineVersion())));
        for (var library : functions.libraries()) {
            rows.add(versionRow(ComponentInventory.Component.of(
                    library.name(), ComponentInventory.FUNCTION_LIBRARY, library.getClass())));
        }
        components.components().forEach(component -> rows.add(versionRow(component)));
        return catalogRelation("version", schema, ShadowPolicy.FORBIDDEN, rows);
    }

    private static Map<String, Operand> versionRow(ComponentInventory.Component component) {
        Map<String, Operand> row = new LinkedHashMap<>();
        row.put("component", new StringOperand(component.name()));
        row.put("kind", new StringOperand(component.kind()));
        row.put("version", new StringOperand(component.version()));
        return row;
    }

    private static Map<String, Operand> functionRow(
            String name, String category, int minArity, int maxArity, String returnType,
            Set<FunctionProperty> props, boolean builtin) {
        Map<String, Operand> row = new LinkedHashMap<>();
        row.put("name", new StringOperand(name));
        row.put("category", new StringOperand(category));
        row.put("arity", new NumberOperand(Integer.toString(minArity)));
        row.put("max_arity", new NumberOperand(Integer.toString(maxArity)));
        row.put("return_type", new StringOperand(returnType));
        row.put("pure", boolOperand(props.contains(FunctionProperty.PURE)));
        row.put("deterministic", boolOperand(props.contains(FunctionProperty.DETERMINISTIC)));
        row.put("idempotent", boolOperand(props.contains(FunctionProperty.IDEMPOTENT)));
        row.put("is_builtin", boolOperand(builtin));
        return row;
    }

    /**
     * {@code relix.connections} — the declared database connections, one row each:
     * {@code (name, dialect)}.  Deliberately <strong>redacted</strong>: the JDBC
     * {@code url}, {@code user}, and {@code password} (which routinely carry
     * credentials) are never exposed (ADR-0007, Decision 5).
     *
     * <p>{@code dialect} is the connection's declared {@code dialect:} hint, or
     * {@code "generic"} when none was given (the URL-based dialect inference lives in
     * the planner and is intentionally not replicated here, so no URL is read).
     * Per-table binding information (a {@code table} column) is a deferred follow-on;
     * a connection spans many tables, which are already visible as {@code SRC} rows
     * in {@code relix.relations}.
     */
    private static RelationSymbol buildConnections(Map<String, ConnectionDeclaration> connections) {
        Schema schema = new Schema(List.of(
                new ColumnDefinition("name", ScalarType.STRING),
                new ColumnDefinition("dialect", ScalarType.STRING)));

        List<Map<String, Operand>> rows = new ArrayList<>(connections.size());
        for (ConnectionDeclaration conn : connections.values()) {
            Map<String, Operand> row = new LinkedHashMap<>();
            row.put("name", new StringOperand(conn.name()));
            // Read the dialect from the raw properties (not config(), which requires a
            // JDBC url) so the catalog covers non-JDBC connections too.
            row.put("dialect", new StringOperand(conn.properties().getOrDefault("dialect", "generic")));
            rows.add(row);
        }
        return catalogRelation("connections", schema, ShadowPolicy.FORBIDDEN, rows);
    }

    /**
     * {@code relix.events} — the observability feed as data, one row per observed
     * decision: {@code (seq, stage, code, description, target, rows)}.
     *
     * <p>This is the temporal half of the catalog. Its rows are the events the
     * session host collected while running the <em>previous</em> statement —
     * optimizer rule firings ({@code stage = "OPTIMIZE"}), the planner's physical
     * choices ({@code "PLAN"}), and decisions taken during execution
     * ({@code "EXECUTE"}) — so what {@code --trace} prints becomes something
     * {@code σ}/{@code γ} can read:
     *
     * <pre>query &#123; γ code, COUNT(*) → fired (σ stage = "OPTIMIZE" (relix.events)) &#125;;</pre>
     *
     * <p>{@code seq} is the event's 1-based arrival position. A {@link QueryEvent}
     * carries no timestamp, so arrival order is the only temporal fact in the feed
     * and {@code seq} is what preserves it — without a stored key, a {@code τ} over
     * the feed would have nothing to sort on, since the algebra guarantees no order
     * a scan did not declare.
     *
     * <p>{@code target} is the query or relation the event applies to, and is NULL
     * for an event whose emitter did not know one — the key is omitted, matching
     * how {@code row_count} handles an uncollected statistic.
     *
     * <p>{@code rows} is the quantity an event measured, and is NULL for the majority
     * that measured nothing — a rule firing describes a rewrite, it does not count
     * anything. It is what makes the feed arithmetic rather than prose: an estimate can
     * be compared against what a run actually delivered without parsing a number back
     * out of {@code description}.
     *
     * <p>{@code elapsed} is how long the step took, and is NULL wherever {@code rows}
     * is: the executing steps are the ones that take time, and a rewrite rule firing
     * takes none worth reporting. It is a real {@code DURATION}, so
     * {@code SECONDS(elapsed)} and {@code τ elapsed DESC} work on it without a unit
     * having to be agreed with the reader. It is wall clock — a number to diagnose a
     * slow query with, never one to assert on.
     *
     * <p>It is {@code elapsed} rather than {@code duration}, which is what
     * {@code EventMetrics} calls it, for a reason the grammar imposes: {@code DURATION}
     * introduces a temporal literal, so a column of that name parses as the start of one
     * and could never be projected or sorted on. A column nothing can reference is worse
     * than a second name for one thing.
     *
     * <p>The extent is empty when the host observed nothing, which is the normal
     * case for a one-shot batch run: it is analysed once, before anything has
     * executed, so there is no earlier feed to carry in.
     */
    private static RelationSymbol buildEvents(List<QueryEvent> events) {
        Schema schema = new Schema(List.of(
                new ColumnDefinition("seq", ScalarType.NUMBER),
                new ColumnDefinition("stage", ScalarType.STRING),
                new ColumnDefinition("code", ScalarType.STRING),
                new ColumnDefinition("description", ScalarType.STRING),
                new ColumnDefinition("target", ScalarType.STRING),
                new ColumnDefinition("rows", ScalarType.NUMBER),
                new ColumnDefinition("elapsed", ScalarType.DURATION)));

        List<Map<String, Operand>> rows = new ArrayList<>(events.size());
        int seq = 0;
        for (QueryEvent event : events) {
            seq++;
            Map<String, Operand> row = new LinkedHashMap<>();
            row.put("seq", new NumberOperand(Integer.toString(seq)));
            row.put("stage", new StringOperand(event.stage().name()));
            row.put("code", new StringOperand(event.code()));
            row.put("description", new StringOperand(event.description()));
            // Absent target → NULL, expressed by omitting the key (as row_count does).
            event.target().ifPresent(t -> row.put("target", new StringOperand(t)));
            // Absent measurement → NULL, by the same omission.
            event.metrics().rows().ifPresent(n -> row.put("rows", new NumberOperand(Long.toString(n))));
            event.metrics().duration().ifPresent(d -> row.put("elapsed", new DurationOperand(d)));
            rows.add(row);
        }
        return catalogRelation("events", schema, ShadowPolicy.FORBIDDEN, rows);
    }

    /**
     * {@code relix.rules} — the optimizer's slice of {@code relix.events}: the
     * logical rewrite rules that fired on the previous statement, in the order
     * they fired.
     *
     * <pre>π seq, code, description, target (σ stage = "OPTIMIZE" (relix.events))</pre>
     *
     * <p>Derived, not computed: like {@code relix.dependencies} over
     * {@code relix.plan}, this is a view over the primitive feed rather than a
     * second Java walk, so the two cannot disagree about what fired. {@code stage}
     * is dropped — it is constant down this branch — while {@code seq} is kept, as
     * it is the feed's only ordering key.
     */
    private static QueryRelationSymbol buildRules() {
        Schema schema = new Schema(List.of(
                new ColumnDefinition("seq", ScalarType.NUMBER),
                new ColumnDefinition("code", ScalarType.STRING),
                new ColumnDefinition("description", ScalarType.STRING),
                new ColumnDefinition("target", ScalarType.STRING)));

        // No `rows`: this is the OPTIMIZE slice, and a rule firing describes a rewrite
        // rather than counting anything, so the column would be NULL in every row. The
        // projection below is the authority on the shape, and it does not select one.
        RelNode body = new ProjectionNode(
                List.of(
                        ProjectedAttribute.simple(new AttributeOperand("seq")),
                        ProjectedAttribute.simple(new AttributeOperand("code")),
                        ProjectedAttribute.simple(new AttributeOperand("description")),
                        ProjectedAttribute.simple(new AttributeOperand("target"))),
                new SelectionNode(
                        new ComparisonPredicate(
                                new AttributeOperand("stage"),
                                ComparisonOperator.EQUAL,
                                new StringOperand("OPTIMIZE")),
                        new RelationNode(CATALOG_NAMESPACE + ".events")));

        return new QueryRelationSymbol(
                CATALOG_NAMESPACE, "rules", Provenance.BUILTIN,
                ShadowPolicy.FORBIDDEN, schema, body);
    }

    /** Booleans surface as STRING "true"/"false" (consistent with the catalog). */
    private static Operand boolOperand(boolean value) {
        return new StringOperand(Boolean.toString(value));
    }

    /** The output materialisation of a relation symbol's extent. */
    private static String materialization(RelationSymbol sym) {
        if (sym instanceof QueryRelationSymbol qr) {
            return switch (qr.body().materializationMode()) {
                case STREAM -> "stream";
                case BAG    -> "bag";
                case SET    -> "set";
                case SORTED -> "sort";
            };
        }
        // Base relations (SRC/INL/DB) are scanned — streamed.
        return "stream";
    }

    /**
     * Wraps a computed extent as a reserved, read-only system catalog relation in
     * the {@link #CATALOG_NAMESPACE} namespace.
     */
    private static SystemRelationSymbol catalogRelation(
            String name, Schema schema, ShadowPolicy shadowPolicy,
            List<Map<String, Operand>> rows) {
        return new SystemRelationSymbol(
                CATALOG_NAMESPACE, name, Provenance.BUILTIN, shadowPolicy, schema, rows);
    }
}

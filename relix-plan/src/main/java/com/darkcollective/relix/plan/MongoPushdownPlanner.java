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

import com.darkcollective.relix.ast.LimitNode;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SortSpecification;
import com.darkcollective.relix.ast.UnnestNode;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.json.JsonStrings;
import com.darkcollective.relix.cost.Ordering;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.function.FunctionContext;
import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.source.ConnectionTableSourceConfig;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.symbol.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Translates a maximal sub-tree of selection / unnest / projection / limit over a
 * single MongoDB connection's collection into one {@link PhysicalNode.PushedScan}
 * carrying an aggregation-pipeline query, so the work runs in MongoDB instead of the
 * relix engine — the document-store {@link PushdownRenderer} of ADR-0011, the
 * counterpart to {@link SqlPushdownPlanner}.
 *
 * <p>Each operator folds into a pipeline stage in legal pipeline order
 * ({@code $match}→{@code $unwind}→{@code $project}→{@code $skip}/{@code $limit}):
 * <ul>
 *   <li>a {@link SelectionNode} (σ) folds into {@code $match} while no projection or
 *       limit has been folded yet;</li>
 *   <li>an inner {@link UnnestNode} (μ) folds into {@code $unwind} under the same rule
 *       (an outer unnest or {@code WITH ORDINALITY} falls back to in-engine);</li>
 *   <li>a {@link ProjectionNode} (π) folds into {@code $project} while no limit has been
 *       folded yet: bare, unaliased columns fold into inclusion ({@code : 1}); a computed
 *       temporal-function expression with an alias folds into a {@code $project} expression
 *       via {@link MongoExpressions#expression} (e.g. {@code YEAR(ts) → yr} →
 *       {@code {"yr": {"$year": "$ts"}}}); anything else — a bare-column rename, a
 *       non-temporal expression, or a computed expression without an alias — falls back;</li>
 *   <li>a {@link LimitNode} (λ) folds into {@code $skip}/{@code $limit}.</li>
 * </ul>
 *
 * <p>The pushed {@link PhysicalNode.PushedScan#nativeQuery()} is a small JSON envelope
 * {@code {"collection": "<name>", "pipeline": [ <stage>, … ]}} — the collection cannot
 * travel in the pipeline itself, so it rides alongside it, the way SQL's {@code FROM}
 * names the table.  The owning connector ({@code openQuery}) parses the envelope and
 * runs the pipeline.
 *
 * <p>Anything else — a non-connection leaf, an untranslatable predicate, an aliased or
 * computed projection, aggregation/sort (not rendered, per ADR-0011) — makes {@link #tryPush}
 * return empty and the planner falls back to in-engine execution.
 */
final class MongoPushdownPlanner implements PushdownRenderer {

    @Override
    public void useFunctionContext(FunctionContext context) {
        this.functions = functions.withContext(context);
    }

    private final SchemaAnnotations schemas;
    private final Map<String, SourceDeclaration> sources;
    private final Map<String, ConnectionDeclaration> connections;
    /**
     * The functions the tree was analysed against — the only place a function's
     * aggregation-expression spelling comes from. An empty catalogue means no function
     * folds into a pipeline, which is a slower plan and never a wrong one.
     */
    private PushdownFunctions functions;

    MongoPushdownPlanner(SchemaAnnotations schemas,
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
        return build(node).map(MongoPushdownPlanner::toScan);
    }

    @Override
    public Optional<PhysicalNode.PushedScan> tryPushOrdered(RelNode node, List<SortSpecification> keys) {
        // This renderer does not push τ→$sort (ADR-0011), so a document source
        // never advertises a delivered ordering and cannot feed a merge join.
        return Optional.empty();
    }

    private static PhysicalNode.PushedScan toScan(Pushed p) {
        return new PhysicalNode.PushedScan(p.schema, p.connectorType, p.connection, p.toQuery(),
                Ordering.none());
    }

    private Optional<Pushed> build(RelNode node) {
        return switch (node) {
            case RelationNode r   -> base(r);
            case SelectionNode s  -> selection(s);
            case UnnestNode u     -> unnest(u);
            case ProjectionNode p -> projection(p);
            case LimitNode l      -> limit(l);
            default               -> Optional.empty();
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
            return Optional.empty();   // not one of this renderer's (mongodb) connections
        }
        // A bare named query ("query Docs;") plans a fresh leaf that schema inference
        // does not annotate; without a schema we cannot push, so fall back to a Scan.
        Optional<Schema> schema = schemas.get(node);
        return schema.map(s -> new Pushed(declaration.connectorType(), connection, table.table(), s));
    }

    private Optional<Pushed> selection(SelectionNode node) {
        Optional<Pushed> input = build(node.input());
        if (input.isEmpty()) {
            return Optional.empty();
        }
        Pushed p = input.get();
        if (p.projectionApplied || p.limitApplied) {
            return Optional.empty();   // $match must precede $project and $limit
        }
        Optional<String> match = MongoExpressions.match(node.predicate());
        if (match.isEmpty()) {
            return Optional.empty();
        }
        p.stages.add("{\"$match\": " + match.get() + "}");
        p.schema = schemaOf(node);     // unchanged by selection, but keep annotations authoritative
        return Optional.of(p);
    }

    private Optional<Pushed> unnest(UnnestNode node) {
        // $unwind cannot reproduce outer (NULL-emitting) unnest or WITH ORDINALITY
        // faithfully in this slice; fall back to in-engine.
        if (node.outer() || node.ordinalityColumn().isPresent()) {
            return Optional.empty();
        }
        Optional<Pushed> input = build(node.input());
        if (input.isEmpty()) {
            return Optional.empty();
        }
        Pushed p = input.get();
        if (p.projectionApplied || p.limitApplied) {
            return Optional.empty();   // $unwind must precede $project and $limit
        }
        p.stages.add("{\"$unwind\": " + JsonStrings.quote("$" + node.column()) + "}");
        p.schema = schemaOf(node);
        return Optional.of(p);
    }

    private Optional<Pushed> projection(ProjectionNode node) {
        Optional<Pushed> input = build(node.input());
        if (input.isEmpty()) {
            return Optional.empty();
        }
        Pushed p = input.get();
        if (p.projectionApplied || p.limitApplied) {
            return Optional.empty();
        }
        StringJoiner fields = new StringJoiner(", ", "{", "}");
        for (ProjectedAttribute attr : node.attributes()) {
            if (attr.expression() instanceof AttributeOperand a && attr.alias().isEmpty()) {
                // bare unaliased column → $project inclusion
                fields.add(JsonStrings.quote(SqlExpressions.column(a.name())) + ": 1");
            } else if (attr.alias().isPresent() && !(attr.expression() instanceof AttributeOperand)) {
                // computed expression with alias → try to render as a MongoDB expression
                Optional<String> expr = MongoExpressions.expression(attr.expression(), functions);
                if (expr.isEmpty()) {
                    return Optional.empty();
                }
                fields.add(JsonStrings.quote(attr.alias().get()) + ": " + expr.get());
            } else {
                // bare column with alias (field rename) or computed without alias → fall back
                return Optional.empty();
            }
        }
        // MongoDB includes _id in an inclusion projection unless it is explicitly
        // suppressed, so a PROJ-004 narrowing π would push {"a": 1} and get _id back on
        // every document — exactly the bytes the pushdown exists to avoid. Suppressed
        // unless the schema declares a column of that name, in which case the user
        // asked for it.
        if (schemaOf(node).column("_id").isEmpty()) {
            fields.add("\"_id\": 0");
        }
        p.stages.add("{\"$project\": " + fields + "}");
        // A *column-pruning* π introduces no new field name, and the pipeline is
        // ordered, so a later `$match`/`$unwind` over the surviving fields is still
        // valid after this `$project`. Only a computed/renaming projection blocks
        // what follows. (See the same rule in SqlPushdownPlanner: without it, the
        // optimizer's PROJ-004 costs the pushdown it was meant to narrow.)
        p.projectionApplied = !node.isColumnPruning();
        p.schema = schemaOf(node);
        return Optional.of(p);
    }

    private Optional<Pushed> limit(LimitNode node) {
        Optional<Pushed> input = build(node.input());
        if (input.isEmpty()) {
            return Optional.empty();
        }
        Pushed p = input.get();
        if (p.limitApplied) {
            return Optional.empty();
        }
        long offset = node.offset().orElse(0L);
        if (offset > 0) {
            p.stages.add("{\"$skip\": " + offset + "}");
        }
        p.stages.add("{\"$limit\": " + node.count() + "}");
        p.limitApplied = true;
        p.schema = schemaOf(node);
        return Optional.of(p);
    }

    private Schema schemaOf(RelNode node) {
        return schemas.require(node, "during MongoDB pushdown");
    }

    /** Mutable accumulator for one collection's pushable sub-tree (never shared). */
    private static final class Pushed {
        final String connectorType;
        final String connection;
        final String collection;
        final List<String> stages = new ArrayList<>();   // JSON pipeline-stage documents
        boolean projectionApplied = false;
        boolean limitApplied = false;
        Schema schema;

        Pushed(String connectorType, String connection, String collection, Schema schema) {
            this.connectorType = connectorType;
            this.connection = connection;
            this.collection = collection;
            this.schema = schema;
        }

        String toQuery() {
            return "{\"collection\": " + JsonStrings.quote(collection)
                    + ", \"pipeline\": [" + String.join(", ", stages) + "]}";
        }
    }
}

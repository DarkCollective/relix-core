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

import com.darkcollective.relix.ast.SortDirection;
import com.darkcollective.relix.ast.SortSpecification;
import com.darkcollective.relix.ast.WindowFrame;
import com.darkcollective.relix.ast.WindowFunction;
import com.darkcollective.relix.ast.visitor.OperandPrettyPrinter;
import com.darkcollective.relix.json.JsonWriter;
import com.darkcollective.relix.semantic.SchemaJson;

import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Serializes a physical {@link PhysicalNode} plan to JSON.
 *
 * <p>This is the machine-readable counterpart to {@link PhysicalPlanPrinter}'s
 * ASCII tree: like that printer it surfaces the <em>physical</em> decisions
 * invisible in the logical IR — the SQL pushed to a connection
 * ({@link PhysicalNode.PushedScan}) and each join's algorithm and build side — but
 * as structured output for the playground's physical-plan panel.
 *
 * <h2>Node shape</h2>
 * Every node is a JSON object with {@code op} (the operator name), its already
 * resolved {@code schema} ({@code {open, columns}}), {@code estimatedRows} (the
 * planner's cardinality estimate, or {@code null} when it made none — which is
 * <em>not</em> the same as {@code 0}), and {@code children}.
 * Nodes that carry a physical decision add fields:
 * <ul>
 *   <li>{@code Scan} — {@code source} (the base relation name)</li>
 *   <li>{@code PushedScan} — {@code connectorType}, {@code connection}, and the pushed {@code
 *   query}</li>
 *   <li>{@code Join} — {@code kind}, {@code algorithm}, {@code build}</li>
 *   <li>{@code SetOp} — {@code kind}</li>
 *   <li>{@code Unnest} — {@code column}, {@code outer}, {@code ordinality} (if present)</li>
 *   <li>{@code Limit} — {@code count}, {@code offset} (or null)</li>
 *   <li>{@code Distinct}/{@code Aggregate} — {@code streaming} (single-pass over an ordered
 *   input)</li>
 * </ul>
 * The remaining streaming/materialising operators carry no extra fields — their
 * logical detail is available in the logical-plan JSON.
 *
 * <p>Unlike the logical plan, a physical node's schema is always resolved, so
 * {@code schema} is never null; an open (schema-on-read) node renders as
 * {@code {"open": true, "columns": []}}.
 */
public final class PhysicalPlanJson {

    private static final OperandPrettyPrinter OPND = new OperandPrettyPrinter();

    private PhysicalPlanJson() {}

    /**
     * Serializes the plan rooted at {@code root} to a standalone JSON string.
     *
     * @param root the physical plan root; must not be null
     * @return the JSON document for the plan
     */
    public static String toJson(PhysicalNode root) {
        return toJson(root, PlanEstimates.none());
    }

    /**
     * Serializes the plan rooted at {@code root} to a standalone JSON string,
     * annotating each node with its estimated row count.
     *
     * @param root      the physical plan root; must not be null
     * @param estimates the estimates recorded while planning; must not be null
     * @return the JSON document for the plan
     */
    public static String toJson(PhysicalNode root, PlanEstimates estimates) {
        JsonWriter writer = new JsonWriter();
        write(writer, root, estimates);
        return writer.toJson();
    }

    /**
     * Writes the plan rooted at {@code root} as a single JSON object value into
     * an existing {@link JsonWriter}, so a larger bundle can embed it.
     *
     * @param writer the writer to emit into; must not be null
     * @param root   the physical plan root; must not be null
     */
    public static void write(JsonWriter writer, PhysicalNode root) {
        write(writer, root, PlanEstimates.none());
    }

    /**
     * Writes the plan rooted at {@code root} as a single JSON object value into an
     * existing {@link JsonWriter}, annotating each node with its estimated row count.
     *
     * <p>Every node carries {@code estimatedRows}: a number when the planner costed
     * it, and {@code null} when it did not. {@code null} and {@code 0} are
     * <strong>different answers</strong> — "nobody costed this" versus "this will
     * produce nothing" — and a consumer must not conflate them.
     *
     * @param writer    the writer to emit into; must not be null
     * @param root      the physical plan root; must not be null
     * @param estimates the estimates recorded while planning; must not be null
     */
    public static void write(JsonWriter writer, PhysicalNode root, PlanEstimates estimates) {
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(estimates, "estimates");
        writeNode(writer, root, estimates, new HashSet<>());
    }

    /**
     * @param spooled the ids of the {@link PhysicalNode.Spool}s already written.  A
     *                shared sub-plan is reached from every site that reads it; writing
     *                its sub-tree once per site would let a consumer count work the plan
     *                does once as work it does twice.  The first occurrence carries the
     *                sub-tree, the rest carry {@code "shared": true} and no children.
     */
    private static void writeNode(JsonWriter w, PhysicalNode node, PlanEstimates estimates,
                                  Set<Integer> spooled) {
        boolean repeated = node instanceof PhysicalNode.Spool s && !spooled.add(s.id());
        w.beginObject();
        w.name("op").value(node.getClass().getSimpleName());
        writeDetail(w, node);
        if (repeated) {
            w.name("shared").value(true);
        }
        w.name("estimatedRows");
        OptionalLong rows = estimates.rows(node);
        if (rows.isPresent()) {
            w.value(rows.getAsLong());
        } else {
            w.nullValue();
        }
        w.name("schema");
        SchemaJson.write(w, node.schema());
        w.name("children").beginArray();
        if (!repeated) {
            for (PhysicalNode child : node.children()) {
                writeNode(w, child, estimates, spooled);
            }
        }
        w.endArray();
        w.endObject();
    }

    /** Emits the physical-decision fields specific to {@code node}, if any. */
    private static void writeDetail(JsonWriter w, PhysicalNode node) {
        switch (node) {
            case PhysicalNode.Spool s -> w.name("id").value(s.id());
            case PhysicalNode.Scan s -> {
                w.name("source").value(s.source().declaredName());
                s.produceBound().ifPresent(b -> w.name("produceWhile")
                        .value(b.column() + " " + b.operator().name() + " " + b.limit().accept(OPND)));
            }
            case PhysicalNode.PushedScan s -> {
                w.name("connectorType").value(s.connectorType());
                w.name("connection").value(s.connection());
                w.name("query").value(s.nativeQuery());
            }
            case PhysicalNode.Unnest u -> {
                w.name("column").value(u.column());
                w.name("outer").value(u.outer());
                u.ordinalityColumn().ifPresent(c -> w.name("ordinality").value(c));
            }
            case PhysicalNode.Closure c -> {
                w.name("from").value(c.fromColumn());
                w.name("to").value(c.toColumn());
                w.name("reflexive").value(c.reflexive());
                if (c.undirected()) {
                    w.name("undirected").value(true);
                }
                c.boundSource().ifPresent(o -> w.name("boundSource").value(o.accept(OPND)));
                c.boundTarget().ifPresent(o -> w.name("boundTarget").value(o.accept(OPND)));
            }
            case PhysicalNode.Cluster cl -> {
                w.name("from").value(cl.fromColumn());
                w.name("to").value(cl.toColumn());
                w.name("label").value(cl.labelColumn());
            }
            case PhysicalNode.Path p -> {
                w.name("from").value(p.fromColumn());
                w.name("to").value(p.toColumn());
                if (p.undirected()) {
                    w.name("undirected").value(true);
                }
                w.name("minHops").value(p.minHops());
                w.name("maxHops").value(p.maxHops());
                w.name("depth").value(p.depthColumn());
            }
            case PhysicalNode.Trace tr -> {
                w.name("from").value(tr.fromColumn());
                w.name("to").value(tr.toColumn());
                if (tr.undirected()) {
                    w.name("undirected").value(true);
                }
                w.name("weight").value(tr.weightColumn());
                w.name("sense").value(tr.sense().name().toLowerCase());
                w.name("path").value(tr.pathColumn());
                w.name("algorithm").value(tr.algorithm().name().toLowerCase(Locale.ROOT));
                tr.boundSource().ifPresent(o -> w.name("boundSource").value(o.accept(OPND)));
                tr.boundTarget().ifPresent(o -> w.name("boundTarget").value(o.accept(OPND)));
            }
            case PhysicalNode.Fixpoint f -> w.name("name").value(f.name());
            case PhysicalNode.RecursiveRef r -> w.name("name").value(r.name());
            case PhysicalNode.Limit l -> {
                w.name("count").value(l.count());
                w.name("offset");
                if (l.offset().isPresent()) {
                    w.value(l.offset().get());
                } else {
                    w.nullValue();
                }
            }
            case PhysicalNode.Join j -> {
                w.name("kind").value(lower(j.kind().name()));
                w.name("algorithm").value(lower(j.algorithm().name()));
                w.name("build").value(lower(j.buildSide().name()));
            }
            case PhysicalNode.AsOfJoin a -> {
                w.name("direction").value(a.backward() ? "backward" : "forward");
                w.name("strict").value(a.strict());
                w.name("inner").value(a.inner());
                w.name("tieBreak").value(lower(a.tieBreak().name()));
                w.name("tolerance");
                if (a.tolerance().isPresent()) {
                    w.value(a.tolerance().get().toString());
                } else {
                    w.nullValue();
                }
            }
            case PhysicalNode.IntervalJoin j -> {
                w.name("relation").value(lower(j.relation().name()));
                w.name("leftStartIdx").value(j.leftStartIdx());
                w.name("leftEndIdx").value(j.leftEndIdx());
                w.name("rightStartIdx").value(j.rightStartIdx());
                w.name("rightEndIdx").value(j.rightEndIdx());
                w.name("merge").value(j.merge());
            }
            case PhysicalNode.SetOp s ->
                    w.name("kind").value(lower(s.kind().name()));

            // Operators with no physical decision to surface (detail lives in the
            // logical plan); listed explicitly so a new node type must be handled.
            case PhysicalNode.Select s    -> { }
            case PhysicalNode.Project p   -> { }
            case PhysicalNode.Rename r    -> { }
            case PhysicalNode.Distinct d  -> w.name("streaming").value(d.streaming());
            case PhysicalNode.Sort s      -> { }
            case PhysicalNode.Aggregate a -> w.name("streaming").value(a.streaming());
            case PhysicalNode.Division d  -> { }

            case PhysicalNode.Universal u -> {
                w.name("groupingAttributes").beginArray();
                for (String key : u.groupingAttributes()) {
                    w.value(key);
                }
                w.endArray();
            }

            case PhysicalNode.Solve s -> {
                w.name("left").value(s.left().accept(OPND));
                w.name("right").value(s.right().accept(OPND));
            }

            case PhysicalNode.Optimize o -> {
                w.name("sense").value(lower(o.sense().name()));
                w.name("objective").value(o.objective().accept(OPND));
                w.name("constraints").beginArray();
                for (var c : o.constraints()) {
                    w.beginObject();
                    w.name("expr").value(c.expr().accept(OPND));
                    w.name("op").value(c.op().name());
                    w.name("bound").value(c.bound());
                    w.endObject();
                }
                w.endArray();
                w.name("groupingKeys").beginArray();
                for (String key : o.groupingKeys()) {
                    w.value(key);
                }
                w.endArray();
                o.allocation().ifPresent(spec -> {
                    w.name("allocation").beginObject();
                    w.name("lo").value(spec.lo());
                    w.name("hi").value(spec.hi());
                    w.name("column").value(spec.columnName());
                    w.endObject();
                });
            }

            case PhysicalNode.TopK t -> {
                w.name("count").value(t.count());
                w.name("offset");
                if (t.offset().isPresent()) {
                    w.value(t.offset().get());
                } else {
                    w.nullValue();
                }
                w.name("groupingAttributes").beginArray();
                for (String key : t.groupingAttributes()) {
                    w.value(key);
                }
                w.endArray();
            }

            case PhysicalNode.Window win -> {
                w.name("kind").value(win.function() instanceof WindowFunction.AggregateWindow
                        ? "rolling" : "window");
                w.name("function").value(windowFunctionLabel(win.function()));
                w.name("frame").value(windowFrameLabel(win.frame()));
                if (!win.partitionKeys().isEmpty()) {
                    w.name("partitionKeys").beginArray();
                    for (String k : win.partitionKeys()) w.value(k);
                    w.endArray();
                }
                w.name("sortSpecs").beginArray();
                for (var spec : win.sortSpecs()) {
                    w.value(spec.expression().accept(OPND)
                            + (spec.direction() == SortDirection.DESC ? " DESC" : " ASC"));
                }
                w.endArray();
                w.name("outputColumn").value(win.outputColumn());
            }

            case PhysicalNode.Sessionize s -> {
                w.name("orderColumn").value(s.orderColumn());
                w.name("threshold").value(s.threshold().accept(OPND));
                if (!s.partitionKeys().isEmpty()) {
                    w.name("partitionKeys").beginArray();
                    for (String k : s.partitionKeys()) w.value(k);
                    w.endArray();
                }
                w.name("sessionColumn").value(s.sessionColumn());
            }

            case PhysicalNode.BernoulliSample s -> {
                w.name("probability").value(s.probability());
                s.seed().ifPresent(seed -> w.name("seed").value(seed));
            }

            case PhysicalNode.ReservoirSample s -> {
                w.name("count").value(s.count());
                s.seed().ifPresent(seed -> w.name("seed").value(seed));
            }

            case PhysicalNode.Cover v -> {
                w.name("exact").value(v.exact());
                w.name("strength").value(v.strength());
            }

            case PhysicalNode.ConstructiveCover v -> {
                w.name("strength").value(v.strength());
                w.name("conjunctCount").value(v.conjuncts().size());
            }

            case PhysicalNode.Downsample d -> {
                w.name("timestampColumn").value(d.timestampColumn());
                w.name("intervalSeconds").value(d.intervalSeconds());
                w.name("function").value(lower(d.function().name()));
                if (!d.groupingKeys().isEmpty()) {
                    w.name("groupingKeys").beginArray();
                    for (String k : d.groupingKeys()) w.value(k);
                    w.endArray();
                }
                if (d.maxRows().isPresent()) w.name("maxRows").value(d.maxRows().getAsLong());
            }

            case PhysicalNode.LateralJoin l -> {
                w.name("functionName").value(l.functionName());
                w.name("arguments").beginArray();
                for (var arg : l.arguments()) {
                    w.value(arg.accept(OPND));
                }
                w.endArray();
            }

            case PhysicalNode.Unpivot uv -> {
                w.name("columns").beginArray();
                for (String col : uv.columns()) w.value(col);
                w.endArray();
                w.name("nameColumn").value(uv.nameColumn());
                w.name("valueColumn").value(uv.valueColumn());
            }

            case PhysicalNode.Pivot pv -> {
                w.name("valueColumn").value(pv.valueColumn());
                w.name("keyColumn").value(pv.keyColumn());
                if (!pv.groupKeys().isEmpty()) {
                    w.name("groupKeys").beginArray();
                    for (String k : pv.groupKeys()) w.value(k);
                    w.endArray();
                }
            }

            case PhysicalNode.Tree t -> {
                w.name("keyColumn").value(t.keyColumn());
                w.name("parentColumn").value(t.parentColumn());
                if (!t.orderSpecs().isEmpty()) {
                    w.name("orderSpecs").beginArray();
                    for (SortSpecification spec : t.orderSpecs()) {
                        w.value(spec.expression().accept(OPND)
                                + (spec.direction() == SortDirection.DESC ? " DESC" : " ASC"));
                    }
                    w.endArray();
                }
                w.name("childrenColumn").value(t.childrenColumn());
            }
            // WHY adds no physical-decision fields; the appended provenance column
            // is visible in the node's schema.
            case PhysicalNode.Why why -> { }
            case PhysicalNode.Empty empty -> { }   // no rows, no detail beyond the schema
        }
    }

    private static String lower(String enumName) {
        return enumName.toLowerCase(Locale.ROOT);
    }

    private static String windowFunctionLabel(WindowFunction fn) {
        return switch (fn) {
            case WindowFunction.AggregateWindow a ->
                    a.operator().name() + "(" + a.argument().accept(OPND) + ")";
            case WindowFunction.RankingWindow r ->
                    r.function().name() + "("
                            + r.ntileCount().map(c -> c.accept(OPND)).orElse("") + ")";
            case WindowFunction.OffsetWindow o ->
                    o.function().name() + "(" + o.expression().accept(OPND)
                            + o.offset().map(off -> ", " + off.accept(OPND)).orElse("")
                            + o.defaultValue().map(d -> ", " + d.accept(OPND)).orElse("")
                            + ")";
        };
    }

    private static String windowFrameLabel(WindowFrame frame) {
        return switch (frame) {
            case WindowFrame.BoundedFrame b -> b.n() + " ROWS";
            case WindowFrame.CumulativeFrame ignored -> "ALL ROWS";
            case WindowFrame.PartitionFrame ignored -> "PARTITION";
        };
    }
}

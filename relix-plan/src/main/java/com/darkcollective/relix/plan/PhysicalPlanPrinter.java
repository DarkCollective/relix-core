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

import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.DisplayLabels;
import com.darkcollective.relix.ast.ObjectiveSense;
import com.darkcollective.relix.ast.SortDirection;
import com.darkcollective.relix.ast.WindowFrame;
import com.darkcollective.relix.ast.WindowFunction;
import com.darkcollective.relix.ast.visitor.OperandPrettyPrinter;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Renders a {@link PhysicalNode} plan as an ASCII tree for {@code --explain}.
 *
 * <p>The output focuses on the <em>physical</em> decisions the planner made — the
 * choices that are invisible in the logical IR report: which sub-trees were
 * pushed to the database as a single {@link PhysicalNode.PushedScan} (with the
 * generated native query), and each join's algorithm and build side.  Streaming unary
 * operators (selection, projection, rename, …) are shown by name only; their
 * detail already appears in the IR report.
 *
 * <p>Tree drawing matches the IR report style: {@code └─} for the last child,
 * {@code ├─} for the others, and {@code │ } for continuation.
 *
 * <p>When {@link PlanEstimates} are supplied, each line ends with the row count the
 * planner estimated for that node ({@code  ~1200 rows}), or {@code  ~? rows} where it
 * had none.  That is what makes a cardinality estimate — and so the build-side and
 * merge-versus-hash decisions it drives — auditable from outside the cost model.
 *
 * <h2>Naming convention</h2>
 * <p>This view names operators; it does not restate the algebra, so it uses no
 * operator glyphs of its own. The rule, relative to the IR report's label for
 * the same operator:
 * <ul>
 *   <li>Where the IR uses a <em>glyph</em> ({@code σ}, {@code π}, {@code γ},
 *       {@code ⋈}, {@code ∀}, …), this view uses a Titlecase word —
 *       {@code Select}, {@code Project}, {@code Aggregate}, {@code Join},
 *       {@code Universal}. A glyph would read worse here, because a physical
 *       {@code Select} carries no predicate to go with it.</li>
 *   <li>Where the IR uses an <em>uppercase keyword</em> (an operator with no
 *       glyph in the language — {@code CLOSURE}, {@code TRACE}, {@code TOP},
 *       {@code SESSIONIZE}, {@code COVER}, {@code OPTIMIZE}, …), this view uses
 *       the same keyword, so the two views agree exactly.</li>
 *   <li>Connective keywords inside a label are uppercase either way
 *       ({@code PER}, {@code AS}, {@code BY}, {@code VIA}, {@code SORT},
 *       {@code OVER}, {@code SEED}, {@code OFFSET}).</li>
 *   <li>Physical annotations — the information only this view has — are
 *       lowercase or bracketed: {@code streaming}, {@code /HASH build=LEFT},
 *       {@code [dijkstra]}, {@code [constructive]}.</li>
 * </ul>
 * <p>Sub-expressions shared with the IR report (window and solver clauses, sort
 * keys, generator bounds) come from
 * {@link com.darkcollective.relix.ast.DisplayLabels} and are byte-identical in
 * both views.
 */
public final class PhysicalPlanPrinter {

    private static final OperandPrettyPrinter OPND = new OperandPrettyPrinter();

    private PhysicalPlanPrinter() {
    }

    /**
     * Renders {@code root} and its descendants as a multi-line ASCII tree, without
     * cardinality estimates.
     *
     * @param root the physical plan root; must not be null
     * @return the rendered tree, newline-terminated per line
     */
    public static String explain(PhysicalNode root) {
        return explain(root, PlanEstimates.none());
    }

    /**
     * Renders {@code root} and its descendants as a multi-line ASCII tree, annotating
     * each node with the row count the planner estimated for it.
     *
     * <p>The annotation is appended as {@code  ~N rows}, and a node the planner did
     * not cost prints {@code  ~? rows} — <strong>not</strong> {@code ~0 rows}. The
     * distinction is the point: an estimate of zero is a claim about the data, and
     * "nobody costed this" is not. The whole column is omitted when no node has an
     * estimate at all, so a plan rendered outside a planning run looks exactly as it
     * did before this existed.
     *
     * @param root      the physical plan root; must not be null
     * @param estimates the estimates recorded while planning; must not be null
     *                  (use {@link PlanEstimates#none()} for none)
     * @return the rendered tree, newline-terminated per line
     */
    public static String explain(PhysicalNode root, PlanEstimates estimates) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(estimates, "estimates");
        StringBuilder out = new StringBuilder();
        render(root, "", true, true, estimates, new HashSet<>(), out);
        return out.toString();
    }

    /**
     * @param spooled the ids of the {@link PhysicalNode.Spool}s already drawn.  A shared
     *                sub-plan is reached from every site that reads it, and drawing it
     *                once per site would misrepresent a plan that evaluates it once — so
     *                the first occurrence carries the sub-tree and the rest are marked
     *                {@code (shared)} and drawn as leaves.
     */
    private static void render(PhysicalNode node, String prefix, boolean isLast,
                               boolean isRoot, PlanEstimates estimates,
                               Set<Integer> spooled, StringBuilder out) {
        boolean repeated = node instanceof PhysicalNode.Spool s && !spooled.add(s.id());
        out.append(prefix);
        if (!isRoot) {
            out.append(isLast ? "└─ " : "├─ ");
        }
        out.append(label(node));
        if (repeated) {
            out.append(" (shared)");
        }
        if (!estimates.isEmpty()) {
            OptionalLong rows = estimates.rows(node);
            out.append("  ~")
               .append(rows.isPresent() ? Long.toString(rows.getAsLong()) : "?")
               .append(" rows");
        }
        out.append('\n');
        if (repeated) {
            return;
        }

        List<PhysicalNode> children = node.children();
        String childPrefix = isRoot ? "" : prefix + (isLast ? "   " : "│  ");
        for (int i = 0; i < children.size(); i++) {
            render(children.get(i), childPrefix, i == children.size() - 1, false,
                   estimates, spooled, out);
        }
    }

    private static String label(PhysicalNode node) {
        return switch (node) {
            case PhysicalNode.Scan s     -> "Scan " + s.source().declaredName()
                    + s.produceBound().map(b -> " ⟨produce while "
                            + DisplayLabels.produceBound(b) + "⟩").orElse("");
            case PhysicalNode.PushedScan s -> "PushedScan [" + s.connectorType() + "/" + s.connection() + "] " + s.nativeQuery();
            case PhysicalNode.Spool sp   -> "Spool #" + sp.id();
            case PhysicalNode.Select s   -> "Select";
            case PhysicalNode.Project p  -> "Project";
            case PhysicalNode.Rename r   -> "Rename";
            case PhysicalNode.Distinct d -> "Distinct" + (d.streaming() ? " streaming" : "");
            case PhysicalNode.Unnest u   -> "Unnest " + u.column() + (u.outer() ? " OUTER" : "")
                    + u.ordinalityColumn().map(c -> " ORDINALITY " + c).orElse("");
            case PhysicalNode.Closure c  -> (c.reflexive() ? "RCLOSURE " : "CLOSURE ")
                    + c.fromColumn() + (c.undirected() ? "↔" : "→") + c.toColumn()
                    + c.boundSource().map(o -> " [" + c.fromColumn() + "=" + o.accept(OPND) + "]").orElse("")
                    + c.boundTarget().map(o -> " [" + c.toColumn() + "=" + o.accept(OPND) + "]").orElse("");
            case PhysicalNode.Cluster cl -> "CLUSTER " + cl.fromColumn() + ", " + cl.toColumn()
                    + " AS " + cl.labelColumn();
            case PhysicalNode.Path p -> "PATH " + p.fromColumn()
                    + (p.undirected() ? " ↔ " : ", ") + p.toColumn()
                    + " HOPS " + p.minHops() + " TO " + p.maxHops()
                    + " AS " + p.depthColumn();
            case PhysicalNode.Trace tr -> "TRACE " + tr.fromColumn()
                    + (tr.undirected() ? " ↔ " : ", ") + tr.toColumn()
                    + " VIA " + tr.weightColumn() + " " + tr.sense()
                    + " AS " + tr.pathColumn()
                    + tr.boundSource().map(o -> " [" + tr.fromColumn() + "=" + o.accept(OPND) + "]").orElse("")
                    + tr.boundTarget().map(o -> " [" + tr.toColumn() + "=" + o.accept(OPND) + "]").orElse("")
                    + (tr.algorithm() == com.darkcollective.relix.plan.TraceAlgorithm.DIJKSTRA
                            ? " [dijkstra]" : "");
            case PhysicalNode.Fixpoint f -> "FIX " + f.name();
            case PhysicalNode.RecursiveRef r -> "REF " + r.name();
            case PhysicalNode.Limit l    -> "Limit " + l.count()
                    + l.offset().map(o -> " OFFSET " + o).orElse("");
            case PhysicalNode.Sort s     -> "Sort";
            case PhysicalNode.Aggregate a -> "Aggregate" + (a.streaming() ? " streaming" : "");
            case PhysicalNode.Universal u -> "Universal"
                    + (u.groupingAttributes().isEmpty()
                            ? "" : " " + String.join(", ", u.groupingAttributes()));
            case PhysicalNode.Solve s    -> "SOLVE " + s.left().accept(OPND)
                    + " = " + s.right().accept(OPND);
            case PhysicalNode.Optimize o -> DisplayLabels.optimize(o.sense(), o.objective(),
                    o.constraints(), o.groupingKeys(), o.allocation());
            case PhysicalNode.TopK t      -> "TOP " + t.count()
                    + t.offset().map(o -> " OFFSET " + o).orElse("")
                    + (t.groupingAttributes().isEmpty() ? ""
                            : " PER " + String.join(", ", t.groupingAttributes()));
            case PhysicalNode.Window w    -> DisplayLabels.window(w.function(), w.frame(),
                    w.sortSpecs(), w.partitionKeys(), w.outputColumn());
            case PhysicalNode.Sessionize s -> "SESSIONIZE " + s.orderColumn()
                    + " GAP " + s.threshold().accept(OPND)
                    + (s.partitionKeys().isEmpty() ? ""
                            : " PER " + String.join(", ", s.partitionKeys()))
                    + " AS " + s.sessionColumn();
            case PhysicalNode.BernoulliSample s -> "SAMPLE " + s.probability()
                    + s.seed().map(seed -> " SEED " + seed).orElse("");
            case PhysicalNode.ReservoirSample s -> "SAMPLE " + s.count() + " ROWS"
                    + s.seed().map(seed -> " SEED " + seed).orElse("");
            case PhysicalNode.Cover v    -> (v.exact() ? "COVER EXACT " : "COVER ") + v.strength();
            // Same COVER keyword as the IR label, plus the physical strategy —
            // constructive generation is a planner choice, so :explain names it.
            case PhysicalNode.ConstructiveCover v -> "COVER " + v.strength() + " [constructive"
                    + (v.conjuncts().isEmpty() ? "" : ", " + v.conjuncts().size() + " conjuncts") + "]";
            case PhysicalNode.Join j     -> "Join " + j.kind() + "/" + j.algorithm()
                    + " build=" + j.buildSide();
            case PhysicalNode.AsOfJoin a -> "AsOfJoin " + (a.backward() ? "backward" : "forward")
                    + (a.strict() ? "/strict" : "")
                    + (a.inner() ? "/inner" : "")
                    + a.tolerance().map(d -> "/within " + d).orElse("")
                    + (a.tieBreak() == com.darkcollective.relix.ast.TieBreak.FIRST ? "/ties(first)" : "");
            case PhysicalNode.IntervalJoin j -> "IntervalJoin " + j.relation().name()
                    + (j.merge() ? "/merge" : "")
                    + " [" + j.leftStartIdx() + "," + j.leftEndIdx()
                    + ";" + j.rightStartIdx() + "," + j.rightEndIdx() + "]";
            case PhysicalNode.LateralJoin l -> "LateralJoin " + l.functionName()
                    + "(" + l.arguments().stream()
                            .map(a -> a.accept(OPND)).collect(java.util.stream.Collectors.joining(", "))
                    + ")";
            case PhysicalNode.SetOp s    -> "SetOp " + s.kind();
            case PhysicalNode.Division d -> "Division";
            case PhysicalNode.Downsample d -> {
                String keys = d.groupingKeys().isEmpty() ? "" : " PER " + String.join(", ", d.groupingKeys());
                String limit = d.maxRows().isPresent() ? " FOR " + d.maxRows().getAsLong() + " ROWS" : "";
                yield "DOWNSAMPLE " + d.timestampColumn() + " BY " + d.intervalSeconds() + "s"
                        + " USING " + d.function() + keys + limit;
            }
            case PhysicalNode.Unpivot uv -> "UNPIVOT (" + String.join(", ", uv.columns()) + ")"
                    + " AS (" + uv.nameColumn() + ", " + uv.valueColumn() + ")";
            case PhysicalNode.Pivot pv -> {
                String keys = pv.groupKeys().isEmpty() ? "" : " PER " + String.join(", ", pv.groupKeys());
                yield "PIVOT " + pv.valueColumn() + " BY " + pv.keyColumn() + keys;
            }
            case PhysicalNode.Tree t -> {
                String order = t.orderSpecs().isEmpty() ? "" : " ORDER " + t.orderSpecs().stream()
                        .map(s -> s.expression().accept(OPND)
                                + (s.direction() == com.darkcollective.relix.ast.SortDirection.DESC ? " DESC" : ""))
                        .collect(java.util.stream.Collectors.joining(", "));
                yield "TREE " + t.keyColumn() + " BY " + t.parentColumn() + order
                        + " AS " + t.childrenColumn();
            }
            case PhysicalNode.Why w -> "Why";
            case PhysicalNode.Empty e -> "Empty";
        };
    }


}

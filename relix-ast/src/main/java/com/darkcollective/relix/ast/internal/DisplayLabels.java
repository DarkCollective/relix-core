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
package com.darkcollective.relix.ast.internal;

import com.darkcollective.relix.ast.AllocationSpec;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ObjectiveSense;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.OptimizeConstraint;
import com.darkcollective.relix.ast.ProduceBound;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SortDirection;
import com.darkcollective.relix.ast.SortSpecification;
import com.darkcollective.relix.ast.WindowFrame;
import com.darkcollective.relix.ast.WindowFunction;
import com.darkcollective.relix.ast.visitor.internal.OperandPrettyPrinter;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Shared rendering for the sub-expressions that appear inside a node label.
 *
 * <p>Relix has two tree views: the IR report ({@code :tree}), which shows the
 * logical {@code RelNode} tree, and the physical plan printer
 * ({@code :explain}), which shows the {@code PhysicalNode} plan. They are
 * deliberately different at the <em>node</em> level — only {@code :explain}
 * names a join algorithm, a build side, or a pushed-down native query — but the
 * structural detail <em>inside</em> a label is the same thing in both, and used
 * to be written out twice by hand. The copies drifted: the same window operator
 * printed {@code WINDOW … SORT … PER …} in one view and
 * {@code Window … sort … per …} in the other, and the same solver constraint
 * differed in spacing, separator, and even {@code →} versus {@code ->}.
 *
 * <p>These renderers are the shared answer, in the language's own spelling —
 * uppercase keywords, glyph arrows, bracketed key lists — so that a construct
 * reads identically wherever it is shown. They take node <em>components</em>
 * rather than nodes, because the logical and physical hierarchies are separate
 * sealed types that happen to embed the same parts.
 *
 * <p>Physical annotations are not this class's business: a caller appends its
 * own {@code /HASH build=LEFT} or {@code streaming} on top.
 *
 * @see ComparisonOperator#symbol()
 */
public final class DisplayLabels {

    private static final OperandPrettyPrinter OPND = new OperandPrettyPrinter();

    private DisplayLabels() {
    }

    /**
     * Formats a solver constraint's numeric bound: a finite whole number renders
     * without a fractional part, so {@code 100.0} reads as {@code 100}.
     *
     * @param bound the constraint bound
     * @return the display text
     */
    public static String bound(double bound) {
        if (Double.isFinite(bound) && bound == Math.rint(bound)) {
            return Long.toString((long) bound);
        }
        return Double.toString(bound);
    }

    /**
     * Renders a sort key as {@code expr↑} (ascending) or {@code expr↓}
     * (descending).
     *
     * @param spec the sort specification; must not be null
     * @return the rendered sort key
     */
    public static String sortKey(SortSpecification spec) {
        Objects.requireNonNull(spec, "spec");
        return spec.expression().accept(OPND)
                + (spec.direction() == SortDirection.DESC ? "↓" : "↑");
    }

    /** Renders a comma-separated list of sort keys. */
    private static String sortKeys(List<SortSpecification> specs) {
        return specs.stream().map(DisplayLabels::sortKey).collect(Collectors.joining(", "));
    }

    /**
     * Renders a generator production bound as {@code column ≤ limit}.
     *
     * @param produceBound the bound; must not be null
     * @return the rendered bound
     */
    public static String produceBound(ProduceBound produceBound) {
        Objects.requireNonNull(produceBound, "produceBound");
        return produceBound.column() + " " + produceBound.operator().symbol()
                + " " + produceBound.limit().accept(OPND);
    }

    /**
     * Renders a window function call, e.g. {@code SUM(amount)},
     * {@code ROW_NUMBER()}, or {@code LAG(price, 1)}.
     *
     * @param fn the window function; must not be null
     * @return the rendered call
     */
    public static String windowFunction(WindowFunction fn) {
        Objects.requireNonNull(fn, "fn");
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

    /**
     * Renders a window frame as a leading-space suffix — {@code " OVER 3 ROWS"},
     * {@code " OVER ALL ROWS"}, or empty for a whole-partition frame.
     *
     * @param frame the frame; must not be null
     * @return the rendered frame, or an empty string
     */
    public static String windowFrame(WindowFrame frame) {
        Objects.requireNonNull(frame, "frame");
        return switch (frame) {
            case WindowFrame.BoundedFrame b -> " OVER " + b.n() + " ROWS";
            case WindowFrame.CumulativeFrame ignored -> " OVER ALL ROWS";
            case WindowFrame.PartitionFrame ignored -> "";
        };
    }

    /**
     * Renders a complete window operator label, e.g.
     * {@code ROLLING SUM(amount) OVER 3 ROWS SORT ts↑ PER region AS running}.
     *
     * <p>The keyword is {@code ROLLING} for an aggregate window and
     * {@code WINDOW} for a ranking or offset function, matching the surface
     * syntax that produces each.
     *
     * @param fn            the window function; must not be null
     * @param frame         the frame; must not be null
     * @param sortSpecs     the ordering within a partition; must not be null
     * @param partitionKeys the partition keys, possibly empty; must not be null
     * @param outputColumn  the column the result is written to; must not be null
     * @return the rendered label
     */
    public static String window(WindowFunction fn, WindowFrame frame,
                                List<SortSpecification> sortSpecs,
                                List<String> partitionKeys, String outputColumn) {
        Objects.requireNonNull(sortSpecs, "sortSpecs");
        Objects.requireNonNull(partitionKeys, "partitionKeys");
        Objects.requireNonNull(outputColumn, "outputColumn");
        StringBuilder sb = new StringBuilder(
                fn instanceof WindowFunction.AggregateWindow ? "ROLLING" : "WINDOW")
                .append(' ')
                .append(windowFunction(fn))
                .append(windowFrame(frame))
                .append(" SORT ")
                .append(sortKeys(sortSpecs));
        if (!partitionKeys.isEmpty()) {
            sb.append(" PER ").append(String.join(", ", partitionKeys));
        }
        return sb.append(" AS ").append(outputColumn).toString();
    }

    /**
     * Renders a complete solver label, e.g.
     * {@code OPTIMIZE max SUM(value) s.t. SUM(weight)≤100 [region]}.
     *
     * @param sense         maximise or minimise; must not be null
     * @param objective     the objective expression; must not be null
     * @param constraints   the constraints, possibly empty; must not be null
     * @param groupingKeys  the per-group keys, possibly empty; must not be null
     * @param allocation    the continuous-allocation spec, if any; must not be null
     * @return the rendered label
     */
    public static String optimize(ObjectiveSense sense, Operand objective,
                                  List<OptimizeConstraint> constraints,
                                  List<String> groupingKeys,
                                  Optional<AllocationSpec> allocation) {
        Objects.requireNonNull(sense, "sense");
        Objects.requireNonNull(objective, "objective");
        Objects.requireNonNull(constraints, "constraints");
        Objects.requireNonNull(groupingKeys, "groupingKeys");
        Objects.requireNonNull(allocation, "allocation");
        String rendered = constraints.stream()
                .map(c -> "SUM(" + c.expr().accept(OPND) + ")"
                        + c.op().symbol() + bound(c.bound()))
                .collect(Collectors.joining(", "));
        String keys = groupingKeys.isEmpty()
                ? "" : " [" + String.join(", ", groupingKeys) + "]";
        String mode = allocation
                .map(s -> "ALLOCATE [" + bound(s.lo()) + "," + bound(s.hi())
                        + "] →" + s.columnName() + " ")
                .orElse("");
        return "OPTIMIZE " + mode + (sense == ObjectiveSense.MAXIMIZE ? "max" : "min")
                + " SUM(" + objective.accept(OPND) + ") s.t. " + rendered + keys;
    }
}

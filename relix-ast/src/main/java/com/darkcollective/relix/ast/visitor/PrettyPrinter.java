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
package com.darkcollective.relix.ast.visitor;

import com.darkcollective.relix.ast.*;

import java.util.stream.Collectors;

import static com.darkcollective.relix.ast.TieBreak.FIRST;

/**
 * Serialises a {@link com.darkcollective.relix.ast.RelNode} tree to a Unicode
 * relational algebra string.
 *
 * <p>The output uses standard relational algebra symbols (π σ ρ ⋈ ⨝ ⟕ ⟖ ⟗
 * ⋉ ▷ × ∪ ⊎ − ∩ ÷ ∆ ∘ ∀ γ τ λ δ ω) and is designed to round-trip through the
 * {@code relix-parser} module.
 *
 * <p>Predicate and operand formatting is delegated to
 * {@link PredicatePrettyPrinter} and {@link OperandPrettyPrinter} respectively.
 *
 * <p>Instances are stateless and safe for concurrent use.
 *
 * @see com.darkcollective.relix.ast.RelNode#prettyPrint()
 */
public final class PrettyPrinter implements RelNodeVisitor<String> {
    private final PredicatePrettyPrinter predicatePrinter = new PredicatePrettyPrinter();
    private final OperandPrettyPrinter operandPrinter = new OperandPrettyPrinter();

    /**
     * Backtick-delimits a name that collides with a reserved word (or is not
     * identifier-shaped) so the printed tree parses back unchanged; a no-op for
     * ordinary names.
     *
     * <p>Applied only in the <em>reference</em> positions where the parser would
     * otherwise capture a bare reserved word as an operator: relation references
     * (the {@code parsePrefix} operator switch) and attribute references (operand
     * context, via {@link OperandPrettyPrinter}). Operator-introduced parameter
     * names — {@code AS}/{@code VIA}/{@code BY}/{@code PER}/{@code HOPS} columns,
     * rename column lists, projection/aggregate aliases — are read leniently by the
     * parser (they accept keyword-shaped tokens) and already round-trip bare, so
     * they are printed unquoted. Function names likewise stay bare (they resolve
     * through the function registry and reuse keyword-shaped names like
     * {@code SUM(...)}).
     */
    private static String q(String name) {
        return Identifiers.render(name);
    }

    @Override
    public String visit(RelationNode node) {
        return node.produceBound()
                .map(b -> q(node.name()) + " ⟨produce while " + b.column() + " "
                        + b.operator().symbol() + " "
                        + b.limit().accept(operandPrinter) + "⟩")
                .orElse(q(node.name()));
    }


    @Override
    public String visit(RelationFunctionCall node) {
        String args = node.arguments().stream()
                .map(arg -> arg.accept(operandPrinter))
                .collect(java.util.stream.Collectors.joining(", "));
        return node.functionName() + "(" + args + ")";
    }

    @Override
    public String visit(TruthRelationNode node) {
        return node.keyword();
    }

    /**
     * Renders the optimizer-introduced empty relation as {@code ∅⟨heading⟩}.
     *
     * <p>This is the one node whose printed form does <strong>not</strong> parse
     * back: {@link com.darkcollective.relix.ast.EmptyRelationNode} has no surface
     * syntax, because only the optimizer creates it. The form is still
     * <em>injective</em> — two empty relations print alike exactly when their
     * headings do — which is the property {@link com.darkcollective.relix.ast.AstEquivalence}
     * relies on; it is the round-trip that is unavailable, and only for a node the
     * parser can never produce.
     */
    @Override
    public String visit(EmptyRelationNode node) {
        return "∅⟨" + node.heading().accept(this) + "⟩";
    }

    @Override
    public String visit(ProjectionNode node) {
        String attributes = node.attributes().stream()
                .map(attr -> {
                    String exprStr = attr.expression().accept(operandPrinter);
                    return attr.alias()
                            .map(alias -> exprStr + " → " + alias)
                            .orElse(exprStr);
                })
                .collect(Collectors.joining(", "));
        return "π " + attributes + " (" + node.input().accept(this) + ")";
    }

    @Override
    public String visit(SelectionNode node) {
        return "σ " + node.predicate().accept(predicatePrinter) + " (" + node.input().accept(this) + ")";
    }

    @Override
    public String visit(RenameNode node) {
        String name = node.relationName().map(n -> q(n) + " ").orElse("");
        String cols;
        if (!node.pairs().isEmpty()) {
            cols = "(" + node.pairs().stream()
                    .map(p -> p.from() + " → " + p.to())
                    .collect(Collectors.joining(", ")) + ") ";
        } else if (!node.attributes().isEmpty()) {
            cols = "(" + String.join(", ", node.attributes()) + ") ";
        } else {
            cols = "";
        }
        return "ρ " + name + cols + "(" + node.input().accept(this) + ")";
    }

    @Override
    public String visit(NaturalJoinNode node) {
        return "(" + node.left().accept(this) + ") ⋈ (" + node.right().accept(this) + ")";
    }

    @Override
    public String visit(ThetaJoinNode node) {
        return "(" + node.left().accept(this) + ") ⨝ " + node.condition().accept(predicatePrinter) + " (" + node.right().accept(this) + ")";
    }

    @Override
    public String visit(LeftOuterJoinNode node) {
        return "(" + node.left().accept(this) + ") ⟕ " + node.condition().accept(predicatePrinter) + " (" + node.right().accept(this) + ")";
    }

    @Override
    public String visit(RightOuterJoinNode node) {
        return "(" + node.left().accept(this) + ") ⟖ " + node.condition().accept(predicatePrinter) + " (" + node.right().accept(this) + ")";
    }

    @Override
    public String visit(FullOuterJoinNode node) {
        return "(" + node.left().accept(this) + ") ⟗ " + node.condition().accept(predicatePrinter) + " (" + node.right().accept(this) + ")";
    }

    @Override
    public String visit(SemiJoinNode node) {
        return "(" + node.left().accept(this) + ") ⋉ " + node.condition().accept(predicatePrinter) + " (" + node.right().accept(this) + ")";
    }

    @Override
    public String visit(AntiJoinNode node) {
        return "(" + node.left().accept(this) + ") ▷ " + node.condition().accept(predicatePrinter) + " (" + node.right().accept(this) + ")";
    }

    @Override
    public String visit(PairwiseUniversalNode node) {
        return "(" + node.left().accept(this) + ") USEMI " + node.condition().accept(predicatePrinter) + " (" + node.right().accept(this) + ")";
    }

    @Override
    public String visit(AsOfJoinNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append("(").append(node.left().accept(this)).append(") ASOF");
        if (node.inner()) sb.append(" INNER");
        sb.append(" ").append(node.condition().accept(predicatePrinter));
        node.tolerance().ifPresent(t -> sb.append(" WITHIN ").append(t.accept(operandPrinter)));
        if (node.tieBreak() == FIRST) sb.append(" TIES(FIRST)");
        sb.append(" (").append(node.right().accept(this)).append(")");
        return sb.toString();
    }

    @Override
    public String visit(IntervalJoinNode node) {
        return "(" + node.left().accept(this) + ") IJOIN " + q(node.relation().name())
                // One comma-separated four-tuple, which is what the grammar accepts.
                // ADR-0014 sketched a "; "-separated pair-of-pairs and this printer
                // followed the sketch while the grammar shipped the comma, so IJOIN
                // did not re-parse until ReferenceExampleRoundTripTest caught it.
                + " (" + node.leftStart() + ", " + node.leftEnd()
                + ", " + node.rightStart() + ", " + node.rightEnd() + ")"
                + " (" + node.right().accept(this) + ")";
    }

    @Override
    public String visit(ProductNode node) {
        return "(" + node.left().accept(this) + ") × (" + node.right().accept(this) + ")";
    }

    @Override
    public String visit(UnionNode node) {
        return "(" + node.left().accept(this) + ") ∪ (" + node.right().accept(this) + ")";
    }

    @Override
    public String visit(UnionAllNode node) {
        return "(" + node.left().accept(this) + ") ⊎ (" + node.right().accept(this) + ")";
    }

    @Override
    public String visit(OuterUnionNode node) {
        return "(" + node.left().accept(this) + ") ⊔ (" + node.right().accept(this) + ")";
    }

    @Override
    public String visit(DifferenceNode node) {
        return "(" + node.left().accept(this) + ") − (" + node.right().accept(this) + ")";
    }

    @Override
    public String visit(IntersectionNode node) {
        return "(" + node.left().accept(this) + ") ∩ (" + node.right().accept(this) + ")";
    }

    @Override
    public String visit(DivisionNode node) {
        return "(" + node.left().accept(this) + ") ÷ (" + node.right().accept(this) + ")";
    }

    @Override
    public String visit(SymmetricDifferenceNode node) {
        return "(" + node.left().accept(this) + ") ∆ (" + node.right().accept(this) + ")";
    }

    @Override
    public String visit(CompositionNode node) {
        return "(" + node.left().accept(this) + ") ∘ (" + node.right().accept(this) + ")";
    }

    @Override
    public String visit(UniversalNode node) {
        String keys = node.groupingAttributes().isEmpty()
                ? "" : String.join(", ", node.groupingAttributes()) + " ";
        return "∀ " + keys + ": " + node.predicate().accept(predicatePrinter)
                + " (" + node.input().accept(this) + ")";
    }

    @Override
    public String visit(SampleNode node) {
        String seed = node.seed().map(s -> " SEED " + s).orElse("");
        return "SAMPLE " + node.probability() + seed + " (" + node.input().accept(this) + ")";
    }

    @Override
    public String visit(ReservoirSampleNode node) {
        String seed = node.seed().map(s -> " SEED " + s).orElse("");
        return "SAMPLE " + node.count() + " ROWS" + seed + " (" + node.input().accept(this) + ")";
    }

    @Override
    public String visit(SolveNode node) {
        return "SOLVE " + node.left().accept(operandPrinter) + " = "
                + node.right().accept(operandPrinter) + " (" + node.input().accept(this) + ")";
    }

    @Override
    public String visit(OptimizeNode node) {
        StringBuilder sb = new StringBuilder("OPTIMIZE ");
        node.allocation().ifPresent(spec ->
                sb.append("ALLOCATE (").append(DisplayLabels.bound(spec.lo()))
                  .append(", ").append(DisplayLabels.bound(spec.hi())).append(") "));
        sb.append(node.sense()).append(" SUM(")
                .append(node.objective().accept(operandPrinter)).append(") SUBJECT TO ");
        String constraints = node.constraints().stream()
                .map(c -> "SUM(" + c.expr().accept(operandPrinter) + ") "
                        + c.op().symbol() + " " + DisplayLabels.bound(c.bound()))
                .collect(Collectors.joining(" AND "));
        sb.append(constraints);
        node.allocation().ifPresent(spec -> sb.append(" -> ").append(spec.columnName()));
        if (!node.groupingKeys().isEmpty()) {
            sb.append(" PER ").append(String.join(", ", node.groupingKeys()));
        }
        sb.append(" (").append(node.input().accept(this)).append(")");
        return sb.toString();
    }



    @Override
    public String visit(TopKNode node) {
        String count = node.offset().map(o -> o + ", ").orElse("") + node.count();
        String specs = node.sortSpecs().stream()
                .map(this::renderSortKey)
                .collect(Collectors.joining(", "));
        String per = node.groupingAttributes().isEmpty()
                ? "" : " PER " + String.join(", ", node.groupingAttributes());
        return "TOP " + count + " " + specs + per
                + " (" + node.input().accept(this) + ")";
    }

    @Override
    public String visit(AggregationNode node) {
        // Format: γ [grouping_keys,] aggregate_funcs (input)
        String grouping = "";
        if (!node.groupingKeys().isEmpty()) {
            grouping = node.groupingKeys().stream()
                    .map(this::renderGroupingKey)
                    .collect(Collectors.joining(", ")) + ", ";
        }

        String aggregates = node.aggregates().stream()
                .map(agg -> {
                    String funcName = agg.operator().name();
                    String args = agg.argument().accept(operandPrinter)
                            + agg.yieldExpr().map(y -> ", " + y.accept(operandPrinter)).orElse("");
                    String funcCall = funcName + "(" + args + ")";
                    return agg.alias()
                            .map(alias -> funcCall + " → " + alias)
                            .orElse(funcCall);
                })
                .collect(Collectors.joining(", "));

        return "γ " + grouping + aggregates + " (" + node.input().accept(this) + ")";
    }

    @Override
    public String visit(SortNode node) {
        String specs = node.sortSpecs().stream()
                .map(this::renderSortKey)
                .collect(Collectors.joining(", "));
        return "τ " + specs + " (" + node.input().accept(this) + ")";
    }

    /** Renders a grouping key as {@code expression [→ alias]}. */
    private String renderGroupingKey(GroupingKey key) {
        String expr = key.expression().accept(operandPrinter);
        return key.alias().map(a -> expr + " → " + a).orElse(expr);
    }

    /** Renders a sort key as {@code expression [DESC]}. */
    private String renderSortKey(SortSpecification spec) {
        return spec.expression().accept(operandPrinter)
                + (spec.direction() == SortDirection.DESC ? " DESC" : "");
    }

    @Override
    public String visit(LimitNode node) {
        String limitSpec = node.offset()
                .map(off -> off + ", " + node.count())
                .orElse(String.valueOf(node.count()));
        return "λ " + limitSpec + " (" + node.input().accept(this) + ")";
    }

    @Override
    public String visit(DistinctNode node) {
        return "δ (" + node.input().accept(this) + ")";
    }

    @Override
    public String visit(WhyNode node) {
        return "ω (" + node.input().accept(this) + ")";
    }

    @Override
    public String visit(UnnestNode node) {
        return "μ " + node.column() + (node.outer() ? " OUTER" : "")
                + node.ordinalityColumn().map(c -> " WITH ORDINALITY " + c).orElse("")
                + " (" + node.input().accept(this) + ")";
    }

    @Override
    public String visit(ClosureNode node) {
        String keyword = node.reflexive() ? "RCLOSURE" : "CLOSURE";
        return keyword + " " + node.fromColumn() + ", " + node.toColumn()
                + boundAnnotation(node)
                + " (" + node.input().accept(this) + ")";
    }

    /**
     * Renders the optimizer-folded endpoint bounds of a closure as a non-syntax
     * annotation (e.g. {@code  ⟨from="X"⟩}), or {@code ""} when unbounded. These
     * bounds never appear on a parsed tree, so round-trip output is unaffected.
     */
    private String boundAnnotation(ClosureNode node) {
        if (node.boundSource().isEmpty() && node.boundTarget().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" ⟨");
        node.boundSource().ifPresent(op ->
                sb.append(node.fromColumn()).append('=').append(op.accept(operandPrinter)));
        node.boundTarget().ifPresent(op -> {
            if (node.boundSource().isPresent()) {
                sb.append(", ");
            }
            sb.append(node.toColumn()).append('=').append(op.accept(operandPrinter));
        });
        return sb.append('⟩').toString();
    }

    @Override
    public String visit(ClusterNode node) {
        return "CLUSTER " + node.fromColumn() + ", " + node.toColumn()
                + " AS " + node.labelColumn() + " (" + node.input().accept(this) + ")";
    }

    @Override
    public String visit(PathNode node) {
        return "PATH " + node.fromColumn() + ", " + node.toColumn()
                + " HOPS " + node.minHops() + " TO " + node.maxHops()
                + " AS " + node.depthColumn() + " (" + node.input().accept(this) + ")";
    }

    @Override
    public String visit(TraceNode node) {
        return "TRACE " + node.fromColumn() + ", " + node.toColumn()
                + " VIA " + node.weightColumn() + " " + node.sense()
                + " AS " + node.pathColumn()
                + traceBoundAnnotation(node)
                + " (" + node.input().accept(this) + ")";
    }

    /**
     * Renders the optimizer-folded endpoint bounds of a trace as a non-syntax
     * annotation (e.g. {@code  ⟨from="JFK"⟩}), or {@code ""} when unbounded. These
     * bounds never appear on a parsed tree, so round-trip output is unaffected.
     */
    private String traceBoundAnnotation(TraceNode node) {
        if (node.boundSource().isEmpty() && node.boundTarget().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" ⟨");
        node.boundSource().ifPresent(op ->
                sb.append(node.fromColumn()).append('=').append(op.accept(operandPrinter)));
        node.boundTarget().ifPresent(op -> {
            if (node.boundSource().isPresent()) {
                sb.append(", ");
            }
            sb.append(node.toColumn()).append('=').append(op.accept(operandPrinter));
        });
        return sb.append('⟩').toString();
    }

    @Override
    public String visit(CoverNode node) {
        return (node.exact() ? "COVER EXACT " : "COVER ") + node.strength() + " (" + node.input().accept(this) + ")";
    }

    @Override
    public String visit(DownsampleNode node) {
        StringBuilder sb = new StringBuilder("DOWNSAMPLE ");
        sb.append(node.timestampColumn())
          .append(" BY '").append(node.interval()).append("'")
          .append(" USING ").append(node.function().name());
        if (!node.groupingKeys().isEmpty()) {
            sb.append(" PER ").append(String.join(", ", node.groupingKeys()));
        }
        node.maxRows().ifPresent(n -> sb.append(" FOR ").append(n).append(" ROWS"));
        sb.append(" (").append(node.input().accept(this)).append(")");
        return sb.toString();
    }

    @Override
    public String visit(WindowNode node) {
        String keyword = node.function() instanceof WindowFunction.AggregateWindow ? "ROLLING" : "WINDOW";
        StringBuilder sb = new StringBuilder(keyword).append(' ')
                .append(windowFunction(node.function()))
                .append(windowFrame(node.frame()))
                .append(" SORT ")
                .append(node.sortSpecs().stream()
                        .map(this::renderSortKey)
                        .collect(Collectors.joining(", ")));
        if (!node.partitionKeys().isEmpty()) {
            sb.append(" PER ").append(String.join(", ", node.partitionKeys()));
        }
        sb.append(" AS ").append(node.outputColumn())
          .append(" (").append(node.input().accept(this)).append(")");
        return sb.toString();
    }

    @Override
    public String visit(SessionizeNode node) {
        StringBuilder sb = new StringBuilder("SESSIONIZE ")
                .append(node.orderColumn())
                .append(" GAP ").append(node.threshold().accept(operandPrinter));
        if (!node.partitionKeys().isEmpty()) {
            sb.append(" PER ").append(String.join(", ", node.partitionKeys()));
        }
        sb.append(" AS ").append(node.sessionColumn())
          .append(" (").append(node.input().accept(this)).append(")");
        return sb.toString();
    }

    @Override
    public String visit(TreeNode node) {
        StringBuilder sb = new StringBuilder("TREE ")
                .append(node.keyColumn())
                .append(" BY ").append(node.parentColumn());
        if (!node.orderSpecs().isEmpty()) {
            sb.append(" ORDER ").append(node.orderSpecs().stream()
                    .map(this::renderSortKey)
                    .collect(Collectors.joining(", ")));
        }
        sb.append(" AS ").append(node.childrenColumn())
          .append(" (").append(node.input().accept(this)).append(")");
        return sb.toString();
    }

    @Override
    public String visit(UnpivotNode node) {
        return "UNPIVOT (" + String.join(", ", node.columns()) + ")"
                + " AS (" + node.nameColumn() + ", " + node.valueColumn() + ")"
                + " (" + node.input().accept(this) + ")";
    }

    @Override
    public String visit(PivotNode node) {
        StringBuilder sb = new StringBuilder("PIVOT ")
                .append(node.valueColumn())
                .append(" BY ").append(node.keyColumn());
        if (!node.groupKeys().isEmpty()) {
            sb.append(" PER ").append(String.join(", ", node.groupKeys()));
        }
        sb.append(" (").append(node.input().accept(this)).append(")");
        return sb.toString();
    }

    private String windowFunction(WindowFunction fn) {
        return switch (fn) {
            case WindowFunction.AggregateWindow a ->
                    a.operator().name() + "(" + a.argument().accept(operandPrinter) + ")";
            case WindowFunction.RankingWindow r ->
                    r.function().name() + "("
                            + r.ntileCount().map(c -> c.accept(operandPrinter)).orElse("") + ")";
            case WindowFunction.OffsetWindow o ->
                    o.function().name() + "(" + o.expression().accept(operandPrinter)
                            + o.offset().map(off -> ", " + off.accept(operandPrinter)).orElse("")
                            + o.defaultValue().map(d -> ", " + d.accept(operandPrinter)).orElse("")
                            + ")";
        };
    }

    private static String windowFrame(WindowFrame frame) {
        return switch (frame) {
            case WindowFrame.BoundedFrame b -> " OVER " + b.n() + " ROWS";
            case WindowFrame.CumulativeFrame _ -> " OVER ALL ROWS";
            case WindowFrame.PartitionFrame _ -> "";
        };
    }

    @Override
    public String visit(FixpointNode node) {
        return "FIX " + q(node.name()) + " (" + node.base().accept(this)
                + ", " + node.step().accept(this) + ")";
    }

    @Override
    public String visit(RecursiveRefNode node) {
        return q(node.name());
    }

    @Override
    public String visit(LateralJoinNode node) {
        String args = node.arguments().stream()
                .map(arg -> arg.accept(operandPrinter))
                .collect(java.util.stream.Collectors.joining(", "));
        return "(" + node.left().accept(this) + ") LATERAL " + node.functionName() + "(" + args + ")";
    }
}

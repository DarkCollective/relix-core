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
package com.darkcollective.relix.ast.visitor.internal;

import com.darkcollective.relix.ast.visitor.RelNodeVisitor;
import com.darkcollective.relix.ast.*;
import com.darkcollective.relix.ast.internal.DisplayLabels;
import com.darkcollective.relix.ast.internal.*;

import java.util.Objects;
import java.util.function.Function;
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
    private final PredicatePrettyPrinter predicatePrinter;
    private final OperandPrettyPrinter operandPrinter;

    /**
     * Whether the optimizer's own annotations are written as the language spells what
     * they mean, rather than as the diagnostic {@code ⟨…⟩} form. See {@link #source()}.
     */
    private final boolean source;

    /** Glyphs or ASCII keywords, for every operator this prints. */
    private final Spelling spelling;

    /** What is written immediately before each node's own text: nothing, or its comments. */
    private final Function<RelNode, String> before;

    /** A printer of the diagnostic form, which shows the optimizer's annotations as they are. */
    public PrettyPrinter() {
        this(false, Spelling.GLYPHS, node -> "");
    }

    private PrettyPrinter(boolean source, Spelling spelling, Function<RelNode, String> before) {
        this.source = source;
        this.spelling = Objects.requireNonNull(spelling, "spelling");
        this.before = Objects.requireNonNull(before, "before");
        this.predicatePrinter = new PredicatePrettyPrinter(spelling);
        this.operandPrinter = new OperandPrettyPrinter(spelling);
    }

    /**
     * A printer whose every output parses back, as a relation that returns the same rows.
     *
     * <p>The diagnostic form prints what the optimizer folded into a node — a closure's
     * seed, a generator's production stop, a relation it proved empty — as an annotation
     * no lexer reads, because those are not the language's to write. This printer spells
     * each one as the expression it stands for instead:
     * <ul>
     *   <li>a {@code CLOSURE}, {@code PATH} or {@code TRACE} seeded at an endpoint is the
     *       selection that seed was folded from, {@code σ from = c (CLOSURE from, to (E))};</li>
     *   <li>a generator's {@code ⟨produce while …⟩} is left out, because the pass that adds
     *       it keeps the selection above the generator;</li>
     *   <li>{@code ∅⟨E⟩} is {@code σ 1 = 0 (E)}: no rows, and the heading of {@code E}.</li>
     * </ul>
     * A tree parsed from source carries none of them, so for one the two forms agree.
     *
     * @return a printer of re-parseable text
     */
    public static PrettyPrinter source() {
        return source(Spelling.GLYPHS);
    }

    /**
     * A printer of re-parseable text, as {@link #source()}, writing each operator as
     * {@code spelling} spells it.
     *
     * @param spelling glyphs or ASCII keywords; must not be null
     * @return a printer of re-parseable text
     */
    public static PrettyPrinter source(Spelling spelling) {
        return source(spelling, node -> "");
    }

    /**
     * A printer of re-parseable text that writes {@code before.apply(n)} immediately before
     * each node {@code n} — how a script printer puts a comment back beside the
     * expression it was written beside. What it returns must be text the grammar skips
     * where an expression may start: whitespace, or a comment ended by a newline when it
     * is a line comment.
     *
     * @param spelling glyphs or ASCII keywords; must not be null
     * @param before   the text to write before each node, {@code ""} for none; must not
     *                 be null
     * @return a printer of re-parseable text
     */
    public static PrettyPrinter source(Spelling spelling, Function<RelNode, String> before) {
        return new PrettyPrinter(true, spelling, before);
    }

    /**
     * {@return {@code node} printed, after whatever the caller writes before it}
     *
     * <p>The entry point and every recursion go through here, so the text a caller asked
     * for lands before each node however deep it is.
     */
    public String print(RelNode node) {
        return before.apply(node) + node.accept(this);
    }

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
        if (source) {
            return q(node.name());
        }
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
     * headings do — which is the property {@link com.darkcollective.relix.ast.internal.AstEquivalence}
     * relies on; it is the round-trip that is unavailable, and only for a node the
     * parser can never produce.
     */
    @Override
    public String visit(EmptyRelationNode node) {
        if (source) {
            return spelling.of("σ") + " 1 = 0 (" + print(node.heading()) + ")";
        }
        return "∅⟨" + print(node.heading()) + "⟩";
    }

    @Override
    public String visit(ProjectionNode node) {
        String attributes = node.attributes().stream()
                .map(attr -> {
                    String exprStr = attr.expression().accept(operandPrinter);
                    return attr.alias()
                            .map(alias -> exprStr + " " + spelling.of("→") + " " + alias)
                            .orElse(exprStr);
                })
                .collect(Collectors.joining(", "));
        return spelling.of("π") + " " + attributes + " (" + print(node.input()) + ")";
    }

    @Override
    public String visit(SelectionNode node) {
        return spelling.of("σ") + " " + node.predicate().accept(predicatePrinter) + " (" + print(node.input()) + ")";
    }

    @Override
    public String visit(RenameNode node) {
        String name = node.relationName().map(n -> q(n) + " ").orElse("");
        String cols;
        if (!node.pairs().isEmpty()) {
            cols = "(" + node.pairs().stream()
                    .map(p -> p.from() + " " + spelling.of("→") + " " + p.to())
                    .collect(Collectors.joining(", ")) + ") ";
        } else if (!node.attributes().isEmpty()) {
            cols = "(" + String.join(", ", node.attributes()) + ") ";
        } else {
            cols = "";
        }
        return spelling.of("ρ") + " " + name + cols + "(" + print(node.input()) + ")";
    }

    @Override
    public String visit(NaturalJoinNode node) {
        return "(" + print(node.left()) + ") " + spelling.of("⋈") + " (" + print(node.right()) + ")";
    }

    @Override
    public String visit(ThetaJoinNode node) {
        return "(" + print(node.left()) + ") " + spelling.of("⨝") + " " + node.condition().accept(predicatePrinter) + " (" + print(node.right()) + ")";
    }

    @Override
    public String visit(LeftOuterJoinNode node) {
        return "(" + print(node.left()) + ") " + spelling.of("⟕") + " " + node.condition().accept(predicatePrinter) + " (" + print(node.right()) + ")";
    }

    @Override
    public String visit(RightOuterJoinNode node) {
        return "(" + print(node.left()) + ") " + spelling.of("⟖") + " " + node.condition().accept(predicatePrinter) + " (" + print(node.right()) + ")";
    }

    @Override
    public String visit(FullOuterJoinNode node) {
        return "(" + print(node.left()) + ") " + spelling.of("⟗") + " " + node.condition().accept(predicatePrinter) + " (" + print(node.right()) + ")";
    }

    @Override
    public String visit(SemiJoinNode node) {
        return "(" + print(node.left()) + ") " + spelling.of("⋉") + " " + node.condition().accept(predicatePrinter) + " (" + print(node.right()) + ")";
    }

    @Override
    public String visit(AntiJoinNode node) {
        return "(" + print(node.left()) + ") " + spelling.of("▷") + " " + node.condition().accept(predicatePrinter) + " (" + print(node.right()) + ")";
    }

    @Override
    public String visit(PairwiseUniversalNode node) {
        return "(" + print(node.left()) + ") USEMI " + node.condition().accept(predicatePrinter) + " (" + print(node.right()) + ")";
    }

    @Override
    public String visit(AsOfJoinNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append("(").append(print(node.left())).append(") ASOF");
        if (node.inner()) sb.append(" INNER");
        sb.append(" ").append(node.condition().accept(predicatePrinter));
        node.tolerance().ifPresent(t -> sb.append(" WITHIN ").append(t.accept(operandPrinter)));
        if (node.tieBreak() == FIRST) sb.append(" TIES(FIRST)");
        sb.append(" (").append(print(node.right())).append(")");
        return sb.toString();
    }

    @Override
    public String visit(IntervalJoinNode node) {
        return "(" + print(node.left()) + ") IJOIN " + q(node.relation().name())
                // One comma-separated four-tuple, which is what the grammar accepts.
                // ADR-0014 sketched a "; "-separated pair-of-pairs and this printer
                // followed the sketch while the grammar shipped the comma, so IJOIN
                // did not re-parse until ReferenceExampleRoundTripTest caught it.
                + " (" + node.leftStart() + ", " + node.leftEnd()
                + ", " + node.rightStart() + ", " + node.rightEnd() + ")"
                + " (" + print(node.right()) + ")";
    }

    @Override
    public String visit(ProductNode node) {
        return "(" + print(node.left()) + ") " + spelling.of("×") + " (" + print(node.right()) + ")";
    }

    @Override
    public String visit(UnionNode node) {
        return "(" + print(node.left()) + ") " + spelling.of("∪") + " (" + print(node.right()) + ")";
    }

    @Override
    public String visit(UnionAllNode node) {
        return "(" + print(node.left()) + ") " + spelling.of("⊎") + " (" + print(node.right()) + ")";
    }

    @Override
    public String visit(OuterUnionNode node) {
        return "(" + print(node.left()) + ") " + spelling.of("⊔") + " (" + print(node.right()) + ")";
    }

    @Override
    public String visit(DifferenceNode node) {
        return "(" + print(node.left()) + ") " + spelling.of("−") + " (" + print(node.right()) + ")";
    }

    @Override
    public String visit(IntersectionNode node) {
        return "(" + print(node.left()) + ") " + spelling.of("∩") + " (" + print(node.right()) + ")";
    }

    @Override
    public String visit(DivisionNode node) {
        return "(" + print(node.left()) + ") " + spelling.of("÷") + " (" + print(node.right()) + ")";
    }

    @Override
    public String visit(SymmetricDifferenceNode node) {
        return "(" + print(node.left()) + ") " + spelling.of("∆") + " (" + print(node.right()) + ")";
    }

    @Override
    public String visit(CompositionNode node) {
        return "(" + print(node.left()) + ") " + spelling.of("∘") + " (" + print(node.right()) + ")";
    }

    @Override
    public String visit(UniversalNode node) {
        String keys = node.groupingAttributes().isEmpty()
                ? "" : String.join(", ", node.groupingAttributes()) + " ";
        return spelling.of("∀") + " " + keys + ": " + node.predicate().accept(predicatePrinter)
                + " (" + print(node.input()) + ")";
    }

    @Override
    public String visit(SampleNode node) {
        String seed = node.seed().map(s -> " SEED " + s).orElse("");
        return "SAMPLE " + node.probability() + seed + " (" + print(node.input()) + ")";
    }

    @Override
    public String visit(ReservoirSampleNode node) {
        String seed = node.seed().map(s -> " SEED " + s).orElse("");
        return "SAMPLE " + node.count() + " ROWS" + seed + " (" + print(node.input()) + ")";
    }

    @Override
    public String visit(SolveNode node) {
        return DisplayLabels.solve(node.equations(), node.groupingKeys(), node.tolerance(),
                node.maxRounds(), node.starts())
                + " (" + print(node.input()) + ")";
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
                        + spelling.of(c.op().symbol()) + " " + DisplayLabels.bound(c.bound()))
                .collect(Collectors.joining(" AND "));
        sb.append(constraints);
        node.allocation().ifPresent(spec -> sb.append(" -> ").append(spec.columnName()));
        if (!node.groupingKeys().isEmpty()) {
            sb.append(" PER ").append(String.join(", ", node.groupingKeys()));
        }
        sb.append(" (").append(print(node.input())).append(")");
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
                + " (" + print(node.input()) + ")";
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
                            .map(alias -> funcCall + " " + spelling.of("→") + " " + alias)
                            .orElse(funcCall);
                })
                .collect(Collectors.joining(", "));

        return spelling.of("γ") + " " + grouping + aggregates + " (" + print(node.input()) + ")";
    }

    @Override
    public String visit(SortNode node) {
        String specs = node.sortSpecs().stream()
                .map(this::renderSortKey)
                .collect(Collectors.joining(", "));
        return spelling.of("τ") + " " + specs + " (" + print(node.input()) + ")";
    }

    @Override
    public String visit(ShuffleNode node) {
        String seed = node.seed().map(s -> " SEED " + s).orElse("");
        return "SHUFFLE" + seed + " (" + print(node.input()) + ")";
    }

    @Override
    public String visit(RollNode node) {
        String weight = node.weight().map(w -> " BY " + w.accept(operandPrinter)).orElse("");
        String seed = node.seed().map(s -> " SEED " + s).orElse("");
        return "ROLL" + weight + seed + " (" + print(node.input()) + ")";
    }

    /** Renders a grouping key as {@code expression [→ alias]}. */
    private String renderGroupingKey(GroupingKey key) {
        String expr = key.expression().accept(operandPrinter);
        return key.alias().map(a -> expr + " " + spelling.of("→") + " " + a).orElse(expr);
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
        return spelling.of("λ") + " " + limitSpec + " (" + print(node.input()) + ")";
    }

    @Override
    public String visit(DistinctNode node) {
        return spelling.of("δ") + " (" + print(node.input()) + ")";
    }

    @Override
    public String visit(WhyNode node) {
        return spelling.of("ω") + " (" + print(node.input()) + ")";
    }

    @Override
    public String visit(UnnestNode node) {
        return spelling.of("μ") + " " + node.column() + (node.outer() ? " OUTER" : "")
                + node.ordinalityColumn().map(c -> " WITH ORDINALITY " + c).orElse("")
                + " (" + print(node.input()) + ")";
    }

    @Override
    public String visit(ClosureNode node) {
        String keyword = node.reflexive() ? "RCLOSURE" : "CLOSURE";
        return seeded(keyword + " " + node.fromColumn() + edgeSeparator(node.undirected())
                + node.toColumn()
                + boundAnnotation(node.fromColumn(), node.toColumn(),
                        node.boundSource(), node.boundTarget())
                + " (" + print(node.input()) + ")",
                node.fromColumn(), node.toColumn(), node.boundSource(), node.boundTarget());
    }

    /**
     * {@return what stands between a graph operator's two endpoint columns} A comma reads
     * them as a directed edge; {@code ↔} reads the relation both ways.
     */
    private String edgeSeparator(boolean undirected) {
        return undirected ? " " + spelling.of("↔") + " " : ", ";
    }


    /**
     * Renders the optimizer-folded endpoint bounds of a graph operator as a non-syntax
     * annotation (e.g. {@code  ⟨from="X"⟩}), or {@code ""} when unbounded. These bounds
     * never appear on a parsed tree, so round-trip output is unaffected.
     */
    private String boundAnnotation(String fromColumn, String toColumn,
                                   java.util.Optional<Operand> boundSource,
                                   java.util.Optional<Operand> boundTarget) {
        if (source || boundSource.isEmpty() && boundTarget.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" ⟨");
        boundSource.ifPresent(op ->
                sb.append(fromColumn).append('=').append(op.accept(operandPrinter)));
        boundTarget.ifPresent(op -> {
            if (boundSource.isPresent()) {
                sb.append(", ");
            }
            sb.append(toColumn).append('=').append(op.accept(operandPrinter));
        });
        return sb.append('⟩').toString();
    }

    /**
     * {@return a graph operator's text, under the selection its endpoint bounds were
     * folded from when printing source} Each bound is an equality between an endpoint
     * column and a literal (CLOSURE-001, PATH-001, TRACE-001), and selecting it from the
     * unseeded operator's output gives exactly the seeded operator's rows.
     */
    private String seeded(String printed, String fromColumn, String toColumn,
                          java.util.Optional<Operand> boundSource,
                          java.util.Optional<Operand> boundTarget) {
        if (!source || boundSource.isEmpty() && boundTarget.isEmpty()) {
            return printed;
        }
        java.util.List<String> conjuncts = new java.util.ArrayList<>(2);
        boundSource.ifPresent(op -> conjuncts.add(q(fromColumn) + " = " + op.accept(operandPrinter)));
        boundTarget.ifPresent(op -> conjuncts.add(q(toColumn) + " = " + op.accept(operandPrinter)));
        return spelling.of("σ") + " " + String.join(" " + spelling.of("∧") + " ", conjuncts) + " (" + printed + ")";
    }

    @Override
    public String visit(ClusterNode node) {
        return "CLUSTER " + node.fromColumn() + ", " + node.toColumn()
                + " AS " + node.labelColumn() + " (" + print(node.input()) + ")";
    }

    @Override
    public String visit(PathNode node) {
        return seeded("PATH " + node.fromColumn() + edgeSeparator(node.undirected()) + node.toColumn()
                + " HOPS " + node.minHops() + " TO " + node.maxHops()
                + " AS " + node.depthColumn()
                + boundAnnotation(node.fromColumn(), node.toColumn(),
                        node.boundSource(), node.boundTarget())
                + " (" + print(node.input()) + ")",
                node.fromColumn(), node.toColumn(), node.boundSource(), node.boundTarget());
    }

    @Override
    public String visit(TraceNode node) {
        return seeded("TRACE " + node.fromColumn() + edgeSeparator(node.undirected()) + node.toColumn()
                + " VIA " + node.weightColumn() + " " + node.sense()
                + " AS " + node.pathColumn()
                + boundAnnotation(node.fromColumn(), node.toColumn(),
                        node.boundSource(), node.boundTarget())
                + " (" + print(node.input()) + ")",
                node.fromColumn(), node.toColumn(), node.boundSource(), node.boundTarget());
    }


    @Override
    public String visit(CoverNode node) {
        return (node.exact() ? "COVER EXACT " : "COVER ") + node.strength() + " (" + print(node.input()) + ")";
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
        sb.append(" (").append(print(node.input())).append(")");
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
          .append(" (").append(print(node.input())).append(")");
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
          .append(" (").append(print(node.input())).append(")");
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
          .append(" (").append(print(node.input())).append(")");
        return sb.toString();
    }

    @Override
    public String visit(UnpivotNode node) {
        return "UNPIVOT (" + String.join(", ", node.columns()) + ")"
                + " AS (" + node.nameColumn() + ", " + node.valueColumn() + ")"
                + " (" + print(node.input()) + ")";
    }

    @Override
    public String visit(PivotNode node) {
        StringBuilder sb = new StringBuilder("PIVOT ")
                .append(node.valueColumn())
                .append(" BY ").append(node.keyColumn());
        if (!node.groupKeys().isEmpty()) {
            sb.append(" PER ").append(String.join(", ", node.groupKeys()));
        }
        sb.append(" (").append(print(node.input())).append(")");
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
            case WindowFrame.CumulativeFrame ignored -> " OVER ALL ROWS";
            case WindowFrame.PartitionFrame ignored -> "";
        };
    }

    @Override
    public String visit(FixpointNode node) {
        return "FIX " + q(node.name()) + " (" + print(node.base())
                + ", " + print(node.step()) + ")";
    }

    @Override
    public String visit(IterateNode node) {
        return "ITERATE " + q(node.name()) + " (" + print(node.base())
                + ", " + print(node.step()) + ")" + iterateStop(node.stop());
    }

    private static String iterateStop(IterateStop stop) {
        return switch (stop) {
            case IterateStop.Rounds r -> " ROUNDS " + r.rounds();
            case IterateStop.Stable s -> " UNTIL STABLE MAX " + s.rounds() + " ROUNDS";
            case IterateStop.Converged c -> " UNTIL " + names(c.columns())
                    + " WITHIN " + c.tolerance().toPlainString()
                    + " PER " + names(c.keys()) + " MAX " + c.rounds() + " ROUNDS";
        };
    }

    private static String names(java.util.List<String> names) {
        return names.stream().map(PrettyPrinter::q).collect(java.util.stream.Collectors.joining(", "));
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
        return "(" + print(node.left()) + ") LATERAL " + node.functionName() + "(" + args + ")";
    }
}

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
package com.darkcollective.relix.semantic.internal;

import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.ast.AggregationNode;
import com.darkcollective.relix.ast.AntiJoinNode;
import com.darkcollective.relix.ast.AsOfJoinNode;
import com.darkcollective.relix.ast.ClosureNode;
import com.darkcollective.relix.ast.ClusterNode;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.CompositionNode;
import com.darkcollective.relix.ast.CoverNode;
import com.darkcollective.relix.ast.DifferenceNode;
import com.darkcollective.relix.ast.internal.DisplayLabels;
import com.darkcollective.relix.ast.DistinctNode;
import com.darkcollective.relix.ast.DivisionNode;
import com.darkcollective.relix.ast.DownsampleNode;
import com.darkcollective.relix.ast.FixpointNode;
import com.darkcollective.relix.ast.FullOuterJoinNode;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.IntersectionNode;
import com.darkcollective.relix.ast.IntervalJoinNode;
import com.darkcollective.relix.ast.LateralJoinNode;
import com.darkcollective.relix.ast.LeftOuterJoinNode;
import com.darkcollective.relix.ast.LimitNode;
import com.darkcollective.relix.ast.MaterializationMode;
import com.darkcollective.relix.ast.NaturalJoinNode;
import com.darkcollective.relix.ast.ObjectiveSense;
import com.darkcollective.relix.ast.OptimizeNode;
import com.darkcollective.relix.ast.OuterUnionNode;
import com.darkcollective.relix.ast.PairwiseUniversalNode;
import com.darkcollective.relix.ast.PathNode;
import com.darkcollective.relix.ast.PivotNode;
import com.darkcollective.relix.ast.ProductNode;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RecursiveRefNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationFunctionCall;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.ReservoirSampleNode;
import com.darkcollective.relix.ast.RightOuterJoinNode;
import com.darkcollective.relix.ast.SampleNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SemiJoinNode;
import com.darkcollective.relix.ast.SessionizeNode;
import com.darkcollective.relix.ast.SolveNode;
import com.darkcollective.relix.ast.SortDirection;
import com.darkcollective.relix.ast.SortNode;
import com.darkcollective.relix.ast.SortSpecification;
import com.darkcollective.relix.ast.SymmetricDifferenceNode;
import com.darkcollective.relix.ast.ThetaJoinNode;
import com.darkcollective.relix.ast.TopKNode;
import com.darkcollective.relix.ast.TraceNode;
import com.darkcollective.relix.ast.EmptyRelationNode;
import com.darkcollective.relix.ast.TruthRelationNode;
import com.darkcollective.relix.ast.TreeNode;
import com.darkcollective.relix.ast.UnionAllNode;
import com.darkcollective.relix.ast.UnionNode;
import com.darkcollective.relix.ast.UniversalNode;
import com.darkcollective.relix.ast.UnnestNode;
import com.darkcollective.relix.ast.UnpivotNode;
import com.darkcollective.relix.ast.WhyNode;
import com.darkcollective.relix.ast.WindowFrame;
import com.darkcollective.relix.ast.WindowFunction;
import com.darkcollective.relix.ast.WindowNode;
import com.darkcollective.relix.ast.visitor.internal.OperandPrettyPrinter;
import com.darkcollective.relix.ast.visitor.internal.PredicatePrettyPrinter;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.Symbol;
import com.darkcollective.relix.symbol.function.FunctionSymbol;
import com.darkcollective.relix.symbol.function.RelationFunctionSymbol;
import com.darkcollective.relix.symbol.graph.EdgeOrigin;
import com.darkcollective.relix.symbol.graph.Endpoint;
import com.darkcollective.relix.symbol.graph.Relationship;
import com.darkcollective.relix.symbol.graph.SchemaGraph;
import com.darkcollective.relix.symbol.relation.DatabaseRelationSymbol;
import com.darkcollective.relix.symbol.relation.InlineRelationSymbol;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.relation.RelationSymbol;
import com.darkcollective.relix.symbol.relation.SourceRelationSymbol;
import com.darkcollective.relix.symbol.relation.SystemRelationSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Produces a concise human-readable IR report from a {@link SemanticModel}.
 *
 * <p>The report is plain UTF-8 text (no BOM), at most {@value #WIDTH} characters
 * wide per line, and contains these sections:
 *
 * <ol>
 *   <li><strong>Header</strong> — namespace and key for all codes used.</li>
 *   <li><strong>Symbols</strong> — every registered symbol, sorted
 *       alphabetically, with its kind code and schema/signature.</li>
 *   <li><strong>Relationships</strong> — the schema graph's edges,
 *       one line per relationship with its endpoints, any non-default bounds,
 *       and an origin tag for non-declared edges.  Omitted entirely when the
 *       graph is empty, so pre-existing reports are unchanged.</li>
 *   <li><strong>Expression Trees</strong> — one indented ASCII tree per named
 *       view ({@link QueryRelationSymbol}), rendered in alphabetical order.</li>
 *   <li><strong>Root Queries</strong> — the ordered {@code query} statements
 *       from the root file; inline expression queries expand their tree.</li>
 * </ol>
 *
 * <h2>Code keys</h2>
 * <pre>
 *   kind: SRC=source  INL=inline  SYS=system  QR=view  DB=database
 *         FN=function  TVF=table-fn  LIT=truth-literal
 *   type: N=number  S=string  B=boolean  ?=any  [x]=array  {…}=struct
 *         D=date  T=time  TS=timestamp  DUR=duration
 *   mat:  [bag]=bag  [set]=dedup-set  [sort]=sorted  (stream=no label)
 * </pre>
 *
 * <p>Materialisation tags ({@code [bag]}, {@code [set]}, {@code [sort]}) are
 * appended to the node label in expression trees for operators that must
 * accumulate all rows before emitting output.  Streaming operators carry no
 * tag.  See {@link com.darkcollective.relix.ast.MaterializationMode} for the
 * full mapping.</p>
 *
 * <p>Lines that would exceed {@value #WIDTH} characters are truncated with
 * a trailing {@code …}.
 *
 * <h2>Usage</h2>
 * <pre>
 *   SemanticResult result = analyzer.analyze("script.relix");
 *   result.model().ifPresent(m -> System.out.print(IrReport.generate(m)));
 * </pre>
 */
public final class IrReport {

    /** Maximum line width (characters). */
    public static final int WIDTH = 80;

    private static final String DOUBLE_RULE = "═".repeat(WIDTH);
    private static final String THIN_RULE   = "─".repeat(WIDTH);

    private static final PredicatePrettyPrinter PRED = new PredicatePrettyPrinter();
    private static final OperandPrettyPrinter   OPND = new OperandPrettyPrinter();

    private IrReport() {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Generates the IR report for {@code model} and returns it as a
     * UTF-8 string.  No BOM is included.
     *
     * @param model the semantic model to report on; must not be null
     * @return the formatted report; never null or empty
     */
    public static String generate(SemanticModel model) {
        return render(model, sym -> true);
    }

    /**
     * Generates a <em>focused</em> IR report restricted to the relations named in
     * {@code focus} — used by interactive tooling (the REPL {@code :tree} command)
     * where the user names a single query and expects to see only its tree and the
     * relations it actually uses, not every symbol in the script.
     *
     * <p>The SYMBOLS and EXPRESSION TREES sections include only relations whose
     * {@link Symbol#declaredName() declared name} is in {@code focus} (matched
     * case-insensitively).  Non-relation symbols (functions) are always omitted.
     * The ROOT QUERIES section is unchanged — it already contains only the query
     * the user asked about.
     *
     * @param model the semantic model to report on; must not be null
     * @param focus the set of relation names to include; must not be null
     * @return the formatted report; never null or empty
     */
    public static String generateFocused(SemanticModel model, Set<String> focus) {
        Set<String> lower = focus.stream()
                .map(n -> n.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return render(model,
                sym -> sym instanceof RelationSymbol
                        && lower.contains(sym.declaredName().toLowerCase(Locale.ROOT)));
    }

    /**
     * Collects the names of every {@link RelationNode} leaf reachable from
     * {@code root}.  Relation references only ever appear as {@code RelationNode}
     * leaves, so a recursive child walk (via {@link #nodeChildren}) finds them all.
     *
     * @param root the root node of the tree; must not be null
     * @return the referenced relation names, in first-seen order; never null
     */
    public static Set<String> referencedRelations(RelNode root) {
        Set<String> out = new LinkedHashSet<>();
        collectRelationNames(root, out);
        return out;
    }

    private static void collectRelationNames(RelNode node, Set<String> acc) {
        if (node instanceof RelationNode rn) {
            acc.add(rn.name());
            return;
        }
        for (RelNode child : nodeChildren(node)) {
            collectRelationNames(child, acc);
        }
    }

    private static String render(SemanticModel model, Predicate<Symbol> include) {
        var sb = new StringBuilder(2048);

        // ── Header ──────────────────────────────────────────────────────────
        sb.append(DOUBLE_RULE).append('\n');
        sb.append(fit("RELIX IR  namespace=" + model.namespace())).append('\n');
        sb.append(DOUBLE_RULE).append('\n');

        // ── Key ─────────────────────────────────────────────────────────────
        sb.append("  kind: SRC=source  INL=inline  SYS=system  QR=view  DB=database\n");
        sb.append("        FN=function  TVF=table-fn  LIT=truth-literal\n");
        sb.append("  type: N=number  S=string  B=boolean  ?=any  [x]=array  {…}=struct\n");
        sb.append("        D=date  T=time  TS=timestamp  DUR=duration\n");
        sb.append("  mat:  [bag]=bag  [set]=dedup-set  [sort]=sorted  (stream=no label)\n");

        // ── Symbols ─────────────────────────────────────────────────────────
        appendSymbols(sb, model, include);

        // ── Relationships (ADR-0024; omitted when the graph is empty) ───────
        appendRelationships(sb, model);

        // ── Expression Trees ────────────────────────────────────────────────
        appendTrees(sb, model, include);

        // ── Root Queries ────────────────────────────────────────────────────
        appendRootQueries(sb, model);

        sb.append(DOUBLE_RULE).append('\n');
        return sb.toString();
    }

    // =========================================================================
    // Section: SYMBOLS
    // =========================================================================

    private static void appendSymbols(StringBuilder sb, SemanticModel model,
                                      Predicate<Symbol> include) {
        sb.append('\n').append(sectionBar("SYMBOLS")).append('\n');

        List<Symbol> all = model.symbolTable().allSymbols().stream()
                .filter(include)
                .sorted(Comparator.comparing(
                        s -> s.declaredName().toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());

        if (all.isEmpty()) {
            sb.append("  (none)\n");
            return;
        }

        int nameW = all.stream()
                .mapToInt(s -> s.declaredName().length())
                .max().orElse(4);

        for (Symbol sym : all) {
            // Format: " <name>  <KND>  <detail>"
            //         1 + nameW + 2 + 3 + 2 + detail ≤ WIDTH
            int detailMax = WIDTH - 1 - nameW - 2 - 3 - 2;
            String line = String.format(" %-" + nameW + "s  %-3s  %s",
                    sym.declaredName(),
                    kindCode(sym),
                    trunc(symbolDetail(sym), detailMax));
            sb.append(line).append('\n');
        }
    }

    // =========================================================================
    // Section: RELATIONSHIPS (ADR-0024)
    // =========================================================================

    /**
     * Renders the schema graph, one line per edge:
     * <pre>
     *  "Order Line Items"  Orders(order_id) → OrderItems(order_id) [1..50]
     *  "Manages" / "Reports To"  Employees(id) → Employees(manager_id)
     *  "Cross Sells"  Products(id) ↔ Products(related_id)
     *  "customer_id"  Orders(customer_id) → Customers(customer_id) [0..1]  (learned)
     * </pre>
     * Bounds appear only when constrained beyond the {@code [0..*]} defaults; a
     * symmetric edge uses {@code ↔}; non-declared edges carry an origin tag.
     * The whole section is omitted when the graph is empty.
     */
    private static void appendRelationships(StringBuilder sb, SemanticModel model) {
        SchemaGraph graph = model.schemaGraph();
        if (graph.isEmpty()) return;

        sb.append('\n').append(sectionBar("RELATIONSHIPS")).append('\n');
        for (Relationship r : graph.relationships()) {
            StringBuilder line = new StringBuilder(" \"").append(r.name()).append('"');
            r.inverseName().ifPresent(inv -> line.append(" / \"").append(inv).append('"'));
            line.append("  ").append(endpointLabel(r.source()));
            line.append(r.symmetric() ? " ↔ " : " → ");
            line.append(endpointLabel(r.target()));
            if (r.origin() != EdgeOrigin.DECLARED) {
                line.append("  (").append(r.origin().name().toLowerCase(Locale.ROOT)).append(')');
            }
            sb.append(fit(line.toString())).append('\n');
        }
    }

    /** {@code Rel(col, …)} plus bounds when constrained, e.g. {@code OrderItems(order_id) [1..50]}. */
    private static String endpointLabel(Endpoint e) {
        String label = e.relation().declaredName()
                + "(" + String.join(", ", e.columns()) + ")";
        return e.bounded() ? label + " " + e.boundsLabel() : label;
    }

    // =========================================================================
    // Section: EXPRESSION TREES
    // =========================================================================

    private static void appendTrees(StringBuilder sb, SemanticModel model,
                                    Predicate<Symbol> include) {
        List<QueryRelationSymbol> views = model.symbolTable().allSymbols().stream()
                .filter(include)
                .filter(s -> s instanceof QueryRelationSymbol)
                .map(s -> (QueryRelationSymbol) s)
                // Catalog views (relix.*, e.g. the derived relix.dependencies) are
                // engine internals — their derivation tree is not part of the user's
                // script, so it is omitted from the report (the inline catalogs are
                // likewise bodiless and never appear here).
                .filter(s -> !CatalogBuilder.CATALOG_NAMESPACE.equals(s.namespace()))
                .sorted(Comparator.comparing(
                        s -> s.declaredName().toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());

        if (views.isEmpty()) return;

        sb.append('\n').append(sectionBar("EXPRESSION TREES")).append('\n');

        for (QueryRelationSymbol view : views) {
            // View heading: "ViewName [QR]  col1:T  col2:T …"
            String heading = view.declaredName() + " [QR]";
            String schemaSuffix = schemaInline(view.schema());
            if (!schemaSuffix.isEmpty()) {
                String candidate = heading + "  " + schemaSuffix;
                heading = candidate.length() <= WIDTH ? candidate
                        : trunc(candidate, WIDTH);
            }
            sb.append(heading).append('\n');

            // Indented RA expression tree (2-space base indent)
            for (String line : renderTree(view.body(), model.symbolTable())) {
                sb.append(trunc("  " + line, WIDTH)).append('\n');
            }
            sb.append('\n');
        }
    }

    // =========================================================================
    // Section: ROOT QUERIES
    // =========================================================================

    private static void appendRootQueries(StringBuilder sb, SemanticModel model) {
        if (model.rootQueries().isEmpty()) return;

        sb.append(sectionBar("ROOT QUERIES")).append('\n');

        for (QueryStatement q : model.rootQueries()) {
            switch (q.target()) {
                case NamedQueryTarget named -> {
                    String kind = model.symbolTable().resolveRelation(named.name())
                            .map(r -> " [" + kindCode(r) + "]")
                            .orElse("");
                    sb.append("  query ").append(named.name()).append(kind).append('\n');
                }
                case ExpressionQueryTarget expr -> {
                    sb.append("  query {\n");
                    for (String line : renderTree(
                            expr.expression(), model.symbolTable())) {
                        sb.append(trunc("    " + line, WIDTH)).append('\n');
                    }
                    sb.append("  }\n");
                }
            }
        }
    }

    // =========================================================================
    // Tree rendering
    // =========================================================================

    /**
     * Renders a relational algebra tree rooted at {@code root} into a list
     * of lines using standard ASCII tree-drawing characters (└─, ├─, │).
     *
     * @param root  the root node of the tree
     * @param table the symbol table used to look up relation kinds for leaf nodes
     * @return ordered list of lines; does not include trailing newlines
     */
    static List<String> renderTree(RelNode root, SymbolTable table) {
        List<String> out = new ArrayList<>();
        renderNode(root, table, "", "", out);
        return out;
    }

    private static void renderNode(RelNode node, SymbolTable table,
                                   String linePrefix, String childPrefix,
                                   List<String> out) {
        out.add(linePrefix + nodeLabel(node, table));
        List<RelNode> kids = nodeChildren(node);
        for (int i = 0; i < kids.size(); i++) {
            boolean last = (i == kids.size() - 1);
            renderNode(kids.get(i), table,
                    childPrefix + (last ? "└─ " : "├─ "),
                    childPrefix + (last ? "   " : "│  "),
                    out);
        }
    }

    /**
     * Returns the single-line label for a node (operation + inline parameters),
     * without recursing into children.  Non-{@link MaterializationMode#STREAM}
     * nodes have a {@code [bag]}, {@code [set]}, or {@code [sort]} tag appended.
     */
    private static String nodeLabel(RelNode node, SymbolTable table) {
        return withMatLabel(baseLabel(node, table), node);
    }

    /**
     * Returns the operator label for {@code node} <em>without</em> any
     * materialisation tag — the σ/π/⋈ form shared by the ASCII IR tree and the
     * machine-readable JSON serializer.  Does not recurse into children.
     *
     * <p><strong>This text is queryable data, not only display.</strong>
     * {@code CatalogBuilder} populates the {@code label} column of the
     * {@code relix.plan} system catalog from this method, so a script can filter
     * or match on it (<code>σ label LIKE '%⋈%' (relix.plan)</code>). Changing a
     * label's wording therefore changes what such a query returns — treat it as
     * a user-visible change, not a cosmetic one.
     *
     * <p>Sub-expressions inside a label (window and solver clauses, sort keys,
     * generator bounds) come from {@link com.darkcollective.relix.ast.internal.DisplayLabels},
     * which the physical plan printer also uses, so those read identically in
     * {@code :tree} and {@code :explain}.
     *
     * @param node  the node to label
     * @param table the symbol table, for leaf relation kinds
     * @return the bare operator label
     */
    static String baseLabel(RelNode node, SymbolTable table) {
        return switch (node) {

            // ── Leaf ────────────────────────────────────────────────────────
            case RelationNode r -> {
                String kind = table.resolveRelation(r.name())
                        .map(s -> "[" + kindCode(s) + "]")
                        .orElse("[?]");
                yield r.name() + " " + kind;
            }

            case RelationFunctionCall f -> f.functionName() + "("
                    + f.arguments().stream().map(a -> a.accept(OPND))
                            .collect(Collectors.joining(", "))
                    + ")";

            // A nullary truth relation resolves against no symbol, so it carries
            // its own kind code rather than one looked up in the symbol table.
            case TruthRelationNode t -> t.keyword() + " [LIT]";

            // ∅ — the optimizer's empty relation, labelled with the heading it kept so
            // the reader can see which sub-tree was proved unsatisfiable.
            case EmptyRelationNode e -> "∅ ⟨" + e.heading().prettyPrint() + "⟩ [LIT]";

            // ── Unary ───────────────────────────────────────────────────────
            case SelectionNode s ->
                    "σ " + s.predicate().accept(PRED);

            case ProjectionNode p -> "π " + p.attributes().stream()
                    .map(pa -> {
                        String e = pa.expression().accept(OPND);
                        return pa.alias().map(a -> e + "→" + a).orElse(e);
                    })
                    .collect(Collectors.joining(", "));

            case RenameNode r -> {
                String name = r.relationName().orElse("");
                String cols;
                if (!r.pairs().isEmpty()) {
                    cols = "(" + r.pairs().stream()
                            .map(p -> p.from() + "→" + p.to())
                            .collect(Collectors.joining(", ")) + ")";
                } else if (!r.attributes().isEmpty()) {
                    cols = "(" + String.join(", ", r.attributes()) + ")";
                } else {
                    cols = "";
                }
                yield ("ρ " + name + cols).stripTrailing();
            }

            case AggregationNode a -> {
                String grp = a.groupingKeys().isEmpty() ? ""
                        : "[" + a.groupingKeys().stream()
                                .map(IrReport::groupingKeyLabel)
                                .collect(Collectors.joining(", ")) + "] ";
                String aggs = a.aggregates().stream()
                        .map(ag -> {
                            String args = ag.argument().accept(OPND)
                                    + ag.yieldExpr().map(y -> ", " + y.accept(OPND)).orElse("");
                            String fc = ag.operator().name() + "(" + args + ")";
                            return ag.alias().map(al -> fc + "→" + al).orElse(fc);
                        })
                        .collect(Collectors.joining(", "));
                yield "γ " + grp + aggs;
            }

            case SortNode s -> "τ " + s.sortSpecs().stream()
                    .map(DisplayLabels::sortKey)
                    .collect(Collectors.joining(", "));

            case LimitNode l -> "λ "
                    + l.offset().map(o -> o + ",").orElse("")
                    + l.count();

            case DistinctNode ignored -> "δ";

            case UnnestNode u -> "μ " + u.column() + (u.outer() ? " OUTER" : "")
                    + u.ordinalityColumn().map(c -> " WITH ORDINALITY " + c).orElse("");

            case ClosureNode c -> (c.reflexive() ? "RCLOSURE " : "CLOSURE ")
                    + c.fromColumn() + (c.undirected() ? "↔" : "→") + c.toColumn();

            case ClusterNode cl -> "CLUSTER " + cl.fromColumn() + ", " + cl.toColumn()
                    + " AS " + cl.labelColumn();

            case PathNode p -> "PATH " + p.fromColumn()
                    + (p.undirected() ? " ↔ " : ", ") + p.toColumn()
                    + " HOPS " + p.minHops() + " TO " + p.maxHops()
                    + " AS " + p.depthColumn();

            case TraceNode tr -> "TRACE " + tr.fromColumn()
                    + (tr.undirected() ? " ↔ " : ", ") + tr.toColumn()
                    + " VIA " + tr.weightColumn() + " " + tr.sense()
                    + " AS " + tr.pathColumn();

            case WindowNode wn -> DisplayLabels.window(wn.function(), wn.frame(),
                    wn.sortSpecs(), wn.partitionKeys(), wn.outputColumn());

            case SessionizeNode sn -> "SESSIONIZE " + sn.orderColumn()
                    + " GAP " + sn.threshold().accept(OPND)
                    + (sn.partitionKeys().isEmpty() ? ""
                            : " PER " + String.join(", ", sn.partitionKeys()))
                    + " AS " + sn.sessionColumn();

            case UniversalNode u -> "∀ " + (u.groupingAttributes().isEmpty()
                    ? "" : String.join(", ", u.groupingAttributes()) + " ")
                    + ": " + u.predicate().accept(PRED);

            case SampleNode s -> "SAMPLE " + s.probability()
                    + s.seed().map(seed -> " SEED " + seed).orElse("");

            case ReservoirSampleNode s -> "SAMPLE " + s.count() + " ROWS"
                    + s.seed().map(seed -> " SEED " + seed).orElse("");

            case SolveNode s -> "SOLVE " + s.left().accept(OPND)
                    + " = " + s.right().accept(OPND);

            case OptimizeNode o -> DisplayLabels.optimize(o.sense(), o.objective(),
                    o.constraints(), o.groupingKeys(), o.allocation());

            case TopKNode t -> "TOP "
                    + t.offset().map(o -> o + ", ").orElse("") + t.count() + " "
                    + t.sortSpecs().stream()
                        .map(DisplayLabels::sortKey)
                        .collect(Collectors.joining(", "))
                    + (t.groupingAttributes().isEmpty() ? ""
                        : " PER " + String.join(", ", t.groupingAttributes()));

            // ── Joins ───────────────────────────────────────────────────────
            case NaturalJoinNode   ignored -> "⋈";
            case ThetaJoinNode     tj -> "⨝ "  + tj.condition().accept(PRED);
            case LeftOuterJoinNode  lj -> "⟕ "  + lj.condition().accept(PRED);
            case RightOuterJoinNode rj -> "⟖ "  + rj.condition().accept(PRED);
            case FullOuterJoinNode  fj -> "⟗ "  + fj.condition().accept(PRED);
            case SemiJoinNode       sj -> "⋉ "  + sj.condition().accept(PRED);
            case AntiJoinNode       aj -> "▷ "  + aj.condition().accept(PRED);
            case PairwiseUniversalNode pu -> "USEMI " + pu.condition().accept(PRED);
            case AsOfJoinNode      ao -> "ASOF " + ao.condition().accept(PRED);
            case IntervalJoinNode  ij -> "IJOIN " + ij.relation().name()
                    + " (" + ij.leftStart() + ", " + ij.leftEnd()
                    + " ; " + ij.rightStart() + ", " + ij.rightEnd() + ")";
            case ProductNode        ignored -> "×";

            // ── Set ops ─────────────────────────────────────────────────────
            case UnionNode        ignored -> "∪";
            case UnionAllNode     ignored -> "⊎";
            case OuterUnionNode   ignored -> "⊔";
            case IntersectionNode ignored -> "∩";
            case DifferenceNode   ignored -> "−";
            case DivisionNode     ignored -> "÷";
            case SymmetricDifferenceNode ignored -> "∆";
            case CompositionNode  ignored -> "∘";

            // ── General recursion (FIX) ─────────────────────────────────────
            // The binder shows "FIX <name>" (with a [set] mat tag via withMatLabel)
            // and renders its base/step as the two children below; the recursive
            // reference is a bare name — it is not a symbol, so it carries no kind code.
            case FixpointNode     fx -> "FIX " + fx.name();
            case RecursiveRefNode rr -> rr.name();

            // ── Covering reduction (COVER) ───────────────────────────────────
            case CoverNode cv -> (cv.exact() ? "COVER EXACT " : "COVER ") + cv.strength();

            case DownsampleNode d -> "DOWNSAMPLE " + d.timestampColumn()
                    + " BY '" + d.interval() + "' USING " + d.function().name()
                    + (d.groupingKeys().isEmpty() ? "" : " PER " + String.join(", ", d.groupingKeys()))
                    + d.maxRows().stream().mapToObj(n -> " FOR " + n + " ROWS").findFirst().orElse("");

            // ── Lateral (correlated) TVF join ────────────────────────────────
            case LateralJoinNode n -> "LATERAL " + n.functionName() + "("
                    + n.arguments().stream().map(a -> a.accept(OPND))
                            .collect(Collectors.joining(", "))
                    + ")";

            // ── Reshape operators ────────────────────────────────────────────
            case UnpivotNode uv -> "UNPIVOT (" + String.join(", ", uv.columns()) + ")"
                    + " AS (" + uv.nameColumn() + ", " + uv.valueColumn() + ")";

            case PivotNode pv -> "PIVOT " + pv.valueColumn() + " BY " + pv.keyColumn()
                    + (pv.groupKeys().isEmpty() ? ""
                            : " PER " + String.join(", ", pv.groupKeys()));

            case TreeNode tn -> "TREE " + tn.keyColumn() + " BY " + tn.parentColumn()
                    + (tn.orderSpecs().isEmpty() ? ""
                            : " ORDER " + tn.orderSpecs().stream()
                                    .map(s -> s.expression().accept(OPND)
                                            + (s.direction() == SortDirection.DESC ? " DESC" : ""))
                                    .collect(java.util.stream.Collectors.joining(", ")))
                    + " AS " + tn.childrenColumn();

            // ── Lineage reification (WHY) — appends a provenance:ANY column ──────
            case WhyNode ignored -> "ω";
        };
    }

    /** Renders a grouping key as {@code expression[→alias]}. */
    private static String groupingKeyLabel(GroupingKey key) {
        String expr = key.expression().accept(OPND);
        return key.alias().map(a -> expr + "→" + a).orElse(expr);
    }


    /**
     * Appends a materialisation-mode tag to {@code base} for any node whose
     * mode is not {@link MaterializationMode#STREAM}.
     */
    private static String withMatLabel(String base, RelNode node) {
        return switch (node.materializationMode()) {
            case STREAM -> base;
            case BAG    -> base + "  [bag]";
            case SET    -> base + "  [set]";
            case SORTED -> base + "  [sort]";
        };
    }

    static List<RelNode> nodeChildren(RelNode node) {
        return switch (node) {
            // Leaf
            case RelationNode   ignored -> List.of();
            case RelationFunctionCall ignored -> List.of();
            case TruthRelationNode ignored -> List.of();
            case EmptyRelationNode ignored -> List.of();   // its heading is inert, not a child
            // Unary
            case SelectionNode  s -> List.of(s.input());
            case ProjectionNode p -> List.of(p.input());
            case RenameNode     r -> List.of(r.input());
            case AggregationNode a -> List.of(a.input());
            case SortNode       s -> List.of(s.input());
            case LimitNode      l -> List.of(l.input());
            case DistinctNode   d -> List.of(d.input());
            case UnnestNode     u -> List.of(u.input());
            case ClosureNode    c -> List.of(c.input());
            case ClusterNode    cl -> List.of(cl.input());
            case PathNode       p -> List.of(p.input());
            case TraceNode      tr -> List.of(tr.input());
            case UniversalNode  u -> List.of(u.input());
            case SampleNode     s -> List.of(s.input());
            case ReservoirSampleNode s -> List.of(s.input());
            case SolveNode      s -> List.of(s.input());
            case OptimizeNode   o -> List.of(o.input());
            case TopKNode       t -> List.of(t.input());
            case WindowNode     w -> List.of(w.input());
            case SessionizeNode s -> List.of(s.input());
            // Binary
            case NaturalJoinNode   nj -> List.of(nj.left(), nj.right());
            case ThetaJoinNode     tj -> List.of(tj.left(), tj.right());
            case LeftOuterJoinNode  lj -> List.of(lj.left(), lj.right());
            case RightOuterJoinNode rj -> List.of(rj.left(), rj.right());
            case FullOuterJoinNode  fj -> List.of(fj.left(), fj.right());
            case SemiJoinNode       sj -> List.of(sj.left(), sj.right());
            case AntiJoinNode       aj -> List.of(aj.left(), aj.right());
            case PairwiseUniversalNode pu -> List.of(pu.left(), pu.right());
            case AsOfJoinNode      ao -> List.of(ao.left(), ao.right());
            case IntervalJoinNode  ij -> List.of(ij.left(), ij.right());
            case ProductNode        p  -> List.of(p.left(), p.right());
            case UnionNode          u  -> List.of(u.left(), u.right());
            case UnionAllNode       ua -> List.of(ua.left(), ua.right());
            case OuterUnionNode     ou -> List.of(ou.left(), ou.right());
            case IntersectionNode   i  -> List.of(i.left(), i.right());
            case DifferenceNode     d  -> List.of(d.left(), d.right());
            case DivisionNode       dv -> List.of(dv.left(), dv.right());
            case SymmetricDifferenceNode sd -> List.of(sd.left(), sd.right());
            case CompositionNode    c  -> List.of(c.left(), c.right());
            // General recursion (FIX): the binder shows its base and step subtrees;
            // the recursive reference is a leaf.
            case FixpointNode       fx -> List.of(fx.base(), fx.step());
            case RecursiveRefNode   ignored  -> List.of();
            // Covering reduction (COVER) — unary, one child.
            case CoverNode          cv -> List.of(cv.input());
            case DownsampleNode      d -> List.of(d.input());
            // Lateral TVF join — left relation is the only relational child.
            case LateralJoinNode     n -> List.of(n.left());
            // Reshape operators — unary.
            case UnpivotNode         uv -> List.of(uv.input());
            case PivotNode           pv -> List.of(pv.input());
            case TreeNode            tn -> List.of(tn.input());
            // Lineage reification (WHY) — unary, one child.
            case WhyNode             w  -> List.of(w.input());
        };
    }

    // =========================================================================
    // Symbol helpers
    // =========================================================================

    static String kindCode(Symbol sym) {
        if (sym instanceof SourceRelationSymbol)   return "SRC";
        if (sym instanceof InlineRelationSymbol)   return "INL";
        if (sym instanceof SystemRelationSymbol)   return "SYS";
        if (sym instanceof QueryRelationSymbol)    return "QR";
        if (sym instanceof DatabaseRelationSymbol) return "DB";
        if (sym instanceof RelationFunctionSymbol) return "TVF";
        if (sym instanceof FunctionSymbol)         return "FN";
        return "?";
    }

    private static String symbolDetail(Symbol sym) {
        return switch (sym) {
            case RelationSymbol rs -> schemaInline(rs.schema());
            case FunctionSymbol fn -> functionSig(fn);
        };
    }

    /**
     * Renders a schema as {@code col1:T  col2:T  …}, or {@code (unresolved)}
     * when the schema is the {@link SymbolCollector#UNRESOLVED_SCHEMA} placeholder.
     */
    private static String schemaInline(Schema schema) {
        List<ColumnDefinition> cols = schema.columns();
        if (cols.isEmpty()) return "(no columns)";
        // Detect the UNRESOLVED placeholder: single column "*" with type ANY
        if (cols.size() == 1
                && cols.get(0).name().equals("*")
                && cols.get(0).type() == ScalarType.ANY) {
            return "(schema unresolved)";
        }
        return cols.stream()
                .map(c -> c.name() + ":" + c.type().code())
                .collect(Collectors.joining("  "));
    }


    private static String functionSig(FunctionSymbol fn) {
        String params = fn.parameters().stream()
                .map(p -> p.name() + ":" + p.type().code())
                .collect(Collectors.joining(", "));
        String ret = fn instanceof RelationFunctionSymbol ? "relation" : fn.returnType().code();
        return "(" + params + ")→" + ret;
    }

    // =========================================================================
    // Formatting utilities
    // =========================================================================

    /**
     * Returns a {@code ── TITLE ────…} bar that is exactly {@link #WIDTH} chars.
     */
    private static String sectionBar(String title) {
        String prefix = "── " + title + " ";
        int dashes = Math.max(0, WIDTH - prefix.length());
        return prefix + "─".repeat(dashes);
    }

    /**
     * Truncates {@code s} to {@code max} characters, appending {@code …} if
     * truncation occurred.  If {@code max ≤ 0} returns an empty string.
     */
    private static String trunc(String s, int max) {
        if (max <= 0)          return "";
        if (s.length() <= max) return s;
        if (max == 1)          return "…";
        return s.substring(0, max - 1) + "…";
    }

    /**
     * Truncates {@code s} to {@link #WIDTH} characters; alias for
     * {@code trunc(s, WIDTH)}.
     */
    private static String fit(String s) {
        return trunc(s, WIDTH);
    }
}

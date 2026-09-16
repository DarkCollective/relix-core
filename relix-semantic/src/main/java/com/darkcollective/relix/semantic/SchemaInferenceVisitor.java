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

import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.AggregationNode;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.ClosureNode;
import com.darkcollective.relix.ast.ClusterNode;
import com.darkcollective.relix.ast.PathNode;
import com.darkcollective.relix.ast.TraceNode;
import com.darkcollective.relix.ast.EmptyRelationNode;
import com.darkcollective.relix.ast.TruthRelationNode;
import com.darkcollective.relix.ast.CoverNode;
import com.darkcollective.relix.ast.DownsampleNode;
import com.darkcollective.relix.ast.ArrayConstruction;
import com.darkcollective.relix.ast.StructConstruction;
import com.darkcollective.relix.ast.AntiJoinNode;
import com.darkcollective.relix.ast.PairwiseUniversalNode;
import com.darkcollective.relix.ast.AttributeNames;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.BooleanOperand;
import com.darkcollective.relix.ast.DateOperand;
import com.darkcollective.relix.ast.TimeOperand;
import com.darkcollective.relix.ast.TimestampOperand;
import com.darkcollective.relix.ast.DurationOperand;
import com.darkcollective.relix.ast.CompositionNode;
import com.darkcollective.relix.ast.DifferenceNode;
import com.darkcollective.relix.ast.DistinctNode;
import com.darkcollective.relix.ast.DivisionNode;
import com.darkcollective.relix.ast.FixpointNode;
import com.darkcollective.relix.ast.FullOuterJoinNode;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.IntersectionNode;
import com.darkcollective.relix.ast.AsOfJoinNode;
import com.darkcollective.relix.ast.IntervalJoinNode;
import com.darkcollective.relix.ast.LeftOuterJoinNode;
import com.darkcollective.relix.ast.LimitNode;
import com.darkcollective.relix.ast.NaturalJoinNode;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.ProductNode;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RecursiveRefNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.RightOuterJoinNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SemiJoinNode;
import com.darkcollective.relix.ast.SetLiteralOperand;
import com.darkcollective.relix.ast.AllocationSpec;
import com.darkcollective.relix.ast.OptimizeNode;
import com.darkcollective.relix.ast.OuterUnionNode;
import com.darkcollective.relix.ast.ReservoirSampleNode;
import com.darkcollective.relix.ast.SampleNode;
import com.darkcollective.relix.ast.SolveNode;
import com.darkcollective.relix.ast.SortNode;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.ast.SymmetricDifferenceNode;
import com.darkcollective.relix.ast.ThetaJoinNode;
import com.darkcollective.relix.ast.TopKNode;
import com.darkcollective.relix.ast.UnaryOperand;
import com.darkcollective.relix.ast.UnionAllNode;
import com.darkcollective.relix.ast.UnionNode;
import com.darkcollective.relix.ast.UniversalNode;
import com.darkcollective.relix.ast.UnnestNode;
import com.darkcollective.relix.ast.WindowFunction;
import com.darkcollective.relix.ast.SessionizeNode;
import com.darkcollective.relix.ast.TreeNode;
import com.darkcollective.relix.ast.WhyNode;
import com.darkcollective.relix.ast.WindowNode;
import com.darkcollective.relix.ast.visitor.RelNodeVisitor;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.symbol.ArrayType;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ColumnProvenance;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.StructType;
import com.darkcollective.relix.symbol.Type;
import com.darkcollective.relix.ast.LateralJoinNode;
import com.darkcollective.relix.ast.PivotNode;
import com.darkcollective.relix.ast.RelationFunctionCall;
import com.darkcollective.relix.ast.UnpivotNode;
import com.darkcollective.relix.symbol.function.FunctionSymbol;
import com.darkcollective.relix.symbol.function.RelationFunctionSymbol;
import com.darkcollective.relix.symbol.relation.RelationSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Infers the output {@link Schema} of every node in a relational algebra tree.
 *
 * <p>Each {@code visit} method returns the inferred schema for that node as an
 * {@code Optional<Schema>}.  An empty optional signals that inference failed for
 * the subtree (e.g. an unresolved relation name); the failure is recorded in
 * the {@code errors} list and the node is left unannotated.
 *
 * <p>Successfully inferred schemas are written into the {@link SchemaAnnotations}
 * map supplied at construction time, keyed by object identity so that separate
 * occurrences of the same node structure are tracked independently.
 *
 * <h2>Schema rules per operation</h2>
 * <dl>
 *   <dt>RelationNode</dt><dd>schema from the symbol table</dd>
 *   <dt>SelectionNode, SortNode, LimitNode, DistinctNode</dt>
 *       <dd>passthrough of input schema</dd>
 *   <dt>ProjectionNode</dt>
 *       <dd>one column per projected attribute; name from alias or attribute name;
 *           type inferred from operand kind and input schema</dd>
 *   <dt>RenameNode</dt>
 *       <dd>if attribute list is non-empty, rename columns positionally;
 *           otherwise same schema as input</dd>
 *   <dt>NaturalJoinNode</dt>
 *       <dd>left columns, then right columns whose names do not appear in left</dd>
 *   <dt>ThetaJoinNode, LeftOuterJoinNode, RightOuterJoinNode, FullOuterJoinNode,
 *       ProductNode</dt>
 *       <dd>concatenation of left and right schemas; right-side duplicate column
 *           names are suffixed with {@code _r} (and {@code _r1}, {@code _r2}, …)
 *           to keep names unique</dd>
 *   <dt>SemiJoinNode, AntiJoinNode</dt><dd>left schema only</dd>
 *   <dt>UnionNode, UnionAllNode, IntersectionNode, DifferenceNode</dt>
 *       <dd>left schema (compatible with right by convention)</dd>
 *   <dt>DivisionNode</dt>
 *       <dd>left columns whose names do not appear in the right schema</dd>
 *   <dt>AggregationNode</dt>
 *       <dd>group-by columns (from input schema) followed by one column per
 *           aggregate function (alias or {@code operator_attribute} name)</dd>
 * </dl>
 */
final class SchemaInferenceVisitor implements RelNodeVisitor<Optional<Schema>> {

    private final SymbolTable         symbolTable;
    private final SchemaAnnotations   annotations;
    private final List<SemanticError> errors;
    private final String              filePath;
    private final FunctionCatalog     functions;
    private final OperandTypeInferrer typeInferrer;

    /**
     * Stack of in-scope {@code FIX} recursive-name bindings (name → the schema the
     * recursive relation accumulates, i.e. the binder's {@code base} schema).
     * Pushed while inferring a {@link FixpointNode}'s {@code step} and popped on
     * exit, so a {@link RecursiveRefNode} in the step resolves to the bound schema.
     * A nested {@code FIX} of the same name shadows the outer binding because the
     * stack is searched innermost-first.
     */
    private final Deque<Map.Entry<String, Schema>> recursionScopes = new ArrayDeque<>();

    SchemaInferenceVisitor(SymbolTable symbolTable,
                           SchemaAnnotations annotations,
                           List<SemanticError> errors,
                           String filePath,
                           FunctionCatalog functions) {
        this.symbolTable  = Objects.requireNonNull(symbolTable,  "symbolTable");
        this.annotations  = Objects.requireNonNull(annotations,  "annotations");
        this.errors       = Objects.requireNonNull(errors,        "errors");
        this.filePath     = Objects.requireNonNull(filePath,      "filePath");
        this.functions    = Objects.requireNonNull(functions,     "functions");
        this.typeInferrer = new OperandTypeInferrer(symbolTable, functions);
    }

    // =========================================================================
    // Leaf nodes
    // =========================================================================

    @Override
    public Optional<Schema> visit(RelationNode node) {
        Optional<RelationSymbol> sym = lookupRelation(node.name());
        if (sym.isEmpty()) {
            errors.add(SemanticError.error(
                    node.location().filePath(),
                    node.location().line(),
                    node.location().column(),
                    "Undefined relation: '" + node.name() + "'"));
            return Optional.empty();
        }
        Schema schema = sym.get().schema();
        if (schema.equals(SymbolCollector.UNRESOLVED_SCHEMA)) {
            // Dependency not yet inferred — propagate failure silently.
            return Optional.empty();
        }
        // Stamp each column with its source-relation origin, keyed by
        // the name the query used to reference this relation, so a qualified
        // reference (`Users.id`) resolves precisely — including above a join, where
        // the physical column may later be renamed to disambiguate a collision.
        return annotate(node, stampProvenance(schema, node.name()));
    }

    /**
     * A nullary truth-relation literal ({@code UNIT}/{@code EMPTY}) has the empty
     * (closed, zero-column) heading — the same schema the no-key whole-relation
     * {@code ∀} produces.  It resolves against no symbol, so it can never fail.
     */
    @Override
    public Optional<Schema> visit(TruthRelationNode node) {
        return annotate(node, Schema.empty());
    }

    /**
     * The optimizer-introduced empty relation ({@code ∅}) has the heading of the
     * expression it replaced, so inference recurses into that carried expression and
     * reports its schema.
     *
     * <p>The recursion also annotates the carried expression's own nodes, which is
     * harmless — nothing plans or executes them — and is what keeps the heading
     * inferable at all: this node holds a {@code RelNode}, not a {@code Schema},
     * because {@code relix-ast} does not depend on the symbol layer.
     */
    @Override
    public Optional<Schema> visit(EmptyRelationNode node) {
        return annotate(node, node.heading().accept(this).orElse(SymbolCollector.UNRESOLVED_SCHEMA));
    }

    @Override
    public Optional<Schema> visit(RelationFunctionCall node) {
        Optional<RelationFunctionSymbol> sym = lookupRelationFunction(node.functionName());
        if (sym.isEmpty()) {
            errors.add(SemanticError.error(
                    node.location().filePath(),
                    node.location().line(),
                    node.location().column(),
                    "Undefined table-valued function: '" + node.functionName() + "'"));
            return Optional.empty();
        }
        // The call's output schema is the function body's inferred output schema,
        // resolved during the inference phase (Phase 4a').  If the body could not be
        // resolved (e.g. an unresolved dependency, or a recursive definition), the
        // failure propagates silently — like any other unresolved subtree.
        Optional<Schema> returnSchema = sym.get().returnSchema();
        return returnSchema.isEmpty() ? Optional.empty() : annotate(node, returnSchema.get());
    }

    @Override
    public Optional<Schema> visit(LateralJoinNode node) {
        // Infer left schema first.
        Optional<Schema> leftOpt = node.left().accept(this);
        if (leftOpt.isEmpty()) return Optional.empty();

        // Look up the TVF.
        Optional<RelationFunctionSymbol> sym = lookupRelationFunction(node.functionName());
        if (sym.isEmpty()) {
            errors.add(SemanticError.error(
                    node.location().filePath(),
                    node.location().line(),
                    node.location().column(),
                    "Undefined table-valued function: '" + node.functionName() + "'"));
            return Optional.empty();
        }
        Optional<Schema> tvfSchema = sym.get().returnSchema();
        if (tvfSchema.isEmpty()) return Optional.empty();

        // Output = left columns ++ TVF body columns (right-side duplicates renamed).
        Schema schema = leftOpt.get().concat(tvfSchema.get());
        return annotate(node, schema);
    }

    // =========================================================================
    // General recursion (FIX)
    // =========================================================================

    @Override
    public Optional<Schema> visit(FixpointNode node) {
        // 1. Infer the base — the recursive name is NOT in scope here (the seed is
        //    non-recursive).  Without a base schema we cannot type the recursive
        //    reference, so a failure propagates.
        Optional<Schema> baseOpt = node.base().accept(this);
        if (baseOpt.isEmpty()) return Optional.empty();
        Schema base = baseOpt.get();

        // 2. Bind name → base schema and infer the step (so its subtree, including
        //    the recursive reference, gets annotated).  Pop the binding on exit.
        recursionScopes.push(Map.entry(node.name(), base));
        try {
            node.step().accept(this);
        } finally {
            recursionScopes.pop();
        }

        // 3. Output schema = base schema (the canonical heading; an open base
        //    propagates an open output).  Union-compatibility of the step against
        //    the base is enforced by the validator (same rule as ∪).
        return annotate(node, base);
    }

    @Override
    public Optional<Schema> visit(RecursiveRefNode node) {
        // Resolve the recursive name against the in-scope FIX bindings, innermost
        // first (so a nested FIX of the same name shadows the outer binder).
        for (Map.Entry<String, Schema> binding : recursionScopes) {
            if (binding.getKey().equals(node.name())) {
                return annotate(node, binding.getValue());
            }
        }
        errors.add(SemanticError.error(
                node.location().filePath(),
                node.location().line(),
                node.location().column(),
                "Recursive reference '" + node.name()
                        + "' is not bound by any enclosing FIX"));
        return Optional.empty();
    }

    // =========================================================================
    // Passthrough nodes — output schema == input schema
    // =========================================================================

    @Override
    public Optional<Schema> visit(SelectionNode node) {
        return passthrough(node, node.input());
    }

    @Override
    public Optional<Schema> visit(SortNode node) {
        return passthrough(node, node.input());
    }

    @Override
    public Optional<Schema> visit(LimitNode node) {
        return passthrough(node, node.input());
    }

    @Override
    public Optional<Schema> visit(DistinctNode node) {
        return passthrough(node, node.input());
    }

    @Override
    public Optional<Schema> visit(SampleNode node) {
        return passthrough(node, node.input());
    }

    @Override
    public Optional<Schema> visit(ReservoirSampleNode node) {
        return passthrough(node, node.input());
    }

    @Override
    public Optional<Schema> visit(SolveNode node) {
        // Goal-seek fills a NULL column in place; the column set is unchanged.
        return passthrough(node, node.input());
    }

    @Override
    public Optional<Schema> visit(OptimizeNode node) {
        Optional<Schema> inputOpt = node.input().accept(this);
        if (inputOpt.isEmpty()) return Optional.empty();
        Schema input = inputOpt.get();
        if (node.allocation().isEmpty()) {
            // MIP mode: chosen rows are emitted verbatim; schema unchanged.
            return annotate(node, input);
        }
        // LP mode: every row is emitted with its allocation value appended.
        AllocationSpec spec = node.allocation().get();
        if (input.column(spec.columnName()).isPresent()) {
            // Column name clash — return the input schema so the validator can report the error.
            return annotate(node, input);
        }
        List<ColumnDefinition> cols = new ArrayList<>(input.columns());
        cols.add(new ColumnDefinition(spec.columnName(), ScalarType.NUMBER));
        return annotate(node, new Schema(cols));
    }

    @Override
    public Optional<Schema> visit(TopKNode node) {
        return passthrough(node, node.input());
    }

    @Override
    public Optional<Schema> visit(DownsampleNode node) {
        Optional<Schema> inputOpt = node.input().accept(this);
        if (inputOpt.isEmpty()) return Optional.empty();
        Schema input = inputOpt.get();

        if (input.isOpen()) {
            return annotate(node, input);
        }

        List<ColumnDefinition> cols = new ArrayList<>();
        // Grouping keys — preserve type from input
        for (String key : node.groupingKeys()) {
            Type type = input.column(key).map(ColumnDefinition::type).orElse(ScalarType.ANY);
            cols.add(new ColumnDefinition(key, type));
        }
        // Bucket column
        cols.add(new ColumnDefinition(DownsampleColumns.BUCKET_COLUMN, ScalarType.TIMESTAMP));
        // Consolidated columns — the rule the executor reads too, so the heading
        // inferred here is the one the rows fill.
        for (DownsampleColumns.Consolidation consolidation : DownsampleColumns.of(node.function(), node.groupingKeys(),
                node.timestampColumn(), input)) {
            cols.add(consolidation.output());
        }
        return annotate(node, new Schema(cols));
    }

    @Override
    public Optional<Schema> visit(CoverNode node) {
        // Covering reduction emits a subset of the input rows; schema is unchanged.
        return passthrough(node, node.input());
    }

    @Override
    public Optional<Schema> visit(UnnestNode node) {
        // Unnest changes cardinality, not the column set.  The unnested column's
        // type becomes its array element type (NF² — μ is the inverse of NEST);
        // an ANY (schema-on-read) column stays ANY.
        Optional<Schema> inputOpt = node.input().accept(this);
        if (inputOpt.isEmpty()) return Optional.empty();
        Schema input = inputOpt.get();

        // An open (schema-on-read) input has no fixed columns; keep it open — the
        // unnested element type is resolved dynamically at runtime.
        if (input.isOpen()) {
            return annotate(node, input);
        }

        List<ColumnDefinition> cols = new ArrayList<>(input.columns().stream()
                .map(c -> c.name().equalsIgnoreCase(node.column()) && c.type() instanceof ArrayType arr
                        ? new ColumnDefinition(c.name(), arr.element())
                        : c)
                .toList());
        // WITH ORDINALITY appends a 1-based element-index column (NUMBER).  Skip the
        // append if the name already exists so inference degrades gracefully rather
        // than throwing on the duplicate — the validator reports the clash.
        node.ordinalityColumn().ifPresent(name -> {
            if (cols.stream().noneMatch(c -> c.name().equalsIgnoreCase(name))) {
                cols.add(new ColumnDefinition(name, ScalarType.NUMBER));
            }
        });
        return annotate(node, new Schema(cols));
    }

    @Override
    public Optional<Schema> visit(ClosureNode node) {
        // Closure output is a binary relation of just the two edge columns,
        // carrying their input types; all other input columns are dropped.
        Optional<Schema> inputOpt = node.input().accept(this);
        if (inputOpt.isEmpty()) return Optional.empty();
        Schema input = inputOpt.get();

        Optional<ColumnDefinition> from = input.column(node.fromColumn());
        Optional<ColumnDefinition> to   = input.column(node.toColumn());
        // Unresolvable columns, or a from == to clash that cannot form a two-column
        // schema, are left unannotated; the validator reports the specific error.
        if (from.isEmpty() || to.isEmpty()
                || node.fromColumn().equalsIgnoreCase(node.toColumn())) {
            return Optional.empty();
        }
        Schema output = new Schema(List.of(
                new ColumnDefinition(node.fromColumn(), from.get().type()),
                new ColumnDefinition(node.toColumn(), to.get().type())));
        return annotate(node, output);
    }

    @Override
    public Optional<Schema> visit(ClusterNode node) {
        // Connected-components output is a binary relation: the node-identifier
        // column (keeping the 'from' column name and type) plus the NUMBER
        // component-label column; all other input columns are dropped.
        Optional<Schema> inputOpt = node.input().accept(this);
        if (inputOpt.isEmpty()) return Optional.empty();
        Schema input = inputOpt.get();

        Optional<ColumnDefinition> from = input.column(node.fromColumn());
        Optional<ColumnDefinition> to   = input.column(node.toColumn());
        // Unresolvable columns, a from == to clash, or a label that would collide
        // with the node column are left unannotated; the validator reports the
        // specific error.
        if (from.isEmpty() || to.isEmpty()
                || node.fromColumn().equalsIgnoreCase(node.toColumn())
                || node.fromColumn().equalsIgnoreCase(node.labelColumn())) {
            return Optional.empty();
        }
        Schema output = new Schema(List.of(
                new ColumnDefinition(node.fromColumn(), from.get().type()),
                new ColumnDefinition(node.labelColumn(), ScalarType.NUMBER)));
        return annotate(node, output);
    }

    @Override
    public Optional<Schema> visit(PathNode node) {
        // Bounded-path output is a ternary relation: the source-endpoint column
        // (keeping the 'from' name and type), the target-endpoint column (keeping
        // the 'to' name and type), and the NUMBER hop-distance column.
        Optional<Schema> inputOpt = node.input().accept(this);
        if (inputOpt.isEmpty()) return Optional.empty();
        Schema input = inputOpt.get();

        Optional<ColumnDefinition> from = input.column(node.fromColumn());
        Optional<ColumnDefinition> to   = input.column(node.toColumn());
        // Unresolvable columns, a from == to clash, or a depth column colliding with
        // either endpoint are left unannotated; the validator reports the specific error.
        if (from.isEmpty() || to.isEmpty()
                || node.fromColumn().equalsIgnoreCase(node.toColumn())
                || node.depthColumn().equalsIgnoreCase(node.fromColumn())
                || node.depthColumn().equalsIgnoreCase(node.toColumn())) {
            return Optional.empty();
        }
        Schema output = new Schema(List.of(
                new ColumnDefinition(node.fromColumn(), from.get().type()),
                new ColumnDefinition(node.toColumn(), to.get().type()),
                new ColumnDefinition(node.depthColumn(), ScalarType.NUMBER)));
        return annotate(node, output);
    }

    @Override
    public Optional<Schema> visit(TraceNode node) {
        // TRACE output: (fromColumn:T, toColumn:T, weightColumn:NUMBER, pathColumn:array<T>)
        // where T is the endpoint column type. Unresolvable columns or name clashes
        // are left unannotated; the validator reports the specific errors.
        Optional<Schema> inputOpt = node.input().accept(this);
        if (inputOpt.isEmpty()) return Optional.empty();
        Schema input = inputOpt.get();

        Optional<ColumnDefinition> fromDef   = input.column(node.fromColumn());
        Optional<ColumnDefinition> toDef     = input.column(node.toColumn());
        Optional<ColumnDefinition> weightDef = input.column(node.weightColumn());
        if (fromDef.isEmpty() || toDef.isEmpty() || weightDef.isEmpty()) {
            return Optional.empty();
        }
        // Guard against name clashes that the validator will reject.
        String f = node.fromColumn(), t = node.toColumn(),
               w = node.weightColumn(), p = node.pathColumn();
        if (f.equalsIgnoreCase(t) || p.equalsIgnoreCase(f)
                || p.equalsIgnoreCase(t) || p.equalsIgnoreCase(w)) {
            return Optional.empty();
        }
        Type nodeType = fromDef.get().type();
        Schema output = new Schema(List.of(
                new ColumnDefinition(f, nodeType),
                new ColumnDefinition(t, nodeType),
                new ColumnDefinition(w, ScalarType.NUMBER),
                new ColumnDefinition(p, new ArrayType(nodeType))));
        return annotate(node, output);
    }

    @Override
    public Optional<Schema> visit(WindowNode node) {
        // Window output = input schema + the computed column appended.  An open
        // (schema-on-read) input has no fixed columns; keep it open.  A name clash
        // skips the append so inference degrades gracefully — the validator reports it.
        Optional<Schema> inputOpt = node.input().accept(this);
        if (inputOpt.isEmpty()) return Optional.empty();
        Schema input = inputOpt.get();
        if (input.isOpen()) {
            return annotate(node, input);
        }
        if (input.column(node.outputColumn()).isPresent()) {
            return annotate(node, input);
        }
        List<ColumnDefinition> cols = new ArrayList<>(input.columns());
        cols.add(new ColumnDefinition(node.outputColumn(), windowOutputType(node.function(), input)));
        return annotate(node, new Schema(cols));
    }

    @Override
    public Optional<Schema> visit(SessionizeNode node) {
        // Sessionize output = input schema + the NUMBER session-id column appended.
        // An open (schema-on-read) input has no fixed columns; keep it open.  A name
        // clash skips the append so inference degrades gracefully — the validator
        // reports it.
        Optional<Schema> inputOpt = node.input().accept(this);
        if (inputOpt.isEmpty()) return Optional.empty();
        Schema input = inputOpt.get();
        if (input.isOpen()) {
            return annotate(node, input);
        }
        if (input.column(node.sessionColumn()).isPresent()) {
            return annotate(node, input);
        }
        List<ColumnDefinition> cols = new ArrayList<>(input.columns());
        cols.add(new ColumnDefinition(node.sessionColumn(), ScalarType.NUMBER));
        return annotate(node, new Schema(cols));
    }

    @Override
    public Optional<Schema> visit(TreeNode node) {
        // TREE output = input schema + the children:ANY nested array column appended
        // (one row per root, each carrying its subtree as a recursive ANY document —
        // no recursive static type in v1, consistent with ADR-0001).  An open
        // (schema-on-read) input keeps its open schema; a name clash with the children
        // column skips the append so inference degrades gracefully — the validator
        // reports it.
        Optional<Schema> inputOpt = node.input().accept(this);
        if (inputOpt.isEmpty()) return Optional.empty();
        Schema input = inputOpt.get();
        if (input.isOpen()) {
            return annotate(node, input);
        }
        if (input.column(node.childrenColumn()).isPresent()) {
            return annotate(node, input);
        }
        List<ColumnDefinition> cols = new ArrayList<>(input.columns());
        cols.add(new ColumnDefinition(node.childrenColumn(), ScalarType.ANY));
        return annotate(node, new Schema(cols));
    }

    @Override
    public Optional<Schema> visit(WhyNode node) {
        // WHY output = input schema + the reserved provenance:ANY column appended,
        // holding each tuple's lineage polynomial as a nested document (ADR-0018).
        // An open (schema-on-read) input keeps its open schema; a clash with an
        // existing `provenance` column skips the append so inference degrades
        // gracefully — the validator reports the reserved-name clash.
        Optional<Schema> inputOpt = node.input().accept(this);
        if (inputOpt.isEmpty()) return Optional.empty();
        Schema input = inputOpt.get();
        if (input.isOpen()) {
            return annotate(node, input);
        }
        if (input.column(WhyNode.PROVENANCE_COLUMN).isPresent()) {
            return annotate(node, input);
        }
        List<ColumnDefinition> cols = new ArrayList<>(input.columns());
        cols.add(new ColumnDefinition(WhyNode.PROVENANCE_COLUMN, ScalarType.ANY));
        return annotate(node, new Schema(cols));
    }

    @Override
    public Optional<Schema> visit(UnpivotNode node) {
        // UNPIVOT output = input schema MINUS the listed columns + nameCol:STRING + valueCol:ANY.
        // An open (schema-on-read) input: cannot subtract columns statically, return open.
        // A name clash with remaining columns: skip the append — validator reports it.
        Optional<Schema> inputOpt = node.input().accept(this);
        if (inputOpt.isEmpty()) return Optional.empty();
        Schema input = inputOpt.get();
        if (input.isOpen()) {
            return annotate(node, input);
        }
        List<ColumnDefinition> cols = new ArrayList<>();
        for (ColumnDefinition c : input.columns()) {
            if (!node.columns().contains(c.name())) {
                cols.add(c);
            }
        }
        // Append nameCol:STRING if no clash
        if (cols.stream().noneMatch(c -> c.name().equals(node.nameColumn()))) {
            cols.add(new ColumnDefinition(node.nameColumn(), ScalarType.STRING));
        }
        // Append valueCol:ANY if no clash
        if (cols.stream().noneMatch(c -> c.name().equals(node.valueColumn()))) {
            cols.add(new ColumnDefinition(node.valueColumn(), ScalarType.ANY));
        }
        return annotate(node, new Schema(cols));
    }

    @Override
    public Optional<Schema> visit(PivotNode node) {
        // PIVOT output schema is always open (dynamic column names depend on runtime key values).
        node.input().accept(this);  // still annotate the input for downstream validation
        return annotate(node, Schema.open());
    }

    /** The output type of a window function: COUNT/ranking → NUMBER, else the argument type. */
    private Type windowOutputType(WindowFunction function, Schema input) {
        return switch (function) {
            case WindowFunction.AggregateWindow a ->
                    a.operator() == AggregateOperator.COUNT
                            ? ScalarType.NUMBER
                            : inferOperandType(a.argument(), input);
            case WindowFunction.RankingWindow _ -> ScalarType.NUMBER;
            case WindowFunction.OffsetWindow o -> inferOperandType(o.expression(), input);
        };
    }

    // =========================================================================
    // Projection
    // =========================================================================

    @Override
    public Optional<Schema> visit(ProjectionNode node) {
        Optional<Schema> inputOpt = node.input().accept(this);
        if (inputOpt.isEmpty()) return Optional.empty();
        Schema input = inputOpt.get();

        List<ColumnDefinition> cols = new ArrayList<>();
        for (int i = 0; i < node.attributes().size(); i++) {
            ProjectedAttribute attr = node.attributes().get(i);
            final int idx = i; // effectively final for the lambda
            String name = attr.alias()
                    .orElseGet(() -> inferColumnName(attr.expression(), idx));
            Type type = inferOperandType(attr.expression(), input);
            // A bare pass-through attribute keeps the origin of the input column it
            // names, so a qualified reference survives a projection;
            // a computed/aliased expression has no single source relation.
            ColumnProvenance prov = passThroughProvenance(attr.expression(), input);
            cols.add(new ColumnDefinition(name, type, prov));
        }

        if (cols.isEmpty()) {
            // Degenerate projection — Schema requires ≥ 1 column.
            return Optional.empty();
        }

        // Deduplicate column names by suffixing conflicts with _1, _2, …
        cols = deduplicateColumns(cols);
        return annotate(node, new Schema(cols));
    }

    // =========================================================================
    // Rename
    // =========================================================================

    @Override
    public Optional<Schema> visit(RenameNode node) {
        Optional<Schema> inputOpt = node.input().accept(this);
        if (inputOpt.isEmpty()) return Optional.empty();
        Schema input = inputOpt.get();

        if (!node.renamesColumns()) {
            // Relation name changes only — columns keep their names but re-anchor
            // their origin to the new relation name: after `ρ R (S)`,
            // the qualifier for every column is `R`.
            return annotate(node, stampProvenance(input, node.relationName().orElseThrow()));
        }

        if (input.columns().isEmpty()) {
            // No known columns to map — pass the schema through, re-anchoring only when a
            // new relation name is supplied. An open heading still resolves the new names,
            // and the executor renames the fields each document carries (#977).
            return annotate(node, node.relationName()
                    .map(name -> stampProvenance(input, name))
                    .orElse(input));
        }

        // Column-renaming form. Compute each output column's new name, then re-anchor
        // its origin: to the new relation name when one is given (so `R.new_id`
        // resolves precisely), otherwise keeping the input column's existing origin
        // relation under the new column name (textbook ρ_{a→b}(R), no relation rename).
        List<String> newNames = renamedColumnNames(node, input);
        List<ColumnDefinition> cols = new ArrayList<>();
        for (int i = 0; i < input.columns().size(); i++) {
            ColumnDefinition src  = input.columns().get(i);
            String           name = newNames.get(i);
            ColumnProvenance prov;
            if (node.relationName().isPresent()) {
                prov = new ColumnProvenance(node.relationName().get(), name);
            } else if (src.provenance() != null) {
                prov = new ColumnProvenance(src.provenance().relation(), name);
            } else {
                prov = null;
            }
            cols.add(new ColumnDefinition(name, src.type(), prov));
        }
        // A collision (e.g. a pair renaming onto a surviving column) is a validation
        // error, but inference must still yield a well-formed schema for downstream
        // nodes — so deduplicate rather than let the Schema constructor throw. An open
        // heading's known columns are renamed like any others, and it stays open (#977).
        return annotate(node, input.withColumns(deduplicateColumns(cols)));
    }

    /**
     * The output column names of a column-renaming ρ, positionally over {@code input}.
     * Positional form assigns names in order (extra input columns keep their names);
     * pair form renames each matched {@code old → new} column (case-insensitively) and
     * passes unlisted columns through unchanged.
     */
    private static List<String> renamedColumnNames(RenameNode node, Schema input) {
        List<String> names = new ArrayList<>(input.columns().size());
        if (!node.pairs().isEmpty()) {
            Map<String, String> byLower = new LinkedHashMap<>();
            for (RenameNode.RenamePair p : node.pairs()) {
                byLower.put(p.from().toLowerCase(Locale.ROOT), p.to());
            }
            for (ColumnDefinition src : input.columns()) {
                names.add(byLower.getOrDefault(src.name().toLowerCase(Locale.ROOT), src.name()));
            }
        } else {
            List<String> positional = node.attributes();
            for (int i = 0; i < input.columns().size(); i++) {
                names.add(i < positional.size() ? positional.get(i) : input.columns().get(i).name());
            }
        }
        return names;
    }

    // =========================================================================
    // Joins — concatenating schemas
    // =========================================================================

    @Override
    public Optional<Schema> visit(ThetaJoinNode node) {
        return binaryConcat(node, node.left(), node.right());
    }

    @Override
    public Optional<Schema> visit(LeftOuterJoinNode node) {
        return binaryConcat(node, node.left(), node.right());
    }

    @Override
    public Optional<Schema> visit(AsOfJoinNode node) {
        // Left-outer concatenation: left ⊕ right (right columns NULL on no match).
        return binaryConcat(node, node.left(), node.right());
    }

    @Override
    public Optional<Schema> visit(IntervalJoinNode node) {
        // Inner join: concatenation of left and right schemas (no NULL padding).
        return binaryConcat(node, node.left(), node.right());
    }

    @Override
    public Optional<Schema> visit(RightOuterJoinNode node) {
        return binaryConcat(node, node.left(), node.right());
    }

    @Override
    public Optional<Schema> visit(FullOuterJoinNode node) {
        return binaryConcat(node, node.left(), node.right());
    }

    @Override
    public Optional<Schema> visit(ProductNode node) {
        return binaryConcat(node, node.left(), node.right());
    }

    // =========================================================================
    // Natural join — deduplicate shared columns
    // =========================================================================

    @Override
    public Optional<Schema> visit(NaturalJoinNode node) {
        Optional<Schema> leftOpt  = node.left().accept(this);
        Optional<Schema> rightOpt = node.right().accept(this);
        if (leftOpt.isEmpty() || rightOpt.isEmpty()) return Optional.empty();

        Schema left  = leftOpt.get();
        Schema right = rightOpt.get();
        // A schema-on-read input has no declared columns to share, and the join keys
        // are settled at plan time from the two schemas — so inferring an open result
        // here would produce a query that analyses, runs, and returns nothing at all.
        // Rejecting it says the same thing while the user can still act on it.
        //
        // Matching on whatever names two documents turn out to share is the behaviour
        // this ought to have; it needs the join planner to work by name rather than by
        // column index, which is a good deal more than a heading rule.
        if (left.isOpen() || right.isOpen()) {
            errors.add(SemanticError.error(
                    node.location().filePath(),
                    node.location().line(),
                    node.location().column(),
                    "Natural join ⋈: open (schema-on-read) input is not supported — the "
                            + "shared columns are settled before any row is read, so there "
                            + "are none to match on. Use a theta join with an explicit "
                            + "condition (L ⨝ L.id = R.id R), which resolves its columns "
                            + "per row"));
            return Optional.empty();
        }
        Schema schema = naturalJoinSchema(left, right);
        return annotate(node, schema);
    }

    // =========================================================================
    // Semi-join and anti-join — left schema only
    // =========================================================================

    @Override
    public Optional<Schema> visit(SemiJoinNode node) {
        Optional<Schema> leftOpt = node.left().accept(this);
        // Still visit right so all nodes get annotated (errors surfaced).
        node.right().accept(this);
        return leftOpt.map(s -> {
            annotate(node, s);
            return s;
        });
    }

    @Override
    public Optional<Schema> visit(AntiJoinNode node) {
        Optional<Schema> leftOpt = node.left().accept(this);
        node.right().accept(this);
        return leftOpt.map(s -> {
            annotate(node, s);
            return s;
        });
    }

    @Override
    public Optional<Schema> visit(PairwiseUniversalNode node) {
        Optional<Schema> leftOpt = node.left().accept(this);
        node.right().accept(this);
        return leftOpt.map(s -> {
            annotate(node, s);
            return s;
        });
    }

    // =========================================================================
    // Set operations — left schema
    // =========================================================================

    @Override
    public Optional<Schema> visit(UnionNode node) {
        return leftSchema(node, node.left(), node.right());
    }

    @Override
    public Optional<Schema> visit(UnionAllNode node) {
        return leftSchema(node, node.left(), node.right());
    }

    @Override
    public Optional<Schema> visit(OuterUnionNode node) {
        Optional<Schema> leftOpt = node.left().accept(this);
        Optional<Schema> rightOpt = node.right().accept(this);
        if (leftOpt.isEmpty() || rightOpt.isEmpty()) {
            return Optional.empty();
        }
        Schema left = leftOpt.get();
        Schema right = rightOpt.get();
        // A dynamic (schema-on-read) input poisons the merge: the merged column
        // set is unknowable statically, so the output is open too.
        if (left.isOpen() || right.isOpen()) {
            return annotate(node, Schema.open());
        }
        return annotate(node, mergeSchemas(left, right));
    }

    @Override
    public Optional<Schema> visit(IntersectionNode node) {
        return leftSchema(node, node.left(), node.right());
    }

    @Override
    public Optional<Schema> visit(DifferenceNode node) {
        return leftSchema(node, node.left(), node.right());
    }

    @Override
    public Optional<Schema> visit(SymmetricDifferenceNode node) {
        return leftSchema(node, node.left(), node.right());
    }

    // =========================================================================
    // Division — left columns minus right columns
    // =========================================================================

    @Override
    public Optional<Schema> visit(DivisionNode node) {
        Optional<Schema> leftOpt  = node.left().accept(this);
        Optional<Schema> rightOpt = node.right().accept(this);
        if (leftOpt.isEmpty() || rightOpt.isEmpty()) return Optional.empty();

        Schema left  = leftOpt.get();
        Schema right = rightOpt.get();

        Set<String> rightNames = new LinkedHashSet<>();
        right.columns().forEach(c -> rightNames.add(c.name().toLowerCase(Locale.ROOT)));

        List<ColumnDefinition> remaining = left.columns().stream()
                .filter(c -> !rightNames.contains(c.name().toLowerCase(Locale.ROOT)))
                .toList();

        if (remaining.isEmpty()) {
            errors.add(SemanticError.error(
                    node.location().filePath(),
                    node.location().line(),
                    node.location().column(),
                    left.isOpen() || right.isOpen()
                            ? "Division ÷: open (schema-on-read) input is not allowed — the "
                              + "result heading is the left columns minus the right ones, "
                              + "which cannot be computed from a heading nothing declares"
                            : "Division result schema is empty — every left column appears "
                              + "in the right schema"));
            return Optional.empty();
        }
        return annotate(node, new Schema(remaining));
    }

    // =========================================================================
    // Composition — natural join on shared columns, then drop them
    // =========================================================================

    @Override
    public Optional<Schema> visit(CompositionNode node) {
        Optional<Schema> leftOpt  = node.left().accept(this);
        Optional<Schema> rightOpt = node.right().accept(this);
        if (leftOpt.isEmpty() || rightOpt.isEmpty()) return Optional.empty();

        Schema left  = leftOpt.get();
        Schema right = rightOpt.get();

        // Unlike ⋈, composition cannot defer to run time: it drops the shared columns,
        // so its result heading depends on knowing which they are. An open input makes
        // that unknowable, and saying the inputs "share no columns" would assert
        // something false about data nobody has described.
        if (left.isOpen() || right.isOpen()) {
            errors.add(SemanticError.error(
                    node.location().filePath(),
                    node.location().line(),
                    node.location().column(),
                    "Composition ∘: open (schema-on-read) input is not allowed — the result "
                            + "drops the shared columns, so which they are must be known"));
            return Optional.empty();
        }

        Set<String> shared = sharedColumnNames(left, right);
        if (shared.isEmpty()) {
            errors.add(SemanticError.error(
                    node.location().filePath(),
                    node.location().line(),
                    node.location().column(),
                    "Composition ∘ requires the inputs to share at least one column"));
            return Optional.empty();
        }

        List<ColumnDefinition> cols = new ArrayList<>();
        left.columns().stream()
                .filter(c -> !shared.contains(c.name().toLowerCase(Locale.ROOT)))
                .forEach(cols::add);
        right.columns().stream()
                .filter(c -> !shared.contains(c.name().toLowerCase(Locale.ROOT)))
                .forEach(cols::add);

        if (cols.isEmpty()) {
            errors.add(SemanticError.error(
                    node.location().filePath(),
                    node.location().line(),
                    node.location().column(),
                    "Composition ∘ result schema is empty — the inputs share every column"));
            return Optional.empty();
        }
        return annotate(node, new Schema(cols));
    }

    // =========================================================================
    // Universal quantification — output is the grouping-key columns, or the
    // empty (closed, zero-column) schema for the no-key whole-relation form
    // =========================================================================

    @Override
    public Optional<Schema> visit(UniversalNode node) {
        Optional<Schema> inputOpt = node.input().accept(this);
        if (inputOpt.isEmpty()) return Optional.empty();
        Schema input = inputOpt.get();

        // No-key whole-relation ∀ (∀ : P (R)) collapses to a nullary truth
        // relation: empty heading, one tuple (true) or none (false) — DEE/DUM.
        if (node.groupingAttributes().isEmpty()) {
            return annotate(node, Schema.empty());
        }

        List<ColumnDefinition> cols = new ArrayList<>(node.groupingAttributes().size());
        for (String key : node.groupingAttributes()) {
            Type type = input.column(key)
                    .map(ColumnDefinition::type)
                    .orElse(ScalarType.ANY);
            cols.add(new ColumnDefinition(key, type));
        }
        return annotate(node, new Schema(cols));
    }

    private static Set<String> sharedColumnNames(Schema left, Schema right) {
        Set<String> rightNames = new LinkedHashSet<>();
        right.columns().forEach(c -> rightNames.add(c.name().toLowerCase(Locale.ROOT)));
        Set<String> shared = new LinkedHashSet<>();
        for (ColumnDefinition lc : left.columns()) {
            String n = lc.name().toLowerCase(Locale.ROOT);
            if (rightNames.contains(n)) shared.add(n);
        }
        return shared;
    }

    // =========================================================================
    // Aggregation
    // =========================================================================

    @Override
    public Optional<Schema> visit(AggregationNode node) {
        Optional<Schema> inputOpt = node.input().accept(this);
        if (inputOpt.isEmpty()) return Optional.empty();
        Schema input = inputOpt.get();

        List<ColumnDefinition> cols = new ArrayList<>();

        // Group-by columns — a bare column preserves its input name and type; a
        // derived key takes its output name (alias, else expression-derived) and
        // the inferred type of its expression.
        for (GroupingKey key : node.groupingKeys()) {
            Type type = inferOperandType(key.expression(), input);
            cols.add(new ColumnDefinition(key.outputName(), type));
        }

        // Aggregate result columns.
        for (AggregateFunction agg : node.aggregates()) {
            String name = agg.outputName();
            Type type = aggregateResultType(agg, input);
            cols.add(new ColumnDefinition(name, type));
        }

        if (cols.isEmpty()) {
            errors.add(SemanticError.error(
                    node.location().filePath(),
                    node.location().line(),
                    node.location().column(),
                    "Aggregation node has no grouping attributes and no aggregate functions"));
            return Optional.empty();
        }

        // Deduplicate in case an alias clashes with a group-by column name.
        cols = deduplicateColumns(cols);
        return annotate(node, new Schema(cols));
    }

    // =========================================================================
    // Helpers — traversal
    // =========================================================================

    /** Returns the schema of {@code input} and annotates {@code node} with it. */
    private Optional<Schema> passthrough(RelNode node, RelNode input) {
        return input.accept(this).map(schema -> {
            annotations.put(node, schema);
            return schema;
        });
    }

    /** Concatenates left + right schemas (right-side duplicates are renamed). */
    private Optional<Schema> binaryConcat(RelNode node, RelNode left, RelNode right) {
        Optional<Schema> leftOpt  = left.accept(this);
        Optional<Schema> rightOpt = right.accept(this);
        if (leftOpt.isEmpty() || rightOpt.isEmpty()) return Optional.empty();

        Schema schema = leftOpt.get().concat(rightOpt.get());
        return annotate(node, schema);
    }

    /** Returns the left schema; visits both inputs for completeness. */
    private Optional<Schema> leftSchema(RelNode node, RelNode left, RelNode right) {
        Optional<Schema> leftOpt = left.accept(this);
        right.accept(this); // visit right to surface errors
        return leftOpt.map(s -> {
            annotations.put(node, s);
            return s;
        });
    }

    private Optional<Schema> annotate(RelNode node, Schema schema) {
        annotations.put(node, schema);
        return Optional.of(schema);
    }

    // =========================================================================
    // Helpers — schema construction
    // =========================================================================

    /**
     * Builds a natural-join schema: left columns, then right columns that do
     * not share a name with any left column.
     */
    static Schema naturalJoinSchema(Schema left, Schema right) {
        Set<String> leftNames = new LinkedHashSet<>();
        left.columns().forEach(c -> leftNames.add(c.name().toLowerCase(Locale.ROOT)));

        List<ColumnDefinition> cols = new ArrayList<>(left.columns());
        for (ColumnDefinition rc : right.columns()) {
            if (!leftNames.contains(rc.name().toLowerCase(Locale.ROOT))) {
                cols.add(rc);
            }
        }
        return new Schema(cols);
    }

    /**
     * Builds an outer-union schema: every left column, then every right column
     * whose name is absent from the left (matched case-insensitively).  A column
     * present in both inputs with differing types is widened to {@code ANY} (the
     * top type); rows from the side missing a given column are NULL-padded for it
     * at execution time.
     */
    static Schema mergeSchemas(Schema left, Schema right) {
        Map<String, Type> rightByName = new LinkedHashMap<>();
        right.columns().forEach(c -> rightByName.put(c.name().toLowerCase(Locale.ROOT), c.type()));

        List<ColumnDefinition> cols = new ArrayList<>(left.columns().size() + right.columns().size());
        Set<String> leftNames = new LinkedHashSet<>();
        for (ColumnDefinition lc : left.columns()) {
            String key = lc.name().toLowerCase(Locale.ROOT);
            leftNames.add(key);
            Type rightType = rightByName.get(key);
            Type type = (rightType != null && !rightType.equals(lc.type())) ? ScalarType.ANY : lc.type();
            cols.add(new ColumnDefinition(lc.name(), type, lc.provenance()));
        }
        for (ColumnDefinition rc : right.columns()) {
            if (!leftNames.contains(rc.name().toLowerCase(Locale.ROOT))) {
                cols.add(rc);
            }
        }
        return new Schema(cols);
    }

    /**
     * Renames duplicate column names in the list by appending {@code _1},
     * {@code _2}, … to the later occurrence.  The first occurrence keeps its
     * original name.
     */
    private static List<ColumnDefinition> deduplicateColumns(List<ColumnDefinition> cols) {
        Set<String> seen  = new LinkedHashSet<>();
        List<ColumnDefinition> result = new ArrayList<>(cols.size());
        for (ColumnDefinition col : cols) {
            String name = col.name();
            if (seen.contains(name.toLowerCase(Locale.ROOT))) {
                int n = 1;
                String candidate;
                do {
                    candidate = name + "_" + n++;
                } while (seen.contains(candidate.toLowerCase(Locale.ROOT)));
                name = candidate;
            }
            seen.add(name.toLowerCase(Locale.ROOT));
            result.add(col.withName(name));   // preserve type + provenance
        }
        return result;
    }

    // =========================================================================
    // Helpers — column provenance
    // =========================================================================

    /**
     * Returns a copy of {@code schema} with every column's source-relation origin
     * (re-)anchored to {@code relation}, keyed by the column's current (physical)
     * name.  Used at a base {@link RelationNode} and by a whole-relation rename.
     * Open and empty schemas are returned unchanged (they carry no columns).
     */
    private static Schema stampProvenance(Schema schema, String relation) {
        if (schema.columns().isEmpty()) {
            return schema;
        }
        List<ColumnDefinition> cols = new ArrayList<>(schema.columns().size());
        for (ColumnDefinition c : schema.columns()) {
            cols.add(c.withProvenance(new ColumnProvenance(relation, c.name())));
        }
        // An open heading with known columns — a join with a schema-on-read side —
        // is re-anchored too, and stays open. Returning it untouched left the known
        // columns answering to the relations *inside* the rename, so a qualifier the
        // rename had hidden still validated (#971).
        return schema.withColumns(cols);
    }

    /**
     * The provenance a projected column inherits: for a bare pass-through
     * attribute, the origin of the input column it names (so a qualified reference
     * survives the projection); otherwise {@code null} (a computed expression has
     * no single source relation).
     */
    private static ColumnProvenance passThroughProvenance(Operand expr, Schema input) {
        if (expr instanceof AttributeOperand attr) {
            int idx = resolveInputIndex(input, attr);
            if (idx >= 0) {
                return input.columns().get(idx).provenance();
            }
        }
        return null;
    }

    /**
     * Resolves an attribute operand against an input schema to a column index:
     * a qualified reference resolves by provenance, a bare reference
     * by name.  Returns {@code -1} if it does not resolve to exactly one column.
     */
    private static int resolveInputIndex(Schema input, AttributeOperand attr) {
        String qualifier = AttributeNames.qualifierOf(attr.name());
        if (qualifier != null) {
            List<Integer> matches = input.qualifiedIndices(qualifier, attr.unqualifiedName());
            if (matches.size() == 1) {
                return matches.get(0);
            }
        }
        return input.indexOf(attr.unqualifiedName());
    }

    // =========================================================================
    // Helpers — operand type and name inference
    // =========================================================================

    /**
     * Infers the output column name for a projected attribute expression when no
     * alias is present.  For a plain {@link AttributeOperand} the attribute name
     * is used (qualifier stripped); for a {@link FunctionCall} the function name
     * is used; for all other expressions a positional synthetic name
     * ({@code _col0}, {@code _col1}, …) is generated.
     */
    private static String inferColumnName(Operand expr, int position) {
        return switch (expr) {
            case AttributeOperand attr -> attr.unqualifiedName();
            case FunctionCall fn -> fn.functionName();
            default              -> "_col" + position;
        };
    }

    /**
     * Infers the scalar type of an operand expression given the schema of the
     * input relation.
     *
     * <ul>
     *   <li>Attribute reference → type from the input schema (or {@code ANY})
     *   <li>Numeric literal → {@code NUMBER}
     *   <li>String literal → {@code STRING}
     *   <li>Boolean literal → {@code BOOLEAN}
     *   <li>Arithmetic expression or unary negation → {@code NUMBER}
     *   <li>Function call → return type of the first matching function in the
     *       symbol table (or {@code ANY} if not found)
     *   <li>Set literal → {@code ANY}
     * </ul>
     */
    private Type inferOperandType(Operand expr, Schema inputSchema) {
        return typeInferrer.infer(expr, inputSchema);
    }

    /**
     * Returns the result type for an aggregate, by asking the aggregate.
     *
     * <p>The rule is the function's own: a counting or summing reduction has a fixed
     * type, {@code MIN}/{@code MAX} take their argument's, a gathering one produces an
     * array of what it gathered, and a "row with the maximum" takes the type of what it
     * yields. Inference states none of that — it types the argument expressions and
     * hands them over.
     *
     * <p>An aggregate no installed library offers types as {@code ANY}, which is the
     * same answer inference gives an unresolvable expression anywhere else; the
     * validator is what reports the name.
     */
    private Type aggregateResultType(AggregateFunction agg, Schema input) {
        List<Type> argumentTypes = new ArrayList<>(2);
        argumentTypes.add(inferOperandType(agg.argument(), input));
        agg.yieldExpr().ifPresent(yield -> argumentTypes.add(inferOperandType(yield, input)));
        return functions.aggregate(agg.operator().name())
                .map(reduction -> reduction.returnTypeFor(argumentTypes))
                .orElse(ScalarType.ANY);
    }

    // =========================================================================
    // Helpers — symbol lookup
    // =========================================================================

    /**
     * Looks up a relation by name.
     *
     * <p>A dotted name like {@code "ns.Relation"} may denote either a
     * namespace-qualified symbol (relation {@code Relation} declared in namespace
     * {@code ns}) or a flat connection-table reference (table {@code Relation} on
     * connection {@code ns}, registered under the flat name {@code "ns.Relation"}).
     * The namespace-qualified interpretation is tried first; if it misses, the
     * flat full-name lookup is used as a fallback.
     */
    private Optional<RelationSymbol> lookupRelation(String name) {
        return symbolTable.resolveRelation(name);
    }

    /** Returns the first table-valued function overload registered under {@code name}. */
    private Optional<RelationFunctionSymbol> lookupRelationFunction(String name) {
        return symbolTable.resolveFunction(name).stream()
                .filter(RelationFunctionSymbol.class::isInstance)
                .map(RelationFunctionSymbol.class::cast)
                .findFirst();
    }
}

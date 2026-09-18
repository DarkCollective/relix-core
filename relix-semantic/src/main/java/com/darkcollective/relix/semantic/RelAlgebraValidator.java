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
import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.AntiJoinNode;
import com.darkcollective.relix.ast.ArrayConstruction;
import com.darkcollective.relix.ast.AsOfJoinNode;
import com.darkcollective.relix.ast.AttributeNames;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.ClosureNode;
import com.darkcollective.relix.ast.ClusterNode;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.CompositionNode;
import com.darkcollective.relix.ast.ConditionOperand;
import com.darkcollective.relix.ast.ConsolidationFunction;
import com.darkcollective.relix.ast.CoverNode;
import com.darkcollective.relix.ast.DifferenceNode;
import com.darkcollective.relix.ast.DistinctNode;
import com.darkcollective.relix.ast.DivisionNode;
import com.darkcollective.relix.ast.DownsampleNode;
import com.darkcollective.relix.ast.DurationOperand;
import com.darkcollective.relix.ast.ElementOfPredicate;
import com.darkcollective.relix.ast.EmptyRelationNode;
import com.darkcollective.relix.ast.FixpointNode;
import com.darkcollective.relix.ast.FullOuterJoinNode;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.IntersectionNode;
import com.darkcollective.relix.ast.IntervalJoinNode;
import com.darkcollective.relix.ast.LateralJoinNode;
import com.darkcollective.relix.ast.LeftOuterJoinNode;
import com.darkcollective.relix.ast.LimitNode;
import com.darkcollective.relix.ast.NaturalJoinNode;
import com.darkcollective.relix.ast.NotPredicate;
import com.darkcollective.relix.ast.NullPredicate;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.OperandWalker;
import com.darkcollective.relix.ast.OptimizeConstraint;
import com.darkcollective.relix.ast.OptimizeNode;
import com.darkcollective.relix.ast.OrPredicate;
import com.darkcollective.relix.ast.OuterUnionNode;
import com.darkcollective.relix.ast.PairwiseUniversalNode;
import com.darkcollective.relix.ast.PathNode;
import com.darkcollective.relix.ast.PatternPredicate;
import com.darkcollective.relix.ast.PivotNode;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProductNode;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RankingFunction;
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
import com.darkcollective.relix.ast.SetLiteralOperand;
import com.darkcollective.relix.ast.SolveNode;
import com.darkcollective.relix.ast.SortNode;
import com.darkcollective.relix.ast.SortSpecification;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.StructConstruction;
import com.darkcollective.relix.ast.SymmetricDifferenceNode;
import com.darkcollective.relix.ast.ThetaJoinNode;
import com.darkcollective.relix.ast.TopKNode;
import com.darkcollective.relix.ast.TraceNode;
import com.darkcollective.relix.ast.TreeNode;
import com.darkcollective.relix.ast.TruthRelationNode;
import com.darkcollective.relix.ast.UnaryOperand;
import com.darkcollective.relix.ast.UnionAllNode;
import com.darkcollective.relix.ast.UnionNode;
import com.darkcollective.relix.ast.UniversalNode;
import com.darkcollective.relix.ast.UnnestNode;
import com.darkcollective.relix.ast.UnpivotNode;
import com.darkcollective.relix.ast.WhyNode;
import com.darkcollective.relix.ast.WindowFunction;
import com.darkcollective.relix.ast.WindowNode;
import com.darkcollective.relix.ast.visitor.RelNodeVisitor;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Type;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.function.FunctionSymbol;
import com.darkcollective.relix.symbol.function.RelationFunctionSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates structural and semantic constraints over a single relational
 * algebra expression tree.
 *
 * <p>This visitor runs <em>after</em> schema inference, consuming the
 * already-populated {@link SchemaAnnotations} map rather than re-deriving
 * schemas.  Nodes that were left unannotated (because inference failed) are
 * silently skipped — the upstream inference error is sufficient.
 *
 * <h2>Checks performed</h2>
 * <dl>
 *   <dt>Set operations (∪ ⊎ ∩ −)</dt>
 *   <dd>Both operands must have the same column count; columns at matching
 *       positions must have compatible types ({@link ScalarType#ANY} is
 *       compatible with any type).</dd>
 *   <dt>Division (÷)</dt>
 *   <dd>Every column in the right (divisor) schema must appear, by name, in
 *       the left (dividend) schema.</dd>
 *   <dt>Projection (π)</dt>
 *   <dd>{@link AttributeOperand} references in projected expressions must
 *       resolve to a column in the input schema (after stripping any qualifier).
 *       {@link FunctionCall} references are validated for existence and arity
 *       against the symbol table.</dd>
 *   <dt>Aggregation (γ)</dt>
 *   <dd>Group-by attribute names and aggregate function attribute names must
 *       all resolve in the input schema.</dd>
 *   <dt>Sort (τ)</dt>
 *   <dd>Sort specification attribute names must resolve in the input schema.</dd>
 * </dl>
 *
 * <p>All errors are appended to the mutable {@code errors} list supplied at
 * construction time; the visitor never throws.
 */
final class RelAlgebraValidator implements RelNodeVisitor<Void> {

    private final SymbolTable             symbolTable;
    private final SchemaAnnotations       annotations;
    private final FunctionCatalog         functions;
    private final List<SemanticError>     errors;
    private final String                  filePath;
    /**
     * Lower-cased names of in-scope function parameters — non-empty only when
     * validating a table-valued function body, so a body's reference to a parameter
     * (e.g. {@code cid} in {@code σ customer_id = cid (Orders)}) is not mistaken for
     * a missing relation column.
     */
    private final Set<String>             parameters;

    RelAlgebraValidator(SymbolTable symbolTable,
                        SchemaAnnotations annotations,
                        FunctionCatalog functions,
                        List<SemanticError> errors,
                        String filePath) {
        this(symbolTable, annotations, functions, errors, filePath, Set.of());
    }

    RelAlgebraValidator(SymbolTable symbolTable,
                        SchemaAnnotations annotations,
                        FunctionCatalog functions,
                        List<SemanticError> errors,
                        String filePath,
                        Set<String> parameters) {
        this.symbolTable = Objects.requireNonNull(symbolTable,  "symbolTable");
        this.annotations = Objects.requireNonNull(annotations,  "annotations");
        this.functions   = Objects.requireNonNull(functions,    "functions");
        this.errors      = Objects.requireNonNull(errors,        "errors");
        this.filePath    = Objects.requireNonNull(filePath,      "filePath");
        this.parameters  = Objects.requireNonNull(parameters,    "parameters");
    }

    // =========================================================================
    // Leaf — nothing to validate beyond what inference already checked
    // =========================================================================

    @Override
    public Void visit(RelationNode node) {
        return null; // existence already validated during inference
    }

    @Override
    public Void visit(TruthRelationNode node) {
        return null; // a literal with a fixed heading — nothing to resolve or check
    }

    @Override
    public Void visit(EmptyRelationNode node) {
        // Optimizer-introduced, and only ever from a sub-tree that already validated;
        // its carried heading is inert, so re-walking it could only re-report an error
        // the original walk already raised.
        return null;
    }

    // =========================================================================
    // Table-valued function call — function exists, arity matches, arguments
    // are constant (a correlated argument needs the LATERAL form instead)
    // =========================================================================

    @Override
    public Void visit(RelationFunctionCall node) {
        List<RelationFunctionSymbol> overloads = symbolTable.resolveFunction(node.functionName()).stream()
                .filter(RelationFunctionSymbol.class::isInstance)
                .map(RelationFunctionSymbol.class::cast)
                .toList();
        if (overloads.isEmpty()) {
            error(node.location(), "Unknown table-valued function: '" + node.functionName() + "'"
                    + Suggestions.didYouMean(node.functionName(), functionCandidates()));
            return null;
        }
        int argCount = node.arguments().size();
        boolean arityMatch = overloads.stream().anyMatch(f -> f.parameters().size() == argCount);
        if (!arityMatch) {
            int expected = overloads.get(0).parameters().size();
            error(node.location(), "Table-valued function '" + node.functionName()
                    + "' called with " + argCount + " argument(s) but expects " + expected);
        }
        // Arguments are bound by substitution into the body, so they must be constant
        // (ground) expressions: a column reference needs correlated evaluation, which
        // is what the LATERAL join provides.
        for (Operand arg : node.arguments()) {
            validateConstantArgument(arg, node.functionName(), node.location());
        }
        return null;
    }

    /**
     * Validates a table-valued-function argument: it must contain no column
     * references (it is bound by substitution, not evaluated against an input row),
     * and any function call within it must exist with a matching arity.
     */
    private void validateConstantArgument(Operand arg, String fnName, SourceLocation loc) {
        OperandWalker.walk(arg,
                a -> {
                    // A reference to an in-scope parameter (when this body is itself a
                    // table-valued function) is allowed: it is bound to the caller's
                    // constant argument by substitution.  Any other column reference needs
                    // correlated evaluation — the LATERAL join, not a bare call.
                    if (!parameters.contains(a.unqualifiedName().toLowerCase(Locale.ROOT))) {
                        error(loc, "Table-valued function '" + fnName + "' argument must be a constant "
                                + "expression; column reference '" + a.name() + "' is not supported "
                                + "(use a LATERAL join for a correlated argument)");
                    }
                },
                f -> {
                    if (ResolvedFunction.of(functions, f.functionName(), symbolTable)
                            .isUnknown()) {
                        unknownFunction(loc, f.functionName());
                    }
                });
    }

    // =========================================================================
    // Lateral (correlated) TVF join — function exists, arity matches, column
    // references in arguments are validated against the left (outer) schema
    // =========================================================================

    @Override
    public Void visit(LateralJoinNode node) {
        // Validate the outer/left relation first.
        node.left().accept(this);

        List<RelationFunctionSymbol> overloads = symbolTable.resolveFunction(node.functionName()).stream()
                .filter(RelationFunctionSymbol.class::isInstance)
                .map(RelationFunctionSymbol.class::cast)
                .toList();
        if (overloads.isEmpty()) {
            error(node.location(), "Unknown table-valued function: '" + node.functionName() + "'"
                    + Suggestions.didYouMean(node.functionName(), functionCandidates()));
            return null;
        }
        int argCount = node.arguments().size();
        boolean arityMatch = overloads.stream().anyMatch(f -> f.parameters().size() == argCount);
        if (!arityMatch) {
            int expected = overloads.get(0).parameters().size();
            error(node.location(), "Table-valued function '" + node.functionName()
                    + "' called with " + argCount + " argument(s) but expects " + expected);
        }

        // Build the set of valid column names from the left schema — these are
        // allowed as column references in LATERAL arguments.
        Optional<Schema> leftSchema = annotations.get(node.left());
        Set<String> leftColumns = leftSchema
                .map(s -> s.columns().stream()
                        .map(c -> c.name().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toSet()))
                .orElse(Set.of());

        // Validate each argument: column refs from the left schema are allowed;
        // other column refs and function calls are validated as usual.
        for (Operand arg : node.arguments()) {
            validateLateralArgument(arg, node.functionName(), node.location(), leftColumns);
        }
        return null;
    }

    /**
     * Validates a lateral TVF argument: column references that resolve against
     * the left (outer) schema or an in-scope parameter are allowed; any other
     * column reference is an error. Function calls are validated for existence.
     */
    private void validateLateralArgument(Operand arg, String fnName, SourceLocation loc,
                                          Set<String> leftColumns) {
        OperandWalker.walk(arg,
                a -> {
                    String unqualified = a.unqualifiedName().toLowerCase(Locale.ROOT);
                    // Column refs from the outer (left) schema or an in-scope parameter are fine.
                    if (!leftColumns.contains(unqualified)
                            && !parameters.contains(unqualified)) {
                        error(loc, "Lateral table-valued function '" + fnName
                                + "' argument references column '" + a.name()
                                + "' which is not in the outer (left) relation");
                    }
                },
                f -> {
                    if (ResolvedFunction.of(functions, f.functionName(), symbolTable)
                            .isUnknown()) {
                        unknownFunction(loc, f.functionName());
                    }
                });
    }

    // =========================================================================
    // Passthrough — recurse, no additional checks
    // =========================================================================

    @Override
    public Void visit(SelectionNode node) {
        node.input().accept(this);
        Optional<Schema> inputOpt = annotations.get(node.input());
        if (inputOpt.isPresent()) {
            validatePredicate(node.predicate(), inputOpt.get(), "Selection σ");
        }
        return null;
    }

    @Override
    public Void visit(DistinctNode node) {
        return node.input().accept(this);
    }

    @Override
    public Void visit(SampleNode node) {
        node.input().accept(this);
        if (node.probability() < 0.0 || node.probability() > 1.0) {
            error(node.location(), "Sample: probability must be between 0 and 1, got "
                    + node.probability());
        }
        return null;
    }

    @Override
    public Void visit(ReservoirSampleNode node) {
        // The row count is a non-negative integer (guaranteed by the parser and the
        // node's own invariant); the output schema is the input schema unchanged.
        node.input().accept(this);
        return null;
    }

    // =========================================================================
    // Downsampling — timestamp column exists and is TIMESTAMP, grouping keys
    // exist, interval is parseable, maxRows ≥ 1
    // =========================================================================

    @Override
    public Void visit(DownsampleNode node) {
        node.input().accept(this);

        Optional<Schema> inputOpt = annotations.get(node.input());
        if (inputOpt.isEmpty()) return null;
        Schema input = inputOpt.get();

        // Timestamp column must exist and be of type TIMESTAMP (or ANY)
        Optional<ColumnDefinition> tsColDef = input.column(node.timestampColumn());
        if (tsColDef.isEmpty()) {
            columnNotFound(node.location(), "Downsample DOWNSAMPLE: timestamp column",
                    node.timestampColumn(), input);
        } else if (tsColDef.get().type() != ScalarType.TIMESTAMP
                && tsColDef.get().type() != ScalarType.ANY) {
            error(node.location(), "Downsample DOWNSAMPLE: column '"
                    + node.timestampColumn() + "' must be of type TIMESTAMP, got "
                    + tsColDef.get().type().display());
        }

        // Grouping keys must exist
        for (String key : node.groupingKeys()) {
            if (input.column(key).isEmpty()) {
                columnNotFound(node.location(), "Downsample DOWNSAMPLE: grouping key", key, input);
            }
        }

        // Interval must parse
        try {
            DownsampleNode.parseIntervalSeconds(node.interval());
        } catch (IllegalArgumentException e) {
            error(node.location(), "Downsample DOWNSAMPLE: " + e.getMessage());
        }

        return null;
    }

    @Override
    public Void visit(TopKNode node) {
        node.input().accept(this);

        Optional<Schema> inputOpt = annotations.get(node.input());
        if (inputOpt.isEmpty()) return null;
        Schema input = inputOpt.get();

        for (String key : node.groupingAttributes()) {
            if (input.column(key).isEmpty()) {
                columnNotFound(node.location(), "Top-k TOP: grouping key", key, input);
            }
        }
        for (SortSpecification spec : node.sortSpecs()) {
            validateSortKey(node.location(), "Top-k TOP: sort attribute", spec, input);
        }
        return null;
    }

    // =========================================================================
    // Declarative optimisation — grouping keys + objective/constraint columns
    // exist; at least one well-formed constraint
    // =========================================================================

    @Override
    public Void visit(OptimizeNode node) {
        node.input().accept(this);

        Optional<Schema> inputOpt = annotations.get(node.input());
        if (inputOpt.isEmpty()) return null;
        Schema input = inputOpt.get();

        for (String key : node.groupingKeys()) {
            if (input.column(key).isEmpty()) {
                columnNotFound(node.location(), "Optimize OPTIMIZE: grouping key", key, input);
            }
        }

        checkColumnsExist(node.objective(), input, node.location());

        if (node.constraints().isEmpty()) {
            error(node.location(),
                    "Optimize OPTIMIZE: at least one constraint (SUBJECT TO …) is required");
        }
        for (OptimizeConstraint c : node.constraints()) {
            checkColumnsExist(c.expr(), input, node.location());
            if (c.op() != ComparisonOperator.LESS_EQUAL
                    && c.op() != ComparisonOperator.GREATER_EQUAL
                    && c.op() != ComparisonOperator.EQUAL) {
                error(node.location(),
                        "Optimize OPTIMIZE: constraint operator must be <=, >=, or =");
            }
        }

        node.allocation().ifPresent(spec -> {
            if (input.declares(spec.columnName())) {
                error(node.location(), "Optimize OPTIMIZE ALLOCATE: allocation column '"
                        + spec.columnName() + "' already exists in the input schema");
            }
        });

        return null;
    }

    /**
     * Verifies that every {@link AttributeOperand} reachable in {@code expr} names
     * a column of {@code input}.  Functions, arithmetic, and literals are walked
     * (their column references checked); other constructs are ignored.
     */
    private void checkColumnsExist(Operand expr, Schema input, SourceLocation loc) {
        OperandWalker.walk(expr,
                a -> {
                    if (input.column(a.unqualifiedName()).isEmpty()) {
                        columnNotFound(loc, "Optimize OPTIMIZE: column", a.name(), input);
                    }
                },
                f -> { /* function existence/arity is not checked for OPTIMIZE expressions */ });
    }

    // =========================================================================
    // Covering reduction — 1 ≤ strength ≤ width; open (schema-on-read) input
    // is rejected because the coverage universe requires a known column set
    // =========================================================================

    @Override
    public Void visit(CoverNode node) {
        node.input().accept(this);

        Optional<Schema> inputOpt = annotations.get(node.input());
        if (inputOpt.isEmpty()) return null;
        Schema input = inputOpt.get();

        if (input.isOpen()) {
            error(node.location(),
                    "Cover COVER: open (schema-on-read) input is not allowed"
                            + " — the column set must be known for the coverage universe"
                            + " to be well-defined");
            return null;
        }

        int width = input.columns().size();
        if (node.strength() > width) {
            error(node.location(),
                    "Cover COVER: coverage strength " + node.strength()
                            + " exceeds column count " + width);
        }
        // strength < 1 is enforced by the parser and CoverNode's own invariant
        return null;
    }

    // =========================================================================
    // Goal-seek — equation references existing numeric columns, each at most
    // once, using only invertible arithmetic
    // =========================================================================

    @Override
    public Void visit(SolveNode node) {
        node.input().accept(this);

        Optional<Schema> inputOpt = annotations.get(node.input());
        if (inputOpt.isEmpty()) return null;
        Schema input = inputOpt.get();

        List<String> columns = new ArrayList<>();
        boolean invertible = collectSolveColumns(node.left(), columns, node.location())
                & collectSolveColumns(node.right(), columns, node.location());
        if (!invertible) return null; // non-invertible construct already reported

        if (columns.isEmpty()) {
            error(node.location(),
                    "Solve SOLVE: the equation must reference at least one column");
            return null;
        }

        Set<String> seen = new HashSet<>();
        for (String col : columns) {
            String colName = AttributeNames.stripQualifier(col);
            String key = colName.toLowerCase(Locale.ROOT);

            Optional<ColumnDefinition> def = input.column(colName);
            if (def.isEmpty()) {
                columnNotFound(node.location(), "Solve SOLVE: column", col, input);
            } else {
                Type t = def.get().type();
                if (t != ScalarType.NUMBER && t != ScalarType.ANY) {
                    error(node.location(), "Solve SOLVE: column '" + col
                            + "' must be NUMBER (or ANY) to be solved, got " + t);
                }
            }
            if (!seen.add(key)) {
                error(node.location(), "Solve SOLVE: column '" + colName
                        + "' appears more than once in the equation; each column may "
                        + "appear at most once so the inversion is deterministic");
            }
        }
        return null;
    }

    /**
     * Walks one side of a SOLVE equation, appending each referenced attribute name
     * (with multiplicity) to {@code out}.  Returns {@code false} — after reporting
     * an error — when the side contains a construct that cannot be inverted; only
     * column references, numeric literals, {@code + − × ÷}, and unary minus are
     * invertible.
     */
    private boolean collectSolveColumns(Operand expr, List<String> out, SourceLocation loc) {
        return switch (expr) {
            case AttributeOperand attr -> {
                out.add(attr.name());
                yield true;
            }
            case NumberOperand ignored -> true;
            case BinaryArithmeticExpression arith ->
                    collectSolveColumns(arith.left(), out, loc)
                            & collectSolveColumns(arith.right(), out, loc);
            case UnaryOperand unary -> collectSolveColumns(unary.operand(), out, loc);
            default -> {
                error(loc, "Solve SOLVE: the equation may contain only column references, "
                        + "numeric literals, and the arithmetic operators + − × ÷; "
                        + "functions, strings, and other constructs cannot be inverted");
                yield false;
            }
        };
    }

    @Override
    public Void visit(UnnestNode node) {
        node.input().accept(this);

        Optional<Schema> inputOpt = annotations.get(node.input());
        if (inputOpt.isEmpty()) return null;
        Schema input = inputOpt.get();
        if (input.column(node.column()).isEmpty()) {
            columnNotFound(node.location(), "Unnest μ: column", node.column(), input);
        }
        // WITH ORDINALITY: the appended index column must not clash with an existing one.
        node.ordinalityColumn().ifPresent(name -> {
            if (input.declares(name)) {
                error(node.location(), "Unnest μ WITH ORDINALITY: column '" + name
                        + "' already exists in the input schema");
            }
        });
        return null;
    }

    @Override
    public Void visit(ClosureNode node) {
        node.input().accept(this);

        Optional<Schema> inputOpt = annotations.get(node.input());
        if (inputOpt.isEmpty()) return null;
        Schema input = inputOpt.get();

        Optional<ColumnDefinition> from = input.column(node.fromColumn());
        Optional<ColumnDefinition> to   = input.column(node.toColumn());
        if (from.isEmpty()) {
            columnNotFound(node.location(), "Closure: 'from' column", node.fromColumn(), input);
        }
        if (to.isEmpty()) {
            columnNotFound(node.location(), "Closure: 'to' column", node.toColumn(), input);
        }
        if (node.fromColumn().equalsIgnoreCase(node.toColumn())) {
            error(node.location(), "Closure: 'from' and 'to' columns must differ");
        } else if (from.isPresent() && to.isPresent()
                && from.get().type() != to.get().type()
                && from.get().type() != ScalarType.ANY
                && to.get().type() != ScalarType.ANY) {
            error(node.location(), "Closure: 'from' (" + from.get().type()
                    + ") and 'to' (" + to.get().type()
                    + ") columns must have the same type");
        }
        return null;
    }

    @Override
    public Void visit(ClusterNode node) {
        node.input().accept(this);

        Optional<Schema> inputOpt = annotations.get(node.input());
        if (inputOpt.isEmpty()) return null;
        Schema input = inputOpt.get();

        Optional<ColumnDefinition> from = input.column(node.fromColumn());
        Optional<ColumnDefinition> to   = input.column(node.toColumn());
        if (from.isEmpty()) {
            columnNotFound(node.location(), "Cluster CLUSTER: 'from' column", node.fromColumn(), input);
        }
        if (to.isEmpty()) {
            columnNotFound(node.location(), "Cluster CLUSTER: 'to' column", node.toColumn(), input);
        }
        if (node.fromColumn().equalsIgnoreCase(node.toColumn())) {
            error(node.location(), "Cluster CLUSTER: 'from' and 'to' columns must differ");
        } else if (from.isPresent() && to.isPresent()
                && from.get().type() != to.get().type()
                && from.get().type() != ScalarType.ANY
                && to.get().type() != ScalarType.ANY) {
            error(node.location(), "Cluster CLUSTER: 'from' (" + from.get().type()
                    + ") and 'to' (" + to.get().type()
                    + ") columns must have the same type");
        }
        if (node.fromColumn().equalsIgnoreCase(node.labelColumn())) {
            error(node.location(), "Cluster CLUSTER: the label column '" + node.labelColumn()
                    + "' must differ from the node column '" + node.fromColumn() + "'");
        }
        return null;
    }

    @Override
    public Void visit(PathNode node) {
        node.input().accept(this);

        Optional<Schema> inputOpt = annotations.get(node.input());
        if (inputOpt.isEmpty()) return null;
        Schema input = inputOpt.get();

        Optional<ColumnDefinition> from = input.column(node.fromColumn());
        Optional<ColumnDefinition> to   = input.column(node.toColumn());
        if (from.isEmpty()) {
            columnNotFound(node.location(), "Path PATH: 'from' column", node.fromColumn(), input);
        }
        if (to.isEmpty()) {
            columnNotFound(node.location(), "Path PATH: 'to' column", node.toColumn(), input);
        }
        if (node.fromColumn().equalsIgnoreCase(node.toColumn())) {
            error(node.location(), "Path PATH: 'from' and 'to' columns must differ");
        } else if (from.isPresent() && to.isPresent()
                && from.get().type() != to.get().type()
                && from.get().type() != ScalarType.ANY
                && to.get().type() != ScalarType.ANY) {
            error(node.location(), "Path PATH: 'from' (" + from.get().type()
                    + ") and 'to' (" + to.get().type()
                    + ") columns must have the same type");
        }
        if (node.depthColumn().equalsIgnoreCase(node.fromColumn())
                || node.depthColumn().equalsIgnoreCase(node.toColumn())) {
            error(node.location(), "Path PATH: the depth column '" + node.depthColumn()
                    + "' must differ from the endpoint columns");
        }
        return null;
    }

    @Override
    public Void visit(WindowNode node) {
        node.input().accept(this);

        Optional<Schema> inputOpt = annotations.get(node.input());
        if (inputOpt.isEmpty()) return null;
        Schema input = inputOpt.get();

        for (String key : node.partitionKeys()) {
            if (input.column(key).isEmpty()) {
                columnNotFound(node.location(), "Window: partition key", key, input);
            }
        }
        for (SortSpecification spec : node.sortSpecs()) {
            validateSortKey(node.location(), "Window: sort attribute", spec, input);
        }
        // The added column must not clash with an existing input column.
        if (input.declares(node.outputColumn())) {
            error(node.location(), "Window: output column '" + node.outputColumn()
                    + "' already exists in the input schema");
        }
        // Validate the function's expression(s) and aggregate legality.
        switch (node.function()) {
            case WindowFunction.AggregateWindow a -> {
                if (a.operator() == AggregateOperator.COLLECT
                        || a.operator() == AggregateOperator.ARGMAX
                        || a.operator() == AggregateOperator.ARGMIN) {
                    error(node.location(), "Window: aggregate " + a.operator().name()
                            + " is not supported in a ROLLING window "
                            + "(use SUM, AVG, COUNT, MIN, or MAX)");
                }
                new PredicateValidator(input, symbolTable, functions, errors, filePath,
                        "Window " + a.operator().name(), parameters)
                        .validateExpression(a.argument());
            }
            case WindowFunction.RankingWindow r -> {
                r.ntileCount().ifPresent(c -> new PredicateValidator(
                        input, symbolTable, functions, errors, filePath,
                        "Window " + r.function().name(), parameters).validateExpression(c));
                if (r.function() == RankingFunction.NTILE) {
                    validateNtileArgument(node, r.ntileCount());
                }
            }
            case WindowFunction.OffsetWindow o -> {
                var v = new PredicateValidator(input, symbolTable, functions, errors, filePath,
                        "Window " + o.function().name(), parameters);
                v.validateExpression(o.expression());
                o.defaultValue().ifPresent(v::validateExpression);
                validateOffsetArgument(node, o.offset());
            }
        }
        return null;
    }

    @Override
    public Void visit(SessionizeNode node) {
        node.input().accept(this);

        Optional<Schema> inputOpt = annotations.get(node.input());
        if (inputOpt.isEmpty()) return null;
        Schema input = inputOpt.get();

        Optional<ColumnDefinition> order = input.column(node.orderColumn());
        if (order.isEmpty()) {
            columnNotFound(node.location(), "Sessionize SESSIONIZE: order column",
                    node.orderColumn(), input);
        }
        for (String key : node.partitionKeys()) {
            if (input.column(key).isEmpty()) {
                columnNotFound(node.location(), "Sessionize SESSIONIZE: partition key", key, input);
            }
        }
        if (input.declares(node.sessionColumn())) {
            error(node.location(), "Sessionize SESSIONIZE: session column '"
                    + node.sessionColumn() + "' already exists in the input schema");
        }
        validateSessionizeThreshold(node, order.map(ColumnDefinition::type).orElse(null));
        return null;
    }

    /**
     * Validates the SESSIONIZE gap threshold: it must be a constant literal — a
     * {@link NumberOperand} for a {@code NUMBER} order column or a
     * {@link DurationOperand} for a temporal ({@code TIMESTAMP}/{@code DATE}/
     * {@code TIME}) order column — so the per-pair gap comparison is well-typed and
     * deterministic.  An {@code ANY} (or unresolved) order column accepts either.
     */
    private void validateSessionizeThreshold(SessionizeNode node, Type orderType) {
        Operand threshold = node.threshold();
        boolean isNumber = threshold instanceof NumberOperand;
        boolean isDuration = threshold instanceof DurationOperand;
        if (!isNumber && !isDuration) {
            error(node.location(), "Sessionize SESSIONIZE: GAP threshold must be a "
                    + "NUMBER or DURATION literal");
            return;
        }
        if (orderType == null || orderType == ScalarType.ANY) {
            return;
        }
        boolean temporalOrder = orderType == ScalarType.TIMESTAMP
                || orderType == ScalarType.DATE || orderType == ScalarType.TIME;
        if (temporalOrder && !isDuration) {
            error(node.location(), "Sessionize SESSIONIZE: a " + orderType
                    + " order column requires a DURATION GAP threshold");
        } else if (orderType == ScalarType.NUMBER && !isNumber) {
            error(node.location(), "Sessionize SESSIONIZE: a NUMBER order column "
                    + "requires a NUMBER GAP threshold");
        }
    }

    @Override
    public Void visit(TreeNode node) {
        node.input().accept(this);

        Optional<Schema> inputOpt = annotations.get(node.input());
        if (inputOpt.isEmpty()) return null;
        Schema input = inputOpt.get();
        // An open (schema-on-read) input resolves every column dynamically to ANY,
        // so no static column check is meaningful — defer to runtime.
        if (input.isOpen()) return null;

        if (input.column(node.keyColumn()).isEmpty()) {
            columnNotFound(node.location(), "TREE: key column", node.keyColumn(), input);
        }
        if (input.column(node.parentColumn()).isEmpty()) {
            columnNotFound(node.location(), "TREE: parent-key column", node.parentColumn(), input);
        }
        for (SortSpecification spec : node.orderSpecs()) {
            validateSortKey(node.location(), "TREE: ORDER column", spec, input);
        }
        if (input.declares(node.childrenColumn())) {
            error(node.location(), "TREE: children column '" + node.childrenColumn()
                    + "' already exists in the input schema");
        }
        return null;
    }

    @Override
    public Void visit(WhyNode node) {
        node.input().accept(this);

        Optional<Schema> inputOpt = annotations.get(node.input());
        if (inputOpt.isEmpty()) return null;
        Schema input = inputOpt.get();
        // An open (schema-on-read) input resolves columns dynamically, so a static
        // reserved-name check is not meaningful — defer to runtime.
        if (input.isOpen()) return null;

        // WHY appends a reserved `provenance` column; a clash with an existing
        // input column is an error — there is no ρ-style rename escape hatch
        // (ADR-0018).
        if (input.declares(WhyNode.PROVENANCE_COLUMN)) {
            error(node.location(), "WHY: reserved column '" + WhyNode.PROVENANCE_COLUMN
                    + "' already exists in the input schema");
        }
        return null;
    }

    @Override
    public Void visit(UnpivotNode node) {
        node.input().accept(this);

        Optional<Schema> inputOpt = annotations.get(node.input());
        if (inputOpt.isEmpty()) return null;
        Schema input = inputOpt.get();
        if (input.isOpen()) return null;

        for (String col : node.columns()) {
            if (input.column(col).isEmpty()) {
                columnNotFound(node.location(), "UNPIVOT: column", col, input);
            }
        }
        // nameColumn and valueColumn must not clash with remaining (non-listed) columns
        List<String> remaining = input.columns().stream()
                .map(ColumnDefinition::name)
                .filter(n -> !node.columns().contains(n))
                .toList();
        if (remaining.contains(node.nameColumn())) {
            error(node.location(), "UNPIVOT: name column '" + node.nameColumn()
                    + "' already exists in the remaining input schema");
        }
        if (remaining.contains(node.valueColumn())) {
            error(node.location(), "UNPIVOT: value column '" + node.valueColumn()
                    + "' already exists in the remaining input schema");
        }
        if (node.nameColumn().equals(node.valueColumn())) {
            error(node.location(), "UNPIVOT: nameColumn and valueColumn must be distinct, got '"
                    + node.nameColumn() + "'");
        }
        return null;
    }

    @Override
    public Void visit(PivotNode node) {
        node.input().accept(this);

        Optional<Schema> inputOpt = annotations.get(node.input());
        if (inputOpt.isEmpty()) return null;
        Schema input = inputOpt.get();
        if (input.isOpen()) return null;

        if (input.column(node.valueColumn()).isEmpty()) {
            columnNotFound(node.location(), "PIVOT: value column", node.valueColumn(), input);
        }
        if (input.column(node.keyColumn()).isEmpty()) {
            columnNotFound(node.location(), "PIVOT: key column", node.keyColumn(), input);
        }
        if (node.keyColumn().equals(node.valueColumn())) {
            error(node.location(), "PIVOT: keyColumn and valueColumn must be distinct, got '"
                    + node.keyColumn() + "'");
        }
        for (String g : node.groupKeys()) {
            if (input.column(g).isEmpty()) {
                columnNotFound(node.location(), "PIVOT: group key", g, input);
            }
            if (g.equals(node.valueColumn())) {
                error(node.location(), "PIVOT: group key '" + g
                        + "' must not be the value column");
            }
            if (g.equals(node.keyColumn())) {
                error(node.location(), "PIVOT: group key '" + g
                        + "' must not be the key column");
            }
        }
        return null;
    }

    /**
     * Validates a {@code LAG}/{@code LEAD} row offset: when present it must be a
     * positive integer literal in v1 (no column references, no fractions). The
     * per-row execution requires a constant, deterministic offset.
     */
    private void validateOffsetArgument(WindowNode node, Optional<Operand> offset) {
        if (offset.isEmpty()) {
            return;
        }
        if (!(offset.get() instanceof NumberOperand num)) {
            error(node.location(), "Window: offset must be a positive integer literal");
            return;
        }
        long n;
        try {
            n = Long.parseLong(num.value());
        } catch (NumberFormatException e) {
            error(node.location(), "Window: offset must be a positive integer literal, got '"
                    + num.value() + "'");
            return;
        }
        if (n < 1) {
            error(node.location(), "Window: offset must be at least 1, got " + n);
        }
    }

    /**
     * Validates an {@code NTILE(n)} bucket-count argument: it must be present and a
     * positive integer literal in v1 (no column references, no fractions). The
     * per-row execution requires a constant, deterministic bucket count.
     */
    private void validateNtileArgument(WindowNode node, Optional<Operand> ntileCount) {
        if (ntileCount.isEmpty()) {
            error(node.location(), "Window: NTILE requires a bucket-count argument (e.g. NTILE(4))");
            return;
        }
        if (!(ntileCount.get() instanceof NumberOperand num)) {
            error(node.location(), "Window: NTILE bucket count must be a positive integer literal");
            return;
        }
        long buckets;
        try {
            buckets = Long.parseLong(num.value());
        } catch (NumberFormatException e) {
            error(node.location(), "Window: NTILE bucket count must be a positive integer literal, got '"
                    + num.value() + "'");
            return;
        }
        if (buckets < 1) {
            error(node.location(), "Window: NTILE bucket count must be at least 1, got " + buckets);
        }
    }

    @Override
    public Void visit(TraceNode node) {
        node.input().accept(this);

        Optional<Schema> inputOpt = annotations.get(node.input());
        if (inputOpt.isEmpty()) return null;
        Schema input = inputOpt.get();

        Optional<ColumnDefinition> from   = input.column(node.fromColumn());
        Optional<ColumnDefinition> to     = input.column(node.toColumn());
        Optional<ColumnDefinition> weight = input.column(node.weightColumn());
        if (from.isEmpty()) {
            columnNotFound(node.location(), "TRACE: 'from' column", node.fromColumn(), input);
        }
        if (to.isEmpty()) {
            columnNotFound(node.location(), "TRACE: 'to' column", node.toColumn(), input);
        }
        if (weight.isEmpty()) {
            columnNotFound(node.location(), "TRACE: weight column", node.weightColumn(), input);
        }
        if (node.fromColumn().equalsIgnoreCase(node.toColumn())) {
            error(node.location(), "TRACE: 'from' and 'to' columns must differ");
        }
        if (weight.isPresent()
                && weight.get().type() != ScalarType.NUMBER
                && weight.get().type() != ScalarType.ANY) {
            error(node.location(), "TRACE: weight column '" + node.weightColumn()
                    + "' must be NUMBER (found " + weight.get().type() + ")");
        }
        // Verify the path column name does not clash with any input column.
        String path = node.pathColumn();
        if (input.declares(path)
                || path.equalsIgnoreCase(node.fromColumn())
                || path.equalsIgnoreCase(node.toColumn())
                || path.equalsIgnoreCase(node.weightColumn())) {
            error(node.location(), "TRACE: path column '" + path
                    + "' clashes with an existing column; choose a different name");
        }
        return null;
    }

    @Override
    public Void visit(LimitNode node) {
        return node.input().accept(this);
    }

    // =========================================================================
    // General recursion (FIX) — union-compatibility, monotonicity, linearity
    // =========================================================================

    @Override
    public Void visit(FixpointNode node) {
        // Recurse so predicate/column checks inside the base and step still run
        // (the recursive reference is annotated with the base schema by inference,
        // so references to its columns validate normally).
        node.base().accept(this);
        node.step().accept(this);

        // Schema rule: the step must be union-compatible with the base (same rule
        // as ∪ — equal width, positionally compatible types, ANY/open universal).
        checkFixpointUnionCompatible(node);

        // Monotonicity + linearity: walk the step, counting non-shadowed recursive
        // references to this binder and flagging any reached through a non-monotone
        // position.  Exactly one reference is required in v1.
        int refs = new RecursiveRefChecker(errors).countRecursiveRefs(node.step(), node.name());
        if (refs == 0) {
            error(node.location(), "FIX '" + node.name() + "' is not recursive: its step "
                    + "makes no reference to '" + node.name() + "' — drop the FIX");
        } else if (refs > 1) {
            error(node.location(), "FIX '" + node.name() + "': v1 supports linear recursion "
                    + "only (exactly one reference to '" + node.name() + "' in the step), found "
                    + refs);
        }
        return null;
    }

    @Override
    public Void visit(RecursiveRefNode node) {
        // A leaf: existence/binding is resolved by inference, and its monotonicity
        // is checked by the enclosing FixpointNode's step walk.  Nothing further.
        return null;
    }

    /**
     * Verifies the {@code step} of a {@link FixpointNode} is union-compatible with
     * its {@code base} (the schema rule of {@code docs/design/general-recursion-plan.md},
     * Decision 5): equal width and positionally compatible column types, with
     * {@code ANY}/open schemas universal.  Mirrors the set-operation rule.
     */
    private void checkFixpointUnionCompatible(FixpointNode node) {
        Optional<Schema> baseOpt = annotations.get(node.base());
        Optional<Schema> stepOpt = annotations.get(node.step());
        if (baseOpt.isEmpty() || stepOpt.isEmpty()) return; // inference failure already reported

        Schema base = baseOpt.get();
        Schema step = stepOpt.get();

        // An open (dynamic, schema-on-read) schema has no fixed shape — vacuously
        // compatible; the check is deferred to runtime.
        if (base.isOpen() || step.isOpen()) return;

        if (base.width() != step.width()) {
            error(node.location(), "FIX '" + node.name() + "': step is not union-compatible "
                    + "with base — base has " + base.width() + " column(s), step has "
                    + step.width() + " column(s)");
            return;
        }
        for (int i = 0; i < base.columns().size(); i++) {
            Type bt = base.columns().get(i).type();
            Type st = step.columns().get(i).type();
            if (!typesCompatible(bt, st)) {
                error(node.location(), "FIX '" + node.name() + "': step is not union-compatible "
                        + "with base — column " + (i + 1) + " type mismatch: base '"
                        + base.columns().get(i).name() + "' is " + bt + ", step '"
                        + step.columns().get(i).name() + "' is " + st);
            }
        }
    }

    @Override
    public Void visit(RenameNode node) {
        node.input().accept(this);
        if (!node.pairs().isEmpty()) {
            validateRenamePairs(node);
        } else if (!node.attributes().isEmpty()) {
            List<String> newNames = node.attributes();
            Optional<Schema> inputOpt = annotations.get(node.input());
            if (inputOpt.isPresent() && inputOpt.get().isOpen()) {
                // Positional rename names columns by position, and a schema-on-read
                // relation has none: each document carries the fields it carries, in
                // its own order, so there is no first or second column to rename. The
                // old message called the input "0-column", which described it as empty
                // rather than undescribed; the operation is genuinely undefined here,
                // and letting it through renamed nothing at all.
                error(node.location(), "Rename ρ: open (schema-on-read) input has no "
                        + "column positions to rename — name the columns explicitly "
                        + "with the pair form, ρ (old → new, …)");
            } else if (inputOpt.isPresent()) {
                int inputWidth = inputOpt.get().width();
                if (newNames.size() != inputWidth) {
                    error(node.location(), "Rename ρ: " + newNames.size() + " attribute name(s) given "
                            + "for " + inputWidth + "-column input");
                }
            }
        }
        return null;
    }

    /**
     * Validates the pair form {@code ρ (old → new, …)}: every source column must
     * exist in the input schema, no two pairs may target the same name, and a
     * target may not collide with a surviving (un-renamed) column. Skips silently
     * when the input schema is unresolved to avoid cascading false positives.
     *
     * <p>Over an open (schema-on-read) input only the checks the pairs settle by
     * themselves apply — a source renamed twice, two pairs with one target — since
     * which fields exist, and so which targets would collide, is a fact about each
     * document. The executor refuses a collision there, row by row.
     */
    private void validateRenamePairs(RenameNode node) {
        Optional<Schema> inputOpt = annotations.get(node.input());
        if (inputOpt.isEmpty()) return;
        Schema input = inputOpt.get();
        if (input == SymbolCollector.UNRESOLVED_SCHEMA) return;
        if (input.isOpen()) {
            validateRenamePairsAlone(node);
            return;
        }

        Set<String> inputNames = input.columns().stream()
                .map(c -> c.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Source columns must exist; a source may not be renamed twice.
        Set<String> renamedSources = new HashSet<>();
        for (RenameNode.RenamePair pair : node.pairs()) {
            String from = pair.from().toLowerCase(Locale.ROOT);
            if (!inputNames.contains(from)) {
                error(node.location(), "Rename ρ: unknown source column '" + pair.from()
                        + "' — input has " + columnListString(input));
            } else if (!renamedSources.add(from)) {
                error(node.location(), "Rename ρ: column '" + pair.from()
                        + "' is renamed more than once");
            }
        }

        // Target names: the surviving columns (input minus renamed sources) plus the
        // new targets must all be distinct.
        Set<String> targets = new HashSet<>();
        for (String name : inputNames) {
            if (!renamedSources.contains(name)) {
                targets.add(name);
            }
        }
        for (RenameNode.RenamePair pair : node.pairs()) {
            String to = pair.to().toLowerCase(Locale.ROOT);
            if (!targets.add(to)) {
                error(node.location(), "Rename ρ: target column '" + pair.to()
                        + "' collides with another column");
            }
        }
    }

    /** The pair-form checks that need no input heading: no source twice, no target twice. */
    private void validateRenamePairsAlone(RenameNode node) {
        Set<String> sources = new HashSet<>();
        Set<String> targets = new HashSet<>();
        for (RenameNode.RenamePair pair : node.pairs()) {
            if (!sources.add(pair.from().toLowerCase(Locale.ROOT))) {
                error(node.location(), "Rename ρ: column '" + pair.from()
                        + "' is renamed more than once");
            }
            if (!targets.add(pair.to().toLowerCase(Locale.ROOT))) {
                error(node.location(), "Rename ρ: target column '" + pair.to()
                        + "' collides with another column");
            }
        }
    }

    // =========================================================================
    // Joins — recurse, no additional schema checks
    // =========================================================================

    @Override
    public Void visit(NaturalJoinNode node) {
        node.left().accept(this);
        node.right().accept(this);
        checkNaturalJoinHasCommonColumns(node);
        return null;
    }

    /**
     * Reports an error when a natural join's two input schemas share no column
     * names, since the result would always be empty — a likely mistake.  Users
     * should use {@code ×} (CROSS) for an explicit Cartesian product.
     *
     * <p>Skips silently when either schema is open (schema-on-read) or the
     * inference placeholder, to avoid cascading false positives.
     */
    private void checkNaturalJoinHasCommonColumns(NaturalJoinNode node) {
        Optional<Schema> leftOpt  = annotations.get(node.left());
        Optional<Schema> rightOpt = annotations.get(node.right());
        if (leftOpt.isEmpty() || rightOpt.isEmpty()) return;
        Schema left  = leftOpt.get();
        Schema right = rightOpt.get();
        if (left.isOpen() || right.isOpen()) return;
        if (left == SymbolCollector.UNRESOLVED_SCHEMA || right == SymbolCollector.UNRESOLVED_SCHEMA) return;

        Set<String> leftNames = left.columns().stream()
                .map(c -> c.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        boolean hasCommonColumn = right.columns().stream()
                .anyMatch(c -> leftNames.contains(c.name().toLowerCase(Locale.ROOT)));

        if (!hasCommonColumn) {
            error(node.location(),
                    "Natural join ⋈ has no common columns"
                    + " (left: " + columnListString(left)
                    + ", right: " + columnListString(right)
                    + "); use × (CROSS) for a Cartesian product");
        }
    }

    private static String columnListString(Schema schema) {
        return "[" + schema.columns().stream()
                .map(ColumnDefinition::name)
                .collect(Collectors.joining(", ")) + "]";
    }

    @Override
    public Void visit(ThetaJoinNode node) {
        node.left().accept(this);
        node.right().accept(this);
        validateJoinCondition(node.left(), node.right(), node.condition(), "Theta join ⋈");
        return null;
    }

    @Override
    public Void visit(LeftOuterJoinNode node) {
        node.left().accept(this);
        node.right().accept(this);
        validateJoinCondition(node.left(), node.right(), node.condition(), "Left outer join ⟕");
        return null;
    }

    @Override
    public Void visit(RightOuterJoinNode node) {
        node.left().accept(this);
        node.right().accept(this);
        validateJoinCondition(node.left(), node.right(), node.condition(), "Right outer join ⟖");
        rejectUnmatchedRightOverOpen(node, node.left(), node.right(), "Right outer join ⟖");
        return null;
    }

    @Override
    public Void visit(FullOuterJoinNode node) {
        node.left().accept(this);
        node.right().accept(this);
        validateJoinCondition(node.left(), node.right(), node.condition(), "Full outer join ⟗");
        rejectUnmatchedRightOverOpen(node, node.left(), node.right(), "Full outer join ⟗");
        return null;
    }

    /**
     * Rejects the two joins that emit an <em>unmatched right</em> row when the result
     * heading is schema-on-read.
     *
     * <p>Over documents, a matched row renames a right field that collides with a left
     * one ({@code id} → {@code id_r}), exactly as a declared join heading does. An
     * unmatched right row has no left row to collide with, so the same field arrives
     * un-renamed — and {@code id} then means the left's id on one row and the right's
     * on the next. That is not shape variation in the data, it is the join naming one
     * field two ways, and a consumer reading the column cannot tell which it got.
     *
     * <p>The inner joins and the left outer join have no such problem: their unmatched
     * rows are <em>left</em> rows, whose names are the ones the matched rows keep.
     */
    private void rejectUnmatchedRightOverOpen(RelNode node, RelNode left, RelNode right,
                                              String context) {
        boolean open = annotations.get(left).map(Schema::isOpen).orElse(false)
                || annotations.get(right).map(Schema::isOpen).orElse(false);
        if (open) {
            error(node.location(), context + ": open (schema-on-read) input is not "
                    + "supported — an unmatched right row cannot be named consistently "
                    + "with a matched one. A left outer join (⟕) over the same inputs "
                    + "has no such ambiguity, as does projecting each side into a "
                    + "declared heading first");
        }
    }

    @Override
    public Void visit(SemiJoinNode node) {
        node.left().accept(this);
        node.right().accept(this);
        // Semi-join output is left-only, but the condition can reference both sides.
        validateJoinCondition(node.left(), node.right(), node.condition(), "Semi-join ⋉");
        return null;
    }

    @Override
    public Void visit(AntiJoinNode node) {
        node.left().accept(this);
        node.right().accept(this);
        validateJoinCondition(node.left(), node.right(), node.condition(), "Anti-join ▷");
        return null;
    }

    @Override
    public Void visit(PairwiseUniversalNode node) {
        node.left().accept(this);
        node.right().accept(this);
        validateJoinCondition(node.left(), node.right(), node.condition(), "Pairwise universal USEMI");
        return null;
    }

    @Override
    public Void visit(AsOfJoinNode node) {
        node.left().accept(this);
        node.right().accept(this);
        validateJoinCondition(node.left(), node.right(), node.condition(), "AS-OF join ASOF");
        ComparisonPredicate matchOn = validateAsOfShape(node);
        if (node.tolerance().isPresent()) {
            validateAsOfTolerance(node, matchOn);
        }
        return null;
    }

    /**
     * Enforces the AS-OF match-condition shape: a flat conjunction (∧-only) of
     * equality conjuncts (the partition keys) plus <em>exactly one</em> ordering
     * inequality (the match column). Anything else — an {@code OR}/{@code NOT},
     * a {@code ≠}, a non-comparison conjunct, or zero/≥2 inequalities — is a
     * positioned error.
     *
     * @return the single ordering inequality, or {@code null} when the shape is
     *         invalid and an error has been reported
     */
    private ComparisonPredicate validateAsOfShape(AsOfJoinNode node) {
        List<Predicate> conjuncts = new ArrayList<>();
        if (!flattenConjuncts(node.condition(), conjuncts)) {
            error(node.location(),
                  "AS-OF join condition must be a conjunction (∧) of comparisons; "
                  + "OR/NOT/IS NULL/∈ are not allowed");
            return null;
        }
        int inequalities = 0;
        ComparisonPredicate matchOn = null;
        for (Predicate p : conjuncts) {
            if (!(p instanceof ComparisonPredicate cmp)) {
                error(node.location(),
                      "AS-OF join condition must be a conjunction of comparisons");
                return null;
            }
            switch (cmp.operator()) {
                case EQUAL -> { /* a partition key */ }
                case LESS, LESS_EQUAL, GREATER, GREATER_EQUAL -> {
                    inequalities++;
                    matchOn = cmp;
                }
                case NOT_EQUAL -> {
                    error(node.location(),
                          "AS-OF join condition may not use ≠ (!=); use one ordering "
                          + "inequality (<, <=, >, >=) for the match column");
                    return null;
                }
            }
        }
        if (inequalities != 1) {
            error(node.location(),
                  "AS-OF join condition must have exactly one ordering inequality "
                  + "(<, <=, >, >=) for the match column; found " + inequalities);
            return null;
        }
        return matchOn;
    }

    /**
     * Checks a {@code WITHIN} tolerance against the match column it bounds: the
     * bound must be a {@code DURATION} literal, and the match column must hold a
     * temporal value.  A tolerance is a temporal distance, and there is none to
     * measure between two strings or two numbers — ISO-8601 text still
     * <em>orders</em> correctly, so the join looks right while the bound does
     * nothing, which is why this is an error rather than a silently skipped check.
     *
     * <p>Where the match column's type is unknown — an open (schema-on-read)
     * source, an {@code ANY} column — nothing is reported: the executor raises the
     * same objection when the values themselves turn out to be non-temporal.
     *
     * @param node    the AS-OF join carrying the tolerance
     * @param matchOn the ordering inequality naming the match column, or
     *                {@code null} when the condition shape was already rejected
     */
    private void validateAsOfTolerance(AsOfJoinNode node, ComparisonPredicate matchOn) {
        if (!(node.tolerance().get() instanceof DurationOperand)) {
            error(node.location(),
                  "AS-OF join: WITHIN tolerance must be a DURATION literal, "
                  + "e.g. WITHIN DURATION 'PT1H'");
        }
        if (matchOn == null) return;
        Optional<Schema> leftOpt  = annotations.get(node.left());
        Optional<Schema> rightOpt = annotations.get(node.right());
        if (leftOpt.isEmpty() || rightOpt.isEmpty()) return;
        String offender = nonTemporalMatchColumn(matchOn.left(), leftOpt.get(), rightOpt.get());
        if (offender == null) {
            offender = nonTemporalMatchColumn(matchOn.right(), leftOpt.get(), rightOpt.get());
        }
        if (offender != null) {
            error(node.location(),
                  "AS-OF join: WITHIN measures a temporal distance, so the match column "
                  + "must be DATE, TIME, TIMESTAMP or DURATION; " + offender
                  + ". Convert it first, e.g. π to_timestamp(ts) → ts (…)");
        }
    }

    /**
     * Describes {@code operand} as a non-temporal match column
     * (e.g. {@code "'Trades.ts' is STRING"}), or returns {@code null} when it is
     * temporal, of unknown type, or not a column reference at all.
     *
     * <p>The operand is resolved against both inputs rather than one: an
     * unqualified name may sit on either side, and the canonical AS-OF condition
     * names the same column on both.  Every resolution has to be temporal, since a
     * distance is only defined when both endpoints are.
     */
    private static String nonTemporalMatchColumn(Operand operand, Schema left, Schema right) {
        if (!(operand instanceof AttributeOperand attr)) return null;
        String column = AttributeNames.stripQualifier(attr.name());
        for (Schema side : List.of(left, right)) {
            Optional<ColumnDefinition> col = side.column(column);
            if (col.isEmpty()) continue;                       // reported by the condition check
            if (!(col.get().type() instanceof ScalarType type)) continue;
            if (type == ScalarType.ANY || TemporalArithmetic.isTemporal(type)) continue;
            return "'" + attr.name() + "' is " + type.name();
        }
        return null;
    }

    /**
     * Flattens a conjunction tree into its atomic conjuncts. Returns {@code false}
     * if any non-{@code AndPredicate}, non-leaf connective ({@code OR}/{@code NOT})
     * is encountered — i.e. the predicate is not a pure conjunction of leaves.
     */
    private static boolean flattenConjuncts(Predicate p, List<Predicate> out) {
        if (p instanceof AndPredicate and) {
            return flattenConjuncts(and.left(), out) && flattenConjuncts(and.right(), out);
        }
        if (p instanceof ComparisonPredicate) {
            out.add(p);
            return true;
        }
        return false;   // OR / NOT / NULL / ∈ — not an allowed AS-OF conjunct
    }

    @Override
    public Void visit(IntervalJoinNode node) {
        node.left().accept(this);
        node.right().accept(this);
        // Validate that the four endpoint columns exist in their respective input schemas.
        Optional<Schema> leftOpt  = annotations.get(node.left());
        Optional<Schema> rightOpt = annotations.get(node.right());
        // Endpoint references may be qualified (e.g. "Stays.checkin"); strip the
        // qualifier before the lookup, matching the planner/executor's resolution.
        if (leftOpt.isPresent() && !leftOpt.get().isOpen()) {
            Schema leftSchema = leftOpt.get();
            if (leftSchema.column(AttributeNames.stripQualifier(node.leftStart())).isEmpty()) {
                error(node.location(),
                      "Interval join: left-start column '" + node.leftStart()
                      + "' not found in left relation" + columnHint(node.leftStart(), leftSchema));
            }
            if (leftSchema.column(AttributeNames.stripQualifier(node.leftEnd())).isEmpty()) {
                error(node.location(),
                      "Interval join: left-end column '" + node.leftEnd()
                      + "' not found in left relation" + columnHint(node.leftEnd(), leftSchema));
            }
        }
        if (rightOpt.isPresent() && !rightOpt.get().isOpen()) {
            Schema rightSchema = rightOpt.get();
            if (rightSchema.column(AttributeNames.stripQualifier(node.rightStart())).isEmpty()) {
                error(node.location(),
                      "Interval join: right-start column '" + node.rightStart()
                      + "' not found in right relation" + columnHint(node.rightStart(), rightSchema));
            }
            if (rightSchema.column(AttributeNames.stripQualifier(node.rightEnd())).isEmpty()) {
                error(node.location(),
                      "Interval join: right-end column '" + node.rightEnd()
                      + "' not found in right relation" + columnHint(node.rightEnd(), rightSchema));
            }
        }
        return null;
    }

    @Override
    public Void visit(ProductNode node) {
        node.left().accept(this);
        node.right().accept(this);
        return null;
    }

    // =========================================================================
    // Set operations — schema compatibility check
    // =========================================================================

    @Override
    public Void visit(UnionNode node) {
        node.left().accept(this);
        node.right().accept(this);
        checkSetOpCompatibility(node.left(), node.right(), "∪");
        return null;
    }

    @Override
    public Void visit(UnionAllNode node) {
        node.left().accept(this);
        node.right().accept(this);
        checkSetOpCompatibility(node.left(), node.right(), "⊎");
        return null;
    }

    @Override
    public Void visit(OuterUnionNode node) {
        // No set-op compatibility check: an outer-union deliberately tolerates
        // heterogeneous schemas (it aligns common columns and NULL-pads the rest).
        node.left().accept(this);
        node.right().accept(this);
        return null;
    }

    @Override
    public Void visit(IntersectionNode node) {
        node.left().accept(this);
        node.right().accept(this);
        checkSetOpCompatibility(node.left(), node.right(), "∩");
        return null;
    }

    @Override
    public Void visit(DifferenceNode node) {
        node.left().accept(this);
        node.right().accept(this);
        checkSetOpCompatibility(node.left(), node.right(), "−");
        return null;
    }

    @Override
    public Void visit(SymmetricDifferenceNode node) {
        node.left().accept(this);
        node.right().accept(this);
        checkSetOpCompatibility(node.left(), node.right(), "∆");
        return null;
    }

    // =========================================================================
    // Composition — natural-join-then-project on shared columns (recurse only;
    // the shared-column requirement is enforced during schema inference)
    // =========================================================================

    @Override
    public Void visit(CompositionNode node) {
        node.left().accept(this);
        node.right().accept(this);
        return null;
    }

    // =========================================================================
    // Universal quantification — grouping keys exist + predicate is valid
    // =========================================================================

    @Override
    public Void visit(UniversalNode node) {
        node.input().accept(this);

        // An empty grouping-key list is the legal no-key whole-relation form
        // (∀ : P (R)) → a nullary truth relation; only the predicate is checked.
        Optional<Schema> inputOpt = annotations.get(node.input());
        if (inputOpt.isEmpty()) return null;
        Schema input = inputOpt.get();

        for (String key : node.groupingAttributes()) {
            if (input.column(key).isEmpty()) {
                columnNotFound(node.location(), "Universal quantification ∀: grouping key", key, input);
            }
        }
        validatePredicate(node.predicate(), input, "Universal quantification ∀");
        return null;
    }

    // =========================================================================
    // Division — right columns must be a subset of left columns
    // =========================================================================

    @Override
    public Void visit(DivisionNode node) {
        node.left().accept(this);
        node.right().accept(this);

        Optional<Schema> leftOpt  = annotations.get(node.left());
        Optional<Schema> rightOpt = annotations.get(node.right());
        if (leftOpt.isEmpty() || rightOpt.isEmpty()) return null;

        Schema left  = leftOpt.get();
        Schema right = rightOpt.get();
        Set<String> leftNames = left.columns().stream()
                .map(c -> c.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        for (ColumnDefinition rc : right.columns()) {
            if (!leftNames.contains(rc.name().toLowerCase(Locale.ROOT))) {
                error(node.location(), "Division ÷: right-side column '" + rc.name()
                        + "' does not appear in the left schema");
            }
        }
        return null;
    }

    // =========================================================================
    // Projection — attribute column existence + function arity
    // =========================================================================

    @Override
    public Void visit(ProjectionNode node) {
        node.input().accept(this);

        Optional<Schema> inputOpt = annotations.get(node.input());
        if (inputOpt.isEmpty()) return null;
        Schema input = inputOpt.get();

        for (ProjectedAttribute pa : node.attributes()) {
            validateProjectedOperand(pa.expression(), input);
        }
        return null;
    }

    // =========================================================================
    // Aggregation — group-by + aggregate attribute existence
    // =========================================================================

    @Override
    public Void visit(AggregationNode node) {
        node.input().accept(this);

        Optional<Schema> inputOpt = annotations.get(node.input());
        if (inputOpt.isEmpty()) return null;
        Schema input = inputOpt.get();

        for (GroupingKey key : node.groupingKeys()) {
            Optional<String> col = key.columnName();
            if (col.isPresent()) {
                if (input.column(col.get()).isEmpty()) {
                    columnNotFound(node.location(), "Aggregation γ: group-by attribute", col.get(), input);
                }
            } else {
                // A derived grouping key (e.g. YEAR(ts)) — validate its attribute
                // references and function calls just like an aggregate argument.
                new PredicateValidator(input, symbolTable, functions, errors, filePath,
                        "Aggregation γ group-by", parameters)
                        .validateExpression(key.expression());
            }
        }

        for (AggregateFunction agg : node.aggregates()) {
            // The argument (and the ARGMAX/ARGMIN yield) are full expressions: validate
            // their attribute references and function calls against the input schema.
            var operandValidator = new PredicateValidator(
                    input, symbolTable, functions, errors, filePath,
                    "Aggregation γ " + agg.operator().name(), parameters);
            operandValidator.validateExpression(agg.argument());
            agg.yieldExpr().ifPresent(operandValidator::validateExpression);
        }
        return null;
    }

    // =========================================================================
    // Sort — attribute existence
    // =========================================================================

    @Override
    public Void visit(SortNode node) {
        node.input().accept(this);

        Optional<Schema> inputOpt = annotations.get(node.input());
        if (inputOpt.isEmpty()) return null;
        Schema input = inputOpt.get();

        for (SortSpecification spec : node.sortSpecs()) {
            validateSortKey(node.location(), "Sort τ: sort attribute", spec, input);
        }
        return null;
    }

    /**
     * Validates a single sort key against {@code input}: a bare column must exist
     * (reported via {@code context}); a derived expression (e.g.
     * {@code to_timestamp(logged)}) has its attribute references and function
     * calls validated like any other operand.
     */
    private void validateSortKey(SourceLocation loc, String context,
                                 SortSpecification spec, Schema input) {
        if (spec.expression() instanceof AttributeOperand attr) {
            // A sort key spelled as an attribute has the three readings every attribute
            // reference has: a bare column, a relation-qualified one, and a path into a
            // nested column. `columnName()` answers only the first two — it hands back
            // the name's tail — so `τ location.city` used to be refused with a
            // diagnostic naming a column ('city') the query never wrote.
            if (input.resolvePath(attr.name()).isEmpty()
                    && input.column(attr.unqualifiedName()).isEmpty()) {
                columnNotFound(loc, context, attr.name(), input);
            }
        } else {
            new PredicateValidator(input, symbolTable, functions, errors, filePath, context, parameters)
                    .validateExpression(spec.expression());
        }
    }

    // =========================================================================
    // Helpers — set-op compatibility
    // =========================================================================

    /**
     * Verifies that {@code left} and {@code right} have schemas compatible for
     * a set operation named {@code opSymbol}.  Both must have the same column
     * count, and positionally corresponding column types must be compatible
     * ({@link ScalarType#ANY} is compatible with any type).
     */
    private void checkSetOpCompatibility(RelNode left, RelNode right, String opSymbol) {
        Optional<Schema> leftOpt  = annotations.get(left);
        Optional<Schema> rightOpt = annotations.get(right);
        if (leftOpt.isEmpty() || rightOpt.isEmpty()) return; // inference failure already reported

        Schema l = leftOpt.get();
        Schema r = rightOpt.get();

        // An open (dynamic, schema-on-read) schema has no fixed shape, so it is
        // compatible with any other — the check is deferred to runtime.
        if (l.isOpen() || r.isOpen()) return;

        // Use the left node's location for set-op errors (it's where the operator sits)
        SourceLocation loc = left.location();

        if (l.width() != r.width()) {
            error(loc, "Set operation " + opSymbol + " requires equal-width schemas: "
                    + "left has " + l.width() + " column(s), "
                    + "right has " + r.width() + " column(s)");
            return;
        }

        for (int i = 0; i < l.columns().size(); i++) {
            Type lt = l.columns().get(i).type();
            Type rt = r.columns().get(i).type();
            if (!typesCompatible(lt, rt)) {
                String lName = l.columns().get(i).name();
                String rName = r.columns().get(i).name();
                error(loc, "Set operation " + opSymbol
                        + ": column " + (i + 1) + " type mismatch — "
                        + "left '" + lName + "' is " + lt
                        + ", right '" + rName + "' is " + rt);
            }
        }
    }

    /**
     * {@code ANY} is compatible with any type; otherwise types must be structurally
     * equal (record equality recurses through nested {@code StructType}/{@code ArrayType}).
     */
    private static boolean typesCompatible(Type a, Type b) {
        return a == ScalarType.ANY || b == ScalarType.ANY || a.equals(b);
    }

    // =========================================================================
    // Helpers — operand validation
    // =========================================================================

    /**
     * Validates a projected operand expression against the given input schema,
     * driving the shared {@link OperandWalker} over the expression tree:
     *
     * <ul>
     *   <li>Each {@link AttributeOperand}'s (unqualified) column name must exist
     *       in {@code inputSchema} (or name an in-scope function parameter).</li>
     *   <li>Each {@link FunctionCall} must be registered in the symbol table and
     *       its argument count must match the parameter count of at least one
     *       overload (see {@link #validateFunctionCall(FunctionCall)}).</li>
     * </ul>
     *
     * Compound operands are traversed by the walker; literals need no validation.
     */
    private void validateProjectedOperand(Operand expr, Schema inputSchema) {
        validateTemporalArithmetic(expr, inputSchema);
        OperandWalker.walk(expr,
                attr -> {
                    // A relation-qualified reference must resolve by source-relation
                    // provenance — never silently strip its qualifier.
                    String qualifiedError = QualifiedReferences.resolutionError(attr, inputSchema);
                    if (qualifiedError != null) {
                        error(attr.location(), "Projection π: " + qualifiedError);
                        return;
                    }
                    String colName = attr.unqualifiedName();
                    if (inputSchema.column(colName).isEmpty()
                            && inputSchema.resolvePath(attr.name()).isEmpty()
                            && !parameters.contains(colName.toLowerCase(Locale.ROOT))) {
                        columnNotFound(attr.location(), "Projection π: attribute", attr.name(), inputSchema);
                    }
                },
                this::validateFunctionCall);
    }

    /**
     * Reports an illegal temporal-arithmetic combination anywhere inside {@code expr}
     * (ADR-0013) — {@code TIMESTAMP + TIMESTAMP}, {@code NUMBER ÷ DURATION} and the rest
     * of the pairs the type rule has no case for.
     *
     * <p>σ, τ and γ reach the same check through {@code PredicateValidator}, which walks
     * an operand for its own reasons and asks the rule on the way past. A projection does
     * not go through that validator — it has its own attribute walk, so that it can report
     * with the source position of the offending reference — and the temporal question was
     * simply never asked on this path. Inference then returned its lenient {@code ANY} and
     * the query analysed clean, leaving the evaluator to raise once rows were already
     * flowing.
     *
     * <p>Children are checked before the node itself, so the innermost illegal combination
     * is the one reported rather than a cascade from it.
     */
    private void validateTemporalArithmetic(Operand expr, Schema inputSchema) {
        OperandTypeInferrer inferrer = new OperandTypeInferrer(symbolTable, functions);
        validateTemporalArithmetic(expr, inputSchema, inferrer);
    }

    private void validateTemporalArithmetic(Operand expr, Schema schema,
                                            OperandTypeInferrer inferrer) {
        switch (expr) {
            case BinaryArithmeticExpression arith -> {
                validateTemporalArithmetic(arith.left(), schema, inferrer);
                validateTemporalArithmetic(arith.right(), schema, inferrer);
                TemporalArithmetic.Result r = TemporalArithmetic.binary(
                        inferrer.infer(arith.left(), schema), arith.operator(),
                        inferrer.infer(arith.right(), schema));
                if (r.isError()) {
                    error(arith.location(), "Projection π: " + r.error());
                }
            }
            case UnaryOperand unary -> {
                validateTemporalArithmetic(unary.operand(), schema, inferrer);
                TemporalArithmetic.Result r =
                        TemporalArithmetic.unary(inferrer.infer(unary.operand(), schema));
                if (r.isError()) {
                    error(unary.location(), "Projection π: " + r.error());
                }
            }
            case FunctionCall fn -> {
                for (Operand arg : fn.arguments()) {
                    validateTemporalArithmetic(arg, schema, inferrer);
                }
            }
            case ConditionOperand cond ->
                    validateTemporalArithmetic(cond.predicate(), schema, inferrer);
            case SetLiteralOperand set -> {
                for (Operand elem : set.elements()) {
                    validateTemporalArithmetic(elem, schema, inferrer);
                }
            }
            case StructConstruction struct -> {
                for (StructConstruction.Field field : struct.fields()) {
                    validateTemporalArithmetic(field.value(), schema, inferrer);
                }
            }
            case ArrayConstruction array -> {
                for (Operand elem : array.elements()) {
                    validateTemporalArithmetic(elem, schema, inferrer);
                }
            }
            default -> {
                // A bare attribute or literal carries no arithmetic to check.
            }
        }
    }

    /**
     * The predicate half of {@link #validateTemporalArithmetic(Operand, Schema,
     * OperandTypeInferrer)} — a boolean written in operand position, as an
     * {@code IIf}'s test is. Exhaustive over the sealed hierarchy, so a new predicate
     * form cannot silently stop being checked.
     */
    private void validateTemporalArithmetic(Predicate p, Schema schema,
                                            OperandTypeInferrer inferrer) {
        switch (p) {
            case ComparisonPredicate c -> {
                validateTemporalArithmetic(c.left(), schema, inferrer);
                validateTemporalArithmetic(c.right(), schema, inferrer);
            }
            case AndPredicate a -> {
                validateTemporalArithmetic(a.left(), schema, inferrer);
                validateTemporalArithmetic(a.right(), schema, inferrer);
            }
            case OrPredicate o -> {
                validateTemporalArithmetic(o.left(), schema, inferrer);
                validateTemporalArithmetic(o.right(), schema, inferrer);
            }
            case NotPredicate n -> validateTemporalArithmetic(n.predicate(), schema, inferrer);
            case NullPredicate n -> validateTemporalArithmetic(n.operand(), schema, inferrer);
            case ElementOfPredicate e -> {
                validateTemporalArithmetic(e.element(), schema, inferrer);
                validateTemporalArithmetic(e.setExpression(), schema, inferrer);
            }
            case PatternPredicate pp -> {
                validateTemporalArithmetic(pp.operand(), schema, inferrer);
                validateTemporalArithmetic(pp.pattern(), schema, inferrer);
            }
        }
    }

    /**
     * Validates a {@link FunctionCall}: the function must be one the symbol table
     * or the function catalogue defines, and the argument count must be one it
     * accepts. The arguments themselves are validated by the
     * {@link OperandWalker} that drives this check.
     */
    private void validateFunctionCall(FunctionCall fn) {
        ResolvedFunction resolved =
                ResolvedFunction.of(functions, fn.functionName(), symbolTable);
        if (resolved.isUnknown()) {
            unknownFunction(fn.location(), fn.functionName());
            return;
        }

        int argCount = fn.arguments().size();
        if (!resolved.accepts(argCount)) {
            error(fn.location(), "Function '" + fn.functionName() + "' called with " + argCount
                    + " argument(s) but expects " + resolved.expectedArity());
        }
    }

    // =========================================================================
    // Helpers — predicate validation
    // =========================================================================

    /**
     * Validates all attribute references and function calls in {@code predicate}
     * against {@code schema} using a {@link PredicateValidator}.
     *
     * @param predicate the predicate to validate
     * @param schema    the schema against which attribute names are checked
     * @param context   short label for error messages (e.g. {@code "Selection σ"})
     */
    private void validatePredicate(Predicate predicate, Schema schema, String context) {
        predicate.accept(new PredicateValidator(
                schema, symbolTable, functions, errors, filePath, context, parameters));
    }

    /**
     * Validates a join condition against the combined left+right schema.
     * Uses {@link Schema#concat(Schema)} to build the schema, matching what
     * the inference phase produced for the join node's output.
     *
     * <p>Skips silently if either input schema is missing (inference already
     * reported an error for the unresolvable subtree).
     */
    private void validateJoinCondition(RelNode left, RelNode right,
                                       Predicate condition, String opName) {
        Optional<Schema> leftOpt  = annotations.get(left);
        Optional<Schema> rightOpt = annotations.get(right);
        if (leftOpt.isEmpty() || rightOpt.isEmpty()) return;
        Schema combined = leftOpt.get().concat(rightOpt.get());
        validatePredicate(condition, combined, opName);
    }

    // =========================================================================
    // Error helpers
    // =========================================================================

    /**
     * Records an error at the position described by {@code loc}.
     *
     * @param loc     the source location from the AST node
     * @param message the diagnostic message
     */
    private void error(SourceLocation loc, String message) {
        errors.add(SemanticError.error(loc.filePath(), loc.line(), loc.column(), message));
    }

    /**
     * Records a "column not found in input schema" error, enriched with a
     * "did you mean?" suggestion drawn from {@code schema}'s column names (or,
     * failing a close match, the list of available columns). The single seam for
     * the operator-specific column-reference checks (projection, grouping/sort
     * keys, join/closure endpoints, …) so every such diagnostic is uniformly
     * REPL-friendly.
     *
     * @param loc           the source location of the offending reference
     * @param contextPrefix the message prefix up to (but excluding) the quoted
     *                      column, e.g. {@code "Aggregation γ: group-by attribute"}
     * @param column        the unresolved column as the user wrote it (may be qualified)
     * @param schema        the input schema the column was sought in
     */
    private void columnNotFound(SourceLocation loc, String contextPrefix, String column, Schema schema) {
        error(loc, contextPrefix + " '" + column + "' not found in input schema"
                + Suggestions.columnHint(AttributeNames.stripQualifier(column),
                        columnNames(schema), !schema.isOpen()));
    }

    /** The column names of {@code schema}, in schema order, for suggestion candidates. */
    private static List<String> columnNames(Schema schema) {
        return schema.columns().stream().map(ColumnDefinition::name).toList();
    }

    /**
     * The "did you mean?"/available-columns suffix for a missing {@code column}
     * against {@code schema} — for the few sites whose message tail differs from
     * the shared {@link #columnNotFound} (e.g. the interval join's "left/right
     * relation").
     */
    private static String columnHint(String column, Schema schema) {
        return Suggestions.columnHint(AttributeNames.stripQualifier(column),
                columnNames(schema), !schema.isOpen());
    }

    /**
     * Records an "Unknown function" error, appending a "did you mean?" suggestion
     * drawn from the function catalogue and any user-defined functions in scope.
     *
     * @param loc  the source location of the offending call
     * @param name the unresolved function name as the user typed it
     */
    private void unknownFunction(SourceLocation loc, String name) {
        String suggestion = Suggestions.didYouMean(name, functionCandidates());
        if (suggestion.isEmpty() && functions.isEmpty()) {
            // Nothing near the name, and no library to have offered one: every call in
            // this script is unknown, and the cause is the installation rather than the
            // spelling. Say that, instead of a message that reads like a typo.
            error(loc, "Unknown function: '" + name + "' — no function library is"
                    + " installed. Add com.darkcollective.relix.function.builtin to the"
                    + " module path, or supply a FunctionLibrary.");
            return;
        }
        error(loc, "Unknown function: '" + name + "'" + suggestion);
    }

    /**
     * Collects every known function name — built-ins plus user-defined scalar and
     * table-valued functions — as candidates for typo suggestions.
     *
     * @return the set of known function names (original casing)
     */
    private Set<String> functionCandidates() {
        Set<String> names = new HashSet<>();
        functions.scalars().forEach(fn -> names.add(fn.signature().name()));
        symbolTable.allSymbols().stream()
                .filter(FunctionSymbol.class::isInstance)
                .map(FunctionSymbol.class::cast)
                .forEach(f -> names.add(f.declaredName()));
        return names;
    }

}

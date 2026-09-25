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
package com.darkcollective.relix.processor.eval;

import com.darkcollective.relix.processor.EvaluationException;

import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.BooleanOperand;
import com.darkcollective.relix.ast.ConditionOperand;
import com.darkcollective.relix.ast.DateOperand;
import com.darkcollective.relix.ast.TimeOperand;
import com.darkcollective.relix.ast.TimestampOperand;
import com.darkcollective.relix.ast.DurationOperand;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.ArrayConstruction;
import com.darkcollective.relix.ast.SetLiteralOperand;
import com.darkcollective.relix.ast.StructConstruction;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.ast.UnaryOperand;
import com.darkcollective.relix.processor.internal.ArrayRow;
import com.darkcollective.relix.processor.internal.ExecutionContext;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.function.Argument;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.function.FunctionContext;
import com.darkcollective.relix.function.LazyScalarFunction;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.function.StrictScalarFunction;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.function.FunctionSymbol;
import com.darkcollective.relix.symbol.function.ScalarFunctionSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Evaluates an {@link Operand} expression against a {@link Row}, producing a
 * runtime {@link Value}.
 *
 * <p>Handles all eight subtypes of the sealed {@code Operand} hierarchy:
 * literals, attribute references, unary negation, binary arithmetic, function
 * calls, and set literals (which cannot be evaluated as scalars and throw).
 *
 * <p>Arithmetic follows SQL NULL-propagation rules: if either operand is NULL
 * the result is NULL.  String addition concatenates two strings; all other
 * arithmetic requires numeric operands.
 *
 * <p>Qualified attribute names (e.g. {@code "Orders.amount"}) are resolved by
 * stripping the relation prefix and looking up just the column name.
 *
 * <p>Function resolution proceeds in two stages:
 * <ol>
 *   <li>The {@link FunctionCatalog} — whichever function libraries are installed —
 *       is checked first.</li>
 *   <li>If not found there, the {@link SymbolTable} (when supplied) is searched
 *       for a user-defined function whose body is evaluated recursively with the
 *       call arguments bound to the function's parameters.</li>
 * </ol>
 *
 * <p>A catalogue holds a function's implementation as well as its form, so the
 * evaluator neither knows nor cares which library supplied one: it dispatches on
 * whether the function wants its arguments evaluated ({@code StrictScalarFunction})
 * or deferred ({@code LazyScalarFunction}), and a library's own function is
 * evaluated exactly as a shipped one is.
 *
 * <h2>Thread safety</h2>
 * <p>Instances are stateless (apart from the immutable {@link SymbolTable}
 * reference, the immutable catalogue, and the immutable ambient context) and
 * therefore thread-safe.
 */
public final class OperandEvaluator {

    /** Symbol table for user-defined function lookup; {@code null} if none provided. */
    private final SymbolTable symbolTable;

    /** The functions available to this evaluator. */
    private final FunctionCatalog functions;

    /**
     * The ambient state a function may read, held as a field rather than built per
     * call: one context serves every row of every query this evaluator runs.
     */
    private final FunctionContext context;

    /**
     * Constructs an evaluator without a symbol table, over the installed function
     * libraries.  User-defined functions resolve to {@link EvaluationException}.
     */
    public OperandEvaluator() {
        this(null);
    }

    /**
     * Constructs an evaluator with the given symbol table for user-defined
     * function resolution, over the installed function libraries and reading the
     * system UTC clock.
     *
     * @param symbolTable the symbol table to search for user-defined functions;
     *                    may be {@code null} (disables user-defined function support)
     */
    public OperandEvaluator(SymbolTable symbolTable) {
        this(symbolTable, ExecutionContext.installedFunctions());
    }

    /**
     * Constructs an evaluator over an explicit catalogue, reading the system UTC clock.
     *
     * @param symbolTable the symbol table to search for user-defined functions;
     *                    may be {@code null} (disables user-defined function support)
     * @param functions   the functions available to calls; must not be {@code null}
     */
    public OperandEvaluator(SymbolTable symbolTable, FunctionCatalog functions) {
        this(symbolTable, functions, contextFor(Clock.systemUTC()));
    }

    /**
     * The ambient context the engine supplies: the given clock, and the engine's own
     * total order over values.
     *
     * <p>The order matters to any function that ranks its input — the {@code MIN} of a
     * group has to be the first row of the corresponding sort, and it is not if the
     * two use different comparators. Built here rather than left to
     * {@link FunctionContext#systemDefault()} so that a value which arrived as
     * ISO-8601 text from a schema-less source still compares against a typed one.
     *
     * @param clock the clock the current-time functions read; must not be {@code null}
     * @return the engine's function context over {@code clock}
     */
    public static FunctionContext contextFor(Clock clock) {
        return FunctionContext.of(clock, ValueComparator.NULLS_LAST);
    }

    /**
     * The functions this evaluator resolves calls against — the same catalogue an
     * aggregate is looked up in, so a γ and a π in one query agree about a name.
     *
     * @return the catalogue; never {@code null}
     */
    public FunctionCatalog functions() {
        return functions;
    }

    /**
     * The ambient state this evaluator hands to a function or an accumulator.
     *
     * @return the context; never {@code null}
     */
    public FunctionContext functionContext() {
        return context;
    }

    /**
     * Constructs an evaluator over an explicit catalogue and ambient context.
     *
     * <p>The context carries the clock the current-time functions read. Pinning it
     * makes a run reproducible — the same script over the same data yields the same
     * rows however much later it is replayed — and costs nothing, because one
     * immutable catalogue serves every clock. See
     * {@link com.darkcollective.relix.processor.internal.ExecutionContext#clock()}.
     *
     * @param symbolTable the symbol table to search for user-defined functions;
     *                    may be {@code null} (disables user-defined function support)
     * @param functions   the functions available to calls; must not be {@code null}
     * @param context     the ambient state a function may read; must not be {@code null}
     */
    public OperandEvaluator(SymbolTable symbolTable, FunctionCatalog functions,
                            FunctionContext context) {
        this.symbolTable = symbolTable;
        this.functions = Objects.requireNonNull(functions, "functions");
        this.context = Objects.requireNonNull(context, "context");
    }

    /**
     * Evaluates {@code operand} against the given {@code row}.
     *
     * @param operand the expression to evaluate; must not be {@code null}
     * @param row     the current row providing attribute values; must not be {@code null}
     * @return the resulting value; never {@code null}
     * @throws EvaluationException if evaluation fails (unknown column, type mismatch, etc.)
     */
    public Value evaluate(Operand operand, Row row) {
        return switch (operand) {
            case StringOperand  s -> new StringValue(s.value());
            case NumberOperand  n -> NumberValue.of(n.value());
            case BooleanOperand b -> BooleanValue.of(b.value());
            case DateOperand      d -> new DateValue(d.value());
            case TimeOperand      t -> new TimeValue(t.value());
            case TimestampOperand ts -> new TimestampValue(ts.value());
            case DurationOperand  du -> new DurationValue(du.value());
            case AttributeOperand a -> evaluateAttribute(a, row);
            case UnaryOperand   u -> evaluateUnary(u, row);
            case BinaryArithmeticExpression b -> evaluateBinary(b, row);
            case FunctionCall   f -> evaluateFunction(f, row);
            case StructConstruction struct -> evaluateStruct(struct, row);
            case ArrayConstruction  array  -> evaluateArray(array, row);
            case SetLiteralOperand s -> throw new EvaluationException(
                    "SetLiteralOperand cannot be evaluated as a scalar value");
            // A predicate in operand position (e.g. IIf's test) evaluates through the
            // same predicate engine σ uses — and this is the one place its third
            // truth value becomes data: UNKNOWN is a boolean nobody knows, which is
            // a NULL, not a false. Projecting `amount > 100` for a row with no
            // amount must not claim the amount is small.
            case ConditionOperand c -> switch (new PredicateEvaluator(this).truth(c.predicate(), row)) {
                case TRUE    -> BooleanValue.of(true);
                case FALSE   -> BooleanValue.of(false);
                case UNKNOWN -> NullValue.INSTANCE;
            };
        };
    }

    /** Builds a {@link StructValue} from a {@code { name: expr, … }} construction. */
    private StructValue evaluateStruct(StructConstruction struct, Row row) {
        Map<String, Value> fields = new java.util.LinkedHashMap<>();
        for (StructConstruction.Field field : struct.fields()) {
            fields.put(field.name(), evaluate(field.value(), row));
        }
        return new StructValue(fields);
    }

    /** Builds an {@link ArrayValue} from a {@code [ expr, … ]} construction. */
    private ArrayValue evaluateArray(ArrayConstruction array, Row row) {
        List<Value> elements = new java.util.ArrayList<>(array.elements().size());
        for (Operand element : array.elements()) {
            elements.add(evaluate(element, row));
        }
        return new ArrayValue(elements);
    }

    // ── private evaluation helpers ──────────────────────────────────────────

    private Value evaluateAttribute(AttributeOperand attr, Row row) {
        // Pass the full (possibly qualified) name to the row: a plain row strips the
        // relation qualifier, while a join-condition row (QualifiedRow) uses it to
        // resolve attributes whose bare name is ambiguous across the two join inputs.
        try {
            return row.get(attr.name());
        } catch (IllegalArgumentException e) {
            throw new EvaluationException("Unknown column '" + attr.name() + "' in row", e);
        }
    }

    private Value evaluateUnary(UnaryOperand unary, Row row) {
        Value v = evaluate(unary.operand(), row);
        if (v.isNull()) return NullValue.INSTANCE;
        // Unary minus negates a DURATION span (ADR-0013); otherwise requires NUMBER.
        if (v instanceof DurationValue d) {
            return TemporalValueArithmetic.unaryMinus(d);
        }
        if (!(v instanceof NumberValue nv)) {
            throw new EvaluationException(
                    "Unary negation requires a NUMBER or DURATION value, got " + v.type());
        }
        return new NumberValue(nv.value().negate());
    }

    private Value evaluateBinary(BinaryArithmeticExpression expr, Row row) {
        Value left  = evaluate(expr.left(),  row);
        Value right = evaluate(expr.right(), row);
        if (left.isNull() || right.isNull()) return NullValue.INSTANCE;
        // Temporal arithmetic (TIMESTAMP − TIMESTAMP → DURATION, ts ± DURATION, …, ADR-0013).
        if (TemporalValueArithmetic.involvesTemporal(left, right)) {
            return TemporalValueArithmetic.binary(left, expr.operator(), right);
        }
        return switch (expr.operator()) {
            case PLUS     -> add(left, right);
            case MINUS    -> subtract(left, right);
            case MULTIPLY -> multiply(left, right);
            case DIVIDE   -> divide(left, right);
        };
    }

    private Value add(Value left, Value right) {
        if (left instanceof StringValue ls && right instanceof StringValue rs) {
            return new StringValue(ls.value() + rs.value());
        }
        return new NumberValue(requireNumber(left, "+").add(requireNumber(right, "+")));
    }

    private Value subtract(Value left, Value right) {
        return new NumberValue(requireNumber(left, "-").subtract(requireNumber(right, "-")));
    }

    private Value multiply(Value left, Value right) {
        return new NumberValue(requireNumber(left, "*").multiply(requireNumber(right, "*")));
    }

    private Value divide(Value left, Value right) {
        BigDecimal divisor = requireNumber(right, "/");
        if (divisor.compareTo(BigDecimal.ZERO) == 0) {
            throw new EvaluationException("Division by zero");
        }
        return new NumberValue(requireNumber(left, "/")
                .divide(divisor, 10, RoundingMode.HALF_UP)
                .stripTrailingZeros());
    }

    private Value evaluateFunction(FunctionCall call, Row row) {
        // 1. A library function, whichever library supplied it
        Optional<ScalarFunction> found = functions.scalar(call.functionName());
        if (found.isPresent()) {
            return invoke(found.get(), call, row);
        }

        // 2. Fall back to user-defined functions in the symbol table
        if (symbolTable != null) {
            List<FunctionSymbol> overloads = symbolTable.lookupFunction(call.functionName());
            for (FunctionSymbol sym : overloads) {
                if (sym instanceof ScalarFunctionSymbol sfs
                        && sfs.body().isPresent()
                        && sfs.parameters().size() == call.arguments().size()) {
                    return evaluateUserFunction(sfs, call.arguments(), row);
                }
            }
        }

        throw new EvaluationException("Unknown function: " + call.functionName()
                + missingLibraryHint());
    }

    /**
     * Invokes a library function, checking the declared arity first so no
     * implementation has to count its own arguments.
     *
     * <p>The two arms are the whole of the dispatch, and the compiler checks there are
     * no others: a strict function is handed evaluated values, a lazy one deferred
     * {@link Argument}s that evaluate at most once and only if asked for. That
     * deferral is what makes a conditional usable as a guard — in
     * {@code IIf(IsNumeric(raw), CDbl(raw), 0)} the conversion never runs on the rows
     * it excludes.
     */
    private Value invoke(ScalarFunction function, FunctionCall call, Row row) {
        List<Operand> args = call.arguments();
        var arity = function.signature().arity();
        if (!arity.accepts(args.size())) {
            throw new EvaluationException(function.name() + ": expected "
                    + arity.describe() + " argument(s), got " + args.size());
        }
        try {
            return switch (function) {
                case StrictScalarFunction strict -> strict.invoke(context,
                        args.stream().map(arg -> evaluate(arg, row)).toList());
                case LazyScalarFunction lazy -> lazy.invoke(context, args.stream()
                        .map(arg -> Argument.memoizing(() -> evaluate(arg, row)))
                        .toList());
            };
        } catch (EvaluationException e) {
            throw e;
        } catch (RuntimeException e) {
            // A function must never leak a raw Java exception to the user: a bad
            // value in the data is an ordinary condition, and a stack trace is not
            // a diagnostic.
            throw new EvaluationException(call.functionName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * The extra sentence an unknown name gets when there is nothing to have found it
     * in. An empty catalogue means no function library was discovered, which in a
     * repackaged or shaded jar is far more likely than a genuine typo — and reads
     * identically to one without this.
     */
    private String missingLibraryHint() {
        return functions.isEmpty()
                ? " (no function library is installed, so no built-in resolves — check"
                        + " that a FunctionLibrary provider is on the module path)"
                : "";
    }

    /**
     * Evaluates a user-defined function by binding its parameters to the evaluated
     * argument values and recursively evaluating the body expression.
     *
     * @param fn       the user-defined function symbol; must have a non-empty body
     * @param argExprs the call-site argument expressions, in parameter order
     * @param callerRow the row from the call site used to evaluate arguments
     * @return the result of the function body
     */
    private Value evaluateUserFunction(ScalarFunctionSymbol fn,
                                       List<Operand> argExprs,
                                       Row callerRow) {
        // Evaluate each argument expression in the caller's row context
        List<Value> argValues = argExprs.stream()
                .map(arg -> evaluate(arg, callerRow))
                .toList();

        // Zero-parameter functions: evaluate body with caller's row context
        // (the body can only reference literals or other functions — no column refs).
        if (fn.parameters().isEmpty()) {
            return evaluate(fn.body().orElseThrow(), callerRow);
        }

        // Build a parameter-binding row: column names = parameter names
        List<ColumnDefinition> paramCols = fn.parameters().stream()
                .map(p -> new ColumnDefinition(p.name(), p.type()))
                .toList();
        Schema paramSchema = new Schema(paramCols);
        Row paramRow = ArrayRow.of(paramSchema, argValues);

        // Evaluate the body expression with the parameter row
        return evaluate(fn.body().orElseThrow(), paramRow);
    }

    // ── arithmetic type helpers ─────────────────────────────────────────────

    private static BigDecimal requireNumber(Value v, String context) {
        if (!(v instanceof NumberValue nv)) {
            throw new EvaluationException(
                    "Operator '" + context + "' requires NUMBER, got " + v.type());
        }
        return nv.value();
    }
}

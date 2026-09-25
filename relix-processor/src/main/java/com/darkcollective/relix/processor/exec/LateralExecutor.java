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
package com.darkcollective.relix.processor.exec;

import com.darkcollective.relix.ast.BooleanOperand;
import com.darkcollective.relix.ast.DateOperand;
import com.darkcollective.relix.ast.DurationOperand;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.ast.TimeOperand;
import com.darkcollective.relix.ast.TimestampOperand;
import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.darkcollective.relix.processor.exec.ExecSupport.concatRows;

/**
 * Executes {@link PhysicalNode.LateralJoin} — a correlated / lateral TVF join
 * that invokes a table-valued function once per outer row, substituting
 * per-row argument values.
 *
 * <p>For each left row:
 * <ol>
 *   <li>Evaluate each argument expression against the left row, producing a
 *       runtime {@link Value}.</li>
 *   <li>Lift each {@code Value} to an AST {@link Operand} literal so the
 *       function body's parameter references can be substituted.</li>
 *   <li>Ask the {@code bodyBuilder} lambda (built by the planner, which has
 *       package-private access to {@code RelationFunctionInliner}) to bind
 *       those literals and plan a fresh body for this invocation.</li>
 *   <li>Execute the planned body against the same {@link EvalCtx}.</li>
 *   <li>Concatenate each TVF output row with the left row.</li>
 * </ol>
 *
 * <h2>Memoization</h2>
 * <p>Steps 3 and 4 are the expensive ones — a {@code bodyBuilder} call re-runs schema
 * inference and <em>the whole planner</em> — and both depend on nothing but the argument
 * tuple, which repeats whenever the correlated column does. A {@link LateralMemo}
 * therefore caches the planned body, and where it fits in the budget the body's rows,
 * per argument tuple. The memo is created per invocation of
 * {@link #executeLateral(PhysicalNode.LateralJoin, EvalCtx)}: it lives in the returned
 * stream's closure and is collected with it, and it is bounded in both entries and
 * retained rows so a high-cardinality correlation degrades to the un-memoized path
 * rather than growing.
 *
 * <p><b>A volatile function body is not detected.</b> Two left rows with the same
 * argument tuple share one execution, so a body reading {@code Rand()} or {@code NOW()}
 * yields one draw for all of them rather than one per row. This is the same latitude the
 * optimizer already takes with a volatile predicate when it pushes a {@code σ} across a
 * join, and it is not something this layer could tighten in any case: the planner hands
 * the executor a {@code bodyBuilder} lambda, not a body it could inspect.
 * {@code LATERAL-001} decorrelation makes the same trade at plan time.
 *
 * <p>A {@code NULL} or nested (struct/array) argument value has no AST literal
 * equivalent and throws an {@link EvaluationException} at runtime.
 */
final class LateralExecutor {

    private final ChildDispatch dispatch;

    LateralExecutor(ChildDispatch dispatch) {
        this.dispatch = dispatch;
    }

    Stream<Row> executeLateral(PhysicalNode.LateralJoin node, EvalCtx ctx) {
        LateralMemo memo = new LateralMemo(node, dispatch, ctx);
        return dispatch.execute(node.left(), ctx).flatMap(leftRow -> {
            // Evaluate each argument expression against the current left row.
            List<Operand> argLiterals = new ArrayList<>(node.arguments().size());
            for (Operand arg : node.arguments()) {
                Value val = ctx.operandEval().evaluate(arg, leftRow);
                argLiterals.add(valueToLiteral(val));
            }
            // Plan and execute the body for this argument tuple — reusing either from the
            // memo when an earlier left row already asked for the same tuple.
            return memo.rowsFor(List.copyOf(argLiterals))
                    .map(tvfRow -> concatRows(leftRow, tvfRow, node.schema()));
        });
    }

    /**
     * Lifts a runtime {@link Value} to a literal AST {@link Operand}.  Only
     * scalar non-null values are representable; struct/array/null values throw.
     */
    static Operand valueToLiteral(Value value) {
        return switch (value) {
            case NumberValue    n -> new NumberOperand(n.asDisplayString());
            case StringValue    s -> new StringOperand(s.value());
            case BooleanValue   b -> new BooleanOperand(b.value());
            case DateValue      d -> new DateOperand(d.value());
            case TimeValue      t -> new TimeOperand(t.value());
            case TimestampValue t -> new TimestampOperand(t.value());
            case DurationValue  d -> new DurationOperand(d.value());
            default -> throw new EvaluationException(
                    "LATERAL join argument evaluated to "
                    + value.getClass().getSimpleName()
                    + "; only scalar non-null values are supported as lateral arguments");
        };
    }
}

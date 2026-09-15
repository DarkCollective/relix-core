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

import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.SortDirection;
import com.darkcollective.relix.ast.SortSpecification;
import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.processor.ArrayRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.eval.OperandEvaluator;
import com.darkcollective.relix.processor.eval.ValueComparator;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static com.darkcollective.relix.processor.exec.ExecSupport.rowComparator;

/**
 * Executes the gap-and-island / sessionization operator (SESSIONIZE): a
 * non-collapsing per-partition computation that appends a 1-based session-id column
 * to every input row.
 *
 * <p>The input is partitioned by the partition keys ({@link IndexedRelation}); each
 * partition is sorted ascending by the order column, then scanned once.  The first
 * row of every partition is session {@code 1}; a new session begins whenever the gap
 * between a row's order value and its predecessor's exceeds the threshold:
 *
 * <pre>{@code orderᵢ − orderᵢ₋₁ > threshold}</pre>
 *
 * The boundary is decided by evaluating {@code previousOrder + threshold} (typed
 * temporal/numeric arithmetic via {@link OperandEvaluator}) and comparing the
 * current order value against it — so {@code TIMESTAMP}/{@code NUMBER} order columns
 * and their {@code DURATION}/{@code NUMBER} thresholds are handled uniformly. A row
 * with a {@code NULL} order value (or a {@code NULL} predecessor) never opens a new
 * session — it stays in the running one.
 */
final class SessionizeExecutor {

    private final ChildDispatch dispatch;

    SessionizeExecutor(ChildDispatch dispatch) {
        this.dispatch = dispatch;
    }

    Stream<Row> executeSessionize(PhysicalNode.Sessionize node, EvalCtx ctx) {
        List<String> partitionKeys = node.partitionKeys();
        Schema inputSchema = node.input().schema();
        Schema outputSchema = node.schema();
        OperandEvaluator eval = ctx.operandEval();

        Comparator<Row> comparator = rowComparator(
                List.of(new SortSpecification(node.orderColumn(), SortDirection.ASC)), eval);

        // A single-column synthetic schema/row lets us reuse the typed arithmetic in
        // OperandEvaluator to compute `previousOrder + threshold` per pair.
        Schema gapSchema = new Schema(List.of(
                new ColumnDefinition(node.orderColumn(), ScalarType.ANY)));
        Operand boundaryExpr = new BinaryArithmeticExpression(
                new AttributeOperand(node.orderColumn()), ArithmeticOperator.PLUS, node.threshold());

        IndexedRelation indexed;
        try (Stream<Row> input = dispatch.buffering(node.input(), ctx, node)) {
            indexed = IndexedRelation.build(inputSchema, input,
                    row -> partitionKeys.stream().map(row::get).toList());
        }

        List<Row> output = new ArrayList<>();
        for (var entry : indexed.groups().entrySet()) {
            List<Row> partition = new ArrayList<>(entry.getValue());
            partition.sort(comparator);
            assignSessions(node, partition, inputSchema, outputSchema, gapSchema, boundaryExpr, eval, output);
        }
        return BagRelation.of(outputSchema, output).stream();
    }

    /** Walks a sorted partition, appending the session id to each row of {@code output}. */
    private static void assignSessions(PhysicalNode.Sessionize node, List<Row> partition,
                                       Schema inputSchema, Schema outputSchema, Schema gapSchema,
                                       Operand boundaryExpr, OperandEvaluator eval, List<Row> output) {
        long session = 1;
        Value previousOrder = null;
        for (Row row : partition) {
            Value current = row.get(node.orderColumn());
            if (previousOrder != null
                    && opensNewSession(previousOrder, current, gapSchema, boundaryExpr, eval)) {
                session++;
            }
            output.add(ExecSupport.appendColumn(outputSchema, inputSchema, row,
                    node.sessionColumn(), new NumberValue(BigDecimal.valueOf(session))));
            previousOrder = current;
        }
    }

    /**
     * Whether {@code current} sits more than the threshold past {@code previous} — the
     * gap {@code current − previous > threshold}, tested as {@code current >
     * previous + threshold}.  A {@code NULL} on either side keeps the running session.
     */
    private static boolean opensNewSession(Value previous, Value current, Schema gapSchema,
                                           Operand boundaryExpr, OperandEvaluator eval) {
        if (previous == null || previous.isNull() || current == null || current.isNull()) {
            return false;
        }
        Row gapRow = ArrayRow.of(gapSchema, List.of(previous));
        Value boundary = eval.evaluate(boundaryExpr, gapRow);
        if (boundary.isNull()) {
            return false;
        }
        return ValueComparator.compareNonNull(current, boundary) > 0;
    }

}

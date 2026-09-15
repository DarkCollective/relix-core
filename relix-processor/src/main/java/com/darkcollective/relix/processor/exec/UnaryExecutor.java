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

import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.processor.ArrayRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Executes the streaming unary operators — projection, rename, limit, and distinct.
 *
 * <p>Holds a {@link ChildDispatch} to run input sub-plans.
 */
final class UnaryExecutor {

    private final ChildDispatch dispatch;

    UnaryExecutor(ChildDispatch dispatch) {
        this.dispatch = dispatch;
    }

    Stream<Row> executeProject(PhysicalNode.Project node, EvalCtx ctx) {
        Schema outputSchema = node.schema();
        var attrs = node.attributes();
        return dispatch.execute(node.input(), ctx).map(row -> {
            List<Value> values = new ArrayList<>(attrs.size());
            for (var attr : attrs) {
                values.add(ctx.operandEval().evaluate(attr.expression(), row));
            }
            return ArrayRow.of(outputSchema, values);
        });
    }

    Stream<Row> executeRename(PhysicalNode.Rename node, EvalCtx ctx) {
        Schema outputSchema = node.schema();
        return dispatch.execute(node.input(), ctx).map(row ->
                row.schema().equals(outputSchema) ? row : reschema(row, outputSchema));
    }

    /** Re-labels {@code row} with {@code newSchema} (same width), sharing values for an {@link ArrayRow}. */
    private static Row reschema(Row row, Schema newSchema) {
        if (row instanceof ArrayRow arrayRow) {
            return arrayRow.withSchema(newSchema);
        }
        List<Value> values = new ArrayList<>(row.width());
        for (int i = 0; i < row.width(); i++) values.add(row.get(i));
        return ArrayRow.of(newSchema, values);
    }

    Stream<Row> executeLimit(PhysicalNode.Limit node, EvalCtx ctx) {
        Stream<Row> input = dispatch.execute(node.input(), ctx);
        if (node.offset().isPresent()) {
            input = input.skip(node.offset().get());
        }
        return input.limit(node.count());
    }

    /**
     * Distinct (δ).  When the plan marks it streaming (ADR-0009, Phase C3) the input
     * delivers an ordering covering the whole row, so equal rows are contiguous and a
     * single linear pass — keeping a row only when it differs from the one before it —
     * deduplicates without buffering a hash set, and the output streams.  Otherwise it
     * falls back to the hash-based {@link Stream#distinct()}.
     *
     * <p>Only the hash variant is metered against the row budget, and only that one is a
     * blocking operator: {@code Stream.distinct()} keeps every distinct row it has seen,
     * so its buffer is bounded by the input, while the streaming variant holds one row.
     */
    Stream<Row> executeDistinct(PhysicalNode.Distinct node, EvalCtx ctx) {
        return node.streaming()
                ? dispatch.execute(node.input(), ctx).filter(new AdjacentDistinct(node.schema()))
                : dispatch.buffering(node.input(), ctx, node)
                        .filter(ExecSupport.distinctByIdentity(node.schema()));
    }

    /**
     * Stateful row predicate for a streaming {@code δ}: keeps a row iff its
     * {@link ExecSupport#identityKey} differs from the previous row's. Correct only when
     * equal rows are adjacent — guaranteed by the planner's whole-row ordering gate, and
     * the reason the key is the right comparison rather than {@link Row#equals}: that
     * ordering is established by {@code ValueComparator}, so rows this key calls equal
     * are exactly the ones the sort placed together.
     */
    private static final class AdjacentDistinct implements java.util.function.Predicate<Row> {
        private final Schema schema;
        private List<Value> previous;

        AdjacentDistinct(Schema schema) {
            this.schema = schema;
        }

        @Override public boolean test(Row row) {
            List<Value> key = ExecSupport.identityKey(row, schema);
            boolean keep = previous == null || !key.equals(previous);
            previous = key;
            return keep;
        }
    }
}

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

import com.darkcollective.relix.events.QueryEventListener;
import com.darkcollective.relix.processor.internal.DataSourceConnector;
import com.darkcollective.relix.processor.internal.ExecutionContext;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.eval.OperandEvaluator;
import com.darkcollective.relix.processor.eval.PredicateEvaluator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-execution bundle: the connector, evaluators, event listener, the spool store,
 * and the recursion binding map (name &rarr; current-delta rows) for nested
 * {@code FIX} evaluation.  The binding map is immutable per context;
 * {@link #withBinding} produces a new {@code EvalCtx} that shadows the named
 * relation with new rows.
 *
 * <p>{@link #spools} and {@link #work} are the <em>mutable</em> members, and each is
 * deliberately the same object in every context {@link #withBinding} derives: a shared
 * sub-plan is filled once for a whole execution, fixpoint iterations included, and every
 * round is charged to the one work budget.
 *
 * <p>Shared (package-private) across the operator-group executors that
 * {@link PhysicalExecutor} delegates to.
 */
record EvalCtx(DataSourceConnector connector,
               OperandEvaluator operandEval,
               PredicateEvaluator predicateEval,
               QueryEventListener listener,
               Map<String, List<Row>> recursionBindings,
               SpoolCache spools,
               int maxFixpointRounds,
               int maxMaterializedRows,
               WorkBudget work,
               ExecutionContext executionContext) {

    static EvalCtx from(ExecutionContext ctx) {
        OperandEvaluator eval = new OperandEvaluator(
                ctx.symbolTable(), ctx.functions(), ctx.functionContext());
        return new EvalCtx(ctx.connector(), eval, new PredicateEvaluator(eval),
                ctx.listener(), Map.of(), new SpoolCache(), ctx.maxFixpointRounds(),
                ctx.maxMaterializedRows(), WorkBudget.startingNow(ctx), ctx);
    }

    /** Returns a new context with {@code name} bound to {@code rows} (shadowing any prior binding). */
    EvalCtx withBinding(String name, List<Row> rows) {
        Map<String, List<Row>> copy = new HashMap<>(recursionBindings);
        copy.put(name, rows);
        return new EvalCtx(connector, operandEval, predicateEval, listener, copy,
                spools, maxFixpointRounds, maxMaterializedRows, work, executionContext);
    }
}

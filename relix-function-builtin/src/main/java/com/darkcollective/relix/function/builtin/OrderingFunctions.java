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
package com.darkcollective.relix.function.builtin;

import com.darkcollective.relix.function.Arity;
import com.darkcollective.relix.function.FunctionContext;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.Value;

import java.util.Comparator;
import java.util.List;

import static com.darkcollective.relix.function.builtin.Category.PURE_DETERMINISTIC;
import static com.darkcollective.relix.function.builtin.Category.p;
import static com.darkcollective.relix.symbol.ScalarType.ANY;

/**
 * The row-wise ordering built-ins: {@code LEAST} and {@code GREATEST}, the smallest and
 * largest of their arguments.
 *
 * <p>They are the scalar, across-a-row counterpart of the aggregate {@code MIN}/{@code MAX},
 * which run down a column inside a {@code γ}. Both are variadic (two arguments or more) and
 * work on any type the engine orders — NUMBER, STRING, and the temporal types — because
 * they rank with the engine's own {@linkplain FunctionContext#valueOrder() order} rather
 * than inventing one, exactly as {@code MIN}/{@code MAX} do. Two arguments of kinds that
 * cannot be ranked (a number against a date) are an error on the row, which is what the
 * order itself reports.
 *
 * <p>Their result type follows their arguments: all-NUMBER is NUMBER, and a mix the row
 * decides is {@code ANY} — {@link Category#widest the type they share, or none}.
 *
 * <h2>NULL is propagated, not skipped</h2>
 * Any NULL argument makes the result NULL. This matches the rest of the engine, where a
 * comparison involving NULL is never true, so there is no basis for calling a NULL smaller
 * or larger than a value — the comparison that would decide simply has no answer.
 *
 * <h2>Which of these a backend is offered: the ones that propagate NULL too</h2>
 * A <em>spelling must compute the same value</em>, and native SQL {@code LEAST}/{@code GREATEST}
 * do not agree on NULL across dialects: MySQL and Db2 propagate a NULL argument exactly as
 * relix does, while PostgreSQL, SQL Server and DuckDB ignore it (NULL only when every
 * argument is NULL), and SQLite has no such function at all. So the spelling is offered on
 * the propagating dialects alone — the rest evaluate it in the engine, where the result is
 * the same. The allow-list is a claim that each named backend was confirmed to agree, which
 * the per-dialect agreement suite ({@code MySqlPushdownAgreementTest},
 * {@code Db2PushdownAgreementTest}) checks against the running database over a NULL-bearing
 * case; a backend that diverged would turn it red rather than ship a wrong answer.
 */
final class OrderingFunctions {

    private static final Category ORDERING = Category.of("ordering");

    private OrderingFunctions() {
    }

    static List<ScalarFunction> all() {
        return List.of(
                ORDERING.contextual("LEAST", ANY, PURE_DETERMINISTIC,
                        List.of(p("a", ANY), p("b", ANY)), Arity.atLeast(2),
                        Category::widest, Spellings.sqlOn("LEAST", Spellings.MYSQL, Spellings.DB2),
                        (context, arguments) -> extreme(context, arguments, true)),

                ORDERING.contextual("GREATEST", ANY, PURE_DETERMINISTIC,
                        List.of(p("a", ANY), p("b", ANY)), Arity.atLeast(2),
                        Category::widest, Spellings.sqlOn("GREATEST", Spellings.MYSQL, Spellings.DB2),
                        (context, arguments) -> extreme(context, arguments, false)));
    }

    /** The smallest or largest argument by the engine's order; NULL if any argument is NULL. */
    private static Value extreme(FunctionContext context, List<Value> arguments, boolean smallest) {
        for (Value argument : arguments) {
            if (argument.isNull()) {
                return NullValue.INSTANCE;
            }
        }
        Comparator<Value> order = context.valueOrder();
        Value best = arguments.get(0);
        for (Value candidate : arguments.subList(1, arguments.size())) {
            int comparison = order.compare(candidate, best);
            if (smallest ? comparison < 0 : comparison > 0) {
                best = candidate;
            }
        }
        return best;
    }
}

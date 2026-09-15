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
package com.darkcollective.relix.plan;

import com.darkcollective.relix.ast.BooleanOperand;
import com.darkcollective.relix.ast.DateOperand;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.ast.TimeOperand;
import com.darkcollective.relix.ast.TimestampOperand;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.function.FunctionContext;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.function.StrictScalarFunction;
import com.darkcollective.relix.symbol.FunctionProperty;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;

import java.util.List;
import java.util.Optional;

/**
 * Turns a call the engine may not hand to a backend into a value it may — by evaluating
 * it here, once, and letting the result travel as a literal.
 *
 * <h2>The problem this solves</h2>
 * {@code σ at &lt; NOW() (db.events)} cannot fold: the database would answer from its own
 * clock rather than the one this session was given, so the same query would return
 * different rows depending on whether the planner folded it. Refusing the fold keeps the
 * answer right and reads the whole table to do it.
 *
 * <p>There is a third option, and it is the one a database itself takes: evaluate the
 * clock <em>once</em> and compare against the value. A backend has no opinion about a
 * timestamp literal, so the predicate folds like any other, and the instant in it is the
 * session's.
 *
 * <h2>What makes it sound</h2>
 * That the call has one value for the whole run — {@link FunctionProperty#STABLE}. It is
 * a claim about the function and an obligation on the engine, and both halves have to
 * hold: the function promises not to change its answer mid-query, and the run reads its
 * clock once and pins it (see {@code ExecutionContext}), so the value substituted here is
 * the same value the unfolded half of the same query computes.
 *
 * <p>Nothing is named. A third-party function declaring itself stable is substituted the
 * day it is installed, and one that declares nothing is per-row volatile and is refused —
 * which is the safe default and why {@code Rand} needs no mention.
 *
 * <h2>Why only a call with no arguments</h2>
 * Because evaluating one with arguments needs their values, and a plan has none: an
 * argument is an expression over a row that does not exist yet. A call over constant
 * arguments could in principle be folded, but that is constant folding and the optimizer
 * already does it — by the time a tree reaches here, an argument that could have been
 * reduced to a literal has been.
 */
final class StableCalls {

    private StableCalls() {
    }

    /**
     * The literal a stable call evaluates to, or empty when the call is not one the
     * engine may evaluate ahead of the rows.
     *
     * <p>Returning an {@link Operand} rather than rendered text is deliberate: every
     * renderer already knows how to write a literal in its own syntax — a SQL dialect's
     * {@code TIMESTAMP '…'}, MongoDB's <code>&#123;"$date": …&#125;</code> — so the
     * substitution costs neither of them a new arm, and the spelling stays where the
     * spellings are.
     *
     * @param call      the call being rendered
     * @param functions the catalogue the call resolves in
     * @param context   the run's ambient state, holding its pinned clock; null when the
     *                  planner was not given one, in which case nothing is substituted
     * @return the value as a literal operand, or empty to leave the call alone
     */
    static Optional<Operand> substitute(FunctionCall call, FunctionCatalog functions,
                                        FunctionContext context) {
        if (context == null || !call.arguments().isEmpty()) {
            return Optional.empty();
        }
        Optional<ScalarFunction> resolved = functions.scalar(call.functionName());
        if (resolved.isEmpty()
                || !resolved.get().signature().has(FunctionProperty.STABLE)
                // A strict function is one the engine can simply invoke. A lazy one
                // decides which of its arguments to evaluate, and with no arguments there
                // is nothing for it to decide — so this excludes nothing real, and avoids
                // asking a form that is not built to be called this way.
                || !(resolved.get() instanceof StrictScalarFunction strict)) {
            return Optional.empty();
        }
        return literal(strict.invoke(context, List.of()));
    }

    /**
     * A value as the literal operand that denotes it, or empty for a value no literal
     * can express.
     *
     * <p>NULL is among those, and deliberately: SQL's {@code NULL} is a keyword rather
     * than a literal of a type, and a comparison against it means something different
     * from a comparison against a value. A stable function answering NULL therefore
     * leaves its call in the engine, where the three-valued rules are the engine's own.
     * So are the nested kinds, which have no scalar literal at all.
     */
    private static Optional<Operand> literal(Value value) {
        return Optional.ofNullable(switch (value) {
            case TimestampValue v -> new TimestampOperand(v.value(), SourceLocation.UNKNOWN);
            case DateValue v -> new DateOperand(v.value(), SourceLocation.UNKNOWN);
            case TimeValue v -> new TimeOperand(v.value(), SourceLocation.UNKNOWN);
            case NumberValue v -> new NumberOperand(v.value().toPlainString(), SourceLocation.UNKNOWN);
            case StringValue v -> new StringOperand(v.value(), SourceLocation.UNKNOWN);
            case BooleanValue v -> new BooleanOperand(v.value(), SourceLocation.UNKNOWN);
            default -> null;
        });
    }
}

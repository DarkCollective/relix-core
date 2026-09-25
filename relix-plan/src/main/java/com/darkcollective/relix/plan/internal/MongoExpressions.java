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
package com.darkcollective.relix.plan.internal;

import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BooleanOperand;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.ElementOfPredicate;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.internal.LikePatterns;
import com.darkcollective.relix.ast.NotPredicate;
import com.darkcollective.relix.ast.NullPredicate;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.OrPredicate;
import com.darkcollective.relix.ast.PatternPredicate;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.SetLiteralOperand;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.ast.TimestampOperand;
import com.darkcollective.relix.ast.UnaryOperand;
import com.darkcollective.relix.function.PushdownTarget;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.json.JsonStrings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Translates relix {@link Predicate}s and {@link Operand}s into MongoDB query/expression
 * documents (rendered as extended-JSON text) for aggregation-pipeline pushdown — the
 * document analogue of {@link SqlExpressions} (ADR-0011).
 *
 * <p>Like {@code SqlExpressions}, every method returns {@link Optional#empty()} when
 * the construct has no faithful form, which {@link MongoPushdownPlanner} treats as
 * "not pushable" and falls back to in-engine evaluation.
 *
 * <p><b>{@code $match} (predicate rendering)</b> — The supported shape is the
 * field-versus-literal query language: a comparison whose left side is a bare attribute
 * and right side is a literal renders to {@code {"field": {"$op": value}}};
 * {@code AND}/{@code OR}/{@code NOT} compose via {@code $and}/{@code $or}/{@code $nor};
 * {@code IS [NOT] NULL} and {@code IN}/{@code NOT IN} of a literal set are supported.
 * Anything else — comparing two fields, an arithmetic or function operand, a non-literal
 * set element — falls back (it would need a {@code $expr} aggregation expression, deferred
 * per ADR-0011).
 *
 * <p><b>{@code $project} (expression rendering)</b> — Scalar operands translate to
 * MongoDB aggregation expressions: field references become {@code "$field"}, literals
 * are JSON values, TIMESTAMP literals use extended-JSON {@code {"$date":"…Z"}}, and the
 * temporal extraction functions YEAR/MONTH/DAY/HOUR/MINUTE/SECOND render as
 * {@code {"$year":…}} etc.; {@code DATE_TRUNC(unit, ts)} renders as
 * {@code {"$dateTrunc":{"date":…,"unit":…}}}.  Anything else falls back.
 */
final class MongoExpressions {

    private MongoExpressions() {
    }

    /** Renders a predicate to a Mongo {@code $match} query document, or empty if unsupported. */
    static Optional<String> match(Predicate predicate) {
        return switch (predicate) {
            case ComparisonPredicate c -> comparison(c);
            case AndPredicate a -> combine("$and", a.left(), a.right());
            case OrPredicate o -> combine("$or", o.left(), o.right());
            // Deliberately not pushed. `$nor` keeps a document whose field is missing
            // or null — the row the engine's ¬UNKNOWN drops — and the null-guard that
            // would fix it needs every field the inner predicate reads, which a
            // general predicate does not hand over. The leaf negations below carry
            // their own guard because their one field is known. Declining costs a
            // slower plan; the σ is then evaluated in-engine, which is correct.
            case NotPredicate n -> Optional.empty();
            case NullPredicate n -> nullCheck(n);
            case ElementOfPredicate e -> elementOf(e);
            case PatternPredicate p -> patternLike(p);
        };
    }

    private static Optional<String> comparison(ComparisonPredicate c) {
        Optional<String> field = field(c.left());
        Optional<String> value = literal(c.right());
        if (field.isEmpty() || value.isEmpty()) {
            return Optional.empty();
        }
        String rendered = "{" + JsonStrings.quote(field.get()) + ": {"
                + JsonStrings.quote(mongoOp(c.operator())) + ": " + value.get() + "}}";
        // `$ne` matches a document that has no such field, where SQL's <> against a
        // NULL is UNKNOWN and drops the row. Every other comparison already fails to
        // match a missing field, so only this one needs the guard.
        return Optional.of(c.operator() == ComparisonOperator.NOT_EQUAL
                ? notNullGuarded(field.get(), rendered)
                : rendered);
    }

    /**
     * Wraps a negated match so that a missing-or-null field excludes the document,
     * which is what three-valued logic says: {@code ¬UNKNOWN} is UNKNOWN, and only a
     * TRUE keeps a row. In Mongo {@code {f: {$ne: null}}} excludes both a null value
     * and an absent field, which are the same thing to the engine reading it.
     */
    private static String notNullGuarded(String field, String rendered) {
        return "{\"$and\": [" + rendered + ", {" + JsonStrings.quote(field) + ": {\"$ne\": null}}]}";
    }

    private static Optional<String> combine(String op, Predicate left, Predicate right) {
        Optional<String> l = match(left);
        Optional<String> r = match(right);
        if (l.isEmpty() || r.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("{" + JsonStrings.quote(op) + ": [" + l.get() + ", " + r.get() + "]}");
    }

    private static Optional<String> nullCheck(NullPredicate n) {
        return field(n.operand()).map(f ->
                "{" + JsonStrings.quote(f) + ": {" + JsonStrings.quote(n.isNull() ? "$eq" : "$ne") + ": null}}");
    }

    private static Optional<String> elementOf(ElementOfPredicate e) {
        Optional<String> field = field(e.element());
        if (field.isEmpty() || !(e.setExpression() instanceof SetLiteralOperand set)) {
            return Optional.empty();
        }
        StringJoiner elements = new StringJoiner(", ", "[", "]");
        for (Operand element : set.elements()) {
            Optional<String> value = literal(element);
            if (value.isEmpty()) {
                return Optional.empty();
            }
            elements.add(value.get());
        }
        String rendered = "{" + JsonStrings.quote(field.get()) + ": {"
                + JsonStrings.quote(e.isNegated() ? "$nin" : "$in") + ": " + elements + "}}";
        return Optional.of(e.isNegated() ? notNullGuarded(field.get(), rendered) : rendered);
    }

    private static Optional<String> patternLike(PatternPredicate p) {
        Optional<String> f = field(p.operand());
        if (f.isEmpty() || !(p.pattern() instanceof StringOperand s)) {
            return Optional.empty();
        }
        String regex = LikePatterns.toRegex(s.value());
        if (p.negated()) {
            return Optional.of(notNullGuarded(f.get(),
                    "{" + JsonStrings.quote(f.get()) + ": {\"$not\": {\"$regex\": " + JsonStrings.quote(regex) + "}}}"));
        }
        return Optional.of("{" + JsonStrings.quote(f.get()) + ": {\"$regex\": " + JsonStrings.quote(regex) + "}}");
    }


    /** The bare field name for an attribute operand (qualifier dropped), or empty otherwise. */
    private static Optional<String> field(Operand operand) {
        return operand instanceof AttributeOperand a
                ? Optional.of(SqlExpressions.column(a.name()))
                : Optional.empty();
    }

    /** The JSON literal text for a scalar literal operand, or empty for anything else. */
    private static Optional<String> literal(Operand operand) {
        return switch (operand) {
            case NumberOperand n -> Optional.of(n.value());
            case StringOperand s -> Optional.of(JsonStrings.quote(s.value()));
            case BooleanOperand b -> Optional.of(b.value() ? "true" : "false");
            case UnaryOperand u when u.operand() instanceof NumberOperand n -> Optional.of("-" + n.value());
            // A TIMESTAMP literal is an instant → extended-JSON {"$date": "…Z"} (ADR-0013).
            // DATE/TIME/DURATION have no BSON literal here and fall back to in-engine.
            case TimestampOperand ts -> Optional.of("{\"$date\": " + JsonStrings.quote(ts.value().toString()) + "}");
            default -> Optional.empty();
        };
    }

    private static String mongoOp(ComparisonOperator op) {
        return switch (op) {
            case EQUAL -> "$eq";
            case NOT_EQUAL -> "$ne";
            case LESS -> "$lt";
            case LESS_EQUAL -> "$lte";
            case GREATER -> "$gt";
            case GREATER_EQUAL -> "$gte";
        };
    }

    /**
     * Renders an operand as a MongoDB aggregation expression for {@code $project}, or
     * empty when no faithful expression form exists.
     *
     * <ul>
     *   <li>Attribute references → {@code "$fieldName"} (field reference).</li>
     *   <li>Number/string/boolean literals → their JSON values.</li>
     *   <li>TIMESTAMP → {@code {"$date":"…Z"}} (extended JSON).</li>
     *   <li>{@code YEAR}…{@code SECOND} → {@code {"$year":…}} etc. (component extraction).</li>
     *   <li>{@code DATE_TRUNC(unit,ts)} →
     *       {@code {"$dateTrunc":{"date":…,"unit":…}}}.</li>
     * </ul>
     */
    static Optional<String> expression(Operand operand, PushdownFunctions functions) {
        return switch (operand) {
            case AttributeOperand a  -> Optional.of("\"$" + SqlExpressions.column(a.name()) + "\"");
            case NumberOperand n     -> Optional.of(n.value());
            case StringOperand s     -> Optional.of(JsonStrings.quote(s.value()));
            case BooleanOperand b    -> Optional.of(b.value() ? "true" : "false");
            case TimestampOperand ts -> Optional.of("{\"$date\": " + JsonStrings.quote(ts.value().toString()) + "}");
            case FunctionCall f      -> functionExpression(f, functions);
            default -> Optional.empty();
        };
    }

    /**
     * Renders a {@link FunctionCall} as an aggregation expression, or empty when the
     * function has no MongoDB spelling.
     *
     * <p>Identical in shape to the SQL side, and for the same reason: the planner knows
     * no function names.  It renders the arguments and asks the function how it is
     * written for this backend (ADR-0026), so a library that ships a Mongo spelling
     * folds into a pipeline without a change here.
     *
     * <p>Identical in its refusals too: a call {@link PushdownFolding#mayFold} declines
     * is never offered to the backend, whatever spelling it carries.
     */
    private static Optional<String> functionExpression(FunctionCall f, PushdownFunctions functions) {
        // A call constant for the run is evaluated here and written as the literal it
        // came to — see StableCalls, and SqlExpressions, which does the same thing with
        // the same operand and its own literal syntax.
        Optional<String> substituted = StableCalls.substitute(f, functions.catalog(), functions.context())
                .flatMap(literal -> expression(literal, functions));
        if (substituted.isPresent()) {
            return substituted;
        }
        Optional<ScalarFunction> function = functions.scalar(f.functionName());
        if (function.isEmpty() || !PushdownFolding.mayFold(function.get())) {
            return Optional.empty();
        }
        List<String> rendered = new ArrayList<>(f.arguments().size());
        for (Operand argument : f.arguments()) {
            Optional<String> json = expression(argument, functions);
            if (json.isEmpty()) {
                return Optional.empty();
            }
            rendered.add(json.get());
        }
        return function.get().pushdown().render(PushdownTarget.mongo(), rendered);
    }

}

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
package com.darkcollective.relix.ast.internal;

import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.ArrayConstruction;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.BooleanOperand;
import com.darkcollective.relix.ast.CollateOperand;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.ConditionOperand;
import com.darkcollective.relix.ast.DateOperand;
import com.darkcollective.relix.ast.DurationOperand;
import com.darkcollective.relix.ast.ElementOfPredicate;
import com.darkcollective.relix.ast.ExistsPredicate;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.LateralJoinNode;
import com.darkcollective.relix.ast.NotPredicate;
import com.darkcollective.relix.ast.NullOperand;
import com.darkcollective.relix.ast.NullPredicate;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.OrPredicate;
import com.darkcollective.relix.ast.ParameterOperand;
import com.darkcollective.relix.ast.PatternPredicate;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.QuantifiedPredicate;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.ScalarSubqueryOperand;
import com.darkcollective.relix.ast.SetLiteralOperand;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.ast.StructConstruction;
import com.darkcollective.relix.ast.TimeOperand;
import com.darkcollective.relix.ast.TimestampOperand;
import com.darkcollective.relix.ast.UnaryOperand;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The questions every layer asks of a relation that reads the row around it: the relation of
 * a {@link ScalarSubqueryOperand}, an {@link ExistsPredicate} or a {@link QuantifiedPredicate},
 * and the right side of an expression {@link LateralJoinNode}.
 *
 * <p><b>The rule.</b> Inside such a relation, a qualified name {@code R.c} whose relation
 * {@code R} is not in scope where the name is written is a column of the outer row: an
 * <em>outer reference</em>. An unqualified name is always the relation's own. Which names are
 * in scope at a place is {@link Qualifiers#inScope}'s answer for the inputs of the operator
 * holding the name, so {@code σ grp = T.grp (ρ U (T))} reads {@code T.grp} from outside: the
 * {@code ρ} has hidden {@code T}. Nested relations compose: a name a nested subquery reads
 * from outside is the enclosing relation's own when that relation has it in scope, and an
 * outer reference of the enclosing relation otherwise.
 *
 * <p>The rule is syntactic, so every layer applies it the same way without a schema. A dotted
 * name is a qualified reference when its part before the last dot is a relation in scope, and
 * a struct path otherwise; inside a subquery a dotted name that is neither is read from the
 * outer row.
 */
public final class Subqueries {

    private Subqueries() {
    }

    /** What a subquery is written as, which decides what its relation must be. */
    public enum Kind {
        /** {@code (relation)} as a value: one column, at most one row. */
        VALUE,
        /** {@code EXISTS (relation)}: any heading. */
        EXISTS,
        /** {@code x op ANY|ALL (relation)}, and {@code IN}: one column. */
        QUANTIFIED
    }

    /**
     * A subquery as written.
     *
     * @param query    its relation
     * @param kind     what it is written as
     * @param location where it is written
     */
    public record Subquery(RelNode query, Kind kind, SourceLocation location) {
    }

    /**
     * The outer references of {@code query}, each once (compared case-insensitively), in the
     * order they are first written.
     *
     * @param query the relation; must not be null
     * @return the references, as written; never null
     */
    public static List<AttributeOperand> outerReferences(RelNode query) {
        Objects.requireNonNull(query, "query");
        Map<String, AttributeOperand> found = new LinkedHashMap<>();
        collect(query, found);
        return List.copyOf(found.values());
    }

    /**
     * Whether {@code node} itself carries a subquery in one of its expressions. Its inputs
     * are not looked at, nor are the relations of the subqueries it carries.
     *
     * @param node the operator; must not be null
     * @return whether any expression of {@code node} holds a subquery
     */
    public static boolean carriesSubquery(RelNode node) {
        return !subqueriesOf(node).isEmpty();
    }

    /**
     * The subqueries {@code node} itself carries in its expressions, outermost first. Its
     * inputs are not looked at, nor are the relations of the subqueries found.
     *
     * @param node the operator; must not be null
     * @return the subqueries, in the order written; never null
     */
    public static List<Subquery> subqueriesOf(RelNode node) {
        List<Subquery> found = new ArrayList<>();
        RelNodeOperands.forEach(node,
                operand -> forEachSubquery(operand, found::add),
                predicate -> forEachSubquery(predicate, found::add));
        return found;
    }

    /**
     * Calls {@code onQuery} with each subquery written in {@code operand},
     * outermost first; the relations themselves are not searched.
     *
     * @param operand the expression; must not be null
     * @param onQuery called once per subquery; must not be null
     */
    public static void forEachSubquery(Operand operand, Consumer<Subquery> onQuery) {
        switch (operand) {
            case ScalarSubqueryOperand s -> onQuery.accept(new Subquery(s.query(), Kind.VALUE, s.location()));
            case FunctionCall f -> f.arguments().forEach(a -> forEachSubquery(a, onQuery));
            case BinaryArithmeticExpression e -> {
                forEachSubquery(e.left(), onQuery);
                forEachSubquery(e.right(), onQuery);
            }
            case UnaryOperand u -> forEachSubquery(u.operand(), onQuery);
            case ConditionOperand c -> forEachSubquery(c.predicate(), onQuery);
            case CollateOperand c -> forEachSubquery(c.operand(), onQuery);
            case SetLiteralOperand s -> s.elements().forEach(e -> forEachSubquery(e, onQuery));
            case StructConstruction s -> s.fields().forEach(f -> forEachSubquery(f.value(), onQuery));
            case ArrayConstruction a -> a.elements().forEach(e -> forEachSubquery(e, onQuery));
            case AttributeOperand ignored -> { }
            case ParameterOperand ignored -> { }
            case StringOperand ignored -> { }
            case NumberOperand ignored -> { }
            case BooleanOperand ignored -> { }
            case NullOperand ignored -> { }
            case DateOperand ignored -> { }
            case TimeOperand ignored -> { }
            case TimestampOperand ignored -> { }
            case DurationOperand ignored -> { }
        }
    }

    /**
     * Calls {@code onQuery} with each subquery written in {@code predicate},
     * outermost first; the relations themselves are not searched.
     *
     * @param predicate the condition; must not be null
     * @param onQuery   called once per subquery; must not be null
     */
    public static void forEachSubquery(Predicate predicate, Consumer<Subquery> onQuery) {
        switch (predicate) {
            case ExistsPredicate e -> onQuery.accept(new Subquery(e.query(), Kind.EXISTS, e.location()));
            case QuantifiedPredicate q -> {
                forEachSubquery(q.left(), onQuery);
                onQuery.accept(new Subquery(q.query(), Kind.QUANTIFIED, q.location()));
            }
            case ComparisonPredicate c -> {
                forEachSubquery(c.left(), onQuery);
                forEachSubquery(c.right(), onQuery);
            }
            case AndPredicate a -> {
                forEachSubquery(a.left(), onQuery);
                forEachSubquery(a.right(), onQuery);
            }
            case OrPredicate o -> {
                forEachSubquery(o.left(), onQuery);
                forEachSubquery(o.right(), onQuery);
            }
            case NotPredicate n -> forEachSubquery(n.predicate(), onQuery);
            case NullPredicate n -> forEachSubquery(n.operand(), onQuery);
            case ElementOfPredicate e -> {
                forEachSubquery(e.element(), onQuery);
                forEachSubquery(e.setExpression(), onQuery);
            }
            case PatternPredicate p -> {
                forEachSubquery(p.operand(), onQuery);
                forEachSubquery(p.pattern(), onQuery);
            }
        }
    }

    /**
     * {@code query} with each outer reference named in {@code values} replaced by its value,
     * in nested subqueries too. A name in {@code values} without a dot is a placeholder the
     * caller wrote into the relation, and is replaced wherever it appears.
     *
     * @param query  the relation; must not be null
     * @param values outer reference names, lowercased, to what replaces them; must not be null
     * @return the relation with the values in place; {@code query} itself when none applied
     */
    public static RelNode bind(RelNode query, Map<String, Operand> values) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(values, "values");
        return values.isEmpty() ? query : bindNode(query, values);
    }

    // ── outer references ──────────────────────────────────────────────────────

    private static void collect(RelNode node, Map<String, AttributeOperand> found) {
        if (node instanceof LateralJoinNode lateral) {
            // The right side reads the left row: what it reads from outside is the left's
            // when the left has that relation in scope.
            collect(lateral.left(), found);
            Set<String> left = Qualifiers.inScope(lateral.left());
            Map<String, AttributeOperand> right = new LinkedHashMap<>();
            collect(lateral.right(), right);
            right.forEach((key, attr) -> {
                if (!left.contains(qualifier(attr))) {
                    found.putIfAbsent(key, attr);
                }
            });
            return;
        }
        Set<String> inScope = inScopeOfInputs(node);
        Consumer<AttributeOperand> onAttribute = attr -> {
            String qualifier = qualifier(attr);
            if (qualifier != null && !inScope.contains(qualifier)) {
                found.putIfAbsent(attr.name().toLowerCase(Locale.ROOT), attr);
            }
        };
        RelNodeOperands.forEach(node,
                operand -> OperandWalker.walk(operand, onAttribute, unused -> { }),
                predicate -> OperandWalker.walk(predicate, onAttribute, unused -> { }));
        node.children().forEach(child -> collect(child, found));
    }

    private static Set<String> inScopeOfInputs(RelNode node) {
        Set<String> names = new HashSet<>();
        node.children().forEach(child -> names.addAll(Qualifiers.inScope(child)));
        return names;
    }

    private static String qualifier(AttributeOperand attr) {
        String qualifier = AttributeNames.qualifierOf(attr.name());
        return qualifier == null ? null : qualifier.toLowerCase(Locale.ROOT);
    }

    // ── binding ───────────────────────────────────────────────────────────────

    private static RelNode bindNode(RelNode node, Map<String, Operand> values) {
        if (node instanceof LateralJoinNode lateral) {
            // A name the left has in scope is the left row's, not the value bound here.
            RelNode left = bindNode(lateral.left(), values);
            RelNode right = bindNode(lateral.right(), without(values, Qualifiers.inScope(lateral.left())));
            return left == lateral.left() && right == lateral.right() ? lateral
                    : new LateralJoinNode(left, right, lateral.kind(), lateral.location());
        }
        Map<String, Operand> visible = without(values, inScopeOfInputs(node));
        RelNode rebuilt = RelNodeOperands.map(node,
                operand -> bindOperand(operand, visible),
                predicate -> bindPredicate(predicate, visible));
        return rebuilt.mapChildren(child -> bindNode(child, values));
    }

    /** {@code values} less the qualified names whose relation is in {@code inScope}. */
    private static Map<String, Operand> without(Map<String, Operand> values, Set<String> inScope) {
        if (inScope.isEmpty()) {
            return values;
        }
        Map<String, Operand> kept = new LinkedHashMap<>();
        values.forEach((name, value) -> {
            String qualifier = AttributeNames.qualifierOf(name);
            if (qualifier == null || !inScope.contains(qualifier)) {
                kept.put(name, value);
            }
        });
        return kept;
    }

    private static Operand bindOperand(Operand op, Map<String, Operand> b) {
        if (b.isEmpty()) {
            return op;
        }
        return switch (op) {
            case AttributeOperand a -> b.getOrDefault(a.name().toLowerCase(Locale.ROOT), a);
            case ScalarSubqueryOperand s -> {
                RelNode query = bindNode(s.query(), b);
                yield query == s.query() ? s : new ScalarSubqueryOperand(query, s.location());
            }
            case BinaryArithmeticExpression e -> new BinaryArithmeticExpression(
                    bindOperand(e.left(), b), e.operator(), bindOperand(e.right(), b), e.location());
            case UnaryOperand u -> new UnaryOperand(bindOperand(u.operand(), b), u.location());
            case FunctionCall f -> new FunctionCall(f.functionName(), bindAll(f.arguments(), b), f.location());
            case SetLiteralOperand s -> new SetLiteralOperand(bindAll(s.elements(), b), s.location());
            case StructConstruction sc -> new StructConstruction(sc.fields().stream()
                    .map(f -> new StructConstruction.Field(f.name(), bindOperand(f.value(), b)))
                    .toList(), sc.location());
            case ArrayConstruction ac -> new ArrayConstruction(bindAll(ac.elements(), b), ac.location());
            case ConditionOperand c -> new ConditionOperand(bindPredicate(c.predicate(), b), c.location());
            case CollateOperand c -> new CollateOperand(bindOperand(c.operand(), b), c.collation(), c.location());
            case ParameterOperand p -> p;
            case StringOperand s -> s;
            case NumberOperand n -> n;
            case BooleanOperand bo -> bo;
            case NullOperand n -> n;
            case DateOperand d -> d;
            case TimeOperand t -> t;
            case TimestampOperand t -> t;
            case DurationOperand d -> d;
        };
    }

    private static List<Operand> bindAll(List<Operand> operands, Map<String, Operand> b) {
        List<Operand> out = new ArrayList<>(operands.size());
        operands.forEach(o -> out.add(bindOperand(o, b)));
        return out;
    }

    private static Predicate bindPredicate(Predicate p, Map<String, Operand> b) {
        if (b.isEmpty()) {
            return p;
        }
        return switch (p) {
            case ComparisonPredicate c -> new ComparisonPredicate(
                    bindOperand(c.left(), b), c.operator(), bindOperand(c.right(), b), c.location());
            case AndPredicate a -> new AndPredicate(bindPredicate(a.left(), b), bindPredicate(a.right(), b), a.location());
            case OrPredicate o -> new OrPredicate(bindPredicate(o.left(), b), bindPredicate(o.right(), b), o.location());
            case NotPredicate n -> new NotPredicate(bindPredicate(n.predicate(), b), n.location());
            case NullPredicate n -> new NullPredicate(bindOperand(n.operand(), b), n.isNull(), n.location());
            case ElementOfPredicate e -> new ElementOfPredicate(
                    bindOperand(e.element(), b), bindOperand(e.setExpression(), b), e.isNegated(), e.location());
            case PatternPredicate pp -> new PatternPredicate(
                    bindOperand(pp.operand(), b), bindOperand(pp.pattern(), b), pp.negated(), pp.escape(), pp.location());
            case ExistsPredicate e -> new ExistsPredicate(bindNode(e.query(), b), e.location());
            case QuantifiedPredicate q -> new QuantifiedPredicate(bindOperand(q.left(), b), q.operator(),
                    q.quantifier(), bindNode(q.query(), b), q.location());
        };
    }
}

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

import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.ConditionalJoinNode;
import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregationNode;
import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.WindowFunction;
import com.darkcollective.relix.ast.SessionizeNode;
import com.darkcollective.relix.ast.WindowNode;
import com.darkcollective.relix.ast.AllenRelation;
import com.darkcollective.relix.ast.AsOfJoinNode;
import com.darkcollective.relix.ast.IntervalJoinNode;
import com.darkcollective.relix.ast.ArrayConstruction;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.BooleanOperand;
import com.darkcollective.relix.ast.ConditionOperand;
import com.darkcollective.relix.ast.DateOperand;
import com.darkcollective.relix.ast.TimeOperand;
import com.darkcollective.relix.ast.TimestampOperand;
import com.darkcollective.relix.ast.DurationOperand;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.ElementOfPredicate;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.NotPredicate;
import com.darkcollective.relix.ast.NullPredicate;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.OptimizeConstraint;
import com.darkcollective.relix.ast.OptimizeNode;
import com.darkcollective.relix.ast.OrPredicate;
import com.darkcollective.relix.ast.PatternPredicate;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationFunctionCall;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SetLiteralOperand;
import com.darkcollective.relix.ast.SolveNode;
import com.darkcollective.relix.ast.StructConstruction;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.ast.UnaryOperand;
import com.darkcollective.relix.ast.UniversalNode;
import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.function.RelationFunctionSymbol;

import java.util.HashMap;
import java.util.Set;
import java.util.IdentityHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Binds a {@link RelationFunctionCall} to the body of its {@link RelationFunctionSymbol}
 * by substitution: each parameter reference in the body (a bare {@link AttributeOperand}
 * whose name matches a parameter) is replaced by the corresponding call argument
 * expression, yielding the relational expression the call denotes.
 *
 * <p>Substitution rewrites every operand-bearing construct of the body (selection and
 * join predicates, projection and aggregate expressions, {@code SOLVE}/{@code OPTIMIZE}
 * operands, and the arguments of nested table-valued-function calls), recursing
 * structurally through the rest of the tree.  The substitution is purely syntactic —
 * this models a parameterized view — so call arguments are expected to be constant
 * expressions (the semantic validator enforces this).
 */
final class RelationFunctionInliner {

    private RelationFunctionInliner() {
    }

    /**
     * Returns {@code fn}'s body with each parameter bound to the corresponding
     * argument in {@code args} (positionally; surplus on either side is ignored — the
     * validator enforces matching arity earlier).
     *
     * @param fn   the table-valued function whose body to bind; must not be null
     * @param args the call arguments, in order; must not be null
     * @return the substituted (inlined) body expression
     */
    static RelNode bind(RelationFunctionSymbol fn, List<Operand> args) {
        return bindCall(fn, args).body();
    }

    /**
     * A bound call: the substituted body, and the relation leaves standing for its relation
     * arguments (by identity). Those leaves belong to the call site rather than to the
     * function — an argument may itself be a call to the same function (a chain
     * {@code step(Gen1)} where {@code Gen1 := { step(Start) }}) without that being
     * recursion — so the planner plans them in the call site's scope.
     *
     * @param body                the substituted body
     * @param arguments           the relation-argument leaves within it
     * @param recursiveReferences the arguments that name an enclosing binder, bound to it
     *                            as recursive references, with the binder's schema
     */
    record Bound(RelNode body, Set<RelNode> arguments, Map<RelNode, Schema> recursiveReferences) {
    }

    /**
     * As {@link #bind}, also reporting which leaves of the result are relation arguments.
     *
     * @param fn   the table-valued function whose body to bind; must not be null
     * @param args the call arguments, in order; must not be null
     * @return the bound body and its relation-argument leaves
     */
    static Bound bindCall(RelationFunctionSymbol fn, List<Operand> args) {
        return bindCall(fn, args, Map.of());
    }

    /**
     * As {@link #bindCall(RelationFunctionSymbol, List)}, inside the step of the binders in
     * {@code binders} (lower-cased name to schema): an argument naming one of them is the
     * relation that binder is building, and is bound as a recursive reference to it.
     *
     * @param fn      the table-valued function whose body to bind; must not be null
     * @param args    the call arguments, in order; must not be null
     * @param binders the {@code ITERATE} binders in scope, by lower-cased name
     * @return the bound body, its relation-argument leaves and its recursive references
     */
    static Bound bindCall(RelationFunctionSymbol fn, List<Operand> args, Map<String, Schema> binders) {
        Set<RelNode> arguments = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<RelNode, Schema> references = new IdentityHashMap<>();
        Map<String, Operand> binding = new HashMap<>();
        Map<String, RelNode> relations = new HashMap<>();
        Map<String, Operand> relationNames = new HashMap<>();
        List<ParameterDefinition> params = fn.parameters();
        for (int i = 0; i < params.size() && i < args.size(); i++) {
            ParameterDefinition p = params.get(i);
            String key = p.name().toLowerCase(Locale.ROOT);
            if (p.isRelation()) {
                // The validator admits only a relation's name here.
                AttributeOperand name = (AttributeOperand) args.get(i);
                relations.put(key, narrowed(p, name.name(), arguments, binders, references));
                relationNames.put(key, name);
            } else {
                binding.put(key, args.get(i));
            }
        }
        // Scalars first: the relation pass inserts projections whose column references
        // must not be taken for scalar parameters of the same name.
        RelNode scalarsBound = substitute(fn.body(), binding);
        return new Bound(relations.isEmpty() ? scalarsBound
                : substituteRelations(scalarsBound, relations, relationNames), arguments, references);
    }

    /**
     * What the body reads in place of a relation parameter: {@code ρ E (π <heading> (Arg))}.
     * The projection is the point — the body was checked against the declared heading, so
     * it must see exactly that, and not an extra column of the argument that a natural join
     * in the body would otherwise silently match on. The rename keeps {@code E.col}
     * resolving inside the body.
     */
    private static RelNode narrowed(ParameterDefinition parameter, String relation,
                                    Set<RelNode> arguments, Map<String, Schema> binders,
                                    Map<RelNode, Schema> references) {
        List<ProjectedAttribute> columns = parameter.heading().orElseThrow().columns().stream()
                .map(c -> AstBuilders.projected(AstBuilders.attr(c.name())))
                .toList();
        Schema binder = binders.get(relation.toLowerCase(Locale.ROOT));
        RelNode argument;
        if (binder != null) {
            // The argument is the relation an enclosing ITERATE is building.
            argument = AstBuilders.recRef(relation);
            references.put(argument, binder);
        } else {
            argument = AstBuilders.rel(relation);
            arguments.add(argument);
        }
        return AstBuilders.rename(parameter.name(), List.of(), AstBuilders.project(columns, argument));
    }

    /**
     * Replaces each reference to a relation parameter: a relation leaf by the narrowed
     * argument, and a nested call's argument by the argument's name, which that call
     * narrows to its own heading in turn.
     */
    private static RelNode substituteRelations(RelNode node, Map<String, RelNode> relations,
                                               Map<String, Operand> names) {
        // A parameter's name has no dot, so a dotted relation name never matches one.
        if (node instanceof RelationNode r) {
            RelNode bound = relations.get(r.name().toLowerCase(Locale.ROOT));
            if (bound != null) {
                return bound;
            }
        }
        RelNode rewritten = node instanceof RelationFunctionCall f
                ? new RelationFunctionCall(f.functionName(), f.arguments().stream()
                        .map(a -> a instanceof AttributeOperand name
                                ? names.getOrDefault(name.name().toLowerCase(Locale.ROOT), a) : a)
                        .toList(), f.location())
                : node;
        return rewritten.mapChildren(child -> substituteRelations(child, relations, names));
    }

    private static RelNode substitute(RelNode node, Map<String, Operand> b) {
        RelNode rewritten = switch (node) {
            case SelectionNode s ->
                    new SelectionNode(substitute(s.predicate(), b), s.input(), s.location());
            case ProjectionNode p ->
                    new ProjectionNode(substituteProjected(p.attributes(), b), p.input(), p.location());
            case AggregationNode a ->
                    new AggregationNode(substituteGroupingKeys(a.groupingKeys(), b),
                            substituteAggregates(a.aggregates(), b), a.input(), a.location());
            case ConditionalJoinNode j ->
                    j.rebuild(j.left(), j.right(), substitute(j.condition(), b));
            case AsOfJoinNode j ->
                    new AsOfJoinNode(j.left(), j.right(), substitute(j.condition(), b),
                            j.tolerance(), j.inner(), j.tieBreak(), j.location());
            case IntervalJoinNode j ->
                    new IntervalJoinNode(j.left(), j.right(), j.relation(),
                            j.leftStart(), j.leftEnd(), j.rightStart(), j.rightEnd(), j.location());
            case UniversalNode u ->
                    new UniversalNode(u.groupingAttributes(), substitute(u.predicate(), b),
                            u.input(), u.location());
            case SolveNode s ->
                    new SolveNode(substituteOperand(s.left(), b), substituteOperand(s.right(), b),
                            s.input(), s.location());
            case OptimizeNode o ->
                    new OptimizeNode(o.sense(), substituteOperand(o.objective(), b),
                            substituteConstraints(o.constraints(), b), o.groupingKeys(),
                            o.allocation(), o.input(), o.location());
            case RelationFunctionCall f ->
                    new RelationFunctionCall(f.functionName(), substitute(f.arguments(), b), f.location());
            case WindowNode wn ->
                    new WindowNode(substituteWindowFunction(wn.function(), b), wn.partitionKeys(),
                            wn.sortSpecs(), wn.frame(), wn.outputColumn(), wn.input(), wn.location());
            case SessionizeNode sn ->
                    new SessionizeNode(sn.input(), sn.orderColumn(), substituteOperand(sn.threshold(), b),
                            sn.partitionKeys(), sn.sessionColumn(), sn.location());
            default -> node;   // no operands of its own — only its children may need it
        };
        return rewritten.mapChildren(child -> substitute(child, b));
    }

    private static WindowFunction substituteWindowFunction(WindowFunction fn, Map<String, Operand> b) {
        return switch (fn) {
            case WindowFunction.AggregateWindow a ->
                    new WindowFunction.AggregateWindow(a.operator(), substituteOperand(a.argument(), b));
            case WindowFunction.RankingWindow r ->
                    new WindowFunction.RankingWindow(r.function(),
                            r.ntileCount().map(c -> substituteOperand(c, b)));
            case WindowFunction.OffsetWindow o ->
                    new WindowFunction.OffsetWindow(o.function(), substituteOperand(o.expression(), b),
                            o.offset().map(x -> substituteOperand(x, b)),
                            o.defaultValue().map(x -> substituteOperand(x, b)));
        };
    }

    private static Predicate substitute(Predicate p, Map<String, Operand> b) {
        return switch (p) {
            case ComparisonPredicate c -> new ComparisonPredicate(
                    substituteOperand(c.left(), b), c.operator(), substituteOperand(c.right(), b), c.location());
            case AndPredicate a -> new AndPredicate(substitute(a.left(), b), substitute(a.right(), b), a.location());
            case OrPredicate o -> new OrPredicate(substitute(o.left(), b), substitute(o.right(), b), o.location());
            case NotPredicate n -> new NotPredicate(substitute(n.predicate(), b), n.location());
            case NullPredicate n -> new NullPredicate(substituteOperand(n.operand(), b), n.isNull(), n.location());
            case ElementOfPredicate e -> new ElementOfPredicate(
                    substituteOperand(e.element(), b), substituteOperand(e.setExpression(), b),
                    e.isNegated(), e.location());
            case PatternPredicate pp -> new PatternPredicate(
                    substituteOperand(pp.operand(), b), substituteOperand(pp.pattern(), b),
                    pp.negated(), pp.location());
        };
    }

    private static Operand substituteOperand(Operand op, Map<String, Operand> b) {
        return switch (op) {
            // A bare parameter reference is replaced by its bound argument; a qualified
            // reference (e.g. R.x) is a column, never a parameter, so it never matches.
            case AttributeOperand a -> {
                Operand bound = b.get(a.name().toLowerCase(Locale.ROOT));
                yield bound != null ? bound : a;
            }
            case BinaryArithmeticExpression e -> new BinaryArithmeticExpression(
                    substituteOperand(e.left(), b), e.operator(), substituteOperand(e.right(), b), e.location());
            case UnaryOperand u -> new UnaryOperand(substituteOperand(u.operand(), b), u.location());
            case FunctionCall f -> new FunctionCall(f.functionName(), substitute(f.arguments(), b), f.location());
            case SetLiteralOperand s -> new SetLiteralOperand(substitute(s.elements(), b), s.location());
            case StructConstruction sc -> new StructConstruction(sc.fields().stream()
                    .map(fld -> new StructConstruction.Field(fld.name(), substituteOperand(fld.value(), b)))
                    .toList(), sc.location());
            case ArrayConstruction ac -> new ArrayConstruction(substitute(ac.elements(), b), ac.location());
            case ConditionOperand c -> new ConditionOperand(substitute(c.predicate(), b), c.location());
            case StringOperand s -> s;
            case NumberOperand n -> n;
            case BooleanOperand bo -> bo;
            case DateOperand d -> d;
            case TimeOperand t -> t;
            case TimestampOperand ts -> ts;
            case DurationOperand du -> du;
        };
    }

    private static List<Operand> substitute(List<Operand> operands, Map<String, Operand> b) {
        return operands.stream().map(o -> substituteOperand(o, b)).toList();
    }

    private static List<ProjectedAttribute> substituteProjected(List<ProjectedAttribute> attrs,
                                                                Map<String, Operand> b) {
        return attrs.stream()
                .map(pa -> new ProjectedAttribute(substituteOperand(pa.expression(), b), pa.alias()))
                .toList();
    }

    private static List<GroupingKey> substituteGroupingKeys(List<GroupingKey> keys,
                                                            Map<String, Operand> b) {
        return keys.stream()
                .map(k -> new GroupingKey(substituteOperand(k.expression(), b), k.alias()))
                .toList();
    }

    private static List<AggregateFunction> substituteAggregates(List<AggregateFunction> aggs,
                                                                Map<String, Operand> b) {
        return aggs.stream()
                .map(ag -> new AggregateFunction(ag.operator(), substituteOperand(ag.argument(), b),
                        ag.yieldExpr().map(y -> substituteOperand(y, b)), ag.alias()))
                .toList();
    }

    private static List<OptimizeConstraint> substituteConstraints(List<OptimizeConstraint> constraints,
                                                                  Map<String, Operand> b) {
        return constraints.stream()
                .map(c -> new OptimizeConstraint(substituteOperand(c.expr(), b), c.op(), c.bound()))
                .toList();
    }
}

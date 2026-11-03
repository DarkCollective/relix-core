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

import com.darkcollective.relix.ast.UnaryOperand;
import com.darkcollective.relix.ast.CollateOperand;
import com.darkcollective.relix.ast.SetLiteralOperand;
import com.darkcollective.relix.ast.ArrayConstruction;
import com.darkcollective.relix.ast.StructConstruction;
import com.darkcollective.relix.ast.NullPredicate;
import com.darkcollective.relix.ast.ElementOfPredicate;
import com.darkcollective.relix.ast.PatternPredicate;
import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.AggregationNode;
import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.ConditionOperand;
import com.darkcollective.relix.ast.ExistsPredicate;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.LateralJoinNode;
import com.darkcollective.relix.ast.LateralKind;
import com.darkcollective.relix.ast.LimitNode;
import com.darkcollective.relix.ast.NotPredicate;
import com.darkcollective.relix.ast.NullOperand;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.OrPredicate;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.QuantifiedPredicate;
import com.darkcollective.relix.ast.Quantifier;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.ScalarSubqueryOperand;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Schema;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Rewrites a SELECT or PROJECT that carries subqueries into lateral joins over its
 * input, so that each subquery runs per row as an expression {@link LateralJoinNode}
 * and the operator reads its answer as a column:
 *
 * <pre>{@code
 *   π e(v)  (L)                 →  π e(c)    (L OUTER LATERAL (π v → c (q)))   one row at most
 *   σ EXISTS (q) (L)            →  π L… (σ c > 0 (L LATERAL (γ COUNT(1) → c (λ 1 (q)))))
 *   σ x op ANY|ALL (q) (L)      →  π L… (σ fold(t, f, n) (L LATERAL (γ t, f, n (q))))
 * }</pre>
 *
 * <p>A value's lateral is OUTER, so a row whose subquery yields nothing keeps a NULL, and
 * is marked to hold at most one row; EXISTS and a quantifier aggregate their relation to
 * one row without a key, which an ungrouped γ yields even over no rows. A quantifier
 * counts the rows its comparison is true, false and anything for: {@code x} is evaluated
 * once per outer row and bound into the relation as a placeholder, and
 * {@code fold} is three-valued, as SQL's is:
 *
 * <pre>{@code
 *   ANY:  t > 0 ∨ (n − t − f > 0 ∧ NULL = NULL)
 *   ALL:  f = 0 ∧ (n − t − f = 0 ∨ NULL = NULL)
 * }</pre>
 *
 * <p>A SELECT is followed by a projection back to its input's columns, so the operator's
 * heading is unchanged. Only the subqueries the operator itself carries are lowered; those
 * nested in their relations are lowered when that relation is planned.
 */
final class SubqueryLowering {

    /**
     * The lowered operator, and what the planner needs to know of each lateral it made.
     *
     * @param tree         the rewritten operator
     * @param singleRow    each value's lateral, to where its subquery was written
     * @param placeholders each quantifier's lateral, to the placeholders it binds and the
     *                     operands their values come from
     */
    record Lowered(RelNode tree, Map<LateralJoinNode, SourceLocation> singleRow,
                   Map<LateralJoinNode, Map<String, Operand>> placeholders) {
    }

    private final Function<RelNode, Schema> schemaOf;
    private final IntFunction<String> freshName;
    private final Map<LateralJoinNode, SourceLocation> singleRow = new IdentityHashMap<>();
    private final Map<LateralJoinNode, Map<String, Operand>> placeholders = new IdentityHashMap<>();
    private RelNode current;

    private SubqueryLowering(RelNode input, Function<RelNode, Schema> schemaOf, IntFunction<String> freshName) {
        this.current = input;
        this.schemaOf = schemaOf;
        this.freshName = freshName;
    }

    /**
     * Lowers the subqueries {@code node} carries.
     *
     * @param node      a SELECT or PROJECT carrying at least one subquery
     * @param schemaOf  the heading of an annotated relation
     * @param freshName a column name no relation in the query has, distinct per call
     * @return the rewritten operator and its laterals' markings
     */
    static Lowered lower(RelNode node, Function<RelNode, Schema> schemaOf, IntFunction<String> freshName) {
        return switch (node) {
            case SelectionNode s -> {
                SubqueryLowering lowering = new SubqueryLowering(s.input(), schemaOf, freshName);
                Predicate predicate = lowering.predicate(s.predicate());
                List<ProjectedAttribute> back = schemaOf.apply(s.input()).columns().stream()
                        .map(ColumnDefinition::name)
                        .map(name -> new ProjectedAttribute(new AttributeOperand(name, s.location()), Optional.empty()))
                        .toList();
                RelNode tree = new ProjectionNode(back,
                        new SelectionNode(predicate, lowering.current, s.location()), s.location());
                yield lowering.result(tree);
            }
            case ProjectionNode p -> {
                SubqueryLowering lowering = new SubqueryLowering(p.input(), schemaOf, freshName);
                List<ProjectedAttribute> attributes = new ArrayList<>();
                for (ProjectedAttribute a : p.attributes()) {
                    attributes.add(new ProjectedAttribute(lowering.operand(a.expression()), a.alias()));
                }
                yield lowering.result(new ProjectionNode(attributes, lowering.current, p.location()));
            }
            default -> throw new IllegalArgumentException("only a SELECT or a PROJECT carries a subquery: " + node);
        };
    }

    private Lowered result(RelNode tree) {
        return new Lowered(tree, singleRow, placeholders);
    }

    // ── the three forms ───────────────────────────────────────────────────────

    /** {@code (q)} as a value: its one column, renamed, from an OUTER lateral marked one-row. */
    private Operand value(ScalarSubqueryOperand subquery) {
        String column = freshName.apply(0);
        String own = schemaOf.apply(subquery.query()).columns().getFirst().name();
        SourceLocation at = subquery.location();
        RelNode body = new ProjectionNode(List.of(new ProjectedAttribute(new AttributeOperand(own, at),
                Optional.of(column))), subquery.query(), at);
        LateralJoinNode lateral = new LateralJoinNode(current, body, LateralKind.OUTER, at);
        singleRow.put(lateral, at);
        current = lateral;
        return new AttributeOperand(column, at);
    }

    /** {@code EXISTS (q)}: whether one row of it is counted. */
    private Predicate exists(ExistsPredicate exists) {
        String column = freshName.apply(0);
        SourceLocation at = exists.location();
        RelNode body = new AggregationNode(List.of(), List.of(count(new NumberOperand("1", at), column)),
                new LimitNode(Optional.empty(), 1L, exists.query(), at), at);
        current = new LateralJoinNode(current, body, LateralKind.INNER, at);
        return new ComparisonPredicate(new AttributeOperand(column, at), ComparisonOperator.GREATER,
                new NumberOperand("0", at), at);
    }

    /** {@code x op ANY|ALL (q)}: the comparison counted true, false and in all, then folded. */
    private Predicate quantified(QuantifiedPredicate q) {
        SourceLocation at = q.location();
        String compared = freshName.apply(0);
        String trueCount = freshName.apply(1);
        String falseCount = freshName.apply(2);
        String rows = freshName.apply(3);
        String own = schemaOf.apply(q.query()).columns().getFirst().name();
        Predicate comparison = new ComparisonPredicate(new AttributeOperand(compared, at), q.operator(),
                new AttributeOperand(own, at), at);
        RelNode body = new AggregationNode(List.of(), List.of(
                count(whenHolds(comparison, at), trueCount),
                count(whenHolds(new NotPredicate(comparison, at), at), falseCount),
                count(new NumberOperand("1", at), rows)), q.query(), at);
        LateralJoinNode lateral = new LateralJoinNode(current, body, LateralKind.INNER, at);
        placeholders.put(lateral, Map.of(compared, operand(q.left())));
        current = lateral;

        Operand t = new AttributeOperand(trueCount, at);
        Operand f = new AttributeOperand(falseCount, at);
        Operand unknown = new BinaryArithmeticExpression(new BinaryArithmeticExpression(
                new AttributeOperand(rows, at), ArithmeticOperator.MINUS, t, at), ArithmeticOperator.MINUS, f, at);
        Predicate isUnknown = new ComparisonPredicate(new NullOperand(at), ComparisonOperator.EQUAL,
                new NullOperand(at), at);
        Operand zero = new NumberOperand("0", at);
        return q.quantifier() == Quantifier.ANY
                ? new OrPredicate(new ComparisonPredicate(t, ComparisonOperator.GREATER, zero, at),
                        new AndPredicate(new ComparisonPredicate(unknown, ComparisonOperator.GREATER, zero, at),
                                isUnknown, at), at)
                : new AndPredicate(new ComparisonPredicate(f, ComparisonOperator.EQUAL, zero, at),
                        new OrPredicate(new ComparisonPredicate(unknown, ComparisonOperator.EQUAL, zero, at),
                                isUnknown, at), at);
    }

    /** {@code IIf(p, 1, NULL)}: what COUNT counts only where {@code p} holds. */
    private static Operand whenHolds(Predicate p, SourceLocation at) {
        return new FunctionCall("IIf", List.of(new ConditionOperand(p, at), new NumberOperand("1", at),
                new NullOperand(at)), at);
    }

    private static AggregateFunction count(Operand argument, String alias) {
        return new AggregateFunction(AggregateOperator.COUNT, argument, Optional.empty(), Optional.of(alias),
                false, List.of());
    }

    // ── the walk: each subquery the operator carries, outermost first ─────────

    /** Rebuilds {@code op} with each subquery in it lowered, outermost first, left to right. */
    private Operand operand(Operand op) {
        return switch (op) {
            case ScalarSubqueryOperand s -> value(s);
            case ConditionOperand c -> new ConditionOperand(predicate(c.predicate()), c.location());
            case FunctionCall f -> new FunctionCall(f.functionName(),
                    f.arguments().stream().map(this::operand).toList(), f.location());
            case BinaryArithmeticExpression e -> new BinaryArithmeticExpression(operand(e.left()),
                    e.operator(), operand(e.right()), e.location());
            case UnaryOperand u -> new UnaryOperand(operand(u.operand()), u.location());
            case CollateOperand c -> new CollateOperand(operand(c.operand()), c.collation(), c.location());
            case SetLiteralOperand s -> new SetLiteralOperand(
                    s.elements().stream().map(this::operand).toList(), s.location());
            case ArrayConstruction a -> new ArrayConstruction(
                    a.elements().stream().map(this::operand).toList(), a.location());
            case StructConstruction s -> new StructConstruction(s.fields().stream()
                    .map(f -> new StructConstruction.Field(f.name(), operand(f.value()))).toList(), s.location());
            default -> op;   // a column or a literal holds no subquery
        };
    }

    private Predicate predicate(Predicate p) {
        return switch (p) {
            case ExistsPredicate e -> exists(e);
            case QuantifiedPredicate q -> quantified(q);
            case AndPredicate a -> new AndPredicate(predicate(a.left()), predicate(a.right()), a.location());
            case OrPredicate o -> new OrPredicate(predicate(o.left()), predicate(o.right()), o.location());
            case NotPredicate n -> new NotPredicate(predicate(n.predicate()), n.location());
            case ComparisonPredicate c -> new ComparisonPredicate(operand(c.left()), c.operator(),
                    operand(c.right()), c.location());
            case NullPredicate n -> new NullPredicate(operand(n.operand()), n.isNull(), n.location());
            case ElementOfPredicate e -> new ElementOfPredicate(operand(e.element()), operand(e.setExpression()),
                    e.isNegated(), e.location());
            case PatternPredicate pp -> new PatternPredicate(operand(pp.operand()), operand(pp.pattern()),
                    pp.negated(), pp.escape(), pp.location());
        };
    }
}

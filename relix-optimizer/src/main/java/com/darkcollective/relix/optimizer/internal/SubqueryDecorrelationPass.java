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
package com.darkcollective.relix.optimizer.internal;

import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.AggregationNode;
import com.darkcollective.relix.ast.AntiJoinNode;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.ExistsPredicate;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.LateralJoinNode;
import com.darkcollective.relix.ast.LateralKind;
import com.darkcollective.relix.ast.LeftOuterJoinNode;
import com.darkcollective.relix.ast.NotPredicate;
import com.darkcollective.relix.ast.NullPredicate;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.OrPredicate;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.QuantifiedPredicate;
import com.darkcollective.relix.ast.Quantifier;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.ScalarSubqueryOperand;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SemiJoinNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.ThetaJoinNode;
import com.darkcollective.relix.ast.internal.AttributeNames;
import com.darkcollective.relix.ast.internal.OperandWalker;
import com.darkcollective.relix.ast.internal.Predicates;
import com.darkcollective.relix.ast.internal.Qualifiers;
import com.darkcollective.relix.ast.internal.Subqueries;
import com.darkcollective.relix.optimizer.OptimizationCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Rewrites the common shapes of subquery into the joins they stand for, so that the join
 * executors, selection pushdown, costing and SQL pushdown carry them rather than a per-row
 * lateral ({@code SUBQ-001} to {@code SUBQ-006}).
 *
 * <pre>{@code
 *   SUBQ-001  σ EXISTS (σ θ (S)) (L)                  →  L ⋉ θ S
 *   SUBQ-002  σ NOT EXISTS (σ θ (S)) (L)              →  L ▷ θ S
 *   SUBQ-003  σ x op ANY (π v (σ θ (S))) (L)          →  L ⋉ (θ ∧ x op v) S
 *   SUBQ-004  σ x op ALL (π v (σ θ (S))) (L)          →  L ▷ (θ ∧ (¬(x op v) ∨ x = ⊥ ∨ v = ⊥)) S
 *   SUBQ-005  π …, (γ agg (σ S.k = L.k ∧ ρ (S))) …     →  π …, g.a … (L ⟕ L.k = g.k ρ g (γ k, agg (σ ρ (S))))
 *   SUBQ-006  L [OUTER] LATERAL (σ θ ∧ ρ (S))          →  L ⨝θ σ ρ (S)  /  L ⟕θ σ ρ (S)
 * }</pre>
 *
 * <h2>When each is exact</h2>
 * <ul>
 *   <li><b>Position.</b> {@code SUBQ-001} to {@code SUBQ-004} take a conjunct at the top of
 *       a SELECT's condition, where UNKNOWN and FALSE both drop the row; the negations
 *       {@code NOT EXISTS}, {@code NOT (x op ANY …)} and {@code NOT (x op ALL …)} there are
 *       the duals. A subquery anywhere else is left to the lateral the planner makes.</li>
 *   <li><b>Shape.</b> The subquery's relation {@code S} reads nothing of the outer row; its
 *       outer references are in the condition {@code θ} alone, and each names a relation in
 *       scope in {@code L}. {@code S} answers to exactly one relation name, which {@code L}
 *       does not have, so that the join condition can name its columns qualified: an
 *       unqualified name in {@code θ} or {@code v} is the subquery's own, and written
 *       {@code S.name}, while one in {@code x} is {@code L}'s.</li>
 *   <li><b>ALL's NULL trap.</b> {@code x op ALL} keeps a row only when no row of the
 *       relation makes the comparison FALSE or UNKNOWN, which for a comparison of two
 *       values is when one of them is NULL — exactly the anti join's condition above.
 *       Over no rows the anti join keeps the row, as {@code ALL} holds.</li>
 *   <li><b>The count bug.</b> {@code SUBQ-005} joins each outer row to its group of
 *       {@code S}, keyed on the correlated equalities, one row per key, so no outer row is
 *       repeated. A row with no group gets NULL where the ungrouped γ gave its value over
 *       no rows: 0 for {@code COUNT}, which is put back with {@code Coalesce}, and NULL for
 *       {@code SUM}, {@code MIN}, {@code MAX} and {@code AVG}, which need nothing. Any other
 *       aggregate, a grouped subquery, or a correlation that is not a conjunction of
 *       equalities stays a lateral.</li>
 *   <li><b>LATERAL.</b> {@code SUBQ-006} keeps the heading: a θ join's is {@code left ++
 *       right}, as the lateral's is, and an outer lateral pads as a left outer join does.</li>
 * </ul>
 *
 * <p>The pass is bottom-up and stateless but for the names it mints for SUBQ-005's groups,
 * which are unique within one query.
 */
final class SubqueryDecorrelationPass {

    private final OptimizationContext ctx;
    private final String queryName;
    private int nextGroup = 1;

    private SubqueryDecorrelationPass(OptimizationContext ctx, String queryName) {
        this.ctx = ctx;
        this.queryName = queryName;
    }

    /**
     * Applies SUBQ-001 to SUBQ-006 to the tree rooted at {@code node}.
     *
     * @param node      the tree; must not be null
     * @param ctx       records each rewrite
     * @param queryName display name for the records
     * @return the rewritten tree, or {@code node} when no rule fires
     */
    static RelNode apply(RelNode node, OptimizationContext ctx, String queryName) {
        return new SubqueryDecorrelationPass(ctx, queryName).rewrite(node);
    }

    private RelNode rewrite(RelNode node) {
        RelNode rewritten = node.mapChildren(this::rewrite);
        return switch (rewritten) {
            case SelectionNode s -> selection(s);
            case ProjectionNode p -> projection(p);
            case LateralJoinNode l -> lateral(l);
            default -> rewritten;
        };
    }

    // ── SUBQ-001 … SUBQ-004: a SELECT's conjuncts ─────────────────────────────

    private RelNode selection(SelectionNode s) {
        RelNode input = s.input();
        List<Predicate> kept = new ArrayList<>();
        boolean changed = false;
        for (Predicate conjunct : Predicates.conjuncts(s.predicate())) {
            RelNode joined = conjunctAsJoin(conjunct, input);
            if (joined == null) {
                kept.add(conjunct);
            } else {
                input = joined;
                changed = true;
            }
        }
        if (!changed) {
            return s;
        }
        return kept.isEmpty() ? input : new SelectionNode(Predicates.conjoin(kept), input, s.location());
    }

    /** The join {@code conjunct} over {@code left} stands for, or null when no rule is exact. */
    private RelNode conjunctAsJoin(Predicate conjunct, RelNode left) {
        boolean negated = conjunct instanceof NotPredicate;
        Predicate inner = negated ? ((NotPredicate) conjunct).predicate() : conjunct;
        if (inner instanceof ExistsPredicate e) {
            return exists(e, left, negated);
        }
        if (inner instanceof QuantifiedPredicate q) {
            // ¬(x op ANY r) is x ¬op ALL r, and ¬(x op ALL r) is x ¬op ANY r, in three values too.
            Quantifier quantifier = negated == (q.quantifier() == Quantifier.ANY) ? Quantifier.ALL : Quantifier.ANY;
            ComparisonOperator op = negated ? negate(q.operator()) : q.operator();
            return quantified(q, left, op, quantifier);
        }
        return null;
    }

    private RelNode exists(ExistsPredicate e, RelNode left, boolean negated) {
        Correlated c = correlated(e.query(), left);
        if (c == null) {
            return null;
        }
        Predicate condition = c.condition().orElse(PredicateSimplifier.constant(true));
        OptimizationCode code = negated ? OptimizationCode.SUBQ_002 : OptimizationCode.SUBQ_001;
        ctx.record(code, queryName, (negated ? "NOT EXISTS" : "EXISTS") + " written as "
                + (negated ? "an anti" : "a semi") + " join", e.location());
        return negated ? new AntiJoinNode(left, c.relation(), condition, e.location())
                : new SemiJoinNode(left, c.relation(), condition, e.location());
    }

    private RelNode quantified(QuantifiedPredicate q, RelNode left, ComparisonOperator op, Quantifier quantifier) {
        if (!(q.query() instanceof ProjectionNode p) || p.attributes().size() != 1
                || !(p.attributes().getFirst().expression() instanceof AttributeOperand v)) {
            return null;
        }
        Correlated c = correlated(p.input(), left);
        Operand x = leftSide(q.left(), left);
        if (c == null || x == null) {
            return null;
        }
        Operand value = qualified(v, c.qualifier());
        SourceLocation at = q.location();
        Predicate compare = new ComparisonPredicate(x, op, value, at);
        if (quantifier == Quantifier.ANY) {
            ctx.record(OptimizationCode.SUBQ_003, queryName, "ANY written as a semi join", at);
            return new SemiJoinNode(left, c.relation(), and(c.condition(), compare), at);
        }
        Predicate fails = new OrPredicate(new OrPredicate(new NotPredicate(compare, at),
                new NullPredicate(x, true, at), at), new NullPredicate(value, true, at), at);
        ctx.record(OptimizationCode.SUBQ_004, queryName, "ALL written as an anti join", at);
        return new AntiJoinNode(left, c.relation(), and(c.condition(), fails), at);
    }

    // ── SUBQ-005: a correlated aggregate in a PROJECT ─────────────────────────

    private RelNode projection(ProjectionNode p) {
        RelNode input = p.input();
        List<ProjectedAttribute> attributes = new ArrayList<>(p.attributes());
        boolean changed = false;
        for (int i = 0; i < attributes.size(); i++) {
            ProjectedAttribute a = attributes.get(i);
            // Unnamed, the column is named after its expression, which the rewrite changes.
            if (!(a.expression() instanceof ScalarSubqueryOperand s) || a.alias().isEmpty()) {
                continue;
            }
            Grouped g = grouped(s, input);
            if (g == null) {
                continue;
            }
            input = g.joined();
            attributes.set(i, new ProjectedAttribute(g.value(), a.alias()));
            changed = true;
        }
        return changed ? new ProjectionNode(attributes, input, p.location()) : p;
    }

    private Grouped grouped(ScalarSubqueryOperand s, RelNode left) {
        if (!(s.query() instanceof AggregationNode agg) || !agg.groupingKeys().isEmpty()
                || agg.aggregates().size() != 1
                || !(agg.input() instanceof SelectionNode where)) {
            return null;
        }
        AggregateFunction aggregate = agg.aggregates().getFirst();
        if (!List.of(AggregateOperator.COUNT, AggregateOperator.SUM, AggregateOperator.MIN,
                AggregateOperator.MAX, AggregateOperator.AVG).contains(aggregate.operator())
                || !aggregate.orderBy().isEmpty()) {
            return null;
        }
        RelNode source = where.input();
        Set<String> leftNames = Qualifiers.inScope(left);
        String qualifier = soleQualifier(source, leftNames);
        if (qualifier == null || !Subqueries.outerReferences(source).isEmpty()
                || hasOuter(aggregate)) {
            return null;
        }
        // Every correlated conjunct is inner = outer; the rest read the subquery alone.
        List<Operand> innerKeys = new ArrayList<>();
        List<AttributeOperand> outerKeys = new ArrayList<>();
        List<Predicate> rest = new ArrayList<>();
        for (Predicate conjunct : Predicates.conjuncts(where.predicate())) {
            List<AttributeOperand> refs = Subqueries.outerReferences(new SelectionNode(conjunct, source));
            if (refs.isEmpty()) {
                rest.add(conjunct);
                continue;
            }
            if (!(conjunct instanceof ComparisonPredicate c) || c.operator() != ComparisonOperator.EQUAL) {
                return null;
            }
            AttributeOperand outer = isOuter(c.right(), refs) ? (AttributeOperand) c.right()
                    : isOuter(c.left(), refs) ? (AttributeOperand) c.left() : null;
            Operand innerSide = outer == c.right() ? c.left() : c.right();
            if (outer == null || !leftNames.contains(qualifierOf(outer)) || !readsOnlyInner(innerSide, refs)) {
                return null;
            }
            innerKeys.add(innerSide);
            outerKeys.add(outer);
        }
        if (innerKeys.isEmpty()) {
            return null;   // uncorrelated: run once by the lateral, nothing to group by
        }
        int n = nextGroup++;
        String group = "$group" + n;
        List<GroupingKey> keys = new ArrayList<>();
        List<String> renamed = new ArrayList<>();
        for (int k = 0; k < innerKeys.size(); k++) {
            keys.add(new GroupingKey(innerKeys.get(k), Optional.of("$k" + k)));
            renamed.add("$k" + k);
        }
        renamed.add("$v");
        SourceLocation at = s.location();
        RelNode filtered = rest.isEmpty() ? source : new SelectionNode(Predicates.conjoin(rest), source, at);
        AggregateFunction value = new AggregateFunction(aggregate.operator(), aggregate.argument(),
                aggregate.yieldExpr(), Optional.of("$v"), aggregate.distinct(), aggregate.orderBy());
        RelNode groups = new RenameNode(group, renamed,
                new AggregationNode(keys, List.of(value), filtered, at), at);
        List<Predicate> on = new ArrayList<>();
        for (int k = 0; k < outerKeys.size(); k++) {
            on.add(new ComparisonPredicate(outerKeys.get(k), ComparisonOperator.EQUAL,
                    new AttributeOperand(group + ".$k" + k, at), at));
        }
        Operand column = new AttributeOperand(group + ".$v", at);
        Operand result = aggregate.operator() == AggregateOperator.COUNT
                ? new FunctionCall("Coalesce", List.of(column, new NumberOperand("0", at)), at) : column;
        ctx.record(OptimizationCode.SUBQ_005, queryName, aggregate.operator()
                + " subquery written as a left join onto its groups", at);
        return new Grouped(new LeftOuterJoinNode(left, groups, Predicates.conjoin(on), at), result);
    }

    private record Grouped(RelNode joined, Operand value) {
    }

    private static boolean hasOuter(AggregateFunction aggregate) {
        boolean[] found = {false};
        OperandWalker.walk(aggregate.argument(), a -> found[0] |= AttributeNames.qualifierOf(a.name()) != null,
                f -> { });
        return found[0];
    }

    private static boolean isOuter(Operand side, List<AttributeOperand> refs) {
        return side instanceof AttributeOperand a
                && refs.stream().anyMatch(r -> r.name().equalsIgnoreCase(a.name()));
    }

    private static boolean readsOnlyInner(Operand side, List<AttributeOperand> refs) {
        boolean[] outer = {false};
        OperandWalker.walk(side, a -> outer[0] |= refs.stream().anyMatch(r -> r.name().equalsIgnoreCase(a.name())),
                f -> { });
        return !outer[0];
    }

    // ── SUBQ-006: an expression lateral over a filtered relation ──────────────

    private RelNode lateral(LateralJoinNode l) {
        if (l.isFunctionCall()) {
            return l;
        }
        Correlated c = correlated(l.right(), l.left());
        if (c == null || c.condition().isEmpty()) {
            return l;   // uncorrelated: LATERAL-001's
        }
        ctx.record(OptimizationCode.SUBQ_006, queryName, (l.kind() == LateralKind.OUTER ? "OUTER " : "")
                + "LATERAL written as a join on its correlation", l.location());
        return l.kind() == LateralKind.OUTER
                ? new LeftOuterJoinNode(l.left(), c.relation(), c.condition().get(), l.location())
                : new ThetaJoinNode(l.left(), c.relation(), c.condition().get(), l.location());
    }

    // ── the shared shape: σ θ (S), correlated through θ alone ─────────────────

    /**
     * A subquery's relation as a join's right input: {@code relation} reads nothing of
     * the outer row, and {@code condition}, qualified, is what correlated it.
     */
    private record Correlated(RelNode relation, Optional<Predicate> condition, String qualifier) {
    }

    /**
     * {@code query} split for a join with {@code left}, or null when the shape is not
     * exact: {@code σ θ (S)} or {@code S}, where {@code S} reads nothing from outside, the
     * correlated conjuncts of {@code θ} name only relations {@code left} has in scope, and
     * {@code S} answers to one name {@code left} does not.
     */
    private Correlated correlated(RelNode query, RelNode left) {
        Set<String> leftNames = Qualifiers.inScope(left);
        if (!(query instanceof SelectionNode where)) {
            String qualifier = soleQualifier(query, leftNames);
            return qualifier == null || !Subqueries.outerReferences(query).isEmpty() ? null
                    : new Correlated(query, Optional.empty(), qualifier);
        }
        RelNode source = where.input();
        String qualifier = soleQualifier(source, leftNames);
        if (qualifier == null || !Subqueries.outerReferences(source).isEmpty()
                || !Subqueries.subqueriesOf(where).isEmpty()) {
            return null;
        }
        List<Predicate> correlated = new ArrayList<>();
        List<Predicate> rest = new ArrayList<>();
        for (Predicate conjunct : Predicates.conjuncts(where.predicate())) {
            List<AttributeOperand> refs = Subqueries.outerReferences(new SelectionNode(conjunct, source));
            if (refs.isEmpty()) {
                rest.add(conjunct);
            } else if (refs.stream().allMatch(r -> leftNames.contains(qualifierOf(r)))) {
                correlated.add(qualify(conjunct, qualifier));
            } else {
                return null;   // reads a row further out than left
            }
        }
        RelNode relation = rest.isEmpty() ? source : new SelectionNode(Predicates.conjoin(rest), source, where.location());
        return new Correlated(relation, correlated.isEmpty() ? Optional.empty()
                : Optional.of(Predicates.conjoin(correlated)), qualifier);
    }

    /** The one relation name {@code relation} answers to, when {@code left} does not have it. */
    private static String soleQualifier(RelNode relation, Set<String> leftNames) {
        Set<String> names = Qualifiers.inScope(relation);
        if (names.size() != 1) {
            return null;
        }
        String name = names.iterator().next();
        return leftNames.contains(name) ? null : name;
    }

    /** {@code x} for a join condition: its unqualified names qualified with {@code left}'s one name. */
    private static Operand leftSide(Operand x, RelNode left) {
        if (!Subqueries.subqueriesOf(new ProjectionNode(List.of(new ProjectedAttribute(x, Optional.empty())), left))
                .isEmpty()) {
            return null;
        }
        boolean[] bare = {false};
        OperandWalker.walk(x, a -> bare[0] |= AttributeNames.qualifierOf(a.name()) == null, f -> { });
        if (!bare[0]) {
            return x;
        }
        Set<String> names = Qualifiers.inScope(left);
        if (names.size() != 1) {
            return null;
        }
        return qualify(x, names.iterator().next());
    }

    private static String qualifierOf(AttributeOperand a) {
        String q = AttributeNames.qualifierOf(a.name());
        return q == null ? null : q.toLowerCase(Locale.ROOT);
    }

    private static Operand qualified(AttributeOperand v, String qualifier) {
        return AttributeNames.qualifierOf(v.name()) != null ? v
                : new AttributeOperand(qualifier + "." + v.name(), v.location());
    }

    /** {@code p} with every unqualified name qualified with {@code qualifier}. */
    private static Predicate qualify(Predicate p, String qualifier) {
        return qualifyPredicate(p, qualifier);
    }

    private static Predicate qualifyPredicate(Predicate p, String qualifier) {
        return switch (p) {
            case ComparisonPredicate c -> new ComparisonPredicate(qualify(c.left(), qualifier), c.operator(),
                    qualify(c.right(), qualifier), c.location());
            case com.darkcollective.relix.ast.AndPredicate a -> new com.darkcollective.relix.ast.AndPredicate(
                    qualifyPredicate(a.left(), qualifier), qualifyPredicate(a.right(), qualifier), a.location());
            case OrPredicate o -> new OrPredicate(qualifyPredicate(o.left(), qualifier),
                    qualifyPredicate(o.right(), qualifier), o.location());
            case NotPredicate n -> new NotPredicate(qualifyPredicate(n.predicate(), qualifier), n.location());
            case NullPredicate n -> new NullPredicate(qualify(n.operand(), qualifier), n.isNull(), n.location());
            case com.darkcollective.relix.ast.ElementOfPredicate e -> new com.darkcollective.relix.ast.ElementOfPredicate(
                    qualify(e.element(), qualifier), qualify(e.setExpression(), qualifier), e.isNegated(), e.location());
            case com.darkcollective.relix.ast.PatternPredicate pp -> new com.darkcollective.relix.ast.PatternPredicate(
                    qualify(pp.operand(), qualifier), qualify(pp.pattern(), qualifier), pp.negated(), pp.escape(),
                    pp.location());
            // Not reached: a condition carrying a subquery is not rewritten.
            case ExistsPredicate e -> e;
            case QuantifiedPredicate q -> q;
        };
    }

    /** {@code op} with every unqualified name qualified with {@code qualifier}. */
    private static Operand qualify(Operand op, String qualifier) {
        return rename(op, qualifier);
    }

    private static Operand rename(Operand op, String qualifier) {
        return switch (op) {
            case AttributeOperand a -> AttributeNames.qualifierOf(a.name()) != null ? a
                    : new AttributeOperand(qualifier + "." + a.name(), a.location());
            case com.darkcollective.relix.ast.BinaryArithmeticExpression e ->
                    new com.darkcollective.relix.ast.BinaryArithmeticExpression(rename(e.left(), qualifier),
                            e.operator(), rename(e.right(), qualifier), e.location());
            case com.darkcollective.relix.ast.UnaryOperand u ->
                    new com.darkcollective.relix.ast.UnaryOperand(rename(u.operand(), qualifier), u.location());
            case FunctionCall f -> new FunctionCall(f.functionName(),
                    f.arguments().stream().map(a -> rename(a, qualifier)).toList(), f.location());
            case com.darkcollective.relix.ast.CollateOperand c ->
                    new com.darkcollective.relix.ast.CollateOperand(rename(c.operand(), qualifier), c.collation(),
                            c.location());
            case com.darkcollective.relix.ast.SetLiteralOperand s -> new com.darkcollective.relix.ast.SetLiteralOperand(
                    s.elements().stream().map(e -> rename(e, qualifier)).toList(), s.location());
            case com.darkcollective.relix.ast.ConditionOperand c ->
                    new com.darkcollective.relix.ast.ConditionOperand(qualifyPredicate(c.predicate(), qualifier),
                            c.location());
            default -> op;   // a literal, a parameter, or a construction left as written
        };
    }

    private static Predicate and(Optional<Predicate> a, Predicate b) {
        return a.<Predicate>map(p -> new com.darkcollective.relix.ast.AndPredicate(p, b, b.location())).orElse(b);
    }

    private static ComparisonOperator negate(ComparisonOperator op) {
        return switch (op) {
            case EQUAL -> ComparisonOperator.NOT_EQUAL;
            case NOT_EQUAL -> ComparisonOperator.EQUAL;
            case LESS -> ComparisonOperator.GREATER_EQUAL;
            case LESS_EQUAL -> ComparisonOperator.GREATER;
            case GREATER -> ComparisonOperator.LESS_EQUAL;
            case GREATER_EQUAL -> ComparisonOperator.LESS;
        };
    }
}

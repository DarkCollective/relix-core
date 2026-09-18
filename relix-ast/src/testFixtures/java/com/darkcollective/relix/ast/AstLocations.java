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
package com.darkcollective.relix.ast;

import com.darkcollective.relix.ast.visitor.RelNodeVisitor;

import java.util.List;

/**
 * Exact, location-blind structural comparison of AST fragments — "is this the tree I
 * expected, wherever it happens to have been written?".
 *
 * <p>Every {@link RelNode}, {@link Predicate} and {@link Operand} is a record carrying a
 * {@link SourceLocation} as a <em>component</em>, so the compiler-generated {@code equals}
 * includes the position: a parsed fragment is never equal to the same fragment built by
 * hand, which defaults to {@link SourceLocation#UNKNOWN}.  {@link #stripLocations(RelNode)}
 * rebuilds a tree with every location replaced by {@code UNKNOWN}, and {@code equals} on
 * the results is then the comparison a test wants.
 *
 * <h2>This is not {@link AstEquivalence}</h2>
 * <p>The two are easy to mistake for one another — both ignore source positions, and both
 * answer a question of the form "are these the same?" — but they normalise different
 * things, and each is wrong where the other is right:
 *
 * <table class="striped">
 *   <caption>What the two comparisons decide</caption>
 *   <thead><tr>
 *     <th>&nbsp;</th>
 *     <th>{@code stripLocations(a).equals(stripLocations(b))}</th>
 *     <th>{@code AstEquivalence.equivalent(a, b)}</th>
 *   </tr></thead>
 *   <tbody>
 *     <tr><td>Compares</td>
 *         <td>rebuilt records, component-wise</td>
 *         <td>{@link RelNode#prettyPrint()} forms</td></tr>
 *     <tr><td>{@code 5} vs {@code 5.0} vs {@code 05}</td>
 *         <td>distinct</td>
 *         <td>equivalent at operand level (compared as {@code BigDecimal})</td></tr>
 *     <tr><td>{@code Users} vs {@code users}</td>
 *         <td>distinct</td>
 *         <td>equivalent (identifier case normalised)</td></tr>
 *   </tbody>
 * </table>
 *
 * <p>So a parser test asserting that {@code 5.0} parses to {@code NumberOperand("5.0")},
 * or that identifier case survives the lexer, would <strong>silently stop asserting
 * anything</strong> under {@link AstEquivalence}.  Comparing printed forms is also the
 * weaker claim in general: two structurally different trees that print alike compare
 * equal, a hazard {@code RelNodeCorpusRoundTripTest} covers with a test of its own.
 *
 * <p>Read the split as: {@link AstEquivalence} asks <em>"do these two fragments say the
 * same thing?"</em> and is what a rewrite rule needs (duplicate-conjunct removal,
 * {@code SharedSubexpressions.detect}); this class asks <em>"is this exactly the tree I
 * expected?"</em> and is what a parser or builder test needs.  Neither replaces the other.
 *
 * <h2>Maintenance</h2>
 * <p>The rebuild is a hand-written {@link RelNodeVisitor} arm per kind, so it duplicates
 * each record's canonical constructor and can drift from it — an arm that quietly drops a
 * component would make a test pass that should fail.  {@code AstLocationsTest} holds it to
 * the {@code RelNodeCorpus}: stripping a corpus node, which already carries only
 * {@code UNKNOWN}, must return something equal to it, for every concrete kind.  That is
 * also what catches a <em>missing</em> arm — five of {@link RelNodeVisitor}'s arms are
 * throwing {@code default}s, so a new kind added that way compiles and fails at run time.
 *
 * @see AstEquivalence
 * @see RelNodeAssert#isStructurallyEqualTo(RelNode)
 */
public final class AstLocations {

    private AstLocations() {
    }

    /**
     * Returns a copy of {@code node} with every {@link SourceLocation} in the tree —
     * on the node, on its children, and inside the predicates and operands it carries —
     * replaced by {@link SourceLocation#UNKNOWN}.
     *
     * @param node the tree to strip; must not be null
     * @return an equal-but-location-free copy
     */
    public static RelNode stripLocations(RelNode node) {
        return node.accept(LocationStripper.INSTANCE);
    }

    /**
     * Returns a copy of {@code predicate} with every {@link SourceLocation} replaced by
     * {@link SourceLocation#UNKNOWN}, recursively.
     *
     * @param predicate the predicate to strip; must not be null
     * @return an equal-but-location-free copy
     */
    public static Predicate stripLocations(Predicate predicate) {
        return switch (predicate) {
            case ComparisonPredicate cp ->
                new ComparisonPredicate(stripLocations(cp.left()), cp.operator(), stripLocations(cp.right()));
            case AndPredicate ap -> new AndPredicate(stripLocations(ap.left()), stripLocations(ap.right()));
            case OrPredicate op -> new OrPredicate(stripLocations(op.left()), stripLocations(op.right()));
            case NotPredicate np -> new NotPredicate(stripLocations(np.predicate()));
            case NullPredicate np -> new NullPredicate(stripLocations(np.operand()), np.isNull());
            case ElementOfPredicate ep ->
                new ElementOfPredicate(stripLocations(ep.element()), stripLocations(ep.setExpression()),
                        ep.isNegated());
            case PatternPredicate pp ->
                new PatternPredicate(stripLocations(pp.operand()), stripLocations(pp.pattern()), pp.negated());
        };
    }

    /**
     * Returns a copy of {@code operand} with every {@link SourceLocation} replaced by
     * {@link SourceLocation#UNKNOWN}, recursively.
     *
     * @param operand the operand to strip; must not be null
     * @return an equal-but-location-free copy
     */
    public static Operand stripLocations(Operand operand) {
        return switch (operand) {
            case AttributeOperand a -> new AttributeOperand(a.name());
            case StringOperand s -> new StringOperand(s.value());
            case NumberOperand n -> new NumberOperand(n.value());
            case BooleanOperand b -> new BooleanOperand(b.value());
            case DateOperand d -> new DateOperand(d.value());
            case TimeOperand t -> new TimeOperand(t.value());
            case TimestampOperand ts -> new TimestampOperand(ts.value());
            case DurationOperand du -> new DurationOperand(du.value());
            case UnaryOperand u -> new UnaryOperand(stripLocations(u.operand()));
            case BinaryArithmeticExpression b ->
                new BinaryArithmeticExpression(stripLocations(b.left()), b.operator(), stripLocations(b.right()));
            case FunctionCall fc -> {
                List<Operand> args = fc.arguments() == null ? null :
                        fc.arguments().stream().map(AstLocations::stripLocations).toList();
                yield new FunctionCall(fc.functionName(), args);
            }
            case SetLiteralOperand sl -> {
                List<Operand> elems = sl.elements().stream().map(AstLocations::stripLocations).toList();
                yield new SetLiteralOperand(elems);
            }
            case StructConstruction sc -> {
                List<StructConstruction.Field> fields = sc.fields().stream()
                        .map(f -> new StructConstruction.Field(f.name(), stripLocations(f.value())))
                        .toList();
                yield new StructConstruction(fields);
            }
            case ArrayConstruction ac -> {
                List<Operand> elems = ac.elements().stream().map(AstLocations::stripLocations).toList();
                yield new ArrayConstruction(elems);
            }
            case ConditionOperand c -> new ConditionOperand(stripLocations(c.predicate()));
        };
    }

    private static ProduceBound stripBound(ProduceBound bound) {
        return new ProduceBound(bound.column(), bound.operator(), stripLocations(bound.limit()));
    }

    private static AggregateFunction stripAgg(AggregateFunction a) {
        return new AggregateFunction(a.operator(), stripLocations(a.argument()),
                a.yieldExpr().map(AstLocations::stripLocations), a.alias());
    }

    private static GroupingKey stripGroupingKey(GroupingKey k) {
        return new GroupingKey(stripLocations(k.expression()), k.alias());
    }

    private static List<SortSpecification> stripSpecs(List<SortSpecification> specs) {
        return specs.stream()
                .map(s -> new SortSpecification(stripLocations(s.expression()), s.direction()))
                .toList();
    }

    private static WindowFunction stripWindowFunction(WindowFunction fn) {
        return switch (fn) {
            case WindowFunction.AggregateWindow a ->
                    new WindowFunction.AggregateWindow(a.operator(), stripLocations(a.argument()));
            case WindowFunction.RankingWindow r ->
                    new WindowFunction.RankingWindow(r.function(), r.ntileCount().map(AstLocations::stripLocations));
            case WindowFunction.OffsetWindow o ->
                    new WindowFunction.OffsetWindow(o.function(), stripLocations(o.expression()),
                            o.offset().map(AstLocations::stripLocations),
                            o.defaultValue().map(AstLocations::stripLocations));
        };
    }

    /**
     * A {@link RelNodeVisitor} that rebuilds every node with {@link SourceLocation#UNKNOWN},
     * recursively stripping all source position information from the tree.
     */
    private static final class LocationStripper implements RelNodeVisitor<RelNode> {
        static final LocationStripper INSTANCE = new LocationStripper();

        @Override
        public RelNode visit(RelationNode node) {
            return new RelationNode(node.name(), node.produceBound().map(AstLocations::stripBound),
                    SourceLocation.UNKNOWN);
        }

        @Override
        public RelNode visit(TruthRelationNode node) {
            return new TruthRelationNode(node.holdsTuple(), SourceLocation.UNKNOWN);
        }

        @Override
        public RelNode visit(EmptyRelationNode node) {
            // Optimizer-only; the parser never produces one. Strip the location on it
            // and on the heading it carries, so a fixture that reaches here compares.
            return new EmptyRelationNode(node.heading().accept(this), SourceLocation.UNKNOWN);
        }

        @Override
        public RelNode visit(RelationFunctionCall node) {
            return new RelationFunctionCall(node.functionName(),
                    node.arguments().stream().map(AstLocations::stripLocations).toList());
        }

        @Override
        public RelNode visit(ProjectionNode node) {
            List<ProjectedAttribute> attrs = node.attributes().stream()
                    .map(a -> a.alias()
                            .map(alias -> ProjectedAttribute.aliased(stripLocations(a.expression()), alias))
                            .orElseGet(() -> ProjectedAttribute.simple(stripLocations(a.expression()))))
                    .toList();
            return new ProjectionNode(attrs, node.input().accept(this));
        }

        @Override
        public RelNode visit(SelectionNode node) {
            return new SelectionNode(stripLocations(node.predicate()), node.input().accept(this));
        }

        @Override
        public RelNode visit(RenameNode node) {
            return new RenameNode(node.relationName(), node.attributes(), node.pairs(),
                    node.input().accept(this), SourceLocation.UNKNOWN);
        }

        @Override
        public RelNode visit(NaturalJoinNode node) {
            return new NaturalJoinNode(node.left().accept(this), node.right().accept(this));
        }

        @Override
        public RelNode visit(ThetaJoinNode node) {
            return new ThetaJoinNode(node.left().accept(this), node.right().accept(this),
                    stripLocations(node.condition()));
        }

        @Override
        public RelNode visit(LeftOuterJoinNode node) {
            return new LeftOuterJoinNode(node.left().accept(this), node.right().accept(this),
                    stripLocations(node.condition()));
        }

        @Override
        public RelNode visit(RightOuterJoinNode node) {
            return new RightOuterJoinNode(node.left().accept(this), node.right().accept(this),
                    stripLocations(node.condition()));
        }

        @Override
        public RelNode visit(FullOuterJoinNode node) {
            return new FullOuterJoinNode(node.left().accept(this), node.right().accept(this),
                    stripLocations(node.condition()));
        }

        @Override
        public RelNode visit(SemiJoinNode node) {
            return new SemiJoinNode(node.left().accept(this), node.right().accept(this),
                    stripLocations(node.condition()));
        }

        @Override
        public RelNode visit(AntiJoinNode node) {
            return new AntiJoinNode(node.left().accept(this), node.right().accept(this),
                    stripLocations(node.condition()));
        }

        @Override
        public RelNode visit(PairwiseUniversalNode node) {
            return new PairwiseUniversalNode(node.left().accept(this), node.right().accept(this),
                    stripLocations(node.condition()));
        }

        @Override
        public RelNode visit(AsOfJoinNode node) {
            return new AsOfJoinNode(node.left().accept(this), node.right().accept(this),
                    stripLocations(node.condition()),
                    node.tolerance().map(AstLocations::stripLocations), node.inner(), node.tieBreak(),
                    SourceLocation.UNKNOWN);
        }

        @Override
        public RelNode visit(IntervalJoinNode node) {
            return new IntervalJoinNode(node.left().accept(this), node.right().accept(this),
                    node.relation(), node.leftStart(), node.leftEnd(),
                    node.rightStart(), node.rightEnd());
        }

        @Override
        public RelNode visit(ProductNode node) {
            return new ProductNode(node.left().accept(this), node.right().accept(this));
        }

        @Override
        public RelNode visit(UnionNode node) {
            return new UnionNode(node.left().accept(this), node.right().accept(this));
        }

        @Override
        public RelNode visit(UnionAllNode node) {
            return new UnionAllNode(node.left().accept(this), node.right().accept(this));
        }

        @Override
        public RelNode visit(OuterUnionNode node) {
            return new OuterUnionNode(node.left().accept(this), node.right().accept(this));
        }

        @Override
        public RelNode visit(DifferenceNode node) {
            return new DifferenceNode(node.left().accept(this), node.right().accept(this));
        }

        @Override
        public RelNode visit(IntersectionNode node) {
            return new IntersectionNode(node.left().accept(this), node.right().accept(this));
        }

        @Override
        public RelNode visit(DivisionNode node) {
            return new DivisionNode(node.left().accept(this), node.right().accept(this));
        }

        @Override
        public RelNode visit(SymmetricDifferenceNode node) {
            return new SymmetricDifferenceNode(node.left().accept(this), node.right().accept(this));
        }

        @Override
        public RelNode visit(CompositionNode node) {
            return new CompositionNode(node.left().accept(this), node.right().accept(this));
        }

        @Override
        public RelNode visit(AggregationNode node) {
            List<GroupingKey> keys = node.groupingKeys().stream()
                    .map(AstLocations::stripGroupingKey).toList();
            List<AggregateFunction> aggs = node.aggregates().stream()
                    .map(AstLocations::stripAgg).toList();
            return new AggregationNode(keys, aggs, node.input().accept(this), SourceLocation.UNKNOWN);
        }

        @Override
        public RelNode visit(SortNode node) {
            return new SortNode(stripSpecs(node.sortSpecs()), node.input().accept(this));
        }

        @Override
        public RelNode visit(LimitNode node) {
            return new LimitNode(node.offset(), node.count(), node.input().accept(this));
        }

        @Override
        public RelNode visit(DistinctNode node) {
            return new DistinctNode(node.input().accept(this));
        }

        @Override
        public RelNode visit(WhyNode node) {
            return new WhyNode(node.input().accept(this));
        }

        @Override
        public RelNode visit(UnnestNode node) {
            return new UnnestNode(node.column(), node.outer(), node.ordinalityColumn(),
                    node.input().accept(this), SourceLocation.UNKNOWN);
        }

        @Override
        public RelNode visit(ClosureNode node) {
            return new ClosureNode(node.input().accept(this), node.fromColumn(),
                    node.toColumn(), node.undirected(), node.reflexive(),
                    node.boundSource().map(AstLocations::stripLocations),
                    node.boundTarget().map(AstLocations::stripLocations),
                    SourceLocation.UNKNOWN);
        }

        @Override
        public RelNode visit(ClusterNode node) {
            return new ClusterNode(node.input().accept(this), node.fromColumn(),
                    node.toColumn(), node.labelColumn());
        }

        @Override
        public RelNode visit(PathNode node) {
            return new PathNode(node.input().accept(this), node.fromColumn(),
                    node.toColumn(), node.undirected(), node.minHops(), node.maxHops(),
                    node.depthColumn(),
                    node.boundSource().map(AstLocations::stripLocations),
                    node.boundTarget().map(AstLocations::stripLocations),
                    SourceLocation.UNKNOWN);
        }

        @Override
        public RelNode visit(TraceNode node) {
            return new TraceNode(node.input().accept(this), node.fromColumn(),
                    node.toColumn(), node.undirected(), node.weightColumn(), node.sense(),
                    node.pathColumn(),
                    node.boundSource().map(AstLocations::stripLocations),
                    node.boundTarget().map(AstLocations::stripLocations),
                    SourceLocation.UNKNOWN);
        }

        @Override
        public RelNode visit(UniversalNode node) {
            return new UniversalNode(node.groupingAttributes(), stripLocations(node.predicate()),
                    node.input().accept(this));
        }

        @Override
        public RelNode visit(SampleNode node) {
            return new SampleNode(node.probability(), node.seed(), node.input().accept(this),
                    SourceLocation.UNKNOWN);
        }

        @Override
        public RelNode visit(ReservoirSampleNode node) {
            return new ReservoirSampleNode(node.count(), node.seed(), node.input().accept(this),
                    SourceLocation.UNKNOWN);
        }

        @Override
        public RelNode visit(SolveNode node) {
            return new SolveNode(stripLocations(node.left()), stripLocations(node.right()),
                    node.input().accept(this));
        }

        @Override
        public RelNode visit(OptimizeNode node) {
            List<OptimizeConstraint> constraints = node.constraints().stream()
                    .map(c -> new OptimizeConstraint(stripLocations(c.expr()), c.op(), c.bound()))
                    .toList();
            return new OptimizeNode(node.sense(), stripLocations(node.objective()), constraints,
                    node.groupingKeys(), node.allocation(), node.input().accept(this),
                    SourceLocation.UNKNOWN);
        }

        @Override
        public RelNode visit(TopKNode node) {
            return new TopKNode(node.groupingAttributes(), stripSpecs(node.sortSpecs()),
                    node.offset(), node.count(), node.input().accept(this), SourceLocation.UNKNOWN);
        }

        @Override
        public RelNode visit(FixpointNode node) {
            return new FixpointNode(node.name(), node.base().accept(this), node.step().accept(this));
        }

        @Override
        public RelNode visit(RecursiveRefNode node) {
            return new RecursiveRefNode(node.name());
        }

        @Override
        public RelNode visit(CoverNode node) {
            return new CoverNode(node.strength(), node.exact(), node.input().accept(this),
                    SourceLocation.UNKNOWN);
        }

        @Override
        public RelNode visit(DownsampleNode node) {
            return new DownsampleNode(node.timestampColumn(), node.interval(), node.function(),
                    node.groupingKeys(), node.maxRows(), node.input().accept(this), SourceLocation.UNKNOWN);
        }

        @Override
        public RelNode visit(LateralJoinNode node) {
            return new LateralJoinNode(node.left().accept(this), node.functionName(),
                    node.arguments().stream().map(AstLocations::stripLocations).toList());
        }

        @Override
        public RelNode visit(WindowNode node) {
            return new WindowNode(stripWindowFunction(node.function()), node.partitionKeys(),
                    stripSpecs(node.sortSpecs()), node.frame(), node.outputColumn(),
                    node.input().accept(this), SourceLocation.UNKNOWN);
        }

        @Override
        public RelNode visit(SessionizeNode node) {
            return new SessionizeNode(node.input().accept(this), node.orderColumn(),
                    stripLocations(node.threshold()), node.partitionKeys(), node.sessionColumn(),
                    SourceLocation.UNKNOWN);
        }

        @Override
        public RelNode visit(TreeNode node) {
            return new TreeNode(node.input().accept(this), node.keyColumn(), node.parentColumn(),
                    stripSpecs(node.orderSpecs()), node.childrenColumn(), SourceLocation.UNKNOWN);
        }

        @Override
        public RelNode visit(UnpivotNode node) {
            return new UnpivotNode(node.columns(), node.nameColumn(), node.valueColumn(),
                    node.input().accept(this), SourceLocation.UNKNOWN);
        }

        @Override
        public RelNode visit(PivotNode node) {
            return new PivotNode(node.valueColumn(), node.keyColumn(), node.groupKeys(),
                    node.input().accept(this), SourceLocation.UNKNOWN);
        }
    }
}

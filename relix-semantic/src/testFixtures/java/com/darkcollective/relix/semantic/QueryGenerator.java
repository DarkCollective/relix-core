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
package com.darkcollective.relix.semantic;

import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.RankingFunction;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SortSpecification;
import com.darkcollective.relix.ast.WindowFrame;
import com.darkcollective.relix.ast.WindowFunction;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.relation.RelationSymbol;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Supplier;

import static com.darkcollective.relix.ast.AstBuilders.agg;
import static com.darkcollective.relix.ast.AstBuilders.and;
import static com.darkcollective.relix.ast.AstBuilders.closure;
import static com.darkcollective.relix.ast.AstBuilders.composition;
import static com.darkcollective.relix.ast.AstBuilders.division;
import static com.darkcollective.relix.ast.AstBuilders.fixpoint;
import static com.darkcollective.relix.ast.AstBuilders.antiJoin;
import static com.darkcollective.relix.ast.AstBuilders.arith;
import static com.darkcollective.relix.ast.AstBuilders.asc;
import static com.darkcollective.relix.ast.AstBuilders.attr;
import static com.darkcollective.relix.ast.AstBuilders.cmp;
import static com.darkcollective.relix.ast.AstBuilders.desc;
import static com.darkcollective.relix.ast.AstBuilders.difference;
import static com.darkcollective.relix.ast.AstBuilders.distinct;
import static com.darkcollective.relix.ast.AstBuilders.elementOf;
import static com.darkcollective.relix.ast.AstBuilders.groupBy;
import static com.darkcollective.relix.ast.AstBuilders.intersection;
import static com.darkcollective.relix.ast.AstBuilders.rightJoin;
import static com.darkcollective.relix.ast.AstBuilders.fullJoin;
import static com.darkcollective.relix.ast.AstBuilders.sample;
import static com.darkcollective.relix.ast.AstBuilders.reservoirSample;
import static com.darkcollective.relix.ast.AstBuilders.join;
import static com.darkcollective.relix.ast.AstBuilders.leftJoin;
import static com.darkcollective.relix.ast.AstBuilders.like;
import static com.darkcollective.relix.ast.AstBuilders.limit;
import static com.darkcollective.relix.ast.AstBuilders.naturalJoin;
import static com.darkcollective.relix.ast.AstBuilders.not;
import static com.darkcollective.relix.ast.AstBuilders.nullPred;
import static com.darkcollective.relix.ast.AstBuilders.num;
import static com.darkcollective.relix.ast.AstBuilders.or;
import static com.darkcollective.relix.ast.AstBuilders.product;
import static com.darkcollective.relix.ast.AstBuilders.project;
import static com.darkcollective.relix.ast.AstBuilders.projected;
import static com.darkcollective.relix.ast.AstBuilders.rel;
import static com.darkcollective.relix.ast.AstBuilders.rename;
import static com.darkcollective.relix.ast.AstBuilders.select;
import static com.darkcollective.relix.ast.AstBuilders.semiJoin;
import static com.darkcollective.relix.ast.AstBuilders.set;
import static com.darkcollective.relix.ast.AstBuilders.sort;
import static com.darkcollective.relix.ast.AstBuilders.str;
import static com.darkcollective.relix.ast.AstBuilders.symmetricDifference;
import static com.darkcollective.relix.ast.AstBuilders.outerUnion;
import static com.darkcollective.relix.ast.AstBuilders.recRef;
import static com.darkcollective.relix.ast.AstBuilders.topK;
import static com.darkcollective.relix.ast.AstBuilders.union;
import static com.darkcollective.relix.ast.AstBuilders.unnest;
import static com.darkcollective.relix.ast.AstBuilders.universal;
import static com.darkcollective.relix.ast.AstBuilders.window;
import static com.darkcollective.relix.ast.AstBuilders.unionAll;

/**
 * Builds random, type-correct {@link RelNode} trees over a fixed set of inline relations,
 * for {@link RandomQueryEquivalence} to run optimized and unoptimized and diff (#722).
 *
 * <h2>It asks the engine for every schema rather than computing one</h2>
 *
 * A generator that derived each operator's output schema itself would be a second
 * implementation of {@code SchemaInferenceVisitor}, wrong in its own way, and its bugs
 * would surface as differential failures blamed on the optimizer. So every candidate
 * sub-tree is handed to {@link SchemaInference#annotate} and <em>asked</em> what it
 * produces; a candidate the engine cannot type is discarded and another drawn. That also
 * makes the type-correctness requirement in #722's scope structural rather than
 * aspirational — a σ comparing a NUMBER column to a string is never built, because the
 * column's type is read off the schema the engine just returned.
 *
 * <h2>Two shapes are deliberately constrained, both for the tie problem</h2>
 *
 * <ul>
 *   <li><b>λ is only ever generated over a totally ordered input.</b> {@code λ n} over an
 *       unordered bag is a query with several correct answers — it returns <em>some</em>
 *       n rows — so a rewrite that changed which n came back would be reported as a
 *       defect when it is the query that is underdetermined. The generator sorts by
 *       <em>every</em> column first, which leaves ties only between wholly identical
 *       rows, and those are interchangeable.</li>
 *   <li><b>The two sides of a set operation are grown from one schema group.</b> Union
 *       compatibility by construction, rather than generate-and-reject, which at these
 *       depths would reject nearly everything.</li>
 * </ul>
 *
 * <h2>The weights are the point, not an accident</h2>
 *
 * A uniform draw over the operators produces trees the optimizer has nothing to say
 * about, and a run that fires no rules tests nothing however many trees it draws. σ and π
 * are weighted up because most rules match on them; {@code RandomQueryEquivalenceTest}
 * asserts the resulting firing spread rather than trusting it.
 */
public final class QueryGenerator {

    /** A generated sub-tree together with the schema the engine infers for it. */
    public record Gen(RelNode node, Schema schema) {}

    /** How many times an operator is re-drawn before the generator settles for a leaf. */
    private static final int ATTEMPTS = 6;

    /** Literals drawn for NUMBER comparisons — values on and around those in the data. */
    private static final List<String> NUMBERS = List.of("1", "2", "5", "10", "20", "50");

    /** Literals drawn for STRING comparisons — values in the data, and one that is not. */
    private static final List<String> STRINGS = List.of("a", "b", "c", "z");

    private static final List<ComparisonOperator> COMPARISONS = List.of(
            ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL,
            ComparisonOperator.LESS, ComparisonOperator.LESS_EQUAL,
            ComparisonOperator.GREATER, ComparisonOperator.GREATER_EQUAL);

    private static final List<AggregateOperator> AGGREGATES = List.of(
            AggregateOperator.SUM, AggregateOperator.AVG, AggregateOperator.COUNT,
            AggregateOperator.MIN, AggregateOperator.MAX);

    private final Random rng;
    private final SemanticModel model;
    private final List<String> relations;

    /** Relation names grouped by identical schema — the candidates for a set operation. */
    private final List<List<String>> compatibleGroups;

    /** Supplies fresh column names, so a rename can never collide with what it renames. */
    private int freshCounter;

    public QueryGenerator(Random rng, SemanticModel model, List<String> relations) {
        this.rng = rng;
        this.model = model;
        this.relations = List.copyOf(relations);
        this.compatibleGroups = groupBySchema(relations);
    }

    private List<List<String>> groupBySchema(List<String> names) {
        Map<Schema, List<String>> bySchema = new LinkedHashMap<>();
        for (String name : names) {
            baseSchema(name).ifPresent(s ->
                    bySchema.computeIfAbsent(s, _ -> new ArrayList<>()).add(name));
        }
        return List.copyOf(bySchema.values());
    }

    private Optional<Schema> baseSchema(String name) {
        return model.symbolTable().lookupRelation(name).map(RelationSymbol::schema);
    }

    // =========================================================================
    // Entry points
    // =========================================================================

    /**
     * Draws one tree of at most {@code depth} operators above its leaves.
     *
     * @param depth the remaining operator budget; 0 yields a bare relation reference
     * @return a tree the engine can type; never null
     */
    public Gen generate(int depth) {
        if (depth <= 0) {
            return leaf();
        }
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            Optional<Gen> candidate = drawOperator(depth);
            if (candidate.isPresent()) {
                return candidate.get();
            }
        }
        return leaf();
    }

    private Gen leaf() {
        String name = pick(relations);
        return typed(rel(name)).orElseThrow(
                () -> new IllegalStateException("base relation " + name + " has no schema"));
    }

    // =========================================================================
    // The weighted draw
    // =========================================================================

    private Optional<Gen> drawOperator(int depth) {
        List<Weighted> table = List.of(
                new Weighted(6, () -> selection(depth)),
                new Weighted(4, () -> projection(depth)),
                new Weighted(2, () -> aggregation(depth)),
                new Weighted(2, () -> duplicateElimination(depth)),
                new Weighted(2, () -> sorting(depth)),
                new Weighted(2, () -> limiting(depth)),
                new Weighted(2, () -> renaming(depth)),
                new Weighted(3, () -> conditionalJoin(depth)),
                new Weighted(2, () -> natural(depth)),
                new Weighted(2, () -> cartesian(depth)),
                new Weighted(4, () -> setOperation(depth)),
                new Weighted(2, () -> ranking(depth)),
                new Weighted(2, () -> rollingWindow(depth)),
                new Weighted(2, () -> quantification(depth)),
                new Weighted(3, () -> nesting(depth)),
                new Weighted(2, () -> transitiveClosure(depth)),
                new Weighted(2, () -> recursion(depth)),
                new Weighted(2, () -> divisionOrComposition(depth)),
                new Weighted(2, () -> sampling(depth)));
        return weightedPick(table).draw().get();
    }

    private record Weighted(int weight, Supplier<Optional<Gen>> draw) {}

    private Weighted weightedPick(List<Weighted> table) {
        int total = table.stream().mapToInt(Weighted::weight).sum();
        int roll = rng.nextInt(total);
        for (Weighted w : table) {
            roll -= w.weight();
            if (roll < 0) {
                return w;
            }
        }
        return table.getLast();
    }

    // =========================================================================
    // Unary operators
    // =========================================================================

    private Optional<Gen> selection(int depth) {
        Gen in = generate(depth - 1);
        return predicate(in.schema(), 2).flatMap(p -> typed(select(p, in.node())));
    }

    private Optional<Gen> projection(int depth) {
        Gen in = generate(depth - 1);
        List<ColumnDefinition> usable = usableColumns(in.schema());
        if (usable.isEmpty()) {
            return Optional.empty();
        }
        List<ColumnDefinition> chosen = pickSome(usable, 1);
        List<ProjectedAttribute> items = new ArrayList<>();
        for (ColumnDefinition c : chosen) {
            items.add(projected(attr(c.name())));
        }
        // A derived column now and then, so PROJ/EXPR rules have an expression to fold.
        numericColumns(usable).stream().findFirst().ifPresent(n -> {
            if (rng.nextInt(4) == 0) {
                items.add(projected(
                        arith(attr(n.name()), ArithmeticOperator.PLUS, num("1")), fresh()));
            }
        });
        return typed(project(List.copyOf(items), in.node()));
    }

    private Optional<Gen> aggregation(int depth) {
        Gen in = generate(depth - 1);
        List<ColumnDefinition> usable = usableColumns(in.schema());
        List<ColumnDefinition> numeric = numericColumns(usable);
        if (numeric.isEmpty()) {
            return Optional.empty();
        }
        List<String> keys = new ArrayList<>();
        if (!usable.isEmpty() && rng.nextInt(4) > 0) {          // a scalar γ one time in four
            for (ColumnDefinition c : pickSome(usable, 1)) {
                keys.add(c.name());
            }
        }
        List<AggregateFunction> aggregates = new ArrayList<>();
        int count = 1 + rng.nextInt(2);
        for (int i = 0; i < count; i++) {
            aggregates.add(agg(pick(AGGREGATES), pick(numeric).name(), fresh()));
        }
        return typed(groupBy(List.copyOf(keys), List.copyOf(aggregates), in.node()));
    }

    private Optional<Gen> duplicateElimination(int depth) {
        Gen in = generate(depth - 1);
        return typed(distinct(in.node()));
    }

    private Optional<Gen> sorting(int depth) {
        Gen in = generate(depth - 1);
        List<ColumnDefinition> usable = usableColumns(in.schema());
        if (usable.isEmpty()) {
            return Optional.empty();
        }
        List<SortSpecification> keys = new ArrayList<>();
        for (ColumnDefinition c : pickSome(usable, 1)) {
            keys.add(rng.nextBoolean() ? asc(c.name()) : desc(c.name()));
        }
        return typed(sort(List.copyOf(keys), in.node()));
    }

    /**
     * λ over a <em>totally</em> ordered input, never over a bare bag — see the class
     * comment. The total order is every column ascending, so the only ties it leaves are
     * between rows that are equal in every column and therefore interchangeable.
     */
    private Optional<Gen> limiting(int depth) {
        Gen in = generate(depth - 1);
        return totallyOrdered(in).flatMap(ordered ->
                typed(limit(1 + rng.nextInt(4), ordered.node())));
    }

    /**
     * {@code TOP k} and a ranking {@code WINDOW}, both ordered by <em>every</em> column.
     *
     * <p>The full-column order is what makes them comparable at all. Both operators pick
     * rows or number them by a sort key, and a tie has two correct answers — so ordering
     * on one column would let an optimized and an unoptimized run disagree lawfully, and
     * the search would report the engine's own freedom as a defect. Ordered on every
     * column, two rows tie only when they are equal in all of them, and which of those
     * the operator keeps cannot change the bag it returns.
     */
    private Optional<Gen> ranking(int depth) {
        Gen in = generate(depth - 1);
        List<ColumnDefinition> usable = usableColumns(in.schema());
        if (usable.isEmpty()) {
            return Optional.empty();
        }
        List<SortSpecification> keys = totalOrderOver(usable);
        List<String> partition = rng.nextBoolean()
                ? List.of() : List.of(pick(usable).name());

        if (rng.nextBoolean()) {
            return typed(topK(partition, keys, 1 + rng.nextInt(3), in.node()));
        }
        return typed(window(
                new WindowFunction.RankingWindow(RankingFunction.ROW_NUMBER, Optional.empty()),
                partition, keys, new WindowFrame.PartitionFrame(), fresh(), in.node()));
    }

    /** A cumulative aggregate window, over the same total order and for the same reason. */
    private Optional<Gen> rollingWindow(int depth) {
        Gen in = generate(depth - 1);
        List<ColumnDefinition> usable = usableColumns(in.schema());
        List<ColumnDefinition> numeric = numericColumns(usable);
        if (usable.isEmpty() || numeric.isEmpty()) {
            return Optional.empty();
        }
        return typed(window(
                new WindowFunction.AggregateWindow(pick(AGGREGATES), attr(pick(numeric).name())),
                rng.nextBoolean() ? List.of() : List.of(pick(usable).name()),
                totalOrderOver(usable),
                rng.nextBoolean() ? new WindowFrame.CumulativeFrame() : new WindowFrame.BoundedFrame(2),
                fresh(), in.node()));
    }

    /** ∀ — the group survives only when every one of its rows satisfies the predicate. */
    private Optional<Gen> quantification(int depth) {
        Gen in = generate(depth - 1);
        List<ColumnDefinition> usable = usableColumns(in.schema());
        if (usable.isEmpty()) {
            return Optional.empty();
        }
        List<String> keys = rng.nextBoolean()
                ? List.of() : List.of(pick(usable).name());
        return predicate(in.schema(), 1)
                .flatMap(p -> typed(universal(keys, p, in.node())));
    }

    /**
     * {@code μ g (γ keys, COLLECT(x) → g (R))} — the shape the nest/unnest laws are about.
     *
     * <p>Gathering a column into an array and immediately exploding it is the identity on
     * the rows that survive the grouping, which is what {@code NEST-001} rewrites away.
     * The generator builds the pair rather than an array-valued fixture because an array
     * has to come from somewhere, and this is where one comes from in practice.
     */
    private Optional<Gen> nesting(int depth) {
        Gen drawn = generate(depth - 1);
        // COLLECT over a *totally ordered* input, never a bare bag — the same rule λ
        // follows here and for the same reason. `docs/reference/aggregates/collect.md`
        // says order within the collected array is not guaranteed unless the input is
        // ordered, so gathering an unordered one asks a question with several correct
        // answers, and any rewrite that reorders rows would look like a defect.
        Optional<Gen> ordered = totallyOrdered(drawn);
        if (ordered.isEmpty()) {
            return Optional.empty();
        }
        Gen in = ordered.get();
        List<ColumnDefinition> usable = usableColumns(in.schema());
        if (usable.isEmpty()) {
            return Optional.empty();
        }
        List<String> keys = pickSome(usable, 1).stream().map(ColumnDefinition::name).toList();
        String gathered = fresh();
        RelNode grouped = groupBy(keys,
                List.of(agg(AggregateOperator.COLLECT, pick(usable).name(), gathered)),
                in.node());
        // Sometimes leave the nested relation as it is: the laws are about what happens
        // when the μ is there, and a γ COLLECT that nothing explodes is the other half.
        return typed(rng.nextInt(4) == 0 ? grouped : unnest(gathered, grouped));
    }

    /**
     * {@code CLOSURE from, to (R)} over two columns of one type — a reachability fold.
     *
     * <p>Recursion is where a rewrite has the most room to be wrong, because the rule that
     * pushes a selection into a fixpoint changes what the recursion <em>derives</em> and
     * not merely what survives it.
     */
    private Optional<Gen> transitiveClosure(int depth) {
        Gen in = generate(depth - 1);
        List<ColumnDefinition> numeric = numericColumns(usableColumns(in.schema()));
        if (numeric.size() < 2) {
            return Optional.empty();
        }
        List<ColumnDefinition> pair = pickSome(numeric, 2);
        if (pair.size() < 2 || pair.get(0).name().equals(pair.get(1).name())) {
            return Optional.empty();
        }
        return typed(closure(pair.get(0).name(), pair.get(1).name(),
                rng.nextBoolean(), in.node()));
    }

    /**
     * {@code FIX g (base, step)} over a step that provably reaches a fixed point.
     *
     * <p>Recursion is where a rewrite has the most room to be wrong, because
     * {@code FIX-001} changes what the recursion <em>derives</em> rather than what
     * survives it — and the rule only has something to push once a σ lands above one of
     * these, which the generator arranges by drawing selections everywhere.
     *
     * <p>The steps are drawn from shapes whose least fixed point is reached in one round:
     * each derives a subset of what the reference already holds, or unions the base back
     * in. A step that derived indefinitely would exhaust {@code maxFixpointRounds} and
     * raise, which the search reads as a skipped draw rather than a finding — so it would
     * cost the budget without ever being wrong, which is the worse failure for a search.
     */
    private Optional<Gen> recursion(int depth) {
        Gen base = generate(depth - 1);
        List<ColumnDefinition> usable = usableColumns(base.schema());
        if (usable.isEmpty()) {
            return Optional.empty();
        }
        String name = fresh();
        RelNode ref = recRef(name);
        RelNode step = switch (rng.nextInt(4)) {
            case 0 -> ref;
            case 1 -> distinct(ref);
            case 2 -> predicate(base.schema(), 1).<RelNode>map(p -> select(p, ref)).orElse(ref);
            default -> union(ref, base.node());
        };
        return typed(fixpoint(name, base.node(), step));
    }

    /**
     * {@code R ÷ S} and {@code R ∘ S}, the two operators whose heading arithmetic decides
     * what they mean.
     *
     * <p>Division answers <em>paired with every</em>, which is the hardest claim in the
     * algebra to get right and the one a rewrite is most likely to disturb without
     * changing the row count enough to notice. Both need a divisor or a right side whose
     * columns stand in a particular relation to the left's, so both are built by
     * projecting one fixture down to a subset of the other's heading rather than by
     * drawing two sub-trees and hoping.
     *
     * @param depth the remaining depth budget
     * @return the operator, or empty when the drawn heading cannot be split
     */
    private Optional<Gen> divisionOrComposition(int depth) {
        Gen dividend = generate(depth - 1);
        List<ColumnDefinition> usable = usableColumns(dividend.schema());
        if (usable.size() < 2) {
            return Optional.empty();      // nothing left over once a column is given away
        }
        ColumnDefinition shared = pick(usable);
        Optional<Gen> divisor = typed(project(
                List.of(projected(attr(shared.name()))), dividend.node()));
        if (divisor.isEmpty()) {
            return Optional.empty();
        }
        // ÷ takes a divisor whose columns are a subset of the dividend's; ∘ joins on the
        // shared ones and drops them. Projecting the dividend gives both what they need,
        // and the shared column carries values that actually match.
        return typed(rng.nextBoolean()
                ? division(dividend.node(), divisor.get().node())
                : composition(dividend.node(), divisor.get().node()));
    }

    /** Every column ascending — a sort key no two distinct rows can tie on. */
    private static List<SortSpecification> totalOrderOver(List<ColumnDefinition> columns) {
        return columns.stream().map(c -> asc(c.name())).toList();
    }

    private Optional<Gen> renaming(int depth) {
        Gen in = generate(depth - 1);
        if (rng.nextBoolean()) {
            return typed(rename(fresh(), List.of(), in.node()));       // relation-only ρ
        }
        List<ColumnDefinition> columns = in.schema().columns();
        if (columns.isEmpty()) {
            return Optional.empty();
        }
        List<String> names = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            names.add(fresh());
        }
        return typed(rename(fresh(), List.copyOf(names), in.node()));
    }

    // =========================================================================
    // Binary operators
    // =========================================================================

    /**
     * A theta, left-outer, semi or anti join. The right side's columns are renamed first,
     * so the condition can never name a column both sides declare — an ambiguity that
     * would fail analysis rather than test anything.
     */
    private Optional<Gen> conditionalJoin(int depth) {
        Gen left = generate(depth - 1);
        Optional<Gen> right = renamedApart(generate(depth - 1));
        if (right.isEmpty()) {
            return Optional.empty();
        }
        Optional<Predicate> condition = joinCondition(left.schema(), right.get().schema());
        if (condition.isEmpty()) {
            return Optional.empty();
        }
        RelNode l = left.node();
        RelNode r = right.get().node();
        Predicate c = condition.get();
        // All six conditional joins. ⟖ and ⟗ were missing, and ⟗ is the one that is
        // blocking rather than streaming — it buffers both sides and has a materialisation
        // mode of its own — so the family's only [bag] member was the one not drawn.
        return typed(switch (rng.nextInt(6)) {
            case 0 -> join(l, r, c);
            case 1 -> leftJoin(l, r, c);
            case 2 -> rightJoin(l, r, c);
            case 3 -> fullJoin(l, r, c);
            case 4 -> semiJoin(l, r, c);
            default -> antiJoin(l, r, c);
        });
    }

    private Optional<Gen> natural(int depth) {
        Gen left = generate(depth - 1);
        Gen right = generate(depth - 1);
        return typed(naturalJoin(left.node(), right.node()));
    }

    private Optional<Gen> cartesian(int depth) {
        Gen left = generate(depth - 1);
        return renamedApart(generate(depth - 1))
                .flatMap(right -> typed(product(left.node(), right.node())));
    }

    /**
     * A set operation over two sides grown from one schema group, so they are union
     * compatible by construction.
     */
    /**
     * A sample, always <b>seeded</b>.
     *
     * <p>An unseeded one reads the system RNG, so the optimized and unoptimized runs would
     * draw different rows and the search would report the engine's own documented
     * behaviour as a disagreement. A seed makes the operator reproducible, which is the
     * property the whole comparison rests on — and it is also what lets the planner share
     * a sub-plan containing one, so the draw exercises that gate rather than avoiding it.
     */
    private Optional<Gen> sampling(int depth) {
        Gen input = generate(depth - 1);
        long seed = rng.nextInt(1_000);
        return typed(rng.nextBoolean()
                ? sample(0.5d, java.util.Optional.of(seed), input.node())
                : reservoirSample(1 + rng.nextInt(3), java.util.Optional.of(seed), input.node()));
    }

    private Optional<Gen> setOperation(int depth) {
        if (compatibleGroups.isEmpty()) {
            return Optional.empty();
        }
        List<String> group = pick(compatibleGroups);
        Gen left = schemaPreserving(depth - 1, group);
        Gen right = schemaPreserving(depth - 1, group);
        RelNode l = left.node();
        RelNode r = right.node();
        return typed(switch (rng.nextInt(6)) {
            case 0 -> union(l, r);
            case 1 -> intersection(l, r);
            case 2 -> difference(l, r);
            case 3 -> unionAll(l, r);
            case 4 -> outerUnion(l, r);
            default -> symmetricDifference(l, r);
        });
    }

    /**
     * Grows a tree whose schema is the group's own — the operators that leave a heading
     * alone, over the relations that share it.
     */
    private Gen schemaPreserving(int depth, List<String> group) {
        Gen here = typed(rel(pick(group))).orElseThrow();
        if (depth <= 0) {
            return here;
        }
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            Gen in = schemaPreserving(depth - 1, group);
            Optional<Gen> next = switch (rng.nextInt(5)) {
                case 0 -> predicate(in.schema(), 2).flatMap(p -> typed(select(p, in.node())));
                case 1 -> typed(distinct(in.node()));
                case 2 -> sortOn(in);
                case 3 -> totallyOrdered(in).flatMap(o -> typed(limit(1 + rng.nextInt(4), o.node())));
                default -> typed(rename(fresh(), List.of(), in.node()));
            };
            if (next.isPresent()) {
                return next.get();
            }
        }
        return here;
    }

    private Optional<Gen> sortOn(Gen in) {
        List<ColumnDefinition> usable = usableColumns(in.schema());
        if (usable.isEmpty()) {
            return Optional.empty();
        }
        ColumnDefinition c = pick(usable);
        return typed(sort(List.of(rng.nextBoolean() ? asc(c.name()) : desc(c.name())), in.node()));
    }

    /** Wraps a sub-tree in a τ over every column, unless it is already so ordered. */
    private Optional<Gen> totallyOrdered(Gen in) {
        List<ColumnDefinition> usable = usableColumns(in.schema());
        if (usable.isEmpty() || usable.size() != in.schema().width()) {
            return Optional.empty();       // a column that cannot be a sort key leaves ties
        }
        List<SortSpecification> keys = usable.stream().map(c -> asc(c.name())).toList();
        return typed(sort(keys, in.node()));
    }

    /** Renames every column of a sub-tree to a fresh name, so no join condition is ambiguous. */
    private Optional<Gen> renamedApart(Gen in) {
        List<ColumnDefinition> columns = in.schema().columns();
        if (columns.isEmpty()) {
            return Optional.empty();
        }
        List<String> names = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            names.add(fresh());
        }
        return typed(rename(fresh(), List.copyOf(names), in.node()));
    }

    /**
     * Prefers a cross-side equality — the shape the join rules and the merge planner
     * match on — and falls back to a one-sided predicate, which is what
     * {@code JOIN-002} exists to pull out of a product.
     */
    private Optional<Predicate> joinCondition(Schema left, Schema right) {
        List<ColumnDefinition> ls = usableColumns(left);
        List<ColumnDefinition> rs = usableColumns(right);
        List<Predicate> equalities = new ArrayList<>();
        for (ColumnDefinition l : ls) {
            for (ColumnDefinition r : rs) {
                if (l.type().equals(r.type())) {
                    equalities.add(cmp(attr(l.name()), ComparisonOperator.EQUAL, attr(r.name())));
                }
            }
        }
        if (!equalities.isEmpty() && rng.nextInt(4) > 0) {
            return Optional.of(pick(equalities));
        }
        return predicate(left, 1);
    }

    // =========================================================================
    // Predicates and operands
    // =========================================================================

    /**
     * A predicate over {@code schema}, type-correct by reading each column's own type.
     *
     * @param depth how many more levels of ∧/∨/¬ may be built above a comparison
     */
    private Optional<Predicate> predicate(Schema schema, int depth) {
        List<ColumnDefinition> usable = usableColumns(schema);
        if (usable.isEmpty()) {
            return Optional.empty();
        }
        if (depth > 0 && rng.nextInt(3) == 0) {
            Optional<Predicate> l = predicate(schema, depth - 1);
            Optional<Predicate> r = predicate(schema, depth - 1);
            if (l.isPresent() && r.isPresent()) {
                return Optional.of(switch (rng.nextInt(3)) {
                    case 0 -> and(l.get(), r.get());
                    case 1 -> or(l.get(), r.get());
                    default -> not(l.get());
                });
            }
        }
        ColumnDefinition column = pick(usable);
        ScalarType type = scalarType(column).orElseThrow();
        return Optional.of(switch (rng.nextInt(8)) {
            case 0 -> nullPred(attr(column.name()), rng.nextBoolean());
            case 1 -> elementOf(attr(column.name()),
                    set(literal(type), literal(type)));
            case 2 -> type == ScalarType.STRING
                    ? like(attr(column.name()), str(pick(STRINGS) + "%"))
                    : cmp(attr(column.name()), pick(COMPARISONS), literal(type));
            case 3 -> sameTypeColumn(usable, type, column.name())
                    .<Predicate>map(other -> cmp(attr(column.name()), pick(COMPARISONS),
                            attr(other.name())))
                    .orElseGet(() -> cmp(attr(column.name()), pick(COMPARISONS), literal(type)));
            default -> cmp(attr(column.name()), pick(COMPARISONS), literal(type));
        });
    }

    private Optional<ColumnDefinition> sameTypeColumn(List<ColumnDefinition> usable,
                                                      ScalarType type, String exclude) {
        List<ColumnDefinition> matches = usable.stream()
                .filter(c -> !c.name().equals(exclude))
                .filter(c -> scalarType(c).filter(t -> t == type).isPresent())
                .toList();
        return matches.isEmpty() ? Optional.empty() : Optional.of(pick(matches));
    }

    /**
     * A literal of {@code type}, and of {@code type} rather than of whatever a string can
     * be read as.
     *
     * <p>The temporal arms are load-bearing, not tidiness. Everything that was not a
     * NUMBER or a BOOLEAN used to render as a plain string, which no fixture of inline
     * relations could notice because none of them has a temporal column. Against a
     * database table that does, {@code σ placed ∈ {"z"}} is a comparison the engine reads
     * as a type mismatch and answers with no rows, while the backend coerces the literal
     * and raises — so the generator was manufacturing a disagreement of its own rather
     * than finding one.
     */
    private Operand literal(ScalarType type) {
        return switch (type) {
            case NUMBER -> num(pick(NUMBERS));
            case BOOLEAN -> com.darkcollective.relix.ast.AstBuilders.bool(rng.nextBoolean());
            case DATE -> com.darkcollective.relix.ast.AstBuilders.date(pick(DATES));
            case TIME -> com.darkcollective.relix.ast.AstBuilders.time(pick(TIMES));
            case TIMESTAMP -> com.darkcollective.relix.ast.AstBuilders.timestamp(pick(TIMESTAMPS));
            case DURATION -> com.darkcollective.relix.ast.AstBuilders.duration(pick(DURATIONS));
            default -> str(pick(STRINGS));
        };
    }

    /** Temporal literals spanning the fixtures' own values and the boundaries around them. */
    private static final List<String> DATES =
            List.of("2020-03-01", "2022-01-01", "2024-02-29", "2030-01-01");
    private static final List<String> TIMES =
            List.of("00:00:00", "08:30:00", "23:45:10");
    private static final List<String> TIMESTAMPS =
            List.of("2024-01-15T08:30:00Z", "2024-02-15T23:45:10Z",
                    "2024-02-29T00:00:00Z", "2023-12-31T23:59:59Z");
    private static final List<String> DURATIONS =
            List.of("PT1H", "PT30M", "P1D");

    // =========================================================================
    // Schema helpers
    // =========================================================================

    /**
     * Asks the engine what a candidate sub-tree produces, and rejects it when the answer
     * is the {@code *:ANY} placeholder or an exception — the two ways a tree says it is
     * not a query.
     */
    private Optional<Gen> typed(RelNode node) {
        try {
            SchemaAnnotations annotations = SchemaInference.annotate(
                    model.symbolTable(), node, model.nodeSchemas(), model.functions());
            return annotations.get(node)
                    .filter(s -> !isUnresolved(s))
                    .filter(s -> !s.isOpen())
                    .map(s -> new Gen(node, s));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Whether a schema is the analyser's unresolved placeholder — one {@code *:ANY}
     * column, which is what a reference the symbol table could not resolve annotates as.
     * Recognised by shape because the engine's own constant is package-private; nothing
     * this generator builds should produce one, so a match means the draw was malformed
     * and is discarded.
     */
    private static boolean isUnresolved(Schema schema) {
        return schema.width() == 1
                && "*".equals(schema.columns().getFirst().name())
                && ScalarType.ANY.equals(schema.columns().getFirst().type());
    }

    /**
     * The columns a generated expression may name: scalar, comparable, and declared
     * exactly once — a name two branches of a product both carry is ambiguous, and
     * naming it would fail analysis rather than test anything.
     */
    private static List<ColumnDefinition> usableColumns(Schema schema) {
        List<ColumnDefinition> out = new ArrayList<>();
        for (ColumnDefinition c : schema.columns()) {
            boolean unique = schema.columns().stream()
                    .filter(o -> o.name().equalsIgnoreCase(c.name()))
                    .count() == 1;
            if (unique && scalarType(c).isPresent()) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * The scalar type of a column, when it has one a predicate can use. ANY is excluded
     * along with the nested types: a comparison against it is legal but says nothing
     * about what the value holds, so the generator cannot pick a literal for it.
     */
    private static Optional<ScalarType> scalarType(ColumnDefinition column) {
        Type type = column.type();
        if (type instanceof ScalarType s && s != ScalarType.ANY) {
            return Optional.of(s);
        }
        return Optional.empty();
    }

    private static List<ColumnDefinition> numericColumns(List<ColumnDefinition> columns) {
        return columns.stream()
                .filter(c -> scalarType(c).filter(t -> t == ScalarType.NUMBER).isPresent())
                .toList();
    }

    // =========================================================================
    // Drawing
    // =========================================================================

    private <T> T pick(List<T> from) {
        return from.get(rng.nextInt(from.size()));
    }

    /** A random non-empty sub-list, order preserved, of at least {@code least} elements. */
    private <T> List<T> pickSome(List<T> from, int least) {
        List<T> chosen = new ArrayList<>();
        for (T t : from) {
            if (rng.nextBoolean()) {
                chosen.add(t);
            }
        }
        while (chosen.size() < least) {
            T t = pick(from);
            if (!chosen.contains(t)) {
                chosen.add(t);
            }
        }
        return chosen;
    }

    private String fresh() {
        return "g" + freshCounter++;
    }
}

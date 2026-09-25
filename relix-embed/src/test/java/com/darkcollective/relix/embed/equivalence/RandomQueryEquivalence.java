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
package com.darkcollective.relix.embed.equivalence;

import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SortSpecification;
import com.darkcollective.relix.cost.DistinctnessSource;
import com.darkcollective.relix.cost.MonotoneGeneratorSource;
import com.darkcollective.relix.cost.OrderDeriver;
import com.darkcollective.relix.ast.Ordering;
import com.darkcollective.relix.events.QueryEventListener;
import com.darkcollective.relix.optimizer.OptimizationCode;
import com.darkcollective.relix.optimizer.internal.OptimizationContext;
import com.darkcollective.relix.optimizer.internal.QueryOptimizer;
import com.darkcollective.relix.optimizer.TransformationRecord;
import com.darkcollective.relix.processor.internal.ExecutionContext;
import com.darkcollective.relix.semantic.internal.RelationDeterminism;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.semantic.internal.SchemaInference;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.Schema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Runs one generated tree optimized and unoptimized and diffs the rows — the differential
 * half of #722, over the oracle {@link OptimizerEquivalence} already provides.
 *
 * <h2>What counts as a defect, and what counts as the generator's own fault</h2>
 *
 * The two sides are not symmetric, and the asymmetry is the whole classification:
 *
 * <ul>
 *   <li>The <b>unoptimized</b> side throwing means the generator built something that is
 *       not a query. That is a {@link Outcome.Skipped}, not a finding — nothing about the
 *       optimizer has been learnt.</li>
 *   <li>The <b>optimized</b> side throwing when the unoptimized side ran is a
 *       {@link Outcome.Disagreed}: whatever the query was, a rewrite broke it.</li>
 *   <li>Either side exceeding the materialization cap is a skip. The cap is this
 *       harness's own bound on a generated product-of-products, not a claim about the
 *       engine, so tripping it says nothing either way.</li>
 * </ul>
 *
 * That split is what lets the generator be robust without being perfect: a shape it types
 * wrongly costs a skipped draw rather than a false report, and the skip rate is asserted
 * so the budget cannot quietly drain away into invalid queries.
 *
 * <h2>Rows are compared as a multiset, and in order only when order is determined</h2>
 *
 * Multiset equality is asserted always, and — because rows are compared positionally —
 * catches a rewrite that permutes the output <em>heading</em>, which is the shape #555
 * had. Row <em>order</em> is asserted only when the original tree delivers an ordering
 * ({@link OrderDeriver}) <em>and</em> that ordering is total on the rows that came back.
 * A tie has two correct answers, so asserting a sequence across one would report the
 * engine's freedom as a defect.
 */
final class RandomQueryEquivalence {

    /**
     * The per-operator buffer cap for a generated run. Small enough that a nested product
     * is refused promptly, far above anything the fixtures can reach legitimately.
     */
    static final int MAX_MATERIALIZED_ROWS = 50_000;

    /** How the cap announces itself; see {@code MaterializationBudget}. */
    private static final String CAP_MARKER = "maxMaterializedRows";

    private RandomQueryEquivalence() {}

    // =========================================================================
    // Outcome
    // =========================================================================

    sealed interface Outcome {

        /** The rewrite preserved the answer; {@code fired} is what the optimizer did. */
        record Agreed(Set<OptimizationCode> fired) implements Outcome {}

        /** Nothing was learnt from this draw — an invalid query, or the cap. */
        record Skipped(String reason) implements Outcome {}

        /** The optimized and unoptimized runs disagree. */
        record Disagreed(RelNode tree, String detail) implements Outcome {}
    }

    // =========================================================================
    // One differential run
    // =========================================================================

    /**
     * Optimizes {@code tree}, runs both versions, and compares.
     *
     * @param tree  the generated query; must not be null
     * @param model the model carrying the inline relations it reads; must not be null
     * @return what the comparison established; never null
     */
    static Outcome check(RelNode tree, SemanticModel model) {
        if (!RelationDeterminism.isDeterministic(tree, model.symbolTable(), model.functions())) {
            // Nothing the generator builds should be volatile. This is the gate the spool
            // planner uses, kept here so that the day one is added the search reports a
            // skip rather than diffing two draws against each other.
            return new Outcome.Skipped("non-deterministic expression");
        }

        List<List<String>> unoptimizedRows;
        try {
            unoptimizedRows = OptimizerEquivalence.runRows(tree, model, MAX_MATERIALIZED_ROWS);
        } catch (RuntimeException e) {
            return new Outcome.Skipped(cause(e));
        }

        OptimizationContext ctx = new OptimizationContext(QueryEventListener.NONE,
                DistinctnessSource.NONE, MonotoneGeneratorSource.NONE, model.functions());
        RelNode optimized;
        try {
            optimized = new QueryOptimizer().optimize(tree, "generated", annotate(tree, model), ctx);
        } catch (RuntimeException e) {
            return new Outcome.Disagreed(tree, "the optimizer threw: " + cause(e));
        }

        List<List<String>> optimizedRows;
        try {
            optimizedRows = OptimizerEquivalence.runRows(optimized, model, MAX_MATERIALIZED_ROWS);
        } catch (RuntimeException e) {
            return exceededCap(e)
                    ? new Outcome.Skipped(cause(e))
                    : new Outcome.Disagreed(tree, "the optimized tree threw: " + cause(e));
        }

        if (!sameMultiset(unoptimizedRows, optimizedRows)) {
            return new Outcome.Disagreed(tree, rowDiff(unoptimizedRows, optimizedRows));
        }

        Optional<List<Integer>> keys = orderingColumns(tree, model);
        if (keys.isPresent() && tieFree(optimizedRows, keys.get())
                && !unoptimizedRows.equals(optimizedRows)) {
            return new Outcome.Disagreed(tree,
                    "the rows agree as a multiset but not in order, and the query's delivered "
                    + "ordering is total on them\n"
                    + rowDiff(unoptimizedRows, optimizedRows));
        }
        return new Outcome.Agreed(codes(ctx));
    }

    private static SchemaAnnotations annotate(RelNode tree, SemanticModel model) {
        return SchemaInference.annotate(
                model.symbolTable(), tree, model.nodeSchemas(), model.functions());
    }

    private static Set<OptimizationCode> codes(OptimizationContext ctx) {
        Set<OptimizationCode> fired = new LinkedHashSet<>();
        for (TransformationRecord record : ctx.records()) {
            fired.add(record.code());
        }
        return fired;
    }

    private static boolean exceededCap(RuntimeException e) {
        return cause(e).contains(CAP_MARKER);
    }

    private static String cause(RuntimeException e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    // =========================================================================
    // Comparison
    // =========================================================================

    private static boolean sameMultiset(List<List<String>> a, List<List<String>> b) {
        return frequencies(a).equals(frequencies(b));
    }

    private static Map<List<String>, Integer> frequencies(List<List<String>> rows) {
        Map<List<String>, Integer> counts = new HashMap<>();
        for (List<String> row : rows) {
            counts.merge(row, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * The root-schema indices of the tree's delivered sort keys, when it delivers an
     * ordering by bare columns. Empty when it delivers none, or delivers one by a derived
     * expression — whose value is not in the output, so tie-freeness cannot be read off
     * the rows.
     */
    private static Optional<List<Integer>> orderingColumns(RelNode tree, SemanticModel model) {
        Ordering delivered = OrderDeriver.derive(tree);
        if (delivered.keys().isEmpty()) {
            return Optional.empty();
        }
        Optional<Schema> schema = annotate(tree, model).get(tree);
        if (schema.isEmpty()) {
            return Optional.empty();
        }
        List<Integer> indices = new ArrayList<>();
        for (SortSpecification key : delivered.keys()) {
            if (!(key.expression() instanceof AttributeOperand a)) {
                return Optional.empty();
            }
            int index = schema.get().indexOf(a.name());
            if (index < 0) {
                return Optional.empty();
            }
            indices.add(index);
        }
        return Optional.of(List.copyOf(indices));
    }

    /**
     * Whether the sort keys determine the sequence: no two <em>distinct</em> rows share
     * their key values. Rows sharing a key form one contiguous block in a sorted output,
     * so a block holding more than one distinct row always has an adjacent distinct pair
     * — which makes the adjacent check sufficient as well as cheap.
     */
    private static boolean tieFree(List<List<String>> rows, List<Integer> keyIndices) {
        for (int i = 1; i < rows.size(); i++) {
            List<String> previous = rows.get(i - 1);
            List<String> current = rows.get(i);
            if (keysEqual(previous, current, keyIndices) && !previous.equals(current)) {
                return false;
            }
        }
        return true;
    }

    private static boolean keysEqual(List<String> a, List<String> b, List<Integer> keyIndices) {
        for (int index : keyIndices) {
            if (index >= a.size() || index >= b.size() || !a.get(index).equals(b.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static String rowDiff(List<List<String>> unoptimized, List<List<String>> optimized) {
        return "unoptimized (" + unoptimized.size() + " rows): " + render(unoptimized) + '\n'
             + "optimized   (" + optimized.size() + " rows): " + render(optimized);
    }

    private static String render(List<List<String>> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            if (i == 12) {
                sb.append("… ").append(rows.size() - 12).append(" more");
                break;
            }
            sb.append('(').append(String.join("|", rows.get(i))).append(')');
        }
        return sb.append(']').toString();
    }

    // =========================================================================
    // Shrinking
    // =========================================================================

    /**
     * Reduces a failing tree to a smaller one that still fails — greedily, adopting the
     * first reduction that reproduces and starting again, until nothing reduces further.
     *
     * <p>Without this a counterexample is a dozen nested operators over renamed columns,
     * which is not a bug report. A reduction that stops failing is simply not adopted, so
     * the one thing this cannot do is turn a real finding into a smaller wrong one.
     *
     * @param failing     a tree {@code stillFails} reports as failing; must not be null
     * @param stillFails  the reproduction test; must not be null
     * @return the smallest tree reached; never null
     */
    static RelNode shrink(RelNode failing, Predicate<RelNode> stillFails) {
        RelNode current = failing;
        boolean reduced = true;
        while (reduced) {
            reduced = false;
            for (RelNode candidate : reductions(current)) {
                if (stillFails.test(candidate)) {
                    current = candidate;
                    reduced = true;
                    break;
                }
            }
        }
        return current;
    }

    /**
     * Every tree one step simpler than {@code node}: the node replaced by each of its own
     * children, then each child replaced by one of <em>its</em> reductions.
     */
    private static List<RelNode> reductions(RelNode node) {
        List<RelNode> out = new ArrayList<>(node.children());
        List<RelNode> children = node.children();
        for (int i = 0; i < children.size(); i++) {
            for (RelNode smaller : reductions(children.get(i))) {
                out.add(replaceChild(node, i, smaller));
            }
        }
        return out;
    }

    /**
     * Rebuilds {@code node} with child {@code index} replaced. {@code mapChildren} visits
     * children in {@code children()} order, which is what makes a counter enough to
     * address one of them.
     */
    private static RelNode replaceChild(RelNode node, int index, RelNode replacement) {
        int[] seen = {0};
        UnaryOperator<RelNode> swap = child -> seen[0]++ == index ? replacement : child;
        return node.mapChildren(swap);
    }
}

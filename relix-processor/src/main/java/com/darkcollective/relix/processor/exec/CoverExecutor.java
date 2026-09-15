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
package com.darkcollective.relix.processor.exec;

import com.darkcollective.relix.ast.OperandWalker;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.processor.ArrayRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.processor.eval.SubsetOptimizer;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Executes the covering-reduction operators ({@code COVER t} and its
 * constructive variant, ADR-0012) &mdash; greedy near-minimal t-way covering-set
 * selection, plus exact-minimum MIP set-cover ({@code COVER EXACT t}).
 * Holds a {@link ChildDispatch} to run its factor/input sub-plans.
 */
final class CoverExecutor {

    /**
     * Maximum number of demanded t-tuples allowed in exact ({@code COVER EXACT}) mode
     * before throwing an {@link EvaluationException}.  The MIP set-cover has one binary
     * variable per candidate row and one constraint per demanded t-tuple; beyond this
     * threshold the model risks hanging the solver.
     */
    static final int MAX_EXACT_TUPLES = 10_000;

    private final ChildDispatch dispatch;

    CoverExecutor(ChildDispatch dispatch) {
        this.dispatch = dispatch;
    }

    /**
     * Immutable setup bundle for the constructive COVER algorithm, threading the
     * shared state through all phases and helpers in place of loose 9-parameter lists.
     */
    private record ConstructiveCoverContext(
            Schema schema,
            int width,
            List<int[]> subsets,
            List<List<Row>> factorDomains,
            int[] factorStart,
            int[] factorEnd,
            List<Predicate> conjuncts,
            EvalCtx evalCtx,
            int numFactors,
            List<Set<Integer>> conjunctFactors) {}

    /**
     * COVER t: greedy near-minimal covering-set selection.
     *
     * <ol>
     *   <li>Buffer input rows in arrival order, deduplicating whole rows.</li>
     *   <li>Build the universe: for each of the {@code C(w, t)} column-index subsets,
     *       the set of distinct t-value tuples over the candidates.</li>
     *   <li>Greedy loop: score each remaining candidate by the number of its
     *       t-tuples that are still in the universe, select the highest-scorer
     *       (earliest-arrival tie-break), emit it, remove its tuples from the
     *       universe, and drop zero-scoring candidates; stop when the universe is
     *       empty.</li>
     * </ol>
     *
     * <p>The universe is built via {@link #columnSubsets}, a private subset-family
     * abstraction, so a mixed-strength sub-models extension can supply a custom
     * family without touching this greedy core.
     */
    Stream<Row> executeCover(PhysicalNode.Cover node, EvalCtx ctx) {
        if (node.exact()) {
            return executeExactCover(node, ctx);
        }

        Schema schema = node.schema();
        int width = schema.width();
        int t = node.strength();

        // 1. Buffer + deduplicate (whole-row identity, arrival order preserved).
        List<Row> candidates = new ArrayList<>();
        Set<Row> seenRows = new LinkedHashSet<>();
        try (Stream<Row> input = dispatch.buffering(node.input(), ctx, node)) {
            input.forEach(row -> {
                if (seenRows.add(row)) {
                    candidates.add(row);
                }
            });
        }

        if (candidates.isEmpty()) {
            return BagRelation.of(schema, List.of()).stream();
        }

        // 2. Build the coverage universe.
        //    subsets: List<int[]> — each int[] is a column-index subset of size t.
        //    universe: for each subset, the set of distinct t-value tuples still
        //              demanded (initially the full set from all candidates).
        List<int[]> subsets = columnSubsets(width, t);
        List<Set<List<Value>>> universe = new ArrayList<>(subsets.size());
        for (int[] subset : subsets) {
            Set<List<Value>> demanded = new HashSet<>();
            for (Row cand : candidates) {
                demanded.add(projectTuple(cand, subset));
            }
            universe.add(demanded);
        }

        // 3. Greedy loop.
        List<Row> output = new ArrayList<>();
        // remaining[i] tracks whether candidates.get(i) is still selectable.
        boolean[] remaining = new boolean[candidates.size()];
        Arrays.fill(remaining, true);

        while (universeNonEmpty(universe)) {
            int bestIdx = -1;
            int bestScore = 0;
            for (int i = 0; i < candidates.size(); i++) {
                if (!remaining[i]) continue;
                int score = score(candidates.get(i), subsets, universe);
                if (score > bestScore) {
                    bestScore = score;
                    bestIdx = i;
                }
                // equal score: earlier index (first arrival) wins — no update.
            }
            if (bestIdx < 0) break;  // no uncovered tuples remain (or nothing left)

            Row chosen = candidates.get(bestIdx);
            output.add(chosen);
            remaining[bestIdx] = false;

            // Remove the chosen row's tuples from the universe.
            for (int s = 0; s < subsets.size(); s++) {
                universe.get(s).remove(projectTuple(chosen, subsets.get(s)));
            }

            // Drop zero-scoring candidates (they can never cover anything new).
            for (int i = 0; i < candidates.size(); i++) {
                if (!remaining[i]) continue;
                if (score(candidates.get(i), subsets, universe) == 0) {
                    remaining[i] = false;
                }
            }
        }

        return BagRelation.of(schema, output).stream();
    }

    /**
     * COVER EXACT t: exact-minimum covering-set selection via MIP set-cover.
     *
     * <ol>
     *   <li>Buffer input rows in arrival order, deduplicating whole rows.</li>
     *   <li>Build the coverage universe: for each of the {@code C(w, t)} column-index
     *       subsets, the set of distinct t-value tuples demanded (from all candidates).</li>
     *   <li>For each demanded t-tuple, record which candidate indices cover it.</li>
     *   <li>Delegate to {@link SubsetOptimizer#setcover} (MIP minimising row count).</li>
     *   <li>Return the minimal chosen rows, or an empty bag when the MIP is proved
     *       infeasible — a search that stops without deciding raises instead.</li>
     * </ol>
     *
     * <p>Rejects inputs whose universe exceeds {@link #MAX_EXACT_TUPLES} to prevent
     * the solver from hanging on large models.
     */
    private Stream<Row> executeExactCover(PhysicalNode.Cover node, EvalCtx ctx) {
        Schema schema = node.schema();
        int width = schema.width();
        int t = node.strength();

        // 1. Buffer + deduplicate (whole-row identity, arrival order preserved).
        List<Row> candidates = new ArrayList<>();
        Set<Row> seenRows = new LinkedHashSet<>();
        try (Stream<Row> input = dispatch.buffering(node.input(), ctx, node)) {
            input.forEach(row -> {
                if (seenRows.add(row)) {
                    candidates.add(row);
                }
            });
        }

        if (candidates.isEmpty()) {
            return BagRelation.of(schema, List.of()).stream();
        }

        // 2. Build the universe: for each C(w,t) column-subset, all distinct
        //    demanded t-value tuples.
        List<int[]> subsets = columnSubsets(width, t);
        // Ordered list of (subsetIndex, tuple) pairs for the size guard.
        // We materialise demanded tuples in insertion order per subset.
        List<List<List<Value>>> universeOrdered = new ArrayList<>(subsets.size());
        for (int[] subset : subsets) {
            // Use LinkedHashSet to preserve stable iteration order across JVM runs.
            Set<List<Value>> seen = new LinkedHashSet<>();
            for (Row cand : candidates) {
                seen.add(projectTuple(cand, subset));
            }
            universeOrdered.add(new ArrayList<>(seen));
        }

        // Count total demanded tuples and enforce size guard.
        int totalDemanded = 0;
        for (List<List<Value>> tuples : universeOrdered) {
            totalDemanded += tuples.size();
        }
        if (totalDemanded > MAX_EXACT_TUPLES) {
            throw new EvaluationException(
                    "COVER EXACT: the covering universe has " + totalDemanded
                    + " demanded t-tuples, exceeding the limit of " + MAX_EXACT_TUPLES
                    + "; use greedy COVER t for large models");
        }

        // 3. For each demanded t-tuple, find which candidate indices cover it.
        //    One entry per demanded tuple (flattened across all subsets).
        List<List<Integer>> coveringSets = new ArrayList<>(totalDemanded);
        for (int s = 0; s < subsets.size(); s++) {
            int[] subset = subsets.get(s);
            for (List<Value> demanded : universeOrdered.get(s)) {
                List<Integer> covering = new ArrayList<>();
                for (int i = 0; i < candidates.size(); i++) {
                    if (projectTuple(candidates.get(i), subset).equals(demanded)) {
                        covering.add(i);
                    }
                }
                coveringSets.add(covering);
            }
        }

        // 4. Solve via MIP set-cover (all ojAlgo usage confined to SubsetOptimizer).
        Optional<List<Row>> result =
                new SubsetOptimizer(ctx.operandEval()).setcover(candidates, coveringSets);

        // 5. Return the chosen rows (or empty on infeasibility).
        return BagRelation.of(schema, result.orElse(List.of())).stream();
    }

    /**
     * Executes the constructive COVER mode (ADR-0012 Decision 3 Option B).
     *
     * <p>Builds candidate rows factor-by-factor over the domain relations, using the
     * conjuncts as a validity oracle, without materialising the full Cartesian product.
     * The algorithm:
     * <ol>
     *   <li>Execute each factor to get its domain rows (deduplicated).</li>
     *   <li>Build the coverage universe for each C(w,t) column-subset by enumerating
     *       only the cross-product of the <em>factors that own columns in that subset</em>
     *       — avoiding the full product.</li>
     *   <li>Greedy loop: build one test-case row at a time, choosing factor rows in
     *       order and scoring by uncovered-tuple gain.  A conjunct is evaluated only
     *       once all the factors whose columns it references have been chosen
     *       (partial-row validity oracle).  When no valid choice exists for some factor,
     *       a tuple is pruned from the universe (it is un-coverable due to constraints)
     *       and the loop continues; termination is guaranteed because the universe
     *       strictly shrinks on each productive step and stalling is capped.</li>
     * </ol>
     */
    Stream<Row> executeConstructiveCover(PhysicalNode.ConstructiveCover node, EvalCtx ctx) {
        ConstructiveCoverContext cctx = buildCoverContext(node, ctx);
        List<Set<List<Value>>> universe = buildConstructiveUniverse(cctx);

        if (!universeNonEmpty(universe)) {
            return BagRelation.of(cctx.schema(), List.of()).stream();
        }
        if (!cctx.conjuncts().isEmpty()) {
            preFilterInfeasiblePairs(cctx, universe);
        }
        if (!universeNonEmpty(universe)) {
            return BagRelation.of(cctx.schema(), List.of()).stream();
        }

        return BagRelation.of(cctx.schema(), greedyConstruct(cctx, universe)).stream();
    }

    /**
     * Phases 1+2: executes each factor, deduplicates, precomputes conjunct factor sets,
     * and builds the column-index subsets — assembling the shared context for all
     * subsequent phases.
     */
    private ConstructiveCoverContext buildCoverContext(PhysicalNode.ConstructiveCover node, EvalCtx ctx) {
        Schema schema = node.schema();
        List<ColumnDefinition> cols = schema.columns();
        int width = cols.size();
        int t = node.strength();
        List<PhysicalNode> factorNodes = node.factors();
        List<Predicate> conjuncts = node.conjuncts();
        int numFactors = factorNodes.size();

        int[] factorStart = new int[numFactors];
        int[] factorEnd   = new int[numFactors];
        List<List<Row>> factorDomains =
                buildFactorDomains(node, factorNodes, factorStart, factorEnd, ctx);
        List<Set<Integer>> conjunctFactors = precomputeConjunctFactors(cols, conjuncts, factorStart, factorEnd);
        List<int[]> subsets = columnSubsets(width, t);

        return new ConstructiveCoverContext(schema, width, subsets, factorDomains,
                factorStart, factorEnd, conjuncts, ctx, numFactors, conjunctFactors);
    }

    /**
     * Phase 1: executes each factor plan, deduplicates its rows (arrival-order
     * preserved), and records the schema-column offsets for each factor.
     */
    private List<List<Row>> buildFactorDomains(
            PhysicalNode.ConstructiveCover owner, List<PhysicalNode> factorNodes,
            int[] factorStart, int[] factorEnd, EvalCtx ctx) {
        int numFactors = factorNodes.size();
        List<List<Row>> factorDomains = new ArrayList<>(numFactors);
        int off = 0;
        for (int f = 0; f < numFactors; f++) {
            factorStart[f] = off;
            off += factorNodes.get(f).schema().width();
            factorEnd[f] = off;
            List<Row> domain = new ArrayList<>();
            Set<Row> seen = new LinkedHashSet<>();
            try (Stream<Row> fs = dispatch.buffering(factorNodes.get(f), ctx, owner)) {
                fs.forEach(r -> { if (seen.add(r)) domain.add(r); });
            }
            factorDomains.add(domain);
        }
        return factorDomains;
    }

    /**
     * Phase 2: for each conjunct, identifies the set of factor indices whose columns
     * it references, so the greedy loop can skip conjuncts that reference unbound
     * (future) factors.
     */
    private static List<Set<Integer>> precomputeConjunctFactors(
            List<ColumnDefinition> cols, List<Predicate> conjuncts,
            int[] factorStart, int[] factorEnd) {
        Map<String, Integer> colToFactor = new HashMap<>();
        int numFactors = factorStart.length;
        for (int f = 0; f < numFactors; f++) {
            for (int ci = factorStart[f]; ci < factorEnd[f]; ci++) {
                colToFactor.put(cols.get(ci).name().toLowerCase(), f);
            }
        }
        List<Set<Integer>> conjunctFactors = new ArrayList<>(conjuncts.size());
        for (Predicate p : conjuncts) {
            Set<String> attrNames = collectCoverAttributeNames(p);
            Set<Integer> factorSet = new HashSet<>();
            for (String name : attrNames) {
                Integer fi = colToFactor.get(name);
                if (fi != null) factorSet.add(fi);
            }
            conjunctFactors.add(Set.copyOf(factorSet));
        }
        return conjunctFactors;
    }

    /**
     * Phase 3 wrapper: builds the constructive coverage universe from the context.
     */
    private static List<Set<List<Value>>> buildConstructiveUniverse(ConstructiveCoverContext cctx) {
        return buildConstructiveUniverse(cctx.subsets(), cctx.factorDomains(),
                cctx.factorStart(), cctx.factorEnd());
    }

    /**
     * Builds the constructive coverage universe: for each column-subset, enumerates
     * only the cross-product of the <em>factors that contribute columns to that subset</em>
     * (not the full Cartesian product of all factors).
     */
    private static List<Set<List<Value>>> buildConstructiveUniverse(
            List<int[]> subsets,
            List<List<Row>> factorDomains,
            int[] factorStart,
            int[] factorEnd) {

        int numFactors = factorDomains.size();
        List<Set<List<Value>>> universe = new ArrayList<>(subsets.size());

        for (int[] subset : subsets) {
            Set<List<Value>> demanded = new HashSet<>();

            // Collect the distinct factors that own at least one column in this subset.
            List<Integer> involvedFactors = new ArrayList<>();
            for (int f = 0; f < numFactors; f++) {
                for (int ci : subset) {
                    if (ci >= factorStart[f] && ci < factorEnd[f]) {
                        involvedFactors.add(f);
                        break;
                    }
                }
            }
            enumerateCombinationsInto(involvedFactors, factorDomains, factorStart, factorEnd, subset, demanded);
            universe.add(demanded);
        }

        return universe;
    }

    /**
     * Phase 3b: removes t-tuples from the universe that have no valid completion
     * under the conjuncts, so the greedy loop never stalls on inherently infeasible
     * demanded pairs (e.g. a forbidden value combination ruled out by a constraint).
     */
    private void preFilterInfeasiblePairs(ConstructiveCoverContext cctx,
                                           List<Set<List<Value>>> universe) {
        for (int si = 0; si < cctx.subsets().size(); si++) {
            int[] subset = cctx.subsets().get(si);
            universe.get(si).removeIf(tuple -> !hasValidCompletion(cctx, tuple, subset));
        }
    }

    /**
     * Returns {@code true} if there exists at least one combination of values for
     * the columns NOT in {@code subset} (drawn from their factor domains) such that
     * the fully-bound row satisfies every conjunct.
     */
    private boolean hasValidCompletion(ConstructiveCoverContext cctx,
                                        List<Value> tuple, int[] subset) {
        int width = cctx.width();

        // Commit subset columns to a base assignment (null = unbound slot).
        Value[] base = new Value[width];
        for (int pi = 0; pi < subset.length; pi++) {
            base[subset[pi]] = tuple.get(pi);
        }

        // Identify factors that have at least one unbound column.
        List<Integer> unboundFactors = new ArrayList<>();
        for (int f = 0; f < cctx.numFactors(); f++) {
            boolean allBound = true;
            for (int ci = cctx.factorStart()[f]; ci < cctx.factorEnd()[f]; ci++) {
                if (base[ci] == null) { allBound = false; break; }
            }
            if (!allBound) unboundFactors.add(f);
        }

        if (unboundFactors.isEmpty()) {
            // Every column already committed — just check constraints directly.
            Row row = ArrayRow.of(cctx.schema(), Arrays.asList(base));
            for (Predicate p : cctx.conjuncts()) {
                if (!cctx.evalCtx().predicateEval().evaluate(p, row)) return false;
            }
            return true;
        }

        // Enumerate all combinations of unbound factor values (mixed-radix counter).
        int n = unboundFactors.size();
        int[] sizes = new int[n];
        for (int i = 0; i < n; i++) {
            sizes[i] = cctx.factorDomains().get(unboundFactors.get(i)).size();
            if (sizes[i] == 0) return false;
        }
        int[] idx = new int[n];

        while (true) {
            Value[] vals = Arrays.copyOf(base, width);
            for (int i = 0; i < n; i++) {
                int f = unboundFactors.get(i);
                Row factorRow = cctx.factorDomains().get(f).get(idx[i]);
                for (int ci = cctx.factorStart()[f]; ci < cctx.factorEnd()[f]; ci++) {
                    vals[ci] = factorRow.get(ci - cctx.factorStart()[f]);
                }
            }

            Row row = ArrayRow.of(cctx.schema(), Arrays.asList(vals));
            boolean allPass = true;
            for (Predicate p : cctx.conjuncts()) {
                if (!cctx.evalCtx().predicateEval().evaluate(p, row)) { allPass = false; break; }
            }
            if (allPass) return true;

            // Increment mixed-radix counter.
            int pos = n - 1;
            while (pos >= 0 && ++idx[pos] >= sizes[pos]) { idx[pos] = 0; pos--; }
            if (pos < 0) break;
        }
        return false;
    }

    /**
     * Phase 4: greedy construction loop — builds one test-case row per iteration by
     * selecting the best-scoring domain row for each factor in order.  Stalls
     * (universe-shrink + retry) when no valid completion exists; terminates when the
     * universe is empty or the stall budget is exhausted.
     */
    private List<Row> greedyConstruct(ConstructiveCoverContext cctx,
                                       List<Set<List<Value>>> universe) {
        List<Row> output = new ArrayList<>();
        // stallLimit = total initial universe size: worst case each stall removes exactly
        // one demanded tuple, so the loop terminates after at most that many stalls.
        int stallLimit = universe.stream().mapToInt(Set::size).sum();
        int stallCount = 0;

        while (universeNonEmpty(universe) && stallCount <= stallLimit) {
            Value[] partial = new Value[cctx.width()];
            Arrays.fill(partial, NullValue.INSTANCE);
            boolean rowComplete = true;

            for (int f = 0; f < cctx.numFactors(); f++) {
                Row best = selectBestFactorRow(cctx, partial, f, universe);
                if (best == null) { rowComplete = false; break; }
                for (int ci = cctx.factorStart()[f]; ci < cctx.factorEnd()[f]; ci++) {
                    partial[ci] = best.get(ci - cctx.factorStart()[f]);
                }
            }

            if (!rowComplete) {
                removeFirstUniverseTuple(universe);
                stallCount++;
                continue;
            }

            Row row = ArrayRow.of(cctx.schema(), Arrays.asList(partial));

            // Full-row constraint check (catches cross-factor constraints not fully
            // evaluated during construction).
            if (!passesAllConjuncts(cctx, row)) {
                removeFirstUniverseTuple(universe);
                stallCount++;
                continue;
            }

            // Only emit rows that cover at least one new pair; a valid row covering
            // nothing new means the greedy scoring picked a combination already satisfied
            // — treat that as a stall so the loop terminates rather than spinning.
            if (!coversNewPairs(cctx, row, universe)) {
                removeFirstUniverseTuple(universe);
                stallCount++;
                continue;
            }

            output.add(row);
            stallCount = 0;
            for (int s = 0; s < cctx.subsets().size(); s++) {
                universe.get(s).remove(projectTuple(row, cctx.subsets().get(s)));
            }
        }

        return output;
    }

    /**
     * Selects the highest-scoring domain row for factor {@code curFactor}, given
     * the already-committed {@code partial} values, using applicable-conjunct
     * filtering and the coverage-gain score.  Returns {@code null} when every
     * candidate fails the applicable conjuncts.
     */
    private Row selectBestFactorRow(ConstructiveCoverContext cctx, Value[] partial,
                                     int curFactor, List<Set<List<Value>>> universe) {
        Row best = null;
        int bestScore = -1;
        for (Row factorRow : cctx.factorDomains().get(curFactor)) {
            Value[] tentative = Arrays.copyOf(partial, cctx.width());
            for (int ci = cctx.factorStart()[curFactor]; ci < cctx.factorEnd()[curFactor]; ci++) {
                tentative[ci] = factorRow.get(ci - cctx.factorStart()[curFactor]);
            }
            Row tentRow = ArrayRow.of(cctx.schema(), Arrays.asList(tentative));
            if (!passesApplicableConjuncts(cctx, tentRow, curFactor)) continue;
            int score = scoreFactorRow(cctx, tentative, curFactor, universe);
            if (score > bestScore) {
                bestScore = score;
                best = factorRow;
            }
        }
        return best;
    }

    /**
     * Checks conjuncts whose factors are all ≤ {@code curFactor} (fully bound in
     * the tentative partial assignment).  Skips conjuncts that reference later factors.
     */
    private boolean passesApplicableConjuncts(ConstructiveCoverContext cctx,
                                               Row tentRow, int curFactor) {
        for (int j = 0; j < cctx.conjuncts().size(); j++) {
            boolean applicable = true;
            for (int fi : cctx.conjunctFactors().get(j)) {
                if (fi > curFactor) { applicable = false; break; }
            }
            if (applicable && !cctx.evalCtx().predicateEval().evaluate(cctx.conjuncts().get(j), tentRow)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Scores a candidate factor-row tentative assignment: for each column-subset that
     * contains at least one column from {@code curFactor}, counts the still-demanded
     * t-tuples whose committed (non-NULL) positions all match.  This correctly
     * distinguishes factor values even when no t-subset is fully bound yet (e.g. factor 0
     * in a 3-factor / t=2 plan), preventing the algorithm from always picking the first
     * domain row.
     */
    private int scoreFactorRow(ConstructiveCoverContext cctx, Value[] tentative,
                                int curFactor, List<Set<List<Value>>> universe) {
        int score = 0;
        for (int s = 0; s < cctx.subsets().size(); s++) {
            int[] subset = cctx.subsets().get(s);
            boolean containsF = false;
            for (int ci : subset) {
                if (ci >= cctx.factorStart()[curFactor] && ci < cctx.factorEnd()[curFactor]) {
                    containsF = true;
                    break;
                }
            }
            if (!containsF) continue;
            for (List<Value> demanded : universe.get(s)) {
                boolean match = true;
                for (int pi = 0; pi < subset.length; pi++) {
                    Value committed = tentative[subset[pi]];
                    if (committed == NullValue.INSTANCE) continue;
                    if (!committed.equals(demanded.get(pi))) { match = false; break; }
                }
                if (match) score++;
            }
        }
        return score;
    }

    /** Returns {@code true} if every conjunct evaluates to true for {@code row}. */
    private boolean passesAllConjuncts(ConstructiveCoverContext cctx, Row row) {
        for (Predicate p : cctx.conjuncts()) {
            if (!cctx.evalCtx().predicateEval().evaluate(p, row)) return false;
        }
        return true;
    }

    /** Returns {@code true} when {@code row} covers at least one t-tuple still in the universe. */
    private boolean coversNewPairs(ConstructiveCoverContext cctx, Row row,
                                    List<Set<List<Value>>> universe) {
        for (int s = 0; s < cctx.subsets().size(); s++) {
            if (universe.get(s).contains(projectTuple(row, cctx.subsets().get(s)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Enumerates all combinations of one row per involved factor and projects each to
     * the given column-index {@code subset}, adding the resulting tuple to {@code out}.
     * The mixed-radix counter visits every combination without recursion.
     */
    private static void enumerateCombinationsInto(
            List<Integer> factors,
            List<List<Row>> domains,
            int[] factorStart,
            int[] factorEnd,
            int[] subset,
            Set<List<Value>> out) {

        int n = factors.size();
        if (n == 0) return;
        int[] sizes = new int[n];
        for (int i = 0; i < n; i++) {
            sizes[i] = domains.get(factors.get(i)).size();
            if (sizes[i] == 0) return;
        }
        int[] idx = new int[n];

        while (true) {
            List<Value> tuple = new ArrayList<>(subset.length);
            for (int ci : subset) {
                for (int i = 0; i < n; i++) {
                    int f = factors.get(i);
                    if (ci >= factorStart[f] && ci < factorEnd[f]) {
                        tuple.add(domains.get(f).get(idx[i]).get(ci - factorStart[f]));
                        break;
                    }
                }
            }
            out.add(tuple);

            // Increment mixed-radix counter (right-to-left with carry).
            int pos = n - 1;
            while (pos >= 0 && ++idx[pos] >= sizes[pos]) {
                idx[pos] = 0;
                pos--;
            }
            if (pos < 0) break;
        }
    }

    /** Removes one tuple from the first non-empty demanded set in the universe. */
    private static void removeFirstUniverseTuple(List<Set<List<Value>>> universe) {
        for (Set<List<Value>> demanded : universe) {
            if (!demanded.isEmpty()) {
                demanded.remove(demanded.iterator().next());
                return;
            }
        }
    }

    /**
     * Collects all bare (dot-stripped, lower-cased) attribute names referenced
     * anywhere in a predicate tree.  Used by the constructive-cover executor to
     * determine which factors a conjunct depends on.
     */
    private static Set<String> collectCoverAttributeNames(Predicate p) {
        Set<String> names = new HashSet<>();
        // Drive the canonical relix-ast walker rather than a private copy: it threads
        // every Operand subtype (incl. StructConstruction / ArrayConstruction) from a
        // single registration point, so a conjunct can never silently under-report the
        // factors it depends on. Function calls are irrelevant here — only the column
        // references inside their arguments matter, and the walker already recurses those.
        OperandWalker.walk(p,
                attr -> names.add(attr.unqualifiedName().toLowerCase()),
                fn -> { /* the function name is not a column reference */ });
        return names;
    }

    /**
     * Returns all C(n, t) column-index subsets of {@code [0, n)} with exactly
     * {@code t} elements, in lexicographic order.  The subset-family abstraction:
     * the only caller passes the full C(w,t) family, but a mixed-strength
     * sub-models extension can supply a custom one.
     */
    private static List<int[]> columnSubsets(int n, int t) {
        List<int[]> result = new ArrayList<>();
        if (t == 0 || t > n) return result;
        int[] indices = new int[t];
        for (int i = 0; i < t; i++) indices[i] = i;
        result.add(indices.clone());
        while (true) {
            // Find the rightmost index that can still be incremented.
            int i = t - 1;
            while (i >= 0 && indices[i] == n - t + i) i--;
            if (i < 0) break;
            indices[i]++;
            for (int j = i + 1; j < t; j++) indices[j] = indices[j - 1] + 1;
            result.add(indices.clone());
        }
        return result;
    }

    /** Projects {@code row} down to the columns indicated by {@code subset} (column indices). */
    private static List<Value> projectTuple(Row row, int[] subset) {
        List<Value> tuple = new ArrayList<>(subset.length);
        for (int col : subset) {
            tuple.add(row.get(col));
        }
        return tuple;
    }

    /** Counts how many of the candidate's t-tuples are still in the universe. */
    private static int score(Row candidate, List<int[]> subsets,
                             List<Set<List<Value>>> universe) {
        int count = 0;
        for (int s = 0; s < subsets.size(); s++) {
            if (universe.get(s).contains(projectTuple(candidate, subsets.get(s)))) {
                count++;
            }
        }
        return count;
    }

    /** Returns true when at least one demanded set in the universe is non-empty. */
    private static boolean universeNonEmpty(List<Set<List<Value>>> universe) {
        for (Set<List<Value>> demanded : universe) {
            if (!demanded.isEmpty()) return true;
        }
        return false;
    }
}

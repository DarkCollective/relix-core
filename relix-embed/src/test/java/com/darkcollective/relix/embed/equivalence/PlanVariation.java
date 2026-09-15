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

import com.darkcollective.relix.plan.PhysicalNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Rewrites a planned query to run a different way, so the same question can be asked of
 * more than one execution strategy and the answers compared.
 *
 * <h2>Why this is a plan rewrite and not a planner setting</h2>
 *
 * The planner chooses a join algorithm from the cost model, and exposes no hint. That is
 * the right design — a query should not carry execution advice — but it leaves the choice
 * untestable against the rows: {@code PlannerTest} asserts which algorithm is chosen for
 * thousands of lines, and nothing anywhere says the choice does not change the answer.
 *
 * <p>Rewriting the finished plan asks exactly that question and needs nothing from
 * production but {@link PhysicalNode#mapChildren}.
 *
 * <h2>What may legally be forced, and what may not</h2>
 *
 * An algorithm is not a free choice — each has a precondition the planner satisfies while
 * building, so forcing one afterwards can produce a plan the planner would never emit:
 *
 * <ul>
 *   <li><b>NESTED_LOOP</b> is the general algorithm and needs nothing of its inputs, so
 *       any join can be forced to it.</li>
 *   <li><b>HASH</b> needs equi-join keys to bucket on. A join with none is a join the
 *       planner already leaves as a nested loop, and forcing it is meaningless.</li>
 *   <li><b>MERGE</b> needs both inputs <em>ordered</em>, which the planner arranges by
 *       inserting sorts or pushing an {@code ORDER BY} into a source while it plans.
 *       Forcing it here would skip that and compare against a plan that was never
 *       legal, so it is <b>not</b> offered.</li>
 * </ul>
 *
 * <p>MERGE is still covered, from the other direction: where the planner chooses it, the
 * plan it produced is one of the variants under comparison and the forced ones are the
 * others. The test states which of its queries those are.
 *
 * <h2>Sharing is the second axis</h2>
 *
 * A {@code Spool} is the planner deciding that a sub-plan read in several places should be
 * evaluated once. Removing one is always legal — the planner only shares a sub-plan it has
 * proved reproducible, which is the same proof that lets a reader re-run it — so the
 * unshared plan is an independent answer to the same question. It is worth asking: the last
 * spool defect made a shared read do <em>more</em> work than not sharing, and the test that
 * should have seen it was counting stream opens, which stayed at one throughout.
 */
final class PlanVariation {

    private PlanVariation() {
    }

    /** The algorithms a finished plan may be rewritten to use. */
    enum Forced {
        /** Any join at all; the algorithm with no precondition. */
        NESTED_LOOP(PhysicalNode.JoinAlgorithm.NESTED_LOOP),
        /** Only a join that has equi-keys to bucket on. */
        HASH(PhysicalNode.JoinAlgorithm.HASH);

        private final PhysicalNode.JoinAlgorithm algorithm;

        Forced(PhysicalNode.JoinAlgorithm algorithm) {
            this.algorithm = algorithm;
        }

        boolean canRun(PhysicalNode.Join join) {
            return this != HASH || !join.keys().left().isEmpty();
        }
    }

    /**
     * {@code plan} with every join it can legally rewrite set to {@code forced}, and every
     * other component of every node untouched.
     *
     * @param plan   the planned query; must not be null
     * @param forced the algorithm to impose where it is legal; must not be null
     * @return the rewritten plan, or {@code plan} itself when nothing changed
     */
    static PhysicalNode forcing(PhysicalNode plan, Forced forced) {
        PhysicalNode mapped = plan.mapChildren(child -> forcing(child, forced));
        if (mapped instanceof PhysicalNode.Join join
                && join.algorithm() != forced.algorithm
                && forced.canRun(join)) {
            return new PhysicalNode.Join(join.schema(), join.kind(), forced.algorithm,
                    join.buildSide(), join.condition(), join.keys(),
                    join.leftRelations(), join.rightRelations(),
                    join.left(), join.right());
        }
        return mapped;
    }

    /**
     * {@code plan} with every {@code Spool} replaced by the sub-plan it was sharing, so
     * each consumer reads for itself.
     *
     * @param plan the planned query; must not be null
     * @return the unshared plan, or {@code plan} itself when it shared nothing
     */
    static PhysicalNode withoutSpools(PhysicalNode plan) {
        PhysicalNode mapped = plan.mapChildren(PlanVariation::withoutSpools);
        return mapped instanceof PhysicalNode.Spool spool ? spool.input() : mapped;
    }

    /**
     * Every rewrite a planned query should survive, by the name a failure should report.
     *
     * <p>Ordered so that a failure names the smallest change that produced it.
     */
    static Map<String, UnaryOperator<PhysicalNode>> variants() {
        Map<String, UnaryOperator<PhysicalNode>> rewrites = new LinkedHashMap<>();
        for (Forced forced : Forced.values()) {
            rewrites.put("every join forced to " + forced, plan -> forcing(plan, forced));
        }
        rewrites.put("no sharing (every spool removed)", PlanVariation::withoutSpools);
        return rewrites;
    }

    /** How many {@code Spool} nodes {@code plan} contains. */
    static int spoolCount(PhysicalNode plan) {
        int here = plan instanceof PhysicalNode.Spool ? 1 : 0;
        return here + plan.children().stream().mapToInt(PlanVariation::spoolCount).sum();
    }

    /** The algorithm of every join in {@code plan}, in traversal order. */
    static List<PhysicalNode.JoinAlgorithm> joinAlgorithms(PhysicalNode plan) {
        List<PhysicalNode.JoinAlgorithm> found = new ArrayList<>();
        collect(plan, found);
        return found;
    }

    private static void collect(PhysicalNode node, List<PhysicalNode.JoinAlgorithm> into) {
        if (node instanceof PhysicalNode.Join join) {
            into.add(join.algorithm());
        }
        node.children().forEach(child -> collect(child, into));
    }
}

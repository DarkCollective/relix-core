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

import com.darkcollective.relix.optimizer.OptimizationCode;
import com.darkcollective.relix.optimizer.internal.OptimizationResult;
import com.darkcollective.relix.optimizer.TransformationRecord;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.optimizeFirst;
import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.run;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-level equivalence for the {@code WHY} hard barrier (ADR-0018) against the
 * <em>preamble</em> rewrites — view inlining, rename elimination, lateral decorrelation —
 * which run outside {@code OptimizationPipeline} and so were once outside the barrier too
 * (#855).
 *
 * <p>The failure this pins is not wrong rows: only the {@code provenance} column degrades,
 * and toward less specific rather than false. That makes it exactly the kind of regression
 * an equivalence test has to state as an <em>identity between two surfaces</em> — the same
 * query written over a view and written inline must reify the same lineage, and optimizing
 * must not change it — because no assertion about the data columns can see it.
 *
 * <p>Harness: {@link OptimizerEquivalence}.
 */
@DisplayName("WHY hard barrier — the preamble passes must not rewrite inside a WHY")
final class WhyBarrierEquivalenceTest {

    private static final String DATA = """
            Orders := [
            | oid | cid | amount |
            |-----|-----|--------|
            | 100 | 7   | 40     |
            | 101 | 8   | 15     |
            ];
            Customers := [
            | cid | name |
            |-----|------|
            | 7   | Ada  |
            | 8   | Bob  |
            ];
            Joined := { Orders ⨝ Orders.cid = Customers.cid Customers };
            """;

    private static List<OptimizationCode> codes(OptimizationResult r) {
        return r.applied().stream().map(TransformationRecord::code).toList();
    }

    @Test
    @DisplayName("a join reached through a view keeps both contributing variables")
    void viewKeepsJointDerivation() {
        SemanticModel m = model(DATA + "query { ω (Joined) };\n");
        OptimizationResult r = optimizeFirst(m);

        assertThat(run(r.optimized(), m))
                .as("optimizing a WHY over a view must not change the reified lineage")
                .containsExactlyElementsOf(run(r.original(), m));
        assertThat(run(r.optimized(), m).getFirst())
                .as("the join is a joint derivation — one monomial, two variables")
                .contains("relation: Orders")
                .contains("relation: Customers")
                .doesNotContain("relation: Rename");
    }

    @Test
    @DisplayName("the view form and the inline form reify identical lineage")
    void viewAgreesWithInline() {
        SemanticModel viaView = model(DATA + "query { ω (Joined) };\n");
        SemanticModel inline  = model(DATA
                + "query { ω (Orders ⨝ Orders.cid = Customers.cid Customers) };\n");

        assertThat(run(optimizeFirst(viaView).optimized(), viaView))
                .as("factoring the WHY input out into a view must not change its lineage")
                .containsExactlyElementsOf(run(optimizeFirst(inline).optimized(), inline));
    }

    @Test
    @DisplayName("the view is still inlined outside a WHY — the barrier is not a blanket opt-out")
    void inliningStillFiresOutsideWhy() {
        SemanticModel m = model(DATA + "query { σ amount > 20 (Joined) };\n");
        OptimizationResult r = optimizeFirst(m);

        assertThat(codes(r))
                .as("a view outside a WHY inlines as it always did")
                .contains(OptimizationCode.INLINE_001);
        assertThat(run(r.optimized(), m)).containsExactlyElementsOf(run(r.original(), m));
    }

    @Test
    @DisplayName("a user-written ρ under a WHY threads lineage rather than going opaque")
    void renameThreadsLineage() {
        SemanticModel m = model(DATA
                + "query { ω (ρ J (Orders ⨝ Orders.cid = Customers.cid Customers)) };\n");

        assertThat(run(optimizeFirst(m).optimized(), m).getFirst())
                .as("ρ is positive — it re-labels columns and derives nothing of its own")
                .contains("relation: Orders")
                .contains("relation: Customers")
                .doesNotContain("relation: Rename");
    }
}

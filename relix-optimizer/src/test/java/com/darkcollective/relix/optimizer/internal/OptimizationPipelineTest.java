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

import com.darkcollective.relix.optimizer.OptimizationCode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the rule registry ({@link OptimizationPipeline#defaultPipeline}) and its
 * bounded fixpoint driver ({@link OptimizationPipeline#run}).
 */
@DisplayName("OptimizationPipeline — rule registry + bounded fixpoint driver")
final class OptimizationPipelineTest {

    private static final RelNode LEAF = rel("R");

    // ── stub rules ───────────────────────────────────────────────────────────

    /** A rule that fires (records + returns a fresh node) the first {@code n} times. */
    private static OptimizationRule firesTimes(String name, int n, List<String> log) {
        var remaining = new int[] {n};
        return PassRule.of(name, (node, queryName, schemas, ctx) -> {
            log.add(name);
            if (remaining[0]-- <= 0) {
                return node;
            }
            ctx.record(OptimizationCode.SEL_001, queryName, name + " fired", node.location());
            return rel("R" + remaining[0]);
        }, OptimizationCode.SEL_001);
    }

    /** A rule that never fires: same node back, nothing recorded. */
    private static OptimizationRule inert(String name, List<String> log) {
        return PassRule.of(name, (node, queryName, schemas, ctx) -> {
            log.add(name);
            return node;
        }, OptimizationCode.SEL_002);
    }

    private static RelNode run(OptimizationPipeline pipeline, OptimizationContext ctx) {
        return pipeline.run(LEAF, "Q", SchemaAnnotations.empty(), ctx);
    }

    // =========================================================================
    // The default registry
    // =========================================================================

    @Nested
    @DisplayName("default pipeline — registry contents")
    class DefaultRegistry {

        private final OptimizationPipeline pipeline = OptimizationPipeline.defaultPipeline();

        @Test
        @DisplayName("the six phases run in the documented order")
        void phaseOrder() {
            assertThat(pipeline.phases()).extracting(OptimizationPipeline.Phase::name)
                    .containsExactly("simplify", "pushdown", "cleanup", "sip", "limit", "prune");
        }

        @Test
        @DisplayName("every optimization code except the pre-pipeline six is registered")
        void everyCodeIsRegistered() {
            var registered = new ArrayList<OptimizationCode>();
            pipeline.rules().forEach(r -> registered.addAll(r.codes()));

            // Six codes belong to the per-query preamble in QueryOptimizer, not to the
            // pipeline, and all six for the same reason — each needs the symbol table,
            // which the pipeline deliberately does not take. INLINE-001 is ViewInliner's
            // (it expands a view body); RENAME-001..004 clean up after it, before the
            // schema re-inference, since removing a node drops the annotations above it;
            // LATERAL-001 resolves a TVF and classifies its body as deterministic.
            //
            // Asserting the *exact* set (not `contains`) is also what catches a
            // conditionally-registered rule. JOIN-003 used to be registered only when a
            // SymbolTable was supplied, so the pipeline's contents depended on the
            // caller; it was removed in #555 and nothing here is optional any more.
            assertThat(registered).containsExactlyInAnyOrderElementsOf(
                    concat(EnumSet.complementOf(EnumSet.of(
                                    OptimizationCode.INLINE_001,
                                    OptimizationCode.RENAME_001,
                                    OptimizationCode.RENAME_002,
                                    OptimizationCode.RENAME_003,
                                    OptimizationCode.RENAME_004,
                                    OptimizationCode.LATERAL_001)),
                            SHARED_WITH_EXPRESSION_PASS));
        }

        /**
         * The PRED codes are declared by two rules, not one (#542): the predicate pass
         * owns them, and the expression pass re-declares them because it is the only pass
         * that walks <em>operands</em> and so the only one that can reach a predicate
         * written in operand position — an {@code IIf} condition. Every other code is
         * still owned by exactly one rule, which is what catches a pass registered twice.
         */
        private static final EnumSet<OptimizationCode> SHARED_WITH_EXPRESSION_PASS =
                EnumSet.of(OptimizationCode.PRED_001, OptimizationCode.PRED_002,
                        OptimizationCode.PRED_003, OptimizationCode.PRED_004,
                        OptimizationCode.PRED_005, OptimizationCode.PRED_006);

        @Test
        @DisplayName("no code but the PRED family is declared by more than one rule")
        void onlyPredicateCodesAreShared() {
            var seen = new ArrayList<OptimizationCode>();
            var duplicated = new ArrayList<OptimizationCode>();
            pipeline.rules().forEach(r -> r.codes().forEach(c -> {
                if (seen.contains(c)) {
                    duplicated.add(c);
                } else {
                    seen.add(c);
                }
            }));
            assertThat(duplicated).containsExactlyInAnyOrderElementsOf(SHARED_WITH_EXPRESSION_PASS);
        }

        /** {@code base} plus {@code extra}, as a list that keeps the repeats. */
        private static List<OptimizationCode> concat(EnumSet<OptimizationCode> base,
                                                     EnumSet<OptimizationCode> extra) {
            var all = new ArrayList<OptimizationCode>(base);
            all.addAll(extra);
            return all;
        }

        @Test
        @DisplayName("rule names are unique")
        void ruleNamesAreUnique() {
            assertThat(pipeline.rules()).extracting(OptimizationRule::name)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("mutual inverses are never in the same phase (SEL-003/PROJ-003, SEL-001/SEL-002)")
        void mutualInversesAreSeparated() {
            for (OptimizationPipeline.Phase phase : pipeline.phases()) {
                var codes = new ArrayList<OptimizationCode>();
                phase.rules().forEach(r -> codes.addAll(r.codes()));

                assertThat(codes)
                        .as("phase '%s' must not iterate σ-below-π with π-below-σ", phase.name())
                        .doesNotContainSequence(OptimizationCode.SEL_003, OptimizationCode.PROJ_003);
                assertThat(codes.contains(OptimizationCode.SEL_003)
                        && codes.contains(OptimizationCode.PROJ_003))
                        .as("phase '%s' holds both SEL-003 and PROJ-003", phase.name())
                        .isFalse();
                assertThat(codes.contains(OptimizationCode.SEL_001)
                        && codes.contains(OptimizationCode.SEL_002))
                        .as("phase '%s' holds both SEL-001 and SEL-002", phase.name())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("column pruning is last and runs a single sweep")
        void pruneIsLastAndSinglePass() {
            var last = pipeline.phases().get(pipeline.phases().size() - 1);

            assertThat(last.name()).isEqualTo("prune");
            assertThat(last.maxIterations()).isEqualTo(OptimizationPipeline.SINGLE_PASS);
            assertThat(last.iterated()).isFalse();
            assertThat(last.rules()).singleElement()
                    .extracting(OptimizationRule::code).isEqualTo(OptimizationCode.PROJ_004);
        }

        @Test
        @DisplayName("every other phase is iterated")
        void everyOtherPhaseIsIterated() {
            assertThat(pipeline.phases().subList(0, 5))
                    .allSatisfy(p -> assertThat(p.iterated())
                            .as("phase '%s'", p.name()).isTrue());
        }

    }

    // =========================================================================
    // Phase validation
    // =========================================================================

    @Nested
    @DisplayName("Phase — validation")
    class PhaseValidation {

        private final List<OptimizationRule> oneRule = List.of(inert("a", new ArrayList<>()));

        @Test
        @DisplayName("a blank name is rejected")
        void blankName() {
            assertThatThrownBy(() -> new OptimizationPipeline.Phase(" ", oneRule, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("name");
        }

        @Test
        @DisplayName("an empty rule list is rejected")
        void emptyRules() {
            assertThatThrownBy(() -> new OptimizationPipeline.Phase("p", List.of(), 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no rules");
        }

        @Test
        @DisplayName("maxIterations below 1 is rejected")
        void nonPositiveIterations() {
            assertThatThrownBy(() -> new OptimizationPipeline.Phase("p", oneRule, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxIterations");
        }

        @Test
        @DisplayName("null components are rejected")
        void nullComponents() {
            assertThatThrownBy(() -> new OptimizationPipeline.Phase(null, oneRule, 1))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new OptimizationPipeline.Phase("p", null, 1))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("the rule list is copied, not aliased")
        void rulesAreCopied() {
            var mutable = new ArrayList<>(oneRule);
            var phase = new OptimizationPipeline.Phase("p", mutable, 1);
            mutable.clear();

            assertThat(phase.rules()).hasSize(1);
        }

        @Test
        @DisplayName("a pipeline needs at least one phase")
        void emptyPipeline() {
            assertThatThrownBy(() -> OptimizationPipeline.of(List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one phase");
            assertThatThrownBy(() -> OptimizationPipeline.of(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // The driver
    // =========================================================================

    @Nested
    @DisplayName("run() — the bounded fixpoint driver")
    class Driver {

        @Test
        @DisplayName("rules run in registration order, phases in phase order")
        void runsInOrder() {
            var log = new ArrayList<String>();
            var pipeline = OptimizationPipeline.of(List.of(
                    new OptimizationPipeline.Phase("one",
                            List.of(inert("a", log), inert("b", log)), 4),
                    new OptimizationPipeline.Phase("two",
                            List.of(inert("c", log)), 4)));

            run(pipeline, new OptimizationContext());

            assertThat(log).containsExactly("a", "b", "c");
        }

        @Test
        @DisplayName("an iterated phase re-sweeps while its rules keep firing")
        void iteratesWhileProgressIsMade() {
            var log = new ArrayList<String>();
            var ctx = new OptimizationContext();
            var pipeline = OptimizationPipeline.of(List.of(
                    new OptimizationPipeline.Phase("one",
                            List.of(firesTimes("a", 3, log)), 8)));

            run(pipeline, ctx);

            // Three firing sweeps, then a fourth that makes no progress and ends the phase.
            assertThat(log).hasSize(4);
            assertThat(ctx).fired(OptimizationCode.SEL_001, 3);
        }

        @Test
        @DisplayName("iteration is capped: an always-firing rule sweeps maxIterations times")
        void iterationIsBounded() {
            var log = new ArrayList<String>();
            var ctx = new OptimizationContext();
            var pipeline = OptimizationPipeline.of(List.of(
                    new OptimizationPipeline.Phase("one",
                            List.of(firesTimes("a", Integer.MAX_VALUE, log)), 3)));

            run(pipeline, ctx);

            assertThat(log).hasSize(3);
            assertThat(ctx).fired(OptimizationCode.SEL_001, 3);
        }

        @Test
        @DisplayName("a SINGLE_PASS phase sweeps once even when its rule keeps firing")
        void singlePassPhaseRunsOnce() {
            var log = new ArrayList<String>();
            var pipeline = OptimizationPipeline.of(List.of(
                    new OptimizationPipeline.Phase("one",
                            List.of(firesTimes("a", Integer.MAX_VALUE, log)),
                            OptimizationPipeline.SINGLE_PASS)));

            run(pipeline, new OptimizationContext());

            assertThat(log).hasSize(1);
        }

        @Test
        @DisplayName("a rule that records without changing the tree does not re-sweep")
        void recordingWithoutRewritingStopsThePhase() {
            var log = new ArrayList<String>();
            var rule = PassRule.of("noisy", (node, queryName, schemas, ctx) -> {
                log.add("noisy");
                ctx.record(OptimizationCode.SEL_001, queryName, "noise", node.location());
                return node;                      // same reference — no structural progress
            }, OptimizationCode.SEL_001);
            var pipeline = OptimizationPipeline.of(List.of(
                    new OptimizationPipeline.Phase("one", List.of(rule), 8)));

            run(pipeline, new OptimizationContext());

            assertThat(log).hasSize(1);
        }

        @Test
        @DisplayName("a rule that rewrites without recording does not re-sweep")
        void rewritingWithoutRecordingStopsThePhase() {
            var log = new ArrayList<String>();
            var rule = PassRule.of("silent", (node, queryName, schemas, ctx) -> {
                log.add("silent");
                return rel("S" + log.size());
            }, OptimizationCode.SEL_001);
            var pipeline = OptimizationPipeline.of(List.of(
                    new OptimizationPipeline.Phase("one", List.of(rule), 8)));

            run(pipeline, new OptimizationContext());

            assertThat(log).hasSize(1);
        }

        @Test
        @DisplayName("the tree flows from phase to phase")
        void treeFlowsThroughPhases() {
            var first = rel("first");
            var second = rel("second");
            var pipeline = OptimizationPipeline.of(List.of(
                    new OptimizationPipeline.Phase("one",
                            List.of(PassRule.of("to-first", (n, q, s, c) -> first,
                                    OptimizationCode.SEL_001)), 1),
                    new OptimizationPipeline.Phase("two",
                            List.of(PassRule.of("to-second", (n, q, s, c) ->
                                    n == first ? second : n, OptimizationCode.SEL_002)), 1)));

            assertThat(run(pipeline, new OptimizationContext())).isSameAs(second);
        }

        @Test
        @DisplayName("a pipeline whose rules never fire returns the original tree unchanged")
        void inertPipelineIsIdentity() {
            var ctx = new OptimizationContext();
            var pipeline = OptimizationPipeline.of(List.of(
                    new OptimizationPipeline.Phase("one",
                            List.of(inert("a", new ArrayList<>())), 8)));

            assertThat(run(pipeline, ctx)).isSameAs(LEAF);
            assertThat(ctx.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("null arguments are rejected")
        void nullArguments() {
            var pipeline = OptimizationPipeline.defaultPipeline();
            var ctx = new OptimizationContext();

            assertThatThrownBy(() -> pipeline.run(null, "Q", SchemaAnnotations.empty(), ctx))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> pipeline.run(LEAF, null, SchemaAnnotations.empty(), ctx))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> pipeline.run(LEAF, "Q", null, ctx))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> pipeline.run(LEAF, "Q", SchemaAnnotations.empty(), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}

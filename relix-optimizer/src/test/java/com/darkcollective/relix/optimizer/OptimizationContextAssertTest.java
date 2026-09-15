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
package com.darkcollective.relix.optimizer;

import com.darkcollective.relix.ast.SourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OptimizationContextAssert} buys one thing over
 * {@code assertThat(ctx.countOf(code)).isEqualTo(n)} — the failure names the rules
 * that <em>did</em> fire — so that is what is tested here, and it is the only reason
 * the assert was written.
 */
@DisplayName("OptimizationContextAssert — firing assertions that name the rules that fired")
final class OptimizationContextAssertTest {

    private static OptimizationContext contextWith(OptimizationCode... codes) {
        OptimizationContext ctx = new OptimizationContext();
        for (OptimizationCode code : codes) {
            ctx.record(code, "Q", "rewrote something", SourceLocation.UNKNOWN);
        }
        return ctx;
    }

    @Test
    @DisplayName("fired / didNotFire state the two claims a rewrite test makes")
    void firing() {
        OptimizationContext ctx = contextWith(OptimizationCode.SEL_003, OptimizationCode.SEL_003);
        assertThat(ctx).fired(OptimizationCode.SEL_003)
                .fired(OptimizationCode.SEL_003, 2)
                .didNotFire(OptimizationCode.PROJ_003);
    }

    @Test
    @DisplayName("a rule that did not fire is reported alongside the rules that did — the diagnosis")
    void namesTheRulesThatFired() {
        OptimizationContext ctx = contextWith(OptimizationCode.PROJ_003);
        assertThatThrownBy(() -> assertThat(ctx).fired(OptimizationCode.SEL_003))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("expected SEL-003 to fire")
                .hasMessageContaining("PROJ-003");
    }

    @Test
    @DisplayName("a wrong count reports the count and the trail")
    void wrongCount() {
        OptimizationContext ctx = contextWith(OptimizationCode.SEL_003);
        assertThatThrownBy(() -> assertThat(ctx).fired(OptimizationCode.SEL_003, 2))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("expected SEL-003 to fire 2 time(s) but it fired 1");
    }

    @Test
    @DisplayName("an unexpected firing names the rule that fired")
    void unexpectedFiring() {
        OptimizationContext ctx = contextWith(OptimizationCode.SEL_003);
        assertThatThrownBy(() -> assertThat(ctx).didNotFire(OptimizationCode.SEL_003))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("expected SEL-003 not to fire, but it fired 1 time(s)");
    }

    @Test
    @DisplayName("recordedNothing is the claim a declined-rewrite test makes")
    void recordedNothing() {
        assertThat(contextWith()).recordedNothing();
        assertThatThrownBy(() -> assertThat(contextWith(OptimizationCode.SEL_003)).recordedNothing())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("expected no transformation");
    }

    @Test
    @DisplayName("codes() hands the trail to AssertJ, in the order the rules ran")
    void codes() {
        assertThat(contextWith(OptimizationCode.SEL_003, OptimizationCode.PROJ_003))
                .codes().containsExactly("SEL-003", "PROJ-003");
    }
}

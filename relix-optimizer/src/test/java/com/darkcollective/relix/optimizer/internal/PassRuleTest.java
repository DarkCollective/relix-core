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
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests the registry adapter that turns a pass into an {@link OptimizationRule}. */
@DisplayName("PassRule — pass → OptimizationRule adapter")
final class PassRuleTest {

    private static final RelNode LEAF = rel("R");

    @Test
    @DisplayName("delegates apply() to the wrapped pass")
    void delegatesApply() {
        RelNode replacement = rel("S");
        var rule = PassRule.of("stub", (n, q, s, c) -> replacement, OptimizationCode.SEL_001);

        assertThat(rule.apply(LEAF, "Q", SchemaAnnotations.empty(), new OptimizationContext()))
                .isSameAs(replacement);
    }

    @Test
    @DisplayName("code() is the first declared code, codes() is all of them")
    void primaryCodeIsFirst() {
        var rule = PassRule.of("stub", (n, q, s, c) -> n,
                OptimizationCode.SEL_003, OptimizationCode.SEL_004);

        assertThat(rule.code()).isEqualTo(OptimizationCode.SEL_003);
        assertThat(rule.codes())
                .containsExactly(OptimizationCode.SEL_003, OptimizationCode.SEL_004);
    }

    @Test
    @DisplayName("name() is the registered name, not the code")
    void nameIsTheRegisteredOne() {
        assertThat(PassRule.of("selection-split", (n, q, s, c) -> n, OptimizationCode.SEL_001)
                .name()).isEqualTo("selection-split");
    }

    @Test
    @DisplayName("codes() is an unmodifiable copy of the caller's list")
    void codesAreCopied() {
        var mutable = new java.util.ArrayList<>(List.of(OptimizationCode.SEL_001));
        var rule = new PassRule("stub", mutable, (n, q, s, c) -> n);
        mutable.add(OptimizationCode.SEL_002);

        assertThat(rule.codes()).containsExactly(OptimizationCode.SEL_001);
    }

    @Test
    @DisplayName("a blank name is rejected")
    void blankNameRejected() {
        assertThatThrownBy(() -> PassRule.of("  ", (n, q, s, c) -> n, OptimizationCode.SEL_001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("a rule with no codes is rejected")
    void noCodesRejected() {
        assertThatThrownBy(() -> PassRule.of("stub", (n, q, s, c) -> n))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one code");
    }

    @Test
    @DisplayName("null components are rejected")
    void nullComponentsRejected() {
        assertThatThrownBy(() -> new PassRule(null, List.of(OptimizationCode.SEL_001),
                (n, q, s, c) -> n)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PassRule("stub", null, (n, q, s, c) -> n))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PassRule("stub", List.of(OptimizationCode.SEL_001), null))
                .isInstanceOf(NullPointerException.class);
    }
}

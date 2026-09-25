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

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the {@link OptimizationRule} interface contract using a pair of
 * minimal inline implementations — one that never fires and one that
 * always fires.
 */
@DisplayName("OptimizationRule interface contract")
final class OptimizationRuleTest {

    private static final RelNode NODE_A = rel("A");
    private static final RelNode NODE_B = rel("B");

    // ── A no-op rule that never fires ────────────────────────────────────────

    private static final OptimizationRule NO_OP = new OptimizationRule() {
        @Override
        public OptimizationCode code() {
            return OptimizationCode.EXPR_001;
        }

        @Override
        public RelNode apply(RelNode node, String queryName,
                             SchemaAnnotations schemas, OptimizationContext ctx) {
            // Never fires: return input unchanged, record nothing.
            return node;
        }
    };

    // ── A rule that always replaces the input with NODE_B ────────────────────

    private static final OptimizationRule ALWAYS_FIRES = new OptimizationRule() {
        @Override
        public OptimizationCode code() {
            return OptimizationCode.SEL_001;
        }

        @Override
        public RelNode apply(RelNode node, String queryName,
                             SchemaAnnotations schemas, OptimizationContext ctx) {
            ctx.record(code(), queryName, "replaced node with B", node.location());
            return NODE_B;
        }
    };

    @Test
    @DisplayName("no-op rule returns the original node unchanged")
    void noOpReturnsSameNode() {
        var ctx = new OptimizationContext();
        RelNode result = NO_OP.apply(NODE_A, "Q", SchemaAnnotations.empty(), ctx);

        assertThat(result).isSameAs(NODE_A);
    }

    @Test
    @DisplayName("no-op rule records nothing")
    void noOpRecordsNothing() {
        var ctx = new OptimizationContext();
        NO_OP.apply(NODE_A, "Q", SchemaAnnotations.empty(), ctx);

        assertThat(ctx.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("firing rule returns a different node")
    void firingRuleReturnsDifferentNode() {
        var ctx = new OptimizationContext();
        RelNode result = ALWAYS_FIRES.apply(NODE_A, "Q", SchemaAnnotations.empty(), ctx);

        assertThat(result).isSameAs(NODE_B);
    }

    @Test
    @DisplayName("firing rule records exactly one transformation")
    void firingRuleRecordsOneTransformation() {
        var ctx = new OptimizationContext();
        ALWAYS_FIRES.apply(NODE_A, "Q", SchemaAnnotations.empty(), ctx);

        assertThat(ctx.size()).isEqualTo(1);
        assertThat(ctx.records().get(0).code()).isEqualTo(OptimizationCode.SEL_001);
        assertThat(ctx.records().get(0).relationName()).isEqualTo("Q");
    }

    @Test
    @DisplayName("code() returns the correct OptimizationCode")
    void codeReturnsCorrectValue() {
        assertThat(NO_OP.code()).isEqualTo(OptimizationCode.EXPR_001);
        assertThat(ALWAYS_FIRES.code()).isEqualTo(OptimizationCode.SEL_001);
    }

    @Test
    @DisplayName("codes() defaults to the single primary code")
    void codesDefaultsToPrimary() {
        assertThat(NO_OP.codes()).containsExactly(OptimizationCode.EXPR_001);
        assertThat(ALWAYS_FIRES.codes()).containsExactly(OptimizationCode.SEL_001);
    }

    @Test
    @DisplayName("name() defaults to the primary code's string")
    void nameDefaultsToCodeString() {
        assertThat(NO_OP.name()).isEqualTo("EXPR-001");
    }

    @Test
    @DisplayName("idempotency: applying no-op twice records nothing both times")
    void idempotentNoOp() {
        var ctx = new OptimizationContext();
        NO_OP.apply(NODE_A, "Q", SchemaAnnotations.empty(), ctx);
        NO_OP.apply(NODE_A, "Q", SchemaAnnotations.empty(), ctx);

        assertThat(ctx.isEmpty()).isTrue();
    }
}

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

import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ConsolidationFunction;
import com.darkcollective.relix.ast.DownsampleNode;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SessionizeNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalLong;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;

@DisplayName("Partition pruning into SESSIONIZE / DOWNSAMPLE — SESSION-001 / DOWNSAMPLE-001 (#535)")
final class SelectionIntoTimeSeriesPassTest {

    private OptimizationContext ctx;

    @BeforeEach
    void setUp() { ctx = new OptimizationContext(); }

    private RelNode apply(RelNode node) {
        return SelectionIntoTimeSeriesPass.apply(node, "Q", SchemaAnnotations.empty(), ctx);
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private static final RelationNode EVENTS = rel("Events");

    private static SessionizeNode sessionize(RelNode input, String... keys) {
        return AstBuilders.sessionize("ts", num("300"),
                List.of(keys), "session",input);
    }

    private static DownsampleNode downsample(RelNode input, OptionalLong maxRows, String... keys) {
        return AstBuilders.downsample("ts", "5m", ConsolidationFunction.AVG,
                List.of(keys), maxRows, input);
    }

    private static Predicate eq(String column, String value) {
        return cmp(attr(column), ComparisonOperator.EQUAL,
                str(value));
    }

    /** The input of the operator sitting at {@code node}, whatever kind it is. */
    private static RelNode innerOf(RelNode node) {
        return node.children().getFirst();
    }

    // =========================================================================
    // SESSION-001
    // =========================================================================

    @Nested
    @DisplayName("SESSION-001 — σ on a PER key below SESSIONIZE")
    class Session001 {

        @Test
        @DisplayName("the equality moves below the operator and is removed from above it")
        void equalityPushedBelow() {
            RelNode result = apply(select(eq("user_id", "u1"),
                    sessionize(EVENTS, "user_id")));

            assertThat(result).isNode(SessionizeNode.class);
            RelNode inner = innerOf(result);
            assertThat(inner).isNode(SelectionNode.class);
            assertThat(((SelectionNode) inner).predicate()).isEqualTo(eq("user_id", "u1"));
            assertThat(ctx.recordsFor(OptimizationCode.SESSION_001)).hasSize(1);
        }

        @Test
        @DisplayName("the operator's own configuration survives the rebuild")
        void configurationPreserved() {
            SessionizeNode result = (SessionizeNode) apply(
                    select(eq("user_id", "u1"), sessionize(EVENTS, "user_id")));

            assertThat(result.orderColumn()).isEqualTo("ts");
            assertThat(result.sessionColumn()).isEqualTo("session");
            assertThat(result.partitionKeys()).containsExactly("user_id");
        }

        @Test
        @DisplayName("a non-key conjunct stays above as a residual σ")
        void nonKeyConjunctStaysAbove() {
            RelNode result = apply(select(
                    and(eq("user_id", "u1"), eq("kind", "click")),
                    sessionize(EVENTS, "user_id")));

            assertThat(result).isNode(SelectionNode.class);
            assertThat(((SelectionNode) result).predicate()).isEqualTo(eq("kind", "click"));
            assertThat(((SelectionNode) result).input()).isNode(SessionizeNode.class);
        }

        @Test
        @DisplayName("a global (PER-less) SESSIONIZE has no partition dimension")
        void globalSessionizeIsANoOp() {
            RelNode tree = select(eq("user_id", "u1"), sessionize(EVENTS));

            assertThat(apply(tree)).isSameAs(tree);
            assertThat(ctx.recordsFor(OptimizationCode.SESSION_001)).isEmpty();
        }

        @Test
        @DisplayName("an inequality on the key is not a partition selection")
        void inequalityDoesNotFire() {
            RelNode tree = select(
                    cmp(attr("user_id"),
                            ComparisonOperator.GREATER, num("1")),
                    sessionize(EVENTS, "user_id"));

            assertThat(apply(tree)).isSameAs(tree);
        }
    }

    // =========================================================================
    // DOWNSAMPLE-001
    // =========================================================================

    @Nested
    @DisplayName("DOWNSAMPLE-001 — σ on a PER key below DOWNSAMPLE")
    class Downsample001 {

        @Test
        @DisplayName("the equality moves below the operator")
        void equalityPushedBelow() {
            RelNode result = apply(select(eq("host", "h1"),
                    downsample(EVENTS, OptionalLong.empty(), "host")));

            assertThat(result).isNode(DownsampleNode.class);
            assertThat(innerOf(result)).isNode(SelectionNode.class);
            assertThat(ctx.recordsFor(OptimizationCode.DOWNSAMPLE_001)).hasSize(1);
        }

        @Test
        @DisplayName("FOR n ROWS blocks the push — it takes the n newest buckets across all groups")
        void maxRowsBlocksThePush() {
            // DownsampleExecutor sorts *all* output rows by bucket DESC and takes n, so
            // with two groups and FOR 2 ROWS, filtering afterwards can leave one row
            // while filtering first would produce two. Not a conservative choice — a
            // counterexample.
            RelNode tree = select(eq("host", "h1"),
                    downsample(EVENTS, OptionalLong.of(2), "host"));

            assertThat(apply(tree)).isSameAs(tree);
            assertThat(ctx.recordsFor(OptimizationCode.DOWNSAMPLE_001)).isEmpty();
        }

        @Test
        @DisplayName("a predicate on the timestamp column is a range restriction, not a partition")
        void timestampPredicateDoesNotFire() {
            RelNode tree = select(eq("ts", "2026-01-01"),
                    downsample(EVENTS, OptionalLong.empty(), "host"));

            assertThat(apply(tree)).isSameAs(tree);
        }

        @Test
        @DisplayName("a PER-less DOWNSAMPLE has no group dimension")
        void globalDownsampleIsANoOp() {
            RelNode tree = select(eq("host", "h1"),
                    downsample(EVENTS, OptionalLong.empty()));

            assertThat(apply(tree)).isSameAs(tree);
        }

        @Test
        @DisplayName("the bucket configuration survives the rebuild")
        void configurationPreserved() {
            DownsampleNode result = (DownsampleNode) apply(select(eq("host", "h1"),
                    downsample(EVENTS, OptionalLong.empty(), "host")));

            assertThat(result.timestampColumn()).isEqualTo("ts");
            assertThat(result.interval()).isEqualTo("5m");
            assertThat(result.function()).isEqualTo(ConsolidationFunction.AVG);
            assertThat(result.groupingKeys()).containsExactly("host");
        }
    }
}

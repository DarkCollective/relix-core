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
package com.darkcollective.relix.plan.internal;

import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.plan.PhysicalNodeCorpus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.darkcollective.relix.ast.AstBuilders.attr;
import static com.darkcollective.relix.ast.AstBuilders.cmp;
import static com.darkcollective.relix.ast.AstBuilders.func;
import static com.darkcollective.relix.ast.AstBuilders.num;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shape {@link SimilarityJoinPlanning} recognises, on hand-built joins: both inputs
 * have a column {@code x}, so an argument is placed only by its qualifier.
 */
@DisplayName("Similarity join recognition")
final class SimilarityJoinPlanningTest {

    private static final FunctionCatalog FUNCTIONS = FunctionCatalog.discover();

    private static PhysicalNode.Join join(Optional<Predicate> condition) {
        return new PhysicalNode.Join(PhysicalNodeCorpus.SCHEMA, PhysicalNode.JoinKind.INNER,
                PhysicalNode.JoinAlgorithm.NESTED_LOOP, PhysicalNode.BuildSide.RIGHT, condition,
                PhysicalNode.JoinKeys.none(), Set.of("l"), Set.of("r"),
                PhysicalNodeCorpus.LEFT, PhysicalNodeCorpus.RIGHT);
    }

    private static Optional<PhysicalNode.SimilarityJoin> plan(Operand a, Operand b) {
        Predicate condition = cmp(func("EditSimilarity", a, b),
                ComparisonOperator.GREATER_EQUAL, num("0.8"));
        return SimilarityJoinPlanning.plan(join(Optional.of(condition)), FUNCTIONS);
    }

    @Test
    @DisplayName("a column of each input, told apart by qualifier, is recognised")
    void recognised() {
        assertThat(plan(attr("l.x"), attr("r.x"))).isPresent();
        assertThat(plan(attr("r.x"), attr("l.x"))).isPresent();
    }

    @Test
    @DisplayName("an argument that is not a column, or names no single input, is not")
    void notColumns() {
        assertThat(plan(num("1"), attr("r.x"))).isEmpty();
        assertThat(plan(attr("x"), attr("r.x"))).isEmpty();
        assertThat(plan(attr("l.x"), attr("x"))).isEmpty();
        assertThat(plan(attr("l.x"), attr("l.x"))).isEmpty();
    }

    @Test
    @DisplayName("a join without a condition has nothing to recognise")
    void noCondition() {
        assertThat(SimilarityJoinPlanning.plan(join(Optional.empty()), FUNCTIONS)).isEmpty();
    }

    @Test
    @DisplayName("a similarity join cannot be made from a join without a condition")
    void factoryNeedsACondition() {
        assertThatThrownBy(() -> PhysicalNode.SimilarityJoin.of(join(Optional.empty()),
                "EditSimilarity", 0, 0,
                com.darkcollective.relix.function.Blocking.Bound.AT_LEAST,
                java.math.BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("condition");
    }
}

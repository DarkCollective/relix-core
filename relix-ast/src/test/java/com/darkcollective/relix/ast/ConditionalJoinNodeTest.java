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
package com.darkcollective.relix.ast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConditionalJoinNode — the uniform condition-carrying join shape")
final class ConditionalJoinNodeTest {

    private static final RelNode L = new RelationNode("L");
    private static final RelNode R = new RelationNode("R");
    private static final SourceLocation LOC = new SourceLocation("test.relix", 3, 14);

    private static Predicate cond(String col) {
        return new ComparisonPredicate(new AttributeOperand(col),
                ComparisonOperator.EQUAL, new NumberOperand("1"));
    }

    /** One instance of every member type, all built at {@link #LOC}. */
    private static List<ConditionalJoinNode> members() {
        Predicate c = cond("a");
        return List.of(
                new ThetaJoinNode(L, R, c, LOC),
                new LeftOuterJoinNode(L, R, c, LOC),
                new RightOuterJoinNode(L, R, c, LOC),
                new FullOuterJoinNode(L, R, c, LOC),
                new SemiJoinNode(L, R, c, LOC),
                new AntiJoinNode(L, R, c, LOC),
                new PairwiseUniversalNode(L, R, c, LOC));
    }

    @Test
    @DisplayName("rebuild preserves the concrete type and the source location")
    void rebuildPreservesTypeAndLocation() {
        RelNode newLeft = new RelationNode("L2");
        Predicate newCond = cond("b");
        for (ConditionalJoinNode join : members()) {
            ConditionalJoinNode rebuilt = join.rebuild(newLeft, join.right(), newCond);
            assertThat(rebuilt).isExactlyInstanceOf(join.getClass());
            assertThat(rebuilt.location()).isEqualTo(LOC);
            assertThat(rebuilt.left()).isSameAs(newLeft);
            assertThat(rebuilt.right()).isSameAs(join.right());
            assertThat(rebuilt.condition()).isSameAs(newCond);
        }
    }

    @Test
    @DisplayName("mapChildren routes through rebuild: children replaced, type/condition/location kept")
    void mapChildrenUsesRebuild() {
        RelNode replacement = new RelationNode("X");
        for (ConditionalJoinNode join : members()) {
            RelNode mapped = join.mapChildren(child -> replacement);
            assertThat(mapped).isExactlyInstanceOf(join.getClass());
            ConditionalJoinNode m = (ConditionalJoinNode) mapped;
            assertThat(m.left()).isSameAs(replacement);
            assertThat(m.right()).isSameAs(replacement);
            assertThat(m.condition()).isSameAs(join.condition());
            assertThat(m.location()).isEqualTo(LOC);
        }
    }

    @Test
    @DisplayName("mapChildren identity: same references back means the same node back")
    void mapChildrenIdentity() {
        for (ConditionalJoinNode join : members()) {
            assertThat(join.mapChildren(child -> child)).isSameAs(join);
        }
    }

    @Test
    @DisplayName("children() lists left then right for every member")
    void childrenOrder() {
        for (ConditionalJoinNode join : members()) {
            assertThat(join.children()).containsExactly(L, R);
        }
    }
}

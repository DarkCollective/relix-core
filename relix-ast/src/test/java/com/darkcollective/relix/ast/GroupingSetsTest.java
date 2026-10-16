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

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The invariants of a γ's grouping sets and of GROUPING (#125). */
final class GroupingSetsTest {

    private static final List<GroupingKey> TWO = GroupingKey.columns(List.of("a", "b"));
    private static final List<AggregateFunction> COUNT = List.of(agg(AggregateOperator.COUNT, "x"));

    private static AggregationNode node(List<List<Integer>> sets) {
        return groupingSets(TWO, sets, COUNT, rel("R"));
    }

    @Test
    void setsAreCanonical() {
        AggregationNode rollup = node(List.of(List.of(1, 0, 0), List.of(0), List.of()));
        assertThat(rollup.groupingSets()).containsExactly(List.of(0, 1), List.of(0), List.of());
        assertThat(rollup.hasGroupingSets()).isTrue();
        assertThat(rollup.inEverySet(0)).isFalse();
        assertThat(rollup.producesRowOverEmptyInput()).isTrue();
        assertThat(rollup.withInput(rel("S")).groupingSets()).isEqualTo(rollup.groupingSets());
        // One set naming every key is the ordinary grouping.
        assertThat(node(List.of(List.of(0, 1))).hasGroupingSets()).isFalse();
        assertThat(node(List.of()).effectiveGroupingSets()).containsExactly(List.of(0, 1));
        assertThat(groupBy(null, COUNT, rel("R")).effectiveGroupingSets()).containsExactly(List.of());
    }

    @Test
    void setsNameRealKeysInOrderAndEveryKey() {
        assertThatThrownBy(() -> node(List.of(List.of(2), List.of()))).hasMessageContaining("there are 2 keys");
        assertThatThrownBy(() -> node(List.of(List.of(-1), List.of()))).hasMessageContaining("names key -1");
        assertThatThrownBy(() -> node(List.of(Arrays.asList((Integer) null), List.of())))
                .hasMessageContaining("names key null");
        assertThatThrownBy(() -> node(List.of(List.of(1), List.of(0)))).hasMessageContaining("key 0 before key 1");
        assertThatThrownBy(() -> node(List.of(List.of(0), List.of()))).hasMessageContaining("key 1 is in no grouping set");
    }

    @Test
    void groupingNamesKeysByExpressionOrOutputName() {
        AggregationNode n = groupingSets(List.of(GroupingKey.aliased(func("YEAR", attr("d")), "yr"),
                GroupingKey.column("b")), List.of(List.of(0, 1), List.of(0)), COUNT, rel("R"));
        assertThat(n.groupingIndex(func("YEAR", attr("d")))).hasValue(0);
        assertThat(n.groupingIndex(attr("yr"))).hasValue(0);
        assertThat(n.groupingIndex(attr("B"))).hasValue(1);
        assertThat(n.groupingIndex(attr("c"))).isEmpty();
        assertThat(n.groupingIndex(num("1"))).isEmpty();
        assertThat(groupBy(null, COUNT, rel("R")).groupingIndex(attr("a"))).isEmpty();
    }

    @Test
    void groupingTakesKeysAndNothingElse() {
        AggregateFunction g = AggregateFunction.grouping(List.of(attr("a"), attr("b")), "g");
        assertThat(g.groupingArguments()).containsExactly(attr("a"), attr("b"));
        assertThat(AggregateFunction.of(AggregateOperator.GROUPING, attr("a")).groupingArguments())
                .containsExactly(attr("a"));
        assertThat(AggregateFunction.of(AggregateOperator.GROUPING, attr("a")).outputName()).isEqualTo("grouping_a");
        assertThat(AggregateFunction.of(AggregateOperator.GROUPING,
                arrayOf(attr("a"), attr("b"))).outputName())
                .isEqualTo("grouping_expr");
        assertThat(agg(AggregateOperator.SUM, "a").groupingArguments()).isEmpty();
        Operand keys = arrayOf(attr("a"));
        assertThatThrownBy(() -> distinctAgg(AggregateOperator.GROUPING, keys, "g"))
                .hasMessageContaining("takes nothing else");
        assertThatThrownBy(() -> argAgg(AggregateOperator.GROUPING, keys, attr("y")))
                .hasMessageContaining("takes nothing else");
        // No factory builds an ordered aggregate without a second argument: the record is the subject.
        assertThatThrownBy(() -> new AggregateFunction(AggregateOperator.GROUPING, keys, Optional.empty(),
                Optional.empty(), false, List.of(asc("a")))).hasMessageContaining("takes nothing else");
    }
}

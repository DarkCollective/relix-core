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

import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.ast.AstAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

final class WindowNodeTest {

    private static final RelNode INPUT = new RelationNode("Ticks");
    private static final WindowFunction AVG_PRICE =
            new WindowFunction.AggregateWindow(AggregateOperator.AVG, new AttributeOperand("price"));
    private static final List<SortSpecification> SORT =
            List.of(asc("ts"));

    private static WindowNode rolling() {
        return new WindowNode(AVG_PRICE, List.of("ticker"), SORT,
                new WindowFrame.BoundedFrame(3), "avg3", INPUT);
    }

    @Test
    void exposesItsFields() {
        var w = rolling();
        assertThat(w.function()).isSameAs(AVG_PRICE);
        assertThat(w.partitionKeys()).containsExactly("ticker");
        assertThat(w.sortSpecs()).containsExactly(asc("ts"));
        assertThat(w.frame()).isEqualTo(new WindowFrame.BoundedFrame(3));
        assertThat(w.outputColumn()).isEqualTo("avg3");
        assertThat(w.input()).isSameAs(INPUT);
    }

    @Test
    void rejectsBlankOutputColumn() {
        assertThatThrownBy(() -> new WindowNode(AVG_PRICE, List.of(), SORT,
                new WindowFrame.CumulativeFrame(), " ", INPUT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outputColumn");
    }

    @Test
    void rejectsNullFunction() {
        assertThatNullPointerException().isThrownBy(() -> new WindowNode(null, List.of(), SORT,
                new WindowFrame.CumulativeFrame(), "c", INPUT));
    }

    @Test
    void boundedFrameRejectsZero() {
        assertThatThrownBy(() -> new WindowFrame.BoundedFrame(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1");
    }

    @Test
    void materializesAsBag() {
        assertThat(rolling().materializationMode()).isEqualTo(MaterializationMode.BAG);
    }

    @Test
    void exposesInputAsSoleChild() {
        assertThat(rolling().children()).containsExactly(INPUT);
    }

    @Test
    void mapChildrenRebuildsWithReplacedInput() {
        var w = rolling();
        RelNode replacement = new RelationNode("Other");
        RelNode mapped = w.mapChildren(child -> replacement);
        assertThat(mapped).isNode(WindowNode.class);
        assertThat(((WindowNode) mapped).input()).isSameAs(replacement);
        assertThat(((WindowNode) mapped).outputColumn()).isEqualTo("avg3");
    }

    @Test
    void mapChildrenReturnsSameInstanceWhenUnchanged() {
        var w = rolling();
        assertThat(w.mapChildren(child -> child)).isSameAs(w);
    }

    @Test
    void prettyPrintsRolling() {
        assertThat(rolling().prettyPrint())
                .isEqualTo("ROLLING AVG(price) OVER 3 ROWS SORT ts PER ticker AS avg3 (Ticks)");
    }

    @Test
    void prettyPrintsCumulativeWithoutPartition() {
        var w = new WindowNode(
                new WindowFunction.AggregateWindow(AggregateOperator.SUM, new AttributeOperand("rev")),
                List.of(), SORT, new WindowFrame.CumulativeFrame(), "running", INPUT);
        assertThat(w.prettyPrint())
                .isEqualTo("ROLLING SUM(rev) OVER ALL ROWS SORT ts AS running (Ticks)");
    }

    @Test
    void rankingStubPrettyPrintsWindowKeyword() {
        var w = new WindowNode(
                new WindowFunction.RankingWindow(RankingFunction.ROW_NUMBER, Optional.empty()),
                List.of("dept"), List.of(desc("score")),
                new WindowFrame.PartitionFrame(), "rnk", INPUT);
        assertThat(w.prettyPrint())
                .isEqualTo("WINDOW ROW_NUMBER() SORT score DESC PER dept AS rnk (Ticks)");
    }
}

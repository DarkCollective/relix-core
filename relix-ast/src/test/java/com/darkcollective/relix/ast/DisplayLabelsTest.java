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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import com.darkcollective.relix.ast.AstBuilders;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Tests for {@link DisplayLabels} — the sub-expression renderers shared by the
 * IR report ({@code :tree}) and the physical plan printer ({@code :explain}).
 *
 * <p>These pin the exact spelling, because the whole point of the class is that
 * one construct reads the same in both views; a change here is a change to both
 * and should be deliberate.
 */
@DisplayName("DisplayLabels — shared node-label sub-expressions")
final class DisplayLabelsTest {

    @Nested
    @DisplayName("bound")
    class Bound {

        @Test
        @DisplayName("a whole number drops its fractional part")
        void wholeNumber() {
            assertThat(DisplayLabels.bound(100.0)).isEqualTo("100");
            assertThat(DisplayLabels.bound(-3.0)).isEqualTo("-3");
        }

        @Test
        @DisplayName("a fractional bound keeps its decimals")
        void fractional() {
            assertThat(DisplayLabels.bound(0.25)).isEqualTo("0.25");
        }

        @Test
        @DisplayName("non-finite bounds fall back to the plain double rendering")
        void nonFinite() {
            assertThat(DisplayLabels.bound(Double.POSITIVE_INFINITY)).isEqualTo("Infinity");
            assertThat(DisplayLabels.bound(Double.NaN)).isEqualTo("NaN");
        }
    }

    @Nested
    @DisplayName("sortKey")
    class SortKey {

        @Test
        @DisplayName("ascending and descending use arrow glyphs")
        void directions() {
            assertThat(DisplayLabels.sortKey(
                    sortKey(attr("ts"), SortDirection.ASC))).isEqualTo("ts↑");
            assertThat(DisplayLabels.sortKey(
                    sortKey(attr("ts"), SortDirection.DESC))).isEqualTo("ts↓");
        }

        @Test
        @DisplayName("rejects null")
        void rejectsNull() {
            assertThatNullPointerException().isThrownBy(() -> DisplayLabels.sortKey(null));
        }
    }

    @Nested
    @DisplayName("produceBound")
    class Produce {

        @Test
        @DisplayName("renders column, operator symbol, and limit")
        void rendersBound() {
            assertThat(DisplayLabels.produceBound(
                    produceBound("n", ComparisonOperator.LESS_EQUAL, num("10"))))
                    .isEqualTo("n ≤ 10");
        }
    }

    @Nested
    @DisplayName("window")
    class Window {

        private static final WindowFrame ROWS_3 = new WindowFrame.BoundedFrame(3);

        @Test
        @DisplayName("an aggregate window is ROLLING; a ranking window is WINDOW")
        void keywordFollowsFunctionKind() {
            var agg = new WindowFunction.AggregateWindow(AggregateOperator.SUM, attr("price"));
            assertThat(DisplayLabels.window(agg, ROWS_3,
                    List.of(sortKey(attr("t"), SortDirection.ASC)),
                    List.of("g"), "w"))
                    .isEqualTo("ROLLING SUM(price) OVER 3 ROWS SORT t↑ PER g AS w");

            var rank = new WindowFunction.RankingWindow(RankingFunction.ROW_NUMBER, Optional.empty());
            assertThat(DisplayLabels.window(rank, new WindowFrame.PartitionFrame(),
                    List.of(sortKey(attr("t"), SortDirection.DESC)),
                    List.of(), "rn"))
                    .isEqualTo("WINDOW ROW_NUMBER() SORT t↓ AS rn");
        }

        @Test
        @DisplayName("a window function renders every optional operand it carries")
        void optionalWindowOperands() {
            // Each of these is an Optional.map(…).orElse("") and the existing cases all
            // take the orElse. The mapping half is what distinguishes NTILE(4) from
            // NTILE(), and LAG(x, 1, 0) from LAG(x, 1) — two different functions that
            // would print identically if the arm were dropped.
            assertThat(DisplayLabels.windowFunction(new WindowFunction.RankingWindow(
                    RankingFunction.NTILE, Optional.of(num("4")))))
                    .isEqualTo("NTILE(4)");
            assertThat(DisplayLabels.windowFunction(new WindowFunction.RankingWindow(
                    RankingFunction.ROW_NUMBER, Optional.empty())))
                    .isEqualTo("ROW_NUMBER()");

            assertThat(DisplayLabels.windowFunction(new WindowFunction.OffsetWindow(
                    OffsetFunction.LAG, attr("amount"),
                    Optional.of(num("2")), Optional.of(num("0")))))
                    .as("offset and default").isEqualTo("LAG(amount, 2, 0)");
            assertThat(DisplayLabels.windowFunction(new WindowFunction.OffsetWindow(
                    OffsetFunction.LAG, attr("amount"),
                    Optional.of(num("2")), Optional.empty())))
                    .as("offset only").isEqualTo("LAG(amount, 2)");
            assertThat(DisplayLabels.windowFunction(new WindowFunction.OffsetWindow(
                    OffsetFunction.FIRST_VALUE, attr("amount"),
                    Optional.empty(), Optional.empty())))
                    .as("neither").isEqualTo("FIRST_VALUE(amount)");
        }

        @Test
        @DisplayName("frames render as OVER n ROWS / OVER ALL ROWS / nothing")
        void frames() {
            assertThat(DisplayLabels.windowFrame(ROWS_3)).isEqualTo(" OVER 3 ROWS");
            assertThat(DisplayLabels.windowFrame(new WindowFrame.CumulativeFrame()))
                    .isEqualTo(" OVER ALL ROWS");
            assertThat(DisplayLabels.windowFrame(new WindowFrame.PartitionFrame())).isEmpty();
        }
    }

    @Nested
    @DisplayName("optimize")
    class Optimize {

        @Test
        @DisplayName("renders objective, constraints, and grouping keys")
        void basic() {
            assertThat(DisplayLabels.optimize(
                    ObjectiveSense.MAXIMIZE, attr("value"),
                    List.of(constraint(attr("weight"),
                            ComparisonOperator.LESS_EQUAL, 100)),
                    List.of("region"), Optional.empty()))
                    .isEqualTo("OPTIMIZE max SUM(value) s.t. SUM(weight)≤100 [region]");
        }

        @Test
        @DisplayName("multiple constraints are comma-separated")
        void multipleConstraints() {
            assertThat(DisplayLabels.optimize(
                    ObjectiveSense.MINIMIZE, attr("cost"),
                    List.of(constraint(attr("w"), ComparisonOperator.LESS_EQUAL, 10),
                            constraint(attr("c"), ComparisonOperator.GREATER_EQUAL, 5)),
                    List.of(), Optional.empty()))
                    .isEqualTo("OPTIMIZE min SUM(cost) s.t. SUM(w)≤10, SUM(c)≥5");
        }

        @Test
        @DisplayName("an allocation spec renders with a glyph arrow")
        void allocation() {
            assertThat(DisplayLabels.optimize(
                    ObjectiveSense.MAXIMIZE, attr("ret"),
                    List.of(constraint(attr("risk"),
                            ComparisonOperator.LESS_EQUAL, 1)),
                    List.of(),
                    Optional.of(AstBuilders.allocation(0.0, 1.0, "alloc"))))
                    .isEqualTo("OPTIMIZE ALLOCATE [0,1] →alloc max SUM(ret) s.t. SUM(risk)≤1");
        }
    }
}

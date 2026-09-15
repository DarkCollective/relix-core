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

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OptimizationResult")
final class OptimizationResultTest {

    private static final RelNode ORIGINAL  = rel("Original");
    private static final RelNode OPTIMIZED = rel("Optimized");

    private static TransformationRecord rec(OptimizationCode code) {
        return new TransformationRecord(code, "Q", "detail", SourceLocation.UNKNOWN);
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("valid construction stores all fields")
        void validConstruction() {
            var applied = List.of(rec(OptimizationCode.EXPR_001));
            var result = new OptimizationResult("Q", ORIGINAL, OPTIMIZED, applied);

            assertThat(result.queryName()).isEqualTo("Q");
            assertThat(result.original()).isSameAs(ORIGINAL);
            assertThat(result.optimized()).isSameAs(OPTIMIZED);
            assertThat(result.applied()).hasSize(1);
        }

        @Test
        @DisplayName("null queryName throws NullPointerException")
        void nullQueryNameThrows() {
            assertThatThrownBy(() ->
                    new OptimizationResult(null, ORIGINAL, OPTIMIZED, List.of()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("queryName");
        }

        @Test
        @DisplayName("blank queryName throws IllegalArgumentException")
        void blankQueryNameThrows() {
            assertThatThrownBy(() ->
                    new OptimizationResult("", ORIGINAL, OPTIMIZED, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("queryName");
        }

        @Test
        @DisplayName("null original throws NullPointerException")
        void nullOriginalThrows() {
            assertThatThrownBy(() ->
                    new OptimizationResult("Q", null, OPTIMIZED, List.of()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("original");
        }

        @Test
        @DisplayName("null optimized throws NullPointerException")
        void nullOptimizedThrows() {
            assertThatThrownBy(() ->
                    new OptimizationResult("Q", ORIGINAL, null, List.of()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("optimized");
        }

        @Test
        @DisplayName("null applied throws NullPointerException")
        void nullAppliedThrows() {
            assertThatThrownBy(() ->
                    new OptimizationResult("Q", ORIGINAL, OPTIMIZED, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("applied");
        }

        @Test
        @DisplayName("applied list is defensively copied")
        void appliedIsDefensivelyCopied() {
            var mutable = new ArrayList<TransformationRecord>();
            mutable.add(rec(OptimizationCode.EXPR_001));
            var result = new OptimizationResult("Q", ORIGINAL, OPTIMIZED, mutable);

            // Mutating the source list must not affect the result.
            mutable.add(rec(OptimizationCode.SEL_001));

            assertThat(result.applied()).hasSize(1);
        }

        @Test
        @DisplayName("applied list returned from result is unmodifiable")
        void appliedListUnmodifiable() {
            var result = new OptimizationResult("Q", ORIGINAL, OPTIMIZED,
                    List.of(rec(OptimizationCode.EXPR_001)));

            assertThatThrownBy(() -> result.applied().add(rec(OptimizationCode.SEL_001)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("wasOptimized()")
    class WasOptimized {

        @Test
        @DisplayName("returns false when applied list is empty")
        void falseWhenNoTransformations() {
            var result = new OptimizationResult("Q", ORIGINAL, ORIGINAL, List.of());
            assertThat(result.wasOptimized()).isFalse();
        }

        @Test
        @DisplayName("returns true when at least one transformation was applied")
        void trueWhenTransformationsPresent() {
            var result = new OptimizationResult("Q", ORIGINAL, OPTIMIZED,
                    List.of(rec(OptimizationCode.EXPR_001)));
            assertThat(result.wasOptimized()).isTrue();
        }
    }

    @Nested
    @DisplayName("transformationCount()")
    class TransformationCount {

        @Test
        @DisplayName("returns zero for empty applied list")
        void zeroForEmpty() {
            var result = new OptimizationResult("Q", ORIGINAL, ORIGINAL, List.of());
            assertThat(result.transformationCount()).isZero();
        }

        @Test
        @DisplayName("matches applied list size")
        void matchesAppliedSize() {
            var applied = List.of(
                    rec(OptimizationCode.EXPR_001),
                    rec(OptimizationCode.SEL_001),
                    rec(OptimizationCode.PROJ_001));
            var result = new OptimizationResult("Q", ORIGINAL, OPTIMIZED, applied);

            assertThat(result.transformationCount()).isEqualTo(3);
            assertThat(result.transformationCount()).isEqualTo(result.applied().size());
        }
    }
}

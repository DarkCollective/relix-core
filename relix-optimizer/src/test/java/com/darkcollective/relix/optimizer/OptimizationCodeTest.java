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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OptimizationCode")
final class OptimizationCodeTest {

    @Test
    @DisplayName("every constant has a non-blank code string")
    void everyCodeIsNonBlank() {
        for (OptimizationCode c : OptimizationCode.values()) {
            assertThat(c.code())
                    .as("code() for %s", c.name())
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("every constant has a non-blank description")
    void everyDescriptionIsNonBlank() {
        for (OptimizationCode c : OptimizationCode.values()) {
            assertThat(c.description())
                    .as("description() for %s", c.name())
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("all code strings are unique")
    void allCodesUnique() {
        Set<String> codes = Arrays.stream(OptimizationCode.values())
                .map(OptimizationCode::code)
                .collect(Collectors.toSet());
        assertThat(codes).hasSize(OptimizationCode.values().length);
    }

    @Test
    @DisplayName("code strings match pattern CATEGORY-nnn")
    void codeMatchesPattern() {
        for (OptimizationCode c : OptimizationCode.values()) {
            assertThat(c.code())
                    .as("code() for %s", c.name())
                    .matches("[A-Z]+-\\d{3}");
        }
    }

    @Test
    @DisplayName("category() returns the prefix before the dash")
    void categoryReturnsPrefixBeforeDash() {
        assertThat(OptimizationCode.EXPR_001.category()).isEqualTo("EXPR");
        assertThat(OptimizationCode.PRED_001.category()).isEqualTo("PRED");
        assertThat(OptimizationCode.SEL_001.category()).isEqualTo("SEL");
        assertThat(OptimizationCode.PROJ_001.category()).isEqualTo("PROJ");
        assertThat(OptimizationCode.JOIN_001.category()).isEqualTo("JOIN");
        assertThat(OptimizationCode.LIM_001.category()).isEqualTo("LIM");
    }

    @Test
    @DisplayName("toString() contains both code and description")
    void toStringContainsCodeAndDescription() {
        for (OptimizationCode c : OptimizationCode.values()) {
            String s = c.toString();
            assertThat(s).contains(c.code());
            assertThat(s).contains(c.description());
        }
    }

    @Test
    @DisplayName("the enum is exactly the expected set of codes")
    void allExpectedCodesPresent() {
        Set<String> actual = Arrays.stream(OptimizationCode.values())
                .map(OptimizationCode::code)
                .collect(Collectors.toSet());

        // Exact match (not just `contains`) so adding a code without listing it here
        // fails this test — the list cannot silently drift out of sync with the enum.
        assertThat(actual).containsExactlyInAnyOrder(
                "EXPR-001", "EXPR-002", "EXPR-003", "EXPR-004", "EXPR-005", "EXPR-006",
                "EXPR-007", "EXPR-008",
                "PRED-001", "PRED-002", "PRED-003", "PRED-004", "PRED-005", "PRED-006",
                "SEL-001",  "SEL-002",  "SEL-003",  "SEL-004",  "SEL-005",  "SEL-006",
                "SEL-007",  "SEL-008",  "SEL-009",
                "PROJ-001", "PROJ-002", "PROJ-003", "PROJ-004",
                "JOIN-001", "JOIN-002", "JOIN-004",
                "LATERAL-001",
                "EQ-001",
                "LIM-001",  "LIM-002",  "LIM-003",  "LIM-004",
                "AGG-001",
                "DIST-001", "DIST-002",
                "SORT-001",
                "PROD-001",
                "EMPTY-001", "EMPTY-002", "EMPTY-003",
                "NEST-001", "NEST-002", "NEST-003",
                "CLOSURE-001",
                "TRACE-001",
                "PATH-001",
                "FIX-001",
                "GEN-001",
                "WINDOW-001", "TOPK-001",
                "OPTIMIZE-001",
                "SESSION-001", "DOWNSAMPLE-001",
                "INLINE-001",
                "RENAME-001", "RENAME-002"
        );
    }

    @Test
    @DisplayName("total count is 60 rules")
    void totalCount() {
        assertThat(OptimizationCode.values()).hasSize(60);
    }

    /**
     * The category set is enumerable from the constants, and the enum's own Javadoc
     * lists it — so the two can drift, and did (#545: the header listed 8 categories
     * against 18 defined).  This pins the list; adding a category means adding it to
     * the Javadoc too.
     */
    @Test
    @DisplayName("the declared categories are exactly the ones the enum's Javadoc lists")
    void categoriesMatchTheDocumentedSet() {
        Set<String> declared = Arrays.stream(OptimizationCode.values())
                .map(OptimizationCode::category)
                .collect(Collectors.toSet());
        assertThat(declared).containsExactlyInAnyOrder(
                "EXPR", "PRED", "SEL", "PROJ", "JOIN", "LATERAL", "EQ", "LIM", "AGG", "DIST", "SORT",
                "PROD", "EMPTY", "NEST", "CLOSURE", "TRACE", "PATH", "FIX", "GEN", "WINDOW", "TOPK", "OPTIMIZE",
                "SESSION", "DOWNSAMPLE", "INLINE", "RENAME");
    }

    @Test
    @DisplayName("specific codes have expected descriptions")
    void specificDescriptions() {
        assertThat(OptimizationCode.EXPR_001.description())
                .contains("Constant arithmetic fold");
        assertThat(OptimizationCode.JOIN_001.description())
                .contains("theta join");
        assertThat(OptimizationCode.LIM_001.description())
                .contains("Limit pushed below projection");
    }
}

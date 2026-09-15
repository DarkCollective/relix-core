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

import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("QueryOptimizer — view inlining (inline-then-optimize)")
final class ViewInliningTest {

    private static final QueryOptimizer OPTIMIZER = new QueryOptimizer();

    private static List<OptimizationCode> codes(OptimizationResult result) {
        return result.applied().stream().map(TransformationRecord::code).toList();
    }

    @Test
    @DisplayName("a referenced view is inlined and recorded as INLINE-001")
    void recordsInlining() {
        // The query references the view V, so optimising it inlines V's body.
        SemanticModel model = model(
                "Base := [| a | b |\n" +
                "         | 1 | 2 |];\n" +
                "V    := { π a, b (Base) };\n" +
                "query { V };");

        OptimizationResult result = OPTIMIZER.optimize(model).get(0);

        assertThat(codes(result)).contains(OptimizationCode.INLINE_001);
    }

    @Test
    @DisplayName("an outer selection optimises across the inlined view boundary")
    void crossViewSelectionPushdown() {
        // σ b > 0 sits outside the view; after inlining V's own σ a > 0, the two
        // selections meet and can be pushed/merged — impossible without inlining.
        SemanticModel model = model(
                "Base  := [| a | b |\n" +
                "          | 1 | 2 |];\n" +
                "V     := { σ a > 0 (Base) };\n" +
                "Outer := { σ b > 0 (V) };\n" +
                "query Outer;");

        OptimizationResult result = OPTIMIZER.optimize(model).get(0);

        // The view is inlined, and at least one selection rule fires across the
        // former boundary (push below the view-alias rename and/or merge).
        assertThat(codes(result)).contains(OptimizationCode.INLINE_001);
        assertThat(codes(result)).anyMatch(c -> c.category().equals("SEL"));
        // The optimized tree no longer references the view by name.
        assertThat(referencesRelation(result.optimized(), "V")).isFalse();
    }

    @Test
    @DisplayName("nested views inline transitively (a view referencing another view)")
    void nestedViewsInline() {
        // query { Mid } → inline Mid, whose body references Inner → inline Inner too.
        SemanticModel model = model(
                "Base  := [| a |\n" +
                "          | 1 |];\n" +
                "Inner := { σ a > 0 (Base) };\n" +
                "Mid   := { π a (Inner) };\n" +
                "query { Mid };");

        OptimizationResult result = OPTIMIZER.optimize(model).get(0);

        // Both Mid and (transitively) Inner expand: INLINE-001 recorded ≥ 2 times,
        // and neither view name remains in the optimized tree.
        assertThat(codes(result).stream().filter(c -> c == OptimizationCode.INLINE_001).count())
                .isGreaterThanOrEqualTo(2);
        assertThat(referencesRelation(result.optimized(), "Mid")).isFalse();
        assertThat(referencesRelation(result.optimized(), "Inner")).isFalse();
    }

    /** True if any {@code RelationNode} in the tree names {@code relation} (case-insensitive). */
    private static boolean referencesRelation(com.darkcollective.relix.ast.RelNode node, String relation) {
        if (node instanceof com.darkcollective.relix.ast.RelationNode r) {
            return r.name().equalsIgnoreCase(relation);
        }
        return node.children().stream().anyMatch(c -> referencesRelation(c, relation));
    }
}

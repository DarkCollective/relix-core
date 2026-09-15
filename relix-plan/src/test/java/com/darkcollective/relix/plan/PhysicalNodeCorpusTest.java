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
package com.darkcollective.relix.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PhysicalNodeCorpus} is only worth anything if it is complete: a test that walks
 * "every physical operator" while quietly missing four proves less than it claims, and
 * the ones it misses are exactly those nobody remembered — which is the population the
 * corpus exists to reach.
 *
 * <p>Same guard and same reasoning as {@code RelNodeCorpusTest}, one layer down. The
 * logical hierarchy has had this since its corpus landed; the physical one had nothing,
 * so a new operator could ship with neither a printer test nor a JSON test and no
 * failure would follow.
 */
@DisplayName("PhysicalNodeCorpus covers every PhysicalNode kind, once each")
final class PhysicalNodeCorpusTest {

    /** Every concrete leaf of a sealed hierarchy, flattening nested sealed interfaces. */
    private static List<Class<?>> concreteKinds(Class<?> root) {
        Class<?>[] permitted = root.getPermittedSubclasses();
        if (permitted == null) {
            return List.of(root);
        }
        List<Class<?>> leaves = new ArrayList<>();
        for (Class<?> child : permitted) {
            leaves.addAll(concreteKinds(child));
        }
        return leaves;
    }

    @Test
    @DisplayName("every concrete kind appears")
    void everyKindAppears() {
        List<String> present = PhysicalNodeCorpus.everyKind().stream()
                .map(n -> n.getClass().getSimpleName())
                .toList();
        List<String> missing = concreteKinds(PhysicalNode.class).stream()
                .map(Class::getSimpleName)
                .filter(kind -> !present.contains(kind))
                .sorted()
                .toList();

        assertThat(missing)
                .as("PhysicalNode kinds with no PhysicalNodeCorpus entry — add one, with "
                        + "its Optionals and lists populated")
                .isEmpty();
    }

    @Test
    @DisplayName("no kind appears twice")
    void noKindAppearsTwice() {
        Map<String, Long> counts = PhysicalNodeCorpus.everyKind().stream()
                .collect(Collectors.groupingBy(n -> n.getClass().getSimpleName(),
                        Collectors.counting()));

        assertThat(counts).allSatisfy((kind, count) ->
                assertThat(count).as("entries for %s", kind).isEqualTo(1L));
    }

    @Test
    @DisplayName("the corpus is exactly as large as the hierarchy")
    void sizeMatchesHierarchy() {
        assertThat(PhysicalNodeCorpus.everyKind())
                .as("one entry per concrete PhysicalNode kind")
                .hasSize(concreteKinds(PhysicalNode.class).size());
    }
}

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RelNodeCorpus} is only worth anything if it is complete: a test that walks
 * "every kind" while silently missing four of them proves less than it claims, and the
 * kinds it misses are exactly the ones nobody remembered — which is the population the
 * corpus exists to reach.
 *
 * <p>Same guard, same reasoning, as {@code AstBuilderCoverageTest}: enumerate the sealed
 * hierarchy reflectively rather than maintaining a list here, so a new {@code permits}
 * entry fails the build until the corpus catches up.
 */
@DisplayName("RelNodeCorpus covers every RelNode kind, once each")
final class RelNodeCorpusTest {

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
        List<String> present = RelNodeCorpus.everyKind().stream()
                .map(n -> n.getClass().getSimpleName())
                .toList();
        List<String> missing = concreteKinds(RelNode.class).stream()
                .map(Class::getSimpleName)
                .filter(kind -> !present.contains(kind))
                .sorted()
                .toList();

        assertThat(missing)
                .as("RelNode kinds with no RelNodeCorpus entry — add one, built through AstBuilders")
                .isEmpty();
    }

    @Test
    @DisplayName("no kind appears twice")
    void noKindAppearsTwice() {
        Map<String, Long> counts = RelNodeCorpus.everyKind().stream()
                .collect(Collectors.groupingBy(n -> n.getClass().getSimpleName(),
                        Collectors.counting()));

        assertThat(counts).allSatisfy((kind, count) ->
                assertThat(count).as("entries for %s", kind).isEqualTo(1L));
    }

    @Test
    @DisplayName("the corpus is exactly as large as the hierarchy")
    void sizeMatchesHierarchy() {
        assertThat(RelNodeCorpus.everyKind())
                .hasSameSizeAs(concreteKinds(RelNode.class));
    }

    @Test
    @DisplayName("every entry is a record, so its components can be enumerated")
    void everyEntryIsARecord() {
        // The structural tests built on this corpus read a node's components through
        // getRecordComponents(). That works today because every RelNode kind is a record;
        // this states the dependency rather than letting a future non-record kind produce
        // a confusing failure somewhere else.
        assertThat(RelNodeCorpus.everyKind())
                .allSatisfy(node -> assertThat(node.getClass().isRecord())
                        .as("%s is a record", node.getClass().getSimpleName())
                        .isTrue());
    }

    @Test
    @DisplayName("entries populate their optional components where invariants allow")
    void optionalComponentsArePopulated() {
        // An entry left at its defaults still satisfies a structural test but tests less:
        // a node whose Optional is empty compares equal to one that dropped it, so an
        // empty Optional here is a hole in the mapChildren check. Listed exceptions are
        // nodes whose own invariants forbid populating everything at once.
        Map<String, List<String>> allowedEmpty = Map.of(
                // ρ rejects positional attributes and from->to pairs together, so the
                // corpus takes the positional form and leaves pairs empty.
                "RenameNode", List.of("pairs"));

        List<String> empties = new ArrayList<>();
        for (RelNode node : RelNodeCorpus.everyKind()) {
            String kind = node.getClass().getSimpleName();
            for (var component : node.getClass().getRecordComponents()) {
                Object value = read(component.getAccessor(), node);
                boolean empty = value instanceof java.util.Optional<?> opt && opt.isEmpty()
                        || value instanceof java.util.OptionalLong ol && ol.isEmpty()
                        || value instanceof List<?> list && list.isEmpty();
                if (empty && !allowedEmpty.getOrDefault(kind, List.of()).contains(component.getName())) {
                    empties.add(kind + "." + component.getName());
                }
            }
        }

        assertThat(empties)
                .as("corpus components left empty — populate them, or record why the "
                    + "node's invariants forbid it in allowedEmpty")
                .isEmpty();
    }

    private static Object read(java.lang.reflect.Method accessor, RelNode node) {
        try {
            return accessor.invoke(node);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot read " + accessor, e);
        }
    }

    @Test
    @DisplayName("the shared child leaves are distinguishable by name")
    void childLeavesAreDistinct() {
        // A source keyed by relation name has to be able to answer differently for the
        // two sides; that only works while the two leaves differ.
        assertThat(RelNodeCorpus.LEFT.name()).isNotEqualTo(RelNodeCorpus.RIGHT.name());
    }
}

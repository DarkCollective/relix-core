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

import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PhysicalNode#mapChildren} rebuilds a node with new children, and each of its
 * arms spells out that record's full component list by hand — the same shape, and the
 * same hazard, as {@code RelNodeMapChildrenTest} covers for the logical tree.
 *
 * <p>A node that gains a component and misses the switch loses it silently on every
 * rewrite: the plan still type-checks, the rewrite still runs, and the component is
 * simply gone. For a physical plan the components that would vanish are the ones that
 * decide how the query runs — a join's algorithm and build side, a distinct's streaming
 * flag, a scan's produce bound.
 *
 * <h2>Why the expected value is reconstructed rather than written down</h2>
 *
 * Each case rebuilds the expected node through the record's own <em>canonical
 * constructor</em>, substituting the children and passing every other component straight
 * through. A canonical constructor cannot drop a component, which is the whole point of
 * comparing against one; an expectation written out by hand would repeat the same
 * component list the implementation repeats and go stale in the same way.
 *
 * <p>The corpus is {@link PhysicalNodeCorpus}, so a newly permitted kind is covered here
 * as soon as it has a corpus entry, which {@code PhysicalNodeCorpusTest} already requires.
 */
@DisplayName("PhysicalNode.mapChildren — every kind")
final class PhysicalNodeMapChildrenTest {

    /**
     * The node every child is replaced with. Its heading is one column named for the job,
     * so it cannot be {@code equals} to anything the corpus holds — these assertions
     * compare rebuilt plans by value, and a replacement that collided with a real node
     * would make a dropped rewrite look like a successful one.
     */
    private static final PhysicalNode REPLACEMENT = new PhysicalNode.Empty(
            new Schema(List.of(new ColumnDefinition("__replaced__", ScalarType.ANY))));

    private static Stream<DynamicTest> forEachKind(String label,
                                                   java.util.function.Consumer<PhysicalNode> check) {
        return PhysicalNodeCorpus.everyKind().stream()
                .map(node -> DynamicTest.dynamicTest(
                        node.getClass().getSimpleName() + " — " + label,
                        () -> check.accept(node)));
    }

    @Nested
    @DisplayName("mapChildren()")
    final class MapChildren {

        @TestFactory
        @DisplayName("returns this when no child changed")
        Stream<DynamicTest> identityReturnsThis() {
            // The == no-op signal a rewrite driver reads to detect "nothing changed", and
            // what lets an untouched sub-tree be shared rather than copied.
            return forEachKind("identity returns this", node ->
                    assertThat(node.mapChildren(child -> child))
                            .as("%s must return itself when f is the identity",
                                    node.getClass().getSimpleName())
                            .isSameAs(node));
        }

        @TestFactory
        @DisplayName("replaces every child and preserves every other component")
        Stream<DynamicTest> replacesChildrenAndKeepsEverythingElse() {
            return forEachKind("rebuild preserves components", node -> {
                PhysicalNode mapped = node.mapChildren(child -> REPLACEMENT);

                if (node.children().isEmpty()) {
                    assertThat(mapped)
                            .as("%s has no children, so mapping must return it unchanged",
                                    node.getClass().getSimpleName())
                            .isSameAs(node);
                    return;
                }

                assertThat(mapped)
                        .as("%s changed a child, so it must not return itself",
                                node.getClass().getSimpleName())
                        .isNotSameAs(node);
                assertThat(mapped.children())
                        .as("%s must apply f to every child", node.getClass().getSimpleName())
                        .allSatisfy(child -> assertThat(child).isSameAs(REPLACEMENT));
                assertThat(mapped)
                        .as("%s must preserve every non-child component — compared against "
                            + "its own canonical constructor, which cannot drop one",
                                node.getClass().getSimpleName())
                        .isEqualTo(canonicalRebuild(node, REPLACEMENT));
            });
        }

        @TestFactory
        @DisplayName("replaces only the children f actually changes")
        Stream<DynamicTest> replacesOnlyWhatChanged() {
            // A binary arm's guard is `l == n.left() && r == n.right()`, so replacing only
            // the first short-circuits before the second comparison is evaluated — half of
            // every binary guard stays untested unless both positions are exercised.
            return PhysicalNodeCorpus.everyKind().stream()
                    .filter(node -> node.children().size() >= 2)
                    .flatMap(node -> Stream.of(0, node.children().size() - 1)
                            .distinct()
                            .map(position -> DynamicTest.dynamicTest(
                                    node.getClass().getSimpleName()
                                    + " — rewriting child " + position + " alone",
                                    () -> assertOnlyOneChildReplaced(node, position))));
        }

        private void assertOnlyOneChildReplaced(PhysicalNode node, int position) {
            List<PhysicalNode> children = node.children();
            PhysicalNode target = children.get(position);
            PhysicalNode mapped = node.mapChildren(child -> child == target ? REPLACEMENT : child);

            assertThat(mapped)
                    .as("%s changed child %d, so it must not return itself",
                            node.getClass().getSimpleName(), position)
                    .isNotSameAs(node);

            List<PhysicalNode> expected = new ArrayList<>(children);
            expected.set(position, REPLACEMENT);
            assertThat(mapped.children())
                    .as("%s must rewrite only child %d and leave the rest alone",
                            node.getClass().getSimpleName(), position)
                    .containsExactlyElementsOf(expected);
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Rebuilds {@code node} through its canonical constructor with every child replaced
     * by {@code replacement} — the independent expectation the implementation is checked
     * against.
     *
     * <p>Children are identified positionally against {@link PhysicalNode#children()}
     * rather than by type, so a plan-shaped component that is <em>not</em> a child is
     * passed through without the rebuild needing to know about it. Two kinds have one: a
     * {@code Why} carries the logical sub-tree it reifies, and a {@code LateralJoin} a
     * body planned per outer row.
     */
    private static PhysicalNode canonicalRebuild(PhysicalNode node, PhysicalNode replacement) {
        RecordComponent[] components = node.getClass().getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] args = new Object[components.length];
        List<PhysicalNode> children = node.children();
        int childIndex = 0;

        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            Object value = read(components[i], node);
            if (value instanceof PhysicalNode child
                    && childIndex < children.size()
                    && children.get(childIndex) == child) {
                args[i] = replacement;
                childIndex++;
            } else if (value instanceof List<?> list && !list.isEmpty()
                    && childIndex < children.size()
                    && children.get(childIndex) == list.getFirst()) {
                // A component that is a list of children, as COVER's factors are.
                args[i] = list.stream().map(unused -> replacement).toList();
                childIndex += list.size();
            } else {
                args[i] = value;
            }
        }
        assertThat(childIndex)
                .as("%s: every child must be reachable as a record component",
                        node.getClass().getSimpleName())
                .isEqualTo(children.size());

        try {
            Constructor<?> canonical = node.getClass().getDeclaredConstructor(types);
            canonical.setAccessible(true);
            return (PhysicalNode) canonical.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                    "cannot rebuild " + node.getClass().getSimpleName()
                    + " through its canonical constructor", e);
        }
    }

    private static Object read(RecordComponent component, Object node) {
        try {
            component.getAccessor().setAccessible(true);
            return component.getAccessor().invoke(node);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot read " + component.getName(), e);
        }
    }
}

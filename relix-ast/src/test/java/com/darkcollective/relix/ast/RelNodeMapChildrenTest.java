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
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RelNode#mapChildren} rebuilds a node with new children, and each of its 52 arms
 * spells out the record's full component list by hand:
 *
 * {@snippet lang = "java":
 * case PathNode n -> {
 *     RelNode in = f.apply(n.input());
 *     yield in == n.input() ? n
 *             : new PathNode(in, n.fromColumn(), n.toColumn(), false,
 *                            n.minHops(), n.maxHops(), n.depthColumn(), n.location());
 * }
 * }
 *
 * <p>A node that gains a component and misses this switch loses it silently on every
 * rewrite: the tree still type-checks, the pass still runs, and the component is simply
 * gone.  That is not a hypothetical failure mode — it is the one that produced
 * {@code QueryEvent.withTarget} reverting metrics to {@code NONE}, and
 * {@code PrettyPrinter} emitting an {@code IJOIN} form the grammar would not parse.  Both
 * were a record rebuilt by hand with a component left out, and neither was caught by the
 * compiler.
 *
 * <p>{@code mapChildren} is the canonical structural-rewrite helper, so every optimizer
 * pass recurses through it, and until now it was tested by hand against roughly a dozen
 * kinds — leaving 27 branches that no test executed.
 *
 * <h2>Why the expected value is reconstructed rather than written down</h2>
 *
 * <p>Each case rebuilds the expected node through the record's own <em>canonical
 * constructor</em>, substituting the children and passing every other component straight
 * through.  A canonical constructor cannot drop a component — that is the whole point of
 * comparing against one.  An expected value written out by hand would have to repeat the
 * same component list the implementation repeats, and would go stale in exactly the same
 * way, which is how a test ends up pinning a bug rather than catching it.
 *
 * <p>The corpus is {@link RelNodeCorpus}, so a newly permitted kind is covered here as
 * soon as it has a corpus entry, which {@code RelNodeCorpusTest} already requires.
 */
@DisplayName("RelNode.mapChildren — every kind")
final class RelNodeMapChildrenTest {

    /** The node every child is replaced with; distinct from anything in the corpus. */
    private static final RelationNode REPLACEMENT = new RelationNode("REPLACED");

    /**
     * Kinds carrying a {@link RelNode} component that is deliberately <em>not</em> a child.
     *
     * <p>{@code EmptyRelationNode} (∅) holds the sub-tree it replaced as an inert heading:
     * {@code children()} is empty and {@code mapChildren} returns {@code this}, so a
     * rewrite cannot silently change the empty relation's heading.  {@code relix-ast}
     * cannot see {@code Schema}, which is why the heading has to be a {@code RelNode} at
     * all; keeping it out of {@code children()} is what makes that safe.
     */
    private static final Set<String> INERT_REL_COMPONENTS = Set.of("EmptyRelationNode");

    private static Stream<DynamicTest> forEachKind(String label,
                                                   java.util.function.Consumer<RelNode> check) {
        return RelNodeCorpus.everyKind().stream()
                .map(node -> DynamicTest.dynamicTest(
                        node.getClass().getSimpleName() + " — " + label,
                        () -> check.accept(node)));
    }

    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("children()")
    final class Children {

        @TestFactory
        @DisplayName("reports exactly the RelNode components, in declaration order")
        Stream<DynamicTest> childrenAreTheRelNodeComponents() {
            return forEachKind("children == RelNode components", node -> {
                List<RelNode> expected = new ArrayList<>();
                for (RecordComponent rc : node.getClass().getRecordComponents()) {
                    if (RelNode.class.isAssignableFrom(rc.getType())) {
                        expected.add((RelNode) read(rc, node));
                    }
                }
                if (INERT_REL_COMPONENTS.contains(node.getClass().getSimpleName())) {
                    assertThat(node.children())
                            .as("%s holds an inert RelNode component, so it exposes no child",
                                    node.getClass().getSimpleName())
                            .isEmpty();
                    return;
                }
                assertThat(node.children())
                        .as("%s.children() must expose every RelNode component — a child "
                            + "missing here is invisible to every structural traversal",
                                node.getClass().getSimpleName())
                        .containsExactlyElementsOf(expected);
            });
        }
    }

    @Nested
    @DisplayName("mapChildren()")
    final class MapChildren {

        @TestFactory
        @DisplayName("returns this when no child changed")
        Stream<DynamicTest> identityReturnsThis() {
            // The == no-op signal every rewrite driver reads to detect "nothing changed".
            // A node that rebuilds itself here makes every pass report a change forever,
            // which the bounded fixpoint driver would then iterate against until its cap.
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
                RelNode mapped = node.mapChildren(child -> REPLACEMENT);

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
            // A binary node whose right input is rewritten must keep its left one. The
            // per-child == check is what makes that work, and getting it wrong on one arm
            // (comparing against the wrong field, say) is invisible when every child is
            // replaced at once.
            //
            // Both positions are exercised, because a binary arm's guard is
            // `l == n.left() && r == n.right()` — replacing only the first short-circuits
            // before the second comparison is ever evaluated, so half of every binary
            // guard stays untested if the test only ever rewrites the left side.
            return RelNodeCorpus.everyKind().stream()
                    .filter(node -> node.children().size() >= 2)
                    .flatMap(node -> Stream.of(0, node.children().size() - 1)
                            .distinct()
                            .map(position -> DynamicTest.dynamicTest(
                                    node.getClass().getSimpleName()
                                    + " — rewriting child " + position + " alone",
                                    () -> assertOnlyOneChildReplaced(node, position))));
        }

        private void assertOnlyOneChildReplaced(RelNode node, int position) {
            List<RelNode> children = node.children();
            RelNode target = children.get(position);
            RelNode mapped = node.mapChildren(child -> child == target ? REPLACEMENT : child);

            assertThat(mapped)
                    .as("%s changed child %d, so it must not return itself",
                            node.getClass().getSimpleName(), position)
                    .isNotSameAs(node);

            List<RelNode> expected = new ArrayList<>(children);
            expected.set(position, REPLACEMENT);
            assertThat(mapped.children())
                    .as("%s must rewrite only child %d and leave the rest alone",
                            node.getClass().getSimpleName(), position)
                    .containsExactlyElementsOf(expected);
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Rebuilds {@code node} through its canonical constructor with every child replaced by
     * {@code replacement} — the independent expectation the implementation is checked
     * against.
     *
     * <p>Children are identified positionally against {@link RelNode#children()} rather
     * than by type, so a {@code RelNode} component that is not a child (∅'s heading) is
     * passed through untouched without the rebuild needing to know about it.
     */
    private static RelNode canonicalRebuild(RelNode node, RelNode replacement) {
        RecordComponent[] components = node.getClass().getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] args = new Object[components.length];
        List<RelNode> children = node.children();
        int childIndex = 0;

        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            Object value = read(components[i], node);
            if (value instanceof RelNode child
                    && childIndex < children.size()
                    && children.get(childIndex) == child) {
                args[i] = replacement;
                childIndex++;
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
            return (RelNode) canonical.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                    "cannot rebuild " + node.getClass().getSimpleName()
                    + " through its canonical constructor", e);
        }
    }

    private static Object read(RecordComponent component, RelNode node) {
        try {
            component.getAccessor().setAccessible(true);
            return component.getAccessor().invoke(node);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot read " + component.getName(), e);
        }
    }
}

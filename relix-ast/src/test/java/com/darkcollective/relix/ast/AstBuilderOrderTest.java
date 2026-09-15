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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every factory puts its relation inputs where {@link AstBuilders}' rule says they go.
 *
 * <p>This exists because the rule was stated twice in this repository and both statements
 * were wrong, in opposite directions. {@code AstBuilders} said a factory reproduces its
 * record's own order, "smoothed over" for nothing — true of {@code closure} and
 * {@code path}, false of {@code sessionize} and {@code tree}, which move the input to the
 * end. {@code CLAUDE.md} said the input always goes last and named the three records that
 * declare it first — of which one ({@code UnnestNode}) declares it fourth. Neither
 * description was checkable, so both drifted, and a caller could not derive the order of
 * a factory they had not already read.
 *
 * <p>The rule is now decided by the <em>operator</em> rather than by the record, since the
 * records are not consistent about it and publishing that inconsistency is what made the
 * surface unlearnable:
 *
 * <ol>
 *   <li><b>Unary — the input goes last.</b> {@code select(pred, input)},
 *       {@code closure(from, to, input)}. The call then reads as the operator is written:
 *       σ<sub>pred</sub>(R), {@code CLOSURE src, dst (Edges)}.</li>
 *   <li><b>Join or set operation — the inputs lead.</b> {@code join(left, right, cond)},
 *       {@code union(a, b)}, matching the infix notation. A {@code *JoinNode} is in this
 *       class however many relations it takes, which is what keeps {@code lateral} here:
 *       its right input is a table-function call rather than a relation.</li>
 *   <li><b>A binder precedes what it scopes</b> — {@link #BINDERS}.</li>
 * </ol>
 *
 * <p>It reads the compiled methods rather than the source, so a factory added with the
 * arguments in the wrong order fails here rather than being noticed in review.
 */
@DisplayName("AstBuilders — the relation input sits where the operator puts it")
final class AstBuilderOrderTest {

    /**
     * {@code fixpoint(name, base, step)} — the one node whose name scopes an input, so the
     * name leads the two relations it binds them for. Listed rather than derived: there is
     * exactly one, and a derivation from a hierarchy of one is a guess about the second.
     */
    private static final Set<String> BINDERS = Set.of("fixpoint");

    @Test
    @DisplayName("a unary operator's relation input is its last parameter")
    void unaryInputGoesLast() {
        List<String> offenders = new ArrayList<>();
        for (Method factory : relationFactories()) {
            Class<?>[] params = factory.getParameterTypes();
            List<Integer> inputs = inputPositions(params);
            if (BINDERS.contains(factory.getName()) || isJoin(factory) || inputs.size() != 1) {
                continue;
            }
            if (inputs.getFirst() != params.length - 1) {
                offenders.add(signature(factory) + "  — the input is at position "
                        + inputs.getFirst() + " of " + params.length + ", not last");
            }
        }
        assertThat(offenders)
                .as("""
                        A unary operator's factory takes its arguments first and its relation \
                        last, so the call reads as the operator does — select(pred, input), \
                        closure(from, to, input). See the rule on AstBuilders.""")
                .isEmpty();
    }

    @Test
    @DisplayName("a join or set operation's relation inputs are its leading parameters")
    void joinInputsLead() {
        List<String> offenders = new ArrayList<>();
        for (Method factory : relationFactories()) {
            Class<?>[] params = factory.getParameterTypes();
            List<Integer> inputs = inputPositions(params);
            if (BINDERS.contains(factory.getName())) {
                continue;
            }
            boolean leads = !inputs.isEmpty() && inputs.equals(range(inputs.size()));
            if ((isJoin(factory) || inputs.size() > 1) && !leads) {
                offenders.add(signature(factory) + "  — the inputs are at " + inputs
                        + ", not leading");
            }
        }
        assertThat(offenders)
                .as("""
                        A join or set operation's factory takes its relations first and any \
                        modifier after — join(left, right, cond), union(a, b) — so the call \
                        reads as the infix operator does. See the rule on AstBuilders.""")
                .isEmpty();
    }

    @Test
    @DisplayName("the binder carve-out names only factories that still need it")
    void binderCarveOutIsMinimal() {
        List<String> stale = new ArrayList<>();
        for (String name : BINDERS) {
            List<Method> factories = relationFactories().stream()
                    .filter(m -> m.getName().equals(name)).toList();
            if (factories.isEmpty()) {
                stale.add(name + " — no such factory");
                continue;
            }
            boolean needsIt = factories.stream().anyMatch(factory -> {
                List<Integer> inputs = inputPositions(factory.getParameterTypes());
                boolean leads = !inputs.isEmpty() && inputs.equals(range(inputs.size()));
                boolean trails = inputs.size() == 1
                        && inputs.getFirst() == factory.getParameterCount() - 1;
                return !leads && !trails;
            });
            if (!needsIt) {
                stale.add(name + " — now obeys the general rule");
            }
        }
        assertThat(stale)
                .as("""
                        A carve-out that no longer excuses anything is reported rather than                         left standing, the rule tools/coverage/register.tsv follows: an                         exemption list nobody prunes is where a third convention hides.""")
                .isEmpty();
    }

    @Test
    @DisplayName("the guard sees both classes of factory, so neither clause is vacuous")
    void bothClausesAreExercised() {
        List<Method> factories = relationFactories();
        assertThat(factories).as("factories building a RelNode").hasSizeGreaterThan(50);

        List<Method> withRelations = factories.stream()
                .filter(m -> !inputPositions(m.getParameterTypes()).isEmpty())
                .filter(m -> !BINDERS.contains(m.getName()))
                .toList();
        assertThat(withRelations.stream().filter(m -> !isJoin(m)
                && inputPositions(m.getParameterTypes()).size() == 1).toList())
                .as("unary factories, held to input-last").hasSizeGreaterThan(30);
        assertThat(withRelations.stream().filter(m -> isJoin(m)
                || inputPositions(m.getParameterTypes()).size() > 1).toList())
                .as("join and set-operation factories, held to inputs-first").hasSizeGreaterThan(15);
    }

    // =========================================================================

    /** Every published factory on {@code AstBuilders} that builds a {@link RelNode}. */
    private static List<Method> relationFactories() {
        return Arrays.stream(AstBuilders.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()))
                .filter(m -> RelNode.class.isAssignableFrom(m.getReturnType()))
                .sorted(java.util.Comparator.comparing(Method::getName)
                        .thenComparing(Method::getParameterCount))
                .toList();
    }

    /**
     * A join by the node it builds, not by a list kept here — every join kind in the
     * hierarchy is named {@code …JoinNode}, so the classification cannot go stale when one
     * is added.
     */
    private static boolean isJoin(Method factory) {
        return factory.getReturnType().getSimpleName().endsWith("JoinNode");
    }

    private static List<Integer> inputPositions(Class<?>[] params) {
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < params.length; i++) {
            if (RelNode.class.isAssignableFrom(params[i])) {
                positions.add(i);
            }
        }
        return positions;
    }

    private static List<Integer> range(int n) {
        return java.util.stream.IntStream.range(0, n).boxed().toList();
    }

    private static String signature(Method factory) {
        return factory.getReturnType().getSimpleName() + " " + factory.getName() + "("
                + String.join(", ", Arrays.stream(factory.getParameterTypes())
                .map(Class::getSimpleName).toList()) + ")";
    }
}

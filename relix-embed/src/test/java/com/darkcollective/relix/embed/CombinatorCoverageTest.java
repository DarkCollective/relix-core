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
package com.darkcollective.relix.embed;

import com.darkcollective.relix.ast.AllenRelation;
import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.ConsolidationFunction;
import com.darkcollective.relix.ast.EmptyRelationNode;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.ObjectiveSense;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.OptimizeConstraint;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.RecursiveRefNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationFunctionCall;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SortSpecification;
import com.darkcollective.relix.ast.TieBreak;
import com.darkcollective.relix.ast.TruthRelationNode;
import com.darkcollective.relix.ast.WindowFrame;
import com.darkcollective.relix.ast.WindowFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds {@link Relation}'s combinators to the {@link RelNode} hierarchy: every operator
 * has one, and every exclusion is justified.
 *
 * <p>It proves it by <strong>invoking</strong> each combinator with synthesized arguments
 * and collecting the node kinds that come back, rather than matching method names to
 * kinds. A name-based check would pass for a method that builds the wrong node, and this
 * is the surface where a wrong node is a wrong answer.
 *
 * <p>The hierarchy is walked reflectively, so a new {@code permits} entry fails the build
 * until it has a combinator or a recorded reason not to.
 */
@DisplayName("Relation covers every operator, and says why where it does not")
final class CombinatorCoverageTest {

    /**
     * The kinds that are deliberately not combinators, each with the reason.
     *
     * <p>Four of the five are how a relation <em>starts</em> rather than something done to
     * one, so they belong on the session. The fifth has no surface syntax at all, which is
     * exactly why it must not acquire one here.
     */
    private static final Map<Class<? extends RelNode>, String> NOT_COMBINATORS =
            new LinkedHashMap<>(Map.of(
                    RelationNode.class,
                    "a named relation is how an expression starts — Relix.relation(name)",
                    RelationFunctionCall.class,
                    "a table-function call starts an expression; as a combinator it is lateral(…)",
                    TruthRelationNode.class,
                    "the nullary UNIT/EMPTY literals take no input relation",
                    RecursiveRefNode.class,
                    "bound inside fix(…) and meaningless outside its binder",
                    EmptyRelationNode.class,
                    "optimizer-only: ∅ has no surface syntax, which is why it must not gain one here"));

    /**
     * Methods that return a {@link Relation} without being combinators.
     *
     * <p>{@code optimized()} is a terminal: it hands back the same relation rewritten, so
     * it produces whatever kind it was given and would otherwise be counted as covering
     * it. {@code asWritten()} is the same shape from the other side — it settles how the
     * relation is executed and touches the tree not at all. The distinction is real rather
     * than bookkeeping — a combinator adds an operator, neither of these adds one.
     */
    private static final Set<String> TERMINALS = Set.of("optimized", "asWritten");

    private static Relation base() {
        Relix relix = Relix.open();
        relix.define("""
                source Orders from csv("./orders.csv") {
                    header: true,
                    schema: { id: NUMBER, parent_id: NUMBER, status: STRING, amount: NUMBER, ts: TIMESTAMP }
                };
                """);
        return relix.relation("Orders");
    }

    /** A sample argument for each parameter type the combinators take. */
    private static Object sample(Parameter parameter, Relation self) {
        Class<?> type = parameter.getType();
        if (type == Relation.class) {
            return self;
        }
        if (type == Predicate.class) {
            return AstBuilders.cmp(AstBuilders.attr("status"),
                    com.darkcollective.relix.ast.ComparisonOperator.EQUAL, AstBuilders.str("OPEN"));
        }
        if (type == Operand.class) {
            return AstBuilders.attr("amount");
        }
        if (type == Operand[].class) {
            return new Operand[]{AstBuilders.attr("amount")};
        }
        if (type == String.class) {
            return "id";
        }
        if (type == String[].class) {
            return new String[]{"id"};
        }
        if (type == SortSpecification.class) {
            return AstBuilders.asc("id");
        }
        if (type == SortSpecification[].class) {
            return new SortSpecification[]{AstBuilders.asc("id")};
        }
        if (type == long.class) {
            return 10L;
        }
        if (type == int.class) {
            return 2;
        }
        if (type == double.class) {
            return 0.5d;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == AllenRelation.class) {
            return AllenRelation.OVERLAPS;
        }
        if (type == ObjectiveSense.class) {
            return ObjectiveSense.MAXIMIZE;
        }
        if (type == ConsolidationFunction.class) {
            return ConsolidationFunction.values()[0];
        }
        if (type == TieBreak.class) {
            return TieBreak.values()[0];
        }
        if (type == WindowFunction.class) {
            return new WindowFunction.AggregateWindow(
                    com.darkcollective.relix.ast.AggregateOperator.SUM, AstBuilders.attr("amount"));
        }
        if (type == WindowFrame.class) {
            return new WindowFrame.PartitionFrame();
        }
        if (type == Optional.class) {
            return Optional.empty();
        }
        if (type == List.class) {
            return sampleList(parameter);
        }
        throw new IllegalStateException("no sample for parameter type " + type
                + " — add one, or the combinator it belongs to goes unchecked");
    }

    /** Lists are typed by their element, which the generic signature still carries. */
    private static Object sampleList(Parameter parameter) {
        String generic = parameter.getParameterizedType().getTypeName();
        if (generic.contains(ProjectedAttribute.class.getName())) {
            return AstBuilders.attrs("id");
        }
        if (generic.contains(SortSpecification.class.getName())) {
            return List.of(AstBuilders.asc("id"));
        }
        if (generic.contains(GroupingKey.class.getName())) {
            return List.of(AstBuilders.key("status"));
        }
        if (generic.contains(OptimizeConstraint.class.getName())) {
            return List.of(AstBuilders.constraint(AstBuilders.attr("amount"),
                    com.darkcollective.relix.ast.ComparisonOperator.LESS_EQUAL, 100d));
        }
        if (generic.contains("AggregateFunction")) {
            return List.of(AstBuilders.agg(
                    com.darkcollective.relix.ast.AggregateOperator.SUM, "amount"));
        }
        return List.of("status");   // List<String>
    }

    /** Every node kind any combinator produces, found by calling them all. */
    private static Set<Class<?>> kindsProduced() {
        Relation self = base();
        Set<Class<?>> produced = new LinkedHashSet<>();
        List<String> failures = new ArrayList<>();

        for (Method method : Relation.class.getDeclaredMethods()) {
            if (method.getReturnType() != Relation.class
                    || !java.lang.reflect.Modifier.isPublic(method.getModifiers())
                    || TERMINALS.contains(method.getName())) {
                continue;
            }
            Object[] args = new Object[method.getParameterCount()];
            for (int i = 0; i < args.length; i++) {
                args[i] = sample(method.getParameters()[i], self);
            }
            try {
                produced.add(((Relation) method.invoke(self, args)).node().getClass());
            } catch (ReflectiveOperationException | RuntimeException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                failures.add(method.getName() + ": " + cause);
            }
        }

        assertThat(failures)
                .as("every combinator must build a node from ordinary arguments")
                .isEmpty();
        return produced;
    }

    @Test
    @DisplayName("every operator with an input relation has a combinator")
    void everyOperatorHasACombinator() {
        Set<Class<?>> produced = kindsProduced();
        List<String> missing = concreteKinds(RelNode.class).stream()
                .filter(kind -> !produced.contains(kind))
                .filter(kind -> !NOT_COMBINATORS.containsKey(kind))
                .map(Class::getSimpleName)
                .sorted()
                .toList();

        assertThat(missing)
                .as("RelNode kinds with no Relation combinator — add one, or record why not")
                .isEmpty();
    }

    @Test
    @DisplayName("every exclusion still names a kind that exists")
    void exclusionsDoNotOutliveTheirKinds() {
        List<String> kinds = concreteKinds(RelNode.class).stream()
                .map(Class::getSimpleName)
                .toList();

        // A justification for a kind the hierarchy no longer has is a reason outliving
        // what it justified — the failure the coverage register reports rather than
        // tolerating.
        assertThat(NOT_COMBINATORS.keySet().stream().map(Class::getSimpleName).toList())
                .allSatisfy(kind -> assertThat(kinds).contains(kind));
    }

    @Test
    @DisplayName("the combinators and the exclusions together account for the whole hierarchy")
    void everyKindIsAccountedFor() {
        Set<Class<?>> produced = kindsProduced();
        List<Class<?>> all = concreteKinds(RelNode.class);

        assertThat(produced.size() + NOT_COMBINATORS.size())
                .as("produced %d kinds + %d excluded should be the hierarchy's %d",
                        produced.size(), NOT_COMBINATORS.size(), all.size())
                .isEqualTo(all.size());
    }

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
}

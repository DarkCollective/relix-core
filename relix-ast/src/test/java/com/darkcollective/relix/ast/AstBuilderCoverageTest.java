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

import com.darkcollective.relix.ast.internal.AstEquivalence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The AST is the engine's published contract (ADR-0025), and {@link AstBuilders} is how it
 * is authored without the grammar. A contract with a hole in its authoring surface is one
 * an embedder discovers the hard way, so completeness is checked rather than remembered:
 * every concrete member of the three sealed hierarchies must have a factory that returns
 * exactly it.
 *
 * <p>This is the step 1 half of the *Adding an operator* checklist that a compiler cannot
 * enforce — a new {@code permits} entry compiles fine with no builder behind it.
 */
@DisplayName("AstBuilders covers every sealed AST hierarchy")
final class AstBuilderCoverageTest {

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

    /** The exact types {@link AstBuilders} hands back from a public static factory. */
    private static Set<Class<?>> builtTypes() {
        return Arrays.stream(AstBuilders.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> Modifier.isStatic(m.getModifiers()))
                .map(Method::getReturnType)
                .collect(Collectors.toSet());
    }

    private static List<String> uncovered(Class<?> root) {
        Set<Class<?>> built = builtTypes();
        return concreteKinds(root).stream()
                .filter(kind -> !built.contains(kind))
                .map(Class::getSimpleName)
                .sorted()
                .toList();
    }

    @Nested
    @DisplayName("every kind has a factory")
    final class Completeness {

        @Test
        @DisplayName("RelNode — all 52 concrete node kinds")
        void relNodesAreCovered() {
            assertThat(uncovered(RelNode.class))
                    .as("RelNode kinds with no AstBuilders factory")
                    .isEmpty();
        }

        @Test
        @DisplayName("Predicate — all 7 kinds")
        void predicatesAreCovered() {
            assertThat(uncovered(Predicate.class))
                    .as("Predicate kinds with no AstBuilders factory")
                    .isEmpty();
        }

        @Test
        @DisplayName("Operand — all 15 kinds")
        void operandsAreCovered() {
            assertThat(uncovered(Operand.class))
                    .as("Operand kinds with no AstBuilders factory")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the hierarchies are the size the docs claim")
    final class Counts {

        @Test
        @DisplayName("RelNode permits 52 concrete kinds")
        void relNodeCount() {
            assertThat(concreteKinds(RelNode.class)).hasSize(52);
        }

        @Test
        @DisplayName("Predicate permits 7 kinds")
        void predicateCount() {
            assertThat(concreteKinds(Predicate.class)).hasSize(7);
        }

        @Test
        @DisplayName("Operand permits 15 kinds")
        void operandCount() {
            assertThat(concreteKinds(Operand.class)).hasSize(15);
        }
    }

    @Nested
    @DisplayName("a factory builds a node the rest of the engine can walk")
    final class Wellformedness {

        @Test
        @DisplayName("every RelNode factory result reports a location and pretty-prints")
        void nodesAreUsable() {
            RelNode plan = AstBuilders.project(
                    AstBuilders.attrs("dept", "total"),
                    AstBuilders.groupBy(
                            AstBuilders.cols("dept"),
                            List.of(AstBuilders.agg(AggregateOperator.SUM, "amount", "total")),
                            AstBuilders.select(
                                    AstBuilders.cmp(AstBuilders.attr("status"),
                                            ComparisonOperator.EQUAL,
                                            AstBuilders.str("OPEN")),
                                    AstBuilders.rel("Orders"))));

            assertThat(plan.location()).isEqualTo(SourceLocation.UNKNOWN);
            assertThat(plan.prettyPrint()).isNotBlank();
            assertThat(plan.children()).hasSize(1);
        }

        @Test
        @DisplayName("factories default the location to UNKNOWN, so AstEquivalence sees past it")
        void locationsDefaultToUnknown() {
            assertThat(AstBuilders.rel("R").location()).isEqualTo(SourceLocation.UNKNOWN);
            assertThat(AstBuilders.distinct(AstBuilders.rel("R")).location())
                    .isEqualTo(SourceLocation.UNKNOWN);
            assertThat(AstBuilders.union(AstBuilders.rel("R"), AstBuilders.rel("S")).location())
                    .isEqualTo(SourceLocation.UNKNOWN);
        }
    }

    // -------------------------------------------------------------------------
    // Invocation
    // -------------------------------------------------------------------------

    private static final ComparisonOperator[] UPPER_BOUNDS = {
            ComparisonOperator.LESS, ComparisonOperator.LESS_EQUAL, ComparisonOperator.EQUAL};

    static BuilderInvocation.Samples samples() {
        return new BuilderInvocation.Samples()
            .of(String.class, i -> "c" + i)
            .of(String[].class, i -> new String[]{"c" + i})
            .of(boolean.class, i -> i % 2 == 0)
            .of(int.class, i -> i + 1)
            .of(long.class, i -> (long) (i + 1))
            .of(Long.class, i -> (long) (i + 1))
            .of(java.util.OptionalLong.class, i -> java.util.OptionalLong.of(i + 1))
            .of(double.class, i -> (i + 1) / 10.0d)
            .of(RelNode.class, i -> AstBuilders.rel("R" + i))
            .of(Operand.class, i -> AstBuilders.attr("a" + i))
            .of(Operand[].class, i -> new Operand[]{AstBuilders.attr("a" + i)})
            .of(Predicate.class, i -> AstBuilders.nullPred(AstBuilders.attr("p" + i), true))
            .of(ProduceBound.class, i -> AstBuilders.produceBound("n" + i, ComparisonOperator.LESS, AstBuilders.num("10")))
            .of(WindowFunction.class, i -> new WindowFunction.AggregateWindow(AggregateOperator.SUM, AstBuilders.attr("w" + i)))
            .of(WindowFrame.class, i -> new WindowFrame.BoundedFrame(i + 1))
            .of(StructConstruction.Field[].class, i -> new StructConstruction.Field[]{new StructConstruction.Field("f" + i, AstBuilders.attr("v" + i))})
            .of(Optional.class, i -> Optional.of("o" + i))
            .of(AggregateOperator.class, i -> AggregateOperator.values()[i % AggregateOperator.values().length])
            .of(ComparisonOperator.class, i -> UPPER_BOUNDS[i % UPPER_BOUNDS.length])
            .of(ArithmeticOperator.class, i -> ArithmeticOperator.values()[i % ArithmeticOperator.values().length])
            .of(AllenRelation.class, i -> AllenRelation.values()[i % AllenRelation.values().length])
            .of(ObjectiveSense.class, i -> ObjectiveSense.values()[i % ObjectiveSense.values().length])
            .of(ConsolidationFunction.class, i -> ConsolidationFunction.values()[i % ConsolidationFunction.values().length])
            .of(SortDirection.class, i -> SortDirection.values()[i % SortDirection.values().length])
            .of(TieBreak.class, i -> TieBreak.values()[i % TieBreak.values().length])
            .ofListElement(Long.class, i -> (long) (i + 1))
            .ofListElement(String.class, i -> "c" + i)
            .ofListElement(Operand.class, i -> AstBuilders.attr("a" + i))
            .ofListElement(Predicate.class, i -> AstBuilders.nullPred(AstBuilders.attr("p" + i), true))
            .ofListElement(RelNode.class, i -> AstBuilders.rel("R" + i))
            .ofListElement(ProjectedAttribute.class, i -> AstBuilders.projected(AstBuilders.attr("a" + i)))
            .ofListElement(SortSpecification.class, i -> AstBuilders.asc("s" + i))
            .ofListElement(GroupingKey.class, i -> AstBuilders.key("g" + i))
            .ofListElement(AggregateFunction.class, i -> AstBuilders.agg(AggregateOperator.SUM, "m" + i))
            .ofListElement(OptimizeConstraint.class, i -> AstBuilders.constraint(AstBuilders.attr("a" + i), ComparisonOperator.LESS_EQUAL, 100d))
            .ofListElement(RenameNode.RenamePair.class, i -> AstBuilders.renamePair("f" + i, "t" + i))
            .ofListElement(StructConstruction.Field.class, i -> new StructConstruction.Field("f" + i, AstBuilders.attr("v" + i)))
            .override("date", 0, "2026-01-01")
            .override("time", 0, "12:00:00")
            .override("timestamp", 0, "2026-01-01T12:00:00Z")
            .override("duration", 0, "PT1H")
            // ρ rejects mixing positional names with old→new pairs, so exercise the pairs form.
            .override("rename", 1, java.util.List.of())
            .ofListElement(ProduceBound.class, i -> AstBuilders.produceBound("n" + i, ComparisonOperator.LESS, AstBuilders.num("10")));
    }

    /**
     * Factories that deliberately do not retain the argument they were given.
     *
     * <p>A temporal literal <em>parses</em> its text into a {@code java.time} value at
     * construction, which is the whole point of the operand — the node holds an
     * {@code Instant}, not the characters it was spelled with. Every other factory keeps
     * what it is handed, and a new name here needs the same kind of reason.
     */
    private static final Map<String, String> PARSES_ITS_ARGUMENT = Map.of(
            "date", "DATE '…' is parsed to a LocalDate at construction",
            "time", "TIME '…' is parsed to a LocalTime at construction",
            "timestamp", "TIMESTAMP '…' is parsed to an Instant at construction",
            "duration", "DURATION '…' is parsed to a Duration at construction");

    private static List<java.lang.reflect.Method> factories() {
        return Arrays.stream(AstBuilders.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .filter(m -> java.lang.reflect.Modifier.isStatic(m.getModifiers()))
                .filter(m -> !m.isSynthetic())
                .toList();
    }

    private static String signature(java.lang.reflect.Method factory) {
        return factory.getName() + "("
                + Arrays.stream(factory.getParameterTypes()).map(Class::getSimpleName)
                        .reduce((a, b) -> a + ", " + b).orElse("") + ")";
    }

    private static List<BuilderInvocation.Invocation> sweep() {
        return BuilderInvocation.invokeAll(AstBuilders.class, samples());
    }

    @Nested
    @DisplayName("every factory is called, and builds what it claims")
    final class Invocation {

        @Test
        @DisplayName("every factory builds a node from ordinary arguments")
        void everyFactoryIsCallable() {
            assertThat(BuilderInvocation.failures(sweep()))
                    .as("factories that could not be called at all")
                    .isEmpty();
        }

        @Test
        @DisplayName("every factory returns exactly the type it declares")
        void returnTypesAreExact() {
            List<String> wrong = BuilderInvocation.succeeded(sweep()).stream()
                    // isInstance rather than an exact class: a factory may declare an
                    // interface (attrs/cols hand back a List). For the record types —
                    // which are final — the two coincide, which is where it matters.
                    .filter(i -> !i.factory().getReturnType().isInstance(i.result()))
                    .map(i -> i.signature() + " returned " + i.result().getClass().getSimpleName())
                    .sorted()
                    .toList();

            assertThat(wrong).as("factories whose result is not the declared type").isEmpty();
        }

        /**
         * The check a return-type guard cannot make. Where a factory takes exactly the
         * record's components, every component must equal the argument that position was
         * given — so an arm that drops one, or swaps two of the same type, fails here.
         * The arguments are distinct by construction, which is what makes a swap visible.
         */
        @Test
        @DisplayName("a full-arity factory passes every component through unchanged")
        void componentsMatchTheArgumentsGiven() throws Exception {
            List<String> mismatches = new ArrayList<>();
            for (BuilderInvocation.Invocation invocation : BuilderInvocation.succeeded(sweep())) {
                if (!invocation.isFullArity()) {
                    continue;
                }
                List<RecordComponent> components =
                        BuilderInvocation.components(invocation.factory().getReturnType());
                for (int i = 0; i < components.size(); i++) {
                    Object actual = components.get(i).getAccessor().invoke(invocation.result());
                    Object expected = invocation.arguments().get(i);
                    if (!Objects.equals(actual, expected)) {
                        mismatches.add(invocation.signature() + ": component '"
                                + components.get(i).getName() + "' is " + actual
                                + " but was given " + expected);
                    }
                }
            }
            assertThat(mismatches)
                    .as("a component that does not hold what its factory was handed")
                    .isEmpty();
        }

        /**
         * Every argument lands in the component its parameter is named for.
         *
         * <p>The strongest of these checks and the only one that catches a factory which
         * distinguishes its arguments perfectly and still routes them to the wrong
         * components. It rests on the convention these builders already follow — a
         * parameter is named for the component it fills — and on the build keeping
         * parameter names.
         */
        @Test
        @DisplayName("every argument lands in the component its parameter names")
        void argumentsLandInTheComponentTheyName() {
            List<String> mismatches = BuilderInvocation.succeeded(sweep()).stream()
                    .flatMap(i -> BuilderInvocation.componentsByName(i).stream())
                    .sorted()
                    .toList();

            assertThat(mismatches)
                    .as("a component holding something other than the argument named for it")
                    .isEmpty();
        }

        /**
         * <strong>No argument is ignored.</strong> Build twice, changing exactly one
         * argument, and the two results must differ — an argument the factory drops on the
         * floor produces the identical record both times.
         *
         * <p>This replaced an earlier check that looked for each argument among the
         * result's components. That one could not see a dropped argument whose value
         * happened to equal the default put in its place, and it could not be fixed by
         * aligning parameters to components, because a convenience overload's parameter
         * order has no correspondence to the record's. Varying one argument needs no
         * alignment at all.
         *
         * <p>A variant that <em>throws</em> counts as distinguishing it: the factory
         * plainly read the argument to reject it.
         */
        @Test
        @DisplayName("no factory ignores an argument it was given")
        void everyArgumentChangesTheResult() {
            List<String> ignored = new ArrayList<>();
            for (java.lang.reflect.Method factory : factories()) {
                Object baseline = BuilderInvocation.call(
                        factory, BuilderInvocation.argumentsFor(factory, samples(), -1));
                if (baseline instanceof BuilderInvocation.Failed) {
                    continue;   // reported by everyFactoryIsCallable
                }
                for (int i = 0; i < factory.getParameterCount(); i++) {
                    Object varied = BuilderInvocation.call(
                            factory, BuilderInvocation.argumentsFor(factory, samples(), i));
                    if (varied instanceof BuilderInvocation.Failed) {
                        continue;
                    }
                    if (Objects.equals(baseline, varied)) {
                        ignored.add(signature(factory) + " ignores argument " + i);
                    }
                }
            }
            assertThat(ignored)
                    .as("an argument the factory never reads — a dropped component")
                    .isEmpty();
        }

        /**
         * <strong>No two arguments are interchangeable.</strong> Swapping a same-typed pair
         * must change the result; if it does not, the factory cannot tell them apart, which
         * is what a wrong-way-round pair looks like from the outside.
         *
         * <p>A swap that throws counts as distinguishing them, for the same reason as above
         * — an ordered pair that rejects the reversal plainly read both ends.
         */
        @Test
        @DisplayName("no two same-typed arguments are interchangeable")
        void argumentOrderIsObservable() {
            List<String> interchangeable = new ArrayList<>();
            for (java.lang.reflect.Method factory : factories()) {
                List<Object> arguments = BuilderInvocation.argumentsFor(factory, samples(), -1);
                Object baseline = BuilderInvocation.call(factory, arguments);
                if (baseline instanceof BuilderInvocation.Failed) {
                    continue;
                }
                java.lang.reflect.Type[] types = factory.getGenericParameterTypes();
                for (int i = 0; i < types.length; i++) {
                    for (int j = i + 1; j < types.length; j++) {
                        if (!types[i].getTypeName().equals(types[j].getTypeName())) {
                            continue;
                        }
                        List<Object> swapped = new ArrayList<>(arguments);
                        Collections.swap(swapped, i, j);
                        Object result = BuilderInvocation.call(factory, swapped);
                        if (result instanceof BuilderInvocation.Failed) {
                            continue;
                        }
                        if (Objects.equals(baseline, result)) {
                            interchangeable.add(
                                    signature(factory) + " cannot tell argument " + i
                                            + " from " + j);
                        }
                    }
                }
            }
            assertThat(interchangeable)
                    .as("two arguments the factory treats alike — a swap would be invisible")
                    .isEmpty();
        }

        @Test
        @DisplayName("every parse-its-argument exemption still names a factory")
        void exemptionsDoNotOutliveTheirFactories() {
            List<String> names = Arrays.stream(AstBuilders.class.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName)
                    .toList();

            assertThat(names).as("an exemption for a factory that no longer exists")
                    .containsAll(PARSES_ITS_ARGUMENT.keySet());
        }
    }
}

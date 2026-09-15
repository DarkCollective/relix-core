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
package com.darkcollective.relix.lang.ast;

import com.darkcollective.relix.ast.BuilderInvocation;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.lang.ast.source.ColumnSpec;
import com.darkcollective.relix.lang.ast.source.HttpMethod;
import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.StructType;
import com.darkcollective.relix.symbol.Type;
import java.util.Map;
import java.util.Objects;
import com.darkcollective.relix.lang.ast.table.InlineTable;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.lang.ast.source.SourceConfig;
import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.assign;
import static com.darkcollective.relix.lang.ast.ScriptBuilders.attr;
import static com.darkcollective.relix.lang.ast.ScriptBuilders.cmp;
import static com.darkcollective.relix.lang.ast.ScriptBuilders.column;
import static com.darkcollective.relix.lang.ast.ScriptBuilders.csvSource;
import static com.darkcollective.relix.lang.ast.ScriptBuilders.query;
import static com.darkcollective.relix.lang.ast.ScriptBuilders.rel;
import static com.darkcollective.relix.lang.ast.ScriptBuilders.script;
import static com.darkcollective.relix.lang.ast.ScriptBuilders.select;
import static com.darkcollective.relix.lang.ast.ScriptBuilders.source;
import static com.darkcollective.relix.lang.ast.ScriptBuilders.str;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The statement-level counterpart of {@code AstBuilderCoverageTest}: a {@link Script} is
 * what {@code SemanticAnalyzer.analyze} takes, so the surface that builds one must stay
 * complete as the language grows.
 */
@DisplayName("ScriptBuilders covers every lang-AST hierarchy")
final class ScriptBuilderCoverageTest {

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

    private static Set<Class<?>> builtTypes() {
        return Arrays.stream(ScriptBuilders.class.getDeclaredMethods())
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
        @DisplayName("Statement — all 9 kinds")
        void statementsAreCovered() {
            assertThat(uncovered(Statement.class))
                    .as("Statement kinds with no ScriptBuilders factory")
                    .isEmpty();
            assertThat(concreteKinds(Statement.class)).hasSize(9);
        }

        @Test
        @DisplayName("SourceConfig — all 6 kinds")
        void sourceConfigsAreCovered() {
            assertThat(uncovered(SourceConfig.class))
                    .as("SourceConfig kinds with no ScriptBuilders factory")
                    .isEmpty();
            assertThat(concreteKinds(SourceConfig.class)).hasSize(6);
        }

        @Test
        @DisplayName("InlineTable — both kinds")
        void inlineTablesAreCovered() {
            assertThat(uncovered(com.darkcollective.relix.lang.ast.table.InlineTable.class))
                    .as("InlineTable kinds with no ScriptBuilders factory")
                    .isEmpty();
        }

        @Test
        @DisplayName("QueryTarget and AssignmentBody are reachable through query()/assign()")
        void bodiesAreReachable() {
            assertThat(query("Open").target()).isInstanceOf(NamedQueryTarget.class);
            assertThat(query(rel("Orders")).target()).isInstanceOf(ExpressionQueryTarget.class);
            assertThat(assign("V", rel("Orders")).body()).isInstanceOf(QueryAssignmentBody.class);
            assertThat(assign("T", ScriptBuilders.markdownTable(List.of("a"), List.of(List.of("1"))))
                    .body()).isInstanceOf(InlineTableBody.class);
        }
    }

    @Nested
    @DisplayName("a whole script assembles without the grammar")
    final class EndToEnd {

        @Test
        @DisplayName("source + view + query, all from factories")
        void buildsAScript() {
            Script built = script(
                    source("Orders", csvSource("orders.csv",
                            column("id", ScalarType.NUMBER),
                            column("status", ScalarType.STRING))),
                    assign("Open", select(cmp(attr("status"), ComparisonOperator.EQUAL, str("OPEN")),
                            rel("Orders"))),
                    query("Open"));

            assertThat(built.namespace()).isEmpty();
            assertThat(built.statements()).hasSize(3);
            assertThat(built.statements().get(0)).isInstanceOf(SourceDeclaration.class);
            assertThat(built.statements().get(2)).isInstanceOf(QueryStatement.class);
        }

        @Test
        @DisplayName("a namespaced script carries its namespace")
        void carriesNamespace() {
            assertThat(script("analytics", query("Open")).namespace()).contains("analytics");
        }
    }

    // -------------------------------------------------------------------------
    // Invocation
    // -------------------------------------------------------------------------

    /**
     * The statement-level half of the same vocabulary {@code AstBuilderCoverageTest}
     * supplies for the AST. Every value is derived from the parameter's position, so no
     * two arguments to one factory are equal — which is what makes a swapped pair
     * visible.
     */
    private static BuilderInvocation.Samples samples() {
        return new BuilderInvocation.Samples()
                .of(String.class, i -> "n" + i)
                .of(boolean.class, i -> i % 2 == 0)
                .of(long.class, i -> (long) (i + 1))
                .of(java.util.OptionalLong.class, i -> java.util.OptionalLong.of(i + 1))
                .of(RelNode.class, i -> ScriptBuilders.rel("R" + i))
                .of(Operand.class, i -> ScriptBuilders.attr("a" + i))
                .of(ScalarType.class, i -> ScalarType.values()[i % ScalarType.values().length])
                .of(Type.class, i -> ScalarType.values()[i % ScalarType.values().length])
                .of(HttpMethod.class, i -> HttpMethod.values()[i % HttpMethod.values().length])
                .of(ImportKind.class, i -> ImportKind.values()[i % ImportKind.values().length])
                .of(SourceConfig.class, i -> ScriptBuilders.jsonSource("f" + i + ".json"))
                .of(com.darkcollective.relix.lang.ast.source.DatabaseConnectionConfig.class,
                        i -> new com.darkcollective.relix.lang.ast.source.DatabaseConnectionConfig(
                                "jdbc:h2:mem:db" + i, java.util.Optional.empty(),
                                java.util.Optional.empty(), java.util.Optional.empty()))
                .of(InlineTable.class, i -> ScriptBuilders.csvTable(
                        List.of("h" + i), List.of(List.of("v" + i))))
                .of(EndpointSpec.class, i -> ScriptBuilders.endpoint("Rel" + i, List.of("k" + i)))
                .of(ColumnSpec[].class, i -> new ColumnSpec[]{
                        ScriptBuilders.column("c" + i, ScalarType.STRING)})
                .of(StructType.Field[].class, i -> new StructType.Field[]{
                        ScriptBuilders.field("f" + i, ScalarType.STRING)})
                .of(Statement[].class, i -> new Statement[]{ScriptBuilders.query("Q" + i)})
                .of(Map.class, i -> Map.of("k" + i, "v" + i))
                .ofListElement(String.class, i -> "n" + i)
                .ofListElement(ColumnSpec.class, i -> ScriptBuilders.column("c" + i, ScalarType.STRING))
                .ofListElement(com.darkcollective.relix.lang.ast.source.ColumnReference.class,
                        i -> ScriptBuilders.reference("c" + i, "T" + i, "k" + i))
                .ofListElement(Statement.class, i -> ScriptBuilders.query("Q" + i))
                .ofListElement(ParameterDefinition.class, i -> ScriptBuilders.param("p" + i, ScalarType.STRING))
                .ofListElement(StructType.Field.class, i -> ScriptBuilders.field("f" + i, ScalarType.STRING))
                // An inline table's rows are a List<List<String>>; the element sample is
                // matched on the generic name, so the inner list is what is registered.
                .ofListElement(List.class, i -> List.of("v" + i));
    }

    private static List<BuilderInvocation.Invocation> sweep() {
        return BuilderInvocation.invokeAll(ScriptBuilders.class, samples());
    }

    private static List<Method> factories() {
        return Arrays.stream(ScriptBuilders.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> Modifier.isStatic(m.getModifiers()))
                .filter(m -> !m.isSynthetic())
                .toList();
    }

    private static String signature(Method factory) {
        return factory.getName() + "("
                + Arrays.stream(factory.getParameterTypes()).map(Class::getSimpleName)
                        .reduce((a, b) -> a + ", " + b).orElse("") + ")";
    }

    @Nested
    @DisplayName("every factory is called, and builds what it claims")
    final class Invocation {

        @Test
        @DisplayName("every factory builds a statement from ordinary arguments")
        void everyFactoryIsCallable() {
            assertThat(BuilderInvocation.failures(sweep()))
                    .as("factories that could not be called at all")
                    .isEmpty();
        }

        @Test
        @DisplayName("every factory returns exactly the type it declares")
        void returnTypesAreExact() {
            List<String> wrong = BuilderInvocation.succeeded(sweep()).stream()
                    .filter(i -> !i.factory().getReturnType().isInstance(i.result()))
                    .map(i -> i.signature() + " returned " + i.result().getClass().getSimpleName())
                    .sorted()
                    .toList();

            assertThat(wrong).as("factories whose result is not the declared type").isEmpty();
        }

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

        @Test
        @DisplayName("no factory ignores an argument it was given")
        void everyArgumentChangesTheResult() {
            List<String> ignored = new ArrayList<>();
            for (Method factory : factories()) {
                Object baseline = BuilderInvocation.call(
                        factory, BuilderInvocation.argumentsFor(factory, samples(), -1));
                if (baseline instanceof BuilderInvocation.Failed) {
                    continue;
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
    }
}

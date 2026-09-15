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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.GenericArrayType;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The core types the facade's public signatures name are themselves an API surface.
 *
 * <p>A facade cannot be more stable than the types it exposes. {@code Relation.schema()}
 * returns core's {@code Schema}; rows are core's {@code Row}, values core's
 * {@code Value}. The alternative — the facade defining its own — would be two
 * {@code Schema} types and a permanent tax on every caller, so the re-export is
 * deliberate. Its consequence is that <strong>core's promise now reaches through the
 * facade</strong>: a caller who writes against {@code Relation} is writing against those
 * core types whether they named them or not.
 *
 * <p>So the set is enumerated here rather than left to accumulate. Adding a package is a
 * decision — it widens what the facade's compatibility promise covers — and this test is
 * where that decision is made visible, in the shape {@code EngineBoundaryTest} and
 * {@code CoreModuleListTest} already use for the module graph.
 *
 * <p>It is deliberately about <em>packages</em>, not types. A per-type list would fail on
 * every new operator and teach people to append without thinking; a package is the unit a
 * reviewer can reason about, because it is the unit the module graph is drawn in.
 *
 * <p>And it follows one step past the signature, because the promise does. A caller who
 * writes {@code relation.plan().estimates()} has reached a package the facade never named
 * — it named the type that hands it over. Stopping at the signature would have described
 * a smaller surface than the one a caller actually depends on, which is the failure mode
 * an allowlist exists to prevent rather than to have.
 */
@DisplayName("the facade re-exports only the core packages it is allowed to")
final class ReExportSurfaceTest {

    /**
     * Every package the facade's public signatures may name.
     *
     * <p>Each is here because a facade type cannot be used without it: an expression is a
     * {@code RelNode}, a heading is a {@code Schema}, a row is a {@code Row} of
     * {@code Value}s, a run reports {@code QueryEvent}s, a rewrite is a
     * {@code TransformationRecord}, a plan carries {@code PlanEstimates}, provenance is
     * annotated over a {@code Semiring}, and the two seams a host fills — a function
     * library and the provisioners — are named where they are supplied.
     */
    private static final Set<String> ALLOWED = Set.of(
            "com.darkcollective.relix.embed",
            "com.darkcollective.relix.ast",
            // Reached one step out: an expression is walked with a visitor, and a
            // symbol table answers with the symbol kinds.
            "com.darkcollective.relix.ast.visitor",
            "com.darkcollective.relix.symbol.relation",
            "com.darkcollective.relix.lang.ast",
            "com.darkcollective.relix.symbol",
            "com.darkcollective.relix.symbol.graph",
            "com.darkcollective.relix.symbol.table",
            "com.darkcollective.relix.value",
            "com.darkcollective.relix.events",
            "com.darkcollective.relix.function",
            "com.darkcollective.relix.optimizer",
            "com.darkcollective.relix.plan",
            "com.darkcollective.relix.processor",
            "com.darkcollective.relix.processor.exec",
            "com.darkcollective.relix.processor.provenance",
            "com.darkcollective.relix.processor.connector",
            "com.darkcollective.relix.provenance",
            "com.darkcollective.relix.semantic",
            "com.darkcollective.relix.connectors.std",
            // The JDK's own, which no promise of ours covers.
            "java.lang", "java.util", "java.util.function", "java.util.stream",
            "java.time", "java.math", "java.nio.file", "java.io", "javax.sql");

    /** The facade's own public types — everything a caller can reach. */
    private static final Class<?>[] FACADE = {
            Relix.class, Relix.Builder.class, Relation.class,
            Rows.class, Tuple.class, Diagnostic.class, RelixException.class};

    @Test
    @DisplayName("nothing a caller can reach falls outside the allowlist")
    void publicSignaturesStayInsideTheAllowlist() {
        Set<String> unexpected = new LinkedHashSet<>(reachable());
        unexpected.removeAll(ALLOWED);
        assertThat(unexpected)
                .as("""
                        the facade's public surface reaches a package the re-export \
                        allowlist does not cover. Adding one is a decision, not a \
                        formality: it widens what the facade's compatibility promise \
                        covers, because a caller writing against Relation writes against \
                        these types whether they named them or not""")
                .isEmpty();
    }

    /**
     * The allowlist is a claim about what the facade needs, so an entry nothing reaches
     * is a claim that has gone stale — the same failure a coverage-register row has when
     * the gap it justified is gone.
     */
    @Test
    @DisplayName("every allowed relix package is actually reached")
    void theAllowlistCarriesNothingStale() {
        Set<String> unusedRelix = new TreeSet<>(ALLOWED);
        unusedRelix.removeIf(pkg -> !pkg.startsWith("com.darkcollective.relix"));
        unusedRelix.removeAll(reachable());

        assertThat(unusedRelix)
                .as("allowed but unreached — the entry outlived the signature that needed it")
                .isEmpty();
    }

    /**
     * Every package a caller can arrive at: the packages the facade's own signatures
     * name, plus those the relix types in them expose in turn.
     *
     * <p>One step, not a closure. One step is what a caller takes without naming a type
     * themselves — {@code plan()} then {@code estimates()} — and a full closure would
     * drag in every package the engine's internals touch, which is a different question
     * with a much larger answer.
     */
    private static Set<String> reachable() {
        Set<Class<?>> named = new LinkedHashSet<>();
        for (Class<?> type : FACADE) {
            signatureTypes(type, named);
        }
        Set<String> packages = new TreeSet<>();
        named.forEach(t -> collect(t, packages));
        named.stream()
                .filter(t -> t.getPackageName().startsWith("com.darkcollective.relix"))
                .filter(t -> !t.getPackageName().equals("com.darkcollective.relix.embed"))
                .forEach(t -> {
                    Set<Class<?>> second = new LinkedHashSet<>();
                    signatureTypes(t, second);
                    second.forEach(inner -> collect(inner, packages));
                });
        return packages;
    }

    /** The types {@code owner}'s public members mention. */
    private static void signatureTypes(Class<?> owner, Set<Class<?>> into) {
        Set<String> packages = new TreeSet<>();
        for (Method method : owner.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            classes(method.getGenericReturnType(), into, packages);
            Arrays.stream(method.getGenericParameterTypes())
                    .forEach(t -> classes(t, into, packages));
            Arrays.stream(method.getGenericExceptionTypes())
                    .forEach(t -> classes(t, into, packages));
        }
        for (Constructor<?> constructor : owner.getDeclaredConstructors()) {
            if (Modifier.isPublic(constructor.getModifiers())) {
                Arrays.stream(constructor.getGenericParameterTypes())
                        .forEach(t -> classes(t, into, packages));
            }
        }
    }

    /** The raw classes a generic type mentions. */
    private static void classes(Type type, Set<Class<?>> into, Set<String> ignored) {
        switch (type) {
            case Class<?> c -> {
                Class<?> element = c;
                while (element.isArray()) {
                    element = element.getComponentType();
                }
                if (!element.isPrimitive()) {
                    into.add(element);
                }
            }
            case ParameterizedType p -> {
                classes(p.getRawType(), into, ignored);
                Arrays.stream(p.getActualTypeArguments()).forEach(t -> classes(t, into, ignored));
            }
            case GenericArrayType a -> classes(a.getGenericComponentType(), into, ignored);
            case WildcardType w -> {
                Arrays.stream(w.getUpperBounds()).forEach(t -> classes(t, into, ignored));
                Arrays.stream(w.getLowerBounds()).forEach(t -> classes(t, into, ignored));
            }
            case TypeVariable<?> v ->
                    Arrays.stream(v.getBounds()).forEach(t -> classes(t, into, ignored));
            default -> { }
        }
    }

    /** Every package a generic type mentions, following type arguments and arrays. */
    private static void collect(Type type, Set<String> into) {
        switch (type) {
            case Class<?> c -> {
                Class<?> element = c;
                while (element.isArray()) {
                    element = element.getComponentType();
                }
                if (!element.isPrimitive() && element.getPackage() != null) {
                    into.add(element.getPackage().getName());
                }
            }
            case ParameterizedType p -> {
                collect(p.getRawType(), into);
                Arrays.stream(p.getActualTypeArguments()).forEach(t -> collect(t, into));
            }
            case GenericArrayType a -> collect(a.getGenericComponentType(), into);
            case WildcardType w -> {
                Arrays.stream(w.getUpperBounds()).forEach(t -> collect(t, into));
                Arrays.stream(w.getLowerBounds()).forEach(t -> collect(t, into));
            }
            case TypeVariable<?> v -> Arrays.stream(v.getBounds()).forEach(t -> collect(t, into));
            default -> { }
        }
    }
}

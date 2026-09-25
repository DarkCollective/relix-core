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

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The packages the published artifact exports are exactly the packages a caller can
 * reach from the facade.
 *
 * <p>A facade cannot be more stable than the types it exposes. {@code Relation.schema()}
 * returns core's {@code Schema}; rows are core's {@code Row}, values core's
 * {@code Value}. The alternative — the facade defining its own — would be two
 * {@code Schema} types and a permanent tax on every caller, so the re-export is
 * deliberate. Its consequence is that <strong>core's promise now reaches through the
 * facade</strong>: a caller who writes against {@code Relation} is writing against those
 * core types whether they named them or not.
 *
 * <p>The list is the {@code exports} of {@code relix-dist}'s descriptor, read from its
 * source. That is the one statement of the published surface: a second list here, which
 * this test used to keep, was a copy the descriptor could disagree with, and nothing
 * compared the two.
 *
 * <p>It is deliberately about <em>packages</em>, not types. A per-type list would fail on
 * every new operator and teach people to append without thinking; a package is the unit a
 * reviewer can reason about, because it is the unit the module graph is drawn in.
 *
 * <p><b>The walk is a closure, not a fixed number of steps.</b> It used to stop one step
 * past the facade's signatures, and that is a guess about how far a caller goes which a
 * real caller disproved: {@code Relix.define(Statement...)} takes a {@code Statement},
 * a {@code SourceDeclaration} is one, and its {@code config()} returns a
 * {@code SourceConfig} — three steps out, in a package the artifact did not export, so an
 * embedder building a source declaration was handed a type they could not name. Every
 * step a caller can take without naming an internal type is one this walk takes: public
 * members, supertypes, public nested types, and the permitted subtypes of a sealed type,
 * since a caller receiving one pattern-matches over them. The JDK's packages are reached
 * and not followed; no promise of ours covers them.
 *
 * <p><b>It starts from every exported public type, not only the facade.</b> Exporting a
 * package publishes all of it, so {@code PhysicalPlanJson} is API although no facade
 * method returns it. Starting from the facade alone missed its public
 * {@code write(JsonWriter, …)}, which put {@code relix-json} on the published surface.
 */
@DisplayName("the artifact exports exactly the packages a caller can reach")
final class ReExportSurfaceTest {

    private static final String RELIX = "com.darkcollective.relix";

    /** The published artifact's descriptor, relative to this module. */
    private static final Path DESCRIPTOR = Path.of("../relix-dist/src/main/java/module-info.java");

    private static final Pattern EXPORTS = Pattern.compile("^\\s*exports\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    /**
     * Public types in exported packages that no facade signature leads to, published
     * anyway because a caller needs them: the authoring surface that builds a tree
     * without text, the failure {@code Relix.parse} throws, the semirings a caller names
     * to call {@code provenance}, the HTTPS fetcher a front end wraps to report download
     * progress, and the run-time failure a connector throws.
     *
     * <p>Everything else in an exported package must be reachable from the facade, and
     * machinery lives in an {@code .internal} package the jar does not export. A name
     * here that the facade does reach, or that no longer exists, fails as stale.
     */
    private static final Set<String> PUBLISHED_BY_DECISION = Set.of(
            "com.darkcollective.relix.ast.AstBuilders",
            "com.darkcollective.relix.ast.Expr",
            "com.darkcollective.relix.lang.ast.ScriptBuilders",
            "com.darkcollective.relix.lang.ast.ScriptParseException",
            "com.darkcollective.relix.lang.ast.ScriptPrinter",
            "com.darkcollective.relix.connectors.std.HttpFetcher",
            // The engine's run-time failure: what a connector throws when its source cannot
            // be read, and what ConnectorConfig.require throws for a missing key.
            "com.darkcollective.relix.processor.EvaluationException",
            "com.darkcollective.relix.provenance.BooleanSemiring",
            "com.darkcollective.relix.provenance.CountingSemiring",
            "com.darkcollective.relix.provenance.TropicalSemiring",
            "com.darkcollective.relix.provenance.PolynomialSemiring",
            "com.darkcollective.relix.provenance.Polynomial",
            "com.darkcollective.relix.provenance.Monomial",
            "com.darkcollective.relix.provenance.ProvenanceVariable",
            "com.darkcollective.relix.provenance.SourceRef",
            "com.darkcollective.relix.provenance.SecurityLattice",
            "com.darkcollective.relix.provenance.SecurityLevel",
            "com.darkcollective.relix.provenance.PathCostSemiring",
            "com.darkcollective.relix.provenance.PathCost",
            "com.darkcollective.relix.provenance.Route",
            "com.darkcollective.relix.provenance.NamedSemiring",
            "com.darkcollective.relix.provenance.SemiringLibrary",
            "com.darkcollective.relix.provenance.SemiringCatalog",
            "com.darkcollective.relix.provenance.Semirings");

    /** The facade's own public types, where every walk starts. */
    private static final Class<?>[] FACADE = {
            Relix.class, Relix.Builder.class, Relation.class, Sandbox.class, Sandbox.Builder.class,
            Rows.class, Tuple.class, Diagnostic.class, RelixException.class,
            QueryExecutionException.class, UnboundedRelationException.class,
            SandboxViolationException.class};

    @Test
    @DisplayName("nothing a caller can reach is in a package the artifact does not export")
    void everythingReachableIsExported() throws IOException {
        Map<String, String> unexported = new TreeMap<>(reachable());
        unexported.keySet().removeAll(exported());
        unexported.keySet().removeIf(ReExportSurfaceTest::isJdk);
        assertThat(unexported)
                .as("""
                        a published type leads to a public type in a package relix-dist does \
                        not export, so a caller is handed a type they cannot name (each \
                        package maps to the path that reaches it). Export it, or stop the \
                        API leading there: exporting widens what the compatibility promise \
                        covers, and that is the decision this test makes visible""")
                .isEmpty();
    }

    /**
     * An export nothing reaches is a claim that has gone stale — the same failure a
     * coverage-register row has when the gap it justified is gone.
     */
    @Test
    @DisplayName("every exported package is reached")
    void nothingIsExportedThatNothingReaches() throws IOException {
        Set<String> stale = new TreeSet<>(exported());
        stale.removeAll(reachable().keySet());
        assertThat(stale)
                .as("exported but unreached — the export outlived the signature that needed it")
                .isEmpty();
    }

    /**
     * Exporting a package publishes all of it, so machinery sitting beside the values a
     * caller uses is published with them. Every public type in an exported package must
     * be one the facade leads to, or one published by decision.
     */
    @Test
    @DisplayName("every exported type is one the facade leads to, or published by decision")
    void everyExportedTypeIsApi() {
        Set<String> fromFacade = new TreeSet<>();
        walk(new LinkedHashSet<>(Arrays.asList(FACADE))).keySet()
                .forEach(type -> fromFacade.add(type.getName()));
        Set<String> exportedTypes = new TreeSet<>();
        seeds().forEach(type -> exportedTypes.add(type.getName()));

        Set<String> machinery = new TreeSet<>(exportedTypes);
        machinery.removeAll(fromFacade);
        machinery.removeAll(PUBLISHED_BY_DECISION);
        assertThat(machinery)
                .as("""
                        public types in an exported package that no facade signature leads \
                        to. Move machinery to the package's .internal sibling; add a type \
                        a caller genuinely needs to PUBLISHED_BY_DECISION""")
                .isEmpty();

        Set<String> stale = new TreeSet<>(PUBLISHED_BY_DECISION);
        stale.removeIf(name -> exportedTypes.contains(name) && !fromFacade.contains(name));
        assertThat(stale)
                .as("listed as published by decision, but the facade reaches it or it is gone")
                .isEmpty();
    }

    @Test
    @DisplayName("the walk reaches a type three steps out, which one step did not")
    void theWalkIsAClosure() {
        assertThat(reachable())
                .containsKey("com.darkcollective.relix.lang.ast.source");
    }

    /** The packages {@code relix-dist}'s descriptor exports. */
    private static Set<String> exported() throws IOException {
        Matcher m = EXPORTS.matcher(Files.readString(DESCRIPTOR));
        Set<String> packages = new TreeSet<>();
        while (m.find()) {
            packages.add(m.group(1));
        }
        assertThat(packages).as("exports read from " + DESCRIPTOR).isNotEmpty();
        return packages;
    }

    private static boolean isJdk(String pkg) {
        return pkg.startsWith("java.") || pkg.startsWith("javax.");
    }

    /**
     * Every package a caller can arrive at from the facade, each mapped to the first path
     * that reached it — the path is what a failure has to show, since the package alone
     * does not say which signature leads there.
     */
    private static Map<String, String> reachable() {
        Map<String, String> packages = new TreeMap<>();
        walk(seeds()).forEach((type, path) -> packages.putIfAbsent(type.getPackageName(), path));
        return packages;
    }

    /**
     * Every type reachable from {@code seeds}, each mapped to the first path that reached
     * it. Relix public types are followed; anything else is recorded and not followed.
     */
    private static Map<Class<?>, String> walk(Set<Class<?>> seeds) {
        Map<Class<?>, String> seen = new java.util.LinkedHashMap<>();
        Deque<Class<?>> queue = new ArrayDeque<>();
        for (Class<?> type : seeds) {
            seen.put(type, type.getSimpleName());
            queue.add(type);
        }
        while (!queue.isEmpty()) {
            Class<?> owner = queue.remove();
            String path = seen.get(owner);
            Set<Class<?>> next = new LinkedHashSet<>();
            surface(owner, next);
            for (Class<?> type : next) {
                if (type.getPackage() == null || seen.containsKey(type)) {
                    continue;
                }
                seen.put(type, path + " → " + type.getSimpleName());
                if (type.getPackageName().startsWith(RELIX) && Modifier.isPublic(type.getModifiers())) {
                    queue.add(type);
                }
            }
        }
        return seen;
    }

    /**
     * Where a caller can start: the facade, and every public type in every exported
     * package. Exporting a package publishes all of it, so a public class the facade
     * never mentions is as much a starting point as {@code Relix} is.
     */
    private static Set<Class<?>> seeds() {
        Set<Class<?>> seeds = new LinkedHashSet<>(Arrays.asList(FACADE));
        try {
            for (String pkg : exported()) {
                for (String name : classNames(pkg)) {
                    Class<?> type = Class.forName(name, false, ReExportSurfaceTest.class.getClassLoader());
                    if (Modifier.isPublic(type.getModifiers())) {
                        seeds.add(type);
                    }
                }
            }
        } catch (IOException | ClassNotFoundException | java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        return seeds;
    }

    /** The top-level classes of {@code pkg} on the class path, from directories or jars. */
    private static Set<String> classNames(String pkg)
            throws IOException, java.net.URISyntaxException {
        String dir = pkg.replace('.', '/');
        Set<String> names = new TreeSet<>();
        var urls = ReExportSurfaceTest.class.getClassLoader().getResources(dir);
        while (urls.hasMoreElements()) {
            java.net.URL url = urls.nextElement();
            // A module's test fixtures share its packages on this class path, and are
            // not in the jar.
            if (url.toString().contains("testFixtures") || url.toString().contains("test-fixtures")) {
                continue;
            }
            if ("file".equals(url.getProtocol())) {
                try (var files = Files.list(Path.of(url.toURI()))) {
                    files.map(f -> f.getFileName().toString()).forEach(f -> addClass(pkg, f, names));
                }
            } else if ("jar".equals(url.getProtocol())) {
                String jar = url.getPath().substring("file:".length(), url.getPath().indexOf('!'));
                try (var zip = new java.util.zip.ZipFile(java.net.URLDecoder.decode(jar, java.nio.charset.StandardCharsets.UTF_8))) {
                    zip.stream().map(java.util.zip.ZipEntry::getName)
                            .filter(n -> n.startsWith(dir + "/") && n.indexOf('/', dir.length() + 1) < 0)
                            .forEach(n -> addClass(pkg, n.substring(dir.length() + 1), names));
                }
            }
        }
        return names;
    }

    private static void addClass(String pkg, String file, Set<String> names) {
        if (file.endsWith(".class") && !file.contains("$") && !file.contains("-info")) {
            names.add(pkg + "." + file.substring(0, file.length() - ".class".length()));
        }
    }

    /** The types a caller meets through {@code owner} without naming anything else. */
    private static void surface(Class<?> owner, Set<Class<?>> into) {
        classes(owner.getGenericSuperclass(), into);
        Arrays.stream(owner.getGenericInterfaces()).forEach(t -> classes(t, into));
        Arrays.stream(owner.getTypeParameters()).forEach(t -> classes(t, into));
        for (Method method : owner.getDeclaredMethods()) {
            if (visible(method.getModifiers()) && !method.isSynthetic() && !method.isBridge()) {
                signature(method, into);
                classes(method.getGenericReturnType(), into);
            }
        }
        for (Constructor<?> constructor : owner.getDeclaredConstructors()) {
            if (visible(constructor.getModifiers()) && !constructor.isSynthetic()) {
                signature(constructor, into);
            }
        }
        for (Field field : owner.getDeclaredFields()) {
            if (visible(field.getModifiers()) && !field.isSynthetic()) {
                classes(field.getGenericType(), into);
            }
        }
        for (Class<?> nested : owner.getDeclaredClasses()) {
            if (visible(nested.getModifiers())) {
                into.add(nested);
            }
        }
        if (owner.isSealed()) {
            into.addAll(Arrays.asList(owner.getPermittedSubclasses()));
        }
    }

    /** A protected member is reachable by extending the type, which a caller may do. */
    private static boolean visible(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static void signature(Executable executable, Set<Class<?>> into) {
        Arrays.stream(executable.getTypeParameters()).forEach(t -> classes(t, into));
        Arrays.stream(executable.getGenericParameterTypes()).forEach(t -> classes(t, into));
        Arrays.stream(executable.getGenericExceptionTypes()).forEach(t -> classes(t, into));
    }

    /** The raw classes a generic type mentions, following type arguments and arrays. */
    private static void classes(Type type, Set<Class<?>> into) {
        switch (type) {
            case null -> { }
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
                classes(p.getRawType(), into);
                Arrays.stream(p.getActualTypeArguments()).forEach(t -> classes(t, into));
            }
            case GenericArrayType a -> classes(a.getGenericComponentType(), into);
            case WildcardType w -> {
                Arrays.stream(w.getUpperBounds()).forEach(t -> classes(t, into));
                Arrays.stream(w.getLowerBounds()).forEach(t -> classes(t, into));
            }
            case TypeVariable<?> v -> Arrays.stream(v.getBounds()).forEach(t -> classes(t, into));
            default -> { }
        }
    }
}

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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;

/**
 * Calls every factory on an authoring surface and reports what came back.
 *
 * <p>It exists because the builder guards used to check <em>return types</em>: they read
 * each factory's signature, collected the types, and asserted every sealed kind had one
 * pointing at it. That passes for a factory that is never called and, worse, for one that
 * builds its record wrongly — a dropped component or two arguments the wrong way round
 * are invisible to a signature. It is the same weakness {@code CombinatorCoverageTest}
 * was written to avoid on the facade, and the same failure {@code AstLocationsTest} found
 * in seven hand-written rebuild arms, each silently dropping a component.
 *
 * <p>So this invokes. Every argument is <strong>distinct</strong>, derived from the
 * parameter's position, which is what makes a swap detectable: two same-typed arguments
 * the wrong way round produce a record whose components do not match what went in.
 *
 * <p>Each surface supplies samples for the types it takes, because a builder module knows
 * its own vocabulary and this class cannot. An unregistered type is an error rather than a
 * skipped factory — a factory quietly excused is exactly what the old guard did.
 */
public final class BuilderInvocation {

    private BuilderInvocation() {
    }

    /** One factory, the arguments it was called with, and what it returned. */
    public record Invocation(Method factory, List<Object> arguments, Object result) {

        /** A readable name for a failure message, overloads included. */
        public String signature() {
            return factory.getName() + "("
                    + Arrays.stream(factory.getParameterTypes())
                            .map(Class::getSimpleName)
                            .reduce((a, b) -> a + ", " + b).orElse("")
                    + ")";
        }

        /**
         * Whether the factory takes exactly the record's own components, less its
         * {@code SourceLocation} — the shape the authoring surface documents, and the one
         * where a parameter and a component correspond one for one.
         */
        public boolean isFullArity() {
            Class<?> type = factory.getReturnType();
            if (!type.isRecord()) {
                return false;
            }
            List<RecordComponent> components = components(type);
            if (arguments.size() != components.size()) {
                return false;
            }
            // Count is not enough: a factory may take as many arguments as there are
            // components and still *convert* them — projected(expr, "a") wraps its alias
            // in an Optional, groupBy(List<String>, …) turns columns into GroupingKeys.
            // Those pass a value through faithfully without passing it through unchanged,
            // so position proves nothing and the weaker survival check is the right one.
            java.lang.reflect.Type[] parameters = factory.getGenericParameterTypes();
            for (int i = 0; i < parameters.length; i++) {
                // Generic names, not erased classes: List<String> and List<GroupingKey>
                // erase to the same thing, and telling them apart is the whole point —
                // one is a pass-through and the other is a conversion.
                if (!components.get(i).getGenericType().getTypeName()
                        .equals(parameters[i].getTypeName())) {
                    return false;
                }
            }
            return true;
        }
    }

    /** The record's components in declaration order, less the location it defaults. */
    public static List<RecordComponent> components(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .filter(c -> c.getType() != SourceLocation.class)
                .toList();
    }

    /**
     * Sample values, by parameter type.
     *
     * <p>Every sample is a function of the parameter's index, so no two arguments to one
     * factory are equal, and each must be <em>valid</em>: records validate in their compact
     * constructors, so a probability outside [0,1] or a blank column name fails for a
     * reason that has nothing to do with what is being tested.
     */
    public static final class Samples {

        private final Map<Class<?>, IntFunction<Object>> byType = new LinkedHashMap<>();
        private final Map<String, IntFunction<Object>> byElement = new LinkedHashMap<>();

        /**
         * Per-argument overrides, keyed {@code factory#index}.
         *
         * <p>For the factories whose record validates its input: a temporal literal must
         * parse, an allocation's bounds must be ordered, a produce bound must be an upper
         * one. A generic sample cannot know that, and the alternative — skipping those
         * factories — is what the old guard did to all of them.
         */
        private final Map<String, IntFunction<Object>> overrides = new LinkedHashMap<>();

        /** A second valid value per type, used to vary one argument at a time. */
        private final Map<Class<?>, IntFunction<Object>> alternates = new LinkedHashMap<>();
        private final Map<String, IntFunction<Object>> alternateOverrides = new LinkedHashMap<>();

        /** Registers the value for one argument of one factory. */
        public Samples override(String factory, int index, Object value) {
            overrides.put(factory + "#" + index, i -> value);
            return this;
        }

        /** Registers the <em>varied</em> value for one argument of one factory. */
        public Samples overrideAlternate(String factory, int index, Object value) {
            alternateOverrides.put(factory + "#" + index, i -> value);
            return this;
        }

        /**
         * Registers a second value for a type whose range is constrained, so varying it
         * stays valid — a probability must remain in [0,1], and an ordered pair must stay
         * ordered when only one end moves.
         */
        public Samples ofAlternate(Class<?> type, IntFunction<Object> sample) {
            alternates.put(type, sample);
            return this;
        }

        /**
         * {@return a valid value for {@code parameter} that differs from its sample}
         *
         * <p>The default shifts the index by an odd number, which yields a different value
         * from any index-derived sample — odd so that a boolean, having only two, actually
         * flips rather than landing back on itself.
         */
        public Object alternate(Method factory, Parameter parameter, int index) {
            IntFunction<Object> override = alternateOverrides.get(factory.getName() + "#" + index);
            if (override != null) {
                return override.apply(index);
            }
            IntFunction<Object> byExactType = alternates.get(parameter.getType());
            if (byExactType != null) {
                return byExactType.apply(index);
            }
            return sample(factory, parameter, index + 1001);
        }

        /** Registers a sample for a parameter type. */
        public Samples of(Class<?> type, IntFunction<Object> sample) {
            byType.put(type, sample);
            return this;
        }

        /** Registers the element sample for a {@code List<E>} parameter. */
        public Samples ofListElement(Class<?> element, IntFunction<Object> sample) {
            byElement.put(element.getName(), sample);
            return this;
        }

        /**
         * The registered sample for a generic parameter's element type.
         *
         * <p>On the element type itself rather than a substring of the whole signature:
         * {@code List<List<String>>} contains the name of {@code String}, and matching that
         * way hands a row-of-rows a bare string. The longest registered prefix wins, so
         * {@code java.util.List<…>} is preferred over {@code java.lang.String} when both
         * could apply.
         */
        private IntFunction<Object> forElement(String genericName) {
            int open = genericName.indexOf('<');
            int close = genericName.lastIndexOf('>');
            if (open < 0 || close < open) {
                return null;
            }
            String element = genericName.substring(open + 1, close);
            return byElement.entrySet().stream()
                    .filter(e -> element.equals(e.getKey()) || element.startsWith(e.getKey() + "<"))
                    .max(java.util.Comparator.comparingInt(e -> e.getKey().length()))
                    .map(Map.Entry::getValue)
                    .orElse(null);
        }

        /** {@return a distinct, valid value for {@code parameter} at {@code index}} */
        public Object sample(Method factory, Parameter parameter, int index) {
            IntFunction<Object> override = overrides.get(factory.getName() + "#" + index);
            if (override != null) {
                return override.apply(index);
            }
            Class<?> type = parameter.getType();
            if (type == Optional.class) {
                IntFunction<Object> element =
                        forElement(parameter.getParameterizedType().getTypeName());
                return Optional.of(element == null ? "o" + index : element.apply(index));
            }
            if (type == List.class) {
                String generic = parameter.getParameterizedType().getTypeName();
                IntFunction<Object> element = forElement(generic);
                if (element == null) {
                    throw new IllegalStateException(
                            "no list-element sample for " + generic + " — register one, or the "
                                    + "factory taking it goes unchecked");
                }
                return List.of(element.apply(index));
            }
            IntFunction<Object> sample = byType.get(type);
            if (sample == null) {
                throw new IllegalStateException(
                        "no sample for parameter type " + type.getName() + " — register one, "
                                + "or the factory taking it goes unchecked");
            }
            return sample.apply(index);
        }
    }

    /**
     * Invokes every public static factory on {@code builders}.
     *
     * @param builders the authoring surface
     * @param samples  values for the types it takes
     * @return one invocation per factory
     * @throws AssertionError never — a factory that throws is reported as a failure by
     *                        {@link #failures}, so one broken factory does not hide the rest
     */
    public static List<Invocation> invokeAll(Class<?> builders, Samples samples) {
        List<Invocation> invocations = new ArrayList<>();
        for (Method factory : builders.getDeclaredMethods()) {
            if (!Modifier.isPublic(factory.getModifiers())
                    || !Modifier.isStatic(factory.getModifiers())
                    || factory.isSynthetic()) {
                continue;
            }
            List<Object> arguments = new ArrayList<>();
            Object result;
            try {
                Parameter[] parameters = factory.getParameters();
                for (int i = 0; i < parameters.length; i++) {
                    arguments.add(samples.sample(factory, parameters[i], i));
                }
                result = factory.invoke(null, arguments.toArray());
            } catch (InvocationTargetException e) {
                result = new Failed(e.getCause());
            } catch (ReflectiveOperationException | RuntimeException e) {
                result = new Failed(e);
            }
            invocations.add(new Invocation(factory, List.copyOf(arguments), result));
        }
        return invocations;
    }

    /** What a factory threw, kept in place of a result so one failure does not stop the sweep. */
    public record Failed(Throwable cause) {
    }

    /** {@return the result of calling {@code factory}, or a {@link Failed}} */
    public static Object call(Method factory, List<Object> arguments) {
        try {
            return factory.invoke(null, arguments.toArray());
        } catch (InvocationTargetException e) {
            return new Failed(e.getCause());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return new Failed(e);
        }
    }

    /**
     * Arguments for {@code factory}, with the one at {@code vary} replaced by its
     * alternate; {@code vary} of {@code -1} gives the plain sample set.
     */
    public static List<Object> argumentsFor(Method factory, Samples samples, int vary) {
        List<Object> arguments = new ArrayList<>();
        Parameter[] parameters = factory.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            arguments.add(i == vary
                    ? samples.alternate(factory, parameters[i], i)
                    : samples.sample(factory, parameters[i], i));
        }
        return arguments;
    }

    /**
     * Checks every argument against the record component of the same name.
     *
     * <p>This is the claim these builders actually make — a factory names its parameters
     * after the components it fills — and it is the only check that catches a
     * <em>consistent</em> swap: {@code closure(input, to, from)} distinguishes its two
     * arguments perfectly and still routes each to the wrong component, which no
     * black-box comparison of results can see. It needs parameter names, which is why the
     * build compiles with {@code -parameters}.
     *
     * <p>A parameter whose name matches no component is skipped rather than failed: a
     * converting overload legitimately takes {@code groupingColumns} and fills
     * {@code groupingKeys}, and the differential checks cover those.
     *
     * @param invocation a successful invocation
     * @return one message per component holding something other than its argument
     */
    public static List<String> componentsByName(Invocation invocation) {
        Class<?> returned = invocation.factory().getReturnType();
        if (!returned.isRecord() || invocation.result() instanceof Failed) {
            return List.of();
        }
        Map<String, RecordComponent> byName = new LinkedHashMap<>();
        for (RecordComponent component : components(returned)) {
            byName.put(component.getName(), component);
        }
        List<String> mismatches = new ArrayList<>();
        Parameter[] parameters = invocation.factory().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (!parameters[i].isNamePresent()) {
                throw new IllegalStateException(
                        "parameter names are absent — the build must compile with -parameters, "
                                + "or this check silently degrades to nothing");
            }
            RecordComponent component = byName.get(parameters[i].getName());
            if (component == null) {
                continue;
            }
            try {
                Object actual = component.getAccessor().invoke(invocation.result());
                Object expected = invocation.arguments().get(i);
                if (!equalsModuloWrapping(actual, expected)) {
                    mismatches.add(invocation.signature() + ": component '"
                            + component.getName() + "' is " + actual + " but the parameter of "
                            + "that name was given " + expected);
                }
            } catch (ReflectiveOperationException e) {
                mismatches.add(invocation.signature() + ": could not read '"
                        + component.getName() + "' — " + e);
            }
        }
        return mismatches;
    }

    /**
     * Whether a component holds exactly {@code argument}, allowing the one wrapping a
     * factory is entitled to apply: an optional component filled from a plain value, or a
     * list component filled from varargs.
     *
     * <p>Deliberately one level and no searching. {@link #contains} looks anywhere, which
     * is right for asking whether a value survived at all and wrong here — a check that
     * hunted for the value would find a swapped argument in the other component and call
     * it a match.
     */
    private static boolean equalsModuloWrapping(Object component, Object argument) {
        if (java.util.Objects.equals(component, argument)) {
            return true;
        }
        if (component instanceof Optional<?> optional) {
            return optional.isPresent() && java.util.Objects.equals(optional.get(), argument);
        }
        if (component instanceof java.util.OptionalLong o) {
            return o.isPresent() && argument instanceof Long l && o.getAsLong() == l;
        }
        if (component instanceof java.util.OptionalInt o) {
            return o.isPresent() && argument instanceof Integer n && o.getAsInt() == n;
        }
        if (component instanceof java.util.OptionalDouble o) {
            return o.isPresent() && argument instanceof Double d && o.getAsDouble() == d;
        }
        if (component instanceof List<?> list && argument != null
                && argument.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(argument);
            if (list.size() != length) {
                return false;
            }
            for (int i = 0; i < length; i++) {
                if (!java.util.Objects.equals(list.get(i),
                        java.lang.reflect.Array.get(argument, i))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** {@return one message per factory that could not be called at all} */
    public static List<String> failures(List<Invocation> invocations) {
        return invocations.stream()
                .filter(i -> i.result() instanceof Failed)
                .map(i -> i.signature() + " → " + ((Failed) i.result()).cause())
                .sorted()
                .toList();
    }

    /** {@return the invocations that produced something} */
    public static List<Invocation> succeeded(List<Invocation> invocations) {
        return invocations.stream().filter(i -> !(i.result() instanceof Failed)).toList();
    }

    /**
     * Whether {@code value} is anywhere in {@code container}, looking inside an
     * {@link Optional} and a collection.
     *
     * <p>A convenience overload passes its arguments to a record that has more components
     * than it has parameters, so position proves nothing — but a value that vanished
     * entirely is a dropped component, which is the failure worth catching.
     */
    public static boolean contains(Object container, Object value) {
        if (java.util.Objects.equals(container, value)) {
            return true;
        }
        if (container instanceof Optional<?> optional) {
            return optional.isPresent() && contains(optional.get(), value);
        }
        if (container instanceof Collection<?> collection) {
            return collection.stream().anyMatch(e -> contains(e, value));
        }
        if (container instanceof java.util.OptionalLong o) {
            return o.isPresent() && contains(o.getAsLong(), value);
        }
        if (container instanceof java.util.OptionalInt o) {
            return o.isPresent() && contains(o.getAsInt(), value);
        }
        if (container instanceof java.util.OptionalDouble o) {
            return o.isPresent() && contains(o.getAsDouble(), value);
        }
        if (container != null && container.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(container);
            for (int i = 0; i < length; i++) {
                if (contains(java.lang.reflect.Array.get(container, i), value)) {
                    return true;
                }
            }
            return false;
        }
        // A collection argument is converted rather than kept whole — groupBy takes
        // List<String> and stores List<GroupingKey> — so what must survive is every
        // element, not the list object.
        if (value instanceof Collection<?> elements) {
            return !elements.isEmpty() && elements.stream().allMatch(e -> contains(container, e));
        }
        // A varargs argument arrives as an array; what matters is that its elements
        // survived, not that the array object did.
        if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                if (!contains(container, java.lang.reflect.Array.get(value, i))) {
                    return false;
                }
            }
            return length > 0;
        }
        // Into records too, because a factory routinely wraps a convenience argument in a
        // node: asc("id") keeps "id" inside an AttributeOperand, and a check that could not
        // see through that would call every such factory a dropped component.
        if (container != null && container.getClass().isRecord()) {
            for (RecordComponent component : container.getClass().getRecordComponents()) {
                try {
                    if (contains(component.getAccessor().invoke(container), value)) {
                        return true;
                    }
                } catch (ReflectiveOperationException e) {
                    return false;
                }
            }
        }
        return false;
    }
}

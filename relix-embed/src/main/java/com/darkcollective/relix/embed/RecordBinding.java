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

import com.darkcollective.relix.symbol.ArrayType;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.StructType;
import com.darkcollective.relix.symbol.Type;
import com.darkcollective.relix.value.Value;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * A record's components bound to a relation's columns — the mapping behind
 * {@link Relation#toList(Class)} and {@link Relation#stream(Class)}.
 *
 * <p>A record component carries the two things a mapper needs, a name and a type, and
 * {@link Tuple}'s accessors already refuse a value of a type they did not name. So the
 * mapping is derivable rather than declared, and it is <em>checked against the heading
 * before any row moves</em>: the heading is known without running anything, which is what
 * turns a mistyped or renamed column from a failure at row 400,000 into one at the call.
 *
 * <p>Names must match. There is no annotation to say otherwise, deliberately: an
 * annotation is a new public type on a surface carrying a compatibility promise, and the
 * language already renames a column — {@code π amount → total (…)} — in the place a
 * reader of the query will look for it.
 */
final class RecordBinding<T> {

    /** How one Java type is read out of a row, and which column types it may be read from. */
    private record Reader(Set<ScalarType> accepts, BiFunction<Tuple, String, Object> read) { }

    /** The four Java shapes a {@code NUMBER} column can be read into. */
    private static final Set<ScalarType> NUMBERS = Set.of(ScalarType.NUMBER);

    /**
     * The Java types a component may have, each paired with the accessor that reads it.
     *
     * <p>One entry per {@link Tuple} accessor, plus the two widths of whole number a
     * record is actually written with. A type absent from here is refused by name rather
     * than guessed at.
     *
     * <p>Declared after {@code NUMBERS}, which building the table reads.
     */
    private static final Map<Class<?>, Reader> READERS = readers();

    private final Class<T> type;
    private final Constructor<T> constructor;
    private final List<Component> components;

    /** One component: where it reads from, how, and whether it can hold a NULL. */
    private record Component(String name, String column, boolean primitive,
                             BiFunction<Tuple, String, Object> read) { }

    private RecordBinding(Class<T> type, Constructor<T> constructor, List<Component> components) {
        this.type = type;
        this.constructor = constructor;
        this.components = components;
    }

    /**
     * Binds {@code type}'s components to {@code schema}'s columns, or refuses.
     *
     * @throws RelixException if the type is not a record, if a component names no column,
     *                        if a column holds a type the component cannot hold, or if the
     *                        record is not reflectively reachable from here
     */
    static <T> RecordBinding<T> of(Class<T> type, Schema schema) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(schema, "schema");
        if (!type.isRecord()) {
            throw new RelixException(type.getName() + " is not a record; the mapping reads a "
                    + "record's components, which is where the column names and types come from");
        }
        RecordComponent[] declared = type.getRecordComponents();
        if (declared.length == 0) {
            throw new RelixException(type.getSimpleName() + " has no components, so there is "
                    + "nothing to read a row into");
        }
        List<Component> components = new ArrayList<>(declared.length);
        for (RecordComponent component : declared) {
            components.add(bind(type, component, schema));
        }
        return new RecordBinding<>(type, canonicalConstructor(type, declared), components);
    }

    /** {@code row} as one instance of the bound record. */
    T bind(Tuple row) {
        Object[] arguments = new Object[components.size()];
        for (int i = 0; i < arguments.length; i++) {
            Component component = components.get(i);
            Object value = component.read().apply(row, component.column());
            if (value == null && component.primitive()) {
                throw new RelixException("column '" + component.column() + "' is NULL, and "
                        + type.getSimpleName() + "." + component.name() + " is a primitive, "
                        + "which cannot hold one — declare it as the boxed type");
            }
            arguments[i] = value;
        }
        try {
            return constructor.newInstance(arguments);
        } catch (InvocationTargetException e) {
            // A compact constructor of the caller's own, validating or normalising. Its
            // failure is theirs and is reported as itself rather than as a mapping fault.
            Throwable cause = e.getCause();
            throw new RelixException(type.getSimpleName() + " rejected a row: "
                    + (cause == null ? e : cause.getMessage()), cause == null ? e : cause);
        } catch (ReflectiveOperationException e) {
            throw new RelixException("could not build a " + type.getSimpleName()
                    + " from a row: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Binding one component
    // -------------------------------------------------------------------------

    private static Component bind(Class<?> type, RecordComponent component, Schema schema) {
        String name = component.getName();
        ColumnDefinition column = schema.column(name).orElseThrow(() -> new RelixException(
                "no column named '" + name + "' for " + type.getSimpleName() + "." + name
                        + "; the relation has " + names(schema)
                        + " — rename it in the query (π old → " + name + ") or rename the component"));
        Reader reader = READERS.get(component.getType());
        if (reader == null) {
            throw new RelixException(type.getSimpleName() + "." + name + " is a "
                    + component.getType().getSimpleName() + ", which no column can be read into; "
                    + "the types that can are " + supported());
        }
        requireCompatible(type, component, column, reader);
        return new Component(name, column.name(), component.getType().isPrimitive(), reader.read());
    }

    /**
     * Refuses a component whose column is known to hold something else.
     *
     * <p>{@code ANY} is the one type that passes everything: a schema-on-read column has
     * no declared type to disagree with, so the check that column falls to is the
     * accessor's own, at the row.
     */
    private static void requireCompatible(Class<?> type, RecordComponent component,
                                          ColumnDefinition column, Reader reader) {
        Class<?> java = component.getType();
        Type held = column.type();
        boolean ok = switch (held) {
            case ScalarType scalar ->
                    scalar == ScalarType.ANY || java == Value.class || reader.accepts().contains(scalar);
            case ArrayType ignored -> java == List.class || java == Value.class;
            case StructType ignored -> java == Map.class || java == Value.class;
        };
        if (!ok) {
            throw new RelixException("column '" + column.name() + "' holds " + held.display()
                    + ", which " + type.getSimpleName() + "." + component.getName() + " ("
                    + java.getSimpleName() + ") cannot hold");
        }
    }

    /**
     * A record's canonical constructor: the one taking its components, in order.
     *
     * <p>Reached reflectively, so a record the calling module has not opened to this one
     * is refused by naming the directive that would fix it rather than by an
     * {@code InaccessibleObjectException} from three frames down.
     */
    private static <T> Constructor<T> canonicalConstructor(Class<T> type, RecordComponent[] components) {
        Class<?>[] parameters = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            parameters[i] = components[i].getType();
        }
        Constructor<T> constructor;
        try {
            constructor = type.getDeclaredConstructor(parameters);
        } catch (NoSuchMethodException e) {
            throw new RelixException(type.getSimpleName()
                    + " has no canonical constructor to build it with", e);
        }
        if (!constructor.canAccess(null)) {
            try {
                constructor.setAccessible(true);
            } catch (RuntimeException e) {
                throw new RelixException(type.getName() + " cannot be constructed from here; "
                        + "its module needs `opens " + type.getPackageName()
                        + " to com.darkcollective.relix.embed;`, or the record can be public "
                        + "in an exported package", e);
            }
        }
        return constructor;
    }

    // -------------------------------------------------------------------------
    // The type table
    // -------------------------------------------------------------------------

    private static Map<Class<?>, Reader> readers() {
        Map<Class<?>, Reader> readers = new LinkedHashMap<>();
        readers.put(String.class, new Reader(Set.of(ScalarType.STRING), Tuple::string));
        readers.put(BigDecimal.class, new Reader(NUMBERS, Tuple::decimal));
        readers.put(Double.class, new Reader(NUMBERS, Tuple::doubleValue));
        readers.put(double.class, new Reader(NUMBERS, Tuple::doubleValue));
        readers.put(Long.class, new Reader(NUMBERS, Tuple::longValue));
        readers.put(long.class, new Reader(NUMBERS, Tuple::longValue));
        readers.put(Integer.class, new Reader(NUMBERS, RecordBinding::intValue));
        readers.put(int.class, new Reader(NUMBERS, RecordBinding::intValue));
        readers.put(Boolean.class, new Reader(Set.of(ScalarType.BOOLEAN), Tuple::booleanValue));
        readers.put(boolean.class, new Reader(Set.of(ScalarType.BOOLEAN), Tuple::booleanValue));
        readers.put(Instant.class, new Reader(Set.of(ScalarType.TIMESTAMP), Tuple::instant));
        readers.put(LocalDate.class, new Reader(Set.of(ScalarType.DATE), Tuple::date));
        readers.put(LocalTime.class, new Reader(Set.of(ScalarType.TIME), Tuple::time));
        readers.put(Duration.class, new Reader(Set.of(ScalarType.DURATION), Tuple::duration));
        readers.put(List.class, new Reader(Set.of(), Tuple::array));
        readers.put(Map.class, new Reader(Set.of(), Tuple::struct));
        // The escape hatch, and the one a varying column wants: the value itself, matched
        // on with a switch over the sealed hierarchy.
        readers.put(Value.class, new Reader(Set.of(), Tuple::get));
        return Map.copyOf(readers);
    }

    /**
     * A whole number narrow enough to be an {@code int}.
     *
     * <p>{@code longValue} already refuses a fractional number, for the reason every
     * accessor refuses a type it did not name; this refuses the other way a number can
     * fail to be an {@code int}, rather than wrapping round to one silently.
     */
    private static Object intValue(Tuple row, String column) {
        Long value = row.longValue(column);
        if (value == null) {
            return null;
        }
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException e) {
            throw new RelixException("column '" + column + "' holds " + value
                    + ", which does not fit in an int — read it with a long component", e);
        }
    }

    private static String names(Schema schema) {
        return schema.columns().stream().map(ColumnDefinition::name).toList().toString();
    }

    private static String supported() {
        return READERS.keySet().stream().map(Class::getSimpleName).distinct().toList().toString();
    }
}

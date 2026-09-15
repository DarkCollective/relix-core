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
package com.darkcollective.relix.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EventMetrics — the numbers an event carries")
final class EventMetricsTest {

    @Nested
    @DisplayName("recording a row count")
    final class Rows {

        @Test
        @DisplayName("rows(n) records n")
        void recordsTheCount() {
            assertThat(EventMetrics.rows(42).rows()).hasValue(42L);
        }

        @Test
        @DisplayName("zero is a measurement, not an absence")
        void zeroIsMeasured() {
            // The #524 distinction: a query that returned nothing measured 0 rows, which
            // is a different claim from an event that counted nothing at all.
            assertThat(EventMetrics.rows(0).rows()).hasValue(0L);
            assertThat(EventMetrics.rows(0).isEmpty()).isFalse();
        }

        @Test
        @DisplayName("a negative count is rejected")
        void rejectsNegative() {
            assertThatThrownBy(() -> EventMetrics.rows(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("-1");
        }
    }

    @Nested
    @DisplayName("recording elapsed time")
    final class Elapsed {

        @Test
        @DisplayName("duration(d) records d")
        void recordsTheElapsedTime() {
            assertThat(EventMetrics.duration(Duration.ofMillis(12)).duration())
                    .hasValue(Duration.ofMillis(12));
        }

        @Test
        @DisplayName("zero is a measurement, not an absence")
        void zeroIsMeasured() {
            // The same distinction the row count draws: a step too fast to register is
            // not a step that was never timed.
            assertThat(EventMetrics.duration(Duration.ZERO).duration()).hasValue(Duration.ZERO);
            assertThat(EventMetrics.duration(Duration.ZERO).isEmpty()).isFalse();
        }

        @Test
        @DisplayName("a negative duration is rejected")
        void rejectsNegative() {
            assertThatThrownBy(() -> EventMetrics.duration(Duration.ofMillis(-1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("PT-0.001S");
        }

        @Test
        @DisplayName("a null duration is rejected rather than read as unmeasured")
        void rejectsNull() {
            // Absence has a spelling already — NONE — so null here is a caller's mistake
            // and not a third state.
            assertThatThrownBy(() -> EventMetrics.duration(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("of(rows, duration) carries both — what a timed count produces")
        void carriesBoth() {
            EventMetrics both = EventMetrics.of(3, Duration.ofMillis(5));

            assertThat(both.rows()).hasValue(3L);
            assertThat(both.duration()).hasValue(Duration.ofMillis(5));
        }
    }

    @Nested
    @DisplayName("NONE")
    final class None {

        @Test
        @DisplayName("measures nothing")
        void measuresNothing() {
            assertThat(EventMetrics.NONE.rows()).isEmpty();
            assertThat(EventMetrics.NONE.duration()).isEmpty();
            assertThat(EventMetrics.NONE.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("is unchanged by deriving from it")
        void isImmutable() {
            EventMetrics derived = EventMetrics.NONE.withRows(7);

            assertThat(derived.rows()).hasValue(7L);
            assertThat(EventMetrics.NONE.rows())
                    .as("withRows must not mutate the shared NONE instance")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("value equality, so two identical measurements are one value")
    void hasValueEquality() {
        assertThat(EventMetrics.rows(3)).isEqualTo(EventMetrics.rows(3));
        assertThat(EventMetrics.rows(3)).hasSameHashCodeAs(EventMetrics.rows(3));
        assertThat(EventMetrics.rows(3)).isNotEqualTo(EventMetrics.rows(4));
        assertThat(EventMetrics.rows(0)).isNotEqualTo(EventMetrics.NONE);
    }

    @Nested
    @DisplayName("extensibility — what must stay true when a measurement is added")
    final class Extensibility {

        @Test
        @DisplayName("there is no public constructor, so a new measurement cannot break a caller")
        void cannotBeConstructedPositionally() {
            // The point of the type: adding a second measurement must add a method rather
            // than change a signature. A record could not promise this — its canonical
            // constructor is public API and cannot be narrowed.
            assertThat(EventMetrics.class.getConstructors())
                    .as("a public constructor would be a signature that a new measurement changes")
                    .isEmpty();
        }

        @TestFactory
        @DisplayName("every measurement has a wither, since that is the only way in")
        Stream<DynamicTest> everyMeasurementHasAWither() {
            return forEachMeasurement(field ->
                    assertThat(witherFor(field))
                            .as("no wither for %s — a measurement with no wither cannot be "
                                + "set at all, because the constructor is private", field.getName())
                            .isNotNull());
        }

        @Test
        @DisplayName("equals rejects a non-EventMetrics rather than reading its rows")
        void equalsRejectsAnotherType() {
            assertThat(EventMetrics.rows(5)).isNotEqualTo("rows=5");
        }

        @TestFactory
        @DisplayName("equals distinguishes every measurement")
        Stream<DynamicTest> equalsReadsEveryMeasurement() {
            return forEachMeasurement(field -> {
                EventMetrics low  = populate(field, 0);
                EventMetrics high = populate(field, 1);

                assertThat(low)
                        .as("two metrics differing only in %s compared equal — equals is "
                            + "not reading it, so they would collapse in a Set", field.getName())
                        .isNotEqualTo(high);
            });
        }

        @TestFactory
        @DisplayName("hashCode distinguishes every measurement")
        Stream<DynamicTest> hashCodeReadsEveryMeasurement() {
            // Unequal objects sharing a hash code is legal, so this is not the hashCode
            // contract — it is a drift signal. With one field per measurement and distinct
            // values, a collision here means the field was left out of hashCode.
            return forEachMeasurement(field ->
                    assertThat(populate(field, 0).hashCode())
                            .as("two metrics differing only in %s hash the same — hashCode "
                                + "is not reading it", field.getName())
                            .isNotEqualTo(populate(field, 1).hashCode()));
        }

        @TestFactory
        @DisplayName("isEmpty reads every measurement")
        Stream<DynamicTest> isEmptyReadsEveryMeasurement() {
            // The trap the type invites: isEmpty() is `rows.isEmpty()` today, and a second
            // measurement must turn it into a conjunction. Nothing else fails if it does
            // not — metrics carrying only a duration would report measuring nothing.
            return forEachMeasurement(field ->
                    assertThat(populate(field, 1).isEmpty())
                            .as("metrics carrying only %s reported measuring nothing",
                                    field.getName())
                            .isFalse());
        }

        @TestFactory
        @DisplayName("toString names every measurement")
        Stream<DynamicTest> toStringNamesEveryMeasurement() {
            return forEachMeasurement(field ->
                    assertThat(populate(field, 1).toString())
                            .as("toString omits %s, so a trace of the feed cannot show it",
                                    field.getName())
                            .contains(field.getName()));
        }

        @Test
        @DisplayName("a wither preserves the measurements it is not setting")
        void withersDoNotClobberEachOther() {
            // #667 shipped with withTarget silently reverting metrics to NONE; the same
            // shape one level down would be withDuration dropping the row count. Applying
            // every wither in turn must leave every measurement set, in either order.
            EventMetrics forwards = applyAll(measurements());
            EventMetrics backwards = applyAll(measurements().reversed());

            assertThat(forwards)
                    .as("the withers must compose in either order")
                    .isEqualTo(backwards);
            assertThat(forwards.isEmpty()).isFalse();
            for (Field field : measurements()) {
                assertThat(read(field, forwards))
                        .as("%s was clobbered by a later wither", field.getName())
                        .isEqualTo(read(field, populate(field, 1)));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Reflective support
    //
    // EventMetrics is a hand-written class precisely so its constructor can be private,
    // which means equals/hashCode/toString/isEmpty are hand-written too and each one
    // enumerates the fields. That is four places to update per measurement, three of
    // which fail silently — so the fields are enumerated here rather than listed.
    // -------------------------------------------------------------------------

    /**
     * A field whose wither is not named {@code with<Field>}.
     *
     * <p>Empty today. A measurement stored differently from how it is set (nanos behind a
     * {@code Duration} wither, say) records the mapping here rather than weakening the
     * lookup, so a genuinely missing wither still fails.
     */
    private static final Map<String, String> WITHER_NAMES = Map.of();

    /** The instance fields that hold measurements — every one of them, by construction. */
    private static List<Field> measurements() {
        return Arrays.stream(EventMetrics.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .filter(f -> !f.isSynthetic())
                .toList();
    }

    private static Stream<DynamicTest> forEachMeasurement(java.util.function.Consumer<Field> check) {
        return measurements().stream()
                .map(field -> DynamicTest.dynamicTest(field.getName(), () -> check.accept(field)));
    }

    private static Method witherFor(Field field) {
        String name = WITHER_NAMES.getOrDefault(field.getName(),
                "with" + Character.toUpperCase(field.getName().charAt(0))
                + field.getName().substring(1));
        return Arrays.stream(EventMetrics.class.getMethods())
                .filter(m -> m.getName().equals(name))
                .filter(m -> m.getParameterCount() == 1)
                .filter(m -> m.getReturnType() == EventMetrics.class)
                .findFirst().orElse(null);
    }

    /** {@link EventMetrics#NONE} with only {@code field} set, to {@code variant}'s value. */
    private static EventMetrics populate(Field field, int variant) {
        return invoke(EventMetrics.NONE, field, variant);
    }

    /** {@link EventMetrics#NONE} with every field in {@code fields} set, in order. */
    private static EventMetrics applyAll(List<Field> fields) {
        EventMetrics metrics = EventMetrics.NONE;
        for (Field field : fields) {
            metrics = invoke(metrics, field, 1);
        }
        return metrics;
    }

    private static EventMetrics invoke(EventMetrics target, Field field, int variant) {
        Method wither = witherFor(field);
        assertThat(wither).as("no wither for %s", field.getName()).isNotNull();
        try {
            return (EventMetrics) wither.invoke(target,
                    sampleValue(wither.getParameterTypes()[0], variant));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot set " + field.getName(), e);
        }
    }

    private static Object read(Field field, EventMetrics metrics) {
        try {
            return EventMetrics.class.getMethod(field.getName()).invoke(metrics);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no accessor named " + field.getName(), e);
        }
    }

    /**
     * Two distinct sample values per supported parameter type.
     *
     * <p>A measurement whose type is not here fails loudly rather than being skipped —
     * a guard that silently ignores the field it was written to check is worse than none.
     */
    private static Object sampleValue(Class<?> type, int variant) {
        if (type == long.class || type == Long.class)     return variant == 0 ? 3L : 7L;
        if (type == int.class || type == Integer.class)   return variant == 0 ? 3 : 7;
        if (type == double.class || type == Double.class) return variant == 0 ? 3.0 : 7.0;
        if (type == boolean.class || type == Boolean.class) return variant != 0;
        if (type == String.class)     return variant == 0 ? "a" : "b";
        if (type == Duration.class)   return Duration.ofMillis(variant == 0 ? 3 : 7);
        if (type == Instant.class)    return Instant.ofEpochMilli(variant == 0 ? 3 : 7);
        throw new AssertionError(
                "no sample value for a measurement of type " + type.getName()
                + " — add one here so the drift guard covers the new measurement");
    }
}

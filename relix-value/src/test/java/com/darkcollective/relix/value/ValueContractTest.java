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
package com.darkcollective.relix.value;

import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One instance of every {@link Value} kind, and the part of the interface contract
 * that is the same claim for all of them.
 *
 * <p>Six kinds were contract-tested in {@code ValueTest} and the four temporal ones in
 * {@code TemporalValueTest}, each by hand, and nothing related either file to the
 * {@code permits} clause. So the shared claims — {@code isNull()} answers for the kind,
 * {@code type()} is the one {@code Value}'s javadoc names, equality is by value —
 * held for the kinds somebody remembered, and an eleventh kind would have arrived
 * contract-untested in both files at once with nothing to say so.
 *
 * <p>The corpus is hand-written because a {@code Value} cannot be synthesised from its
 * class, which is what makes {@link #everyPermittedKindIsInTheCorpus()} load-bearing:
 * it reads {@code Value.class.getPermittedSubclasses()}, so a new kind fails this suite
 * until it has an entry. The same guard is why the JSON claim below can be stated as a
 * total one — every kind either round-trips through {@link JsonValues} or is named,
 * with its reason, as a kind JSON cannot spell.
 *
 * <p>What stays where it was: the per-kind suites keep everything that differs by kind
 * — that {@code NumberValue} strips trailing zeros, that a {@code TimestampValue}
 * displays with a {@code Z}, that each rejects a null component. Only the repeated
 * claims moved here, and only because they were repeated.
 */
@DisplayName("Value — the contract every kind shares")
final class ValueContractTest {

    /**
     * A kind's representative, its declared type, and the JSON that parses to it.
     *
     * @param json the document {@link JsonValues} reads back as an equal value, or
     *             {@code null} when JSON has no way to spell the kind — see
     *             {@link #NOT_SPELLABLE_IN_JSON}
     */
    private record Kind(Class<? extends Value> type,
                        ScalarType scalarType,
                        Supplier<Value> make,
                        String json) {

        /** A fresh instance of this kind. */
        Value value() {
            return make.get();
        }

        @Override
        public String toString() {
            return type.getSimpleName();
        }
    }

    private static final List<Kind> CORPUS = List.of(
            new Kind(StringValue.class, ScalarType.STRING,
                    () -> new StringValue("abc"), "\"abc\""),
            new Kind(NumberValue.class, ScalarType.NUMBER,
                    () -> NumberValue.of("42"), "42"),
            new Kind(BooleanValue.class, ScalarType.BOOLEAN,
                    () -> BooleanValue.of(true), "true"),
            new Kind(NullValue.class, ScalarType.ANY,
                    () -> NullValue.INSTANCE, "null"),
            new Kind(StructValue.class, ScalarType.ANY,
                    () -> new StructValue(Map.of("a", new StringValue("x"))), "{\"a\":\"x\"}"),
            new Kind(ArrayValue.class, ScalarType.ANY,
                    () -> new ArrayValue(List.of(NumberValue.of("1"))), "[1]"),
            new Kind(DateValue.class, ScalarType.DATE,
                    () -> new DateValue(LocalDate.of(2026, 1, 2)), null),
            new Kind(TimeValue.class, ScalarType.TIME,
                    () -> new TimeValue(LocalTime.of(3, 4, 5)), null),
            new Kind(TimestampValue.class, ScalarType.TIMESTAMP,
                    () -> new TimestampValue(Instant.parse("2026-01-02T03:04:05Z")), null),
            new Kind(DurationValue.class, ScalarType.DURATION,
                    () -> new DurationValue(Duration.ofMinutes(90)), null));

    /**
     * The kinds with no {@code json} above, named here so the omission is a decision
     * rather than a blank. JSON has six value forms and none of them is temporal: a
     * timestamp travels as a string and becomes a {@code TimestampValue} because a
     * schema says the column is one, which is the connector's reading of the document
     * rather than the document's own. Round-tripping one through {@link JsonValues}
     * would therefore be asserting a conversion this module does not perform.
     */
    private static final Set<Class<? extends Value>> NOT_SPELLABLE_IN_JSON = Set.of(
            DateValue.class, TimeValue.class, TimestampValue.class, DurationValue.class);

    static List<Kind> corpus() {
        return CORPUS;
    }

    // ── The shared contract ──────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    @DisplayName("reports the ScalarType its kind is documented to report")
    void reportsItsDeclaredType(Kind kind) {
        assertThat(kind.value().type()).isEqualTo(kind.scalarType());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    @DisplayName("isNull() is true for NullValue and false for every other kind")
    void isNullAnswersForItsKind(Kind kind) {
        assertThat(kind.value().isNull()).isEqualTo(kind.type() == NullValue.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    @DisplayName("asDisplayString() returns text, never null or blank")
    void hasADisplayString(Kind kind) {
        assertThat(kind.value().asDisplayString()).isNotNull().isNotBlank();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    @DisplayName("equality is by value — two independently built instances agree")
    void equalityIsByValue(Kind kind) {
        Value one = kind.value();
        Value other = kind.value();

        assertThat(one).isEqualTo(other);
        assertThat(one).hasSameHashCodeAs(other);
    }

    @Test
    @DisplayName("no two kinds are ever equal to each other")
    void kindsAreDistinct() {
        List<String> collisions = new ArrayList<>();
        for (Kind left : CORPUS) {
            for (Kind right : CORPUS) {
                if (left != right && left.value().equals(right.value())) {
                    collisions.add(left + " equals " + right);
                }
            }
        }
        assertThat(collisions)
                .as("values of different kinds comparing equal — every consumer that "
                        + "switches on the kind reads one of them as the other")
                .isEmpty();
    }

    // ── JSON, stated as a total claim ────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    @DisplayName("every kind JSON can spell parses back from it")
    void jsonRoundTripsOrIsDeclaredUnspellable(Kind kind) {
        if (kind.json() == null) {
            assertThat(NOT_SPELLABLE_IN_JSON)
                    .as("%s supplies no JSON document, so it must be named as a kind "
                            + "JSON cannot spell, with the reason", kind)
                    .contains(kind.type());
            return;
        }
        assertThat(NOT_SPELLABLE_IN_JSON)
                .as("%s supplies a JSON document and so is spellable — remove it from "
                        + "NOT_SPELLABLE_IN_JSON", kind)
                .doesNotContain(kind.type());
        assertThat(JsonValues.parse(kind.json()))
                .as("%s parsed back from %s", kind, kind.json())
                .isEqualTo(kind.value());
    }

    // ── The completeness guard ───────────────────────────────────────────────

    @Test
    @DisplayName("every kind Value permits has a corpus entry, and vice versa")
    void everyPermittedKindIsInTheCorpus() {
        Set<Class<?>> permitted = Set.of(Value.class.getPermittedSubclasses());
        Set<Class<?>> covered = CORPUS.stream().map(Kind::type)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<Class<?>> missing = new LinkedHashSet<>(permitted);
        missing.removeAll(covered);
        assertThat(missing)
                .as("Value kinds with no entry in CORPUS — the shared contract is "
                        + "asserted over the corpus, so a kind absent from it is a kind "
                        + "nothing here checks")
                .isEmpty();

        Set<Class<?>> stale = new LinkedHashSet<>(covered);
        stale.removeAll(permitted);
        assertThat(stale)
                .as("CORPUS entries naming a type Value no longer permits")
                .isEmpty();
    }

    @Test
    @DisplayName("the corpus number is the BigDecimal it spells, not a coincidence")
    void numberCorpusEntryIsExact() {
        // The JSON row above compares BigDecimals with equals(), which is scale-
        // sensitive: 42 and 42.0 are not equal. That makes the round trip a real
        // assertion rather than a lenient one, so the entry has to be exact.
        assertThat(((NumberValue) CORPUS.get(1).value()).value())
                .isEqualByComparingTo(new BigDecimal("42"))
                .isEqualTo(new BigDecimal("42"));
    }
}

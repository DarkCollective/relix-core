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
package com.darkcollective.relix.processor.exec;

import com.darkcollective.relix.processor.internal.ArrayRow;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.processor.eval.ValueComparator;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>When are two rows the same row?</b> — asked of every mechanism that answers it, and
 * held to one story.
 *
 * <p>There are four, and they are not interchangeable. {@code Value.equals} is record
 * equality. {@link ValueComparator#equal} canonicalises, so a string that spells a boolean
 * or an instant <em>is</em> that value. {@link ExecSupport#identityKey} is what δ, γ and
 * the set operations compare, and it chooses between the first two by asking the column's
 * declared type. {@code ArrayRow.equals} is what a materialised set holds rows by.
 *
 * <p>Two defects came from those drifting apart and neither was visible from any one of
 * them: a σ matched a string-spelled boolean against a {@code BOOLEAN} while δ kept the
 * two apart and returned a duplicate that printed identically to its neighbour; and set
 * operations compared tuples by name while the analyser compared them positionally, so ∩
 * returned nothing. Each was fixed where it surfaced. Nothing stops a fifth mechanism
 * answering the question a fifth way, and that is what this is for.
 *
 * <h2>The boundary is declared, not derived</h2>
 *
 * The table below is the whole rule in one place, which is otherwise spread across three
 * class javadocs. A change to any of those four mechanisms shows up here as a diff to a
 * line somebody has to justify, rather than as a passing test somewhere else.
 */
@DisplayName("Row identity — one question, four mechanisms, one answer")
final class RowIdentityLawTest extends ProcessorTestSupport {

    /** A column whose declared type has settled what its values are. */
    private static final Schema TYPED = schema(col("v", ScalarType.STRING));

    /** A column that has settled nothing — schema-on-read, where σ's coercion is the rule. */
    private static final Schema UNTYPED = schema("v");

    private static final Value FIVE = NumberValue.of("5");
    private static final Value FIVE_POINT_ZERO = NumberValue.of("5.0");
    private static final Value TRUE_TEXT = new StringValue("true");
    private static final Value TRUE_BOOL = BooleanValue.TRUE;
    private static final Value ZULU = new StringValue("2024-01-15T10:00:00Z");
    private static final Value OFFSET = new StringValue("2024-01-15T11:00:00+01:00");
    private static final Value INSTANT =
            new TimestampValue(Instant.parse("2024-01-15T10:00:00Z"));
    private static final Value SEVEN = NumberValue.of("7");
    private static final Value SEVEN_TEXT = new StringValue("7");
    private static final Value NUL = NullValue.INSTANCE;

    /**
     * One pair, and what each mechanism says about it.
     *
     * @param what        what the pair is, for the failure message
     * @param a           the first value
     * @param b           the second
     * @param recordEqual what {@code Value.equals} says
     * @param comparesEqual what {@link ValueComparator#equal} says
     * @param sameTyped   whether they are one key in a column of declared type
     * @param sameUntyped whether they are one key in a column that declared nothing
     */
    private record Pair(String what, Value a, Value b,
                        boolean recordEqual, boolean comparesEqual,
                        boolean sameTyped, boolean sameUntyped) {}

    private static final List<Pair> BOUNDARY = List.of(
            // Within a type, the value decides and every mechanism agrees. NumberValue
            // overrides equals for exactly this; it is the bridge the others rely on.
            new Pair("5 and 5.0 — one number, two spellings",
                    FIVE, FIVE_POINT_ZERO, true, true, true, true),
            new Pair("5 and 7", FIVE, SEVEN, false, false, false, false),
            new Pair("a string and itself", ZULU, new StringValue(ZULU.asDisplayString()),
                    true, true, true, true),

            // Across types the column's type decides, and this is the line the two defects
            // were on. σ calls these equal; a typed column still holds two values.
            new Pair("the string \"true\" and the boolean",
                    TRUE_TEXT, TRUE_BOOL, false, true, false, true),
            new Pair("two spellings of one instant",
                    ZULU, OFFSET, false, true, false, true),
            new Pair("a timestamp and the string that spells it",
                    INSTANT, ZULU, false, true, false, true),

            // A coercion that does not apply: "7" denotes nothing but itself, so no
            // mechanism unifies it with the number. They share a hash bucket and are
            // separated by the re-check, which is the join's design.
            new Pair("the number 7 and the string \"7\"",
                    SEVEN, SEVEN_TEXT, false, false, false, false),

            // NULL is the one place identity and equality are *supposed* to differ, and
            // the difference is not an accident of either: `=` cannot evaluate, so it
            // matches nothing; DISTINCT keeps one NULL and GROUP BY puts them in one
            // group, so a key must be equal to itself.
            new Pair("NULL and NULL", NUL, NUL, true, false, true, true),
            new Pair("NULL and a value", NUL, FIVE, false, false, false, false));

    // =========================================================================

    private static Row row(Schema schema, Value v) {
        return ArrayRow.of(schema, List.of(v));
    }

    private static boolean sameKey(Schema schema, Value a, Value b) {
        return ExecSupport.identityKey(row(schema, a), schema)
                .equals(ExecSupport.identityKey(row(schema, b), schema));
    }

    /** Every value the boundary names, plus the ones a generated sweep should also cover. */
    private static List<Value> allValues() {
        List<Value> values = new ArrayList<>(List.of(
                FIVE, FIVE_POINT_ZERO, SEVEN, SEVEN_TEXT, TRUE_TEXT, TRUE_BOOL,
                ZULU, OFFSET, INSTANT, NUL, BooleanValue.FALSE,
                new StringValue(""), new StringValue("FALSE"), new StringValue("pending"),
                NumberValue.of("0"), NumberValue.of("0.00"), NumberValue.of("-5")));
        return List.copyOf(values);
    }

    // =========================================================================

    @TestFactory
    @DisplayName("the declared boundary")
    Stream<DynamicTest> theBoundaryHolds() {
        return BOUNDARY.stream().map(p -> DynamicTest.dynamicTest(p.what(), () -> {
            assertThat(p.a().equals(p.b()))
                    .as("%s — Value.equals", p.what()).isEqualTo(p.recordEqual());
            assertThat(ValueComparator.equal(p.a(), p.b()))
                    .as("%s — ValueComparator.equal, which is what σ and a join ask",
                            p.what())
                    .isEqualTo(p.comparesEqual());
            assertThat(sameKey(TYPED, p.a(), p.b()))
                    .as("%s — one key in a column of declared type, which is what δ asks",
                            p.what())
                    .isEqualTo(p.sameTyped());
            assertThat(sameKey(UNTYPED, p.a(), p.b()))
                    .as("%s — one key in a column that declared nothing", p.what())
                    .isEqualTo(p.sameUntyped());
        }));
    }

    @Nested
    @DisplayName("the laws that hold for every pair, not only the interesting ones")
    class Laws {

        /**
         * Java's own contract, and not a formality here: {@code NumberValue} overrides
         * {@code equals} to ignore scale and {@code hashCode} to strip trailing zeros, and
         * those are two separate pieces of code that have to agree or a hash set holds
         * {@code 5} and {@code 5.0} apart while calling them equal.
         */
        @Test
        @DisplayName("equal values hash alike")
        void equalValuesHashAlike() {
            for (Value a : allValues()) {
                for (Value b : allValues()) {
                    if (a.equals(b)) {
                        assertThat(a.hashCode())
                                .as("%s equals %s but hashes differently", a, b)
                                .isEqualTo(b.hashCode());
                    }
                }
            }
        }

        /**
         * A declared type settles identity, so a key in one is record equality and nothing
         * more. This is what makes δ over a {@code STRING} column keep two strings that
         * denote one instant — and what a folded {@code SELECT DISTINCT} does.
         */
        @Test
        @DisplayName("in a typed column, a key is record equality")
        void aTypedKeyIsRecordEquality() {
            for (Value a : allValues()) {
                for (Value b : allValues()) {
                    assertThat(sameKey(TYPED, a, b))
                            .as("typed column: %s and %s", a, b)
                            .isEqualTo(a.equals(b));
                }
            }
        }

        /**
         * An untyped column has settled nothing, so a key follows the same coercion σ
         * does — with NULL the documented exception, equal to itself for grouping where
         * {@code =} matches nothing.
         */
        @Test
        @DisplayName("in an untyped column, a key follows the coercion σ follows")
        void anUntypedKeyFollowsTheComparator() {
            for (Value a : allValues()) {
                for (Value b : allValues()) {
                    boolean expected = a.isNull() || b.isNull()
                            ? a.isNull() && b.isNull()
                            : ValueComparator.equal(a, b);
                    assertThat(sameKey(UNTYPED, a, b))
                            .as("untyped column: %s and %s", a, b)
                            .isEqualTo(expected);
                }
            }
        }

        /**
         * The bridge the set operations stand on. They hold rows in a {@code Set<Row>} and
         * key them with {@link ExecSupport#identityKey}; if the two disagreed, a row would
         * be in the set under one rule and looked up under another.
         */
        @Test
        @DisplayName("row equality and the identity key agree in a typed schema")
        void rowEqualityAgreesWithTheKey() {
            for (Value a : allValues()) {
                for (Value b : allValues()) {
                    assertThat(row(TYPED, a).equals(row(TYPED, b)))
                            .as("row equality vs identity key: %s and %s", a, b)
                            .isEqualTo(sameKey(TYPED, a, b));
                }
            }
        }

        /**
         * A hash join buckets on {@link ExecSupport#keyTokens} and re-checks each candidate
         * pair; the bucket must therefore never separate a pair the re-check would match,
         * or matching rows are dropped with no diagnostic. The obligation is one-way — a
         * bucket may over-collect.
         */
        @Test
        @DisplayName("rows a join would match land in one bucket")
        void matchingRowsShareABucket() {
            int[] key = {0};
            for (Value a : allValues()) {
                for (Value b : allValues()) {
                    if (a.isNull() || b.isNull() || !ValueComparator.equal(a, b)) {
                        continue;
                    }
                    assertThat(ExecSupport.keyTokens(row(UNTYPED, a), key))
                            .as("a join would match %s with %s, so they must bucket alike", a, b)
                            .isEqualTo(ExecSupport.keyTokens(row(UNTYPED, b), key));
                }
            }
        }

        /**
         * The one mechanism allowed to refuse: ordering. A pair it cannot order is not a
         * pair equality may call equal, or a sort would depend on the order rows arrived.
         */
        @Test
        @DisplayName("what equality joins, ordering does not separate")
        void orderingNeverContradictsEquality() {
            for (Value a : allValues()) {
                for (Value b : allValues()) {
                    if (a.isNull() || b.isNull() || !ValueComparator.equal(a, b)) {
                        continue;
                    }
                    assertThat(ValueComparator.compareNonNull(a, b))
                            .as("equal(%s, %s) but ordering disagrees", a, b)
                            .isZero();
                }
            }
        }
    }
}

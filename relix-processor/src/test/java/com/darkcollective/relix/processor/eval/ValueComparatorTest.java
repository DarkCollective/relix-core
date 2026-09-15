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
package com.darkcollective.relix.processor.eval;

import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ValueComparator — total ordering over Value instances")
final class ValueComparatorTest {

    private static final Value NULL   = NullValue.INSTANCE;
    private static final Value NUM1   = NumberValue.of("1");
    private static final Value NUM2   = NumberValue.of("2");
    private static final Value STR_A  = new StringValue("apple");
    private static final Value STR_B  = new StringValue("banana");
    private static final Value BOOL_T = BooleanValue.TRUE;
    private static final Value BOOL_F = BooleanValue.FALSE;

    // ── NULLS_LAST ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("NULLS_LAST comparator")
    class NullsLast {

        @Test @DisplayName("NULL vs NULL → 0")
        void nullVsNull() {
            assertThat(ValueComparator.NULLS_LAST.compare(NULL, NULL)).isEqualTo(0);
        }

        @Test @DisplayName("NULL vs non-null → positive (NULL sorts after)")
        void nullAfterNonNull() {
            assertThat(ValueComparator.NULLS_LAST.compare(NULL, NUM1)).isGreaterThan(0);
        }

        @Test @DisplayName("non-null vs NULL → negative (non-null sorts before)")
        void nonNullBeforeNull() {
            assertThat(ValueComparator.NULLS_LAST.compare(NUM1, NULL)).isLessThan(0);
        }

        @Test @DisplayName("1 < 2 for numbers")
        void numberOrdering() {
            assertThat(ValueComparator.NULLS_LAST.compare(NUM1, NUM2)).isLessThan(0);
            assertThat(ValueComparator.NULLS_LAST.compare(NUM2, NUM1)).isGreaterThan(0);
            assertThat(ValueComparator.NULLS_LAST.compare(NUM1, NUM1)).isEqualTo(0);
        }

        @Test @DisplayName("lexicographic string ordering")
        void stringOrdering() {
            assertThat(ValueComparator.NULLS_LAST.compare(STR_A, STR_B)).isLessThan(0);
            assertThat(ValueComparator.NULLS_LAST.compare(STR_B, STR_A)).isGreaterThan(0);
            assertThat(ValueComparator.NULLS_LAST.compare(STR_A, STR_A)).isEqualTo(0);
        }

        @Test @DisplayName("false < true for booleans")
        void booleanOrdering() {
            assertThat(ValueComparator.NULLS_LAST.compare(BOOL_F, BOOL_T)).isLessThan(0);
            assertThat(ValueComparator.NULLS_LAST.compare(BOOL_T, BOOL_F)).isGreaterThan(0);
            assertThat(ValueComparator.NULLS_LAST.compare(BOOL_T, BOOL_T)).isEqualTo(0);
        }
    }

    // ── NULLS_FIRST ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("NULLS_FIRST comparator")
    class NullsFirst {

        @Test @DisplayName("NULL vs NULL → 0")
        void nullVsNull() {
            assertThat(ValueComparator.NULLS_FIRST.compare(NULL, NULL)).isEqualTo(0);
        }

        @Test @DisplayName("NULL vs non-null → negative (NULL sorts before)")
        void nullBeforeNonNull() {
            assertThat(ValueComparator.NULLS_FIRST.compare(NULL, NUM1)).isLessThan(0);
        }

        @Test @DisplayName("non-null vs NULL → positive (non-null sorts after)")
        void nonNullAfterNull() {
            assertThat(ValueComparator.NULLS_FIRST.compare(NUM1, NULL)).isGreaterThan(0);
        }

        @Test @DisplayName("non-null vs non-null delegates to compareNonNull")
        void nonNullVsNonNull() {
            assertThat(ValueComparator.NULLS_FIRST.compare(NUM1, NUM2)).isLessThan(0);
        }
    }

    // ── compareNonNull ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("compareNonNull — static helper")
    class CompareNonNull {

        @Test @DisplayName("incompatible types throw EvaluationException")
        void incompatibleTypesThrow() {
            assertThatThrownBy(() -> ValueComparator.compareNonNull(NUM1, STR_A))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("Cannot compare");
        }

        @Test @DisplayName("number vs boolean throws EvaluationException")
        void numberVsBooleanThrows() {
            assertThatThrownBy(() -> ValueComparator.compareNonNull(NUM1, BOOL_T))
                    .isInstanceOf(EvaluationException.class);
        }

        @Test @DisplayName("string vs boolean throws EvaluationException")
        void stringVsBooleanThrows() {
            assertThatThrownBy(() -> ValueComparator.compareNonNull(STR_A, BOOL_T))
                    .isInstanceOf(EvaluationException.class);
        }
    }

    // ── Temporal ordering ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Temporal values order by their java.time natural order")
    class Temporal {

        private static final Value DATE1 = new DateValue(LocalDate.of(2026, 1, 1));
        private static final Value DATE2 = new DateValue(LocalDate.of(2026, 6, 15));
        private static final Value TIME1 = new TimeValue(LocalTime.of(9, 0));
        private static final Value TIME2 = new TimeValue(LocalTime.of(13, 40));
        private static final Value TS1   = new TimestampValue(Instant.parse("2026-01-01T00:00:00Z"));
        private static final Value TS2   = new TimestampValue(Instant.parse("2026-06-15T13:40:00Z"));
        private static final Value DUR1  = new DurationValue(Duration.ofMinutes(30));
        private static final Value DUR2  = new DurationValue(Duration.ofHours(2));

        @Test @DisplayName("DATE ordering")
        void dateOrdering() {
            assertThat(ValueComparator.compareNonNull(DATE1, DATE2)).isNegative();
            assertThat(ValueComparator.compareNonNull(DATE2, DATE1)).isPositive();
            assertThat(ValueComparator.compareNonNull(DATE1, DATE1)).isZero();
        }

        @Test @DisplayName("TIME ordering")
        void timeOrdering() {
            assertThat(ValueComparator.compareNonNull(TIME1, TIME2)).isNegative();
            assertThat(ValueComparator.compareNonNull(TIME2, TIME1)).isPositive();
            assertThat(ValueComparator.compareNonNull(TIME1, TIME1)).isZero();
        }

        @Test @DisplayName("TIMESTAMP ordering")
        void timestampOrdering() {
            assertThat(ValueComparator.compareNonNull(TS1, TS2)).isNegative();
            assertThat(ValueComparator.compareNonNull(TS2, TS1)).isPositive();
            assertThat(ValueComparator.compareNonNull(TS1, TS1)).isZero();
        }

        @Test @DisplayName("DURATION ordering")
        void durationOrdering() {
            assertThat(ValueComparator.compareNonNull(DUR1, DUR2)).isNegative();
            assertThat(ValueComparator.compareNonNull(DUR2, DUR1)).isPositive();
            assertThat(ValueComparator.compareNonNull(DUR1, DUR1)).isZero();
        }

        @Test @DisplayName("cross-temporal comparison (DATE vs TIMESTAMP) throws")
        void crossTemporalThrows() {
            assertThatThrownBy(() -> ValueComparator.compareNonNull(DATE1, TS1))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("Cannot compare");
        }

        @Test @DisplayName("temporal vs NUMBER throws")
        void temporalVsNumberThrows() {
            assertThatThrownBy(() -> ValueComparator.compareNonNull(TS1, NUM1))
                    .isInstanceOf(EvaluationException.class);
        }

        @Test @DisplayName("NULL placement applies to a temporal value (NULLS_LAST / NULLS_FIRST)")
        void nullPlacement() {
            assertThat(ValueComparator.NULLS_LAST.compare(NULL, TS1)).isPositive();
            assertThat(ValueComparator.NULLS_LAST.compare(TS1, NULL)).isNegative();
            assertThat(ValueComparator.NULLS_FIRST.compare(NULL, TS1)).isNegative();
            assertThat(ValueComparator.NULLS_FIRST.compare(TS1, NULL)).isPositive();
        }

        @Test @DisplayName("τ-style sort orders timestamps chronologically (NULLS_LAST)")
        void sortOrdersChronologically() {
            Value tsMid = new TimestampValue(Instant.parse("2026-03-10T08:00:00Z"));
            List<Value> rows = new ArrayList<>(List.of(TS2, NULL, TS1, tsMid));
            rows.sort(ValueComparator.NULLS_LAST);
            assertThat(rows).containsExactly(TS1, tsMid, TS2, NULL);
        }
    }

    // ── joinToken ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("joinToken — the hash-bucket pre-filter")
    class JoinToken {

        /**
         * The whole obligation of the token, stated as a property: a hash join
         * buckets by the token and re-checks with {@code compareNonNull}, so any
         * pair the re-check would accept must already share a bucket. Splitting
         * such a pair drops matching rows silently — there is no error path.
         */
        @Test @DisplayName("equal values always share a token")
        void equalValuesShareAToken() {
            List<Value> values = List.of(
                    NumberValue.of("5"), NumberValue.of("5.0"), NumberValue.of("05"),
                    NumberValue.of("6"),
                    new StringValue("2024-01-15T10:00:00"),
                    new StringValue("2024-01-15T10:00:00Z"),
                    new TimestampValue(Instant.parse("2024-01-15T10:00:00Z")),
                    new StringValue("2024-01-15"),
                    new DateValue(LocalDate.parse("2024-01-15")),
                    new StringValue("10:00:00"),
                    new TimeValue(LocalTime.parse("10:00:00")),
                    new StringValue("PT1H"),
                    new DurationValue(Duration.ofHours(1)),
                    STR_A, BOOL_T, BOOL_F);

            for (Value a : values) {
                for (Value b : values) {
                    boolean equal;
                    try {
                        equal = ValueComparator.compareNonNull(a, b) == 0;
                    } catch (EvaluationException incomparable) {
                        continue;   // never reaches the re-check; the bucket cannot be wrong
                    }
                    if (equal) {
                        assertThat(ValueComparator.joinToken(a))
                                .as("%s and %s compare equal, so they must share a bucket", a, b)
                                .isEqualTo(ValueComparator.joinToken(b));
                    }
                }
            }
        }

        @Test @DisplayName("a temporal-shaped string tokenises as the value it denotes")
        void temporalStringCanonicalises() {
            // The regression: these two compare equal, but their display forms differ
            // ("2024-01-15T10:00:00" vs "2024-01-15T10:00:00Z"), so keying the bucket
            // on asDisplayString() put them in different buckets and the natural join
            // returned no rows.
            Value asText  = new StringValue("2024-01-15T10:00:00");
            Value asStamp = new TimestampValue(Instant.parse("2024-01-15T10:00:00Z"));

            assertThat(asText.asDisplayString()).isNotEqualTo(asStamp.asDisplayString());
            assertThat(ValueComparator.compareNonNull(asText, asStamp)).isZero();
            assertThat(ValueComparator.joinToken(asText)).isEqualTo(ValueComparator.joinToken(asStamp));
        }

        @Test @DisplayName("NUMBER scale still collapses")
        void numberScaleCollapses() {
            assertThat(ValueComparator.joinToken(NumberValue.of("5.0")))
                    .isEqualTo(ValueComparator.joinToken(NumberValue.of("5")));
        }

        @Test @DisplayName("a non-temporal string is left alone")
        void plainStringUnchanged() {
            assertThat(ValueComparator.joinToken(STR_A)).isEqualTo("apple");
            assertThat(ValueComparator.joinToken(BOOL_T)).isEqualTo("true");
        }

        @Test @DisplayName("distinct values keep distinct tokens")
        void distinctValuesKeepDistinctTokens() {
            assertThat(ValueComparator.joinToken(NumberValue.of("5")))
                    .isNotEqualTo(ValueComparator.joinToken(NumberValue.of("6")));
            assertThat(ValueComparator.joinToken(new TimestampValue(Instant.parse("2024-01-15T10:00:00Z"))))
                    .isNotEqualTo(ValueComparator.joinToken(
                            new TimestampValue(Instant.parse("2024-01-15T11:00:00Z"))));
        }
    }
}

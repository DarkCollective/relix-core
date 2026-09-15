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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ValueComparator} against {@link java.util.Comparator}'s three axioms, over
 * generated values rather than hand-picked pairs.
 *
 * <p>The class documents itself as a "total ordering" and is the single authority on when
 * two values are equal or ordered — {@code τ} sorts with it, merge joins assume both sides
 * agree with it, and {@code FunctionContext.valueOrder()} hands it to installed aggregates.
 * Its 26 existing tests check <em>results</em>: this one checks the <em>axioms</em>, which
 * is a different question and has a different answer.
 *
 * <p>The three, for a comparator {@code c}:
 * <ul>
 *   <li><strong>sign reversal</strong> — {@code sgn(c(a,b)) == -sgn(c(b,a))};</li>
 *   <li><strong>transitivity</strong> — {@code c(a,b) > 0 && c(b,c) > 0} implies
 *       {@code c(a,c) > 0};</li>
 *   <li><strong>substitutability</strong> — {@code c(a,b) == 0} implies
 *       {@code sgn(c(a,x)) == sgn(c(b,x))} for every {@code x}.</li>
 * </ul>
 *
 * <p>Comparing values of genuinely different types throws by design, so a triple is only
 * held to an axiom when all the comparisons it needs succeed.
 *
 * <p>Substitutability is the one that did not hold, and the nested class below is where
 * the fix for it is pinned: a string was coerced toward a typed operand but never against
 * another string, so equality depended on the pair rather than on the values. It is a
 * property of the comparison rule and not of any particular pair, which is why the fix was
 * to make the rule read one value — no pairwise rule can satisfy the axiom.
 *
 * <p>Generators are seeded from {@link #SEED}, a constant — a generated-input test that
 * fails differently on each run is worse than no test. Change it deliberately to explore,
 * and pin any new witness rather than leaving the search in the build.
 */
@DisplayName("ValueComparator — the Comparator axioms, over generated values")
final class ValueComparatorOrderAxiomsTest {

    private static final long SEED = 20260828L;

    /** Values of every kind the comparator orders, several of each. */
    private static List<Value> generated() {
        Random r = new Random(SEED);
        List<Value> values = new ArrayList<>();
        values.add(NullValue.INSTANCE);
        for (int i = 0; i < 4; i++) {
            values.add(new NumberValue(BigDecimal.valueOf(r.nextInt(200) - 100)));
            values.add(new StringValue("s" + r.nextInt(20)));
            values.add(new DateValue(LocalDate.of(2024, 1, 1).plusDays(r.nextInt(400))));
            values.add(new TimeValue(LocalTime.of(0, 0).plusMinutes(r.nextInt(1400))));
            values.add(new TimestampValue(
                    Instant.parse("2024-01-15T10:00:00Z").plusSeconds(r.nextInt(100_000))));
            values.add(new DurationValue(Duration.ofMinutes(r.nextInt(500))));
        }
        values.add(BooleanValue.TRUE);
        values.add(BooleanValue.FALSE);
        // Scale ties in, so BigDecimal.compareTo == 0 with equals() false is exercised.
        values.add(new NumberValue(new BigDecimal("1.0")));
        values.add(new NumberValue(new BigDecimal("1.00")));
        return List.copyOf(values);
    }

    /** {@code c(a,b)}, or empty when the pair is unorderable (different types). */
    private static Integer compareOrNull(ValueComparator c, Value a, Value b) {
        try {
            return c.compare(a, b);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int sgn(int n) {
        return Integer.compare(n, 0);
    }

    // =========================================================================

    @Nested
    @DisplayName("The axioms hold for every orderable triple")
    class Axioms {

        @Test
        @DisplayName("sign reversal — c(a,b) is the negation of c(b,a)")
        void signReversal() {
            for (ValueComparator c : List.of(ValueComparator.NULLS_LAST, ValueComparator.NULLS_FIRST)) {
                for (Value a : generated()) {
                    for (Value b : generated()) {
                        Integer ab = compareOrNull(c, a, b);
                        Integer ba = compareOrNull(c, b, a);
                        if (ab == null || ba == null) {
                            continue;
                        }
                        assertThat(sgn(ab)).as("sgn(c(%s,%s)) == -sgn(c(%s,%s))", a, b, b, a)
                                .isEqualTo(-sgn(ba));
                    }
                }
            }
        }

        @Test
        @DisplayName("transitivity — a > b > c implies a > c")
        void transitivity() {
            List<Value> values = generated();
            for (ValueComparator c : List.of(ValueComparator.NULLS_LAST, ValueComparator.NULLS_FIRST)) {
                for (Value a : values) {
                    for (Value b : values) {
                        Integer ab = compareOrNull(c, a, b);
                        if (ab == null || ab <= 0) {
                            continue;
                        }
                        for (Value x : values) {
                            Integer bx = compareOrNull(c, b, x);
                            Integer ax = compareOrNull(c, a, x);
                            if (bx == null || ax == null || bx <= 0) {
                                continue;
                            }
                            assertThat(ax).as("%s > %s > %s, so %s > %s", a, b, x, a, x)
                                    .isPositive();
                        }
                    }
                }
            }
        }

        /**
         * Restricted to values of one runtime type — which is where the ordering is a total
         * order, and where every consumer that sorts a typed column operates. The
         * cross-type case has its own section below, because it does not hold.
         */
        @Test
        @DisplayName("substitutability — equal-comparing values are interchangeable")
        void substitutabilityWithinAType() {
            List<Value> values = generated();
            for (ValueComparator c : List.of(ValueComparator.NULLS_LAST, ValueComparator.NULLS_FIRST)) {
                for (Value a : values) {
                    for (Value b : values) {
                        Integer ab = compareOrNull(c, a, b);
                        if (ab == null || ab != 0 || a.getClass() != b.getClass()) {
                            continue;
                        }
                        for (Value x : values) {
                            Integer ax = compareOrNull(c, a, x);
                            Integer bx = compareOrNull(c, b, x);
                            if (ax == null || bx == null) {
                                continue;
                            }
                            assertThat(sgn(ax))
                                    .as("%s and %s compare equal, so both must order alike against %s",
                                            a, b, x)
                                    .isEqualTo(sgn(bx));
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("NULL is ordered consistently at whichever end, and equals itself")
        void nullPlacement() {
            Value nul = NullValue.INSTANCE;
            assertThat(ValueComparator.NULLS_LAST.compare(nul, nul)).isZero();
            assertThat(ValueComparator.NULLS_FIRST.compare(nul, nul)).isZero();
            for (Value v : generated()) {
                if (v.isNull()) {
                    continue;
                }
                assertThat(ValueComparator.NULLS_LAST.compare(nul, v)).isPositive();
                assertThat(ValueComparator.NULLS_LAST.compare(v, nul)).isNegative();
                assertThat(ValueComparator.NULLS_FIRST.compare(nul, v)).isNegative();
                assertThat(ValueComparator.NULLS_FIRST.compare(v, nul)).isPositive();
            }
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("Canonicalisation — how substitutability is kept")
    class CanonicalisationKeepsSubstitutability {

        /**
         * A string denotes a value on its own, not by reference to whatever it is being
         * compared against, so two spellings of one value are equal to each other and not
         * merely each equal to the typed form.
         *
         * <p>This is the pair that used to break the axiom: a string was coerced toward a
         * typed operand but never against another string, so {@code a == b} and
         * {@code a == c} held with {@code b != c}. It is asserted as the three-way claim
         * rather than as two, because two of the three passed all along.
         */
        @Test
        @DisplayName("a typed value and both its spellings are mutually equal")
        void twoSpellingsOfTheSameValue() {
            ValueComparator c = ValueComparator.NULLS_LAST;

            Value asBoolean = BooleanValue.TRUE;
            Value lower = new StringValue("true");
            Value upper = new StringValue("TRUE");
            assertThat(c.compare(asBoolean, lower)).isZero();
            assertThat(c.compare(asBoolean, upper)).isZero();
            assertThat(c.compare(lower, upper)).isZero();

            Value instant = new TimestampValue(Instant.parse("2024-01-15T10:00:00Z"));
            Value zulu = new StringValue("2024-01-15T10:00:00Z");
            Value offset = new StringValue("2024-01-15T11:00:00+01:00");
            assertThat(c.compare(instant, zulu)).isZero();
            assertThat(c.compare(instant, offset)).isZero();
            assertThat(c.compare(zulu, offset)).isZero();
        }

        /**
         * The multiset the fix was measured against, and the reason it is <em>this</em>
         * multiset: every element must be distinguishable and no two may be equal.
         *
         * <p>An earlier version of this test used four values that all denote the same
         * instant and asserted that they sorted into more than one sequence. That witnesses
         * the bug but cannot witness the fix — once they are all equal a <em>stable</em>
         * sort preserves arrival order, so the count stays above one for an entirely
         * benign reason and the test passes either way. Ties have to be excluded for the
         * claim to mean anything.
         */
        @Test
        @DisplayName("a tie-free multiset sorts into exactly one sequence")
        void sortedOrderIsWellDefined() {
            // 10:00Z written with an offset, and a strictly later instant whose text sorts
            // BEFORE it lexicographically — so text order and chronological order disagree
            // and only one of them can be what the sort delivers.
            Value earlier = new StringValue("2024-01-15T11:00:00+01:00");
            Value later = new StringValue("2024-01-15T10:30:00Z");
            assertThat(ValueComparator.NULLS_LAST.compare(earlier, later))
                    .as("ordered by the instant denoted, not by the leading digits")
                    .isNegative();

            List<Value> multiset = List.of(later, earlier,
                    new StringValue("2024-01-16T00:00:00Z"));

            Set<String> orders = new LinkedHashSet<>();
            for (long seed = 1; seed <= 32; seed++) {
                List<Value> shuffled = new ArrayList<>(multiset);
                Collections.shuffle(shuffled, new Random(seed));
                shuffled.sort(ValueComparator.NULLS_LAST);
                orders.add(shuffled.toString());
            }

            assertThat(orders)
                    .as("permutations of one tie-free multiset must sort to one sequence")
                    .hasSize(1);
        }

        /**
         * What the fix costs, asserted rather than described. Text that denotes a value
         * <em>is</em> that value, so a column holding {@code "2024-01-15"} beside
         * {@code "pending"} holds a DATE beside a STRING and has no order — which the
         * comparator says rather than inventing one.
         *
         * <p>Pinned because it is the deliberate trade, not an oversight: the alternative
         * is an order that depends on which rows a sort happens to compare.
         */
        @Test
        @DisplayName("value-shaped text and plain text have no shared order")
        void mixedColumnHasNoOrder() {
            List<Value> mixed = new ArrayList<>(List.of(
                    new StringValue("2024-01-15"),
                    new StringValue("pending"),
                    new StringValue("2024-03-02")));

            assertThatThrownBy(() -> mixed.sort(ValueComparator.NULLS_LAST))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("DATE")
                    .hasMessageContaining("STRING");
        }

        /**
         * The constraint the class javadoc records: equality, ordering and the hash-bucket
         * token must agree, because a predicate written as a selection and the same
         * predicate written as a join once gave different answers with no diagnostic.
         *
         * <p>They agree here by construction — all three read one canonicalisation — and
         * this holds them to it over the generated values, which is the form in which a
         * later divergence gets noticed.
         */
        @Test
        @DisplayName("equal, compareNonNull and joinToken agree, pairwise")
        void theThreeAgree() {
            List<Value> values = new ArrayList<>(generated());
            values.add(new StringValue("true"));
            values.add(new StringValue("TRUE"));
            values.add(new StringValue("2024-01-15T10:00:00Z"));
            values.add(new StringValue("2024-01-15T11:00:00+01:00"));
            values.add(new StringValue("2024-01-15"));

            for (Value a : values) {
                for (Value b : values) {
                    if (a.isNull() || b.isNull()) continue;
                    boolean equal = ValueComparator.equal(a, b);
                    if (equal) {
                        assertThat(ValueComparator.compareNonNull(a, b))
                                .as("equal(%s, %s) but compareNonNull disagrees", a, b)
                                .isZero();
                        assertThat(ValueComparator.joinToken(a))
                                .as("equal(%s, %s) but they land in different hash buckets", a, b)
                                .isEqualTo(ValueComparator.joinToken(b));
                    } else {
                        // The token may over-collect — that is its documented one-way
                        // obligation — but ordering must never call an unequal pair equal.
                        try {
                            assertThat(ValueComparator.compareNonNull(a, b))
                                    .as("compareNonNull says %s == %s but equal says otherwise", a, b)
                                    .isNotZero();
                        } catch (EvaluationException unorderable) {
                            // "different types" is a consistent answer to both questions.
                        }
                    }
                }
            }
        }
    }
}

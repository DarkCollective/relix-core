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
package com.darkcollective.relix.processor.provenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.darkcollective.relix.processor.ArrayRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.provenance.BooleanSemiring;
import com.darkcollective.relix.provenance.CountingSemiring;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BinaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

final class AnnotatedAlgebraTest {

    // (region, name) — projecting away `name` merges rows that share a region.
    private static final Schema PEOPLE = schema("region", "name");
    private static final Schema REGION = schema("region");
    // join fixtures
    private static final Schema LEFT = numberKey("id", "name");
    private static final Schema RIGHT = numberKey("rid", "city");
    private static final Schema JOINED = LEFT.concat(RIGHT);

    private static Schema schema(String... cols) {
        return new Schema(Stream.of(cols)
                .map(c -> new ColumnDefinition(c, ScalarType.STRING)).toList());
    }

    private static Schema numberKey(String key, String text) {
        return new Schema(List.of(
                new ColumnDefinition(key, ScalarType.NUMBER),
                new ColumnDefinition(text, ScalarType.STRING)));
    }

    private static Row people(String region, String name) {
        return ArrayRow.of(PEOPLE, new StringValue(region), new StringValue(name));
    }

    private static Row left(long id, String name) {
        return ArrayRow.of(LEFT, NumberValue.of(Long.toString(id)), new StringValue(name));
    }

    private static Row right(long rid, String city) {
        return ArrayRow.of(RIGHT, NumberValue.of(Long.toString(rid)), new StringValue(city));
    }

    private static final BinaryOperator<Row> CONCAT = (l, r) -> {
        List<Value> vals = new ArrayList<>(l.width() + r.width());
        for (int i = 0; i < l.width(); i++) vals.add(l.get(i));
        for (int i = 0; i < r.width(); i++) vals.add(r.get(i));
        return ArrayRow.of(JOINED, vals);
    };

    // ─── selection ─────────────────────────────────────────────────────────────

    @Nested
    class Selection {
        @Test
        void keepsMatchingTuplesWithAnnotationsIntact() {
            var rel = AnnotatedRelation.lift(PEOPLE, CountingSemiring.INSTANCE,
                    Stream.of(people("eu", "a"), people("eu", "a"), people("us", "b")));

            var filtered = rel.select(row -> row.get("region").equals(new StringValue("eu")));

            assertThat(filtered.rows()).containsExactly(people("eu", "a"));
            assertThat(filtered.annotationOf(people("eu", "a"))).isEqualTo(BigInteger.TWO);
            assertThat(filtered.annotationOf(people("us", "b"))).isEqualTo(BigInteger.ZERO);
        }

        @Test
        void alwaysFalsePredicateYieldsEmpty() {
            var rel = AnnotatedRelation.lift(PEOPLE, BooleanSemiring.INSTANCE,
                    Stream.of(people("eu", "a")));
            assertThat(rel.select(r -> false).isEmpty()).isTrue();
        }
    }

    // ─── projection ────────────────────────────────────────────────────────────

    @Nested
    class Projection {
        @Test
        void mergesCollidingRowsByAddingCountsUnderNaturals() {
            var rel = AnnotatedRelation.lift(PEOPLE, CountingSemiring.INSTANCE,
                    Stream.of(people("eu", "a"), people("eu", "b"), people("eu", "b"),
                            people("us", "c")));
            // counts before projection: (eu,a)=1, (eu,b)=2, (us,c)=1

            var byRegion = rel.project(REGION,
                    row -> ArrayRow.of(REGION, row.get("region")));

            // (eu,*) collapses to one tuple with 1+2 = 3; (us,*) = 1
            assertThat(byRegion.annotationOf(ArrayRow.of(REGION, new StringValue("eu"))))
                    .isEqualTo(BigInteger.valueOf(3));
            assertThat(byRegion.annotationOf(ArrayRow.of(REGION, new StringValue("us"))))
                    .isEqualTo(BigInteger.ONE);
        }

        @Test
        void underBooleanIsPlainDuplicateElimination() {
            var rel = AnnotatedRelation.lift(PEOPLE, BooleanSemiring.INSTANCE,
                    Stream.of(people("eu", "a"), people("eu", "b")));

            var byRegion = rel.project(REGION,
                    row -> ArrayRow.of(REGION, row.get("region")));

            assertThat(byRegion.rows()).containsExactly(ArrayRow.of(REGION, new StringValue("eu")));
            assertThat(byRegion.annotationOf(ArrayRow.of(REGION, new StringValue("eu")))).isTrue();
        }
    }

    // ─── product & join ──────────────────────────────────────────────────────────

    @Nested
    class ProductAndJoin {
        @Test
        void productMultipliesAnnotationsOverAllPairs() {
            var l = AnnotatedRelation.lift(LEFT, CountingSemiring.INSTANCE,
                    Stream.of(left(1, "a"), left(1, "a"))); // (1,a) count 2
            var r = AnnotatedRelation.lift(RIGHT, CountingSemiring.INSTANCE,
                    Stream.of(right(9, "x"), right(9, "x"), right(9, "x"))); // (9,x) count 3

            var prod = l.product(r, JOINED, CONCAT);

            assertThat(prod.size()).isEqualTo(1);
            assertThat(prod.annotationOf(CONCAT.apply(left(1, "a"), right(9, "x"))))
                    .isEqualTo(BigInteger.valueOf(6)); // 2 ⊗ 3
        }

        @Test
        void joinKeepsOnlyMatchingPairs() {
            var l = AnnotatedRelation.lift(LEFT, CountingSemiring.INSTANCE,
                    Stream.of(left(1, "a"), left(2, "b")));
            var r = AnnotatedRelation.lift(RIGHT, CountingSemiring.INSTANCE,
                    Stream.of(right(1, "x"), right(3, "y")));

            var joined = l.join(r,
                    (lr, rr) -> lr.get("id").equals(rr.get("rid")), JOINED, CONCAT);

            assertThat(joined.rows()).containsExactly(CONCAT.apply(left(1, "a"), right(1, "x")));
            assertThat(joined.annotationOf(CONCAT.apply(left(1, "a"), right(1, "x"))))
                    .isEqualTo(BigInteger.ONE);
        }

        @Test
        void rejectsDifferentSemirings() {
            var l = AnnotatedRelation.lift(LEFT, CountingSemiring.INSTANCE, Stream.of(left(1, "a")));
            var r = AnnotatedRelation.lift(RIGHT, BooleanSemiring.INSTANCE, Stream.of(right(1, "x")));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> l.join(castSemiring(r), (a, b) -> true, JOINED, CONCAT))
                    .withMessageContaining("different semirings");
        }
    }

    // ─── union ──────────────────────────────────────────────────────────────────

    @Nested
    class Union {
        @Test
        void addsAnnotationsOfSharedTuplesUnderNaturals() {
            var a = AnnotatedRelation.lift(PEOPLE, CountingSemiring.INSTANCE,
                    Stream.of(people("eu", "a"), people("eu", "a"))); // count 2
            var b = AnnotatedRelation.lift(PEOPLE, CountingSemiring.INSTANCE,
                    Stream.of(people("eu", "a"), people("us", "c"))); // (eu,a)=1, (us,c)=1

            var u = a.union(b);

            assertThat(u.annotationOf(people("eu", "a"))).isEqualTo(BigInteger.valueOf(3));
            assertThat(u.annotationOf(people("us", "c"))).isEqualTo(BigInteger.ONE);
        }

        @Test
        void rejectsMismatchedSchema() {
            var a = AnnotatedRelation.lift(PEOPLE, BooleanSemiring.INSTANCE, Stream.of(people("eu", "a")));
            var b = AnnotatedRelation.lift(REGION, BooleanSemiring.INSTANCE,
                    Stream.of(ArrayRow.of(REGION, new StringValue("eu"))));
            assertThatIllegalArgumentException().isThrownBy(() -> a.union(b))
                    .withMessageContaining("union-compatible");
        }
    }

    // ─── correspondence: boolean ≡ set semantics, ℕ ≡ bag multiplicity ──────────

    @Nested
    class Correspondence {
        @Test
        void booleanReproducesSetSemanticsAcrossOperators() {
            var l = AnnotatedRelation.lift(LEFT, BooleanSemiring.INSTANCE,
                    Stream.of(left(1, "a"), left(1, "a"), left(2, "b"))); // dedups to 2 tuples
            var r = AnnotatedRelation.lift(RIGHT, BooleanSemiring.INSTANCE,
                    Stream.of(right(1, "x")));

            assertThat(l.rows()).containsExactly(left(1, "a"), left(2, "b")); // set: no dups
            assertThat(l.select(row -> row.get("id").equals(NumberValue.of("1"))).rows())
                    .containsExactly(left(1, "a"));
            assertThat(l.join(r, (a, b) -> a.get("id").equals(b.get("rid")), JOINED, CONCAT).rows())
                    .containsExactly(CONCAT.apply(left(1, "a"), right(1, "x")));
            assertThat(l.union(AnnotatedRelation.lift(LEFT, BooleanSemiring.INSTANCE,
                    Stream.of(left(2, "b"), left(3, "c")))).rows())
                    .containsExactly(left(1, "a"), left(2, "b"), left(3, "c")); // set union
        }

        @Test
        void naturalsReproduceBagMultiplicity() {
            var bag = AnnotatedRelation.lift(LEFT, CountingSemiring.INSTANCE,
                    Stream.of(left(1, "a"), left(1, "a"), left(1, "a")));
            assertThat(bag.annotationOf(left(1, "a"))).isEqualTo(BigInteger.valueOf(3));
        }
    }

    /** The different-semiring test deliberately defeats generics to reach the runtime guard. */
    @SuppressWarnings("unchecked")
    private static AnnotatedRelation<BigInteger> castSemiring(AnnotatedRelation<?> rel) {
        return (AnnotatedRelation<BigInteger>) rel;
    }
}

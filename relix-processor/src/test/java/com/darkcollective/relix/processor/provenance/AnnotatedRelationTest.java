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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.darkcollective.relix.processor.ArrayRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.provenance.BooleanSemiring;
import com.darkcollective.relix.provenance.CountingSemiring;
import com.darkcollective.relix.provenance.SecurityLattice;
import com.darkcollective.relix.provenance.SecurityLevel;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import java.math.BigInteger;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class AnnotatedRelationTest {

    private static final Schema SCHEMA =
            new Schema(List.of(new ColumnDefinition("name", ScalarType.STRING)));

    private static Row row(String name) {
        return ArrayRow.of(SCHEMA, new StringValue(name));
    }

    // ─── lift ───────────────────────────────────────────────────────────────

    @Test
    void liftAnnotatesPresentTuplesUnderBoolean() {
        var rel = AnnotatedRelation.lift(
                SCHEMA, BooleanSemiring.INSTANCE, Stream.of(row("a"), row("b"), row("a")));

        assertThat(rel.size()).isEqualTo(2); // duplicate "a" merged
        assertThat(rel.annotationOf(row("a"))).isTrue();
        assertThat(rel.annotationOf(row("b"))).isTrue();
        assertThat(rel.annotationOf(row("absent"))).isFalse(); // zero
    }

    @Test
    void liftRecordsMultiplicityUnderCounting() {
        var rel = AnnotatedRelation.lift(
                SCHEMA,
                CountingSemiring.INSTANCE,
                Stream.of(row("a"), row("a"), row("a"), row("b")));

        assertThat(rel.size()).isEqualTo(2);
        assertThat(rel.annotationOf(row("a"))).isEqualTo(BigInteger.valueOf(3));
        assertThat(rel.annotationOf(row("b"))).isEqualTo(BigInteger.ONE);
    }

    @Test
    void liftPreservesFirstSeenOrder() {
        var rel = AnnotatedRelation.lift(
                SCHEMA, BooleanSemiring.INSTANCE, Stream.of(row("b"), row("a"), row("b")));

        assertThat(rel.rows()).containsExactly(row("b"), row("a"));
    }

    // ─── normalise ──────────────────────────────────────────────────────────

    @Test
    void normaliseCombinesAnnotationsWithPlus() {
        var rel = AnnotatedRelation.normalise(
                SCHEMA,
                CountingSemiring.INSTANCE,
                Stream.of(
                        new Annotated<>(row("a"), BigInteger.TWO),
                        new Annotated<>(row("a"), BigInteger.valueOf(3)),
                        new Annotated<>(row("b"), BigInteger.ONE)));

        assertThat(rel.annotationOf(row("a"))).isEqualTo(BigInteger.valueOf(5));
        assertThat(rel.annotationOf(row("b"))).isEqualTo(BigInteger.ONE);
    }

    @Test
    void normaliseDropsZeroAnnotatedTuples() {
        var rel = AnnotatedRelation.normalise(
                SCHEMA,
                CountingSemiring.INSTANCE,
                Stream.of(
                        new Annotated<>(row("present"), BigInteger.ONE),
                        new Annotated<>(row("zero"), BigInteger.ZERO)));

        assertThat(rel.size()).isEqualTo(1);
        assertThat(rel.rows()).containsExactly(row("present"));
        assertThat(rel.annotationOf(row("zero"))).isEqualTo(BigInteger.ZERO); // absent
    }

    @Test
    void normaliseDropsTuplesThatCombineToZeroUnderBoolean() {
        // false ⊕ false = false (= zero), so the tuple is absent.
        var rel = AnnotatedRelation.normalise(
                SCHEMA,
                BooleanSemiring.INSTANCE,
                Stream.of(
                        new Annotated<>(row("gone"), Boolean.FALSE),
                        new Annotated<>(row("gone"), Boolean.FALSE)));

        assertThat(rel.isEmpty()).isTrue();
    }

    @Test
    void securityLatticeKeepsTheMostAccessibleDerivation() {
        var rel = AnnotatedRelation.normalise(
                SCHEMA,
                SecurityLattice.INSTANCE,
                Stream.of(
                        new Annotated<>(row("doc"), SecurityLevel.SECRET),
                        new Annotated<>(row("doc"), SecurityLevel.CONFIDENTIAL)));

        assertThat(rel.annotationOf(row("doc"))).isEqualTo(SecurityLevel.CONFIDENTIAL);
    }

    // ─── views & accessors ────────────────────────────────────────────────────

    @Test
    void streamPairsRowsWithAnnotations() {
        var rel = AnnotatedRelation.lift(
                SCHEMA, CountingSemiring.INSTANCE, Stream.of(row("a"), row("a")));

        assertThat(rel.stream())
                .containsExactly(new Annotated<>(row("a"), BigInteger.TWO));
    }

    @Test
    void exposesSchemaAndSemiring() {
        var rel = AnnotatedRelation.lift(SCHEMA, BooleanSemiring.INSTANCE, Stream.of(row("a")));
        assertThat(rel.schema()).isEqualTo(SCHEMA);
        assertThat(rel.semiring()).isEqualTo(BooleanSemiring.INSTANCE);
    }

    @Test
    void emptyInputYieldsEmptyRelation() {
        var rel = AnnotatedRelation.lift(SCHEMA, BooleanSemiring.INSTANCE, Stream.of());
        assertThat(rel.isEmpty()).isTrue();
        assertThat(rel.size()).isZero();
        assertThat(rel.rows()).isEmpty();
        assertThat(rel.stream()).isEmpty();
    }

    // ─── null-guards ──────────────────────────────────────────────────────────

    @Test
    void rejectsNullArguments() {
        Stream<Row> rows = Stream.of(row("a"));
        assertThatNullPointerException()
                .isThrownBy(() -> AnnotatedRelation.lift(SCHEMA, null, rows));
        assertThatNullPointerException()
                .isThrownBy(() -> AnnotatedRelation.normalise(null, BooleanSemiring.INSTANCE, Stream.of()));
        var rel = AnnotatedRelation.lift(SCHEMA, BooleanSemiring.INSTANCE, Stream.of(row("a")));
        assertThatNullPointerException().isThrownBy(() -> rel.annotationOf(null));
    }
}

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

import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.provenance.Semiring;
import com.darkcollective.relix.symbol.Schema;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.function.BiPredicate;
import java.util.function.BinaryOperator;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * A K-relation: a relation in which every distinct tuple carries an annotation
 * drawn from a {@link Semiring} (after Green, Karvounarakis &amp; Tannen,
 * PODS 2007). This is the <strong>opt-in</strong> annotated-relation model — it is
 * built only when a provenance mode is active; the engine's default
 * {@code Stream<Row>} path is untouched and pays nothing.
 *
 * <p>The relation is held in <em>canonical</em> form: each distinct tuple appears
 * exactly once, mapped to the {@link Semiring#plus ⊕}-combination of every
 * derivation that produced it, and no tuple maps to {@link Semiring#zero() zero}
 * (a zero annotation means "absent"). Tuple identity is the {@link Row}'s own
 * structural {@code equals}/{@code hashCode} — the same value-equality the engine's
 * {@code DISTINCT}/set operators already use — so equal tuples are merged exactly as
 * a set relation would dedup them, but combining their annotations instead of
 * discarding the duplicate. Insertion order is preserved for stable output.
 *
 * <p>Choosing the semiring chooses the meaning of the annotation: the
 * {@link com.darkcollective.relix.provenance.BooleanSemiring boolean} semiring
 * reproduces set semantics (present / absent), the
 * {@link com.darkcollective.relix.provenance.CountingSemiring ℕ} semiring
 * reproduces bag multiplicity, and so on. This class is the carrier model and its
 * defining operations — {@code ProvenanceEvaluator} threads the annotations through
 * the positive operators (σ/π/×/⋈/∪): {@link #lift lift} a plain relation in,
 * {@link #normalise normalise} an annotated stream to canonical form, and
 * {@link #rows forget} the annotations back out.
 *
 * @param <K> the semiring annotation type
 */
public final class AnnotatedRelation<K> {

    private final Schema schema;
    private final Semiring<K> semiring;
    private final SequencedMap<Row, K> annotations;

    /** Trusted constructor: {@code annotations} must already be canonical (no zero values). */
    private AnnotatedRelation(Schema schema, Semiring<K> semiring, SequencedMap<Row, K> annotations) {
        this.schema = schema;
        this.semiring = semiring;
        this.annotations = annotations;
    }

    /**
     * Lifts a plain relation into a K-relation by annotating every input row with
     * the semiring's {@link Semiring#one() one}, combining duplicate tuples with
     * {@link Semiring#plus ⊕}. This is the canonical embedding of ordinary data:
     * under the boolean semiring a tuple becomes simply "present", and under ℕ a
     * tuple's annotation becomes its multiplicity in the input bag.
     *
     * @param schema   the relation's schema; never {@code null}
     * @param semiring the annotation semiring; never {@code null}
     * @param rows     the plain input rows; never {@code null}
     * @param <K>      the annotation type
     * @return the canonical K-relation
     */
    public static <K> AnnotatedRelation<K> lift(Schema schema, Semiring<K> semiring, Stream<Row> rows) {
        Objects.requireNonNull(rows, "rows");
        K one = Objects.requireNonNull(semiring, "semiring").one();
        return normalise(schema, semiring, rows.map(row -> new Annotated<>(row, one)));
    }

    /**
     * Reduces a stream of {@link Annotated} rows to canonical form: tuples that are
     * structurally equal have their annotations combined with {@link Semiring#plus ⊕}
     * (in encounter order), and any tuple whose combined annotation equals
     * {@link Semiring#zero() zero} is dropped (it is absent). This is the operation
     * that makes an annotated multiset a well-defined K-relation.
     *
     * @param schema    the relation's schema; never {@code null}
     * @param semiring  the annotation semiring; never {@code null}
     * @param annotated the annotated input rows; never {@code null}
     * @param <K>       the annotation type
     * @return the canonical K-relation
     */
    public static <K> AnnotatedRelation<K> normalise(
            Schema schema, Semiring<K> semiring, Stream<Annotated<K>> annotated) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(semiring, "semiring");
        Objects.requireNonNull(annotated, "annotated");

        SequencedMap<Row, K> merged = new LinkedHashMap<>();
        annotated.forEach(a ->
                merged.merge(a.row(), a.annotation(), semiring::plus));
        merged.values().removeIf(k -> k.equals(semiring.zero()));
        return new AnnotatedRelation<>(schema, semiring, merged);
    }

    /** {@return the schema shared by every tuple} */
    public Schema schema() {
        return schema;
    }

    /** {@return the semiring over which this relation's annotations are combined} */
    public Semiring<K> semiring() {
        return semiring;
    }

    /**
     * Returns this tuple's annotation, or the semiring's {@link Semiring#zero() zero}
     * if the tuple is absent from the relation.
     *
     * @param row the tuple to look up; never {@code null}
     * @return the tuple's annotation, or {@code zero} if absent
     */
    public K annotationOf(Row row) {
        Objects.requireNonNull(row, "row");
        return annotations.getOrDefault(row, semiring.zero());
    }

    /** {@return the number of distinct present (non-zero) tuples} */
    public int size() {
        return annotations.size();
    }

    /** {@return whether the relation has no present tuples} */
    public boolean isEmpty() {
        return annotations.isEmpty();
    }

    /**
     * {@return a lazy stream of the present tuples paired with their annotations,
     * in insertion order}
     */
    public Stream<Annotated<K>> stream() {
        return annotations.entrySet().stream()
                .map(e -> new Annotated<>(e.getKey(), e.getValue()));
    }

    /**
     * Forgets the annotations, yielding the present tuples as a plain row stream in
     * insertion order — the bridge back to the engine's ordinary {@code Stream<Row>}
     * path (and the basis for surfacing provenance without exposing it as a column).
     *
     * @return a lazy stream of the present rows
     */
    public Stream<Row> rows() {
        return annotations.keySet().stream();
    }

    // ─── positive relational algebra (ADR-0004 item 4) ──────────────────────────
    // Each operator threads the semiring: σ multiplies a tuple's annotation by 1
    // (keep) or 0 (drop); π and ∪ combine alternative derivations with ⊕; × and ⋈
    // combine joint requirements with ⊗. Under the boolean semiring these reproduce
    // set semantics; under the ℕ semiring, bag multiplicity.

    /**
     * Selection (σ): keeps each tuple satisfying {@code predicate} with its
     * annotation unchanged, and drops the rest. In semiring terms a kept tuple is
     * multiplied by {@link Semiring#one() one} (identity) and a dropped tuple by
     * {@link Semiring#zero() zero} (absent), so this is simply a filter — no two
     * surviving tuples can collide, so no {@code ⊕} is needed.
     *
     * @param predicate the row predicate; never {@code null}
     * @return a K-relation of the matching tuples
     */
    public AnnotatedRelation<K> select(Predicate<Row> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        SequencedMap<Row, K> kept = new LinkedHashMap<>();
        annotations.forEach((row, k) -> {
            if (predicate.test(row)) {
                kept.put(row, k);
            }
        });
        return new AnnotatedRelation<>(schema, semiring, kept);
    }

    /**
     * Projection (π): rewrites each tuple to {@code outputSchema} via {@code map},
     * combining the annotations of tuples that collapse to the same output tuple
     * with {@link Semiring#plus ⊕} (alternative derivations of one output row). Under
     * ℕ this adds the multiplicities of merged rows; under booleans it is plain
     * duplicate elimination.
     *
     * @param outputSchema the projected schema; never {@code null}
     * @param map          maps an input row to its projected row; never {@code null}
     * @return the projected K-relation
     */
    public AnnotatedRelation<K> project(Schema outputSchema, UnaryOperator<Row> map) {
        Objects.requireNonNull(outputSchema, "outputSchema");
        Objects.requireNonNull(map, "map");
        return normalise(outputSchema, semiring,
                stream().map(a -> new Annotated<>(map.apply(a.row()), a.annotation())));
    }

    /**
     * Cartesian product (×): every pair of tuples, one from each side, combined by
     * {@code concat} and annotated {@link Semiring#times times}{@code (k₁, k₂)}.
     * Equivalent to a {@link #join join} whose match always holds.
     *
     * @param other        the right relation; must share this relation's semiring
     * @param outputSchema the combined schema; never {@code null}
     * @param concat       combines a left and right row into the output row; never {@code null}
     * @return the product K-relation
     */
    public AnnotatedRelation<K> product(
            AnnotatedRelation<K> other, Schema outputSchema, BinaryOperator<Row> concat) {
        return join(other, (l, r) -> true, outputSchema, concat);
    }

    /**
     * Join (⋈/⨝): pairs of tuples satisfying {@code match}, combined by
     * {@code concat} and annotated {@link Semiring#times times}{@code (k₁, k₂)} — the
     * joint-requirement {@code ⊗}. Output tuples that coincide have their annotations
     * {@link Semiring#plus ⊕}-combined. Under booleans this is the ordinary join;
     * under ℕ the result multiplicity is the product of the matched multiplicities.
     *
     * @param other        the right relation; must share this relation's semiring
     * @param match        whether a left/right row pair joins; never {@code null}
     * @param outputSchema the combined schema; never {@code null}
     * @param concat       combines a matched left and right row; never {@code null}
     * @return the join K-relation
     */
    public AnnotatedRelation<K> join(
            AnnotatedRelation<K> other,
            BiPredicate<Row, Row> match,
            Schema outputSchema,
            BinaryOperator<Row> concat) {
        requireSameSemiring(other);
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(outputSchema, "outputSchema");
        Objects.requireNonNull(concat, "concat");

        Stream<Annotated<K>> pairs = stream().flatMap(left ->
                other.stream()
                        .filter(right -> match.test(left.row(), right.row()))
                        .map(right -> new Annotated<>(
                                concat.apply(left.row(), right.row()),
                                semiring.times(left.annotation(), right.annotation()))));
        return normalise(outputSchema, semiring, pairs);
    }

    /**
     * Union (∪): the union-compatible combination of two K-relations, with a shared
     * tuple's annotations combined by {@link Semiring#plus ⊕} (it is derivable from
     * either side). Under booleans this is set union; under ℕ the multiplicities add
     * (bag union). Both relations must have equal schemas and share the semiring.
     *
     * @param other the other relation; same schema and semiring
     * @return the union K-relation
     */
    public AnnotatedRelation<K> union(AnnotatedRelation<K> other) {
        requireSameSemiring(other);
        if (!schema.equals(other.schema)) {
            throw new IllegalArgumentException(
                    "union requires union-compatible schemas: " + schema + " vs " + other.schema);
        }
        return normalise(schema, semiring, Stream.concat(stream(), other.stream()));
    }

    private void requireSameSemiring(AnnotatedRelation<K> other) {
        Objects.requireNonNull(other, "other");
        if (!semiring.equals(other.semiring)) {
            throw new IllegalArgumentException(
                    "operands carry different semirings: " + semiring + " vs " + other.semiring);
        }
    }
}

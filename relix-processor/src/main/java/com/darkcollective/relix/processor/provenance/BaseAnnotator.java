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
import com.darkcollective.relix.provenance.CountingSemiring;
import com.darkcollective.relix.provenance.PathCost;
import com.darkcollective.relix.provenance.PathCostSemiring;
import com.darkcollective.relix.provenance.Polynomial;
import com.darkcollective.relix.provenance.PolynomialSemiring;
import com.darkcollective.relix.provenance.Route;
import com.darkcollective.relix.provenance.Semiring;
import com.darkcollective.relix.provenance.SourceRef;
import com.darkcollective.relix.provenance.TropicalSemiring;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.Value;

import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Assigns the provenance annotation of a <em>base-tuple occurrence</em> — the
 * {@code K} value a {@link ProvenanceEvaluator} lifts each leaf row to before the
 * positive operators thread {@code ⊕}/{@code ⊗} above it.
 *
 * <p>For the cheap semirings this is simply {@link Semiring#one() one} for every
 * tuple (see {@link #constantOne}), so a base relation embeds with each row "present"
 * (boolean) or multiplicity one (ℕ). The lineage semiring {@code ℕ[X]} instead mints
 * a <em>distinct provenance variable per occurrence</em> (e.g. {@code Orders#1},
 * {@code Orders#2}), which is what makes it the free, most-informative semiring: two
 * structurally-equal source rows receive different variables, and the merged tuple's
 * annotation becomes {@code x₁ ⊕ x₂}.
 *
 * <p>The {@code ordinal} is a 1-based per-leaf occurrence counter, so
 * {@code (source, ordinal)} uniquely names each base-tuple occurrence. This seam is
 * also the natural home for future source-level annotations (e.g. a security level
 * per source).
 *
 * @param <K> the semiring annotation type
 */
@FunctionalInterface
public interface BaseAnnotator<K> {

    /**
     * Returns the annotation for one base-tuple occurrence.
     *
     * @param source  a label for the leaf the row came from (e.g. a relation name)
     * @param ordinal the 1-based occurrence index within that leaf
     * @param row     the base row
     * @return the tuple's base annotation
     */
    K annotate(String source, long ordinal, Row row);

    /**
     * {@return an annotator that lifts every base tuple to the semiring's
     * {@link Semiring#one() one}} The correct (and zero-cost) lift for every cheap
     * semiring; lineage is the exception.
     *
     * @param semiring the semiring whose {@code one()} is used
     * @param <K>      the annotation type
     */
    static <K> BaseAnnotator<K> constantOne(Semiring<K> semiring) {
        K one = semiring.one();
        return (source, ordinal, row) -> one;
    }

    /**
     * {@return the annotator {@code semiring} wants, given an optional per-edge
     * {@code weightColumn}}
     *
     * <p>Three shapes, and which one applies is a property of the semiring rather than
     * a choice the caller makes: the cheapest-route semiring needs both a cost and a
     * per-edge identity token; every other semiring reads the weight column alone; and
     * with no weight column at all the answer is {@link #constantOne}. A row lacking the
     * column, or carrying a null or non-numeric value, weighs {@link Semiring#one() one}.
     *
     * <p>Lineage is not reachable from here — {@code ℕ[X]} mints a variable per
     * occurrence rather than reading a weight, so it is {@link #lineage()}.
     *
     * @param semiring     the annotation semiring; must not be null
     * @param weightColumn the per-edge weight column, or {@code null} for the plain lift
     * @param <K>          the annotation type
     */
    @SuppressWarnings("unchecked")
    static <K> BaseAnnotator<K> forSemiring(Semiring<K> semiring, String weightColumn) {
        if (semiring == PathCostSemiring.INSTANCE) {
            return (BaseAnnotator<K>) pathCost(weightColumn);
        }
        return weightColumn == null
                ? constantOne(semiring)
                : (source, ordinal, row) -> row.schema().indexOf(weightColumn) < 0
                        ? semiring.one()
                        : coerceWeight(semiring, row.get(weightColumn));
    }

    /**
     * {@return the lineage annotator} Each base-tuple occurrence becomes a fresh
     * {@code ℕ[X]} variable named {@code <source>#<ordinal>}, carrying that occurrence's
     * column values — which is what makes a lineage variable addressable back to the row
     * that produced it, rather than merely nameable.
     */
    static BaseAnnotator<Polynomial> lineage() {
        return (source, ordinal, row) ->
                PolynomialSemiring.variable(new SourceRef(source, ordinal, capturedColumns(row)));
    }

    /**
     * {@return the cheapest-route annotator} It mints each edge a distinct identity token
     * and reads its cost from {@code weightColumn}. A row lacking the column (or carrying
     * a null or non-numeric value), and the absence of a weight column entirely, weighs
     * the edge {@code 0.0} — every route then weighs its hop count of zero, making
     * cheapest-route a degenerate-but-defined reachability with witnesses.
     */
    private static BaseAnnotator<PathCost> pathCost(String weightColumn) {
        return (source, ordinal, row) -> {
            double cost = 0.0d;
            if (weightColumn != null
                    && row.schema().indexOf(weightColumn) >= 0
                    && row.get(weightColumn) instanceof NumberValue n) {
                cost = n.value().doubleValue();
            }
            return PathCost.of(cost, Route.of(source + "#" + ordinal));
        };
    }

    /**
     * {@return {@code value} coerced into the weight of {@code semiring}} A numeric value
     * becomes a {@code double} (tropical) or a {@code BigInteger} multiplicity (ℕ); every
     * other semiring, and any non-numeric or null value, yields {@link Semiring#one()}.
     */
    @SuppressWarnings("unchecked")
    private static <K> K coerceWeight(Semiring<K> semiring, Value value) {
        if (value instanceof NumberValue n) {
            if (semiring == TropicalSemiring.INSTANCE) {
                return (K) (Double) n.value().doubleValue();
            }
            if (semiring == CountingSemiring.INSTANCE) {
                return (K) n.value().toBigInteger();
            }
        }
        return semiring.one();
    }

    /**
     * {@return a base tuple's column values as a sorted {@code name → display-string}
     * map} A SQL-null column is captured as a {@code null} value, which is why this is
     * not a {@code Map.of}.
     */
    private static SortedMap<String, String> capturedColumns(Row row) {
        SortedMap<String, String> columns = new TreeMap<>();
        for (String name : row.columnNames()) {
            Value v = row.get(name);
            columns.put(name, v.isNull() ? null : v.asDisplayString());
        }
        return columns;
    }
}

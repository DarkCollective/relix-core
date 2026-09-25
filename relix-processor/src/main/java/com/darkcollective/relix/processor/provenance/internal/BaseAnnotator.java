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
package com.darkcollective.relix.processor.provenance.internal;

import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.provenance.BaseTuple;
import com.darkcollective.relix.provenance.Polynomial;
import com.darkcollective.relix.provenance.PolynomialSemiring;
import com.darkcollective.relix.provenance.Semiring;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.Value;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Assigns the provenance annotation of a <em>base-tuple occurrence</em> — the
 * {@code K} value a {@link ProvenanceEvaluator} lifts each leaf row to before the
 * positive operators thread {@code ⊕}/{@code ⊗} above it.
 *
 * <p>Which annotation a tuple lifts to is the <em>semiring's</em> answer, not this
 * interface's: {@link #forSemiring} reduces the row to a
 * {@link com.darkcollective.relix.provenance.BaseTuple} and asks
 * {@link Semiring#base}. For the cheap semirings that is {@link Semiring#one() one} for
 * every tuple, so a base relation embeds with each row "present" (boolean) or
 * multiplicity one (ℕ). The lineage semiring {@code ℕ[X]} instead mints a
 * <em>distinct provenance variable per occurrence</em> (e.g. {@code Orders#1},
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
     * {@return the annotator {@code semiring} wants, given an optional per-edge
     * {@code weightColumn}}
     *
     * <p>Which shape of annotation a base tuple lifts to is a property of the semiring
     * rather than a choice the caller makes, and it is the semiring that states it: this
     * reduces each row to a {@link BaseTuple} — its leaf, its occurrence index, its weight
     * and, on demand, its columns — and hands that to {@link Semiring#base}. So a
     * constant, a cost, a multiplicity, a route token and a lineage variable are all the
     * same call here, and an installed semiring reaches every one of those shapes without
     * the engine recognising it.
     *
     * <p>A row lacking the weight column, or carrying a null or non-numeric value, has no
     * weight; the semiring decides what that means for it.
     *
     * @param semiring     the annotation semiring; must not be null
     * @param weightColumn the per-edge weight column, or {@code null} for no weight
     * @param <K>          the annotation type
     */
    static <K> BaseAnnotator<K> forSemiring(Semiring<K> semiring, String weightColumn) {
        return (source, ordinal, row) ->
                semiring.base(tuple(source, ordinal, row, weightColumn));
    }

    /**
     * {@return {@code row} as the neutral view a semiring reads} {@code columns()} builds
     * its map per call, which is why it is a method rather than a captured field: only
     * lineage asks for one.
     */
    private static BaseTuple tuple(String source, long ordinal, Row row, String weightColumn) {
        return new BaseTuple() {
            @Override
            public String source() {
                return source;
            }

            @Override
            public long ordinal() {
                return ordinal;
            }

            @Override
            public Optional<BigDecimal> weight() {
                if (weightColumn == null || row.schema().indexOf(weightColumn) < 0) {
                    return Optional.empty();
                }
                return row.get(weightColumn) instanceof NumberValue n
                        ? Optional.of(n.value())
                        : Optional.empty();
            }

            @Override
            public SortedMap<String, String> columns() {
                return capturedColumns(row);
            }
        };
    }

    /**
     * {@return the lineage annotator} Each base-tuple occurrence becomes a fresh
     * {@code ℕ[X]} variable named {@code <source>#<ordinal>}, carrying that occurrence's
     * column values — which is what makes a lineage variable addressable back to the row
     * that produced it, rather than merely nameable.
     */
    static BaseAnnotator<Polynomial> lineage() {
        return forSemiring(PolynomialSemiring.INSTANCE, null);
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

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
package com.darkcollective.relix.provenance;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A structured, machine-readable identity for one base-tuple occurrence — the
 * data a {@link ProvenanceVariable} carries so a consuming tool can pinpoint
 * <em>which row of which relation</em> a lineage variable stands for.
 *
 * <p>The bare {@code <source>#<ordinal>} label that a variable renders as is
 * convenient for humans but opaque to tooling: it names the producing leaf and a
 * per-run occurrence counter, nothing more. A {@code SourceRef} additionally
 * captures the occurrence's {@code columns} — the base tuple's column values at
 * lift time — so the variable becomes addressable: a consumer can match
 * {@code columns} against a key (or the full row) to locate the exact source
 * tuple.
 *
 * <p>The values are captured as their display strings (a {@code null} value marks
 * a SQL null), keeping this module free of the data-layer {@code Value} type. The
 * producing leaf is identified by {@code source} (a relation name for a true base
 * relation, else the opaque operator's label, e.g. {@code Aggregation}) and a
 * 1-based per-leaf occurrence {@code ordinal}; together they form the
 * {@link #canonicalName() canonical name} that identifies the variable.
 *
 * @param source  the producing leaf's label — a relation name or operator label;
 *                never {@code null} or blank
 * @param ordinal the 1-based occurrence index within that leaf
 * @param columns the base tuple's column → display-value map (a {@code null} value
 *                is a SQL null); copied defensively, never {@code null}
 */
public record SourceRef(String source, long ordinal, SortedMap<String, String> columns) {

    /**
     * Canonicalises the reference: validates {@code source} and defensively copies
     * {@code columns} into an unmodifiable sorted map (preserving null values, which
     * mark SQL nulls).
     *
     * @param source  the producing leaf's label; never {@code null} or blank
     * @param ordinal the 1-based occurrence index
     * @param columns the captured column values; never {@code null} (may be empty)
     */
    public SourceRef {
        Objects.requireNonNull(source, "source");
        if (source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        Objects.requireNonNull(columns, "columns");
        // TreeMap (not Map.copyOf) so SQL-null column values are retained.
        columns = Collections.unmodifiableSortedMap(new TreeMap<>(columns));
    }

    /**
     * {@return the canonical {@code <source>#<ordinal>} label for this occurrence}
     * This is the variable's identity — two references with the same source and
     * ordinal name the same variable, regardless of captured columns.
     */
    public String canonicalName() {
        return source + "#" + ordinal;
    }

    /**
     * {@return a detailed label, the {@link #canonicalName() canonical name}
     * followed by the captured columns} e.g. {@code Orders#1{id: 42, status: OPEN}}.
     * Falls back to the bare canonical name when no columns were captured.
     */
    public String detailedLabel() {
        if (columns.isEmpty()) {
            return canonicalName();
        }
        StringBuilder sb = new StringBuilder(canonicalName()).append('{');
        boolean first = true;
        for (Map.Entry<String, String> e : columns.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(e.getKey()).append(": ")
              .append(e.getValue() == null ? "null" : e.getValue());
        }
        return sb.append('}').toString();
    }
}

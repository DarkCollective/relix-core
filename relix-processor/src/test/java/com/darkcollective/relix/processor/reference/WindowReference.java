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
package com.darkcollective.relix.processor.reference;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/**
 * The window operators, implemented from {@code docs/reference} rather than from the
 * executor — the independent statement of what they mean.
 *
 * <h2>What an oracle is for, and what makes one worth having</h2>
 *
 * Recursion, the windows, PIVOT, TREE, COVER, ∀, ÷ and provenance have <b>no external
 * oracle at all</b>. No database computes them, so the pushdown agreement suites cannot
 * reach them, and their correctness rests entirely on expected rows somebody wrote out.
 * A worked example pasted from a run is a regression test: it says the answer has not
 * changed, never that it was right.
 *
 * <p>This is written from the manual's own wording — the trailing frame, the tie rules,
 * which row an offset reads, where the leftover rows of an {@code NTILE} go — so a
 * disagreement is a disagreement between the engine and its documentation, and either
 * one being wrong is worth knowing. It is deliberately the slowest possible reading:
 * materialise the partition, sort it, and walk it once per row.
 *
 * <p>It is not a second implementation to keep in step. It has no NULL-ordering rule, no
 * coercion, no streaming and no frames beyond the two the operator offers, because the
 * tests hand it data where none of those arise — every one of them is a question the
 * engine answers elsewhere and is tested elsewhere.
 */
final class WindowReference {

    /** The scale a relix average carries; the one arithmetic detail taken from the engine. */
    private static final int AVERAGE_SCALE = 10;

    private WindowReference() {
    }

    /** One input row: an identity, a partition key, and the value a window reads. */
    record Row(int id, String partition, Integer sortKey, Integer value) {}

    /**
     * {@code rows} grouped by partition, each sorted by its sort key — stably, so rows
     * the key cannot separate stay in input order, which is what the ranking family's
     * tie rule is defined against.
     */
    private static SequencedMap<String, List<Row>> partitions(List<Row> rows, boolean ascending) {
        SequencedMap<String, List<Row>> byKey = new LinkedHashMap<>();
        for (Row row : rows) {
            byKey.computeIfAbsent(row.partition(), _ -> new ArrayList<>()).add(row);
        }
        Comparator<Row> order = Comparator.comparing(Row::sortKey);
        byKey.values().forEach(part -> part.sort(ascending ? order : order.reversed()));
        return byKey;
    }

    // =========================================================================
    // Ranking — "partition, order, then number the rows"
    // =========================================================================

    /** {@code ROW_NUMBER()}: a unique 1-based integer per partition, ties in row order. */
    static Map<Integer, String> rowNumber(List<Row> rows, boolean ascending) {
        Map<Integer, String> out = new LinkedHashMap<>();
        partitions(rows, ascending).values().forEach(part -> {
            for (int i = 0; i < part.size(); i++) {
                out.put(part.get(i).id(), Integer.toString(i + 1));
            }
        });
        return out;
    }

    /** {@code RANK()}: tied rows share a rank and the next one skips the gap. */
    static Map<Integer, String> rank(List<Row> rows, boolean ascending) {
        return ranked(rows, ascending, true);
    }

    /** {@code DENSE_RANK()}: tied rows share a rank and the next one does not skip. */
    static Map<Integer, String> denseRank(List<Row> rows, boolean ascending) {
        return ranked(rows, ascending, false);
    }

    private static Map<Integer, String> ranked(List<Row> rows, boolean ascending, boolean skip) {
        Map<Integer, String> out = new LinkedHashMap<>();
        partitions(rows, ascending).values().forEach(part -> {
            int assigned = 0;
            Integer previousKey = null;
            int seen = 0;
            for (Row row : part) {
                seen++;
                if (previousKey == null || !previousKey.equals(row.sortKey())) {
                    assigned = skip ? seen : assigned + 1;
                    previousKey = row.sortKey();
                }
                out.put(row.id(), Integer.toString(assigned));
            }
        });
        return out;
    }

    /** {@code PERCENT_RANK()}: {@code (rank − 1) / (rows − 1)}, and 0 for one row. */
    static Map<Integer, String> percentRank(List<Row> rows, boolean ascending) {
        Map<Integer, String> ranks = rank(rows, ascending);
        Map<Integer, String> out = new LinkedHashMap<>();
        partitions(rows, ascending).values().forEach(part -> {
            int n = part.size();
            for (Row row : part) {
                BigDecimal value = n == 1 ? BigDecimal.ZERO
                        : new BigDecimal(Integer.parseInt(ranks.get(row.id())) - 1)
                                .divide(new BigDecimal(n - 1), AVERAGE_SCALE, RoundingMode.HALF_UP);
                out.put(row.id(), value.stripTrailingZeros().toPlainString());
            }
        });
        return out;
    }

    /** {@code NTILE(n)}: even buckets, the leftover rows going to the lower ones. */
    static Map<Integer, String> ntile(List<Row> rows, boolean ascending, int buckets) {
        Map<Integer, String> out = new LinkedHashMap<>();
        partitions(rows, ascending).values().forEach(part -> {
            int n = part.size();
            int base = n / buckets;
            int larger = n % buckets;   // the first `larger` buckets take one row more
            int at = 0;
            for (int bucket = 1; bucket <= buckets; bucket++) {
                int size = base + (bucket <= larger ? 1 : 0);
                for (int i = 0; i < size && at < n; i++, at++) {
                    out.put(part.get(at).id(), Integer.toString(bucket));
                }
            }
        });
        return out;
    }

    // =========================================================================
    // Offset — "read a value from another row of the same ordered partition"
    // =========================================================================

    /** {@code LAG(value, n)}: the value {@code n} rows before, else NULL. */
    static Map<Integer, String> lag(List<Row> rows, boolean ascending, int offset) {
        return offsetBy(rows, ascending, -offset);
    }

    /** {@code LEAD(value, n)}: the value {@code n} rows after, else NULL. */
    static Map<Integer, String> lead(List<Row> rows, boolean ascending, int offset) {
        return offsetBy(rows, ascending, offset);
    }

    private static Map<Integer, String> offsetBy(List<Row> rows, boolean ascending, int delta) {
        Map<Integer, String> out = new LinkedHashMap<>();
        partitions(rows, ascending).values().forEach(part -> {
            for (int i = 0; i < part.size(); i++) {
                int from = i + delta;
                out.put(part.get(i).id(),
                        from >= 0 && from < part.size() ? render(part.get(from).value()) : "NULL");
            }
        });
        return out;
    }

    /** {@code FIRST_VALUE(value)}: the first row's value, for every row. */
    static Map<Integer, String> firstValue(List<Row> rows, boolean ascending) {
        return edgeValue(rows, ascending, true);
    }

    /** {@code LAST_VALUE(value)}: the last row's value, for every row. */
    static Map<Integer, String> lastValue(List<Row> rows, boolean ascending) {
        return edgeValue(rows, ascending, false);
    }

    private static Map<Integer, String> edgeValue(List<Row> rows, boolean ascending, boolean first) {
        Map<Integer, String> out = new LinkedHashMap<>();
        partitions(rows, ascending).values().forEach(part -> {
            String edge = render(first ? part.getFirst().value() : part.getLast().value());
            part.forEach(row -> out.put(row.id(), edge));
        });
        return out;
    }

    // =========================================================================
    // Rolling — "aggregate over the rows in this row's frame"
    // =========================================================================

    /**
     * A rolling aggregate. {@code frame} is the trailing row count, or 0 for the
     * cumulative frame — the partition up to and including this row.
     *
     * <p>NULLs are skipped, which is the rule every aggregate follows: COUNT counts
     * non-NULL values, and the other four answer NULL for a frame holding none.
     */
    static Map<Integer, String> rolling(List<Row> rows, boolean ascending,
                                        String aggregate, int frame) {
        Map<Integer, String> out = new LinkedHashMap<>();
        partitions(rows, ascending).values().forEach(part -> {
            for (int i = 0; i < part.size(); i++) {
                int from = frame == 0 ? 0 : Math.max(0, i - frame + 1);
                List<Integer> values = new ArrayList<>();
                for (int j = from; j <= i; j++) {
                    if (part.get(j).value() != null) {
                        values.add(part.get(j).value());
                    }
                }
                out.put(part.get(i).id(), reduce(aggregate, values));
            }
        });
        return out;
    }

    private static String reduce(String aggregate, List<Integer> values) {
        if (aggregate.equals("COUNT")) {
            return Integer.toString(values.size());
        }
        if (values.isEmpty()) {
            return "NULL";
        }
        return switch (aggregate) {
            case "SUM" -> Integer.toString(values.stream().mapToInt(Integer::intValue).sum());
            case "MIN" -> Integer.toString(values.stream().mapToInt(Integer::intValue).min().orElseThrow());
            case "MAX" -> Integer.toString(values.stream().mapToInt(Integer::intValue).max().orElseThrow());
            case "AVG" -> new BigDecimal(values.stream().mapToInt(Integer::intValue).sum())
                    .divide(new BigDecimal(values.size()), AVERAGE_SCALE, RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString();
            default -> throw new IllegalArgumentException("no such aggregate: " + aggregate);
        };
    }

    private static String render(Integer value) {
        return value == null ? "NULL" : Integer.toString(value);
    }
}

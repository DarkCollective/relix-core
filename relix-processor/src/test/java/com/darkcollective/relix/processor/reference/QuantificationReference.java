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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Universal quantification, stated the way the manual states it.
 *
 * <p>"∀_keys:P(R) groups R by the key tuple and emits a key tuple iff all rows in its
 * group satisfy P. NULL semantics are strict: a row whose predicate is UNKNOWN
 * disqualifies its group."
 *
 * <p>Two sentences, and the second is the whole difficulty. A comparison against a
 * missing value is neither true nor false, and "all rows satisfy P" has to decide what to
 * do with a row that has not answered — so the truth value is written out as three cases
 * rather than as a boolean, because a boolean is exactly the representation that cannot
 * hold the distinction.
 *
 * <p>∀ has an SQL spelling and the agreement suites do reach a keyed one over a single
 * connection. What they cannot reach is the in-engine path, the no-key form, or any of
 * this when a value is missing — and a group disqualified by an unknown is the case where
 * being right and being nearly right return different rows.
 */
final class QuantificationReference {

    private QuantificationReference() {
    }

    /** What a comparison answers when one side may be absent. */
    enum Truth {
        TRUE, FALSE, UNKNOWN
    }

    /**
     * {@code value = required}, three-valued: a missing value answers neither way.
     *
     * @param value    the row's value, or null if the row has none
     * @param required what it is compared against; must not be null
     * @return the comparison's truth value
     */
    static Truth equals(String value, String required) {
        if (value == null) {
            return Truth.UNKNOWN;
        }
        return value.equals(required) ? Truth.TRUE : Truth.FALSE;
    }

    /**
     * The keys whose every row answers TRUE.
     *
     * <p>Only TRUE qualifies: a group holding one FALSE fails, and so does a group holding
     * one UNKNOWN, which is what "strict" means and is the half an ordinary
     * {@code all-minus-any-that-fail} reading gets wrong.
     *
     * @param rows     {@code (key, value)} pairs, the value null where the row has none
     * @param required the value every row must carry
     * @return the qualifying keys, without duplicates, in first-seen order
     */
    static List<String> forAll(List<String[]> rows, String required) {
        Map<String, Boolean> qualifying = new LinkedHashMap<>();
        for (String[] row : rows) {
            boolean satisfied = equals(row[1], required) == Truth.TRUE;
            qualifying.merge(row[0], satisfied, (before, now) -> before && now);
        }
        List<String> kept = new ArrayList<>();
        qualifying.forEach((key, all) -> {
            if (all) {
                kept.add(key);
            }
        });
        return kept;
    }

    /**
     * The no-key form: does every row answer TRUE?
     *
     * <p>Vacuously true on empty input — there is no row that fails, which is the same
     * rule the keyed form applies to a group and not a special case of it.
     *
     * @param values   each row's value, null where the row has none
     * @param required the value every row must carry
     * @return whether every row satisfies the comparison
     */
    static boolean everyRow(List<String> values, String required) {
        return values.stream().allMatch(value -> equals(value, required) == Truth.TRUE);
    }

    /** Every key the relation mentions, in first-seen order — what ∀ chooses from. */
    static Set<String> keys(List<String[]> rows) {
        Set<String> all = new LinkedHashSet<>();
        rows.forEach(row -> all.add(row[0]));
        return all;
    }
}

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Relational division, stated the way the manual states it.
 *
 * <p>"For R(A,B) ÷ S(B), division returns the values of A that are paired with every
 * value of B present in S. Formally the largest set Q such that Q × S ⊆ R."
 *
 * <p>That is the whole implementation below — enumerate the candidate A values, keep the
 * ones for which every row of S appears paired. No index, no set algebra, no early exit:
 * an oracle is worth having only while it is obviously the definition, and the operator it
 * checks is the one allowed to be clever.
 *
 * <p>Division has no external oracle of any kind. No SQL backend has the operator, so the
 * pushdown suites cannot reach it, and what asserts it otherwise is rows somebody wrote
 * out — which says the answer stopped changing, not that it started right.
 */
final class DivisionReference {

    private DivisionReference() {
    }

    /**
     * {@code dividend ÷ divisor}, as the definition reads.
     *
     * @param dividend pairs of (a, b); must not be null
     * @param divisor  the b values every kept a must be paired with; must not be null
     * @return the a values, without duplicates, in first-seen order
     */
    static List<String> divide(List<String[]> dividend, List<String> divisor) {
        Set<String> candidates = new LinkedHashSet<>();
        dividend.forEach(pair -> candidates.add(pair[0]));

        List<String> kept = new ArrayList<>();
        for (String a : candidates) {
            boolean pairedWithEvery = true;
            for (String b : divisor) {
                boolean found = false;
                for (String[] pair : dividend) {
                    if (pair[0].equals(a) && pair[1].equals(b)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    pairedWithEvery = false;
                    break;
                }
            }
            if (pairedWithEvery) {
                kept.add(a);
            }
        }
        return kept;
    }
}

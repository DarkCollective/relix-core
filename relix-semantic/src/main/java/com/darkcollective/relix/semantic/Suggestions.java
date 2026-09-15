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
package com.darkcollective.relix.semantic;

import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ColumnProvenance;
import com.darkcollective.relix.symbol.Schema;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Edit-distance "did you mean?" suggestions for unknown names (relations,
 * functions, columns). A pure utility with no engine dependencies, used to
 * enrich "unknown ..." diagnostics so a typo points the user at the name they
 * most likely meant.
 *
 * <p>Matching is case-insensitive and tolerant of ordinary typos (one or two
 * single-character edits, including adjacent transpositions). An exact match is
 * never suggested — it would not have produced an error in the first place.
 */
final class Suggestions {

    private Suggestions() {
    }

    /**
     * Returns the candidate closest to {@code target} within an edit-distance
     * threshold scaled to the target's length (1 for short names, 2 otherwise),
     * or empty when no candidate is close enough. Matching is case-insensitive;
     * the candidate's original spelling is returned. Ties are broken
     * deterministically by case-insensitive ordering so output is reproducible.
     *
     * @param target     the unknown name the user typed
     * @param candidates the known names in scope
     * @return the closest reasonable candidate, or empty
     */
    static Optional<String> closest(String target, Collection<String> candidates) {
        if (target == null || target.isEmpty() || candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        String needle = target.toLowerCase(Locale.ROOT);
        int maxDistance = target.length() <= 3 ? 1 : 2;
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            int d = osaDistance(needle, candidate.toLowerCase(Locale.ROOT));
            if (d == 0 || d > maxDistance) {
                continue;
            }
            if (d < bestDistance
                    || (d == bestDistance && best != null && candidate.compareToIgnoreCase(best) < 0)) {
                best = candidate;
                bestDistance = d;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Returns {@code " — did you mean 'X'?"} for the closest candidate, or an
     * empty string when no candidate is close enough. Designed to be appended
     * directly to an "unknown ..." diagnostic message.
     *
     * @param target     the unknown name the user typed
     * @param candidates the known names in scope
     * @return a suggestion suffix, or {@code ""}
     */
    static String didYouMean(String target, Collection<String> candidates) {
        return closest(target, candidates)
                .map(s -> " — did you mean '" + s + "'?")
                .orElse("");
    }

    /**
     * Returns a column "did you mean?" suffix for a missing column reference,
     * falling back to a short {@code " (available: …)"} listing of the known
     * columns when no close match exists and the column set is known (a closed
     * schema). Designed to be appended directly to a "column '…' not found"
     * diagnostic so a REPL user is pointed either at the column they likely meant
     * or at the columns actually in scope.
     *
     * @param target      the unknown column name the user typed (qualifier stripped)
     * @param columnNames the columns in scope, in schema order
     * @param schemaKnown whether the column set is fully known (a closed schema);
     *                    an open/schema-on-read input lists nothing
     * @return a suggestion-or-availability suffix, or {@code ""}
     */
    static String columnHint(String target, Collection<String> columnNames, boolean schemaKnown) {
        String suggestion = didYouMean(target, columnNames);
        if (!suggestion.isEmpty()) {
            return suggestion;
        }
        if (schemaKnown && columnNames != null && !columnNames.isEmpty()) {
            int shown = Math.min(columnNames.size(), MAX_AVAILABLE_LISTED);
            String list = columnNames.stream().limit(shown).collect(java.util.stream.Collectors.joining(", "));
            String ellipsis = columnNames.size() > shown ? ", …" : "";
            return " (available: " + list + ellipsis + ")";
        }
        return "";
    }

    /**
     * Returns a "did you mean?" / availability suffix for a stale relation
     * qualifier in a qualified reference {@code qualifier.column} that did not
     * resolve. Suggests the closest in-scope source relation, else
     * lists the source relations actually present in the schema.
     *
     * @param qualifier the relation qualifier the user typed
     * @param schema    the schema whose columns carry source-relation provenance
     * @return a suggestion-or-availability suffix, or {@code ""}
     */
    static String qualifierHint(String qualifier, Schema schema) {
        Set<String> relations = new LinkedHashSet<>();
        for (ColumnDefinition col : schema.columns()) {
            ColumnProvenance p = col.provenance();
            if (p != null) {
                relations.add(p.relation());
            }
        }
        if (relations.isEmpty()) {
            return "";
        }
        String suggestion = didYouMean(qualifier, relations);
        if (!suggestion.isEmpty()) {
            return suggestion;
        }
        return " (relations in scope: " + String.join(", ", relations) + ")";
    }

    /** Upper bound on columns listed in a {@link #columnHint} availability fallback. */
    private static final int MAX_AVAILABLE_LISTED = 12;

    /**
     * Optimal string alignment (restricted Damerau–Levenshtein) distance: the
     * minimum number of single-character insertions, deletions, substitutions,
     * and transpositions of adjacent characters to turn {@code a} into {@code b}.
     * Suitable for catching the ordinary typos a learner makes while typing.
     *
     * @param a the first string
     * @param b the second string
     * @return the edit distance
     */
    static int osaDistance(String a, String b) {
        int n = a.length();
        int m = b.length();
        if (n == 0) {
            return m;
        }
        if (m == 0) {
            return n;
        }
        int[] prev2 = new int[m + 1];
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            char ai = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                char bj = b.charAt(j - 1);
                int cost = (ai == bj) ? 0 : 1;
                int best = Math.min(Math.min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost);
                if (i > 1 && j > 1 && ai == b.charAt(j - 2) && a.charAt(i - 2) == bj) {
                    best = Math.min(best, prev2[j - 2] + 1);
                }
                curr[j] = best;
            }
            int[] tmp = prev2;
            prev2 = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[m];
    }
}

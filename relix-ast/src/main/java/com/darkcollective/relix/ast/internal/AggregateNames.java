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
package com.darkcollective.relix.ast.internal;

import com.darkcollective.relix.ast.AggregateOperator;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The message for a call in GROUP's list to a name that is no aggregate, such as SQL's
 * {@code MODE(x)} or {@code BOOL_AND(b)}.
 *
 * <p>The parser reports it where the call cannot be a grouping key, and analysis where
 * it was read as one but names no function either. Both say the same thing: the name,
 * the aggregate it is a typo of when one is that close, and every aggregate there is.
 */
public final class AggregateNames {

    private static final List<String> NAMES =
            Arrays.stream(AggregateOperator.values()).map(Enum::name).toList();

    private AggregateNames() {
    }

    /** {@code Unknown aggregate: 'name'}, a did-you-mean when one is close, and the list of aggregates. */
    public static String unknown(String name) {
        String closest = closest(name);
        return "Unknown aggregate: '" + name + "'"
                + (closest == null ? "" : " — did you mean '" + closest + "'?")
                + "; GROUP's aggregates are " + String.join(", ", NAMES.subList(0, NAMES.size() - 1))
                + " and " + NAMES.get(NAMES.size() - 1);
    }

    /**
     * The aggregate within one edit of {@code name} (two for a name longer than three
     * letters), case aside, as the unknown-function error suggests; null when none is.
     * The first in declaration order wins a tie. {@code name} is no aggregate's, so
     * none is at distance 0.
     */
    private static String closest(String name) {
        String needle = name.toUpperCase(Locale.ROOT);
        int limit = needle.length() <= 3 ? 1 : 2;
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : NAMES) {
            int d = distance(needle, candidate);
            if (d <= limit && d < bestDistance) {
                best = candidate;
                bestDistance = d;
            }
        }
        return best;
    }

    /** Edit distance with adjacent transpositions: optimal string alignment. */
    private static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }
}

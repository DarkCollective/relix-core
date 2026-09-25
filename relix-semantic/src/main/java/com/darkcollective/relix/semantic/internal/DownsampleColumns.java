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
package com.darkcollective.relix.semantic.internal;

import com.darkcollective.relix.ast.ConsolidationFunction;
import com.darkcollective.relix.ast.DownsampleNode;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Which columns a {@code DOWNSAMPLE} consolidates, and what each one is called.
 *
 * <p>Two phases need this answer and they must give the same one: schema inference
 * builds the operator's output heading from it, and the executor fills that heading in.
 * When the two decided separately, a column either phase admitted and the other did not
 * became a row of the wrong width. So the rule is stated once, here, and both read it.
 *
 * <h2>The rule</h2>
 * <ul>
 *   <li>The grouping keys and the {@code bucket} timestamp come first and are not
 *       consolidations — they identify the bucket rather than summarising it.</li>
 *   <li>{@link ConsolidationFunction#COUNT} summarises the <em>rows</em>, so it yields a
 *       single {@code count} column reducing no input column at all.</li>
 *   <li>Every other function yields one {@code <fn>_<column>} column per eligible input
 *       column: the numeric ones for {@code AVG}/{@code SUM}, whose arithmetic is
 *       defined over nothing else, and every scalar one for {@code MIN}/{@code MAX},
 *       which compare rather than compute and rank a date or a name as readily as a
 *       number. A nested column is never consolidated — an array has no position in the
 *       engine's value order.</li>
 * </ul>
 */
public final class DownsampleColumns {

    /** The output column a bucket's timestamp lands in. */
    public static final String BUCKET_COLUMN = "bucket";

    /** The output column {@link ConsolidationFunction#COUNT} produces. */
    public static final String COUNT_COLUMN = "count";

    private DownsampleColumns() {
    }

    /**
     * One consolidated output column, and the input column reduced into it.
     *
     * @param inputColumn the input column being reduced, or empty when the reduction is
     *                    over the rows themselves — which is what {@code COUNT} does
     * @param output      the column this reduction contributes to the output heading
     */
    public record Consolidation(Optional<String> inputColumn, ColumnDefinition output) {
    }

    /**
     * The consolidated columns a {@code DOWNSAMPLE} emits, in output order.
     *
     * <p>These follow the grouping keys and the {@code bucket} column, which together
     * make up the whole output heading.
     *
     * <p>Taken as loose parts rather than as a {@link DownsampleNode} because the
     * executor asks the same question of a physical plan node, which carries these
     * fields and not the logical node they came from.
     *
     * @param function        the consolidation; must not be null
     * @param groupingKeys    the bucket's grouping keys, which are not consolidated
     * @param timestampColumn the column bucketed on, which is not consolidated either
     * @param input           the schema of the operator's input; must not be null
     * @return the consolidations, in output order; never null, possibly empty when no
     *         input column is eligible
     */
    public static List<Consolidation> of(ConsolidationFunction function,
                                         List<String> groupingKeys,
                                         String timestampColumn,
                                         Schema input) {
        if (function.countsRows()) {
            return List.of(new Consolidation(Optional.empty(),
                    new ColumnDefinition(COUNT_COLUMN, ScalarType.NUMBER)));
        }
        String prefix = function.name().toLowerCase(Locale.ROOT) + "_";
        Set<String> excluded = new HashSet<>(groupingKeys);
        excluded.add(timestampColumn);

        List<Consolidation> consolidations = new ArrayList<>();
        for (ColumnDefinition column : input.columns()) {
            if (excluded.contains(column.name()) || !eligible(function, column)) {
                continue;
            }
            // MIN/MAX return whatever they were given; AVG/SUM always return a number.
            ScalarType produced = function.numericOnly()
                    ? ScalarType.NUMBER : (ScalarType) column.type();
            consolidations.add(new Consolidation(Optional.of(column.name()),
                    new ColumnDefinition(prefix + column.name(), produced)));
        }
        return List.copyOf(consolidations);
    }

    /** Whether {@code column} is of a type {@code function} is defined over. */
    private static boolean eligible(ConsolidationFunction function, ColumnDefinition column) {
        if (function.numericOnly()) {
            return column.type() == ScalarType.NUMBER;
        }
        return column.type() instanceof ScalarType;
    }
}

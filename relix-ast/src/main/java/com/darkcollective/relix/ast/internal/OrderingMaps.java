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

import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.Ordering;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.SortSpecification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Carries an {@link Ordering} through an operator that renames or drops columns
 * without moving rows: a {@code π} and a column-renaming {@code ρ}.
 *
 * <p>Both deliver the longest prefix of their input's ordering whose every key
 * survives into the output, rewritten in terms of the output's column names. The
 * prefix stops at the first key that does not survive: rows sorted by
 * {@code (a, b, c)} are still sorted by {@code a}, but with {@code b} gone they are not
 * sorted by {@code (a, c)}.
 *
 * <p>The logical {@code OrderDeriver} and the physical {@code Project} and
 * {@code Rename} nodes share these, so the optimizer and the plan agree on what a
 * projection keeps.
 */
public final class OrderingMaps {

    private OrderingMaps() {}

    /**
     * The ordering a projection delivers over input delivering {@code input}.
     *
     * <p>A key survives when some projected column computes it: a bare column projected
     * as itself or under an alias, or an expression projected as an equal expression
     * ({@code price * qty → total} carries the key {@code price * qty} as {@code total}).
     * Expressions compare as written, source locations aside, as {@link Ordering} does.
     *
     * @param input      the ordering of the projection's input; must not be null
     * @param attributes the projected columns, in output order; must not be null
     * @param outputName the output column name of the projected column at an index, or
     *                   empty when it is not known
     * @return the delivered ordering; never null, {@link Ordering#none()} when the
     *         leading key does not survive
     */
    public static Ordering throughProjection(Ordering input, List<ProjectedAttribute> attributes,
                                             IntFunction<Optional<String>> outputName) {
        List<SortSpecification> kept = new ArrayList<>();
        for (SortSpecification key : input.keys()) {
            Optional<String> name = Optional.empty();
            for (int i = 0; i < attributes.size() && name.isEmpty(); i++) {
                if (computes(attributes.get(i).expression(), key.expression())) {
                    name = outputName.apply(i);
                }
            }
            if (name.isEmpty()) {
                break;
            }
            kept.add(key.withExpression(new AttributeOperand(name.get())));
        }
        return Ordering.of(kept);
    }

    /**
     * The ordering a column rename delivers over input delivering {@code input}.
     *
     * <p>A key over a bare column survives under the name {@code rename} gives that
     * column. A key that is an expression does not survive, since the columns it reads
     * may have been renamed under it.
     *
     * @param input  the ordering of the rename's input; must not be null
     * @param rename the output name of an input column, or empty when it is not known
     * @return the delivered ordering; never null
     */
    public static Ordering renamed(Ordering input, Function<String, Optional<String>> rename) {
        List<SortSpecification> kept = new ArrayList<>();
        for (SortSpecification key : input.keys()) {
            Optional<String> name = key.columnName().flatMap(rename);
            if (name.isEmpty()) {
                break;
            }
            kept.add(key.withExpression(new AttributeOperand(name.get())));
        }
        return Ordering.of(kept);
    }

    /**
     * The output name a projected column gets from the column alone: its alias, or the
     * name of the bare column it projects. Empty for an unaliased expression, whose name
     * only the schema knows.
     *
     * @param attribute the projected column; must not be null
     * @return the output column name, or empty
     */
    public static Optional<String> nameOf(ProjectedAttribute attribute) {
        if (attribute.alias().isPresent()) {
            return attribute.alias();
        }
        return attribute.expression() instanceof AttributeOperand a
                ? Optional.of(a.unqualifiedName())
                : Optional.empty();
    }

    /** Whether the projected {@code expression} computes the sort key {@code key}. */
    private static boolean computes(Operand expression, Operand key) {
        if (expression instanceof AttributeOperand e && key instanceof AttributeOperand k) {
            // A qualified name on one side only still names the same column: the
            // analyser has already refused an unqualified name that is ambiguous.
            boolean bothQualified = !e.name().equals(e.unqualifiedName())
                    && !k.name().equals(k.unqualifiedName());
            String left = bothQualified ? e.name() : e.unqualifiedName();
            String right = bothQualified ? k.name() : k.unqualifiedName();
            return left.toLowerCase(Locale.ROOT).equals(right.toLowerCase(Locale.ROOT));
        }
        return expression.prettyPrint().equals(key.prettyPrint());
    }
}

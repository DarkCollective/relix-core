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
package com.darkcollective.relix.plan.internal;

import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.function.Blocking;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.plan.PhysicalNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Recognises a nested-loop inner join whose condition bounds a similarity score, and
 * plans it as a {@link PhysicalNode.SimilarityJoin}.
 *
 * <p>The shape is a conjunct {@code F(a, b) op t} (or its mirror {@code t op F(a, b)}),
 * where {@code a} and {@code b} are columns of opposite inputs, {@code t} is a number
 * literal and {@code op} is one of {@code >=}, {@code >}, {@code <=}, {@code <} or
 * {@code =}. Which functions qualify is not known here: the function's own
 * {@link ScalarFunction#blocking} decides, from the catalogue the tree was analysed
 * against, so a library's function joins this way on the same terms as a built-in.
 *
 * <p>The first conjunct that qualifies is used. The others stay in the join's condition,
 * which confirms every candidate pair, so choosing one never changes the answer.
 */
final class SimilarityJoinPlanning {

    private SimilarityJoinPlanning() {
    }

    /**
     * The similarity join that can replace {@code join}, if its condition has the shape.
     *
     * @param join      a planned join; only an inner nested loop is considered
     * @param functions the catalogue the tree was analysed against
     * @return the replacement, or empty to keep {@code join}
     */
    static Optional<PhysicalNode.SimilarityJoin> plan(PhysicalNode.Join join,
                                                      FunctionCatalog functions) {
        if (join.kind() != PhysicalNode.JoinKind.INNER
                || join.algorithm() != PhysicalNode.JoinAlgorithm.NESTED_LOOP
                || join.condition().isEmpty()) {
            return Optional.empty();
        }
        return find(join.condition().get(), join, functions);
    }

    private static Optional<PhysicalNode.SimilarityJoin> find(Predicate p,
                                                              PhysicalNode.Join join,
                                                              FunctionCatalog functions) {
        return switch (p) {
            case AndPredicate a -> find(a.left(), join, functions)
                    .or(() -> find(a.right(), join, functions));
            case ComparisonPredicate c -> comparison(c, join, functions);
            default -> Optional.empty();
        };
    }

    private static Optional<PhysicalNode.SimilarityJoin> comparison(ComparisonPredicate c,
                                                                    PhysicalNode.Join join,
                                                                    FunctionCatalog functions) {
        FunctionCall call;
        NumberOperand limit;
        boolean mirrored;
        if (c.left() instanceof FunctionCall f && c.right() instanceof NumberOperand n) {
            call = f;
            limit = n;
            mirrored = false;
        } else if (c.left() instanceof NumberOperand n && c.right() instanceof FunctionCall f) {
            call = f;
            limit = n;
            mirrored = true;
        } else {
            return Optional.empty();
        }
        Optional<Columns> columns = columns(call.arguments(), join);
        Optional<ScalarFunction> function = functions.scalar(call.functionName());
        if (columns.isEmpty() || function.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal threshold;
        try {
            threshold = new BigDecimal(limit.value());
        } catch (NumberFormatException notADecimal) {
            return Optional.empty();
        }
        for (Blocking.Bound bound : bounds(c, mirrored)) {
            if (function.get().blocking(bound, threshold).isPresent()) {
                return Optional.of(PhysicalNode.SimilarityJoin.of(join,
                        function.get().name(), columns.get().left(), columns.get().right(),
                        bound, threshold));
            }
        }
        return Optional.empty();
    }

    /** The column of each input that the call reads, when it reads one of each. */
    private record Columns(int left, int right) {
    }

    private static Optional<Columns> columns(List<Operand> arguments, PhysicalNode.Join join) {
        if (arguments.size() != 2
                || !(arguments.get(0) instanceof AttributeOperand a)
                || !(arguments.get(1) instanceof AttributeOperand b)) {
            return Optional.empty();
        }
        JoinPlanning.Side first = JoinPlanning.sideOf(a.name(), join.left().schema(),
                join.right().schema(), join.leftRelations(), join.rightRelations());
        JoinPlanning.Side second = JoinPlanning.sideOf(b.name(), join.left().schema(),
                join.right().schema(), join.leftRelations(), join.rightRelations());
        if (first == null || second == null || first.left() == second.left()) {
            return Optional.empty();
        }
        return Optional.of(first.left()
                ? new Columns(first.index(), second.index())
                : new Columns(second.index(), first.index()));
    }

    /**
     * The bounds a comparison implies, read with the call on the left. An equality
     * implies both a lower and an upper bound, and either one may be the one the
     * function can index.
     */
    private static List<Blocking.Bound> bounds(ComparisonPredicate c, boolean mirrored) {
        return switch (c.operator()) {
            case GREATER_EQUAL -> List.of(mirrored ? Blocking.Bound.AT_MOST : Blocking.Bound.AT_LEAST);
            case GREATER -> List.of(mirrored ? Blocking.Bound.BELOW : Blocking.Bound.ABOVE);
            case LESS_EQUAL -> List.of(mirrored ? Blocking.Bound.AT_LEAST : Blocking.Bound.AT_MOST);
            case LESS -> List.of(mirrored ? Blocking.Bound.ABOVE : Blocking.Bound.BELOW);
            case EQUAL -> List.of(Blocking.Bound.AT_LEAST, Blocking.Bound.AT_MOST);
            case NOT_EQUAL -> List.of();
        };
    }
}

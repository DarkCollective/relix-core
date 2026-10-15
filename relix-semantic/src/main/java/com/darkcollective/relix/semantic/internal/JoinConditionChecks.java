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

import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.internal.AttributeNames;
import com.darkcollective.relix.ast.internal.OperandWalker;
import com.darkcollective.relix.ast.internal.Predicates;
import com.darkcollective.relix.symbol.Schema;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Finds a join condition that never relates its two inputs, the mark of a typo such as
 * {@code Orders.customer_id = Orders.customer_id} where
 * {@code Customers.customer_id = Orders.customer_id} was meant.
 *
 * <p>Such a condition analyses clean and degenerates quietly: a join or an outer join
 * becomes a cross product, and a semi- or anti-join becomes all or nothing on whether
 * the right side has a row. These are warnings rather than errors, since a one-sided
 * condition is legitimate ({@code Customers ⋈ Customers.customer_id = 1 Orders} is a
 * filtered cross product someone may mean).
 *
 * <p>A reference is placed on a side only when both headings are declared and say so
 * without doubt; one either side could own makes the check say nothing, so a warning
 * is never a guess.
 */
final class JoinConditionChecks {

    private JoinConditionChecks() {}

    private enum Side { LEFT, RIGHT, UNKNOWN }

    /**
     * The warnings for {@code condition} joining {@code left} to {@code right}.
     *
     * @param left        the left input
     * @param right       the right input
     * @param leftSchema  the left input's heading
     * @param rightSchema the right input's heading
     * @param condition   the join condition, already found valid
     * @param opName      the operator as messages name it, such as {@code Anti-join ▷}
     * @return the warning messages, in source order; empty when the condition relates
     *         the two inputs or the headings cannot tell
     */
    static List<String> warnings(RelNode left, RelNode right, Schema leftSchema, Schema rightSchema,
                                 Predicate condition, String opName) {
        if (leftSchema.isOpen() || rightSchema.isOpen()
                || !leftSchema.hasProvenance() || !rightSchema.hasProvenance()) {
            return List.of();
        }
        String leftName = displayName(left).orElse("the left input");
        String rightName = displayName(right).orElse("the right input");

        Set<Side> referenced = EnumSet.noneOf(Side.class);
        boolean relates = false;
        List<AttributeOperand> references = new ArrayList<>();
        List<ComparisonPredicate> selfComparisons = new ArrayList<>();
        for (Predicate conjunct : Predicates.conjuncts(condition)) {
            Set<Side> sides = EnumSet.noneOf(Side.class);
            OperandWalker.walk(conjunct, a -> {
                references.add(a);
                sides.add(sideOf(a.name(), leftSchema, rightSchema));
            }, f -> { });
            referenced.addAll(sides);
            relates |= sides.contains(Side.UNKNOWN)
                    || (sides.contains(Side.LEFT) && sides.contains(Side.RIGHT));
            if (conjunct instanceof ComparisonPredicate c
                    && c.left() instanceof AttributeOperand l && c.right() instanceof AttributeOperand r
                    && l.name().equalsIgnoreCase(r.name())) {
                selfComparisons.add(c);
            }
        }
        if (references.isEmpty()) {
            return List.of();   // a constant condition says what it means
        }

        List<String> warnings = new ArrayList<>();
        for (ComparisonPredicate c : selfComparisons) {
            AttributeOperand column = (AttributeOperand) c.left();
            Side side = sideOf(column.name(), leftSchema, rightSchema);
            warnings.add(opName + ": " + column.name() + " " + c.operator().symbol() + " "
                    + ((AttributeOperand) c.right()).name()
                    + " compares a column with itself, so it says nothing about how a row of "
                    + leftName + " meets a row of " + rightName
                    + suggestion(side, column, leftName, rightName, leftSchema, rightSchema));
        }
        if (relates || !warnings.isEmpty()) {
            return warnings;
        }
        if (!referenced.contains(Side.LEFT) || !referenced.contains(Side.RIGHT)) {
            boolean neverLeft = !referenced.contains(Side.LEFT);
            String missing = neverLeft ? leftName : rightName;
            Side present = neverLeft ? Side.RIGHT : Side.LEFT;
            String hint = references.stream()
                    .filter(a -> sideOf(a.name(), leftSchema, rightSchema) == present)
                    .map(a -> suggestion(present, a, leftName, rightName, leftSchema, rightSchema))
                    .filter(s -> !s.isEmpty())
                    .findFirst().orElse("");
            warnings.add(opName + ": the condition never refers to " + missing
                    + ", so it compares no row of " + leftName + " with any row of " + rightName
                    + hint);
        } else {
            warnings.add(opName + ": no part of the condition relates " + leftName + " to "
                    + rightName + ": each conjunct refers to one side only, so every row of "
                    + leftName + " is paired with every row of " + rightName + " that passes it");
        }
        return warnings;
    }

    /**
     * {@code "; did you mean L.c = R.c?"} when the side {@code column} is not on declares
     * a column of the same name, else empty.
     */
    private static String suggestion(Side side, AttributeOperand column, String leftName, String rightName,
                                     Schema leftSchema, Schema rightSchema) {
        String bare = column.unqualifiedName();
        if (side == Side.UNKNOWN) {
            // Only a bare name both inputs carry gets here: one neither carries is an
            // error, and so is a qualified name both claim. It reads the left column on
            // both sides, so dept_id = dept_id means Users.dept_id = Users.dept_id.
            return !leftName.startsWith("the ") && !rightName.startsWith("the ")
                    ? "; did you mean " + leftName + "." + bare + " = " + rightName + "." + bare + "?"
                    : "";
        }
        Schema other = side == Side.LEFT ? rightSchema : leftSchema;
        String otherName = side == Side.LEFT ? rightName : leftName;
        if (!other.declares(bare) || otherName.startsWith("the ")) {
            return "";
        }
        String counterpart = otherName + "." + other.column(bare).orElseThrow().name();
        return side == Side.LEFT
                ? "; did you mean " + column.name() + " = " + counterpart + "?"
                : "; did you mean " + counterpart + " = " + column.name() + "?";
    }

    /** The side of the join {@code reference} names, or UNKNOWN when it is not certain. */
    private static Side sideOf(String reference, Schema left, Schema right) {
        String qualifier = AttributeNames.qualifierOf(reference);
        String bare = AttributeNames.stripQualifier(reference);
        boolean inLeft;
        boolean inRight;
        if (qualifier != null) {
            inLeft = !left.qualifiedIndices(qualifier, bare).isEmpty();
            inRight = !right.qualifiedIndices(qualifier, bare).isEmpty();
        } else {
            inLeft = left.declares(bare);
            inRight = right.declares(bare);
        }
        if (inLeft == inRight) {
            return Side.UNKNOWN;
        }
        return inLeft ? Side.LEFT : Side.RIGHT;
    }

    /** The name an input answers to, when it answers to exactly one. */
    private static Optional<String> displayName(RelNode node) {
        return switch (node) {
            case RelationNode r -> Optional.of(r.name());
            case RenameNode r when r.relationName().isPresent() -> r.relationName();
            default -> node.children().size() == 1
                    ? displayName(node.children().getFirst())
                    : Optional.empty();
        };
    }
}
